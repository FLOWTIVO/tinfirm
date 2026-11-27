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
        // A heartbeat goes out as soon as the run is active; the agent has written its file only
        // once it speaks. A cancel before that would leave nothing to push.
        conn.wait_running(spec.run_id).await;
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
/// Set, in host A: the moment of a run's end at which its run halts for good ([`halt_step`]),
/// once it has written the file `halted` in host A's directory.
const RESTART_HALT: &str = "TINFIRM_TEST_RESTART_HALT";

/// The moment of a run's end that [`RESTART_HALT`] names.
fn halt_step(name: &str) -> tinfirm_runner_host::run::EndStep {
    use tinfirm_runner_host::run::EndStep;
    match name {
        "stopped" => EndStep::Stopped,
        "decided" => EndStep::Decided,
        "pushed" => EndStep::Pushed,
        "reported" => EndStep::Reported,
        "stopping" => EndStep::Stopping,
        "report-kept" => EndStep::ReportKept,
        "output-file-saved" => EndStep::OutputFileSaved,
        other => panic!("no moment of a run's end is called {other}"),
    }
}

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
            let reader = std::env::var_os(RESTART_READER).map(PathBuf::from);
            let halt =
                std::env::var(RESTART_HALT)
                    .ok()
                    .map(|step| tinfirm_runner_host::run::HaltAt {
                        step: halt_step(&step),
                        marker: std::path::Path::new(&dir).join("halted"),
                        resume: false,
                    });
            setup.options = Some(Box::new(move |opts| {
                if let Some(reader) = reader {
                    let bridge = opts.bridge.as_mut().expect("a bridged host");
                    bridge.reader = tinfirm_runner_host::reader::Reader::new(reader)
                        .allowing_unconfined(bridge.reader.allows_unconfined());
                }
                opts.halt_at = halt;
            }));
            // SIGTERM stops host A as it stops `run`: its runs are cancelled and their ends
            // delivered. The process stays until its test ends it.
            let mut stop =
                tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).unwrap();
            let host = start_host_at(std::path::Path::new(&dir), &url, setup).await;
            stop.recv().await;
            host.shutdown.cancel();
            let _ = host.handle.await;
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
    host_process_with(dir, url, v2, reader, None)
}

