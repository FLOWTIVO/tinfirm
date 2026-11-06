//! Credential redaction (docs/archive/v1/CONVENTIONS.md §15.4 step 10).
//!
//! Every credential value is matched raw, base64-encoded (standard and URL-safe alphabet,
//! at all three byte alignments, so a value embedded in a larger base64 blob such as
//! `Authorization: Basic …` is found too) and URL-encoded. Matches are replaced with
//! `[REDACTED]`.
//!
//! Masked values (the git proxy's run secrets) are replaced the same way, but they are not
//! credentials: `git fetch` in the sandbox prints the proxy URL, so finding one is no leak.
//!
//! One kind of credential is matched by its shape, not its value: the tokens of a Claude
//! login (`sk-ant-oat01-…`, `sk-ant-ort01-…`), which Claude Code keeps in the
//! `.credentials.json` the host mounts into the sandbox and never reads (ADR-0085). The
//! sandbox can read that file, so the shape is a credential hit wherever it appears raw.

use std::borrow::Cow;
use std::collections::BTreeSet;

use aho_corasick::{AhoCorasick, AhoCorasickBuilder, MatchKind};
use base64::Engine;
use base64::engine::general_purpose::{STANDARD_NO_PAD, URL_SAFE_NO_PAD};
use percent_encoding::{AsciiSet, CONTROLS, NON_ALPHANUMERIC, utf8_percent_encode};
use serde_json::Value;

pub const REDACTED: &str = "[REDACTED]";

/// Values shorter than this are not treated as credentials (too many false positives).
pub const MIN_SECRET_LEN: usize = 8;

/// Characters escaped by typical URL/query encoders (`encodeURIComponent`-like).
const COMPONENT: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'"')
    .add(b'#')
    .add(b'$')
    .add(b'%')
    .add(b'&')
    .add(b'+')
    .add(b',')
    .add(b'/')
    .add(b':')
    .add(b';')
    .add(b'<')
    .add(b'=')
    .add(b'>')
    .add(b'?')
    .add(b'@')
    .add(b'[')
    .add(b'\\')
    .add(b']')
    .add(b'^')
    .add(b'`')
    .add(b'{')
    .add(b'|')
    .add(b'}');

#[derive(Clone, Default)]
pub struct Redactor {
    credentials: Patterns,
    masked: Patterns,
}

/// The start of a Claude login's tokens: the access token and the refresh token, as Claude Code
/// writes them.
const CLAUDE_TOKEN_PREFIXES: [&str; 2] = ["sk-ant-oat01-", "sk-ant-ort01-"];
/// The least bytes of a token after its prefix for a shape (a real token has about 80).
const CLAUDE_TOKEN_MIN_TAIL: usize = 20;
/// The longest a Claude login's token is taken to be, base64 included, for
/// [`Redactor::hold_back`] (a real one has about 110 characters raw, 150 as base64).
const CLAUDE_TOKEN_MAX_LEN: usize = 256;

/// The shapes of a Claude login's tokens: each prefix raw, and as base64 at every byte
/// alignment and in both alphabets ([`variants`]), each followed by a run of the alphabet it
/// is written in. The host never knows the tokens by value (ADR-0085), so it matches these.
struct Shapes {
    matcher: AhoCorasick,
    /// For each pattern: `true` when it is base64, `false` when it is the raw prefix.
    base64: Vec<bool>,
}

fn claude_token_shapes() -> &'static Shapes {
    static SHAPES: std::sync::OnceLock<Shapes> = std::sync::OnceLock::new();
    SHAPES.get_or_init(|| {
        let mut patterns = BTreeSet::new();
        for prefix in CLAUDE_TOKEN_PREFIXES {
            patterns.extend(variants(prefix));
        }
        let patterns: Vec<String> = patterns.into_iter().collect();
        let base64 = patterns
            .iter()
            .map(|pattern| !pattern.starts_with("sk-ant-"))
            .collect();
        let matcher = AhoCorasickBuilder::new()
            .match_kind(MatchKind::LeftmostLongest)
            .build(&patterns)
            .expect("token shapes compile");
        Shapes { matcher, base64 }
    })
}

