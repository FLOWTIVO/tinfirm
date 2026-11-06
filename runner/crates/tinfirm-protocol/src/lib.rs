//! Hand-written types for the Tinfirm runner protocol, versions 1 and 2.
//!
//! The binding contracts are `protocol/runner-v1.schema.json` and
//! `protocol/runner-v2.schema.json` (JSON Schema 2020-12) at the repository root. The tests in
//! `tests/schema.rs` validate samples and every type in this crate against both schemas, so
//! they cannot drift apart silently.
//!
//! Rules taken from the schemas:
//! - every frame is one JSON object whose `type` names the message;
//! - unknown fields are ignored (serde's default, so no `deny_unknown_fields` anywhere);
//! - unknown message types are reported as [`ParseError::UnknownType`] so the caller can
//!   answer with an `error` frame and otherwise ignore them.
//!
//! # The two versions in one set of types
//!
//! A host speaks one version per connection (`hello.protocolVersion`); the server answers
//! with the version it speaks (`welcome.protocolVersion`, v2). Both versions share one
//! [`Message`] enum, because most frames are the same in both; what differs is expressed
//! once, so that v1 code keeps working unchanged:
//! - the frames of a version are listed in [`MESSAGE_TYPES`] (v1) and [`MESSAGE_TYPES_V2`];
//!   [`Message::parse_version`] refuses a frame of the other version as
//!   [`ParseError::UnknownType`], as the schemas demand;
//! - v1's `run.ask`, `run.repository_request`, `run.task_create` and their acks exist only
//!   in v1; v2 carries every platform request as [`RunRequest`] and answers it with
//!   [`RunRequestAck`];
//! - `run.assign` names its subject `task` in v1 and `workItem` in v2 ([`Subject`], read with
//!   [`RunAssign::work_item`]); v2 adds `job` and the server-executed `tools`;
//! - `run.finished.artifacts`, `run.finished.resumed`, `run.finished.resumeOutcome` and
//!   `welcome.protocolVersion` are v2 fields that v1 never writes (an empty list and `None`
//!   are left out of the JSON);
//! - the usage of USAGE events and `run.finished` reports cache writes apart from the input
//!   in v2 (`cacheWriteTokens`); v1 counts them as input ([`UsagePayload::into_v1`]);
//! - `hello.isolationTiers` has `microvm` in v2 (and, as an additive change, in the v1
//!   schema: a server from before v2 refuses it, so a v1 host reports `container`).

use std::fmt;

use chrono::{DateTime, SecondsFormat, Utc};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use uuid::Uuid;

/// The binding JSON Schema of protocol v1 (`protocol/runner-v1.schema.json`), embedded for
/// tests of this crate and of the runner host.
pub const SCHEMA_JSON: &str = include_str!("../../../../protocol/runner-v1.schema.json");

/// The binding JSON Schema of protocol v2 (`protocol/runner-v2.schema.json`).
pub const SCHEMA_JSON_V2: &str = include_str!("../../../../protocol/runner-v2.schema.json");

/// Protocol v1 (`hello.protocolVersion`), the version a host speaks unless configured
/// otherwise.
pub const PROTOCOL_VERSION: u32 = 1;

/// Protocol v2: the generic request pair, server-executed tools, `/output` artifacts.
pub const PROTOCOL_VERSION_V2: u32 = 2;

/// Every version this crate speaks.
pub const PROTOCOL_VERSIONS: [u32; 2] = [PROTOCOL_VERSION, PROTOCOL_VERSION_V2];

/// The schema of `version`, or `None` for a version this crate does not speak.
pub fn schema_json(version: u32) -> Option<&'static str> {
    match version {
        PROTOCOL_VERSION => Some(SCHEMA_JSON),
        PROTOCOL_VERSION_V2 => Some(SCHEMA_JSON_V2),
        _ => None,
    }
}

/// The `type` values of `version`, in schema order, or `None` for an unknown version.
pub fn message_types(version: u32) -> Option<&'static [&'static str]> {
    match version {
        PROTOCOL_VERSION => Some(&MESSAGE_TYPES),
        PROTOCOL_VERSION_V2 => Some(&MESSAGE_TYPES_V2),
        _ => None,
    }
}

/// Every `type` value defined by the v1 schema, in schema order.
pub const MESSAGE_TYPES: [&str; 19] = [
    "hello",
    "welcome",
    "run.assign",
    "run.accepted",
    "run.rejected",
    "run.event",
    "run.heartbeat",
    "run.finished",
    "run.cancel",
    "run.repository_request",
    "run.repository_request.ack",
    "run.task_create",
    "run.task_create.ack",
    "run.ask",
    "run.ask.ack",
    "credential.status",
    "host.config",
    "host.config.applied",
    "error",
];

/// Every `type` value defined by the v2 schema, in schema order: v1 without the three
/// bespoke request pairs, plus `run.request` and `run.request.ack`, and the frames of the
/// optional features ([`feature`]).
pub const MESSAGE_TYPES_V2: [&str; 19] = [
    "hello",
    "welcome",
    "run.assign",
    "run.accepted",
    "run.rejected",
    "run.event",
    "run.heartbeat",
    "run.finished",
    "run.cancel",
    "run.request",
    "run.request.ack",
    "run.steer",
    "run.steer.ack",
    "credential.status",
    "host.config",
    "host.config.applied",
    "host.pool.status",
    "psl.update",
    "error",
];

/// Every `type` value of any version: what [`Message::parse`] accepts.
const KNOWN_MESSAGE_TYPES: [&str; 25] = [
    "hello",
    "welcome",
    "run.assign",
    "run.accepted",
    "run.rejected",
    "run.event",
    "run.heartbeat",
    "run.finished",
    "run.cancel",
    "run.repository_request",
    "run.repository_request.ack",
    "run.task_create",
    "run.task_create.ack",
    "run.ask",
    "run.ask.ack",
    "run.request",
    "run.request.ack",
    "run.steer",
    "run.steer.ack",
    "credential.status",
    "host.config",
    "host.config.applied",
    "host.pool.status",
    "psl.update",
    "error",
];

/// Largest number of concurrent runs of a credential's pool (`credential add --pool`), and of
/// a runner host with Docker (`run --max-concurrent-runs`): ceilings, desired and effective
/// values alike (`$defs/Capacity`).
pub const MAX_CAPACITY: u32 = 16;

/// Largest number of concurrent runs of any runner host in protocol v2 (`$defs/HostCapacity`):
/// a host that creates a sandbox per run has no fixed maximum of its own (D12 of the
/// Kubernetes runner analysis), its operator's ceiling and the provider's quota bound it.
pub const MAX_HOST_CAPACITY: u32 = 1000;

/// The value a host uses: the desired value from `host.config` capped by the local ceiling,
/// or the ceiling when no desired value is set. Never above the ceiling (docs/archive/v1/CONVENTIONS.md §15.7).
pub fn effective_capacity(desired: Option<u32>, ceiling: u32) -> u32 {
    desired.map_or(ceiling, |d| d.min(ceiling))
}

/// Values of `run.rejected.code` (docs/archive/v1/CONVENTIONS.md §15.4 step 1). The server treats
/// [`TASK_RUN_ACTIVE`](reject_code::TASK_RUN_ACTIVE) specially and shows every other code with
/// the rejection's `reason`.
pub mod reject_code {
    /// The host drains or shuts down.
    pub const DRAINING: &str = "DRAINING";
    /// The host runs this run with a newer epoch.
    pub const STALE_EPOCH: &str = "STALE_EPOCH";
    /// The host has no credential with this label, or cannot read it.
    pub const UNKNOWN_CREDENTIAL: &str = "UNKNOWN_CREDENTIAL";
    /// The credential is invalid, in cooldown, does not serve the runtime, or its Codex home
    /// is broken.
    pub const CREDENTIAL_UNAVAILABLE: &str = "CREDENTIAL_UNAVAILABLE";
    /// A workspace of the assignment is not valid (URL, branch, `dir`, count).
    pub const INVALID_ASSIGNMENT: &str = "INVALID_ASSIGNMENT";
    /// The runtime's image is missing.
    pub const IMAGE_MISSING: &str = "IMAGE_MISSING";
    /// Docker is not reachable.
    pub const DOCKER_UNAVAILABLE: &str = "DOCKER_UNAVAILABLE";
    /// The host still runs another run of the same task (it is ending, e.g. after a cancel).
    pub const TASK_RUN_ACTIVE: &str = "TASK_RUN_ACTIVE";
    /// Every slot of the host is in use (effective `maxConcurrentRuns`).
    pub const NO_FREE_SLOT: &str = "NO_FREE_SLOT";
    /// Every slot of the credential's pool is in use (effective `poolSize`).
    pub const POOL_FULL: &str = "POOL_FULL";
}

/// The agent's working directory in the sandbox. Each repository of the task is cloned into
/// `/workspace/<dir>` ([`WorkspaceSpec::dir`]).
pub const WORKSPACE_ROOT: &str = "/workspace";

/// Most repositories a task (and so a run) can have (`run.assign.workspaces` `maxItems`).
pub const MAX_WORKSPACES: usize = 20;

/// Longest `dir` the schema allows.
pub const MAX_DIR_LEN: usize = 64;

/// True when `dir` matches the schema's pattern for [`WorkspaceSpec::dir`]:
/// `^[a-z0-9]+(-[a-z0-9]+)*$`, at most [`MAX_DIR_LEN`] bytes. Such a name is one safe path
/// segment: no `/`, no leading dot, never `.` or `..`.
pub fn is_valid_workspace_dir(dir: &str) -> bool {
    !dir.is_empty()
        && dir.len() <= MAX_DIR_LEN
        && dir
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        && !dir.starts_with('-')
        && !dir.ends_with('-')
        && !dir.contains("--")
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// A string that must never be printed. `Debug` and `Display` show `[REDACTED]`; serde
/// sees the plain value, because the protocol carries it (the git token in `run.assign`).
#[derive(Clone, PartialEq, Eq, Default)]
pub struct Secret(String);

impl Secret {
    pub fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }

    /// The secret value. Callers must not log it.
    pub fn expose(&self) -> &str {
        &self.0
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("[REDACTED]")
    }
}

impl fmt::Display for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("[REDACTED]")
    }
}

impl Serialize for Secret {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for Secret {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        String::deserialize(deserializer).map(Secret)
    }
}

