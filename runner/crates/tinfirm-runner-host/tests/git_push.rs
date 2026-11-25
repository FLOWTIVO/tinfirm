//! The runner host's own receive-pack client (ADR-0112, path C, slice 7) against real git: a
//! `git http-backend` that takes pushes and requires the run's token, as a code host does, and
//! small servers that answer what a hostile or broken code host could.
//!
//! - Pushes: a new branch, an update with the lease, an empty push, SHA-256 repositories, an
//!   empty repository; with the pack made by `git pack-objects` without a token, and in a
//!   reader.
//! - The lease: a branch that moved, was created or was deleted is refused before anything is
//!   sent; one that changes between the ref discovery and the push is refused by the repository,
//!   which checks `old` again.
//! - The token: read at each request, so a revoked one pushes nothing; never in what is
//!   reported; never sent after a redirect.
//! - The pack: streamed in chunks, never held whole; a pack cut short is never taken.

#![cfg(target_os = "linux")]

mod common;

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use common::git_http::{GitHttpServer, SeenRequest, assert_pushed_by, test_git_authorization};
use common::*;
use tinfirm_protocol::{GitCredentials, Secret, WorkspaceSpec};
use tinfirm_runner_host::git::{Git, GitOutput, MirrorRef};
use tinfirm_runner_host::git_push::{Outcome, PushClient, PushSettings};
use tinfirm_runner_host::reader::Reader;
use uuid::Uuid;

const BIN: &str = env!("CARGO_BIN_EXE_tinfirm-runner-host");

/// The binary that is the reader: the build's, or `TINFIRM_TEST_BIN` where the user who runs
/// the tests cannot reach the build's directory.
fn bin() -> PathBuf {
    std::env::var_os("TINFIRM_TEST_BIN")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(BIN))
}
const BRANCH: &str = "agent/TEST-1";

/// Held shared by every test of this file for its whole body, and alone by a test that
/// measures the memory of the whole process, which the tests of a file share: while one
/// measures, no other runs.
static QUIET: tokio::sync::RwLock<()> = tokio::sync::RwLock::const_new(());

/// A test's body with an overall timeout, beside the file's other tests.
async fn beside<F: std::future::Future<Output = ()>>(secs: u64, test: F) {
    let _beside = QUIET.read().await;
    with_timeout(secs, test).await
}

/// A test's body with an overall timeout, while no other test of this file runs.
async fn alone<F: std::future::Future<Output = ()>>(secs: u64, test: F) {
    let _alone = QUIET.write().await;
    with_timeout(secs, test).await
}
const SETTINGS: PushSettings = PushSettings {
    client: PushClient::Host,
    allow_http: true,
};

/// How the pack is made: by `git pack-objects` as a child of the host's without a token, or
/// in a reader (a clone that holds a sandbox's objects).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Packer {
    Plain,
    Reader,
}

/// A repository served by `git http-backend` and the host's clone of it.
struct Scene {
    _dir: tempfile::TempDir,
    bare: PathBuf,
    http: Option<GitHttpServer>,
    work: PathBuf,
    spec: WorkspaceSpec,
}

fn spec(url: &str) -> WorkspaceSpec {
    WorkspaceSpec {
        component_id: Uuid::new_v4(),
        dir: "repo".into(),
        name: "Repo".into(),
        repo_url: url.into(),
        default_branch: "main".into(),
        branch: BRANCH.into(),
        git: Some(GitCredentials::new(
            "x-access-token",
            Secret::new(TEST_GIT_TOKEN),
        )),
        author_name: None,
        author_email: None,
        read_only: false,
    }
}

impl Scene {
    /// A bare repository of `format` (with `main` when `seeded`) and its server.
    async fn new(format: &str, seeded: bool) -> Scene {
        let dir = tempfile::tempdir().unwrap();
        let bare = dir.path().join("origin.git");
        let fmt = format!("--object-format={format}");
        git(
            dir.path(),
            &[
                "init",
                "--bare",
                "-q",
                "-b",
                "main",
                &fmt,
                bare.to_str().unwrap(),
            ],
        );
        let work = dir.path().join("workspace/repo");
        std::fs::create_dir_all(&work).unwrap();
        if seeded {
            let seed = dir.path().join("seed");
            git(
                dir.path(),
                &["init", "-q", "-b", "main", &fmt, seed.to_str().unwrap()],
            );
            std::fs::write(seed.join("README.md"), "# test repo\n").unwrap();
            git(&seed, &["add", "-A"]);
            git(&seed, &["commit", "-q", "-m", "Initial commit"]);
            git(
                &seed,
                &["push", "-q", bare.to_str().unwrap(), "HEAD:refs/heads/main"],
            );
        }
        let http = GitHttpServer::start(dir.path(), Some(test_git_authorization())).await;
        let url = format!("{}/origin.git", http.url);
        Scene {
            spec: spec(&url),
            _dir: dir,
            bare,
            http: Some(http),
            work,
        }
    }

    fn http(&self) -> &GitHttpServer {
        self.http.as_ref().unwrap()
    }

    /// The host's clone, as a run makes it: the repository's refs now are the lease's base.
    fn clone_host(&self, packer: Packer) -> Git {
        let refs = refs_of(&self.bare);
        let parent = self.work.parent().unwrap();
        std::fs::remove_dir_all(&self.work).unwrap();
        if refs.is_empty() {
            let fmt = format!(
                "--object-format={}",
                git(&self.bare, &["rev-parse", "--show-object-format"]).trim()
            );
            git(
                parent,
                &[
                    "init",
                    "-q",
                    "-b",
                    BRANCH,
                    &fmt,
                    self.work.to_str().unwrap(),
                ],
            );
        } else {
            git(
                parent,
                &[
                    "clone",
                    "-q",
                    "--no-hardlinks",
                    self.bare.to_str().unwrap(),
                    self.work.to_str().unwrap(),
                ],
            );
            let start = if refs
                .iter()
                .any(|r| r.name == format!("refs/heads/{BRANCH}"))
            {
                format!("origin/{BRANCH}")
            } else {
                "origin/main".to_string()
            };
            git(
                &self.work,
                &["checkout", "-q", "--no-track", "-B", BRANCH, &start],
            );
        }
        let mut host = Git::restored(
            &self.work,
            &self.spec,
            (
                "Tinfirm agent Tester".into(),
                "agent@tinfirm.invalid".into(),
            ),
            refs,
            None,
        );
        if packer == Packer::Reader {
            host.read_through(Reader::new(bin()).allowing_unconfined(true));
        }
        host
    }

    /// The agent's commit in the host's clone; returns HEAD.
    fn commit(&self, file: &str, content: &[u8]) -> String {
        std::fs::write(self.work.join(file), content).unwrap();
        git(&self.work, &["add", "-A"]);
        git(&self.work, &["commit", "-q", "-m", &format!("add {file}")]);
        head(&self.work)
    }

