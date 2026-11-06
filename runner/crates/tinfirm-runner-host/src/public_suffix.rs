//! The Public Suffix List (publicsuffix.org), which the server sends with `psl.update`
//! (protocol v2, ADR-0044). The host uses it to refuse network wildcards that would reach every
//! site of a shared service: `*.com.sg`, `*.github.io`.
//!
//! The runner embeds no list. Without one (the server never sent it) the checks are skipped and
//! nothing else changes. A list that arrives is checked (size, SHA-256, shape), swapped in and
//! kept in `<data dir>/public_suffix_list.dat`, which is read again when the host starts; its
//! SHA-256 goes into `hello.publicSuffixList`.

use std::collections::HashSet;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use ring::digest;
use tinfirm_protocol::{PSL_CONTENT_MAX_LEN, PSL_MAX_BYTES, PslUpdate};

use crate::egress_proxy::Rule;
use crate::paths::Paths;
use crate::secure_fs;

/// The file in the data directory that holds the list as the server sent it (uncompressed).
pub const FILE_NAME: &str = "public_suffix_list.dat";
/// Next to the list: when the server fetched it (RFC 3339), so that a restart does not forget which list is newer.
pub const FETCHED_FILE_NAME: &str = "public_suffix_list.dat.fetched";

/// A list with fewer rules is not the Public Suffix List (the real one has about 9000).
const MIN_RULES: usize = 1000;

/// The rules of a Public Suffix List: normal rules, the bases of `*.` wildcard rules and the
/// exceptions (`!`). Names are lower case ASCII without a trailing dot.
#[derive(Debug, Default, Clone)]
pub struct PublicSuffixList {
    exact: HashSet<String>,
    wildcards: HashSet<String>,
    exceptions: HashSet<String>,
    rule_count: usize,
}

impl PublicSuffixList {
    /// Parses the list's text: one rule per line, up to the first white space; `//` starts a
    /// comment line; blank lines are ignored. Rules are lower-cased. A line with a non-ASCII
    /// character (an IDN rule) or a `*` that is not the whole first label is skipped: the host
    /// checks ASCII names only.
    pub fn parse(bytes: &[u8]) -> Result<PublicSuffixList, String> {
        let text = std::str::from_utf8(bytes).map_err(|_| "the list is not UTF-8".to_string())?;
        let mut list = PublicSuffixList::default();
        for line in text.lines() {
            let line = line.trim_start();
            if line.is_empty() || line.starts_with("//") {
                continue;
            }
            let rule = line.split_whitespace().next().unwrap_or_default();
            if !rule.is_ascii() {
                continue;
            }
            let rule = rule.to_ascii_lowercase();
            let (target, rule) = match rule.strip_prefix('!') {
                Some(rest) => (Kind::Exception, rest),
                None => match rule.strip_prefix("*.") {
                    Some(rest) => (Kind::Wildcard, rest),
                    None => (Kind::Exact, rule.as_str()),
                },
            };
            let rule = rule.trim_end_matches('.');
            if rule.is_empty() || rule.contains('*') || rule.starts_with('.') || rule.contains("..")
            {
                continue;
            }
            let set = match target {
                Kind::Exact => &mut list.exact,
                Kind::Wildcard => &mut list.wildcards,
                Kind::Exception => &mut list.exceptions,
            };
            if set.insert(rule.to_string()) {
                list.rule_count += 1;
            }
        }
        Ok(list)
    }

    /// The number of distinct rules read.
    pub fn rule_count(&self) -> usize {
        self.rule_count
    }

    /// Whether it has the shape of the Public Suffix List: at least [`MIN_RULES`] rules, and
    /// `com` among them. A truncated or wrong file is refused before it replaces a good list.
    pub fn looks_like_psl(&self) -> Result<(), String> {
        if self.rule_count < MIN_RULES {
            return Err(format!(
                "it has {} rules; the Public Suffix List has more than {MIN_RULES}",
                self.rule_count
            ));
        }
        if !self.exact.contains("com") {
            return Err("it has no `com` rule".into());
        }
        Ok(())
    }

