//! End-to-end runs over the sandbox bridge (ADR-0095, `run --sandbox-bridge`) with the local
//! test executor: the host starts this crate's binary as `bridge`, with only the bridge's
//! settings in its environment; the bridge connects back over pinned TLS, listens for the
//! run's tunnels on 127.0.0.1 and starts the fake agent with the run's environment. The host's
//! listeners are on 127.0.0.2 here, where the agent reaches them only through the tunnels. The
//! Docker side (the mount, the container's settings) is covered by the unit tests of
//! `sandbox.rs`.

mod common;

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
        // The sandbox's bridge was its front's: the host's shared listener never started, and
        // no identity of it is kept.
        assert!(host.host.started_bridge().await.is_none());
        // The run's record went with its sandbox.
        let runs = host.paths.data_dir.join("runs");
        assert!(!runs.join(format!("{}.json", spec.run_id)).exists());
        assert!(!runs.join("bridge.json").exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_server_that_refuses_the_hellos_features_stops_the_host() {
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
        // A server whose licence does not take a feature of the hello: an error before the
        // welcome, then 1008. A Community server before ADR-0114 refuses `sandbox.bridge` so.
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
        // Its bridge was its front's, gone with the run; the shared listener never started.
        assert!(host.host.started_bridge().await.is_none());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

// ----- the host's listeners, through the bridge's tunnels --------------------------------

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
        // The host binds no listener of the proxies for the run, and the shared git listener
        // never started: the sandbox reaches them through its tunnels to its front only.
        assert!(host.host.started_git_proxy().await.is_none());
        // The run is over: its tunnel is gone with its bridge.
        let port = text
            .split("model-port=")
            .nth(1)
            .and_then(|rest| rest.split_whitespace().next())
            .and_then(|port| port.parse::<u16>().ok())
            .expect("a port");
        assert_eq!(
            port,
            tinfirm_runner_host::bridge::tunnel_port(
                tinfirm_runner_host::front::wire::Service::Model
            )
        );
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
        // With the bridge, a front process serves the sandbox's proxy HTTP (path C, part 2).
        assert!(
            host.host.sandbox_front().await.unwrap().is_some(),
            "no front serves the sandboxes' proxies"
        );
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
/// Set: host A speaks protocol 2.
const RESTART_V2: &str = "TINFIRM_TEST_RESTART_V2";
/// Set, in host A: the program its readers run ([`held_reader`]).
const RESTART_READER: &str = "TINFIRM_TEST_RESTART_READER";

/// The setup of both hosts of a restart test.
fn restart_setup(v2: bool) -> HostSetup {
    HostSetup {
        protocol_version: if v2 { 2 } else { 1 },
        ..HostSetup::bridged()
    }
}

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
            let v2 = std::env::var_os(RESTART_V2).is_some();
            let mut setup = restart_setup(v2);
            if let Some(reader) = std::env::var_os(RESTART_READER).map(PathBuf::from) {
                setup.options = Some(Box::new(move |opts| {
                    let bridge = opts.bridge.as_mut().expect("a bridged host");
                    bridge.reader = tinfirm_runner_host::reader::Reader::new(reader)
                        .allowing_unconfined(bridge.reader.allows_unconfined());
                }));
            }
            let _host = start_host_at(std::path::Path::new(&dir), &url, setup).await;
            std::future::pending::<()>().await;
        });
}

/// Whether process `pid` has ended: gone, or a zombie that nobody reaps.
fn ended(pid: u32) -> bool {
    let Ok(stat) = std::fs::read_to_string(format!("/proc/{pid}/stat")) else {
        return true;
    };
    stat.rfind(')')
        .and_then(|at| stat[at + 1..].split_whitespace().next())
        .is_none_or(|state| state == "Z" || state == "X")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_process_that_its_test_did_not_crash_leaves_nothing_behind() {
    with_timeout(60, async {
        let dir = tempfile::tempdir().unwrap();
        // A "host" that starts a process of its own, as host A starts a local sandbox's bridge,
        // and then stays, as host A does when its test fails before it kills it.
        let started = |name: &str| {
            let pids = dir.path().join(name);
            let mut command = std::process::Command::new("sh");
            command
                .arg("-c")
                .arg(format!(
                    "sleep 600 & echo $$ $! > {}.tmp && mv {0}.tmp {0}; exec sleep 600",
                    pids.display()
                ))
                .stdout(std::process::Stdio::null());
            (HostProcess::spawn(command), pids)
        };
        let pids_of = |pids: &std::path::Path| loop {
            if let Ok(text) = std::fs::read_to_string(pids) {
                let pids: Vec<u32> = text
                    .split_whitespace()
                    .map(|pid| pid.parse().unwrap())
                    .collect();
                break (pids[0], pids[1]);
            }
            std::thread::sleep(Duration::from_millis(20));
        };
        let (host, pids) = started("dropped");
        let (leader, child) = pids_of(&pids);
        assert_eq!(leader, host.id());
        assert!(!ended(leader) && !ended(child));
        // A test that panics drops it while it unwinds, as one that ends does.
        let unwound = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
            let _host = host;
            panic!("the test failed");
        }));
        assert!(unwound.is_err());
        while !(ended(leader) && ended(child)) {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        // After a crash, what it started lives on for the next host, as after a real crash.
        let (mut host, pids) = started("crashed");
        let (leader, child) = pids_of(&pids);
        host.crash();
        drop(host);
        assert!(ended(leader));
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert!(!ended(child), "a crash ended what the host started");
        std::process::Command::new("kill")
            .args(["-KILL", &child.to_string()])
            .status()
            .unwrap();
    })
    .await;
}

