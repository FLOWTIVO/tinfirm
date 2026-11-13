//! Host mirrors with real git (docs/archive/v1/CONVENTIONS.md §15.4 step 2): atomic creation, fetch and prune,
//! the per-mirror lock, refused tokens, workspaces without a link back to the mirror, and a
//! credential scan that the workspace's own refs cannot fool. The repositories are served by
//! `git http-backend` and require the host's `Authorization`.

mod common;

use std::os::unix::fs::MetadataExt;
use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

use common::git_http::{GitHttpServer, test_git_authorization};
use common::*;
use tinfirm_protocol::{GitCredentials, Secret, WorkspaceSpec};
use tinfirm_runner_host::git::{Git, branch_tip, ref_shas};
use tinfirm_runner_host::mirror::{Mirrors, mirror_key};
use tinfirm_runner_host::redact::Redactor;
use uuid::Uuid;

fn host_authorization() -> Secret {
    Secret::new(test_git_authorization())
}

fn spec(repo_url: &str) -> WorkspaceSpec {
    WorkspaceSpec {
        component_id: Uuid::new_v4(),
        dir: "repo".into(),
        name: "Repo".into(),
        repo_url: repo_url.into(),
        default_branch: "main".into(),
        branch: "agent/TEST-1".into(),
        git: Some(GitCredentials {
            username: "x-access-token".into(),
            token: Secret::new(TEST_GIT_TOKEN),
        }),
        author_name: None,
        author_email: None,
        read_only: false,
    }
}

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

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_mirror_is_created_atomically_then_fetched_and_pruned() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let gone = repo.create_branch("gone");
        let server = GitHttpServer::start(repo.dir.path(), Some(test_git_authorization())).await;
        let url = format!("{}/origin.git", server.url);
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));

        let lease = mirrors
            .update(&url, Some(&host_authorization()))
            .await
            .unwrap();
        let key = mirror_key(&url, Some(&host_authorization()));
        assert_eq!(key.len(), 32);
        assert_eq!(
            lease.path,
            scratch.path().join(format!("mirrors/{key}.git"))
        );
        assert_eq!(
            lease.path,
            mirrors.path_for(&url, Some(&host_authorization()))
        );
        // Only the mirror itself: no temporary directory is left.
        assert_eq!(entries(mirrors.dir()), vec![format!("{key}.git")]);
        assert_eq!(
            std::fs::metadata(mirrors.dir()).unwrap().mode() & 0o777,
            0o700
        );
        assert_eq!(
            git(&lease.path, &["rev-parse", "--is-bare-repository"]).trim(),
            "true"
        );
        assert_eq!(git(&lease.path, &["config", "gc.auto"]).trim(), "0");
        // No credentials and no remote in the mirror's config: every fetch names the URL.
        let config = std::fs::read_to_string(lease.path.join("config")).unwrap();
        assert!(
            !config.contains(TEST_GIT_TOKEN) && !config.contains("remote"),
            "{config}"
        );
        assert_eq!(branch_tip(&lease.refs, "gone"), Some(gone.as_str()));
        let main_before = repo.branch_head("main").unwrap();
        assert_eq!(branch_tip(&lease.refs, "main"), Some(main_before.as_str()));
        drop(lease);

        // Upstream moves on: a new commit, a new branch and tag, a deleted branch.
        let main_after = repo.commit_to_main("next.txt", "next\n", "Next change");
        repo.create_branch("feature");
        git(&repo.bare, &["tag", "v1", "main"]);
        repo.delete_branch("gone");
        let lease = mirrors
            .update(&url, Some(&host_authorization()))
            .await
            .unwrap();
        assert_eq!(branch_tip(&lease.refs, "main"), Some(main_after.as_str()));
        assert!(branch_tip(&lease.refs, "feature").is_some());
        assert!(lease.refs.iter().any(|r| r.name == "refs/tags/v1"));
        assert!(
            branch_tip(&lease.refs, "gone").is_none(),
            "pruned: {:?}",
            lease.refs
        );
        assert!(!git(&lease.path, &["for-each-ref"]).contains("refs/heads/gone"));
        // Every request carried the host's header.
        assert!(
            server
                .seen()
                .iter()
                .all(|r| r.authorization.as_deref() == Some(test_git_authorization().as_str()))
        );
        drop(lease);
        // `refresh` is a fetch under the lock, too.
        let newest = repo.commit_to_main("newest.txt", "newest\n", "Newest change");
        let refs = mirrors
            .refresh(&url, Some(&host_authorization()))
            .await
            .unwrap();
        assert_eq!(branch_tip(&refs, "main"), Some(newest.as_str()));
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_refused_token_fails_without_a_mirror_or_with_a_stale_one() {
    with_timeout(120, async {
        for forbidding in [false, true] {
            let repo = TestRepo::new();
            let server = if forbidding {
                GitHttpServer::start_forbidding(repo.dir.path(), Some(test_git_authorization()))
                    .await
            } else {
                GitHttpServer::start(repo.dir.path(), Some(test_git_authorization())).await
            };
            let url = format!("{}/origin.git", server.url);
            let scratch = tempfile::tempdir().unwrap();
            let mirrors = Mirrors::new(scratch.path().join("mirrors"));
            let wrong = Secret::new("Basic eC1hY2Nlc3MtdG9rZW46d3Jvbmc=");

            // No mirror yet: the failed first fetch leaves nothing behind.
            let err = mirrors.update(&url, Some(&wrong)).await.unwrap_err();
            // git says "…returned error: 403" for 403 and "Authentication failed" for 401.
            let status = if forbidding {
                "403"
            } else {
                "Authentication failed"
            };
            assert!(format!("{err:#}").contains(status), "{err:#}");
            assert!(
                entries(mirrors.dir()).is_empty(),
                "{:?}",
                entries(mirrors.dir())
            );
            assert!(mirrors.update(&url, None).await.is_err());

            // A mirror exists, then the token stops working: the fetch fails, and the
            // mirror's old content is not handed out.
            mirrors
                .update(&url, Some(&host_authorization()))
                .await
                .unwrap();
            server.set_authorization(Some("Basic cm90YXRlZA==".into()));
            let err = mirrors
                .update(&url, Some(&host_authorization()))
                .await
                .unwrap_err();
            assert!(format!("{err:#}").contains(status), "{err:#}");
            assert!(
                mirrors.path_for(&url, Some(&host_authorization())).exists(),
                "the mirror itself stays"
            );
        }
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn one_run_at_a_time_uses_a_mirror() {
    with_timeout(120, async {
        let first = TestRepo::new();
        let second = TestRepo::new();
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Arc::new(Mirrors::new(scratch.path().join("mirrors")));
        let hold = Duration::from_millis(300);
        let mut tasks = Vec::new();
        for _ in 0..3 {
            let mirrors = mirrors.clone();
            let url = first.url.clone();
            tasks.push(tokio::spawn(async move {
                let lease = mirrors.update(&url, None).await.unwrap();
                let start = Instant::now();
                tokio::time::sleep(hold).await;
                let end = Instant::now();
                drop(lease);
                (start, end)
            }));
        }
        // Another repository's mirror is not blocked meanwhile.
        tokio::time::sleep(Duration::from_millis(100)).await;
        let other =
            tokio::time::timeout(Duration::from_secs(20), mirrors.update(&second.url, None))
                .await
                .expect("another mirror does not wait for this one")
                .unwrap();
        drop(other);
        let mut spans = Vec::new();
        for task in tasks {
            spans.push(task.await.unwrap());
        }
        spans.sort();
        for pair in spans.windows(2) {
            assert!(
                pair[0].1 <= pair[1].0,
                "two runs held the same mirror at once"
            );
        }
        let mut expected = vec![
            format!("{}.git", mirror_key(&first.url, None)),
            format!("{}.git", mirror_key(&second.url, None)),
        ];
        expected.sort();
        assert_eq!(entries(mirrors.dir()), expected);
    })
    .await;
}

/// M15 (security review of 2026-10-27), Q8: mirrors are keyed per principal. An object that
/// one principal's fetch brought in and that is unreachable since (a deleted branch) stays in
/// that principal's mirror until maintenance collects it, and never reaches the mirror, and
/// so the workspace clones, of another principal.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn two_principals_never_share_a_mirror() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let secret = repo.commit_to_branch("secret", "secret.txt", "s3cr3t\n", "Secret");
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));
        let alice = Secret::new("Basic YWxpY2U6dG9rZW4tYQ==");
        let bob = Secret::new("Basic Ym9iOnRva2VuLWI=");

        let of_alice = mirrors.update(&repo.url, Some(&alice)).await.unwrap();
        assert_eq!(branch_tip(&of_alice.refs, "secret"), Some(secret.as_str()));
        let alice_path = of_alice.path.clone();
        drop(of_alice);
        repo.delete_branch("secret");
        drop(mirrors.update(&repo.url, Some(&alice)).await.unwrap());
        // Alice's mirror still holds the object, unreachable.
        assert_eq!(
            git(&alice_path, &["cat-file", "-t", &secret]).trim(),
            "commit"
        );

        let of_bob = mirrors.update(&repo.url, Some(&bob)).await.unwrap();
        assert_ne!(of_bob.path, alice_path, "a mirror of its own");
        assert_eq!(of_bob.path, mirrors.path_for(&repo.url, Some(&bob)));
        let missing = std::process::Command::new("git")
            .args(["cat-file", "-e", &secret])
            .current_dir(&of_bob.path)
            .output()
            .unwrap();
        assert!(
            !missing.status.success(),
            "the other principal's mirror never had the object"
        );
        drop(of_bob);
        let mut expected = vec![
            format!("{}.git", mirror_key(&repo.url, Some(&alice))),
            format!("{}.git", mirror_key(&repo.url, Some(&bob))),
        ];
        expected.sort();
        assert_eq!(entries(mirrors.dir()), expected);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_half_created_mirror_is_removed_at_startup() {
    let scratch = tempfile::tempdir().unwrap();
    let mirrors = Mirrors::new(scratch.path().join("mirrors"));
    let leftover = mirrors.dir().join(".tmp-0123-abc.git/objects");
    std::fs::create_dir_all(&leftover).unwrap();
    std::fs::create_dir_all(mirrors.dir().join("0123.git")).unwrap();
    mirrors.remove_stale_temp_dirs();
    assert_eq!(entries(mirrors.dir()), vec!["0123.git".to_string()]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_workspace_is_a_copy_with_no_link_to_the_mirror() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let server = GitHttpServer::start(repo.dir.path(), Some(test_git_authorization())).await;
        let url = format!("{}/origin.git", server.url);
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));
        let workspace = scratch.path().join("workspace/repo");
        std::fs::create_dir_all(workspace.parent().unwrap()).unwrap();
        let spec = spec(&url);
        let mut host_git = Git::new(
            &workspace,
            &spec,
            ("Host".into(), "host@tinfirm.invalid".into()),
        );
        let lease = mirrors
            .update(&url, host_git.authorization())
            .await
            .unwrap();
        let requests = server.seen().len();
        let head = host_git
            .clone_from_mirror(&lease.path, &lease.refs, &spec)
            .await
            .unwrap();
        assert_eq!(server.seen().len(), requests, "the clone needs no network");
        assert_eq!(head, repo.branch_head("main").unwrap());
        assert_eq!(
            git(&workspace, &["rev-parse", "--abbrev-ref", "HEAD"]).trim(),
            "agent/TEST-1"
        );
        assert_eq!(
            git(&workspace, &["remote", "get-url", "origin"]).trim(),
            url
        );
        assert!(!workspace.join(".git/objects/info/alternates").exists());
        let config = std::fs::read_to_string(workspace.join(".git/config")).unwrap();
        assert!(
            !config.contains(&lease.path.to_string_lossy().into_owned()),
            "{config}"
        );
        // Copies, not hard links: writing the workspace can never change the mirror.
        let mut files = 0;
        for dir in [workspace.join(".git/objects"), lease.path.join("objects")] {
            let mut stack = vec![dir];
            while let Some(dir) = stack.pop() {
                for entry in std::fs::read_dir(&dir).unwrap() {
                    let entry = entry.unwrap();
                    let meta = entry.metadata().unwrap();
                    if meta.is_dir() {
                        stack.push(entry.path());
                    } else {
                        files += 1;
                        assert_eq!(meta.nlink(), 1, "{} is hard-linked", entry.path().display());
                    }
                }
            }
        }
        assert!(files > 0);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn forged_remote_tracking_refs_do_not_hide_a_credential_from_the_scan() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));
        let workspace = scratch.path().join("workspace/repo");
        std::fs::create_dir_all(workspace.parent().unwrap()).unwrap();
        let spec = spec(&repo.url);
        let mut host_git = Git::new(
            &workspace,
            &spec,
            ("Host".into(), "host@tinfirm.invalid".into()),
        );
        let lease = mirrors.update(&repo.url, None).await.unwrap();
        host_git
            .clone_from_mirror(&lease.path, &lease.refs, &spec)
            .await
            .unwrap();
        drop(lease);

        // The "agent": a commit with the credential, forged remote-tracking refs pointing at
        // it (so `--remotes=origin` would call it already pushed), a clean commit on top, and
        // a replace ref that swaps the leaking commit for a clean one.
        std::fs::write(
            workspace.join("config.env"),
            format!("TOKEN={TEST_CLAUDE_TOKEN}\n"),
        )
        .unwrap();
        git(&workspace, &["add", "config.env"]);
        git(&workspace, &["commit", "-q", "-m", "Add config"]);
        let leaking = git(&workspace, &["rev-parse", "HEAD"]).trim().to_string();
        git(
            &workspace,
            &["update-ref", "refs/remotes/origin/x", &leaking],
        );
        git(
            &workspace,
            &["update-ref", "refs/remotes/origin/main", &leaking],
        );
        let clean_tree = git(&workspace, &["rev-parse", "HEAD~1^{tree}"])
            .trim()
            .to_string();
        let clean = git(
            &workspace,
            &[
                "commit-tree",
                &clean_tree,
                "-p",
                "HEAD~1",
                "-m",
                "Add config",
            ],
        )
        .trim()
        .to_string();
        git(&workspace, &["replace", &leaking, &clean]);
        std::fs::write(workspace.join("clean.txt"), "clean\n").unwrap();
        git(&workspace, &["add", "clean.txt"]);
        git(&workspace, &["commit", "-q", "-m", "Clean change"]);
        let shallow = git(&workspace, &["rev-parse", "HEAD"]).trim().to_string();
        std::fs::write(workspace.join(".git/shallow"), format!("{shallow}\n")).unwrap();

        host_git.sanitize().unwrap();
        assert!(!workspace.join(".git/shallow").exists());
        let redactor = Redactor::new([TEST_CLAUDE_TOKEN]);
        let exclude = ref_shas(host_git.base_refs());
        assert!(
            host_git
                .outgoing_contains_secret(&redactor, &exclude)
                .await
                .unwrap(),
            "the leaking commit is outgoing, whatever the workspace's refs say"
        );
        let main = branch_tip(host_git.base_refs(), "main")
            .unwrap()
            .to_string();
        assert_eq!(host_git.count_ahead(&main).await.unwrap(), 2);
        // Only objects the workspace has are used as boundaries.
        let unknown = "0".repeat(40);
        let present = host_git
            .present(&[main.clone(), unknown.clone()])
            .await
            .unwrap();
        assert!(present.contains(&main) && !present.contains(&unknown));
    })
    .await;
}