    /// Whether `domain` is a public suffix by the algorithm of publicsuffix.org: a name with
    /// one label is (the default rule `*`); an exception rule (`!www.ck`) is not; a rule that
    /// names it is; a `*.` rule over its parent (`*.ck` for `foo.ck`) is. Case and a trailing
    /// dot do not matter; an empty name is none.
    pub fn is_public_suffix(&self, domain: &str) -> bool {
        let domain = domain.trim_end_matches('.').to_ascii_lowercase();
        if domain.is_empty() {
            return false;
        }
        if self.exceptions.contains(&domain) {
            return false;
        }
        if self.exact.contains(&domain) {
            return true;
        }
        match domain.split_once('.') {
            None => true,
            Some((_, parent)) => self.wildcards.contains(parent),
        }
    }

    /// Whether `*.base` would reach every site of a shared service: `base` is a public suffix,
    /// or every name directly below it is one (a `*.base` rule, as `*.compute.amazonaws.com`).
    pub fn wildcard_is_too_broad(&self, base: &str) -> bool {
        let base = base.trim_end_matches('.').to_ascii_lowercase();
        self.is_public_suffix(&base) || self.wildcards.contains(&base)
    }
}

#[derive(Clone, Copy)]
enum Kind {
    Exact,
    Wildcard,
    Exception,
}

/// A list the host holds, with the SHA-256 of its bytes.
#[derive(Debug)]
struct Held {
    list: PublicSuffixList,
    sha256: String,
    /// When the server fetched the list the host received (`fetchedAt`); `None` for a list read
    /// from the data dir. An update older than this is not applied: updates are applied on
    /// blocking tasks, which do not keep the order in which the frames arrived.
    announced_at: Option<chrono::DateTime<chrono::Utc>>,
}

/// The list the host holds now, shared by the connection (which receives updates), the runs and
/// the platform tools. Cheap to read: a read lock and an `Arc`.
#[derive(Debug)]
pub struct PublicSuffixStore {
    /// `<data dir>/public_suffix_list.dat`; `None` keeps nothing on disk (tests).
    path: Option<PathBuf>,
    held: RwLock<Option<Arc<Held>>>,
    /// Applies updates one at a time, in the order they arrive.
    apply: Mutex<()>,
}

impl PublicSuffixStore {
    /// A store without a list that keeps nothing on disk.
    pub fn in_memory() -> PublicSuffixStore {
        PublicSuffixStore {
            path: None,
            held: RwLock::new(None),
            apply: Mutex::new(()),
        }
    }

    /// The store of a host: reads `<data dir>/public_suffix_list.dat` when it is there and is a
    /// valid list; one that is not is ignored with a warning, as if the host held none.
    pub fn load(paths: &Paths) -> PublicSuffixStore {
        let path = paths.data_dir.join(FILE_NAME);
        let held = match secure_fs::read_private_file(&path) {
            Ok(bytes) => match Self::check(&bytes) {
                Ok(mut held) => {
                    held.announced_at = read_fetched_at(&path);
                    tracing::info!(
                        rules = held.list.rule_count(),
                        sha256 = %held.sha256,
                        fetched_at = ?held.announced_at,
                        "loaded the Public Suffix List"
                    );
                    Some(Arc::new(held))
                }
                Err(why) => {
                    tracing::warn!(path = %path.display(), %why, "ignoring the stored Public Suffix List");
                    None
                }
            },
            Err(_) if !path.exists() => None,
            Err(e) => {
                tracing::warn!(path = %path.display(), error = %format!("{e:#}"), "cannot read the stored Public Suffix List");
                None
            }
        };
        PublicSuffixStore {
            path: Some(path),
            held: RwLock::new(held),
            apply: Mutex::new(()),
        }
    }

    fn check(bytes: &[u8]) -> Result<Held, String> {
        if bytes.len() > PSL_MAX_BYTES {
            return Err(format!("it is larger than {PSL_MAX_BYTES} bytes"));
        }
        let list = PublicSuffixList::parse(bytes)?;
        list.looks_like_psl()?;
        Ok(Held {
            list,
            sha256: hex(digest::digest(&digest::SHA256, bytes).as_ref()),
            announced_at: None,
        })
    }

