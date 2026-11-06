//! Runs in real Docker containers with the fake ACP agent image `tinfirm-runner-fake:test`,
//! which the harness builds on first use.
//!
//! Skipped (with a message) when Docker is not reachable or `TINFIRM_SKIP_DOCKER_TESTS=1`.

mod common;

use std::collections::HashMap;
use std::process::Stdio;
use std::time::Duration;

use bollard::Docker;
use bollard::models::ContainerCreateBody;
use bollard::query_parameters::CreateContainerOptionsBuilder;
use common::*;
use serde_json::json;
use tinfirm_protocol::Runtime;
use tinfirm_runner_host::sandbox::Executor;
use tinfirm_runner_host::{docker, images};
use tokio::sync::OnceCell;
use uuid::Uuid;

const FAKE_IMAGE: &str = "tinfirm-runner-fake:test";

static IMAGE: OnceCell<bool> = OnceCell::const_new();

/// Docker client plus the fake image, or `None` when Docker tests are skipped.
async fn docker_ready() -> Option<Docker> {
    if std::env::var("TINFIRM_SKIP_DOCKER_TESTS").is_ok_and(|v| v == "1") {
        eprintln!("skipped: TINFIRM_SKIP_DOCKER_TESTS=1");
        return None;
    }
    let docker = match docker::connect() {
        Ok(d) => d,
        Err(e) => {
            eprintln!("skipped: Docker is not available ({e:#})");
            return None;
        }
    };
    if let Err(e) = docker.ping().await {
        eprintln!("skipped: Docker is not reachable ({e})");
        return None;
    }
    let built = IMAGE
        .get_or_init(|| async {
            let dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fake-agent");
            let dockerfile = std::fs::read_to_string(dir.join("Dockerfile")).unwrap();
            let script = std::fs::read(dir.join("fake-agent.mjs")).unwrap();
            let context =
                images::build_context(&dockerfile, &[("fake-agent.mjs", &script)]).unwrap();
            let docker = docker::connect().unwrap();
            let none = HashMap::new();
            match images::build_image(
                &docker,
                FAKE_IMAGE,
                context,
                false,
                false,
                &none,
                &none,
                |_| {},
            )
            .await
            {
                Ok(()) => true,
                Err(e) => panic!("cannot build {FAKE_IMAGE}: {e:#}"),
            }
        })
        .await;
    built.then_some(docker)
}

