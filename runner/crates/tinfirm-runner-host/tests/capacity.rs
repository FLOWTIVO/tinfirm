//! Capacity settings and drain (ADR-0016; docs/archive/v1/CONVENTIONS.md §15.4 steps 1 and 13, §15.7) against
//! the fake server, with the local test executor. Most runs use the fake agent's `gate`
//! scenario: the run goes on until the test releases it, and then succeeds.

mod common;

use std::time::Duration;

use chrono::Utc;
use common::*;
use serde_json::{Value, json};
use tinfirm_protocol::CredentialKind;
use tinfirm_runner_host::credentials::{Credential, CredentialStore};
use uuid::Uuid;

/// A run that waits at the gate until [`release`].
fn gate() -> AssignSpec<'static> {
    AssignSpec::new("gate", None)
}

/// Sends the run and waits until its agent is running.
async fn start(conn: &mut Conn, spec: &AssignSpec<'_>) {
    conn.send(spec.to_json());
    conn.take_run("run.accepted", spec.run_id).await;
    conn.wait_running(spec.run_id).await;
}

/// Sends the run and returns its rejection.
async fn rejected(conn: &mut Conn, spec: &AssignSpec<'_>) -> Value {
    conn.send(spec.to_json());
    conn.take_run("run.rejected", spec.run_id).await
}

/// Releases a `gate` run and returns its `run.finished`.
async fn release_and_finish(conn: &mut Conn, host: &TestHost, run_id: Uuid) -> Value {
    release(&host.paths, run_id);
    conn.take_run("run.finished", run_id).await
}

fn pools(expected: &[(&str, u64)]) -> Vec<(String, u64)> {
    expected.iter().map(|(l, n)| (l.to_string(), *n)).collect()
}

fn applied_frames(conn: &Conn) -> Vec<&Value> {
    conn.frames
        .iter()
        .filter(|f| f["type"] == "host.config.applied")
        .collect()
}

