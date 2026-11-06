//! Server URL, host token (`login`), the protocol version and small persistent state.

use std::path::Path;
use std::sync::{Arc, Mutex};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use tinfirm_protocol::{PROTOCOL_VERSION_V2, PROTOCOL_VERSIONS, Secret};
use url::Url;
use uuid::Uuid;

use crate::paths::Paths;
use crate::secure_fs;

pub const CONNECT_PATH: &str = "/api/runner/v1/connect";

/// The runner protocol version a host speaks unless `config.json` or `run --protocol-version`
/// says otherwise: v2, which the server accepts next to v1 since package 8. A server from
/// before it speaks v1 only: start the host with `--protocol-version 1` for such a server.
pub const DEFAULT_PROTOCOL_VERSION: u32 = PROTOCOL_VERSION_V2;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostConfig {
    /// Base URL of the Tinfirm server, e.g. `https://tinfirm.example.com`.
    pub server_url: String,
    /// Runner host token `tfr_…`, shown once at registration.
    pub token: Secret,
    /// The runner protocol version (`hello.protocolVersion`) this host speaks: 1 or 2.
    /// Absent (as in a `config.json` written before package 8): [`DEFAULT_PROTOCOL_VERSION`].
    #[serde(default = "default_protocol_version")]
    pub protocol_version: u32,
}

fn default_protocol_version() -> u32 {
    DEFAULT_PROTOCOL_VERSION
}

/// Checks a protocol version from `config.json` or the command line.
pub fn validate_protocol_version(version: u32) -> Result<u32> {
    if PROTOCOL_VERSIONS.contains(&version) {
        Ok(version)
    } else {
        bail!(
            "unsupported runner protocol version {version}; this runner host speaks {}",
            PROTOCOL_VERSIONS
                .iter()
                .map(|v| v.to_string())
                .collect::<Vec<_>>()
                .join(" and ")
        )
    }
}

impl HostConfig {
    /// A configuration with the default protocol version.
    pub fn new(server_url: impl Into<String>, token: Secret) -> HostConfig {
        HostConfig {
            server_url: server_url.into(),
            token,
            protocol_version: DEFAULT_PROTOCOL_VERSION,
        }
    }

    pub fn load(paths: &Paths) -> Result<HostConfig> {
        let file = paths.config_file();
        if !file.exists() {
            bail!(
                "not logged in: run `tinfirm-runner-host login --server <url> --token <token>` first"
            );
        }
        let bytes = secure_fs::read_private_file(&file)?;
        let config: HostConfig = serde_json::from_slice(&bytes)
            .with_context(|| format!("{} is invalid", file.display()))?;
        validate_protocol_version(config.protocol_version)
            .with_context(|| format!("{} is invalid", file.display()))?;
        Ok(config)
    }

    /// The configuration of `run --server --token-file`, for a host whose settings come from its
    /// environment, such as the Helm chart's runner pod: the server URL checked as `login` checks
    /// it, the token read from a file (a mounted secret), and the default protocol version.
    pub fn from_token_file(server_url: &str, token_file: &Path) -> Result<HostConfig> {
        let server_url = validate_server_url(server_url)?;
        let bytes = std::fs::read(token_file)
            .with_context(|| format!("cannot read the token file {}", token_file.display()))?;
        // Never in a message: a file that holds something else may hold another secret.
        let token = std::str::from_utf8(&bytes).unwrap_or_default().trim();
        if !token_looks_valid(token) {
            bail!(
                "{} does not hold a runner host token (`tfr_` + 43 base64url characters)",
                token_file.display()
            );
        }
        Ok(HostConfig::new(server_url, Secret::new(token.to_string())))
    }

    pub fn save(&self, paths: &Paths) -> Result<()> {
        let bytes = serde_json::to_vec_pretty(self)?;
        secure_fs::write_private_file(&paths.config_file(), &bytes)
    }

    /// The WebSocket URL of the runner endpoint.
    pub fn websocket_url(&self) -> Result<Url> {
        websocket_url(&self.server_url)
    }
}

