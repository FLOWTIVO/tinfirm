//! Disk maintenance of the runner host (docs/archive/v1/CONVENTIONS.md §15.4 step 2): repository mirrors,
//! session homes and the cache of skills. `run` makes a pass shortly after startup and then every 6 hours;
//! `mirrors prune` makes the mirror part of a pass while `run` is stopped.
//!
//! Mirrors ([`maintain_mirrors`]), one at a time, each under its lock, taken without waiting
//! (a mirror that a run holds or waits for is skipped until the next pass):
//! 1. A mirror that no run used for longer than `TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS`
//!    (default 30) is deleted: renamed to `.tmp-delete-…`, then removed.
//! 2. The others get `git gc --prune=now` when it is due: when the mirror was fetched since
//!    its last gc and that gc is 7 days old (or there was none yet), or at once when it has
//!    1000 loose objects or 20 packs. A clone copies every object file, so loose objects make
//!    every run slower, and gc drops the objects of deleted and force-pushed branches.
//! 3. With `TINFIRM_RUNNER_MIRROR_MAX_BYTES`, the least recently used mirrors are deleted
//!    until the total is under the cap; a mirror in use is never deleted.
//!
//! Session homes ([`crate::homes::SessionHomes::prune`]): homes that no run used for longer
//! than `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS` (default 30) are deleted, except those of active
//! runs.
//!
//! Skills ([`crate::skills::SkillCache::prune`], ADR-0064): unpacked skills that no run used
//! for longer than `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS` (default 14) are deleted, except
//! those of active runs. A later run with that skill downloads it again.
//!
//! Every deletion and gc is logged with the mirror key (and the repository URL without
//! credentials) and the bytes it freed.

use std::time::{Duration, Instant};

use chrono::{DateTime, TimeDelta, Utc};
use tokio_util::sync::CancellationToken;

use crate::homes::HomesReport;
use crate::host::Host;
use crate::mirror::{self, MirrorInfo, MirrorState, Mirrors, ObjectCounts};
use crate::skills::SkillCacheReport;
use crate::util::dir_size;

/// Default of `TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS` and `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS`.
pub const DEFAULT_MAX_IDLE_DAYS: u32 = 30;
/// Default of `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS`: a skill is small and downloaded again
/// in a moment, and a pinned version is usually replaced by the next one.
pub const DEFAULT_SKILL_CACHE_MAX_IDLE_DAYS: u32 = 14;
/// A mirror fetched since its last gc gets the next one after this many days.
pub const GC_INTERVAL_DAYS: i64 = 7;
/// Loose objects that make a gc due at once (git's own `gc.auto` is 6700).
pub const GC_LOOSE_OBJECTS: u64 = 1000;
/// Packs that make a gc due at once (git's own `gc.autoPackLimit` is 50). Every fetch of
/// more than 100 objects adds a pack.
pub const GC_PACKS: u64 = 20;
/// The first pass of `run`, after startup.
pub const INITIAL_DELAY: Duration = Duration::from_secs(60);
/// Between two passes of `run`.
pub const INTERVAL: Duration = Duration::from_secs(6 * 3600);

/// What maintenance deletes and when it collects garbage.
#[derive(Debug, Clone)]
pub struct Policy {
    pub mirror_max_idle: TimeDelta,
    /// `TINFIRM_RUNNER_MIRROR_MAX_BYTES`; `None` is no cap.
    pub mirror_max_bytes: Option<u64>,
    pub gc_interval: TimeDelta,
    pub gc_loose_objects: u64,
    pub gc_packs: u64,
    pub home_max_idle: TimeDelta,
    /// `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS`.
    pub skill_cache_max_idle: TimeDelta,
}

impl Default for Policy {
    fn default() -> Policy {
        Policy {
            mirror_max_idle: TimeDelta::days(DEFAULT_MAX_IDLE_DAYS.into()),
            mirror_max_bytes: None,
            gc_interval: TimeDelta::days(GC_INTERVAL_DAYS),
            gc_loose_objects: GC_LOOSE_OBJECTS,
            gc_packs: GC_PACKS,
            home_max_idle: TimeDelta::days(DEFAULT_MAX_IDLE_DAYS.into()),
            skill_cache_max_idle: TimeDelta::days(DEFAULT_SKILL_CACHE_MAX_IDLE_DAYS.into()),
        }
    }
}

