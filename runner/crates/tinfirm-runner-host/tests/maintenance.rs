//! Disk maintenance with real git and explicit timestamps (no test waits for days): idle
//! mirrors are deleted and used ones kept, a mirror in use is skipped, gc leaves a mirror
//! that still clones, the size cap deletes the least recently used mirrors first, a deletion
//! is a rename first, and idle session homes are deleted except under a run.

mod common;

use std::path::Path;
use std::process::Command;
use std::sync::Arc;
use std::time::Duration;

use chrono::{DateTime, TimeDelta, Utc};
use common::git_http::{GitHttpServer, test_git_authorization};
use common::*;
use tinfirm_protocol::{Secret, WorkspaceSpec};
use tinfirm_runner_host::git::Git;
use tinfirm_runner_host::homes::SessionHomes;
use tinfirm_runner_host::maintenance::{DeleteReason, GcReason, Policy, maintain_mirrors};
use tinfirm_runner_host::mirror::{self, MirrorState, Mirrors, mirror_key};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

fn entries(dir: &Path) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(dir)
        .map(|entries| {
            entries
                .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default();
    names.sort();
    names
}

fn days_before(now: DateTime<Utc>, days: i64) -> DateTime<Utc> {
    now - TimeDelta::days(days)
}

/// Changes the state file of the mirror at `path`, as if time had passed.
fn edit_state(path: &Path, edit: impl FnOnce(&mut MirrorState)) {
    let mut state = mirror::read_state(path).expect("the mirror has a state");
    edit(&mut state);
    mirror::write_state(path, &state).unwrap();
}

async fn create_mirror(mirrors: &Mirrors, url: &str) -> String {
    drop(mirrors.update(url, None).await.unwrap());
    mirror_key(url, None)
}

async fn pass(
    mirrors: &Mirrors,
    policy: &Policy,
    now: DateTime<Utc>,
) -> tinfirm_runner_host::maintenance::MirrorReport {
    maintain_mirrors(mirrors, policy, now, false, &CancellationToken::new()).await
}

fn deleted_keys(report: &tinfirm_runner_host::maintenance::MirrorReport) -> Vec<String> {
    report.deleted.iter().map(|d| d.key.clone()).collect()
}

/// `git` in `cwd` that may fail; returns whether it succeeded.
fn git_ok(cwd: &Path, args: &[&str]) -> bool {
    Command::new("git")
        .args(args)
        .current_dir(cwd)
        .output()
        .unwrap()
        .status
        .success()
}

fn count_objects(mirror: &Path) -> (u64, u64) {
    let out = git(mirror, &["count-objects", "-v"]);
    let value = |name: &str| {
        out.lines()
            .find_map(|l| l.strip_prefix(&format!("{name}: ")))
            .unwrap()
            .trim()
            .parse::<u64>()
            .unwrap()
    };
    (value("count"), value("packs"))
}

/// Pushes a commit that only branch `branch` has; returns its sha.
fn commit_on_branch(repo: &TestRepo, branch: &str) -> String {
    let work = repo.dir.path().join(format!("work-{}", Uuid::new_v4()));
    git(
        repo.dir.path(),
        &[
            "clone",
            "-q",
            repo.bare.to_str().unwrap(),
            work.to_str().unwrap(),
        ],
    );
    std::fs::write(work.join("doomed.txt"), "soon unreachable\n").unwrap();
    git(&work, &["add", "doomed.txt"]);
    git(&work, &["commit", "-q", "-m", "Doomed change"]);
    git(
        &work,
        &["push", "-q", "origin", &format!("HEAD:refs/heads/{branch}")],
    );
    git(&work, &["rev-parse", "HEAD"]).trim().to_string()
}

fn spec(repo_url: &str) -> WorkspaceSpec {
    WorkspaceSpec {
        component_id: Uuid::new_v4(),
        dir: "repo".into(),
        name: "Repo".into(),
        repo_url: repo_url.into(),
        default_branch: "main".into(),
        branch: "agent/TEST-1".into(),
        git: None,
        author_name: None,
        author_email: None,
        read_only: false,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_records_the_repository_and_the_last_use_without_credentials() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let server = GitHttpServer::start(repo.dir.path(), Some(test_git_authorization())).await;
        let url = format!("{}/origin.git", server.url);
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));
        let before = Utc::now();
        let lease = mirrors
            .update(&url, Some(&Secret::new(test_git_authorization())))
            .await
            .unwrap();
        let state = mirror::read_state(&lease.path).unwrap();
        assert_eq!(state.repo_url.as_deref(), Some(url.as_str()));
        assert!(state.created_at.unwrap() >= before);
        assert!(state.last_used_at.unwrap() >= state.created_at.unwrap());
        assert_eq!(state.last_gc_at, None);
        let file = std::fs::read_to_string(lease.path.join(mirror::STATE_FILE)).unwrap();
        assert!(
            !file.contains(TEST_GIT_TOKEN) && !file.contains("Basic"),
            "{file}"
        );
        let first_use = state.last_used_at.unwrap();
        drop(lease);
        // Every run's fetch counts as a use.
        tokio::time::sleep(Duration::from_millis(20)).await;
        mirrors
            .refresh(&url, Some(&Secret::new(test_git_authorization())))
            .await
            .unwrap();
        let state = mirror::read_state(
            &mirrors.path_for(&url, Some(&Secret::new(test_git_authorization()))),
        )
        .unwrap();
        assert!(state.last_used_at.unwrap() > first_use);
        let listed = mirrors.list().unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(
            listed[0].key,
            mirror_key(&url, Some(&Secret::new(test_git_authorization())))
        );
        assert_eq!(listed[0].state.as_ref(), Some(&state));
        assert!(listed[0].size_bytes > 0);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn idle_mirrors_are_deleted_and_used_ones_kept() {
    with_timeout(120, async {
        let idle = TestRepo::new();
        let used = TestRepo::new();
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));
        let idle_key = create_mirror(&mirrors, &idle.url).await;
        let used_key = create_mirror(&mirrors, &used.url).await;
        let now = Utc::now();
        edit_state(&mirrors.path_for(&idle.url, None), |s| {
            s.last_used_at = Some(days_before(now, 31));
        });
        edit_state(&mirrors.path_for(&used.url, None), |s| {
            s.last_used_at = Some(days_before(now, 29));
        });
        let idle_size = mirrors
            .list()
            .unwrap()
            .into_iter()
            .find(|m| m.key == idle_key)
            .unwrap()
            .size_bytes;

        // A dry run changes nothing.
        let dry = maintain_mirrors(
            &mirrors,
            &Policy::default(),
            now,
            true,
            &CancellationToken::new(),
        )
        .await;
        assert!(dry.dry_run);
        assert_eq!(deleted_keys(&dry), vec![idle_key.clone()]);
        assert!(mirrors.path_for(&idle.url, None).exists());

        let report = pass(&mirrors, &Policy::default(), now).await;
        assert_eq!(deleted_keys(&report), vec![idle_key.clone()]);
        assert_eq!(report.deleted[0].reason, DeleteReason::Idle { days: 31 });
        assert_eq!(report.deleted[0].bytes, idle_size);
        assert_eq!(
            report.deleted[0].repo_url.as_deref(),
            Some(idle.url.as_str())
        );
        assert!(
            report.failed.is_empty() && report.busy.is_empty(),
            "{report:?}"
        );
        assert_eq!(report.remaining, 1);
        // Nothing is left of it, not even a temporary directory.
        assert_eq!(entries(mirrors.dir()), vec![format!("{used_key}.git")]);

        // A later run creates it again from scratch.
        let lease = mirrors.update(&idle.url, None).await.unwrap();
        assert!(!lease.refs.is_empty());
        drop(lease);
        let again = pass(&mirrors, &Policy::default(), now).await;
        assert!(again.deleted.is_empty(), "{again:?}");
        // The idle limit is configurable (`--older-than-days`).
        let strict = Policy {
            mirror_max_idle: TimeDelta::days(7),
            ..Policy::default()
        };
        let later = pass(&mirrors, &strict, now + TimeDelta::days(8)).await;
        let mut expected = vec![idle_key, used_key];
        expected.sort();
        assert_eq!(deleted_keys(&later), expected);
        assert!(entries(mirrors.dir()).is_empty());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_mirror_in_use_is_skipped_until_the_next_pass() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));
        let key = create_mirror(&mirrors, &repo.url).await;
        let later = Utc::now() + TimeDelta::days(31);
        // A run holds the mirror's lock (fetching or cloning).
        let held = mirrors.lock(&repo.url, None).await;
        let report = pass(&mirrors, &Policy::default(), later).await;
        assert!(report.deleted.is_empty());
        assert_eq!(report.busy, vec![key.clone()]);
        assert_eq!(report.remaining, 1);
        assert!(mirrors.path_for(&repo.url, None).exists());
        drop(held);
        let report = pass(&mirrors, &Policy::default(), later).await;
        assert_eq!(deleted_keys(&report), vec![key]);
        assert!(!mirrors.path_for(&repo.url, None).exists());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn gc_packs_a_mirror_that_still_clones_afterwards() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let doomed = commit_on_branch(&repo, "doomed");
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));
        let key = create_mirror(&mirrors, &repo.url).await;
        let path = mirrors.path_for(&repo.url, None);
        // The branch goes away upstream: the fetch prunes it, and its commit is garbage.
        repo.delete_branch("doomed");
        create_mirror(&mirrors, &repo.url).await;
        assert!(git_ok(&path, &["cat-file", "-e", &doomed]));
        let (loose, _) = count_objects(&path);
        assert!(loose > 0, "small fetches leave loose objects");

        let now = Utc::now();
        // Fresh: not due (the first fetch left it as git fetched it).
        let report = pass(&mirrors, &Policy::default(), now).await;
        assert!(report.collected.is_empty(), "{report:?}");
        // Last gc 8 days ago, fetched since: due.
        edit_state(&path, |s| {
            s.last_gc_at = Some(days_before(now, 8));
            s.last_used_at = Some(days_before(now, 1));
        });
        let dry = maintain_mirrors(
            &mirrors,
            &Policy::default(),
            now,
            true,
            &CancellationToken::new(),
        )
        .await;
        assert_eq!(dry.collected.len(), 1);
        assert_eq!(count_objects(&path).0, loose, "a dry run collects nothing");
        let report = pass(&mirrors, &Policy::default(), now).await;
        assert_eq!(report.collected.len(), 1, "{report:?}");
        assert_eq!(report.collected[0].key, key);
        assert_eq!(report.collected[0].reason, GcReason::Scheduled);
        assert!(report.failed.is_empty(), "{report:?}");
        assert_eq!(mirror::read_state(&path).unwrap().last_gc_at, Some(now));
        assert_eq!(count_objects(&path), (0, 1), "everything is in one pack");
        assert!(
            !git_ok(&path, &["cat-file", "-e", &doomed]),
            "unreachable objects are pruned at once"
        );
        assert!(git_ok(&path, &["fsck", "--no-progress"]));
        // No commit-graph or bitmap ends up in the files a clone copies.
        assert!(!path.join("objects/info/commit-graph").exists());
        let packs = entries(&path.join("objects/pack"));
        assert!(packs.iter().all(|f| !f.ends_with(".bitmap")), "{packs:?}");
        assert_eq!(git(&path, &["config", "gc.auto"]).trim(), "0");

        // The mirror still serves runs: fetch and a clone into a workspace.
        let main = repo.commit_to_main("after-gc.txt", "after gc\n", "After the gc");
        let workspace = scratch.path().join("workspace/repo");
        std::fs::create_dir_all(workspace.parent().unwrap()).unwrap();
        let spec = spec(&repo.url);
        let mut host_git = Git::new(
            &workspace,
            &spec,
            ("Host".into(), "host@tinfirm.invalid".into()),
        );
        let lease = mirrors.update(&repo.url, None).await.unwrap();
        let head = host_git
            .clone_from_mirror(&lease.path, &lease.refs, &spec)
            .await
            .unwrap();
        assert_eq!(head, main);
        assert_eq!(
            std::fs::read_to_string(workspace.join("after-gc.txt")).unwrap(),
            "after gc\n"
        );
        drop(lease);

        // Not due again right after, nor a week later without a fetch in between.
        edit_state(&path, |s| s.last_used_at = Some(days_before(now, 1)));
        let report = pass(&mirrors, &Policy::default(), now + TimeDelta::days(8)).await;
        assert!(report.collected.is_empty(), "{report:?}");
        // Many loose objects make it due at once.
        let eager = Policy {
            gc_loose_objects: 1,
            ..Policy::default()
        };
        let (loose, _) = count_objects(&path);
        assert!(loose > 0, "the last fetch left loose objects");
        let report = pass(&mirrors, &eager, now).await;
        assert_eq!(report.collected[0].reason, GcReason::LooseObjects(loose));
        assert_eq!(count_objects(&path).0, 0);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_size_cap_deletes_the_least_recently_used_mirrors_first() {
    with_timeout(120, async {
        let repos: Vec<TestRepo> = (0..3).map(|_| TestRepo::new()).collect();
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Arc::new(Mirrors::new(scratch.path().join("mirrors")));
        let now = Utc::now();
        // Used 3, 1 and 2 days ago; none idle, none due for gc.
        let mut keys = Vec::new();
        for (repo, days) in repos.iter().zip([3, 1, 2]) {
            keys.push(create_mirror(&mirrors, &repo.url).await);
            edit_state(&mirrors.path_for(&repo.url, None), |s| {
                s.last_used_at = Some(days_before(now, days));
                s.last_gc_at = Some(now);
            });
        }
        let size = |key: &str| {
            mirrors
                .list()
                .unwrap()
                .into_iter()
                .find(|m| m.key == key)
                .unwrap()
                .size_bytes
        };
        let (oldest, newest, middle) = (keys[0].clone(), keys[1].clone(), keys[2].clone());

        // Under the cap: nothing happens.
        let total: u64 = keys.iter().map(|k| size(k)).sum();
        let roomy = Policy {
            mirror_max_bytes: Some(total),
            ..Policy::default()
        };
        let report = pass(&mirrors, &roomy, now).await;
        assert!(report.deleted.is_empty(), "{report:?}");
        assert_eq!((report.remaining, report.remaining_bytes), (3, total));

        // A run holds the least recently used mirror: it is never deleted, the next one is.
        let held = mirrors.lock(&repos[0].url, None).await;
        let tight = Policy {
            mirror_max_bytes: Some(size(&oldest) + size(&newest)),
            ..Policy::default()
        };
        let report = pass(&mirrors, &tight, now).await;
        assert_eq!(deleted_keys(&report), vec![middle.clone()]);
        assert_eq!(report.deleted[0].reason, DeleteReason::OverCap);
        assert_eq!(report.busy, vec![oldest.clone()]);
        assert!(mirrors.path_for(&repos[0].url, None).exists());
        drop(held);

        // Recreate it, used 2 days ago again; now the order is oldest, then middle.
        create_mirror(&mirrors, &repos[2].url).await;
        edit_state(&mirrors.path_for(&repos[2].url, None), |s| {
            s.last_used_at = Some(days_before(now, 2));
            s.last_gc_at = Some(now);
        });
        let cap = size(&newest);
        let dry = maintain_mirrors(
            &mirrors,
            &Policy {
                mirror_max_bytes: Some(cap),
                ..Policy::default()
            },
            now,
            true,
            &CancellationToken::new(),
        )
        .await;
        assert_eq!(deleted_keys(&dry), vec![oldest.clone(), middle.clone()]);
        assert_eq!(entries(mirrors.dir()).len(), 3, "a dry run deletes nothing");
        let report = pass(
            &mirrors,
            &Policy {
                mirror_max_bytes: Some(cap),
                ..Policy::default()
            },
            now,
        )
        .await;
        assert_eq!(deleted_keys(&report), vec![oldest, middle]);
        assert!(
            report
                .deleted
                .iter()
                .all(|d| d.reason == DeleteReason::OverCap)
        );
        assert_eq!((report.remaining, report.remaining_bytes), (1, cap));
        assert_eq!(entries(mirrors.dir()), vec![format!("{newest}.git")]);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_deleted_mirror_is_moved_away_before_its_files_are_removed() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let scratch = tempfile::tempdir().unwrap();
        let dir = scratch.path().join("mirrors");
        let mirrors = Mirrors::new(&dir);
        let key = create_mirror(&mirrors, &repo.url).await;

        // Crash after the rename, in the middle of removing the files.
        let lock = mirrors.try_lock_for_maintenance(&key).unwrap();
        let retired = mirrors.retire(&lock).unwrap();
        drop(lock);
        std::fs::remove_dir_all(retired.join("refs")).unwrap();
        std::fs::remove_file(retired.join("HEAD")).unwrap();
        let name = retired.file_name().unwrap().to_string_lossy().into_owned();
        assert!(name.starts_with(&format!(".tmp-delete-{key}-")), "{name}");
        // The half-deleted mirror has no mirror's name: no run and no listing sees it.
        assert!(!mirrors.path_for(&repo.url, None).exists());
        assert!(mirrors.list().unwrap().is_empty());

        // After the restart, the next run creates a complete mirror in its place.
        let restarted = Mirrors::new(&dir);
        let lease = restarted.update(&repo.url, None).await.unwrap();
        assert_eq!(
            lease
                .refs
                .iter()
                .find(|r| r.name == "refs/heads/main")
                .map(|r| r.sha.clone()),
            repo.branch_head("main")
        );
        drop(lease);
        // A creation in progress looks similar, but maintenance leaves it alone.
        let creating = dir.join(format!(".tmp-{key}-0123.git"));
        std::fs::create_dir_all(creating.join("objects")).unwrap();
        let report = pass(&restarted, &Policy::default(), Utc::now()).await;
        assert!(
            report.deleted.is_empty() && report.failed.is_empty(),
            "{report:?}"
        );
        let mut expected = vec![format!(".tmp-{key}-0123.git"), format!("{key}.git")];
        expected.sort();
        assert_eq!(
            entries(&dir),
            expected,
            "the leftover of the deletion is gone"
        );
        // The startup cleanup removes every temporary directory.
        restarted.remove_stale_temp_dirs();
        assert_eq!(entries(&dir), vec![format!("{key}.git")]);
    })
    .await;
}

