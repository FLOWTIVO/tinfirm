//! Ready VMs (ADR-0098, crate::ready_vms) with the VM executor and the fake driver of
//! `runner/crates/tinfirm-runner-host/tests/fake-vm-driver`: the host creates VMs for no run,
//! up to Tinfirm's count and the operator's ceiling; a run takes a ready one, and its
//! replacement starts at once; a run of another shape starts cold, unless the VMs run the
//! multi-runtime image (ADR-0101), which every runtime's runs take; VMs above the target, and
//! old ones, go; a VM that waited is probed again when a run claims it; and the ready VMs are
//! deleted when the host stops. The VMs here are processes on this machine
//! (`VmOptions::on_this_machine`). Part of elastic runners, under the Tinfirm Enterprise
//! licence (`ee/LICENSE`); the cold starts are tested with the core, in `vm_runs.rs`.

#[path = "../../../../runner/crates/tinfirm-runner-host/tests/common/mod.rs"]
mod common;

use std::sync::Arc;
use std::time::Duration;

use common::*;
use serde_json::Value;
use tinfirm_bridge::control::ProbeCheck;
use tinfirm_runner_host::ready_vms::{ReadyVmOptions, ReadyVms};

fn calls_of(calls: &[Value], method: &str) -> Vec<Value> {
    calls
        .iter()
        .filter(|c| c["method"] == method)
        .cloned()
        .collect()
}

fn deleted(calls: &[Value], id: &str) -> bool {
    calls_of(calls, "delete")
        .iter()
        .any(|c| c["params"]["id"] == id)
}

/// Polls `check` until it holds, for at most `seconds`.
async fn until(what: &str, seconds: u64, mut check: impl FnMut() -> bool) {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(seconds);
    while !check() {
        assert!(tokio::time::Instant::now() < deadline, "timed out: {what}");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

/// Ready VMs' options for tests: on, up to 4, and those above the target go at the next pass.
fn quick() -> ReadyVmOptions {
    ReadyVmOptions {
        max_ready: 4,
        cool_down: Duration::ZERO,
        ..ReadyVmOptions::default()
    }
}

fn pool_host(options: tinfirm_runner_host::vm::VmOptions, pool: ReadyVmOptions) -> HostSetup {
    pool_host_reprobing(options, pool, tinfirm_runner_host::pool::REPROBE_AFTER)
}

/// [`pool_host`] whose VMs that came up `reprobe_after` before their claim are probed again.
fn pool_host_reprobing(
    options: tinfirm_runner_host::vm::VmOptions,
    pool: ReadyVmOptions,
    reprobe_after: Duration,
) -> HostSetup {
    HostSetup {
        options: Some(Box::new(move |opts| {
            opts.ready_vms = pool;
            opts.vm_reprobe_after = reprobe_after;
        })),
        ..HostSetup::vm_with(options)
    }
}

fn pool_of(host: &TestHost) -> Arc<ReadyVms> {
    host.host
        .ready_vms()
        .expect("a VM host with --pool-max-ready has ready VMs")
        .clone()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_takes_a_ready_vm_and_its_replacement_starts_at_once() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, pool_host(vm_options(&log, &[]), quick())).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        // Tinfirm's count: until it sends one, no VM waits.
        tokio::time::sleep(Duration::from_millis(300)).await;
        assert!(calls_of(&vm_driver_calls(&log), "create").is_empty());
        pool.set_scheduled(1);
        until("one VM ready", 30, || pool.status().ready == 1).await;
        assert_eq!(calls_of(&vm_driver_calls(&log), "create").len(), 1);

        let spec = AssignSpec::new("bridge-env", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(
            t.text().contains("took a ready sandbox VM (vm-1)"),
            "{}",
            t.text()
        );
        // The run's VM is deleted after it, and the replacement is ready.
        until("the replacement ready", 30, || {
            pool.status().ready == 1 && deleted(&vm_driver_calls(&log), "vm-1")
        })
        .await;
        let calls = vm_driver_calls(&log);
        let creates = calls_of(&calls, "create");
        assert_eq!(creates.len(), 2, "{calls:#?}");
        // No VM was created for the run: none of them names it.
        for create in &creates {
            assert!(
                !create.to_string().contains(&spec.run_id.to_string()),
                "{create}"
            );
        }
        let status = pool.status();
        assert_eq!(
            (status.served_ready, status.served_cold),
            (1, 0),
            "{status:?}"
        );
        assert!(status.cold_start_p90.is_some());

        // A host that stops deletes its ready VMs.
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
        assert!(deleted(&vm_driver_calls(&log), "vm-2"));
    })
    .await;
}