/// Incompressible bytes, so that the bundle's pack is as large as they are.
fn incompressible(len: usize) -> Vec<u8> {
    let mut state: u64 = 0x9e37_79b9_7f4a_7c15;
    (0..len)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            (state >> 24) as u8
        })
        .collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_bundle_larger_than_the_pack_window_imports() {
    with_timeout(120, async {
        // A small repository: its mirror and the host's clone keep their objects loose
        // (fewer than `transfer.unpackLimit`), as a fresh clone of a small repository does.
        let repo = TestRepo::new();
        let seed = repo.dir.path().join("seed");
        for i in 0..20 {
            std::fs::write(seed.join(format!("file{i:02}.txt")), format!("{i}\n")).unwrap();
        }
        git(&seed, &["add", "-A"]);
        git(&seed, &["commit", "-q", "-m", "Add files"]);
        git(&seed, &["push", "-q", "origin", "HEAD:refs/heads/main"]);
        let scratch = tempfile::tempdir().unwrap();
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));
        let workspace = scratch.path().join("workspace/repo");
        std::fs::create_dir_all(workspace.parent().unwrap()).unwrap();
        let spec = spec(&repo.url);
        let mut host_git = Git::new(
            &workspace,
            &spec,
            ("Host".into(), "host@tinfirm.invalid".into()),
        );
        let lease = mirrors.update(&repo.url, None).await.unwrap();
        let base = host_git
            .clone_from_mirror(&lease.path, &lease.refs, &spec)
            .await
            .unwrap();
        drop(lease);
        assert!(
            entries(&workspace.join(".git/objects/pack"))
                .iter()
                .all(|name| !name.ends_with(".pack")),
            "the host's clone has a pack"
        );
        // git's pack window, 1 GiB by default on 64-bit, at its smallest, so that a bundle of
        // a few MiB is larger than it, as a sandbox's bundle of over 1 GiB is in a real run.
        git(&workspace, &["config", "core.packedGitWindowSize", "1m"]);

        // The "sandbox": a copy of the clone with a commit of 4 MiB that also changes a file,
        // sent back as a bundle without the commits the host has. Its root tree is a delta
        // against the host's, which `index-pack --fix-thin` appends to the end of the new
        // pack, past the first window.
        let sandbox = scratch.path().join("sandbox");
        let workspace_arg = workspace.to_string_lossy().into_owned();
        git(
            scratch.path(),
            &["clone", "-q", "--no-local", &workspace_arg, "sandbox"],
        );
        std::fs::write(sandbox.join("data.bin"), incompressible(4 << 20)).unwrap();
        std::fs::write(sandbox.join("file07.txt"), "changed\n").unwrap();
        git(&sandbox, &["add", "-A"]);
        git(&sandbox, &["commit", "-q", "-m", "Add data"]);
        let head = git(&sandbox, &["rev-parse", "HEAD"]).trim().to_string();
        let bundle = scratch.path().join("repository-0.bundle");
        let bundle_arg = bundle.to_string_lossy().into_owned();
        git(
            &sandbox,
            &[
                "bundle",
                "create",
                "-q",
                &bundle_arg,
                "HEAD",
                &format!("^{base}"),
            ],
        );

        // git's bundle transport (2.43 to 2.48) closes the bundle's descriptor a second time after the
        // refs are updated, when the new pack has that number. The on-demand submodule check
        // then reads HEAD's root tree, which git finds in the new pack before the loose copy,
        // and mapping its window fails: "cannot be mapped: Bad file descriptor".
        host_git.import_bundle(&bundle, &head).await.unwrap();
        assert_eq!(git(&workspace, &["rev-parse", "HEAD"]).trim(), head);
        git(&workspace, &["fsck", "--strict", "--no-dangling"]);
    })
    .await;
}
