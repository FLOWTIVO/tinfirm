//! The skills of a run: the optional feature `run.skills` of protocol 2 (ADR-0064; the
//! contract, docs/reviews/2026-10-15-skills-contract.md §8).
//!
//! `run.assign.skills` names each skill of the agent at the version it pins, with the SHA-256
//! and size of its canonical zip. After the run is accepted, where it downloads the
//! attachments, the host provides each one ([`provide`]):
//!
//! 1. **The cache**: `<data>/skills/<sha256>/<name>/` is the skill, unpacked. An entry that is
//!    there is used as it is, without a download: it was unpacked from a zip with that hash.
//! 2. Otherwise the host downloads the zip from
//!    `GET /api/runner/v1/runs/{runId}/skills/{skillId}/versions/{version}?epoch={epoch}` with
//!    its host token, as it downloads the attachments, checks the size and the SHA-256 while it
//!    reads, unpacks it into `<data>/skills/.tmp-unpack-…` ([`unpack`]) and renames that into
//!    place: an entry is complete or absent.
//! 3. **The mounts**: each entry is bind-mounted read-only at the runtime's user-level skill
//!    directory ([`mount_dir`]): `~/.claude/skills/<name>` for Claude Code,
//!    `~/.agents/skills/<name>` for Codex, OpenCode and pi.
//!
//! A skill the host cannot provide (download, hash, unpack, mount point) fails the run as an
//! INFRA failure whose message names the skill.
//!
//! **Mount points.** The session home is the agent's, and an earlier run may have left a link
//! anywhere in it; a mount point that Docker creates itself is owned by root, and the runtime
//! could then not write its own files in `~/.claude`. So before the sandbox starts the host
//! creates each mount point through plain directories only ([`create_mount_points`]): each
//! component a directory and never a link, created `0755` when it is missing. It first
//! removes the empty mount points it created for the previous run of the home
//! ([`remove_earlier_mount_points`]), which `homes/<agentId>/<workItemId>.skills` lists, outside
//! the mount ([`crate::homes::skills_record_path`]).
//!
//! **Unpacking** ([`unpack`]) refuses an entry that is a link or a device, an absolute path, a
//! `..`, a backslash, a path over the upload's limits (1,024 bytes, 8 segments of 1 to 255
//! bytes), more than 200 files, and more than 5 MiB in all, counted as the bytes come out of
//! the zip, never as it claims them. Files are written `0644` and directories `0755`, owned
//! by the host's user. The server checked and scanned the zip already; this is the host's own
//! guard.
//!
//! **Maintenance** ([`SkillCache::prune`]): a run takes each entry with a lease
//! ([`SkillCache::lease`]), which writes `<sha256>/<name>.last-used` next to it. An entry that
//! no run used for `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS` (default 14) is deleted, never
//! one a run holds.
//!
//! The local test executor has no mounts: such a host does not list the feature
//! ([`crate::host::features`]) and refuses an assignment with skills.

use std::collections::{HashMap, HashSet};
use std::fs::{self, DirBuilder, OpenOptions, Permissions};
use std::io::Read;
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use anyhow::{Context, anyhow, bail};
use chrono::{DateTime, TimeDelta, Utc};
use http_body_util::BodyExt;
use tinfirm_protocol::{
    AssignedSkill, MAX_SKILLS, RunAssign, Runtime, SKILL_MAX_BYTES, SKILL_NAME_MAX_LEN,
    is_valid_skill_name,
};
use uuid::Uuid;

use crate::attachments;
use crate::sandbox::Bind;
use crate::util::dir_size;

/// Most files of a skill (the upload's limit).
pub const MAX_FILES: usize = 200;
/// Most bytes of a skill's files, uncompressed, in all (the upload's limit).
pub const MAX_UNPACKED_BYTES: u64 = 5 * 1024 * 1024;
/// Longest path of a file in a skill, in bytes.
pub const MAX_PATH_BYTES: usize = 1024;
/// Most segments of a path in a skill.
pub const MAX_PATH_SEGMENTS: usize = 8;
/// Longest segment of a path in a skill, in bytes.
pub const MAX_SEGMENT_BYTES: usize = 255;
/// Most entries of a zip, directories included: far more than [`MAX_FILES`] files need, and
/// a bound on the work before the file count is reached.
const MAX_ENTRIES: usize = 4 * MAX_FILES;

/// Modes of what the host unpacks and of the mount points it creates.
const FILE_MODE: u32 = 0o644;
const DIR_MODE: u32 = 0o755;
/// Mode of `<data>/skills/<sha256>`, which is never mounted.
const HASH_DIR_MODE: u32 = 0o700;

/// Suffix of the file next to an entry that holds its last use (RFC 3339).
const STAMP_SUFFIX: &str = ".last-used";
/// Prefix of a directory a skill is unpacked into before it is renamed into place.
const UNPACK_PREFIX: &str = ".tmp-unpack-";
/// Prefix of an entry that is being deleted.
const DELETE_PREFIX: &str = ".tmp-delete-";

/// File type bits of a Unix mode, and the types an entry of a skill may have.
const S_IFMT: u32 = 0o170000;
const S_IFREG: u32 = 0o100000;
const S_IFDIR: u32 = 0o040000;
const S_IFLNK: u32 = 0o120000;

// ---------------------------------------------------------------------------------------
// The assignment
// ---------------------------------------------------------------------------------------

/// Checks `run.assign.skills` before the run is accepted, as the schema bounds it: at most 20,
/// valid and unique names, a version, a well-formed hash, a size of 1 byte to 5 MiB.
pub fn validate(skills: &[AssignedSkill]) -> Result<(), String> {
    if skills.len() > MAX_SKILLS {
        return Err(format!(
            "run.assign.skills has {} skills, more than {MAX_SKILLS}",
            skills.len()
        ));
    }
    let mut names = HashSet::new();
    for skill in skills {
        if !is_valid_skill_name(&skill.name) {
            return Err(format!(
                "skill name `{}` is not a skill name (lower-case letters and digits in words joined by `-`, at most {SKILL_NAME_MAX_LEN} characters)",
                shown(&skill.name)
            ));
        }
        if !names.insert(skill.name.as_str()) {
            return Err(format!("skill `{}` appears twice", skill.name));
        }
        if skill.version < 1 {
            return Err(format!("skill `{}` has no valid version", skill.name));
        }
        if !is_sha256(&skill.sha256) {
            return Err(format!("skill `{}` has an invalid sha256", skill.name));
        }
        if skill.size_bytes == 0 || skill.size_bytes > SKILL_MAX_BYTES {
            return Err(format!(
                "skill `{}` has {} bytes; a skill has 1 to {SKILL_MAX_BYTES}",
                skill.name, skill.size_bytes
            ));
        }
    }
    Ok(())
}

/// 64 lower-case hex digits.
fn is_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// An untrusted name for a message: escaped, and cut at 100 characters.
fn shown(name: &str) -> String {
    let escaped: String = name.escape_debug().collect();
    match escaped.char_indices().nth(100) {
        Some((cut, _)) => format!("{}…", &escaped[..cut]),
        None => escaped,
    }
}

// ---------------------------------------------------------------------------------------
// Where the runtimes read them
// ---------------------------------------------------------------------------------------

/// The runtime's user-level skill directory, relative to the home: where the runtime lists a
/// user's skills (the skills analysis, §3).
pub fn mount_dir(runtime: Runtime) -> &'static str {
    match runtime {
        Runtime::ClaudeCode => ".claude/skills",
        Runtime::Codex | Runtime::Opencode | Runtime::Pi => ".agents/skills",
    }
}

/// The mount point of the skill `name` in the sandbox, e.g. `/home/agent/.claude/skills/pdf`.
pub fn container_path(runtime: Runtime, name: &str) -> String {
    format!("{}/{}/{name}", crate::sandbox::HOME, mount_dir(runtime))
}

/// The read-only binds of the run's skills, at the runtime's skill directory.
pub fn binds(runtime: Runtime, skills: &[ProvidedSkill]) -> Vec<Bind> {
    skills
        .iter()
        .map(|skill| Bind {
            host: skill.path.clone(),
            container: container_path(runtime, &skill.name),
            read_only: true,
        })
        .collect()
}