/// Instants are written as RFC 3339 UTC with millisecond precision.
mod instant {
    use super::*;

    pub fn serialize<S: Serializer>(value: &DateTime<Utc>, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&value.to_rfc3339_opts(SecondsFormat::Millis, true))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<DateTime<Utc>, D::Error> {
        let raw = String::deserialize(d)?;
        DateTime::parse_from_rfc3339(&raw)
            .map(|t| t.with_timezone(&Utc))
            .map_err(serde::de::Error::custom)
    }
}

mod opt_instant {
    use super::*;

    pub fn serialize<S: Serializer>(
        value: &Option<DateTime<Utc>>,
        s: S,
    ) -> Result<S::Ok, S::Error> {
        match value {
            Some(t) => instant::serialize(t, s),
            None => s.serialize_none(),
        }
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<DateTime<Utc>>, D::Error> {
        let raw: Option<String> = Option::deserialize(d)?;
        raw.map(|r| {
            DateTime::parse_from_rfc3339(&r)
                .map(|t| t.with_timezone(&Utc))
                .map_err(serde::de::Error::custom)
        })
        .transpose()
    }
}

// ---------------------------------------------------------------------------
// Enums shared by several messages
// ---------------------------------------------------------------------------

/// The agent CLI of a run (ADR-0041).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Runtime {
    ClaudeCode,
    Codex,
    Pi,
    Opencode,
}

impl Runtime {
    /// Every runtime, in the order of the schema.
    pub const ALL: [Runtime; 4] = [
        Runtime::ClaudeCode,
        Runtime::Codex,
        Runtime::Pi,
        Runtime::Opencode,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Runtime::ClaudeCode => "CLAUDE_CODE",
            Runtime::Codex => "CODEX",
            Runtime::Pi => "PI",
            Runtime::Opencode => "OPENCODE",
        }
    }

    /// Whether protocol v1 may name the runtime: `PI` and `OPENCODE` are an additive change of
    /// the v1 schema that a server from before them refuses, so a v1 host leaves them out of
    /// `hello`.
    pub fn in_v1(self) -> bool {
        matches!(self, Runtime::ClaudeCode | Runtime::Codex)
    }
}

impl fmt::Display for Runtime {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// How much the agent's runtime thinks (v2 `run.assign.agent.effort`), from the least to the
/// most. The host sets it the runtime's own way.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Effort {
    Low,
    Medium,
    High,
    Xhigh,
    Max,
}

impl Effort {
    /// Every level, from the least to the most.
    pub const ALL: [Effort; 5] = [
        Effort::Low,
        Effort::Medium,
        Effort::High,
        Effort::Xhigh,
        Effort::Max,
    ];

    /// The level as the runtimes name it: `low`, `medium`, `high`, `xhigh` or `max`.
    pub fn level(self) -> &'static str {
        match self {
            Effort::Low => "low",
            Effort::Medium => "medium",
            Effort::High => "high",
            Effort::Xhigh => "xhigh",
            Effort::Max => "max",
        }
    }

    /// The level a runtime calls `level`, if it is one of these.
    pub fn from_level(level: &str) -> Option<Effort> {
        Effort::ALL.into_iter().find(|e| e.level() == level)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CredentialKind {
    /// A Claude Pro/Max login: Claude Code's own `.credentials.json`, written by `claude auth
    /// login` into a directory of the host, which the host mounts into the sandbox and never
    /// reads (ADR-0085).
    ClaudeLogin,
    CodexChatgptAuth,
    AnthropicApiKey,
    OpenaiApiKey,
}

impl CredentialKind {
    /// The runtimes a credential of this kind can serve. A subscription serves its vendor's CLI
    /// only; an API key also serves pi and OpenCode, which take the provider's key as it is.
    pub fn runtimes(self) -> Vec<Runtime> {
        match self {
            CredentialKind::ClaudeLogin => vec![Runtime::ClaudeCode],
            CredentialKind::CodexChatgptAuth => vec![Runtime::Codex],
            CredentialKind::AnthropicApiKey => {
                vec![Runtime::ClaudeCode, Runtime::Pi, Runtime::Opencode]
            }
            CredentialKind::OpenaiApiKey => vec![Runtime::Codex, Runtime::Pi, Runtime::Opencode],
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            CredentialKind::ClaudeLogin => "CLAUDE_LOGIN",
            CredentialKind::CodexChatgptAuth => "CODEX_CHATGPT_AUTH",
            CredentialKind::AnthropicApiKey => "ANTHROPIC_API_KEY",
            CredentialKind::OpenaiApiKey => "OPENAI_API_KEY",
        }
    }
}

impl fmt::Display for CredentialKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum FailureClass {
    UsageLimit,
    ServerLimited,
    ContextOverflow,
    LoginExpired,
    VendorBlocked,
    CredentialLeak,
    ProtocolError,
    /// A guard of the host: too many tool calls, the same call repeated, no message from the
    /// agent; and, under protocol v1, the wall clock.
    GuardStopped,
    /// The wall-clock guard at `limits.maxWallClockSeconds` (protocol v2 only): the session and
    /// the work so far are kept, so a retry goes on from where the run stopped.
    TimeLimit,
    GitError,
    Infra,
    RunnerUnavailable,
    /// The model refused to continue (`stopReason: refusal`). Never pauses the credential.
    ModelRefused,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Executor {
    Docker,
}

/// `hello.isolationTiers[]` (ADR-0006). `microvm` came with protocol v2: a v1 server from
/// before it refuses a hello that carries it, so a v1 host reports `container`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum IsolationTier {
    Container,
    Gvisor,
    /// Kata Containers: every sandbox is a microVM with its own kernel (ADR-0025).
    Microvm,
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

/// One WebSocket frame.
// A frame is read, handled and dropped one at a time, never kept by the thousand: the size of
// its largest variant (`run.assign`) costs nothing worth a box on every match.
#[allow(clippy::large_enum_variant)]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum Message {
    #[serde(rename = "hello")]
    Hello(Hello),
    #[serde(rename = "welcome")]
    Welcome(Welcome),
    #[serde(rename = "run.assign")]
    RunAssign(RunAssign),
    #[serde(rename = "run.accepted")]
    RunAccepted(RunAccepted),
    #[serde(rename = "run.rejected")]
    RunRejected(RunRejected),
    #[serde(rename = "run.event")]
    RunEvent(RunEvent),
    #[serde(rename = "run.heartbeat")]
    RunHeartbeat(RunHeartbeat),
    #[serde(rename = "run.finished")]
    RunFinished(RunFinished),
    #[serde(rename = "run.cancel")]
    RunCancel(RunCancel),
    #[serde(rename = "run.repository_request")]
    RunRepositoryRequest(RunRepositoryRequest),
    #[serde(rename = "run.repository_request.ack")]
    RunRepositoryRequestAck(RunRepositoryRequestAck),
    #[serde(rename = "run.task_create")]
    RunTaskCreate(RunTaskCreate),
    #[serde(rename = "run.task_create.ack")]
    RunTaskCreateAck(RunTaskCreateAck),
    #[serde(rename = "run.ask")]
    RunAsk(RunAsk),
    #[serde(rename = "run.ask.ack")]
    RunAskAck(RunAskAck),
    /// v2 only: a platform request of the agent (ASK, ACCESS, ARTIFACT, NETWORK or TOOL).
    #[serde(rename = "run.request")]
    RunRequest(RunRequest),
    /// v2 only: the answer to a [`RunRequest`].
    #[serde(rename = "run.request.ack")]
    RunRequestAck(RunRequestAck),
    /// v2 feature [`feature::RUN_STEER`], server -> host: a message for the running agent.
    #[serde(rename = "run.steer")]
    RunSteer(RunSteer),
    /// v2 feature [`feature::RUN_STEER`]: the answer to a [`RunSteer`].
    #[serde(rename = "run.steer.ack")]
    RunSteerAck(RunSteerAck),
    #[serde(rename = "credential.status")]
    CredentialStatus(CredentialStatus),
    #[serde(rename = "host.config")]
    HostConfig(HostConfig),
    #[serde(rename = "host.config.applied")]
    HostConfigApplied(HostConfigApplied),
    /// v2 feature [`feature::SANDBOX_POOL`], host -> server: the host's sandboxes now.
    #[serde(rename = "host.pool.status")]
    HostPoolStatus(HostPoolStatus),
    /// v2 only, server -> host: the Public Suffix List.
    #[serde(rename = "psl.update")]
    PslUpdate(PslUpdate),
    #[serde(rename = "error")]
    Error(ErrorMessage),
}

/// Why a frame could not be turned into a [`Message`].
#[derive(Debug, thiserror::Error)]
pub enum ParseError {
    #[error("frame is not a JSON object with a string `type`")]
    NotAMessage,
    #[error("unknown message type `{0}`")]
    UnknownType(String),
    #[error("invalid `{message_type}` message: {source}")]
    Invalid {
        message_type: String,
        #[source]
        source: serde_json::Error,
    },
    #[error("frame is not valid JSON: {0}")]
    Json(#[source] serde_json::Error),
}

impl ParseError {
    /// The `error.code` to answer with.
    pub fn code(&self) -> &'static str {
        match self {
            ParseError::UnknownType(_) => "UNKNOWN_TYPE",
            _ => "BAD_MESSAGE",
        }
    }
}

impl Message {
    /// Parses one text frame of any version.
    pub fn parse(text: &str) -> Result<Message, ParseError> {
        let value: serde_json::Value = serde_json::from_str(text).map_err(ParseError::Json)?;
        Self::from_value(value)
    }

    /// Parses one text frame of `version`: a frame whose type the version does not have is
    /// [`ParseError::UnknownType`], as it is for the peer (an unknown version knows no type).
    pub fn parse_version(text: &str, version: u32) -> Result<Message, ParseError> {
        let value: serde_json::Value = serde_json::from_str(text).map_err(ParseError::Json)?;
        Self::from_value_version(value, version)
    }

    pub fn from_value(value: serde_json::Value) -> Result<Message, ParseError> {
        Self::from_value_in(value, &KNOWN_MESSAGE_TYPES)
    }

    /// [`Message::parse_version`] for a parsed value.
    pub fn from_value_version(
        value: serde_json::Value,
        version: u32,
    ) -> Result<Message, ParseError> {
        Self::from_value_in(value, message_types(version).unwrap_or(&[]))
    }

    fn from_value_in(value: serde_json::Value, types: &[&str]) -> Result<Message, ParseError> {
        let message_type = value
            .get("type")
            .and_then(|t| t.as_str())
            .ok_or(ParseError::NotAMessage)?
            .to_string();
        if !types.contains(&message_type.as_str()) {
            return Err(ParseError::UnknownType(message_type));
        }
        serde_json::from_value(value).map_err(|source| ParseError::Invalid {
            message_type,
            source,
        })
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).expect("protocol messages always serialize")
    }

    /// The `type` value of this message.
    pub fn message_type(&self) -> &'static str {
        match self {
            Message::Hello(_) => "hello",
            Message::Welcome(_) => "welcome",
            Message::RunAssign(_) => "run.assign",
            Message::RunAccepted(_) => "run.accepted",
            Message::RunRejected(_) => "run.rejected",
            Message::RunEvent(_) => "run.event",
            Message::RunHeartbeat(_) => "run.heartbeat",
            Message::RunFinished(_) => "run.finished",
            Message::RunCancel(_) => "run.cancel",
            Message::RunRepositoryRequest(_) => "run.repository_request",
            Message::RunRepositoryRequestAck(_) => "run.repository_request.ack",
            Message::RunTaskCreate(_) => "run.task_create",
            Message::RunTaskCreateAck(_) => "run.task_create.ack",
            Message::RunAsk(_) => "run.ask",
            Message::RunAskAck(_) => "run.ask.ack",
            Message::RunRequest(_) => "run.request",
            Message::RunRequestAck(_) => "run.request.ack",
            Message::RunSteer(_) => "run.steer",
            Message::RunSteerAck(_) => "run.steer.ack",
            Message::CredentialStatus(_) => "credential.status",
            Message::HostConfig(_) => "host.config",
            Message::HostConfigApplied(_) => "host.config.applied",
            Message::HostPoolStatus(_) => "host.pool.status",
            Message::PslUpdate(_) => "psl.update",
            Message::Error(_) => "error",
        }
    }
}

