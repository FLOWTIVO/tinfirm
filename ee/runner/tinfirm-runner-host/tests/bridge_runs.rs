//! End-to-end runs over the sandbox bridge (ADR-0095, `run --sandbox-bridge`) with the local
//! test executor: the host starts this crate's binary as `bridge`, with only the bridge's
//! settings in its environment; the bridge connects back over pinned TLS, listens for the
//! run's tunnels on 127.0.0.1 and starts the fake agent with the run's environment. The host's
//! listeners are on 127.0.0.2 here, where the agent reaches them only through the tunnels. The
//! Docker side (the mount, the container's settings) is covered by the unit tests of
//! `sandbox.rs`.

#[path = "../../../../runner/crates/tinfirm-runner-host/tests/common/mod.rs"]
mod common;

use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;
use std::time::Duration;

use common::*;
use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_over_the_bridge_clones_runs_commits_and_pushes() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("happy", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_contiguous_seq();
        t.assert_no_secrets();
        assert_eq!(
            t.statuses(),
            [
                "preparing",
                "cloning",
                "starting",
                "running",
                "finalizing",
                "pushing"
            ]
        );
        assert!(
            t.agent_text()
                .contains("Done. I created hello.txt and committed it.")
        );
        let finished = t.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED");
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(finished["git"][0]["commits"], 2);
        assert_eq!(
            repo.file_at("agent/TEST-1", "hello.txt").unwrap(),
            "hello from the fake agent\n"
        );
        // The agent ran over the bridge, and the run's registration ended with the run.
        assert!(
            t.text().contains("the agent runs over the sandbox bridge"),
            "no bridge in the run's log"
        );
        let listener = host.host.bridge().await.unwrap().expect("a listener");
        assert_eq!(listener.server.run_count(), 0);
        // The run's record went with its sandbox; the listener's identity stays for the next
        // process of the host (ADR-0101).
        let runs = host.paths.data_dir.join("runs");
        assert!(!runs.join(format!("{}.json", spec.run_id)).exists());
        assert!(runs.join("bridge.json").exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_server_whose_licence_holds_no_elastic_runners_stops_the_host() {
    with_timeout(60, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::bridged();
        setup.protocol_version = 2;
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.expect("hello").await;
        assert!(
            hello["features"]
                .as_array()
                .unwrap()
                .contains(&json!("sandbox.bridge")),
            "{hello}"
        );
        // A Community server (ADR-0100): an error before the welcome, then 1008.
        conn.send(json!({
            "type": "error",
            "code": "EDITION_REQUIRED",
            "message": "This server's licence does not hold elastic runners"
        }));
        conn.close_with(1008, "Edition required");
        let err = host.handle.await.unwrap().expect_err("a fatal end");
        let message = format!("{err:#}");
        assert!(message.contains("EDITION_REQUIRED"), "{message}");
        assert!(
            message.contains("does not hold elastic runners"),
            "{message}"
        );
        assert!(
            tokio::time::timeout(Duration::from_millis(500), server.accept())
                .await
                .is_err(),
            "the host must not reconnect"
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agent_gets_the_runs_environment_and_the_bridge_holds_no_credential() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("bridge-env", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED");
        let report = t.agent_text();
        // The credential reaches the agent through `start`...
        assert!(report.contains("token=set"), "{report}");
        assert!(report.contains("run=set"), "{report}");
        // ...none of the bridge's own settings do...
        assert!(report.contains("bridge-vars=0"), "{report}");
        // ...the agent's parent is the bridge, and the bridge's environment (the sandbox's
        // settings) never held the credential.
        if cfg!(target_os = "linux") {
            assert!(report.contains("parent=bridge"), "{report}");
            assert!(report.contains("parent-has-token=no"), "{report}");
        }
        t.assert_no_secrets();
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn without_the_bridge_the_agent_is_the_hosts_own_child() {
    // The control for the test above: the same scenario, attached.
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("bridge-env", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let report = t.agent_text();
        assert!(report.contains("token=set"), "{report}");
        if cfg!(target_os = "linux") {
            assert!(report.contains("parent=other"), "{report}");
        }
        assert!(!t.text().contains("the agent runs over the sandbox bridge"));
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_cancel_stops_the_agent_over_the_bridge_and_pushes_its_work() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        conn.expect("run.heartbeat").await;
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "CANCELLED");
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(
            repo.subjects("agent/TEST-1")[0],
            format!("WIP: uncommitted changes from run {}", spec.run_id)
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_v2_run_over_the_bridge_uses_the_platform_tools_and_saves_its_output() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            protocol_version: 2,
            ..HostSetup::bridged()
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(2).await;
        let spec = AssignSpec::v2("happy", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED");
        assert_eq!(t.finished()["git"][0]["pushed"], true);
        t.assert_no_secrets();
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_sandbox_whose_bridge_never_connects_fails_the_run_with_infra() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        // A "bridge" that exits at once, as a sandbox whose bridge cannot run would.
        let setup = HostSetup {
            bridge: Some("/bin/true".into()),
            ..HostSetup::bridged()
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("happy", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "INFRA");
        assert!(
            t.text().contains("ended before its bridge connected"),
            "{}",
            t.text()
        );
        // Its registration is gone with the run.
        let listener = host.host.bridge().await.unwrap().expect("a listener");
        assert_eq!(listener.server.run_count(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

// ----- the host's listeners, through the bridge's tunnels --------------------------------

/// The host's listeners bind 127.0.0.2 with the bridge and the local executor.
const LISTENERS: IpAddr = IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2));

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agent_fetches_through_its_tunnel_to_the_git_proxy() {
    let t = with_timeout(120, run_fetch_scenario(HostSetup::bridged())).await;
    let text = t.agent_text();
    // `origin` names the sandbox's own loopback, where the tunnel listens: here the run's
    // own address, since the local executor's sandboxes share this machine's network.
    assert!(
        text.contains("origin: http://127.") && !text.contains("origin: http://127.0.0.2:"),
        "the origin is not the tunnel: {text}"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_model_proxy_answers_through_the_tunnel_and_the_listeners_are_on_loopback() {
    use tinfirm_runner_host::model_proxy::DUMMY_KEY;
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("model-proxy", None);
        // A gateway on a documentation address: the proxy refuses to connect there, so its
        // answer shows that the run's listener answered through the tunnel.
        spec.model_access = Some(json!({
            "format": "anthropic", "baseUrl": "https://192.0.2.10:8443", "apiKey": TEST_MODEL_KEY
        }));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let text = t.agent_text();
        assert!(text.contains(&format!("model-key={DUMMY_KEY}")), "{text}");
        assert!(text.contains("messages=502"), "{text}");
        assert!(text.contains("keys=404"), "{text}");
        // The listeners are on loopback, not where the sandboxes reach this host.
        let proxy = host.host.git_proxy().await.unwrap().expect("started");
        assert_eq!(proxy.local_addr().ip(), LISTENERS);
        assert_eq!(proxy.endpoint().sandbox_host, "127.0.0.1");
        // The run is over: its tunnel is gone with its bridge.
        let port = text
            .split("model-port=")
            .nth(1)
            .and_then(|rest| rest.split_whitespace().next())
            .and_then(|port| port.parse::<u16>().ok())
            .expect("a port");
        let tunnel = text
            .split("model-host=")
            .nth(1)
            .and_then(|rest| rest.split_whitespace().next())
            .expect("a host")
            .to_string();
        assert!(
            tunnel.starts_with("127.") && tunnel != "127.0.0.2",
            "{tunnel}"
        );
        let mut gone = false;
        for _ in 0..50 {
            if tokio::net::TcpStream::connect((tunnel.as_str(), port))
                .await
                .is_err()
            {
                gone = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert!(gone, "the run's tunnel still listens on {port}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_platform_tools_answer_through_the_tunnel() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("request-repo", None);
        spec.prompt_extra = " [finish:COMPLETED]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let text = t.agent_text();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{text}");
        for line in [
            "mcp url: http://127.",
            "initialize: 200 tinfirm",
            "finish COMPLETED: ok",
        ] {
            assert!(text.contains(line), "missing `{line}` in:\n{text}");
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// Echoes one line per connection, on loopback.
async fn echo() -> u16 {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        while let Ok((mut socket, _)) = listener.accept().await {
            tokio::spawn(async move {
                let mut buf = [0u8; 1024];
                if let Ok(n) = socket.read(&mut buf).await {
                    let _ = socket.write_all(&buf[..n]).await;
                }
            });
        }
    });
    port
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_egress_proxy_answers_through_the_tunnel() {
    use tinfirm_runner_host::egress_proxy::Rule;
    with_timeout(120, async {
        let port = echo().await;
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            egress: Some(EgressSettings {
                allow: vec![Rule::parse(&format!("localhost:{port}")).unwrap()],
                allow_private: false,
                allow_loopback: true,
            }),
            ..HostSetup::bridged()
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        // An allowed destination: the proxy, reached through the tunnel, connects to it.
        let mut spec = AssignSpec::new("egress", None);
        spec.prompt_extra = format!(" [target:localhost:{port}]");
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let text = t.agent_text();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{text}");
        assert!(
            text.contains("proxy: 127.") && !text.contains("proxy: 127.0.0.2:"),
            "{text}"
        );
        assert!(text.contains("echo: hello through the tunnel"), "{text}");
        // Another destination: the proxy's own rules refuse it, as without the bridge.
        let mut spec = AssignSpec::new("egress", None);
        spec.prompt_extra = " [target:localhost:9]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let text = t.agent_text();
        assert!(text.contains("status: HTTP/1.1 403"), "{text}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

// ----- the run's files over the bridge ---------------------------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agents_work_stays_in_the_sandbox_until_the_host_takes_it() {
    use tinfirm_runner_host::sandbox_files::Layout;
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        loop {
            let frame = conn.next().await.expect("connection open");
            if frame["eventType"] == "AGENT_MESSAGE"
                && frame["payload"]["text"]
                    .as_str()
                    .is_some_and(|t| t.contains("tick"))
            {
                break;
            }
        }
        // The agent writes in the sandbox's copy of the workspace; the host's clone, which it
        // never reaches, stays as the host made it.
        let host_workspace = host.paths.workspace(spec.run_id);
        let sandbox_workspace = PathBuf::from(Layout::local(&host_workspace).workspace);
        assert_eq!(
            std::fs::read_to_string(sandbox_workspace.join("repo/slow.txt")).unwrap(),
            "work in progress\n"
        );
        assert!(!host_workspace.join("repo/slow.txt").exists());
        // The cancel stops the agent; the sandbox makes the WIP commit, and its bundle reaches
        // the branch.
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "CANCELLED", "{}", t.text());
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        assert_eq!(
            repo.subjects("agent/TEST-1")[0],
            format!("WIP: uncommitted changes from run {}", spec.run_id)
        );
        assert!(
            t.events_of("GIT")
                .iter()
                .any(|e| e["payload"]["action"] == "committed"),
            "{}",
            t.text()
        );
        // The stand-in for the sandbox goes with the run.
        assert!(!sandbox_workspace.exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// Where the restart test's host A keeps its directories, and its server.
const RESTART_DIR: &str = "TINFIRM_TEST_RESTART_DIR";
const RESTART_URL: &str = "TINFIRM_TEST_RESTART_URL";

/// Host A of [`a_host_that_restarted_takes_its_run_back_and_saves_the_work`], in a process of its
/// own that the test kills as a crash would (SIGKILL): run only by that test.
#[test]
#[ignore = "host A of the restart test, which starts it"]
fn restart_test_host_a() {
    let (Ok(dir), Ok(url)) = (std::env::var(RESTART_DIR), std::env::var(RESTART_URL)) else {
        return;
    };
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let _host = start_host_at(std::path::Path::new(&dir), &url, HostSetup::bridged()).await;
            std::future::pending::<()>().await;
        });
}

/// Host A, in a process of its own, runs the `slow` scenario until the agent has written its
/// work, and is killed (SIGKILL). The directories, the run, the record's path and the last
/// event number A sent.
async fn a_host_killed_mid_run(
    server: &mut FakeServer,
    repo: &TestRepo,
) -> (tempfile::TempDir, AssignSpec<'static>, PathBuf, u64) {
    let dir = tempfile::tempdir().unwrap();
    let mut host_a = std::process::Command::new(std::env::current_exe().unwrap())
        .args(["restart_test_host_a", "--exact", "--ignored", "--nocapture"])
        .env(RESTART_DIR, dir.path())
        .env(RESTART_URL, &server.url)
        .stdout(std::process::Stdio::null())
        .spawn()
        .unwrap();
    let mut conn = server.accept().await;
    conn.handshake(1).await;
    let spec = AssignSpec::new("slow", Some(&repo.url));
    conn.send(spec.to_json());
    // The agent has written its work and goes on: the run's record is there.
    conn.wait_running(spec.run_id).await;
    let record = dir.path().join(format!("data/runs/{}.json", spec.run_id));
    assert!(record.exists(), "no record of the run");
    let before = conn.until_seq(spec.run_id, 3).await;
    host_a.kill().unwrap();
    host_a.wait().unwrap();
    (dir, spec, record, before)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_that_restarted_takes_its_run_back_and_saves_the_work() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, record, before) = a_host_killed_mid_run(&mut server, &repo).await;

        // Host B, on A's directories: it takes the run back before its hello.
        let host = start_host_at(dir.path(), &server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(1).await;
        let active = hello["activeRuns"].as_array().expect("activeRuns");
        assert_eq!(active.len(), 1, "{hello}");
        assert_eq!(active[0]["runId"], spec.run_id.to_string());
        assert_eq!(active[0]["epoch"], 1);
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(message.contains("took the run back"), "{message}");
        assert!(
            message.contains("its work so far was pushed"),
            "{message}\n{}",
            t.text()
        );
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        // Host B numbers its events after every one of A's.
        let seqs: Vec<u64> = t
            .events()
            .iter()
            .filter_map(|e| e["seq"].as_u64())
            .collect();
        assert!(!seqs.is_empty());
        assert!(
            seqs.iter().all(|seq| *seq > before),
            "{seqs:?} after {before}"
        );
        assert!(
            t.text()
                .contains("restarted during the run and takes it back"),
            "{}",
            t.text()
        );
        // Nothing is left to take back.
        assert!(!record.exists());
        let listener = host.host.bridge().await.unwrap().expect("a listener");
        assert_eq!(listener.server.run_count(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_taken_back_that_the_server_cancels_still_saves_its_work() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, record, _) = a_host_killed_mid_run(&mut server, &repo).await;
        let host = start_host_at(dir.path(), &server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        // At once, as a server that no longer keeps the run would: the work is still taken.
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "CANCELLED", "{}", t.text());
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        assert!(!record.exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}
