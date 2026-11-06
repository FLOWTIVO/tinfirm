//! The ACP client of a run (docs/archive/v1/CONVENTIONS.md §15.4 steps 3–6), built on the official ACP Rust SDK.
//!
//! - `initialize` with no fs or terminal client capabilities: the agent uses its own tools
//!   inside the sandbox;
//! - `session/load(resumeSessionId)` when given and supported, else `session/new`; both get
//!   the run's platform MCP endpoint in `mcpServers` when the agent supports MCP over HTTP
//!   (`agentCapabilities.mcpCapabilities.http`), else `[]` (docs/archive/v1/CONVENTIONS.md §15.6),
//!   and the runtime's session `_meta` ([`crate::runtime_env::session_meta`]: Claude Code's
//!   disallowed WebSearch, ADR-0108);
//!   why the run did not resume is reported as a [`ResumeOutcome`] (`run.finished.resumeOutcome`);
//! - `session/set_config_option` of the session's thought-level option when the run has an
//!   effort that its runtime does not take from the environment ([`ThoughtLevel`]);
//! - `session/prompt` with `resumePrompt` after a successful load, else with `prompt`, and the
//!   person's messages that arrived for the run until then ([`Steering`]) appended to it;
//! - while the prompt runs, each new message of the person goes to `_session/steering`, when the
//!   agent advertises it (`initialize`'s `_meta.steering.supported`), which delivers it after the
//!   agent's current step; the answer tells the server whether the turn took it;
//! - `session/update` notifications are forwarded raw to the run loop, each holding its share
//!   of the run's budget for the agent's messages in flight ([`ACP_INBOUND_BYTES`]);
//! - `session/request_permission` is answered by auto-selecting an allow option (v0);
//! - `session/cancel` is sent when the run loop asks for it.

use agent_client_protocol::schema::ProtocolVersion;
use agent_client_protocol::schema::v1::{
    CancelNotification, ContentBlock, Implementation, InitializeRequest, LoadSessionRequest,
    McpServer, McpServerHttp, NewSessionRequest, PromptRequest, SessionConfigId, SessionConfigKind,
    SessionConfigOption, SessionConfigOptionCategory, SessionConfigSelectOptions,
    SetSessionConfigOptionRequest, TextContent,
};
use std::collections::VecDeque;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;

use agent_client_protocol::{
    Agent, Client, ConnectionTo, Dispatch, HandleDispatchFrom, Handled, Lines, Responder,
    UntypedMessage,
};
use futures::future::BoxFuture;
use futures::stream::FuturesUnordered;
use futures::{Sink, Stream, StreamExt};
use serde_json::{Value, json};
use tinfirm_protocol::{Effort, ResumeOutcome, RunSteer, RunSteerAck, steer_code};
use tokio::sync::mpsc::{self, UnboundedReceiver, UnboundedSender};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

use crate::limits::AgentError;
use crate::steer::Steering;

/// The ACP extension request that gives a running turn a message of the person (the steering
/// extension of claude-agent-acp and codex-acp, and of Tinfirm's pi adapter).
pub const STEER_METHOD: &str = "_session/steering";

/// After the prompt's answer, how long the answers of steering requests already sent may still
/// come in: an agent answers a steer it took before it ends the turn.
const STEER_ANSWER_GRACE: Duration = Duration::from_millis(500);

/// Text bytes of the agent's ACP messages the host holds at once between the sandbox's output
/// and the run loop: complete lines not yet taken by the ACP SDK, what the SDK has parsed and
/// not yet dispatched, and `session/update` notifications the run loop has not taken. Twice
/// [`crate::run::MAX_ACP_LINE`], so a line of the cap is always admissible while another is held,
/// and the same order as the bridge's 8 MiB read-ahead (ADR-0095). What a line holds is its
/// bytes plus [`ACP_LINE_OVERHEAD`]; a line waits for it, and the splitter with it, so the
/// sandbox's output stays in its bounded queue and then in the sandbox. The SDK's parsed form
/// of a line (`serde_json::Value`) takes a bounded multiple of its text.
pub(crate) const ACP_INBOUND_BYTES: usize = 16 * 1024 * 1024;
/// What each ACP line holds of [`ACP_INBOUND_BYTES`] beyond its bytes, for the queue slots,
/// the parsed message and the budget's own bookkeeping: bounds the lines in flight to 32 K,
/// however short they are.
pub(crate) const ACP_LINE_OVERHEAD: usize = 512;
/// Complete ACP lines the splitter may hand on ahead of the SDK's transport, which takes them
/// as fast as it is polled; the budget, not this, bounds what waits.
pub(crate) const ACP_LINE_QUEUE: usize = 256;
/// The notification the host sends itself through the SDK behind each of the agent's lines:
/// the SDK parses and dispatches it in order with them, so the line's own dispatch, when
/// there is one, comes between the probe before it and the probe after it, and the probe
/// after it proves the SDK took the line. The line's budget thus goes with its own dispatch
/// (never with another line's), and comes back with the probe when the SDK dispatched
/// nothing for the line (a response nobody waits for, a line that is not a JSON-RPC message,
/// which the SDK rejects on its own without touching the probe's line).
const BUDGET_PROBE_METHOD: &str = "_tinfirm/budget";

/// The run's budget for the agent's messages in flight ([`ACP_INBOUND_BYTES`]).
pub(crate) fn inbound_budget() -> Arc<Semaphore> {
    Arc::new(Semaphore::new(ACP_INBOUND_BYTES))
}

/// What a line of `len` bytes holds of the budget.
pub(crate) fn line_cost(len: usize) -> u32 {
    u32::try_from(len.saturating_add(ACP_LINE_OVERHEAD).min(ACP_INBOUND_BYTES)).unwrap_or(u32::MAX)
}

/// One ACP line of the agent's stdout on its way to the SDK, with the budget it holds.
#[derive(Debug)]
pub(crate) struct AcpLine {
    pub(crate) text: String,
    pub(crate) permit: OwnedSemaphorePermit,
}

/// The run's ACP transport: the agent's stdin as a sink of lines, and its stdout's ACP lines
/// as the splitter hands them on, each with the budget it holds.
pub(crate) struct AcpTransport {
    pub(crate) outgoing: Pin<Box<dyn Sink<String, Error = std::io::Error> + Send + 'static>>,
    pub(crate) lines: mpsc::Receiver<std::io::Result<AcpLine>>,
    pub(crate) budget: Arc<Semaphore>,
}

