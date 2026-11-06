//! Session homes (docs/archive/v1/CONVENTIONS.md §15.3): `<data>/homes/<agentId>/<workItemId>`,
//! mounted at `/home/agent`. The runtime keeps its session files there, so that a follow-up run on
//! the same work item can resume the session with `session/load`.
//!
//! - A run leases its home for as long as it runs ([`SessionHomes::lease`]), and the lease
//!   records the time in `homes/<agentId>/<workItemId>.last-used`: next to the home, outside the
//!   mount, so the agent cannot change it.
//! - Maintenance ([`crate::maintenance`]) deletes homes that no run used for longer than
//!   `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS`, and never one that a run holds. A later follow-up
//!   run on that task gets a new, empty home: `session/load` fails there, and the host starts
//!   a new session with the full prompt (§15.4 step 4).
//! - A home is renamed to `.tmp-delete-…` in its agent's directory first and removed
//!   afterwards; the startup cleanup removes what a crash left behind.
//! - `homes/<agentId>/<workItemId>.skills`, also outside the mount, lists the mount points of
//!   skills that the host created in the home for its last run ([`crate::skills`]); it goes
//!   with the home.
//! - `homes/<agentId>/<workItemId>.bundle`, also outside the mount, holds the SHA-256 of the
//!   session bundle that the home's session folders were last stored as or restored from
//!   ([`crate::session_bundles`]); it goes with the home.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use chrono::{DateTime, TimeDelta, Utc};
use uuid::Uuid;

use crate::util::dir_size;

/// Suffix of the file next to a home that holds its last use (RFC 3339).
const STAMP_SUFFIX: &str = ".last-used";
/// Suffix of the file next to a home that lists the skills' mount points of its last run.
const SKILLS_SUFFIX: &str = ".skills";
/// Suffix of the file next to a home that names its session bundle.
const BUNDLE_SUFFIX: &str = ".bundle";
/// Prefix of a home that is being deleted.
pub(crate) const DELETE_PREFIX: &str = ".tmp-delete-";

type Active = Arc<Mutex<HashMap<PathBuf, usize>>>;

fn lock(active: &Active) -> std::sync::MutexGuard<'_, HashMap<PathBuf, usize>> {
    active.lock().unwrap_or_else(|e| e.into_inner())
}

/// The session homes of this host, and which of them runs hold right now.
#[derive(Debug, Clone)]
pub struct SessionHomes {
    dir: PathBuf,
    active: Active,
}

/// A home in use by a run. Dropping it releases the home.
#[derive(Debug)]
pub struct HomeLease {
    active: Active,
    path: PathBuf,
}