/// Runner host -> server, first frame after connect.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Hello {
    pub protocol_version: u32,
    pub runner_version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hostname: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub os: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arch: Option<String>,
    pub executors: Vec<Executor>,
    pub isolation_tiers: Vec<IsolationTier>,
    /// The host's ceiling (`run --max-concurrent-runs`: 1 to [`MAX_CAPACITY`] with Docker, to
    /// [`MAX_HOST_CAPACITY`] with a VM per run), not the effective value: that one is reported
    /// in [`HostConfigApplied`].
    pub max_concurrent_runs: u32,
    pub credentials: Vec<CredentialInfo>,
    pub runtimes: Vec<RuntimeInfo>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_runs: Option<Vec<ActiveRun>>,
    /// v2 only: the version of the Public Suffix List the host holds; omitted when it holds
    /// none ([`PslUpdate`]).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub public_suffix_list: Option<PublicSuffixListVersion>,
    /// v2 only: the optional features the host implements ([`feature`]); left out when empty.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub features: Vec<String>,
    /// v2, the feature [`feature::SANDBOX_POOL`]: the operator's ceiling of ready sandboxes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pool: Option<HelloPool>,
}

/// `hello.pool` (ADR-0099).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloPool {
    /// `run --pool-max-ready`: the most ready sandboxes Tinfirm may ask for; 0 is off.
    pub max_ready: u32,
}

/// Names of the optional features of protocol v2 (`hello.features`). The server sends a
/// feature's frames only to a host that lists it.
pub mod feature {
    /// The host takes [`super::RunSteer`] and answers each with [`super::RunSteerAck`].
    pub const RUN_STEER: &str = "run.steer";
    /// The host takes [`super::RunAssign::skills`] (ADR-0064): it downloads, verifies and
    /// unpacks each skill and mounts it read-only into the sandbox.
    pub const RUN_SKILLS: &str = "run.skills";
    /// The host takes [`super::RunAssign::session_bundle`] and reports
    /// [`super::RunFinished::session_bundle`] (ADR-0097): it restores the conversation's
    /// session folders from the server's bundle, and uploads them again when the run ends.
    pub const SESSION_BUNDLE: &str = "session.bundle";
    /// The host keeps ready sandboxes (ADR-0098) and takes Tinfirm's count of them (ADR-0099):
    /// [`super::Hello::pool`], [`super::HostConfig::pool`], [`super::HostConfigApplied::pool`]
    /// and [`super::HostPoolStatus`].
    pub const SANDBOX_POOL: &str = "sandbox.pool";
    /// The host runs its agents over the sandbox bridge (ADR-0095): with `run
    /// --sandbox-bridge`, and always with a VM per run (ADR-0096). It adds no frame: it tells a
    /// server whose licence does not hold elastic runners (ADR-0100) to refuse the hello with
    /// `EDITION_REQUIRED` ([`EDITION_REQUIRED`](super::EDITION_REQUIRED)).
    pub const SANDBOX_BRIDGE: &str = "sandbox.bridge";
}

/// `error.code` before `welcome`: the server's licence does not hold a feature the hello
/// lists (ADR-0100), so it closes the connection with 1008. Reconnecting cannot help.
pub const EDITION_REQUIRED: &str = "EDITION_REQUIRED";

/// `hello.publicSuffixList`: which Public Suffix List the host holds.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PublicSuffixListVersion {
    /// Lowercase hex SHA-256 of the list's uncompressed bytes.
    pub sha256: String,
}

/// Credential metadata. Never contains the secret.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CredentialInfo {
    pub label: String,
    pub kind: CredentialKind,
    pub runtimes: Vec<Runtime>,
    /// The credential's ceiling (`credential add --pool`, 1 to [`MAX_CAPACITY`]), not the
    /// effective value.
    pub pool_size: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeInfo {
    pub runtime: Runtime,
    pub image: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActiveRun {
    pub run_id: Uuid,
    pub epoch: i64,
    pub last_seq: u64,
}

/// Server -> runner host, answer to hello.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Welcome {
    /// v2: the protocol version the server speaks on this connection (the hello's). A v1
    /// server sends none; a host that asked for v2 and gets none, or another version, ends
    /// the connection with an error instead of falling back.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub protocol_version: Option<u32>,
    pub runner_host_id: Uuid,
    pub company_id: Uuid,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server_version: Option<String>,
    pub heartbeat_interval_seconds: u64,
    pub lease_ttl_seconds: u64,
}

/// Server -> runner host: start a run. Exactly one of `credential_label` and `model_access` is
/// set (see [`RunAssign::credential`]).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunAssign {
    pub run_id: Uuid,
    pub epoch: i64,
    /// The label of the host credential the run uses (`hello.credentials`); absent for a run
    /// with `model_access`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credential_label: Option<String>,
    /// A company model credential (ADR-0027): the gateway's base URL and key, for the sandbox
    /// environment. The run uses no credential of this host.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model_access: Option<ModelAccess>,
    pub agent: AgentSpec,
    /// The subject of the run: `task` (v1) or `workItem` (v2), see [`RunAssign::work_item`].
    #[serde(flatten)]
    pub subject: Subject,
    /// v2: the job (the agent's thread on the work item) this run is a turn of.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub job: Option<JobSpec>,
    pub prompt: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resume_session_id: Option<String>,
    /// The repositories of the workspace; empty for work without repositories. Each is cloned
    /// into `/workspace/<dir>`.
    pub workspaces: Vec<WorkspaceSpec>,
    /// Repositories the agent may ask for with the platform tool `request_repository` (v1) or
    /// `request_access` (v2).
    pub requestable_repositories: Vec<RequestableRepository>,
    pub limits: Limits,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resume_prompt: Option<String>,
    /// Files attached to the work item (ADR-0014); absent means none.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub attachments: Vec<AttachmentSpec>,
    /// v2: the server-executed tools of the run, listed in the MCP `tools/list` next to the
    /// platform tools and forwarded as [`RunRequest`] of kind TOOL. Empty in v1.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tools: Vec<ToolSpec>,
    /// v2: network hosts a member approved for this run's job or its agent
    /// (`run.request` kind NETWORK), as egress rules `HOST[:PORT]`; the host adds them to the
    /// run's egress rules when it runs with the egress proxy. Empty in v1.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub allowed_hosts: Vec<String>,
    /// v2: the platform tools the agent may use in this run, by name (`ask`, `finish`,
    /// `request_access`, `request_network_access`, `save_artifact`, `list_repositories`): an
    /// allowlist the host filters the run's MCP `tools/list` by, so the agent does not see a
    /// tool the server would refuse. `finish` and `list_repositories` are listed whatever it
    /// says; a name the host does not know is ignored. `None` (a v1 server, or a v2 server
    /// before the field) means every platform tool.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub platform_tools: Option<Vec<String>>,
    /// v2, the optional feature [`feature::RUN_SKILLS`] (ADR-0064): the agent's skills at the
    /// versions it pins, at most [`MAX_SKILLS`], names unique. Sent only to a host that lists
    /// the feature; empty (absent) for none.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub skills: Vec<AssignedSkill>,
    /// v2, the optional feature [`feature::SESSION_BUNDLE`] (ADR-0097): the server keeps the
    /// session folders between turns. Present: the host uploads them when the run ends; with a
    /// [`SessionBundleSpec::restore`], it restores that bundle first. `None`: the host keeps its
    /// homes as before.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_bundle: Option<SessionBundleSpec>,
}

/// `run.assign.sessionBundle` (v2, feature [`feature::SESSION_BUNDLE`], ADR-0097).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct SessionBundleSpec {
    /// Lower-case hex SHA-256 of the bundle the download answers, with `size_bytes`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha256: Option<String>,
    /// The bundle's size, 1 to [`SESSION_BUNDLE_MAX_BYTES`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub size_bytes: Option<u64>,
}

impl SessionBundleSpec {
    /// The bundle to restore before the sandbox starts: its SHA-256 and size, when the server
    /// holds one of the run's `resumeSessionId`.
    pub fn restore(&self) -> Option<(&str, u64)> {
        match (&self.sha256, self.size_bytes) {
            (Some(sha256), Some(size)) => Some((sha256.as_str(), size)),
            _ => None,
        }
    }
}