impl Policy {
    /// The defaults with `TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS`,
    /// `TINFIRM_RUNNER_MIRROR_MAX_BYTES`, `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS` and
    /// `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS` applied.
    pub fn from_env() -> Policy {
        let var = |name: &str| std::env::var(name).ok();
        Policy::from_values(
            var("TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS").as_deref(),
            var("TINFIRM_RUNNER_MIRROR_MAX_BYTES").as_deref(),
            var("TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS").as_deref(),
            var("TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS").as_deref(),
        )
    }

    fn from_values(
        mirror_idle: Option<&str>,
        mirror_bytes: Option<&str>,
        home_idle: Option<&str>,
        skill_cache_idle: Option<&str>,
    ) -> Policy {
        Policy {
            mirror_max_idle: parse_days(
                "TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS",
                mirror_idle,
                DEFAULT_MAX_IDLE_DAYS,
            ),
            mirror_max_bytes: parse_cap(mirror_bytes),
            home_max_idle: parse_days(
                "TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS",
                home_idle,
                DEFAULT_MAX_IDLE_DAYS,
            ),
            skill_cache_max_idle: parse_days(
                "TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS",
                skill_cache_idle,
                DEFAULT_SKILL_CACHE_MAX_IDLE_DAYS,
            ),
            ..Policy::default()
        }
    }
}

/// Whole days, at least 1; `default` when unset, empty or invalid (with a warning).
fn parse_days(name: &str, value: Option<&str>, default: u32) -> TimeDelta {
    let days = match value.map(str::trim).filter(|v| !v.is_empty()) {
        None => default,
        Some(v) => match v.parse::<u32>() {
            Ok(days) if days >= 1 => days,
            _ => {
                tracing::warn!(
                    variable = name,
                    value = v,
                    "invalid number of days; using {default}"
                );
                default
            }
        },
    };
    TimeDelta::days(days.into())
}

/// Bytes with an optional K/M/G/T suffix; `None` (no cap) when unset or invalid.
fn parse_cap(value: Option<&str>) -> Option<u64> {
    let value = value.map(str::trim).filter(|v| !v.is_empty())?;
    match crate::util::parse_bytes(value) {
        Some(bytes) if bytes > 0 => Some(bytes),
        _ => {
            tracing::warn!(
                value,
                "invalid TINFIRM_RUNNER_MIRROR_MAX_BYTES (bytes, or a number with K, M, G or T); no cap"
            );
            None
        }
    }
}

/// Maintenance of a running host.
#[derive(Debug, Clone)]
pub struct Settings {
    pub policy: Policy,
    pub initial_delay: Duration,
    pub interval: Duration,
}

impl Settings {
    pub fn from_env() -> Settings {
        Settings {
            policy: Policy::from_env(),
            initial_delay: INITIAL_DELAY,
            interval: INTERVAL,
        }
    }
}

/// Why a mirror was deleted.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeleteReason {
    /// No run used it for this many days.
    Idle { days: i64 },
    /// The mirrors were over `TINFIRM_RUNNER_MIRROR_MAX_BYTES`, and it was the least recently
    /// used one that no run held.
    OverCap,
}

/// Why a gc was due.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GcReason {
    /// Fetched since its last gc, which is at least 7 days old (or unknown).
    Scheduled,
    LooseObjects(u64),
    Packs(u64),
}

impl std::fmt::Display for GcReason {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            GcReason::Scheduled => f.write_str("scheduled"),
            GcReason::LooseObjects(n) => write!(f, "{n} loose objects"),
            GcReason::Packs(n) => write!(f, "{n} packs"),
        }
    }
}

#[derive(Debug, Clone)]
pub struct DeletedMirror {
    pub key: String,
    pub repo_url: Option<String>,
    pub bytes: u64,
    pub reason: DeleteReason,
}