/// A Codex ChatGPT login with the pool its file names.
fn codex_credential(label: &str, pool: u32) -> Credential {
    Credential {
        label: label.into(),
        kind: CredentialKind::CodexChatgptAuth,
        pool_size: pool,
        created_at: Utc::now(),
        secret: None,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn hello_reports_the_ceilings_and_host_config_sets_the_effective_values() {
    with_timeout(60, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.max_concurrent_runs = 3;
        setup.credentials = vec![codex_credential("codex-test", 4)];
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;

        // hello: the ceilings.
        assert_eq!(hello["maxConcurrentRuns"], 3);
        let ceilings: Vec<(String, u64)> = hello["credentials"]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| {
                (
                    c["label"].as_str().unwrap().to_string(),
                    c["poolSize"].as_u64().unwrap(),
                )
            })
            .collect();
        let at_ceiling = pools(&[("claude-test", 2), ("codex-test", 4), ("openai-test", 1)]);
        assert_eq!(ceilings, at_ceiling);
        // Without desired values, the ceilings are the effective values.
        let first = applied_frames(&conn)[0].clone();
        assert_eq!(first["draining"], false);
        assert_eq!(first["maxConcurrentRuns"], 3);
        assert_eq!(applied_pools(&first), at_ceiling);

        // Desired values above a ceiling apply up to it; unknown labels are ignored.
        let applied = conn
            .apply(host_config(
                1,
                Some(9),
                &[("claude-test", 7), ("codex-test", 5), ("ghost", 3)],
            ))
            .await;
        assert_eq!(applied["version"], 1);
        assert_eq!(applied["draining"], false);
        assert_eq!(applied["maxConcurrentRuns"], 3);
        assert_eq!(applied["maxConcurrentRunsCeiling"], 3);
        assert_eq!(applied_pools(&applied), at_ceiling);
        assert_applied_matches_hello(&hello, &applied);
        assert_eq!(host.host.capacity().pool_size("codex-test"), Some(4));

        // Lower values count. Every message is the full state: a credential without an
        // entry goes back to its ceiling.
        let applied = conn
            .apply(host_config(
                2,
                Some(1),
                &[("claude-test", 1), ("codex-test", 2)],
            ))
            .await;
        assert_eq!(applied["maxConcurrentRuns"], 1);
        assert_eq!(
            applied_pools(&applied),
            pools(&[("claude-test", 1), ("codex-test", 2), ("openai-test", 1)])
        );
        let applied = conn.apply(host_config(3, None, &[])).await;
        assert_eq!(applied["maxConcurrentRuns"], 3);
        assert_eq!(applied_pools(&applied), at_ceiling);

        // An invalid host.config is answered with an error and changes nothing.
        conn.send(host_config(4, Some(0), &[]));
        let error = conn.take("error").await;
        assert_eq!(error["code"], "BAD_MESSAGE");
        conn.send(host_config(
            4,
            None,
            &[("claude-test", 1), ("claude-test", 2)],
        ));
        let error = conn.take("error").await;
        assert_eq!(error["code"], "BAD_MESSAGE");
        // The same version again is applied and answered again.
        let applied = conn.apply(host_config(3, None, &[])).await;
        assert_eq!(applied["version"], 3);
        assert_eq!(applied["maxConcurrentRuns"], 3);
        let versions: Vec<&Value> = applied_frames(&conn)
            .iter()
            .map(|f| &f["version"])
            .collect();
        assert_eq!(
            versions,
            vec![&json!(0), &json!(1), &json!(2), &json!(3), &json!(3)]
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn more_slots_take_runs_at_once_and_fewer_never_stop_running_ones() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        // Ceiling 2; claude-test has a pool of 2.
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        let (hello, applied) = conn
            .handshake_with(1, Some(host_config(1, Some(1), &[])))
            .await;
        let applied = applied.unwrap();
        assert_eq!(hello["maxConcurrentRuns"], 2);
        assert_eq!(applied["maxConcurrentRuns"], 1);
        assert_eq!(applied["maxConcurrentRunsCeiling"], 2);

        let a = gate();
        start(&mut conn, &a).await;
        let b = gate();
        let r = rejected(&mut conn, &b).await;
        assert_eq!(r["code"], "NO_FREE_SLOT");
        assert_eq!(r["reason"], "no free slot (1 of 1 runs active)");

        // More slots: the next run.assign takes one.
        let applied = conn.apply(host_config(2, None, &[])).await;
        assert_eq!(applied["maxConcurrentRuns"], 2);
        start(&mut conn, &b).await;

        // Fewer slots than active runs: both keep running, new ones wait.
        let applied = conn.apply(host_config(3, Some(1), &[])).await;
        assert_eq!(applied["maxConcurrentRuns"], 1);
        let c = gate();
        let r = rejected(&mut conn, &c).await;
        assert_eq!(r["code"], "NO_FREE_SLOT");
        assert_eq!(r["reason"], "no free slot (2 of 1 runs active)");
        let beat = conn
            .take_where("a heartbeat after the shrink", |f| {
                f["type"] == "run.heartbeat" && f["runs"].as_array().unwrap().len() == 2
            })
            .await;
        let beating: Vec<&Value> = beat["runs"]
            .as_array()
            .unwrap()
            .iter()
            .map(|r| &r["runId"])
            .collect();
        assert!(beating.contains(&&json!(a.run_id)) && beating.contains(&&json!(b.run_id)));
        assert_eq!(host.host.active_run_count(), 2);

        // The runs end on their own; only then is there room again.
        let finished = release_and_finish(&mut conn, &host, a.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        let r = rejected(&mut conn, &c).await;
        assert_eq!(r["reason"], "no free slot (1 of 1 runs active)");
        let finished = release_and_finish(&mut conn, &host, b.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        start(&mut conn, &c).await;
        let finished = release_and_finish(&mut conn, &host, c.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        for spec in [&a, &b, &c] {
            let t = run_frames(&conn, spec.run_id);
            t.assert_contiguous_seq();
            assert!(!t.statuses().contains(&"cancelling".to_string()));
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_with_model_access_takes_a_slot_of_the_host_but_of_no_pool() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.max_concurrent_runs = 3;
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake_with(5, Some(host_config(1, None, &[("claude-test", 1)])))
            .await;

        // The pool of claude-test is full with one run.
        let a = gate();
        start(&mut conn, &a).await;
        let b = gate();
        let r = rejected(&mut conn, &b).await;
        assert_eq!(r["code"], "POOL_FULL");

        // Runs with model access need no credential of the host: they take host slots only.
        let m1 = gate().with_model_access("anthropic");
        start(&mut conn, &m1).await;
        let m2 = gate().with_model_access("anthropic");
        start(&mut conn, &m2).await;
        let m3 = gate().with_model_access("anthropic");
        let r = rejected(&mut conn, &m3).await;
        assert_eq!(r["code"], "NO_FREE_SLOT");
        assert_eq!(r["reason"], "no free slot (3 of 3 runs active)");

        // They never hold the pool: when the host credential's run ends, the pool is free
        // again for the next run of it.
        let finished = release_and_finish(&mut conn, &host, a.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        start(&mut conn, &b).await;
        for spec in [&m1, &m2, &b] {
            let finished = release_and_finish(&mut conn, &host, spec.run_id).await;
            assert_eq!(finished["outcome"], "SUCCEEDED", "{finished}");
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn credential_pools_follow_host_config_up_to_their_ceiling() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.max_concurrent_runs = 4;
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let (_, applied) = conn
            .handshake_with(5, Some(host_config(1, None, &[("claude-test", 1)])))
            .await;
        assert_eq!(
            applied_pools(&applied.unwrap()),
            pools(&[("claude-test", 1), ("openai-test", 1)])
        );

        let a = gate();
        start(&mut conn, &a).await;
        let b = gate();
        let r = rejected(&mut conn, &b).await;
        assert_eq!(r["code"], "POOL_FULL");
        assert_eq!(
            r["reason"],
            "credential `claude-test` has no free slot (1 of 1 in use)"
        );

        // A desired pool above the ceiling (2) applies up to it.
        let applied = conn
            .apply(host_config(2, None, &[("claude-test", 5)]))
            .await;
        assert_eq!(
            applied_pools(&applied),
            pools(&[("claude-test", 2), ("openai-test", 1)])
        );
        start(&mut conn, &b).await;
        let c = gate();
        let r = rejected(&mut conn, &c).await;
        assert_eq!(r["code"], "POOL_FULL");
        assert_eq!(
            r["reason"],
            "credential `claude-test` has no free slot (2 of 2 in use)"
        );
        // Another credential has its own pool; the host still has slots.
        let mut d = gate();
        d.credential = "openai-test";
        d.runtime = "CODEX";
        start(&mut conn, &d).await;
        for spec in [&a, &b, &d] {
            let finished = release_and_finish(&mut conn, &host, spec.run_id).await;
            assert_eq!(finished["outcome"], "SUCCEEDED", "{finished}");
        }
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn host_config_versions_are_ordered_per_connection_and_forgotten_on_reconnect() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        let (_, applied) = conn
            .handshake_with(5, Some(host_config(5, Some(1), &[])))
            .await;
        assert_eq!(applied.unwrap()["version"], 5);

        // Frames of the server can overtake each other: an older version is neither applied
        // nor answered. (The error for an unknown run shows that the frame was handled.)
        conn.send(host_config(4, None, &[]));
        conn.send(json!({"type": "run.cancel", "runId": Uuid::new_v4(), "epoch": 1}));
        conn.take("error").await;
        assert_eq!(host.host.capacity().max_concurrent_runs(), 1);
        // The same version again is answered again; a newer one is applied.
        assert_eq!(conn.apply(host_config(5, Some(1), &[])).await["version"], 5);
        let applied = conn
            .apply(host_config(6, Some(1), &[("claude-test", 1)]))
            .await;
        assert_eq!(applied["version"], 6);
        assert_eq!(applied["maxConcurrentRuns"], 1);
        assert_eq!(
            applied_pools(&applied),
            pools(&[("claude-test", 1), ("openai-test", 1)])
        );
        let versions: Vec<&Value> = applied_frames(&conn)
            .iter()
            .map(|f| &f["version"])
            .collect();
        assert_eq!(versions, vec![&json!(5), &json!(5), &json!(6)]);

        // A new connection forgets the version; the values stay until the next host.config.
        conn.kill();
        while conn.next().await.is_some() {}
        let mut conn2 = server.accept().await;
        conn2.handshake_with(5, None).await;
        let capacity = host.host.capacity();
        assert!(capacity.applied().is_none());
        assert_eq!(capacity.max_concurrent_runs(), 1);
        assert_eq!(capacity.pool_size("claude-test"), Some(1));
        let applied = conn2.apply(host_config(2, None, &[])).await;
        assert_eq!(
            applied["version"], 2,
            "lower than 6, but on a new connection"
        );
        assert_eq!(applied["maxConcurrentRuns"], 2);
        assert_eq!(
            applied_pools(&applied),
            pools(&[("claude-test", 2), ("openai-test", 1)])
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_second_run_of_a_task_waits_until_the_first_has_ended() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let a = gate();
        start(&mut conn, &a).await;
        // Another run of the same task would share its session home and branch.
        let mut b = gate();
        b.agent_id = a.agent_id;
        b.task_id = a.task_id;
        let r = rejected(&mut conn, &b).await;
        assert_eq!(r["code"], "TASK_RUN_ACTIVE");
        assert_eq!(
            r["reason"],
            format!(
                "run {} of task TEST-1 is still active on this runner host",
                a.run_id
            )
        );
        // Another task is not held up.
        let c = gate();
        start(&mut conn, &c).await;
        let finished = release_and_finish(&mut conn, &host, a.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        let finished = release_and_finish(&mut conn, &host, c.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        start(&mut conn, &b).await;
        let finished = release_and_finish(&mut conn, &host, b.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn only_the_credentials_read_at_startup_are_used() {
    with_timeout(60, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let store = CredentialStore::new(&host.paths);

        let mut unknown = gate();
        unknown.credential = "nope";
        let r = rejected(&mut conn, &unknown).await;
        assert_eq!(r["code"], "UNKNOWN_CREDENTIAL");
        assert_eq!(r["reason"], "unknown credential `nope`");

        // Added after the start: not in hello, so not used before the next start.
        store
            .save(&Credential {
                label: "claude-late".into(),
                kind: CredentialKind::ClaudeLogin,
                pool_size: 3,
                created_at: Utc::now(),
                secret: None,
            })
            .unwrap();
        let mut late = gate();
        late.credential = "claude-late";
        let r = rejected(&mut conn, &late).await;
        assert_eq!(r["code"], "UNKNOWN_CREDENTIAL");
        assert!(
            r["reason"]
                .as_str()
                .unwrap()
                .contains("was added after `run` started"),
            "{r}"
        );

        // Removed: refused at once.
        assert!(store.remove("openai-test").unwrap());
        let mut removed = gate();
        removed.credential = "openai-test";
        removed.runtime = "CODEX";
        let r = rejected(&mut conn, &removed).await;
        assert_eq!(r["code"], "UNKNOWN_CREDENTIAL");

        // A reconnect reports the credentials and ceilings of the start again.
        conn.kill();
        while conn.next().await.is_some() {}
        let mut conn2 = server.accept().await;
        let hello = conn2.handshake(5).await;
        let labels: Vec<&str> = hello["credentials"]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| c["label"].as_str().unwrap())
            .collect();
        assert_eq!(labels, vec!["claude-test", "openai-test"]);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

// ---------------------------------------------------------------------------------------
// Drain (docs/archive/v1/CONVENTIONS.md §15.4 step 13)
// ---------------------------------------------------------------------------------------

/// Waits for `serve()` to return after a drain or shutdown.
async fn served(host: TestHost) -> anyhow::Result<()> {
    tokio::time::timeout(Duration::from_secs(30), host.handle)
        .await
        .expect("serve() returns")
        .unwrap()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_drain_lets_the_active_runs_finish_then_closes_with_1000() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(1).await;
        let a = gate();
        start(&mut conn, &a).await;

        assert!(host.host.request_drain());
        let applied = conn.take("host.config.applied").await;
        assert_eq!(applied["draining"], true);
        assert_eq!(applied["maxConcurrentRuns"], 0);
        assert_eq!(applied["version"], 0);
        assert_applied_matches_hello(&hello, &applied);
        // A second request changes nothing.
        assert!(!host.host.request_drain());

        // New runs are refused; a host.config is still applied and answered as draining.
        let b = gate();
        let r = rejected(&mut conn, &b).await;
        assert_eq!(r["code"], "DRAINING");
        let applied = conn.apply(host_config(1, Some(2), &[])).await;
        assert_eq!(applied["version"], 1);
        assert_eq!(applied["draining"], true);
        assert_eq!(applied["maxConcurrentRuns"], 0);
        // The active run goes on as usual, with heartbeats; the host does not exit.
        conn.take_where("a heartbeat while draining", |f| {
            f["type"] == "run.heartbeat" && f["runs"][0]["runId"] == json!(a.run_id)
        })
        .await;
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert!(!host.handle.is_finished());
        assert_eq!(host.host.active_run_count(), 1);

        let finished = release_and_finish(&mut conn, &host, a.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        served(host).await.expect("a drain ends with Ok");
        while conn.next().await.is_some() {}
        assert_eq!(
            conn.close_frame.lock().unwrap().as_ref().map(|c| c.0),
            Some(1000)
        );
        run_frames(&conn, a.run_id).assert_contiguous_seq();
        let draining: Vec<&Value> = applied_frames(&conn)
            .into_iter()
            .filter(|f| f["draining"] == true)
            .collect();
        assert_eq!(
            draining.len(),
            2,
            "the unprompted one and the answer to version 1"
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_drain_before_the_first_host_config_shows_in_its_answer() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        let (hello, _) = conn.handshake_with(5, None).await;
        let a = gate();
        start(&mut conn, &a).await;
        assert!(host.host.request_drain());
        let b = gate();
        let r = rejected(&mut conn, &b).await;
        assert_eq!(r["code"], "DRAINING");
        // Nothing unprompted: no host.config was applied on this connection yet.
        assert!(applied_frames(&conn).is_empty());
        let applied = conn.apply(host_config(3, None, &[])).await;
        assert_eq!(applied["version"], 3);
        assert_eq!(applied["draining"], true);
        assert_eq!(applied["maxConcurrentRuns"], 0);
        assert_applied_matches_hello(&hello, &applied);
        let finished = release_and_finish(&mut conn, &host, a.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        served(host).await.unwrap();
        while conn.next().await.is_some() {}
        assert_eq!(
            conn.close_frame.lock().unwrap().as_ref().map(|c| c.0),
            Some(1000)
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_shutdown_during_a_drain_cancels_the_remaining_runs() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let a = gate();
        start(&mut conn, &a).await;
        assert!(host.host.request_drain());
        conn.take("host.config.applied").await;
        host.shutdown.cancel();
        let finished = conn.take_run("run.finished", a.run_id).await;
        assert_eq!(finished["outcome"], "CANCELLED");
        served(host).await.unwrap();
        while conn.next().await.is_some() {}
        assert_eq!(
            conn.close_frame.lock().unwrap().as_ref().map(|c| c.0),
            Some(1000)
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_drain_delivers_everything_across_a_reconnect() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let a = gate();
        start(&mut conn, &a).await;
        assert!(host.host.request_drain());
        conn.take("host.config.applied").await;

        // The server goes away; the run ends meanwhile.
        server.set_refusing(true);
        conn.kill();
        while conn.next().await.is_some() {}
        release(&host.paths, a.run_id);
        let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
        while host.host.active_run_count() > 0 {
            assert!(
                tokio::time::Instant::now() < deadline,
                "the run did not end"
            );
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        // The server has not heard of the end yet: the host waits for it.
        tokio::time::sleep(Duration::from_secs(1)).await;
        assert!(!host.handle.is_finished());

        server.set_refusing(false);
        let mut conn2 = server.accept().await;
        // The host closes as soon as the resent frames are out, without waiting for
        // host.config (a frame sent now could meet the closed socket).
        let (hello, _) = conn2.handshake_with(1, None).await;
        assert_eq!(hello["activeRuns"][0]["runId"], json!(a.run_id));
        let finished = conn2.take_run("run.finished", a.run_id).await;
        assert_eq!(finished["outcome"], "SUCCEEDED");
        served(host).await.unwrap();
        while conn2.next().await.is_some() {}
        assert_eq!(
            conn2.close_frame.lock().unwrap().as_ref().map(|c| c.0),
            Some(1000)
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_drain_without_runs_does_not_wait_for_the_server() {
    with_timeout(60, async {
        let server = FakeServer::start().await;
        server.set_refusing(true);
        let host = start_host(&server.url, HostSetup::local()).await;
        tokio::time::sleep(Duration::from_millis(300)).await;
        assert!(host.host.request_drain());
        served(host).await.expect("nothing to deliver");
    })
    .await;
}
