//! End-to-end runs with the VM executor (`run --executor vm`, ADR-0096) and the fake driver of
//! `tests/fake-vm-driver`: the host creates a "VM" per run through the driver protocol, with
//! the run's user data, dials its bridge, which listens on a loopback address of its own,
//! probes it, puts the run's files in, starts the fake agent as the sandbox's own command,
//! takes the work back and deletes the VM. The VMs here are processes on this machine
//! (`VmOptions::on_this_machine`), so the layout is the local test executor's.

#[path = "../../../../runner/crates/tinfirm-runner-host/tests/common/mod.rs"]
mod common;

use std::time::Duration;

use common::*;
use serde_json::{Value, json};
use tinfirm_bridge::control::ProbeCheck;
use uuid::Uuid;

fn calls_of<'a>(calls: &'a [Value], method: &str) -> Vec<&'a Value> {
    calls.iter().filter(|c| c["method"] == method).collect()
}

fn deleted(calls: &[Value], id: &str) -> bool {
    calls_of(calls, "delete")
        .iter()
        .any(|c| c["params"]["id"] == id)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_in_a_vm_clones_runs_commits_pushes_and_deletes_its_vm() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::vm(&log, &[])).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("happy", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_contiguous_seq();
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{}", t.text());
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(finished["git"][0]["commits"], 2);
        assert_eq!(
            repo.file_at("agent/TEST-1", "hello.txt").unwrap(),
            "hello from the fake agent\n"
        );

        // One VM, with the host's label and its own, and its user data, deleted before the
        // run ended. Nothing of it names the run (ADR-0098).
        let calls = vm_driver_calls(&log);
        let creates = calls_of(&calls, "create");
        assert_eq!(creates.len(), 1, "{calls:#?}");
        let create = &creates[0]["params"];
        assert_eq!(create["template"], "sandbox-test");
        let vm_label = create["labels"]["tinfirm-vm"].as_str().unwrap().to_string();
        assert!(vm_label.parse::<Uuid>().is_ok(), "{create}");
        assert_eq!(
            create["labels"],
            json!({
                "tinfirm-host": server.runner_host_id.to_string(),
                "tinfirm-vm": vm_label,
            })
        );
        assert!(
            !create.to_string().contains(&spec.run_id.to_string()),
            "{create}"
        );
        assert!(deleted(&calls, "vm-1"), "{calls:#?}");
        let delete = calls_of(&calls, "delete")[0];
        assert_eq!(
            delete["params"]["labels"],
            json!({"tinfirm-host": server.runner_host_id.to_string()})
        );
        // The user data: the bridge listens, with the VM's ticket and the host's pin; no
        // address to dial, and no credential of any kind.
        let data = &create["userData"];
        assert_eq!(data["tinfirm"], 1);
        assert!(data["image"].as_str().unwrap().contains("claude"), "{data}");
        let env = &data["env"];
        assert_eq!(env["TINFIRM_BRIDGE_LISTEN"], "0.0.0.0:7443");
        assert_eq!(env["TINFIRM_BRIDGE_RUN"], format!("vm-{vm_label}"));
        assert_eq!(env["TINFIRM_BRIDGE_TICKET"].as_str().unwrap().len(), 64);
        assert_eq!(env["TINFIRM_BRIDGE_PIN"].as_str().unwrap().len(), 64);
        assert!(env.get("TINFIRM_BRIDGE_ADDR").is_none(), "{env}");
        let text = data.to_string();
        for secret in [
            TEST_CLAUDE_TOKEN,
            TEST_OPENAI_KEY,
            TEST_GIT_TOKEN,
            TEST_HOST_TOKEN,
        ] {
            assert!(!text.contains(secret), "a credential in the user data");
        }
        assert!(env.get("CLAUDE_CODE_OAUTH_TOKEN").is_none(), "{env}");
        // The run's registration ended with the run.
        let listener = host.host.bridge().await.unwrap().expect("a listener");
        assert_eq!(listener.server.run_count(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agent_in_a_vm_gets_the_runs_environment_over_the_bridge_only() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::vm(&log, &[])).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        // A key of the host, which reaches the agent in its environment; a Claude login is a file
        // that a VM cannot mount (ADR-0085), and the company host a VM runs on holds none.
        let mut spec = AssignSpec::new("bridge-env", None);
        spec.runtime = "CODEX";
        spec.credential = "openai-test";
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let report = t.agent_text();
        assert!(report.contains("token=set"), "{report}");
        assert!(report.contains("run=set"), "{report}");
        assert!(report.contains("bridge-vars=0"), "{report}");
        if cfg!(target_os = "linux") {
            // The sandbox's own command was started by the bridge, which never had the token.
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
async fn a_vm_that_reaches_what_it_must_not_is_deleted_before_the_agent_starts() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        // "The internet" that the VM must not reach, but does: a port that takes connections.
        let open = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let open_addr = open.local_addr().unwrap().to_string();
        let closed = {
            let gone = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            gone.local_addr().unwrap().to_string()
        };
        let mut options = vm_options(&log, &[]);
        options.probe = vec![
            ProbeCheck::Tcp { addr: closed },
            ProbeCheck::Tcp {
                addr: open_addr.clone(),
            },
        ];
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::vm_with(options)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("happy", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "INFRA");
        let text = t.text();
        assert!(
            text.contains("the sandbox reached what it must not"),
            "{text}"
        );
        assert!(
            text.contains(&format!("a connection to {open_addr}")),
            "{text}"
        );
        // The agent never started, and the VM is gone.
        assert!(t.events_of("AGENT_MESSAGE").is_empty());
        assert!(deleted(&vm_driver_calls(&log), "vm-1"));
        drop(open);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_vm_that_does_not_come_up_fails_the_run_and_is_deleted() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        for (template, expected) in [
            ("stuck", "did not come up within 2 s"),
            ("failing", "the provider could not start the VM"),
        ] {
            let log = dir.path().join(format!("{template}.log"));
            let mut options = vm_options(&log, &[("FAKE_VM_TEMPLATE", template)]);
            options.start_timeout = Duration::from_secs(2);
            let mut server = FakeServer::start().await;
            let host = start_host(&server.url, HostSetup::vm_with(options)).await;
            let mut conn = server.accept().await;
            conn.handshake(1).await;
            let spec = AssignSpec::new("happy", None);
            conn.send(spec.to_json());
            let t = conn.until_finished(spec.run_id).await;
            let finished = t.finished();
            assert_eq!(finished["outcome"], "FAILED", "{template}");
            assert_eq!(finished["failureClass"], "INFRA", "{template}");
            assert!(t.text().contains(expected), "{template}: {}", t.text());
            assert!(deleted(&vm_driver_calls(&log), "vm-1"), "{template}");
            host.shutdown.cancel();
            host.handle.await.unwrap().unwrap();
        }
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_leftover_vms_of_this_host_are_deleted_and_no_other() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let ours = server.runner_host_id.to_string();
        let leftovers = json!([
            {"id": "ours-1", "labels": {"tinfirm-host": ours, "tinfirm-run": Uuid::new_v4().to_string()}},
            {"id": "ours-2", "labels": {"tinfirm-host": ours}},
            {"id": "theirs-1", "labels": {"tinfirm-host": Uuid::new_v4().to_string(), "tinfirm-run": Uuid::new_v4().to_string()}},
        ])
        .to_string();
        let host = start_host(
            &server.url,
            HostSetup::vm(&log, &[("FAKE_VM_LEFTOVERS", &leftovers)]),
        )
        .await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
        loop {
            let calls = vm_driver_calls(&log);
            if deleted(&calls, "ours-1") && deleted(&calls, "ours-2") {
                // Listed by this host's label, and never by another's.
                let lists = calls_of(&calls, "list");
                assert!(
                    lists
                        .iter()
                        .all(|c| c["params"]["labels"] == json!({"tinfirm-host": ours})),
                    "{lists:#?}"
                );
                assert!(!deleted(&calls, "theirs-1"), "{calls:#?}");
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "the leftovers were not deleted: {calls:#?}"
            );
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_vm_host_says_microvm_and_takes_skills() {
    with_timeout(60, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            protocol_version: 2,
            ..HostSetup::vm(&log, &[])
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(1).await;
        assert_eq!(hello["isolationTiers"], json!(["microvm"]));
        assert_eq!(hello["executors"], json!(["docker"]));
        let runtimes = hello["runtimes"].as_array().unwrap();
        assert!(!runtimes.is_empty());
        for runtime in runtimes {
            assert_eq!(runtime["version"], "VM template sandbox-test", "{runtime}");
        }
        let features = hello["features"].as_array().unwrap();
        assert!(features.contains(&json!("run.skills")), "{features:?}");
        // Every agent runs over the bridge, which the server's licence must hold (ADR-0100).
        assert!(features.contains(&json!("sandbox.bridge")), "{features:?}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn run_refuses_a_template_that_breaks_the_contract_or_a_limit_below_its_ceiling() {
    use tinfirm_runner_host::vm::Vms;
    let dir = tempfile::tempdir().unwrap();
    let log = dir.path().join("driver.log");
    let check = |env: &[(&str, &str)], max: u32| {
        let vms = Vms::new(vm_options(&log, env));
        async move { vms.check(max).await }
    };
    let (hello, verified) = check(&[("FAKE_VM_LIMIT", "4")], 4).await.unwrap();
    assert_eq!(hello.driver, "fake");
    assert_eq!(verified.max_vms, Some(4));
    let error = check(&[("FAKE_VM_LIMIT", "3")], 4).await.unwrap_err();
    let error = format!("{error:#}");
    assert!(error.contains("--max-concurrent-runs is 4"), "{error}");
    assert!(error.contains("3 more VM(s)"), "{error}");
    let problems = json!(["the firewall allows outbound tcp to 0.0.0.0/0"]).to_string();
    let error = check(&[("FAKE_VM_PROBLEMS", &problems)], 1)
        .await
        .unwrap_err();
    let error = format!("{error:#}");
    assert!(error.contains("breaks the contract"), "{error}");
    assert!(error.contains("allows outbound tcp"), "{error}");
    let calls = vm_driver_calls(&log);
    assert_eq!(calls[0]["method"], "hello");
    assert_eq!(calls[1]["method"], "verify");
    assert_eq!(calls[1]["params"]["template"], "sandbox-test");
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

/// ADR-0064 in a VM: the skill reaches the VM over the bridge, where the runtime reads it, and
/// the session's own files come back to the host's home without it.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_vm_gets_the_runs_skills_over_the_bridge() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            protocol_version: 2,
            ..HostSetup::vm(&log, &[])
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
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
            t.agent_text()
                .contains("skills: [.claude/skills/pdf: name: pdf,"),
            "{}",
            t.agent_text()
        );
        assert!(t.agent_text().ends_with("home writable"), "{}", t.agent_text());
        let home = host.paths.session_home(spec.agent_id, spec.task_id);
        assert!(home.join(".claude/fake-session.json").is_file());
        assert!(!home.join(".claude/skills/pdf/SKILL.md").exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_cancelled_run_takes_its_work_back_and_deletes_its_vm() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::vm(&log, &[])).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        conn.wait_running(spec.run_id).await;
        assert!(!deleted(&vm_driver_calls(&log), "vm-1"));
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "CANCELLED", "{}", t.text());
        // The agent's file came back from the VM and went out as WIP.
        assert!(repo.file_at("agent/TEST-1", "slow.txt").is_some());
        assert!(deleted(&vm_driver_calls(&log), "vm-1"));
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_builtin_digitalocean_driver_speaks_the_protocol() {
    use tinfirm_runner_host::vm::{Driver, DriverCommand};
    let dir = tempfile::tempdir().unwrap();
    let token = dir.path().join("token");
    std::fs::write(&token, "dop_v1_TEST-ONLY-fake-token\n").unwrap();
    // An API that is not there: `hello` needs none, `verify` fails on it.
    let closed = std::net::TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap();
    let config = dir.path().join("digitalocean.json");
    std::fs::write(
        &config,
        json!({
            "apiUrl": format!("http://{closed}"),
            "tokenFile": token,
            "templates": {"sandbox-4c8g": {
                "region": "fra1", "size": "s-4vcpu-8gb", "image": "123",
                "vpc": "vpc-1", "firewall": "fw-1"
            }}
        })
        .to_string(),
    )
    .unwrap();
    let driver = Driver::new(DriverCommand {
        program: env!("CARGO_BIN_EXE_tinfirm-runner-host").into(),
        args: vec![
            "vm-driver".into(),
            "digitalocean".into(),
            "--config".into(),
            config.to_string_lossy().into_owned(),
        ],
        env: vec![("TINFIRM_RUNNER_LOG".into(), "warn".into())],
    });
    let hello = driver.hello().await.unwrap();
    assert_eq!(hello.driver, "digitalocean");
    let error = format!("{:#}", driver.verify("sandbox-4c8g").await.unwrap_err());
    assert!(error.contains("/v2/account"), "{error}");
    let error = format!("{:#}", driver.verify("other").await.unwrap_err());
    assert!(error.contains("no template `other`"), "{error}");
    let error = format!("{:#}", driver.describe("1; rm").await.unwrap_err());
    assert!(error.contains("not a Droplet id"), "{error}");
}

/// Where the VM restart test's host A keeps its directories, and its server.
const RESTART_DIR: &str = "TINFIRM_TEST_VM_RESTART_DIR";
const RESTART_URL: &str = "TINFIRM_TEST_VM_RESTART_URL";

/// The hosts of the VM restart test: the fake driver keeps its VMs in a file, so the driver
/// of the host that restarted has them, and they outlive the host that is killed.
fn restart_setup(dir: &std::path::Path) -> HostSetup {
    let state = dir.join("vms.json").to_string_lossy().into_owned();
    HostSetup::vm(&dir.join("driver.log"), &[("FAKE_VM_STATE", &state)])
}

/// Host A of [`a_host_that_restarted_takes_back_the_run_in_its_vm`], in a process of its own
/// that the test kills as a crash would (SIGKILL): run only by that test.
#[test]
#[ignore = "host A of the VM restart test, which starts it"]
fn vm_restart_test_host_a() {
    let (Ok(dir), Ok(url)) = (std::env::var(RESTART_DIR), std::env::var(RESTART_URL)) else {
        return;
    };
    let dir = std::path::PathBuf::from(dir);
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let _host = start_host_at(&dir.join("host"), &url, restart_setup(&dir)).await;
            std::future::pending::<()>().await;
        });
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_that_restarted_takes_back_the_run_in_its_vm() {
    with_timeout(180, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let dir = tempfile::tempdir().unwrap();
        let mut host_a = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "vm_restart_test_host_a",
                "--exact",
                "--ignored",
                "--nocapture",
            ])
            .env(RESTART_DIR, dir.path())
            .env(RESTART_URL, &server.url)
            .stdout(std::process::Stdio::null())
            .spawn()
            .unwrap();
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        conn.wait_running(spec.run_id).await;
        let record = dir
            .path()
            .join(format!("host/data/runs/{}.json", spec.run_id));
        assert!(record.exists(), "no record of the run");
        conn.until_seq(spec.run_id, 3).await;
        host_a.kill().unwrap();
        host_a.wait().unwrap();
        let log = dir.path().join("driver.log");
        let creates = calls_of(&vm_driver_calls(&log), "create").len();
        let vm_id = {
            let state: Vec<Value> =
                serde_json::from_slice(&std::fs::read(dir.path().join("vms.json")).unwrap())
                    .unwrap();
            let running: Vec<&Value> = state.iter().filter(|vm| vm["state"] == "running").collect();
            assert_eq!(running.len(), 1, "{state:#?}");
            running[0]["id"].as_str().unwrap().to_string()
        };

        // Host B dials the VM's bridge, as A did, and takes the run back.
        let host = start_host_at(
            &dir.path().join("host"),
            &server.url,
            restart_setup(dir.path()),
        )
        .await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(1).await;
        assert_eq!(
            hello["activeRuns"][0]["runId"],
            spec.run_id.to_string(),
            "{hello}"
        );
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        assert_eq!(finished["git"][0]["pushed"], true, "{}", t.text());
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").as_deref(),
            Some("work in progress\n")
        );
        // The same VM, deleted by host B; no other was created for the run.
        let calls = vm_driver_calls(&log);
        assert_eq!(calls_of(&calls, "create").len(), creates, "{calls:#?}");
        assert!(deleted(&calls, &vm_id), "{calls:#?}");
        assert!(!record.exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}