/// Largest session bundle (`run.assign.sessionBundle.sizeBytes` `maximum`): 32 MiB, compressed.
pub const SESSION_BUNDLE_MAX_BYTES: u64 = 32 * 1024 * 1024;

/// Largest session bundle before compression: 64 MiB, what the server's credential scan
/// unpacks at most.
pub const SESSION_BUNDLE_MAX_UNPACKED_BYTES: u64 = 64 * 1024 * 1024;

/// The subject of a `run.assign`: the same `{id, key, title}` object, named `task` by a v1
/// server and `workItem` by a v2 server. Exactly one of the two is in the frame.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Subject {
    #[serde(rename = "task")]
    Task(WorkItemSpec),
    #[serde(rename = "workItem")]
    WorkItem(WorkItemSpec),
}

impl Subject {
    /// The `{id, key, title}` object, whatever its name.
    pub fn spec(&self) -> &WorkItemSpec {
        match self {
            Subject::Task(spec) | Subject::WorkItem(spec) => spec,
        }
    }
}

impl RunAssign {
    /// The work item (v2) or task (v1) of the run: what the agent works on.
    pub fn work_item(&self) -> &WorkItemSpec {
        self.subject.spec()
    }
}

/// `run.assign.job` (v2): the job this run is a turn of.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JobSpec {
    pub id: Uuid,
}

/// `run.assign.tools[]` (v2): a server-executed tool, as MCP describes a tool.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolSpec {
    /// The name the agent calls, e.g. `jira_get`; see [`is_valid_tool_name`].
    pub name: String,
    pub description: String,
    /// The JSON Schema of the call's `arguments`.
    pub input_schema: serde_json::Value,
    /// Whether the server may run the call again after it lost track of it (package R):
    /// for the runtime's information only, the server decides. `None` from a server before
    /// the field; the class then follows the tool's effect.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub replay: Option<ToolReplay>,
}

/// `run.assign.tools[].replay` (v2, package R): whether running a tool's call again does no
/// harm (`SAFE`: a read, an idempotent write) or may write twice (`UNSAFE`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ToolReplay {
    Safe,
    Unsafe,
}

/// Longest tool name (`$defs/ToolSpec.name`, `$defs/ToolRequest.tool`).
pub const TOOL_NAME_MAX_LEN: usize = 100;

/// Most server-executed tools of a run (`run.assign.tools` `maxItems`).
pub const MAX_TOOLS: usize = 200;

/// True when `name` matches the schema's pattern for a tool name:
/// `^[a-z0-9][a-z0-9_-]{0,99}$`.
pub fn is_valid_tool_name(name: &str) -> bool {
    let mut bytes = name.bytes();
    match bytes.next() {
        Some(first) if first.is_ascii_lowercase() || first.is_ascii_digit() => {}
        _ => return false,
    }
    name.len() <= TOOL_NAME_MAX_LEN
        && bytes.all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

/// What a [`RunAssign`] runs with.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum AssignedCredential<'a> {
    /// A credential of this host, by label.
    Host(&'a str),
    /// A company model credential; the host uses none of its own.
    Model(&'a ModelAccess),
}

impl RunAssign {
    /// The credential of the run: `None` when the frame carries both or neither of
    /// `credentialLabel` and `modelAccess`, which the schema forbids.
    pub fn credential(&self) -> Option<AssignedCredential<'_>> {
        match (&self.credential_label, &self.model_access) {
            (Some(label), None) => Some(AssignedCredential::Host(label)),
            (None, Some(access)) => Some(AssignedCredential::Model(access)),
            _ => None,
        }
    }
}

/// `run.assign.modelAccess`: the endpoint of the company's model gateway and its key
/// (`$defs/ModelAccess`). `api_key` never shows in `Debug`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelAccess {
    pub format: ModelFormat,
    /// The company's gateway (`anthropic`, `openai`); `None` for the provider's own API.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub base_url: Option<String>,
    /// The AWS region of `bedrock`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub region: Option<String>,
    /// `bedrock`: the AWS access key ID whose secret access key is `api_key`; `None` when
    /// `api_key` is an Amazon Bedrock API key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub access_key_id: Option<String>,
    pub api_key: Secret,
}

/// The provider whose API a model credential's key opens (ADR-0042).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ModelFormat {
    /// The Anthropic Messages API, or a gateway that speaks it.
    Anthropic,
    /// The OpenAI API, or a gateway that speaks it.
    Openai,
    /// Amazon Bedrock.
    Bedrock,
    /// The Gemini API.
    Google,
    Openrouter,
    Mistral,
    Groq,
    Xai,
    Deepseek,
}

impl ModelFormat {
    /// The runtimes a key of this provider serves, in schema order.
    pub fn runtimes(self) -> &'static [Runtime] {
        match self {
            ModelFormat::Anthropic | ModelFormat::Bedrock => {
                &[Runtime::ClaudeCode, Runtime::Pi, Runtime::Opencode]
            }
            ModelFormat::Openai => &[Runtime::Codex, Runtime::Pi, Runtime::Opencode],
            ModelFormat::Google
            | ModelFormat::Openrouter
            | ModelFormat::Mistral
            | ModelFormat::Groq
            | ModelFormat::Xai
            | ModelFormat::Deepseek => &[Runtime::Pi, Runtime::Opencode],
        }
    }

    pub fn serves(self, runtime: Runtime) -> bool {
        self.runtimes().contains(&runtime)
    }

    /// The wire name: `anthropic`, `openai`, `bedrock`, …
    pub fn as_str(self) -> &'static str {
        match self {
            ModelFormat::Anthropic => "anthropic",
            ModelFormat::Openai => "openai",
            ModelFormat::Bedrock => "bedrock",
            ModelFormat::Google => "google",
            ModelFormat::Openrouter => "openrouter",
            ModelFormat::Mistral => "mistral",
            ModelFormat::Groq => "groq",
            ModelFormat::Xai => "xai",
            ModelFormat::Deepseek => "deepseek",
        }
    }
}

impl fmt::Display for ModelFormat {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// `run.assign.skills[]` (v2, feature [`feature::RUN_SKILLS`], ADR-0064): one skill of the
/// agent at the version it pins. The host downloads its canonical zip from
/// `GET /api/runner/v1/runs/{runId}/skills/{skillId}/versions/{version}?epoch={epoch}`,
/// checks `size_bytes` and `sha256`, and mounts it unpacked as the folder `name`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssignedSkill {
    pub skill_id: Uuid,
    /// The pinned version, 1 or more.
    pub version: u32,
    /// The skill's folder name in the sandbox; see [`is_valid_skill_name`].
    pub name: String,
    /// Lower-case hex SHA-256 of the zip the download answers.
    pub sha256: String,
    /// The zip's size, 1 to [`SKILL_MAX_BYTES`].
    pub size_bytes: u64,
}

/// Most skills of a run (`run.assign.skills` `maxItems`).
pub const MAX_SKILLS: usize = 20;

/// Longest skill name (`$defs/RunAssign/properties/skills/items/properties/name`).
pub const SKILL_NAME_MAX_LEN: usize = 64;

/// Largest zip of a skill (`sizeBytes` `maximum`): 5 MiB.
pub const SKILL_MAX_BYTES: u64 = 5 * 1024 * 1024;

/// True when `name` matches the schema's pattern for a skill name,
/// `^[a-z0-9]+(-[a-z0-9]+)*$`, at most [`SKILL_NAME_MAX_LEN`] bytes: one safe path segment, as
/// a workspace's `dir` is ([`is_valid_workspace_dir`]).
pub fn is_valid_skill_name(name: &str) -> bool {
    name.len() <= SKILL_NAME_MAX_LEN && is_valid_workspace_dir(name)
}

/// A task attachment of `run.assign`. The host downloads it from
/// `GET /api/runner/v1/runs/{runId}/attachments/{id}?epoch={epoch}` and checks size and hash.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentSpec {
    pub id: Uuid,
    pub file_name: String,
    pub size_bytes: u64,
    pub sha256: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentSpec {
    pub id: Uuid,
    pub name: String,
    pub runtime: Runtime,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    /// v2: how much the runtime thinks; absent leaves it to the runtime and its model.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effort: Option<Effort>,
}

/// The `{id, key, title}` of the work item (v2 `run.assign.workItem`) or task (v1
/// `run.assign.task`) of a run.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkItemSpec {
    pub id: Uuid,
    pub key: String,
    pub title: String,
}

/// The v1 name of [`WorkItemSpec`].
pub type TaskSpec = WorkItemSpec;

/// One repository of the task (`run.assign.workspaces[]`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceSpec {
    pub component_id: Uuid,
    /// Directory under [`WORKSPACE_ROOT`]; see [`is_valid_workspace_dir`].
    pub dir: String,
    /// The component name, as shown to the agent.
    pub name: String,
    pub repo_url: String,
    pub default_branch: String,
    /// The task branch `agent/<KEY>`, the same in every repository of the task.
    pub branch: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub git: Option<GitCredentials>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub author_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub author_email: Option<String>,
    /// The agent may only read this repository (ADR-0017): it is cloned and fetched as usual,
    /// but never pushed.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub read_only: bool,
}

/// A repository the agent may request (`run.assign.requestableRepositories[]`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RequestableRepository {
    pub component_id: Uuid,
    pub name: String,
    pub repo_url: String,
    /// The task already has a PENDING request for it.
    pub pending_request: bool,
}

/// HTTPS credentials for clone and push. Used only on the runner host.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GitCredentials {
    pub username: String,
    pub token: Secret,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Limits {
    pub max_wall_clock_seconds: u64,
    pub max_turns: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunAccepted {
    pub run_id: Uuid,
    pub epoch: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunRejected {
    pub run_id: Uuid,
    pub epoch: i64,
    /// Text for humans. Never contains secrets.
    pub reason: String,
    /// Machine-readable reason, one of [`reject_code`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
}

/// Runner host -> server. `seq` starts at 1 per run and increases by 1.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunEvent {
    pub run_id: Uuid,
    pub epoch: i64,
    pub seq: u64,
    #[serde(with = "instant")]
    pub at: DateTime<Utc>,
    #[serde(flatten)]
    pub event: EventPayload,
}

/// `eventType` plus its `payload`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(
    tag = "eventType",
    content = "payload",
    rename_all = "SCREAMING_SNAKE_CASE"
)]
pub enum EventPayload {
    AgentMessage(TextPayload),
    AgentThought(TextPayload),
    ToolCall(ToolCallPayload),
    ToolCallUpdate(ToolCallUpdatePayload),
    Plan(PlanPayload),
    Usage(UsagePayload),
    RateLimit(RateLimitPayload),
    Status(StatusPayload),
    Git(GitPayload),
    Log(LogPayload),
    Error(ErrorPayload),
}