/// A host on the directories under `dir`, in a process of its own ([`restart_test_host_a`]),
/// which the test kills as a crash would.
fn host_process(dir: &std::path::Path, url: &str, v2: bool) -> HostProcess {
    host_process_reading(dir, url, v2, None)
}

/// [`host_process`]; with `reader`, the program that host A's readers run.
fn host_process_reading(
    dir: &std::path::Path,
    url: &str,
    v2: bool,
    reader: Option<&std::path::Path>,
) -> HostProcess {
    let mut command = std::process::Command::new(std::env::current_exe().unwrap());
    command
        .args(["restart_test_host_a", "--exact", "--ignored", "--nocapture"])
        .env(RESTART_DIR, dir)
        .env(RESTART_URL, url)
        .stdout(std::process::Stdio::null());
    if v2 {
        command.env(RESTART_V2, "1");
    }
    if let Some(reader) = reader {
        command.env(RESTART_READER, reader);
    }
    HostProcess::spawn(command)
}

/// Host A, in a process of its own, runs the `slow` scenario until the agent has written its
/// work, and is killed (SIGKILL). The directories, the run, the record's path and the last
/// event number A sent.
async fn a_host_killed_mid_run(
    server: &mut FakeServer,
    repo: &TestRepo,
) -> (tempfile::TempDir, AssignSpec<'static>, PathBuf, u64) {
    a_host_killed_mid_run_after(server, repo, false, None).await
}

/// [`a_host_killed_mid_run`], with host A of protocol 2 when `v2`, which gets a `run.git.revoke`
/// with the message `revoke` (ADR-0116) while its run's agent works, and applies it, before it
/// is killed.
async fn a_host_killed_mid_run_after(
    server: &mut FakeServer,
    repo: &TestRepo,
    v2: bool,
    revoke: Option<&str>,
) -> (tempfile::TempDir, AssignSpec<'static>, PathBuf, u64) {
    a_host_killed_mid_run_with(server, repo, v2, revoke, |_| {}).await
}