#[derive(Debug, Clone)]
pub struct CollectedMirror {
    pub key: String,
    pub repo_url: Option<String>,
    pub reason: GcReason,
    /// 0 in a dry run.
    pub freed_bytes: u64,
}

/// What a pass over the mirrors did (or would do, in a dry run).
#[derive(Debug, Default)]
pub struct MirrorReport {
    pub dry_run: bool,
    pub deleted: Vec<DeletedMirror>,
    pub collected: Vec<CollectedMirror>,
    /// Mirrors that a run held or waited for; the next pass tries again.
    pub busy: Vec<String>,
    /// Mirrors that maintenance could not delete or collect, with the error.
    pub failed: Vec<(String, String)>,
    /// Mirrors left, and their bytes.
    pub remaining: usize,
    pub remaining_bytes: u64,
}

impl MirrorReport {
    pub fn freed_bytes(&self) -> u64 {
        self.deleted.iter().map(|d| d.bytes).sum::<u64>()
            + self.collected.iter().map(|c| c.freed_bytes).sum::<u64>()
    }
}

/// Whether `state` and `counts` make a gc due at `now`.
pub fn gc_due(
    state: &MirrorState,
    counts: ObjectCounts,
    policy: &Policy,
    now: DateTime<Utc>,
) -> Option<GcReason> {
    if counts.loose >= policy.gc_loose_objects {
        return Some(GcReason::LooseObjects(counts.loose));
    }
    if counts.packs >= policy.gc_packs {
        return Some(GcReason::Packs(counts.packs));
    }
    // The first fetch left the mirror packed; count from its creation until the first gc.
    match state.last_gc_at.or(state.created_at) {
        None => Some(GcReason::Scheduled),
        Some(base) => (now - base >= policy.gc_interval
            && state.last_used_at.is_none_or(|used| used > base))
        .then_some(GcReason::Scheduled),
    }
}

/// A mirror that stays after its step of the pass.
struct Kept {
    key: String,
    repo_url: Option<String>,
    last_used: DateTime<Utc>,
    bytes: u64,
}

fn url_for_log(url: &Option<String>) -> &str {
    url.as_deref().unwrap_or("unknown")
}

/// One pass over the mirrors (see the module docs) at `now`. `dry_run` changes nothing and
/// reports what a pass would do. `stop` ends the pass between two mirrors.
pub async fn maintain_mirrors(
    mirrors: &Mirrors,
    policy: &Policy,
    now: DateTime<Utc>,
    dry_run: bool,
    stop: &CancellationToken,
) -> MirrorReport {
    let mut report = MirrorReport {
        dry_run,
        ..MirrorReport::default()
    };
    if !dry_run {
        mirrors.remove_deleted_dirs().await;
    }
    let listed = match mirrors.list() {
        Ok(listed) => listed,
        Err(e) => {
            let error = format!("{e:#}");
            tracing::warn!(%error, "cannot list the repository mirrors");
            report.failed.push(("mirrors".into(), error));
            return report;
        }
    };
    let mut kept = Vec::new();
    // Mirrors in use: they stay and count towards the cap, but are never deleted.
    let mut in_use = 0usize;
    let mut in_use_bytes = 0u64;
    for info in listed {
        if stop.is_cancelled() {
            return report;
        }
        let Some(lock) = mirrors.try_lock_for_maintenance(&info.key) else {
            tracing::debug!(mirror = %info.key, "mirror in use; maintenance skips it");
            in_use += 1;
            in_use_bytes += info.size_bytes;
            report.busy.push(info.key);
            continue;
        };
        if let Some(stays) = maintain_one(mirrors, policy, now, lock, info, &mut report).await {
            kept.push(stays);
        }
    }
    let mut total = kept.iter().map(|k| k.bytes).sum::<u64>() + in_use_bytes;
    let mut remaining = kept.len() + in_use;
    if let Some(cap) = policy.mirror_max_bytes
        && total > cap
    {
        kept.sort_by(|a, b| a.last_used.cmp(&b.last_used).then(a.key.cmp(&b.key)));
        for candidate in kept {
            if total <= cap || stop.is_cancelled() {
                break;
            }
            let deleted = DeletedMirror {
                key: candidate.key.clone(),
                repo_url: candidate.repo_url.clone(),
                bytes: candidate.bytes,
                reason: DeleteReason::OverCap,
            };
            if dry_run {
                total -= candidate.bytes;
                remaining -= 1;
                report.deleted.push(deleted);
                continue;
            }
            let Some(lock) = mirrors.try_lock_for_maintenance(&candidate.key) else {
                report.busy.push(candidate.key);
                continue;
            };
            // A run that fetched it meanwhile made it the most recently used one.
            if mirror::read_state(lock.path()).and_then(|s| s.last_used_at)
                != Some(candidate.last_used)
            {
                continue;
            }
            match mirrors.delete(lock).await {
                Ok(()) => {
                    tracing::info!(
                        mirror = %candidate.key,
                        repo_url = url_for_log(&candidate.repo_url),
                        freed_bytes = candidate.bytes,
                        reason = "cap",
                        cap_bytes = cap,
                        "deleted the least recently used repository mirror (over the size cap)"
                    );
                    total -= candidate.bytes;
                    remaining -= 1;
                    report.deleted.push(deleted);
                }
                Err(e) => {
                    let error = format!("{e:#}");
                    tracing::warn!(mirror = %candidate.key, %error, "cannot delete a repository mirror");
                    report.failed.push((candidate.key, error));
                }
            }
        }
    }
    report.remaining = remaining;
    report.remaining_bytes = total;
    report
}