/// The budget of the lines the SDK has taken and not yet dispatched, in the order it took
/// them, each followed by its probe.
type Held = Arc<std::sync::Mutex<VecDeque<Entry>>>;

enum Entry {
    Line(OwnedSemaphorePermit),
    Probe(u64),
}

/// Takes the budget of the line the SDK is dispatching: the oldest held, every line before
/// it having gone with its own dispatch or its probe.
fn take_oldest(held: &Held) -> Option<OwnedSemaphorePermit> {
    let mut held = held.lock().unwrap_or_else(|e| e.into_inner());
    while let Some(entry) = held.pop_front() {
        if let Entry::Line(permit) = entry {
            return Some(permit);
        }
    }
    None
}

/// Gives back the budget of every line up to the probe `seq`, the SDK having taken them all:
/// the probe's own line, when the SDK dispatched nothing for it.
fn release_through(held: &Held, seq: Option<u64>) {
    let mut held = held.lock().unwrap_or_else(|e| e.into_inner());
    while let Some(entry) = held.pop_front() {
        if let Entry::Probe(found) = entry
            && Some(found) == seq
        {
            return;
        }
    }
}

/// The agent's lines as the SDK's transport reads them, each followed by its probe. A line's
/// budget is parked in `held` as the SDK takes the line; [`BudgetObserver`] hands it on or
/// gives it back as the SDK dispatches the line, or its probe.
struct BudgetedLines {
    rx: mpsc::Receiver<std::io::Result<AcpLine>>,
    held: Held,
    budget: Arc<Semaphore>,
    /// Probes so far.
    seq: u64,
    /// The probe of the line just given, to go before anything else.
    probe: Option<u64>,
}

impl BudgetedLines {
    fn new(
        rx: mpsc::Receiver<std::io::Result<AcpLine>>,
        held: Held,
        budget: Arc<Semaphore>,
    ) -> Self {
        BudgetedLines {
            rx,
            held,
            budget,
            seq: 0,
            probe: None,
        }
    }
}

fn probe_line(seq: u64) -> String {
    json!({"jsonrpc": "2.0", "method": BUDGET_PROBE_METHOD, "params": {"seq": seq}}).to_string()
}

impl Stream for BudgetedLines {
    type Item = std::io::Result<String>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        let this = self.get_mut();
        if let Some(seq) = this.probe.take() {
            return Poll::Ready(Some(Ok(probe_line(seq))));
        }
        match this.rx.poll_recv(cx) {
            Poll::Ready(Some(Ok(line))) => {
                this.seq += 1;
                let mut held = this.held.lock().unwrap_or_else(|e| e.into_inner());
                held.push_back(Entry::Line(line.permit));
                held.push_back(Entry::Probe(this.seq));
                drop(held);
                this.probe = Some(this.seq);
                Poll::Ready(Some(Ok(line.text)))
            }
            Poll::Ready(Some(Err(e))) => Poll::Ready(Some(Err(e))),
            Poll::Ready(None) => Poll::Ready(None),
            Poll::Pending => Poll::Pending,
        }
    }
}

impl Drop for BudgetedLines {
    /// The transport's read side is gone (the agent's output ended, or the connection): the
    /// splitter's next line finds the budget closed rather than waiting for it. What the SDK
    /// still has to dispatch keeps its budget until then; the rest goes back with the observer.
    fn drop(&mut self) {
        self.budget.close();
    }
}

/// First in the connection's handler chain: sees every message the SDK dispatches, in the
/// order the transport took them, so a message's dispatch finds its own line's budget at the
/// head of `held` (the line before it went with its dispatch or its probe). A `session/update`
/// goes to the run loop with its budget; any other message gives its own back; a probe gives
/// back what its line still holds. Every notification is claimed, as the SDK would otherwise
/// keep one that names a session for a handler that never comes.
struct BudgetObserver {
    held: Held,
    inbound: UnboundedSender<AcpInbound>,
}

impl HandleDispatchFrom<Agent> for BudgetObserver {
    fn describe_chain(&self) -> impl std::fmt::Debug {
        "tinfirm-budget"
    }

    async fn handle_dispatch_from(
        &mut self,
        dispatch: Dispatch,
        _cx: ConnectionTo<Agent>,
    ) -> Result<Handled<Dispatch>, agent_client_protocol::Error> {
        match dispatch {
            Dispatch::Notification(message) if message.method == BUDGET_PROBE_METHOD => {
                release_through(
                    &self.held,
                    message.params.get("seq").and_then(Value::as_u64),
                );
                Ok(Handled::Yes)
            }
            Dispatch::Notification(message) => {
                let permit = take_oldest(&self.held);
                if message.method == "session/update" {
                    let _ = self.inbound.send(AcpInbound::Update {
                        params: message.params,
                        permit,
                    });
                }
                Ok(Handled::Yes)
            }
            other => {
                drop(take_oldest(&self.held));
                Ok(Handled::No {
                    message: other,
                    retry: false,
                })
            }
        }
    }
}

/// What the ACP client reports to the run loop, in order.
#[derive(Debug)]
pub enum AcpInbound {
    /// Params of a `session/update` notification, with its share of the run's budget for the
    /// agent's messages in flight: dropping it lets the splitter take the next line.
    Update {
        params: Value,
        permit: Option<OwnedSemaphorePermit>,
    },
    /// The session is open; the prompt is about to be sent. Updates before this marker are
    /// the history replay of `session/load`.
    Prompting {
        session_id: String,
        /// Why the session was not resumed; `None` when `session/load` succeeded.
        not_resumed: Option<NotResumed>,
        /// The agent's version, as `initialize` named it (`agentInfo.version`).
        agent_version: Option<String>,
    },
    /// A permission request was answered automatically.
    PermissionGranted { title: String, option: String },
    /// Something the run should log as a warning.
    Warning(String),
    /// Something the run should log as information.
    Info(String),
}

/// Commands from the run loop.
#[derive(Debug, Clone, Copy)]
pub enum AcpControl {
    Cancel,
}

