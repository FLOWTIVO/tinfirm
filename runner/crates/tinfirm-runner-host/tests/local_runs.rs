//! End-to-end runs against a fake server and the fake ACP agent, using the local test
//! executor (the agent runs as a child process). These tests need `node` and `git`, but no
//! Docker; `docker_runs.rs` repeats the important ones in real containers.

mod common;

use std::time::Duration;

use chrono::{TimeDelta, Utc};
use common::*;
use serde_json::json;
use tinfirm_protocol::Runtime;
use tinfirm_runner_host::maintenance;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn happy_path_clones_runs_commits_and_pushes() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        assert_eq!(conn.authorization, format!("Bearer {TEST_HOST_TOKEN}"));
        let hello = conn.handshake(1).await;

        // hello: metadata only, pinned runtimes, no active runs after a fresh start.
        assert_eq!(hello["protocolVersion"], 1);
        assert_eq!(hello["executors"], json!(["docker"]));
        assert_eq!(hello["activeRuns"], json!([]));
        let labels: Vec<&str> = hello["credentials"]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| c["label"].as_str().unwrap())
            .collect();
        assert_eq!(labels, vec!["claude-test", "openai-test"]);
        assert_eq!(hello["credentials"][0]["kind"], "CLAUDE_LOGIN");
        assert_eq!(hello["credentials"][0]["runtimes"], json!(["CLAUDE_CODE"]));
        assert!(!hello.to_string().contains(TEST_CLAUDE_TOKEN));

        // The server pings every heartbeat interval; the host answers with pongs.
        conn.ping();
        let spec = AssignSpec::new("happy", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert!(
            conn.pongs.load(std::sync::atomic::Ordering::SeqCst) >= 1,
            "no pong"
        );

        assert_eq!(t.frames[0]["type"], "run.accepted");
        assert_eq!(t.frames[0]["epoch"], 1);
        t.assert_contiguous_seq();
        t.assert_no_secrets();

        let statuses = t.statuses();
        assert_eq!(
            statuses,
            vec![
                "preparing",
                "cloning",
                "starting",
                "running",
                "finalizing",
                "pushing"
            ]
        );
        let types = t.event_types();
        for expected in [
            "GIT",
            "AGENT_THOUGHT",
            "PLAN",
            "TOOL_CALL",
            "TOOL_CALL_UPDATE",
            "USAGE",
            "RATE_LIMIT",
            "AGENT_MESSAGE",
            "LOG",
        ] {
            assert!(
                types.contains(&expected.to_string()),
                "missing {expected} in {types:?}"
            );
        }
        let git_actions: Vec<&str> = t
            .events_of("GIT")
            .iter()
            .map(|e| e["payload"]["action"].as_str().unwrap())
            .collect();
        assert_eq!(git_actions, vec!["cloned", "committed", "pushed"]);
        // GIT events and results name the repository.
        for event in t.events_of("GIT") {
            assert_eq!(event["payload"]["dir"], "repo");
            assert_eq!(
                event["payload"]["componentId"],
                spec.workspaces[0].component_id.to_string()
            );
        }
        assert_eq!(
            t.finished()["git"][0]["componentId"],
            spec.workspaces[0].component_id.to_string()
        );

        // Tool calls: edit with input + locations, then an update with a diff.
        let tool_calls = t.events_of("TOOL_CALL");
        assert_eq!(tool_calls.len(), 2);
        assert_eq!(tool_calls[0]["payload"]["kind"], "edit");
        assert_eq!(tool_calls[0]["payload"]["input"]["path"], "hello.txt");
        let updates = t.events_of("TOOL_CALL_UPDATE");
        assert!(
            updates
                .iter()
                .any(|u| u["payload"]["diffs"][0]["newText"] == "hello from the fake agent\n")
        );
        assert!(t.text().contains("auto-approved permission (allow)"));

        // Coalescing: the 8 streamed chunks of the final message arrive in few events.
        let messages = t.events_of("AGENT_MESSAGE");
        assert!(
            messages.len() <= 5,
            "chunks were not coalesced: {}",
            messages.len()
        );
        assert!(
            t.agent_text()
                .contains("Done. I created hello.txt and committed it.")
        );

        // Rate limit meta and cumulative usage.
        let rate = t.events_of("RATE_LIMIT");
        assert_eq!(rate[0]["payload"]["status"], "allowed");
        assert_eq!(rate[0]["payload"]["window"], "five_hour");
        let usages = t.events_of("USAGE");
        let last_usage = &usages.last().unwrap()["payload"];
        // v1 counts the 15 cache writes as input (a v2 host reports them apart).
        assert_eq!(last_usage["inputTokens"], 135);
        assert_eq!(last_usage["outputTokens"], 45);
        assert_eq!(last_usage["cachedInputTokens"], 30);
        assert!(last_usage.get("cacheWriteTokens").is_none());
        assert_eq!(last_usage["totalTokens"], 210);
        assert_eq!(last_usage["estimated"], false);
        assert_eq!(last_usage["contextUsed"], 1234);

        let finished = t.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED");
        assert!(finished.get("failureClass").is_none());
        assert_eq!(
            finished["summary"],
            "Done. I created hello.txt and committed it. Notes are left uncommitted."
        );
        assert!(finished["sessionId"].as_str().unwrap().starts_with("fake-"));
        assert_eq!(finished["git"][0]["branch"], "agent/TEST-1");
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(finished["git"][0]["commits"], 2);
        assert_eq!(finished["usage"], *last_usage);
        assert_eq!(finished["usage"]["costUsd"], 0.01);

        // The branch is in the bare repository with the agent's commit and the WIP commit.
        let head = repo.branch_head("agent/TEST-1").expect("branch pushed");
        assert_eq!(finished["git"][0]["headSha"], head);
        assert_eq!(
            repo.file_at("agent/TEST-1", "hello.txt").unwrap(),
            "hello from the fake agent\n"
        );
        assert_eq!(
            repo.file_at("agent/TEST-1", "notes.txt").unwrap(),
            "uncommitted notes\n"
        );
        let subjects = repo.subjects("agent/TEST-1");
        assert_eq!(
            subjects[0],
            format!("WIP: uncommitted changes from run {}", spec.run_id)
        );
        assert_eq!(subjects[1], "Add hello.txt");
        assert!(
            repo.file_at("main", "hello.txt").is_none(),
            "main is untouched"
        );

        // The token never reached .git/config; the workspace was deleted after success.
        assert!(!host.paths.workspace(spec.run_id).exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn follow_up_run_resumes_the_session_and_the_branch() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        let first = AssignSpec::new("happy", Some(&repo.url));
        conn.send(first.to_json());
        let t1 = conn.until_finished(first.run_id).await;
        let session_id = t1.finished()["sessionId"].as_str().unwrap().to_string();
        let head1 = repo.branch_head("agent/TEST-1").unwrap();

        let mut second = AssignSpec::new("slow", Some(&repo.url));
        second.agent_id = first.agent_id;
        second.task_id = first.task_id;
        second.resume_session_id = Some(session_id.clone());
        second.resume_prompt = Some("[scenario:slow] RESUME-MARKER new comments".into());
        conn.send(second.to_json());
        // Wait until the agent is running, then cancel.
        loop {
            let frame = conn.next().await.unwrap();
            if frame["type"] == "run.event" && frame["eventType"] == "AGENT_MESSAGE" {
                break;
            }
        }
        conn.send(json!({"type": "run.cancel", "runId": second.run_id, "epoch": 1}));
        let t2 = conn.until_finished(second.run_id).await;
        let all = conn
            .frames
            .iter()
            .filter(|f| f["runId"] == second.run_id.to_string())
            .cloned()
            .collect::<Vec<_>>();
        let t2_all = Transcript { frames: all };

        // session/load succeeded: the resume prompt was used, the history replay was not emitted.
        assert!(
            t2_all
                .text()
                .contains(&format!("resumed session {session_id}"))
        );
        assert!(t2_all.agent_text().contains("RESUME-MARKER"));
        assert!(!t2_all.text().contains("Earlier answer"));
        assert_eq!(t2.finished()["outcome"], "CANCELLED");
        assert_eq!(t2.finished()["sessionId"], session_id);
        // `resumed` and `resumeOutcome` are v2 fields: a v1 host leaves them out.
        assert!(t2.finished().get("resumed").is_none());
        assert!(t2.finished().get("resumeOutcome").is_none());

        // The branch continued from the remote branch and was pushed fast-forward with WIP.
        let cloned = t2_all.events_of("GIT")[0].clone();
        assert_eq!(cloned["payload"]["headSha"], head1);
        assert_eq!(cloned["payload"]["commits"], 2);
        assert_eq!(t2.finished()["git"][0]["pushed"], true);
        assert_eq!(t2.finished()["git"][0]["commits"], 3);
        assert_eq!(
            repo.file_at("agent/TEST-1", "slow.txt").unwrap(),
            "work in progress\n"
        );
        assert_eq!(
            repo.file_at("agent/TEST-1", "hello.txt").unwrap(),
            "hello from the fake agent\n"
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn credential_in_agent_output_is_redacted_and_stops_the_run() {
    if !logins_reach_the_sandbox() {
        // The agent leaks the run's login, which a VM per run never gets.
        return;
    }
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        let spec = AssignSpec::new("leak", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let text = t.text();
        assert!(text.contains("[REDACTED]"));
        // The base64 of "x-access-token:<token>" is redacted too.
        use base64::Engine;
        let b64 = base64::engine::general_purpose::STANDARD
            .encode(format!("x-access-token:{TEST_CLAUDE_TOKEN}"));
        assert!(!text.contains(&b64[..40]));
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "CREDENTIAL_LEAK");
        assert_eq!(finished["git"][0]["pushed"], false);
        assert!(
            repo.branch_head("agent/TEST-1").is_none(),
            "nothing may be pushed after a leak"
        );
        let errors = t.events_of("ERROR");
        assert_eq!(
            errors.last().unwrap()["payload"]["failureClass"],
            "CREDENTIAL_LEAK"
        );
        // The failed workspace is kept for inspection.
        assert!(
            host.paths
                .data_dir
                .join("failed-workspaces")
                .join(spec.run_id.to_string())
                .exists()
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn credential_in_a_commit_blocks_the_push() {
    if !logins_reach_the_sandbox() {
        // The agent leaks the run's login, which a VM per run never gets.
        return;
    }
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("leak-commit", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["outcome"], "FAILED");
        assert_eq!(t.finished()["failureClass"], "CREDENTIAL_LEAK");
        assert_eq!(t.finished()["git"][0]["pushed"], false);
        assert!(repo.branch_head("agent/TEST-1").is_none());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

async fn guard_run(scenario: &str, max_turns: u32, wall_clock: Option<Duration>) -> Transcript {
    guard_run_as(1, scenario, max_turns, wall_clock).await
}

/// [`guard_run`] on a host that speaks `protocol_version`.
async fn guard_run_as(
    protocol_version: u32,
    scenario: &str,
    max_turns: u32,
    wall_clock: Option<Duration>,
) -> Transcript {
    let repo = TestRepo::new();
    let mut server = FakeServer::start().await;
    let mut setup = if protocol_version >= 2 {
        HostSetup::v2()
    } else {
        HostSetup::local()
    };
    setup.wall_clock_cap = wall_clock;
    let host = start_host(&server.url, setup).await;
    let mut conn = server.accept().await;
    conn.handshake(5).await;
    let mut spec = if protocol_version >= 2 {
        AssignSpec::v2(scenario, Some(&repo.url))
    } else {
        AssignSpec::new(scenario, Some(&repo.url))
    };
    spec.max_turns = max_turns;
    conn.send(spec.to_json());
    let t = conn.until_finished(spec.run_id).await;
    host.shutdown.cancel();
    host.handle.await.unwrap().unwrap();
    t
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn repeated_identical_tool_calls_stop_the_run() {
    with_timeout(120, async {
        let t = guard_run("loop", 200, None).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "GUARD_STOPPED");
        assert!(
            finished["failureMessage"]
                .as_str()
                .unwrap()
                .contains("same tool call 5 times")
        );
        assert!(t.statuses().contains(&"cancelling".to_string()));
        assert!(t.events_of("TOOL_CALL").len() >= 5);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn too_many_tool_calls_stop_the_run() {
    with_timeout(120, async {
        let t = guard_run("many-tools", 3, None).await;
        assert_eq!(t.finished()["failureClass"], "GUARD_STOPPED");
        assert!(
            t.finished()["failureMessage"]
                .as_str()
                .unwrap()
                .contains("maxTurns")
        );
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn wall_clock_limit_stops_the_run_and_pushes_wip() {
    with_timeout(120, async {
        // Protocol v1 has no TIME_LIMIT: the wall clock is one of the guards there.
        let t = guard_run("slow", 200, Some(Duration::from_secs(2))).await;
        let finished = t.finished();
        assert_eq!(finished["failureClass"], "GUARD_STOPPED");
        assert_eq!(
            finished["failureMessage"],
            "the run reached its time limit (2 s)"
        );
        // Guard stops still push the work in progress.
        assert_eq!(finished["git"][0]["pushed"], true);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_v2_host_reports_the_wall_clock_as_a_time_limit() {
    with_timeout(120, async {
        let t = guard_run_as(2, "slow", 200, Some(Duration::from_secs(2))).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "TIME_LIMIT");
        assert_eq!(
            finished["failureMessage"],
            "the run reached its time limit (2 s)"
        );
        assert!(t.statuses().contains(&"cancelling".to_string()));
        // The work so far is pushed, so a retry goes on from it.
        assert_eq!(finished["git"][0]["pushed"], true);
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn run_cancel_pushes_wip_and_reports_cancelled() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        // Heartbeats renew the lease while the run is active (welcome said every 1 s).
        let heartbeat = conn.expect("run.heartbeat").await;
        assert_eq!(
            heartbeat["runs"],
            json!([{"runId": spec.run_id, "epoch": 1}])
        );
        // The server resends pending cancels; duplicates are idempotent.
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let t = conn.until_finished(spec.run_id).await;
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let finished = t.finished();
        assert_eq!(finished["outcome"], "CANCELLED");
        assert!(finished.get("failureClass").is_none());
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(finished["git"][0]["commits"], 1);
        assert!(t.statuses().contains(&"cancelling".to_string()));
        assert_eq!(
            repo.subjects("agent/TEST-1")[0],
            format!("WIP: uncommitted changes from run {}", spec.run_id)
        );
        // Cancel of an unknown run is answered with an error; the resent cancel of the
        // finished run above was not.
        let unknown = Uuid::new_v4();
        conn.send(json!({"type": "run.cancel", "runId": unknown, "epoch": 1}));
        let err = conn.expect("error").await;
        assert_eq!(err["code"], "UNKNOWN_RUN");
        assert_eq!(err["runId"], unknown.to_string());
        assert_eq!(
            conn.frames.iter().filter(|f| f["type"] == "error").count(),
            1
        );
        assert_eq!(
            conn.frames
                .iter()
                .filter(|f| f["type"] == "run.finished")
                .count(),
            1
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn claude_usage_limit_puts_the_credential_in_cooldown() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("usage-limit", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "USAGE_LIMIT");
        let rejected: Vec<_> = t
            .events_of("RATE_LIMIT")
            .into_iter()
            .filter(|e| e["payload"]["status"] == "rejected")
            .collect();
        assert!(!rejected.is_empty());
        let status = t.of_type("credential.status");
        assert_eq!(status.len(), 1, "one COOLDOWN status: {status:?}");
        assert_eq!(status[0]["label"], "claude-test");
        assert_eq!(status[0]["status"], "COOLDOWN");
        let until =
            chrono::DateTime::parse_from_rfc3339(status[0]["cooldownUntil"].as_str().unwrap())
                .unwrap();
        let hours = (until.with_timezone(&chrono::Utc) - chrono::Utc::now()).num_minutes();
        assert!(
            (110..=121).contains(&hours),
            "cooldown follows resetsAt (+2 h): {hours} min"
        );

        // The credential is not used again while it cools down.
        let next = AssignSpec::new("happy", Some(&repo.url));
        conn.send(next.to_json());
        let rejected = conn.expect("run.rejected").await;
        assert_eq!(rejected["runId"], next.run_id.to_string());
        assert!(rejected["reason"].as_str().unwrap().contains("cooldown"));
        assert_eq!(rejected["code"], "CREDENTIAL_UNAVAILABLE");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn model_access_reaches_the_sandbox_as_the_runtimes_variables() {
    use tinfirm_runner_host::model_proxy::DUMMY_KEY;
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        // The host's defaults: the model proxy is always on, and no setting turns it off.
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        // Claude Code with a gateway: the run's model proxy as its base URL (its secret masked)
        // and the proxy's dummy key as a bearer token; never the gateway or its key, and no
        // credential of the host.
        let spec = AssignSpec::new("model-env", None).with_model_access("anthropic");
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let text = t.agent_text();
        for expected in [
            format!("ANTHROPIC_BASE_URL={}", listener_url()),
            "/[REDACTED] ".to_string(),
            "OPENAI_BASE_URL=-".to_string(),
            "ANTHROPIC_AUTH_TOKEN=set".to_string(),
            "OPENAI_API_KEY=-".to_string(),
            "ANTHROPIC_API_KEY=-".to_string(),
            "CLAUDE_CODE_OAUTH_TOKEN=-".to_string(),
            "DEFAULT_AUTH_REQUEST=-".to_string(),
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1".to_string(),
            "CODEX_CONFIG=-".to_string(),
            // Claude Code's WebSearch, Anthropic's server-side tool, is off through a gateway: the
            // relay refuses it (ADR-0108).
            r#"session-meta={"claudeCode":{"options":{"disallowedTools":["WebSearch"]}}}"#
                .to_string(),
            format!("key-length={}", DUMMY_KEY.len()),
        ] {
            assert!(text.contains(&expected), "{expected} in {text}");
        }
        assert!(!text.contains(TEST_MODEL_BASE_URL), "{text}");

        // Codex with a gateway: OPENAI_BASE_URL and OPENAI_API_KEY (the proxy and its dummy key),
        // logged in as an OpenAI API key, and the proxy in its config, where Codex reads its base
        // URL: a provider of its own, without WebSockets.
        let mut spec = AssignSpec::new("model-env", None).with_model_access("openai");
        spec.runtime = "CODEX";
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let text = t.agent_text();
        for expected in [
            format!("OPENAI_BASE_URL={}", listener_url()),
            "ANTHROPIC_BASE_URL=-".to_string(),
            "OPENAI_API_KEY=set".to_string(),
            "ANTHROPIC_AUTH_TOKEN=-".to_string(),
            r#"DEFAULT_AUTH_REQUEST={"methodId":"api-key"}"#.to_string(),
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=-".to_string(),
            "CODEX_CONFIG={".to_string(),
            r#""model_provider":"tinfirm""#.to_string(),
            format!(r#""base_url":"{}"#, listener_url()),
            r#""env_key":"OPENAI_API_KEY""#.to_string(),
            r#""wire_api":"responses""#.to_string(),
            r#""supports_websockets":false"#.to_string(),
            r#""web_search":"disabled""#.to_string(),
            "session-meta=null".to_string(),
            format!("key-length={}", DUMMY_KEY.len()),
        ] {
            assert!(text.contains(&expected), "{expected} in {text}");
        }
        assert!(!text.contains("openai_base_url"), "{text}");
        assert!(!text.contains(TEST_MODEL_BASE_URL), "{text}");

        // Model access without a base URL (a provider's own key, Bedrock, OpenRouter): the server
        // sends none, and the host refuses it rather than put the key into the sandbox (I8).
        for (runtime, access) in [
            (
                "CLAUDE_CODE",
                json!({"format": "anthropic", "apiKey": TEST_MODEL_KEY}),
            ),
            (
                "PI",
                json!({
                    "format": "bedrock", "region": "eu-central-1",
                    "accessKeyId": "AKIAIOSFODNN7EXAMPLE", "apiKey": TEST_MODEL_KEY
                }),
            ),
            (
                "OPENCODE",
                json!({"format": "openrouter", "apiKey": TEST_MODEL_KEY}),
            ),
        ] {
            let mut spec = AssignSpec::new("model-env", None);
            spec.model_access = Some(access);
            spec.runtime = runtime;
            conn.send(spec.to_json());
            let rejected = conn.take_run("run.rejected", spec.run_id).await;
            assert_eq!(rejected["code"], "INVALID_ASSIGNMENT");
            assert!(
                rejected["reason"]
                    .as_str()
                    .unwrap()
                    .contains("without a base URL is refused"),
                "{rejected}"
            );
            assert!(!rejected.to_string().contains(TEST_MODEL_KEY));
        }
        assert!(t.of_type("credential.status").is_empty());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// The port that the `model-proxy` scenario reported.
fn reported_port(text: &str) -> u16 {
    text.split("model-port=")
        .nth(1)
        .and_then(|rest| rest.split_whitespace().next())
        .and_then(|port| port.parse().ok())
        .unwrap_or_else(|| panic!("no model port in {text}"))
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_model_proxy_is_always_on_and_a_gateways_key_stays_on_the_host() {
    use tinfirm_runner_host::model_proxy::DUMMY_KEY;
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        // The host's defaults: nothing turns the model proxy on, or off.
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        // A gateway on a documentation address: the proxy refuses to connect there, so its
        // answer shows that the run's listener answered, without a gateway in the test.
        let gateway = |format: &str| {
            json!({"format": format, "baseUrl": "https://192.0.2.10:8443", "apiKey": TEST_MODEL_KEY})
        };

        // Claude Code: the sandbox has the run's listener and the dummy key, and the listener
        // answers while the run lasts.
        let mut spec = AssignSpec::new("model-proxy", None);
        spec.model_access = Some(gateway("anthropic"));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let text = t.agent_text();
        assert!(text.contains(&format!("model-key={DUMMY_KEY}")), "{text}");
        assert!(text.contains("messages=502"), "{text}");
        assert!(text.contains("keys=404"), "{text}");
        // The run is over: nothing answers on its port any more.
        let port = reported_port(&text);
        let mut gone = false;
        for _ in 0..50 {
            if tokio::net::TcpStream::connect(("127.0.0.1", port))
                .await
                .is_err()
            {
                gone = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert!(gone, "the run's model proxy still listens on {port}");

        // The variables: the listener (its secret masked) and the dummy key, never the gateway.
        let mut spec = AssignSpec::new("model-env", None);
        spec.model_access = Some(gateway("anthropic"));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let text = t.agent_text();
        for expected in [
            format!("ANTHROPIC_BASE_URL={}", listener_url()),
            "ANTHROPIC_AUTH_TOKEN=set".to_string(),
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1".to_string(),
            format!("key-length={}", DUMMY_KEY.len()),
        ] {
            assert!(text.contains(&expected), "{expected} in {text}");
        }
        assert!(!text.contains("192.0.2.10"), "{text}");
        assert!(text.contains("/[REDACTED] "), "{text}");
        assert!(
            !t.text().contains("model relay at another origin"),
            "{}",
            t.text()
        );

        // A Tinfirm model relay at another origin than the host's server URL: the host's own
        // token does not go there, and the run's log says why its model requests will be refused.
        let mut spec = AssignSpec::new("model-env", None);
        spec.model_access = Some(json!({
            "format": "anthropic",
            "baseUrl": "https://192.0.2.10:8443/api/runner/v1/model/anthropic",
            "apiKey": TEST_MODEL_KEY
        }));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(
            t.text().contains("model relay at another origin"),
            "{}",
            t.text()
        );

        // Codex: the gateway as a provider of its own, without WebSockets.
        let mut spec = AssignSpec::new("model-env", None);
        spec.model_access = Some(json!({
            "format": "openai", "baseUrl": "https://192.0.2.10:8443/v1", "apiKey": TEST_MODEL_KEY
        }));
        spec.runtime = "CODEX";
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let text = t.agent_text();
        for expected in [
            r#""model_provider":"tinfirm""#.to_string(),
            r#""supports_websockets":false"#.to_string(),
            r#""wire_api":"responses""#.to_string(),
            "/[REDACTED]/v1".to_string(),
            "OPENAI_API_KEY=set".to_string(),
            format!("key-length={}", DUMMY_KEY.len()),
        ] {
            assert!(text.contains(&expected), "{expected} in {text}");
        }
        assert!(!text.contains("openai_base_url"), "{text}");
        assert!(!text.contains("192.0.2.10"), "{text}");

        // A gateway whose listener cannot start (here, a base URL with a query, which the proxy
        // does not forward to) fails the run before its sandbox starts: there is no fallback that
        // hands the sandbox the key.
        let mut spec = AssignSpec::new("model-env", None);
        spec.model_access = Some(json!({
            "format": "anthropic", "baseUrl": "https://192.0.2.10:8443/?tenant=a", "apiKey": TEST_MODEL_KEY
        }));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{}", t.text());
        assert_eq!(finished["failureClass"], "INFRA");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(
            message.contains("the model proxy cannot start") && message.contains("query"),
            "{message}"
        );
        assert!(t.agent_text().is_empty(), "the agent never ran: {}", t.text());
        assert!(!t.statuses().contains(&"running".to_string()));

        // A provider's own key has no gateway to proxy: it is refused, never put into the
        // sandbox (I8).
        let mut spec = AssignSpec::new("model-env", None);
        spec.model_access =
            Some(json!({"format": "openrouter", "apiKey": TEST_MODEL_KEY}));
        spec.runtime = "PI";
        conn.send(spec.to_json());
        let rejected = conn.take_run("run.rejected", spec.run_id).await;
        assert_eq!(rejected["code"], "INVALID_ASSIGNMENT");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn model_access_without_a_base_url_never_reaches_the_sandbox() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        // A provider's own key (no gateway, so no model proxy) would be in the sandbox's
        // environment: the host refuses the run (I8 of the security review of 2026-10-27).
        let mut spec = AssignSpec::new("leak", Some(&repo.url));
        spec.model_access = Some(json!({"format": "anthropic", "apiKey": TEST_MODEL_KEY}));
        conn.send(spec.to_json());
        let rejected = conn.take_run("run.rejected", spec.run_id).await;
        assert_eq!(rejected["code"], "INVALID_ASSIGNMENT");
        assert!(!rejected.to_string().contains(TEST_MODEL_KEY));
        assert!(repo.branch_head("agent/TEST-1").is_none());

        // The key belonged to that run only: a later run's output does not know it.
        let next = AssignSpec::new("model-env", None);
        conn.send(next.to_json());
        let t = conn.until_finished(next.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(
            !host
                .host
                .redactor()
                .redact(TEST_MODEL_KEY)
                .contains("[REDACTED]")
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_usage_limit_of_model_access_reports_the_reset_and_cools_down_nothing_on_the_host() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        // Claude's rate limit meta gives the reset time (in 2 hours).
        let spec = AssignSpec::new("usage-limit", None).with_model_access("anthropic");
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "USAGE_LIMIT");
        let rejected: Vec<_> = t
            .events_of("RATE_LIMIT")
            .into_iter()
            .filter(|e| e["payload"]["status"] == "rejected")
            .collect();
        let resets = rejected
            .iter()
            .filter_map(|e| e["payload"]["resetsAt"].as_str())
            .map(|r| chrono::DateTime::parse_from_rfc3339(r).unwrap())
            .max()
            .expect("a reset time");
        let minutes = (resets.with_timezone(&chrono::Utc) - chrono::Utc::now()).num_minutes();
        assert!((110..=121).contains(&minutes), "resetsAt: +{minutes} min");
        // The server cools the company's credential down; the host has no credential to report.
        assert!(t.of_type("credential.status").is_empty());

        // Codex: the reset time parsed from the error text.
        let mut spec = AssignSpec::new("codex-usage-limit", None).with_model_access("openai");
        spec.runtime = "CODEX";
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["failureClass"], "USAGE_LIMIT");
        let reported = t.events_of("RATE_LIMIT");
        let resets = reported.last().unwrap()["payload"]["resetsAt"]
            .as_str()
            .unwrap();
        let minutes = (chrono::DateTime::parse_from_rfc3339(resets)
            .unwrap()
            .with_timezone(&chrono::Utc)
            - chrono::Utc::now())
        .num_minutes();
        assert!((145..=151).contains(&minutes), "resetsAt: +{minutes} min");
        assert!(t.of_type("credential.status").is_empty());

        // The host's own credentials stay available, and its runs get no model variables.
        let next = AssignSpec::new("model-env", None);
        conn.send(next.to_json());
        let t = conn.until_finished(next.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let text = t.agent_text();
        // A Claude login is the mounted credentials file, never a variable (ADR-0085); the
        // local executor points the agent at the login's directory instead.
        assert!(text.contains("CLAUDE_CODE_OAUTH_TOKEN=-"), "{text}");
        if logins_reach_the_sandbox() {
            assert!(text.contains("CLAUDE_CONFIG_DIR=set"), "{text}");
        }
        assert!(text.contains("ANTHROPIC_BASE_URL=-"), "{text}");
        assert!(text.contains("ANTHROPIC_AUTH_TOKEN=-"), "{text}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_assignment_without_exactly_one_credential_or_with_the_wrong_format_is_refused() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        // Both a label and model access.
        let spec = AssignSpec::new("happy", None);
        let mut both = spec.to_json();
        both["modelAccess"] = model_access("anthropic");
        conn.send(both);
        let rejected = conn.take_run("run.rejected", spec.run_id).await;
        assert_eq!(rejected["code"], "INVALID_ASSIGNMENT");

        // The format does not serve the agent's runtime.
        let wrong = AssignSpec::new("happy", None).with_model_access("openai");
        conn.send(wrong.to_json());
        let rejected = conn.take_run("run.rejected", wrong.run_id).await;
        assert_eq!(rejected["code"], "INVALID_ASSIGNMENT");
        assert!(
            rejected["reason"]
                .as_str()
                .unwrap()
                .contains("does not serve runtime CLAUDE_CODE")
        );
        assert!(!rejected.to_string().contains(TEST_MODEL_KEY));
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// A Codex login shared by the runs of its pool (ADR-0085, decision 5): a run that lost a
/// renewal race to another run of this host fails SERVER_LIMITED, for the server to retry,
/// and the login stays as it is; one that lost it to nothing on this host holds a stale
/// login, which is expired.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn codex_refresh_race_is_retried_but_a_stale_login_is_expired() {
    if !logins_reach_the_sandbox() {
        // The agent leaks the run's login, which a VM per run never gets.
        return;
    }
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.credentials = vec![tinfirm_runner_host::credentials::Credential {
            label: "codex-test".into(),
            kind: tinfirm_protocol::CredentialKind::CodexChatgptAuth,
            pool_size: 2,
            created_at: Utc::now(),
            secret: None,
        }];
        let host = start_host(&server.url, setup).await;
        write_fake_codex_home(&host.paths, "codex-test");
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        // The fake agent renews auth.json as the winner would, then fails as the loser.
        let mut race = AssignSpec::new("codex-refresh-race", None);
        race.runtime = "CODEX";
        race.credential = "codex-test";
        conn.send(race.to_json());
        let t = conn.until_finished(race.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["failureClass"], "SERVER_LIMITED", "{finished}");
        assert!(
            t.of_type("credential.status").is_empty(),
            "the login stays as it is"
        );

        // Nothing on this host renewed the file: the refresh token was used elsewhere.
        let mut stale = AssignSpec::new("codex-refresh-stale", None);
        stale.runtime = "CODEX";
        stale.credential = "codex-test";
        conn.send(stale.to_json());
        let t = conn.until_finished(stale.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["failureClass"], "LOGIN_EXPIRED", "{finished}");
        assert!(
            finished["failureMessage"]
                .as_str()
                .unwrap()
                .contains("sign in again on the host"),
            "{finished}"
        );
        let status = t.of_type("credential.status");
        assert_eq!(status[0]["status"], "INVALID");
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn codex_usage_limit_error_is_classified() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("codex-usage-limit", None);
        spec.runtime = "CODEX";
        spec.credential = "openai-test";
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["failureClass"], "USAGE_LIMIT");
        assert_eq!(
            finished["git"],
            json!([]),
            "no repositories, no git results"
        );
        let status = t.of_type("credential.status");
        assert_eq!(status[0]["label"], "openai-test");
        assert_eq!(status[0]["status"], "COOLDOWN");
        let until =
            chrono::DateTime::parse_from_rfc3339(status[0]["cooldownUntil"].as_str().unwrap())
                .unwrap();
        let minutes = (until.with_timezone(&chrono::Utc) - chrono::Utc::now()).num_minutes();
        assert!(
            (145..=151).contains(&minutes),
            "parsed 'try again in 2 hours 30 minutes': {minutes}"
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn codex_per_request_usage_is_accumulated() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("codex-usage", None);
        spec.runtime = "CODEX";
        spec.credential = "openai-test";
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED");
        let usage = &finished["usage"];
        let total = usage["inputTokens"].as_u64().unwrap()
            + usage["outputTokens"].as_u64().unwrap()
            + usage["cachedInputTokens"].as_u64().unwrap();
        assert_eq!(total, 7000, "the three requests are summed: {usage}");
        assert_eq!(usage["totalTokens"], 7000, "the total is exact");
        assert_eq!(
            usage["estimated"], true,
            "the split of earlier requests is estimated"
        );
        assert_eq!(usage["outputTokens"], 700);
        assert_eq!(usage["contextUsed"], 4000);
        // Every USAGE event carries running totals: they never decrease.
        let totals: Vec<u64> = t
            .events_of("USAGE")
            .iter()
            .map(|e| {
                let p = &e["payload"];
                p["inputTokens"].as_u64().unwrap()
                    + p["outputTokens"].as_u64().unwrap()
                    + p["cachedInputTokens"].as_u64().unwrap()
            })
            .collect();
        // While the turn runs, request totals count as input; the final event re-splits
        // them with the proportions of the last request (same total, exact split).
        assert_eq!(totals, vec![1000, 3000, 7000, 7000]);
        assert!(totals.windows(2).all(|w| w[0] <= w[1]));
        assert_eq!(t.events_of("USAGE").last().unwrap()["payload"], *usage);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// OpenCode 2 reports the tokens of every model request of a turn, OpenCode 1 only the last
/// one's; `initialize`'s `agentInfo.version` tells which runs, whatever the image (ADR-0061).
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn opencode_usage_follows_the_version_that_initialize_names() {
    with_timeout(120, async {
        for (version, estimated) in [("2.0.29", false), ("1.18.34", true)] {
            let mut server = FakeServer::start().await;
            let mut setup = HostSetup::v2();
            setup.executor = local_executor_with(&format!("--agent-version={version}"));
            let host = start_host(&server.url, setup).await;
            let mut conn = server.accept().await;
            conn.handshake(5).await;
            let mut spec = AssignSpec::v2("turn-usage", None).with_model_access("anthropic");
            spec.runtime = "OPENCODE";
            conn.send(spec.to_json());
            let t = conn.until_finished(spec.run_id).await;
            assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
            let usage = &t.finished()["usage"];
            assert_eq!(usage["totalTokens"], 5000, "{version}: {usage}");
            assert_eq!(usage["cacheWriteTokens"], 100, "{version}: {usage}");
            assert_eq!(usage["estimated"], estimated, "{version}: {usage}");
            assert_eq!(usage["costUsd"], 0.25, "{version}: {usage}");

            // The session's cost includes the earlier run: a resumed run reports none.
            let session_id = t.finished()["sessionId"].as_str().unwrap().to_string();
            let mut resumed = AssignSpec::v2("turn-usage", None).with_model_access("anthropic");
            resumed.runtime = "OPENCODE";
            resumed.agent_id = spec.agent_id;
            resumed.task_id = spec.task_id;
            resumed.resume_session_id = Some(session_id);
            conn.send(resumed.to_json());
            let t = conn.until_finished(resumed.run_id).await;
            assert_eq!(t.finished()["resumed"], true, "{}", t.text());
            let usage = &t.finished()["usage"];
            assert_eq!(usage["totalTokens"], 5000, "{version}: {usage}");
            assert!(usage.get("costUsd").is_none(), "{version}: {usage}");
            host.shutdown.cancel();
            host.handle.await.unwrap().unwrap();
        }
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn reconnect_keeps_the_run_and_flushes_buffered_events() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        let mut ticks = 0;
        while ticks < 3 {
            let f = conn.next().await.unwrap();
            if f["eventType"] == "AGENT_MESSAGE" {
                ticks += 1;
            }
        }
        // Take the server away for a moment so events pile up in the host's buffer.
        server.set_refusing(true);
        conn.kill();
        while conn.next().await.is_some() {}
        let before: Vec<serde_json::Value> = conn.frames.clone();
        tokio::time::sleep(Duration::from_millis(1500)).await;
        server.set_refusing(false);
        let mut conn2 = server.accept().await;
        let hello = conn2.handshake(1).await;
        let active = hello["activeRuns"].as_array().unwrap();
        assert_eq!(active.len(), 1);
        assert_eq!(active[0]["runId"], spec.run_id.to_string());
        assert_eq!(active[0]["epoch"], 1);
        let last_seq_at_hello = active[0]["lastSeq"].as_u64().unwrap();
        let max_before = before
            .iter()
            .filter_map(|f| f["seq"].as_u64())
            .max()
            .unwrap();
        assert!(
            last_seq_at_hello > max_before,
            "events kept coming while disconnected"
        );
        // Let the flushed events arrive, then cancel.
        loop {
            let f = conn2.next().await.unwrap();
            if f["seq"].as_u64().unwrap_or(0) >= last_seq_at_hello {
                break;
            }
        }
        conn2.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let t2 = conn2.until_finished(spec.run_id).await;
        assert_eq!(t2.finished()["outcome"], "CANCELLED");
        assert_eq!(
            conn2
                .frames
                .iter()
                .filter(|f| f["type"] == "run.accepted")
                .count(),
            0,
            "accepted is not repeated"
        );
        // Union of both connections: every seq 1..=lastSeq, the server dedups repeats.
        let mut seqs: Vec<u64> = before
            .iter()
            .chain(conn2.frames.iter())
            .filter(|f| f["type"] == "run.event")
            .map(|f| f["seq"].as_u64().unwrap())
            .collect();
        seqs.sort();
        seqs.dedup();
        let last = t2.finished()["lastSeq"].as_u64().unwrap();
        assert_eq!(seqs, (1..=last).collect::<Vec<_>>());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn admission_rejects_unknown_credentials_mismatched_runtimes_and_full_hosts() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.max_concurrent_runs = 1;
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        let mut unknown = AssignSpec::new("happy", None);
        unknown.credential = "nope";
        conn.send(unknown.to_json());
        let r = conn.expect("run.rejected").await;
        assert!(r["reason"].as_str().unwrap().contains("unknown credential"));
        assert_eq!(r["code"], "UNKNOWN_CREDENTIAL");

        let mut mismatch = AssignSpec::new("happy", None);
        mismatch.runtime = "CODEX";
        conn.send(mismatch.to_json());
        let r = conn.expect("run.rejected").await;
        assert!(
            r["reason"]
                .as_str()
                .unwrap()
                .contains("cannot serve runtime")
        );
        assert_eq!(r["code"], "CREDENTIAL_UNAVAILABLE");

        let slow = AssignSpec::new("slow", Some(&repo.url));
        conn.send(slow.to_json());
        conn.expect("run.accepted").await;
        // A duplicate assign of the same epoch is accepted again (idempotent).
        conn.send(slow.to_json());
        let again = conn.expect("run.accepted").await;
        assert_eq!(again["runId"], slow.run_id.to_string());
        let second = AssignSpec::new("happy", None);
        conn.send(second.to_json());
        let r = conn.expect("run.rejected").await;
        assert_eq!(r["runId"], second.run_id.to_string());
        assert_eq!(r["reason"], "no free slot (1 of 1 runs active)");
        assert_eq!(r["code"], "NO_FREE_SLOT");

        // Workspaces must be agent/ branches.
        let mut bad_branch = AssignSpec::new("happy", Some(&repo.url));
        bad_branch.task_key = "../main";
        conn.send(bad_branch.to_json());
        let r = conn.expect("run.rejected").await;
        assert!(r["reason"].as_str().unwrap().contains("agent/"), "{r}");
        assert_eq!(r["code"], "INVALID_ASSIGNMENT");

        // Unknown message types are answered with an error and otherwise ignored.
        conn.send_raw(r#"{"type":"run.bogus","x":1}"#);
        let err = conn.expect("error").await;
        assert_eq!(err["code"], "UNKNOWN_TYPE");

        conn.send(json!({"type": "run.cancel", "runId": slow.run_id, "epoch": 1}));
        let t = conn.until_finished(slow.run_id).await;
        assert_eq!(t.finished()["outcome"], "CANCELLED");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn stale_epoch_aborts_the_run_without_pushing_or_reporting() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
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
        // An error about another (older) epoch of the run leaves this execution alone.
        conn.send(json!({"type": "error", "code": "STALE_EPOCH", "message": "epoch 0 is stale", "runId": spec.run_id, "epoch": 0}));
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert_eq!(host.host.active_run_count(), 1);
        // With Error.epoch naming this execution, no run.cancel is needed.
        conn.send(json!({"type": "error", "code": "STALE_EPOCH", "message": "epoch 1 is stale", "runId": spec.run_id, "epoch": 1}));
        // The run disappears from the heartbeat, and no run.finished is ever sent.
        let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
        while host.host.active_run_count() > 0 {
            assert!(tokio::time::Instant::now() < deadline, "run not aborted");
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
        while conn.next().await.is_some() {}
        assert!(conn.frames.iter().all(|f| f["type"] != "run.finished"));
        assert!(repo.branch_head("agent/TEST-1").is_none(), "an aborted run must not push");
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn graceful_shutdown_cancels_runs_and_reports_before_exit() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
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
        host.shutdown.cancel();
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "CANCELLED");
        assert_eq!(t.finished()["git"][0]["pushed"], true);
        host.handle.await.unwrap().unwrap();
        // The host closes the socket normally after the last message.
        assert!(conn.next().await.is_none());
        assert_eq!(
            conn.close_frame.lock().unwrap().as_ref().map(|c| c.0),
            Some(1000)
        );
        assert!(repo.branch_head("agent/TEST-1").is_some());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn crash_auth_and_bad_token() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;

        let crash = AssignSpec::new("crash", None);
        conn.send(crash.to_json());
        let t = conn.until_finished(crash.run_id).await;
        assert_eq!(t.finished()["failureClass"], "PROTOCOL_ERROR");
        assert!(
            t.finished()["failureMessage"]
                .as_str()
                .unwrap()
                .contains("simulated crash")
        );

        let auth = AssignSpec::new("auth", None);
        conn.send(auth.to_json());
        let t = conn.until_finished(auth.run_id).await;
        assert_eq!(t.finished()["failureClass"], "LOGIN_EXPIRED");
        let status = t.of_type("credential.status");
        assert_eq!(status[0]["status"], "INVALID");

        // 4401 from the server is fatal: serve() returns an error instead of reconnecting.
        conn.close_with(4401, "token revoked");
        let result = tokio::time::timeout(Duration::from_secs(20), host.handle)
            .await
            .expect("serve() returns after a 4401 close")
            .unwrap();
        assert!(result.unwrap_err().to_string().contains("revoked"));
    })
    .await;
}

#[test]
fn runtime_usage_modes() {
    use tinfirm_runner_host::usage::UsageMode;
    assert_eq!(
        UsageMode::for_runtime(Runtime::ClaudeCode),
        UsageMode::TurnTotals
    );
    assert_eq!(
        UsageMode::for_runtime(Runtime::Codex),
        UsageMode::LastRequestOnly
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn newer_epoch_replaces_the_running_copy() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("slow", Some(&repo.url));
        conn.send(spec.to_json());
        loop {
            let f = conn.next().await.unwrap();
            if f["eventType"] == "AGENT_MESSAGE" {
                break;
            }
        }
        // The server lost the lease and re-dispatched the same run with epoch 2.
        spec.epoch = 2;
        conn.send(spec.to_json());
        loop {
            let f = conn.next().await.unwrap();
            if f["type"] == "run.accepted" && f["epoch"] == 2 {
                break;
            }
        }
        loop {
            let f = conn.next().await.unwrap();
            if f["type"] == "run.event" && f["epoch"] == 2 && f["eventType"] == "AGENT_MESSAGE" {
                break;
            }
        }
        // The older epoch again: this host runs a newer one.
        spec.epoch = 1;
        conn.send(spec.to_json());
        let stale = conn.take_run("run.rejected", spec.run_id).await;
        assert_eq!(stale["epoch"], 1);
        assert_eq!(stale["code"], "STALE_EPOCH");
        assert_eq!(stale["reason"], "stale epoch 1 (running epoch 2)");
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 2}));
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["epoch"], 2);
        assert_eq!(t.finished()["outcome"], "CANCELLED");
        // The epoch-2 events start again at seq 1 and the epoch-1 copy never finished.
        let epoch2: Vec<u64> = conn
            .frames
            .iter()
            .filter(|f| f["type"] == "run.event" && f["epoch"] == 2)
            .map(|f| f["seq"].as_u64().unwrap())
            .collect();
        assert_eq!(epoch2, (1..=epoch2.len() as u64).collect::<Vec<_>>());
        assert_eq!(
            conn.frames
                .iter()
                .filter(|f| f["type"] == "run.finished")
                .count(),
            1
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn unknown_resume_session_falls_back_to_a_new_session() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("codex-usage", None);
        spec.resume_session_id = Some("fake-does-not-exist".into());
        spec.resume_prompt = Some("[scenario:happy] RESUME-ONLY".into());
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert!(t.text().contains("started session fake-"));
        // session/load failed, so the full prompt was sent, not the resume prompt.
        assert!(!t.agent_text().contains("RESUME-ONLY"));
        assert!(t.agent_text().contains("Do the test task."));
        assert_ne!(t.finished()["sessionId"], "fake-does-not-exist");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_deleted_session_home_is_created_again_and_starts_a_new_session() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let first = AssignSpec::new("codex-usage", None);
        conn.send(first.to_json());
        let t1 = conn.until_finished(first.run_id).await;
        let session_id = t1.finished()["sessionId"].as_str().unwrap().to_string();
        let home = host.paths.session_home(first.agent_id, first.task_id);
        assert!(
            home.join(format!(".claude/projects/fake-agent/{session_id}.json"))
                .exists()
        );
        // 31 days on (TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS is 30).
        let later = Utc::now() + TimeDelta::days(31);
        let stop = CancellationToken::new();

        // While a follow-up run holds the home, maintenance keeps it, however old it is.
        let mut second = AssignSpec::new("slow", None);
        second.agent_id = first.agent_id;
        second.task_id = first.task_id;
        second.resume_session_id = Some(session_id.clone());
        second.resume_prompt = Some("[scenario:slow] RESUME-MARKER".into());
        conn.send(second.to_json());
        loop {
            let frame = conn.next().await.unwrap();
            if frame["type"] == "run.event" && frame["eventType"] == "AGENT_MESSAGE" {
                break;
            }
        }
        let (_, homes, _) = maintenance::run_pass(&host.host, later, &stop).await;
        assert!(homes.deleted.is_empty(), "{homes:?}");
        assert_eq!(homes.active, 1);
        assert!(home.is_dir());
        conn.send(json!({"type": "run.cancel", "runId": second.run_id, "epoch": 1}));
        let t2 = conn.until_finished(second.run_id).await;
        assert_eq!(t2.finished()["outcome"], "CANCELLED");

        // No run holds it any more: it is deleted.
        let (_, homes, _) = maintenance::run_pass(&host.host, later, &stop).await;
        assert_eq!(homes.deleted.len(), 1, "{homes:?}");
        assert_eq!(homes.deleted[0].task, first.task_id.to_string());
        assert!(!home.exists());

        // The next follow-up run gets a new, empty home: session/load fails there, and the
        // host starts a new session with the full prompt.
        let mut third = AssignSpec::new("codex-usage", None);
        third.agent_id = first.agent_id;
        third.task_id = first.task_id;
        third.resume_session_id = Some(session_id.clone());
        third.resume_prompt = Some("[scenario:codex-usage] RESUME-ONLY".into());
        conn.send(third.to_json());
        let t3 = conn.until_finished(third.run_id).await;
        assert_eq!(t3.finished()["outcome"], "SUCCEEDED", "{}", t3.text());
        assert!(!t3.text().contains("resumed session"));
        assert!(t3.text().contains("started session fake-"));
        assert!(!t3.agent_text().contains("RESUME-ONLY"));
        assert!(t3.agent_text().contains("Do the test task."));
        assert_ne!(t3.finished()["sessionId"], session_id.as_str());
        assert!(home.is_dir(), "the run created the home again");
        let used = host.host.homes.last_used(&home).unwrap();
        assert!((Utc::now() - used).num_seconds().abs() < 60);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn only_the_last_five_failed_workspaces_are_kept() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let failed = host.paths.data_dir.join("failed-workspaces");
        std::fs::create_dir_all(&failed).unwrap();
        for i in 0..7 {
            std::fs::create_dir(failed.join(format!("old-{i}"))).unwrap();
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("crash", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "FAILED");
        let mut kept: Vec<String> = std::fs::read_dir(&failed)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        kept.sort();
        assert_eq!(kept.len(), 5, "{kept:?}");
        assert!(
            kept.contains(&spec.run_id.to_string()),
            "the newest failure is kept"
        );
        assert!(!kept.contains(&"old-0".to_string()) && !kept.contains(&"old-2".to_string()));
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn credential_becomes_available_after_the_cooldown() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let until = chrono::Utc::now() + chrono::Duration::seconds(1);
        host.host
            .set_cooldown("claude-test", until, "test cooldown".into());
        let cooldown = conn.expect("credential.status").await;
        assert_eq!(cooldown["status"], "COOLDOWN");
        let available = conn.expect("credential.status").await;
        assert_eq!(available["label"], "claude-test");
        assert_eq!(available["status"], "AVAILABLE");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn oversized_agent_output_stays_under_the_frame_limit() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("huge", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED");
        assert!(
            conn.max_frame < 300 * 1024,
            "largest frame {} bytes",
            conn.max_frame
        );
        // The 300 000-byte message arrives complete, in bounded pieces.
        let text = t.agent_text();
        assert_eq!(text.matches('x').count(), 300_000);
        let plan = &t.events_of("PLAN")[0]["payload"]["entries"];
        assert!(!plan.as_array().unwrap().is_empty());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn close_4401_right_after_the_upgrade_is_fatal() {
    with_timeout(60, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let conn = server.accept().await;
        conn.close_with(4401, "invalid token");
        let result = tokio::time::timeout(Duration::from_secs(20), host.handle)
            .await
            .expect("serve() returns after 4401")
            .unwrap();
        let message = result.unwrap_err().to_string();
        assert!(message.contains("4401"), "{message}");
        // No reconnect attempt.
        let again = tokio::time::timeout(Duration::from_secs(2), server.accept()).await;
        assert!(again.is_err(), "the host must not reconnect after 4401");
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn close_4409_stops_without_reconnecting_or_pushing() {
    with_timeout(60, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
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
        conn.close_with(4409, "replaced by a newer connection");
        let result = tokio::time::timeout(Duration::from_secs(30), host.handle)
            .await
            .expect("serve() returns after 4409")
            .unwrap();
        assert!(result.unwrap_err().to_string().contains("4409"));
        let again = tokio::time::timeout(Duration::from_secs(2), server.accept()).await;
        assert!(
            again.is_err(),
            "the host must not fight the other connection"
        );
        assert!(
            repo.branch_head("agent/TEST-1").is_none(),
            "an aborted run does not push"
        );
        assert!(!host.paths.workspace(spec.run_id).exists());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn stale_epoch_without_error_epoch_falls_back_to_the_run_cancel() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
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
        // Older servers: STALE_EPOCH without epoch, then run.cancel naming the stale epoch.
        conn.send(json!({"type": "error", "code": "STALE_EPOCH", "message": "stale", "runId": spec.run_id}));
        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
        while host.host.active_run_count() > 0 {
            assert!(tokio::time::Instant::now() < deadline, "run not aborted");
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
        while conn.next().await.is_some() {}
        assert!(conn.frames.iter().all(|f| f["type"] != "run.finished"), "a stale execution is not reported");
        assert!(repo.branch_head("agent/TEST-1").is_none());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn inactivity_guard_stops_a_hung_agent() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.inactivity_timeout = Some(Duration::from_secs(2));
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;
        let spec = AssignSpec::new("hang", Some(&repo.url));
        conn.send(spec.to_json());
        let started = tokio::time::Instant::now();
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "GUARD_STOPPED");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(message.contains("no ACP message for 2 s"), "{message}");
        assert!(message.contains("inactivity"), "{message}");
        assert!(
            t.statuses().contains(&"cancelling".to_string()),
            "session/cancel was sent"
        );
        // Timeout (2 s) + cancel grace (5 s, ignored by the hung agent) + finalize.
        assert!(started.elapsed() < Duration::from_secs(30));
        // The slot and the credential are free again, and the work in progress was pushed.
        assert_eq!(host.host.active_run_count(), 0);
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(
            repo.file_at("agent/TEST-1", "partial.txt").unwrap(),
            "started before hanging\n"
        );
        assert!(t.of_type("credential.status").is_empty());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn long_tool_call_with_updates_does_not_trip_the_inactivity_guard() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        // The tool runs ~3 s but reports progress every 300 ms.
        setup.inactivity_timeout = Some(Duration::from_secs(1));
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("long-tool", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.finished());
        assert_eq!(t.finished()["summary"], "Tests passed.");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn model_refusal_is_model_refused_and_keeps_the_credential() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("refusal", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "MODEL_REFUSED");
        assert!(
            finished["summary"]
                .as_str()
                .unwrap()
                .ends_with("I can't help with that.")
        );
        assert!(finished["sessionId"].as_str().is_some());
        assert!(
            t.of_type("credential.status").is_empty(),
            "a refusal never pauses the credential"
        );
        // The credential is immediately usable again.
        let next = AssignSpec::new("codex-usage", None);
        conn.send(next.to_json());
        let t2 = conn.until_finished(next.run_id).await;
        assert_eq!(t2.finished()["outcome"], "SUCCEEDED");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agent_fetches_through_the_git_proxy_and_cannot_push() {
    with_timeout(120, run_fetch_scenario(HostSetup::local())).await;
}

// ---------------------------------------------------------------------------------------
// Several repositories per run (docs/archive/v1/CONVENTIONS.md §14.7, §15.4)
// ---------------------------------------------------------------------------------------

fn git_events(t: &Transcript) -> Vec<(String, String)> {
    t.events_of("GIT")
        .iter()
        .map(|e| {
            (
                e["payload"]["dir"].as_str().unwrap().to_string(),
                e["payload"]["action"].as_str().unwrap().to_string(),
            )
        })
        .collect()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn several_repositories_are_cloned_committed_and_pushed_one_by_one() {
    use common::git_http::{GitHttpServer, test_git_authorization};
    with_timeout(120, async {
        let server_repo = TestRepo::new();
        let web_repo = TestRepo::new();
        let docs_repo = TestRepo::new();
        let authorization = test_git_authorization();
        let server_http =
            GitHttpServer::start(server_repo.dir.path(), Some(authorization.clone())).await;
        let web_http = GitHttpServer::start(web_repo.dir.path(), Some(authorization.clone())).await;
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let workspaces = vec![
            WorkspaceEntry::new(
                "server",
                "Server",
                &format!("{}/origin.git", server_http.url),
            ),
            WorkspaceEntry::new(
                "web-app",
                "Web App",
                &format!("{}/origin.git", web_http.url),
            ),
            WorkspaceEntry::new("docs", "Docs", &docs_repo.url),
        ];
        let mut spec = AssignSpec::with_workspaces("multi", workspaces.clone());
        spec.prompt_extra = " [commit:server] [wip:web-app]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_contiguous_seq();
        t.assert_no_secrets();
        let finished = t.finished();
        let text = t.agent_text();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{finished:#}\n{text}");

        // The agent's cwd is the workspace root, with one directory per repository.
        assert!(text.contains("dirs: docs,server,web-app"), "{text}");
        assert!(text.contains("root is cwd: true"), "{text}");
        for dir in ["server", "web-app", "docs"] {
            assert!(text.contains(&format!("alternates {dir}: false")), "{text}");
            assert!(
                text.contains(&format!("branch {dir}: agent/TEST-1")),
                "{text}"
            );
        }
        // Each HTTP repository has its own proxy route (masked); `file://` has none.
        assert!(
            text.contains(&format!("origin server: {}", listener_url())),
            "{text}"
        );
        assert!(
            text.contains(&format!("origin web-app: {}", listener_url())),
            "{text}"
        );
        assert_eq!(text.matches("/[REDACTED]/origin.git").count(), 2, "{text}");
        assert!(
            text.contains(&format!("origin docs: {}", docs_repo.url)),
            "{text}"
        );

        // One result per repository, in workspace order.
        let git = finished["git"].as_array().unwrap();
        assert_eq!(git.len(), 3);
        for (result, ws) in git.iter().zip(&workspaces) {
            assert_eq!(result["componentId"], ws.component_id.to_string());
            assert_eq!(result["branch"], "agent/TEST-1");
        }
        assert_eq!(git[0]["pushed"], true);
        assert_eq!(git[0]["commits"], 1);
        assert_eq!(
            git[0]["headSha"],
            server_repo.branch_head("agent/TEST-1").unwrap()
        );
        assert_eq!(git[1]["pushed"], true);
        assert_eq!(git[1]["commits"], 1);
        assert_eq!(git[2]["pushed"], false, "nothing ahead of main");
        assert_eq!(git[2]["commits"], 0);
        assert_eq!(
            server_repo.file_at("agent/TEST-1", "server.txt").as_deref(),
            Some("change in server\n")
        );
        assert_eq!(
            web_repo.file_at("agent/TEST-1", "wip.txt").as_deref(),
            Some("work in progress in web-app\n")
        );
        assert_eq!(
            web_repo.subjects("agent/TEST-1")[0],
            format!("WIP: uncommitted changes from run {}", spec.run_id)
        );
        assert!(docs_repo.branch_head("agent/TEST-1").is_none());

        // GIT events name their repository.
        for event in t.events_of("GIT") {
            let dir = event["payload"]["dir"].as_str().unwrap();
            let ws = workspaces.iter().find(|w| w.dir == dir).unwrap();
            assert_eq!(event["payload"]["componentId"], ws.component_id.to_string());
        }
        let events = git_events(&t);
        let of = |action: &str| {
            events
                .iter()
                .filter(|(_, a)| a == action)
                .map(|(d, _)| d.as_str())
                .collect::<Vec<_>>()
        };
        assert_eq!(of("cloned"), vec!["server", "web-app", "docs"]);
        assert_eq!(of("committed"), vec!["web-app"]);
        assert_eq!(of("pushed"), vec!["server", "web-app"]);
        assert_eq!(of("nothing_to_push"), vec!["docs"]);
        assert_eq!(
            t.statuses(),
            vec![
                "preparing",
                "cloning",
                "starting",
                "running",
                "finalizing",
                "pushing"
            ]
        );

        // One mirror per repository URL; nothing of the run is left.
        let mut mirrors: Vec<String> = std::fs::read_dir(host.paths.mirrors_dir())
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        mirrors.sort();
        let mut expected: Vec<String> = workspaces
            .iter()
            .map(|w| {
                format!(
                    "{}.git",
                    tinfirm_runner_host::mirror::mirror_key(
                        &w.repo_url,
                        w.token
                            .as_ref()
                            .map(|t| {
                                use base64::Engine;
                                tinfirm_protocol::Secret::new(format!(
                                    "Basic {}",
                                    base64::engine::general_purpose::STANDARD
                                        .encode(format!("x-access-token:{t}"))
                                ))
                            })
                            .as_ref()
                    )
                )
            })
            .collect();
        expected.sort();
        assert_eq!(mirrors, expected);
        assert!(!host.paths.workspace(spec.run_id).exists());
        let proxy = host.host.git_proxy().await.unwrap().unwrap();
        assert_eq!(proxy.route_count(), 0);
        assert_eq!(proxy.mcp_route_count(), 0);

        // A second run of the task clones from the mirrors and continues the branches.
        let mut again = AssignSpec::with_workspaces("multi", workspaces.clone());
        again.task_id = spec.task_id;
        again.agent_id = spec.agent_id;
        again.prompt_extra = " [commit:docs]".into();
        conn.send(again.to_json());
        let t2 = conn.until_finished(again.run_id).await;
        let finished = t2.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{finished:#}");
        assert_eq!(
            finished["git"][0]["commits"], 1,
            "the earlier commit is on the branch"
        );
        assert_eq!(finished["git"][0]["pushed"], true);
        assert_eq!(finished["git"][2]["commits"], 1);
        assert_eq!(finished["git"][2]["pushed"], true);
        assert!(docs_repo.branch_head("agent/TEST-1").is_some());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_failed_push_does_not_stop_the_other_repositories() {
    use common::git_http::{GitHttpServer, test_git_authorization};
    with_timeout(120, async {
        let closed_repo = TestRepo::new();
        closed_repo.reject_pushes();
        let open_repo = TestRepo::new();
        let authorization = test_git_authorization();
        let closed_http =
            GitHttpServer::start(closed_repo.dir.path(), Some(authorization.clone())).await;
        let open_http = GitHttpServer::start(open_repo.dir.path(), Some(authorization)).await;
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let workspaces = vec![
            WorkspaceEntry::new(
                "server",
                "Server",
                &format!("{}/origin.git", closed_http.url),
            ),
            WorkspaceEntry::new("web", "Web", &format!("{}/origin.git", open_http.url)),
        ];
        let mut spec = AssignSpec::with_workspaces("multi", workspaces);
        spec.prompt_extra = " [commit:server] [commit:web]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "GIT_ERROR");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(
            message.starts_with(
                "git failed in `Server` (/workspace/server): the repository refused the push"
            ),
            "{message}"
        );
        assert!(
            message.contains("pushes are closed for this test"),
            "{message}"
        );
        assert!(!message.contains("/workspace/web"), "{message}");
        // The other repository was pushed all the same.
        assert_eq!(finished["git"][0]["pushed"], false);
        assert_eq!(finished["git"][0]["commits"], 1);
        assert_eq!(finished["git"][1]["pushed"], true);
        assert!(closed_repo.branch_head("agent/TEST-1").is_none());
        assert_eq!(
            open_repo.file_at("agent/TEST-1", "web.txt").as_deref(),
            Some("change in web\n")
        );
        let pushed: Vec<_> = git_events(&t)
            .into_iter()
            .filter(|(_, a)| a == "pushed")
            .collect();
        assert_eq!(pushed, vec![("web".to_string(), "pushed".to_string())]);
        // The workspace of the failed run is kept.
        assert!(
            host.paths
                .data_dir
                .join("failed-workspaces")
                .join(spec.run_id.to_string())
                .join("server/.git")
                .exists()
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_read_only_repository_is_never_pushed() {
    use common::git_http::{GitHttpServer, test_git_authorization};
    with_timeout(120, async {
        let docs_repo = TestRepo::new();
        let web_repo = TestRepo::new();
        let authorization = test_git_authorization();
        let docs_http =
            GitHttpServer::start(docs_repo.dir.path(), Some(authorization.clone())).await;
        let web_http = GitHttpServer::start(web_repo.dir.path(), Some(authorization)).await;
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut docs =
            WorkspaceEntry::new("docs", "Docs", &format!("{}/origin.git", docs_http.url));
        docs.read_only = true;
        let workspaces = vec![
            docs,
            WorkspaceEntry::new("web", "Web", &format!("{}/origin.git", web_http.url)),
        ];
        let mut spec = AssignSpec::with_workspaces("multi", workspaces);
        spec.prompt_extra = " [commit:docs] [commit:web]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{finished}");
        // The read-only repository reports its commit but was not pushed; the other one was.
        assert_eq!(finished["git"][0]["pushed"], false);
        assert_eq!(finished["git"][0]["commits"], 1);
        assert_eq!(finished["git"][1]["pushed"], true);
        assert!(docs_repo.branch_head("agent/TEST-1").is_none());
        assert_eq!(receive_packs(&docs_http.seen()), 0);
        assert_eq!(
            web_repo.file_at("agent/TEST-1", "web.txt").as_deref(),
            Some("change in web\n")
        );
        let pushed: Vec<_> = git_events(&t)
            .into_iter()
            .filter(|(_, a)| a == "pushed")
            .collect();
        assert_eq!(pushed, vec![("web".to_string(), "pushed".to_string())]);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// Pushes that reached the repository (`POST …/git-receive-pack`).
fn receive_packs(seen: &[common::git_http::SeenRequest]) -> usize {
    seen.iter()
        .filter(|r| r.method == "POST" && r.path.ends_with("/git-receive-pack"))
        .count()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agent_rebases_its_branch_and_the_host_pushes_it_with_a_lease() {
    use common::git_http::{GitHttpServer, test_git_authorization};
    with_timeout(120, async {
        // The task branch has commit A from an earlier run; `main` moved on since.
        let repo = TestRepo::new();
        let earlier = repo.commit_to_branch(
            "agent/TEST-1",
            "earlier.txt",
            "from an earlier run\n",
            "Earlier work",
        );
        let main = repo.commit_to_main("upstream.txt", "from upstream\n", "Upstream change");
        let http = GitHttpServer::start(repo.dir.path(), Some(test_git_authorization())).await;
        let url = format!("{}/origin.git", http.url);
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::new("rebase", Some(&url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_contiguous_seq();
        t.assert_no_secrets();
        let finished = t.finished();
        let text = t.agent_text();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{finished:#}\n{text}");
        assert!(
            text.contains("before: Earlier work < Initial commit"),
            "{text}"
        );
        assert!(
            text.contains("rebased: Earlier work < Upstream change < Initial commit"),
            "{text}"
        );
        let cloned = t.events_of("GIT")[0];
        assert_eq!(cloned["payload"]["headSha"], earlier);
        assert_eq!(cloned["payload"]["commits"], 1);

        // A' (A rebased onto the new `main`) replaced A: one commit ahead, pushed with the
        // lease on A.
        assert_eq!(finished["git"][0]["pushed"], true, "{finished:#}");
        assert_eq!(finished["git"][0]["commits"], 1);
        let head = repo.branch_head("agent/TEST-1").unwrap();
        assert_ne!(head, earlier);
        assert_eq!(finished["git"][0]["headSha"], head);
        assert_eq!(
            git(&repo.bare, &["rev-parse", "agent/TEST-1^"]).trim(),
            main
        );
        assert_eq!(
            repo.subjects("agent/TEST-1"),
            vec!["Earlier work", "Upstream change", "Initial commit"]
        );
        assert_eq!(
            repo.file_at("agent/TEST-1", "earlier.txt").as_deref(),
            Some("from an earlier run\n")
        );
        let git_actions: Vec<(String, String)> = git_events(&t);
        assert_eq!(
            git_actions.last().unwrap(),
            &("repo".to_string(), "pushed".to_string())
        );
        assert_eq!(receive_packs(&http.seen()), 1);
        assert!(!host.paths.workspace(spec.run_id).exists());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// A run whose agent rebases its branch and commits, while someone else changes the task
/// branch in the repository after the clone and before the finalize (`meanwhile` returns the
/// branch's tip afterwards, `None` when it deleted it). A plain force push would overwrite
/// that; the lease refuses the push instead, and nothing reaches the repository.
async fn the_branch_changes_during_the_run(
    branch_existed: bool,
    meanwhile: impl FnOnce(&TestRepo) -> Option<String>,
) {
    use common::git_http::{GitHttpServer, test_git_authorization};
    let repo = TestRepo::new();
    if branch_existed {
        repo.commit_to_branch(
            "agent/TEST-1",
            "earlier.txt",
            "from an earlier run\n",
            "Earlier work",
        );
    }
    repo.commit_to_main("upstream.txt", "from upstream\n", "Upstream change");
    let http = GitHttpServer::start(repo.dir.path(), Some(test_git_authorization())).await;
    let url = format!("{}/origin.git", http.url);
    let mut server = FakeServer::start().await;
    let host = start_host(&server.url, HostSetup::local()).await;
    let mut conn = server.accept().await;
    conn.handshake(5).await;
    let mut spec = AssignSpec::new("rebase", Some(&url));
    spec.prompt_extra = " [commit:repo] [wait]".into();
    conn.send(spec.to_json());
    loop {
        let frame = conn.next().await.expect("connection open");
        if frame["type"] == "run.finished" {
            panic!("the run finished before the agent waited: {frame:#}");
        }
        if frame["eventType"] == "AGENT_MESSAGE"
            && frame["payload"]["text"]
                .as_str()
                .unwrap()
                .contains("waiting for release")
        {
            break;
        }
    }
    let theirs = meanwhile(&repo);
    release(&host.paths, spec.run_id);
    conn.until_finished(spec.run_id).await;
    let t = run_frames(&conn, spec.run_id);
    t.assert_contiguous_seq();
    t.assert_no_secrets();
    let finished = t.finished();
    let text = t.agent_text();
    assert!(text.contains("rebased: "), "{text}");
    assert_eq!(finished["outcome"], "FAILED", "{finished:#}\n{text}");
    assert_eq!(finished["failureClass"], "GIT_ERROR");
    let message = finished["failureMessage"].as_str().unwrap();
    assert!(
        message.starts_with(
            "git failed in `Repo` (/workspace/repo): the remote branch changed during the run (someone else pushed to it or deleted it); nothing was pushed and nothing was overwritten"
        ),
        "{message}"
    );
    assert_eq!(finished["git"][0]["pushed"], false);
    assert_eq!(
        finished["git"][0]["commits"],
        if branch_existed { 2 } else { 1 }
    );
    // Their change is still there, and the host sent nothing to the repository.
    assert_eq!(repo.branch_head("agent/TEST-1"), theirs);
    assert_eq!(receive_packs(&http.seen()), 0);
    assert!(
        t.events_of("GIT")
            .iter()
            .all(|e| e["payload"]["action"] != "pushed")
    );
    // The agent's work stays on the host, in the kept workspace of the failed run.
    let kept = host
        .paths
        .data_dir
        .join("failed-workspaces")
        .join(spec.run_id.to_string())
        .join("repo");
    assert_eq!(
        std::fs::read_to_string(kept.join("repo.txt")).unwrap(),
        "change in repo\n"
    );
    host.shutdown.cancel();
    host.handle.await.unwrap().unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_push_to_the_branch_during_the_run_is_never_overwritten() {
    with_timeout(120, async {
        the_branch_changes_during_the_run(true, |repo| {
            Some(repo.commit_to_branch(
                "agent/TEST-1",
                "theirs.txt",
                "pushed during the run\n",
                "Their change",
            ))
        })
        .await;
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_branch_created_during_the_run_is_never_overwritten() {
    with_timeout(120, async {
        the_branch_changes_during_the_run(false, |repo| {
            Some(repo.commit_to_branch(
                "agent/TEST-1",
                "theirs.txt",
                "pushed during the run\n",
                "Their change",
            ))
        })
        .await;
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_branch_deleted_during_the_run_is_not_recreated() {
    with_timeout(120, async {
        the_branch_changes_during_the_run(true, |repo| {
            repo.delete_branch("agent/TEST-1");
            None
        })
        .await;
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_credential_behind_a_forged_remote_ref_blocks_every_push() {
    if !logins_reach_the_sandbox() {
        // The agent leaks the run's login, which a VM per run never gets.
        return;
    }
    with_timeout(120, async {
        let leaky = TestRepo::new();
        let other = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let workspaces = vec![
            WorkspaceEntry::new("leaky", "Leaky", &leaky.url),
            WorkspaceEntry::new("other", "Other", &other.url),
        ];
        let mut spec = AssignSpec::with_workspaces("forge-remote-ref", workspaces);
        spec.prompt_extra = " [commit:other]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED", "{finished:#}");
        assert_eq!(finished["failureClass"], "CREDENTIAL_LEAK");
        assert_eq!(
            finished["failureMessage"],
            "a commit contains a credential value; no repository was pushed"
        );
        // The forged `refs/remotes/origin/*` did not hide the commit, and the clean
        // repository was not pushed either.
        assert_eq!(finished["git"][0]["pushed"], false);
        assert_eq!(finished["git"][0]["commits"], 2);
        assert_eq!(finished["git"][1]["pushed"], false);
        assert_eq!(finished["git"][1]["commits"], 1);
        assert!(leaky.branch_head("agent/TEST-1").is_none());
        assert!(other.branch_head("agent/TEST-1").is_none());
        assert!(
            t.text().contains(
                "not pushing any repository: a commit in `Leaky` (/workspace/leaky) contains a credential value"
            ),
            "{}",
            t.text()
        );
        assert!(t.events_of("GIT").iter().all(|e| e["payload"]["action"] != "pushed"));
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_token_that_no_longer_works_fails_the_run_before_the_agent_starts() {
    use common::git_http::{GitHttpServer, test_git_authorization};
    with_timeout(120, async {
        let repo = TestRepo::new();
        let http =
            GitHttpServer::start_forbidding(repo.dir.path(), Some(test_git_authorization())).await;
        let url = format!("{}/origin.git", http.url);
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        // The first run creates the mirror.
        let first = AssignSpec::new("happy", Some(&url));
        conn.send(first.to_json());
        let t = conn.until_finished(first.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.finished());
        let mirror = host.host.mirrors.path_for(
            &url,
            Some(&tinfirm_protocol::Secret::new(test_git_authorization())),
        );
        assert!(mirror.exists());

        // Then the token loses its access: the mirror is not served stale (a new token is a
        // principal of its own, with a mirror of its own, which the server refuses).
        let revoked = "github_pat_TEST_ONLY_revoked_git_token_0123456789";
        let mut entry = WorkspaceEntry::new("repo", "Repo", &url);
        entry.token = Some(revoked.into());
        let second = AssignSpec::with_workspaces("happy", vec![entry]);
        conn.send(second.to_json());
        let t = conn.until_finished(second.run_id).await;
        t.assert_no_secrets();
        assert!(!t.text().contains(revoked));
        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "GIT_ERROR");
        let message = finished["failureMessage"].as_str().unwrap();
        assert!(
            message.starts_with(
                "cannot clone `Repo` (/workspace/repo): cannot update the host's mirror"
            ),
            "{message}"
        );
        assert!(message.contains("403"), "{message}");
        assert!(
            finished.get("git").is_none(),
            "nothing was cloned: {finished:#}"
        );
        assert!(
            !t.statuses().contains(&"starting".to_string()),
            "no sandbox"
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn admission_rejects_invalid_workspace_lists() {
    with_timeout(60, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let cases: Vec<(Vec<WorkspaceEntry>, &str)> = vec![
            (
                vec![
                    WorkspaceEntry::new("server", "Server", &repo.url),
                    WorkspaceEntry::new("server", "Server 2", &repo.url),
                ],
                "appears twice",
            ),
            (
                vec![WorkspaceEntry::new("../escape", "Escape", &repo.url)],
                "invalid workspace dir",
            ),
            (
                vec![WorkspaceEntry::new("", "Empty", &repo.url)],
                "invalid workspace dir",
            ),
            (
                (0..21)
                    .map(|i| WorkspaceEntry::new(&format!("r{i}"), "R", &repo.url))
                    .collect(),
                "at most 20",
            ),
            (
                vec![WorkspaceEntry::new(
                    "web",
                    "Web",
                    "ext::sh -c touch% /tmp/pwned",
                )],
                "repository `web`",
            ),
        ];
        for (workspaces, reason) in cases {
            let spec = AssignSpec::with_workspaces("happy", workspaces);
            conn.send(spec.to_json());
            let rejected = conn.expect("run.rejected").await;
            assert_eq!(rejected["runId"], spec.run_id.to_string());
            let text = rejected["reason"].as_str().unwrap();
            assert!(text.contains(reason), "{text}");
            assert_eq!(rejected["code"], "INVALID_ASSIGNMENT", "{rejected}");
        }
        assert_eq!(host.host.active_run_count(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

// ---------------------------------------------------------------------------------------
// Platform MCP tools (docs/archive/v1/CONVENTIONS.md §15.6)
// ---------------------------------------------------------------------------------------

/// Answers `run.repository_request`, `run.task_create` and `run.ask` frames of `run_id` with
/// `answer` (no answer for `None`) until the run finishes. Returns the requests and the run's
/// frames.
async fn answer_requests(
    conn: &mut Conn,
    run_id: Uuid,
    answer: impl Fn(&serde_json::Value) -> Option<serde_json::Value>,
) -> (Vec<serde_json::Value>, Transcript) {
    let mut requests = Vec::new();
    loop {
        let frame = conn.next().await.expect("connection open");
        if frame["type"] == "run.repository_request"
            || frame["type"] == "run.task_create"
            || frame["type"] == "run.ask"
        {
            requests.push(frame.clone());
            if let Some(ack) = answer(&frame) {
                conn.send(ack);
            }
        }
        if frame["type"] == "run.finished" && frame["runId"] == run_id.to_string() {
            break;
        }
    }
    (requests, run_frames(conn, run_id))
}

const PENDING_MESSAGE: &str = "Requested the repository `Web`. The request is posted on the task, where a member approves or denies it. You may ask more, or request another repository. When you have nothing else to do, stop without writing a summary: Tinfirm wakes you with the decision.";

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agent_requests_repositories_through_the_platform_tools() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.repository_request_timeout = Some(Duration::from_secs(1));
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("request-repo", Some(&repo.url));
        let web = requestable("Web", false);
        spec.requestable = vec![web.clone(), requestable("Infra", true)];
        spec.prompt_extra = " [request:repo] [request:web] [request:Unknown] [request:Slow]".into();
        conn.send(spec.to_json());
        let (requests, t) = answer_requests(&mut conn, spec.run_id, |request| {
            match request["name"].as_str().unwrap() {
                "web" => Some(repository_ack(request, "PENDING", None, PENDING_MESSAGE)),
                "Slow" => None,
                other => Some(repository_ack(
                    request,
                    "REJECTED",
                    Some("UNKNOWN_REPOSITORY"),
                    &format!("There is no repository `{other}` in this project."),
                )),
            }
        })
        .await;
        t.assert_no_secrets();
        let finished = t.finished();
        let text = t.agent_text();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{finished:#}\n{text}");

        // Nothing is sent for a repository of the run; the others are sent once each.
        let names: Vec<&str> = requests.iter().map(|r| r["name"].as_str().unwrap()).collect();
        assert_eq!(names, vec!["web", "Unknown", "Slow"]);
        let web_request = &requests[0];
        assert_eq!(web_request["runId"], spec.run_id.to_string());
        assert_eq!(web_request["epoch"], 1);
        assert_eq!(web_request["componentId"], web["componentId"], "matched ignoring case");
        assert_eq!(web_request["reason"], "The task needs web.");
        assert!(requests[1].get("componentId").is_none());
        let ids: std::collections::HashSet<&str> = requests
            .iter()
            .map(|r| r["requestId"].as_str().unwrap())
            .collect();
        assert_eq!(ids.len(), 3, "one requestId per tool call");

        // What the agent saw.
        for line in [
            "mcp: 1 server(s) from session/new",
            "/[REDACTED]/mcp",
            "initialize: 200 tinfirm 2025-06-18",
            "initialized: 202",
            "tools: list_repositories,request_repository,create_task",
            "repositories: /workspace/repo; requestable: Web=false,Infra=true",
            "request repo: error: The repository `Repo` is already on the task, at /workspace/repo.",
            &format!("request web: ok: {PENDING_MESSAGE}"),
            "request Unknown: error: There is no repository `Unknown` in this project.",
            "request Slow: error: Tinfirm did not answer the request in time. Say in your summary that you need the repository `Slow` and why.",
            "repositories: /workspace/repo; requestable: Web=true,Infra=true",
            "get: 405",
        ] {
            assert!(text.contains(line), "missing `{line}` in:\n{text}");
        }
        assert!(text.contains(&format!("mcp url: {}", listener_url())), "{text}");
        // The MCP secret is masked, and masking it is no leak.
        assert!(t.of_type("credential.status").is_empty());
        assert!(
            !t.events_of("LOG")
                .iter()
                .any(|e| e["payload"]["message"].as_str().unwrap().contains("MCP")),
            "no fallback warning"
        );
        // The run is over: its endpoint and its waiting requests are gone.
        assert_eq!(host.host.waiting_platform_requests(), 0);
        let proxy = host.host.git_proxy().await.unwrap().unwrap();
        assert_eq!(proxy.mcp_route_count(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

const CREATED_MESSAGE: &str =
    "Created the task ACME-8 in the backlog of the project, assigned to you.";

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agent_creates_tasks_through_the_platform_tools() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.repository_request_timeout = Some(Duration::from_secs(1));
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("request-repo", None);
        spec.prompt_extra = " [task:Retry webhooks] [task:Too many] [task:Slow]".into();
        conn.send(spec.to_json());
        let (requests, t) = answer_requests(&mut conn, spec.run_id, |request| {
            assert_eq!(request["type"], "run.task_create");
            match request["title"].as_str().unwrap() {
                "Retry webhooks" => Some(task_ack(request, "CREATED", None, CREATED_MESSAGE)),
                "Slow" => None,
                _ => Some(task_ack(
                    request,
                    "REJECTED",
                    Some("TOO_MANY_TASKS"),
                    "This run has already created 5 tasks.",
                )),
            }
        })
        .await;
        t.assert_no_secrets();
        let text = t.agent_text();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{text}");
        let titles: Vec<&str> = requests.iter().map(|r| r["title"].as_str().unwrap()).collect();
        assert_eq!(titles, vec!["Retry webhooks", "Too many", "Slow"]);
        assert_eq!(requests[0]["runId"], spec.run_id.to_string());
        assert_eq!(requests[0]["epoch"], 1);
        assert_eq!(
            requests[0]["description"],
            "Found while working on the task: Retry webhooks."
        );
        assert!(
            requests[0].get("repositories").is_none(),
            "the server takes the task's repositories"
        );
        for line in [
            &format!("task Retry webhooks: ok: {CREATED_MESSAGE}"),
            "task Too many: error: This run has already created 5 tasks.",
            "task Slow: error: Tinfirm did not answer in time, so it is not known whether the task was created. Describe the task you wanted to create in your summary instead.",
        ] {
            assert!(text.contains(line), "missing `{line}` in:\n{text}");
        }
        assert_eq!(host.host.waiting_platform_requests(), 0);
        // An ack of the wrong kind for a request id is not taken as the answer.
        conn.send(repository_ack(&requests[2], "PENDING", None, PENDING_MESSAGE));
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert!(conn.frames.iter().all(|f| f["type"] != "error"));
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

const ASK_MESSAGE: &str = "Your question is posted on the task, where a person answers it. Ask any other question you need, one per call. When you have nothing else to do, stop without writing a summary: Tinfirm wakes you with all the answers at once.";

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agent_asks_and_reports_its_result_through_the_platform_tools() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.repository_request_timeout = Some(Duration::from_secs(1));
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("request-repo", None);
        spec.prompt_extra =
            " [ask:Retry 4xx answers too?] [ask:Too many] [ask:Slow] [finish:COMPLETED] [finish:BLOCKED]"
                .into();
        conn.send(spec.to_json());
        let (requests, t) = answer_requests(&mut conn, spec.run_id, |request| {
            assert_eq!(request["type"], "run.ask");
            match request["question"].as_str().unwrap() {
                "Retry 4xx answers too?" => Some(ask_ack(request, "PENDING", None, ASK_MESSAGE)),
                "Slow" => None,
                _ => Some(ask_ack(
                    request,
                    "REJECTED",
                    Some("TOO_MANY_ASKS"),
                    "This run has already asked 10 questions.",
                )),
            }
        })
        .await;
        t.assert_no_secrets();
        let text = t.agent_text();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{text}");

        // One run.ask per call, with its options once each (ignoring case).
        let questions: Vec<&str> = requests.iter().map(|r| r["question"].as_str().unwrap()).collect();
        assert_eq!(questions, vec!["Retry 4xx answers too?", "Too many", "Slow"]);
        assert_eq!(requests[0]["runId"], spec.run_id.to_string());
        assert_eq!(requests[0]["epoch"], 1);
        assert_eq!(requests[0]["options"], json!(["Yes", "No"]));
        for line in [
            &format!("ask Retry 4xx answers too?: ok: {ASK_MESSAGE}"),
            "ask Too many: error: This run has already asked 10 questions.",
            "ask Slow: error: Tinfirm did not answer in time, so it is not known whether the question was posted. Write the question in your summary instead, and finish your turn.",
            "finish COMPLETED: ok: Recorded your result: completed.",
            "finish BLOCKED: ok: Recorded your result: blocked. It replaces your earlier result.",
        ] {
            assert!(text.contains(line), "missing `{line}` in:\n{text}");
        }

        // finish_run sent nothing during the run; the last call is run.finished.result, and the
        // last message stays the summary.
        assert_eq!(
            finished["result"],
            json!({
                "outcome": "BLOCKED",
                "summary": "Finished as BLOCKED.",
                "nextSteps": ["Review the change"],
                "openQuestions": ["Is BLOCKED right?"],
            })
        );
        assert!(
            finished["summary"].as_str().unwrap().contains("finish BLOCKED"),
            "{finished:#}"
        );
        assert_eq!(host.host.waiting_platform_requests(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agent_uploads_files_through_the_platform_tools() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("request-repo", None);
        spec.prompt_extra = " [upload:report.md] [upload:/etc/hostname]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let text = t.agent_text();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{text}");
        assert!(
            text.contains("upload report.md: ok: Attached the file."),
            "{text}"
        );
        assert!(
            text.contains("upload /etc/hostname: error: Only files under "),
            "{text}"
        );

        // One upload, with the host token, to the run's path and epoch.
        let uploads = server.uploads.lock().unwrap().clone();
        assert_eq!(uploads.len(), 1, "{uploads:?}");
        let upload = &uploads[0];
        assert_eq!(
            upload.path,
            format!("/api/runner/v1/runs/{}/attachments?epoch=1", spec.run_id)
        );
        assert!(upload.authorization.starts_with("Bearer tfr_"));
        let boundary = upload
            .content_type
            .strip_prefix("multipart/form-data; boundary=")
            .expect("multipart");
        let body = String::from_utf8(upload.body.clone()).unwrap();
        assert!(body.starts_with(&format!("--{boundary}\r\n")), "{body}");
        assert!(body.contains("Content-Disposition: form-data; name=\"file\"; filename=\"report.md\"; filename*=UTF-8''report%2Emd\r\nContent-Type: text/markdown\r\n\r\nThe report report.md.\n\r\n"), "{body}");
        assert!(body.contains("Content-Disposition: form-data; name=\"note\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nAbout report.md.\r\n"), "{body}");
        assert!(body.ends_with(&format!("--{boundary}--\r\n")), "{body}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_agent_without_mcp_over_http_gets_no_platform_tools() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::local();
        setup.executor = local_executor_without_mcp_http();
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("request-repo", None);
        spec.requestable = vec![requestable("Web", false)];
        spec.prompt_extra = " [request:Web]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let text = t.agent_text();
        assert_eq!(t.finished()["outcome"], "SUCCEEDED");
        assert_eq!(t.finished()["git"], json!([]));
        assert!(text.contains("mcp: 0 server(s) from session/new"), "{text}");
        assert!(text.contains("no tinfirm MCP server"), "{text}");
        let warnings: Vec<&str> = t
            .events_of("LOG")
            .iter()
            .filter(|e| e["payload"]["level"] == "warn")
            .map(|e| e["payload"]["message"].as_str().unwrap())
            .collect();
        assert!(
            warnings
                .iter()
                .any(|w| w.contains("does not support MCP over HTTP")),
            "{warnings:?}"
        );
        assert!(t.of_type("run.repository_request").is_empty());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_resumed_session_gets_the_platform_tools_in_session_load() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let first = AssignSpec::new("codex-usage", None);
        conn.send(first.to_json());
        let t = conn.until_finished(first.run_id).await;
        let session_id = t.finished()["sessionId"].as_str().unwrap().to_string();

        let mut second = AssignSpec::new("codex-usage", None);
        second.agent_id = first.agent_id;
        second.task_id = first.task_id;
        second.requestable = vec![requestable("Web", false)];
        second.resume_session_id = Some(session_id.clone());
        second.resume_prompt = Some("[scenario:request-repo] [request:Web] New comments.".into());
        conn.send(second.to_json());
        let (requests, t) = answer_requests(&mut conn, second.run_id, |request| {
            Some(repository_ack(request, "PENDING", None, PENDING_MESSAGE))
        })
        .await;
        let text = t.agent_text();
        assert!(t.text().contains(&format!("resumed session {session_id}")));
        assert!(
            text.contains("mcp: 1 server(s) from session/load"),
            "{text}"
        );
        assert!(
            text.contains(&format!("request Web: ok: {PENDING_MESSAGE}")),
            "{text}"
        );
        assert_eq!(requests.len(), 1);
        assert_eq!(t.finished()["outcome"], "SUCCEEDED");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_waiting_repository_request_is_sent_again_after_a_reconnect() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::new("request-repo", None);
        spec.requestable = vec![requestable("Web", false)];
        spec.prompt_extra = " [request:Web]".into();
        conn.send(spec.to_json());
        let first = conn.expect("run.repository_request").await;
        assert_eq!(host.host.waiting_platform_requests(), 1);
        // The connection dies before the server answers.
        conn.kill();
        while conn.next().await.is_some() {}
        let mut conn2 = server.accept().await;
        conn2.handshake(5).await;
        let again = conn2.expect("run.repository_request").await;
        assert_eq!(
            again["requestId"], first["requestId"],
            "the same frame again"
        );
        assert_eq!(again, first);
        conn2.send(repository_ack(&again, "PENDING", None, PENDING_MESSAGE));
        conn2.until_finished(spec.run_id).await;
        let t = run_frames(&conn2, spec.run_id);
        let text = t.agent_text();
        assert!(
            text.contains(&format!("request Web: ok: {PENDING_MESSAGE}")),
            "{text}"
        );
        assert_eq!(t.finished()["outcome"], "SUCCEEDED");
        assert_eq!(host.host.waiting_platform_requests(), 0);
        // A late duplicate ack is ignored.
        conn2.send(repository_ack(&again, "PENDING", None, PENDING_MESSAGE));
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert!(conn2.frames.iter().all(|f| f["type"] != "error"));
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn downloads_the_attachments_before_the_agent_starts_and_removes_them_after() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;

        let design = Uuid::new_v4();
        let deleted = Uuid::new_v4();
        server
            .files
            .lock()
            .unwrap()
            .insert(design.to_string(), b"design v2".to_vec());
        let mut spec = AssignSpec::new("attachments", None);
        spec.attachments = vec![
            json!({"id": design, "fileName": "design notes.md", "sizeBytes": 9, "sha256": sha256_hex(b"design v2")}),
            // Deleted on the server after the assignment: 404, skipped.
            json!({"id": deleted, "fileName": "gone.txt", "sizeBytes": 1, "sha256": "0".repeat(64)}),
        ];
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;

        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(
            t.agent_text()
                .contains("attachments: design notes.md=design v2 (444)"),
            "{}",
            t.agent_text()
        );
        assert!(
            t.text()
                .contains("attachments deleted before the run started: gone.txt"),
            "{}",
            t.text()
        );
        t.assert_no_secrets();
        let downloads = server.downloads.lock().unwrap().clone();
        assert_eq!(downloads.len(), 2);
        assert_eq!(
            downloads[0].path,
            format!(
                "/api/runner/v1/runs/{}/attachments/{design}?epoch=1",
                spec.run_id
            )
        );
        assert_eq!(
            downloads[0].authorization,
            format!("Bearer {TEST_HOST_TOKEN}")
        );
        assert!(!host.paths.attachments(spec.run_id).exists());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_attachment_that_does_not_match_its_hash_fails_the_run_before_the_agent_starts() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;

        let id = Uuid::new_v4();
        server
            .files
            .lock()
            .unwrap()
            .insert(id.to_string(), b"tampered".to_vec());
        let mut spec = AssignSpec::new("attachments", None);
        spec.attachments = vec![
            json!({"id": id, "fileName": "a.txt", "sizeBytes": 8, "sha256": sha256_hex(b"original")}),
        ];
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;

        let finished = t.finished();
        assert_eq!(finished["outcome"], "FAILED");
        assert_eq!(finished["failureClass"], "INFRA");
        assert!(
            finished["failureMessage"]
                .as_str()
                .unwrap()
                .contains("does not match")
        );
        assert!(!t.statuses().contains(&"running".to_string()));
        assert!(!host.paths.attachments(spec.run_id).exists());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_attachment_with_a_path_as_its_name_is_rejected() {
    with_timeout(60, async {
        let mut server = FakeServer::start().await;
        let _host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        conn.handshake(1).await;

        let mut spec = AssignSpec::new("attachments", None);
        spec.attachments = vec![
            json!({"id": Uuid::new_v4(), "fileName": "../../.ssh/authorized_keys", "sizeBytes": 1, "sha256": "0".repeat(64)}),
        ];
        conn.send(spec.to_json());
        let rejected = conn.expect("run.rejected").await;
        assert!(
            rejected["reason"]
                .as_str()
                .unwrap()
                .contains("not a plain file name")
        );
        assert!(server.downloads.lock().unwrap().is_empty());
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_host_without_mounts_lists_no_skills_and_rejects_a_run_with_skills() {
    with_timeout(60, async {
        let mut server = FakeServer::start().await;
        let setup = HostSetup {
            executor: local_executor(),
            ..HostSetup::v2()
        };
        let _host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        // The local test executor has no mounts: it does not list `run.skills` (ADR-0064). Over
        // the bridge it says so, for the server's licence (ADR-0100).
        let mut features = vec!["run.steer", "session.bundle"];
        if bridged_tests() {
            features.push("sandbox.bridge");
        }
        assert_eq!(hello["features"], json!(features));
        let skill = |name: &str| {
            json!({"skillId": Uuid::new_v4(), "version": 1, "name": name, "sha256": "0".repeat(64), "sizeBytes": 10})
        };

        let spec = AssignSpec::v2("platform-v2", None);
        let mut frame = spec.to_json();
        frame["skills"] = json!([skill("pdf")]);
        conn.send(frame);
        let rejected = conn.expect("run.rejected").await;
        assert_eq!(rejected["runId"], spec.run_id.to_string());
        assert_eq!(rejected["code"], "INVALID_ASSIGNMENT");
        assert!(
            rejected["reason"]
                .as_str()
                .unwrap()
                .contains("does not list the feature run.skills"),
            "{rejected}"
        );

        // A list the schema refuses is refused as such, whatever the host.
        let spec = AssignSpec::v2("platform-v2", None);
        let mut frame = spec.to_json();
        frame["skills"] = json!([skill("pdf"), skill("pdf")]);
        conn.send(frame);
        let rejected = conn.expect("run.rejected").await;
        assert_eq!(rejected["code"], "INVALID_ASSIGNMENT");
        assert!(
            rejected["reason"]
                .as_str()
                .unwrap()
                .contains("skill `pdf` appears twice"),
            "{rejected}"
        );
        assert!(server.downloads.lock().unwrap().is_empty());
    })
    .await;
}

// ---------------------------------------------------------------------------------------
// Protocol v2
// ---------------------------------------------------------------------------------------

/// Answers the `run.request` frames of `run_id` with `answer` (no answer for `None`) until the
/// run finishes. Returns the requests and the run's frames.
async fn answer_v2_requests(
    conn: &mut Conn,
    run_id: Uuid,
    answer: impl Fn(&serde_json::Value) -> Option<serde_json::Value>,
) -> (Vec<serde_json::Value>, Transcript) {
    let mut requests = Vec::new();
    loop {
        let frame = conn.next().await.expect("connection open");
        if frame["type"] == "run.request" {
            requests.push(frame.clone());
            if let Some(ack) = answer(&frame) {
                conn.send(ack);
            }
        }
        if frame["type"] == "run.finished" && frame["runId"] == run_id.to_string() {
            break;
        }
    }
    (requests, run_frames(conn, run_id))
}

const V2_ASK_MESSAGE: &str = "Your question is posted on the work item, where a person answers it. Ask any other question you need, one per call. When you have nothing else to do, stop without writing a summary: Tinfirm wakes you with all the answers at once.";

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_v2_host_uses_the_generic_request_the_server_tools_and_the_output_folder() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::v2();
        setup.repository_request_timeout = Some(Duration::from_secs(1));
        setup.tool_request_timeout = Some(Duration::from_secs(2));
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        assert_eq!(hello["protocolVersion"], 2);
        assert_eq!(hello["isolationTiers"], json!(["container"]));

        let mut spec = AssignSpec::v2("platform-v2", Some(&repo.url));
        spec.requestable = vec![requestable("Web", false)];
        spec.prompt_extra = " [access:Web] [access-tool:Jira] [tool:jira_get] [tool:jira_search] [link:https://wiki.example.com/q3] [save:notes.md] [output:report.md] [output:reports/q3.csv] [ask:Which quarter?] [ask:Slow] [finish:COMPLETED]".into();
        conn.send(spec.to_json());
        let artifact_id = Uuid::new_v4();
        let decision_id = Uuid::new_v4();
        let (requests, t) = answer_v2_requests(&mut conn, spec.run_id, |request| {
            let payload = &request["payload"];
            match request["kind"].as_str().unwrap() {
                "ACCESS" => Some(request_ack(
                    request,
                    "PENDING",
                    Some(json!({"decisionId": decision_id})),
                    None,
                    Some("Requested. Stop when you have nothing else to do."),
                )),
                "TOOL" if payload["tool"] == "jira_get" => Some(request_ack(
                    request,
                    "DONE",
                    Some(json!({
                        "content": [{"type": "text", "text": "SHOP-1: Checkout fails for guests (Done)"}],
                        "structuredContent": {"key": "SHOP-1", "status": "Done"},
                        "isError": false
                    })),
                    None,
                    None,
                )),
                "TOOL" => Some(request_ack(
                    request,
                    "REJECTED",
                    None,
                    Some("RESULT_TOO_LARGE"),
                    Some("The result is larger than Tinfirm carries to you. Narrow the query."),
                )),
                "ARTIFACT" => Some(request_ack(
                    request,
                    "DONE",
                    Some(json!({"artifactId": artifact_id})),
                    None,
                    None,
                )),
                "ASK" if payload["question"] == "Slow" => None,
                "ASK" => Some(request_ack(
                    request,
                    "PENDING",
                    Some(json!({"decisionId": Uuid::new_v4()})),
                    None,
                    None,
                )),
                other => panic!("unexpected kind {other}"),
            }
        })
        .await;
        t.assert_no_secrets();
        t.assert_contiguous_seq();
        let finished = t.finished();
        let text = t.agent_text();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{finished:#}\n{text}");

        // Every platform request went over the generic frame, none over a v1 frame.
        let kinds: Vec<&str> = requests.iter().map(|r| r["kind"].as_str().unwrap()).collect();
        assert_eq!(
            kinds,
            vec!["ACCESS", "ACCESS", "TOOL", "TOOL", "ARTIFACT", "ASK", "ASK"]
        );
        for frame in &conn.frames {
            let t = frame["type"].as_str().unwrap();
            assert!(
                !t.starts_with("run.ask") && !t.starts_with("run.repository_request") && !t.starts_with("run.task_create"),
                "a v1 frame from a v2 host: {t}"
            );
        }
        assert_eq!(requests[0]["payload"], json!({"repository": "Web", "reason": "The request needs Web."}));
        assert_eq!(requests[1]["payload"], json!({"tool": "Jira", "reason": "The request needs the Jira tools."}));
        assert_eq!(requests[2]["payload"], json!({"tool": "jira_get", "arguments": {"key": "SHOP-1"}}));
        assert_eq!(requests[4]["payload"], json!({"url": "https://wiki.example.com/q3", "title": "Link"}));
        assert_eq!(requests[5]["payload"], json!({"question": "Which quarter?", "options": ["Yes", "No"]}));
        assert_eq!(requests[5]["epoch"], 1);
        let ids: std::collections::HashSet<&str> = requests
            .iter()
            .map(|r| r["requestId"].as_str().unwrap())
            .collect();
        assert_eq!(ids.len(), 7, "one requestId per tool call");

        // What the agent saw.
        for line in [
            "output dir: set",
            "tools: list_repositories,ask,finish,request_access,save_artifact,jira_get,jira_search",
            "repositories: /workspace/repo; requestable: Web=false",
            "access Web: ok: Requested. Stop when you have nothing else to do.",
            "access-tool Jira: ok: Requested. Stop when you have nothing else to do.",
            "tool jira_get: ok: SHOP-1: Checkout fails for guests (Done) structured={\"key\":\"SHOP-1\",\"status\":\"Done\"}",
            "tool jira_search: error: The result is larger than Tinfirm carries to you. Narrow the query.",
            "link https://wiki.example.com/q3: ok: Saved the link `Link` as an artifact of the job.",
            "save notes.md: ok: Attached the file.",
            &format!("ask Which quarter?: ok: {V2_ASK_MESSAGE}"),
            "ask Slow: error: Tinfirm did not answer in time, so it is not known whether the question was posted. Write the question in your summary instead, and finish your turn.",
            "finish COMPLETED: ok: Recorded your result: completed. Stop now: end your turn without further work. When the run ends, Tinfirm posts your summary on the work item for review.",
            "ask_human: rpc error -32602: unknown tool `ask_human`",
        ] {
            assert!(text.contains(line), "missing `{line}` in:\n{text}");
        }
        assert!(text.contains("instructions: Tinfirm platform tools for this run, which works on the requ"), "{text}");

        // The files of /output were saved as artifacts, after the file of save_artifact; all
        // three uploads carry the artifact flag and the host token, and the source says who
        // named the file: the agent's save_artifact, or the host from /output.
        let uploads = server.uploads.lock().unwrap().clone();
        let names: Vec<String> = uploads.iter().map(Upload::file_name).collect();
        assert_eq!(names, vec!["notes.md", "report.md", "reports/q3.csv"]);
        for (upload, source) in uploads.iter().zip(["save_artifact", "output", "output"]) {
            assert_eq!(
                upload.path,
                format!(
                    "/api/runner/v1/runs/{}/attachments?epoch=1&artifact=true&source={source}",
                    spec.run_id
                )
            );
            assert!(upload.authorization.starts_with("Bearer tfr_"));
        }
        assert_eq!(uploads[1].file_content(), b"Output report.md.\n");
        assert_eq!(uploads[2].file_content(), b"Output reports/q3.csv.\n");
        assert_eq!(
            finished["artifacts"],
            json!([
                {"name": "report.md", "attachmentId": uploads[1].attachment_id, "sizeBytes": 18},
                {"name": "reports/q3.csv", "attachmentId": uploads[2].attachment_id, "sizeBytes": 23},
            ])
        );
        assert_eq!(finished["result"]["outcome"], "COMPLETED");
        let logs: Vec<&str> = t
            .events_of("LOG")
            .iter()
            .map(|e| e["payload"]["message"].as_str().unwrap())
            .collect();
        assert!(logs.contains(&"saved /output/report.md as an artifact (18 bytes)"), "{logs:?}");
        assert!(logs.contains(&"2 file(s) of /output saved as artifacts of the job"), "{logs:?}");
        // The run pushed as usual; the output folder went with the workspace.
        assert_eq!(finished["git"][0]["branch"], "agent/TEST-1");
        assert!(!host.paths.output(spec.run_id).exists());
        assert_eq!(host.host.waiting_platform_requests(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn the_agents_effort_reaches_each_runtime_its_own_way() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let run = |runtime: &'static str, access: &str, effort: Option<&'static str>| {
            let mut spec = AssignSpec::v2("model-env", None).with_model_access(access);
            spec.runtime = runtime;
            spec.effort = effort;
            spec
        };

        // Claude Code, Codex and pi take it from their environment, not from the session.
        let cases = [
            ("CLAUDE_CODE", "anthropic", "CLAUDE_CODE_EFFORT_LEVEL=xhigh"),
            ("CODEX", "openai", r#""model_reasoning_effort":"xhigh""#),
            ("PI", "anthropic", "TINFIRM_THINKING_LEVEL=xhigh"),
        ];
        for (runtime, access, expected) in cases {
            let spec = run(runtime, access, Some("XHIGH"));
            conn.send(spec.to_json());
            let t = conn.until_finished(spec.run_id).await;
            assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
            let text = t.agent_text();
            assert!(text.contains(expected), "{runtime}: {expected} in {text}");
            assert!(text.contains("effort-option=-"), "{runtime}: {text}");
        }

        // OpenCode over ACP: the model offers no xhigh, so the nearest level below it.
        let spec = run("OPENCODE", "anthropic", Some("XHIGH"));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let text = t.agent_text();
        assert!(text.contains("effort-option=high"), "{text}");
        assert!(text.contains("CLAUDE_CODE_EFFORT_LEVEL=-"), "{text}");
        assert!(text.contains("TINFIRM_THINKING_LEVEL=-"), "{text}");
        let logs: Vec<&str> = t
            .events_of("LOG")
            .iter()
            .map(|e| e["payload"]["message"].as_str().unwrap())
            .collect();
        assert!(
            logs.contains(&"effort high: the model offers no xhigh"),
            "{logs:?}"
        );

        // The agent went back to the runtime's default: the resumed session, which kept the
        // level, returns to the option's default.
        let session_id = t.finished()["sessionId"].as_str().unwrap().to_string();
        let mut resumed = run("OPENCODE", "anthropic", None);
        resumed.agent_id = spec.agent_id;
        resumed.task_id = spec.task_id;
        resumed.resume_session_id = Some(session_id);
        conn.send(resumed.to_json());
        let t = conn.until_finished(resumed.run_id).await;
        assert_eq!(t.finished()["resumed"], true, "{}", t.text());
        let text = t.agent_text();
        assert!(text.contains("effort-option=default"), "{text}");
        let logs: Vec<&str> = t
            .events_of("LOG")
            .iter()
            .map(|e| e["payload"]["message"].as_str().unwrap())
            .collect();
        assert!(
            logs.contains(&"effort default (the session had high)"),
            "{logs:?}"
        );

        // A new thread without an effort: nothing is set anywhere.
        let spec = run("OPENCODE", "anthropic", None);
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        let text = t.agent_text();
        assert!(text.contains("effort-option=-"), "{text}");
        assert!(text.contains("CLAUDE_CODE_EFFORT_LEVEL=-"), "{text}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_credential_in_an_output_file_keeps_it_on_the_host_and_the_run_goes_on() {
    if !logins_reach_the_sandbox() {
        // The agent leaks the run's login, which a VM per run never gets.
        return;
    }
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::v2("platform-v2", None);
        spec.prompt_extra = " [output:ok.md] [output-secret:env.txt] [finish:COMPLETED]".into();
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        t.assert_no_secrets();
        let finished = t.finished();
        assert_eq!(finished["outcome"], "SUCCEEDED", "{}", t.text());
        assert_eq!(finished["git"], json!([]));
        let uploads = server.uploads.lock().unwrap().clone();
        assert_eq!(uploads.len(), 1, "{uploads:?}");
        assert_eq!(uploads[0].file_name(), "ok.md");
        assert_eq!(finished["artifacts"].as_array().unwrap().len(), 1);
        assert_eq!(finished["artifacts"][0]["name"], "ok.md");
        let warnings: Vec<&str> = t
            .events_of("LOG")
            .iter()
            .filter(|e| e["payload"]["level"] == "warn")
            .map(|e| e["payload"]["message"].as_str().unwrap())
            .collect();
        assert_eq!(
            warnings,
            vec![
                "/output/env.txt was not saved: it holds a credential or a secret of this run, so it does not leave the runner host"
            ]
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_v2_host_ends_the_connection_when_the_welcome_has_no_version() {
    with_timeout(60, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        let hello = conn.expect("hello").await;
        assert_eq!(hello["protocolVersion"], 2);
        // A server that speaks v1 only: a welcome without protocolVersion.
        conn.send(json!({
            "type": "welcome",
            "runnerHostId": conn.runner_host_id,
            "companyId": COMPANY_ID,
            "heartbeatIntervalSeconds": 5,
            "leaseTtlSeconds": 30
        }));
        let error = conn.expect("error").await;
        assert_eq!(error["code"], "UNSUPPORTED_PROTOCOL");
        let message = error["message"].as_str().unwrap();
        assert!(
            message.contains("welcome without protocolVersion"),
            "{message}"
        );
        assert!(message.contains("--protocol-version 1"), "{message}");
        // Closed with 1002, and no second connection: the host exits with an error.
        while conn.next().await.is_some() {}
        assert_eq!(
            conn.close_frame.lock().unwrap().as_ref().map(|c| c.0),
            Some(1002)
        );
        let result = host.handle.await.unwrap();
        let err = result.expect_err("a fatal end");
        assert!(
            format!("{err:#}").contains("welcome without protocolVersion"),
            "{err:#}"
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
async fn a_v2_request_that_waits_is_sent_again_after_a_reconnect() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::v2("platform-v2", None);
        spec.prompt_extra = " [tool:jira_get]".into();
        conn.send(spec.to_json());
        let first = conn.expect("run.request").await;
        assert_eq!(first["kind"], "TOOL");
        assert_eq!(host.host.waiting_platform_requests(), 1);
        conn.kill();
        while conn.next().await.is_some() {}
        let mut conn2 = server.accept().await;
        conn2.handshake(5).await;
        let again = conn2.expect("run.request").await;
        assert_eq!(again, first, "the same frame again");
        conn2.send(request_ack(
            &again,
            "DONE",
            Some(json!({"content": [{"type": "text", "text": "SHOP-1"}], "isError": false})),
            None,
            None,
        ));
        conn2.until_finished(spec.run_id).await;
        let t = run_frames(&conn2, spec.run_id);
        assert!(
            t.agent_text().contains("tool jira_get: ok: SHOP-1"),
            "{}",
            t.agent_text()
        );
        assert_eq!(t.finished()["outcome"], "SUCCEEDED");
        assert_eq!(host.host.waiting_platform_requests(), 0);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_v2_host_reports_whether_a_run_resumed_its_session() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let first = AssignSpec::v2("codex-usage", None);
        conn.send(first.to_json());
        let t1 = conn.until_finished(first.run_id).await;
        assert_eq!(t1.finished()["outcome"], "SUCCEEDED", "{}", t1.text());
        assert_eq!(t1.finished()["resumed"], false);
        assert_eq!(t1.finished()["resumeOutcome"], "FIRST_RUN");
        let session_id = t1.finished()["sessionId"].as_str().unwrap().to_string();

        // The session is in the home: the run resumes it with the resume prompt.
        let follow_up = |resume: &str| {
            let mut spec = AssignSpec::v2("codex-usage", None);
            spec.agent_id = first.agent_id;
            spec.task_id = first.task_id;
            spec.resume_session_id = Some(resume.into());
            spec.resume_prompt = Some("[scenario:codex-usage] RESUME-ONLY".into());
            spec
        };
        let second = follow_up(&session_id);
        conn.send(second.to_json());
        let t2 = conn.until_finished(second.run_id).await;
        assert_eq!(t2.finished()["outcome"], "SUCCEEDED", "{}", t2.text());
        assert!(t2.text().contains(&format!("resumed session {session_id}")));
        assert!(t2.agent_text().contains("RESUME-ONLY"));
        assert_eq!(t2.finished()["resumed"], true);
        assert!(t2.finished().get("resumeOutcome").is_none());

        // In the same home, the agent does not know the id (-32002): NO_SESSION.
        let third = follow_up("fake-not-in-this-home");
        conn.send(third.to_json());
        let t3 = conn.until_finished(third.run_id).await;
        assert_eq!(t3.finished()["outcome"], "SUCCEEDED", "{}", t3.text());
        assert!(!t3.agent_text().contains("RESUME-ONLY"));
        assert!(t3.agent_text().contains("Do the test task."));
        assert_eq!(t3.finished()["resumed"], false);
        assert_eq!(t3.finished()["resumeOutcome"], "NO_SESSION");
        assert!(t3.text().contains("session/load failed (NO_SESSION)"));

        // A home that is new for the run holds no session, as on a host that did not run the
        // previous turn: NO_SESSION.
        let mut elsewhere = follow_up(&session_id);
        elsewhere.task_id = Uuid::new_v4();
        conn.send(elsewhere.to_json());
        let t4 = conn.until_finished(elsewhere.run_id).await;
        assert_eq!(t4.finished()["outcome"], "SUCCEEDED", "{}", t4.text());
        assert_eq!(t4.finished()["resumed"], false);
        assert_eq!(t4.finished()["resumeOutcome"], "NO_SESSION");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_v2_host_reports_cache_writes_apart_from_input() {
    with_timeout(120, async {
        let repo = TestRepo::new();
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::v2("happy", Some(&repo.url));
        conn.send(spec.to_json());
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let usage = &t.finished()["usage"];
        assert_eq!(usage["inputTokens"], 120);
        assert_eq!(usage["cachedInputTokens"], 30);
        assert_eq!(usage["cacheWriteTokens"], 15);
        assert_eq!(usage["outputTokens"], 45);
        assert_eq!(usage["totalTokens"], 210);
        assert_eq!(t.events_of("USAGE").last().unwrap()["payload"], *usage);

        // Codex reports no cache writes.
        let mut codex = AssignSpec::v2("codex-usage", None);
        codex.runtime = "CODEX";
        codex.credential = "openai-test";
        conn.send(codex.to_json());
        let t = conn.until_finished(codex.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert_eq!(t.finished()["usage"]["cacheWriteTokens"], 0);
        assert_eq!(t.finished()["usage"]["totalTokens"], 7000);
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

/// A first run on the work item on a v2 host, then a follow-up run that tries to resume its
/// session; the follow-up's transcript.
async fn first_then_follow_up_v2(executor: tinfirm_runner_host::sandbox::Executor) -> Transcript {
    let mut server = FakeServer::start().await;
    let mut setup = HostSetup::v2();
    setup.executor = executor;
    let host = start_host(&server.url, setup).await;
    let mut conn = server.accept().await;
    conn.handshake(5).await;
    let first = AssignSpec::v2("codex-usage", None);
    conn.send(first.to_json());
    let t1 = conn.until_finished(first.run_id).await;
    assert_eq!(t1.finished()["resumeOutcome"], "FIRST_RUN");
    let mut second = AssignSpec::v2("codex-usage", None);
    second.agent_id = first.agent_id;
    second.task_id = first.task_id;
    second.resume_session_id = Some(t1.finished()["sessionId"].as_str().unwrap().into());
    second.resume_prompt = Some("[scenario:codex-usage] RESUME-ONLY".into());
    conn.send(second.to_json());
    let t2 = conn.until_finished(second.run_id).await;
    assert_eq!(t2.finished()["outcome"], "SUCCEEDED", "{}", t2.text());
    // Not resumed: the full prompt was sent.
    assert!(!t2.agent_text().contains("RESUME-ONLY"));
    assert!(t2.agent_text().contains("Do the test task."));
    host.shutdown.cancel();
    host.handle.await.unwrap().unwrap();
    t2
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_agent_without_session_load_is_not_supported() {
    with_timeout(120, async {
        let t = first_then_follow_up_v2(local_executor_with("--no-load-session")).await;
        assert_eq!(t.finished()["resumed"], false);
        assert_eq!(t.finished()["resumeOutcome"], "NOT_SUPPORTED");
        assert!(t.text().contains("does not support session/load"));
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_session_load_error_is_load_failed_with_its_message_in_a_log_event() {
    with_timeout(120, async {
        let t = first_then_follow_up_v2(local_executor_with("--fail-load-session")).await;
        assert_eq!(t.finished()["resumed"], false);
        assert_eq!(t.finished()["resumeOutcome"], "LOAD_FAILED");
        let warnings: Vec<&str> = t
            .events_of("LOG")
            .iter()
            .filter(|e| e["payload"]["level"] == "warn")
            .map(|e| e["payload"]["message"].as_str().unwrap())
            .collect();
        assert!(
            warnings
                .iter()
                .any(|w| w.contains("LOAD_FAILED") && w.contains("the session file is corrupt")),
            "{warnings:?}"
        );
    })
    .await;
}

// ----- session bundles (ADR-0097) ------------------------------------------------------------

/// The frame of `spec` with `sessionBundle`: upload only, or with the bundle to restore.
fn with_session_bundle(spec: &AssignSpec, restore: Option<&[u8]>) -> serde_json::Value {
    let mut frame = spec.to_json();
    frame["sessionBundle"] = match restore {
        Some(bundle) => json!({"sha256": sha256_hex(bundle), "sizeBytes": bundle.len()}),
        None => json!({}),
    };
    frame
}

fn session_bundle_upload(server: &FakeServer) -> Option<Upload> {
    server
        .uploads
        .lock()
        .unwrap()
        .iter()
        .find(|u| u.path.contains("/session-bundle"))
        .cloned()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_session_bundle_carries_the_session_to_another_host() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let first_host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let first = AssignSpec::v2("skills", None);
        conn.send(with_session_bundle(&first, None));
        let t1 = conn.until_finished(first.run_id).await;
        assert_eq!(t1.finished()["outcome"], "SUCCEEDED", "{}", t1.text());
        assert_eq!(t1.finished()["sessionBundle"], "STORED", "{}", t1.text());
        let session_id = t1.finished()["sessionId"].as_str().unwrap().to_string();
        let upload = session_bundle_upload(&server).expect("the host uploaded its bundle");
        assert_eq!(
            upload.path,
            format!(
                "/api/runner/v1/runs/{}/session-bundle?epoch=1&sessionId={session_id}",
                first.run_id
            )
        );
        assert_eq!(upload.content_type, "application/gzip");
        assert_eq!(upload.authorization, format!("Bearer {TEST_HOST_TOKEN}"));
        let bundle = upload.body.clone();
        let tar = String::from_utf8_lossy(
            &tinfirm_runner_host::session_bundles::gunzip(&bundle).unwrap(),
        )
        .into_owned();
        // The runtime's session folders, and nothing else of the home.
        assert!(
            tar.contains(&format!(".claude/projects/fake-agent/{session_id}.json")),
            "{tar}"
        );
        assert!(!tar.contains("fake-session.json"), "{tar}");
        let home = first_host.paths.session_home(first.agent_id, first.task_id);
        assert_eq!(
            tinfirm_runner_host::session_bundles::recorded(&home),
            Some(sha256_hex(&bundle))
        );
        first_host.shutdown.cancel();
        first_host.handle.await.unwrap().unwrap();

        // The next turn on another host, whose home for the conversation is new.
        let mut server = FakeServer::start().await;
        let second_host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut second = AssignSpec::v2("skills", None);
        second.agent_id = first.agent_id;
        second.task_id = first.task_id;
        second.resume_session_id = Some(session_id.clone());
        second.resume_prompt = Some("[scenario:skills] again".into());
        server
            .files
            .lock()
            .unwrap()
            .insert(format!("session-bundle/{}", second.run_id), bundle.clone());
        conn.send(with_session_bundle(&second, Some(&bundle)));
        let t2 = conn.until_finished(second.run_id).await;
        assert_eq!(t2.finished()["resumed"], true, "{}", t2.text());
        assert!(
            t2.text()
                .contains("restored the session from the server's bundle"),
            "{}",
            t2.text()
        );
        assert_eq!(t2.finished()["sessionBundle"], "STORED");
        let downloads = server.downloads.lock().unwrap().clone();
        let download = downloads
            .iter()
            .find(|d| d.path.contains("/session-bundle"))
            .expect("the host downloaded the bundle");
        assert_eq!(
            download.path,
            format!(
                "/api/runner/v1/runs/{}/session-bundle?epoch=1",
                second.run_id
            )
        );
        assert_eq!(download.authorization, format!("Bearer {TEST_HOST_TOKEN}"));
        second_host.shutdown.cancel();
        second_host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_session_bundle_that_does_not_match_its_assignment_is_left_out() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let mut spec = AssignSpec::v2("skills", None);
        spec.resume_session_id = Some("01a1-not-here".into());
        spec.resume_prompt = Some("[scenario:skills] again".into());
        // The server answers other bytes than the assignment names.
        server.files.lock().unwrap().insert(
            format!("session-bundle/{}", spec.run_id),
            b"other bytes".to_vec(),
        );
        conn.send(with_session_bundle(&spec, Some(b"the assigned bundle")));
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert_eq!(t.finished()["resumed"], false);
        assert_eq!(t.finished()["resumeOutcome"], "NO_SESSION");
        assert!(
            t.text()
                .contains("cannot download the conversation's session bundle"),
            "{}",
            t.text()
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_refused_session_bundle_is_reported_and_a_run_without_one_uploads_none() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        server
            .refuse_session_bundles
            .store(true, std::sync::atomic::Ordering::SeqCst);
        let refused = AssignSpec::v2("skills", None);
        conn.send(with_session_bundle(&refused, None));
        let t = conn.until_finished(refused.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert_eq!(t.finished()["sessionBundle"], "REFUSED");
        assert!(t.text().contains("DEPLOY_KEY"), "{}", t.text());
        let home = host.paths.session_home(refused.agent_id, refused.task_id);
        assert_eq!(tinfirm_runner_host::session_bundles::recorded(&home), None);

        server.uploads.lock().unwrap().clear();
        let plain = AssignSpec::v2("skills", None);
        conn.send(plain.to_json());
        let t = conn.until_finished(plain.run_id).await;
        assert!(t.finished().get("sessionBundle").is_none());
        assert!(session_bundle_upload(&server).is_none());
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_run_that_leaked_a_credential_stores_no_session_bundle() {
    if !logins_reach_the_sandbox() {
        // The agent leaks the run's login, which a VM per run never gets.
        return;
    }
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::v2("leak", None);
        conn.send(with_session_bundle(&spec, None));
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(
            t.finished()["failureClass"],
            "CREDENTIAL_LEAK",
            "{}",
            t.text()
        );
        assert_eq!(t.finished()["sessionBundle"], "REFUSED");
        assert!(session_bundle_upload(&server).is_none());
        t.assert_no_secrets();
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}