/// Deletes an idle mirror or collects its garbage when due. Returns the mirror if it stays.
async fn maintain_one(
    mirrors: &Mirrors,
    policy: &Policy,
    now: DateTime<Utc>,
    lock: mirror::MaintenanceLock,
    info: MirrorInfo,
    report: &mut MirrorReport,
) -> Option<Kept> {
    let dry_run = report.dry_run;
    let key = info.key;
    // Read again under the lock: a run may have used the mirror since the listing.
    let state = match mirror::read_state(lock.path()) {
        Some(state) => state,
        None => {
            // A mirror from before usage tracking: its idle time starts now.
            let adopted = MirrorState {
                last_used_at: Some(now),
                ..MirrorState::default()
            };
            if !dry_run && let Err(e) = mirror::write_state(lock.path(), &adopted) {
                tracing::warn!(mirror = %key, error = %format!("{e:#}"), "cannot record the state of a mirror");
            }
            adopted
        }
    };
    let last_used = state.last_used_at.unwrap_or(now);
    let idle = now - last_used;
    if idle > policy.mirror_max_idle {
        let deleted = DeletedMirror {
            key: key.clone(),
            repo_url: state.repo_url.clone(),
            bytes: info.size_bytes,
            reason: DeleteReason::Idle {
                days: idle.num_days(),
            },
        };
        if dry_run {
            report.deleted.push(deleted);
            return None;
        }
        return match mirrors.delete(lock).await {
            Ok(()) => {
                tracing::info!(
                    mirror = %key,
                    repo_url = url_for_log(&state.repo_url),
                    freed_bytes = info.size_bytes,
                    reason = "idle",
                    idle_days = idle.num_days(),
                    "deleted an idle repository mirror"
                );
                report.deleted.push(deleted);
                None
            }
            Err(e) => {
                let error = format!("{e:#}");
                tracing::warn!(mirror = %key, %error, "cannot delete a repository mirror");
                report.failed.push((key.clone(), error));
                Some(Kept {
                    key,
                    repo_url: state.repo_url,
                    last_used,
                    bytes: info.size_bytes,
                })
            }
        };
    }
    let mut bytes = info.size_bytes;
    let counts = match mirrors.count_objects(&lock).await {
        Ok(counts) => Some(counts),
        Err(e) => {
            let error = format!("{e:#}");
            tracing::warn!(mirror = %key, %error, "cannot count the objects of a mirror");
            report.failed.push((key.clone(), error));
            None
        }
    };
    if let Some(reason) = counts.and_then(|c| gc_due(&state, c, policy, now)) {
        if dry_run {
            report.collected.push(CollectedMirror {
                key: key.clone(),
                repo_url: state.repo_url.clone(),
                reason,
                freed_bytes: 0,
            });
        } else if lock.wanted() {
            // A run arrived meanwhile; it goes first, and the gc waits for the next pass.
            report.busy.push(key.clone());
        } else {
            let started = Instant::now();
            match mirrors.gc(&lock).await {
                Ok(()) => {
                    let collected = MirrorState {
                        last_gc_at: Some(now),
                        ..state.clone()
                    };
                    if let Err(e) = mirror::write_state(lock.path(), &collected) {
                        tracing::warn!(mirror = %key, error = %format!("{e:#}"), "cannot record the gc of a mirror");
                    }
                    let after = dir_size(lock.path());
                    let freed_bytes = bytes.saturating_sub(after);
                    bytes = after;
                    tracing::info!(
                        mirror = %key,
                        repo_url = url_for_log(&state.repo_url),
                        freed_bytes,
                        size_bytes = after,
                        trigger = %reason,
                        duration_ms = started.elapsed().as_millis() as u64,
                        "collected garbage in a repository mirror"
                    );
                    report.collected.push(CollectedMirror {
                        key: key.clone(),
                        repo_url: state.repo_url.clone(),
                        reason,
                        freed_bytes,
                    });
                }
                Err(e) => {
                    let error = format!("{e:#}");
                    tracing::warn!(mirror = %key, %error, "git gc failed in a repository mirror");
                    report.failed.push((key.clone(), error));
                }
            }
        }
    }
    Some(Kept {
        key,
        repo_url: state.repo_url,
        last_used,
        bytes,
    })
}