impl EventPayload {
    /// The `eventType` value.
    pub fn event_type(&self) -> &'static str {
        match self {
            EventPayload::AgentMessage(_) => "AGENT_MESSAGE",
            EventPayload::AgentThought(_) => "AGENT_THOUGHT",
            EventPayload::ToolCall(_) => "TOOL_CALL",
            EventPayload::ToolCallUpdate(_) => "TOOL_CALL_UPDATE",
            EventPayload::Plan(_) => "PLAN",
            EventPayload::Usage(_) => "USAGE",
            EventPayload::RateLimit(_) => "RATE_LIMIT",
            EventPayload::Status(_) => "STATUS",
            EventPayload::Git(_) => "GIT",
            EventPayload::Log(_) => "LOG",
            EventPayload::Error(_) => "ERROR",
        }
    }

    pub fn is_log(&self) -> bool {
        matches!(self, EventPayload::Log(_))
    }
}

/// AGENT_MESSAGE / AGENT_THOUGHT.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TextPayload {
    pub text: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ToolKind {
    Read,
    Edit,
    Delete,
    Move,
    Search,
    Execute,
    Think,
    Fetch,
    SwitchMode,
    Other,
}

impl ToolKind {
    /// Maps an ACP `ToolKind` string; unknown values become `other`.
    pub fn from_acp(value: &str) -> ToolKind {
        match value {
            "read" => ToolKind::Read,
            "edit" => ToolKind::Edit,
            "delete" => ToolKind::Delete,
            "move" => ToolKind::Move,
            "search" => ToolKind::Search,
            "execute" => ToolKind::Execute,
            "think" => ToolKind::Think,
            "fetch" => ToolKind::Fetch,
            "switch_mode" => ToolKind::SwitchMode,
            _ => ToolKind::Other,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ToolCallStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
}

impl ToolCallStatus {
    /// Maps an ACP `ToolCallStatus` string.
    pub fn from_acp(value: &str) -> Option<ToolCallStatus> {
        match value {
            "pending" => Some(ToolCallStatus::Pending),
            "in_progress" => Some(ToolCallStatus::InProgress),
            "completed" => Some(ToolCallStatus::Completed),
            "failed" => Some(ToolCallStatus::Failed),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolCallPayload {
    pub tool_call_id: String,
    pub title: String,
    pub kind: ToolKind,
    pub status: ToolCallStatus,
    /// Raw tool input (an object); strings longer than 4 KiB are truncated.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub locations: Option<Vec<Location>>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Location {
    pub path: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub line: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolCallUpdatePayload {
    pub tool_call_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<ToolCallStatus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    /// Text output; truncated to 16 KiB with a trailing marker.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub diffs: Option<Vec<DiffPayload>>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiffPayload {
    pub path: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub old_text: Option<String>,
    pub new_text: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PlanPayload {
    pub entries: Vec<PlanEntry>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PlanEntry {
    pub content: String,
    pub status: PlanEntryStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub priority: Option<PlanEntryPriority>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlanEntryStatus {
    Pending,
    InProgress,
    Completed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlanEntryPriority {
    High,
    Medium,
    Low,
}

/// USAGE payload, also used as `run.finished.usage`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UsagePayload {
    /// Input tokens neither read from nor written to the prompt cache; in v1 including the
    /// cache writes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input_tokens: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output_tokens: Option<u64>,
    /// Input tokens read from the prompt cache.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cached_input_tokens: Option<u64>,
    /// v2: input tokens written to the prompt cache, not part of `input_tokens`. `None` (left
    /// out) in v1, and from a v2 host older than the field.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cache_write_tokens: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub context_used: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub context_size: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cost_usd: Option<f64>,
    /// Exact total tokens of the run so far.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub total_tokens: Option<u64>,
    /// `true` when the input/output/cache split is estimated (Codex); the total is exact.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub estimated: Option<bool>,
}

impl UsagePayload {
    pub fn is_empty(&self) -> bool {
        *self == UsagePayload::default()
    }

    /// The payload as v1 carries it: v1 has no `cacheWriteTokens`, so the cache writes count
    /// as input.
    pub fn into_v1(mut self) -> UsagePayload {
        if let Some(writes) = self.cache_write_tokens.take() {
            self.input_tokens = Some(self.input_tokens.unwrap_or(0) + writes);
        }
        self
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RateLimitStatus {
    Allowed,
    AllowedWarning,
    Rejected,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RateLimitPayload {
    pub status: RateLimitStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub utilization: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none", with = "opt_instant")]
    pub resets_at: Option<DateTime<Utc>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub window: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Preparing,
    Cloning,
    Starting,
    Running,
    Finalizing,
    Pushing,
    Cancelling,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StatusPayload {
    pub phase: Phase,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GitAction {
    Cloned,
    Committed,
    Pushed,
    NothingToPush,
}

/// GIT event. Hosts always set `component_id` and `dir`; events stored before
/// multi-repository runs have neither.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GitPayload {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub component_id: Option<Uuid>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dir: Option<String>,
    pub action: GitAction,
    pub branch: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub head_sha: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub commits: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LogPayload {
    pub level: LogLevel,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorPayload {
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub failure_class: Option<FailureClass>,
}

/// Runner host -> server while at least one run is active.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RunHeartbeat {
    pub runs: Vec<RunRef>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunRef {
    pub run_id: Uuid,
    pub epoch: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Outcome {
    Succeeded,
    Failed,
    Cancelled,
}

/// Runner host -> server, exactly once per run, after the last run.event.
///
/// Optional fields are omitted when unset (never written as `null`); both forms parse.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunFinished {
    pub run_id: Uuid,
    pub epoch: i64,
    pub outcome: Outcome,
    pub last_seq: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub failure_class: Option<FailureClass>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub failure_message: Option<String>,
    /// The agent's last message.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
    /// The agent's last `finish_run` call, if it made one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<RunResult>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    /// One result per cloned repository, in the order of `run.assign.workspaces`; empty for a
    /// run without repositories; `None` when there is no git result at all.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub git: Option<Vec<GitResult>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub usage: Option<UsagePayload>,
    /// v2: the files of `/output` the host saved as artifacts of the job. Empty (and left
    /// out) in v1 and when the agent left nothing there.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub artifacts: Vec<ArtifactResult>,
    /// v2: whether `session/load` of `resumeSessionId` succeeded. `None` (left out) in v1,
    /// and from a v2 host older than the field.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resumed: Option<bool>,
    /// v2: why the run did not resume a session; `None` when it did, and when it ended
    /// before the host tried `session/load`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resume_outcome: Option<ResumeOutcome>,
    /// v2, the feature [`feature::SESSION_BUNDLE`]: what became of the run's session bundle,
    /// for a run whose `run.assign` had `sessionBundle`; `None` (left out) otherwise.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_bundle: Option<SessionBundleOutcome>,
}

/// `run.finished.sessionBundle` (v2, feature [`feature::SESSION_BUNDLE`], ADR-0097).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SessionBundleOutcome {
    /// The server stored it.
    Stored,
    /// Over the host's caps: not sent.
    TooLarge,
    /// The server refused it: a credential in it, or over its cap.
    Refused,
    /// The upload failed.
    Failed,
    /// No session folders to pack.
    None,
}

impl SessionBundleOutcome {
    /// The wire name, e.g. `TOO_LARGE`.
    pub fn as_str(self) -> &'static str {
        match self {
            SessionBundleOutcome::Stored => "STORED",
            SessionBundleOutcome::TooLarge => "TOO_LARGE",
            SessionBundleOutcome::Refused => "REFUSED",
            SessionBundleOutcome::Failed => "FAILED",
            SessionBundleOutcome::None => "NONE",
        }
    }
}

/// Why a run did not resume a session (`run.finished.resumeOutcome`, v2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ResumeOutcome {
    /// `run.assign` had no `resumeSessionId`.
    FirstRun,
    /// The session files were not in the session home: the home was new for the run, or
    /// the agent did not know the id.
    NoSession,
    /// `session/load` failed for another reason; the message is in a LOG event.
    LoadFailed,
    /// The agent does not offer `session/load`.
    NotSupported,
}

impl ResumeOutcome {
    /// The wire name, e.g. `NO_SESSION`.
    pub fn as_str(self) -> &'static str {
        match self {
            ResumeOutcome::FirstRun => "FIRST_RUN",
            ResumeOutcome::NoSession => "NO_SESSION",
            ResumeOutcome::LoadFailed => "LOAD_FAILED",
            ResumeOutcome::NotSupported => "NOT_SUPPORTED",
        }
    }
}

/// `run.finished.artifacts[]` (v2): one file of `/output`, uploaded as an artifact.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtifactResult {
    /// The path relative to `/output`, with `/` between the segments.
    pub name: String,
    /// The attachment the upload created.
    pub attachment_id: Uuid,
    pub size_bytes: u64,
}

/// Most files of `/output` a run saves (`run.finished.artifacts` `maxItems`).
pub const MAX_ARTIFACTS: usize = 200;

/// The end state of one repository of a run.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GitResult {
    pub component_id: Uuid,
    pub branch: String,
    pub pushed: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub head_sha: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub commits: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ResultOutcome {
    /// The work is done and ready for review.
    Completed,
    /// The agent cannot continue without a person.
    Blocked,
}

/// The structured result of a run (`run.finished.result`): the agent's last `finish_run` call.
/// Every text is trimmed, redacted and cut to its length.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunResult {
    pub outcome: ResultOutcome,
    /// Markdown for the reviewer, 1 to [`RESULT_SUMMARY_MAX_CHARS`] characters.
    pub summary: String,
    /// At most [`MAX_NEXT_STEPS`] entries of 1 to [`RESULT_ITEM_MAX_CHARS`] characters.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub next_steps: Vec<String>,
    /// At most [`MAX_OPEN_QUESTIONS`] entries of 1 to [`RESULT_ITEM_MAX_CHARS`] characters.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub open_questions: Vec<String>,
}

/// Longest `run.finished.result.summary`, in characters.
pub const RESULT_SUMMARY_MAX_CHARS: usize = 20_000;
/// Longest next step or open question of a result, in characters.
pub const RESULT_ITEM_MAX_CHARS: usize = 2000;
/// Most next steps of a result.
pub const MAX_NEXT_STEPS: usize = 20;
/// Most open questions of a result.
pub const MAX_OPEN_QUESTIONS: usize = 10;

/// Server -> runner host.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunCancel {
    pub run_id: Uuid,
    pub epoch: i64,
}

/// Longest `run.steer.text`, in characters.
pub const STEER_TEXT_MAX_CHARS: usize = 110_000;
/// Longest `run.steer.ack.message`, in characters.
pub const STEER_ACK_MESSAGE_MAX_CHARS: usize = 2000;

/// Server -> runner host (v2 feature [`feature::RUN_STEER`]): a person wrote while the run
/// works. The host gives `text` to the agent's running turn and answers exactly one
/// [`RunSteerAck`] per `steer_id` (the same one again for a repeated `steer_id`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunSteer {
    pub run_id: Uuid,
    pub epoch: i64,
    /// Chosen by the server, one per message.
    pub steer_id: Uuid,
    /// What the agent reads (1 to [`STEER_TEXT_MAX_CHARS`] characters).
    pub text: String,
}

/// `run.steer.ack.status`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SteerStatus {
    /// The agent has the text in the turn it runs, and reads it before the turn ends.
    Delivered,
    /// It does not; `code` says why, and the server lets the message wait for the next turn.
    Refused,
}

/// Values of `run.steer.ack.code` (`$defs/RunSteerAck`).
pub mod steer_code {
    /// The agent has no steering (ACP `_session/steering`).
    pub const NOT_SUPPORTED: &str = "NOT_SUPPORTED";
    /// The host does not run this execution, or its agent's turn is over or ending.
    pub const NOT_RUNNING: &str = "NOT_RUNNING";
    /// The agent's steering answered with an error.
    pub const FAILED: &str = "FAILED";
}

/// Runner host -> server (v2 feature [`feature::RUN_STEER`]): the answer to one [`RunSteer`],
/// sent before the run's `run.finished`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunSteerAck {
    pub run_id: Uuid,
    pub epoch: i64,
    pub steer_id: Uuid,
    pub status: SteerStatus,
    /// Why the steer was refused (one of [`steer_code`]); set when `status` is `Refused`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    /// Details for the server's log (at most [`STEER_ACK_MESSAGE_MAX_CHARS`] characters).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

impl RunSteerAck {
    pub fn delivered(steer: &RunSteer) -> RunSteerAck {
        RunSteerAck {
            run_id: steer.run_id,
            epoch: steer.epoch,
            steer_id: steer.steer_id,
            status: SteerStatus::Delivered,
            code: None,
            message: None,
        }
    }

    pub fn refused(steer: &RunSteer, code: &str, message: impl Into<String>) -> RunSteerAck {
        let message: String = message.into();
        RunSteerAck {
            run_id: steer.run_id,
            epoch: steer.epoch,
            steer_id: steer.steer_id,
            status: SteerStatus::Refused,
            code: Some(code.to_string()),
            message: Some(message.chars().take(STEER_ACK_MESSAGE_MAX_CHARS).collect()),
        }
    }
}

/// Runner host -> server: the agent called the platform tool `request_repository`. The server
/// answers with exactly one [`RunRepositoryRequestAck`] with the same `request_id`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunRepositoryRequest {
    pub run_id: Uuid,
    pub epoch: i64,
    /// Generated by the host, one per tool call (idempotency key).
    pub request_id: Uuid,
    /// The requestable repository that `name` matched, if any.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub component_id: Option<Uuid>,
    /// The name the agent asked for, trimmed (1 to 100 characters).
    pub name: String,
    /// Why the agent needs it: trimmed, redacted, at most 2000 characters.
    pub reason: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RepositoryRequestStatus {
    /// Stored; waits for a human to approve or deny it.
    Pending,
    /// Not stored; `code` and `message` say why.
    Rejected,
}

/// Server -> runner host: the answer to one [`RunRepositoryRequest`]. The host returns
/// `message` to the agent as the tool result (an error result for `Rejected`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunRepositoryRequestAck {
    pub run_id: Uuid,
    pub request_id: Uuid,
    pub status: RepositoryRequestStatus,
    /// The stored request; set when `status` is `Pending`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub repository_request_id: Option<Uuid>,
    /// Why it was rejected (e.g. `ALREADY_PENDING`); set when `status` is `Rejected`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    pub message: String,
}

/// Runner host -> server: the agent called the platform tool `create_task`. The server answers
/// with exactly one [`RunTaskCreateAck`] with the same `request_id`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunTaskCreate {
    pub run_id: Uuid,
    pub epoch: i64,
    /// Generated by the host, one per tool call (idempotency key).
    pub request_id: Uuid,
    /// Trimmed, redacted, 1 to 200 characters.
    pub title: String,
    /// Markdown: trimmed, redacted, at most 50000 characters.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// Component names of the project, trimmed, without duplicates (at most 20). `None`: the
    /// repositories of the run's task that the assignee may access.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub repositories: Option<Vec<String>>,
    /// The name of the agent to assign the task to, trimmed (1 to 100 characters). `None`: the
    /// run's agent.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub assignee: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TaskCreateStatus {
    /// The task was stored.
    Created,
    /// Nothing was stored; `code` and `message` say why.
    Rejected,
}

/// Server -> runner host: the answer to one [`RunTaskCreate`]. The host returns `message` to
/// the agent as the tool result (an error result for `Rejected`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunTaskCreateAck {
    pub run_id: Uuid,
    pub request_id: Uuid,
    pub status: TaskCreateStatus,
    /// The new task; set when `status` is `Created`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task_id: Option<Uuid>,
    /// The new task's key, e.g. `WEB-13`; set when `status` is `Created`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task_key: Option<String>,
    /// Why nothing was created (e.g. `NO_ACCESS`); set when `status` is `Rejected`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    pub message: String,
}

/// Longest `run.ask.question`, in characters.
pub const ASK_QUESTION_MAX_CHARS: usize = 2000;
/// Longest option of `run.ask`, in characters.
pub const ASK_OPTION_MAX_CHARS: usize = 100;
/// Most options of `run.ask`.
pub const MAX_ASK_OPTIONS: usize = 10;

/// Runner host -> server: the agent called the platform tool `ask_human`. The server answers
/// with exactly one [`RunAskAck`] with the same `request_id`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunAsk {
    pub run_id: Uuid,
    pub epoch: i64,
    /// Generated by the host, one per tool call (idempotency key).
    pub request_id: Uuid,
    /// Markdown: trimmed, redacted, 1 to [`ASK_QUESTION_MAX_CHARS`] characters.
    pub question: String,
    /// Answers a person can pick: trimmed, redacted, without duplicates (ignoring case), at
    /// most [`MAX_ASK_OPTIONS`] of 1 to [`ASK_OPTION_MAX_CHARS`] characters. Empty: a free
    /// answer.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub options: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AskStatus {
    /// Stored and posted on the task; waits for a person's answer.
    Pending,
    /// Not stored; `code` and `message` say why.
    Rejected,
}

/// Server -> runner host: the answer to one [`RunAsk`]. The host returns `message` to the agent
/// as the tool result (an error result for `Rejected`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunAskAck {
    pub run_id: Uuid,
    pub request_id: Uuid,
    pub status: AskStatus,
    /// The stored ask; set when `status` is `Pending`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ask_id: Option<Uuid>,
    /// Why nothing was stored (e.g. `TOO_MANY_ASKS`); set when `status` is `Rejected`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    pub message: String,
}

// ---------------------------------------------------------------------------
// Protocol v2: the generic request pair
// ---------------------------------------------------------------------------

/// Longest `repository` or `tool` name of an ACCESS request, in characters.
pub const ACCESS_NAME_MAX_CHARS: usize = 100;
/// Longest `reason` of an ACCESS request, in characters.
pub const ACCESS_REASON_MAX_CHARS: usize = 2000;
/// Longest `host` of a NETWORK request, in characters: a 253-character name and `:65535`.
pub const NETWORK_HOST_MAX_CHARS: usize = 253 + 6;
/// Longest `reason` of a NETWORK request, in characters.
pub const NETWORK_REASON_MAX_CHARS: usize = ACCESS_REASON_MAX_CHARS;
/// Longest `url` of an ARTIFACT request, in characters.
pub const ARTIFACT_URL_MAX_CHARS: usize = 2000;
/// Longest `title` of an ARTIFACT request, in characters.
pub const ARTIFACT_TITLE_MAX_CHARS: usize = 200;
/// Largest `arguments` of a TOOL request, as JSON.
pub const TOOL_ARGUMENTS_MAX_BYTES: usize = 64 * 1024;
/// A TOOL result above this is REJECTED by the server with [`request_code::RESULT_TOO_LARGE`].
pub const TOOL_RESULT_MAX_BYTES: usize = 800 * 1024;

/// Values of `run.request.ack.code` (`$defs/RunRequestAck`).
pub mod request_code {
    pub const INVALID_REQUEST: &str = "INVALID_REQUEST";
    pub const RUN_NOT_ACTIVE: &str = "RUN_NOT_ACTIVE";
    pub const WORK_ITEM_CLOSED: &str = "WORK_ITEM_CLOSED";
    pub const UNKNOWN_REPOSITORY: &str = "UNKNOWN_REPOSITORY";
    pub const UNKNOWN_CONNECTION: &str = "UNKNOWN_CONNECTION";
    pub const UNKNOWN_TOOL: &str = "UNKNOWN_TOOL";
    pub const NO_ACCESS: &str = "NO_ACCESS";
    pub const ALREADY_IN_WORKSPACE: &str = "ALREADY_IN_WORKSPACE";
    pub const ALREADY_PENDING: &str = "ALREADY_PENDING";
    pub const TOO_MANY_REQUESTS: &str = "TOO_MANY_REQUESTS";
    /// A tool result above [`super::TOOL_RESULT_MAX_BYTES`]: the agent should narrow the query.
    pub const RESULT_TOO_LARGE: &str = "RESULT_TOO_LARGE";
    pub const TOOL_FAILED: &str = "TOOL_FAILED";
}

/// The kinds of a [`RunRequest`] (`run.request.kind`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RequestKind {
    /// A question for a person (`ask`); answered PENDING with a decision.
    Ask,
    /// Access to a repository or a tool connection (`request_access`); answered PENDING.
    Access,
    /// A link the agent saves (`save_artifact` with a URL); answered DONE with an artifact.
    Artifact,
    /// A network host for the sandbox (`request_network_access`); answered PENDING.
    Network,
    /// A server-executed tool call; answered DONE with a [`ToolResultPayload`], or PENDING with a
    /// decision when the call waits for a member's approval (ADR-0046).
    Tool,
}