fn docker_setup(docker: Docker) -> HostSetup {
    let mut overrides = HashMap::new();
    overrides.insert(Runtime::ClaudeCode, FAKE_IMAGE.to_string());
    overrides.insert(Runtime::Codex, FAKE_IMAGE.to_string());
    HostSetup {
        executor: Executor::Docker(docker),
        max_concurrent_runs: 2,
        wall_clock_cap: None,
        image_overrides: overrides,
        sandbox_network: None,
        inactivity_timeout: None,
        repository_request_timeout: None,
        tool_request_timeout: None,
        maintenance_every: None,
        credentials: Vec::new(),
        protocol_version: 1,
        model_proxy: ModelProxySettings::default(),
        bridge: None,
        egress: None,
        sandbox: None,
        pool: None,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_happy_path() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, docker_setup(docker)).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        let runtimes = hello["runtimes"].as_array().unwrap();
        assert_eq!(runtimes.len(), 2);
        assert_eq!(runtimes[0]["image"], FAKE_IMAGE);
        assert_eq!(runtimes[0]["version"], "fake-agent 1.0.0");

        let spec = AssignSpec::new("happy", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_contiguous_seq();
        t.assert_no_secrets();
        let finished = t.finished();
        let logs: Vec<String> = t
            .events_of("LOG")
            .iter()
            .map(|e| e["payload"]["message"].as_str().unwrap().to_string())
            .collect();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{finished:#}\n{logs:#?}");
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(finished["git"][0]["commits"], 2);
        assert_eq!(
            finished["summary"],
            "Done. I created hello.txt and committed it. Notes are left uncommitted."
        );
        assert_eq!(
            repo.file_at("agent/TEST-1", "hello.txt").unwrap(),
            "hello from the fake agent\n"
        );
        // Commits made inside the sandbox carry the run's git identity.
        let author = git(
            &repo.bare,
            &["log", "-1", "--format=%an <%ae>", "agent/TEST-1~1"],
        );
        assert_eq!(
            author.trim(),
            format!(
                "Tinfirm agent Tester <agent+{}@tinfirm.invalid>",
                spec.agent_id
            )
        );
        // The container is gone after the run.
        let name = docker::container_name(spec.run_id);
        let d = docker::connect().unwrap();
        assert!(
            d.inspect_container(&name, None).await.is_err(),
            "container removed"
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_container_settings_and_cancel() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, docker_setup(docker.clone())).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        loop {
            let f = conn.next().await.unwrap();
            if f["eventType"] == "AGENT_MESSAGE" {
                break;
            }
        }
        // docs/archive/v1/CONVENTIONS.md §15.3 container settings.
        let info = docker
            .inspect_container(&docker::container_name(spec.run_id), None)
            .await
            .expect("the run container exists");
        let config = info.config.unwrap();
        assert_eq!(config.user.as_deref(), Some("1000:1000"));
        assert_eq!(config.image.as_deref(), Some(FAKE_IMAGE));
        let labels = config.labels.unwrap();
        assert_eq!(
            labels.get("dev.tinfirm.run-id"),
            Some(&spec.run_id.to_string())
        );
        assert_eq!(
            labels.get("dev.tinfirm.runner-host"),
            Some(&server.runner_host_id.to_string())
        );
        let env = config.env.unwrap();
        assert!(env.contains(&format!("CLAUDE_CODE_OAUTH_TOKEN={TEST_CLAUDE_TOKEN}")));
        assert!(env.contains(&"DISABLE_AUTOUPDATER=1".to_string()));
        assert!(env.contains(&"GIT_CONFIG_COUNT=1".to_string()));
        assert!(env.contains(&"GIT_CONFIG_KEY_0=safe.directory".to_string()));
        assert!(env.contains(&"GIT_CONFIG_VALUE_0=/workspace/repo".to_string()));
        let env_text = env.join("\n");
        assert!(
            !env_text.contains(TEST_GIT_TOKEN),
            "git credentials never enter the sandbox"
        );
        assert!(
            !env_text.contains(TEST_HOST_TOKEN),
            "the host token never enters the sandbox"
        );
        let hc = info.host_config.unwrap();
        assert_eq!(hc.cap_drop, Some(vec!["ALL".to_string()]));
        assert!(
            hc.security_opt
                .unwrap()
                .iter()
                .any(|o| o.starts_with("no-new-privileges"))
        );
        assert_eq!(hc.memory, Some(4 * 1024 * 1024 * 1024));
        assert_eq!(hc.nano_cpus, Some(2_000_000_000));
        assert_eq!(hc.pids_limit, Some(1024));
        let binds = hc.binds.unwrap();
        let workspace = host.paths.workspace(spec.run_id);
        assert!(
            binds
                .iter()
                .any(|b| b == &format!("{}:/workspace:rw", workspace.display()))
        );
        assert!(binds.iter().any(|b| b.ends_with(":/home/agent:rw")));
        // The repository is at /workspace/repo; its git config has no token (it went through
        // the per-command env) and nothing points at the host's mirror.
        let git_config = std::fs::read_to_string(workspace.join("repo/.git/config")).unwrap();
        assert!(!git_config.contains(TEST_GIT_TOKEN) && !git_config.contains("extraHeader"));
        assert!(!git_config.contains("mirrors"), "{git_config}");
        assert!(!workspace.join("repo/.git/objects/info/alternates").exists());
        assert!(
            !binds.iter().any(|b| b.contains("mirrors")),
            "a mirror is never mounted: {binds:?}"
        );

        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "CANCELLED");
        assert_eq!(t.finished()["git"][0]["pushed"], true);
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").unwrap(),
            "work in progress\n"
        );
        assert!(
            docker
                .inspect_container(&docker::container_name(spec.run_id), None)
                .await
                .is_err()
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_leak_and_codex_usage() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, docker_setup(docker)).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        let leak = AssignSpec::new("leak", Some(&repo.url));
        conn.send(leak.to_json());
        let t = conn.until_finished(leak.run_id).await;
        t.assert_no_secrets();
        assert!(t.text().contains("[REDACTED]"));
        assert_eq!(t.finished()["failureClass"], "CREDENTIAL_LEAK");
        assert!(repo.branch_head("agent/TEST-1").is_none());

        let mut codex = AssignSpec::new("codex-usage", None);
        codex.runtime = "CODEX";
        codex.credential = "openai-test";
        conn.send(codex.to_json());
        let t = conn.until_finished(codex.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED");
        let usage = &t.finished()["usage"];
        let total = usage["inputTokens"].as_u64().unwrap()
            + usage["outputTokens"].as_u64().unwrap()
            + usage["cachedInputTokens"].as_u64().unwrap();
        assert_eq!(total, 7000);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_orphan_containers_are_removed_after_welcome() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let mut server = FakeServer::start().await;
        let orphan = format!("tinfirm-run-{}", Uuid::new_v4());
        let mut labels = HashMap::new();
        labels.insert(
            docker::RUNNER_HOST_LABEL.to_string(),
            server.runner_host_id.to_string(),
        );
        labels.insert(docker::RUN_ID_LABEL.to_string(), Uuid::new_v4().to_string());
        docker
            .create_container(
                Some(
                    CreateContainerOptionsBuilder::default()
                        .name(&orphan)
                        .build(),
                ),
                ContainerCreateBody {
                    image: Some(FAKE_IMAGE.to_string()),
                    labels: Some(labels),
                    ..Default::default()
                },
            )
            .await
            .unwrap();
        let host = start_host(&server.url, docker_setup(docker.clone())).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
        while docker.inspect_container(&orphan, None).await.is_ok() {
            assert!(
                tokio::time::Instant::now() < deadline,
                "orphan container not removed"
            );
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// The real binary: `run` with SIGTERM cancels the run, pushes WIP, reports CANCELLED and
/// exits 0.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_binary_sigterm_cancels_runs() {
    let Some(_docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let paths = tinfirm_runner_host::paths::Paths::new(
            dir.path().join("config"),
            dir.path().join("data"),
        );
        write_config(&paths, &server.url);
        let mut child = tokio::process::Command::new(env!("CARGO_BIN_EXE_tinfirm-runner-host"))
            .args(["run", "--max-concurrent-runs", "1"])
            .env("TINFIRM_RUNNER_CONFIG_DIR", &paths.config_dir)
            .env("TINFIRM_RUNNER_DATA_DIR", &paths.data_dir)
            .env("TINFIRM_RUNNER_IMAGE_CLAUDE", FAKE_IMAGE)
            .env("TINFIRM_RUNNER_ALLOW_INSECURE_REPOS", "1")
            .env("TINFIRM_RUNNER_LOG", "info")
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        assert_eq!(hello["maxConcurrentRuns"], 1);
        let spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        loop {
            let f = conn.next().await.unwrap();
            if f["eventType"] == "AGENT_MESSAGE" {
                break;
            }
        }
        let pid = child.id().unwrap().to_string();
        let status = std::process::Command::new("kill")
            .args(["-TERM", &pid])
            .status()
            .unwrap();
        assert!(status.success());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "CANCELLED");
        assert_eq!(t.finished()["git"][0]["pushed"], true);
        let exit = tokio::time::timeout(Duration::from_secs(60), child.wait())
            .await
            .unwrap()
            .unwrap();
        let mut stderr = String::new();
        use tokio::io::AsyncReadExt;
        child
            .stderr
            .take()
            .unwrap()
            .read_to_string(&mut stderr)
            .await
            .unwrap();
        assert!(exit.success(), "exit {exit:?}; stderr:\n{stderr}");
        // Logs are JSON lines and never contain a credential value.
        for line in stderr.lines().filter(|l| !l.trim().is_empty()) {
            let parsed: serde_json::Value =
                serde_json::from_str(line).unwrap_or_else(|_| panic!("not JSON: {line}"));
            assert!(parsed.get("level").is_some());
        }
        for secret in [TEST_CLAUDE_TOKEN, TEST_HOST_TOKEN, TEST_GIT_TOKEN] {
            assert!(!stderr.contains(secret));
        }
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").unwrap(),
            "work in progress\n"
        );
    })
    .await;
}

