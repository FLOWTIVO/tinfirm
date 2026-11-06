//! Keeps the hand-written types honest against `protocol/runner-v1.schema.json` and
//! `protocol/runner-v2.schema.json`: every sample and every constructed message of a version
//! validates against its schema, round-trips through the types, and is refused by the other
//! version where the versions differ.

use std::collections::BTreeSet;
use std::path::PathBuf;

use chrono::{TimeZone, Utc};
use serde_json::{Value, json};
use tinfirm_protocol::*;
use uuid::Uuid;

fn validator() -> jsonschema::Validator {
    validator_of(SCHEMA_JSON)
}

fn validator_v2() -> jsonschema::Validator {
    validator_of(SCHEMA_JSON_V2)
}

fn validator_of(schema: &str) -> jsonschema::Validator {
    let schema: Value = serde_json::from_str(schema).expect("schema is JSON");
    jsonschema::options()
        .should_validate_formats(true)
        .build(&schema)
        .expect("schema compiles")
}

fn assert_valid(validator: &jsonschema::Validator, value: &Value, what: &str) {
    let errors: Vec<String> = validator
        .iter_errors(value)
        .map(|e| format!("{} at {}", e, e.instance_path()))
        .collect();
    assert!(
        errors.is_empty(),
        "{what} does not match the schema: {errors:#?}\n{value:#}"
    );
}

fn samples() -> Vec<(String, Value)> {
    let out = samples_in("tests/samples");
    assert!(
        out.len() >= MESSAGE_TYPES.len(),
        "expected samples for every message type"
    );
    out
}

/// The v2 samples (`tests/samples/v2`).
fn samples_v2() -> Vec<(String, Value)> {
    let out = samples_in("tests/samples/v2");
    assert!(
        out.len() >= MESSAGE_TYPES_V2.len(),
        "expected v2 samples for every message type"
    );
    out
}