impl RequestKind {
    pub fn as_str(self) -> &'static str {
        match self {
            RequestKind::Ask => "ASK",
            RequestKind::Access => "ACCESS",
            RequestKind::Artifact => "ARTIFACT",
            RequestKind::Network => "NETWORK",
            RequestKind::Tool => "TOOL",
        }
    }
}

impl fmt::Display for RequestKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// `run.request.kind` plus its `payload`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "payload", rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RequestPayload {
    Ask(AskRequest),
    Access(AccessRequest),
    Artifact(ArtifactRequest),
    Network(NetworkRequest),
    Tool(ToolRequest),
}

impl RequestPayload {
    pub fn kind(&self) -> RequestKind {
        match self {
            RequestPayload::Ask(_) => RequestKind::Ask,
            RequestPayload::Access(_) => RequestKind::Access,
            RequestPayload::Artifact(_) => RequestKind::Artifact,
            RequestPayload::Network(_) => RequestKind::Network,
            RequestPayload::Tool(_) => RequestKind::Tool,
        }
    }
}

/// Runner host -> server (v2): a platform request of the agent, one per tool call. The server
/// answers with exactly one [`RunRequestAck`] with the same `request_id`; a repeated
/// `request_id` (a resend after a reconnect) never creates a second decision, artifact or
/// tool call.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunRequest {
    pub run_id: Uuid,
    pub epoch: i64,
    /// Generated by the host, one per tool call (idempotency key).
    pub request_id: Uuid,
    #[serde(flatten)]
    pub request: RequestPayload,
}