fn is_raw_token_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'-' || b == b'_'
}

fn is_base64_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || matches!(b, b'+' | b'/' | b'=' | b'-' | b'_')
}

/// The byte ranges of every Claude token shape in `bytes`, in order, without overlaps.
fn claude_token_ranges(bytes: &[u8]) -> Vec<(usize, usize)> {
    let shapes = claude_token_shapes();
    let mut ranges: Vec<(usize, usize)> = Vec::new();
    for found in shapes.matcher.find_iter(bytes) {
        if ranges.last().is_some_and(|(_, end)| found.start() < *end) {
            continue;
        }
        let base64 = shapes.base64[found.pattern().as_usize()];
        let (is_byte, min_tail): (fn(u8) -> bool, usize) = if base64 {
            (is_base64_byte, CLAUDE_TOKEN_MIN_TAIL.div_ceil(3) * 4)
        } else {
            (is_raw_token_byte, CLAUDE_TOKEN_MIN_TAIL)
        };
        let mut end = found.end();
        while end < bytes.len() && is_byte(bytes[end]) {
            end += 1;
        }
        if end - found.end() >= min_tail {
            ranges.push((found.start(), end));
        }
    }
    ranges
}

/// Replaces every Claude token shape in `text`; says whether one was found.
fn replace_claude_tokens(text: Cow<'_, str>) -> (Cow<'_, str>, bool) {
    let ranges = claude_token_ranges(text.as_bytes());
    if ranges.is_empty() {
        return (text, false);
    }
    let mut out = String::with_capacity(text.len());
    let mut copied = 0;
    for (start, end) in ranges {
        // The shapes are ASCII, so the ranges fall on character boundaries.
        out.push_str(&text[copied..start]);
        out.push_str(REDACTED);
        copied = end;
    }
    out.push_str(&text[copied..]);
    (Cow::Owned(out), true)
}

/// One set of values, in all their encodings.
#[derive(Clone, Default)]
struct Patterns {
    matcher: Option<AhoCorasick>,
    count: usize,
    /// The longest pattern, in bytes.
    max_len: usize,
}

/// What a redaction replaced.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Redacted {
    /// A credential value: a leak when the text came from the agent.
    pub credential: bool,
    /// A masked value, which is not a credential.
    pub masked: bool,
}

impl Redacted {
    pub fn any(self) -> bool {
        self.credential || self.masked
    }
}

impl std::ops::BitOrAssign for Redacted {
    fn bitor_assign(&mut self, other: Redacted) {
        self.credential |= other.credential;
        self.masked |= other.masked;
    }
}

impl std::fmt::Debug for Redactor {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Redactor")
            .field("patterns", &self.credentials.count)
            .field("masked", &self.masked.count)
            .finish()
    }
}

impl Patterns {
    fn new<I, S>(values: I) -> Patterns
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        let mut patterns = BTreeSet::new();
        for value in values {
            let value = value.as_ref();
            if value.len() < MIN_SECRET_LEN {
                continue;
            }
            for variant in variants(value) {
                if variant.len() >= MIN_SECRET_LEN {
                    patterns.insert(variant);
                }
            }
        }
        if patterns.is_empty() {
            return Patterns::default();
        }
        let patterns: Vec<String> = patterns.into_iter().collect();
        let max_len = patterns.iter().map(String::len).max().unwrap_or(0);
        let matcher = AhoCorasickBuilder::new()
            .match_kind(MatchKind::LeftmostLongest)
            .build(&patterns)
            .expect("redaction patterns compile");
        Patterns {
            matcher: Some(matcher),
            count: patterns.len(),
            max_len,
        }
    }

    fn contains(&self, text: &str) -> bool {
        self.matcher.as_ref().is_some_and(|m| m.is_match(text))
    }

    fn max_len(&self) -> usize {
        self.max_len
    }

    fn replace<'a>(&self, text: Cow<'a, str>) -> (Cow<'a, str>, bool) {
        match &self.matcher {
            Some(m) if m.is_match(text.as_ref()) => {
                let replacements = vec![REDACTED; self.count];
                (
                    Cow::Owned(m.replace_all(text.as_ref(), &replacements)),
                    true,
                )
            }
            _ => (text, false),
        }
    }
}