    fn current(&self) -> Option<Arc<Held>> {
        self.held.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// The SHA-256 (lower case hex) of the list the host holds, for `hello`.
    pub fn sha256(&self) -> Option<String> {
        self.current().map(|held| held.sha256.clone())
    }

    /// Whether the host holds a list.
    pub fn is_held(&self) -> bool {
        self.current().is_some()
    }

    /// `Some(whether `domain` is a public suffix)`, or `None` without a list.
    pub fn is_public_suffix(&self, domain: &str) -> Option<bool> {
        self.current()
            .map(|held| held.list.is_public_suffix(domain))
    }

    /// The name `base` of a wildcard `*.base` that would reach every site of a shared service
    /// (see [`PublicSuffixList::wildcard_is_too_broad`]); `None` for a rule that is no wildcard,
    /// one over a tenant, or when the host holds no list.
    pub fn broad_wildcard_base<'a>(&self, rule: &'a Rule) -> Option<&'a str> {
        let base = rule.wildcard_base()?;
        let held = self.current()?;
        held.list.wildcard_is_too_broad(base).then_some(base)
    }

    /// Applies a `psl.update`: decodes, decompresses (at most [`PSL_MAX_BYTES`]), checks the
    /// SHA-256 and the shape, swaps the list in and keeps it on disk. On `Err` (the reason, for
    /// the log) the list the host held stays. A failure to write the file is only logged: the
    /// list is in use all the same.
    pub fn apply_update(&self, update: &PslUpdate) -> Result<(), String> {
        let _serial = self.apply.lock().unwrap_or_else(|e| e.into_inner());
        let bytes = decode(update)?;
        let mut held = Self::check(&bytes)?;
        if held.sha256 != update.sha256 {
            return Err("the SHA-256 of the list differs from the one the frame names".into());
        }
        if let Some(current) = self.current()
            && let Some(current_at) = current.announced_at
            && update.fetched_at < current_at
        {
            tracing::debug!(
                fetched_at = %update.fetched_at,
                held_fetched_at = %current_at,
                "ignoring a psl.update older than the Public Suffix List the host holds"
            );
            return Ok(());
        }
        held.announced_at = Some(update.fetched_at);
        if self.sha256().as_deref() == Some(held.sha256.as_str()) {
            tracing::debug!(sha256 = %held.sha256, "the Public Suffix List is the one held");
            return Ok(());
        }
        tracing::info!(
            rules = held.list.rule_count(),
            announced_rules = update.rule_count,
            fetched_at = %update.fetched_at,
            sha256 = %held.sha256,
            "using a new Public Suffix List"
        );
        *self.held.write().unwrap_or_else(|e| e.into_inner()) = Some(Arc::new(held));
        if let Some(path) = &self.path
            && let Err(e) = persist(path, &bytes, update.fetched_at)
        {
            tracing::warn!(path = %path.display(), error = %format!("{e:#}"), "cannot store the Public Suffix List; it stays in use until the host stops");
        }
        Ok(())
    }
}

fn fetched_path(path: &Path) -> PathBuf {
    path.with_file_name(FETCHED_FILE_NAME)
}

fn persist(
    path: &Path,
    bytes: &[u8],
    fetched_at: chrono::DateTime<chrono::Utc>,
) -> anyhow::Result<()> {
    // Written to a temporary file and moved into place, mode 0600. The time comes last: after
    // a crash in between, the list is newer than its time says, and nothing is lost by that.
    secure_fs::write_private_file(path, bytes)?;
    secure_fs::write_private_file(&fetched_path(path), fetched_at.to_rfc3339().as_bytes())
}

/// The time stored next to the list, when there is one and it reads.
fn read_fetched_at(path: &Path) -> Option<chrono::DateTime<chrono::Utc>> {
    let text = secure_fs::read_private_file(&fetched_path(path)).ok()?;
    chrono::DateTime::parse_from_rfc3339(String::from_utf8_lossy(&text).trim())
        .ok()
        .map(|t| t.with_timezone(&chrono::Utc))
}