/// ASK: the agent called `ask` (the shape of v1's `run.ask`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AskRequest {
    /// Markdown: trimmed, redacted, 1 to [`ASK_QUESTION_MAX_CHARS`] characters.
    pub question: String,
    /// At most [`MAX_ASK_OPTIONS`] of 1 to [`ASK_OPTION_MAX_CHARS`] characters, without
    /// duplicates (ignoring case). Empty: a free answer.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub options: Vec<String>,
}

/// ACCESS: the agent called `request_access` for a repository or a tool connection; exactly
/// one of `repository` and `tool` is set.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccessRequest {
    /// The repository name, trimmed (1 to [`ACCESS_NAME_MAX_CHARS`] characters).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub repository: Option<String>,
    /// The connection name, trimmed (1 to [`ACCESS_NAME_MAX_CHARS`] characters).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool: Option<String>,
    /// Trimmed, redacted, at most [`ACCESS_REASON_MAX_CHARS`] characters.
    pub reason: String,
}

/// ARTIFACT: the agent called `save_artifact` with a link.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtifactRequest {
    /// An http(s) URL without user info, at most [`ARTIFACT_URL_MAX_CHARS`] characters.
    pub url: String,
    /// Trimmed, redacted, 1 to [`ARTIFACT_TITLE_MAX_CHARS`] characters.
    pub title: String,
}

/// NETWORK: the agent called `request_network_access` for a host its sandbox cannot reach.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkRequest {
    /// `HOST[:PORT]`: a name, `*.example.com` or an IP address; the port is 1 to 65535 and
    /// 443 unless given. At most [`NETWORK_HOST_MAX_CHARS`] characters.
    pub host: String,
    /// Trimmed, redacted, at most [`NETWORK_REASON_MAX_CHARS`] characters.
    pub reason: String,
}

/// TOOL: the agent called a server-executed tool of `run.assign.tools`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolRequest {
    /// The tool's name, as `run.assign.tools` lists it.
    pub tool: String,
    /// The call's arguments (an object), every string redacted, at most
    /// [`TOOL_ARGUMENTS_MAX_BYTES`] as JSON.
    pub arguments: serde_json::Value,
}

/// `run.request.ack.status`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RequestStatus {
    /// A decision was stored and waits for a person (`payload.decisionId`).
    Pending,
    /// The request was carried out (`payload`: an artifact, or a tool result).
    Done,
    /// Nothing was stored or done; `code` and `message` say why.
    Rejected,
}

/// Server -> runner host (v2): the answer to one [`RunRequest`]. The payload's shape follows
/// the request's kind and the status; [`RunRequestAck::decision_id`],
/// [`RunRequestAck::artifact_id`] and [`RunRequestAck::tool_result`] read it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunRequestAck {
    pub run_id: Uuid,
    pub request_id: Uuid,
    pub status: RequestStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub payload: Option<serde_json::Value>,
    /// Why the request was rejected (one of [`request_code`]); set when `status` is `Rejected`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    /// Text for the agent, written by the server; required for `Rejected`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

/// `run.request.ack.payload` of a DONE answer to a TOOL request: an MCP `tools/call` result.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolResultPayload {
    /// MCP content blocks (`{type: "text", text}`).
    pub content: Vec<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub structured_content: Option<serde_json::Value>,
    pub is_error: bool,
}

impl RunRequestAck {
    /// `payload.decisionId` of a PENDING answer.
    pub fn decision_id(&self) -> Option<Uuid> {
        self.payload
            .as_ref()
            .and_then(|p| p.get("decisionId"))
            .and_then(serde_json::Value::as_str)
            .and_then(|s| Uuid::parse_str(s).ok())
    }

    /// `payload.artifactId` of a DONE answer to an ARTIFACT request.
    pub fn artifact_id(&self) -> Option<Uuid> {
        self.payload
            .as_ref()
            .and_then(|p| p.get("artifactId"))
            .and_then(serde_json::Value::as_str)
            .and_then(|s| Uuid::parse_str(s).ok())
    }

    /// The tool result of a DONE answer to a TOOL request; `None` when the payload is not one.
    pub fn tool_result(&self) -> Option<ToolResultPayload> {
        self.payload
            .clone()
            .and_then(|p| serde_json::from_value(p).ok())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CredentialState {
    Available,
    Cooldown,
    Invalid,
}

/// Runner host -> server when a credential's state changes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CredentialStatus {
    pub label: String,
    pub status: CredentialState,
    #[serde(default, skip_serializing_if = "Option::is_none", with = "opt_instant")]
    pub cooldown_until: Option<DateTime<Utc>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

/// Server -> runner host: the desired capacity settings of the host and its credentials,
/// set in Tinfirm. Always the full state, never a delta; sent right after `welcome` and after
/// every change. The host applies it at once and answers [`HostConfigApplied`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostConfig {
    /// Increases by 1 with every change; 0 when no desired value was ever set. The host
    /// ignores a lower version than the last one it applied on the current connection.
    pub version: u64,
    /// `None`: no desired value; the host uses its ceiling.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub desired_max_concurrent_runs: Option<u32>,
    /// The credentials that have a desired pool size; the others use their ceilings.
    pub credentials: Vec<CredentialConfig>,
    /// v2, the feature [`feature::SANDBOX_POOL`]: Tinfirm's count of ready sandboxes; `None`
    /// is 0.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pool: Option<PoolConfig>,
}

/// `host.config.pool` (ADR-0099): the count the server evaluated from the pool's schedule, and
/// its next change.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PoolConfig {
    pub ready_target: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next: Option<PoolChange>,
}

/// The next change of [`PoolConfig::ready_target`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PoolChange {
    pub ready_target: u32,
    #[serde(with = "instant")]
    pub at: DateTime<Utc>,
}

impl HostConfig {
    /// The desired pool size of `label`, if one is set.
    pub fn desired_pool_size(&self, label: &str) -> Option<u32> {
        self.credentials
            .iter()
            .find(|c| c.label == label)
            .map(|c| c.desired_pool_size)
    }
}

/// One credential in [`HostConfig`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CredentialConfig {
    pub label: String,
    pub desired_pool_size: u32,
}

/// Runner host -> server: the capacity the host uses now. Answers every applied
/// [`HostConfig`]; sent unprompted when the host starts to drain.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostConfigApplied {
    /// The latest `host.config` version the host applied on the current connection.
    pub version: u64,
    /// The host finishes its active runs, takes no new ones, then exits.
    pub draining: bool,
    /// Effective: [`effective_capacity`] of the desired value and the ceiling; 0 while draining.
    pub max_concurrent_runs: u32,
    pub max_concurrent_runs_ceiling: u32,
    /// Every credential reported in `hello`.
    pub credentials: Vec<CredentialCapacity>,
    /// v2, the feature [`feature::SANDBOX_POOL`]: the pool's effective values.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pool: Option<PoolApplied>,
}

/// `host.config.applied.pool` (ADR-0099).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PoolApplied {
    /// Tinfirm's count capped by the ceiling; 0 while draining.
    pub ready_target: u32,
    pub max_ready_ceiling: u32,
}

/// Runner host -> server (v2, the feature [`feature::SANDBOX_POOL`]): the host's sandboxes
/// now, on change and at most every 10 seconds (ADR-0099).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostPoolStatus {
    pub ready: u32,
    pub starting: u32,
    pub busy: u32,
    /// The 90th percentile of the last cold starts, in seconds; `None` before the first.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cold_start_p90_seconds: Option<u64>,
    /// Runs of the last 24 hours that took a ready sandbox, and that waited for one.
    pub served_ready: u32,
    pub served_cold: u32,
}

/// One credential in [`HostConfigApplied`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CredentialCapacity {
    pub label: String,
    /// Effective: [`effective_capacity`] of the desired value and the ceiling.
    pub pool_size: u32,
    pub pool_size_ceiling: u32,
}