fn samples_in(relative: &str) -> Vec<(String, Value)> {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(relative);
    let mut out = Vec::new();
    for entry in std::fs::read_dir(&dir).expect("samples dir") {
        let path = entry.unwrap().path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let text = std::fs::read_to_string(&path).unwrap();
        let value: Value = serde_json::from_str(&text)
            .unwrap_or_else(|e| panic!("{} is not JSON: {e}", path.display()));
        out.push((
            path.file_name().unwrap().to_string_lossy().into_owned(),
            value,
        ));
    }
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

#[test]
fn samples_validate_and_round_trip() {
    let v = validator();
    for (name, sample) in samples() {
        assert_valid(&v, &sample, &name);
        let parsed = Message::from_value(sample.clone())
            .unwrap_or_else(|e| panic!("{name} does not parse: {e}"));
        assert_eq!(
            parsed.message_type(),
            sample["type"].as_str().unwrap(),
            "{name}"
        );
        let serialized = serde_json::to_value(&parsed).unwrap();
        assert_valid(&v, &serialized, &format!("{name} (re-serialized)"));
        let reparsed = Message::from_value(serialized.clone()).unwrap();
        assert_eq!(parsed, reparsed, "{name} does not round-trip");
        // Everything the sample says (except explicit nulls, which we omit) survives.
        assert_eq!(
            strip_nulls(sample.clone()),
            strip_nulls(serialized),
            "{name}"
        );
    }
}

fn strip_nulls(value: Value) -> Value {
    match value {
        Value::Object(map) => Value::Object(
            map.into_iter()
                .filter(|(_, v)| !v.is_null())
                .map(|(k, v)| (k, strip_nulls(v)))
                .collect(),
        ),
        Value::Array(items) => Value::Array(items.into_iter().map(strip_nulls).collect()),
        Value::String(text) => match chrono::DateTime::parse_from_rfc3339(&text) {
            // Instants are compared by value: we always write millisecond precision.
            Ok(t) => Value::String(
                t.with_timezone(&Utc)
                    .to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
            ),
            Err(_) => Value::String(text),
        },
        other => other,
    }
}

#[test]
fn every_message_and_event_type_has_a_sample() {
    let schema: Value = serde_json::from_str(SCHEMA_JSON).unwrap();
    let defs = &schema["$defs"];
    let mut schema_types = BTreeSet::new();
    for variant in defs["Message"]["oneOf"].as_array().unwrap() {
        let reference = variant["$ref"].as_str().unwrap();
        let name = reference.trim_start_matches("#/$defs/");
        let t = defs[name]["properties"]["type"]["const"].as_str().unwrap();
        schema_types.insert(t.to_string());
    }
    let ours: BTreeSet<String> = MESSAGE_TYPES.iter().map(|s| s.to_string()).collect();
    assert_eq!(schema_types, ours, "MESSAGE_TYPES differs from the schema");

    let samples = samples();
    let sample_types: BTreeSet<String> = samples
        .iter()
        .map(|(_, v)| v["type"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(sample_types, ours, "a message type has no sample");

    let event_types: BTreeSet<String> = defs["RunEvent"]["properties"]["eventType"]["enum"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_str().unwrap().to_string())
        .collect();
    let sampled_events: BTreeSet<String> = samples
        .iter()
        .filter(|(_, v)| v["type"] == "run.event")
        .map(|(_, v)| v["eventType"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(event_types, sampled_events, "an eventType has no sample");
}

fn run_id() -> Uuid {
    Uuid::parse_str("0192f1a4-7c1e-7b3a-9d2e-1f2a3b4c5d6e").unwrap()
}

fn component_id() -> Uuid {
    Uuid::parse_str("0192f1a4-7c1e-7b3a-9d2e-000000000010").unwrap()
}

fn all_payloads(full: bool) -> Vec<EventPayload> {
    let resets = Utc.with_ymd_and_hms(2026, 9, 26, 15, 0, 0).unwrap();
    vec![
        EventPayload::AgentMessage(TextPayload { text: "hi".into() }),
        EventPayload::AgentThought(TextPayload { text: "hmm".into() }),
        EventPayload::ToolCall(ToolCallPayload {
            tool_call_id: "t1".into(),
            title: "Read a.txt".into(),
            kind: ToolKind::SwitchMode,
            status: ToolCallStatus::InProgress,
            input: full.then(|| json!({"path": "a.txt"})),
            locations: full.then(|| {
                vec![
                    Location {
                        path: "a.txt".into(),
                        line: Some(3),
                    },
                    Location {
                        path: "b.txt".into(),
                        line: None,
                    },
                ]
            }),
        }),
        EventPayload::ToolCallUpdate(ToolCallUpdatePayload {
            tool_call_id: "t1".into(),
            status: full.then_some(ToolCallStatus::Failed),
            title: full.then(|| "Read a.txt".to_string()),
            output: full.then(|| "out".to_string()),
            diffs: full.then(|| {
                vec![DiffPayload {
                    path: "a.txt".into(),
                    old_text: Some("a".into()),
                    new_text: "b".into(),
                }]
            }),
        }),
        EventPayload::Plan(PlanPayload {
            entries: vec![PlanEntry {
                content: "do it".into(),
                status: PlanEntryStatus::Completed,
                priority: full.then_some(PlanEntryPriority::Low),
            }],
        }),
        EventPayload::Usage(if full {
            UsagePayload {
                input_tokens: Some(1),
                output_tokens: Some(2),
                cached_input_tokens: Some(3),
                // v2 only: usage_cache_writes.
                cache_write_tokens: None,
                context_used: Some(4),
                context_size: Some(5),
                cost_usd: Some(0.5),
                total_tokens: Some(6),
                estimated: Some(true),
            }
        } else {
            UsagePayload::default()
        }),
        EventPayload::RateLimit(RateLimitPayload {
            status: RateLimitStatus::Rejected,
            utilization: full.then_some(1.0),
            resets_at: full.then_some(resets),
            window: full.then(|| "five_hour".to_string()),
        }),
        EventPayload::Status(StatusPayload {
            phase: Phase::Cancelling,
            message: full.then(|| "x".into()),
        }),
        EventPayload::Git(GitPayload {
            component_id: full.then(component_id),
            dir: full.then(|| "server".into()),
            action: GitAction::NothingToPush,
            branch: "agent/X-1".into(),
            head_sha: full.then(|| "abc".into()),
            commits: full.then_some(0),
        }),
        EventPayload::Log(LogPayload {
            level: LogLevel::Debug,
            message: "m".into(),
        }),
        EventPayload::Error(ErrorPayload {
            message: "boom".into(),
            failure_class: full.then_some(FailureClass::RunnerUnavailable),
        }),
        EventPayload::Error(ErrorPayload {
            message: "refused".into(),
            failure_class: Some(FailureClass::ModelRefused),
        }),
    ]
}

fn constructed_messages() -> Vec<Message> {
    let at = Utc.with_ymd_and_hms(2026, 9, 26, 9, 30, 0).unwrap();
    let mut out = vec![
        Message::Hello(Hello {
            protocol_version: PROTOCOL_VERSION,
            runner_version: "0.1.0".into(),
            hostname: Some("h".into()),
            os: Some("linux".into()),
            arch: Some("x86_64".into()),
            executors: vec![Executor::Docker],
            isolation_tiers: vec![IsolationTier::Container, IsolationTier::Gvisor],
            max_concurrent_runs: 1,
            credentials: vec![CredentialInfo {
                label: "codex-main".into(),
                kind: CredentialKind::CodexChatgptAuth,
                runtimes: CredentialKind::CodexChatgptAuth.runtimes(),
                pool_size: 1,
            }],
            runtimes: vec![RuntimeInfo {
                runtime: Runtime::Codex,
                image: "i".into(),
                version: None,
            }],
            active_runs: Some(vec![ActiveRun {
                run_id: run_id(),
                epoch: 1,
                last_seq: 0,
            }]),
            public_suffix_list: None,
            features: vec![],
            pool: None,
        }),
        Message::Welcome(Welcome {
            protocol_version: None,
            runner_host_id: Uuid::new_v4(),
            company_id: Uuid::new_v4(),
            server_version: None,
            heartbeat_interval_seconds: 10,
            lease_ttl_seconds: 30,
        }),
        Message::RunAssign(RunAssign {
            run_id: run_id(),
            epoch: 1,
            credential_label: Some("claude-main".into()),
            model_access: None,
            agent: AgentSpec {
                id: Uuid::new_v4(),
                name: "A".into(),
                runtime: Runtime::ClaudeCode,
                model: None,
                effort: None,
            },
            subject: Subject::Task(WorkItemSpec {
                id: Uuid::new_v4(),
                key: "X-1".into(),
                title: "t".into(),
            }),
            job: None,
            prompt: "p".into(),
            resume_session_id: None,
            workspaces: vec![],
            requestable_repositories: vec![],
            limits: Limits {
                max_wall_clock_seconds: 60,
                max_turns: 1,
            },
            resume_prompt: None,
            attachments: vec![AttachmentSpec {
                id: Uuid::new_v4(),
                file_name: "notes v2.md".into(),
                size_bytes: 12,
                sha256: "0".repeat(64),
            }],
            tools: vec![],
            allowed_hosts: vec![],
            platform_tools: None,
            skills: vec![],
            session_bundle: None,
        }),
        Message::RunAssign(RunAssign {
            run_id: run_id(),
            epoch: 2,
            credential_label: Some("claude-main".into()),
            model_access: None,
            agent: AgentSpec {
                id: Uuid::new_v4(),
                name: "A".into(),
                runtime: Runtime::ClaudeCode,
                model: Some("m".into()),
                effort: None,
            },
            subject: Subject::Task(WorkItemSpec {
                id: Uuid::new_v4(),
                key: "X-1".into(),
                title: "t".into(),
            }),
            job: None,
            prompt: "p".into(),
            resume_session_id: Some("s".into()),
            workspaces: vec![
                WorkspaceSpec {
                    component_id: component_id(),
                    dir: "server".into(),
                    name: "server".into(),
                    repo_url: "https://github.com/o/server.git".into(),
                    default_branch: "main".into(),
                    branch: "agent/X-1".into(),
                    git: Some(GitCredentials {
                        username: "x-access-token".into(),
                        token: Secret::new("t"),
                    }),
                    author_name: Some("Tinfirm agent A".into()),
                    author_email: Some("a@tinfirm.invalid".into()),
                    read_only: false,
                },
                WorkspaceSpec {
                    component_id: Uuid::new_v4(),
                    dir: "server-2".into(),
                    name: "Server".into(),
                    repo_url: "https://github.com/o/server2.git".into(),
                    default_branch: "main".into(),
                    branch: "agent/X-1".into(),
                    git: None,
                    author_name: None,
                    author_email: None,
                    read_only: false,
                },
            ],
            requestable_repositories: vec![RequestableRepository {
                component_id: Uuid::new_v4(),
                name: "web".into(),
                repo_url: "https://github.com/o/web.git".into(),
                pending_request: true,
            }],
            limits: Limits {
                max_wall_clock_seconds: 3600,
                max_turns: 200,
            },
            resume_prompt: Some("r".into()),
            attachments: vec![AttachmentSpec {
                id: Uuid::new_v4(),
                file_name: "notes v2.md".into(),
                size_bytes: 12,
                sha256: "0".repeat(64),
            }],
            tools: vec![],
            allowed_hosts: vec![],
            platform_tools: None,
            skills: vec![],
            session_bundle: None,
        }),
        Message::RunAssign(RunAssign {
            run_id: run_id(),
            epoch: 3,
            credential_label: None,
            model_access: Some(ModelAccess {
                format: ModelFormat::Anthropic,
                base_url: Some("https://litellm.example.com".into()),
                region: None,
                access_key_id: None,
                api_key: Secret::new("sk-not-a-real-key"),
            }),
            agent: AgentSpec {
                id: Uuid::new_v4(),
                name: "Shared".into(),
                runtime: Runtime::ClaudeCode,
                model: None,
                effort: None,
            },
            subject: Subject::Task(WorkItemSpec {
                id: Uuid::new_v4(),
                key: "X-2".into(),
                title: "t".into(),
            }),
            job: None,
            prompt: "p".into(),
            resume_session_id: None,
            workspaces: vec![],
            requestable_repositories: vec![],
            limits: Limits {
                max_wall_clock_seconds: 60,
                max_turns: 1,
            },
            resume_prompt: None,
            attachments: vec![],
            tools: vec![],
            allowed_hosts: vec![],
            platform_tools: None,
            skills: vec![],
            session_bundle: None,
        }),
        Message::RunAccepted(RunAccepted {
            run_id: run_id(),
            epoch: 1,
        }),
        Message::RunRejected(RunRejected {
            run_id: run_id(),
            epoch: 1,
            reason: "r".into(),
            code: None,
        }),
        Message::RunRejected(RunRejected {
            run_id: run_id(),
            epoch: 2,
            reason: "the runner host is draining".into(),
            code: Some(reject_code::DRAINING.into()),
        }),
        Message::RunHeartbeat(RunHeartbeat { runs: vec![] }),
        Message::RunFinished(RunFinished {
            run_id: run_id(),
            epoch: 1,
            outcome: Outcome::Cancelled,
            last_seq: 0,
            failure_class: None,
            failure_message: None,
            summary: None,
            result: None,
            session_id: None,
            git: Some(vec![
                GitResult {
                    component_id: component_id(),
                    branch: "agent/X-1".into(),
                    pushed: false,
                    head_sha: None,
                    commits: None,
                },
                GitResult {
                    component_id: Uuid::new_v4(),
                    branch: "agent/X-1".into(),
                    pushed: true,
                    head_sha: Some("abc".into()),
                    commits: Some(3),
                },
            ]),
            usage: Some(UsagePayload::default()),
            artifacts: vec![],
            resumed: None,
            resume_outcome: None,
            session_bundle: None,
        }),
        Message::RunFinished(RunFinished {
            run_id: run_id(),
            epoch: 1,
            outcome: Outcome::Succeeded,
            last_seq: 3,
            failure_class: None,
            failure_message: None,
            summary: Some("s".into()),
            result: None,
            session_id: None,
            git: Some(vec![]),
            usage: None,
            artifacts: vec![],
            resumed: None,
            resume_outcome: None,
            session_bundle: None,
        }),
        Message::RunFinished(RunFinished {
            run_id: run_id(),
            epoch: 1,
            outcome: Outcome::Succeeded,
            last_seq: 3,
            failure_class: None,
            failure_message: None,
            summary: Some("s".into()),
            result: Some(RunResult {
                outcome: ResultOutcome::Blocked,
                summary: "blocked".into(),
                next_steps: vec!["grant access".into()],
                open_questions: vec!["which database?".into()],
            }),
            session_id: None,
            git: Some(vec![]),
            usage: None,
            artifacts: vec![],
            resumed: None,
            resume_outcome: None,
            session_bundle: None,
        }),
        Message::RunFinished(RunFinished {
            run_id: run_id(),
            epoch: 1,
            outcome: Outcome::Cancelled,
            last_seq: 3,
            failure_class: None,
            failure_message: None,
            summary: None,
            result: Some(RunResult {
                outcome: ResultOutcome::Completed,
                summary: "done".into(),
                next_steps: vec![],
                open_questions: vec![],
            }),
            session_id: None,
            git: None,
            usage: None,
            artifacts: vec![],
            resumed: None,
            resume_outcome: None,
            session_bundle: None,
        }),
        Message::RunCancel(RunCancel {
            run_id: run_id(),
            epoch: 1,
        }),
        Message::RunRepositoryRequest(RunRepositoryRequest {
            run_id: run_id(),
            epoch: 1,
            request_id: Uuid::new_v4(),
            component_id: Some(component_id()),
            name: "server".into(),
            reason: "r".into(),
        }),
        Message::RunRepositoryRequest(RunRepositoryRequest {
            run_id: run_id(),
            epoch: 1,
            request_id: Uuid::new_v4(),
            component_id: None,
            name: "unknown".into(),
            reason: "r".into(),
        }),
        Message::RunRepositoryRequestAck(RunRepositoryRequestAck {
            run_id: run_id(),
            request_id: Uuid::new_v4(),
            status: RepositoryRequestStatus::Pending,
            repository_request_id: Some(Uuid::new_v4()),
            code: None,
            message: "m".into(),
        }),
        Message::RunRepositoryRequestAck(RunRepositoryRequestAck {
            run_id: run_id(),
            request_id: Uuid::new_v4(),
            status: RepositoryRequestStatus::Rejected,
            repository_request_id: None,
            code: Some("ALREADY_PENDING".into()),
            message: "m".into(),
        }),
        Message::RunTaskCreate(RunTaskCreate {
            run_id: run_id(),
            epoch: 1,
            request_id: Uuid::new_v4(),
            title: "t".into(),
            description: Some("d".into()),
            repositories: Some(vec!["server".into()]),
            assignee: Some("Frontend engineer".into()),
        }),
        Message::RunTaskCreate(RunTaskCreate {
            run_id: run_id(),
            epoch: 1,
            request_id: Uuid::new_v4(),
            title: "t".into(),
            description: None,
            repositories: None,
            assignee: None,
        }),
        Message::RunTaskCreateAck(RunTaskCreateAck {
            run_id: run_id(),
            request_id: Uuid::new_v4(),
            status: TaskCreateStatus::Created,
            task_id: Some(Uuid::new_v4()),
            task_key: Some("X-2".into()),
            code: None,
            message: "m".into(),
        }),
        Message::RunTaskCreateAck(RunTaskCreateAck {
            run_id: run_id(),
            request_id: Uuid::new_v4(),
            status: TaskCreateStatus::Rejected,
            task_id: None,
            task_key: None,
            code: Some("NO_ACCESS".into()),
            message: "m".into(),
        }),
        Message::RunAsk(RunAsk {
            run_id: run_id(),
            epoch: 1,
            request_id: Uuid::new_v4(),
            question: "q?".into(),
            options: vec!["a".into(), "b".into()],
        }),
        Message::RunAsk(RunAsk {
            run_id: run_id(),
            epoch: 1,
            request_id: Uuid::new_v4(),
            question: "q?".into(),
            options: vec![],
        }),
        Message::RunAskAck(RunAskAck {
            run_id: run_id(),
            request_id: Uuid::new_v4(),
            status: AskStatus::Pending,
            ask_id: Some(Uuid::new_v4()),
            code: None,
            message: "m".into(),
        }),
        Message::RunAskAck(RunAskAck {
            run_id: run_id(),
            request_id: Uuid::new_v4(),
            status: AskStatus::Rejected,
            ask_id: None,
            code: Some("TOO_MANY_ASKS".into()),
            message: "m".into(),
        }),
        Message::CredentialStatus(CredentialStatus {
            label: "l".into(),
            status: CredentialState::Invalid,
            cooldown_until: None,
            detail: Some("login expired".into()),
        }),
        Message::HostConfig(HostConfig {
            version: 0,
            desired_max_concurrent_runs: None,
            credentials: vec![],
            pool: None,
        }),
        Message::HostConfig(HostConfig {
            version: 12,
            desired_max_concurrent_runs: Some(MAX_CAPACITY),
            credentials: vec![CredentialConfig {
                label: "claude-main".into(),
                desired_pool_size: 1,
            }],
            pool: None,
        }),
        Message::HostConfigApplied(HostConfigApplied {
            version: 12,
            draining: false,
            max_concurrent_runs: effective_capacity(Some(MAX_CAPACITY), 4),
            max_concurrent_runs_ceiling: 4,
            credentials: vec![CredentialCapacity {
                label: "claude-main".into(),
                pool_size: effective_capacity(Some(1), 3),
                pool_size_ceiling: 3,
            }],
            pool: None,
        }),
        Message::HostConfigApplied(HostConfigApplied {
            version: 0,
            draining: true,
            max_concurrent_runs: 0,
            max_concurrent_runs_ceiling: 1,
            credentials: vec![],
            pool: None,
        }),
        Message::Error(ErrorMessage {
            code: "BAD_MESSAGE".into(),
            message: "m".into(),
            run_id: None,
            epoch: None,
        }),
        Message::Error(ErrorMessage {
            code: "STALE_EPOCH".into(),
            message: "m".into(),
            run_id: Some(run_id()),
            epoch: Some(2),
        }),
    ];
    for (i, event) in all_payloads(true)
        .into_iter()
        .chain(all_payloads(false))
        .enumerate()
    {
        out.push(Message::RunEvent(RunEvent {
            run_id: run_id(),
            epoch: 1,
            seq: i as u64 + 1,
            at,
            event,
        }));
    }
    out
}

#[test]
fn constructed_messages_validate_and_round_trip() {
    let v = validator();
    for message in constructed_messages() {
        let value = serde_json::to_value(&message).unwrap();
        assert_valid(&v, &value, message.message_type());
        let parsed = Message::parse(&message.to_json()).unwrap();
        assert_eq!(parsed, message);
    }
}

#[test]
fn a_run_assign_carries_exactly_one_of_a_credential_label_and_model_access() {
    let v = validator();
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/samples/run.assign-model-access.json");
    let sample: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    let Message::RunAssign(assign) = Message::from_value(sample.clone()).unwrap() else {
        panic!("not a run.assign");
    };
    let Some(AssignedCredential::Model(access)) = assign.credential() else {
        panic!("expected model access");
    };
    assert_eq!(access.format, ModelFormat::Openai);
    assert!(access.format.serves(Runtime::Codex));
    assert_eq!(
        access.base_url.as_deref(),
        Some("https://litellm.example.com/v1")
    );
    assert_eq!(access.api_key.expose(), "sk-litellm-EXAMPLE-NOT-A-REAL-KEY");
    // The key never shows in Debug output.
    assert!(!format!("{assign:?}").contains("sk-litellm-EXAMPLE"));

    let mut both = sample.clone();
    both["credentialLabel"] = json!("claude-main");
    assert!(!v.is_valid(&both), "both a label and model access");
    let Message::RunAssign(both) = Message::from_value(both).unwrap() else {
        panic!("not a run.assign");
    };
    assert_eq!(both.credential(), None);

    let mut neither = sample.clone();
    neither.as_object_mut().unwrap().remove("modelAccess");
    assert!(!v.is_valid(&neither), "neither a label nor model access");
    let Message::RunAssign(neither) = Message::from_value(neither).unwrap() else {
        panic!("not a run.assign");
    };
    assert_eq!(neither.credential(), None);

    let mut plain_http = sample.clone();
    plain_http["modelAccess"]["baseUrl"] = json!("http://litellm.example.com");
    assert!(!v.is_valid(&plain_http), "an http base URL");
    let mut unknown_format = sample;
    unknown_format["modelAccess"]["format"] = json!("azure");
    assert!(!v.is_valid(&unknown_format), "an unknown format");
    assert!(Message::from_value(unknown_format).is_err());
}

/// Provider keys (ADR-0042): no base URL for the provider's own API, a region for Bedrock and
/// only there, an access key ID only for Bedrock, a base URL only for anthropic and openai.
#[test]
fn model_access_takes_the_fields_of_its_provider() {
    for v in [validator(), validator_v2()] {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests/samples/v2/run.assign-bedrock.json");
        let sample: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let access = |patch: Value| {
            let mut value = json!({"format": "bedrock", "region": "eu-central-1", "apiKey": "k"});
            for (key, field) in patch.as_object().unwrap() {
                if field.is_null() {
                    value.as_object_mut().unwrap().remove(key);
                } else {
                    value[key] = field.clone();
                }
            }
            let mut assign = sample.clone();
            assign["modelAccess"] = value;
            assign
        };
        let valid = |patch: Value| {
            let assign = access(patch);
            let mut v1 = assign.clone();
            // The v1 schema knows `task`, the v2 schema `workItem`.
            if let Some(item) = v1.as_object_mut().unwrap().remove("workItem") {
                v1["task"] = item;
                v1.as_object_mut().unwrap().remove("job");
            }
            v.is_valid(&assign) || v.is_valid(&v1)
        };
        assert!(valid(json!({})));
        assert!(valid(json!({"accessKeyId": "AKIAIOSFODNN7EXAMPLE"})));
        assert!(!valid(json!({"region": null})), "bedrock without a region");
        assert!(
            !valid(json!({"baseUrl": "https://bedrock.example.com"})),
            "bedrock with a base URL"
        );
        assert!(!valid(json!({"region": "EU"})), "a malformed region");
        assert!(valid(json!({"format": "anthropic", "region": null})));
        assert!(valid(
            json!({"format": "openai", "region": null, "baseUrl": "https://gw.example.com/v1"})
        ));
        assert!(
            !valid(json!({"format": "anthropic"})),
            "a region outside bedrock"
        );
        assert!(!valid(
            json!({"format": "google", "region": null, "accessKeyId": "AKIAIOSFODNN7EXAMPLE"})
        ));
        assert!(!valid(
            json!({"format": "google", "region": null, "baseUrl": "https://g.example.com"})
        ));
        for format in ["google", "openrouter", "mistral", "groq", "xai", "deepseek"] {
            assert!(valid(json!({"format": format, "region": null})), "{format}");
        }
    }
    let parsed: ModelAccess = serde_json::from_value(
        json!({"format": "bedrock", "region": "us-east-1", "accessKeyId": "AKIAIOSFODNN7EXAMPLE", "apiKey": "secret-access-key"}),
    )
    .unwrap();
    assert_eq!(parsed.format, ModelFormat::Bedrock);
    assert_eq!(parsed.base_url, None);
    assert_eq!(parsed.region.as_deref(), Some("us-east-1"));
    assert!(!format!("{parsed:?}").contains("secret-access-key"));
    // A provider's own API serializes without a base URL.
    let out = serde_json::to_value(&parsed).unwrap();
    assert!(out.get("baseUrl").is_none());
    assert_eq!(
        ModelFormat::Bedrock.runtimes(),
        &[Runtime::ClaudeCode, Runtime::Pi, Runtime::Opencode]
    );
    assert_eq!(
        ModelFormat::Openai.runtimes(),
        &[Runtime::Codex, Runtime::Pi, Runtime::Opencode]
    );
    assert!(!ModelFormat::Google.serves(Runtime::ClaudeCode));
    assert!(ModelFormat::Deepseek.serves(Runtime::Opencode));
}

#[test]
fn run_event_instant_uses_utc_millis() {
    let at = Utc.with_ymd_and_hms(2026, 9, 26, 9, 30, 0).unwrap();
    let msg = Message::RunEvent(RunEvent {
        run_id: run_id(),
        epoch: 1,
        seq: 1,
        at,
        event: EventPayload::Log(LogPayload {
            level: LogLevel::Info,
            message: "x".into(),
        }),
    });
    let value = serde_json::to_value(&msg).unwrap();
    assert_eq!(value["at"], "2026-09-26T09:30:00.000Z");
    assert_eq!(value["eventType"], "LOG");
    assert_eq!(value["payload"]["level"], "info");
}

/// Values the types keep as free-form JSON, where an added field is content, not an unknown
/// field: a tool call's `input` (v1), a tool's `inputSchema` and a call's `arguments` (v2).
const FREE_FORM_KEYS: [&str; 3] = ["input", "inputSchema", "arguments"];

fn add_unknown_fields(value: &mut Value) {
    match value {
        Value::Object(map) => {
            for (key, child) in map.iter_mut() {
                if !FREE_FORM_KEYS.contains(&key.as_str()) {
                    add_unknown_fields(child);
                }
            }
            map.insert("zzUnknownField".into(), json!({"nested": [1, "two", null]}));
        }
        Value::Array(items) => items.iter_mut().for_each(add_unknown_fields),
        _ => {}
    }
}

#[test]
fn unknown_fields_are_ignored() {
    for (name, sample) in samples() {
        let expected = Message::from_value(sample.clone()).unwrap();
        let mut extended = sample.clone();
        add_unknown_fields(&mut extended);
        let parsed = Message::from_value(extended)
            .unwrap_or_else(|e| panic!("{name} with unknown fields does not parse: {e}"));
        assert_eq!(parsed, expected, "{name}");
    }
}

#[test]
fn schema_rejects_what_the_types_reject() {
    let v = validator();
    let bad = json!({"type": "hello", "protocolVersion": 1});
    assert!(!v.is_valid(&bad));
    assert!(Message::from_value(bad).is_err());
    let bad_event = json!({
        "type": "run.event", "runId": run_id(), "epoch": 1, "seq": 1,
        "at": "2026-09-26T09:30:00Z", "eventType": "PLAN", "payload": {"text": "x"}
    });
    assert!(!v.is_valid(&bad_event));
    assert!(Message::from_value(bad_event).is_err());
}

fn sample(name: &str) -> Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/samples")
        .join(name);
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

#[test]
fn run_assign_needs_the_multi_repository_shape() {
    let v = validator();
    // The single-repository shape (`workspace`) is gone from v1.
    let mut old = sample("run.assign.json");
    let first = old["workspaces"][0].clone();
    let map = old.as_object_mut().unwrap();
    map.remove("workspaces");
    map.remove("requestableRepositories");
    map.insert("workspace".into(), first);
    assert!(!v.is_valid(&old));
    assert!(Message::from_value(old).is_err());

    // At most MAX_WORKSPACES repositories.
    let mut many = sample("run.assign.json");
    let item = many["workspaces"][0].clone();
    many["workspaces"] = Value::Array(vec![item; MAX_WORKSPACES + 1]);
    assert!(!v.is_valid(&many));
}

#[test]
fn workspace_dir_helper_matches_the_schema() {
    let v = validator();
    let long = "a".repeat(MAX_DIR_LEN);
    let too_long = "a".repeat(MAX_DIR_LEN + 1);
    for dir in [
        "server",
        "web-app-2",
        "0",
        long.as_str(),
        too_long.as_str(),
        "",
        "-a",
        "a-",
        "a--b",
        "Web",
        "a_b",
        "a.b",
        "..",
        "a/b",
        "ü",
    ] {
        let mut assign = sample("run.assign.json");
        assign["workspaces"][0]["dir"] = json!(dir);
        assert_eq!(
            v.is_valid(&assign),
            is_valid_workspace_dir(dir),
            "schema and is_valid_workspace_dir disagree on {dir:?}"
        );
    }
}

#[test]
fn repository_request_ack_carries_an_id_or_a_code() {
    let v = validator();
    let pending = sample("run.repository_request.ack.json");
    assert_valid(&v, &pending, "pending ack");
    let mut no_id = pending.clone();
    no_id["repositoryRequestId"] = Value::Null;
    assert!(!v.is_valid(&no_id), "PENDING needs repositoryRequestId");

    let rejected = sample("run.repository_request.ack-rejected.json");
    assert_valid(&v, &rejected, "rejected ack");
    let mut no_code = rejected.clone();
    no_code.as_object_mut().unwrap().remove("code");
    assert!(!v.is_valid(&no_code), "REJECTED needs code");

    let mut bad_status = rejected;
    bad_status["status"] = json!("APPROVED");
    assert!(!v.is_valid(&bad_status));
    assert!(Message::from_value(bad_status).is_err());
}

#[test]
fn repository_request_bounds() {
    let v = validator();
    let base = sample("run.repository_request.json");
    for (field, value) in [
        ("reason", json!("")),
        ("reason", json!("x".repeat(2001))),
        ("name", json!("")),
        ("name", json!("x".repeat(101))),
    ] {
        let mut bad = base.clone();
        bad[field] = value;
        assert!(!v.is_valid(&bad), "{field} out of bounds must be invalid");
    }
}

#[test]
fn task_create_ack_carries_a_task_or_a_code() {
    let v = validator();
    let created = sample("run.task_create.ack.json");
    assert_valid(&v, &created, "created ack");
    for field in ["taskId", "taskKey"] {
        let mut missing = created.clone();
        missing[field] = Value::Null;
        assert!(!v.is_valid(&missing), "CREATED needs {field}");
    }

    let rejected = sample("run.task_create.ack-rejected.json");
    assert_valid(&v, &rejected, "rejected ack");
    let mut no_code = rejected.clone();
    no_code.as_object_mut().unwrap().remove("code");
    assert!(!v.is_valid(&no_code), "REJECTED needs code");

    let mut bad_status = rejected;
    bad_status["status"] = json!("PENDING");
    assert!(!v.is_valid(&bad_status));
    assert!(Message::from_value(bad_status).is_err());
}

#[test]
fn task_create_bounds() {
    let v = validator();
    let base = sample("run.task_create.json");
    for (field, value) in [
        ("title", json!("")),
        ("title", json!("x".repeat(201))),
        ("description", json!("x".repeat(50001))),
        ("repositories", json!([""])),
        ("repositories", json!(["x".repeat(101)])),
        ("repositories", json!(vec!["x"; 21])),
        ("assignee", json!("")),
        ("assignee", json!("x".repeat(101))),
    ] {
        let mut bad = base.clone();
        bad[field] = value;
        assert!(!v.is_valid(&bad), "{field} out of bounds must be invalid");
    }
    let minimal = sample("run.task_create-minimal.json");
    assert_valid(&v, &minimal, "a title alone");
}

#[test]
fn ask_ack_carries_an_id_or_a_code() {
    let v = validator();
    let pending = sample("run.ask.ack.json");
    assert_valid(&v, &pending, "pending ack");
    let mut no_id = pending.clone();
    no_id["askId"] = Value::Null;
    assert!(!v.is_valid(&no_id), "PENDING needs askId");

    let rejected = sample("run.ask.ack-rejected.json");
    assert_valid(&v, &rejected, "rejected ack");
    let mut no_code = rejected.clone();
    no_code.as_object_mut().unwrap().remove("code");
    assert!(!v.is_valid(&no_code), "REJECTED needs code");

    let mut bad_status = rejected;
    bad_status["status"] = json!("ANSWERED");
    assert!(!v.is_valid(&bad_status));
    assert!(Message::from_value(bad_status).is_err());
}

#[test]
fn ask_bounds() {
    let v = validator();
    let base = sample("run.ask.json");
    for (field, value) in [
        ("question", json!("")),
        ("question", json!("x".repeat(ASK_QUESTION_MAX_CHARS + 1))),
        ("options", json!([""])),
        ("options", json!(["x".repeat(ASK_OPTION_MAX_CHARS + 1)])),
        ("options", json!(vec!["x"; MAX_ASK_OPTIONS + 1])),
        ("options", json!("yes")),
    ] {
        let mut bad = base.clone();
        bad[field] = value;
        assert!(!v.is_valid(&bad), "{field} out of bounds must be invalid");
    }
    let mut longest = base.clone();
    longest["question"] = json!("é".repeat(ASK_QUESTION_MAX_CHARS));
    longest["options"] = json!(vec!["ü".repeat(ASK_OPTION_MAX_CHARS); MAX_ASK_OPTIONS]);
    assert_valid(&v, &longest, "the longest question and options");
    let free = sample("run.ask-free-answer.json");
    assert_valid(&v, &free, "a question without options");
    let Message::RunAsk(parsed) = Message::from_value(free).unwrap() else {
        panic!("not run.ask");
    };
    assert!(parsed.options.is_empty());
    let mut no_request = base;
    no_request.as_object_mut().unwrap().remove("requestId");
    assert!(
        !v.is_valid(&no_request),
        "an ask needs a requestId to be answered"
    );
    assert!(Message::from_value(no_request).is_err());
}

#[test]
fn run_finished_result_bounds() {
    let v = validator();
    let base = sample("run.finished-result.json");
    let Message::RunFinished(parsed) = Message::from_value(base.clone()).unwrap() else {
        panic!("not run.finished");
    };
    let result = parsed.result.expect("a result");
    assert_eq!(result.outcome, ResultOutcome::Blocked);
    assert_eq!(result.open_questions.len(), 1);
    for (field, value) in [
        ("outcome", json!("DONE")),
        ("summary", json!("")),
        ("summary", json!("x".repeat(RESULT_SUMMARY_MAX_CHARS + 1))),
        ("nextSteps", json!([""])),
        ("nextSteps", json!(["x".repeat(RESULT_ITEM_MAX_CHARS + 1)])),
        ("nextSteps", json!(vec!["x"; MAX_NEXT_STEPS + 1])),
        (
            "openQuestions",
            json!(["x".repeat(RESULT_ITEM_MAX_CHARS + 1)]),
        ),
        ("openQuestions", json!(vec!["x?"; MAX_OPEN_QUESTIONS + 1])),
    ] {
        let mut bad = base.clone();
        bad["result"][field] = value;
        assert!(
            !v.is_valid(&bad),
            "result.{field} out of bounds must be invalid"
        );
    }
    for field in ["outcome", "summary"] {
        let mut missing = base.clone();
        missing["result"].as_object_mut().unwrap().remove(field);
        assert!(!v.is_valid(&missing), "result.{field} is required");
        assert!(Message::from_value(missing).is_err());
    }
    // Without lists, and without a result at all (null or absent), it is valid.
    let mut bare = base.clone();
    for field in ["nextSteps", "openQuestions"] {
        bare["result"].as_object_mut().unwrap().remove(field);
    }
    assert_valid(&v, &bare, "a result without lists");
    let mut null = base.clone();
    null["result"] = Value::Null;
    assert_valid(&v, &null, "a null result");
    let Message::RunFinished(parsed) = Message::from_value(null).unwrap() else {
        panic!("not run.finished");
    };
    assert_eq!(parsed.result, None);
    let out = serde_json::to_value(Message::RunFinished(parsed)).unwrap();
    assert!(
        out.get("result").is_none(),
        "an absent result is not written"
    );
}

#[test]
fn credential_kinds_map_to_runtimes() {
    assert_eq!(
        CredentialKind::ClaudeLogin.runtimes(),
        vec![Runtime::ClaudeCode]
    );
    assert_eq!(
        CredentialKind::AnthropicApiKey.runtimes(),
        vec![Runtime::ClaudeCode, Runtime::Pi, Runtime::Opencode]
    );
    assert_eq!(
        CredentialKind::CodexChatgptAuth.runtimes(),
        vec![Runtime::Codex]
    );
    assert_eq!(
        CredentialKind::OpenaiApiKey.runtimes(),
        vec![Runtime::Codex, Runtime::Pi, Runtime::Opencode]
    );
    // Protocol v1 names only the runtimes from before ADR-0041.
    assert_eq!(Runtime::ALL.iter().filter(|r| r.in_v1()).count(), 2);
    assert_eq!(Runtime::Opencode.as_str(), "OPENCODE");
    assert_eq!(serde_json::to_value(Runtime::Pi).unwrap(), json!("PI"));
}

#[test]
fn run_finished_null_and_absent_failure_class_are_equivalent() {
    let v = validator();
    let base = json!({
        "type": "run.finished", "runId": run_id(), "epoch": 1,
        "outcome": "SUCCEEDED", "lastSeq": 0
    });
    let mut with_null = base.clone();
    with_null["failureClass"] = Value::Null;
    assert_valid(&v, &base, "absent failureClass");
    assert_valid(&v, &with_null, "null failureClass");
    let a = Message::from_value(base).unwrap();
    let b = Message::from_value(with_null).unwrap();
    assert_eq!(a, b);
    // We always write it as absent.
    let out = serde_json::to_value(&a).unwrap();
    assert!(out.get("failureClass").is_none());
    assert_valid(&v, &out, "serialized");
}

#[test]
fn capacity_is_bounded_everywhere() {
    let v = validator();
    let hello = sample("hello.json");
    for (value, ok) in [
        (0, false),
        (1, true),
        (MAX_CAPACITY, true),
        (MAX_CAPACITY + 1, false),
    ] {
        let mut host = hello.clone();
        host["maxConcurrentRuns"] = json!(value);
        assert_eq!(v.is_valid(&host), ok, "hello.maxConcurrentRuns {value}");
        let mut pool = hello.clone();
        pool["credentials"][0]["poolSize"] = json!(value);
        assert_eq!(v.is_valid(&pool), ok, "hello poolSize {value}");
        let mut desired = sample("host.config.json");
        desired["desiredMaxConcurrentRuns"] = json!(value);
        assert_eq!(v.is_valid(&desired), ok, "desiredMaxConcurrentRuns {value}");
        let mut desired_pool = sample("host.config.json");
        desired_pool["credentials"][0]["desiredPoolSize"] = json!(value);
        assert_eq!(v.is_valid(&desired_pool), ok, "desiredPoolSize {value}");
        let mut ceiling = sample("host.config.applied.json");
        ceiling["maxConcurrentRunsCeiling"] = json!(value);
        assert_eq!(v.is_valid(&ceiling), ok, "maxConcurrentRunsCeiling {value}");
        for field in ["poolSize", "poolSizeCeiling"] {
            let mut applied = sample("host.config.applied.json");
            applied["credentials"][0][field] = json!(value);
            assert_eq!(v.is_valid(&applied), ok, "applied {field} {value}");
        }
    }
    // A desired value may be absent or null: the host then uses its ceiling.
    let mut absent = sample("host.config.json");
    absent
        .as_object_mut()
        .unwrap()
        .remove("desiredMaxConcurrentRuns");
    assert_valid(&v, &absent, "host.config without desiredMaxConcurrentRuns");
    assert_valid(
        &v,
        &sample("host.config-none.json"),
        "host.config with null",
    );
    // A desired pool entry needs its value.
    let mut no_pool = sample("host.config.json");
    no_pool["credentials"][0]
        .as_object_mut()
        .unwrap()
        .remove("desiredPoolSize");
    assert!(!v.is_valid(&no_pool));
    assert!(Message::from_value(no_pool).is_err());
}

#[test]
fn a_hosts_slots_go_to_1000_in_v2_and_a_credentials_pool_stays_at_16() {
    let v = validator_v2();
    for (value, ok) in [
        (0, false),
        (1, true),
        (MAX_CAPACITY + 1, true),
        (MAX_HOST_CAPACITY, true),
        (MAX_HOST_CAPACITY + 1, false),
    ] {
        let mut host = sample_v2("hello-pool.json");
        host["maxConcurrentRuns"] = json!(value);
        assert_eq!(v.is_valid(&host), ok, "hello.maxConcurrentRuns {value}");
        let mut desired = sample_v2("host.config-pool.json");
        desired["desiredMaxConcurrentRuns"] = json!(value);
        assert_eq!(v.is_valid(&desired), ok, "desiredMaxConcurrentRuns {value}");
        let mut ceiling = sample_v2("host.config.applied-pool.json");
        ceiling["maxConcurrentRunsCeiling"] = json!(value);
        assert_eq!(v.is_valid(&ceiling), ok, "maxConcurrentRunsCeiling {value}");
    }
    let mut pool = sample_v2("hello.json");
    pool["credentials"][0]["poolSize"] = json!(MAX_CAPACITY + 1);
    assert!(!v.is_valid(&pool), "a credential's pool stays at most 16");
    // The pool's counts are bounded too, and `next` needs both of its fields.
    let mut ready = sample_v2("host.config-pool.json");
    ready["pool"]["readyTarget"] = json!(1001);
    assert!(!v.is_valid(&ready));
    let mut next = sample_v2("host.config-pool.json");
    next["pool"]["next"].as_object_mut().unwrap().remove("at");
    assert!(!v.is_valid(&next));
    assert!(Message::from_value(next).is_err());
    let mut status = sample_v2("host.pool.status.json");
    status.as_object_mut().unwrap().remove("busy");
    assert!(!v.is_valid(&status));
}

#[test]
fn draining_means_no_slots() {
    let v = validator();
    let draining = sample("host.config.applied-draining.json");
    assert_valid(&v, &draining, "draining");
    let mut with_slots = draining.clone();
    with_slots["maxConcurrentRuns"] = json!(1);
    assert!(!v.is_valid(&with_slots), "a draining host reports 0 slots");

    let serving = sample("host.config.applied.json");
    let mut no_slots = serving.clone();
    no_slots["maxConcurrentRuns"] = json!(0);
    assert!(
        !v.is_valid(&no_slots),
        "only a draining host reports 0 slots"
    );

    let mut no_flag = serving;
    no_flag.as_object_mut().unwrap().remove("draining");
    assert!(!v.is_valid(&no_flag));
    assert!(Message::from_value(no_flag).is_err());
}

#[test]
fn run_rejected_code_is_optional() {
    let v = validator();
    let plain = sample("run.rejected.json");
    assert!(plain.get("code").is_none());
    assert_valid(&v, &plain, "run.rejected without code");
    let Message::RunRejected(parsed) = Message::from_value(plain).unwrap() else {
        panic!("not run.rejected");
    };
    assert_eq!(parsed.code, None);

    let coded = sample("run.rejected-task-run-active.json");
    let Message::RunRejected(parsed) = Message::from_value(coded).unwrap() else {
        panic!("not run.rejected");
    };
    assert_eq!(parsed.code.as_deref(), Some(reject_code::TASK_RUN_ACTIVE));
}

// ---------------------------------------------------------------------------------------
// Protocol v2
// ---------------------------------------------------------------------------------------

fn sample_v2(name: &str) -> Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/samples/v2")
        .join(name);
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

#[test]
fn v2_samples_validate_and_round_trip() {
    let v = validator_v2();
    for (name, sample) in samples_v2() {
        assert_valid(&v, &sample, &format!("v2/{name}"));
        let parsed = Message::from_value_version(sample.clone(), PROTOCOL_VERSION_V2)
            .unwrap_or_else(|e| panic!("v2/{name} does not parse: {e}"));
        assert_eq!(
            parsed.message_type(),
            sample["type"].as_str().unwrap(),
            "v2/{name}"
        );
        let serialized = serde_json::to_value(&parsed).unwrap();
        assert_valid(&v, &serialized, &format!("v2/{name} (re-serialized)"));
        let reparsed = Message::from_value(serialized.clone()).unwrap();
        assert_eq!(parsed, reparsed, "v2/{name} does not round-trip");
        assert_eq!(
            strip_nulls(sample.clone()),
            strip_nulls(serialized),
            "v2/{name}"
        );
        // Unknown fields are ignored in v2 as in v1 (an ack's payload is kept as JSON, so an
        // added field there is content).
        let mut extended = sample.clone();
        add_unknown_fields(&mut extended);
        let again = Message::from_value(extended)
            .unwrap_or_else(|e| panic!("v2/{name} with unknown fields does not parse: {e}"));
        if sample["type"] != "run.request.ack" {
            assert_eq!(again, parsed, "v2/{name}");
        }
    }
}

#[test]
fn every_v2_message_and_event_type_has_a_sample() {
    let schema: Value = serde_json::from_str(SCHEMA_JSON_V2).unwrap();
    let defs = &schema["$defs"];
    let mut schema_types = BTreeSet::new();
    for variant in defs["Message"]["oneOf"].as_array().unwrap() {
        let reference = variant["$ref"].as_str().unwrap();
        let name = reference.trim_start_matches("#/$defs/");
        let t = defs[name]["properties"]["type"]["const"].as_str().unwrap();
        schema_types.insert(t.to_string());
    }
    let ours: BTreeSet<String> = MESSAGE_TYPES_V2.iter().map(|s| s.to_string()).collect();
    assert_eq!(
        schema_types, ours,
        "MESSAGE_TYPES_V2 differs from the schema"
    );
    assert_eq!(
        message_types(PROTOCOL_VERSION_V2),
        Some(&MESSAGE_TYPES_V2[..])
    );
    assert_eq!(message_types(PROTOCOL_VERSION), Some(&MESSAGE_TYPES[..]));

    let samples = samples_v2();
    let sample_types: BTreeSet<String> = samples
        .iter()
        .map(|(_, v)| v["type"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(sample_types, ours, "a v2 message type has no sample");

    let event_types: BTreeSet<String> = defs["RunEvent"]["properties"]["eventType"]["enum"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_str().unwrap().to_string())
        .collect();
    let sampled_events: BTreeSet<String> = samples
        .iter()
        .filter(|(_, v)| v["type"] == "run.event")
        .map(|(_, v)| v["eventType"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(event_types, sampled_events, "an eventType has no v2 sample");
    // Every request kind and every ack status has a sample.
    let kinds: BTreeSet<String> = samples
        .iter()
        .filter(|(_, v)| v["type"] == "run.request")
        .map(|(_, v)| v["kind"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(
        kinds,
        ["ACCESS", "ARTIFACT", "ASK", "NETWORK", "TOOL"]
            .iter()
            .map(|s| s.to_string())
            .collect()
    );
    let statuses: BTreeSet<String> = samples
        .iter()
        .filter(|(_, v)| v["type"] == "run.request.ack")
        .map(|(_, v)| v["status"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(
        statuses,
        ["DONE", "PENDING", "REJECTED"]
            .iter()
            .map(|s| s.to_string())
            .collect()
    );
}

fn request(payload: RequestPayload) -> Message {
    Message::RunRequest(RunRequest {
        run_id: run_id(),
        epoch: 1,
        request_id: Uuid::new_v4(),
        request: payload,
    })
}

fn ack(status: RequestStatus, payload: Option<Value>, code: Option<&str>) -> Message {
    Message::RunRequestAck(RunRequestAck {
        run_id: run_id(),
        request_id: Uuid::new_v4(),
        status,
        payload,
        code: code.map(str::to_string),
        message: Some("m".into()),
    })
}

/// Every v2 shape the types can build, for the v2 schema.
fn constructed_messages_v2() -> Vec<Message> {
    let at = Utc.with_ymd_and_hms(2026, 10, 5, 9, 30, 0).unwrap();
    let mut out = vec![
        Message::Hello(Hello {
            protocol_version: PROTOCOL_VERSION_V2,
            runner_version: "0.1.0".into(),
            hostname: None,
            os: None,
            arch: None,
            executors: vec![Executor::Docker],
            isolation_tiers: vec![IsolationTier::Microvm],
            max_concurrent_runs: 4,
            credentials: vec![],
            runtimes: vec![],
            active_runs: None,
            public_suffix_list: Some(PublicSuffixListVersion {
                sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".into(),
            }),
            features: vec![feature::RUN_STEER.into(), feature::RUN_SKILLS.into()],
            pool: None,
        }),
        // A host that keeps ready sandboxes (ADR-0098, ADR-0099), above the 16 of a host with
        // Docker (D12).
        Message::Hello(Hello {
            protocol_version: PROTOCOL_VERSION_V2,
            runner_version: "0.3.0".into(),
            hostname: None,
            os: None,
            arch: None,
            executors: vec![Executor::Docker],
            isolation_tiers: vec![IsolationTier::Microvm],
            max_concurrent_runs: MAX_HOST_CAPACITY,
            credentials: vec![],
            runtimes: vec![],
            active_runs: None,
            public_suffix_list: None,
            features: vec![feature::SANDBOX_POOL.into()],
            pool: Some(HelloPool { max_ready: 4 }),
        }),
        Message::HostConfig(HostConfig {
            version: 9,
            desired_max_concurrent_runs: Some(MAX_HOST_CAPACITY),
            credentials: vec![],
            pool: Some(PoolConfig {
                ready_target: 2,
                next: Some(PoolChange {
                    ready_target: 0,
                    at,
                }),
            }),
        }),
        Message::HostConfig(HostConfig {
            version: 10,
            desired_max_concurrent_runs: None,
            credentials: vec![],
            pool: Some(PoolConfig {
                ready_target: 0,
                next: None,
            }),
        }),
        Message::HostConfigApplied(HostConfigApplied {
            version: 9,
            draining: false,
            max_concurrent_runs: 24,
            max_concurrent_runs_ceiling: MAX_HOST_CAPACITY,
            credentials: vec![],
            pool: Some(PoolApplied {
                ready_target: 2,
                max_ready_ceiling: 4,
            }),
        }),
        Message::HostPoolStatus(HostPoolStatus {
            ready: 2,
            starting: 1,
            busy: 5,
            cold_start_p90_seconds: Some(74),
            served_ready: 31,
            served_cold: 4,
        }),
        Message::HostPoolStatus(HostPoolStatus {
            ready: 0,
            starting: 0,
            busy: 0,
            cold_start_p90_seconds: None,
            served_ready: 0,
            served_cold: 0,
        }),
        Message::Welcome(Welcome {
            protocol_version: Some(PROTOCOL_VERSION_V2),
            runner_host_id: Uuid::new_v4(),
            company_id: Uuid::new_v4(),
            server_version: Some("0.2.0".into()),
            heartbeat_interval_seconds: 10,
            lease_ttl_seconds: 30,
        }),
        Message::RunAssign(RunAssign {
            run_id: run_id(),
            epoch: 1,
            credential_label: Some("claude-main".into()),
            model_access: None,
            agent: AgentSpec {
                id: Uuid::new_v4(),
                name: "A".into(),
                runtime: Runtime::ClaudeCode,
                model: None,
                effort: Some(Effort::High),
            },
            subject: Subject::WorkItem(WorkItemSpec {
                id: Uuid::new_v4(),
                key: "SHOP-241".into(),
                title: "t".into(),
            }),
            job: Some(JobSpec { id: Uuid::new_v4() }),
            prompt: "p".into(),
            resume_session_id: None,
            workspaces: vec![],
            requestable_repositories: vec![],
            limits: Limits {
                max_wall_clock_seconds: 60,
                max_turns: 1,
            },
            resume_prompt: None,
            attachments: vec![],
            tools: vec![ToolSpec {
                name: "jira_get".into(),
                description: "Reads one issue by key".into(),
                input_schema: json!({"type": "object", "properties": {"key": {"type": "string"}}, "required": ["key"]}),
                replay: Some(ToolReplay::Safe),
            }],
            allowed_hosts: vec![
                "registry.example.com".into(),
                "*.cdn.example.com:8443".into(),
            ],
            platform_tools: Some(vec!["finish".into(), "list_repositories".into()]),
            skills: vec![AssignedSkill {
                skill_id: Uuid::new_v4(),
                version: 3,
                name: "release-notes".into(),
                sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".into(),
                size_bytes: SKILL_MAX_BYTES,
            }],
            session_bundle: Some(SessionBundleSpec {
                sha256: Some(
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".into(),
                ),
                size_bytes: Some(SESSION_BUNDLE_MAX_BYTES),
            }),
        }),
        Message::RunFinished(RunFinished {
            run_id: run_id(),
            epoch: 1,
            outcome: Outcome::Succeeded,
            last_seq: 3,
            failure_class: None,
            failure_message: None,
            summary: Some("s".into()),
            result: Some(RunResult {
                outcome: ResultOutcome::Completed,
                summary: "done".into(),
                next_steps: vec![],
                open_questions: vec![],
            }),
            session_id: None,
            git: Some(vec![]),
            usage: None,
            artifacts: vec![ArtifactResult {
                name: "reports/q3.md".into(),
                attachment_id: Uuid::new_v4(),
                size_bytes: 12,
            }],
            resumed: Some(false),
            resume_outcome: Some(ResumeOutcome::NoSession),
            session_bundle: Some(SessionBundleOutcome::Stored),
        }),
        request(RequestPayload::Ask(AskRequest {
            question: "q?".into(),
            options: vec!["a".into(), "b".into()],
        })),
        request(RequestPayload::Ask(AskRequest {
            question: "q?".into(),
            options: vec![],
        })),
        request(RequestPayload::Access(AccessRequest {
            repository: Some("docs".into()),
            tool: None,
            reason: "r".into(),
        })),
        request(RequestPayload::Access(AccessRequest {
            repository: None,
            tool: Some("Jira".into()),
            reason: "r".into(),
        })),
        request(RequestPayload::Artifact(ArtifactRequest {
            url: "https://wiki.example.com/page".into(),
            title: "Page".into(),
        })),
        request(RequestPayload::Network(NetworkRequest {
            host: "registry.example.com:8443".into(),
            reason: "r".into(),
        })),
        request(RequestPayload::Tool(ToolRequest {
            tool: "jira_get".into(),
            arguments: json!({"key": "SHOP-241"}),
        })),
        ack(
            RequestStatus::Pending,
            Some(json!({"decisionId": Uuid::new_v4()})),
            None,
        ),
        ack(
            RequestStatus::Done,
            Some(json!({"artifactId": Uuid::new_v4()})),
            None,
        ),
        ack(
            RequestStatus::Done,
            Some(
                serde_json::to_value(ToolResultPayload {
                    content: vec![json!({"type": "text", "text": "SHOP-241: t"})],
                    structured_content: Some(json!({"key": "SHOP-241"})),
                    is_error: false,
                })
                .unwrap(),
            ),
            None,
        ),
        ack(
            RequestStatus::Rejected,
            None,
            Some(request_code::RESULT_TOO_LARGE),
        ),
    ];
    out.push(Message::PslUpdate(PslUpdate {
        sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".into(),
        fetched_at: at,
        rule_count: 9000,
        content: "H4sIAAAAAAAAA8vPBQBH8y4KAgAAAA==".into(),
    }));
    let steer = RunSteer {
        run_id: run_id(),
        epoch: 1,
        steer_id: Uuid::new_v4(),
        text: "Use the staging database.".into(),
    };
    out.push(Message::RunSteerAck(RunSteerAck::delivered(&steer)));
    for code in [
        steer_code::NOT_SUPPORTED,
        steer_code::NOT_RUNNING,
        steer_code::FAILED,
    ] {
        out.push(Message::RunSteerAck(RunSteerAck::refused(
            &steer, code, "m",
        )));
    }
    out.push(Message::RunSteer(steer));
    for (i, event) in all_payloads(true).into_iter().enumerate() {
        out.push(Message::RunEvent(RunEvent {
            run_id: run_id(),
            epoch: 1,
            seq: i as u64 + 1,
            at,
            event,
        }));
    }
    out
}

#[test]
fn constructed_v2_messages_validate_and_round_trip() {
    let v = validator_v2();
    for message in constructed_messages_v2() {
        let value = serde_json::to_value(&message).unwrap();
        assert_valid(&v, &value, message.message_type());
        let parsed = Message::parse_version(&message.to_json(), PROTOCOL_VERSION_V2).unwrap();
        assert_eq!(parsed, message);
    }
}

#[test]
fn the_versions_refuse_each_others_frames() {
    let v1 = validator();
    let v2 = validator_v2();
    // The bespoke pairs are v1 only; the generic pair is v2 only.
    for name in [
        "run.ask.json",
        "run.ask.ack.json",
        "run.repository_request.json",
        "run.repository_request.ack.json",
        "run.task_create.json",
        "run.task_create.ack.json",
    ] {
        let frame = sample(name);
        assert!(!v2.is_valid(&frame), "{name} is not a v2 frame");
        assert!(
            Message::from_value_version(frame, PROTOCOL_VERSION_V2).is_err(),
            "{name} is not a v2 frame"
        );
    }
    for name in [
        "run.request-ask.json",
        "run.request.ack-pending.json",
        "psl.update.json",
    ] {
        let frame = sample_v2(name);
        assert!(!v1.is_valid(&frame), "v2/{name} is not a v1 frame");
        assert!(
            Message::from_value_version(frame, PROTOCOL_VERSION).is_err(),
            "v2/{name} is not a v1 frame"
        );
    }
    // hello names the version; welcome echoes it in v2 and must.
    assert!(!v2.is_valid(&sample("hello.json")));
    assert!(!v1.is_valid(&sample_v2("hello.json")));
    assert!(!v2.is_valid(&sample("welcome.json")), "no protocolVersion");
    let mut old = sample_v2("welcome.json");
    old["protocolVersion"] = json!(1);
    assert!(!v2.is_valid(&old), "a welcome for v1");
    assert_valid(&v1, &sample_v2("welcome.json"), "v1 ignores the field");
    // run.assign: `task` in v1, `workItem` and `job` in v2.
    assert!(!v2.is_valid(&sample("run.assign.json")));
    assert!(!v1.is_valid(&sample_v2("run.assign.json")));
    let mut no_job = sample_v2("run.assign.json");
    no_job.as_object_mut().unwrap().remove("job");
    assert!(!v2.is_valid(&no_job), "v2 needs the job");
    // The types read both names, so a host of either version can decode either server.
    let Message::RunAssign(assign) = Message::from_value(sample_v2("run.assign.json")).unwrap()
    else {
        panic!("not run.assign");
    };
    assert_eq!(assign.work_item().key, "MYCO-12");
    assert_eq!(assign.tools.len(), 2);
    assert_eq!(assign.tools[0].name, "jira_get");
    assert!(assign.job.is_some());
    let Message::RunAssign(assign) = Message::from_value(sample("run.assign.json")).unwrap() else {
        panic!("not run.assign");
    };
    assert_eq!(assign.work_item().key, "MYCO-12");
    assert!(assign.tools.is_empty() && assign.job.is_none());
    assert_eq!(assign.platform_tools, None);
    let out = serde_json::to_value(Message::RunAssign(assign)).unwrap();
    assert!(out.get("platformTools").is_none());
    // `microvm` is a v2 tier, and an additive change of the v1 schema.
    let mut hello = sample("hello.json");
    hello["isolationTiers"] = json!(["microvm"]);
    assert_valid(&v1, &hello, "microvm in v1");
    hello["isolationTiers"] = json!(["vm"]);
    assert!(!v1.is_valid(&hello));
    assert!(Message::from_value(hello).is_err());
    // v1 never writes the v2 fields.
    let Message::RunFinished(finished) = Message::from_value(sample("run.finished.json")).unwrap()
    else {
        panic!("not run.finished");
    };
    let out = serde_json::to_value(Message::RunFinished(finished)).unwrap();
    assert!(out.get("artifacts").is_none());
    assert!(out.get("resumed").is_none() && out.get("resumeOutcome").is_none());
    assert!(out["usage"].get("cacheWriteTokens").is_none());
    let Message::Welcome(welcome) = Message::from_value(sample("welcome.json")).unwrap() else {
        panic!("not welcome");
    };
    assert_eq!(welcome.protocol_version, None);
}

#[test]
fn request_bounds() {
    let v = validator_v2();
    let ask = sample_v2("run.request-ask.json");
    for (field, value) in [
        ("question", json!("")),
        ("question", json!("x".repeat(ASK_QUESTION_MAX_CHARS + 1))),
        ("options", json!([""])),
        ("options", json!(["x".repeat(ASK_OPTION_MAX_CHARS + 1)])),
        ("options", json!(vec!["x"; MAX_ASK_OPTIONS + 1])),
        ("options", json!("yes")),
    ] {
        let mut bad = ask.clone();
        bad["payload"][field] = value;
        assert!(
            !v.is_valid(&bad),
            "ASK {field} out of bounds must be invalid"
        );
    }
    let access = sample_v2("run.request-access-repository.json");
    for (field, value) in [
        ("repository", json!("")),
        ("repository", json!("x".repeat(ACCESS_NAME_MAX_CHARS + 1))),
        ("reason", json!("")),
        ("reason", json!("x".repeat(ACCESS_REASON_MAX_CHARS + 1))),
        ("tool", json!("Jira")),
    ] {
        let mut bad = access.clone();
        bad["payload"][field] = value;
        assert!(!v.is_valid(&bad), "ACCESS {field} must be invalid");
    }
    let mut neither = access.clone();
    neither["payload"]
        .as_object_mut()
        .unwrap()
        .remove("repository");
    assert!(!v.is_valid(&neither), "ACCESS needs a repository or a tool");
    let network = sample_v2("run.request-network.json");
    for host in [
        "example.com",
        "*.example.com",
        "api.example.com:8443",
        "192.0.2.10:65535",
        "[2001:db8::1]:443",
        "x.example:1",
    ] {
        let mut good = network.clone();
        good["payload"]["host"] = json!(host);
        assert_valid(&v, &good, &format!("NETWORK host {host}"));
    }
    for host in [
        "",
        "https://example.com",
        "example.com/path",
        "user@example.com",
        "example.com:0",
        "example.com:65536",
        "example.com:*",
        "example.com:",
        "*",
        "exa mple.com",
        &"x".repeat(NETWORK_HOST_MAX_CHARS + 1),
    ] {
        let mut bad = network.clone();
        bad["payload"]["host"] = json!(host);
        assert!(!v.is_valid(&bad), "NETWORK host {host:?} must be invalid");
    }
    for (field, value) in [
        ("reason", json!("")),
        ("reason", json!("x".repeat(NETWORK_REASON_MAX_CHARS + 1))),
        ("host", json!(7)),
    ] {
        let mut bad = network.clone();
        bad["payload"][field] = value;
        assert!(!v.is_valid(&bad), "NETWORK {field} must be invalid");
    }
    let mut no_host = network.clone();
    no_host["payload"].as_object_mut().unwrap().remove("host");
    assert!(!v.is_valid(&no_host), "NETWORK needs a host");
    assert!(Message::from_value(no_host).is_err());
    let Message::RunRequest(parsed) = Message::from_value(network.clone()).unwrap() else {
        panic!("not a run.request");
    };
    assert_eq!(parsed.request.kind(), RequestKind::Network);
    assert_eq!(parsed.request.kind().as_str(), "NETWORK");
    // The payload of another kind is no NETWORK payload.
    let mut mismatched = network;
    mismatched["kind"] = json!("ACCESS");
    assert!(
        !v.is_valid(&mismatched),
        "an ACCESS needs a repository or a tool"
    );
    let artifact = sample_v2("run.request-artifact.json");
    for (field, value) in [
        ("url", json!("")),
        ("url", json!("ftp://x.example/a")),
        ("url", json!("https://x.example/a b")),
        (
            "url",
            json!(format!(
                "https://x.example/{}",
                "a".repeat(ARTIFACT_URL_MAX_CHARS)
            )),
        ),
        ("title", json!("")),
        ("title", json!("x".repeat(ARTIFACT_TITLE_MAX_CHARS + 1))),
    ] {
        let mut bad = artifact.clone();
        bad["payload"][field] = value;
        assert!(!v.is_valid(&bad), "ARTIFACT {field} must be invalid");
    }
    let tool = sample_v2("run.request-tool.json");
    for (field, value) in [
        ("tool", json!("")),
        ("tool", json!("x".repeat(TOOL_NAME_MAX_LEN + 1))),
        ("arguments", json!("key=SHOP-1")),
        ("arguments", json!(null)),
    ] {
        let mut bad = tool.clone();
        bad["payload"][field] = value;
        assert!(!v.is_valid(&bad), "TOOL {field} must be invalid");
    }
    let mut kind = tool.clone();
    kind["kind"] = json!("WRITE");
    assert!(!v.is_valid(&kind));
    assert!(Message::from_value(kind).is_err());
    let mut mismatched = tool;
    mismatched["kind"] = json!("ASK");
    assert!(!v.is_valid(&mismatched), "an ASK needs a question");
    assert!(Message::from_value(mismatched).is_err());
    // `allowedHosts` is optional, a list of strings, and read into the frame.
    let Message::RunAssign(with_hosts) = Message::from_value(assign_with_hosts()).unwrap() else {
        panic!("not a run.assign");
    };
    assert_eq!(
        with_hosts.allowed_hosts,
        ["registry.example.com", "*.cdn.example.com:8443"]
    );
    let Message::RunAssign(without) =
        Message::from_value(sample_v2("run.assign-bedrock.json")).unwrap()
    else {
        panic!("not a run.assign");
    };
    assert!(without.allowed_hosts.is_empty());
    assert_valid(&v, &assign_with_hosts(), "allowedHosts");
    for bad in [
        json!("example.com"),
        json!([7]),
        json!([""]),
        json!(vec!["x"; MAX_TOOLS + 1]),
    ] {
        let mut assign = sample_v2("run.assign.json");
        assign["allowedHosts"] = bad.clone();
        assert!(!v.is_valid(&assign), "allowedHosts {bad} must be invalid");
    }
    // The tools of an assignment are named like the schema says.
    let mut assign = sample_v2("run.assign.json");
    assign["tools"][0]["name"] = json!("Jira Get");
    assert!(!v.is_valid(&assign));
    assert!(!is_valid_tool_name("Jira Get"));
    let mut too_many = sample_v2("run.assign.json");
    let item = too_many["tools"][0].clone();
    too_many["tools"] = Value::Array(vec![item; MAX_TOOLS + 1]);
    assert!(!v.is_valid(&too_many));
}

fn assign_with_hosts() -> Value {
    let assign = sample_v2("run.assign.json");
    assert_eq!(
        assign["allowedHosts"],
        json!(["registry.example.com", "*.cdn.example.com:8443"])
    );
    assign
}

#[test]
fn run_assign_agent_effort_is_an_optional_v2_level() {
    let v = validator_v2();
    // Named: read into the agent and written back as it came.
    let assign = sample_v2("run.assign-resume-no-workspaces.json");
    assert_valid(&v, &assign, "agent.effort");
    let Message::RunAssign(parsed) =
        Message::from_value_version(assign, PROTOCOL_VERSION_V2).unwrap()
    else {
        panic!("not a run.assign");
    };
    assert_eq!(parsed.agent.effort, Some(Effort::High));
    assert_eq!(parsed.agent.effort.map(Effort::level), Some("high"));
    let out = serde_json::to_value(Message::RunAssign(parsed)).unwrap();
    assert_eq!(out["agent"]["effort"], json!("HIGH"));
    // Absent: the runtime's default, and never written as null.
    let Message::RunAssign(without) = Message::from_value(sample_v2("run.assign.json")).unwrap()
    else {
        panic!("not a run.assign");
    };
    assert_eq!(without.agent.effort, None);
    let out = serde_json::to_value(Message::RunAssign(without)).unwrap();
    assert!(out["agent"].get("effort").is_none());
    // Only the five levels: the runtime's default is the absent field, not a value.
    for bad in ["DEFAULT", "low", "EXTREME"] {
        let mut assign = sample_v2("run.assign.json");
        assign["agent"]["effort"] = json!(bad);
        assert!(!v.is_valid(&assign), "{bad} must be refused");
        assert!(Message::from_value_version(assign, PROTOCOL_VERSION_V2).is_err());
    }
    // The levels in order, each with the name the runtimes use.
    assert_eq!(
        Effort::ALL.map(Effort::level),
        ["low", "medium", "high", "xhigh", "max"]
    );
    assert!(Effort::ALL.windows(2).all(|w| w[0] < w[1]));
    assert_eq!(Effort::from_level("xhigh"), Some(Effort::Xhigh));
    assert_eq!(Effort::from_level("minimal"), None);
}

#[test]
fn run_assign_platform_tools_is_an_optional_allowlist() {
    let v = validator_v2();
    // Named: valid, read into the frame and written back as it came.
    let assign = sample_v2("run.assign-model-access.json");
    assert_valid(&v, &assign, "platformTools");
    let Message::RunAssign(parsed) =
        Message::from_value_version(assign, PROTOCOL_VERSION_V2).unwrap()
    else {
        panic!("not a run.assign");
    };
    assert_eq!(
        parsed.platform_tools.as_deref(),
        Some(
            &[
                "finish".to_string(),
                "list_repositories".into(),
                "save_artifact".into()
            ][..]
        )
    );
    let out = serde_json::to_value(Message::RunAssign(parsed.clone())).unwrap();
    assert_eq!(
        out["platformTools"],
        json!(["finish", "list_repositories", "save_artifact"])
    );
    assert_valid(&v, &out, "platformTools (re-serialized)");
    assert_eq!(
        Message::from_value(out).unwrap(),
        Message::RunAssign(parsed)
    );
    // Absent: every platform tool, and the host never writes the field.
    let Message::RunAssign(without) =
        Message::from_value(sample_v2("run.assign-bedrock.json")).unwrap()
    else {
        panic!("not a run.assign");
    };
    assert_eq!(without.platform_tools, None);
    let out = serde_json::to_value(Message::RunAssign(without)).unwrap();
    assert!(out.get("platformTools").is_none());
    // An empty list is not an absent one: only the two the host always lists.
    let mut empty = sample_v2("run.assign.json");
    empty["platformTools"] = json!([]);
    assert_valid(&v, &empty, "empty platformTools");
    let Message::RunAssign(empty) = Message::from_value(empty).unwrap() else {
        panic!("not a run.assign");
    };
    assert_eq!(empty.platform_tools, Some(vec![]));
    // The schema names the platform tools, once each; the types read any string, since the
    // host ignores a name it does not know.
    for bad in [
        json!("ask"),
        json!([7]),
        json!([""]),
        json!(["request_repository"]),
        json!(["ask", "ask"]),
    ] {
        let mut assign = sample_v2("run.assign.json");
        assign["platformTools"] = bad.clone();
        assert!(!v.is_valid(&assign), "platformTools {bad} must be invalid");
    }
    let mut unknown = sample_v2("run.assign.json");
    unknown["platformTools"] = json!(["request_repository"]);
    let Message::RunAssign(unknown) = Message::from_value(unknown).unwrap() else {
        panic!("not a run.assign");
    };
    assert_eq!(
        unknown.platform_tools.as_deref(),
        Some(&["request_repository".to_string()][..])
    );
}

#[test]
fn request_ack_carries_what_its_status_needs() {
    let v = validator_v2();
    let pending = sample_v2("run.request.ack-pending.json");
    let mut no_decision = pending.clone();
    no_decision["payload"] = json!({});
    assert!(!v.is_valid(&no_decision), "PENDING needs decisionId");
    let mut no_payload = pending;
    no_payload.as_object_mut().unwrap().remove("payload");
    assert!(!v.is_valid(&no_payload), "PENDING needs a payload");
    assert_valid(
        &v,
        &sample_v2("run.request.ack-pending-no-message.json"),
        "a PENDING ack without a message",
    );

    let done = sample_v2("run.request.ack-tool.json");
    let mut no_result = done.clone();
    no_result["payload"] = json!({"text": "x"});
    assert!(
        !v.is_valid(&no_result),
        "DONE needs a tool result or an artifact"
    );
    let mut no_flag = done;
    no_flag["payload"]
        .as_object_mut()
        .unwrap()
        .remove("isError");
    assert!(
        !v.is_valid(&no_flag),
        "a tool result says whether it is an error"
    );

    let rejected = sample_v2("run.request.ack-rejected.json");
    assert_valid(&v, &rejected, "rejected ack");
    let mut no_code = rejected.clone();
    no_code.as_object_mut().unwrap().remove("code");
    assert!(!v.is_valid(&no_code), "REJECTED needs code");
    let mut no_message = rejected.clone();
    no_message["message"] = Value::Null;
    assert!(!v.is_valid(&no_message), "REJECTED needs message");
    let mut bad_status = rejected;
    bad_status["status"] = json!("CREATED");
    assert!(!v.is_valid(&bad_status));
    assert!(Message::from_value(bad_status).is_err());

    let Message::RunRequestAck(parsed) =
        Message::from_value(sample_v2("run.request.ack-tool.json")).unwrap()
    else {
        panic!("not an ack");
    };
    let result = parsed.tool_result().expect("a tool result");
    assert_eq!(result.content[0]["type"], "text");
    assert!(!result.is_error);
    assert!(result.structured_content.is_some());
    assert_eq!(parsed.decision_id(), None);
}

#[test]
fn run_finished_artifacts_bounds() {
    let v = validator_v2();
    let base = sample_v2("run.finished.json");
    let Message::RunFinished(parsed) = Message::from_value(base.clone()).unwrap() else {
        panic!("not run.finished");
    };
    assert_eq!(parsed.artifacts.len(), 2);
    assert_eq!(parsed.artifacts[0].name, "reports/q3-profitability.md");
    for (field, value) in [
        ("name", json!("")),
        ("attachmentId", json!("not-a-uuid")),
        ("sizeBytes", json!(-1)),
    ] {
        let mut bad = base.clone();
        bad["artifacts"][0][field] = value;
        assert!(!v.is_valid(&bad), "artifacts[].{field} must be invalid");
    }
    for field in ["name", "attachmentId", "sizeBytes"] {
        let mut missing = base.clone();
        missing["artifacts"][0]
            .as_object_mut()
            .unwrap()
            .remove(field);
        assert!(!v.is_valid(&missing), "artifacts[].{field} is required");
        assert!(Message::from_value(missing).is_err());
    }
    let mut too_many = base.clone();
    let item = too_many["artifacts"][0].clone();
    too_many["artifacts"] = Value::Array(vec![item; MAX_ARTIFACTS + 1]);
    assert!(!v.is_valid(&too_many));
    let mut none = base;
    none.as_object_mut().unwrap().remove("artifacts");
    assert_valid(&v, &none, "no artifacts");
}

#[test]
fn run_finished_resume_fields() {
    let v = validator_v2();
    let base = sample_v2("run.finished.json");
    let Message::RunFinished(parsed) = Message::from_value(base.clone()).unwrap() else {
        panic!("not run.finished");
    };
    assert_eq!(parsed.resumed, Some(true));
    assert_eq!(parsed.resume_outcome, None);
    for (name, outcome) in [
        ("run.finished-failed.json", ResumeOutcome::NoSession),
        ("run.finished-no-repositories.json", ResumeOutcome::FirstRun),
        ("run.finished-refused.json", ResumeOutcome::LoadFailed),
        ("run.finished-result.json", ResumeOutcome::NotSupported),
    ] {
        let Message::RunFinished(parsed) = Message::from_value(sample_v2(name)).unwrap() else {
            panic!("not run.finished");
        };
        assert_eq!(parsed.resumed, Some(false), "{name}");
        assert_eq!(parsed.resume_outcome, Some(outcome), "{name}");
        assert_eq!(
            serde_json::to_value(outcome).unwrap(),
            json!(outcome.as_str())
        );
    }
    let mut contradiction = base.clone();
    contradiction["resumeOutcome"] = json!("NO_SESSION");
    assert!(
        !v.is_valid(&contradiction),
        "a resumed run has no resumeOutcome"
    );
    let mut unknown = base.clone();
    unknown["resumed"] = json!(false);
    unknown["resumeOutcome"] = json!("MAYBE");
    assert!(!v.is_valid(&unknown));
    assert!(Message::from_value(unknown).is_err());
    // Not resumed and not tried (the run ended before session/load): no outcome.
    let mut untried = base.clone();
    untried["resumed"] = json!(false);
    assert_valid(&v, &untried, "not resumed, not tried");
    // A host older than the fields: not known.
    let mut older = base;
    older.as_object_mut().unwrap().remove("resumed");
    assert_valid(&v, &older, "no resumed");
    let Message::RunFinished(parsed) = Message::from_value(older).unwrap() else {
        panic!("not run.finished");
    };
    assert_eq!((parsed.resumed, parsed.resume_outcome), (None, None));
}

#[test]
fn usage_cache_writes() {
    let v1 = validator();
    let v2 = validator_v2();
    let base = sample_v2("run.finished.json");
    let Message::RunFinished(parsed) = Message::from_value(base.clone()).unwrap() else {
        panic!("not run.finished");
    };
    // v2 reports cache writes apart from the input; the four parts add up to the total.
    let usage = parsed.usage.clone().unwrap();
    assert_eq!(usage.cache_write_tokens, Some(300));
    assert_eq!(
        usage.input_tokens.unwrap()
            + usage.cached_input_tokens.unwrap()
            + usage.cache_write_tokens.unwrap()
            + usage.output_tokens.unwrap(),
        usage.total_tokens.unwrap()
    );
    // v1 has no cacheWriteTokens: they count as input, and the total stays the same.
    let mut v1_finished = parsed.clone();
    v1_finished.usage = Some(usage.clone().into_v1());
    v1_finished.artifacts = vec![];
    v1_finished.resumed = None;
    let out = serde_json::to_value(Message::RunFinished(v1_finished)).unwrap();
    assert_valid(&v1, &out, "v1 run.finished");
    assert!(out["usage"].get("cacheWriteTokens").is_none());
    assert_eq!(out["usage"]["inputTokens"], json!(1500));
    assert_eq!(out["usage"]["totalTokens"], json!(2640));
    assert_eq!(UsagePayload::default().into_v1(), UsagePayload::default());
    let mut not_a_count = base.clone();
    not_a_count["usage"]["cacheWriteTokens"] = json!("300");
    assert!(!v2.is_valid(&not_a_count));
    assert!(Message::from_value(not_a_count).is_err());
    // A host older than the field: not known, and its input includes the cache writes.
    let mut older = base;
    older["usage"]
        .as_object_mut()
        .unwrap()
        .remove("cacheWriteTokens");
    assert_valid(&v2, &older, "no cacheWriteTokens");
    let Message::RunFinished(parsed) = Message::from_value(older).unwrap() else {
        panic!("not run.finished");
    };
    assert_eq!(parsed.usage.unwrap().cache_write_tokens, None);
}

#[test]
fn psl_update_and_hello_version_bounds() {
    let v = validator_v2();
    let update = sample_v2("psl.update.json");
    assert_valid(&v, &update, "psl.update");
    for field in ["sha256", "fetchedAt", "ruleCount", "content"] {
        let mut frame = update.clone();
        frame.as_object_mut().unwrap().remove(field);
        assert!(!v.is_valid(&frame), "psl.update without {field}");
        assert!(
            Message::from_value(frame).is_err(),
            "psl.update without {field}"
        );
    }
    for bad in [
        "",
        "ABCDEF",
        &"A".repeat(64),
        &"0".repeat(63),
        &"0".repeat(65),
    ] {
        let mut frame = update.clone();
        frame["sha256"] = json!(bad);
        assert!(!v.is_valid(&frame), "sha256 {bad:?}");
        let mut hello = sample_v2("hello.json");
        hello["publicSuffixList"] = json!({"sha256": bad});
        assert!(!v.is_valid(&hello), "hello sha256 {bad:?}");
    }
    let mut big = update.clone();
    big["content"] = json!("A".repeat(1_572_864));
    assert_valid(&v, &big, "content at the limit");
    big["content"] = json!("A".repeat(1_572_865));
    assert!(!v.is_valid(&big), "content above the limit");
    let mut negative = update.clone();
    negative["ruleCount"] = json!(-1);
    assert!(!v.is_valid(&negative));
    // hello: the list is optional and its version needs a sha256.
    let hello = sample_v2("hello.json");
    assert!(hello.get("publicSuffixList").is_some());
    let mut none = hello.clone();
    none.as_object_mut().unwrap().remove("publicSuffixList");
    assert_valid(&v, &none, "hello without a list");
    let mut empty = hello;
    empty["publicSuffixList"] = json!({});
    assert!(!v.is_valid(&empty));
}

#[test]
fn steer_bounds_and_versions() {
    let v1 = validator();
    let v2 = validator_v2();
    let steer = sample_v2("run.steer.json");
    let delivered = sample_v2("run.steer.ack.json");
    let refused = sample_v2("run.steer.ack-refused.json");
    for (name, frame) in [
        ("run.steer", &steer),
        ("run.steer.ack", &delivered),
        ("run.steer.ack-refused", &refused),
    ] {
        assert_valid(&v2, frame, name);
        // A v2 feature: v1 knows no such frame.
        assert!(!v1.is_valid(frame), "{name} is not a v1 frame");
        assert!(
            Message::from_value_version(frame.clone(), PROTOCOL_VERSION).is_err(),
            "{name} is not a v1 frame"
        );
    }
    for field in ["runId", "epoch", "steerId", "text"] {
        let mut frame = steer.clone();
        frame.as_object_mut().unwrap().remove(field);
        assert!(!v2.is_valid(&frame), "run.steer without {field}");
        assert!(
            Message::from_value(frame).is_err(),
            "run.steer without {field}"
        );
    }
    let mut empty = steer.clone();
    empty["text"] = json!("");
    assert!(!v2.is_valid(&empty), "empty text");
    let mut long = steer.clone();
    long["text"] = json!("x".repeat(STEER_TEXT_MAX_CHARS));
    assert_valid(&v2, &long, "text at the limit");
    long["text"] = json!("x".repeat(STEER_TEXT_MAX_CHARS + 1));
    assert!(!v2.is_valid(&long), "text above the limit");
    // REFUSED needs its code; DELIVERED needs none.
    let mut no_code = refused.clone();
    no_code.as_object_mut().unwrap().remove("code");
    assert!(!v2.is_valid(&no_code), "REFUSED without a code");
    let mut null_code = refused.clone();
    null_code["code"] = Value::Null;
    assert!(!v2.is_valid(&null_code), "REFUSED with a null code");
    let mut other = delivered.clone();
    other["status"] = json!("QUEUED");
    assert!(!v2.is_valid(&other));
    assert!(Message::from_value(other).is_err());
    let Message::RunSteerAck(ack) = Message::from_value(refused).unwrap() else {
        panic!("not run.steer.ack");
    };
    assert_eq!(ack.status, SteerStatus::Refused);
    assert_eq!(ack.code.as_deref(), Some(steer_code::NOT_SUPPORTED));
    let Message::RunSteer(parsed) = Message::from_value(steer).unwrap() else {
        panic!("not run.steer");
    };
    let long_message = RunSteerAck::refused(&parsed, steer_code::FAILED, "x".repeat(5000));
    assert_eq!(
        long_message.message.unwrap().chars().count(),
        STEER_ACK_MESSAGE_MAX_CHARS
    );
    // hello.features: optional names; the types leave an empty list out.
    let hello = sample_v2("hello.json");
    assert_eq!(hello["features"], json!([feature::RUN_STEER]));
    let mut none = hello.clone();
    none.as_object_mut().unwrap().remove("features");
    assert_valid(&v2, &none, "hello without features");
    let Message::Hello(parsed) = Message::from_value(none).unwrap() else {
        panic!("not hello");
    };
    assert!(parsed.features.is_empty());
    let out = serde_json::to_value(Message::Hello(parsed)).unwrap();
    assert!(out.get("features").is_none());
    let mut unknown = hello.clone();
    unknown["features"] = json!(["run.steer", "some.later-feature"]);
    assert_valid(&v2, &unknown, "an unknown feature");
    for bad in [json!(["Run.Steer"]), json!([""]), json!("run.steer")] {
        let mut frame = hello.clone();
        frame["features"] = bad.clone();
        assert!(!v2.is_valid(&frame), "features {bad}");
    }
}

#[test]
fn run_assign_skills_are_an_optional_v2_list() {
    let v = validator_v2();
    // Named: read into the frame and written back as it came.
    let assign = sample_v2("run.assign-skills.json");
    assert_valid(&v, &assign, "skills");
    let Message::RunAssign(parsed) =
        Message::from_value_version(assign.clone(), PROTOCOL_VERSION_V2).unwrap()
    else {
        panic!("not a run.assign");
    };
    assert_eq!(parsed.skills.len(), 2);
    assert_eq!(parsed.skills[0].name, "release-notes");
    assert_eq!(parsed.skills[0].version, 3);
    assert_eq!(parsed.skills[1].size_bytes, SKILL_MAX_BYTES);
    let out = serde_json::to_value(Message::RunAssign(parsed)).unwrap();
    assert_eq!(out["skills"], assign["skills"]);
    // Absent: no skills, and the host never writes the field.
    let Message::RunAssign(without) = Message::from_value(sample_v2("run.assign.json")).unwrap()
    else {
        panic!("not a run.assign");
    };
    assert!(without.skills.is_empty());
    let out = serde_json::to_value(Message::RunAssign(without)).unwrap();
    assert!(out.get("skills").is_none());
    // The schema's bounds.
    let skill = assign["skills"][0].clone();
    let with = |field: &str, value: Option<Value>| {
        let mut frame = assign.clone();
        match value {
            Some(value) => frame["skills"][0][field] = value,
            None => {
                frame["skills"][0].as_object_mut().unwrap().remove(field);
            }
        }
        frame
    };
    for (what, frame) in [
        ("version 0", with("version", Some(json!(0)))),
        (
            "an upper-case name",
            with("name", Some(json!("Release-Notes"))),
        ),
        ("a name with a slash", with("name", Some(json!("a/b")))),
        ("a dot name", with("name", Some(json!("..")))),
        ("a long name", with("name", Some(json!("a".repeat(65))))),
        (
            "an upper-case hash",
            with("sha256", Some(json!("A".repeat(64)))),
        ),
        ("a short hash", with("sha256", Some(json!("a".repeat(63))))),
        ("size 0", with("sizeBytes", Some(json!(0)))),
        (
            "a size over 5 MiB",
            with("sizeBytes", Some(json!(SKILL_MAX_BYTES + 1))),
        ),
        ("no skillId", with("skillId", None)),
        ("no sha256", with("sha256", None)),
    ] {
        assert!(!v.is_valid(&frame), "{what} must be invalid");
    }
    let mut too_many = assign.clone();
    too_many["skills"] = Value::Array(vec![skill.clone(); MAX_SKILLS + 1]);
    assert!(!v.is_valid(&too_many));
    let mut at_limit = assign.clone();
    at_limit["skills"] = Value::Array(
        (0..MAX_SKILLS)
            .map(|i| {
                let mut s = skill.clone();
                s["name"] = json!(format!("skill-{i}"));
                s
            })
            .collect(),
    );
    assert_valid(&v, &at_limit, "20 skills");
    let long = with("name", Some(json!("a".repeat(SKILL_NAME_MAX_LEN))));
    assert_valid(&v, &long, "a name at the limit");
}

#[test]
fn skill_name_helper_matches_the_schema() {
    let v = validator_v2();
    let at_limit = "a".repeat(SKILL_NAME_MAX_LEN);
    let over_limit = "a".repeat(SKILL_NAME_MAX_LEN + 1);
    for name in [
        "pdf",
        "release-notes",
        "a1-b2-c3",
        "0",
        "",
        "-a",
        "a-",
        "a--b",
        "A",
        "a_b",
        "a.b",
        "..",
        "a/b",
        "über",
        &at_limit,
        &over_limit,
    ] {
        let mut frame = sample_v2("run.assign-skills.json");
        frame["skills"][0]["name"] = json!(name);
        assert_eq!(
            is_valid_skill_name(name),
            v.is_valid(&frame),
            "the helper and the schema disagree on {name:?}"
        );
    }
}
