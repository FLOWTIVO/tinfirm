//! Host mirrors of the task repositories (docs/archive/v1/CONVENTIONS.md §15.4 step 2).
//!
//! The host keeps one bare mirror per repository URL and principal at
//! `<data>/mirrors/<key>.git`, where `<key>` is [`mirror_key`]: the first 32 hex characters of
//! SHA-256 over the URL and the SHA-256 of the credentials the fetch uses (the account's or
//! the credential's `Authorization` header), or over the URL alone, marked anonymous, for a
//! public repository. A mirror's objects are therefore only ever those that one principal's
//! credentials fetched, and a workspace clone, which copies the mirror's object store with
//! unreachable objects included, never hands one member's objects (a branch since deleted,
//! history since rewritten) to a run of another (the owner's decision Q8 of the security
//! review of 2026-10-27, M15). A rotated token gets a fresh mirror; the old one is idle and
//! maintenance deletes it like any other. Mirrors from before this keying are never used
//! again and go the same way. Every run fetches the mirror
//! with its own credentials before it clones from it, so the mirror has the current branches
//! and tags, and a run whose token no longer works fails instead of getting stale content.
//! A workspace is then a plain local copy of the mirror ([`crate::git::Git::clone_from_mirror`]).
//!
//! - A mirror is created in a temporary directory next to its final place and renamed into
//!   place once the first fetch succeeded, so a mirror that exists is complete.
//! - `gc.auto=0`: git never collects garbage in a mirror on its own, so a clone never races
//!   with a repack. Garbage is collected by maintenance ([`crate::maintenance`]) under the
//!   mirror's lock.
//! - Fetches use `+refs/heads/*:refs/heads/*` and `+refs/tags/*:refs/tags/*` with `--prune`:
//!   the mirror's branches and tags are exactly the repository's.
//! - One run at a time uses a mirror: a per-mirror lock (in this process; `run.lock` makes
//!   sure there is only one `run` process per config) covers the fetch, reading the refs, and
//!   the clone. Maintenance takes the same lock, but only when no run holds it or waits for
//!   it.
//! - Each mirror has a state file, `tinfirm-mirror.json` in the bare repository: the
//!   repository URL (without userinfo, query or fragment, so never a credential), and when
//!   the mirror was created, last used by a run, and last garbage-collected.
//! - A mirror is deleted by renaming it to `.tmp-delete-…` first and removing that
//!   directory afterwards, so a mirror under its own name is never half deleted.
//! - Mirrors are never mounted into a sandbox, and no workspace links to one.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use anyhow::{Context, Result, bail};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tinfirm_protocol::Secret;
use tokio::sync::OwnedMutexGuard;

use crate::git::{self, MirrorRef};
use crate::secure_fs;
use crate::util::dir_size;

/// Hex characters of SHA-256 in a mirror's name.
const KEY_LEN: usize = 32;
/// Prefix of the temporary directory a new mirror is fetched into.
const TEMP_PREFIX: &str = ".tmp-";
/// Prefix of a mirror that is being deleted. It starts with [`TEMP_PREFIX`], so the startup
/// cleanup removes what a crash left behind.
const DELETE_PREFIX: &str = ".tmp-delete-";
/// The host's bookkeeping inside each bare mirror.
pub const STATE_FILE: &str = "tinfirm-mirror.json";

/// The mirror key of a repository URL fetched with `authorization` (the `Authorization`
/// header of the run's git credentials, `None` for a public repository): the first 32 hex
/// characters of SHA-256 over a version tag, the URL and either the SHA-256 of
/// `authorization` or `anonymous`. Two principals never share a mirror, and the key holds
/// no credential.
pub fn mirror_key(repo_url: &str, authorization: Option<&Secret>) -> String {
    let principal = match authorization {
        Some(secret) => {
            hex(ring::digest::digest(&ring::digest::SHA256, secret.expose().as_bytes()).as_ref())
        }
        None => "anonymous".to_string(),
    };
    let mut ctx = ring::digest::Context::new(&ring::digest::SHA256);
    ctx.update(b"tinfirm-mirror-v2\0");
    ctx.update(repo_url.as_bytes());
    ctx.update(b"\0");
    ctx.update(principal.as_bytes());
    let mut key = hex(ctx.finish().as_ref());
    key.truncate(KEY_LEN);
    key
}

fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// The key of a directory name `<key>.git`, if it is one.
fn key_of(name: &str) -> Option<&str> {
    let key = name.strip_suffix(".git")?;
    (key.len() == KEY_LEN
        && key
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)))
    .then_some(key)
}

/// The repository URL as the state file keeps it: without userinfo, query and fragment,
/// so no credential ever lands there. `None` when it does not parse.
pub fn display_url(repo_url: &str) -> Option<String> {
    let mut url = url::Url::parse(repo_url).ok()?;
    // URLs that cannot have a username (`file:`) refuse both calls; they have none.
    let _ = url.set_username("");
    let _ = url.set_password(None);
    url.set_query(None);
    url.set_fragment(None);
    Some(url.to_string())
}

/// What the host records about a mirror, in [`STATE_FILE`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MirrorState {
    /// The repository URL (see [`display_url`]).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub repo_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub created_at: Option<DateTime<Utc>>,
    /// The last time a run fetched the mirror.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_used_at: Option<DateTime<Utc>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_gc_at: Option<DateTime<Utc>>,
}

/// The state of the mirror at `mirror`; `None` when it has none (a mirror created before the
/// state file existed) or the file cannot be read.
pub fn read_state(mirror: &Path) -> Option<MirrorState> {
    let bytes = std::fs::read(mirror.join(STATE_FILE)).ok()?;
    serde_json::from_slice(&bytes).ok()
}

/// Replaces the state of the mirror at `mirror` (atomically, mode 0600).
pub fn write_state(mirror: &Path, state: &MirrorState) -> Result<()> {
    let bytes = serde_json::to_vec_pretty(state)?;
    secure_fs::write_private_file(&mirror.join(STATE_FILE), &bytes)
}

/// A mirror on disk, as [`Mirrors::list`] finds it.
#[derive(Debug, Clone)]
pub struct MirrorInfo {
    pub key: String,
    pub path: PathBuf,
    /// `None` for a mirror without a state file.
    pub state: Option<MirrorState>,
    /// Bytes of its files.
    pub size_bytes: u64,
}

/// Loose objects and packs of a mirror (`git count-objects -v`).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ObjectCounts {
    pub loose: u64,
    pub packs: u64,
}

/// A mirror's lock, and how many runs wait for it.
#[derive(Default)]
struct Slot {
    lock: Arc<tokio::sync::Mutex<()>>,
    /// Runs waiting for `lock`: maintenance leaves the mirror to them.
    waiting: AtomicUsize,
}

/// Counts a run as waiting for a mirror while it exists.
struct Waiting<'a>(&'a AtomicUsize);

impl<'a> Waiting<'a> {
    fn new(counter: &'a AtomicUsize) -> Waiting<'a> {
        counter.fetch_add(1, Ordering::SeqCst);
        Waiting(counter)
    }
}

impl Drop for Waiting<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// The mirrors of this host.
pub struct Mirrors {
    dir: PathBuf,
    slots: Mutex<HashMap<String, Arc<Slot>>>,
}

impl std::fmt::Debug for Mirrors {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Mirrors").field("dir", &self.dir).finish()
    }
}

/// A mirror that is up to date and locked for this run. Dropping it releases the lock.
pub struct MirrorLease {
    pub path: PathBuf,
    /// The mirror's branches and tags right after the fetch.
    pub refs: Vec<MirrorRef>,
    _lock: OwnedMutexGuard<()>,
}

impl std::fmt::Debug for MirrorLease {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MirrorLease")
            .field("path", &self.path)
            .field("refs", &self.refs.len())
            .finish()
    }
}

/// A mirror locked by maintenance: no run fetches or clones it meanwhile. Dropping it
/// releases the lock.
pub struct MaintenanceLock {
    key: String,
    path: PathBuf,
    slot: Arc<Slot>,
    _lock: OwnedMutexGuard<()>,
}

impl MaintenanceLock {
    pub fn key(&self) -> &str {
        &self.key
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// `true` when a run waits for this mirror; maintenance then lets it go.
    pub fn wanted(&self) -> bool {
        self.slot.waiting.load(Ordering::SeqCst) > 0
    }
}

impl std::fmt::Debug for MaintenanceLock {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MaintenanceLock")
            .field("key", &self.key)
            .finish()
    }
}

/// Removes a half-created mirror unless it was moved into place.
struct TempMirror {
    path: PathBuf,
    armed: bool,
}