const RESTART_DIR: &str = "TINFIRM_TEST_POOL_RESTART_DIR";
const RESTART_URL: &str = "TINFIRM_TEST_POOL_RESTART_URL";

/// The hosts of the ready VMs' restart test: the fake driver keeps its VMs in a file, so that
/// they outlive the host that is killed.
fn restart_pool_host(dir: &std::path::Path) -> HostSetup {
    let state = dir.join("vms.json").to_string_lossy().into_owned();
    pool_host(
        vm_options(
            &dir.join("driver.log"),
            &[("FAKE_VM_STATE", state.as_str())],
        ),
        quick(),
    )
}

/// Host A of the ready VMs' restart test, in a process of its own that the test kills as a
/// crash would (SIGKILL), with one ready VM: run only by that test.
#[test]
#[ignore = "host A of the ready VMs' restart test, which starts it"]
fn pool_restart_test_host_a() {
    let (Ok(dir), Ok(url)) = (std::env::var(RESTART_DIR), std::env::var(RESTART_URL)) else {
        return;
    };
    let dir = std::path::PathBuf::from(dir);
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let host = start_host_at(&dir.join("host"), &url, restart_pool_host(&dir)).await;
            // Tinfirm's count, again and again: the server's welcome, which this test's server
            // sends with no count, sets it to none.
            loop {
                pool_of(&host).set_scheduled(1);
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
        });
}