/// The LOG line of the skills a run got: names and versions, e.g.
/// `2 skills in /home/agent/.claude/skills: pdf v1, release-notes v3 (1 from the cache)`.
pub fn summary(runtime: Runtime, skills: &[ProvidedSkill]) -> String {
    let listed: Vec<String> = skills
        .iter()
        .map(|s| format!("{} v{}", s.name, s.version))
        .collect();
    let cached = skills.iter().filter(|s| s.cached).count();
    format!(
        "{} skill{} in {}/{}: {}{}",
        skills.len(),
        if skills.len() == 1 { "" } else { "s" },
        crate::sandbox::HOME,
        mount_dir(runtime),
        listed.join(", "),
        if cached > 0 {
            format!(" ({cached} from the cache)")
        } else {
            String::new()
        }
    )
}

// ---------------------------------------------------------------------------------------
// The cache
// ---------------------------------------------------------------------------------------

type Active = Arc<Mutex<HashMap<PathBuf, usize>>>;

fn lock(active: &Active) -> std::sync::MutexGuard<'_, HashMap<PathBuf, usize>> {
    active.lock().unwrap_or_else(|e| e.into_inner())
}

/// The unpacked skills of this host (`<data>/skills`), and which of them runs hold.
#[derive(Debug, Clone)]
pub struct SkillCache {
    dir: PathBuf,
    active: Active,
}

/// An entry of the cache in use by a run. Dropping it releases the entry.
#[derive(Debug)]
pub struct SkillLease {
    active: Active,
    path: PathBuf,
}

impl SkillLease {
    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for SkillLease {
    fn drop(&mut self) {
        let mut active = lock(&self.active);
        if let Some(count) = active.get_mut(&self.path) {
            *count -= 1;
            if *count == 0 {
                active.remove(&self.path);
            }
        }
    }
}

/// An entry on disk.
#[derive(Debug, Clone)]
pub struct CachedSkill {
    pub sha256: String,
    pub name: String,
    pub path: PathBuf,
    /// `None` when the entry has no (readable) stamp.
    pub last_used: Option<DateTime<Utc>>,
}

/// An entry that maintenance deleted (or would delete, in a dry run).
#[derive(Debug, Clone)]
pub struct DeletedSkill {
    pub sha256: String,
    pub name: String,
    pub bytes: u64,
    pub idle_days: i64,
}

/// What a pass over the cache did.
#[derive(Debug, Default)]
pub struct SkillCacheReport {
    pub deleted: Vec<DeletedSkill>,
    /// Idle entries kept because a run holds them.
    pub active: usize,
    pub kept: usize,
    pub failed: Vec<(PathBuf, String)>,
}

fn stamp_path(entry: &Path) -> PathBuf {
    let mut name = entry.file_name().unwrap_or_default().to_os_string();
    name.push(STAMP_SUFFIX);
    entry.with_file_name(name)
}

/// A directory, not a link to one.
fn is_real_dir(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok_and(|m| m.is_dir())
}

/// Sub-directories of `dir` (never links) whose names pass `wanted`, by name.
fn subdirs(dir: &Path, wanted: impl Fn(&str) -> bool) -> Vec<(String, PathBuf)> {
    let Ok(entries) = fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut dirs: Vec<(String, PathBuf)> = entries
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
        .map(|e| (e.file_name().to_string_lossy().into_owned(), e.path()))
        .filter(|(name, _)| wanted(name))
        .collect();
    dirs.sort();
    dirs
}

fn set_mode(path: &Path, mode: u32) -> std::io::Result<()> {
    fs::set_permissions(path, Permissions::from_mode(mode))
}

/// Creates the directory `path` with `mode` (whatever the umask), its parent being there.
fn create_dir(path: &Path, mode: u32) -> std::io::Result<()> {
    DirBuilder::new().mode(mode).create(path)?;
    set_mode(path, mode)
}

impl SkillCache {
    pub fn new(dir: impl Into<PathBuf>) -> SkillCache {
        SkillCache {
            dir: dir.into(),
            active: Arc::default(),
        }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// `<data>/skills/<sha256>/<name>` ([`crate::paths::Paths::skill`]).
    pub fn entry(&self, sha256: &str, name: &str) -> PathBuf {
        self.dir.join(sha256).join(name)
    }

    /// Marks the entry of `sha256` and `name` as in use until the lease is dropped, and
    /// records the time. Maintenance never deletes a leased entry; one that it deleted just
    /// before is unpacked again by the run.
    pub fn lease(&self, sha256: &str, name: &str) -> SkillLease {
        let path = self.entry(sha256, name);
        *lock(&self.active).entry(path.clone()).or_default() += 1;
        if let Err(e) = self.record_use(&path, Utc::now()) {
            tracing::warn!(skill = %path.display(), error = %format!("{e:#}"), "cannot record the use of a skill");
        }
        SkillLease {
            active: self.active.clone(),
            path,
        }
    }

    /// Records that a run used `entry` at `at`.
    pub fn record_use(&self, entry: &Path, at: DateTime<Utc>) -> anyhow::Result<()> {
        let stamp = stamp_path(entry);
        if let Some(parent) = stamp.parent() {
            self.ensure_hash_dir(parent)?;
        }
        fs::write(&stamp, at.to_rfc3339())
            .with_context(|| format!("cannot write {}", stamp.display()))
    }

    /// When a run last used `entry`; `None` without a readable stamp.
    pub fn last_used(&self, entry: &Path) -> Option<DateTime<Utc>> {
        let text = fs::read_to_string(stamp_path(entry)).ok()?;
        DateTime::parse_from_rfc3339(text.trim())
            .ok()
            .map(|t| t.with_timezone(&Utc))
    }

    /// `true` while a run holds `entry`.
    pub fn is_active(&self, entry: &Path) -> bool {
        lock(&self.active).contains_key(entry)
    }

    /// `<data>/skills` (0700) and the directory of one hash in it (0700), never links.
    fn ensure_hash_dir(&self, hash_dir: &Path) -> anyhow::Result<()> {
        crate::secure_fs::ensure_private_dir(&self.dir)?;
        match fs::symlink_metadata(hash_dir) {
            Ok(meta) if meta.is_dir() => Ok(()),
            Ok(_) => bail!("{} is not a directory", hash_dir.display()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                match create_dir(hash_dir, HASH_DIR_MODE) {
                    Ok(()) => Ok(()),
                    // Another run created it meanwhile.
                    Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
                        if is_real_dir(hash_dir) {
                            Ok(())
                        } else {
                            bail!("{} is not a directory", hash_dir.display())
                        }
                    }
                    Err(e) => {
                        Err(e).with_context(|| format!("cannot create {}", hash_dir.display()))
                    }
                }
            }
            Err(e) => Err(e).with_context(|| format!("cannot inspect {}", hash_dir.display())),
        }
    }

    /// Unpacks `zip` into a new directory and renames it to `entry`. When another run put the
    /// entry there meanwhile, that one stays.
    pub fn install(&self, zip: &[u8], entry: &Path) -> anyhow::Result<()> {
        let hash_dir = entry.parent().context("a cache entry has a parent")?;
        self.ensure_hash_dir(hash_dir)?;
        let temp = self
            .dir
            .join(format!("{UNPACK_PREFIX}{}", Uuid::new_v4().simple()));
        create_dir(&temp, DIR_MODE).with_context(|| format!("cannot create {}", temp.display()))?;
        let result = unpack(zip, &temp)
            .map_err(anyhow::Error::msg)
            .and_then(|_| match fs::rename(&temp, entry) {
                Ok(()) => Ok(()),
                Err(_) if is_real_dir(entry) => Ok(()),
                Err(e) => Err(e).context("cannot move the unpacked skill into the cache"),
            });
        if temp.exists() {
            let _ = fs::remove_dir_all(&temp);
        }
        result
    }

    /// The entries on disk, by hash and name.
    pub fn list(&self) -> Vec<CachedSkill> {
        let mut entries = Vec::new();
        for (sha256, hash_dir) in subdirs(&self.dir, is_sha256) {
            for (name, path) in subdirs(&hash_dir, is_valid_skill_name) {
                entries.push(CachedSkill {
                    last_used: self.last_used(&path),
                    sha256: sha256.clone(),
                    name,
                    path,
                });
            }
        }
        entries
    }

