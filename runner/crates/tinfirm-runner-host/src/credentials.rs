//! Credential store: one 0600 JSON file per credential under `<config>/credentials/`.
//!
//! Secret values never leave the host except into the sandbox of a run (as an env var, or
//! for Codex ChatGPT auth as the mounted dedicated `CODEX_HOME`). A Claude login holds no
//! secret of its own: `claude auth login` wrote Claude Code's `.credentials.json` into the
//! credential's own `CLAUDE_CONFIG_DIR` ([`crate::paths::Paths::claude_login`]), which the host
//! mounts into the sandbox as a file and never reads (ADR-0085).

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result, bail};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tinfirm_protocol::{CredentialInfo, CredentialKind, MAX_CAPACITY, Secret};

use crate::paths::Paths;
use crate::secure_fs;

pub const DEFAULT_CLAUDE_POOL: u32 = 3;
pub const DEFAULT_API_KEY_POOL: u32 = 3;
/// Codex ChatGPT auth shares one `auth.json` between the runs of its pool: Codex re-reads
/// it before every refresh and on every 401, so a refresh by one run serves the others
/// (ADR-0085, decision 5).
pub const DEFAULT_CODEX_POOL: u32 = 3;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Credential {
    pub label: String,
    pub kind: CredentialKind,
    /// `credential add --pool`: the credential's ceiling (docs/archive/v1/CONVENTIONS.md §14.8);
    /// see [`Credential::pool_ceiling`].
    pub pool_size: u32,
    pub created_at: DateTime<Utc>,
    /// The token or API key. `None` for Codex ChatGPT auth, whose secret is `auth.json`
    /// in the dedicated Codex home, and for a Claude login, whose secret is Claude Code's
    /// `.credentials.json` in the login's directory, which the host never reads.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub secret: Option<Secret>,
}

impl Credential {
    /// The most runs that may use this credential at once: its pool, 1 to 16. Tinfirm can
    /// lower it, never raise it.
    pub fn pool_ceiling(&self) -> u32 {
        self.pool_size.clamp(1, MAX_CAPACITY)
    }

    /// `hello.credentials[]`: metadata and the ceiling, never the secret.
    pub fn info(&self) -> CredentialInfo {
        CredentialInfo {
            label: self.label.clone(),
            kind: self.kind,
            runtimes: self.kind.runtimes(),
            pool_size: self.pool_ceiling(),
        }
    }
}

/// Checks the label pattern of the protocol (`^[a-z0-9][a-z0-9-]{0,62}$`).
pub fn validate_label(label: &str) -> Result<()> {
    let bytes = label.as_bytes();
    let ok = !bytes.is_empty()
        && bytes.len() <= 63
        && (bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit())
        && bytes
            .iter()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || *b == b'-');
    if !ok {
        bail!(
            "invalid label `{label}`: use lowercase letters, digits and dashes (max 63, starting with a letter or digit)"
        );
    }
    Ok(())
}

/// Format problems of a secret value (presence and shape only; no network calls).
pub fn check_secret_format(kind: CredentialKind, value: &str) -> Vec<String> {
    let mut problems = Vec::new();
    if value.trim() != value || value.chars().any(char::is_whitespace) {
        problems.push("contains whitespace".to_string());
    }
    if value.len() < 20 {
        problems.push("is too short".to_string());
    }
    let expected_prefix = match kind {
        CredentialKind::AnthropicApiKey => Some("sk-ant-api"),
        CredentialKind::OpenaiApiKey => Some("sk-"),
        CredentialKind::CodexChatgptAuth | CredentialKind::ClaudeLogin => None,
    };
    if let Some(prefix) = expected_prefix
        && !value.starts_with(prefix)
    {
        problems.push(format!("does not start with `{prefix}`"));
    }
    problems
}

/// Credential files a host cannot make sense of, each with why ([`CredentialStore::unreadable`]).
pub type Unreadable = Vec<(PathBuf, String)>;

pub struct CredentialStore {
    paths: Paths,
}

