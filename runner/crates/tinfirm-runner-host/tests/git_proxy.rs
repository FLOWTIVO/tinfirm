//! The git proxy with real git (docs/archive/v1/CONVENTIONS.md §15.4 step 2). The host mirrors and clones the
//! way a run does, `origin` then points at the proxy, and a git without any credentials (what
//! the sandbox has) fetches and cannot push. The upstream is `git http-backend` behind a
//! server that requires the host's `Authorization`.

mod common;

use std::path::Path;
use std::process::Output;

use common::git_http::{GitHttpServer, test_git_authorization};
use common::*;
use tinfirm_protocol::{GitCredentials, Secret, WorkspaceSpec};
use tinfirm_runner_host::git::Git;
use tinfirm_runner_host::git_proxy::{Endpoint, GitProxy};
use tinfirm_runner_host::mirror::Mirrors;
use uuid::Uuid;

/// git as the sandbox runs it: no credential helper, no user or system config, no prompt.
async fn sandbox_git(home: &Path, cwd: &Path, args: &[&str]) -> Output {
    tokio::process::Command::new("git")
        .args(["-c", "credential.helper="])
        .args(args)
        .current_dir(cwd)
        .env_clear()
        .env("PATH", std::env::var("PATH").unwrap_or_default())
        .env("HOME", home)
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_AUTHOR_NAME", "Agent")
        .env("GIT_AUTHOR_EMAIL", "agent@tinfirm.invalid")
        .env("GIT_COMMITTER_NAME", "Agent")
        .env("GIT_COMMITTER_EMAIL", "agent@tinfirm.invalid")
        .output()
        .await
        .unwrap()
}