/// [`a_host_killed_mid_run_after`], with the run's assignment changed by `change` first.
async fn a_host_killed_mid_run_with(
    server: &mut FakeServer,
    repo: &TestRepo,
    v2: bool,
    revoke: Option<&str>,
    change: impl FnOnce(&mut AssignSpec<'static>),
) -> (tempfile::TempDir, AssignSpec<'static>, PathBuf, u64) {
    a_host_killed_when(server, repo, v2, revoke, change, None).await
}

/// [`a_host_killed_mid_run_with`], killed only once the agent has said `ready`, when given.
async fn a_host_killed_when(
    server: &mut FakeServer,
    repo: &TestRepo,
    v2: bool,
    revoke: Option<&str>,
    change: impl FnOnce(&mut AssignSpec<'static>),
    ready: Option<&str>,
) -> (tempfile::TempDir, AssignSpec<'static>, PathBuf, u64) {
    let dir = tempfile::tempdir().unwrap();
    let mut host_a = host_process(dir.path(), &server.url, v2);
    let mut conn = server.accept().await;
    conn.handshake(1).await;
    let mut spec = AssignSpec::new("slow", Some(&repo.url));
    if v2 {
        spec.protocol_version = 2;
    }
    change(&mut spec);
    conn.send(spec.to_json());
    // The agent has written its work and goes on: the run's record is there.
    match ready {
        // Its first message may hold `ready` already.
        Some(ready) => conn.until_agent_says(spec.run_id, ready).await,
        None => conn.wait_running(spec.run_id).await,
    }
    let record = dir.path().join(format!("data/runs/{}.json", spec.run_id));
    assert!(record.exists(), "no record of the run");
    // The sandbox's bridge is its front's: its record keeps the bridge's own identity and
    // port, and the shared listener's file is not written.
    let kept: serde_json::Value = serde_json::from_slice(&std::fs::read(&record).unwrap()).unwrap();
    assert!(kept["bridge"]["port"].as_u64().is_some(), "{kept}");
    assert!(!dir.path().join("data/runs/bridge.json").exists());
    if let Some(message) = revoke {
        conn.send(git_revoke(
            spec.run_id,
            1,
            &[spec.workspaces[0].component_id],
            message,
        ));
        let id = spec.run_id.to_string();
        conn.take_where("the revocation's warning", |f| {
            f["runId"] == id && f["eventType"] == "LOG" && f["payload"]["message"] == message
        })
        .await;
    }
    let before = if ready.is_some() {
        // An agent that waits says nothing more: the events so far.
        let id = spec.run_id.to_string();
        conn.frames
            .iter()
            .filter(|f| f["runId"] == id)
            .filter_map(|f| f["seq"].as_u64())
            .max()
            .unwrap_or(0)
    } else {
        conn.until_seq(spec.run_id, 3).await
    };
    host_a.crash();
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
        // Nothing is left to take back, and the run came back through a new front: the shared
        // listener never started.
        assert!(!record.exists());
        assert!(host.host.started_bridge().await.is_none());
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

/// `welcome.features` of a server of ADR-0116 and later.
const SERVER_FEATURES: &[&str] = &["run.steer", "run.git.revoke", "run.git.refresh"];

/// The server's message of the revocation tests (ADR-0116).
const REVOKED: &str =
    "Tinfirm revoked this run's access to acme/origin: the account was disconnected.";

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_token_revoked_before_a_restart_does_not_come_back_with_the_run() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, record, _) =
            a_host_killed_mid_run_after(&mut server, &repo, true, Some(REVOKED)).await;
        // Host A wrote its record again: no token, the repository named as revoked.
        let kept = std::fs::read_to_string(&record).unwrap();
        assert!(!kept.contains(TEST_GIT_TOKEN), "{kept}");
        let kept: serde_json::Value = serde_json::from_str(&kept).unwrap();
        assert_eq!(kept["version"], 1);
        assert!(
            kept["assign"]["workspaces"][0].get("git").is_none(),
            "{kept:#}"
        );
        assert_eq!(
            kept["revoked"],
            json!([{
                "componentId": spec.workspaces[0].component_id,
                "reason": "GRANT_REMOVED",
                "message": REVOKED
            }])
        );

        // Host B takes the run back from that record; the server does not even send the
        // revocation again.
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        assert_eq!(finished["git"][0]["pushed"], false, "{}", t.text());
        assert!(
            t.text().contains(&format!("not pushed: {REVOKED}")),
            "{}",
            t.text()
        );
        assert!(repo.branch_head("agent/TEST-1").is_none());
        assert!(!record.exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_revocation_sent_again_at_the_hello_comes_before_the_push_of_a_run_taken_back() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        // Host A never got the revocation: it was disconnected when the server sent it.
        let (dir, spec, record, _) =
            a_host_killed_mid_run_after(&mut server, &repo, true, None).await;
        assert!(
            std::fs::read_to_string(&record)
                .unwrap()
                .contains(TEST_GIT_TOKEN)
        );
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake_listing(1, SERVER_FEATURES).await;
        assert_eq!(hello["activeRuns"][0]["runId"], spec.run_id.to_string());
        // As the server does at every hello that lists the run: the pending revocation, then
        // `welcome.done`, which the push waits for.
        conn.send(git_revoke(
            spec.run_id,
            1,
            &[spec.workspaces[0].component_id],
            REVOKED,
        ));
        conn.send(json!({"type": "welcome.done"}));
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["git"][0]["pushed"], false, "{}", t.text());
        assert!(
            t.text().contains(&format!("not pushed: {REVOKED}")),
            "{}",
            t.text()
        );
        assert!(repo.branch_head("agent/TEST-1").is_none());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_taken_back_pushes_only_once_the_server_says_welcome_done() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _, _) = a_host_killed_mid_run_after(&mut server, &repo, true, None).await;
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake_listing(1, SERVER_FEATURES).await;
        // The sandbox came back and its agent stopped: the run is ready to push, and waits.
        let id = spec.run_id.to_string();
        conn.take_where("the agent stopped", |f| {
            f["runId"] == id
                && f["eventType"] == "LOG"
                && f["payload"]["message"]
                    .as_str()
                    .is_some_and(|m| m.contains("was stopped") || m.contains("had stopped"))
        })
        .await;
        tokio::time::sleep(Duration::from_secs(3)).await;
        assert!(
            repo.branch_head("agent/TEST-1").is_none(),
            "pushed before welcome.done"
        );
        assert!(
            !conn.frames.iter().any(|f| f["type"] == "run.finished"),
            "finished before welcome.done"
        );
        conn.send(json!({"type": "welcome.done"}));
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert!(!t.text().contains("welcome.done"), "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// Waits until the take-back record at `record` says that the run's work came back from its
/// sandbox, which then has stopped: the run waits for the server to push.
async fn until_collected(record: &std::path::Path) -> serde_json::Value {
    let started = std::time::Instant::now();
    loop {
        if let Ok(text) = std::fs::read_to_string(record)
            && let Ok(kept) = serde_json::from_str::<serde_json::Value>(&text)
            && kept["collected"] == true
        {
            return kept;
        }
        assert!(
            started.elapsed() < Duration::from_secs(90),
            "the run's work never came back from its sandbox"
        );
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_taken_back_without_a_connection_waits_past_the_old_cap_and_pushes_after_welcome_done()
 {
    with_timeout(330, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, record, _) =
            a_host_killed_mid_run_after(&mut server, &repo, true, None).await;
        // Host B gets no connection: the server drops every one before the handshake.
        server.set_refusing(true);
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let kept = until_collected(&record).await;
        assert_eq!(kept["repos"][0]["collected"]["result"], "ok", "{kept:#}");
        assert!(!host.host.is_connected());
        // Longer than the 2 minutes after which the host used to push with what its record
        // held: nothing is pushed, and the run and its record stay.
        tokio::time::sleep(Duration::from_secs(125)).await;
        assert!(
            repo.branch_head("agent/TEST-1").is_none(),
            "pushed without the server's answer"
        );
        assert!(record.exists(), "the record went while the run waits");
        assert!(host.paths.workspace(spec.run_id).is_dir());
        assert_eq!(host.host.active_run_count(), 1);

        // The connection, `welcome`, then `welcome.done`: the push.
        server.set_refusing(false);
        let mut conn = server.accept().await;
        let hello = conn.handshake_listing(1, SERVER_FEATURES).await;
        assert_eq!(hello["activeRuns"][0]["runId"], spec.run_id.to_string());
        tokio::time::sleep(Duration::from_secs(2)).await;
        assert!(
            repo.branch_head("agent/TEST-1").is_none(),
            "pushed at welcome, before welcome.done"
        );
        conn.send(json!({"type": "welcome.done"}));
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        // The wait is said once in the run's events, and no push happened without the answer.
        let said: Vec<_> = t
            .events_of("LOG")
            .into_iter()
            .filter(|e| {
                e["payload"]["message"]
                    .as_str()
                    .is_some_and(|m| m.contains("waits on the runner host"))
            })
            .collect();
        assert_eq!(said.len(), 1, "{}", t.text());
        assert!(!t.text().contains("pushed without knowing"), "{}", t.text());
        assert!(!record.exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_that_restarts_again_while_a_run_taken_back_waits_keeps_the_run() {
    with_timeout(240, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, record, _) =
            a_host_killed_mid_run_after(&mut server, &repo, true, None).await;
        // Host B, in a process of its own, takes the run back without a connection, collects
        // its work, waits, and is killed.
        server.set_refusing(true);
        let mut host_b = host_process(dir.path(), &server.url, true);
        until_collected(&record).await;
        host_b.crash();
        assert!(record.exists(), "a second restart lost the run");
        assert!(repo.branch_head("agent/TEST-1").is_none());

        // Host C takes the run back from its own disk: no sandbox, no bridge.
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        server.set_refusing(false);
        let mut conn = server.accept().await;
        let hello = conn.handshake_listing(1, SERVER_FEATURES).await;
        assert_eq!(hello["activeRuns"][0]["runId"], spec.run_id.to_string());
        conn.send(json!({"type": "welcome.done"}));
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert!(
            t.text().contains("restarted again before it pushed"),
            "{}",
            t.text()
        );
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

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_that_shuts_down_while_a_run_taken_back_waits_leaves_the_run_for_its_next_start() {
    with_timeout(240, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, record, _) =
            a_host_killed_mid_run_after(&mut server, &repo, true, None).await;
        server.set_refusing(true);
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        until_collected(&record).await;
        // SIGTERM: the run that waits neither pushes nor reports, and is not held for the
        // shutdown's 2 minutes of grace.
        let started = std::time::Instant::now();
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
        assert!(
            started.elapsed() < Duration::from_secs(60),
            "{:?}",
            started.elapsed()
        );
        assert!(record.exists());
        assert!(host.paths.workspace(spec.run_id).is_dir());
        assert!(repo.branch_head("agent/TEST-1").is_none());

        let again = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        server.set_refusing(false);
        let mut conn = server.accept().await;
        conn.handshake_listing(1, SERVER_FEATURES).await;
        conn.send(json!({"type": "welcome.done"}));
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["git"][0]["pushed"], true, "{}", t.text());
        assert!(!record.exists());
        again.shutdown.cancel();
        again.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_taken_back_whose_record_cannot_be_marked_keeps_its_sandbox_for_the_next_start() {
    with_timeout(240, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, record, _) =
            a_host_killed_mid_run_after(&mut server, &repo, true, None).await;
        // The record cannot be written: its temporary file's place is taken.
        let blocker = record.with_extension("json.tmp");
        std::fs::create_dir(&blocker).unwrap();
        // Host B, in a process of its own, takes the run back and collects the work.
        let mut host_b = host_process(dir.path(), &server.url, true);
        let mut conn = server.accept().await;
        conn.handshake_listing(1, SERVER_FEATURES).await;
        let id = spec.run_id.to_string();
        conn.take_where("the warning that the sandbox is kept", |f| {
            f["runId"] == id
                && f["eventType"] == "LOG"
                && f["payload"]["message"]
                    .as_str()
                    .is_some_and(|m| m.contains("keeps the sandbox until the run ends"))
        })
        .await;
        // The disk still says that the work is in the sandbox, and the sandbox is there. Host B
        // dies before its push (no welcome.done).
        let kept: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&record).unwrap()).unwrap();
        assert!(kept.get("collected").is_none(), "{kept:#}");
        host_b.crash();
        assert!(repo.branch_head("agent/TEST-1").is_none());

        // The next start takes the work from the sandbox again, and pushes it.
        std::fs::remove_dir(&blocker).unwrap();
        let again = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake_listing(1, SERVER_FEATURES).await;
        conn.send(json!({"type": "welcome.done"}));
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        assert!(
            t.text()
                .contains("restarted during the run and takes it back"),
            "{}",
            t.text()
        );
        assert!(!record.exists());
        again.shutdown.cancel();
        again.handle.await.unwrap().unwrap();
    })
    .await;
}

/// A run whose record, when its host is killed, no longer holds two of the tokens it had: the
/// server revoked `shop`'s (`TEST_GIT_TOKEN`), and `docs`'s App token (`TEST_APP_TOKEN`) was
/// renewed. Before the kill the agent's work in `docs` gains a file with `leaked`, one of the
/// two. Host B takes the run back: the scan before the push finds it by the record's hashes.
async fn a_token_the_record_no_longer_holds_is_caught_after_a_restart(leaked: &str) {
    let (shop, docs) = (TestRepo::new(), TestRepo::new());
    let mut server = FakeServer::start().await;
    let dir = tempfile::tempdir().unwrap();
    let mut host_a = host_process(dir.path(), &server.url, true);
    let mut conn = server.accept().await;
    conn.handshake(1).await;
    let mut spec = AssignSpec::with_workspaces(
        "rebase",
        vec![
            WorkspaceEntry::new("shop", "Shop", &shop.url),
            WorkspaceEntry::new("docs", "Docs", &docs.url).expiring(
                TEST_APP_TOKEN,
                chrono::Utc::now() + chrono::TimeDelta::minutes(5),
                TEST_MIRROR_KEY,
            ),
        ],
    );
    spec.protocol_version = 2;
    spec.prompt_extra = " [commit:shop] [commit:docs] [wait]".into();
    let (shop_id, docs_id) = (
        spec.workspaces[0].component_id,
        spec.workspaces[1].component_id,
    );
    conn.send(spec.to_json());
    let id = spec.run_id.to_string();
    // The App token is due: host A renews it.
    let request = conn
        .take_where("a GIT_TOKEN request", |f| {
            f["type"] == "run.request" && f["runId"] == id && f["kind"] == "GIT_TOKEN"
        })
        .await;
    conn.send(git_tokens_ack(
        &request,
        &[(
            docs_id,
            TEST_RENEWED_TOKEN,
            chrono::Utc::now() + chrono::TimeDelta::minutes(60),
        )],
        &[],
    ));
    conn.until_agent_says(spec.run_id, "waiting for release")
        .await;
    conn.send(git_revoke(spec.run_id, 1, &[shop_id], REVOKED));
    conn.take_where("the revocation's warning", |f| {
        f["runId"] == id && f["eventType"] == "LOG" && f["payload"]["message"] == REVOKED
    })
    .await;
    // The record holds neither token, and the renewed one.
    let record = dir.path().join(format!("data/runs/{}.json", spec.run_id));
    let started = std::time::Instant::now();
    loop {
        let text = std::fs::read_to_string(&record).unwrap_or_default();
        if text.contains(TEST_RENEWED_TOKEN)
            && !text.contains(TEST_APP_TOKEN)
            && !text.contains(TEST_GIT_TOKEN)
        {
            assert!(text.contains("\"redaction\""), "{text}");
            break;
        }
        assert!(started.elapsed() < Duration::from_secs(30), "{text}");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    host_a.crash();
    // The agent's work in `docs` holds the token, uncommitted: the sandbox commits it.
    let paths =
        tinfirm_runner_host::paths::Paths::new(dir.path().join("config"), dir.path().join("data"));
    let workspace = paths.workspace(spec.run_id);
    let sandbox =
        PathBuf::from(tinfirm_runner_host::sandbox_files::Layout::local(&workspace).workspace);
    std::fs::write(
        sandbox.join("docs").join("notes.txt"),
        format!("the token: {leaked}\n"),
    )
    .unwrap();

    let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
    let mut conn = server.accept().await;
    conn.handshake_listing(1, SERVER_FEATURES).await;
    conn.send(json!({"type": "welcome.done"}));
    // The renewal of every token that expires, after welcome.done.
    let request = conn
        .take_where("a GIT_TOKEN request", |f| {
            f["type"] == "run.request" && f["runId"] == id && f["kind"] == "GIT_TOKEN"
        })
        .await;
    conn.send(git_tokens_ack(
        &request,
        &[(
            docs_id,
            TEST_RENEWED_TOKEN,
            chrono::Utc::now() + chrono::TimeDelta::minutes(60),
        )],
        &[],
    ));
    let t = conn.until_finished(spec.run_id).await;
    t.assert_no_secrets();
    let finished = t.finished();
    assert_eq!(finished["failureClass"], "CREDENTIAL_LEAK", "{}", t.text());
    assert!(
        finished["failureMessage"]
            .as_str()
            .unwrap()
            .contains("a commit contains a credential value"),
        "{finished:#}"
    );
    let results = finished["git"].as_array().unwrap();
    assert!(results.iter().all(|r| r["pushed"] == false), "{finished:#}");
    assert!(docs.branch_head("agent/TEST-1").is_none());
    assert!(shop.branch_head("agent/TEST-1").is_none());
    host.shutdown.cancel();
    host.handle.await.unwrap().unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_revoked_token_in_a_commit_of_another_repository_is_caught_after_a_restart() {
    with_timeout(
        180,
        a_token_the_record_no_longer_holds_is_caught_after_a_restart(TEST_GIT_TOKEN),
    )
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_apps_earlier_token_in_a_commit_is_caught_after_a_restart() {
    with_timeout(
        180,
        a_token_the_record_no_longer_holds_is_caught_after_a_restart(TEST_APP_TOKEN),
    )
    .await;
}

/// Host A killed mid-run; its record's `redaction` changed by `change` before host B takes the
/// run back. The transcript of host B.
async fn a_run_taken_back_from_a_record_whose_hashes_are(
    change: impl FnOnce(&mut serde_json::Value),
) -> (Transcript, TestRepo) {
    let repo = TestRepo::new();
    let mut server = FakeServer::start().await;
    let (dir, spec, record, _) = a_host_killed_mid_run_after(&mut server, &repo, true, None).await;
    let mut kept: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&record).unwrap()).unwrap();
    assert!(kept["redaction"]["salt"].is_string(), "{kept:#}");
    change(&mut kept);
    std::fs::write(&record, serde_json::to_vec(&kept).unwrap()).unwrap();
    let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
    let mut conn = server.accept().await;
    conn.handshake_listing(1, SERVER_FEATURES).await;
    conn.send(json!({"type": "welcome.done"}));
    let t = conn.until_finished(spec.run_id).await;
    t.assert_no_secrets();
    host.shutdown.cancel();
    host.handle.await.unwrap().unwrap();
    (t, repo)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_record_whose_hashes_cannot_be_used_pushes_nothing_of_the_run() {
    with_timeout(180, async {
        let (t, repo) = a_run_taken_back_from_a_record_whose_hashes_are(|kept| {
            kept["redaction"]["forms"][0]["len"] = json!(u32::MAX);
        })
        .await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(
            message.contains("its record of the run is damaged"),
            "{message}"
        );
        assert!(message.contains("the work was not pushed"), "{message}");
        assert_eq!(finished["git"][0]["pushed"], false, "{}", t.text());
        assert!(repo.branch_head("agent/TEST-1").is_none());
        assert!(
            t.text()
                .contains("nothing of the run's work leaves the host"),
            "{}",
            t.text()
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_record_without_hashes_is_taken_back_as_before() {
    with_timeout(180, async {
        let (t, repo) = a_run_taken_back_from_a_record_whose_hashes_are(|kept| {
            kept.as_object_mut().unwrap().remove("redaction");
        })
        .await;
        assert_eq!(t.finished()["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_revocation_applied_before_a_restart_is_confirmed_again_after_it() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        // Host A applies the revocation and is killed, its confirmation written or not.
        let (dir, spec, record, _) =
            a_host_killed_mid_run_after(&mut server, &repo, true, Some(REVOKED)).await;
        assert!(
            !std::fs::read_to_string(&record)
                .unwrap()
                .contains(TEST_GIT_TOKEN)
        );
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake_listing(1, SERVER_FEATURES).await;
        // The server has no confirmation it trusts: it sends the frame again at the hello.
        let component = spec.workspaces[0].component_id;
        conn.send(git_revoke(spec.run_id, 1, &[component], REVOKED));
        let id = spec.run_id.to_string();
        let confirmed = conn
            .take_where("run.git.revoked", |f| {
                f["type"] == "run.git.revoked" && f["runId"] == id
            })
            .await;
        assert_eq!(confirmed["epoch"], 1);
        assert_eq!(confirmed["componentIds"], json!([component]));
        conn.send(json!({"type": "welcome.done"}));
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["git"][0]["pushed"], false, "{}", t.text());
        assert!(repo.branch_head("agent/TEST-1").is_none());
        // The revocation came back with the record, not with this frame: no second warning.
        assert!(
            t.events_of("LOG")
                .iter()
                .all(|e| e["payload"]["message"] != REVOKED),
            "{}",
            t.text()
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_taken_back_after_its_token_expired_renews_it_before_the_push() {
    use common::git_http::GitHttpServer;
    with_timeout(180, async {
        let repo = TestRepo::new();
        let git_server =
            GitHttpServer::start(repo.dir.path(), Some(basic_of(TEST_APP_TOKEN))).await;
        let url = format!("{}/origin.git", git_server.url);
        let mut server = FakeServer::start().await;
        // Host A's token expires in seconds; A asks to renew it, and gets no answer.
        let expires_at = chrono::Utc::now() + chrono::TimeDelta::seconds(4);
        let (dir, spec, record, _) =
            a_host_killed_mid_run_with(&mut server, &repo, true, None, |spec| {
                spec.workspaces = vec![WorkspaceEntry::new("repo", "Repo", &url).expiring(
                    TEST_APP_TOKEN,
                    expires_at,
                    TEST_MIRROR_KEY,
                )];
            })
            .await;
        let kept: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&record).unwrap()).unwrap();
        let git = &kept["assign"]["workspaces"][0]["git"];
        assert_eq!(git["token"], TEST_APP_TOKEN);
        assert_eq!(git["mirrorKey"], TEST_MIRROR_KEY);
        assert!(git["expiresAt"].is_string(), "{kept:#}");
        // The token expires while no host runs.
        let wait = (expires_at - chrono::Utc::now())
            .to_std()
            .unwrap_or_default();
        tokio::time::sleep(wait + Duration::from_millis(200)).await;
        git_server.set_authorization(Some(basic_of(TEST_RENEWED_TOKEN)));

        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake_listing(1, SERVER_FEATURES).await;
        conn.send(json!({"type": "welcome.done"}));
        // After welcome.done the host renews every token of the run that expires.
        let id = spec.run_id.to_string();
        let request = conn
            .take_where("a GIT_TOKEN request", |f| {
                f["type"] == "run.request" && f["runId"] == id && f["kind"] == "GIT_TOKEN"
            })
            .await;
        let component = spec.workspaces[0].component_id;
        assert_eq!(request["payload"]["componentIds"], json!([component]));
        conn.send(git_tokens_ack(
            &request,
            &[(
                component,
                TEST_RENEWED_TOKEN,
                chrono::Utc::now() + chrono::TimeDelta::minutes(60),
            )],
            &[],
        ));
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        let pushes: Vec<_> = git_server
            .seen()
            .into_iter()
            .filter(|r| r.path.ends_with("git-receive-pack"))
            .collect();
        assert!(!pushes.is_empty());
        assert!(
            pushes
                .iter()
                .all(|r| r.authorization.as_deref() == Some(basic_of(TEST_RENEWED_TOKEN).as_str()))
        );
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_taken_back_by_an_older_servers_host_pushes_without_waiting() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _, _) = a_host_killed_mid_run_after(&mut server, &repo, true, None).await;
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        // A server of before ADR-0116: no `welcome.features`, and never a `welcome.done`.
        conn.handshake(1).await;
        let started = std::time::Instant::now();
        let t = conn.until_finished(spec.run_id).await;
        assert!(started.elapsed() < Duration::from_secs(60), "it waited");
        let finished = t.finished();
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert!(!t.text().contains("welcome.done"), "{}", t.text());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_abort_while_a_run_taken_back_waits_for_welcome_done_pushes_and_reports_nothing() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _, _) = a_host_killed_mid_run_after(&mut server, &repo, true, None).await;
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake_listing(1, SERVER_FEATURES).await;
        let id = spec.run_id.to_string();
        conn.take_where("the agent stopped", |f| {
            f["runId"] == id
                && f["eventType"] == "LOG"
                && f["payload"]["message"]
                    .as_str()
                    .is_some_and(|m| m.contains("was stopped") || m.contains("had stopped"))
        })
        .await;
        // The run waits for welcome.done; the server calls its execution stale meanwhile.
        conn.send(json!({
            "type": "error",
            "code": "STALE_EPOCH",
            "message": "epoch 1 is stale",
            "runId": spec.run_id,
            "epoch": 1
        }));
        tokio::time::sleep(Duration::from_millis(500)).await;
        conn.send(json!({"type": "welcome.done"}));
        let _ = tokio::time::timeout(Duration::from_secs(4), async {
            while conn.next().await.is_some() {}
        })
        .await;
        assert!(
            !conn
                .frames
                .iter()
                .any(|f| f["type"] == "run.finished" && f["runId"] == id),
            "an aborted run reports nothing"
        );
        assert!(
            repo.branch_head("agent/TEST-1").is_none(),
            "an aborted run pushes nothing"
        );
        assert!(!host.paths.workspace(spec.run_id).exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_taken_back_with_a_revocation_and_a_cancel_in_the_hellos_answer_pushes_the_others() {
    with_timeout(180, async {
        let shop = TestRepo::new();
        let docs = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (shop_url, docs_url) = (shop.url.clone(), docs.url.clone());
        let (dir, spec, _, _) = a_host_killed_when(
            &mut server,
            &shop,
            true,
            None,
            move |spec| {
                spec.scenario = "rebase";
                spec.prompt_extra = " [commit:shop] [commit:docs] [wait]".into();
                spec.workspaces = vec![
                    WorkspaceEntry::new("shop", "Shop", &shop_url),
                    WorkspaceEntry::new("docs", "Docs", &docs_url),
                ];
            },
            Some("waiting for release"),
        )
        .await;
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake_listing(1, SERVER_FEATURES).await;
        // One hello's answer: the pending revocation, then the cancel, then welcome.done.
        conn.send(git_revoke(
            spec.run_id,
            1,
            &[spec.workspaces[0].component_id],
            REVOKED,
        ));
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        conn.send(json!({"type": "welcome.done"}));
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "CANCELLED", "{}", t.text());
        let results = finished["git"].as_array().unwrap();
        assert_eq!(results[0]["pushed"], false, "{}", t.text());
        assert_eq!(results[1]["pushed"], true, "{}", t.text());
        assert!(shop.branch_head("agent/TEST-1").is_none());
        assert_eq!(
            docs.file_at("agent/TEST-1", "docs.txt").as_deref(),
            Some("change in docs\n")
        );
        assert!(
            t.text().contains(&format!("not pushed: {REVOKED}")),
            "{}",
            t.text()
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

// ----- the sandbox's bridge in its front: its end, and the records of earlier hosts ----------

/// Host A, in a process of its own, runs the `slow` scenario until the agent has written its
/// work, and its sandbox's front is killed (SIGKILL). Host A, its connection and the run.
async fn a_front_killed_mid_run(
    server: &mut FakeServer,
    repo: &TestRepo,
) -> (
    tempfile::TempDir,
    HostProcess,
    Conn,
    AssignSpec<'static>,
    u32,
) {
    let dir = tempfile::tempdir().unwrap();
    a_front_killed_mid_run_in(dir, server, Some(repo), false).await
}

/// [`a_front_killed_mid_run`] in `dir`, with `repo` if any; with `held`, host A's readers are
/// [`held_reader`]'s.
async fn a_front_killed_mid_run_in(
    dir: tempfile::TempDir,
    server: &mut FakeServer,
    repo: Option<&TestRepo>,
    held: bool,
) -> (
    tempfile::TempDir,
    HostProcess,
    Conn,
    AssignSpec<'static>,
    u32,
) {
    let reader = held.then(|| held_reader(dir.path()));
    let host_a = host_process_reading(dir.path(), &server.url, false, reader.as_deref());
    let mut conn = server.accept().await;
    conn.handshake(1).await;
    let spec = AssignSpec::new("slow", repo.map(|repo| repo.url.as_str()));
    conn.send(spec.to_json());
    conn.wait_running(spec.run_id).await;
    conn.until_seq(spec.run_id, 3).await;
    if held {
        // A session home larger than a pipe holds: its archive is still on its way when the
        // test ends the new front.
        let home = sandbox_home(dir.path()).expect("the sandbox's home");
        let noise: Vec<u8> = (0..(1u32 << 20))
            .map(|i| (i.wrapping_mul(2_654_435_761) >> 13) as u8)
            .collect();
        std::fs::write(home.join("noise.bin"), noise).unwrap();
    }
    let fronts = fronts_of(host_a.id());
    assert_eq!(
        fronts.len(),
        1,
        "one front for the run's sandbox: {fronts:?}"
    );
    kill_front(fronts[0]);
    (dir, host_a, conn, spec, fronts[0])
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_front_that_ends_during_a_run_is_replaced_once_and_the_work_is_saved() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (_dir, mut host_a, mut conn, spec, _) =
            a_front_killed_mid_run(&mut server, &repo).await;
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(
            message.contains("front process ended during the run")
                && message.contains("its work so far was saved"),
            "{message}"
        );
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        // The host goes on: its next run is fine.
        let next = AssignSpec::new("turn-usage", None);
        conn.send(next.to_json());
        let t = conn.until_finished(next.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        host_a.crash();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_second_front_that_ends_too_is_not_replaced_and_the_run_says_its_work_is_lost() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (_dir, mut host_a, mut conn, spec, first) =
            a_front_killed_mid_run(&mut server, &repo).await;
        // The new front, as soon as it is there.
        let deadline = std::time::Instant::now() + Duration::from_secs(60);
        loop {
            if let Some(second) = fronts_of(host_a.id()).into_iter().find(|&pid| pid != first) {
                kill_front(second);
                break;
            }
            assert!(std::time::Instant::now() < deadline, "no new front came");
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(
            message.contains("front process ended during the run")
                && message.contains("the work of the run is lost"),
            "{message}"
        );
        // No third front was started for the run.
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert!(fronts_of(host_a.id()).is_empty());
        host_a.crash();
    })
    .await;
}

/// A reader program for host A that holds the first archive it unpacks, the session home's at
/// the run's end (the repositories come back before it, as bundles), until the test says so:
/// it makes `home-reached` in `dir`, and goes on once `go` is there.
fn held_reader(dir: &std::path::Path) -> PathBuf {
    use std::os::unix::fs::PermissionsExt as _;
    let script = dir.join("held-reader.sh");
    std::fs::write(
        &script,
        format!(
            "#!/bin/sh\ncase \"$2\" in\n*'\"kind\":\"unpack\"'*)\n  if mkdir '{reached}' 2>/dev/null; then\n    while [ ! -e '{go}' ]; do sleep 0.05; done\n  fi;;\nesac\nexec '{real}' \"$@\"\n",
            reached = dir.join("home-reached").display(),
            go = dir.join("go").display(),
            real = env!("CARGO_BIN_EXE_tinfirm-runner-host"),
        ),
    )
    .unwrap();
    std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
    script
}

/// The session home of the local sandbox of host A's run under `dir`.
fn sandbox_home(dir: &std::path::Path) -> Option<PathBuf> {
    let mut stack = vec![dir.to_path_buf()];
    while let Some(next) = stack.pop() {
        for entry in std::fs::read_dir(&next).ok()?.flatten() {
            let path = entry.path();
            if !entry.file_type().is_ok_and(|t| t.is_dir()) {
                continue;
            }
            if path.extension().is_some_and(|e| e == "sandbox") && path.join("home").is_dir() {
                return Some(path.join("home"));
            }
            stack.push(path);
        }
    }
    None
}

/// Waits until host A's run has its session home to collect, after the new front took the
/// sandbox back; then ends that front, and lets the home's reader go on.
async fn end_the_new_front_before_the_home(dir: &std::path::Path, host_a: u32, first: u32) {
    let deadline = std::time::Instant::now() + Duration::from_secs(90);
    while !dir.join("home-reached").exists() {
        assert!(
            std::time::Instant::now() < deadline,
            "the session home was not collected"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let second = fronts_of(host_a)
        .into_iter()
        .find(|&pid| pid != first)
        .expect("the new front");
    kill_front(second);
    std::fs::write(dir.join("go"), b"").unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_new_front_that_ends_after_the_repositories_came_back_says_what_was_saved() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let (dir, mut host_a, mut conn, spec, first) =
            a_front_killed_mid_run_in(dir, &mut server, Some(&repo), true).await;
        end_the_new_front_before_the_home(dir.path(), host_a.id(), first).await;
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(
            message.contains("the new front that took the sandbox back ended too")
                && message.contains("saved: the repository")
                && message.contains("not saved: the session home")
                && !message.contains("its work so far was saved"),
            "{message}"
        );
        // The repository came back, and is pushed.
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        host_a.crash();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_without_a_repository_whose_new_front_ends_too_says_its_work_is_lost() {
    with_timeout(180, async {
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let (dir, mut host_a, mut conn, spec, first) =
            a_front_killed_mid_run_in(dir, &mut server, None, true).await;
        end_the_new_front_before_the_home(dir.path(), host_a.id(), first).await;
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(
            message.contains("ended too")
                && message.contains("the work of the run is lost")
                && message.contains("the session home")
                && !message.contains("its work so far was saved"),
            "{message}"
        );
        host_a.crash();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_of_a_host_before_fronts_is_taken_back_through_the_shared_listener() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let mut host_a = host_process(dir.path(), &server.url, false);
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        conn.wait_running(spec.run_id).await;
        conn.until_seq(spec.run_id, 3).await;
        host_a.crash();
        // Host A's record becomes one of the code before each sandbox's bridge was its front's.
        make_record_of_the_version_before(&dir.path().join("data"), spec.run_id);
        let record = dir.path().join(format!("data/runs/{}.json", spec.run_id));
        let kept: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&record).unwrap()).unwrap();
        assert!(kept.get("bridge").is_none(), "{kept}");
        assert!(dir.path().join("data/runs/bridge.json").exists());

        // Host B, of this version, takes the run back through its shared listener.
        let host = start_host_at(dir.path(), &server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        assert!(
            finished["failureMessage"]
                .as_str()
                .unwrap()
                .contains("its work so far was pushed"),
            "{}",
            t.text()
        );
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        assert!(!record.exists());
        let listener = host
            .host
            .started_bridge()
            .await
            .expect("the shared listener");
        assert_eq!(listener.server.run_count(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}