impl CredentialStore {
    pub fn new(paths: &Paths) -> CredentialStore {
        CredentialStore {
            paths: paths.clone(),
        }
    }

    /// Every credential this host can read, by label. A file it cannot make sense of is
    /// skipped with a warning, not an error: a credential a host of another version wrote (a
    /// `setup-token` token, `CLAUDE_OAUTH_TOKEN`, of a host before ADR-0085) must not keep the
    /// host from starting with its other credentials. `doctor` names such files
    /// ([`CredentialStore::unreadable`]); `credential remove <label>` deletes one.
    pub fn list(&self) -> Result<Vec<Credential>> {
        let (credentials, unreadable) = self.read_all()?;
        for (path, why) in unreadable {
            tracing::warn!(
                path = %path.display(),
                %why,
                "skipping a credential file this host cannot read; `credential remove <label>` deletes it"
            );
        }
        Ok(credentials)
    }

    /// The credential files this host cannot make sense of, with why ([`CredentialStore::list`]).
    pub fn unreadable(&self) -> Unreadable {
        self.read_all()
            .map(|(_, unreadable)| unreadable)
            .unwrap_or_default()
    }

    fn read_all(&self) -> Result<(Vec<Credential>, Unreadable)> {
        let dir = self.paths.credentials_dir();
        let mut out = BTreeMap::new();
        let mut unreadable = Vec::new();
        let entries = match fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                return Ok((Vec::new(), unreadable));
            }
            Err(e) => return Err(e).with_context(|| format!("cannot read {}", dir.display())),
        };
        for entry in entries {
            let path = entry?.path();
            if path.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            // A file the host cannot read or make sense of is named, never the end of the list.
            let bytes = match secure_fs::read_private_file(&path) {
                Ok(bytes) => bytes,
                Err(e) => {
                    unreadable.push((path, format!("{e:#}")));
                    continue;
                }
            };
            match serde_json::from_slice::<Credential>(&bytes) {
                Ok(credential) => {
                    out.insert(credential.label.clone(), credential);
                }
                Err(e) => unreadable.push((path, e.to_string())),
            }
        }
        Ok((out.into_values().collect(), unreadable))
    }

    pub fn get(&self, label: &str) -> Result<Option<Credential>> {
        validate_label(label)?;
        let path = self.paths.credential_file(label);
        if !path.exists() {
            return Ok(None);
        }
        let bytes = secure_fs::read_private_file(&path)?;
        Ok(Some(serde_json::from_slice(&bytes)?))
    }

    pub fn save(&self, credential: &Credential) -> Result<()> {
        validate_label(&credential.label)?;
        secure_fs::ensure_private_dir(&self.paths.config_dir)?;
        let bytes = serde_json::to_vec_pretty(credential)?;
        secure_fs::write_private_file(&self.paths.credential_file(&credential.label), &bytes)
    }

    /// Removes the credential file and, for Codex, its dedicated home; for a Claude login,
    /// its directory, with the login Claude Code wrote there.
    pub fn remove(&self, label: &str) -> Result<bool> {
        validate_label(label)?;
        let path = self.paths.credential_file(label);
        let existed = path.exists();
        if existed {
            fs::remove_file(&path)?;
        }
        let codex_home = self.paths.codex_home(label);
        if codex_home.exists() {
            fs::remove_dir_all(&codex_home)?;
        }
        let claude_login = self.paths.claude_login(label);
        if claude_login.exists() {
            fs::remove_dir_all(&claude_login)?;
        }
        Ok(existed)
    }

    pub fn codex_home(&self, label: &str) -> PathBuf {
        self.paths.codex_home(label)
    }

    /// The `CLAUDE_CONFIG_DIR` of a Claude login credential.
    pub fn claude_login(&self, label: &str) -> PathBuf {
        self.paths.claude_login(label)
    }

    /// Claude Code's credentials file of a Claude login: what the host mounts into the sandbox
    /// at [`CLAUDE_CREDENTIALS_IN_SANDBOX`].
    pub fn claude_credentials_file(&self, label: &str) -> PathBuf {
        claude_credentials_file(&self.paths.claude_login(label))
    }

    /// Every secret value held on this host, for redaction: stored tokens and keys, plus
    /// the tokens inside each Codex `auth.json` (which Codex refreshes during runs).
    pub fn secret_values(&self) -> Vec<String> {
        let mut values = Vec::new();
        let Ok(credentials) = self.list() else {
            return values;
        };
        for credential in credentials {
            if let Some(secret) = &credential.secret {
                values.push(secret.expose().to_string());
            }
            if credential.kind == CredentialKind::CodexChatgptAuth {
                values.extend(codex_auth_secrets(&self.codex_home(&credential.label)));
            }
        }
        values
    }
}