/// One maintenance pass of a running host at `now`: mirrors, then session homes, then the
/// cache of skills.
pub async fn run_pass(
    host: &Host,
    now: DateTime<Utc>,
    stop: &CancellationToken,
) -> (MirrorReport, HomesReport, SkillCacheReport) {
    let policy = &host.opts.maintenance.policy;
    let mirrors = maintain_mirrors(&host.mirrors, policy, now, false, stop).await;
    let homes = {
        let homes = host.homes.clone();
        let max_idle = policy.home_max_idle;
        tokio::task::spawn_blocking(move || homes.prune(max_idle, now, false))
            .await
            .unwrap_or_else(|e| {
                tracing::warn!(error = %e, "session home maintenance failed");
                HomesReport::default()
            })
    };
    let skills = {
        let skills = host.skills.clone();
        let max_idle = policy.skill_cache_max_idle;
        tokio::task::spawn_blocking(move || skills.prune(max_idle, now, false))
            .await
            .unwrap_or_else(|e| {
                tracing::warn!(error = %e, "skill cache maintenance failed");
                SkillCacheReport::default()
            })
    };
    tracing::info!(
        mirrors_deleted = mirrors.deleted.len(),
        mirrors_collected = mirrors.collected.len(),
        mirrors_busy = mirrors.busy.len(),
        mirrors_failed = mirrors.failed.len(),
        mirrors_left = mirrors.remaining,
        mirror_bytes = mirrors.remaining_bytes,
        mirror_freed_bytes = mirrors.freed_bytes(),
        homes_deleted = homes.deleted.len(),
        homes_active = homes.active,
        home_freed_bytes = homes.deleted.iter().map(|h| h.bytes).sum::<u64>(),
        skills_deleted = skills.deleted.len(),
        skills_active = skills.active,
        skill_freed_bytes = skills.deleted.iter().map(|s| s.bytes).sum::<u64>(),
        "maintenance pass finished"
    );
    (mirrors, homes, skills)
}