#[derive(Debug, Clone)]
pub struct AcpParams {
    pub cwd: String,
    pub prompt: String,
    pub resume_session_id: Option<String>,
    pub resume_prompt: Option<String>,
    /// The session home was created for this run: no earlier session can be in it.
    pub new_home: bool,
    pub client_version: String,
    /// The run's platform MCP endpoint, when the host serves one.
    pub mcp_url: Option<String>,
    /// The `_meta` of `session/new` and `session/load`
    /// ([`crate::runtime_env::session_meta`]): Claude Code's disallowed tools.
    pub session_meta: Option<serde_json::Map<String, Value>>,
    /// What the host does with the session's thought-level config option.
    pub thought_level: ThoughtLevel,
    /// The person's messages for the run: the session takes them, and closes it when the prompt
    /// is over.
    pub steering: Arc<Steering>,
}

/// What the host does with the session's thought-level config option (ACP category
/// `thought_level`), for a runtime that takes the agent's effort from it rather than from its
/// environment (`runtime_env::effort_in_env`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ThoughtLevel {
    /// The runtime takes the effort from its environment: the option stays as it is.
    Untouched,
    /// The agent's effort: the level itself, or the nearest one the model offers.
    Effort(Effort),
    /// The runtime's default: the option's `default` value. A resumed session keeps the level an
    /// earlier run set, so a thread whose agent went back to the default is reset.
    Default,
}

/// Why a run started a new session instead of resuming one.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NotResumed {
    pub outcome: ResumeOutcome,
    /// The error of `session/load`, when it failed.
    pub error: Option<String>,
}

impl NotResumed {
    fn of(outcome: ResumeOutcome) -> NotResumed {
        NotResumed {
            outcome,
            error: None,
        }
    }
}