const BIN: &str = env!("CARGO_BIN_EXE_tinfirm-runner-host");

/// The real binary `run` with the fake image. Its stderr (JSON logs) is collected in the
/// background, so a full pipe never blocks it.
fn spawn_run(
    paths: &tinfirm_runner_host::paths::Paths,
    max_concurrent_runs: u32,
) -> (tokio::process::Child, tokio::task::JoinHandle<String>) {
    let mut child = tokio::process::Command::new(BIN)
        .args([
            "run",
            "--max-concurrent-runs",
            &max_concurrent_runs.to_string(),
        ])
        .env("TINFIRM_RUNNER_CONFIG_DIR", &paths.config_dir)
        .env("TINFIRM_RUNNER_DATA_DIR", &paths.data_dir)
        .env("TINFIRM_RUNNER_IMAGE_CLAUDE", FAKE_IMAGE)
        .env("TINFIRM_RUNNER_ALLOW_INSECURE_REPOS", "1")
        .env("TINFIRM_RUNNER_LOG", "info")
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let mut stderr = child.stderr.take().unwrap();
    let logs = tokio::spawn(async move {
        use tokio::io::AsyncReadExt;
        let mut text = String::new();
        let _ = stderr.read_to_string(&mut text).await;
        text
    });
    (child, logs)
}

/// `tinfirm-runner-host drain <args>` for the config of `paths`.
fn drain_command(
    paths: &tinfirm_runner_host::paths::Paths,
    args: &[&str],
) -> tokio::process::Command {
    let mut command = tokio::process::Command::new(BIN);
    command
        .arg("drain")
        .args(args)
        .env("TINFIRM_RUNNER_CONFIG_DIR", &paths.config_dir)
        .env("TINFIRM_RUNNER_DATA_DIR", &paths.data_dir)
        .env("TINFIRM_RUNNER_LOG", "warn")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    command
}

fn output_text(output: &std::process::Output) -> String {
    format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    )
}

/// Starts a `gate` run on the binary and waits until its agent runs.
async fn start_gate(conn: &mut Conn) -> AssignSpec<'static> {
    let spec = AssignSpec::new("gate", None);
    conn.send(spec.to_json());
    conn.take_run("run.accepted", spec.run_id).await;
    conn.wait_running(spec.run_id).await;
    spec
}

/// The frames `host.config.applied` with `draining: true`.
fn draining_frames(conn: &Conn) -> usize {
    conn.frames
        .iter()
        .filter(|f| f["type"] == "host.config.applied" && f["draining"] == true)
        .count()
}

fn assert_json_logs_without_secrets(stderr: &str) {
    for line in stderr.lines().filter(|l| !l.trim().is_empty()) {
        let parsed: serde_json::Value =
            serde_json::from_str(line).unwrap_or_else(|_| panic!("not JSON: {line}"));
        assert!(parsed.get("level").is_some());
    }
    for secret in [TEST_CLAUDE_TOKEN, TEST_HOST_TOKEN, TEST_GIT_TOKEN] {
        assert!(!stderr.contains(secret));
    }
}