#[test]
fn idle_session_homes_are_deleted_but_never_under_a_run() {
    let scratch = tempfile::tempdir().unwrap();
    let homes = SessionHomes::new(scratch.path().join("homes"));
    let agent = Uuid::new_v4();
    let (idle_task, active_task, fresh_task) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
    let now = Utc::now();
    let later = now + TimeDelta::days(31);
    let max_idle = TimeDelta::days(30);
    let mut paths = Vec::new();
    for task in [idle_task, active_task, fresh_task] {
        let home = homes.path(agent, task);
        std::fs::create_dir_all(home.join(".claude/projects")).unwrap();
        std::fs::write(home.join(".claude/projects/session.jsonl"), "{}\n").unwrap();
        paths.push(home);
    }
    homes.record_use(&paths[0], now).unwrap();
    // A run on `active_task` has held its home since then.
    let lease = homes.lease(agent, active_task);
    homes.record_use(&paths[1], now).unwrap();
    homes
        .record_use(&paths[2], later - TimeDelta::days(1))
        .unwrap();
    // A home from before stamps existed: its idle time starts at this pass.
    let legacy_agent = Uuid::new_v4();
    let legacy = homes.path(legacy_agent, Uuid::new_v4());
    std::fs::create_dir_all(&legacy).unwrap();

    let dry = homes.prune(max_idle, later, true);
    assert_eq!(dry.deleted.len(), 1);
    assert!(paths[0].exists() && homes.last_used(&legacy).is_none());

    let report = homes.prune(max_idle, later, false);
    assert_eq!(report.deleted.len(), 1, "{report:?}");
    assert_eq!(report.deleted[0].task, idle_task.to_string());
    assert_eq!(report.deleted[0].agent, agent.to_string());
    assert_eq!(report.deleted[0].idle_days, 31);
    assert!(report.deleted[0].bytes >= 3);
    assert_eq!(report.active, 1);
    assert!(!paths[0].exists());
    assert!(
        !paths[0]
            .with_file_name(format!("{idle_task}.last-used"))
            .exists(),
        "its stamp goes too"
    );
    assert!(paths[1].exists() && paths[2].exists());
    assert_eq!(homes.last_used(&legacy), Some(later));
    // Nothing temporary is left in the agent's directory.
    assert!(
        entries(&scratch.path().join(format!("homes/{agent}")))
            .iter()
            .all(|n| !n.starts_with('.')),
    );

    // The run ended: now its home goes as well.
    drop(lease);
    let report = homes.prune(max_idle, later, false);
    assert_eq!(report.deleted.len(), 1, "{report:?}");
    assert_eq!(report.deleted[0].task, active_task.to_string());
    assert!(!paths[1].exists() && paths[2].exists());

    // When the last home of an agent goes, so does the agent's directory.
    let report = homes.prune(max_idle, later + TimeDelta::days(31), false);
    assert_eq!(report.deleted.len(), 2, "{report:?}");
    assert!(entries(&scratch.path().join("homes")).is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_running_host_maintains_mirrors_and_homes_in_the_background() {
    with_timeout(120, async {
        let idle_repo = TestRepo::new();
        let used_repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.maintenance_every = Some(Duration::from_millis(200));
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        // What runs 40 days ago left behind, next to what a run left yesterday.
        let long_ago = days_before(Utc::now(), 40);
        let yesterday = days_before(Utc::now(), 1);
        let mirrors = &host.host.mirrors;
        for (repo, used) in [(&idle_repo, long_ago), (&used_repo, yesterday)] {
            drop(mirrors.update(&repo.url, None).await.unwrap());
            edit_state(&mirrors.path_for(&repo.url, None), |s| {
                s.last_used_at = Some(used);
                s.last_gc_at = Some(Utc::now());
            });
        }
        let homes = &host.host.homes;
        let agent = Uuid::new_v4();
        let (idle_home, used_home) = (
            homes.path(agent, Uuid::new_v4()),
            homes.path(agent, Uuid::new_v4()),
        );
        for (home, used) in [(&idle_home, long_ago), (&used_home, yesterday)] {
            // The stamp first: a pass that sees a home without one starts its idle time now.
            homes.record_use(home, used).unwrap();
            std::fs::create_dir_all(home.join(".codex")).unwrap();
        }

        let idle_mirror = mirrors.path_for(&idle_repo.url, None);
        let deadline = std::time::Instant::now() + Duration::from_secs(60);
        while idle_mirror.exists() || idle_home.exists() {
            assert!(
                std::time::Instant::now() < deadline,
                "maintenance did not run in the background"
            );
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        assert!(mirrors.path_for(&used_repo.url, None).exists());
        assert!(used_home.exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}