/// Server -> runner host (v2): the Public Suffix List. The host answers nothing: it checks
/// `sha256` over the decompressed bytes, keeps its current list when the frame is invalid, and
/// otherwise uses it, stores it and reports its `sha256` in `hello.publicSuffixList`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PslUpdate {
    /// Lowercase hex SHA-256 of the UNCOMPRESSED list bytes.
    pub sha256: String,
    #[serde(with = "instant")]
    pub fetched_at: DateTime<Utc>,
    pub rule_count: u32,
    /// Base64 (standard, padded) of the gzip of the list bytes; at most
    /// [`PSL_CONTENT_MAX_LEN`] characters, at most [`PSL_MAX_BYTES`] uncompressed.
    pub content: String,
}

/// `psl.update.content` is at most this many characters (`maxLength` in the schema).
pub const PSL_CONTENT_MAX_LEN: usize = 1_572_864;
/// A list is at most this many bytes uncompressed.
pub const PSL_MAX_BYTES: usize = 4 * 1024 * 1024;

/// Either direction: a frame could not be processed. Never contains secrets.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorMessage {
    pub code: String,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub run_id: Option<Uuid>,
    /// The epoch the error refers to, when it concerns one execution of a run.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub epoch: Option<i64>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secret_is_not_printed() {
        let creds = GitCredentials {
            username: "x-access-token".into(),
            token: Secret::new("ghp_secret_value"),
        };
        let printed = format!("{creds:?}");
        assert!(!printed.contains("ghp_secret_value"));
        assert!(printed.contains("[REDACTED]"));
        let json = serde_json::to_string(&creds).unwrap();
        assert!(json.contains("ghp_secret_value"));
    }

    #[test]
    fn workspace_dir_rule() {
        for ok in ["server", "web-app", "web-app-2", "a", "0", &"a".repeat(64)] {
            assert!(is_valid_workspace_dir(ok), "{ok}");
        }
        for bad in [
            "",
            "-a",
            "a-",
            "a--b",
            "Web",
            "a_b",
            "a.b",
            ".",
            "..",
            "a/b",
            "ü",
            &"a".repeat(65),
        ] {
            assert!(!is_valid_workspace_dir(bad), "{bad}");
        }
    }

    #[test]
    fn effective_capacity_never_exceeds_the_ceiling() {
        assert_eq!(effective_capacity(None, 2), 2);
        assert_eq!(effective_capacity(Some(1), 2), 1);
        assert_eq!(effective_capacity(Some(2), 2), 2);
        assert_eq!(effective_capacity(Some(5), 2), 2);
        assert_eq!(
            effective_capacity(Some(MAX_CAPACITY), MAX_CAPACITY),
            MAX_CAPACITY
        );
    }

    #[test]
    fn host_config_lookup() {
        let config = HostConfig {
            version: 3,
            desired_max_concurrent_runs: None,
            credentials: vec![CredentialConfig {
                label: "claude-main".into(),
                desired_pool_size: 2,
            }],
            pool: None,
        };
        assert_eq!(config.desired_pool_size("claude-main"), Some(2));
        assert_eq!(config.desired_pool_size("codex-main"), None);
    }

    #[test]
    fn unknown_type_is_reported() {
        let err = Message::parse(r#"{"type":"run.bogus","x":1}"#).unwrap_err();
        assert!(matches!(err, ParseError::UnknownType(ref t) if t == "run.bogus"));
        assert_eq!(err.code(), "UNKNOWN_TYPE");
        let err = Message::parse(r#"{"no":"type"}"#).unwrap_err();
        assert_eq!(err.code(), "BAD_MESSAGE");
        let err = Message::parse("not json").unwrap_err();
        assert_eq!(err.code(), "BAD_MESSAGE");
    }

    #[test]
    fn a_frame_of_the_other_version_is_an_unknown_type() {
        let ask_ack = r#"{"type":"run.ask.ack","runId":"0192f1a4-7c1e-7b3a-9d2e-1f2a3b4c5d6e","requestId":"0192f1a4-7c1e-7b3a-9d2e-1f2a3b4c5d6e","status":"REJECTED","code":"X","message":"m"}"#;
        let request_ack = r#"{"type":"run.request.ack","runId":"0192f1a4-7c1e-7b3a-9d2e-1f2a3b4c5d6e","requestId":"0192f1a4-7c1e-7b3a-9d2e-1f2a3b4c5d6e","status":"REJECTED","code":"X","message":"m"}"#;
        // Any version parses both ...
        assert!(matches!(Message::parse(ask_ack), Ok(Message::RunAskAck(_))));
        assert!(matches!(
            Message::parse(request_ack),
            Ok(Message::RunRequestAck(_))
        ));
        // ... but each version only its own frames.
        assert!(matches!(
            Message::parse_version(ask_ack, PROTOCOL_VERSION),
            Ok(Message::RunAskAck(_))
        ));
        let err = Message::parse_version(request_ack, PROTOCOL_VERSION).unwrap_err();
        assert!(matches!(err, ParseError::UnknownType(ref t) if t == "run.request.ack"));
        assert!(matches!(
            Message::parse_version(request_ack, PROTOCOL_VERSION_V2),
            Ok(Message::RunRequestAck(_))
        ));
        let err = Message::parse_version(ask_ack, PROTOCOL_VERSION_V2).unwrap_err();
        assert!(matches!(err, ParseError::UnknownType(ref t) if t == "run.ask.ack"));
        // An unknown version knows no frame at all.
        assert!(Message::parse_version(ask_ack, 3).is_err());
        assert_eq!(schema_json(3), None);
        assert_eq!(message_types(3), None);
        for version in PROTOCOL_VERSIONS {
            let types = message_types(version).unwrap();
            assert!(types.iter().all(|t| KNOWN_MESSAGE_TYPES.contains(t)));
            assert!(schema_json(version).is_some());
        }
    }

    #[test]
    fn the_subject_is_task_in_v1_and_work_item_in_v2() {
        let v1 = serde_json::json!({
            "type": "run.assign", "runId": "0192f1a4-7c1e-7b3a-9d2e-1f2a3b4c5d6e", "epoch": 1,
            "credentialLabel": "c", "agent": {"id": "0192f1a4-7c1e-7b3a-9d2e-1f2a3b4c5d6e", "name": "A", "runtime": "CODEX"},
            "task": {"id": "0192f1a4-7c1e-7b3a-9d2e-1f2a3b4c5d6e", "key": "X-1", "title": "t"},
            "prompt": "p", "workspaces": [], "requestableRepositories": [],
            "limits": {"maxWallClockSeconds": 60, "maxTurns": 1}
        });
        let mut v2 = v1.clone();
        let subject = v2.as_object_mut().unwrap().remove("task").unwrap();
        v2["workItem"] = subject;
        v2["job"] = serde_json::json!({"id": "0192f1a4-7c1e-7b3a-9d2e-1f2a3b4c5d6e"});
        v2["tools"] = serde_json::json!([{"name": "jira_get", "description": "d", "inputSchema": {"type": "object"}}]);
        let Message::RunAssign(one) = Message::from_value(v1.clone()).unwrap() else {
            panic!("not run.assign");
        };
        let Message::RunAssign(two) = Message::from_value(v2.clone()).unwrap() else {
            panic!("not run.assign");
        };
        assert_eq!(one.work_item().key, "X-1");
        assert_eq!(two.work_item().key, "X-1");
        assert!(matches!(one.subject, Subject::Task(_)));
        assert!(matches!(two.subject, Subject::WorkItem(_)));
        assert!(one.job.is_none() && one.tools.is_empty());
        assert!(two.job.is_some());
        assert_eq!(two.tools[0].name, "jira_get");
        // Each is written back under its own name.
        let out = serde_json::to_value(Message::RunAssign(one)).unwrap();
        assert!(out.get("task").is_some() && out.get("workItem").is_none());
        assert!(out.get("job").is_none() && out.get("tools").is_none());
        let out = serde_json::to_value(Message::RunAssign(two)).unwrap();
        assert!(out.get("workItem").is_some() && out.get("task").is_none());
        // Without either, the frame is invalid.
        let mut neither = v1;
        neither.as_object_mut().unwrap().remove("task");
        assert!(Message::from_value(neither).is_err());
    }

    #[test]
    fn tool_name_rule() {
        for ok in ["jira_get", "github-search", "a", "0", &"a".repeat(100)] {
            assert!(is_valid_tool_name(ok), "{ok}");
        }
        for bad in [
            "",
            "Jira_get",
            "_a",
            "-a",
            "a.b",
            "a b",
            "ü",
            &"a".repeat(101),
        ] {
            assert!(!is_valid_tool_name(bad), "{bad}");
        }
    }

    #[test]
    fn request_ack_payload_readers() {
        let id = Uuid::new_v4();
        let ack = |payload: serde_json::Value| RunRequestAck {
            run_id: id,
            request_id: id,
            status: RequestStatus::Done,
            payload: Some(payload),
            code: None,
            message: None,
        };
        assert_eq!(
            ack(serde_json::json!({"decisionId": id})).decision_id(),
            Some(id)
        );
        assert_eq!(
            ack(serde_json::json!({"artifactId": id})).artifact_id(),
            Some(id)
        );
        let result = ack(serde_json::json!({
            "content": [{"type": "text", "text": "SHOP-1: Checkout"}],
            "structuredContent": {"key": "SHOP-1"},
            "isError": false
        }))
        .tool_result()
        .expect("a tool result");
        assert_eq!(result.content.len(), 1);
        assert_eq!(result.structured_content.unwrap()["key"], "SHOP-1");
        assert!(!result.is_error);
        assert_eq!(
            ack(serde_json::json!({"decisionId": id})).tool_result(),
            None
        );
        assert_eq!(
            ack(serde_json::json!({"decisionId": "x"})).decision_id(),
            None
        );
        let frame = RunRequest {
            run_id: id,
            epoch: 1,
            request_id: id,
            request: RequestPayload::Tool(ToolRequest {
                tool: "jira_get".into(),
                arguments: serde_json::json!({"key": "SHOP-1"}),
            }),
        };
        assert_eq!(frame.request.kind(), RequestKind::Tool);
        let value = serde_json::to_value(Message::RunRequest(frame)).unwrap();
        assert_eq!(value["kind"], "TOOL");
        assert_eq!(value["payload"]["tool"], "jira_get");
    }
}