/// The real binary: SIGHUP drains. `run` rejects new runs, lets the active one finish,
/// delivers its `run.finished`, closes with 1000 and exits 0. It found its PID in `run.lock`.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_binary_sighup_drains_and_exits_0() {
    let Some(_docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let paths = tinfirm_runner_host::paths::Paths::new(
            dir.path().join("config"),
            dir.path().join("data"),
        );
        write_config(&paths, &server.url);
        let (mut child, logs) = spawn_run(&paths, 2);
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        assert_eq!(hello["maxConcurrentRuns"], 2);
        let pid = child.id().unwrap();
        assert_eq!(
            std::fs::read_to_string(paths.run_lock_file()).unwrap(),
            format!("{pid}\n")
        );
        let a = start_gate(&mut conn).await;

        let status = std::process::Command::new("kill")
            .args(["-HUP", &pid.to_string()])
            .status()
            .unwrap();
        assert!(status.success());
        let applied = conn.take("host.config.applied").await;
        assert_eq!(applied["draining"], true);
        assert_eq!(applied["maxConcurrentRuns"], 0);
        // A second SIGHUP, here from `drain --no-wait`, changes nothing.
        let out = drain_command(&paths, &["--no-wait"])
            .output()
            .await
            .unwrap();
        assert!(out.status.success(), "{}", output_text(&out));
        assert!(
            output_text(&out).contains(&format!("PID {pid}")),
            "{}",
            output_text(&out)
        );
        let b = AssignSpec::new("gate", None);
        conn.send(b.to_json());
        let r = conn.take_run("run.rejected", b.run_id).await;
        assert_eq!(r["code"], "DRAINING");
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert!(
            child.try_wait().unwrap().is_none(),
            "`run` waits for its run"
        );

        release(&paths, a.run_id);
        let finished = conn.take_run("run.finished", a.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        let exit = tokio::time::timeout(Duration::from_secs(60), child.wait())
            .await
            .expect("`run` exits after the drain")
            .unwrap();
        let stderr = logs.await.unwrap();
        assert!(exit.success(), "exit {exit:?}; stderr:\n{stderr}");
        while conn.next().await.is_some() {}
        assert_eq!(
            conn.close_frame.lock().unwrap().as_ref().map(|c| c.0),
            Some(1000)
        );
        assert_eq!(draining_frames(&conn), 1);
        assert!(stderr.contains("already draining"), "{stderr}");
        assert_json_logs_without_secrets(&stderr);
        // The PID leaves with the process.
        assert_eq!(std::fs::read_to_string(paths.run_lock_file()).unwrap(), "");
    })
    .await;
}

/// The real binary: SIGHUP from a terminal that closed. Nothing reads its log any more (here a
/// pipe without a reader, EPIPE, where a hung-up terminal answers EIO), and `run` still drains
/// and exits 0, rather than drain with 0 runs for ever.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_binary_sighup_without_stderr_drains_and_exits_0() {
    let Some(_docker) = docker_ready().await else {
        return;
    };
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let paths = tinfirm_runner_host::paths::Paths::new(
            dir.path().join("config"),
            dir.path().join("data"),
        );
        write_config(&paths, &server.url);
        let (mut child, logs) = spawn_run(&paths, 2);
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        // The terminal closes: the reader of stderr is gone.
        logs.abort();
        let _ = logs.await;

        let pid = child.id().unwrap();
        let status = std::process::Command::new("kill")
            .args(["-HUP", &pid.to_string()])
            .status()
            .unwrap();
        assert!(status.success());
        let applied = conn.take("host.config.applied").await;
        assert_eq!(applied["draining"], true);
        let exit = tokio::time::timeout(Duration::from_secs(60), child.wait())
            .await
            .expect("`run` exits after the drain")
            .unwrap();
        assert!(exit.success(), "exit {exit:?}");
        while conn.next().await.is_some() {}
        assert_eq!(
            conn.close_frame.lock().unwrap().as_ref().map(|c| c.0),
            Some(1000)
        );
    })
    .await;
}