    fn tip(&self) -> Option<String> {
        let out = std::process::Command::new("git")
            .args([
                "rev-parse",
                "--verify",
                "--quiet",
                &format!("refs/heads/{BRANCH}"),
            ])
            .current_dir(&self.bare)
            .output()
            .unwrap();
        out.status
            .success()
            .then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
    }

    /// A commit of someone else's in the repository, on no branch, each one new; returns its
    /// name.
    fn their_commit(&self) -> String {
        let tree = git(&self.bare, &["rev-parse", "main^{tree}"]);
        let message = format!("Their change {}", Uuid::new_v4());
        git(
            &self.bare,
            &["commit-tree", tree.trim(), "-p", "main", "-m", &message],
        )
        .trim()
        .to_string()
    }

    fn set_branch(&self, sha: Option<&str>) {
        let name = format!("refs/heads/{BRANCH}");
        match sha {
            Some(sha) => git(&self.bare, &["update-ref", &name, sha]),
            None => git(&self.bare, &["update-ref", "-d", &name]),
        };
    }
}

fn head(dir: &Path) -> String {
    git(dir, &["rev-parse", "HEAD"]).trim().to_string()
}

fn refs_of(bare: &Path) -> Vec<MirrorRef> {
    git(
        bare,
        &[
            "for-each-ref",
            "--format=%(objectname) %(refname)",
            "refs/heads",
            "refs/tags",
        ],
    )
    .lines()
    .filter_map(|l| l.split_once(' '))
    .map(|(sha, name)| MirrorRef {
        name: name.to_string(),
        sha: sha.to_string(),
    })
    .collect()
}

fn pushes(seen: &[SeenRequest]) -> Vec<&SeenRequest> {
    seen.iter()
        .filter(|r| r.method == "POST" && r.path.ends_with("/git-receive-pack"))
        .collect()
}