    /// Removes what a deletion left behind and, at `startup` (when no run unpacks), what an
    /// unpack left behind.
    pub fn remove_stale_temp_dirs(&self, startup: bool) {
        let Ok(entries) = fs::read_dir(&self.dir) else {
            return;
        };
        for entry in entries.filter_map(|e| e.ok()) {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with(DELETE_PREFIX) || (startup && name.starts_with(UNPACK_PREFIX)) {
                let path = entry.path();
                if let Err(e) = fs::remove_dir_all(&path) {
                    tracing::warn!(path = %path.display(), error = %e, "cannot remove a deleted skill");
                }
            }
        }
    }

    /// Deletes the entries that no run used for longer than `max_idle` before `now`, except
    /// those a run holds. An entry without a stamp gets one with `now`, so its idle time
    /// starts now. `dry_run` only reports.
    pub fn prune(
        &self,
        max_idle: TimeDelta,
        now: DateTime<Utc>,
        dry_run: bool,
    ) -> SkillCacheReport {
        let mut report = SkillCacheReport::default();
        if !dry_run {
            self.remove_stale_temp_dirs(false);
        }
        for entry in self.list() {
            let Some(last_used) = entry.last_used else {
                if !dry_run && let Err(e) = self.record_use(&entry.path, now) {
                    tracing::warn!(skill = %entry.path.display(), error = %format!("{e:#}"), "cannot record the use of a skill");
                }
                report.kept += 1;
                continue;
            };
            if now - last_used <= max_idle {
                report.kept += 1;
                continue;
            }
            let bytes = dir_size(&entry.path);
            let deleted = |idle_days| DeletedSkill {
                sha256: entry.sha256.clone(),
                name: entry.name.clone(),
                bytes,
                idle_days,
            };
            let retired = {
                // Checked and renamed under the lease registry's lock: a run that leases the
                // entry after this unpacks it again.
                let active = lock(&self.active);
                if active.contains_key(&entry.path) {
                    report.active += 1;
                    continue;
                }
                // A run may have used it since the listing.
                let last_used = self.last_used(&entry.path).unwrap_or(last_used);
                if now - last_used <= max_idle {
                    report.kept += 1;
                    continue;
                }
                let idle_days = (now - last_used).num_days();
                if dry_run {
                    report.deleted.push(deleted(idle_days));
                    continue;
                }
                let target = self
                    .dir
                    .join(format!("{DELETE_PREFIX}{}", Uuid::new_v4().simple()));
                if let Err(e) = fs::rename(&entry.path, &target) {
                    tracing::warn!(skill = %entry.path.display(), error = %e, "cannot delete a cached skill");
                    report.failed.push((entry.path.clone(), e.to_string()));
                    continue;
                }
                let _ = fs::remove_file(stamp_path(&entry.path));
                (target, idle_days)
            };
            let (target, idle_days) = retired;
            if let Err(e) = fs::remove_dir_all(&target) {
                // The rename took it out of use; the next pass tries the removal again.
                tracing::warn!(path = %target.display(), error = %e, "cannot remove a deleted skill");
            }
            tracing::info!(
                sha256 = %entry.sha256,
                skill = %entry.name,
                freed_bytes = bytes,
                idle_days,
                "deleted an idle cached skill"
            );
            report.deleted.push(deleted(idle_days));
        }
        if !dry_run {
            self.remove_orphans();
        }
        report
    }

    /// Removes stamps without an entry and hash directories without entries, unless a run
    /// holds an entry there.
    fn remove_orphans(&self) {
        let active = lock(&self.active);
        for (_, hash_dir) in subdirs(&self.dir, is_sha256) {
            if active
                .keys()
                .any(|entry| entry.parent() == Some(hash_dir.as_path()))
            {
                continue;
            }
            let Ok(entries) = fs::read_dir(&hash_dir) else {
                continue;
            };
            for entry in entries.filter_map(|e| e.ok()) {
                let name = entry.file_name().to_string_lossy().into_owned();
                if let Some(skill) = name.strip_suffix(STAMP_SUFFIX)
                    && !hash_dir.join(skill).exists()
                {
                    let _ = fs::remove_file(entry.path());
                }
            }
            // Fails (and is left alone) while anything is in it.
            let _ = fs::remove_dir(&hash_dir);
        }
    }
}

// ---------------------------------------------------------------------------------------
// Providing the skills of a run
// ---------------------------------------------------------------------------------------

/// A skill of the run, in the cache and ready to mount. It holds its entry until dropped.
#[derive(Debug)]
pub struct ProvidedSkill {
    pub name: String,
    pub version: u32,
    /// `<data>/skills/<sha256>/<name>`.
    pub path: PathBuf,
    /// Found in the cache: nothing was downloaded.
    pub cached: bool,
    _lease: SkillLease,
}

#[cfg(test)]
impl ProvidedSkill {
    /// The skill `name` of `cache` as [`provide`] hands it out, for the tests of a sandbox.
    pub(crate) fn leased(cache: &SkillCache, sha256: &str, name: &str, version: u32) -> Self {
        let lease = cache.lease(sha256, name);
        ProvidedSkill {
            name: name.into(),
            version,
            path: lease.path().to_path_buf(),
            cached: true,
            _lease: lease,
        }
    }
}

/// Provides every skill of the assignment from the cache, downloading and unpacking those it
/// does not hold. `Err` is the message of the run's INFRA failure, which names the skill.
pub async fn provide(
    cache: &SkillCache,
    server_url: &str,
    host_token: &str,
    assign: &RunAssign,
) -> Result<Vec<ProvidedSkill>, String> {
    let mut provided = Vec::new();
    for skill in &assign.skills {
        let lease = cache.lease(&skill.sha256, &skill.name);
        let path = lease.path().to_path_buf();
        let cached = is_real_dir(&path);
        if !cached {
            fetch(cache, server_url, host_token, assign, skill, &path)
                .await
                .map_err(|e| {
                    format!(
                        "cannot provide skill `{}` (version {}): {e:#}",
                        skill.name, skill.version
                    )
                })?;
        }
        provided.push(ProvidedSkill {
            name: skill.name.clone(),
            version: skill.version,
            path,
            cached,
            _lease: lease,
        });
    }
    Ok(provided)
}

/// Downloads, checks and unpacks one skill into its entry.
async fn fetch(
    cache: &SkillCache,
    server_url: &str,
    host_token: &str,
    assign: &RunAssign,
    skill: &AssignedSkill,
    entry: &Path,
) -> anyhow::Result<()> {
    let zip = download(server_url, host_token, assign.run_id, assign.epoch, skill).await?;
    let cache = cache.clone();
    let entry = entry.to_path_buf();
    tokio::task::spawn_blocking(move || cache.install(&zip, &entry))
        .await
        .map_err(|e| anyhow!("the unpack stopped: {e}"))?
}

/// `GET /api/runner/v1/runs/{runId}/skills/{skillId}/versions/{version}?epoch=` with the host
/// token: the zip, checked against `sizeBytes` and `sha256` while it is read.
pub async fn download(
    server_url: &str,
    host_token: &str,
    run_id: Uuid,
    epoch: i64,
    skill: &AssignedSkill,
) -> anyhow::Result<Vec<u8>> {
    let base = server_url.trim_end_matches('/');
    let url = format!(
        "{base}/api/runner/v1/runs/{run_id}/skills/{}/versions/{}?epoch={epoch}",
        skill.skill_id, skill.version
    );
    let response = attachments::get(&url, host_token)
        .await
        .context("the download failed")?;
    if !response.status().is_success() {
        bail!("the download failed: HTTP {}", response.status());
    }
    let expected = skill.size_bytes.min(SKILL_MAX_BYTES);
    let mut zip = Vec::with_capacity(expected as usize);
    let mut digest = ring::digest::Context::new(&ring::digest::SHA256);
    let mut body = response.into_body();
    loop {
        let frame = tokio::time::timeout(attachments::CHUNK_TIMEOUT, body.frame())
            .await
            .map_err(|_| anyhow!("the server stopped sending"))
            .and_then(|frame| frame.transpose().map_err(anyhow::Error::from))
            .context("the download failed")?;
        let Some(frame) = frame else { break };
        let Ok(chunk) = frame.into_data() else {
            continue;
        };
        if zip.len() as u64 + chunk.len() as u64 > expected {
            bail!("the zip is larger than its {} bytes", skill.size_bytes);
        }
        digest.update(&chunk);
        zip.extend_from_slice(&chunk);
    }
    if zip.len() as u64 != skill.size_bytes {
        bail!("the zip has {} bytes, not {}", zip.len(), skill.size_bytes);
    }
    if attachments::hex(digest.finish().as_ref()) != skill.sha256 {
        bail!("the zip does not match its sha256");
    }
    Ok(zip)
}

// ---------------------------------------------------------------------------------------
// Unpacking
// ---------------------------------------------------------------------------------------

/// What [`unpack`] wrote.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Unpacked {
    pub files: usize,
    pub bytes: u64,
}