impl Drop for TempMirror {
    fn drop(&mut self) {
        if self.armed {
            let _ = std::fs::remove_dir_all(&self.path);
        }
    }
}

impl Mirrors {
    pub fn new(dir: impl Into<PathBuf>) -> Mirrors {
        Mirrors {
            dir: dir.into(),
            slots: Mutex::new(HashMap::new()),
        }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Where the mirror of `repo_url` fetched with `authorization` is (or will be).
    pub fn path_for(&self, repo_url: &str, authorization: Option<&Secret>) -> PathBuf {
        self.path_of(&mirror_key(repo_url, authorization))
    }

    fn path_of(&self, key: &str) -> PathBuf {
        self.dir.join(format!("{key}.git"))
    }

    fn slot(&self, key: &str) -> Arc<Slot> {
        self.slots
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .entry(key.to_string())
            .or_default()
            .clone()
    }

    /// Waits for the lock of mirror `key`, counted as a waiting run meanwhile.
    async fn lock_key(&self, key: &str) -> OwnedMutexGuard<()> {
        let slot = self.slot(key);
        let _waiting = Waiting::new(&slot.waiting);
        slot.lock.clone().lock_owned().await
    }

    /// Waits for the mirror of `repo_url` fetched with `authorization` and locks it.
    pub async fn lock(
        &self,
        repo_url: &str,
        authorization: Option<&Secret>,
    ) -> OwnedMutexGuard<()> {
        self.lock_key(&mirror_key(repo_url, authorization)).await
    }

    /// Locks mirror `key` for maintenance without waiting: `None` when a run holds the lock
    /// or waits for it. Maintenance never makes a run wait for a lock it does not have yet.
    pub fn try_lock_for_maintenance(&self, key: &str) -> Option<MaintenanceLock> {
        let slot = self.slot(key);
        if slot.waiting.load(Ordering::SeqCst) > 0 {
            return None;
        }
        let guard = slot.lock.clone().try_lock_owned().ok()?;
        let lock = MaintenanceLock {
            key: key.to_string(),
            path: self.path_of(key),
            slot,
            _lock: guard,
        };
        // A run may have started to wait between the check and the lock.
        (!lock.wanted()).then_some(lock)
    }

    /// Creates or fetches the mirror of `repo_url` with `authorization`, under its lock, and
    /// returns it still locked. The mirror is the one of this principal ([`mirror_key`]). Any failure (a 401 or 403 included) is an error: the run must
    /// not work on content its credentials can no longer read.
    pub async fn update(
        &self,
        repo_url: &str,
        authorization: Option<&Secret>,
    ) -> Result<MirrorLease> {
        secure_fs::ensure_private_dir(&self.dir)?;
        let key = mirror_key(repo_url, authorization);
        let lock = self.lock_key(&key).await;
        let path = self.path_of(&key);
        match std::fs::symlink_metadata(&path) {
            Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => {
                fetch(&path, repo_url, authorization).await?;
            }
            Ok(_) => bail!(
                "{} exists and is not a mirror directory; remove it",
                path.display()
            ),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                self.create(&key, &path, repo_url, authorization).await?;
            }
            Err(e) => {
                return Err(e).with_context(|| format!("cannot inspect {}", path.display()));
            }
        }
        record_use(&path, repo_url, Utc::now());
        let refs = refs_of(&path).await?;
        Ok(MirrorLease {
            path,
            refs,
            _lock: lock,
        })
    }

    /// Fetches an existing mirror again and returns its refs (after the agent, for the
    /// finalize step). The lock is held only for this.
    pub async fn refresh(
        &self,
        repo_url: &str,
        authorization: Option<&Secret>,
    ) -> Result<Vec<MirrorRef>> {
        let lease = self.update(repo_url, authorization).await?;
        Ok(lease.refs)
    }