/// Nothing of the token in what the push reports.
fn assert_no_token(out: &GitOutput) {
    let authorization = test_git_authorization();
    let basic = authorization.trim_start_matches("Basic ");
    for text in [&out.stdout, &out.stderr] {
        assert!(!text.contains(TEST_GIT_TOKEN), "{text}");
        assert!(!text.contains(basic), "{text}");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn pushes_a_new_branch_and_an_update_with_the_lease() {
    beside(120, async {
        for packer in [Packer::Plain, Packer::Reader] {
            let scene = Scene::new("sha1", true).await;
            let host = scene.clone_host(packer);
            let first = scene.commit("one.txt", b"one\n");
            let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
            assert!(out.status.success(), "{packer:?}: {out:?}");
            assert!(out.stdout.contains("[new branch]"), "{out:?}");
            assert_no_token(&out);
            assert_eq!(scene.tip().as_deref(), Some(first.as_str()));
            git(&scene.bare, &["fsck", "--strict", "--no-dangling"]);
            // The ref discovery and the push, each with the token; the small pack with a length.
            let seen = scene.http().seen();
            assert_eq!(seen.len(), 2, "{seen:?}");
            assert_eq!(seen[0].method, "GET");
            assert_eq!(seen[0].query, "service=git-receive-pack");
            assert_eq!(seen[0].git_protocol, None, "a push is protocol v0");
            for request in &seen {
                assert_eq!(
                    request.authorization.as_deref(),
                    Some(test_git_authorization().as_str())
                );
                assert_eq!(request.status, 200);
            }
            assert!(
                seen[1].content_length.is_some() && !seen[1].chunked,
                "{seen:?}"
            );
            assert_eq!(
                assert_pushed_by(&seen, true),
                2,
                "the host's own client pushed"
            );

            // A run of later: the branch exists at clone time, and the agent rewrites it (a
            // rebase): the lease allows it, and HEAD replaces the branch.
            let host = scene.clone_host(packer);
            git(&scene.work, &["reset", "-q", "--hard", "origin/main"]);
            let rewritten = scene.commit("two.txt", b"two\n");
            let out = host
                .push_with(BRANCH, Some(rewritten.clone()), &SETTINGS)
                .await
                .unwrap();
            assert!(out.status.success(), "{packer:?}: {out:?}");
            assert_eq!(scene.tip().as_deref(), Some(rewritten.as_str()));
            git(&scene.bare, &["fsck", "--strict", "--no-dangling"]);

            // An empty push: HEAD is the branch's tip already. Nothing is sent but the ref
            // discovery.
            let before = pushes(&scene.http().seen()).len();
            let host = scene.clone_host(packer);
            let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
            assert!(out.status.success(), "{out:?}");
            assert!(out.stdout.contains("[up to date]"), "{out:?}");
            assert_eq!(pushes(&scene.http().seen()).len(), before);
            assert_eq!(scene.tip().as_deref(), Some(rewritten.as_str()));
            assert_pushed_by(&scene.http().seen(), true);
        }
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn pushes_to_sha256_repositories_and_to_an_empty_one() {
    beside(120, async {
        for (format, seeded, packer) in [
            ("sha256", true, Packer::Plain),
            ("sha256", true, Packer::Reader),
            ("sha256", false, Packer::Reader),
            ("sha1", false, Packer::Plain),
        ] {
            let scene = Scene::new(format, seeded).await;
            let host = scene.clone_host(packer);
            let pushed = scene.commit("one.txt", b"one\n");
            assert_eq!(pushed.len(), if format == "sha256" { 64 } else { 40 });
            let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
            assert!(
                out.status.success(),
                "{format} {seeded} {packer:?}: {out:?}"
            );
            assert_eq!(scene.tip().as_deref(), Some(pushed.as_str()));
            assert_eq!(assert_pushed_by(&scene.http().seen(), true), 2);
            git(&scene.bare, &["fsck", "--strict", "--no-dangling"]);
        }
        // A sha1 clone and a sha256 repository: refused before anything is sent.
        let scene = Scene::new("sha256", true).await;
        let host = Git::restored(
            &scene.work,
            &scene.spec,
            ("T".into(), "t@tinfirm.invalid".into()),
            Vec::new(),
            None,
        );
        let out = tinfirm_runner_host::git_push::push_outcome(&host, BRANCH, &"a".repeat(40), true)
            .await
            .unwrap();
        assert_eq!(
            out,
            Outcome::Failed {
                error: "the receiving end does not support this repository's hash algorithm".into(),
                messages: Vec::new()
            }
        );
        assert!(pushes(&scene.http().seen()).is_empty());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_branch_that_moved_was_created_or_was_deleted_is_refused_before_anything_is_sent() {
    beside(120, async {
        // (whether the branch exists at clone time, what happens to it during the run)
        for (existed, deleted) in [(true, false), (false, false), (true, true)] {
            let scene = Scene::new("sha1", true).await;
            if existed {
                let tip = scene.their_commit();
                scene.set_branch(Some(&tip));
            }
            let host = scene.clone_host(Packer::Plain);
            scene.commit("work.txt", b"the agent's work\n");
            let theirs = if deleted {
                scene.set_branch(None);
                None
            } else {
                let theirs = scene.their_commit();
                scene.set_branch(Some(&theirs));
                Some(theirs)
            };
            let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
            assert!(!out.status.success(), "{out:?}");
            assert!(out.stdout.contains("[rejected] (stale info)"), "{out:?}");
            assert!(
                out.stderr.contains("error: failed to push some refs to"),
                "{out:?}"
            );
            assert_eq!(scene.tip(), theirs, "existed {existed}, deleted {deleted}");
            assert!(pushes(&scene.http().seen()).is_empty());
            assert_eq!(
                assert_pushed_by(&scene.http().seen(), true),
                1,
                "the ref discovery"
            );
        }
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_repository_checks_the_lease_again_when_the_branch_changes_after_the_discovery() {
    beside(120, async {
        for existed in [true, false] {
            let scene = Scene::new("sha1", true).await;
            if existed {
                let tip = scene.their_commit();
                scene.set_branch(Some(&tip));
            }
            let host = scene.clone_host(Packer::Reader);
            scene.commit("work.txt", b"the agent's work\n");
            let theirs = scene.their_commit();
            let bare = scene.bare.clone();
            let moved = theirs.clone();
            scene.http().before_push(move || {
                git(
                    &bare,
                    &["update-ref", &format!("refs/heads/{BRANCH}"), &moved],
                );
            });
            let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
            assert!(!out.status.success(), "{out:?}");
            assert_no_token(&out);
            // As `git push --porcelain` words it, which the run's reasons read: a
            // `[remote rejected]` update and the repository's own error.
            assert!(
                out.stdout
                    .contains("[remote rejected] (failed to update ref)"),
                "{out:?}"
            );
            assert!(
                out.stderr.contains("remote: error: cannot lock ref"),
                "{out:?}"
            );
            if existed {
                assert!(out.stderr.contains("but expected"), "{out:?}");
            } else {
                assert!(out.stderr.contains("reference already exists"), "{out:?}");
            }
            assert_eq!(scene.tip().as_deref(), Some(theirs.as_str()));
            assert_eq!(pushes(&scene.http().seen()).len(), 1);
            assert_eq!(assert_pushed_by(&scene.http().seen(), true), 2);
        }
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_token_revoked_before_the_push_pushes_nothing() {
    beside(120, async {
        let scene = Scene::new("sha1", true).await;
        let host = scene.clone_host(Packer::Plain);
        scene.commit("work.txt", b"work\n");
        host.tokens().run().revoke(
            &[scene.spec.component_id],
            "REVOKED",
            "the token was revoked",
        );
        let error = host.push_with(BRANCH, None, &SETTINGS).await.unwrap_err();
        assert_eq!(format!("{error:#}"), "not pushed: the token was revoked");
        assert!(scene.http().seen().is_empty());
        assert_eq!(scene.tip(), None);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_repository_that_refuses_the_push_is_reported_as_git_reports_it() {
    beside(120, async {
        let scene = Scene::new("sha1", true).await;
        let hook = scene.bare.join("hooks/pre-receive");
        std::fs::write(
            &hook,
            "#!/bin/sh\necho 'pushes are closed for this test' >&2\nexit 1\n",
        )
        .unwrap();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&hook, std::fs::Permissions::from_mode(0o755)).unwrap();
        let host = scene.clone_host(Packer::Plain);
        scene.commit("work.txt", b"work\n");
        let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
        assert!(!out.status.success());
        assert!(
            out.stdout
                .contains("[remote rejected] (pre-receive hook declined)"),
            "{out:?}"
        );
        assert!(
            out.stderr
                .contains("remote: pushes are closed for this test"),
            "{out:?}"
        );
        assert_eq!(scene.tip(), None);
        assert_eq!(assert_pushed_by(&scene.http().seen(), true), 2);
    })
    .await;
}

/// A file of `len` bytes that do not compress (xorshift64*), written 64 KiB at a time.
fn incompressible(path: &Path, len: usize, seed: u64) {
    use std::io::Write;
    let mut file = std::io::BufWriter::new(std::fs::File::create(path).unwrap());
    let mut state = seed.wrapping_mul(0x9e37_79b9_7f4a_7c15) | 1;
    let mut chunk = vec![0u8; 64 * 1024];
    let mut left = len;
    while left > 0 {
        for word in chunk.chunks_mut(8) {
            state ^= state >> 12;
            state ^= state << 25;
            state ^= state >> 27;
            let v = state.wrapping_mul(0x2545_f491_4f6c_dd1d).to_le_bytes();
            word.copy_from_slice(&v[..word.len()]);
        }
        let n = left.min(chunk.len());
        file.write_all(&chunk[..n]).unwrap();
        left -= n;
    }
    file.flush().unwrap();
}

/// This process's resident set: (now, its peak since the last reset), in KiB.
fn rss() -> (u64, u64) {
    let status = std::fs::read_to_string("/proc/self/status").unwrap();
    let field = |name: &str| {
        status
            .lines()
            .find_map(|l| l.strip_prefix(name))
            .and_then(|v| v.trim().trim_end_matches(" kB").trim().parse().ok())
            .unwrap_or(0)
    };
    (field("VmRSS:"), field("VmHWM:"))
}

/// Resets the peak of the resident set (`/proc/self/clear_refs`, 5); `false` where the kernel
/// does not let this process do that.
fn reset_peak() -> bool {
    std::fs::write("/proc/self/clear_refs", "5").is_ok()
}

/// Pushes `size` bytes that do not compress through a reader, and returns the seconds it
/// took, the peak growth of this process's memory in KiB (if it could be measured) and the
/// request that carried it.
async fn push_large(size: usize) -> (f64, Option<u64>, SeenRequest) {
    let scene = Scene::new("sha1", true).await;
    let host = scene.clone_host(Packer::Reader);
    incompressible(&scene.work.join("large.bin"), size, 7);
    git(&scene.work, &["add", "-A"]);
    git(&scene.work, &["commit", "-q", "-m", "large"]);
    let pushed = head(&scene.work);
    let measured = reset_peak();
    let (before, _) = rss();
    let started = Instant::now();
    let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
    let seconds = started.elapsed().as_secs_f64();
    let (_, peak) = rss();
    assert!(out.status.success(), "{out:?}");
    assert_eq!(scene.tip().as_deref(), Some(pushed.as_str()));
    assert_eq!(assert_pushed_by(&scene.http().seen(), true), 2);
    let seen = pushes(&scene.http().seen())[0].clone();
    (seconds, measured.then(|| peak.saturating_sub(before)), seen)
}

/// A pack of 48 MiB goes in chunks (no length), and this process (the client, and the test's
/// server, which streams too) never holds it: its memory grows by less than half the pack
/// (by about 6 MiB when it was measured). The measure is the whole process's, so the test
/// runs alone in it (`alone`): beside the file's other tests it counted their memory too.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_large_pack_is_streamed_in_chunks_and_never_held_whole() {
    alone(300, async {
        let size = 48 << 20;
        let (seconds, growth, request) = push_large(size).await;
        assert!(
            request.chunked && request.content_length.is_none(),
            "{request:?}"
        );
        eprintln!("48 MiB pushed in {seconds:.1} s; peak growth {growth:?} KiB");
        match growth {
            Some(kib) => assert!(
                kib < (size as u64 / 2) / 1024,
                "the process grew by {kib} KiB for a 48 MiB pack"
            ),
            None => eprintln!("SKIPPED (the memory part): this kernel does not reset the peak RSS"),
        }
    })
    .await;
}

/// The same with 2 GiB: by hand (`--ignored`), for its time and memory.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "pushes 2 GiB: run by hand for the time and memory it takes"]
async fn a_pack_of_2_gib_is_streamed() {
    alone(3600, async {
        let (seconds, growth, request) = push_large(2 << 30).await;
        assert!(request.chunked);
        eprintln!("2 GiB pushed in {seconds:.1} s; peak growth {growth:?} KiB");
        if let Some(kib) = growth {
            assert!(kib < 64 * 1024, "{kib} KiB");
        }
    })
    .await;
}

/// A pack's writer that fails in the middle of its pack (an object it cannot read): the
/// request is broken off, and the repository takes nothing.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_pack_cut_short_is_never_taken() {
    beside(120, async {
        let scene = Scene::new("sha1", true).await;
        let host = scene.clone_host(Packer::Plain);
        // The older commit has the blob that breaks, the newer one (HEAD) a large good one and
        // not the other: git writes HEAD's objects first. Without a delta search (which would
        // read the broken blob before writing anything), git meets it only after more than a
        // MiB of pack has gone out.
        git(&scene.work, &["config", "pack.window", "0"]);
        incompressible(&scene.work.join("z.bin"), 1 << 20, 2);
        git(&scene.work, &["add", "-A"]);
        git(&scene.work, &["commit", "-q", "-m", "the blob that breaks"]);
        let blob = git(&scene.work, &["rev-parse", "HEAD:z.bin"])
            .trim()
            .to_string();
        std::fs::remove_file(scene.work.join("z.bin")).unwrap();
        incompressible(&scene.work.join("a.bin"), 3 << 20, 1);
        git(&scene.work, &["add", "-A"]);
        git(&scene.work, &["commit", "-q", "-m", "a good blob"]);
        // The blob's loose object loses its end: its header still reads, its content does not.
        let path = scene
            .work
            .join(".git/objects")
            .join(&blob[..2])
            .join(&blob[2..]);
        let bytes = std::fs::read(&path).unwrap();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        std::fs::write(&path, &bytes[..bytes.len() / 2]).unwrap();
        let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
        assert!(!out.status.success(), "{out:?}");
        assert!(out.stderr.contains("git pack-objects failed"), "{out:?}");
        assert_eq!(scene.tip(), None);
        // The pack's first MiB went out in chunks before the writer failed: the request was
        // broken off, and the repository did not update the branch.
        let seen = scene.http().seen();
        let pushes = pushes(&seen);
        assert_eq!(pushes.len(), 1, "{seen:?}");
        assert!(pushes[0].chunked, "{seen:?}");
        assert_pushed_by(&seen, true);
    })
    .await;
}

// ----- hostile and broken code hosts ---------------------------------------------------------

/// What a fake code host answers: status, content type, extra headers, body.
type Answer = (u16, &'static str, Vec<(&'static str, String)>, Vec<u8>);

/// A request a fake code host got: its method, its target and its `Authorization`.
type Seen = (String, String, Option<String>);
/// What makes a fake code host's answer to the ref discovery, from `main`'s tip.
type InfoAnswer = Box<dyn Fn(&str) -> Answer>;
/// What a case expects of the outcome.
type Check = Box<dyn Fn(&Outcome) -> bool>;

/// A code host that answers `info` to the ref discovery and `push` to the push, and records
/// each request's method, path and `Authorization`.
struct FakeHost {
    url: String,
    seen: Arc<Mutex<Vec<Seen>>>,
    /// The bodies of the pushes.
    bodies: Arc<Mutex<Vec<Vec<u8>>>>,
}

async fn fake_host(info: Answer, push: Answer) -> FakeHost {
    fake_host_with(info, push, None).await
}

/// [`fake_host`], which runs `on_get` before it answers the ref discovery.
async fn fake_host_with(
    info: Answer,
    push: Answer,
    on_get: Option<Arc<dyn Fn() + Send + Sync>>,
) -> FakeHost {
    use http_body_util::{BodyExt, Full};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/origin.git", listener.local_addr().unwrap());
    let seen = Arc::new(Mutex::new(Vec::new()));
    let seen_task = seen.clone();
    let bodies = Arc::new(Mutex::new(Vec::new()));
    let bodies_task = bodies.clone();
    let answers = Arc::new((info, push));
    tokio::spawn(async move {
        while let Ok((stream, _)) = listener.accept().await {
            let seen = seen_task.clone();
            let bodies = bodies_task.clone();
            let answers = answers.clone();
            let on_get = on_get.clone();
            tokio::spawn(async move {
                let service = hyper::service::service_fn(
                    move |req: hyper::Request<hyper::body::Incoming>| {
                        let seen = seen.clone();
                        let bodies = bodies.clone();
                        let answers = answers.clone();
                        let on_get = on_get.clone();
                        async move {
                            let authorization = req
                                .headers()
                                .get("authorization")
                                .map(|v| v.to_str().unwrap_or_default().to_string());
                            let method = req.method().to_string();
                            seen.lock().unwrap().push((
                                method.clone(),
                                req.uri().to_string(),
                                authorization,
                            ));
                            let body = req
                                .into_body()
                                .collect()
                                .await
                                .map(|b| b.to_bytes().to_vec())
                                .unwrap_or_default();
                            if method == "POST" {
                                bodies.lock().unwrap().push(body);
                            } else if let Some(on_get) = &on_get {
                                on_get();
                            }
                            let (status, content_type, headers, body) = if method == "GET" {
                                &answers.0
                            } else {
                                &answers.1
                            };
                            let mut response = hyper::Response::new(Full::new(
                                hyper::body::Bytes::from(body.clone()),
                            ));
                            *response.status_mut() = hyper::StatusCode::from_u16(*status).unwrap();
                            response
                                .headers_mut()
                                .insert("content-type", content_type.parse().unwrap());
                            for (name, value) in headers {
                                response.headers_mut().insert(*name, value.parse().unwrap());
                            }
                            Ok::<_, std::convert::Infallible>(response)
                        }
                    },
                );
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), service)
                    .await;
            });
        }
    });
    FakeHost { url, seen, bodies }
}

fn pkt(payload: &[u8]) -> Vec<u8> {
    let mut out = format!("{:04x}", payload.len() + 4).into_bytes();
    out.extend_from_slice(payload);
    out
}

const ADVERTISEMENT: &str = "application/x-git-receive-pack-advertisement";
const RESULT: &str = "application/x-git-receive-pack-result";

/// A ref advertisement of `main` at `main` and the capabilities `caps`.
fn advertisement(main: &str, caps: &str) -> Vec<u8> {
    let mut body = pkt(b"# service=git-receive-pack\n");
    body.extend_from_slice(b"0000");
    body.extend(pkt(format!("{main} refs/heads/main\0{caps}\n").as_bytes()));
    body.extend_from_slice(b"0000");
    body
}

/// A report inside side-band-64k: `lines`, after `before` (other side-band packets).
fn sideband_report(before: &[Vec<u8>], lines: &[&str]) -> Vec<u8> {
    let mut out: Vec<u8> = before.concat();
    let mut inner = Vec::new();
    for line in lines {
        inner.extend(pkt(format!("{line}\n").as_bytes()));
    }
    inner.extend_from_slice(b"0000");
    let mut band = vec![1u8];
    band.extend(inner);
    out.extend(pkt(&band));
    out.extend_from_slice(b"0000");
    out
}

/// The host's clone of a seeded repository, whose URL is `url` (a fake host's), one commit ahead
/// of `main`; with `main`'s tip.
fn host_for(url: &str) -> (tempfile::TempDir, Git, String) {
    let dir = tempfile::tempdir().unwrap();
    let work = dir.path().join("repo");
    git(
        dir.path(),
        &["init", "-q", "-b", BRANCH, work.to_str().unwrap()],
    );
    std::fs::write(work.join("README.md"), "x\n").unwrap();
    git(&work, &["add", "-A"]);
    git(&work, &["commit", "-q", "-m", "base"]);
    let main = head(&work);
    std::fs::write(work.join("work.txt"), "work\n").unwrap();
    git(&work, &["add", "-A"]);
    git(&work, &["commit", "-q", "-m", "work"]);
    let base = vec![MirrorRef {
        name: "refs/heads/main".into(),
        sha: main.clone(),
    }];
    let host = Git::restored(
        &work,
        &spec(url),
        ("T".into(), "t@tinfirm.invalid".into()),
        base,
        None,
    );
    (dir, host, main)
}

const CAPS: &str = "report-status side-band-64k quiet ofs-delta agent=fake/1";

async fn outcome_against(info: impl FnOnce(&str) -> Answer, push: Answer) -> (Outcome, FakeHost) {
    // The clone first: the advertisement names its `main`.
    let (_dir, host, main) = host_for("http://127.0.0.1:9/origin.git");
    let fake = fake_host(info(&main), push).await;
    let host = Git::restored(
        host.dir(),
        &spec(&fake.url),
        ("T".into(), "t@tinfirm.invalid".into()),
        host.base_refs().to_vec(),
        None,
    );
    let head = head(host.dir());
    let outcome = tinfirm_runner_host::git_push::push_outcome(&host, BRANCH, &head, true)
        .await
        .unwrap();
    // The text the run would report holds no token.
    assert_no_token(&outcome.output(&fake.url, &format!("refs/heads/{BRANCH}")));
    (outcome, fake)
}

fn failed(outcome: &Outcome) -> &str {
    match outcome {
        Outcome::Failed { error, .. } => error,
        other => panic!("not a failure: {other:?}"),
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_redirect_is_followed_nowhere_and_the_token_goes_nowhere_else() {
    beside(60, async {
        let elsewhere = fake_host(
            (
                200,
                ADVERTISEMENT,
                vec![],
                advertisement(&"0".repeat(40), CAPS),
            ),
            (200, RESULT, vec![], Vec::new()),
        )
        .await;
        for status in [301, 302, 307, 308] {
            let location = format!("{}/info/refs?service=git-receive-pack", elsewhere.url);
            let (outcome, fake) = outcome_against(
                |_| {
                    (
                        status,
                        "text/html",
                        vec![("location", location.clone())],
                        b"<a>moved</a>".to_vec(),
                    )
                },
                (200, RESULT, vec![], Vec::new()),
            )
            .await;
            let error = failed(&outcome);
            assert!(
                error.contains("redirect") && error.contains("follows no redirect"),
                "{error}"
            );
            assert_eq!(fake.seen.lock().unwrap().len(), 1);
        }
        assert!(
            elsewhere.seen.lock().unwrap().is_empty(),
            "the redirect was followed"
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn answers_that_are_no_ref_advertisement_are_refused_before_any_push() {
    beside(60, async {
        let cases: Vec<(&str, InfoAnswer)> = vec![
            (
                "returned error: 401",
                Box::new(|_| {
                    (
                        401,
                        "text/plain",
                        vec![("www-authenticate", "Basic realm=\"x\"".into())],
                        b"no".to_vec(),
                    )
                }),
            ),
            (
                "returned error: 403",
                Box::new(|_| (403, "text/plain", vec![], b"forbidden".to_vec())),
            ),
            (
                "returned error: 404",
                Box::new(|_| (404, "text/html", vec![], b"<html>not found</html>".to_vec())),
            ),
            (
                "is not `application/x-git-receive-pack-advertisement`",
                Box::new(|_| {
                    (
                        200,
                        "text/html",
                        vec![],
                        b"<!DOCTYPE html><html>Sign in</html>".to_vec(),
                    )
                }),
            ),
            (
                "not a smart git server",
                Box::new(|_| (200, ADVERTISEMENT, vec![], b"<!DOCTYPE html>".to_vec())),
            ),
            (
                "encoded",
                Box::new(|main| {
                    (
                        200,
                        ADVERTISEMENT,
                        vec![("content-encoding", "gzip".into())],
                        advertisement(main, CAPS),
                    )
                }),
            ),
            (
                "not `# service=git-receive-pack`",
                Box::new(|_| {
                    let mut body = pkt(b"# service=git-upload-pack\n");
                    body.extend_from_slice(b"0000");
                    (200, ADVERTISEMENT, vec![], body)
                }),
            ),
            (
                "no report-status",
                Box::new(|main| {
                    (
                        200,
                        ADVERTISEMENT,
                        vec![],
                        advertisement(main, "side-band-64k ofs-delta"),
                    )
                }),
            ),
            (
                "named `refs/heads/a\u{1b}b`",
                Box::new(|main| {
                    let mut body = advertisement(main, CAPS);
                    body.truncate(body.len() - 4);
                    body.extend(pkt(format!("{main} refs/heads/a\x1bb\n").as_bytes()));
                    body.extend_from_slice(b"0000");
                    (200, ADVERTISEMENT, vec![], body)
                }),
            ),
        ];
        for (expected, info) in cases {
            let (outcome, fake) = outcome_against(info, (200, RESULT, vec![], Vec::new())).await;
            let error = failed(&outcome).to_string();
            let shown = error.replace('?', "\u{1b}");
            assert!(
                error.contains(expected) || shown.contains(expected),
                "{expected}: {error}"
            );
            let seen = fake.seen.lock().unwrap();
            assert!(
                seen.iter().all(|(method, _, _)| method == "GET"),
                "{expected}: {seen:?}"
            );
        }
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn hostile_or_broken_reports_are_failures_and_never_a_push() {
    beside(60, async {
        let band = |b: u8, text: &str| {
            let mut data = vec![b];
            data.extend_from_slice(text.as_bytes());
            pkt(&data)
        };
        let info = |main: &str| (200, ADVERTISEMENT, vec![], advertisement(main, CAPS));
        // The repository took it.
        let (outcome, fake) = outcome_against(
            info,
            (200, RESULT, vec![], sideband_report(&[band(2, "Resolving deltas: done.\n")], &["unpack ok", &format!("ok refs/heads/{BRANCH}")])),
        )
        .await;
        assert!(matches!(outcome, Outcome::Pushed { created: true, .. }), "{outcome:?}");
        // The command, as the repository got it: the zero id (a new branch), HEAD, the ref, and
        // the capabilities the repository named, with the token on both requests.
        let seen = fake.seen.lock().unwrap().clone();
        assert_eq!(seen.len(), 2);
        assert!(seen.iter().all(|(_, _, a)| a.as_deref() == Some(test_git_authorization().as_str())));

        let cases: Vec<(Answer, Check)> = vec![
            ((200, RESULT, vec![], band(3, "fatal: the disk is full\n")), Box::new(|o| failed(o) == "remote error: fatal: the disk is full?")),
            ((200, RESULT, vec![], band(5, "x")), Box::new(|o| failed(o).contains("band 5"))),
            ((200, RESULT, vec![], sideband_report(&[], &["unpack index-pack abnormal exit", &format!("ng refs/heads/{BRANCH} unpacker error")])),
                Box::new(|o| matches!(o, Outcome::UnpackFailed { reason, .. } if reason == "index-pack abnormal exit"))),
            ((200, RESULT, vec![], sideband_report(&[band(2, "error: hook said no\n")], &["unpack ok", &format!("ng refs/heads/{BRANCH} hook declined")])),
                Box::new(|o| matches!(o, Outcome::Rejected { reason, messages } if reason == "hook declined" && messages == &vec!["error: hook said no".to_string()]))),
            ((200, RESULT, vec![], sideband_report(&[], &["unpack ok", "ok refs/heads/main"])), Box::new(|o| failed(o).contains("was not pushed"))),
            ((200, RESULT, vec![], sideband_report(&[], &["unpack ok"])), Box::new(|o| failed(o).contains("names no status"))),
            ((200, RESULT, vec![], b"<html>500</html>".to_vec()), Box::new(|o| failed(o).contains("pkt-line"))),
            ((200, "text/html", vec![], b"<html>ok</html>".to_vec()), Box::new(|o| failed(o).contains("is not `application/x-git-receive-pack-result`"))),
            ((500, RESULT, vec![], Vec::new()), Box::new(|o| failed(o).contains("returned error: 500"))),
            ((302, RESULT, vec![("location", "http://127.0.0.1:1/".into())], Vec::new()), Box::new(|o| failed(o).contains("follows no redirect"))),
            ((200, RESULT, vec![], Vec::new()), Box::new(|o| failed(o).contains("ended before its report"))),
            ((200, RESULT, vec![], pkt(b"\x02").repeat((16 << 20) / 5 + 1)), Box::new(|o| failed(o).contains("larger than 16 MiB"))),
        ];
        for (i, (push, check)) in cases.into_iter().enumerate() {
            let (outcome, _fake) = outcome_against(info, push).await;
            assert!(check(&outcome), "case {i}: {outcome:?}");
            assert!(!outcome.succeeded(), "case {i}");
        }
    })
    .await;
}

/// What a code host does with its answer to a push once the report is out, as a receive-pack that
/// runs the repository's post-receive hooks after its report.
#[derive(Clone)]
enum Then {
    /// After `wait` (the hooks), these bytes, and the end of the answer.
    End {
        wait: std::time::Duration,
        bytes: Vec<u8>,
    },
    /// These bytes, and then neither more nor the end of the answer.
    Hold(Vec<u8>),
    /// The answer breaks off.
    Break,
}

/// A code host that answers the push with `report` and then goes on as `then` says.
struct SlowHost {
    url: String,
    /// For [`Then::End`], once the wait is over: the client was still there to take the rest.
    stayed: Arc<Mutex<Option<bool>>>,
}

async fn slow_host(advertisement: Vec<u8>, report: Vec<u8>, then: Then) -> SlowHost {
    use http_body_util::{BodyExt, Full, StreamBody};
    use hyper::body::{Bytes, Frame};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/origin.git", listener.local_addr().unwrap());
    let stayed = Arc::new(Mutex::new(None));
    let parts = Arc::new((advertisement, report, then, stayed.clone()));
    tokio::spawn(async move {
        while let Ok((stream, _)) = listener.accept().await {
            let parts = parts.clone();
            tokio::spawn(async move {
                let service = hyper::service::service_fn(
                    move |req: hyper::Request<hyper::body::Incoming>| {
                        let parts = parts.clone();
                        async move {
                            let get = req.method() == hyper::Method::GET;
                            let _ = req.into_body().collect().await;
                            let (advertisement, report, then, stayed) = &*parts;
                            let (content_type, body) = if get {
                                let body = Full::new(Bytes::from(advertisement.clone()))
                                    .map_err(|never| match never {})
                                    .boxed_unsync();
                                (ADVERTISEMENT, body)
                            } else {
                                let (tx, rx) = futures::channel::mpsc::unbounded();
                                let frame = |bytes: Vec<u8>| {
                                    Ok::<_, std::io::Error>(Frame::data(Bytes::from(bytes)))
                                };
                                let _ = tx.unbounded_send(frame(report.clone()));
                                let (then, stayed) = (then.clone(), stayed.clone());
                                tokio::spawn(async move {
                                    match then {
                                        Then::End { wait, bytes } => {
                                            tokio::time::sleep(wait).await;
                                            // hyper drops an answer whose client hung up, and
                                            // with it the receiving end of this channel.
                                            let there = tx.unbounded_send(frame(bytes)).is_ok();
                                            *stayed.lock().unwrap() = Some(there);
                                        }
                                        Then::Hold(bytes) => {
                                            let _ = tx.unbounded_send(frame(bytes));
                                            while !tx.is_closed() {
                                                tokio::time::sleep(
                                                    std::time::Duration::from_millis(50),
                                                )
                                                .await;
                                            }
                                        }
                                        Then::Break => {
                                            tokio::time::sleep(std::time::Duration::from_millis(
                                                200,
                                            ))
                                            .await;
                                            let _ = tx.unbounded_send(Err(std::io::Error::other(
                                                "the hook was ended",
                                            )));
                                        }
                                    }
                                });
                                (RESULT, StreamBody::new(rx).boxed_unsync())
                            };
                            let mut response = hyper::Response::new(body);
                            response
                                .headers_mut()
                                .insert("content-type", content_type.parse().unwrap());
                            Ok::<_, std::convert::Infallible>(response)
                        }
                    },
                );
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), service)
                    .await;
            });
        }
    });
    SlowHost { url, stayed }
}

/// A push that the code host reports as taken, after which the host goes on as `then` says:
/// the outcome, how long the push took, and what the host saw of the client ([`SlowHost`]).
async fn pushed_and_then(
    caps: &str,
    then: Then,
    deadline: std::time::Duration,
) -> (Outcome, std::time::Duration, Option<bool>) {
    let (_dir, host, main) = host_for("http://127.0.0.1:9/origin.git");
    let ok = format!("ok refs/heads/{BRANCH}");
    let lines = ["unpack ok", ok.as_str()];
    let report = if caps.contains("side-band-64k") {
        // Without the side-band's own flush: a receive-pack sends it last, after its hooks.
        let mut report = sideband_report(&[], &lines);
        report.truncate(report.len() - 4);
        report
    } else {
        let mut report: Vec<u8> = lines
            .iter()
            .flat_map(|line| pkt(format!("{line}\n").as_bytes()))
            .collect();
        report.extend_from_slice(b"0000");
        report
    };
    let slow = slow_host(advertisement(&main, caps), report, then).await;
    let host = Git::restored(
        host.dir(),
        &spec(&slow.url),
        ("T".into(), "t@tinfirm.invalid".into()),
        host.base_refs().to_vec(),
        None,
    );
    let head = head(host.dir());
    let started = Instant::now();
    let outcome =
        tinfirm_runner_host::git_push::push_outcome_within(&host, BRANCH, &head, true, deadline)
            .await
            .unwrap();
    let took = started.elapsed();
    let stayed = *slow.stayed.lock().unwrap();
    (outcome, took, stayed)
}

/// git's receive-pack writes its report before it runs the repository's post-receive hooks, and
/// ends its answer after them; `git push` waits for that end. A client that hung up at the report
/// could have a server end the hooks with the connection, after the branch moved. The host's
/// client reads to the answer's end, and nothing that happens there changes the push's outcome.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_answer_is_read_to_its_end_after_the_report_and_the_report_decides() {
    use std::time::Duration;
    beside(120, async {
        let pushed = |outcome: &Outcome| matches!(outcome, Outcome::Pushed { created: true, .. });
        let long = Duration::from_secs(60);
        let hook_time = Duration::from_millis(700);
        let band = |b: u8, text: &str| {
            let mut data = vec![b];
            data.extend_from_slice(text.as_bytes());
            pkt(&data)
        };
        // The hooks' words and the side-band's own flush come after a while: the client is
        // still there to take them, and the push ends when the answer does.
        let mut rest = band(2, "post-receive: a pipeline was started\n");
        rest.extend_from_slice(b"0000");
        let then = Then::End {
            wait: hook_time,
            bytes: rest,
        };
        let (outcome, took, stayed) = pushed_and_then(CAPS, then, long).await;
        assert!(pushed(&outcome), "{outcome:?}");
        assert_eq!(stayed, Some(true), "the client hung up at the report");
        assert!(took >= hook_time, "{took:?}");
        // Without the side-band only the answer's end tells that the repository is done.
        let then = Then::End {
            wait: hook_time,
            bytes: Vec::new(),
        };
        let plain = "report-status quiet ofs-delta agent=fake/1";
        let (outcome, took, stayed) = pushed_and_then(plain, then, long).await;
        assert!(pushed(&outcome), "{outcome:?}");
        assert_eq!(stayed, Some(true), "the client hung up at the report");
        assert!(took >= hook_time, "{took:?}");
        // The side-band's flush ends the reading, also where the answer itself never ends.
        let (outcome, took, _) = pushed_and_then(CAPS, Then::Hold(b"0000".to_vec()), long).await;
        assert!(pushed(&outcome), "{outcome:?}");
        assert!(took < Duration::from_secs(20), "{took:?}");
        // An answer that breaks off after its report, and one that goes on past its bound of
        // 16 MiB: the push is what the report said.
        let (outcome, took, _) = pushed_and_then(CAPS, Then::Break, long).await;
        assert!(pushed(&outcome), "{outcome:?}");
        assert!(took < Duration::from_secs(20), "{took:?}");
        let endless = Then::Hold(pkt(b"\x02").repeat((16 << 20) / 5 + 1));
        let (outcome, took, _) = pushed_and_then(CAPS, endless, long).await;
        assert!(pushed(&outcome), "{outcome:?}");
        assert!(took < Duration::from_secs(20), "{took:?}");
        // An answer that never ends is read for what is left of the push's time, here 3 s, and
        // the push is still what the report said, not one that "did not end".
        let short = Duration::from_secs(3);
        let (outcome, took, _) = pushed_and_then(CAPS, Then::Hold(Vec::new()), short).await;
        assert!(pushed(&outcome), "{outcome:?}");
        assert!(
            took >= Duration::from_secs(2) && took < Duration::from_secs(20),
            "{took:?}"
        );
    })
    .await;
}

/// The command is `<old> <new> <ref>\0 <capabilities>` as git's send-pack writes it, with only
/// capabilities the repository named, and `object-format` and `agent` only when it named them.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_command_names_the_lease_and_only_capabilities_the_repository_named() {
    beside(60, async {
        let ok = format!("ok refs/heads/{BRANCH}");
        let ok = sideband_report(&[], &["unpack ok", &ok]);
        for (caps, expected) in [
            (CAPS, " report-status side-band-64k quiet agent=tinfirm-runner-host/"),
            ("report-status", " report-status"),
            ("report-status report-status-v2 side-band-64k atomic delete-refs push-options object-format=sha1 agent=x", " report-status side-band-64k object-format=sha1 agent=tinfirm-runner-host/"),
        ] {
            let (outcome, fake) = outcome_against(
                |main| (200, ADVERTISEMENT, vec![], advertisement(main, caps)),
                (200, RESULT, vec![], if caps == "report-status" {
                    let mut plain = pkt(b"unpack ok\n");
                    plain.extend(pkt(format!("ok refs/heads/{BRANCH}\n").as_bytes()));
                    plain.extend_from_slice(b"0000");
                    plain
                } else {
                    ok.clone()
                }),
            )
            .await;
            assert!(outcome.succeeded(), "{caps}: {outcome:?}");
            let body = fake.bodies.lock().unwrap().last().cloned().unwrap();
            let len = usize::from_str_radix(std::str::from_utf8(&body[..4]).unwrap(), 16).unwrap();
            let command = &body[4..len];
            let (line, sent) = command.split_at(command.iter().position(|&b| b == 0).unwrap());
            let line = String::from_utf8_lossy(line);
            let sent = String::from_utf8_lossy(&sent[1..]);
            let words: Vec<&str> = line.split(' ').collect();
            assert_eq!(words[0], "0".repeat(40), "a new branch: the zero id");
            assert_eq!(words[2], format!("refs/heads/{BRANCH}"));
            assert!(sent.starts_with(expected), "{caps}: `{sent}`");
            if !caps.contains("agent") {
                assert!(!sent.contains("agent"), "{sent}");
            }
            // The flush, then the pack.
            assert_eq!(&body[len..len + 4], b"0000");
            assert_eq!(&body[len + 4..len + 8], b"PACK");
        }
    })
    .await;
}

/// The token is read again for the push itself (ADR-0116): one revoked after the ref discovery
/// sends no pack.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_token_revoked_after_the_ref_discovery_sends_no_pack() {
    beside(60, async {
        let (_dir, host, main) = host_for("http://127.0.0.1:9/origin.git");
        // The fake host's URL is known once it listens: the clone's holder is made for it, and
        // the answer to the GET revokes its token.
        let holder: Arc<Mutex<Option<Git>>> = Arc::new(Mutex::new(None));
        let revoke = holder.clone();
        let fake = fake_host_with(
            (200, ADVERTISEMENT, vec![], advertisement(&main, CAPS)),
            (200, RESULT, vec![], Vec::new()),
            Some(Arc::new(move || {
                if let Some(git) = revoke.lock().unwrap().as_ref() {
                    let id = git.tokens().component_id();
                    git.tokens()
                        .run()
                        .revoke(&[id], "REVOKED", "revoked during the push");
                }
            })),
        )
        .await;
        let git = Git::restored(
            host.dir(),
            &spec(&fake.url),
            ("T".into(), "t@tinfirm.invalid".into()),
            host.base_refs().to_vec(),
            None,
        );
        *holder.lock().unwrap() = Some(git.clone());
        let error = git.push_with(BRANCH, None, &SETTINGS).await.unwrap_err();
        assert_eq!(format!("{error:#}"), "not pushed: revoked during the push");
        let seen = fake.seen.lock().unwrap().clone();
        assert_eq!(seen.len(), 1, "{seen:?}");
        assert_eq!(seen[0].0, "GET");
        assert!(fake.bodies.lock().unwrap().is_empty());
    })
    .await;
}

// ----- regressions of the review ---------------------------------------------------------------

/// A reader that answers every job as the real reader does, but the pack's, which runs
/// `pack_body` (a shell fragment, in the clone) in its place.
fn fake_pack_reader(dir: &Path, pack_body: &str) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    let script = dir.join("fake-reader.sh");
    std::fs::write(
        &script,
        format!(
            "#!/bin/sh\ncase \"$2\" in\n*'\"kind\":\"pack_objects\"'*)\n{pack_body}\n;;\n*) exec '{}' \"$@\";;\nesac\n",
            bin().display()
        ),
    )
    .unwrap();
    std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
    script
}

/// A pack's writer that writes a whole, valid pack of more than a MiB and then fails. If the
/// body sent the pack's end before the writer had ended, receive-pack would take the whole pack
/// and move the branch while the run is told "not pushed". The last chunk goes only once the
/// writer has ended well: nothing is taken, and the run is told so.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_writer_that_fails_after_a_whole_pack_never_sends_its_end() {
    beside(120, async {
        let scene = Scene::new("sha1", true).await;
        let mut host = scene.clone_host(Packer::Plain);
        let tmp = tempfile::tempdir().unwrap();
        let script = fake_pack_reader(
            tmp.path(),
            "printf '%s\\n^%s\\n' \"$(git rev-parse HEAD)\" \"$(git rev-parse refs/remotes/origin/main)\" | git pack-objects --revs --stdout -q\nexec 1>&-\nsleep 3\nexit 1",
        );
        host.read_through(Reader::new(&script).allowing_unconfined(true));
        incompressible(&scene.work.join("big.bin"), 3 << 20, 9);
        git(&scene.work, &["add", "-A"]);
        git(&scene.work, &["commit", "-q", "-m", "big"]);
        let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
        assert!(!out.status.success(), "{out:?}");
        assert!(out.stderr.contains("git pack-objects failed"), "{out:?}");
        assert_eq!(scene.tip(), None, "the repository took the pack");
        // The pack went in chunks, all but its end.
        let seen = scene.http().seen();
        assert_eq!(pushes(&seen).len(), 1, "{seen:?}");
        assert!(pushes(&seen)[0].chunked);
        assert_pushed_by(&seen, true);
    })
    .await;
}

/// A pack's writer that writes less than a MiB and then neither ends nor closes its output: the
/// push's one deadline, which runs from the first reading of the token, ends it, kills the
/// writer's group, and nothing is sent. A run's deadline is 60 minutes; the test gives 3 s
/// through `push_outcome_within`.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_writer_that_stalls_is_ended_by_the_pushs_deadline() {
    beside(60, async {
        let scene = Scene::new("sha1", true).await;
        let mut host = scene.clone_host(Packer::Plain);
        let tmp = tempfile::tempdir().unwrap();
        let script = fake_pack_reader(tmp.path(), "printf PACK\nexec sleep 3599");
        host.read_through(Reader::new(&script).allowing_unconfined(true));
        let pushed = scene.commit("work.txt", b"work\n");
        let started = Instant::now();
        let outcome = tinfirm_runner_host::git_push::push_outcome_within(
            &host,
            BRANCH,
            &pushed,
            true,
            std::time::Duration::from_secs(3),
        )
        .await
        .unwrap();
        let took = started.elapsed();
        assert_eq!(
            outcome,
            Outcome::Failed {
                error: "the push did not end within 3 s".into(),
                messages: Vec::new()
            }
        );
        assert!(took < std::time::Duration::from_secs(10), "{took:?}");
        assert!(pushes(&scene.http().seen()).is_empty());
        // The writer went with its group.
        let deadline = Instant::now() + std::time::Duration::from_secs(5);
        loop {
            let alive = std::fs::read_dir("/proc").unwrap().flatten().any(|e| {
                std::fs::read(e.path().join("cmdline")).is_ok_and(|c| c == b"sleep\x003599\x00")
            });
            if !alive {
                break;
            }
            assert!(Instant::now() < deadline, "the stalled writer still runs");
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        }
    })
    .await;
}

/// A shallow repository on the code host: receive-pack lists its `shallow` lines after the refs.
/// The host's client pushes to it, as `git push` does.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_shallow_repository_on_the_code_host_takes_the_push_as_from_git() {
    beside(120, async {
        let scene = Scene::new("sha1", true).await;
        // `main` two commits deep, and the repository shallow at its tip.
        let tree = git(&scene.bare, &["rev-parse", "main^{tree}"]);
        let second = git(
            &scene.bare,
            &["commit-tree", tree.trim(), "-p", "main", "-m", "second"],
        );
        git(
            &scene.bare,
            &["update-ref", "refs/heads/main", second.trim()],
        );
        std::fs::write(scene.bare.join("shallow"), format!("{}\n", second.trim())).unwrap();
        let host = scene.clone_host(Packer::Plain);
        let pushed = scene.commit("work.txt", b"work\n");
        let out = host.push_with(BRANCH, None, &SETTINGS).await.unwrap();
        assert!(out.status.success(), "{out:?}");
        assert_eq!(scene.tip().as_deref(), Some(pushed.as_str()));
        assert_eq!(assert_pushed_by(&scene.http().seen(), true), 2);
        // And git, to another branch of the same repository, for comparison.
        let before = scene.http().seen().len();
        let git_settings = PushSettings {
            client: PushClient::Git,
            allow_http: true,
        };
        let out = host
            .push_with("agent/TEST-2", None, &git_settings)
            .await
            .unwrap();
        assert!(out.status.success(), "{out:?}");
        assert!(assert_pushed_by(&scene.http().seen()[before..], false) >= 2);
    })
    .await;
}
