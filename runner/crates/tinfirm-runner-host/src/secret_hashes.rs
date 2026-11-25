//! Redaction without the values: salted hashes of a run's git tokens in its take-back record
//! (ADR-0116; threat model 6.20 and Q48 h).
//!
//! The record holds a run's live tokens, which the run needs after a restart. A token that the
//! server revoked, and a GitHub App's token that a renewal replaced, are no longer in it, so a
//! host that restarts could not redact them, nor find them in a commit before the push, if it
//! knew them only from the record. Keeping them in plain text would keep a usable token on
//! disk. So the record keeps, for every token the run has held, and for each form in which the
//! redactor matches it ([`crate::redact::variants`]: the raw value, its URL encodings and its
//! base64 at each byte alignment), only:
//!
//! - the form's length;
//! - a short tag of a rolling hash of the form ([`TAG_BITS`] bits), the pre-filter;
//! - SHA-256 over the record's random salt and the form, the proof.
//!
//! A scan slides a window of each stored length over the bytes, keeps the rolling hash of the
//! window, and only where the tag matches hashes the window with the salt and compares. What a
//! reader of the record learns about a token is said in the README ("Redaction after a
//! restart"): at most [`TAG_BITS`] bits per stored form from the tags, and a salted SHA-256,
//! which a token of high entropy keeps out of reach of a search.

use std::collections::{BTreeMap, HashMap};

use anyhow::{Context, Result, bail};
use ring::digest::{SHA256, digest};
use ring::rand::SecureRandom;
use serde::{Deserialize, Serialize};

/// The bits of the rolling hash a form keeps: the pre-filter, and all that the tag gives away.
pub const TAG_BITS: u32 = 12;
/// The longest form of a token: its percent-encoding, at most three bytes per byte of the
/// longest git token the host takes ([`crate::host::MAX_GIT_TOKEN_BYTES`]); its base64 is
/// shorter.
pub const MAX_FORM_LEN: usize = 3 * crate::host::MAX_GIT_TOKEN_BYTES;
/// The most forms a record keeps: far above a run of the most repositories
/// ([`tinfirm_protocol::MAX_WORKSPACES`], 20) whose tokens are renewed every 50 minutes for the
/// longest run of a day (30 tokens each), at 9 forms a token: 20 × 31 × 9 = 5580.
pub const MAX_FORMS: usize = 16 * 1024;
/// The bytes of the salt.
pub const SALT_BYTES: usize = 32;

/// The hashes of every token a run has held, as its take-back record keeps them.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SecretHashes {
    /// 32 random bytes, hex: one per record.
    pub salt: String,
    pub forms: Vec<HashedForm>,
}

/// One form of one token.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HashedForm {
    /// In bytes.
    pub len: u32,
    /// The top [`TAG_BITS`] bits of the form's rolling hash.
    pub tag: u16,
    /// SHA-256 over the salt's bytes and the form, hex.
    pub sha256: String,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(text: &str) -> Option<Vec<u8>> {
    if !text.len().is_multiple_of(2) {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(text.get(i..i + 2)?, 16).ok())
        .collect()
}

/// The tag of a rolling hash: its top [`TAG_BITS`] bits, which depend on every byte.
fn tag_of(h: u64) -> u16 {
    (h >> (64 - TAG_BITS)) as u16
}

/// The rolling hash's base, from the salt: odd, so that it is invertible modulo 2^64. A reader
/// of the record can compute it; an agent, which never sees the record, cannot, so it cannot
/// make windows whose tags collide on purpose.
fn base_of(salt: &[u8]) -> u64 {
    let mut input = b"tinfirm-redaction-base:".to_vec();
    input.extend_from_slice(salt);
    let d = digest(&SHA256, &input);
    let mut word = [0u8; 8];
    word.copy_from_slice(&d.as_ref()[..8]);
    u64::from_le_bytes(word) | 1
}

/// The polynomial hash of `bytes` modulo 2^64.
fn rolling(bytes: &[u8], base: u64) -> u64 {
    bytes.iter().fold(0u64, |h, b| {
        h.wrapping_mul(base).wrapping_add(u64::from(*b))
    })
}

fn salted(salt: &[u8], form: &[u8]) -> [u8; 32] {
    let mut context = ring::digest::Context::new(&SHA256);
    context.update(salt);
    context.update(form);
    let mut out = [0u8; 32];
    out.copy_from_slice(context.finish().as_ref());
    out
}