    async fn create(
        &self,
        key: &str,
        path: &Path,
        repo_url: &str,
        authorization: Option<&Secret>,
    ) -> Result<()> {
        let mut temp = TempMirror {
            path: self.dir.join(format!(
                "{TEMP_PREFIX}{key}-{}.git",
                uuid::Uuid::new_v4().simple()
            )),
            armed: true,
        };
        let temp_arg = temp.path.to_string_lossy().into_owned();
        git::run_ok(&self.dir, None, &["init", "--bare", "--quiet", &temp_arg])
            .await
            .context("cannot create the mirror")?;
        git::run_ok(&temp.path, None, &["config", "gc.auto", "0"]).await?;
        fetch(&temp.path, repo_url, authorization).await?;
        write_state(
            &temp.path,
            &MirrorState {
                repo_url: display_url(repo_url),
                created_at: Some(Utc::now()),
                ..MirrorState::default()
            },
        )?;
        std::fs::rename(&temp.path, path)
            .with_context(|| format!("cannot move the new mirror to {}", path.display()))?;
        temp.armed = false;
        tracing::info!(mirror = %path.display(), "created a repository mirror");
        Ok(())
    }

    /// The mirrors on disk (`<key>.git` directories), by key. Temporary directories are
    /// no mirrors.
    pub fn list(&self) -> Result<Vec<MirrorInfo>> {
        let entries = match std::fs::read_dir(&self.dir) {
            Ok(entries) => entries,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => {
                return Err(e).with_context(|| format!("cannot read {}", self.dir.display()));
            }
        };
        let mut mirrors = Vec::new();
        for entry in entries.filter_map(|e| e.ok()) {
            let name = entry.file_name();
            let Some(key) = key_of(&name.to_string_lossy()).map(str::to_string) else {
                continue;
            };
            let path = entry.path();
            match std::fs::symlink_metadata(&path) {
                Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => {}
                _ => continue,
            }
            mirrors.push(MirrorInfo {
                state: read_state(&path),
                size_bytes: dir_size(&path),
                key,
                path,
            });
        }
        mirrors.sort_by(|a, b| a.key.cmp(&b.key));
        Ok(mirrors)
    }

    /// Takes the mirror held by `lock` out of use: renames it to `.tmp-delete-<key>-…`, so
    /// that a crash while its files are removed never leaves a mirror that looks valid.
    /// Returns the new path. The startup cleanup ([`Mirrors::remove_stale_temp_dirs`]) and
    /// every maintenance pass ([`Mirrors::remove_deleted_dirs`]) remove what is left.
    pub fn retire(&self, lock: &MaintenanceLock) -> Result<PathBuf> {
        let target = self.dir.join(format!(
            "{DELETE_PREFIX}{}-{}.git",
            lock.key,
            uuid::Uuid::new_v4().simple()
        ));
        std::fs::rename(&lock.path, &target).with_context(|| {
            format!(
                "cannot move {} out of the way to delete it",
                lock.path.display()
            )
        })?;
        Ok(target)
    }

    /// Deletes the mirror held by `lock` ([`Mirrors::retire`], then the directory). The lock
    /// is released once the mirror is out of the way, so a run can create a new one while
    /// the old files are removed. Fails only when the mirror could not be moved away; files
    /// that cannot be removed are left to the next pass.
    pub async fn delete(&self, lock: MaintenanceLock) -> Result<()> {
        let retired = self.retire(&lock)?;
        drop(lock);
        if let Err(e) = remove_dir(retired).await {
            tracing::warn!(error = %format!("{e:#}"), "cannot remove a deleted mirror; the next maintenance pass tries again");
        }
        Ok(())
    }

    /// Removes directories of mirrors whose deletion did not finish (a crash, or an error),
    /// but never a mirror that a run is creating.
    pub async fn remove_deleted_dirs(&self) {
        let Ok(entries) = std::fs::read_dir(&self.dir) else {
            return;
        };
        for entry in entries.filter_map(|e| e.ok()) {
            if entry
                .file_name()
                .to_string_lossy()
                .starts_with(DELETE_PREFIX)
                && let Err(e) = remove_dir(entry.path()).await
            {
                tracing::warn!(error = %format!("{e:#}"), "cannot remove a deleted mirror");
            }
        }
    }

    /// `git count-objects -v` of the locked mirror.
    pub async fn count_objects(&self, lock: &MaintenanceLock) -> Result<ObjectCounts> {
        let out = git::run_ok(&lock.path, None, &["count-objects", "-v"]).await?;
        let mut counts = ObjectCounts::default();
        for line in out.lines() {
            match line.split_once(':') {
                Some(("count", n)) => counts.loose = n.trim().parse().unwrap_or(0),
                Some(("packs", n)) => counts.packs = n.trim().parse().unwrap_or(0),
                _ => {}
            }
        }
        Ok(counts)
    }