impl Redactor {
    pub fn new<I, S>(secrets: I) -> Redactor
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        Redactor::with_masked(secrets, std::iter::empty::<&str>())
    }

    /// Credentials plus masked values: both are replaced, but only credentials count as
    /// hits for [`Redactor::contains`] and [`Redacted::credential`].
    pub fn with_masked<I, S, M, T>(secrets: I, masked: M) -> Redactor
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
        M: IntoIterator<Item = T>,
        T: AsRef<str>,
    {
        Redactor {
            credentials: Patterns::new(secrets),
            masked: Patterns::new(masked),
        }
    }

    /// How many bytes at the end of a text stream that goes out in pieces must wait for the
    /// next piece: one less than the longest value it matches, in any encoding. A text cut
    /// anywhere before its last `hold_back()` bytes, after it was redacted, never splits a
    /// value it would have matched whole (L26 of the security review of 2026-10-27).
    pub fn hold_back(&self) -> usize {
        self.credentials
            .max_len()
            .max(self.masked.max_len())
            .max(CLAUDE_TOKEN_MAX_LEN)
            - 1
    }

    /// Always `false`: the shapes of a Claude login's tokens are matched by every redactor,
    /// whether or not it knows credential values (ADR-0085), so a scan is never skipped.
    pub fn is_empty(&self) -> bool {
        false
    }

    /// `true` when `text` contains a credential value in any encoding, or a Claude OAuth
    /// token shape.
    pub fn contains(&self, text: &str) -> bool {
        self.credentials.contains(text) || !claude_token_ranges(text.as_bytes()).is_empty()
    }

    /// `true` when `bytes` (any content, not only text) contain a credential or a masked
    /// value in any encoding, or a Claude OAuth token shape: such content must not leave the
    /// host as it is.
    pub fn contains_any(&self, bytes: &[u8]) -> bool {
        [&self.credentials, &self.masked]
            .iter()
            .any(|p| p.matcher.as_ref().is_some_and(|m| m.is_match(bytes)))
            || !claude_token_ranges(bytes).is_empty()
    }

    /// `true` when `bytes` contain a credential value in any encoding; masked values (a run's
    /// URL secrets and tickets, which open nothing once the run ends) do not count.
    pub fn contains_credential(&self, bytes: &[u8]) -> bool {
        self.credentials
            .matcher
            .as_ref()
            .is_some_and(|m| m.is_match(bytes))
    }

    /// Replaces every credential and masked value, and every Claude OAuth token shape, with
    /// `[REDACTED]`.
    pub fn redact<'a>(&self, text: &'a str) -> Cow<'a, str> {
        let (text, _) = self.credentials.replace(Cow::Borrowed(text));
        let (text, _) = replace_claude_tokens(text);
        self.masked.replace(text).0
    }

    /// Redacts in place; says what was replaced.
    pub fn redact_string(&self, text: &mut String) -> Redacted {
        let (replaced, credential) = self.credentials.replace(Cow::Borrowed(text.as_str()));
        let (replaced, shaped) = replace_claude_tokens(replaced);
        let (replaced, masked) = self.masked.replace(replaced);
        if let Cow::Owned(replaced) = replaced {
            *text = replaced;
        }
        Redacted {
            credential: credential || shaped,
            masked,
        }
    }

    /// Redacts every string (and object key) in a JSON value; says what was replaced.
    pub fn redact_json(&self, value: &mut Value) -> Redacted {
        match value {
            Value::String(s) => self.redact_string(s),
            Value::Array(items) => {
                let mut hit = Redacted::default();
                for item in items {
                    hit |= self.redact_json(item);
                }
                hit
            }
            Value::Object(map) => {
                let mut hit = Redacted::default();
                let keys: Vec<String> = map.keys().cloned().collect();
                for key in keys {
                    let mut child = map.remove(&key).unwrap_or(Value::Null);
                    hit |= self.redact_json(&mut child);
                    let mut new_key = key;
                    hit |= self.redact_string(&mut new_key);
                    map.insert(new_key, child);
                }
                hit
            }
            _ => Redacted::default(),
        }
    }
}