impl SecretHashes {
    /// No token yet, with a new random salt.
    pub fn new() -> SecretHashes {
        let mut salt = [0u8; 32];
        ring::rand::SystemRandom::new()
            .fill(&mut salt)
            .expect("the system's random source works");
        SecretHashes {
            salt: hex(&salt),
            forms: Vec::new(),
        }
    }

    fn salt_bytes(&self) -> Option<Vec<u8>> {
        unhex(&self.salt).filter(|salt| salt.len() == SALT_BYTES)
    }

    /// Adds every form of `secret` that the redactor would match and that is not here yet.
    pub fn add(&mut self, secret: &str) {
        let Some(salt) = self.salt_bytes() else {
            return;
        };
        let base = base_of(&salt);
        if secret.len() < crate::redact::MIN_SECRET_LEN
            || secret.len() > crate::host::MAX_GIT_TOKEN_BYTES
        {
            return;
        }
        for form in crate::redact::variants(secret) {
            if form.len() < crate::redact::MIN_SECRET_LEN || form.len() > MAX_FORM_LEN {
                continue;
            }
            if self.forms.len() >= MAX_FORMS {
                tracing::warn!(
                    "a run has held more git tokens than its record keeps hashes of; the latest are not kept"
                );
                return;
            }
            let sha256 = hex(&salted(&salt, form.as_bytes()));
            if self.forms.iter().any(|f| f.sha256 == sha256) {
                continue;
            }
            self.forms.push(HashedForm {
                len: u32::try_from(form.len()).unwrap_or(u32::MAX),
                tag: tag_of(rolling(form.as_bytes(), base)),
                sha256,
            });
        }
    }

    pub fn is_empty(&self) -> bool {
        self.forms.is_empty()
    }
}

impl Default for SecretHashes {
    fn default() -> SecretHashes {
        SecretHashes::new()
    }
}

/// The forms of one length: `base^(len - 1)`, a bit per tag that some form has (the cheap
/// test of each window), and the proofs by tag.
struct OfLength {
    top: u64,
    tags: Box<[u64; (1 << TAG_BITS) / 64]>,
    proofs: HashMap<u16, Vec<[u8; 32]>>,
}

impl std::fmt::Debug for OfLength {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("OfLength").finish_non_exhaustive()
    }
}

/// The scanner of a [`SecretHashes`].
#[derive(Debug)]
pub struct HashedMatcher {
    salt: Vec<u8>,
    base: u64,
    /// By length: `base^(len - 1)`, and the proofs by tag.
    by_len: BTreeMap<usize, OfLength>,
    max_len: usize,
}

impl HashedMatcher {
    /// The scanner of `hashes`, which a record held, so read as untrusted: a salt of
    /// [`SALT_BYTES`] in hex, at most [`MAX_FORMS`] forms, each of
    /// [`crate::redact::MIN_SECRET_LEN`] to [`MAX_FORM_LEN`] bytes with a tag of [`TAG_BITS`]
    /// bits and a SHA-256 in 64 lowercase hex digits. Anything else is an error, and nothing
    /// is computed before the bounds are checked.
    pub fn new(hashes: &SecretHashes) -> Result<HashedMatcher> {
        let salt = hashes
            .salt_bytes()
            .with_context(|| format!("the salt is not {SALT_BYTES} bytes in hex"))?;
        if hashes.forms.len() > MAX_FORMS {
            bail!(
                "{} forms, more than the {MAX_FORMS} a record keeps",
                hashes.forms.len()
            );
        }
        for form in &hashes.forms {
            let len = form.len as usize;
            if !(crate::redact::MIN_SECRET_LEN..=MAX_FORM_LEN).contains(&len) {
                bail!(
                    "a form of {len} bytes, outside {} to {MAX_FORM_LEN}",
                    crate::redact::MIN_SECRET_LEN
                );
            }
            if u32::from(form.tag) >= 1 << TAG_BITS {
                bail!("a tag of more than {TAG_BITS} bits");
            }
            if form.sha256.len() != 64
                || !form
                    .sha256
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            {
                bail!("a hash is not SHA-256 in 64 lowercase hex digits");
            }
        }
        let base = base_of(&salt);
        let mut by_len: BTreeMap<usize, OfLength> = BTreeMap::new();
        for form in &hashes.forms {
            let len = form.len as usize;
            let proof: [u8; 32] = unhex(&form.sha256)
                .and_then(|bytes| bytes.try_into().ok())
                .context("a hash is not SHA-256 in hex")?;
            let entry = by_len.entry(len).or_insert_with(|| OfLength {
                top: (1..len).fold(1u64, |p, _| p.wrapping_mul(base)),
                tags: Box::new([0; (1 << TAG_BITS) / 64]),
                proofs: HashMap::new(),
            });
            let tag = form.tag;
            entry.tags[usize::from(tag) / 64] |= 1 << (tag % 64);
            entry.proofs.entry(tag).or_default().push(proof);
        }
        let max_len = by_len.keys().next_back().copied().unwrap_or(0);
        Ok(HashedMatcher {
            salt,
            base,
            by_len,
            max_len,
        })
    }

