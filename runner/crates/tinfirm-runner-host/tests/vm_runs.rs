//! End-to-end runs with the VM executor (`run --executor vm`, ADR-0096) and the fake driver of
//! `tests/fake-vm-driver`: the host creates a "VM" per run through the driver protocol, with
//! the run's user data, dials its bridge, which listens on a loopback address of its own,
//! probes it, puts the run's files in, starts the fake agent as the sandbox's own command,
//! takes the work back and deletes the VM. Every run starts cold: the host keeps no VM ready
//! (that is ready VMs', `ee/runner/tinfirm-runner-host/tests/pool_runs.rs`). The VMs here are
//! processes on this machine (`VmOptions::on_this_machine`), so the layout is the local test
//! executor's.

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
        // The driver named no port: the host dialed `--vm-port`, where the bridge listens.
        assert!(calls_of(&calls, "published").is_empty(), "{calls:#?}");
        // The run's registration ended with the run.
        let listener = host.host.bridge().await.unwrap().expect("a listener");
        assert_eq!(listener.server.run_count(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// ADR-0112: a driver that names a `port` in `describe` (a node publishes each VM's bridge on a
/// port of its own address) is dialed there, while the bridge in the VM still listens where its
/// user data says.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_vm_described_with_a_port_is_dialed_at_that_port() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let host = start_host(
            &server.url,
            HostSetup::vm(&log, &[("FAKE_VM_PUBLISH", "1")]),
        )
        .await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("bridge-env", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let calls = vm_driver_calls(&log);
        let published = calls_of(&calls, "published");
        assert_eq!(published.len(), 1, "{calls:#?}");
        let port = published[0]["params"]["port"].as_u64().unwrap();
        assert_ne!(port, 7443, "the published port is not --vm-port");
        // The user data is unchanged: the bridge's own port in the VM is still --vm-port.
        let create = calls_of(&calls, "create")[0];
        assert_eq!(
            create["params"]["userData"]["env"]["TINFIRM_BRIDGE_LISTEN"],
            "0.0.0.0:7443"
        );
        assert!(deleted(&calls, "vm-1"));
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

/// The run's claim and its VM's create each have a start timeout of the same length, the
/// VM's from its create, a moment later. A VM that does not come up is deleted before its
/// create fails; with a delete that takes 3 s, the claim's own deadline passes for certain
/// while the delete is in flight. The claim that started the VM waits for that VM's outcome:
/// the run fails with what happened to its VM, once the VM is deleted.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_whose_vm_is_still_being_deleted_at_its_deadline_fails_with_its_vms_outcome() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut options = vm_options(
            &log,
            &[("FAKE_VM_TEMPLATE", "stuck"), ("FAKE_VM_DELETE_MS", "3000")],
        );
        options.start_timeout = Duration::from_secs(1);
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
            text.contains("the VM vm-1 did not come up within 1 s"),
            "{text}"
        );
        assert!(!text.contains("no sandbox VM came up"), "{text}");
        // Deleted, the driver's answer in, before the run finished.
        let calls = vm_driver_calls(&log);
        assert!(
            calls_of(&calls, "deleted")
                .iter()
                .any(|c| c["params"]["id"] == "vm-1"),
            "{calls:#?}"
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
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
async fn a_vm_host_of_a_driver_of_ones_own_says_container_and_takes_skills() {
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
        // The host cannot see where an operator's own driver runs the VMs: it reports
        // `container` until the operator states a level (ADR-0113).
        assert_eq!(hello["isolationTiers"], json!(["container"]));
        assert!(hello.get("hardened").is_none(), "{hello}");
        assert!(hello.get("isolationStated").is_none(), "{hello}");
        assert_eq!(hello["executors"], json!(["docker"]));
        let runtimes = hello["runtimes"].as_array().unwrap();
        assert!(!runtimes.is_empty());
        for runtime in runtimes {
            assert_eq!(runtime["version"], "VM template sandbox-test", "{runtime}");
        }
        let features = hello["features"].as_array().unwrap();
        assert!(features.contains(&json!("run.skills")), "{features:?}");
        // Every agent runs over the bridge (ADR-0095). An operator's own driver and no ready
        // VMs: nothing that a server's licence must hold (ADR-0114).
        assert!(features.contains(&json!("sandbox.bridge")), "{features:?}");
        assert!(
            !features.contains(&json!("sandbox.elastic")),
            "{features:?}"
        );
        assert!(!features.contains(&json!("sandbox.pool")), "{features:?}");
        assert!(hello.get("pool").is_none(), "{hello}");
        assert!(!features.contains(&json!("host.isolation")), "{features:?}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_vm_host_reports_the_level_its_operator_states() {
    use tinfirm_protocol::{Hardened, IsolationTier};
    use tinfirm_runner_host::isolation::Stated;
    with_timeout(60, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            protocol_version: 2,
            options: Some(Box::new(|opts| {
                opts.isolation_stated = Some(Stated {
                    level: IsolationTier::Machine,
                    hardened: Hardened::Yes,
                });
            })),
            ..HostSetup::vm(&log, &[])
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        // The fake server checks the hello against the schema.
        let hello = conn.handshake(1).await;
        assert_eq!(hello["isolationTiers"], json!(["machine"]));
        assert_eq!(hello["hardened"], "yes");
        assert_eq!(hello["isolationStated"], true);
        assert!(
            !hello["features"]
                .as_array()
                .unwrap()
                .contains(&json!("host.isolation")),
            "{hello}"
        );
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
        async move { vms.check(max, Some("this-host")).await }
    };
    let (hello, verified, _) = check(&[("FAKE_VM_LIMIT", "4")], 4).await.unwrap();
    assert_eq!(hello.driver, "fake");
    assert_eq!(verified.max_vms, Some(4));
    let error = check(&[("FAKE_VM_LIMIT", "3")], 4).await.unwrap_err();
    let error = format!("{error:#}");
    assert!(error.contains("--max-concurrent-runs is 4"), "{error}");
    assert!(error.contains("room for 3 more VM(s)"), "{error}");
    assert!(
        error.contains("this host holds 0 VM(s) of its own"),
        "{error}"
    );
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

/// `count` VMs of `host`'s, running, for `FAKE_VM_LEFTOVERS`.
fn vms_of(host: &str, count: usize) -> String {
    json!(
        (0..count)
            .map(|i| json!({"id": format!("vm-{host}-{i}"), "labels": {"tinfirm-host": host, "tinfirm-vm": format!("{host}-{i}")}}))
            .collect::<Vec<_>>()
    )
    .to_string()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_host_whose_own_vms_fill_its_nodes_starts_and_one_with_too_few_slots_does_not() {
    use tinfirm_runner_host::vm::Vms;
    let dir = tempfile::tempdir().unwrap();
    let log = dir.path().join("driver.log");
    let check = |env: Vec<(&'static str, String)>, max: u32, own: Option<&'static str>| {
        let env: Vec<(&str, &str)> = env.iter().map(|(k, v)| (*k, v.as_str())).collect();
        let vms = Vms::new(vm_options(&log, &env));
        async move { vms.check(max, own).await }
    };
    // Nodes with 3 slots, all held by this host's VMs (runs to take back, leftovers): no free
    // slot, and the host starts.
    let (_, verified, own) = check(
        vec![
            ("FAKE_VM_SLOTS", "3".into()),
            ("FAKE_VM_LEFTOVERS", vms_of("host-a", 3)),
        ],
        3,
        Some("host-a"),
    )
    .await
    .unwrap();
    assert_eq!(verified.max_vms, Some(0));
    assert_eq!(own, 3);
    // Two of this host's and one of another's on the same 3 slots: the other host's VM is no
    // room of this host's.
    let both = json!([
        {"id": "a1", "labels": {"tinfirm-host": "host-a"}},
        {"id": "a2", "labels": {"tinfirm-host": "host-a"}},
        {"id": "b1", "labels": {"tinfirm-host": "host-b"}},
    ])
    .to_string();
    let error = check(
        vec![("FAKE_VM_SLOTS", "3".into()), ("FAKE_VM_LEFTOVERS", both)],
        3,
        Some("host-a"),
    )
    .await
    .unwrap_err();
    let error = format!("{error:#}");
    assert!(
        error.contains("room for 0 more VM(s), and this host holds 2 VM(s) of its own"),
        "{error}"
    );
    // Nodes too small for the ceiling: refused, with what is missing.
    let error = check(vec![("FAKE_VM_SLOTS", "2".into())], 3, Some("host-a"))
        .await
        .unwrap_err();
    let error = format!("{error:#}");
    assert!(error.contains("--max-concurrent-runs is 3"), "{error}");
    assert!(error.contains("room for 2 more VM(s)"), "{error}");
    assert!(error.contains("more nodes or slots"), "{error}");
    // A host before its first welcome has no VMs of its own, and is asked for none.
    let error = check(
        vec![
            ("FAKE_VM_SLOTS", "3".into()),
            ("FAKE_VM_LEFTOVERS", vms_of("host-a", 3)),
        ],
        3,
        None,
    )
    .await
    .unwrap_err();
    assert!(
        format!("{error:#}").contains("has none of its own yet"),
        "{error:#}"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn with_the_node_driver_only_own_vms_on_nodes_of_the_template_count_as_room() {
    use tinfirm_runner_host::vm::{Builtin, Vms};
    let dir = tempfile::tempdir().unwrap();
    let log = dir.path().join("driver.log");
    // Two nodes as the node driver's file names them: `n1` offers the template, `n2` another.
    let config = dir.path().join("node-driver.json");
    std::fs::write(
        &config,
        json!({"nodes": [
            {"name": "n1", "socket": "/run/tinfirm-node-agent/n1.sock", "templates": ["sandbox-test"]},
            {"name": "n2", "socket": "/run/tinfirm-node-agent/n2.sock", "templates": ["other"]},
        ]})
        .to_string(),
    )
    .unwrap();
    // This host's VMs, with the node driver's ids (`<node>:<id>`): one on `n1`, two on `n2`.
    let own = json!([
        {"id": "n1:a", "labels": {"tinfirm-host": "host-a"}},
        {"id": "n2:b", "labels": {"tinfirm-host": "host-a"}},
        {"id": "n2:c", "labels": {"tinfirm-host": "host-a"}},
    ])
    .to_string();
    let check = |max: u32| {
        let mut options = vm_options(
            &log,
            &[("FAKE_VM_SLOTS", "3"), ("FAKE_VM_LEFTOVERS", own.as_str())],
        );
        // The fake driver stands in for the node driver, whose file the host reads.
        options.driver.builtin = Some(Builtin::Node {
            config: config.clone(),
        });
        let vms = Vms::new(options);
        async move { vms.check(max, Some("host-a")).await }
    };
    // The VMs on `n2` hold no room of the template: one own VM counts, and a ceiling of 3 on
    // no free slot is refused.
    let error = format!("{:#}", check(3).await.unwrap_err());
    assert!(
        error.contains("room for 0 more VM(s), and this host holds 1 VM(s) of its own"),
        "{error}"
    );
    let (_, _, held) = check(1).await.unwrap();
    assert_eq!(held, 1);
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

/// Where the VM restart test's host A keeps its directories, and its server; and whether its
/// driver publishes each VM's bridge on a port of its own.
const RESTART_DIR: &str = "TINFIRM_TEST_VM_RESTART_DIR";
const RESTART_URL: &str = "TINFIRM_TEST_VM_RESTART_URL";
const RESTART_PUBLISH: &str = "TINFIRM_TEST_VM_RESTART_PUBLISH";

/// The hosts of the VM restart test: the fake driver keeps its VMs in a file, so the driver
/// of the host that restarted has them, and they outlive the host that is killed. With
/// `publish`, the driver names each VM's port in `describe` (ADR-0112).
fn restart_setup(dir: &std::path::Path, publish: bool) -> HostSetup {
    let state = dir.join("vms.json").to_string_lossy().into_owned();
    let mut env = vec![("FAKE_VM_STATE", state.as_str())];
    if publish {
        env.push(("FAKE_VM_PUBLISH", "1"));
    }
    HostSetup::vm(&dir.join("driver.log"), &env)
}

/// Host A of the VM restart tests, in a process of its own that the test kills as a crash
/// would (SIGKILL): run only by those tests.
#[test]
#[ignore = "host A of the VM restart tests, which start it"]
fn vm_restart_test_host_a() {
    let (Ok(dir), Ok(url)) = (std::env::var(RESTART_DIR), std::env::var(RESTART_URL)) else {
        return;
    };
    let publish = std::env::var(RESTART_PUBLISH).is_ok();
    let dir = std::path::PathBuf::from(dir);
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let _host = start_host_at(&dir.join("host"), &url, restart_setup(&dir, publish)).await;
            std::future::pending::<()>().await;
        });
}

/// A run in a VM whose host is killed and started again: host B takes it back. With `publish`,
/// the run's record keeps the VM's port and host B dials it there; without, the record has no
/// port, as one written before the port existed, and host B dials `--vm-port`.
async fn a_host_that_restarted_takes_back_the_run(publish: bool) {
    let repo = TestRepo::new();
    let mut server = FakeServer::start().await;
    let dir = tempfile::tempdir().unwrap();
    let mut command = std::process::Command::new(std::env::current_exe().unwrap());
    command
        .args([
            "vm_restart_test_host_a",
            "--exact",
            "--ignored",
            "--nocapture",
        ])
        .env(RESTART_DIR, dir.path())
        .env(RESTART_URL, &server.url)
        .stdout(std::process::Stdio::null());
    if publish {
        command.env(RESTART_PUBLISH, "1");
    }
    let mut host_a = command.spawn().unwrap();
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
            serde_json::from_slice(&std::fs::read(dir.path().join("vms.json")).unwrap()).unwrap();
        let running: Vec<&Value> = state.iter().filter(|vm| vm["state"] == "running").collect();
        assert_eq!(running.len(), 1, "{state:#?}");
        running[0]["id"].as_str().unwrap().to_string()
    };
    // The record keeps the port the driver named, or none.
    let kept: Value = serde_json::from_slice(&std::fs::read(&record).unwrap()).unwrap();
    let published = calls_of(&vm_driver_calls(&log), "published")
        .first()
        .map(|c| c["params"]["port"].clone());
    match published {
        Some(port) => {
            assert!(publish);
            assert_eq!(kept["sandbox"]["port"], port, "{kept}");
            assert_ne!(port, 7443);
        }
        None => {
            assert!(!publish);
            assert!(kept["sandbox"].get("port").is_none(), "{kept}");
        }
    }

    // Nodes with one slot, which host A's VM holds: no room is free. Host B's start check, as
    // `run --executor vm` makes it, counts that VM as its own and lets it start.
    {
        let paths = tinfirm_runner_host::paths::Paths::new(
            dir.path().join("host/config"),
            dir.path().join("host/data"),
        );
        let (_, state) = tinfirm_runner_host::config::HostStateFile::load(&paths);
        let own = state.runner_host_id.map(|id| id.to_string());
        assert_eq!(own, Some(server.runner_host_id.to_string()));
        let state_file = dir.path().join("vms.json").to_string_lossy().into_owned();
        let vms = tinfirm_runner_host::vm::Vms::new(vm_options(
            &log,
            &[("FAKE_VM_STATE", &state_file), ("FAKE_VM_SLOTS", "1")],
        ));
        let (_, verified, held) = vms.check(1, own.as_deref()).await.unwrap();
        assert_eq!((verified.max_vms, held), (Some(0), 1));
    }
    // Host B dials the VM's bridge, as A did, and takes the run back.
    let host = start_host_at(
        &dir.path().join("host"),
        &server.url,
        restart_setup(dir.path(), publish),
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
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_that_restarted_takes_back_the_run_in_its_vm() {
    with_timeout(180, a_host_that_restarted_takes_back_the_run(false)).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_that_restarted_dials_the_port_its_record_keeps() {
    with_timeout(180, a_host_that_restarted_takes_back_the_run(true)).await;
}

// ----- cold starts (crate::pool) -----------------------------------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn without_ready_vms_each_run_creates_its_vm_and_none_is_kept() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::vm(&log, &[])).await;
        assert!(host.host.ready_vms().is_none());
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        // Until a run asks, no VM is created.
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert!(calls_of(&vm_driver_calls(&log), "create").is_empty());

        // Two runs at once: each waits for a VM of its own. (The first's transcript takes the
        // frames of both until it ends, the second's finish too if it came first.)
        let first = AssignSpec::new("bridge-env", None);
        let second = AssignSpec::new("bridge-env", None);
        conn.send(first.to_json());
        conn.send(second.to_json());
        let t = conn.until_finished(first.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(t.text().contains("the run started cold"), "{}", t.text());
        let second_id = second.run_id.to_string();
        let finished = match t
            .frames
            .iter()
            .find(|f| f["type"] == "run.finished" && f["runId"] == second_id)
        {
            Some(finished) => finished.clone(),
            None => conn.take_run("run.finished", second.run_id).await,
        };
        assert_eq!(finished["outcome"], "SUCCEEDED", "{finished}");
        // Each VM is deleted after its run, and no other is created.
        let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
        while !(deleted(&vm_driver_calls(&log), "vm-1") && deleted(&vm_driver_calls(&log), "vm-2"))
        {
            assert!(
                tokio::time::Instant::now() < deadline,
                "the VMs were not deleted"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
        let calls = vm_driver_calls(&log);
        assert_eq!(calls_of(&calls, "create").len(), 2, "{calls:#?}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// A driver that can make no VM (a node whose firewall table is missing) fails the run whose
/// create it refuses, with its cause. The host then
/// asks the driver's `verify`, finds problems and no room, and rejects every new run with the
/// cause instead of failing it, so that the runs wait in the server's queue; no VM is asked for
/// meanwhile. Once the driver is fixed, the host's own recheck finds it, and a run runs.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_driver_that_can_make_no_vm_fails_one_run_and_the_host_takes_none_until_it_can() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let broken = dir.path().join("broken");
        let broken_path = broken.to_string_lossy().into_owned();
        let mut options = vm_options(&log, &[("FAKE_VM_BROKEN", &broken_path)]);
        options.recheck = Duration::from_millis(200);
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::vm_with(options)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        std::fs::write(
            &broken,
            r#"["node node-1: the node's own firewall table (inet tinfirm_node) is not loaded"]"#,
        )
        .unwrap();

        // The run whose create fails: it fails, and says why.
        let first = AssignSpec::new("bridge-env", None);
        conn.send(first.to_json());
        let t = conn.until_finished(first.run_id).await;
        assert_eq!(t.finished()["outcome"], "FAILED", "{}", t.text());
        assert_eq!(t.finished()["failureClass"], "INFRA");
        assert!(
            t.text().contains("firewall table (inet tinfirm_node)"),
            "{}",
            t.text()
        );

        // The next runs are rejected with the cause, and no VM is asked for.
        for _ in 0..2 {
            let spec = AssignSpec::new("bridge-env", None);
            conn.send(spec.to_json());
            let rejected = conn.take_run("run.rejected", spec.run_id).await;
            assert_eq!(rejected["code"], "NO_FREE_SLOT", "{rejected}");
            let reason = rejected["reason"].as_str().unwrap();
            assert!(
                reason.contains("the VM driver can make no VM now"),
                "{reason}"
            );
            assert!(
                reason.contains("firewall table (inet tinfirm_node)"),
                "{reason}"
            );
        }
        let calls = vm_driver_calls(&log);
        assert_eq!(calls_of(&calls, "create").len(), 1, "{calls:#?}");

        // Fixed: the host's own recheck finds it without a run, and the next run runs.
        std::fs::remove_file(&broken).unwrap();
        let fixed = vm_driver_calls(&log).len();
        let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
        while calls_of(&vm_driver_calls(&log)[fixed..], "verify").is_empty() {
            assert!(
                tokio::time::Instant::now() < deadline,
                "the host did not ask the driver again"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        // Its answer is in.
        tokio::time::sleep(Duration::from_millis(500)).await;
        let spec = AssignSpec::new("bridge-env", None);
        conn.send(spec.to_json());
        let id = spec.run_id.to_string();
        let answer = conn
            .take_where("the run's accept or rejection", |f| {
                f["runId"] == id && (f["type"] == "run.accepted" || f["type"] == "run.rejected")
            })
            .await;
        assert_eq!(answer["type"], "run.accepted", "{answer}");
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// A driver whose `verify` is clean after a failed create: the failure was the run's own, and
/// the host goes on taking runs.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_create_that_fails_for_the_run_alone_keeps_the_host_taking_runs() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let options = vm_options(
            &log,
            &[(
                "FAKE_VM_CREATE_FAILS",
                "the user data is over the provider's limit",
            )],
        );
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::vm_with(options)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        for _ in 0..2 {
            let spec = AssignSpec::new("bridge-env", None);
            conn.send(spec.to_json());
            conn.take_run("run.accepted", spec.run_id).await;
            let t = conn.until_finished(spec.run_id).await;
            assert_eq!(t.finished()["outcome"], "FAILED", "{}", t.text());
            assert!(
                t.text().contains("over the provider's limit"),
                "{}",
                t.text()
            );
        }
        // Each failed create was followed by a verify, which was clean.
        let calls = vm_driver_calls(&log);
        assert_eq!(calls_of(&calls, "create").len(), 2, "{calls:#?}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// A `verify` that fails, or that has not answered yet, after a failed create tells nothing:
/// the host goes on taking runs, each failing at its create as before, and the failed run's
/// report waits at most `check_wait` for the driver.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_verify_that_fails_or_is_slow_after_a_failed_create_keeps_the_host_taking_runs() {
    with_timeout(120, async {
        for mode in ["fail", "slow 5000"] {
            let dir = tempfile::tempdir().unwrap();
            let log = dir.path().join("driver.log");
            let verify = dir.path().join("verify");
            let verify_path = verify.to_string_lossy().into_owned();
            let mut options = vm_options(
                &log,
                &[
                    ("FAKE_VM_CREATE_FAILS", "the provider refused the VM"),
                    ("FAKE_VM_VERIFY", &verify_path),
                ],
            );
            options.check_wait = Duration::from_millis(200);
            let mut server = FakeServer::start().await;
            let host = start_host(&server.url, HostSetup::vm_with(options)).await;
            let mut conn = server.accept().await;
            conn.handshake(1).await;
            std::fs::write(&verify, mode).unwrap();
            for _ in 0..2 {
                let spec = AssignSpec::new("bridge-env", None);
                let sent = std::time::Instant::now();
                conn.send(spec.to_json());
                conn.take_run("run.accepted", spec.run_id).await;
                let t = conn.until_finished(spec.run_id).await;
                assert_eq!(t.finished()["outcome"], "FAILED", "{mode}: {}", t.text());
                assert!(
                    t.text().contains("the provider refused the VM"),
                    "{mode}: {}",
                    t.text()
                );
                // Not the 5 s of the slow verify.
                assert!(
                    sent.elapsed() < Duration::from_secs(4),
                    "{mode}: {:?}",
                    sent.elapsed()
                );
            }
            host.shutdown.cancel();
            host.handle.await.unwrap().unwrap();
        }
    })
    .await;
}

/// While the driver can make no VM and its `verify` is slow, a new run is rejected within
/// `check_wait` with the cause that is known; the state ends when `verify` answers clean.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn with_a_slow_verify_a_run_is_rejected_at_once_with_the_known_cause() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let broken = dir.path().join("broken");
        let verify = dir.path().join("verify");
        let (broken_path, verify_path) = (
            broken.to_string_lossy().into_owned(),
            verify.to_string_lossy().into_owned(),
        );
        let mut options = vm_options(
            &log,
            &[
                ("FAKE_VM_BROKEN", &broken_path),
                ("FAKE_VM_VERIFY", &verify_path),
            ],
        );
        options.recheck = Duration::from_millis(200);
        options.check_wait = Duration::from_millis(300);
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::vm_with(options)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        std::fs::write(&broken, r#"["node node-1: the firewall table is missing"]"#).unwrap();
        let first = AssignSpec::new("bridge-env", None);
        conn.send(first.to_json());
        let t = conn.until_finished(first.run_id).await;
        assert_eq!(t.finished()["outcome"], "FAILED", "{}", t.text());

        // The driver's verify now takes 3 s: the run is rejected within the bound.
        std::fs::write(&verify, "slow 3000").unwrap();
        tokio::time::sleep(Duration::from_millis(400)).await;
        let spec = AssignSpec::new("bridge-env", None);
        let sent = std::time::Instant::now();
        conn.send(spec.to_json());
        let rejected = conn.take_run("run.rejected", spec.run_id).await;
        assert!(
            sent.elapsed() < Duration::from_secs(2),
            "{:?}",
            sent.elapsed()
        );
        let reason = rejected["reason"].as_str().unwrap();
        assert!(reason.contains("the firewall table is missing"), "{reason}");

        // Fixed: once a verify answers clean, a run is taken again.
        std::fs::remove_file(&broken).unwrap();
        std::fs::remove_file(&verify).unwrap();
        let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
        loop {
            let spec = AssignSpec::new("bridge-env", None);
            conn.send(spec.to_json());
            let id = spec.run_id.to_string();
            let answer = conn
                .take_where("the run's accept or rejection", |f| {
                    f["runId"] == id && (f["type"] == "run.accepted" || f["type"] == "run.rejected")
                })
                .await;
            if answer["type"] == "run.accepted" {
                let t = conn.until_finished(spec.run_id).await;
                assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "the host did not take runs again: {answer}"
            );
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// A health check is older evidence than a VM made while its `verify` ran: a failed create
/// starts a slow `verify` while the driver can make no VM; the driver is fixed and the next
/// run's VM is made; then the slow answer "no VM" comes. The host still takes runs.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_no_vm_answer_older_than_a_vm_that_was_made_does_not_stop_the_host() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let broken = dir.path().join("broken");
        let verify = dir.path().join("verify");
        let (broken_path, verify_path) = (
            broken.to_string_lossy().into_owned(),
            verify.to_string_lossy().into_owned(),
        );
        let mut options = vm_options(
            &log,
            &[
                ("FAKE_VM_BROKEN", &broken_path),
                ("FAKE_VM_VERIFY", &verify_path),
            ],
        );
        options.check_wait = Duration::from_millis(200);
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::vm_with(options)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        std::fs::write(&verify, "slow 3000").unwrap();
        std::fs::write(&broken, r#"["node node-1: the firewall table is missing"]"#).unwrap();

        // The create fails; its check's verify takes 3 s, and will answer "no VM".
        let first = AssignSpec::new("bridge-env", None);
        let sent = std::time::Instant::now();
        conn.send(first.to_json());
        let t = conn.until_finished(first.run_id).await;
        assert_eq!(t.finished()["outcome"], "FAILED", "{}", t.text());
        assert!(
            sent.elapsed() < Duration::from_secs(2),
            "{:?}",
            sent.elapsed()
        );

        // Fixed while that verify runs: the next run's VM is made.
        std::fs::remove_file(&broken).unwrap();
        let second = AssignSpec::new("bridge-env", None);
        conn.send(second.to_json());
        let t = conn.until_finished(second.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());

        // The slow answer has come by now; it is older than the VM, and changes nothing.
        let wait = Duration::from_millis(3500).saturating_sub(sent.elapsed());
        tokio::time::sleep(wait).await;
        let third = AssignSpec::new("bridge-env", None);
        conn.send(third.to_json());
        let id = third.run_id.to_string();
        let answer = conn
            .take_where("the run's accept or rejection", |f| {
                f["runId"] == id && (f["type"] == "run.accepted" || f["type"] == "run.rejected")
            })
            .await;
        assert_eq!(answer["type"], "run.accepted", "{answer}");
        let t = conn.until_finished(third.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}