/// All encodings of one secret that we look for.
fn variants(secret: &str) -> Vec<String> {
    let mut out = vec![secret.to_string()];
    out.push(utf8_percent_encode(secret, NON_ALPHANUMERIC).to_string());
    out.push(utf8_percent_encode(secret, COMPONENT).to_string());
    let bytes = secret.as_bytes();
    for engine in [&STANDARD_NO_PAD, &URL_SAFE_NO_PAD] {
        for shift in 0..3usize {
            // Encode `shift` filler bytes + the secret and keep only the characters that
            // depend on secret bytes alone: that substring appears in any base64 stream
            // containing the secret at a byte offset ≡ shift (mod 3).
            let mut buf = vec![0u8; shift];
            buf.extend_from_slice(bytes);
            let encoded = engine.encode(&buf);
            let start = (shift * 8).div_ceil(6);
            let end = ((shift + bytes.len()) * 8) / 6;
            if end > start && end <= encoded.len() {
                out.push(encoded[start..end].to_string());
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::engine::general_purpose::{STANDARD, URL_SAFE};

    const SECRET: &str = "sk-ant-oat01-TEST/ONLY+NOT=A-REAL_TOKEN";

    #[test]
    fn redacts_claude_token_shapes_it_was_never_told() {
        // A login's tokens are Claude Code's; the host matches their shape (ADR-0085).
        let redactor = Redactor::new(std::iter::empty::<&str>());
        let access = "sk-ant-oat01-TESTONLYabcdefghijklmnopqrstuvwxyz0123456789_-END";
        let refresh = "sk-ant-ort01-TESTONLYabcdefghijklmnopqrstuvwxyz0123456789_-END";
        let text = format!("accessToken={access}; refreshToken={refresh}.");
        assert!(redactor.contains(&text));
        assert!(redactor.contains_any(text.as_bytes()));
        assert_eq!(
            redactor.redact(&text),
            "accessToken=[REDACTED]; refreshToken=[REDACTED]."
        );
        let mut json = serde_json::json!({"claudeAiOauth": {"accessToken": access}});
        let hit = redactor.redact_json(&mut json);
        assert!(hit.credential && !hit.masked);
        assert_eq!(json["claudeAiOauth"]["accessToken"], "[REDACTED]");
        // In base64 too, at any alignment and in both alphabets: the encoded prefix is a
        // fixed string there, as `variants` computes it.
        for lead in ["", "x", "xy", "x-access-token:"] {
            for engine in [&STANDARD, &URL_SAFE] {
                let encoded = engine.encode(format!("{lead}{access}"));
                assert!(redactor.contains(&encoded), "{lead:?} {encoded}");
                let redacted = redactor.redact(&encoded);
                assert!(redacted.contains(REDACTED), "{lead:?} {redacted}");
                // What stays is at most the lead and one shared character.
                assert!(
                    redacted.len() <= REDACTED.len() + (lead.len() * 4).div_ceil(3) + 4,
                    "{lead:?} {redacted}"
                );
            }
        }
        // A short tail is a prefix in prose, not a token.
        let prose = "tokens start with sk-ant-oat01-x and sk-ant-ort01-";
        assert!(!redactor.contains(prose));
        assert_eq!(redactor.redact(prose), prose);
    }

    #[test]
    fn the_hold_back_covers_the_longest_encoding() {
        let redactor = Redactor::new(["x".repeat(300)]);
        // The URL-encoded form of an all-ASCII-letter value is the value; base64 is 4/3 of it.
        assert_eq!(redactor.hold_back(), 400 - 1);
        assert_eq!(Redactor::new(Vec::<String>::new()).hold_back(), 255);
    }

    #[test]
    fn redacts_raw_values() {
        let r = Redactor::new([SECRET]);
        let text = format!("token is {SECRET}!");
        assert!(r.contains(&text));
        assert_eq!(r.redact(&text), "token is [REDACTED]!");
        assert_eq!(r.redact("nothing here"), "nothing here");
    }

    #[test]
    fn finds_credentials_and_masked_values_in_any_content() {
        let r = Redactor::with_masked([SECRET], ["mcp-SECRET-of-the-run"]);
        let mut binary = vec![0u8, 159, 146, 150];
        binary.extend_from_slice(SECRET.as_bytes());
        assert!(r.contains_any(&binary));
        assert!(r.contains_any(STANDARD.encode(SECRET).as_bytes()));
        assert!(r.contains_any(b"http://proxy/mcp-SECRET-of-the-run/mcp"));
        assert!(!r.contains_any(&[0u8, 159, 146, 150]));
        assert!(!Redactor::default().contains_any(SECRET.as_bytes()));
    }

    #[test]
    fn redacts_url_encoded_values() {
        let r = Redactor::new([SECRET]);
        let encoded = utf8_percent_encode(SECRET, NON_ALPHANUMERIC).to_string();
        assert!(r.contains(&format!("https://x/?t={encoded}")));
        let component = utf8_percent_encode(SECRET, COMPONENT).to_string();
        assert!(!r.redact(&component).contains("TEST"));
    }

    #[test]
    fn redacts_base64_at_every_alignment() {
        let r = Redactor::new([SECRET]);
        for prefix in ["", "a", "ab", "abc", "x-access-token:"] {
            for engine in [&STANDARD, &URL_SAFE] {
                let blob = engine.encode(format!("{prefix}{SECRET}-suffix"));
                assert!(
                    r.contains(&blob),
                    "missed base64 with prefix {prefix:?}: {blob}"
                );
                let redacted = r.redact(&blob);
                assert!(redacted.contains(REDACTED));
            }
        }
    }

    #[test]
    fn short_values_are_ignored() {
        let r = Redactor::new(["abc", ""]);
        assert!(!r.contains("abc"));
        // No value is known, and the token shapes still are: the redactor is never empty.
        assert!(!r.is_empty());
    }

    #[test]
    fn redacts_json_recursively() {
        let r = Redactor::new([SECRET]);
        let mut value = serde_json::json!({
            "a": [format!("x{SECRET}y"), 1, null],
            SECRET: {"b": SECRET},
            "c": "clean"
        });
        assert!(r.redact_json(&mut value).credential);
        let text = value.to_string();
        assert!(!text.contains(SECRET));
        assert!(text.contains("clean"));
        let mut clean = serde_json::json!({"c": "clean"});
        assert!(!r.redact_json(&mut clean).any());
    }

    #[test]
    fn masked_values_are_replaced_but_are_no_credential_hit() {
        let masked = "Zm9vYmFyLXJ1bi1zZWNyZXQtb2YtdGhlLWdpdC1wcm94eQ";
        let r = Redactor::with_masked([SECRET], [masked]);
        let url = format!("From http://172.17.0.1:40123/{masked}/acme/widgets");
        assert!(!r.contains(&url), "a masked value is no leak");
        assert_eq!(
            r.redact(&url),
            "From http://172.17.0.1:40123/[REDACTED]/acme/widgets"
        );
        let mut value = serde_json::json!({"output": url});
        let hit = r.redact_json(&mut value);
        assert_eq!(
            hit,
            Redacted {
                credential: false,
                masked: true
            }
        );
        assert!(!value.to_string().contains(masked));
        let mut both = serde_json::json!([format!("{SECRET} {masked}")]);
        let hit = r.redact_json(&mut both);
        assert!(hit.credential && hit.masked);
        assert_eq!(both[0], "[REDACTED] [REDACTED]");
        // Only masked values: outgoing commits are still scanned, for the token shapes.
        assert!(!Redactor::with_masked(std::iter::empty::<&str>(), [masked]).is_empty());
    }
}