    /// `git gc --prune=now` in the locked mirror, at low CPU priority, with the host's
    /// hardened git settings (no hooks, no credential helpers, no replace objects, …).
    ///
    /// `--prune=now` deletes every unreachable object at once, without git's two-week grace
    /// period. That is safe here only because
    /// - no workspace links to the mirror: clones are `--no-hardlinks` copies without
    ///   alternates, so no other repository needs an object from it, and
    /// - the caller holds the mirror's lock, so no fetch runs meanwhile (a fetch writes its
    ///   objects before it updates the refs, and those objects would look unreachable) and
    ///   no clone copies a half-repacked object directory.
    ///
    /// The command is not killed when the future is dropped: killing `git gc` would leave its
    /// `git prune` child running without the lock. Commit-graphs and bitmaps are not written,
    /// so the files a clone copies stay the same kinds as before.
    pub async fn gc(&self, lock: &MaintenanceLock) -> Result<()> {
        let args = [
            "-c",
            "gc.writeCommitGraph=false",
            "-c",
            "repack.writeBitmaps=false",
            "gc",
            "--prune=now",
            "--quiet",
        ];
        let niced = git::low_priority_command(&lock.path)
            .args(args)
            .kill_on_drop(false)
            .output()
            .await;
        let output = match niced {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                git::command(&lock.path, None)
                    .args(args)
                    .kill_on_drop(false)
                    .output()
                    .await
            }
            other => other,
        }
        .context("cannot run git gc; is git installed?")?;
        if !output.status.success() {
            bail!(
                "git gc failed: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        Ok(())
    }

    /// Startup: removes mirrors that a previous process left half-created or half-deleted.
    pub fn remove_stale_temp_dirs(&self) {
        let Ok(entries) = std::fs::read_dir(&self.dir) else {
            return;
        };
        for entry in entries.filter_map(|e| e.ok()) {
            let name = entry.file_name();
            if name.to_string_lossy().starts_with(TEMP_PREFIX) {
                let path = entry.path();
                if let Err(e) = std::fs::remove_dir_all(&path) {
                    tracing::warn!(path = %path.display(), error = %e, "cannot remove a half-created mirror");
                }
            }
        }
    }
}

/// Records that a run used the mirror at `at`, and its URL if the state has none yet.
fn record_use(mirror: &Path, repo_url: &str, at: DateTime<Utc>) {
    let mut state = read_state(mirror).unwrap_or_default();
    if state.repo_url.is_none() {
        state.repo_url = display_url(repo_url);
    }
    state.last_used_at = Some(at);
    if let Err(e) = write_state(mirror, &state) {
        tracing::warn!(mirror = %mirror.display(), error = %format!("{e:#}"), "cannot record the use of a mirror");
    }
}

/// Removes a directory tree off the async threads.
async fn remove_dir(path: PathBuf) -> Result<()> {
    let shown = path.display().to_string();
    tokio::task::spawn_blocking(move || std::fs::remove_dir_all(&path))
        .await
        .context("the removal task failed")?
        .with_context(|| format!("cannot remove {shown}"))
}

/// Fetches every branch and tag of `repo_url` into the mirror, pruning what is gone.
async fn fetch(mirror: &Path, repo_url: &str, authorization: Option<&Secret>) -> Result<()> {
    let out = git::run(
        mirror,
        authorization,
        &[
            "fetch",
            "--prune",
            "--no-write-fetch-head",
            "--quiet",
            "--",
            repo_url,
            "+refs/heads/*:refs/heads/*",
            "+refs/tags/*:refs/tags/*",
        ],
    )
    .await?;
    if !out.status.success() {
        bail!("cannot fetch the repository: {}", out.stderr.trim());
    }
    Ok(())
}

/// The branches and tags of a mirror.
async fn refs_of(mirror: &Path) -> Result<Vec<MirrorRef>> {
    let out = git::run_ok(
        mirror,
        None,
        &[
            "for-each-ref",
            "--format=%(objectname) %(refname)",
            "refs/heads",
            "refs/tags",
        ],
    )
    .await?;
    Ok(out
        .lines()
        .filter_map(|line| line.split_once(' '))
        .map(|(sha, name)| MirrorRef {
            name: name.to_string(),
            sha: sha.to_string(),
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_are_32_hex_characters() {
        let url = "https://github.com/acme/widgets.git";
        let key = mirror_key(url, None);
        assert_eq!(key.len(), 32);
        assert!(
            key.bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        );
        assert_ne!(key, mirror_key("https://github.com/acme/widgets", None));
        let mirrors = Mirrors::new("/data/mirrors");
        assert_eq!(
            mirrors.path_for(url, None),
            PathBuf::from(format!("/data/mirrors/{key}.git"))
        );
        assert_eq!(key_of(&format!("{key}.git")), Some(key.as_str()));
        for name in [
            key.clone(),
            format!(".tmp-{key}.git"),
            format!("{}.git", key.to_uppercase()),
            format!("{}.git", &key[..31]),
            "tinfirm-mirror.json".to_string(),
        ] {
            assert_eq!(key_of(&name), None, "{name}");
        }
    }

    /// M15 (security review of 2026-10-27), Q8: one mirror per repository and principal, so
    /// that the objects one member's credentials fetched never reach another member's run.
    #[test]
    fn keys_differ_by_principal() {
        let url = "https://github.com/acme/widgets.git";
        let alice = Secret::new("Basic YWxpY2U6dG9rZW4tYQ==");
        let bob = Secret::new("Basic Ym9iOnRva2VuLWI=");
        let anonymous = mirror_key(url, None);
        let of_alice = mirror_key(url, Some(&alice));
        let of_bob = mirror_key(url, Some(&bob));
        assert_ne!(of_alice, of_bob, "two principals never share a mirror");
        assert_ne!(of_alice, anonymous);
        assert_ne!(of_bob, anonymous);
        assert_eq!(
            of_alice,
            mirror_key(url, Some(&Secret::new("Basic YWxpY2U6dG9rZW4tYQ=="))),
            "the same principal keeps its mirror"
        );
        assert_ne!(
            of_alice,
            mirror_key("https://github.com/acme/other.git", Some(&alice))
        );
        // The key of a URL alone (the keying before Q8) is never reused.
        let url_only = hex(ring::digest::digest(&ring::digest::SHA256, url.as_bytes()).as_ref());
        assert_ne!(anonymous, url_only[..KEY_LEN]);
        // The key holds no part of the credential.
        assert!(!of_alice.contains("YWxp"));
    }

    #[test]
    fn the_state_keeps_no_credentials() {
        assert_eq!(
            display_url("https://x-access-token:secret@github.com/acme/w.git?token=t#frag")
                .as_deref(),
            Some("https://github.com/acme/w.git")
        );
        assert_eq!(
            display_url("file:///tmp/repos/origin.git").as_deref(),
            Some("file:///tmp/repos/origin.git")
        );
        assert_eq!(display_url("not a url"), None);
    }

    #[tokio::test]
    async fn one_lock_per_mirror() {
        let mirrors = Mirrors::new("/nonexistent");
        let a = mirror_key("https://example.com/a.git", None);
        let b = mirror_key("https://example.com/b.git", None);
        let held = mirrors.lock("https://example.com/a.git", None).await;
        assert!(
            mirrors.try_lock_for_maintenance(&a).is_none(),
            "the same mirror is locked"
        );
        let other = mirrors.try_lock_for_maintenance(&b);
        assert!(other.is_some(), "another mirror is not");
        drop(held);
        let maintained = mirrors.try_lock_for_maintenance(&a).unwrap();
        assert!(!maintained.wanted());
        assert_eq!(maintained.key(), a);
        assert_eq!(
            maintained.path(),
            Path::new(&format!("/nonexistent/{a}.git"))
        );
    }

    #[tokio::test]
    async fn maintenance_leaves_a_mirror_to_a_waiting_run() {
        let mirrors = Arc::new(Mirrors::new("/nonexistent"));
        let url = "https://example.com/a.git";
        let key = mirror_key(url, None);
        let maintenance = mirrors.try_lock_for_maintenance(&key).unwrap();
        let waiter = {
            let mirrors = mirrors.clone();
            tokio::spawn(async move {
                let _lock = mirrors.lock(url, None).await;
            })
        };
        // The run now waits for the lock that maintenance holds.
        tokio::time::timeout(std::time::Duration::from_secs(10), async {
            while !maintenance.wanted() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("the run is counted as waiting");
        drop(maintenance);
        waiter.await.unwrap();
        assert!(mirrors.try_lock_for_maintenance(&key).is_some());
    }
}