/// Normalizes a server URL (`http(s)://host[:port][/base]`) to the runner WebSocket URL.
/// A `ws(s)://` URL with a path is taken as is.
pub fn websocket_url(server_url: &str) -> Result<Url> {
    let mut url = Url::parse(server_url.trim())
        .with_context(|| format!("invalid server URL `{server_url}`"))?;
    let scheme = match url.scheme() {
        "http" => "ws",
        "https" => "wss",
        "ws" | "wss" => {
            if url.path() != "/" && !url.path().is_empty() {
                return Ok(url);
            }
            url.scheme()
        }
        other => bail!(
            "unsupported URL scheme `{other}`; use https:// (or http:// for local development)"
        ),
    }
    .to_string();
    url.set_scheme(&scheme)
        .map_err(|_| anyhow::anyhow!("cannot convert `{server_url}` to a WebSocket URL"))?;
    let base = url.path().trim_end_matches('/').to_string();
    url.set_path(&format!("{base}{CONNECT_PATH}"));
    url.set_query(None);
    url.set_fragment(None);
    Ok(url)
}

/// Checks a server URL for `login`: http(s) only, and plain http only for loopback hosts.
pub fn validate_server_url(server_url: &str) -> Result<String> {
    let url = Url::parse(server_url.trim())
        .with_context(|| format!("invalid server URL `{server_url}`"))?;
    match url.scheme() {
        "https" | "wss" => {}
        "http" | "ws" => {
            let host = url.host_str().unwrap_or_default();
            let loopback = host == "localhost"
                || host == "127.0.0.1"
                || host == "[::1]"
                || host == "::1"
                || host.ends_with(".localhost");
            if !loopback {
                bail!(
                    "refusing plain `{}` for a non-local server; use https://",
                    url.scheme()
                );
            }
        }
        other => bail!("unsupported URL scheme `{other}`"),
    }
    websocket_url(server_url)?;
    Ok(server_url.trim().trim_end_matches('/').to_string())
}

/// `true` when the token has the documented shape `tfr_` + base64url(32 bytes).
pub fn token_looks_valid(token: &str) -> bool {
    let Some(rest) = token.strip_prefix("tfr_") else {
        return false;
    };
    rest.len() == 43
        && rest
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// State the host remembers between processes.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostState {
    /// `welcome.runnerHostId` of the last connection; labels our containers.
    #[serde(default)]
    pub runner_host_id: Option<Uuid>,
    #[serde(default)]
    pub company_id: Option<Uuid>,
}

impl HostState {
    pub fn load(paths: &Paths) -> HostState {
        let file = paths.state_file();
        secure_fs::read_private_file(&file)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, paths: &Paths) -> Result<()> {
        secure_fs::write_private_file(&paths.state_file(), &serde_json::to_vec_pretty(self)?)
    }
}

/// The host's `state.json`, which each `welcome` names, written off the connection's task.
///
/// A write syncs the file to disk, and a slow disk can take a second for that: on the
/// connection's task it would hold back the heartbeats and every frame behind the welcome.
/// [`HostStateFile::want`] only notes what the file should hold, and a write runs on Tokio's
/// blocking threads, and only when that differs from what the file holds: a reconnect that
/// names the same host writes nothing. Writes do not overlap, and each one writes what the
/// last welcome named, so an older state never lands after a newer one. A write that fails
/// is tried again at the next welcome.
#[derive(Debug)]
pub struct HostStateFile {
    paths: Paths,
    /// What the file should hold.
    wanted: Mutex<HostState>,
    /// What the file holds; held while the file is written.
    written: Mutex<HostState>,
}

impl HostStateFile {
    /// The file as the host finds it at the start, and what it holds.
    pub fn load(paths: &Paths) -> (Arc<HostStateFile>, HostState) {
        let state = HostState::load(paths);
        let file = Arc::new(HostStateFile {
            paths: paths.clone(),
            wanted: Mutex::new(state.clone()),
            written: Mutex::new(state.clone()),
        });
        (file, state)
    }