/// base64, then gunzip with a hard cap on the output.
fn decode(update: &PslUpdate) -> Result<Vec<u8>, String> {
    let sha = &update.sha256;
    if sha.len() != 64 || !sha.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')) {
        return Err("`sha256` is not 64 lower case hex digits".into());
    }
    if update.content.len() > PSL_CONTENT_MAX_LEN {
        return Err(format!(
            "`content` is longer than {PSL_CONTENT_MAX_LEN} characters"
        ));
    }
    let compressed = STANDARD
        .decode(update.content.as_bytes())
        .map_err(|e| format!("`content` is not base64: {e}"))?;
    // One byte more than the cap tells a list at the cap from a bomb.
    let mut out = Vec::new();
    flate2::read::GzDecoder::new(compressed.as_slice())
        .take(PSL_MAX_BYTES as u64 + 1)
        .read_to_end(&mut out)
        .map_err(|e| format!("`content` is not gzip: {e}"))?;
    if out.len() > PSL_MAX_BYTES {
        return Err(format!("the list is larger than {PSL_MAX_BYTES} bytes"));
    }
    Ok(out)
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    bytes.iter().fold(String::new(), |mut out, b| {
        let _ = write!(out, "{b:02x}");
        out
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use std::io::Write;

    use chrono::Utc;
    use flate2::Compression;
    use flate2::write::GzEncoder;

    use super::*;

    /// A small list with the shape of the real one: filler rules to reach the minimum count,
    /// and the rules the tests name.
    pub(crate) fn sample_list() -> String {
        let mut text = String::from(
            "// ===BEGIN ICANN DOMAINS===\n\
             \n\
             com\n\
             uk\n\
             co.uk\n\
             sg\n\
             com.sg\n\
             au\n\
             asn.au\n\
             // Cook Islands\n\
             *.ck\n\
             !www.ck\n\
             // ===END ICANN DOMAINS===\n\
             // ===BEGIN PRIVATE DOMAINS===\n\
             github.io\n\
             *.compute.amazonaws.com   // trailing text after white space\n\
             \u{4e2d}\u{56fd}\n\
             XN--FIQS8S\n\
             // ===END PRIVATE DOMAINS===\n",
        );
        for i in 0..1100 {
            text.push_str(&format!("filler{i}.example\n"));
        }
        text
    }

    pub(crate) fn gzip_base64(bytes: &[u8]) -> String {
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(bytes).unwrap();
        STANDARD.encode(encoder.finish().unwrap())
    }

    pub(crate) fn sha256_hex(bytes: &[u8]) -> String {
        hex(digest::digest(&digest::SHA256, bytes).as_ref())
    }

    pub(crate) fn update_of(bytes: &[u8]) -> PslUpdate {
        PslUpdate {
            sha256: sha256_hex(bytes),
            fetched_at: Utc::now(),
            rule_count: 1111,
            content: gzip_base64(bytes),
        }
    }

    fn list() -> PublicSuffixList {
        PublicSuffixList::parse(sample_list().as_bytes()).unwrap()
    }

    #[test]
    fn parses_rules_comments_wildcards_and_exceptions() {
        let list = list();
        assert!(list.rule_count() > 1100);
        assert!(list.exact.contains("com"));
        assert!(list.exact.contains("github.io"));
        assert!(list.exact.contains("xn--fiqs8s"), "lower-cased");
        assert!(list.wildcards.contains("ck"));
        assert!(list.wildcards.contains("compute.amazonaws.com"));
        assert!(list.exceptions.contains("www.ck"));
        assert!(!list.exact.iter().any(|r| !r.is_ascii()), "IDN skipped");
        assert!(!list.exact.iter().any(|r| r.starts_with("//")));
        assert!(!list.exact.iter().any(|r| r.contains("trailing")));
        list.looks_like_psl().unwrap();
        // CRLF line ends and a missing final newline.
        let crlf = PublicSuffixList::parse(b"com\r\n*.ck\r\n!www.ck").unwrap();
        assert!(crlf.exact.contains("com") && crlf.wildcards.contains("ck"));
        assert!(crlf.exceptions.contains("www.ck"));
        // Malformed rules are skipped.
        let odd = PublicSuffixList::parse(b"a*b.com\n*.*.com\n..\n.com\n*\n!\nok.com\n").unwrap();
        assert_eq!(odd.rule_count(), 1);
        assert!(PublicSuffixList::parse(&[0xff, 0xfe]).is_err());
    }

    #[test]
    fn an_empty_or_small_list_is_no_public_suffix_list() {
        assert!(
            PublicSuffixList::parse(b"")
                .unwrap()
                .looks_like_psl()
                .is_err()
        );
        assert!(
            PublicSuffixList::parse(b"com\nco.uk\n")
                .unwrap()
                .looks_like_psl()
                .is_err()
        );
        let no_com: String = (0..1200).map(|i| format!("r{i}.example\n")).collect();
        let why = PublicSuffixList::parse(no_com.as_bytes())
            .unwrap()
            .looks_like_psl()
            .unwrap_err();
        assert!(why.contains("com"), "{why}");
    }

    #[test]
    fn public_suffixes() {
        let list = list();
        for suffix in [
            "com",
            "co.uk",
            "uk",
            "github.io",
            "com.sg",
            "asn.au",
            // One label is a suffix, listed or not (the default rule).
            "unlisted",
            // `*.ck`
            "foo.ck",
            "anything.ck",
            // `*.compute.amazonaws.com`
            "eu-1.compute.amazonaws.com",
            // Case and a trailing dot do not matter.
            "COM",
            "Co.Uk",
            "github.io.",
        ] {
            assert!(list.is_public_suffix(suffix), "{suffix} is a public suffix");
        }
    }

    #[test]
    fn registrable_names_and_tenants_are_no_public_suffix() {
        let list = list();
        for name in [
            "example.com",
            "tenant.github.io",
            "a.b.github.io",
            "bbc.co.uk",
            "example.com.sg",
            "example.asn.au",
            // `!www.ck` is an exception to `*.ck`.
            "www.ck",
            "a.www.ck",
            "a.foo.ck",
            "compute.amazonaws.com",
            "tenant.eu-1.compute.amazonaws.com",
            "",
            ".",
        ] {
            assert!(!list.is_public_suffix(name), "{name:?} is no public suffix");
        }
    }

    #[test]
    fn a_wildcard_is_too_broad_over_a_suffix_or_over_names_that_all_are_suffixes() {
        let list = list();
        for base in [
            "com",
            "co.uk",
            "github.io",
            "com.sg",
            "asn.au",
            "foo.ck",
            "compute.amazonaws.com",
            "eu-1.compute.amazonaws.com",
        ] {
            assert!(list.wildcard_is_too_broad(base), "*.{base}");
        }
        for base in [
            "example.com",
            "tenant.github.io",
            "www.ck",
            "amazonaws.com",
            "tenant.eu-1.compute.amazonaws.com",
        ] {
            assert!(!list.wildcard_is_too_broad(base), "*.{base}");
        }
    }

    #[test]
    fn the_store_checks_wildcard_rules_only_when_it_holds_a_list() {
        let store = PublicSuffixStore::in_memory();
        let rule = |entry: &str| Rule::parse(entry).unwrap();
        assert!(!store.is_held());
        assert_eq!(store.sha256(), None);
        assert_eq!(store.is_public_suffix("com"), None);
        assert_eq!(
            store.broad_wildcard_base(&rule("*.github.io")),
            None,
            "no list"
        );
        store
            .apply_update(&update_of(sample_list().as_bytes()))
            .unwrap();
        assert_eq!(
            store.broad_wildcard_base(&rule("*.github.io")),
            Some("github.io")
        );
        assert_eq!(
            store.broad_wildcard_base(&rule("*.github.io:8443")),
            Some("github.io")
        );
        assert_eq!(store.broad_wildcard_base(&rule("*.com.sg")), Some("com.sg"));
        assert_eq!(store.broad_wildcard_base(&rule("*.tenant.github.io")), None);
        assert_eq!(store.broad_wildcard_base(&rule("*.example.com")), None);
        assert_eq!(
            store.broad_wildcard_base(&rule("github.io")),
            None,
            "an exact host"
        );
        assert_eq!(store.broad_wildcard_base(&rule("tenant.github.io")), None);
        assert_eq!(store.broad_wildcard_base(&rule("203.0.113.7")), None);
        assert_eq!(store.is_public_suffix("co.uk"), Some(true));
    }

    #[test]
    fn an_older_update_arriving_late_does_not_replace_a_newer_one() {
        let store = PublicSuffixStore::in_memory();
        let newer_text = format!("{}\nnewer.test\n", sample_list());
        let older_text = format!("{}\nolder.test\n", sample_list());
        let mut newer = update_of(newer_text.as_bytes());
        let mut older = update_of(older_text.as_bytes());
        older.fetched_at = newer.fetched_at - chrono::TimeDelta::hours(1);
        newer.fetched_at = older.fetched_at + chrono::TimeDelta::hours(1);
        // The newer frame is applied first although the older one was sent first.
        store.apply_update(&newer).unwrap();
        store.apply_update(&older).unwrap();
        assert_eq!(store.sha256().as_deref(), Some(newer.sha256.as_str()));
        // In the order they were sent, the newer one wins as well.
        let other = PublicSuffixStore::in_memory();
        other.apply_update(&older).unwrap();
        other.apply_update(&newer).unwrap();
        assert_eq!(other.sha256().as_deref(), Some(newer.sha256.as_str()));
    }

    #[test]
    fn a_restart_remembers_which_list_is_newer() {
        let temp = tempfile::tempdir().unwrap();
        let paths = Paths::new(temp.path().join("config"), temp.path().join("data"));
        let newer_text = format!("{}\nnewer.test\n", sample_list());
        let older_text = format!("{}\nolder.test\n", sample_list());
        let mut newer = update_of(newer_text.as_bytes());
        let mut older = update_of(older_text.as_bytes());
        newer.fetched_at = older.fetched_at + chrono::TimeDelta::hours(1);
        older.fetched_at = newer.fetched_at - chrono::TimeDelta::hours(1);
        PublicSuffixStore::load(&paths)
            .apply_update(&newer)
            .unwrap();
        // After a restart a stale server pushes the older list: it is not taken.
        let restarted = PublicSuffixStore::load(&paths);
        assert_eq!(restarted.sha256().as_deref(), Some(newer.sha256.as_str()));
        restarted.apply_update(&older).unwrap();
        assert_eq!(restarted.sha256().as_deref(), Some(newer.sha256.as_str()));
        // A list without a stored time (written by an earlier version) accepts what comes.
        std::fs::remove_file(paths.data_dir.join(FETCHED_FILE_NAME)).unwrap();
        let unknown = PublicSuffixStore::load(&paths);
        unknown.apply_update(&older).unwrap();
        assert_eq!(unknown.sha256().as_deref(), Some(older.sha256.as_str()));
    }

    #[test]
    fn a_valid_update_is_used_and_kept_in_a_private_file() {
        use std::os::unix::fs::PermissionsExt;
        let temp = tempfile::tempdir().unwrap();
        let paths = Paths::new(temp.path().join("config"), temp.path().join("data"));
        let store = PublicSuffixStore::load(&paths);
        assert!(!store.is_held(), "nothing is embedded");
        let bytes = sample_list().into_bytes();
        store.apply_update(&update_of(&bytes)).unwrap();
        assert_eq!(store.sha256(), Some(sha256_hex(&bytes)));
        let file = paths.data_dir.join(FILE_NAME);
        assert_eq!(std::fs::read(&file).unwrap(), bytes);
        assert_eq!(
            std::fs::metadata(&file).unwrap().permissions().mode() & 0o777,
            0o600
        );
        let leftovers: Vec<_> = std::fs::read_dir(&paths.data_dir).unwrap().collect();
        assert_eq!(
            leftovers.len(),
            2,
            "the list and its time; no temporary file stays"
        );

        // A restart reads it again.
        let again = PublicSuffixStore::load(&paths);
        assert_eq!(again.sha256(), Some(sha256_hex(&bytes)));
        assert_eq!(again.is_public_suffix("github.io"), Some(true));

        // The same list again changes nothing; another one replaces it.
        store.apply_update(&update_of(&bytes)).unwrap();
        let mut other = sample_list();
        other.push_str("added.example\n");
        store.apply_update(&update_of(other.as_bytes())).unwrap();
        assert_eq!(store.sha256(), Some(sha256_hex(other.as_bytes())));
        assert_eq!(std::fs::read(&file).unwrap(), other.as_bytes());
    }

    #[test]
    fn a_stored_file_that_is_no_list_is_ignored() {
        let temp = tempfile::tempdir().unwrap();
        let paths = Paths::new(temp.path().join("config"), temp.path().join("data"));
        for content in [&b"not a list"[..], b"", &[0xff, 0xfe, 0x00][..]] {
            secure_fs::write_private_file(&paths.data_dir.join(FILE_NAME), content).unwrap();
            assert!(!PublicSuffixStore::load(&paths).is_held());
        }
    }

    #[test]
    fn an_invalid_update_keeps_the_list_the_host_holds() {
        let temp = tempfile::tempdir().unwrap();
        let paths = Paths::new(temp.path().join("config"), temp.path().join("data"));
        let store = PublicSuffixStore::load(&paths);
        let bytes = sample_list().into_bytes();
        store.apply_update(&update_of(&bytes)).unwrap();
        let kept = Some(sha256_hex(&bytes));
        let refused = |update: PslUpdate, expected: &str| {
            let why = store.apply_update(&update).unwrap_err();
            assert!(
                why.contains(expected),
                "{why:?} should contain {expected:?}"
            );
            assert_eq!(store.sha256(), kept);
            assert_eq!(
                std::fs::read(paths.data_dir.join(FILE_NAME)).unwrap(),
                bytes
            );
        };

        let mut other = sample_list();
        other.push_str("added.example\n");
        let good = update_of(other.as_bytes());

        // A wrong SHA-256, in shape or in value.
        let mut wrong = good.clone();
        wrong.sha256 = sha256_hex(b"something else");
        refused(wrong, "differs");
        for bad in ["", "ABC", &"A".repeat(64), &"g".repeat(64)] {
            let mut update = good.clone();
            update.sha256 = bad.to_string();
            refused(update, "lower case hex");
        }
        // Not base64, not gzip, gzip of nothing useful.
        let mut update = good.clone();
        update.content = "!!! not base64 !!!".into();
        refused(update, "base64");
        let mut update = good.clone();
        update.content = STANDARD.encode(b"plain, not gzip");
        refused(update, "gzip");
        let mut update = good.clone();
        let mut truncated = STANDARD.decode(&good.content).unwrap();
        truncated.truncate(truncated.len() / 2);
        update.content = STANDARD.encode(truncated);
        refused(update, "gzip");
        // Too long a frame.
        let mut update = good.clone();
        update.content = "A".repeat(PSL_CONTENT_MAX_LEN + 4);
        refused(update, "longer than");
        // Not a list: too few rules, no `com`.
        refused(update_of(b"com\nco.uk\n"), "rules");
        let no_com: String = (0..1200).map(|i| format!("r{i}.example\n")).collect();
        refused(update_of(no_com.as_bytes()), "com");
        refused(update_of(&[0xff, 0xfe]), "UTF-8");
    }

    #[test]
    fn a_decompression_bomb_is_stopped_at_the_cap() {
        let store = PublicSuffixStore::in_memory();
        // A little more than the cap, of the most compressible content: a few kilobytes.
        let bomb = vec![b'a'; PSL_MAX_BYTES + 1024];
        let update = update_of(&bomb);
        assert!(update.content.len() < 20_000, "{}", update.content.len());
        let why = store.apply_update(&update).unwrap_err();
        assert!(why.contains("larger than"), "{why}");
        assert!(!store.is_held());
        // Exactly the cap is read (and then refused for its shape, not its size).
        let at_cap = vec![b'\n'; PSL_MAX_BYTES];
        let why = store.apply_update(&update_of(&at_cap)).unwrap_err();
        assert!(why.contains("rules"), "{why}");
    }
}