    /// The longest form, in bytes.
    pub fn max_len(&self) -> usize {
        self.max_len
    }

    /// Calls `found` with each window of `bytes` that is a stored form, by length, in order
    /// within a length; stops when it returns `false`.
    fn scan(&self, bytes: &[u8], mut found: impl FnMut(usize, usize) -> bool) {
        for (&len, of) in &self.by_len {
            if bytes.len() < len {
                continue;
            }
            let mut h = rolling(&bytes[..len], self.base);
            let mut start = 0;
            loop {
                let tag = usize::from(tag_of(h));
                if of.tags[tag / 64] & (1 << (tag % 64)) != 0
                    && let Some(proofs) = of.proofs.get(&(tag as u16))
                {
                    let window = &bytes[start..start + len];
                    let proof = salted(&self.salt, window);
                    if proofs.contains(&proof) && !found(start, start + len) {
                        return;
                    }
                }
                let next = start + len;
                if next >= bytes.len() {
                    break;
                }
                let out = u64::from(bytes[start]).wrapping_mul(of.top);
                h = h
                    .wrapping_sub(out)
                    .wrapping_mul(self.base)
                    .wrapping_add(u64::from(bytes[next]));
                start += 1;
            }
        }
    }

    /// Whether `bytes` hold a stored form.
    pub fn contains(&self, bytes: &[u8]) -> bool {
        let mut hit = false;
        self.scan(bytes, |_, _| {
            hit = true;
            false
        });
        hit
    }

    /// Every stored form in `bytes`, as byte ranges in order, overlaps merged.
    pub fn ranges(&self, bytes: &[u8]) -> Vec<(usize, usize)> {
        let mut ranges = Vec::new();
        self.scan(bytes, |start, end| {
            ranges.push((start, end));
            true
        });
        merge(ranges)
    }
}