/// The maintenance of `run`: a pass after `initial_delay`, then every `interval`, until
/// `stop`. A pass stops between two steps; a gc that has started runs to its end.
pub async fn run_loop(host: Host, stop: CancellationToken) {
    let settings = host.opts.maintenance.clone();
    let mut delay = settings.initial_delay;
    loop {
        tokio::select! {
            _ = stop.cancelled() => return,
            _ = tokio::time::sleep(delay) => {}
        }
        run_pass(&host, Utc::now(), &stop).await;
        delay = settings.interval;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_policy_from_the_environment_values() {
        let policy = Policy::from_values(None, None, None, None);
        assert_eq!(policy.mirror_max_idle, TimeDelta::days(30));
        assert_eq!(policy.home_max_idle, TimeDelta::days(30));
        assert_eq!(policy.skill_cache_max_idle, TimeDelta::days(14));
        assert_eq!(policy.mirror_max_bytes, None);
        assert_eq!(policy.gc_interval, TimeDelta::days(7));
        let policy = Policy::from_values(Some(" 7 "), Some("50G"), Some("90"), Some(" 3 "));
        assert_eq!(policy.mirror_max_idle, TimeDelta::days(7));
        assert_eq!(policy.mirror_max_bytes, Some(50 << 30));
        assert_eq!(policy.home_max_idle, TimeDelta::days(90));
        assert_eq!(policy.skill_cache_max_idle, TimeDelta::days(3));
        let policy = Policy::from_values(Some("0"), Some("0"), Some("-3"), Some("0"));
        assert_eq!(policy.mirror_max_idle, TimeDelta::days(30));
        assert_eq!(policy.mirror_max_bytes, None);
        assert_eq!(policy.home_max_idle, TimeDelta::days(30));
        assert_eq!(policy.skill_cache_max_idle, TimeDelta::days(14));
        let policy = Policy::from_values(Some("P30D"), Some("lots"), Some(""), Some("2w"));
        assert_eq!(policy.mirror_max_idle, TimeDelta::days(30));
        assert_eq!(policy.mirror_max_bytes, None);
        assert_eq!(policy.home_max_idle, TimeDelta::days(30));
        assert_eq!(policy.skill_cache_max_idle, TimeDelta::days(14));
    }

    #[test]
    fn decides_when_a_gc_is_due() {
        let policy = Policy::default();
        let now = Utc::now();
        let days = |n: i64| now - TimeDelta::days(n);
        let tidy = ObjectCounts { loose: 0, packs: 1 };
        let state = |created: Option<i64>, used: Option<i64>, gc: Option<i64>| MirrorState {
            repo_url: None,
            created_at: created.map(days),
            last_used_at: used.map(days),
            last_gc_at: gc.map(days),
        };
        // Unknown history (a mirror from before the state file): one gc sets a baseline.
        assert_eq!(
            gc_due(&state(None, Some(0), None), tidy, &policy, now),
            Some(GcReason::Scheduled)
        );
        // New mirrors are packed by their first fetch.
        assert_eq!(
            gc_due(&state(Some(1), Some(0), None), tidy, &policy, now),
            None
        );
        assert_eq!(
            gc_due(&state(Some(8), Some(0), None), tidy, &policy, now),
            Some(GcReason::Scheduled)
        );
        // Weekly, but only after a fetch since the last gc.
        assert_eq!(
            gc_due(&state(Some(90), Some(1), Some(6)), tidy, &policy, now),
            None
        );
        assert_eq!(
            gc_due(&state(Some(90), Some(1), Some(7)), tidy, &policy, now),
            Some(GcReason::Scheduled)
        );
        assert_eq!(
            gc_due(&state(Some(90), Some(9), Some(8)), tidy, &policy, now),
            None
        );
        // Many loose objects or packs: at once.
        let fresh = state(Some(90), Some(0), Some(0));
        assert_eq!(
            gc_due(
                &fresh,
                ObjectCounts {
                    loose: 1000,
                    packs: 1
                },
                &policy,
                now
            ),
            Some(GcReason::LooseObjects(1000))
        );
        assert_eq!(
            gc_due(
                &fresh,
                ObjectCounts {
                    loose: 999,
                    packs: 20
                },
                &policy,
                now
            ),
            Some(GcReason::Packs(20))
        );
        assert_eq!(
            gc_due(
                &fresh,
                ObjectCounts {
                    loose: 999,
                    packs: 19
                },
                &policy,
                now
            ),
            None
        );
    }
}