async fn sandbox_git_ok(home: &Path, cwd: &Path, args: &[&str]) -> String {
    let out = sandbox_git(home, cwd, args).await;
    assert!(
        out.status.success(),
        "git {args:?} failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8_lossy(&out.stdout).into_owned()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_sandbox_fetches_through_the_proxy_and_cannot_push() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let authorization = test_git_authorization();
        let server = GitHttpServer::start(repo.dir.path(), Some(authorization.clone())).await;
        let repo_url = format!("{}/origin.git", server.url);
        let scratch = tempfile::tempdir().unwrap();
        let home = scratch.path().join("home");
        std::fs::create_dir(&home).unwrap();

        // Without the host's header the server refuses, like GitHub for a private repository.
        let anonymous = sandbox_git(&home, scratch.path(), &["ls-remote", &repo_url]).await;
        assert!(!anonymous.status.success());

        // The host's mirror and clone, exactly as a run makes them.
        let spec = WorkspaceSpec {
            component_id: Uuid::new_v4(),
            dir: "repo".into(),
            name: "Repo".into(),
            repo_url: repo_url.clone(),
            default_branch: "main".into(),
            branch: "agent/TEST-1".into(),
            git: Some(GitCredentials {
                username: "x-access-token".into(),
                token: Secret::new(TEST_GIT_TOKEN),
            }),
            author_name: None,
            author_email: None,
            read_only: false,
        };
        let root = scratch.path().join("workspace");
        std::fs::create_dir(&root).unwrap();
        let workspace = root.join("repo");
        let mut host_git = Git::new(
            &workspace,
            &spec,
            ("Tinfirm agent Tester".into(), "agent@tinfirm.invalid".into()),
        );
        let mirrors = Mirrors::new(scratch.path().join("mirrors"));
        let mirror = mirrors
            .update(&repo_url, host_git.authorization())
            .await
            .unwrap();
        host_git
            .clone_from_mirror(&mirror.path, &mirror.refs, &spec)
            .await
            .unwrap();
        drop(mirror);
        assert_eq!(
            host_git.authorization().unwrap().expose(),
            authorization,
            "the proxy sends the same header as host-side git"
        );

        let proxy = GitProxy::start(
            Endpoint {
                bind: "127.0.0.1:0".parse().unwrap(),
                sandbox_host: "127.0.0.1".into(),
                extra_hosts: Vec::new(),
            },
            true,
        )
        .await
        .unwrap();
        let route = proxy
            .register(
                Uuid::new_v4(),
                &repo_url,
                host_git.authorization().cloned(),
                &spec.branch,
            )
            .unwrap();
        host_git.set_origin_url(route.sandbox_url()).await.unwrap();
        let config = std::fs::read_to_string(workspace.join(".git/config")).unwrap();
        assert!(config.contains(route.sandbox_url()));
        assert!(!config.contains(TEST_GIT_TOKEN) && !config.contains(&authorization));
        assert!(!config.contains("mirrors"), "no link to the mirror: {config}");
        assert!(!workspace.join(".git/objects/info/alternates").exists());

        // New upstream work after the clone: a commit on main.
        let new_head = repo.commit_to_main("upstream.txt", "from upstream\n", "Upstream change");
        let requests_before = server.seen().len();

        // The sandbox: fetch, read and log, all without credentials.
        sandbox_git_ok(&home, &workspace, &["fetch", "origin", "main"]).await;
        assert_eq!(
            sandbox_git_ok(&home, &workspace, &["rev-parse", "origin/main"])
                .await
                .trim(),
            new_head
        );
        assert_eq!(
            sandbox_git_ok(&home, &workspace, &["show", "origin/main:upstream.txt"]).await,
            "from upstream\n"
        );
        let subjects = sandbox_git_ok(&home, &workspace, &["log", "--format=%s", "origin/main"]).await;
        assert_eq!(
            subjects.lines().collect::<Vec<_>>(),
            vec!["Upstream change", "Initial commit"]
        );

        // Pushing through the proxy fails with its reason, and nothing reaches the server.
        std::fs::write(workspace.join("agent.txt"), "agent work\n").unwrap();
        sandbox_git_ok(&home, &workspace, &["add", "agent.txt"]).await;
        sandbox_git_ok(&home, &workspace, &["commit", "-q", "-m", "Agent work"]).await;
        let push = sandbox_git(&home, &workspace, &["push", "origin", "HEAD:refs/heads/agent/TEST-1"]).await;
        let push_err = String::from_utf8_lossy(&push.stderr);
        assert!(!push.status.success());
        assert!(
            push_err.contains("pushing from the sandbox is not allowed; the runner host pushes agent/TEST-1 when the run ends"),
            "{push_err}"
        );
        assert!(repo.branch_head("agent/TEST-1").is_none());

        // The server saw only fetch requests, each with the host's header and protocol v2.
        let seen = server.seen();
        let through_proxy = &seen[requests_before..];
        assert!(through_proxy.len() >= 3, "{through_proxy:#?}");
        for request in through_proxy {
            assert_eq!(request.authorization.as_deref(), Some(authorization.as_str()));
            assert_eq!(request.git_protocol.as_deref(), Some("version=2"));
            assert!(
                (request.method == "GET"
                    && request.path == "/origin.git/info/refs"
                    && request.query == "service=git-upload-pack")
                    || (request.method == "POST" && request.path == "/origin.git/git-upload-pack"),
                "{request:?}"
            );
            assert_eq!(request.status, 200, "{request:?}");
        }
        assert!(seen.iter().all(|r| !r.query.contains("receive-pack") && !r.path.contains("receive-pack")));

        // The run ends: the route goes, and so does the sandbox's way to fetch.
        let secret = route.secret().expose().to_string();
        drop(route);
        assert_eq!(proxy.route_count(), 0);
        let after = sandbox_git(&home, &workspace, &["fetch", "origin", "main"]).await;
        assert!(!after.status.success());
        assert_eq!(server.seen().len(), seen.len(), "nothing reached the server");

        // The host path is unchanged: sanitize restores the real origin, and the host pushes.
        host_git.sanitize().unwrap();
        let config = std::fs::read_to_string(workspace.join(".git/config")).unwrap();
        assert!(config.contains(&format!("url = {repo_url}")), "{config}");
        assert!(!config.contains(&secret));
        let pushed = host_git.push("agent/TEST-1").await.unwrap();
        assert!(pushed.status.success(), "{}", pushed.stderr);
        assert_eq!(
            repo.file_at("agent/TEST-1", "agent.txt").as_deref(),
            Some("agent work\n")
        );
        proxy.shutdown();
    })
    .await;
}