/// How the prompt ended.
#[derive(Debug, Clone)]
pub struct PromptOutcome {
    pub stop_reason: String,
    pub usage: Option<Value>,
    pub meta: Option<Value>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AcpStage {
    Initialize,
    Session,
    Prompt,
}

#[derive(Debug, Clone)]
pub struct AcpResult {
    pub session_id: Option<String>,
    pub loaded: bool,
    pub outcome: Result<PromptOutcome, (AcpStage, AgentError)>,
}

fn agent_error(e: agent_client_protocol::Error) -> AgentError {
    let transport_closed = agent_client_protocol::is_incoming_transport_closed(&e);
    AgentError {
        code: Some(i32::from(e.code)),
        message: e.message,
        data: e.data,
        transport_closed,
    }
}

/// What a failed `session/load` means. The session files are missing when the home is new,
/// or when the agent says it does not know the id (ACP `-32002` "Resource not found", or a
/// message to that effect); any other error is [`ResumeOutcome::LoadFailed`].
pub fn load_failure(new_home: bool, code: i32, message: &str) -> ResumeOutcome {
    const RESOURCE_NOT_FOUND: i32 = -32002;
    let message = message.to_ascii_lowercase();
    let unknown = [
        "not found",
        "no such",
        "does not exist",
        "doesn't exist",
        "no conversation",
        "unknown session",
    ]
    .iter()
    .any(|m| message.contains(m));
    if new_home || code == RESOURCE_NOT_FOUND || unknown {
        ResumeOutcome::NoSession
    } else {
        ResumeOutcome::LoadFailed
    }
}

/// The prompt to send: `resumePrompt` only when `session/load` of `resumeSessionId`
/// succeeded (the agent has the history); otherwise the full `prompt`.
pub fn prompt_text(params: &AcpParams, loaded: bool) -> &str {
    match (&params.resume_prompt, loaded) {
        (Some(resume), true) if !resume.trim().is_empty() => resume,
        _ => &params.prompt,
    }
}

/// `mcpServers` for `session/new` and `session/load`: the platform MCP endpoint when there
/// is one and the agent speaks MCP over HTTP. `Err` carries the reason for an empty list
/// when an endpoint was available.
pub fn mcp_servers(mcp_url: Option<&str>, agent_http: bool) -> Result<Vec<McpServer>, String> {
    match mcp_url {
        None => Ok(Vec::new()),
        Some(url) if agent_http => Ok(vec![McpServer::Http(McpServerHttp::new(
            crate::mcp::SERVER_NAME,
            url,
        ))]),
        Some(_) => Err(
            "the agent does not support MCP over HTTP (agentCapabilities.mcpCapabilities.http); the Tinfirm platform tools are not available in this run".into(),
        ),
    }
}

/// Picks the allow option of a permission request: `allow_once` first (so that no
/// "always allow" rule is written into the repository), then `allow_always`.
pub fn choose_permission_option(params: &Value) -> Option<String> {
    let options = params.get("options")?.as_array()?;
    for wanted in ["allow_once", "allow_always"] {
        if let Some(option) = options
            .iter()
            .find(|o| o.get("kind").and_then(Value::as_str) == Some(wanted))
        {
            return option
                .get("optionId")
                .and_then(Value::as_str)
                .map(str::to_string);
        }
    }
    None
}

/// Whether the agent advertises the steering extension ([`STEER_METHOD`]) in `initialize`'s
/// top-level `_meta.steering.supported`.
pub fn steering_supported(meta: Option<&serde_json::Map<String, Value>>) -> bool {
    meta.and_then(|m| m.get("steering"))
        .and_then(|s| s.get("supported"))
        .and_then(Value::as_bool)
        .unwrap_or(false)
}

/// The params of a [`STEER_METHOD`] request: the text, and the host's choice to keep a message
/// that finds no running turn (`idleBehavior: promptRequired`) rather than have the agent start a
/// turn of its own for it.
pub fn steer_params(session_id: &str, steer: &RunSteer) -> Value {
    json!({
        "sessionId": session_id,
        "prompt": [{"type": "text", "text": steer.text}],
        "_meta": {"steering": {"idleBehavior": "promptRequired"}}
    })
}

/// The `run.steer.ack` for the agent's answer to a [`STEER_METHOD`] request: `injected` is
/// delivered; a turn that was over (`promptRequired`, or a turn the agent started for it,
/// `startedNewTurn`) is not running; an unknown method is no steering.
pub fn steer_ack(steer: &RunSteer, answer: Result<&Value, (i32, &str)>) -> RunSteerAck {
    const METHOD_NOT_FOUND: i32 = -32601;
    match answer {
        Ok(value) => match value.get("outcome").and_then(Value::as_str) {
            Some("injected") => RunSteerAck::delivered(steer),
            Some(outcome @ ("promptRequired" | "startedNewTurn")) => RunSteerAck::refused(
                steer,
                steer_code::NOT_RUNNING,
                format!("the agent's turn was over ({outcome})"),
            ),
            other => RunSteerAck::refused(
                steer,
                steer_code::FAILED,
                format!(
                    "the agent answered the steer with {}",
                    other.unwrap_or("no outcome")
                ),
            ),
        },
        Err((METHOD_NOT_FOUND, _)) => RunSteerAck::refused(
            steer,
            steer_code::NOT_SUPPORTED,
            "the agent has no steering (_session/steering)",
        ),
        Err((_, message)) => RunSteerAck::refused(steer, steer_code::FAILED, message),
    }
}

/// The prompt text with the person's messages that arrived before it was sent.
pub fn with_steers(prompt: &str, steers: &[RunSteer]) -> String {
    let mut text = prompt.to_string();
    for steer in steers {
        text.push_str("\n\n");
        text.push_str(&steer.text);
    }
    text
}

/// Runs the ACP conversation over `transport` until the prompt finishes (or the transport
/// closes). Dropping the returned future abandons the conversation.
pub(crate) async fn run_session(
    transport: AcpTransport,
    params: AcpParams,
    inbound: UnboundedSender<AcpInbound>,
    mut control: UnboundedReceiver<AcpControl>,
) -> AcpResult {
    let AcpTransport {
        outgoing,
        lines,
        budget,
    } = transport;
    let held = Held::default();
    let transport = Lines::new(outgoing, BudgetedLines::new(lines, held.clone(), budget));
    let observer = BudgetObserver {
        held,
        inbound: inbound.clone(),
    };
    let permission_tx = inbound.clone();
    let result = Client
        .builder()
        .name("tinfirm-runner-host")
        .with_handler(observer)
        .on_receive_request(
            async move |request: UntypedMessage,
                        responder: Responder<Value>,
                        _cx: ConnectionTo<Agent>| {
                if request.method != "session/request_permission" {
                    // No fs or terminal capabilities were advertised.
                    return responder
                        .respond_with_error(agent_client_protocol::Error::method_not_found());
                }
                let title = request
                    .params
                    .pointer("/toolCall/title")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string();
                match choose_permission_option(&request.params) {
                    Some(option) => {
                        let _ = permission_tx.send(AcpInbound::PermissionGranted {
                            title,
                            option: option.clone(),
                        });
                        responder.respond(json!({
                            "outcome": {"outcome": "selected", "optionId": option}
                        }))
                    }
                    None => responder.respond(json!({"outcome": {"outcome": "cancelled"}})),
                }
            },
            agent_client_protocol::on_receive_request!(),
        )
        .connect_with(transport, async move |cx: ConnectionTo<Agent>| {
            Ok(converse(cx, params, inbound, &mut control).await)
        })
        .await;
    match result {
        Ok(result) => result,
        Err(e) => AcpResult {
            session_id: None,
            loaded: false,
            outcome: Err((AcpStage::Initialize, agent_error(e))),
        },
    }
}

async fn converse(
    cx: ConnectionTo<Agent>,
    params: AcpParams,
    inbound: UnboundedSender<AcpInbound>,
    control: &mut UnboundedReceiver<AcpControl>,
) -> AcpResult {
    let steering = params.steering.clone();
    let init = InitializeRequest::new(ProtocolVersion::V1).client_info(Implementation::new(
        "tinfirm-runner-host",
        params.client_version.clone(),
    ));
    let init = match cx.send_request(init).block_task().await {
        Ok(init) => init,
        Err(e) => {
            return AcpResult {
                session_id: None,
                loaded: false,
                outcome: Err((AcpStage::Initialize, agent_error(e))),
            };
        }
    };

    let can_steer = steering_supported(init.meta.as_ref());
    let servers = match mcp_servers(
        params.mcp_url.as_deref(),
        init.agent_capabilities.mcp_capabilities.http,
    ) {
        Ok(servers) => servers,
        Err(reason) => {
            let _ = inbound.send(AcpInbound::Warning(reason));
            Vec::new()
        }
    };

    let mut session_id = None;
    let mut loaded = false;
    let mut not_resumed = None;
    let mut config_options = Vec::new();
    match &params.resume_session_id {
        None => not_resumed = Some(NotResumed::of(ResumeOutcome::FirstRun)),
        Some(_) if !init.agent_capabilities.load_session => {
            not_resumed = Some(NotResumed::of(ResumeOutcome::NotSupported));
        }
        Some(resume) => {
            match cx
                .send_request(
                    LoadSessionRequest::new(resume.clone(), params.cwd.clone())
                        .mcp_servers(servers.clone())
                        .meta(params.session_meta.clone()),
                )
                .block_task()
                .await
            {
                Ok(response) => {
                    session_id = Some(resume.clone());
                    loaded = true;
                    config_options = response.config_options.unwrap_or_default();
                }
                Err(e) => {
                    let outcome = load_failure(params.new_home, i32::from(e.code), &e.message);
                    tracing::info!(error = %e.message, ?outcome, "session/load failed; starting a new session");
                    not_resumed = Some(NotResumed {
                        outcome,
                        error: Some(e.message),
                    });
                }
            }
        }
    }
    let session_id = match session_id {
        Some(id) => id,
        None => match cx
            .send_request(
                NewSessionRequest::new(params.cwd.clone())
                    .mcp_servers(servers)
                    .meta(params.session_meta.clone()),
            )
            .block_task()
            .await
        {
            Ok(response) => {
                config_options = response.config_options.unwrap_or_default();
                response.session_id.to_string()
            }
            Err(e) => {
                return AcpResult {
                    session_id: None,
                    loaded: false,
                    outcome: Err((AcpStage::Session, agent_error(e))),
                };
            }
        },
    };
    match params.thought_level {
        ThoughtLevel::Untouched => {}
        ThoughtLevel::Effort(effort) => {
            set_effort(&cx, &session_id, &config_options, effort, &inbound).await;
        }
        ThoughtLevel::Default => {
            reset_effort(&cx, &session_id, &config_options, &inbound).await;
        }
    }

    let _ = inbound.send(AcpInbound::Prompting {
        session_id: session_id.clone(),
        not_resumed,
        agent_version: init.agent_info.as_ref().map(|info| info.version.clone()),
    });
    // A stop that came while the session opened: no message goes to a turn that is cancelled.
    let mut control_open = true;
    let mut cancel_first = false;
    while let Ok(AcpControl::Cancel) = control.try_recv() {
        cancel_first = true;
    }
    if cancel_first {
        steering.close("the run is stopping");
    }
    let early = steering.start_prompt();
    let text = with_steers(prompt_text(&params, loaded), &early);
    let request = PromptRequest::new(
        session_id.clone(),
        vec![ContentBlock::Text(TextContent::new(text))],
    );
    // The response is read as raw JSON so that stop reasons and usage fields newer than
    // this SDK version do not fail the run.
    let untyped = match UntypedMessage::new("session/prompt", &request) {
        Ok(m) => m,
        Err(e) => {
            steering.close("the prompt could not be sent");
            return AcpResult {
                session_id: Some(session_id),
                loaded,
                outcome: Err((AcpStage::Prompt, agent_error(e))),
            };
        }
    };
    let response = cx.send_request(untyped).block_task();
    tokio::pin!(response);
    if cancel_first {
        let _ = cx.send_notification(CancelNotification::new(session_id.clone()));
    }
    for steer in &early {
        steering.answer(RunSteerAck::delivered(steer));
    }
    if !early.is_empty() {
        let _ = inbound.send(AcpInbound::Info(format!(
            "{} message(s) of the person went with the prompt",
            early.len()
        )));
    }
    let mut steers: FuturesUnordered<BoxFuture<'static, (RunSteer, RunSteerAck)>> =
        FuturesUnordered::new();
    let outcome = loop {
        tokio::select! {
            result = &mut response => break result,
            command = control.recv(), if control_open => match command {
                Some(AcpControl::Cancel) => {
                    steering.close("the run is stopping");
                    let _ = cx.send_notification(CancelNotification::new(session_id.clone()));
                }
                None => control_open = false,
            },
            () = steering.arrived() => {
                while let Some(steer) = steering.take() {
                    if !can_steer {
                        steering.answer(RunSteerAck::refused(
                            &steer,
                            steer_code::NOT_SUPPORTED,
                            "the agent has no steering (_session/steering); the message waits for the next turn",
                        ));
                        continue;
                    }
                    match UntypedMessage::new(STEER_METHOD, steer_params(&session_id, &steer)) {
                        Ok(request) => {
                            let answer = cx.send_request(request).block_task();
                            steers.push(Box::pin(async move {
                                let ack = match answer.await {
                                    Ok(value) => steer_ack(&steer, Ok(&value)),
                                    Err(e) => steer_ack(&steer, Err((i32::from(e.code), &e.message))),
                                };
                                (steer, ack)
                            }));
                        }
                        Err(e) => steering.answer(RunSteerAck::refused(&steer, steer_code::FAILED, e.message)),
                    }
                }
            }
            Some((_, ack)) = steers.next(), if !steers.is_empty() => {
                report_steer(&inbound, &ack);
                steering.answer(ack);
            }
        }
    };
    // Answers already on their way count; the rest, and every later message, wait for the next
    // turn.
    let grace = tokio::time::Instant::now() + STEER_ANSWER_GRACE;
    while !steers.is_empty() {
        match tokio::time::timeout_at(grace, steers.next()).await {
            Ok(Some((_, ack))) => {
                report_steer(&inbound, &ack);
                steering.answer(ack);
            }
            _ => break,
        }
    }
    steering.close("the agent's turn is over");
    let outcome = match outcome {
        Ok(value) => Ok(PromptOutcome {
            stop_reason: value
                .get("stopReason")
                .and_then(Value::as_str)
                .unwrap_or("end_turn")
                .to_string(),
            usage: value.get("usage").cloned().filter(|v| !v.is_null()),
            meta: value.get("_meta").cloned().filter(|v| !v.is_null()),
        }),
        Err(e) => Err((AcpStage::Prompt, agent_error(e))),
    };
    AcpResult {
        session_id: Some(session_id),
        loaded,
        outcome,
    }
}

/// Logs what became of a message of the person that went to the agent's steering.
fn report_steer(inbound: &UnboundedSender<AcpInbound>, ack: &RunSteerAck) {
    let line = match &ack.code {
        None => "a message of the person reached the agent's running turn".to_string(),
        Some(code) => format!(
            "a message of the person did not reach the agent's turn ({code}: {}); it waits for the next turn",
            ack.message.as_deref().unwrap_or("")
        ),
    };
    let _ = inbound.send(AcpInbound::Info(line));
}

/// Sets the session's thought level to the value [`effort_choice`] picks for `effort`. A
/// session without such an option, or an agent that refuses the value, runs at its default:
/// the run goes on, with a warning in its log.
async fn set_effort(
    cx: &ConnectionTo<Agent>,
    session_id: &str,
    options: &[SessionConfigOption],
    effort: Effort,
    inbound: &UnboundedSender<AcpInbound>,
) {
    let Some((config_id, value)) = effort_choice(options, effort) else {
        let _ = inbound.send(AcpInbound::Warning(format!(
            "the agent offers no effort level near {}; the run uses its default",
            effort.level()
        )));
        return;
    };
    let request =
        SetSessionConfigOptionRequest::new(session_id.to_string(), config_id, value.as_str());
    match cx.send_request(request).block_task().await {
        Ok(_) if value == effort.level() => {
            let _ = inbound.send(AcpInbound::Info(format!("effort {value}")));
        }
        Ok(_) => {
            let _ = inbound.send(AcpInbound::Info(format!(
                "effort {value}: the model offers no {}",
                effort.level()
            )));
        }
        Err(e) => {
            let _ = inbound.send(AcpInbound::Warning(format!(
                "setting the effort to {value} failed ({}); the run uses its default",
                e.message
            )));
        }
    }
}

/// Returns the session's thought level to its `default` value when it holds another. A session
/// without such an option or value stays as it is.
async fn reset_effort(
    cx: &ConnectionTo<Agent>,
    session_id: &str,
    options: &[SessionConfigOption],
    inbound: &UnboundedSender<AcpInbound>,
) {
    let Some((config_id, current, values)) = thought_level_select(options) else {
        return;
    };
    if current == DEFAULT_VALUE || !values.contains(&DEFAULT_VALUE) {
        return;
    }
    let request =
        SetSessionConfigOptionRequest::new(session_id.to_string(), config_id, DEFAULT_VALUE);
    match cx.send_request(request).block_task().await {
        Ok(_) => {
            let _ = inbound.send(AcpInbound::Info(format!(
                "effort default (the session had {current})"
            )));
        }
        Err(e) => {
            let _ = inbound.send(AcpInbound::Warning(format!(
                "returning the effort to the default failed ({}); the run keeps {current}",
                e.message
            )));
        }
    }
}

/// The value of a thought-level option that leaves the level to the model.
const DEFAULT_VALUE: &str = "default";

/// The session's thought-level select (ACP category `thought_level`): its id, its current value
/// and the values it offers.
fn thought_level_select(
    options: &[SessionConfigOption],
) -> Option<(SessionConfigId, &str, Vec<&str>)> {
    options.iter().find_map(|option| {
        if option.category != Some(SessionConfigOptionCategory::ThoughtLevel) {
            return None;
        }
        let SessionConfigKind::Select(select) = &option.kind else {
            return None;
        };
        let values: Vec<&str> = match &select.options {
            SessionConfigSelectOptions::Ungrouped(options) => {
                options.iter().map(|o| o.value.0.as_ref()).collect()
            }
            SessionConfigSelectOptions::Grouped(groups) => groups
                .iter()
                .flat_map(|g| g.options.iter().map(|o| o.value.0.as_ref()))
                .collect(),
            _ => Vec::new(),
        };
        Some((option.id.clone(), select.current_value.0.as_ref(), values))
    })
}

/// The session's thought-level option and the value of it for `effort`: the level itself when
/// offered, else the nearest offered level, the lower one when two are as near. Values that are
/// not one of the levels (`default`, `minimal`, `none`) never count. `None` when the session has
/// no such option or it offers none of the levels.
pub fn effort_choice(
    options: &[SessionConfigOption],
    effort: Effort,
) -> Option<(SessionConfigId, String)> {
    let (config_id, _, values) = thought_level_select(options)?;
    let rank = |level: Effort| Effort::ALL.iter().position(|e| *e == level).unwrap_or(0);
    values
        .into_iter()
        .filter_map(Effort::from_level)
        .min_by_key(|level| (rank(*level).abs_diff(rank(effort)), *level > effort))
        .map(|level| (config_id, level.level().to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn options(value: Value) -> Vec<SessionConfigOption> {
        serde_json::from_value(value).unwrap()
    }

    fn thought_level(values: &[&str]) -> Vec<SessionConfigOption> {
        options(json!([
            {"id": "model", "name": "Model", "category": "model", "type": "select",
             "currentValue": "high", "options": [{"value": "high", "name": "High"}]},
            {"id": "effort", "name": "Effort", "category": "thought_level", "type": "select",
             "currentValue": "default",
             "options": values.iter().map(|v| json!({"value": v, "name": v})).collect::<Vec<_>>()}
        ]))
    }

    #[test]
    fn the_effort_is_the_level_itself_or_the_nearest_offered() {
        let choice = |values: &[&str], effort| {
            effort_choice(&thought_level(values), effort)
                .map(|(id, value)| (id.0.to_string(), value))
        };
        let all = ["default", "low", "medium", "high", "xhigh", "max"];
        for effort in Effort::ALL {
            assert_eq!(
                choice(&all, effort),
                Some(("effort".to_string(), effort.level().to_string()))
            );
        }
        // Not offered: the nearest level, the lower one when two are as near.
        let some = ["default", "low", "high", "max"];
        assert_eq!(choice(&some, Effort::Xhigh).unwrap().1, "high");
        assert_eq!(choice(&some, Effort::Medium).unwrap().1, "low");
        assert_eq!(choice(&["low", "max"], Effort::Xhigh).unwrap().1, "max");
        assert_eq!(choice(&["low", "max"], Effort::Medium).unwrap().1, "low");
        assert_eq!(
            choice(&["minimal", "high", "max"], Effort::Low).unwrap().1,
            "high"
        );
        // Other values never count, and only the thought-level option does.
        assert_eq!(choice(&["default", "minimal", "none"], Effort::Max), None);
        assert_eq!(effort_choice(&[], Effort::High), None);
        let model_only = options(json!([
            {"id": "model", "name": "Model", "category": "model", "type": "select",
             "currentValue": "high", "options": [{"value": "high", "name": "High"}]}
        ]));
        assert_eq!(effort_choice(&model_only, Effort::High), None);
        // Grouped options count like ungrouped ones.
        let grouped = options(json!([
            {"id": "variant", "name": "Variant", "category": "thought_level", "type": "select",
             "currentValue": "low",
             "options": [{"group": "fast", "name": "Fast", "options": [{"value": "low", "name": "Low"}]},
                         {"group": "deep", "name": "Deep", "options": [{"value": "max", "name": "Max"}]}]}
        ]));
        assert_eq!(
            effort_choice(&grouped, Effort::High).map(|(id, v)| (id.0.to_string(), v)),
            Some(("variant".to_string(), "low".to_string()))
        );
    }

    #[test]
    fn resume_prompt_only_after_a_successful_load() {
        let mut params = AcpParams {
            cwd: "/workspace".into(),
            prompt: "FULL".into(),
            resume_session_id: Some("s1".into()),
            resume_prompt: Some("RESUME".into()),
            new_home: false,
            client_version: "0".into(),
            mcp_url: None,
            session_meta: None,
            thought_level: ThoughtLevel::Untouched,
            steering: Steering::new(
                uuid::Uuid::new_v4(),
                1,
                Arc::new(crate::outbox::Outbox::new()),
            ),
        };
        assert_eq!(prompt_text(&params, true), "RESUME");
        assert_eq!(
            prompt_text(&params, false),
            "FULL",
            "load failed or unsupported"
        );
        params.resume_prompt = Some("  ".into());
        assert_eq!(prompt_text(&params, true), "FULL");
        params.resume_prompt = None;
        assert_eq!(prompt_text(&params, true), "FULL");
    }

    #[test]
    fn a_failed_load_is_no_session_when_the_files_cannot_be_there() {
        use ResumeOutcome::*;
        assert_eq!(load_failure(true, -32603, "Internal error"), NoSession);
        assert_eq!(load_failure(false, -32002, "Resource not found"), NoSession);
        for message in [
            "Session not found: 5b0c",
            "No conversation found with session ID 5b0c",
            "ENOENT: no such file or directory",
            "Unknown session id",
        ] {
            assert_eq!(load_failure(false, -32603, message), NoSession, "{message}");
        }
        assert_eq!(
            load_failure(false, -32603, "Internal error: rollout is corrupt"),
            LoadFailed
        );
        assert_eq!(load_failure(false, -32602, "Invalid params"), LoadFailed);
    }

    #[test]
    fn mcp_servers_only_for_agents_with_http_support() {
        let url = "http://host.docker.internal:40000/SECRET/mcp";
        let servers = mcp_servers(Some(url), true).unwrap();
        assert_eq!(
            serde_json::to_value(&servers).unwrap(),
            json!([{"type": "http", "name": "tinfirm", "url": url, "headers": []}])
        );
        let reason = mcp_servers(Some(url), false).unwrap_err();
        assert!(reason.contains("mcpCapabilities.http"), "{reason}");
        assert!(mcp_servers(None, true).unwrap().is_empty());
        assert!(mcp_servers(None, false).unwrap().is_empty());
    }

    #[test]
    fn steering_is_advertised_answered_and_appended() {
        let meta = |value: Value| value.as_object().cloned();
        assert!(steering_supported(
            meta(json!({"steering": {"supported": true}})).as_ref()
        ));
        assert!(!steering_supported(
            meta(json!({"steering": {"supported": false}})).as_ref()
        ));
        assert!(!steering_supported(
            meta(json!({"steering": true})).as_ref()
        ));
        assert!(!steering_supported(None));

        let steer = RunSteer {
            run_id: uuid::Uuid::new_v4(),
            epoch: 2,
            steer_id: uuid::Uuid::new_v4(),
            text: "Use staging.".into(),
        };
        assert_eq!(
            steer_params("s1", &steer),
            json!({"sessionId": "s1", "prompt": [{"type": "text", "text": "Use staging."}],
                   "_meta": {"steering": {"idleBehavior": "promptRequired"}}})
        );
        let code = |answer: Value| steer_ack(&steer, Ok(&answer)).code;
        assert_eq!(
            steer_ack(&steer, Ok(&json!({"outcome": "injected"}))),
            RunSteerAck::delivered(&steer)
        );
        for outcome in ["promptRequired", "startedNewTurn"] {
            assert_eq!(
                code(json!({"outcome": outcome})).as_deref(),
                Some(steer_code::NOT_RUNNING),
                "{outcome}"
            );
        }
        assert_eq!(
            code(json!({"outcome": "failed"})).as_deref(),
            Some(steer_code::FAILED)
        );
        assert_eq!(code(json!({})).as_deref(), Some(steer_code::FAILED));
        assert_eq!(
            steer_ack(&steer, Err((-32601, "Method not found")))
                .code
                .as_deref(),
            Some(steer_code::NOT_SUPPORTED)
        );
        let failed = steer_ack(&steer, Err((-32603, "boom")));
        assert_eq!(failed.code.as_deref(), Some(steer_code::FAILED));
        assert_eq!(failed.message.as_deref(), Some("boom"));

        assert_eq!(with_steers("P", &[]), "P");
        let other = RunSteer {
            text: "And tests.".into(),
            ..steer.clone()
        };
        assert_eq!(
            with_steers("P", &[steer, other]),
            "P\n\nUse staging.\n\nAnd tests."
        );
    }

    #[test]
    fn prefers_allow_once() {
        let params = json!({"options": [
            {"optionId": "reject", "kind": "reject_once", "name": "No"},
            {"optionId": "always", "kind": "allow_always", "name": "Always"},
            {"optionId": "once", "kind": "allow_once", "name": "Yes"}
        ]});
        assert_eq!(choose_permission_option(&params).as_deref(), Some("once"));
        let params = json!({"options": [{"optionId": "always", "kind": "allow_always"}]});
        assert_eq!(choose_permission_option(&params).as_deref(), Some("always"));
        let params = json!({"options": [{"optionId": "no", "kind": "reject_once"}]});
        assert_eq!(choose_permission_option(&params), None);
    }

    /// The SDK's own queues hold the agent's lines only as long as their budget: a
    /// `session/update` takes its budget to the run loop, and a line the SDK dispatches
    /// nothing for (a response nobody waits for) gives its budget back with the probe behind
    /// it, so nothing stays taken once the SDK has taken everything.
    #[tokio::test]
    async fn the_budget_comes_back_as_the_sdk_takes_the_lines() {
        use futures::SinkExt;
        let total = 64 * 1024;
        let budget = Arc::new(Semaphore::new(total));
        let (line_tx, line_rx) = mpsc::channel(8);
        let held = Held::default();
        let stream = BudgetedLines::new(line_rx, held.clone(), budget.clone());
        let (inbound_tx, mut inbound_rx) = mpsc::unbounded_channel();
        let observer = BudgetObserver {
            held: held.clone(),
            inbound: inbound_tx,
        };
        let outgoing =
            futures::sink::drain::<String>().sink_map_err(|e: std::convert::Infallible| match e {});
        let transport = Lines::new(Box::pin(outgoing), stream);
        let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();
        let connection = Client
            .builder()
            .name("test")
            .with_handler(observer)
            .connect_with(transport, async move |_cx: ConnectionTo<Agent>| {
                let _ = done_rx.await;
                Ok::<(), agent_client_protocol::Error>(())
            });
        tokio::pin!(connection);
        let send = async |text: String| {
            let permit = budget
                .clone()
                .try_acquire_many_owned(line_cost(text.len()))
                .unwrap();
            line_tx.send(Ok(AcpLine { text, permit })).await.unwrap();
        };
        let update = |i: u32| {
            json!({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": "s", "update": {"i": i}}})
                .to_string()
        };
        send(update(1)).await;
        // A response nobody waits for: the SDK takes it and dispatches nothing.
        send(json!({"jsonrpc": "2.0", "id": 99, "result": {}}).to_string()).await;
        // A notification of another method: claimed, not forwarded.
        send(
            json!({"jsonrpc": "2.0", "method": "_other/thing", "params": {"sessionId": "s"}})
                .to_string(),
        )
        .await;
        send(update(2)).await;
        assert!(budget.available_permits() < total);
        let mut updates = Vec::new();
        while updates.len() < 2 {
            tokio::select! {
                item = inbound_rx.recv() => updates.push(item.unwrap()),
                result = &mut connection => panic!("the connection ended: {result:?}"),
            }
        }
        for (i, item) in updates.into_iter().enumerate() {
            let AcpInbound::Update { params, permit } = item else {
                panic!("not an update: {item:?}");
            };
            assert_eq!(params["update"]["i"], json!(i as u32 + 1));
            assert!(permit.is_some(), "the update holds budget");
            drop((params, permit));
        }
        // The SDK took every line: the probe behind them gives the rest back.
        tokio::time::timeout(Duration::from_secs(5), async {
            while budget.available_permits() < total {
                tokio::select! {
                    result = &mut connection => panic!("the connection ended: {result:?}"),
                    () = tokio::time::sleep(Duration::from_millis(5)) => {}
                }
            }
        })
        .await
        .expect("every permit came back");
        assert!(held.lock().unwrap().is_empty());
        done_tx.send(()).unwrap();
        connection.await.unwrap();
        // The transport is gone with the connection: the budget is closed.
        assert!(budget.try_acquire_many_owned(1).is_err());
    }

    /// A tiny response nobody waits for before each large update, again and again, with the
    /// run loop not taking anything: every update reaches the run loop with its own line's
    /// budget (never the tiny line's), so what waits for the loop never exceeds the budget.
    #[tokio::test]
    async fn an_update_holds_its_own_lines_budget() {
        use futures::SinkExt;
        let total = 64 * 1024;
        let budget = Arc::new(Semaphore::new(total));
        let (line_tx, line_rx) = mpsc::channel(8);
        let held = Held::default();
        let stream = BudgetedLines::new(line_rx, held.clone(), budget.clone());
        let (inbound_tx, mut inbound_rx) = mpsc::unbounded_channel();
        let observer = BudgetObserver {
            held: held.clone(),
            inbound: inbound_tx,
        };
        let outgoing =
            futures::sink::drain::<String>().sink_map_err(|e: std::convert::Infallible| match e {});
        let transport = Lines::new(Box::pin(outgoing), stream);
        let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();
        let connection = Client
            .builder()
            .name("test")
            .with_handler(observer)
            .connect_with(transport, async move |_cx: ConnectionTo<Agent>| {
                let _ = done_rx.await;
                Ok::<(), agent_client_protocol::Error>(())
            });
        tokio::pin!(connection);
        // An update costs a quarter of the budget; a response far less.
        let update_line = |i: u32, pad: &str| {
            json!({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": "s", "update": {"i": i, "pad": pad}}})
                .to_string()
        };
        let pad = "u".repeat(16 * 1024 - ACP_LINE_OVERHEAD - update_line(0, "").len());
        let update = move |i: u32| update_line(i, &pad);
        assert_eq!(line_cost(update(0).len()) as usize, 16 * 1024);
        let count = 12u32;
        // The agent: feeds the lines as the splitter would, waiting for the budget.
        let producer = tokio::spawn({
            let budget = budget.clone();
            let update = update.clone();
            async move {
                for i in 0..count {
                    for text in [
                        json!({"jsonrpc": "2.0", "id": 1000 + i, "result": {}}).to_string(),
                        update(i),
                    ] {
                        let permit = budget
                            .clone()
                            .acquire_many_owned(line_cost(text.len()))
                            .await
                            .unwrap();
                        line_tx.send(Ok(AcpLine { text, permit })).await.unwrap();
                    }
                }
            }
        });
        // The run loop takes nothing: the SDK dispatches what the budget admits, the
        // responses' budget comes back with their probes, and the producer stops at the
        // budget's worth of updates.
        tokio::select! {
            result = &mut connection => panic!("the connection ended: {result:?}"),
            () = tokio::time::sleep(Duration::from_millis(300)) => {}
        }
        assert!(!producer.is_finished(), "the producer waits for the budget");
        let mut queued = Vec::new();
        while let Ok(item) = inbound_rx.try_recv() {
            queued.push(item);
        }
        let queued_cost: usize = queued
            .iter()
            .map(|item| match item {
                AcpInbound::Update { permit, params } => permit
                    .as_ref()
                    .unwrap_or_else(|| panic!("no permit on update {}", params["update"]["i"]))
                    .num_permits(),
                other => panic!("not an update: {other:?}"),
            })
            .sum();
        assert!(
            queued_cost <= total && queued.len() >= 3,
            "{} updates waiting, {queued_cost} of the budget",
            queued.len()
        );
        // Each update holds exactly its own line's budget, and in order; the rest follow as
        // the loop takes them.
        let check = |item: AcpInbound, expected: u32| {
            let AcpInbound::Update { params, permit } = item else {
                panic!("not an update: {item:?}");
            };
            assert_eq!(params["update"]["i"], json!(expected));
            assert_eq!(
                permit.unwrap().num_permits(),
                line_cost(update(expected).len()) as usize
            );
        };
        let mut next = 0u32;
        for item in queued {
            check(item, next);
            next += 1;
        }
        while next < count {
            tokio::select! {
                item = inbound_rx.recv() => check(item.unwrap(), next),
                result = &mut connection => panic!("the connection ended: {result:?}"),
            }
            next += 1;
        }
        producer.await.unwrap();
        tokio::time::timeout(Duration::from_secs(5), async {
            while budget.available_permits() < total {
                tokio::select! {
                    result = &mut connection => panic!("the connection ended: {result:?}"),
                    () = tokio::time::sleep(Duration::from_millis(5)) => {}
                }
            }
        })
        .await
        .expect("every permit came back");
        assert!(held.lock().unwrap().is_empty());
        done_tx.send(()).unwrap();
        connection.await.unwrap();
    }
}