    /// The file should hold `state`: written in the background, or here outside a Tokio
    /// runtime; nothing when it holds `state` or a write of it is on its way.
    pub fn want(self: &Arc<Self>, state: HostState) {
        {
            let mut wanted = lock(&self.wanted);
            if *wanted == state {
                return;
            }
            *wanted = state;
        }
        let file = self.clone();
        let write = move || file.write();
        match tokio::runtime::Handle::try_current() {
            Ok(runtime) => {
                runtime.spawn_blocking(write);
            }
            Err(_) => write(),
        }
    }

    /// Writes what the file should hold now, unless it holds it.
    fn write(&self) {
        let mut written = lock(&self.written);
        let wanted = lock(&self.wanted).clone();
        if *written == wanted {
            return;
        }
        match wanted.save(&self.paths) {
            Ok(()) => *written = wanted,
            Err(e) => {
                tracing::warn!(error = %format!("{e:#}"), "cannot save host state");
                // The next welcome tries again, unless another one named a newer state.
                let mut still_wanted = lock(&self.wanted);
                if *still_wanted == wanted {
                    *still_wanted = written.clone();
                }
            }
        }
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_websocket_urls() {
        assert_eq!(
            websocket_url("https://tinfirm.example.com")
                .unwrap()
                .as_str(),
            "wss://tinfirm.example.com/api/runner/v1/connect"
        );
        assert_eq!(
            websocket_url("http://localhost:8080/").unwrap().as_str(),
            "ws://localhost:8080/api/runner/v1/connect"
        );
        assert_eq!(
            websocket_url("https://example.com/tinfirm/")
                .unwrap()
                .as_str(),
            "wss://example.com/tinfirm/api/runner/v1/connect"
        );
        assert_eq!(
            websocket_url("ws://127.0.0.1:9/custom").unwrap().as_str(),
            "ws://127.0.0.1:9/custom"
        );
        assert!(websocket_url("ftp://x").is_err());
    }

    #[test]
    fn validates_login_urls() {
        assert!(validate_server_url("https://tinfirm.example.com/").is_ok());
        assert!(validate_server_url("http://localhost:8080").is_ok());
        assert!(validate_server_url("http://127.0.0.1:8080").is_ok());
        assert!(validate_server_url("http://tinfirm.example.com").is_err());
    }

    #[test]
    fn the_protocol_version_defaults_to_2_and_is_checked() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = Paths::new(tmp.path().join("config"), tmp.path().join("data"));
        secure_fs::write_private_file(
            &paths.config_file(),
            br#"{"serverUrl": "https://t.example", "token": "tfr_x"}"#,
        )
        .unwrap();
        let config = HostConfig::load(&paths).unwrap();
        assert_eq!(config.protocol_version, DEFAULT_PROTOCOL_VERSION);
        assert_eq!(config.protocol_version, 2);
        assert_eq!(
            HostConfig::new("https://t.example", Secret::new("tfr_x")).protocol_version,
            2
        );
        let mut v1 = config.clone();
        v1.protocol_version = 1;
        v1.save(&paths).unwrap();
        assert_eq!(HostConfig::load(&paths).unwrap().protocol_version, 1);
        let text = std::fs::read_to_string(paths.config_file()).unwrap();
        assert!(text.contains("\"protocolVersion\": 1"), "{text}");
        let mut v3 = config;
        v3.protocol_version = 3;
        v3.save(&paths).unwrap();
        let err = HostConfig::load(&paths).unwrap_err();
        assert!(
            format!("{err:#}").contains("unsupported runner protocol version 3"),
            "{err:#}"
        );
        assert!(validate_protocol_version(1).is_ok());
        assert!(validate_protocol_version(2).is_ok());
        assert!(validate_protocol_version(0).is_err());
    }

    fn state(company_id: Uuid) -> HostState {
        HostState {
            runner_host_id: Some(Uuid::from_u128(7)),
            company_id: Some(company_id),
        }
    }