/// The real binary: `drain` finds `run` through `run.lock`, sends SIGHUP and waits until it
/// has exited.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_drain_command_waits_for_run_to_exit() {
    let Some(_docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let paths = tinfirm_runner_host::paths::Paths::new(
            dir.path().join("config"),
            dir.path().join("data"),
        );
        write_config(&paths, &server.url);
        let (mut child, logs) = spawn_run(&paths, 2);
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let a = start_gate(&mut conn).await;

        let mut drain = drain_command(&paths, &[]).spawn().unwrap();
        let applied = conn.take("host.config.applied").await;
        assert_eq!(applied["draining"], true);
        let b = AssignSpec::new("gate", None);
        conn.send(b.to_json());
        assert_eq!(
            conn.take_run("run.rejected", b.run_id).await["code"],
            "DRAINING"
        );
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert!(
            child.try_wait().unwrap().is_none(),
            "`run` waits for its run"
        );
        assert!(
            drain.try_wait().unwrap().is_none(),
            "`drain` waits for `run`"
        );

        release(&paths, a.run_id);
        let finished = conn.take_run("run.finished", a.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        let out = tokio::time::timeout(Duration::from_secs(60), drain.wait_with_output())
            .await
            .expect("`drain` returns once `run` has exited")
            .unwrap();
        assert!(out.status.success(), "{}", output_text(&out));
        assert!(
            output_text(&out).contains("has exited"),
            "{}",
            output_text(&out)
        );
        // `drain` returned when `run` let go of run.lock, which happens as the process ends.
        let exit = tokio::time::timeout(Duration::from_secs(5), child.wait())
            .await
            .expect("`run` has exited")
            .unwrap();
        let stderr = logs.await.unwrap();
        assert!(exit.success(), "exit {exit:?}; stderr:\n{stderr}");
        while conn.next().await.is_some() {}
        assert_eq!(
            conn.close_frame.lock().unwrap().as_ref().map(|c| c.0),
            Some(1000)
        );
        assert_json_logs_without_secrets(&stderr);

        let out = drain_command(&paths, &[]).output().await.unwrap();
        assert!(out.status.success(), "{}", output_text(&out));
        assert!(
            output_text(&out).contains("Nothing to drain"),
            "{}",
            output_text(&out)
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_admission_rejects_a_missing_image() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let mut setup = docker_setup(docker);
        setup
            .image_overrides
            .insert(Runtime::ClaudeCode, "tinfirm-runner-missing:test".into());
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("happy", None);
        conn.send(spec.to_json());
        let r = conn.take_run("run.rejected", spec.run_id).await;
        assert_eq!(r["code"], "IMAGE_MISSING");
        assert!(
            r["reason"]
                .as_str()
                .unwrap()
                .contains("tinfirm-runner-missing:test is missing"),
            "{r}"
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// Smoke test of the REAL runner images (`images build` first), fully offline: the sandbox
/// gets `--network none`, so no request reaches Anthropic or OpenAI and no credential is
/// real. It proves that initialize and session/new work against claude-agent-acp (with the
/// bundled Claude Code) and codex-acp (with codex app-server) in the container settings of
/// docs/archive/v1/CONVENTIONS.md §15.3, and that cancel/guards stop them. Run with
/// `cargo test -p tinfirm-runner-host --test docker_runs -- --ignored real_images`.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "needs the real runner images (images build)"]
async fn real_images_offline_smoke() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    for image in [images::CLAUDE_IMAGE, images::CODEX_IMAGE] {
        if images::inspect(&docker, image).await.unwrap().is_none() {
            eprintln!("skipped: {image} is missing; run `tinfirm-runner-host images build`");
            return;
        }
    }
    with_timeout(300, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            executor: Executor::Docker(docker),
            max_concurrent_runs: 2,
            wall_clock_cap: Some(Duration::from_secs(40)),
            image_overrides: HashMap::new(),
            sandbox_network: Some("none".into()),
            inactivity_timeout: None,
            repository_request_timeout: None,
            tool_request_timeout: None,
            maintenance_every: None,
            credentials: Vec::new(),
            protocol_version: 1,
            model_proxy: ModelProxySettings::default(),
            bridge: None,
            egress: None,
            sandbox: None,
            pool: None,
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        let versions: Vec<&str> = hello["runtimes"]
            .as_array()
            .unwrap()
            .iter()
            .map(|r| r["version"].as_str().unwrap())
            .collect();
        eprintln!("runtimes: {versions:?}");
        assert!(versions.iter().any(|v| v.starts_with("claude-code ")));
        assert!(versions.iter().any(|v| v.starts_with("codex ")));

        let mut claude = AssignSpec::new("happy", Some(&repo.url));
        claude.task_key = "REAL-1";
        let mut codex = AssignSpec::new("happy", Some(&repo.url));
        codex.task_key = "REAL-2";
        codex.runtime = "CODEX";
        codex.credential = "openai-test";
        conn.send(claude.to_json());
        conn.send(codex.to_json());
        let mut finished = Vec::new();
        let mut all = Transcript::default();
        while finished.len() < 2 {
            let f = conn.next().await.expect("connection open");
            if f["type"] == "run.finished" {
                finished.push(f.clone());
            }
            all.push(f);
        }
        all.assert_no_secrets();
        for run in [&claude, &codex] {
            let id = run.run_id.to_string();
            let frames: Vec<serde_json::Value> = all
                .frames
                .iter()
                .filter(|f| f["runId"] == id)
                .cloned()
                .collect();
            let t = Transcript { frames };
            let logs: Vec<String> = t
                .events_of("LOG")
                .iter()
                .map(|e| e["payload"]["message"].as_str().unwrap().to_string())
                .collect();
            let fin = t.finished();
            eprintln!(
                "{} {}: {} {:?} {:?}",
                run.runtime, id, fin["outcome"], fin["failureClass"], fin["failureMessage"]
            );
            for line in logs.iter().rev().take(6).rev() {
                eprintln!("  log: {line}");
            }
            assert!(
                logs.iter().any(|l| l.starts_with("started session ")),
                "session/new must succeed against the real adapter"
            );
            assert_eq!(
                fin["outcome"], "FAILED",
                "offline, the model call cannot succeed"
            );
            assert!(t.statuses().contains(&"running".to_string()));
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// The real pi and OpenCode images (ADR-0041) with the host's OpenAI API key, offline: the
/// adapter starts, opens a session, and the turn fails at the model call. Run like
/// `real_images_offline_smoke`, after `images build --runtime pi` and `--runtime opencode`.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "needs the real runner images (images build)"]
async fn real_pi_and_opencode_images_offline_smoke() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    for image in [images::PI_IMAGE, images::OPENCODE_IMAGE] {
        if images::inspect(&docker, image).await.unwrap().is_none() {
            eprintln!("skipped: {image} is missing; run `tinfirm-runner-host images build`");
            return;
        }
    }
    with_timeout(300, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            executor: Executor::Docker(docker),
            max_concurrent_runs: 2,
            wall_clock_cap: Some(Duration::from_secs(60)),
            image_overrides: HashMap::new(),
            sandbox_network: Some("none".into()),
            inactivity_timeout: None,
            repository_request_timeout: None,
            tool_request_timeout: None,
            maintenance_every: None,
            credentials: Vec::new(),
            protocol_version: 2,
            model_proxy: ModelProxySettings::default(),
            bridge: None,
            egress: None,
            sandbox: None,
            pool: None,
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        let versions: Vec<&str> = hello["runtimes"]
            .as_array()
            .unwrap()
            .iter()
            .map(|r| r["version"].as_str().unwrap())
            .collect();
        eprintln!("runtimes: {versions:?}");
        assert!(versions.iter().any(|v| v.starts_with("pi ")));
        assert!(versions.iter().any(|v| v.starts_with("opencode ")));

        // The host's own OpenAI API key, not a gateway: a gateway's run needs its model proxy,
        // which a sandbox without a network cannot reach, so it would fail before the adapter
        // starts; and model access without a base URL is refused (I8 of the security review of
        // 2026-10-27).
        let mut pi = AssignSpec::v2("happy", Some(&repo.url));
        pi.credential = "openai-test";
        pi.task_key = "REAL-3";
        pi.runtime = "PI";
        pi.model = Some("gpt-5.5");
        let mut opencode = AssignSpec::v2("happy", Some(&repo.url));
        opencode.credential = "openai-test";
        opencode.task_key = "REAL-4";
        opencode.runtime = "OPENCODE";
        conn.send(pi.to_json());
        conn.send(opencode.to_json());
        let mut finished = Vec::new();
        let mut all = Transcript::default();
        while finished.len() < 2 {
            let f = conn.next().await.expect("connection open");
            if f["type"] == "run.finished" {
                finished.push(f.clone());
            }
            all.push(f);
        }
        all.assert_no_secrets();
        assert!(!all.text().contains(TEST_OPENAI_KEY));
        for run in [&pi, &opencode] {
            let id = run.run_id.to_string();
            let frames: Vec<serde_json::Value> = all
                .frames
                .iter()
                .filter(|f| f["runId"] == id)
                .cloned()
                .collect();
            let t = Transcript { frames };
            let logs: Vec<String> = t
                .events_of("LOG")
                .iter()
                .map(|e| e["payload"]["message"].as_str().unwrap().to_string())
                .collect();
            let fin = t.finished();
            eprintln!(
                "{} {}: {} {:?} {:?}",
                run.runtime, id, fin["outcome"], fin["failureClass"], fin["failureMessage"]
            );
            for line in logs.iter().rev().take(6).rev() {
                eprintln!("  log: {line}");
            }
            assert!(
                logs.iter().any(|l| l.starts_with("started session ")),
                "session/new must succeed against the real adapter"
            );
            assert_eq!(
                fin["outcome"], "FAILED",
                "offline, the model call cannot succeed"
            );
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// The sandbox fetches through the git proxy (`host.docker.internal` with Docker Desktop,
/// the bridge gateway with Docker Engine on Linux), reads blobs lazily, and cannot push.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_sandbox_fetches_through_the_git_proxy() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    let t = with_timeout(300, run_fetch_scenario(docker_setup(docker))).await;
    // Engines in a VM (macOS) reach the proxy on this machine's loopback.
    if !cfg!(target_os = "linux") {
        let text = t.agent_text();
        assert!(
            text.contains("origin: http://host.docker.internal:"),
            "{text}"
        );
    }
}

/// A container whose agent hangs (no messages, ignores session/cancel) is stopped by the
/// inactivity guard; the run fails GUARD_STOPPED, the WIP is pushed, the container is gone.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_inactivity_guard_stops_a_hung_container() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let mut setup = docker_setup(docker.clone());
        setup.inactivity_timeout = Some(Duration::from_secs(2));
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("hang", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["failureClass"], "GUARD_STOPPED");
        assert!(
            t.finished()["failureMessage"]
                .as_str()
                .unwrap()
                .contains("inactivity")
        );
        assert_eq!(t.finished()["git"][0]["pushed"], true);
        assert!(
            docker
                .inspect_container(&docker::container_name(spec.run_id), None)
                .await
                .is_err()
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// Several repositories in a container: each at `/workspace/<dir>`, pushed one by one; then
/// the platform tools over MCP from inside the container (`host.docker.internal` with Docker
/// Desktop, the bridge gateway with Docker Engine on Linux).
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_several_repositories_and_the_platform_tools() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let server_repo = TestRepo::new();
        let web_repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, docker_setup(docker)).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let workspaces = vec![
            WorkspaceEntry::new("server", "Server", &server_repo.url),
            WorkspaceEntry::new("web-app", "Web App", &web_repo.url),
        ];
        let mut spec = AssignSpec::with_workspaces("multi", workspaces);
        spec.prompt_extra = " [commit:server] [wip:web-app]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let text = t.agent_text();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{finished:#}\n{text}");
        assert!(text.contains("dirs: server,web-app"), "{text}");
        assert!(text.contains("root is cwd: true"), "{text}");
        assert!(text.contains("alternates server: false"), "{text}");
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(finished["git"][1]["pushed"], true);
        assert_eq!(
            server_repo.file_at("agent/TEST-1", "server.txt").as_deref(),
            Some("change in server\n")
        );
        assert_eq!(
            web_repo.file_at("agent/TEST-1", "wip.txt").as_deref(),
            Some("work in progress in web-app\n")
        );

        let mut request = AssignSpec::new("request-repo", None);
        request.requestable = vec![requestable("Web", false)];
        request.prompt_extra = " [request:Web]".into();
        conn.send(request.to_json());
        let mut requests = Vec::new();
        loop {
            let frame = conn.next().await.expect("connection open");
            if frame["type"] == "run.repository_request" {
                conn.send(repository_ack(
                    &frame,
                    "PENDING",
                    None,
                    "Requested the repository `Web`.",
                ));
                requests.push(frame.clone());
            }
            if frame["type"] == "run.finished" && frame["runId"] == request.run_id.to_string() {
                break;
            }
        }
        let t = run_frames(&conn, request.run_id);
        t.assert_no_secrets();
        let text = t.agent_text();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{text}");
        assert_eq!(requests.len(), 1);
        assert!(
            text.contains("request Web: ok: Requested the repository `Web`."),
            "{text}"
        );
        assert!(text.contains("initialize: 200 tinfirm"), "{text}");
        if !cfg!(target_os = "linux") {
            assert!(
                text.contains("mcp url: http://host.docker.internal:"),
                "{text}"
            );
        }
        assert!(text.contains("/[REDACTED]/mcp"), "{text}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// The Engine API itself accepts the container of a sandbox with Docker in it. The `docker`
/// CLI translates options that the API rejects (`systempaths=unconfined`), so a check with
/// the CLI proves nothing for the runner host. Created under the default runtime and never
/// started: running it needs a `kata…` runtime.
#[tokio::test]
async fn the_engine_accepts_the_container_of_a_sandbox_with_docker() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    let name = format!("tinfirm-test-sandbox-docker-{}", Uuid::new_v4());
    let spec = tinfirm_runner_host::sandbox::SandboxSpec {
        name: name.clone(),
        image: FAKE_IMAGE.to_string(),
        env: Vec::new(),
        binds: Vec::new(),
        labels: HashMap::new(),
        resources: tinfirm_runner_host::sandbox::Resources::default(),
        network_mode: None,
        extra_hosts: Vec::new(),
        dns: Vec::new(),
        runtime: None,
        docker: true,
        bridge_binary: None,
        command: None,
        local_cwd: std::path::PathBuf::new(),
        local_home: std::path::PathBuf::new(),
    };
    let created = docker
        .create_container(
            Some(CreateContainerOptionsBuilder::default().name(&name).build()),
            tinfirm_runner_host::sandbox::container_body(&spec),
        )
        .await;
    let inspected = docker.inspect_container(&name, None).await;
    let _ = docker
        .remove_container(
            &name,
            Some(
                bollard::query_parameters::RemoveContainerOptionsBuilder::default()
                    .force(true)
                    .build(),
            ),
        )
        .await;
    created.expect("the daemon accepts the container of a sandbox with Docker");
    let hc = inspected.unwrap().host_config.unwrap();
    assert!(hc.masked_paths.unwrap_or_default().is_empty());
    assert!(hc.readonly_paths.unwrap_or_default().is_empty());
    assert_eq!(hc.cap_add, Some(vec!["ALL".to_string()]));
    assert_eq!(hc.privileged, Some(false));
}

/// The canonical zip of a skill: `SKILL.md` and a script, deflated.
fn skill_zip(name: &str) -> Vec<u8> {
    use std::io::Write;
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    writer.start_file("SKILL.md", options).unwrap();
    write!(
        writer,
        "---\nname: {name}\ndescription: Fills PDF forms.\n---\nRun scripts/fill.py.\n"
    )
    .unwrap();
    writer.start_file("scripts/fill.py", options).unwrap();
    writer.write_all(b"print('filled')\n").unwrap();
    writer.finish().unwrap().into_inner()
}

/// ADR-0064: the skill is downloaded, unpacked into the cache and mounted read-only at
/// `~/.claude/skills/<name>`, where the runtime still writes its own files in `~/.claude`;
/// the next run of the home without the skill no longer has its empty mount point.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn docker_skills_are_mounted_read_only_where_the_runtime_reads_them() {
    let Some(docker) = docker_ready().await else {
        return;
    };
    with_timeout(300, async {
        let mut server = FakeServer::start().await;
        let mut setup = docker_setup(docker);
        setup.protocol_version = 2;
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        assert_eq!(
            hello["features"],
            json!(["run.steer", "run.skills", "session.bundle"])
        );

        let zip = skill_zip("pdf");
        let sha256 = sha256_hex(&zip);
        let skill_id = Uuid::new_v4();
        server
            .files
            .lock()
            .unwrap()
            .insert(skill_id.to_string(), zip.clone());
        let spec = AssignSpec::v2("skills", None);
        let mut frame = spec.to_json();
        frame["skills"] = json!([
            {"skillId": skill_id, "version": 2, "name": "pdf", "sha256": sha256, "sizeBytes": zip.len()}
        ]);
        conn.send(frame);
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(
            t.text()
                .contains("1 skill in /home/agent/.claude/skills: pdf v2"),
            "{}",
            t.text()
        );
        assert!(
            t.agent_text()
                .ends_with("skills: [.claude/skills/pdf: name: pdf, read-only]; home writable"),
            "{}",
            t.agent_text()
        );
        let downloads = server.downloads.lock().unwrap().clone();
        assert_eq!(downloads.len(), 1);
        assert_eq!(
            downloads[0].path,
            format!(
                "/api/runner/v1/runs/{}/skills/{skill_id}/versions/2?epoch=1",
                spec.run_id
            )
        );
        assert_eq!(
            downloads[0].authorization,
            format!("Bearer {TEST_HOST_TOKEN}")
        );
        let home = host.paths.session_home(spec.agent_id, spec.task_id);
        assert!(home.join(".claude/fake-session.json").is_file());
        assert!(home.join(".claude/skills/pdf").is_dir());
        assert!(
            host.paths
                .skill(&sha256, "pdf")
                .join("scripts/fill.py")
                .is_file()
        );

        // The next run of the home, without the skill.
        let mut next = AssignSpec::v2("skills", None);
        next.agent_id = spec.agent_id;
        next.task_id = spec.task_id;
        conn.send(next.to_json());
        let t = conn.until_finished(next.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(
            t.agent_text().ends_with("skills: []; home writable"),
            "{}",
            t.agent_text()
        );
        assert!(!home.join(".claude/skills/pdf").exists());
        assert!(home.join(".claude/skills").is_dir());
        assert_eq!(server.downloads.lock().unwrap().len(), 1);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// Docker, without the fake agent's image, or `None` when Docker tests are skipped.
async fn docker_only() -> Option<Docker> {
    if std::env::var("TINFIRM_SKIP_DOCKER_TESTS").is_ok_and(|v| v == "1") {
        eprintln!("skipped: TINFIRM_SKIP_DOCKER_TESTS=1");
        return None;
    }
    let docker = docker::connect().ok()?;
    match docker.ping().await {
        Ok(_) => Some(docker),
        Err(e) => {
            eprintln!("skipped: Docker is not reachable ({e})");
            None
        }
    }
}

/// `run`'s rebuild of stale images (ADR-0062), with two small images in place of the base and a
/// runtime image: a first build of recorded images, a rebuild of only the image whose
/// Dockerfile changed, and a base that fails, on which no runtime image is built.
#[tokio::test]
async fn stale_images_are_rebuilt_and_a_failed_base_stops_the_runtime_images() {
    let Some(docker) = docker_only().await else {
        return;
    };
    const BASE_TAG: &str = "tinfirm-refresh-test-base:local";
    const RUNTIME_TAG: &str = "tinfirm-refresh-test-runtime:local";
    let remove = || async {
        for tag in [RUNTIME_TAG, BASE_TAG] {
            let _ = docker
                .remove_image(
                    tag,
                    Some(
                        bollard::query_parameters::RemoveImageOptionsBuilder::default()
                            .force(true)
                            .build(),
                    ),
                    None,
                )
                .await;
        }
    };
    remove().await;
    let base = images::ImageDef {
        tag: BASE_TAG,
        dockerfile: "FROM scratch\nCOPY base.txt /base.txt\n",
        runtime: None,
        multi: false,
        files: &[("base.txt", b"base 1\n")],
    };
    let runtime = images::ImageDef {
        tag: RUNTIME_TAG,
        dockerfile: "FROM tinfirm-refresh-test-base:local\nARG NPM_REGISTRY=https://registry.npmjs.org/\nCOPY runtime.txt /runtime.txt\nLABEL dev.tinfirm.runtime-version=\"test 1\"\n",
        runtime: Some(Runtime::Opencode),
        multi: false,
        files: &[("runtime.txt", b"runtime 1\n")],
    };
    let args = vec![(
        "NPM_REGISTRY".to_string(),
        "https://nexus.example/npm/".to_string(),
    )];
    let opencode_built = images::ImageSettings {
        runtimes: vec![Runtime::Opencode],
        ..images::ImageSettings::default()
    };
    let present = |defs: Vec<images::ImageDef>| {
        let docker = docker.clone();
        async move { images::present_specs(&docker, &base, &defs).await.unwrap() }
    };

    // Not built here and not recorded: nothing to do. Recorded: both are built.
    let plan = images::plan_refresh(
        &base,
        &[runtime],
        &args,
        &images::ImageSettings::default(),
        &present(vec![runtime]).await,
    );
    assert!(plan.is_empty());
    let plan = images::plan_refresh(
        &base,
        &[runtime],
        &args,
        &opencode_built,
        &present(vec![runtime]).await,
    );
    let out = images::refresh(&docker, &base, &plan, &args, |_| {}).await;
    assert!(out.failed.is_empty(), "{:?}", out.failed);
    assert_eq!(
        out.built,
        vec![
            (BASE_TAG, images::Staleness::Missing),
            (RUNTIME_TAG, images::Staleness::Missing)
        ]
    );
    let status = images::inspect(&docker, RUNTIME_TAG)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(status.spec, Some(images::spec(&runtime, &base, &args)));
    assert_eq!(status.version.as_deref(), Some("test 1"));
    let plan = images::plan_refresh(
        &base,
        &[runtime],
        &args,
        &opencode_built,
        &present(vec![runtime]).await,
    );
    assert!(plan.is_empty(), "current after the build");

    // A new binary's runtime Dockerfile: only that image is rebuilt.
    let runtime_2 = images::ImageDef {
        files: &[("runtime.txt", b"runtime 2\n")],
        ..runtime
    };
    let plan = images::plan_refresh(
        &base,
        &[runtime_2],
        &args,
        &images::ImageSettings::default(),
        &present(vec![runtime_2]).await,
    );
    let out = images::refresh(&docker, &base, &plan, &args, |_| {}).await;
    assert!(out.failed.is_empty(), "{:?}", out.failed);
    assert_eq!(out.built, vec![(RUNTIME_TAG, images::Staleness::Outdated)]);

    // A base that cannot be built: no runtime image is built on the old one, and the images
    // stay as they were.
    let broken_base = images::ImageDef {
        dockerfile: "FROM scratch\nRUN false\n",
        ..base
    };
    let before = images::inspect(&docker, RUNTIME_TAG)
        .await
        .unwrap()
        .unwrap();
    let plan = images::plan_refresh(
        &broken_base,
        &[runtime_2],
        &args,
        &images::ImageSettings::default(),
        &images::present_specs(&docker, &broken_base, &[runtime_2])
            .await
            .unwrap(),
    );
    assert_eq!(plan.base, Some(images::Staleness::Outdated));
    let out = images::refresh(&docker, &broken_base, &plan, &args, |_| {}).await;
    assert!(out.built.is_empty(), "{:?}", out.built);
    let failed: Vec<&str> = out.failed.iter().map(|(tag, _)| *tag).collect();
    assert_eq!(failed, vec![BASE_TAG, RUNTIME_TAG]);
    assert!(
        out.failed[1].1.contains("could not be built"),
        "{:?}",
        out.failed
    );
    let after = images::inspect(&docker, RUNTIME_TAG)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(after.id, before.id);
    remove().await;
}