/// Claude Code's credentials file, relative to its `CLAUDE_CONFIG_DIR`.
pub const CLAUDE_CREDENTIALS_FILE: &str = ".credentials.json";

/// Where a Claude login's credentials file is mounted in the sandbox: Claude Code's default
/// `CLAUDE_CONFIG_DIR` in the agent's home, so the runtime is signed in as the member, with
/// the rest of that directory (skills, settings, sessions) the run's own.
pub const CLAUDE_CREDENTIALS_IN_SANDBOX: &str = "/home/agent/.claude/.credentials.json";

/// Claude Code's credentials file inside a login directory.
pub fn claude_credentials_file(claude_login: &std::path::Path) -> PathBuf {
    claude_login.join(CLAUDE_CREDENTIALS_FILE)
}

/// Problems of a Claude login directory (for `doctor`, `credential add` and run acceptance),
/// from the credentials file's metadata alone: it exists, is a regular file (Claude Code itself
/// refuses a link) and is private. The host reads no byte of it: the tokens are Claude Code's,
/// and whether the login is in force is Claude Code's to say (`claude auth status`, which
/// `credential add` asks). Nor does it look at the size, which is zero for a moment while
/// Claude Code renews the login in place.
pub fn check_claude_login(claude_login: &std::path::Path) -> Vec<String> {
    let file = claude_credentials_file(claude_login);
    let mut problems = Vec::new();
    match fs::symlink_metadata(&file) {
        Err(_) => {
            problems.push(format!(
                "{} is missing; run `credential add claude` again",
                file.display()
            ));
            return problems;
        }
        Ok(meta) if meta.file_type().is_symlink() => {
            problems.push(format!(
                "{} is a link; Claude Code refuses it",
                file.display()
            ));
            return problems;
        }
        Ok(meta) if !meta.is_file() => {
            problems.push(format!("{} is not a file", file.display()));
            return problems;
        }
        Ok(_) => {}
    }
    // Private, and readable and writable by the owner: the sandbox's user reads and renews it.
    if let Some(mode) = secure_fs::mode_of(&file)
        && mode & 0o777 != 0o600
    {
        problems.push(format!(
            "{} has mode {mode:o}; expected 600",
            file.display()
        ));
    }
    problems
}

/// Makes the mount point of a Claude login's credentials file in the session home before the
/// sandbox starts: `~/.claude` a plain directory and `~/.claude/.credentials.json` a plain,
/// empty, private file. Docker then mounts the host's file over it instead of creating a
/// root-owned one, and a link an earlier run left there is refused, as Claude Code refuses
/// one. The empty file stays in the home after the run: Claude Code reads nothing from it.
pub fn claude_login_mount_point(home: &std::path::Path) -> std::result::Result<(), String> {
    use std::os::unix::fs::OpenOptionsExt;
    let dir = home.join(".claude");
    match fs::symlink_metadata(&dir) {
        Ok(meta) if meta.file_type().is_symlink() => {
            return Err("~/.claude in the session home is a link".into());
        }
        Ok(meta) if !meta.is_dir() => {
            return Err("~/.claude in the session home is not a directory".into());
        }
        Ok(_) => {}
        Err(_) => fs::create_dir(&dir)
            .map_err(|e| format!("cannot create ~/.claude in the session home: {e}"))?,
    }
    let file = claude_credentials_file(&dir);
    match fs::symlink_metadata(&file) {
        Ok(meta) if meta.file_type().is_symlink() => {
            Err("~/.claude/.credentials.json in the session home is a link".into())
        }
        Ok(meta) if !meta.is_file() => {
            Err("~/.claude/.credentials.json in the session home is not a file".into())
        }
        Ok(_) => Ok(()),
        Err(_) => fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&file)
            .map(|_| ())
            .map_err(|e| {
                format!("cannot create the mount point ~/.claude/.credentials.json in the session home: {e}")
            }),
    }
}

