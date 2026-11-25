//! DigitalOcean's VM driver (`vm-driver digitalocean`, ADR-0096) and the feature
//! `sandbox.elastic` that a host with one of this binary's paid drivers lists (ADR-0114). Part
//! of elastic runners, under the Tinfirm Enterprise licence (`ee/LICENSE`).

#[path = "../../../../runner/crates/tinfirm-runner-host/tests/common/mod.rs"]
mod common;

use std::time::Duration;

use common::*;
use serde_json::json;

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_builtin_digitalocean_driver_speaks_the_protocol() {
    use tinfirm_runner_host::vm::{Builtin, Driver, DriverCommand};
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
        builtin: Some(Builtin::Digitalocean),
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

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_with_a_paid_driver_lists_it_and_a_community_server_stops_the_host() {
    with_timeout(60, async {
        let dir = tempfile::tempdir().unwrap();
        // The fake driver, standing in for one of this binary's paid drivers.
        let mut options = vm_options(&dir.path().join("driver.log"), &[]);
        options.driver.builtin = Some(tinfirm_runner_host::vm::Builtin::Digitalocean);
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            protocol_version: 2,
            ..HostSetup::vm_with(options)
        };
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.expect("hello").await;
        let features = hello["features"].as_array().unwrap();
        assert!(features.contains(&json!("sandbox.elastic")), "{hello}");
        assert!(features.contains(&json!("sandbox.bridge")), "{hello}");
        // A provider's VM per run: the host reports `machine`, and runs no machine of its own
        // for its VMs (ADR-0113).
        assert_eq!(hello["isolationTiers"], json!(["machine"]), "{hello}");
        assert_eq!(hello["hardened"], "none", "{hello}");
        assert!(hello.get("isolationStated").is_none(), "{hello}");
        assert!(!features.contains(&json!("host.isolation")), "{hello}");
        // A Community server (ADR-0114): an error before the welcome, then 1008.
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