impl HomeLease {
    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for HomeLease {
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

/// A home on disk.
#[derive(Debug, Clone)]
pub struct HomeInfo {
    /// `<agentId>` and `<workItemId>`, as directory names.
    pub agent: String,
    pub task: String,
    pub path: PathBuf,
    /// `None` when the home has no (readable) stamp: it was created before stamps existed.
    pub last_used: Option<DateTime<Utc>>,
}

/// A home that maintenance deleted (or would delete, in a dry run).
#[derive(Debug, Clone)]
pub struct DeletedHome {
    pub agent: String,
    pub task: String,
    pub bytes: u64,
    pub idle_days: i64,
}

/// What a pass over the homes did.
#[derive(Debug, Default)]
pub struct HomesReport {
    pub deleted: Vec<DeletedHome>,
    /// Idle homes kept because a run holds them.
    pub active: usize,
    pub kept: usize,
    pub failed: Vec<(PathBuf, String)>,
}

fn stamp_path(home: &Path) -> PathBuf {
    beside(home, STAMP_SUFFIX)
}

/// `homes/<agentId>/<workItemId>.skills`: the mount points of skills (relative to the home) that
/// the host created for the last run of `home`, outside the mount ([`crate::skills`]).
pub fn skills_record_path(home: &Path) -> PathBuf {
    beside(home, SKILLS_SUFFIX)
}

/// `homes/<agentId>/<workItemId>.bundle`: the SHA-256 of the session bundle that the home's
/// session folders last were (ADR-0097, [`crate::session_bundles`]), outside the mount.
pub fn bundle_record_path(home: &Path) -> PathBuf {
    beside(home, BUNDLE_SUFFIX)
}

fn beside(home: &Path, suffix: &str) -> PathBuf {
    let mut name = home.file_name().unwrap_or_default().to_os_string();
    name.push(suffix);
    home.with_file_name(name)
}

/// Sub-directories of `dir` that are neither hidden nor symlinks, by name.
fn subdirs(dir: &Path) -> Vec<(String, PathBuf)> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut dirs: Vec<(String, PathBuf)> = entries
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
        .map(|e| (e.file_name().to_string_lossy().into_owned(), e.path()))
        .filter(|(name, _)| !name.starts_with('.'))
        .collect();
    dirs.sort();
    dirs
}

impl SessionHomes {
    pub fn new(dir: impl Into<PathBuf>) -> SessionHomes {
        SessionHomes {
            dir: dir.into(),
            active: Arc::default(),
        }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// The home of `agent_id` on `task_id` ([`crate::paths::Paths::session_home`]).
    pub fn path(&self, agent_id: Uuid, task_id: Uuid) -> PathBuf {
        self.dir
            .join(agent_id.to_string())
            .join(task_id.to_string())
    }

    /// Marks the home of `agent_id` on `task_id` as in use until the lease is dropped, and
    /// records the time. Maintenance never deletes a leased home; one that it deleted just
    /// before is created again by the run.
    pub fn lease(&self, agent_id: Uuid, task_id: Uuid) -> HomeLease {
        let path = self.path(agent_id, task_id);
        *lock(&self.active).entry(path.clone()).or_default() += 1;
        if let Err(e) = self.record_use(&path, Utc::now()) {
            tracing::warn!(home = %path.display(), error = %e, "cannot record the use of a session home");
        }
        HomeLease {
            active: self.active.clone(),
            path,
        }
    }

    /// Records that a run used `home` at `at`.
    pub fn record_use(&self, home: &Path, at: DateTime<Utc>) -> std::io::Result<()> {
        let stamp = stamp_path(home);
        if let Some(parent) = stamp.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(stamp, at.to_rfc3339())
    }

    /// When a run last used `home`; `None` without a readable stamp.
    pub fn last_used(&self, home: &Path) -> Option<DateTime<Utc>> {
        let text = std::fs::read_to_string(stamp_path(home)).ok()?;
        DateTime::parse_from_rfc3339(text.trim())
            .ok()
            .map(|t| t.with_timezone(&Utc))
    }

    /// `true` while a run holds `home`.
    pub fn is_active(&self, home: &Path) -> bool {
        lock(&self.active).contains_key(home)
    }

    /// The homes on disk, by agent and task.
    pub fn list(&self) -> Vec<HomeInfo> {
        let mut homes = Vec::new();
        for (agent, agent_dir) in subdirs(&self.dir) {
            for (task, path) in subdirs(&agent_dir) {
                homes.push(HomeInfo {
                    last_used: self.last_used(&path),
                    agent: agent.clone(),
                    task,
                    path,
                });
            }
        }
        homes
    }

    /// Startup and every pass: removes homes whose deletion did not finish.
    pub fn remove_stale_temp_dirs(&self) {
        let Ok(agents) = std::fs::read_dir(&self.dir) else {
            return;
        };
        for agent in agents.filter_map(|e| e.ok()) {
            let Ok(entries) = std::fs::read_dir(agent.path()) else {
                continue;
            };
            for entry in entries.filter_map(|e| e.ok()) {
                if entry
                    .file_name()
                    .to_string_lossy()
                    .starts_with(DELETE_PREFIX)
                {
                    let path = entry.path();
                    if let Err(e) = std::fs::remove_dir_all(&path) {
                        tracing::warn!(path = %path.display(), error = %e, "cannot remove a deleted session home");
                    }
                }
            }
        }
    }

    /// Deletes the homes that no run used for longer than `max_idle` before `now`, except
    /// those a run holds. A home without a stamp (from before stamps existed) gets one with
    /// `now`, so its idle time starts now. `dry_run` only reports.
    pub fn prune(&self, max_idle: TimeDelta, now: DateTime<Utc>, dry_run: bool) -> HomesReport {
        let mut report = HomesReport::default();
        if !dry_run {
            self.remove_stale_temp_dirs();
        }
        for home in self.list() {
            let Some(last_used) = home.last_used else {
                if !dry_run && let Err(e) = self.record_use(&home.path, now) {
                    tracing::warn!(home = %home.path.display(), error = %e, "cannot record the use of a session home");
                }
                report.kept += 1;
                continue;
            };
            if now - last_used <= max_idle {
                report.kept += 1;
                continue;
            }
            let bytes = dir_size(&home.path);
            let retired = {
                // Checked and renamed under the lease registry's lock: a run that leases the
                // home after this gets a new one.
                let active = lock(&self.active);
                if active.contains_key(&home.path) {
                    report.active += 1;
                    continue;
                }
                // A run may have used it since the listing.
                let last_used = self.last_used(&home.path).unwrap_or(last_used);
                if now - last_used <= max_idle {
                    report.kept += 1;
                    continue;
                }
                let idle_days = (now - last_used).num_days();
                if dry_run {
                    report.deleted.push(DeletedHome {
                        agent: home.agent.clone(),
                        task: home.task.clone(),
                        bytes,
                        idle_days,
                    });
                    continue;
                }
                let target = home.path.with_file_name(format!(
                    "{DELETE_PREFIX}{}-{}",
                    home.task,
                    Uuid::new_v4().simple()
                ));
                if let Err(e) = std::fs::rename(&home.path, &target) {
                    tracing::warn!(home = %home.path.display(), error = %e, "cannot delete a session home");
                    report.failed.push((home.path.clone(), e.to_string()));
                    continue;
                }
                let _ = std::fs::remove_file(stamp_path(&home.path));
                let _ = std::fs::remove_file(skills_record_path(&home.path));
                let _ = std::fs::remove_file(bundle_record_path(&home.path));
                (target, idle_days)
            };
            let (target, idle_days) = retired;
            if let Err(e) = std::fs::remove_dir_all(&target) {
                // The rename took it out of use; the next pass tries the removal again.
                tracing::warn!(path = %target.display(), error = %e, "cannot remove a deleted session home");
            }
            tracing::info!(
                agent_id = %home.agent,
                task_id = %home.task,
                freed_bytes = bytes,
                idle_days,
                "deleted an idle session home"
            );
            report.deleted.push(DeletedHome {
                agent: home.agent.clone(),
                task: home.task.clone(),
                bytes,
                idle_days,
            });
        }
        if !dry_run {
            self.remove_orphans();
        }
        report
    }

    /// Removes stamps and skill records without a home, and agent directories without homes,
    /// unless a run holds a home there.
    fn remove_orphans(&self) {
        let Ok(agents) = std::fs::read_dir(&self.dir) else {
            return;
        };
        let active = lock(&self.active);
        for agent in agents.filter_map(|e| e.ok()) {
            let agent_dir = agent.path();
            if !agent.file_type().is_ok_and(|t| t.is_dir())
                || active.keys().any(|home| home.parent() == Some(&agent_dir))
            {
                continue;
            }
            let Ok(entries) = std::fs::read_dir(&agent_dir) else {
                continue;
            };
            for entry in entries.filter_map(|e| e.ok()) {
                let name = entry.file_name().to_string_lossy().into_owned();
                if let Some(task) = name
                    .strip_suffix(STAMP_SUFFIX)
                    .or_else(|| name.strip_suffix(SKILLS_SUFFIX))
                    .or_else(|| name.strip_suffix(BUNDLE_SUFFIX))
                    && !agent_dir.join(task).exists()
                {
                    let _ = std::fs::remove_file(entry.path());
                }
            }
            // Fails (and is left alone) while anything is in it.
            let _ = std::fs::remove_dir(&agent_dir);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn homes_are_where_the_paths_say() {
        let paths = crate::paths::Paths::new("/c", "/d");
        let homes = SessionHomes::new(paths.homes_dir());
        let (agent, task) = (Uuid::new_v4(), Uuid::new_v4());
        assert_eq!(homes.path(agent, task), paths.session_home(agent, task));
        assert_eq!(
            stamp_path(&homes.path(agent, task)),
            PathBuf::from(format!("/d/homes/{agent}/{task}.last-used"))
        );
        assert_eq!(
            skills_record_path(&homes.path(agent, task)),
            PathBuf::from(format!("/d/homes/{agent}/{task}.skills"))
        );
    }

    #[test]
    fn leases_count_per_home() {
        let tmp = tempfile::tempdir().unwrap();
        let homes = SessionHomes::new(tmp.path());
        let (agent, task) = (Uuid::new_v4(), Uuid::new_v4());
        let home = homes.path(agent, task);
        let first = homes.lease(agent, task);
        let second = homes.lease(agent, task);
        assert!(homes.is_active(&home));
        drop(first);
        assert!(homes.is_active(&home), "a second run still holds it");
        drop(second);
        assert!(!homes.is_active(&home));
        let used = homes.last_used(&home).unwrap();
        assert!((Utc::now() - used).num_seconds().abs() < 60);
    }
}