/// Token values inside `<codex_home>/auth.json`.
pub fn codex_auth_secrets(codex_home: &std::path::Path) -> Vec<String> {
    let Ok(bytes) = fs::read(codex_home.join("auth.json")) else {
        return Vec::new();
    };
    let Ok(json) = serde_json::from_slice::<serde_json::Value>(&bytes) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    if let Some(key) = json.get("OPENAI_API_KEY").and_then(|v| v.as_str()) {
        out.push(key.to_string());
    }
    if let Some(tokens) = json.get("tokens") {
        for key in ["id_token", "access_token", "refresh_token"] {
            if let Some(v) = tokens.get(key).and_then(|v| v.as_str()) {
                out.push(v.to_string());
            }
        }
    }
    out.retain(|v| v.len() >= 8);
    out
}

/// What a Codex login's `auth.json` is at one moment: its modification time, its size and a
/// hash of its content, `None` when it is missing. Two stamps differ when a run renewed the
/// login in between (ADR-0085, decision 5), on a file system with coarse timestamps and for a
/// renewal of the same size too. The host reads the Codex login for redaction already.
pub type FileStamp = Option<(std::time::SystemTime, u64, u64)>;

/// The stamp of a Codex home's `auth.json` as it is now.
pub fn codex_auth_stamp(codex_home: &std::path::Path) -> FileStamp {
    use std::hash::{Hash, Hasher};
    let auth = codex_home.join("auth.json");
    let meta = fs::metadata(&auth).ok()?;
    let bytes = fs::read(&auth).ok()?;
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    bytes.hash(&mut hasher);
    Some((meta.modified().ok()?, meta.len(), hasher.finish()))
}