/// [`host_process_reading`]; with `halt`, a run's end halts at that moment ([`RESTART_HALT`]).
fn host_process_with(
    dir: &std::path::Path,
    url: &str,
    v2: bool,
    reader: Option<&std::path::Path>,
    halt: Option<&str>,
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
    if let Some(halt) = halt {
        command.env(RESTART_HALT, halt);
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

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_front_that_ends_as_the_host_stops_leaves_its_run_cancelled_with_its_work_pushed() {
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
        let fronts = fronts_of(host_a.id());
        assert_eq!(
            fronts.len(),
            1,
            "one front for the run's sandbox: {fronts:?}"
        );
        // As a service manager stops the host's service with a signal to each of its
        // processes (systemd without `KillMode=mixed`): the front goes as `run` is told to
        // stop. A front stays on SIGTERM now, so SIGKILL stands in for one that did not.
        let status = std::process::Command::new("sh")
            .arg("-c")
            .arg(format!(
                "kill -KILL {} && kill -TERM {}",
                fronts[0],
                host_a.id()
            ))
            .status()
            .unwrap();
        assert!(status.success(), "cannot signal the front and the host");
        // The stop cancels the run: a new front brings its work back, and the front's end is
        // no failure of the run.
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "CANCELLED", "{}", t.text());
        assert!(finished.get("failureClass").is_none(), "{}", t.text());
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
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

// ----- a host that dies in a run's end (ADR-0101) -----------------------------------------

/// A run of protocol 2 with a session bundle to store, of `scenario` with `extra` in its
/// prompt. The `end-window` scenario ends its turn by itself, with work to push, files in
/// `/output` and its session.
fn end_run(
    scenario: &'static str,
    repo_url: &str,
    extra: &str,
) -> (AssignSpec<'static>, serde_json::Value) {
    let mut spec = AssignSpec::new(scenario, Some(repo_url));
    spec.protocol_version = 2;
    spec.prompt_extra = extra.to_string();
    let mut frame = spec.to_json();
    frame["sessionBundle"] = json!({});
    (spec, frame)
}

/// Waits until a host in a process of its own has halted where the test asked ([`RESTART_HALT`]).
async fn until_halted(dir: &std::path::Path) {
    let marker = dir.join("halted");
    let started = std::time::Instant::now();
    while !marker.exists() {
        assert!(
            started.elapsed() < Duration::from_secs(90),
            "the host never reached the moment of the run's end"
        );
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    std::fs::remove_file(&marker).unwrap();
}

/// What a killed host's connection carried after the kill: the frames until it closed. None is
/// a `run.finished`: the killed host reported nothing of the run's end.
async fn assert_no_end_reported(conn: &mut Conn, run_id: uuid::Uuid) {
    while conn.next().await.is_some() {}
    let id = run_id.to_string();
    assert!(
        !conn
            .frames
            .iter()
            .any(|f| f["type"] == "run.finished" && f["runId"] == id),
        "the killed host reported the run's end"
    );
}

/// The take-back record of the run in `dir`, as JSON.
fn kept_record(dir: &std::path::Path, run_id: uuid::Uuid) -> serde_json::Value {
    let path = dir.join(format!("data/runs/{run_id}.json"));
    serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap()
}

/// The run's kept report ([`tinfirm_runner_host::takeback::ReportRecord`]).
fn kept_report(dir: &std::path::Path, run_id: uuid::Uuid, epoch: i64) -> PathBuf {
    dir.join(format!("data/runs/{run_id}.{epoch}.finished.json"))
}

/// The names of the kept reports in `dir`'s records.
fn kept_reports(dir: &std::path::Path) -> Vec<String> {
    std::fs::read_dir(dir.join("data/runs"))
        .map(|entries| {
            entries
                .filter_map(|e| e.ok())
                .filter_map(|e| e.file_name().to_str().map(str::to_string))
                .filter(|name| name.ends_with(".finished.json"))
                .collect()
        })
        .unwrap_or_default()
}

/// Host A, in a process of its own, gets `frame` and halts at `halt` of the run's end; `while_halted`
/// acts on its connection then. Then it is killed (SIGKILL). The directories.
async fn host_a_killed_at(
    server: &mut FakeServer,
    spec: &AssignSpec<'_>,
    frame: serde_json::Value,
    halt: &str,
    while_halted: impl AsyncFnOnce(&mut Conn),
) -> tempfile::TempDir {
    let dir = tempfile::tempdir().unwrap();
    let mut host_a = host_process_with(dir.path(), &server.url, true, None, Some(halt));
    let mut conn = server.accept().await;
    conn.handshake(1).await;
    conn.send(frame);
    until_halted(dir.path()).await;
    while_halted(&mut conn).await;
    host_a.crash();
    assert_no_end_reported(&mut conn, spec.run_id).await;
    dir
}

/// [`host_a_killed_at`] with the `end-window` run (`extra` in its prompt). Before the kill, the
/// sandbox has stopped and the record says that the work came back in the run's end.
async fn a_host_killed_in_the_runs_end(
    server: &mut FakeServer,
    repo_url: &str,
    halt: &str,
    extra: &str,
) -> (tempfile::TempDir, AssignSpec<'static>, PathBuf) {
    let (spec, frame) = end_run("end-window", repo_url, extra);
    let dir = host_a_killed_at(server, &spec, frame, halt, async |_| {}).await;
    let record = dir.path().join(format!("data/runs/{}.json", spec.run_id));
    let kept = kept_record(dir.path(), spec.run_id);
    assert_eq!(kept["collected"], true, "{kept:#}");
    assert_eq!(kept["version"], 2, "{kept:#}");
    assert!(kept["end"].is_object(), "{kept:#}");
    assert_eq!(kept["repos"][0]["collected"]["result"], "ok", "{kept:#}");
    (dir, spec, record)
}

/// The setup of the host that finishes a run: it forgets a delivered report after a second of
/// connection, not 3 minutes.
fn next_host_setup() -> HostSetup {
    HostSetup {
        options: Some(Box::new(|opts| {
            opts.finished_retention = Duration::from_secs(1);
        })),
        ..restart_setup(true)
    }
}

/// Host B, on the directories of the host killed in `dir`: it takes the run back, finishes it
/// and reports it, once; `after_hello` acts on its connection first. Its transcript, once its
/// kept report is gone and the host has stopped; and nothing of the run is left on this host.
async fn finished_by_the_next_host_after(
    server: &mut FakeServer,
    dir: &std::path::Path,
    spec: &AssignSpec<'_>,
    after_hello: impl AsyncFnOnce(&mut Conn),
) -> Transcript {
    let host = start_host_at(dir, &server.url, next_host_setup()).await;
    let mut conn = server.accept().await;
    let hello = conn.handshake(1).await;
    assert_eq!(
        hello["activeRuns"][0]["runId"],
        spec.run_id.to_string(),
        "{hello}"
    );
    after_hello(&mut conn).await;
    let t = conn.until_finished(spec.run_id).await;
    t.assert_no_secrets();
    // The report goes once the connection stayed up for the retention after it.
    let report = kept_report(dir, spec.run_id, spec.epoch);
    let started = std::time::Instant::now();
    while report.exists() {
        assert!(
            started.elapsed() < Duration::from_secs(30),
            "the delivered report stays"
        );
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    host.shutdown.cancel();
    host.handle.await.unwrap().unwrap();
    while conn.next().await.is_some() {}
    // The end was reported once, and the server's frames got no complaint back.
    let id = spec.run_id.to_string();
    let ends = conn
        .frames
        .iter()
        .filter(|f| f["type"] == "run.finished" && f["runId"] == id)
        .count();
    assert_eq!(ends, 1, "{}", t.text());
    assert!(
        !conn.frames.iter().any(|f| f["type"] == "error"),
        "{:?}",
        conn.frames
    );
    // Nothing of the run is left: its record and report, its workspace and the sandbox's
    // stand-in beside it, its /output, its attachments.
    let paths = &host.paths;
    assert!(!dir.join(format!("data/runs/{id}.json")).exists());
    let workspace = paths.workspace(spec.run_id);
    assert!(!workspace.exists());
    assert!(
        !tinfirm_runner_host::sandbox_files::Layout::local(&workspace)
            .local_root
            .is_some_and(|root| root.exists())
    );
    assert!(!paths.output(spec.run_id).exists());
    assert!(!paths.attachments(spec.run_id).exists());
    // A run that failed keeps its workspace for inspection, as any failed run does.
    let failed = paths.data_dir.join("failed-workspaces").join(&id);
    assert_eq!(
        failed.exists(),
        t.finished()["outcome"] == "FAILED",
        "{}",
        t.text()
    );
    t
}

/// [`finished_by_the_next_host_after`], with nothing done after the hello.
async fn finished_by_the_next_host(
    server: &mut FakeServer,
    dir: &std::path::Path,
    spec: &AssignSpec<'_>,
) -> Transcript {
    finished_by_the_next_host_after(server, dir, spec, async |_| {}).await
}

/// The uploads of the run's `/output` file `name` and of its session bundle.
fn uploads_of_named(server: &FakeServer, name: &str) -> (usize, usize) {
    let uploads = server.uploads.lock().unwrap();
    let output = uploads
        .iter()
        .filter(|u| !u.path.contains("/session-bundle") && u.file_name() == name)
        .count();
    let bundles = uploads
        .iter()
        .filter(|u| u.path.contains("/session-bundle"))
        .count();
    (output, bundles)
}

/// [`uploads_of_named`] of `report.md`.
fn uploads_of(server: &FakeServer) -> (usize, usize) {
    uploads_of_named(server, "report.md")
}

/// The run ended as its agent did: succeeded, with the agent's summary and usage, its work
/// pushed to the run's branch, `/output` listed and its session stored.
fn assert_the_agents_outcome(t: &Transcript, repo: &TestRepo) {
    let finished = t.finished();
    assert_eq!(finished["outcome"], "SUCCEEDED", "{}", t.text());
    assert!(finished.get("failureClass").is_none(), "{}", t.text());
    assert!(
        finished["summary"]
            .as_str()
            .is_some_and(|s| s.contains("Done: the work is in done.txt.")),
        "{}",
        t.text()
    );
    assert_eq!(finished["usage"]["outputTokens"], 20, "{}", t.text());
    assert_eq!(finished["resumeOutcome"], "FIRST_RUN", "{}", t.text());
    assert!(finished["sessionId"].is_string(), "{}", t.text());
    assert_eq!(finished["sessionBundle"], "STORED", "{}", t.text());
    assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
    let artifacts = finished["artifacts"]
        .as_array()
        .unwrap_or_else(|| panic!("no artifacts: {}", t.text()));
    assert!(
        artifacts.iter().any(|a| a["name"] == "report.md"),
        "{}",
        t.text()
    );
    assert_eq!(
        repo.file_at("agent/TEST-1", "done.txt").as_deref(),
        Some("the finished work\n")
    );
    // One WIP commit on the branch: the work was pushed once.
    assert_eq!(
        repo.subjects("agent/TEST-1").len(),
        2,
        "{:?}",
        repo.subjects("agent/TEST-1")
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_killed_once_the_runs_outcome_is_decided_is_finished_by_the_next_with_that_outcome()
{
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _) =
            a_host_killed_in_the_runs_end(&mut server, &repo.url, "decided", "").await;
        let kept = kept_record(dir.path(), spec.run_id);
        let outcome = &kept["end"]["outcome"];
        assert!(outcome["summary"].is_string(), "{kept:#}");
        assert!(outcome.get("failureClass").is_none(), "{kept:#}");
        assert!(kept["end"].get("artifacts").is_none(), "{kept:#}");
        // Host A pushed nothing and saved nothing of /output.
        assert!(repo.branch_head("agent/TEST-1").is_none());
        assert_eq!(uploads_of(&server), (0, 0));

        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_the_agents_outcome(&t, &repo);
        assert!(
            t.text().contains("restarted at the end of the run"),
            "{}",
            t.text()
        );
        assert_eq!(uploads_of(&server), (1, 1));
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_killed_right_after_the_sandbox_stopped_has_its_run_pushed_and_reported_as_taken_back()
 {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _) =
            a_host_killed_in_the_runs_end(&mut server, &repo.url, "stopped", "").await;
        let kept = kept_record(dir.path(), spec.run_id);
        assert!(kept["end"].get("outcome").is_none(), "{kept:#}");

        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        let finished = t.finished();
        // The agent's outcome was not in the record yet: the run is reported as taken back,
        // with what was saved.
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        let message = finished["failureMessage"].as_str().unwrap();
        for part in [
            "restarted at the end of the run",
            "the agent's turn had ended",
            "its work was pushed",
            "its session was kept",
        ] {
            assert!(message.contains(part), "{part}: {message}");
        }
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(finished["artifacts"].as_array().map(Vec::len), Some(1));
        assert_eq!(finished["sessionBundle"], "STORED", "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "done.txt").as_deref(),
            Some("the finished work\n")
        );
        assert_eq!(uploads_of(&server), (1, 1));
    })
    .await;
}

/// A reader program for a host in a process of its own that writes the job of each reader the
/// host starts on a line of `reader-jobs` in `dir`, and then is the host's own reader.
fn recording_reader(dir: &std::path::Path) -> PathBuf {
    use std::os::unix::fs::PermissionsExt as _;
    let script = dir.join("recording-reader.sh");
    std::fs::write(
        &script,
        format!(
            "#!/bin/sh\nprintf '%s\\n' \"$2\" >> '{jobs}'\nexec '{real}' \"$@\"\n",
            jobs = dir.join("reader-jobs").display(),
            real = env!("CARGO_BIN_EXE_tinfirm-runner-host"),
        ),
    )
    .unwrap();
    std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
    script
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_that_finishes_a_run_whose_work_came_back_reads_its_clone_through_readers() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _) =
            a_host_killed_in_the_runs_end(&mut server, &repo.url, "stopped", "").await;
        // Host B, in a process of its own, whose readers write down their jobs. It has no
        // sandbox to collect from: the clone holds the sandbox's commits already.
        let reader = recording_reader(dir.path());
        let mut host_b = host_process_with(dir.path(), &server.url, true, Some(&reader), None);
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["git"][0]["pushed"], true, "{}", t.text());
        host_b.crash();
        // What reads the sandbox's objects ran in a reader, on the run's clone: HEAD, the
        // objects that are there, the count of commits ahead, and the scan of what the push
        // sends.
        let jobs = std::fs::read_to_string(dir.path().join("reader-jobs")).unwrap_or_default();
        let clone = tinfirm_runner_host::paths::Paths::new(
            dir.path().join("config"),
            dir.path().join("data"),
        )
        .workspace(spec.run_id)
        .join("repo");
        let clone = serde_json::to_string(&clone).unwrap();
        for args in [
            r#"["rev-parse","HEAD"]"#,
            r#"["cat-file","--batch-check"]"#,
            r#"["rev-list","--count","--stdin"]"#,
            r#"["cat-file","--batch"]"#,
        ] {
            assert!(
                jobs.lines().any(|job| job.contains(r#""kind":"git""#)
                    && job.contains(&clone)
                    && job.contains(args)),
                "no reader ran git {args} on the clone:\n{jobs}"
            );
        }
    })
    .await;
}