/// Unpacks the zip `zip` into the empty directory `dir`, or says why it refuses (see the
/// module docs). On a refusal `dir` may hold part of the files; the caller removes it.
pub fn unpack(zip: &[u8], dir: &Path) -> Result<Unpacked, String> {
    let mut archive = zip::ZipArchive::new(std::io::Cursor::new(zip))
        .map_err(|e| format!("not a readable zip: {e}"))?;
    if archive.len() > MAX_ENTRIES {
        return Err(format!(
            "the zip has {} entries, more than {MAX_ENTRIES}",
            archive.len()
        ));
    }
    // Lower-case paths of the files and of the folders they lie in.
    let mut files: HashSet<String> = HashSet::new();
    let mut folders: HashSet<String> = HashSet::new();
    let mut unpacked = Unpacked::default();
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|e| format!("entry {index} of the zip cannot be read: {e}"))?;
        let name = std::str::from_utf8(entry.name_raw())
            .map_err(|_| format!("entry {index} of the zip has a path that is not UTF-8"))?
            .to_string();
        let is_dir = name.ends_with('/');
        let path = name.strip_suffix('/').unwrap_or(&name);
        check_path(path).map_err(|why| format!("`{}` {why}", shown(&name)))?;
        let kind = entry.unix_mode().map_or(0, |mode| mode & S_IFMT);
        match kind {
            0 => {}
            S_IFREG if !is_dir => {}
            S_IFDIR if is_dir => {}
            S_IFLNK => return Err(format!("`{}` is a link", shown(&name))),
            S_IFREG | S_IFDIR => {
                return Err(format!("`{}` is a folder and a file at once", shown(&name)));
            }
            _ => {
                return Err(format!(
                    "`{}` is a device or another special file",
                    shown(&name)
                ));
            }
        }
        if is_dir {
            // A folder is made for the files in it.
            continue;
        }
        unpacked.files += 1;
        if unpacked.files > MAX_FILES {
            return Err(format!("the zip has more than {MAX_FILES} files"));
        }
        // No two files that differ in case only, and no file that is also a folder.
        let lower = path.to_lowercase();
        if files.contains(&lower) || folders.contains(&lower) {
            return Err(format!(
                "`{}` is in the zip twice, or also as a folder",
                shown(&name)
            ));
        }
        let segments: Vec<&str> = path.split('/').collect();
        let lower_segments: Vec<&str> = lower.split('/').collect();
        for end in 1..lower_segments.len() {
            let folder = lower_segments[..end].join("/");
            if files.contains(&folder) {
                return Err(format!("`{}` lies in a file", shown(&name)));
            }
            folders.insert(folder);
        }
        files.insert(lower);
        // The folders of the file, each a directory this function made (`dir` is new).
        let mut target = dir.to_path_buf();
        for segment in &segments[..segments.len() - 1] {
            target.push(segment);
            if !is_real_dir(&target) {
                create_dir(&target, DIR_MODE)
                    .map_err(|e| format!("cannot create `{}`: {e}", shown(&name)))?;
            }
        }
        target.push(segments[segments.len() - 1]);
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(FILE_MODE)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&target)
            .map_err(|e| format!("cannot create `{}`: {e}", shown(&name)))?;
        // At most one byte past what is left: the bytes that come out count, not the claims.
        let left = MAX_UNPACKED_BYTES - unpacked.bytes;
        let written = std::io::copy(&mut (&mut entry).take(left + 1), &mut file)
            .map_err(|e| format!("`{}` cannot be unpacked: {e}", shown(&name)))?;
        unpacked.bytes += written;
        if unpacked.bytes > MAX_UNPACKED_BYTES {
            return Err(format!(
                "the files have more than {MAX_UNPACKED_BYTES} bytes uncompressed"
            ));
        }
        set_mode(&target, FILE_MODE)
            .map_err(|e| format!("cannot set the mode of `{}`: {e}", shown(&name)))?;
    }
    Ok(unpacked)
}