/// Presence and format problems of a Codex home (for `doctor` and run acceptance).
pub fn check_codex_home(codex_home: &std::path::Path) -> Vec<String> {
    let auth = codex_home.join("auth.json");
    let mut problems = Vec::new();
    match fs::read(&auth) {
        Err(_) => problems.push(format!(
            "{} is missing; run `credential add codex` again",
            auth.display()
        )),
        Ok(bytes) => match serde_json::from_slice::<serde_json::Value>(&bytes) {
            Err(_) => problems.push(format!("{} is not valid JSON", auth.display())),
            Ok(json) => {
                let has_tokens = json
                    .get("tokens")
                    .and_then(|t| t.get("refresh_token"))
                    .and_then(|v| v.as_str())
                    .is_some_and(|v| !v.is_empty());
                let has_key = json
                    .get("OPENAI_API_KEY")
                    .and_then(|v| v.as_str())
                    .is_some_and(|v| !v.is_empty());
                if !has_tokens && !has_key {
                    problems.push(format!("{} holds no ChatGPT tokens", auth.display()));
                }
            }
        },
    }
    // Private, and readable and writable by the owner: the sandbox's user reads and renews it.
    if let Some(mode) = secure_fs::mode_of(&auth)
        && mode & 0o777 != 0o600
    {
        problems.push(format!(
            "{} has mode {mode:o}; expected 600",
            auth.display()
        ));
    }
    problems
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codex_auth_stamp_changes_with_the_file() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(codex_auth_stamp(dir.path()), None);
        fs::write(dir.path().join("auth.json"), b"{\"tokens\":{}}").unwrap();
        let first = codex_auth_stamp(dir.path());
        assert!(first.is_some());
        assert_eq!(codex_auth_stamp(dir.path()), first, "unchanged, the same");
        fs::write(dir.path().join("auth.json"), b"{\"tokens\":{\"x\":1}}").unwrap();
        let second = codex_auth_stamp(dir.path());
        assert_ne!(second, first, "renewed, another");
        // The same size in the same instant: the content tells them apart.
        fs::write(dir.path().join("auth.json"), b"{\"tokens\":{\"x\":2}}").unwrap();
        assert_ne!(codex_auth_stamp(dir.path()), second, "same size, another");
    }

    #[test]
    fn labels() {
        assert!(validate_label("claude-main").is_ok());
        assert!(validate_label("0a").is_ok());
        assert!(validate_label("-a").is_err());
        assert!(validate_label("Claude").is_err());
        assert!(validate_label("").is_err());
        assert!(validate_label(&"a".repeat(64)).is_err());
        assert!(validate_label("../x").is_err());
    }

    #[test]
    fn secret_formats() {
        assert!(
            check_secret_format(
                CredentialKind::AnthropicApiKey,
                "sk-ant-api03-abcdefghijklmnop"
            )
            .is_empty()
        );
        assert!(
            !check_secret_format(
                CredentialKind::AnthropicApiKey,
                "sk-ant-oat01-abcdefghijklmnop"
            )
            .is_empty()
        );
        assert!(
            !check_secret_format(CredentialKind::OpenaiApiKey, "sk-abc def ghijklmnopqrstu")
                .is_empty()
        );
    }

    #[test]
    fn store_round_trip_and_secret_values() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = Paths::new(tmp.path().join("cfg"), tmp.path().join("data"));
        let store = CredentialStore::new(&paths);
        store
            .save(&Credential {
                label: "claude-main".into(),
                kind: CredentialKind::ClaudeLogin,
                pool_size: 3,
                created_at: Utc::now(),
                secret: None,
            })
            .unwrap();
        let login = store.claude_login("claude-main");
        fs::create_dir_all(&login).unwrap();
        secure_fs::write_private_file(
            &claude_credentials_file(&login),
            br#"{"claudeAiOauth":{"accessToken":"sk-ant-oat01-TEST-ONLY-NOT-A-REAL-TOKEN"}}"#,
        )
        .unwrap();
        store
            .save(&Credential {
                label: "codex-main".into(),
                kind: CredentialKind::CodexChatgptAuth,
                pool_size: 1,
                created_at: Utc::now(),
                secret: None,
            })
            .unwrap();
        let home = store.codex_home("codex-main");
        fs::create_dir_all(&home).unwrap();
        fs::write(
            home.join("auth.json"),
            r#"{"OPENAI_API_KEY":null,"tokens":{"id_token":"eyJ.test.idtoken","access_token":"test-access-token-value","refresh_token":"test-refresh-token-value","account_id":"acc"}}"#,
        )
        .unwrap();
        let listed = store.list().unwrap();
        assert_eq!(listed.len(), 2);
        assert_eq!(listed[0].label, "claude-main");
        let secrets = store.secret_values();
        // A Claude login's tokens are Claude Code's: the host reads none of them.
        assert!(!secrets.contains(&"sk-ant-oat01-TEST-ONLY-NOT-A-REAL-TOKEN".to_string()));
        assert!(secrets.contains(&"test-refresh-token-value".to_string()));
        assert!(!secrets.contains(&"acc".to_string()));
        assert_eq!(
            secure_fs::mode_of(&paths.credential_file("claude-main")),
            Some(0o600)
        );
        assert!(store.remove("codex-main").unwrap());
        assert!(!home.exists());
        assert_eq!(store.list().unwrap().len(), 1);
        assert!(store.remove("claude-main").unwrap());
        assert!(
            !login.exists(),
            "the login directory goes with the credential"
        );
        assert!(store.list().unwrap().is_empty());
    }

    #[test]
    fn a_credential_file_of_another_version_is_skipped_named_and_removable() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = Paths::new(tmp.path().join("cfg"), tmp.path().join("data"));
        let store = CredentialStore::new(&paths);
        store
            .save(&Credential {
                label: "claude-main".into(),
                kind: CredentialKind::ClaudeLogin,
                pool_size: 3,
                created_at: Utc::now(),
                secret: None,
            })
            .unwrap();
        // A `setup-token` token of a host before ADR-0085.
        let old = paths.credential_file("claude-old");
        secure_fs::write_private_file(
            &old,
            br#"{"label":"claude-old","kind":"CLAUDE_OAUTH_TOKEN","poolSize":3,"createdAt":"2026-01-01T00:00:00Z","secret":"sk-ant-oat01-TEST-ONLY"}"#,
        )
        .unwrap();
        let listed = store.list().unwrap();
        assert_eq!(listed.len(), 1, "the host starts with what it can read");
        assert_eq!(listed[0].label, "claude-main");
        let unreadable = store.unreadable();
        assert_eq!(unreadable.len(), 1);
        assert_eq!(unreadable[0].0, old);
        assert!(
            unreadable[0].1.contains("CLAUDE_OAUTH_TOKEN"),
            "{}",
            unreadable[0].1
        );
        assert!(store.remove("claude-old").unwrap());
        assert!(store.unreadable().is_empty());
    }

    #[test]
    fn claude_login_checks_the_file_and_reads_nothing_from_it() {
        let tmp = tempfile::tempdir().unwrap();
        let login = tmp.path().join("login");
        fs::create_dir_all(&login).unwrap();
        assert!(check_claude_login(&login)[0].contains("is missing"));
        let file = claude_credentials_file(&login);
        // Only the metadata: an unreadable file passes, and so does one Claude Code is
        // renewing (empty for a moment); the contents are Claude Code's to judge.
        secure_fs::write_private_file(&file, b"").unwrap();
        assert!(check_claude_login(&login).is_empty());
        // Owner-write only: private, but Claude Code could not read it; the check reads no byte
        // either way (a read of this file would fail).
        fs::set_permissions(&file, std::os::unix::fs::PermissionsExt::from_mode(0o200)).unwrap();
        assert!(check_claude_login(&login)[0].contains("mode 200"));
        fs::set_permissions(&file, std::os::unix::fs::PermissionsExt::from_mode(0o600)).unwrap();
        secure_fs::write_private_file(&file, br#"{"claudeAiOauth":{"accessToken":"x"}}"#).unwrap();
        assert!(check_claude_login(&login).is_empty());
        fs::set_permissions(&file, std::os::unix::fs::PermissionsExt::from_mode(0o644)).unwrap();
        assert!(check_claude_login(&login)[0].contains("mode 644"));
        // A link is refused, as Claude Code refuses one.
        fs::remove_file(&file).unwrap();
        let target = tmp.path().join("elsewhere.json");
        secure_fs::write_private_file(&target, br#"{"claudeAiOauth":{}}"#).unwrap();
        std::os::unix::fs::symlink(&target, &file).unwrap();
        assert!(check_claude_login(&login)[0].contains("is a link"));
    }

    #[test]
    fn the_mount_point_in_the_home_is_a_plain_empty_private_file() {
        let tmp = tempfile::tempdir().unwrap();
        let home = tmp.path().join("home");
        fs::create_dir_all(&home).unwrap();
        claude_login_mount_point(&home).unwrap();
        let file = home.join(".claude/.credentials.json");
        let meta = fs::symlink_metadata(&file).unwrap();
        assert!(meta.is_file());
        assert_eq!(meta.len(), 0);
        assert_eq!(secure_fs::mode_of(&file), Some(0o600));
        // Made once; a second run of the home finds it.
        claude_login_mount_point(&home).unwrap();
        // A link in its place is refused.
        fs::remove_file(&file).unwrap();
        std::os::unix::fs::symlink(tmp.path().join("x"), &file).unwrap();
        assert!(
            claude_login_mount_point(&home)
                .unwrap_err()
                .contains("is a link")
        );
        fs::remove_file(&file).unwrap();
        fs::remove_dir(home.join(".claude")).unwrap();
        std::os::unix::fs::symlink(tmp.path(), home.join(".claude")).unwrap();
        assert!(
            claude_login_mount_point(&home)
                .unwrap_err()
                .contains("is a link")
        );
    }
}