    #[test]
    fn the_state_file_is_written_when_its_state_changes_and_only_then() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = Paths::new(tmp.path().join("config"), tmp.path().join("data"));
        let (file, loaded) = HostStateFile::load(&paths);
        assert_eq!(loaded, HostState::default());
        // Outside a Tokio runtime the write happens at once.
        let first = state(Uuid::new_v4());
        file.want(first.clone());
        assert_eq!(HostState::load(&paths), first);
        // The same state again, as after a reconnect: nothing is written.
        secure_fs::write_private_file(&paths.state_file(), b"{}").unwrap();
        file.want(first.clone());
        assert_eq!(HostState::load(&paths), HostState::default());
        let second = state(Uuid::new_v4());
        file.want(second.clone());
        assert_eq!(HostState::load(&paths), second);
        // A host that starts finds it, and writes nothing for it.
        let (again, loaded) = HostStateFile::load(&paths);
        assert_eq!(loaded, second);
        secure_fs::write_private_file(&paths.state_file(), b"{}").unwrap();
        again.want(second);
        assert_eq!(HostState::load(&paths), HostState::default());
    }

    #[test]
    fn a_state_that_could_not_be_written_is_written_at_the_next_welcome() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = Paths::new(tmp.path().join("config"), tmp.path().join("data"));
        let (file, _) = HostStateFile::load(&paths);
        // A directory where the file goes: the write fails, and the host goes on.
        std::fs::create_dir_all(paths.state_file()).unwrap();
        let wanted = state(Uuid::new_v4());
        file.want(wanted.clone());
        assert!(paths.state_file().is_dir());
        std::fs::remove_dir(paths.state_file()).unwrap();
        file.want(wanted.clone());
        assert_eq!(HostState::load(&paths), wanted);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn in_a_runtime_the_file_is_written_in_the_background_and_the_last_state_wins() {
        let tmp = tempfile::tempdir().unwrap();
        let paths = Paths::new(tmp.path().join("config"), tmp.path().join("data"));
        let (file, _) = HostStateFile::load(&paths);
        let states: Vec<HostState> = (0..20).map(|_| state(Uuid::new_v4())).collect();
        for wanted in &states {
            file.want(wanted.clone());
        }
        let last = states.last().unwrap().clone();
        tokio::time::timeout(std::time::Duration::from_secs(10), async {
            while HostState::load(&paths) != last {
                tokio::time::sleep(std::time::Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("the last state is written");
        // No write of an earlier state lands after it.
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
        assert_eq!(HostState::load(&paths), last);
    }

    #[test]
    fn a_token_file_with_the_server_url_stands_in_for_login() {
        let tmp = tempfile::tempdir().unwrap();
        let file = tmp.path().join("token");
        let token = format!("tfr_{}", "A".repeat(43));
        std::fs::write(&file, format!("{token}\n")).unwrap();
        let config = HostConfig::from_token_file("https://t.example/", &file).unwrap();
        assert_eq!(config.server_url, "https://t.example");
        assert_eq!(config.token.expose(), token);
        assert_eq!(config.protocol_version, DEFAULT_PROTOCOL_VERSION);

        assert!(HostConfig::from_token_file("http://t.example", &file).is_err());
        let other = "not-a-token-but-a-secret";
        std::fs::write(&file, other).unwrap();
        let refused = HostConfig::from_token_file("https://t.example", &file)
            .err()
            .unwrap();
        assert!(!format!("{refused:#}").contains(other));
        assert!(
            HostConfig::from_token_file("https://t.example", &tmp.path().join("none")).is_err()
        );
    }

    #[test]
    fn checks_token_shape() {
        let token = format!("tfr_{}", "A".repeat(43));
        assert!(token_looks_valid(&token));
        assert!(!token_looks_valid("tfr_short"));
        assert!(!token_looks_valid(&format!("xyz_{}", "A".repeat(43))));
    }
}
