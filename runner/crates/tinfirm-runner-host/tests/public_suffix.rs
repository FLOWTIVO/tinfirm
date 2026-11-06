//! `psl.update` (protocol v2, ADR-0044) against the fake server: the host verifies, uses and
//! stores the list, reports its version in `hello`, and keeps its list when a frame is invalid.

mod common;

use std::io::Write;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use common::*;
use serde_json::{Value, json};
use tinfirm_runner_host::egress_proxy::Rule;
use tinfirm_runner_host::host::Host;

/// The server's bundled list when this checkout has it, else a small list of the same shape.
fn list_bytes() -> Vec<u8> {
    let bundled = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../server/src/main/resources/psl/public_suffix_list.dat");
    if let Ok(bytes) = std::fs::read(bundled) {
        return bytes;
    }
    let mut text =
        String::from("// test list\ncom\nco.uk\ngithub.io\ncom.sg\nasn.au\n*.ck\n!www.ck\n");
    for i in 0..1100 {
        text.push_str(&format!("filler{i}.example\n"));
    }
    text.into_bytes()
}

fn frame(bytes: &[u8], sha256: &str) -> Value {
    let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    encoder.write_all(bytes).unwrap();
    json!({
        "type": "psl.update",
        "sha256": sha256,
        "fetchedAt": "2026-10-07T06:00:00.000Z",
        "ruleCount": 9000,
        "content": STANDARD.encode(encoder.finish().unwrap()),
    })
}

fn sha256_of(bytes: &[u8]) -> String {
    sha256_hex(bytes)
}

async fn wait_for(host: &Host, sha256: &str) {
    for _ in 0..100 {
        if host.public_suffix().sha256().as_deref() == Some(sha256) {
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("the host never used the list {sha256}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_psl_update_is_used_stored_and_reported_in_the_next_hello() {
    with_timeout(60, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        assert_eq!(hello["protocolVersion"], 2);
        assert!(
            hello.get("publicSuffixList").is_none(),
            "none held yet: {hello}"
        );
        assert_schema_valid_version(&hello, 2);
        let rule = Rule::parse("*.github.io").unwrap();
        assert!(
            host.host.public_suffix_wildcard_base(&rule).is_none(),
            "no list, no check"
        );

        let bytes = list_bytes();
        let sha = sha256_of(&bytes);
        let update = frame(&bytes, &sha);
        assert_schema_valid_version(&update, 2);
        conn.send(update);
        wait_for(&host.host, &sha).await;
        assert_eq!(
            host.host.public_suffix_wildcard_base(&rule),
            Some("github.io")
        );
        let tenant = Rule::parse("*.tenant.github.io").unwrap();
        assert!(host.host.public_suffix_wildcard_base(&tenant).is_none());
        let stored = host.paths.data_dir.join("public_suffix_list.dat");
        for _ in 0..100 {
            if stored.exists() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert_eq!(std::fs::read(&stored).unwrap(), bytes);

        // Invalid frames: a wrong SHA-256, bad gzip, not a list. The list stays, no answer.
        let mut wrong = frame(&bytes, &sha256_of(b"other"));
        conn.send(wrong.clone());
        wrong["sha256"] = json!(sha);
        wrong["content"] = json!(STANDARD.encode(b"not gzip"));
        conn.send(wrong);
        let tiny = b"com\n";
        conn.send(frame(tiny, &sha256_of(tiny)));
        // The connection is alive and nothing was answered: a heartbeat-free round trip.
        let applied = conn.apply(host_config(1, Some(1), &[])).await;
        assert_eq!(applied["version"], 1);
        assert_eq!(
            host.host.public_suffix().sha256().as_deref(),
            Some(sha.as_str())
        );
        assert_eq!(std::fs::read(&stored).unwrap(), bytes);
        assert!(conn.frames.iter().all(|f| f["type"] != "error"));

        // After a reconnect the hello names the list.
        conn.kill();
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        assert_eq!(hello["publicSuffixList"], json!({"sha256": sha}));
        assert_schema_valid_version(&hello, 2);
        host.shutdown.cancel();
    })
    .await;
}