/// The path rules of an upload (the contract, §4.2): relative, `/`-separated, 1 to 8
/// segments of 1 to 255 bytes without control characters, `\` or `:`, never `.` or `..`, at
/// most 1,024 bytes. `Err` completes "`<path>` …".
fn check_path(path: &str) -> Result<(), &'static str> {
    if path.is_empty() {
        return Err("has an empty path");
    }
    if path.starts_with('/') {
        return Err("is an absolute path");
    }
    if path.contains('\\') {
        return Err("has a backslash");
    }
    if path.len() > MAX_PATH_BYTES {
        return Err("is a path longer than 1,024 bytes");
    }
    let segments: Vec<&str> = path.split('/').collect();
    if segments.len() > MAX_PATH_SEGMENTS {
        return Err("is a path of more than 8 segments");
    }
    for segment in segments {
        match segment {
            "" => return Err("has an empty segment"),
            "." => return Err("has a segment `.`"),
            ".." => return Err("leaves the skill's folder (`..`)"),
            _ if segment.len() > MAX_SEGMENT_BYTES => {
                return Err("has a segment longer than 255 bytes");
            }
            _ if segment.contains(':') => return Err("has a `:`"),
            _ if segment.chars().any(char::is_control) => {
                return Err("has a control character");
            }
            _ => {}
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------------------
// Mount points in the session home
// ---------------------------------------------------------------------------------------

/// The two directories of the home that hold mount points of skills.
const MOUNT_TOPS: [&str; 2] = [".claude", ".agents"];

/// Removes, in `~/.claude/skills` and `~/.agents/skills`, each mount point that the host
/// created for the previous run of `home` and that is still an empty directory, then the
/// record of them. Never through a link: a component that is not a plain directory leaves
/// that mount point alone.
pub fn remove_earlier_mount_points(home: &Path) {
    let record = crate::homes::skills_record_path(home);
    let Ok(text) = fs::read_to_string(&record) else {
        return;
    };
    for line in text.lines() {
        let Some((top, name)) = parse_record_line(line) else {
            continue;
        };
        let skills_dir = home.join(top).join("skills");
        if !(is_real_dir(home) && is_real_dir(&home.join(top)) && is_real_dir(&skills_dir)) {
            continue;
        }
        let mount_point = skills_dir.join(name);
        if is_real_dir(&mount_point) {
            // Fails, and leaves it, unless it is empty.
            let _ = fs::remove_dir(&mount_point);
        }
    }
    if let Err(e) = fs::remove_file(&record)
        && e.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(path = %record.display(), error = %e, "cannot remove the record of the skills' mount points");
    }
}

/// A line of the record: `.claude/skills/<name>` or `.agents/skills/<name>`.
fn parse_record_line(line: &str) -> Option<(&'static str, &str)> {
    let mut parts = line.trim().split('/');
    let top = parts.next()?;
    let top = MOUNT_TOPS.into_iter().find(|t| *t == top)?;
    if parts.next()? != "skills" {
        return None;
    }
    let name = parts.next()?;
    (parts.next().is_none() && is_valid_skill_name(name)).then_some((top, name))
}

/// Creates the mount point of each skill in `home`, at the runtime's skill directory, through
/// plain directories only: each component of `<.claude|.agents>/skills/<name>` must be a
/// directory, never a link or a file, and is created `0755` when it is missing. It records the
/// mount points next to the home first, so that the next run of the home removes them while
/// they are empty ([`remove_earlier_mount_points`]). `Err` names the skill and the component.
pub fn create_mount_points(home: &Path, runtime: Runtime, names: &[&str]) -> Result<(), String> {
    let relative: Vec<String> = names
        .iter()
        .map(|name| format!("{}/{name}", mount_dir(runtime)))
        .collect();
    let record = crate::homes::skills_record_path(home);
    let mut text = relative.join("\n");
    text.push('\n');
    fs::write(&record, text).map_err(|e| {
        format!(
            "cannot record the skills' mount points in {}: {e}",
            record.display()
        )
    })?;
    if !is_real_dir(home) {
        return Err("the session home is not a directory".into());
    }
    for (name, relative) in names.iter().zip(&relative) {
        let mut path = home.to_path_buf();
        let mut shown_path = String::from("~");
        for component in relative.split('/') {
            path.push(component);
            shown_path.push('/');
            shown_path.push_str(component);
            plain_dir(&path, &shown_path).map_err(|why| {
                format!(
                    "cannot create the mount point of skill `{name}` in the session home: {why}"
                )
            })?;
        }
    }
    Ok(())
}

/// `path` is a directory, or is created as one (`0755`); never a link or a file.
fn plain_dir(path: &Path, shown_path: &str) -> Result<(), String> {
    match fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_symlink() => Err(format!("`{shown_path}` is a link")),
        Ok(meta) if meta.is_dir() => Ok(()),
        Ok(_) => Err(format!("`{shown_path}` is not a directory")),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            create_dir(path, DIR_MODE).map_err(|e| format!("cannot create `{shown_path}`: {e}"))?;
            if is_real_dir(path) {
                Ok(())
            } else {
                Err(format!("`{shown_path}` changed while it was created"))
            }
        }
        Err(e) => Err(format!("cannot inspect `{shown_path}`: {e}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};
    use std::os::unix::fs::symlink;
    use zip::write::SimpleFileOptions;
    use zip::{CompressionMethod, ZipWriter};

    const SHA: &str = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    fn skill(name: &str) -> AssignedSkill {
        AssignedSkill {
            skill_id: Uuid::new_v4(),
            version: 1,
            name: name.into(),
            sha256: SHA.into(),
            size_bytes: 100,
        }
    }

    fn deflated() -> SimpleFileOptions {
        SimpleFileOptions::default().compression_method(CompressionMethod::Deflated)
    }

    /// A zip of `files`, deflated, as the server's canonical zip is.
    fn zip_of(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
        for (name, content) in files {
            writer.start_file(*name, deflated()).unwrap();
            writer.write_all(content).unwrap();
        }
        writer.finish().unwrap().into_inner()
    }

    fn skill_md(name: &str) -> Vec<u8> {
        format!("---\nname: {name}\ndescription: Test skill.\n---\nDo it.\n").into_bytes()
    }

    fn unpack_into(zip: &[u8]) -> (tempfile::TempDir, Result<Unpacked, String>) {
        let temp = tempfile::tempdir().unwrap();
        let result = unpack(zip, temp.path());
        (temp, result)
    }

    fn refusal(zip: &[u8]) -> String {
        unpack_into(zip).1.unwrap_err()
    }

    #[test]
    fn validates_the_assignment() {
        assert!(validate(&[]).is_ok());
        assert!(validate(&[skill("pdf"), skill("release-notes")]).is_ok());
        let err = validate(&[skill("pdf"), skill("pdf")]).unwrap_err();
        assert!(err.contains("appears twice"), "{err}");
        for bad in ["", "PDF", "a/b", "..", "-a", "a--b", "a_b", &"a".repeat(65)] {
            let err = validate(&[skill(bad)]).unwrap_err();
            assert!(err.contains("is not a skill name"), "{bad:?}: {err}");
        }
        // A long bad name is cut in the message.
        let err = validate(&[skill(&"X".repeat(5000))]).unwrap_err();
        assert!(err.len() < 400, "{err}");
        let mut no_version = skill("pdf");
        no_version.version = 0;
        assert!(
            validate(&[no_version])
                .unwrap_err()
                .contains("no valid version")
        );
        for sha in [
            "A".repeat(64),
            "a".repeat(63),
            "g".repeat(64),
            String::new(),
        ] {
            let mut bad_hash = skill("pdf");
            bad_hash.sha256 = sha;
            assert!(
                validate(&[bad_hash])
                    .unwrap_err()
                    .contains("invalid sha256")
            );
        }
        for size in [0, SKILL_MAX_BYTES + 1] {
            let mut bad_size = skill("pdf");
            bad_size.size_bytes = size;
            assert!(validate(&[bad_size]).unwrap_err().contains("bytes"));
        }
        let mut at_limit = skill("pdf");
        at_limit.size_bytes = SKILL_MAX_BYTES;
        assert!(validate(&[at_limit]).is_ok());
        let many: Vec<AssignedSkill> = (0..MAX_SKILLS + 1)
            .map(|i| skill(&format!("skill-{i}")))
            .collect();
        assert!(validate(&many[..MAX_SKILLS]).is_ok());
        assert!(validate(&many).unwrap_err().contains("more than 20"));
    }

    #[test]
    fn unpacks_files_with_fixed_modes() {
        let zip = zip_of(&[
            ("SKILL.md", &skill_md("pdf")),
            ("scripts/fill.py", b"print('x')\n"),
            ("reference/forms/a.md", b"A"),
        ]);
        let (temp, result) = unpack_into(&zip);
        assert_eq!(
            result.unwrap(),
            Unpacked {
                files: 3,
                bytes: (skill_md("pdf").len() + 11 + 1) as u64
            }
        );
        let dir = temp.path();
        assert_eq!(fs::read(dir.join("SKILL.md")).unwrap(), skill_md("pdf"));
        assert_eq!(
            fs::read(dir.join("scripts/fill.py")).unwrap(),
            b"print('x')\n"
        );
        let mode = |p: &str| {
            fs::symlink_metadata(dir.join(p))
                .unwrap()
                .permissions()
                .mode()
                & 0o7777
        };
        assert_eq!(mode("SKILL.md"), 0o644);
        assert_eq!(mode("scripts/fill.py"), 0o644);
        assert_eq!(mode("scripts"), 0o755);
        assert_eq!(mode("reference/forms"), 0o755);
    }

    #[test]
    fn an_executable_or_setuid_mode_in_the_zip_is_not_kept() {
        let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
        writer
            .start_file(
                "run.sh",
                deflated()
                    .system(zip::System::Unix)
                    .external_attributes(0o104777 << 16),
            )
            .unwrap();
        writer.write_all(b"#!/bin/sh\n").unwrap();
        let zip = writer.finish().unwrap().into_inner();
        let (temp, result) = unpack_into(&zip);
        result.unwrap();
        let mode = fs::metadata(temp.path().join("run.sh"))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o7777, 0o644);
    }

    #[test]
    fn refuses_links() {
        let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
        writer.start_file("SKILL.md", deflated()).unwrap();
        writer.write_all(&skill_md("pdf")).unwrap();
        writer
            .add_symlink("secrets", "/home/agent/.ssh/id_ed25519", deflated())
            .unwrap();
        let zip = writer.finish().unwrap().into_inner();
        let (temp, result) = unpack_into(&zip);
        let err = result.unwrap_err();
        assert_eq!(err, "`secrets` is a link");
        // Nothing was written for it, as a link or as a file.
        assert!(fs::symlink_metadata(temp.path().join("secrets")).is_err());
    }

    #[test]
    fn refuses_devices_and_other_special_files() {
        for (kind, what) in [
            (0o020000, "a character device"),
            (0o060000, "a block device"),
            (0o010000, "a FIFO"),
            (0o140000, "a socket"),
        ] {
            let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
            writer
                .start_file(
                    "dev",
                    SimpleFileOptions::default()
                        .compression_method(CompressionMethod::Stored)
                        .system(zip::System::Unix)
                        .external_attributes((kind | 0o644) << 16),
                )
                .unwrap();
            let zip = writer.finish().unwrap().into_inner();
            assert_eq!(
                refusal(&zip),
                "`dev` is a device or another special file",
                "{what}"
            );
        }
    }

    #[test]
    fn refuses_paths_that_leave_the_folder_or_break_the_rules() {
        let cases: Vec<(String, &str)> = vec![
            ("../evil.sh".into(), "leaves the skill's folder (`..`)"),
            (
                "scripts/../../evil.sh".into(),
                "leaves the skill's folder (`..`)",
            ),
            ("/etc/cron.d/evil".into(), "is an absolute path"),
            ("scripts\\evil.bat".into(), "has a backslash"),
            ("C:/evil".into(), "has a `:`"),
            ("./SKILL.md".into(), "has a segment `.`"),
            ("a//b".into(), "has an empty segment"),
            ("bell\u{7}.md".into(), "has a control character"),
            (
                "a/b/c/d/e/f/g/h/i.md".into(),
                "is a path of more than 8 segments",
            ),
            (
                format!("{}.md", "x".repeat(256)),
                "has a segment longer than 255 bytes",
            ),
            (
                (0..5)
                    .map(|_| "x".repeat(250))
                    .collect::<Vec<_>>()
                    .join("/"),
                "is a path longer than 1,024 bytes",
            ),
        ];
        for (path, why) in cases {
            let zip = zip_of(&[("SKILL.md", &skill_md("pdf")), (path.as_str(), b"x")]);
            let err = refusal(&zip);
            assert!(err.ends_with(why), "{path:?}: {err}");
        }
        // A folder entry with `..` is refused as well, though folders are skipped.
        let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
        writer.add_directory("../up/", deflated()).unwrap();
        let zip = writer.finish().unwrap().into_inner();
        assert!(refusal(&zip).contains("leaves the skill's folder"));
        // Paths at the limits are fine.
        let deep = "a/b/c/d/e/f/g/h.md";
        let long_segment = "x".repeat(255);
        let zip = zip_of(&[(deep, b"x"), (long_segment.as_str(), b"y")]);
        let (temp, result) = unpack_into(&zip);
        assert_eq!(result.unwrap().files, 2);
        assert!(temp.path().join(deep).is_file());
    }

    #[test]
    fn refuses_files_that_collide() {
        let err = refusal(&zip_of(&[("SKILL.md", b"a"), ("skill.md", b"b")]));
        assert!(err.contains("twice"), "{err}");
        let err = refusal(&zip_of(&[("docs", b"a"), ("docs/a.md", b"b")]));
        assert!(err.contains("lies in a file"), "{err}");
        let err = refusal(&zip_of(&[("Docs/a.md", b"a"), ("docs", b"b")]));
        assert!(err.contains("also as a folder"), "{err}");
    }

    #[test]
    fn refuses_more_than_200_files() {
        let names: Vec<String> = (0..=MAX_FILES).map(|i| format!("f{i}.md")).collect();
        let files: Vec<(&str, &[u8])> = names.iter().map(|n| (n.as_str(), &b"x"[..])).collect();
        assert_eq!(refusal(&zip_of(&files)), "the zip has more than 200 files");
        let (_, result) = unpack_into(&zip_of(&files[..MAX_FILES]));
        assert_eq!(result.unwrap().files, MAX_FILES);
        // Folder entries do not count as files.
        let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
        for i in 0..10 {
            writer.add_directory(format!("d{i}/"), deflated()).unwrap();
        }
        for (name, content) in &files[..MAX_FILES] {
            writer.start_file(*name, deflated()).unwrap();
            writer.write_all(content).unwrap();
        }
        let zip = writer.finish().unwrap().into_inner();
        assert_eq!(unpack_into(&zip).1.unwrap().files, MAX_FILES);
    }

    #[test]
    fn refuses_more_than_5_mib_in_all() {
        let three_mib = vec![0u8; 3 * 1024 * 1024];
        let zip = zip_of(&[("a.bin", &three_mib), ("b.bin", &three_mib)]);
        // Zeros deflate well: the zip is small, the files are not.
        assert!(zip.len() < 64 * 1024, "{}", zip.len());
        let (temp, result) = unpack_into(&zip);
        assert_eq!(
            result.unwrap_err(),
            "the files have more than 5242880 bytes uncompressed"
        );
        // It stopped one byte past the limit.
        let written = fs::metadata(temp.path().join("b.bin")).unwrap().len();
        assert_eq!(written, (MAX_UNPACKED_BYTES + 1) - three_mib.len() as u64);
        let exactly = vec![0u8; MAX_UNPACKED_BYTES as usize];
        assert!(unpack_into(&zip_of(&[("a.bin", &exactly)])).1.is_ok());
    }

    /// Sets the uncompressed size of every entry, in the local headers and the central
    /// directory, to `size`: a zip that lies about its sizes.
    fn claim_size(zip: &mut [u8], size: u32) {
        let mut i = 0;
        while i + 4 <= zip.len() {
            let at = |offset: usize| offset..offset + 4;
            match zip[i..i + 4] {
                [0x50, 0x4b, 0x03, 0x04] => zip[at(i + 22)].copy_from_slice(&size.to_le_bytes()),
                [0x50, 0x4b, 0x01, 0x02] => zip[at(i + 24)].copy_from_slice(&size.to_le_bytes()),
                _ => {}
            }
            i += 1;
        }
    }

    #[test]
    fn counts_the_bytes_that_come_out_not_the_ones_the_zip_claims() {
        // Claims 2 bytes in all, holds 6 MiB: refused as the bytes come out (the zip reader
        // stops too, past a file's declared size).
        let six_mib = vec![b'a'; 6 * 1024 * 1024];
        let mut zip = zip_of(&[("SKILL.md", b"x"), ("big.txt", &six_mib)]);
        claim_size(&mut zip, 1);
        let reread = zip::ZipArchive::new(Cursor::new(&zip[..])).unwrap();
        assert_eq!(
            reread.decompressed_size(),
            Some(2),
            "the zip claims 2 bytes"
        );
        let (temp, result) = unpack_into(&zip);
        let err = result.unwrap_err();
        assert!(err.starts_with("`big.txt` cannot be unpacked: "), "{err}");
        let written = fs::metadata(temp.path().join("big.txt")).map_or(0, |m| m.len());
        assert!(written <= MAX_UNPACKED_BYTES + 1, "{written}");
        // Claims 6 MiB, holds 1 byte: the claim refuses nothing.
        let mut zip = zip_of(&[("SKILL.md", b"x")]);
        claim_size(&mut zip, 6 * 1024 * 1024);
        assert_eq!(
            unpack_into(&zip).1.unwrap(),
            Unpacked { files: 1, bytes: 1 }
        );
    }

    #[test]
    fn refuses_what_is_not_a_zip_or_is_damaged() {
        assert!(refusal(b"not a zip at all").starts_with("not a readable zip"));
        // A damaged file: its checksum no longer matches.
        let mut writer = ZipWriter::new(Cursor::new(Vec::new()));
        writer
            .start_file(
                "SKILL.md",
                SimpleFileOptions::default().compression_method(CompressionMethod::Stored),
            )
            .unwrap();
        writer.write_all(b"hello, world").unwrap();
        let mut zip = writer.finish().unwrap().into_inner();
        let at = zip.windows(12).position(|w| w == b"hello, world").unwrap();
        zip[at] ^= 0x01;
        let err = refusal(&zip);
        assert!(err.starts_with("`SKILL.md` cannot be unpacked: "), "{err}");
    }

    #[test]
    fn mounts_at_the_skill_directory_of_each_runtime() {
        assert_eq!(
            container_path(Runtime::ClaudeCode, "pdf"),
            "/home/agent/.claude/skills/pdf"
        );
        for runtime in [Runtime::Codex, Runtime::Opencode, Runtime::Pi] {
            assert_eq!(
                container_path(runtime, "pdf"),
                "/home/agent/.agents/skills/pdf",
                "{runtime}"
            );
        }
        let temp = tempfile::tempdir().unwrap();
        let cache = SkillCache::new(temp.path());
        let provided = vec![
            ProvidedSkill {
                name: "pdf".into(),
                version: 2,
                path: cache.entry(SHA, "pdf"),
                cached: true,
                _lease: cache.lease(SHA, "pdf"),
            },
            ProvidedSkill {
                name: "release-notes".into(),
                version: 7,
                path: cache.entry(&"0".repeat(64), "release-notes"),
                cached: false,
                _lease: cache.lease(&"0".repeat(64), "release-notes"),
            },
        ];
        let binds = binds(Runtime::ClaudeCode, &provided);
        assert_eq!(binds.len(), 2);
        assert!(binds.iter().all(|b| b.read_only));
        assert_eq!(binds[0].host, temp.path().join(SHA).join("pdf"));
        assert_eq!(binds[0].container, "/home/agent/.claude/skills/pdf");
        assert_eq!(
            binds[1].container,
            "/home/agent/.claude/skills/release-notes"
        );
        let codex = binds_for(Runtime::Codex, &provided);
        assert_eq!(
            codex,
            [
                "/home/agent/.agents/skills/pdf",
                "/home/agent/.agents/skills/release-notes"
            ]
        );
        assert_eq!(
            summary(Runtime::ClaudeCode, &provided),
            "2 skills in /home/agent/.claude/skills: pdf v2, release-notes v7 (1 from the cache)"
        );
        assert_eq!(
            summary(Runtime::Pi, &provided[1..]),
            "1 skill in /home/agent/.agents/skills: release-notes v7"
        );
    }

    fn binds_for(runtime: Runtime, provided: &[ProvidedSkill]) -> Vec<String> {
        binds(runtime, provided)
            .into_iter()
            .map(|b| b.container)
            .collect()
    }

    fn new_home() -> (tempfile::TempDir, PathBuf) {
        let temp = tempfile::tempdir().unwrap();
        let home = temp.path().join("homes/agent/task");
        fs::create_dir_all(&home).unwrap();
        (temp, home)
    }

    #[test]
    fn creates_mount_points_through_plain_directories() {
        let (_temp, home) = new_home();
        create_mount_points(&home, Runtime::ClaudeCode, &["pdf", "release-notes"]).unwrap();
        for dir in [".claude", ".claude/skills", ".claude/skills/pdf"] {
            let meta = fs::symlink_metadata(home.join(dir)).unwrap();
            assert!(meta.is_dir(), "{dir}");
            assert_eq!(meta.permissions().mode() & 0o777, 0o755, "{dir}");
        }
        assert!(home.join(".claude/skills/release-notes").is_dir());
        assert_eq!(
            fs::read_to_string(crate::homes::skills_record_path(&home)).unwrap(),
            ".claude/skills/pdf\n.claude/skills/release-notes\n"
        );
        // The runtime's own directories stay as they are.
        fs::create_dir(home.join(".agents")).unwrap();
        fs::set_permissions(home.join(".agents"), Permissions::from_mode(0o700)).unwrap();
        create_mount_points(&home, Runtime::Codex, &["pdf"]).unwrap();
        assert_eq!(
            fs::metadata(home.join(".agents"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o700
        );
        assert!(home.join(".agents/skills/pdf").is_dir());
    }

    #[test]
    fn a_link_or_a_file_on_the_way_to_a_mount_point_fails() {
        // `~/.claude` is a link the agent left, to a directory outside the home.
        let (temp, home) = new_home();
        let outside = temp.path().join("outside");
        fs::create_dir(&outside).unwrap();
        symlink(&outside, home.join(".claude")).unwrap();
        let err = create_mount_points(&home, Runtime::ClaudeCode, &["pdf"]).unwrap_err();
        assert_eq!(
            err,
            "cannot create the mount point of skill `pdf` in the session home: `~/.claude` is a link"
        );
        assert!(
            fs::read_dir(&outside).unwrap().next().is_none(),
            "nothing created through it"
        );
        // A link deeper down.
        fs::remove_file(home.join(".claude")).unwrap();
        fs::create_dir_all(home.join(".claude")).unwrap();
        symlink(&outside, home.join(".claude/skills")).unwrap();
        let err = create_mount_points(&home, Runtime::ClaudeCode, &["pdf"]).unwrap_err();
        assert!(err.ends_with("`~/.claude/skills` is a link"), "{err}");
        // The skill's own folder as a link, or as a file.
        let (_temp, home) = new_home();
        fs::create_dir_all(home.join(".agents/skills")).unwrap();
        symlink("/etc", home.join(".agents/skills/pdf")).unwrap();
        let err = create_mount_points(&home, Runtime::Pi, &["pdf"]).unwrap_err();
        assert!(err.contains("skill `pdf`") && err.ends_with("`~/.agents/skills/pdf` is a link"));
        let (_temp, home) = new_home();
        fs::write(home.join(".agents"), "x").unwrap();
        let err = create_mount_points(&home, Runtime::Opencode, &["pdf"]).unwrap_err();
        assert!(err.ends_with("`~/.agents` is not a directory"), "{err}");
    }

    #[test]
    fn earlier_mount_points_are_removed_while_empty_and_never_through_a_link() {
        let (temp, home) = new_home();
        create_mount_points(&home, Runtime::ClaudeCode, &["pdf", "notes", "kept"]).unwrap();
        // The agent wrote into one of them in a run without the skill.
        fs::write(home.join(".claude/skills/kept/mine.md"), "x").unwrap();
        // And replaced `~/.agents` by a link; a forged line would point through it.
        let outside = temp.path().join("outside");
        fs::create_dir_all(outside.join("skills/pdf")).unwrap();
        symlink(&outside, home.join(".agents")).unwrap();
        let record = crate::homes::skills_record_path(&home);
        let mut text = fs::read_to_string(&record).unwrap();
        text.push_str(".agents/skills/pdf\n../../outside/skills/pdf\n.claude/x/pdf\n");
        fs::write(&record, text).unwrap();

        remove_earlier_mount_points(&home);
        assert!(!home.join(".claude/skills/pdf").exists());
        assert!(!home.join(".claude/skills/notes").exists());
        assert!(home.join(".claude/skills/kept/mine.md").exists());
        assert!(
            home.join(".claude/skills").is_dir(),
            "the runtime's directory stays"
        );
        assert!(
            outside.join("skills/pdf").is_dir(),
            "nothing removed through a link"
        );
        assert!(!record.exists());
        // Without a record there is nothing to do.
        remove_earlier_mount_points(&home);
    }

    #[test]
    fn the_cache_keeps_an_entry_complete_or_absent() {
        let temp = tempfile::tempdir().unwrap();
        let cache = SkillCache::new(temp.path().join("skills"));
        let entry = cache.entry(SHA, "pdf");
        cache
            .install(&zip_of(&[("SKILL.md", &skill_md("pdf"))]), &entry)
            .unwrap();
        assert_eq!(fs::read(entry.join("SKILL.md")).unwrap(), skill_md("pdf"));
        assert_eq!(
            fs::metadata(&entry).unwrap().permissions().mode() & 0o777,
            0o755
        );
        assert_eq!(crate::secure_fs::mode_of(cache.dir()), Some(0o700));
        // Another run that unpacked it at the same time keeps the one in place.
        cache
            .install(&zip_of(&[("SKILL.md", b"other")]), &entry)
            .unwrap();
        assert_eq!(fs::read(entry.join("SKILL.md")).unwrap(), skill_md("pdf"));
        // A refused zip leaves no entry and no temporary directory.
        let refused = cache.entry(&"1".repeat(64), "bad");
        let err = cache
            .install(&zip_of(&[("../x", b"x")]), &refused)
            .unwrap_err();
        assert!(format!("{err:#}").contains("leaves the skill's folder"));
        assert!(!refused.exists());
        let left: Vec<String> = fs::read_dir(cache.dir())
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        assert!(left.iter().all(|n| !n.starts_with('.')), "{left:?}");
    }

    #[test]
    fn maintenance_deletes_idle_entries_and_never_one_a_run_holds() {
        let temp = tempfile::tempdir().unwrap();
        let cache = SkillCache::new(temp.path().join("skills"));
        let zip = zip_of(&[("SKILL.md", &skill_md("pdf"))]);
        let old = cache.entry(SHA, "pdf");
        let held = cache.entry(&"0".repeat(64), "notes");
        let fresh = cache.entry(&"1".repeat(64), "fresh");
        for entry in [&old, &held, &fresh] {
            cache.install(&zip, entry).unwrap();
        }
        let now = Utc::now();
        let max_idle = TimeDelta::days(14);
        cache.record_use(&old, now - TimeDelta::days(15)).unwrap();
        cache.record_use(&fresh, now - TimeDelta::days(13)).unwrap();
        let lease = cache.lease(&"0".repeat(64), "notes");
        cache.record_use(&held, now - TimeDelta::days(30)).unwrap();
        assert_eq!(cache.list().len(), 3);

        let dry = cache.prune(max_idle, now, true);
        assert_eq!(dry.deleted.len(), 1);
        assert!(old.exists(), "a dry run deletes nothing");

        let report = cache.prune(max_idle, now, false);
        assert_eq!(report.deleted.len(), 1, "{report:?}");
        assert_eq!(report.deleted[0].name, "pdf");
        assert_eq!(report.deleted[0].idle_days, 15);
        assert!(report.deleted[0].bytes > 0);
        assert_eq!(report.active, 1);
        assert_eq!(report.kept, 1);
        assert!(!old.exists());
        assert!(
            !temp.path().join("skills").join(SHA).exists(),
            "the empty hash directory goes"
        );
        assert!(held.exists() && fresh.exists());

        // Released, it goes in the next pass.
        drop(lease);
        let report = cache.prune(max_idle, now, false);
        assert_eq!(report.deleted.len(), 1);
        assert!(!held.exists());
        // An entry without a stamp starts its idle time now.
        fs::remove_file(stamp_path(&fresh)).unwrap();
        let report = cache.prune(max_idle, now + TimeDelta::days(30), false);
        assert!(report.deleted.is_empty());
        assert!(cache.last_used(&fresh).is_some());
    }

    #[test]
    fn startup_removes_what_an_unpack_or_a_deletion_left() {
        let temp = tempfile::tempdir().unwrap();
        let cache = SkillCache::new(temp.path());
        for name in [".tmp-unpack-1", ".tmp-delete-2"] {
            fs::create_dir_all(temp.path().join(name).join("x")).unwrap();
        }
        cache.remove_stale_temp_dirs(false);
        assert!(
            temp.path().join(".tmp-unpack-1").exists(),
            "a run may unpack into it"
        );
        assert!(!temp.path().join(".tmp-delete-2").exists());
        cache.remove_stale_temp_dirs(true);
        assert!(!temp.path().join(".tmp-unpack-1").exists());
    }

    /// A server that answers every request with `status` and `body`, and counts the requests.
    async fn serve(status: &'static str, body: Vec<u8>) -> (String, Arc<Mutex<Vec<String>>>) {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = requests.clone();
        tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else {
                    return;
                };
                let mut head = Vec::new();
                let mut buf = [0u8; 1024];
                while !head.windows(4).any(|w| w == b"\r\n\r\n") {
                    match stream.read(&mut buf).await {
                        Ok(0) | Err(_) => break,
                        Ok(n) => head.extend_from_slice(&buf[..n]),
                    }
                }
                seen.lock()
                    .unwrap()
                    .push(String::from_utf8_lossy(&head).into_owned());
                let response = format!(
                    "HTTP/1.1 {status}\r\nContent-Type: application/zip\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(response.as_bytes()).await;
                let _ = stream.write_all(&body).await;
                let _ = stream.shutdown().await;
            }
        });
        (url, requests)
    }

    fn assign_with(skills: Vec<AssignedSkill>) -> RunAssign {
        let mut assign: RunAssign = serde_json::from_value(serde_json::json!({
            "runId": Uuid::new_v4(),
            "epoch": 4,
            "credentialLabel": "claude-test",
            "agent": {"id": Uuid::new_v4(), "name": "Tester", "runtime": "CLAUDE_CODE"},
            "workItem": {"id": Uuid::new_v4(), "key": "TEST-1", "title": "Test"},
            "prompt": "p",
            "workspaces": [],
            "requestableRepositories": [],
            "limits": {"maxWallClockSeconds": 60, "maxTurns": 1}
        }))
        .unwrap();
        assign.skills = skills;
        assign
    }

    fn skill_of(name: &str, zip: &[u8]) -> AssignedSkill {
        AssignedSkill {
            skill_id: Uuid::new_v4(),
            version: 3,
            name: name.into(),
            sha256: attachments::hex(ring::digest::digest(&ring::digest::SHA256, zip).as_ref()),
            size_bytes: zip.len() as u64,
        }
    }

    #[tokio::test]
    async fn downloads_once_then_takes_the_skill_from_the_cache() {
        let zip = zip_of(&[("SKILL.md", &skill_md("pdf")), ("scripts/a.py", b"1")]);
        let (url, requests) = serve("200 OK", zip.clone()).await;
        let temp = tempfile::tempdir().unwrap();
        let cache = SkillCache::new(temp.path().join("skills"));
        let assign = assign_with(vec![skill_of("pdf", &zip)]);
        let skill = &assign.skills[0];

        let provided = provide(&cache, &url, "tfr_TEST-ONLY-token", &assign)
            .await
            .unwrap();
        assert_eq!(provided.len(), 1);
        assert!(!provided[0].cached);
        assert_eq!(provided[0].path, cache.entry(&skill.sha256, "pdf"));
        assert!(provided[0].path.join("scripts/a.py").is_file());
        assert!(cache.is_active(&provided[0].path), "the run holds it");
        {
            let requests = requests.lock().unwrap();
            assert_eq!(requests.len(), 1);
            let line = requests[0].lines().next().unwrap().to_string();
            assert_eq!(
                line,
                format!(
                    "GET /api/runner/v1/runs/{}/skills/{}/versions/3?epoch=4 HTTP/1.1",
                    assign.run_id, skill.skill_id
                )
            );
            assert!(requests[0].contains("authorization: Bearer tfr_TEST-ONLY-token"));
        }
        drop(provided);
        assert!(!cache.is_active(&cache.entry(&skill.sha256, "pdf")));

        // The next run with the same version downloads nothing.
        let again = provide(&cache, &url, "tfr_TEST-ONLY-token", &assign)
            .await
            .unwrap();
        assert!(again[0].cached);
        assert_eq!(requests.lock().unwrap().len(), 1);
        assert!(cache.last_used(&again[0].path).is_some());
    }

    #[tokio::test]
    async fn a_skill_that_does_not_match_or_cannot_be_downloaded_fails_naming_it() {
        let zip = zip_of(&[("SKILL.md", &skill_md("pdf"))]);
        let temp = tempfile::tempdir().unwrap();
        let cache = SkillCache::new(temp.path().join("skills"));

        // Another zip than the assignment names.
        let (url, _) = serve("200 OK", zip_of(&[("SKILL.md", b"tampered")])).await;
        let mut expected = skill_of("pdf", &zip);
        let tampered = zip_of(&[("SKILL.md", b"tampered")]);
        expected.size_bytes = tampered.len() as u64;
        let err = provide(&cache, &url, "t", &assign_with(vec![expected]))
            .await
            .unwrap_err();
        assert_eq!(
            err,
            "cannot provide skill `pdf` (version 3): the zip does not match its sha256"
        );
        // Longer than announced.
        let mut short = skill_of("pdf", &zip);
        short.size_bytes = 10;
        let (url, _) = serve("200 OK", zip.clone()).await;
        let err = provide(&cache, &url, "t", &assign_with(vec![short]))
            .await
            .unwrap_err();
        assert!(
            err.ends_with("the zip is larger than its 10 bytes"),
            "{err}"
        );
        // Shorter than announced.
        let mut long = skill_of("pdf", &zip);
        long.size_bytes += 1;
        let err = provide(&cache, &url, "t", &assign_with(vec![long]))
            .await
            .unwrap_err();
        assert!(err.contains("the zip has"), "{err}");
        // Not there for this run.
        let (url, _) = serve("404 Not Found", Vec::new()).await;
        let err = provide(&cache, &url, "t", &assign_with(vec![skill_of("pdf", &zip)]))
            .await
            .unwrap_err();
        assert_eq!(
            err,
            "cannot provide skill `pdf` (version 3): the download failed: HTTP 404 Not Found"
        );
        // A zip the host refuses to unpack.
        let evil = zip_of(&[("../../etc/evil", b"x")]);
        let (url, _) = serve("200 OK", evil.clone()).await;
        let err = provide(
            &cache,
            &url,
            "t",
            &assign_with(vec![skill_of("evil", &evil)]),
        )
        .await
        .unwrap_err();
        assert!(
            err.starts_with("cannot provide skill `evil` (version 3): `../../etc/evil`"),
            "{err}"
        );
        // None of them left an entry.
        assert!(cache.list().is_empty(), "{:?}", cache.list());
    }
}