/// Sorts `ranges` and merges those that overlap or touch.
pub fn merge(mut ranges: Vec<(usize, usize)>) -> Vec<(usize, usize)> {
    ranges.sort_unstable();
    let mut merged: Vec<(usize, usize)> = Vec::with_capacity(ranges.len());
    for (start, end) in ranges {
        match merged.last_mut() {
            Some(last) if start <= last.1 => last.1 = last.1.max(end),
            _ => merged.push((start, end)),
        }
    }
    merged
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;

    // Underscores keep these made-up tokens out of the shape that secret scanners look for.
    const TOKEN: &str = "ghs_TEST_ONLY_abcdefghijklmnopqrstuvwxyz0123";

    #[test]
    fn a_token_is_found_in_every_form_the_redactor_matches_and_never_stored_in_any() {
        let mut hashes = SecretHashes::new();
        hashes.add(TOKEN);
        let header = crate::git_tokens::basic_authorization(
            "x-access-token",
            &tinfirm_protocol::Secret::new(TOKEN),
        );
        let before = hashes.forms.len();
        hashes.add(TOKEN);
        assert_eq!(hashes.forms.len(), before, "a form is kept once");
        let stored = serde_json::to_string(&hashes).unwrap();
        for form in crate::redact::variants(TOKEN) {
            if form.len() >= 8 {
                assert!(!stored.contains(&form), "{form} is in {stored}");
            }
        }
        let matcher = HashedMatcher::new(&hashes).unwrap();
        // Raw, in a sentence; percent-encoded; and in base64 at each offset in both alphabets.
        let raw = format!("the token is {TOKEN}, see?");
        assert_eq!(matcher.ranges(raw.as_bytes()), vec![(13, 13 + TOKEN.len())]);
        let encoded =
            percent_encoding::utf8_percent_encode(TOKEN, percent_encoding::NON_ALPHANUMERIC)
                .to_string();
        assert!(matcher.contains(encoded.as_bytes()));
        for lead in ["", "x", "xy", "x-access-token:"] {
            for engine in [
                &base64::engine::general_purpose::STANDARD,
                &base64::engine::general_purpose::URL_SAFE,
            ] {
                let blob = engine.encode(format!("{lead}{TOKEN} and more"));
                assert!(matcher.contains(blob.as_bytes()), "{lead:?} {blob}");
            }
        }
        // The `Basic` header holds the token's base64: found by the token's own forms.
        assert!(matcher.contains(header.expose().as_bytes()));
        println!("forms of one token: {}", hashes.forms.len());
        // Another token, or one character off, is not found.
        assert!(!matcher.contains(b"ghs_TEST_ONLY_abcdefghijklmnopqrstuvwxyz0124"));
        assert!(!matcher.contains(b""));
        // A record read back keeps the salt and finds the same.
        let read: SecretHashes = serde_json::from_str(&stored).unwrap();
        assert!(HashedMatcher::new(&read).unwrap().contains(raw.as_bytes()));
        // Another salt, another record: its hashes find nothing of this one's.
        let mut other = SecretHashes::new();
        other.add("ghs_TEST_ONLY_another_records_token_0123");
        assert_ne!(other.salt, hashes.salt);
        assert!(!HashedMatcher::new(&other).unwrap().contains(raw.as_bytes()));
    }

    #[test]
    fn overlapping_finds_merge() {
        assert_eq!(
            merge(vec![(5, 9), (0, 3), (2, 4), (9, 12)]),
            vec![(0, 4), (5, 12)]
        );
    }

    /// The cost of the hashes (README, "Redaction after a restart"): a scan of 200 MiB of
    /// pack-like bytes for ten tokens and their `Basic` headers, by value and by hashes.
    /// `cargo test --release -p tinfirm-runner-host --lib secret_hashes -- --ignored --nocapture`
    #[test]
    #[ignore = "a measurement, run by hand in release"]
    fn measure_a_scan_of_a_large_pack() {
        let mut state: u64 = 0x9e37_79b9_7f4a_7c15;
        let mut pack = vec![0u8; 200 << 20];
        for chunk in pack.chunks_mut(8) {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            let bytes = state.to_le_bytes();
            chunk.copy_from_slice(&bytes[..chunk.len()]);
        }
        let tokens: Vec<String> = (0..10)
            .map(|i| format!("ghs_TESTONLY{i:02}abcdefghijklmnopqrstuvwxyz0"))
            .collect();
        let mut values = Vec::new();
        let mut hashes = SecretHashes::new();
        for token in &tokens {
            let header = crate::git_tokens::basic_authorization(
                "x-access-token",
                &tinfirm_protocol::Secret::new(token.as_str()),
            );
            // As the holder keeps them: the token's forms alone, which its header holds.
            hashes.add(token);
            values.push(token.clone());
            values.push(header.expose().to_string());
        }
        let lengths: std::collections::BTreeSet<u32> = hashes.forms.iter().map(|f| f.len).collect();
        let plain = crate::redact::Redactor::new(&values);
        let hashed = crate::redact::Redactor::new(std::iter::empty::<&str>()).with_hashed(vec![
            std::sync::Arc::new(HashedMatcher::new(&hashes).unwrap()),
        ]);
        for (name, redactor) in [("by value", &plain), ("by hashes", &hashed)] {
            let started = std::time::Instant::now();
            assert!(!redactor.contains_any(&pack));
            let took = started.elapsed();
            println!(
                "{name}: {} forms, {} lengths: {:.2} s for 200 MiB ({:.0} MiB/s)",
                hashes.forms.len(),
                lengths.len(),
                took.as_secs_f64(),
                200.0 / took.as_secs_f64()
            );
        }
        // With a token in it: found by both.
        pack[100 << 20..(100 << 20) + tokens[3].len()].copy_from_slice(tokens[3].as_bytes());
        assert!(plain.contains_any(&pack) && hashed.contains_any(&pack));
    }

    /// Bytes like a pack's: incompressible, from a seeded xorshift.
    fn pack_like(bytes: usize) -> Vec<u8> {
        let mut state: u64 = 0x9e37_79b9_7f4a_7c15;
        let mut pack = vec![0u8; bytes];
        for chunk in pack.chunks_mut(8) {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            chunk.copy_from_slice(&state.to_le_bytes()[..chunk.len()]);
        }
        pack
    }

    /// The cost of ten runs taken back (B3.1): one 200 MiB scan with every run's hashes, as the
    /// host-wide redactor holds them, and with one run's alone, as a run's bulk scans now use.
    /// `cargo test --release -p tinfirm-runner-host --lib secret_hashes -- --ignored --nocapture`
    #[test]
    #[ignore = "a measurement, run by hand in release"]
    fn measure_a_scan_with_ten_runs_taken_back() {
        let pack = pack_like(200 << 20);
        let matchers: Vec<std::sync::Arc<HashedMatcher>> = (0..10)
            .map(|run| {
                // Each run: a revoked token and a GitHub App's earlier one.
                let mut hashes = SecretHashes::new();
                for which in ["revoked", "earlier"] {
                    hashes.add(&format!(
                        "{}_TEST_ONLY_{which}_{run:02}_abcdefghijklmn",
                        "ghs"
                    ));
                }
                std::sync::Arc::new(HashedMatcher::new(&hashes).unwrap())
            })
            .collect();
        let host_wide =
            crate::redact::Redactor::new(std::iter::empty::<&str>()).with_hashed(matchers.clone());
        let one_run = host_wide.with_only_hashed(Some(matchers[0].clone()));
        for (name, redactor) in [
            ("ten runs' hashes", &host_wide),
            ("one run's hashes", &one_run),
        ] {
            let started = std::time::Instant::now();
            assert!(!redactor.contains_any(&pack));
            let took = started.elapsed();
            println!(
                "{name}: {:.2} s for 200 MiB ({:.0} MiB/s)",
                took.as_secs_f64(),
                200.0 / took.as_secs_f64()
            );
        }
    }

    #[test]
    fn a_records_hashes_out_of_bounds_are_refused_before_any_work() {
        let mut good = SecretHashes::new();
        good.add("ghs_TEST_ONLY_bounded_token_0123");
        assert!(HashedMatcher::new(&good).is_ok());
        let refused = |change: &dyn Fn(&mut SecretHashes)| {
            let mut hashes = good.clone();
            change(&mut hashes);
            let started = std::time::Instant::now();
            let error = HashedMatcher::new(&hashes).unwrap_err().to_string();
            assert!(
                started.elapsed() < std::time::Duration::from_secs(1),
                "{error}"
            );
            error
        };
        // The salt: 32 bytes in hex, no more, no less, no other characters.
        assert!(refused(&|h| h.salt = "ab".repeat(31)).contains("salt"));
        assert!(refused(&|h| h.salt = "ab".repeat(33)).contains("salt"));
        assert!(refused(&|h| h.salt = "zz".repeat(32)).contains("salt"));
        // A form's length: from the shortest secret to the longest form of the longest token.
        for len in [0, 7, MAX_FORM_LEN as u32 + 1, u32::MAX] {
            let error = refused(&|h| h.forms[0].len = len);
            assert!(error.contains("a form of"), "{len}: {error}");
        }
        // The tag: 12 bits.
        assert!(refused(&|h| h.forms[0].tag = 1 << TAG_BITS).contains("tag"));
        // The hash: 64 lowercase hex digits.
        assert!(
            refused(&|h| h.forms[0].sha256 = h.forms[0].sha256.to_uppercase()).contains("SHA-256")
        );
        assert!(refused(&|h| h.forms[0].sha256.truncate(62)).contains("SHA-256"));
        // The count of forms.
        let error = refused(&|h| {
            let form = h.forms[0].clone();
            h.forms = vec![form; MAX_FORMS + 1];
        });
        assert!(error.contains("forms"), "{error}");
        // A record keeps no form of a token longer than the host takes.
        let mut long = SecretHashes::new();
        long.add(&"x".repeat(crate::host::MAX_GIT_TOKEN_BYTES + 1));
        assert!(long.is_empty());
    }
}