/// A run whose agent leaked a credential value, its host killed at `halt` of its end: the next
/// host lets nothing of it leave, and reports the leak.
async fn a_leak_survives_a_restart_in_the_runs_end(halt: &str) {
    let repo = TestRepo::new();
    let mut server = FakeServer::start().await;
    // The run's token expires in an hour: a host that takes a run back renews every token that
    // expires before it pushes, unless the run leaked.
    let (mut spec, _) = end_run("end-window", &repo.url, "[leak]");
    spec.workspaces = vec![WorkspaceEntry::new("repo", "Repo", &repo.url).expiring(
        TEST_GIT_TOKEN,
        chrono::Utc::now() + chrono::TimeDelta::minutes(60),
        TEST_MIRROR_KEY,
    )];
    let mut frame = spec.to_json();
    frame["sessionBundle"] = json!({});
    let dir = host_a_killed_at(&mut server, &spec, frame, halt, async |_| {}).await;
    // The leak is in the record with its mark, before the sandbox stopped.
    let kept = kept_record(dir.path(), spec.run_id);
    assert_eq!(kept["end"]["leak"], true, "{kept:#}");
    assert!(
        kept["assign"]["workspaces"][0]["git"]["expiresAt"].is_string(),
        "{kept:#}"
    );

    let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
    let finished = t.finished();
    assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
    assert_eq!(finished["failureClass"], "CREDENTIAL_LEAK", "{}", t.text());
    assert_eq!(finished["git"][0]["pushed"], false, "{}", t.text());
    assert_ne!(finished["sessionBundle"], "STORED", "{}", t.text());
    assert!(repo.branch_head("agent/TEST-1").is_none());
    // No file of /output and no session bundle left the host, before or after the restart.
    assert!(server.uploads.lock().unwrap().is_empty());
    // A run that leaked asks for no new git token.
    assert!(!t.text().contains("GIT_TOKEN"), "{}", t.text());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_leak_whose_host_died_while_the_sandbox_stopped_leaves_nothing_after_a_restart() {
    with_timeout(180, a_leak_survives_a_restart_in_the_runs_end("stopped")).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_leak_whose_host_died_once_the_outcome_was_decided_leaves_nothing_after_a_restart() {
    with_timeout(180, a_leak_survives_a_restart_in_the_runs_end("decided")).await;
}

/// A run that the server cancelled, its host killed at `halt` of its end: the next host pushes
/// its work and reports it cancelled.
async fn a_cancel_survives_a_restart_in_the_runs_end(halt: &str) {
    let repo = TestRepo::new();
    let mut server = FakeServer::start().await;
    let (spec, frame) = end_run("slow", &repo.url, "");
    let dir = tempfile::tempdir().unwrap();
    let mut host_a = host_process_with(dir.path(), &server.url, true, None, Some(halt));
    let mut conn = server.accept().await;
    conn.handshake(1).await;
    conn.send(frame);
    conn.until_agent_says(spec.run_id, "tick").await;
    conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
    until_halted(dir.path()).await;
    host_a.crash();
    assert_no_end_reported(&mut conn, spec.run_id).await;
    let kept = kept_record(dir.path(), spec.run_id);
    assert_eq!(kept["end"]["cancelled"], true, "{kept:#}");

    let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
    let finished = t.finished();
    assert_eq!(finished["outcome"], "CANCELLED", "{}", t.text());
    assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
    assert_eq!(
        repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
        Some("work in progress\n")
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_cancel_whose_host_died_while_the_sandbox_stopped_ends_cancelled_after_a_restart() {
    with_timeout(180, a_cancel_survives_a_restart_in_the_runs_end("stopped")).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_cancel_whose_host_died_once_the_outcome_was_decided_ends_cancelled_after_a_restart() {
    with_timeout(180, a_cancel_survives_a_restart_in_the_runs_end("decided")).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_failure_the_runs_end_decided_is_reported_by_the_next_host() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (spec, frame) = end_run("refusal", &repo.url, "");
        let dir = host_a_killed_at(&mut server, &spec, frame, "decided", async |_| {}).await;
        let kept = kept_record(dir.path(), spec.run_id);
        assert_eq!(
            kept["end"]["outcome"]["failureClass"], "MODEL_REFUSED",
            "{kept:#}"
        );
        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "MODEL_REFUSED", "{}", t.text());
        assert!(
            finished["failureMessage"]
                .as_str()
                .is_some_and(|m| m.contains("refusal")),
            "{}",
            t.text()
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_revocation_in_the_runs_end_stays_with_the_record_and_the_next_host_does_not_push() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (spec, frame) = end_run("end-window", &repo.url, "");
        let component = spec.workspaces[0].component_id;
        let run_id = spec.run_id;
        // Host A gets the revocation while it is in the run's end, applies and confirms it.
        let dir = host_a_killed_at(&mut server, &spec, frame, "decided", async |conn| {
            conn.send(git_revoke(run_id, 1, &[component], REVOKED));
            let id = run_id.to_string();
            conn.take_where("the confirmation", |f| {
                f["type"] == "run.git.revoked" && f["runId"] == id
            })
            .await;
        })
        .await;
        let text =
            std::fs::read_to_string(dir.path().join(format!("data/runs/{run_id}.json"))).unwrap();
        assert!(!text.contains(TEST_GIT_TOKEN), "{text}");
        let kept: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(kept["revoked"][0]["componentId"], component.to_string());

        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        let finished = t.finished();
        // The revoked repository's commits stay on the host: GIT_ERROR, as in the run's end.
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "GIT_ERROR", "{}", t.text());
        assert_eq!(finished["git"][0]["pushed"], false, "{}", t.text());
        assert!(t.text().contains(&format!("not pushed: {REVOKED}")));
        assert!(repo.branch_head("agent/TEST-1").is_none());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_abort_at_the_next_host_of_a_run_in_its_end_pushes_and_reports_nothing() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, record) =
            a_host_killed_in_the_runs_end(&mut server, &repo.url, "decided", "").await;
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake_listing(1, SERVER_FEATURES).await;
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
        let id = spec.run_id.to_string();
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
        assert!(repo.branch_head("agent/TEST-1").is_none());
        assert_eq!(uploads_of(&server), (0, 0));
        assert!(!record.exists());
        assert!(!kept_report(dir.path(), spec.run_id, 1).exists());
        assert!(!host.paths.workspace(spec.run_id).exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_killed_during_the_push_has_its_run_pushed_once_by_the_next() {
    use common::git_http::{GitHttpServer, test_git_authorization};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
    with_timeout(180, async {
        let repo = TestRepo::new();
        let git_server =
            GitHttpServer::start(repo.dir.path(), Some(test_git_authorization())).await;
        let url = format!("{}/origin.git", git_server.url);
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let mut host_a = host_process_with(dir.path(), &server.url, true, None, None);
        // Host A is killed while its push is with the repository: after the client sent it,
        // before the repository took it. What host A started (git's push) lives on.
        let pid = Arc::new(AtomicU32::new(host_a.id()));
        let killed = Arc::new(AtomicBool::new(false));
        {
            let (pid, killed) = (pid.clone(), killed.clone());
            git_server.before_push(move || {
                if killed.swap(true, Ordering::SeqCst) {
                    return;
                }
                let pid = pid.load(Ordering::SeqCst);
                std::process::Command::new("kill")
                    .args(["-KILL", &pid.to_string()])
                    .status()
                    .unwrap();
                while !ended(pid) {
                    std::thread::sleep(Duration::from_millis(10));
                }
            });
        }
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (spec, frame) = end_run("end-window", &url, "");
        conn.send(frame);
        assert_no_end_reported(&mut conn, spec.run_id).await;
        assert!(
            killed.load(Ordering::SeqCst),
            "host A ended before its push"
        );
        host_a.crash();
        // The push that host A had started has ended, with the repository's answer or without.
        let started = std::time::Instant::now();
        while git_server
            .seen()
            .iter()
            .any(|r| r.path.ends_with("/git-receive-pack") && r.status == 0)
            && started.elapsed() < Duration::from_secs(10)
        {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        let before = repo.branch_head("agent/TEST-1");

        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_the_agents_outcome(&t, &repo);
        // The branch is at the run's head: pushed by host A's push or by host B's, once.
        let head = repo.branch_head("agent/TEST-1").unwrap();
        assert_eq!(t.finished()["git"][0]["headSha"], head.as_str());
        if let Some(before) = before {
            assert_eq!(before, head, "host B pushed another head");
        }
        assert_eq!(uploads_of(&server).0, 1);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_push_refused_because_the_branch_reached_the_runs_head_meanwhile_counts_as_pushed() {
    use common::git_http::{GitHttpServer, test_git_authorization};
    with_timeout(180, async {
        let repo = TestRepo::new();
        let git_server =
            GitHttpServer::start(repo.dir.path(), Some(test_git_authorization())).await;
        let url = format!("{}/origin.git", git_server.url);
        let mut server = FakeServer::start().await;
        let (dir, spec, _) = a_host_killed_in_the_runs_end(&mut server, &url, "decided", "").await;
        // Between host B's look at the branch and its update, another push (as host A's own
        // `git push`, which outlived it, would) puts the run's head on the branch: B's update
        // is refused.
        let clone = dir
            .path()
            .join(format!("data/workspaces/{}/repo", spec.run_id));
        let bare = repo.bare.clone();
        git_server.before_push(move || {
            git(
                &clone,
                &[
                    "push",
                    "-q",
                    bare.to_str().unwrap(),
                    "HEAD:refs/heads/agent/TEST-1",
                ],
            );
        });
        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_the_agents_outcome(&t, &repo);
        assert!(
            t.text().contains("is at the run's head already"),
            "{}",
            t.text()
        );
        assert_eq!(
            t.finished()["git"][0]["headSha"],
            repo.branch_head("agent/TEST-1").unwrap().as_str()
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_killed_between_two_files_of_output_has_only_the_others_saved_by_the_next() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _) = a_host_killed_in_the_runs_end(
            &mut server,
            &repo.url,
            "output-file-saved",
            "[second-output]",
        )
        .await;
        // Host A saved the first file (by path, appendix.md) and the record says so.
        let kept = kept_record(dir.path(), spec.run_id);
        assert_eq!(
            kept["end"]["artifacts"][0]["name"], "appendix.md",
            "{kept:#}"
        );
        assert!(kept["end"].get("outputSaved").is_none(), "{kept:#}");
        assert_eq!(uploads_of_named(&server, "appendix.md").0, 1);
        assert_eq!(uploads_of(&server).0, 0);
        let first = server.uploads.lock().unwrap()[0].attachment_id.to_string();

        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_the_agents_outcome(&t, &repo);
        // Each file was uploaded once; the report lists both, the first with host A's id.
        assert_eq!(uploads_of_named(&server, "appendix.md").0, 1);
        assert_eq!(uploads_of(&server).0, 1);
        let artifacts = t.finished()["artifacts"].as_array().unwrap().clone();
        assert_eq!(artifacts.len(), 2, "{}", t.text());
        let appendix = artifacts
            .iter()
            .find(|a| a["name"] == "appendix.md")
            .unwrap();
        assert_eq!(appendix["attachmentId"], first.as_str());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_killed_after_the_push_and_before_the_report_is_made_has_its_end_reported_once() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _) =
            a_host_killed_in_the_runs_end(&mut server, &repo.url, "reported", "").await;
        // Host A pushed, saved /output and stored the session; the record keeps the files.
        let pushed = repo.branch_head("agent/TEST-1").expect("host A pushed");
        assert_eq!(uploads_of(&server), (1, 1));
        let kept = kept_record(dir.path(), spec.run_id);
        assert_eq!(kept["end"]["artifacts"][0]["name"], "report.md", "{kept:#}");
        assert_eq!(kept["end"]["outputSaved"], true, "{kept:#}");

        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_the_agents_outcome(&t, &repo);
        // The branch was at the run's head already; /output is not saved again, the session
        // bundle is stored again (the server keeps the latest).
        assert_eq!(
            repo.branch_head("agent/TEST-1").as_deref(),
            Some(pushed.as_str())
        );
        assert_eq!(uploads_of(&server), (1, 2));
        let listed = &t.finished()["artifacts"][0]["attachmentId"];
        let first = server.uploads.lock().unwrap()[0].attachment_id.to_string();
        assert_eq!(listed, first.as_str(), "{}", t.text());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_killed_with_its_report_undelivered_has_it_delivered_once_by_the_next() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (spec, frame) = end_run("end-window", &repo.url, "");
        // Host A makes and keeps the report, and dies before the server has it.
        let dir = host_a_killed_at(&mut server, &spec, frame, "report-kept", async |_| {}).await;
        assert!(
            !dir.path()
                .join(format!("data/runs/{}.json", spec.run_id))
                .exists()
        );
        let report = kept_report(dir.path(), spec.run_id, 1);
        let kept: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&report).unwrap()).unwrap();
        assert_eq!(kept["finished"]["outcome"], "SUCCEEDED", "{kept:#}");
        assert_eq!(crate_mode(&report), Some(0o600));
        let pushed = repo.branch_head("agent/TEST-1").expect("host A pushed");
        let uploads = server.uploads.lock().unwrap().len();

        // Host B sends the report as it is, and does nothing else of the run.
        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_the_agents_outcome(&t, &repo);
        let mut sent = t.finished().clone();
        sent.as_object_mut().unwrap().remove("type");
        assert_eq!(sent, kept["finished"]);
        assert!(t.events().is_empty(), "{}", t.text());
        assert_eq!(
            repo.branch_head("agent/TEST-1").as_deref(),
            Some(pushed.as_str())
        );
        assert_eq!(server.uploads.lock().unwrap().len(), uploads);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_report_the_server_had_already_is_sent_once_more_and_then_forgotten() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let mut host_a = host_process_with(dir.path(), &server.url, true, None, None);
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (spec, frame) = end_run("end-window", &repo.url, "");
        conn.send(frame);
        // The server has the report; host A dies before it forgets it (3 minutes).
        conn.until_finished(spec.run_id).await;
        let report = kept_report(dir.path(), spec.run_id, 1);
        assert!(report.exists());
        host_a.crash();

        // As a server does for a run that ended, it answers the hello that lists it with
        // `run.cancel`; host B sends the report once more, which the server ignores, and then
        // forgets it.
        let id = spec.run_id;
        let t = finished_by_the_next_host_after(&mut server, dir.path(), &spec, async |conn| {
            conn.send(json!({"type": "run.cancel", "runId": id, "epoch": 1}));
        })
        .await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(t.events().is_empty(), "{}", t.text());
    })
    .await;
}

/// A file's mode bits.
fn crate_mode(path: &std::path::Path) -> Option<u32> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path)
        .ok()
        .map(|m| m.permissions().mode() & 0o777)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_that_dies_again_in_the_end_it_took_back_leaves_the_run_to_the_next() {
    with_timeout(240, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _) =
            a_host_killed_in_the_runs_end(&mut server, &repo.url, "decided", "").await;
        // Host B, in a process of its own, takes the run back, saves /output, pushes, and is
        // killed then.
        let mut host_b = host_process_with(dir.path(), &server.url, true, None, Some("pushed"));
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        until_halted(dir.path()).await;
        host_b.crash();
        assert_no_end_reported(&mut conn, spec.run_id).await;
        let pushed = repo.branch_head("agent/TEST-1").expect("host B pushed");
        assert_eq!(uploads_of(&server), (1, 0));
        let kept = kept_record(dir.path(), spec.run_id);
        assert_eq!(kept["end"]["artifacts"][0]["name"], "report.md", "{kept:#}");
        assert!(kept["end"]["outcome"].is_object(), "{kept:#}");

        // Host C finishes it.
        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_the_agents_outcome(&t, &repo);
        assert_eq!(
            repo.branch_head("agent/TEST-1").as_deref(),
            Some(pushed.as_str())
        );
        assert_eq!(uploads_of(&server), (1, 1));
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_cancel_at_the_hello_does_not_change_the_outcome_that_the_runs_end_decided() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let (dir, spec, _) =
            a_host_killed_in_the_runs_end(&mut server, &repo.url, "decided", "").await;
        // A cancel that the server held for the run while no host ran it comes at the hello:
        // the agent's turn had ended, so the run reports its outcome, as its end would have.
        let id = spec.run_id;
        let t = finished_by_the_next_host_after(&mut server, dir.path(), &spec, async |conn| {
            conn.send(json!({"type": "run.cancel", "runId": id, "epoch": 1}));
        })
        .await;
        assert_the_agents_outcome(&t, &repo);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_record_that_cannot_be_marked_in_the_runs_end_goes_with_the_sandbox_as_before() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::bridged()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("gate", Some(&repo.url));
        conn.send(spec.to_json());
        conn.wait_running(spec.run_id).await;
        let record = host
            .paths
            .data_dir
            .join(format!("runs/{}.json", spec.run_id));
        assert!(record.exists());
        // The record cannot be written: its temporary file's place is taken.
        let blocker = record.with_extension("json.tmp");
        std::fs::create_dir(&blocker).unwrap();
        release(&host.paths, spec.run_id);
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(
            t.text()
                .contains("cannot write in its record that the run's work came back"),
            "{}",
            t.text()
        );
        assert!(!record.exists());
        std::fs::remove_dir(&blocker).unwrap();
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

// ----- the kept report across executions and planned stops --------------------------------

/// A host on `dir`, in this process, that forgets a delivered report after a second.
async fn host_with_short_retention(dir: &std::path::Path, server: &FakeServer) -> TestHost {
    start_host_at(dir, &server.url, next_host_setup()).await
}

/// Runs `spec` (`frame`) on `conn` to its end.
async fn run_to_its_end(
    conn: &mut Conn,
    spec: &AssignSpec<'_>,
    frame: serde_json::Value,
) -> Transcript {
    conn.send(frame);
    let t = conn.until_finished(spec.run_id).await;
    assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
    t
}

/// The next start on `dir` lists no run and sends no end of one.
async fn the_next_start_sends_nothing(dir: &std::path::Path, server: &mut FakeServer) {
    let host = start_host_at(dir, &server.url, restart_setup(true)).await;
    let mut conn = server.accept().await;
    let hello = conn.handshake(1).await;
    assert!(
        hello["activeRuns"]
            .as_array()
            .is_none_or(|runs| runs.is_empty()),
        "{hello}"
    );
    let _ = tokio::time::timeout(Duration::from_secs(2), async {
        while conn.next().await.is_some() {}
    })
    .await;
    assert!(
        !conn.frames.iter().any(|f| f["type"] == "run.finished"),
        "{:?}",
        conn.frames
    );
    host.shutdown.cancel();
    host.handle.await.unwrap().unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_kept_report_of_an_older_execution_gives_way_to_the_newer_executions_record() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        // Epoch 2 of the run is in its end when its host dies; a report of epoch 1 is on disk
        // beside its record (the outbox of an earlier process held it when epoch 2 came).
        let (mut spec, _) = end_run("end-window", &repo.url, "");
        spec.epoch = 2;
        let mut frame = spec.to_json();
        frame["sessionBundle"] = json!({});
        let dir = host_a_killed_at(&mut server, &spec, frame, "decided", async |_| {}).await;
        let records =
            tinfirm_runner_host::takeback::Records::new(&tinfirm_runner_host::paths::Paths::new(
                dir.path().join("config"),
                dir.path().join("data"),
            ));
        let older: tinfirm_protocol::RunFinished = serde_json::from_value(json!({
            "runId": spec.run_id, "epoch": 1, "outcome": "FAILED", "lastSeq": 4,
            "failureClass": "INFRA", "failureMessage": "the older execution"
        }))
        .unwrap();
        records.save_report(&older).unwrap();
        assert_eq!(kept_record(dir.path(), spec.run_id)["assign"]["epoch"], 2);

        // The next start takes epoch 2 back and sends nothing of epoch 1.
        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_the_agents_outcome(&t, &repo);
        assert_eq!(t.finished()["epoch"], 2, "{}", t.text());
        assert!(!t.text().contains("the older execution"), "{}", t.text());
        assert!(
            kept_reports(dir.path()).is_empty(),
            "{:?}",
            kept_reports(dir.path())
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_finished_run_assigned_again_to_the_same_host_leaves_no_report_of_its_old_execution() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (mut spec, frame) = end_run("end-window", &repo.url, "");
        run_to_its_end(&mut conn, &spec, frame).await;
        assert!(kept_report(dir.path(), spec.run_id, 1).exists());
        // The server gives the run to this host again, as epoch 2.
        spec.epoch = 2;
        spec.scenario = "gate";
        let mut frame = spec.to_json();
        frame["sessionBundle"] = json!({});
        conn.send(frame);
        let id = spec.run_id.to_string();
        conn.take_where("epoch 2 accepted", |f| {
            f["type"] == "run.accepted" && f["runId"] == id && f["epoch"] == 2
        })
        .await;
        // Epoch 1's report goes once epoch 2 has taken its place in the outbox.
        let started = std::time::Instant::now();
        while kept_report(dir.path(), spec.run_id, 1).exists() {
            assert!(
                started.elapsed() < Duration::from_secs(20),
                "the report of epoch 1 stays"
            );
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
        conn.wait_running(spec.run_id).await;
        release(&host.paths, spec.run_id);
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["epoch"], 2, "{}", t.text());
        assert!(!kept_report(dir.path(), spec.run_id, 1).exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_stopped_right_after_a_run_ended_leaves_no_report_and_its_next_start_sends_nothing()
{
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (spec, frame) = end_run("end-window", &repo.url, "");
        run_to_its_end(&mut conn, &spec, frame).await;
        assert!(kept_report(dir.path(), spec.run_id, 1).exists());
        // SIGTERM: the end was written on the connection, which then closed in order.
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
        assert!(
            kept_reports(dir.path()).is_empty(),
            "{:?}",
            kept_reports(dir.path())
        );
        the_next_start_sends_nothing(dir.path(), &mut server).await;
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_drained_right_after_a_run_ended_leaves_no_report_and_its_next_start_sends_nothing()
{
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let host = host_with_short_retention(dir.path(), &server).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (spec, frame) = end_run("end-window", &repo.url, "");
        run_to_its_end(&mut conn, &spec, frame).await;
        assert!(host.host.request_drain());
        host.handle.await.unwrap().unwrap();
        assert!(
            kept_reports(dir.path()).is_empty(),
            "{:?}",
            kept_reports(dir.path())
        );
        the_next_start_sends_nothing(dir.path(), &mut server).await;
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_stopped_while_cut_off_keeps_its_report_and_its_next_start_sends_it_once() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (spec, frame) = end_run("gate", &repo.url, "");
        conn.send(frame);
        conn.wait_running(spec.run_id).await;
        // The host is cut off from the server; the run ends meanwhile.
        server.set_refusing(true);
        conn.kill();
        release(&host.paths, spec.run_id);
        let report = kept_report(dir.path(), spec.run_id, 1);
        let started = std::time::Instant::now();
        while !report.exists() {
            assert!(
                started.elapsed() < Duration::from_secs(60),
                "no report kept"
            );
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        // SIGTERM while still cut off: the end was never written, so its report stays.
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
        assert!(report.exists());
        server.set_refusing(false);
        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(t.events().is_empty(), "{}", t.text());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_stopped_as_the_server_goes_down_after_the_end_keeps_its_report() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let host = start_host_at(dir.path(), &server.url, restart_setup(true)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (spec, frame) = end_run("end-window", &repo.url, "");
        run_to_its_end(&mut conn, &spec, frame).await;
        // The end was written, and the server goes down with the connection, as in a restart
        // of the server or of a proxy: what it had not handled is lost with it. SIGTERM comes
        // before a welcome could follow.
        server.set_refusing(true);
        conn.kill();
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
        let report = kept_report(dir.path(), spec.run_id, 1);
        assert!(report.exists(), "{:?}", kept_reports(dir.path()));
        server.set_refusing(false);
        let t = finished_by_the_next_host(&mut server, dir.path(), &spec).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(t.events().is_empty(), "{}", t.text());
    })
    .await;
}

/// A host in this process whose runs' ends wait at `step` until the test removes
/// `<dir>/halted`.
async fn host_pausing_at(
    dir: &std::path::Path,
    server: &FakeServer,
    step: tinfirm_runner_host::run::EndStep,
) -> TestHost {
    let marker = dir.join("halted");
    start_host_at(
        dir,
        &server.url,
        HostSetup {
            options: Some(Box::new(move |opts| {
                opts.halt_at = Some(tinfirm_runner_host::run::HaltAt {
                    step,
                    marker,
                    resume: true,
                });
            })),
            ..restart_setup(true)
        },
    )
    .await
}

/// Waits until a host of [`host_pausing_at`] waits at its step.
async fn until_paused(dir: &std::path::Path) {
    let marker = dir.join("halted");
    let started = std::time::Instant::now();
    while !marker.exists() {
        assert!(started.elapsed() < Duration::from_secs(90), "never paused");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

/// The server calls execution `epoch` of `run_id` stale: the host aborts it.
fn call_stale(conn: &Conn, run_id: uuid::Uuid, epoch: i64) {
    conn.send(json!({
        "type": "error",
        "code": "STALE_EPOCH",
        "message": format!("epoch {epoch} is stale"),
        "runId": run_id,
        "epoch": epoch
    }));
    conn.send(json!({"type": "run.cancel", "runId": run_id, "epoch": epoch}));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_abort_after_the_session_bundle_makes_and_keeps_no_report() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let host = host_pausing_at(
            dir.path(),
            &server,
            tinfirm_runner_host::run::EndStep::SessionStored,
        )
        .await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (spec, frame) = end_run("end-window", &repo.url, "");
        conn.send(frame);
        until_paused(dir.path()).await;
        call_stale(&conn, spec.run_id, 1);
        tokio::time::sleep(Duration::from_millis(500)).await;
        std::fs::remove_file(dir.path().join("halted")).unwrap();
        let id = spec.run_id.to_string();
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
            kept_reports(dir.path()).is_empty(),
            "{:?}",
            kept_reports(dir.path())
        );
        assert!(!dir.path().join(format!("data/runs/{id}.json")).exists());
        assert!(!host.paths.workspace(spec.run_id).exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_aborted_execution_in_its_end_leaves_the_newer_executions_workspace_alone() {
    with_timeout(240, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let host = host_pausing_at(
            dir.path(),
            &server,
            tinfirm_runner_host::run::EndStep::SessionStored,
        )
        .await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (mut spec, frame) = end_run("end-window", &repo.url, "");
        conn.send(frame);
        until_paused(dir.path()).await;
        // The server gives the run to this host again as epoch 2: epoch 1 is aborted, and epoch
        // 2 starts once epoch 1 has ended or 20 s have passed.
        spec.epoch = 2;
        spec.scenario = "gate";
        let mut frame = spec.to_json();
        frame["sessionBundle"] = json!({});
        conn.send(frame);
        let id = spec.run_id.to_string();
        conn.take_where("epoch 2 running", |f| {
            f["runId"] == id
                && f["epoch"] == 2
                && f["eventType"] == "STATUS"
                && f["payload"]["phase"] == "running"
        })
        .await;
        let workspace = host.paths.workspace(spec.run_id);
        assert!(workspace.is_dir());
        // Epoch 1 goes on, sees its abort, and leaves epoch 2's workspace and record alone.
        std::fs::remove_file(dir.path().join("halted")).unwrap();
        tokio::time::sleep(Duration::from_secs(2)).await;
        assert!(workspace.is_dir(), "epoch 1 removed epoch 2's workspace");
        assert_eq!(kept_record(dir.path(), spec.run_id)["assign"]["epoch"], 2);
        release(&host.paths, spec.run_id);
        // Epoch 2 waits at the same moment of its end: it goes on too.
        until_paused(dir.path()).await;
        std::fs::remove_file(dir.path().join("halted")).unwrap();
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["epoch"], 2, "{}", t.text());
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_record_whose_mark_cannot_be_written_is_gone_before_the_sandbox_stops() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let mut host_a = host_process_with(dir.path(), &server.url, true, None, Some("stopping"));
        let group = host_a.id();
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let (spec, frame) = end_run("gate", &repo.url, "");
        conn.send(frame);
        conn.wait_running(spec.run_id).await;
        let record = dir.path().join(format!("data/runs/{}.json", spec.run_id));
        assert!(record.exists());
        // The record cannot be written from now on: its temporary file's place is taken.
        std::fs::create_dir(record.with_extension("json.tmp")).unwrap();
        let paths = tinfirm_runner_host::paths::Paths::new(
            dir.path().join("config"),
            dir.path().join("data"),
        );
        release(&paths, spec.run_id);
        // The sandbox has not stopped yet, and the record is gone already.
        until_halted(dir.path()).await;
        assert!(!record.exists());
        host_a.crash();
        assert_no_end_reported(&mut conn, spec.run_id).await;
        // So a restart there does not take the run back from a sandbox that is still there.
        std::fs::remove_dir(record.with_extension("json.tmp")).unwrap();
        the_next_start_sends_nothing(dir.path(), &mut server).await;
        assert!(repo.branch_head("agent/TEST-1").is_none());
        // What host A started (the sandbox's bridge) goes with the test.
        let _ = std::process::Command::new("kill")
            .args(["-KILL", "--", &format!("-{group}")])
            .stderr(std::process::Stdio::null())
            .status();
    })
    .await;
}