/// The VMs of the fake driver's state file that are running.
fn running_vms(dir: &std::path::Path) -> Vec<String> {
    let Ok(bytes) = std::fs::read(dir.join("vms.json")) else {
        return Vec::new();
    };
    let state: Vec<Value> = serde_json::from_slice(&bytes).unwrap_or_default();
    state
        .iter()
        .filter(|vm| vm["state"] == "running")
        .filter_map(|vm| vm["id"].as_str().map(str::to_string))
        .collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn after_a_host_restart_its_ready_vms_go_and_a_run_takes_a_new_one_through_its_front() {
    with_timeout(180, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let mut command = std::process::Command::new(std::env::current_exe().unwrap());
        command
            .args([
                "pool_restart_test_host_a",
                "--exact",
                "--ignored",
                "--nocapture",
            ])
            .env(RESTART_DIR, dir.path())
            .env(RESTART_URL, &server.url)
            .stdout(std::process::Stdio::null());
        let mut host_a = HostProcess::spawn(command);
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        // Host A's ready VM, with its front, which holds its bridge.
        until("host A's ready VM", 60, || {
            running_vms(dir.path()).len() == 1 && fronts_of(host_a.id()).len() == 1
        })
        .await;
        let old = running_vms(dir.path()).remove(0);
        host_a.crash();
        drop(conn);

        // Host B holds no record of the ready VM, which no run had: it deletes it, and makes a
        // new one, whose new front takes its bridge; the next run takes that one.
        let host = start_host_at(
            &dir.path().join("host"),
            &server.url,
            restart_pool_host(dir.path()),
        )
        .await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        pool.set_scheduled(1);
        until("host A's ready VM deleted and a new one ready", 60, || {
            deleted(&vm_driver_calls(&log), &old) && pool.status().ready == 1
        })
        .await;
        let spec = AssignSpec::new("bridge-env", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(t.text().contains("took a ready sandbox VM"), "{}", t.text());
        assert!(!t.text().contains(&format!("({old})")), "{}", t.text());
        assert!(host.host.started_bridge().await.is_none());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_of_another_runtime_starts_cold_and_the_ready_vm_makes_room() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            max_concurrent_runs: 1,
            ..pool_host(vm_options(&log, &[]), quick())
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        pool.set_scheduled(1);
        until("one VM ready", 30, || pool.status().ready == 1).await;

        // The pool's VMs run Claude Code; a Codex run needs a VM of its own image.
        let mut spec = AssignSpec::new("bridge-env", None);
        spec.runtime = "CODEX";
        spec.credential = "openai-test";
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(t.text().contains("the run started cold"), "{}", t.text());
        let calls = vm_driver_calls(&log);
        let creates = calls_of(&calls, "create");
        let image = |i: usize| {
            creates[i]["params"]["userData"]["image"]
                .as_str()
                .unwrap()
                .to_string()
        };
        assert!(image(0).contains("claude"), "{}", image(0));
        assert!(image(1).contains("codex"), "{}", image(1));
        // With one slot, the ready VM went before the run's own was created.
        let order: Vec<String> = calls
            .iter()
            .filter(|c| {
                c["method"] == "create" || (c["method"] == "delete" && c["params"]["id"] == "vm-1")
            })
            .map(|c| c["method"].as_str().unwrap().to_string())
            .collect();
        assert_eq!(&order[..3], ["create", "delete", "create"], "{calls:#?}");
        // After the run, the pool is back to its count.
        until("the pool refilled", 30, || pool.status().ready == 1).await;
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// The most VMs the host held at once, from the driver's calls in their order. The fake driver
/// names its VMs `vm-1`, `vm-2` and so on as it creates them, and the host holds a VM from its
/// `create` until it asks for its `delete`.
fn most_vms_held(calls: &[Value]) -> usize {
    let mut created = 0;
    let mut held = std::collections::BTreeSet::new();
    let mut most = 0;
    for call in calls {
        match call["method"].as_str() {
            Some("create") => {
                created += 1;
                held.insert(format!("vm-{created}"));
            }
            Some("delete") => {
                if let Some(id) = call["params"]["id"].as_str() {
                    held.remove(id);
                }
            }
            _ => {}
        }
        most = most.max(held.len());
    }
    most
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn runs_of_another_runtime_never_take_the_host_above_its_pool_size() {
    with_timeout(240, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        // The ready VMs are of Codex's image, so that two runs of Claude Code can go at once
        // (the tests' Claude Code credential takes two runs, the OpenAI one a single run).
        let ready = ReadyVmOptions {
            runtime: tinfirm_protocol::Runtime::Codex,
            ..quick()
        };
        let setup = HostSetup {
            max_concurrent_runs: 3,
            ..pool_host(vm_options(&log, &[]), ready)
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        // The ready VMs fill the pool: three.
        pool.set_scheduled(3);
        until("three VMs ready", 60, || pool.status().ready == 3).await;
        assert_eq!(most_vms_held(&vm_driver_calls(&log)), 3);

        // Two Claude Code runs at once. Each needs a VM of its own image and starts cold,
        // while the pool's own passes go on beside them. (The first's transcript takes the
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
        // After the runs the pool is back to its count.
        until("the pool refilled", 60, || pool.status().ready == 3).await;
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();

        let calls = vm_driver_calls(&log);
        let images: Vec<String> = calls_of(&calls, "create")
            .iter()
            .map(|c| {
                c["params"]["userData"]["image"]
                    .as_str()
                    .unwrap()
                    .to_string()
            })
            .collect();
        assert_eq!(
            images.iter().filter(|i| i.contains("claude")).count(),
            2,
            "{images:?}"
        );
        // Never more than the three VMs of the host's pool size, at any call of the driver.
        assert_eq!(most_vms_held(&calls), 3, "{calls:#?}");
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn with_the_multi_runtime_image_a_run_of_any_runtime_takes_a_ready_vm() {
    use std::os::unix::fs::PermissionsExt;
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        // The adapters the host names (ADR-0101), each the fake agent saying which it is.
        let bin = dir.path().join("bin");
        std::fs::create_dir(&bin).unwrap();
        for adapter in ["claude-agent-acp", "codex-acp"] {
            let script = bin.join(adapter);
            std::fs::write(
                &script,
                format!(
                    "#!/bin/sh\nFAKE_ADAPTER={adapter} exec node '{}' \"$@\"\n",
                    fake_agent_script().display()
                ),
            )
            .unwrap();
            std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let path = format!(
            "{}:{}",
            bin.display(),
            std::env::var("PATH").unwrap_or_default()
        );
        let mut server = FakeServer::start().await;
        let pool_options = ReadyVmOptions {
            multi_runtime: true,
            ..quick()
        };
        let setup = HostSetup {
            max_concurrent_runs: 2,
            ..pool_host(vm_options(&log, &[("PATH", &path)]), pool_options)
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        pool.set_scheduled(1);
        until("one VM ready", 30, || pool.status().ready == 1).await;

        // A Codex run and then a Claude Code run each take a ready VM of the one shape, and the
        // host starts the run's own adapter in it.
        for (runtime, credential, adapter, served) in [
            ("CODEX", "openai-test", "codex-acp", 1),
            ("CLAUDE_CODE", "claude-test", "claude-agent-acp", 2),
        ] {
            until("a VM ready", 30, || pool.status().ready == 1).await;
            let mut spec = AssignSpec::new("bridge-env", None);
            spec.runtime = runtime;
            spec.credential = credential;
            conn.send(spec.to_json());
            let t = conn.until_finished(spec.run_id).await;
            assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
            assert!(
                t.text().contains(&format!("adapter={adapter}")),
                "{}",
                t.text()
            );
            assert!(!t.text().contains("started cold"), "{}", t.text());
            assert_eq!(pool.status().served_ready, served, "{:?}", pool.status());
        }
        let creates = calls_of(&vm_driver_calls(&log), "create");
        assert!(creates.len() >= 2);
        for create in &creates {
            assert_eq!(
                create["params"]["userData"]["image"],
                tinfirm_runner_host::images::MULTI_IMAGE
            );
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_ceiling_bounds_the_count_and_vms_above_the_target_or_too_old_go() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let pool_options = ReadyVmOptions {
            max_ready: 2,
            ..quick()
        };
        let host = start_host(&server.url, pool_host(vm_options(&log, &[]), pool_options)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        // Tinfirm asks for 3; the operator's ceiling is 2.
        pool.set_scheduled(3);
        until("two VMs ready", 30, || pool.status().ready == 2).await;
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert_eq!(calls_of(&vm_driver_calls(&log), "create").len(), 2);
        assert_eq!(pool.status().target, 2);
        // A lower count: the extra VM goes at once (no cool-down here).
        pool.set_scheduled(1);
        until("one VM left", 30, || {
            pool.status().ready == 1 && calls_of(&vm_driver_calls(&log), "delete").len() == 1
        })
        .await;
        // None: every ready VM goes. A VM leaves the pool before the driver's delete is logged, so
        // wait for both, as for the one above.
        pool.set_scheduled(0);
        until("none left", 30, || {
            pool.status().ready == 0 && calls_of(&vm_driver_calls(&log), "delete").len() == 2
        })
        .await;
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert_eq!(calls_of(&vm_driver_calls(&log), "delete").len(), 2);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_ready_vm_older_than_its_max_age_is_replaced() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let pool_options = ReadyVmOptions {
            max_age: Duration::from_secs(2),
            ..quick()
        };
        let host = start_host(&server.url, pool_host(vm_options(&log, &[]), pool_options)).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        pool.set_scheduled(1);
        until("one VM ready", 30, || pool.status().ready == 1).await;
        until("the old VM replaced", 30, || {
            let calls = vm_driver_calls(&log);
            deleted(&calls, "vm-1")
                && calls_of(&calls, "create").len() >= 2
                && pool.status().ready == 1
        })
        .await;
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_vm_that_waited_is_probed_again_when_a_run_claims_it() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        // Closed while the VM starts; open later, as a firewall that changed would be.
        let drift = {
            let free = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            free.local_addr().unwrap().to_string()
        };
        let mut options = vm_options(&log, &[]);
        options.probe = vec![ProbeCheck::Tcp {
            addr: drift.clone(),
        }];
        let mut server = FakeServer::start().await;
        let host = start_host(
            &server.url,
            pool_host_reprobing(options, quick(), Duration::from_millis(500)),
        )
        .await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        pool.set_scheduled(1);
        until("one VM ready", 30, || pool.status().ready == 1).await;
        let open = std::net::TcpListener::bind(&drift).unwrap();
        tokio::time::sleep(Duration::from_millis(700)).await;

        let spec = AssignSpec::new("bridge-env", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        assert!(
            t.text().contains("the sandbox reached what it must not"),
            "{}",
            t.text()
        );
        assert!(t.events_of("AGENT_MESSAGE").is_empty());
        until("the VM deleted", 30, || {
            deleted(&vm_driver_calls(&log), "vm-1")
        })
        .await;
        drop(open);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// Echoes the first chunk of each connection; returns its port.
async fn echo() -> u16 {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
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
async fn a_ready_vms_egress_proxy_is_reached_only_through_the_tunnel_of_the_run_that_claims_it() {
    use tinfirm_runner_host::egress_proxy::{EgressSettings, Rule};
    with_timeout(120, async {
        let port = echo().await;
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            egress: Some(EgressSettings {
                allow: vec![Rule::parse(&format!("localhost:{port}")).unwrap()],
                allow_private: false,
                allow_loopback: true,
            }),
            ..pool_host(vm_options(&log, &[]), quick())
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        pool.set_scheduled(1);
        until("one VM ready", 30, || pool.status().ready == 1).await;

        // The VM's container starts with its egress proxy's URL: the VM's front serves it,
        // through a tunnel on the VM's own loopback at the egress proxy's fixed port, which
        // only the run that claims the VM opens. While the VM waits, nothing listens there,
        // and this host binds no listener for it (its unbound listener refusing every
        // destination is the egress proxy's own test).
        let calls = vm_driver_calls(&log);
        let env = &calls_of(&calls, "create")[0]["params"]["userData"]["env"];
        let proxy_url = env["HTTPS_PROXY"]
            .as_str()
            .expect("the proxy in the VM's settings")
            .to_string();
        let url = url::Url::parse(&proxy_url).unwrap();
        let listener_port = url.port().unwrap();
        assert_eq!(listener_port, 18_763, "{proxy_url}");
        let vm_side = format!("{}:{listener_port}", url.host_str().unwrap());
        assert!(tokio::net::TcpStream::connect(&vm_side).await.is_err());
        let host_side = format!("127.0.0.2:{listener_port}");
        assert!(tokio::net::TcpStream::connect(&host_side).await.is_err());

        // The run that claims the VM binds its rules, and reaches the destination through its
        // tunnel.
        let mut spec = AssignSpec::new("egress", None);
        spec.prompt_extra = format!(" [target:localhost:{port}]");
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let text = t.agent_text();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{text}");
        assert!(text.contains(&format!(":{listener_port}")), "{text}");
        assert!(text.contains("echo: hello through the tunnel"), "{text}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_vm_that_cannot_be_created_pauses_the_pools_own_creations_but_not_a_runs() {
    with_timeout(120, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let mut options = vm_options(&log, &[("FAKE_VM_TEMPLATE", "failing")]);
        options.start_timeout = Duration::from_secs(5);
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, pool_host(options, quick())).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        pool.set_scheduled(1);
        until("the first create", 30, || {
            !calls_of(&vm_driver_calls(&log), "create").is_empty()
        })
        .await;
        // Several passes later the pool has not tried again (it waits 30 s after a failure).
        tokio::time::sleep(Duration::from_secs(6)).await;
        assert_eq!(calls_of(&vm_driver_calls(&log), "create").len(), 1);
        // A run still gets its own try, and fails with the provider's answer.
        let spec = AssignSpec::new("bridge-env", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["failureClass"], "INFRA", "{}", t.text());
        assert!(
            t.text().contains("the provider could not start the VM"),
            "{}",
            t.text()
        );
        assert_eq!(calls_of(&vm_driver_calls(&log), "create").len(), 2);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

// ----- Tinfirm's count (ADR-0099) ----------------------------------------------------------

fn v2_pool_host(pool: ReadyVmOptions) -> HostSetup {
    HostSetup {
        protocol_version: 2,
        max_concurrent_runs: 3,
        ..pool_host(vm_options(std::path::Path::new("/dev/null"), &[]), pool)
    }
}

fn pool_config(version: u64, pool: Value) -> Value {
    serde_json::json!({"type": "host.config", "version": version, "credentials": [], "pool": pool})
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn tinfirms_count_comes_in_host_config_and_the_host_reports_its_pool() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(
            &server.url,
            v2_pool_host(ReadyVmOptions {
                max_ready: 2,
                ..quick()
            }),
        )
        .await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(1).await;
        assert!(
            hello["features"]
                .as_array()
                .unwrap()
                .iter()
                .any(|f| f == "sandbox.pool"),
            "{hello}"
        );
        assert_eq!(hello["pool"]["maxReady"], 2);
        let pool = pool_of(&host);

        // Tinfirm asks for 3; the operator's ceiling is 2.
        let applied = conn
            .apply(pool_config(1, serde_json::json!({"readyTarget": 3})))
            .await;
        assert_eq!(
            applied["pool"],
            serde_json::json!({"readyTarget": 2, "maxReadyCeiling": 2})
        );
        until("two VMs ready", 30, || pool.status().ready == 2).await;
        // The host tells the server.
        let status = tokio::time::timeout(Duration::from_secs(30), async {
            loop {
                let status = conn.take("host.pool.status").await;
                if status["ready"] == 2 {
                    return status;
                }
            }
        })
        .await
        .expect("a host.pool.status with two ready VMs");
        assert_eq!(status["busy"], 0);

        // None any more.
        let applied = conn
            .apply(pool_config(2, serde_json::json!({"readyTarget": 0})))
            .await;
        assert_eq!(applied["pool"]["readyTarget"], 0);
        until("no VM ready", 30, || pool.status().ready == 0).await;
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_window_fills_early_and_its_end_is_followed_without_the_server() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(
            &server.url,
            v2_pool_host(ReadyVmOptions {
                lead_time_max: Duration::from_secs(120),
                ..quick()
            }),
        )
        .await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        let at = |seconds: i64| {
            (chrono::Utc::now() + chrono::Duration::seconds(seconds))
                .to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
        };
        // A window opens in a minute: within the lead time (no cold start measured yet: 5
        // minutes, at most the 2 of this host), so it fills now.
        conn.apply(pool_config(
            1,
            serde_json::json!({"readyTarget": 0, "next": {"readyTarget": 1, "at": at(60)}}),
        ))
        .await;
        until("the window filled early", 30, || pool.status().ready == 1).await;
        // It ends in a second: the host follows that by itself.
        conn.apply(pool_config(
            2,
            serde_json::json!({"readyTarget": 1, "next": {"readyTarget": 0, "at": at(1)}}),
        ))
        .await;
        until("the window ended", 30, || pool.status().ready == 0).await;
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// A run's VM whose delete fails while its node does not answer (a node that reboots) is deleted
/// later by the host on its own, without a restart, once the node answers again: its slot is
/// free again. Nothing else is deleted meanwhile: neither the VM of a run that still runs nor a
/// ready VM.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_vm_whose_delete_failed_while_its_node_was_down_is_deleted_once_it_answers() {
    with_timeout(180, async {
        let dir = tempfile::tempdir().unwrap();
        let log = dir.path().join("driver.log");
        let down = dir.path().join("down");
        let down_env = down.to_string_lossy().into_owned();
        let mut options = vm_options(&log, &[("FAKE_VM_DOWN", &down_env)]);
        options.delete_retry = Duration::from_millis(300);
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, pool_host(options, quick())).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let pool = pool_of(&host);
        pool.set_scheduled(1);
        until("one VM ready", 30, || pool.status().ready == 1).await;

        // A run that goes on, in vm-1; its replacement, vm-2, is ready.
        let live = AssignSpec::new("gate", None);
        conn.send(live.to_json());
        conn.wait_running(live.run_id).await;
        until("the replacement ready", 30, || pool.status().ready == 1).await;

        // A run that ends, in vm-2, while the node does not answer: its delete fails.
        std::fs::write(&down, "").unwrap();
        let ended = AssignSpec::new("bridge-env", None);
        conn.send(ended.to_json());
        let t = conn.until_finished(ended.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(
            t.text().contains("took a ready sandbox VM (vm-2)"),
            "{}",
            t.text()
        );
        let calls = vm_driver_calls(&log);
        let gone = |calls: &[Value], id: &str| {
            calls_of(calls, "deleted")
                .iter()
                .any(|c| c["params"]["id"] == id)
        };
        assert!(
            deleted(&calls, "vm-2") && !gone(&calls, "vm-2"),
            "{calls:#?}"
        );

        // The node answers again: the host deletes vm-2 on its own.
        std::fs::remove_file(&down).unwrap();
        until("vm-2 deleted after the node is back", 30, || {
            gone(&vm_driver_calls(&log), "vm-2")
        })
        .await;
        until("vm-3 ready", 30, || pool.status().ready == 1).await;
        let calls = vm_driver_calls(&log);
        // Neither the live run's VM nor the ready one was touched.
        assert!(!deleted(&calls, "vm-1"), "{calls:#?}");
        assert!(!deleted(&calls, "vm-3"), "{calls:#?}");
        // And vm-2 is not tried again once it is deleted.
        let tries = calls_of(&calls, "delete")
            .iter()
            .filter(|c| c["params"]["id"] == "vm-2")
            .count();
        tokio::time::sleep(Duration::from_secs(2)).await;
        let calls = vm_driver_calls(&log);
        assert_eq!(
            calls_of(&calls, "delete")
                .iter()
                .filter(|c| c["params"]["id"] == "vm-2")
                .count(),
            tries,
            "{calls:#?}"
        );

        // The live run ends as usual, and its VM goes.
        release(&host.paths, live.run_id);
        let t = conn.until_finished(live.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(gone(&vm_driver_calls(&log), "vm-1"));
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}
