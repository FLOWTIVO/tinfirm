//! Maps ACP `session/update` notifications to `run.event` payloads
//! (docs/archive/v1/CONVENTIONS.md §15.4 steps 6, 7 and 9).
//!
//! The mapper works on the raw JSON of the notification, so a newer adapter that sends an
//! update kind or content block this host does not know yet degrades to "ignored" instead
//! of breaking the run. It also:
//! - coalesces message and thought chunks, flushing at least every 250 ms;
//! - with a redactor ([`EventMapper::with_redactor`]), redacts before it truncates or cuts
//!   (L26 of the security review of 2026-10-27): tool input, tool output, diffs and the
//!   summary are redacted whole and then truncated, and a text stream that goes out in
//!   pieces keeps its last [`Redactor::hold_back`] bytes back until the next piece or the end
//!   of the message, so that no credential is split across two events where neither part
//!   would match; a credential it replaces is a leak ([`EventMapper::take_leak`]);
//! - truncates tool input strings (4 KiB), tool output (16 KiB) and diffs;
//! - tracks the guards that depend on tool calls (count and repeated identical calls);
//! - reads the Claude rate-limit extension `_meta["_claude/rateLimit"]`;
//! - keeps cumulative token totals for the run (see [`crate::usage`]);
//! - remembers the agent's last message, which becomes the run summary.

use std::collections::HashMap;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::sync::Arc;
use std::time::{Duration, Instant};

use chrono::{DateTime, Utc};
use serde_json::{Value, json};
use tinfirm_protocol::{
    DiffPayload, EventPayload, Location, PlanEntry, PlanEntryPriority, PlanEntryStatus,
    PlanPayload, RateLimitPayload, RateLimitStatus, TextPayload, ToolCallPayload, ToolCallStatus,
    ToolCallUpdatePayload, ToolKind, UsagePayload,
};

use crate::redact::Redactor;
use crate::usage::{UsageAccumulator, UsageMode};
use crate::util::{
    DIFF_TEXT_MAX, SUMMARY_MAX, TEXT_EVENT_MAX, TOOL_INPUT_STRING_MAX, TOOL_OUTPUT_MAX,
    floor_char_boundary, truncate_json_strings, truncate_with_marker, unix_seconds,
};

/// Text chunks are flushed at least this often.
pub const COALESCE_INTERVAL: Duration = Duration::from_millis(250);
/// The bytes a stream keeps back go out when no chunk came for this long: a message that
/// stops in the middle (the agent waits) is not held until its next event.
pub const HELD_TEXT_RELEASE: Duration = Duration::from_secs(1);
/// Identical consecutive tool calls that stop the run.
pub const REPEATED_TOOL_CALL_LIMIT: usize = 5;

/// Something the run loop must act on.
#[derive(Debug, Clone, PartialEq)]
pub enum Signal {
    /// A guard tripped; the message says which.
    Guard(String),
    /// The vendor rejected the request because the plan limit is reached.
    UsageLimit {
        resets_at: Option<DateTime<Utc>>,
        window: Option<String>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TextKind {
    Message,
    Thought,
}

#[derive(Debug)]
struct Pending {
    kind: TextKind,
    text: String,
    since: Instant,
    /// When the last chunk arrived.
    last_chunk: Instant,
}

/// Where the mapper gets the run's current redactor.
pub type RedactorSource = Arc<dyn Fn() -> Arc<Redactor> + Send + Sync>;

/// Redaction before truncation, and whether it replaced a credential.
#[derive(Default)]
struct Scrub {
    source: Option<RedactorSource>,
    leak: bool,
}

impl std::fmt::Debug for Scrub {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Scrub")
            .field("redacts", &self.source.is_some())
            .field("leak", &self.leak)
            .finish()
    }
}

impl Scrub {
    /// `text` redacted; a credential found is recorded as a leak.
    fn text(&mut self, text: &str) -> String {
        let mut out = text.to_string();
        if let Some(source) = &self.source {
            self.leak |= source().redact_string(&mut out).credential;
        }
        out
    }

    /// Redacts every string and key of `value`; a credential found is recorded as a leak.
    fn value(&mut self, value: &mut Value) {
        if let Some(source) = &self.source {
            self.leak |= source().redact_json(value).credential;
        }
    }

    /// `text` redacted, without recording anything.
    fn quiet(&self, text: &str) -> String {
        match &self.source {
            Some(source) => source().redact(text).into_owned(),
            None => text.to_string(),
        }
    }

    /// The bytes a text stream keeps back between pieces (none without a redactor).
    fn hold_back(&self) -> usize {
        self.source
            .as_ref()
            .map_or(0, |source| source().hold_back())
    }
}

#[derive(Debug, Default)]
struct ToolState {
    kind: Option<ToolKind>,
    title: String,
    status: Option<ToolCallStatus>,
    identity: Option<u64>,
}

#[derive(Debug)]
pub struct EventMapper {
    max_turns: u32,
    repeat_limit: usize,
    active: bool,
    pending: Option<Pending>,
    current_message: String,
    last_message: String,
    tools: HashMap<String, ToolState>,
    tool_order: Vec<String>,
    tool_calls: u32,
    last_usage: Option<UsagePayload>,
    last_rate_limit: Option<RateLimitPayload>,
    usage: UsageAccumulator,
    last_resets_at: Option<DateTime<Utc>>,
    last_window: Option<String>,
    scrub: Scrub,
    /// The last [`Redactor::hold_back`] bytes sent of the current text stream, and its kind:
    /// a credential that starts there and ends in the next chunk (a stream released after a
    /// pause) is still found, redacted in what is left of it, and a leak.
    sent_tail: Option<(TextKind, String)>,
}

impl EventMapper {
    pub fn new(max_turns: u32, usage_mode: UsageMode) -> EventMapper {
        EventMapper {
            max_turns,
            repeat_limit: REPEATED_TOOL_CALL_LIMIT,
            active: false,
            pending: None,
            current_message: String::new(),
            last_message: String::new(),
            tools: HashMap::new(),
            tool_order: Vec::new(),
            tool_calls: 0,
            last_usage: None,
            last_rate_limit: None,
            usage: UsageAccumulator::new(usage_mode),
            last_resets_at: None,
            last_window: None,
            scrub: Scrub::default(),
            sent_tail: None,
        }
    }

    /// Redacts with the run's redactor before anything is truncated or cut (see the module
    /// docs). Without one, the events are cut as they come and the event sink redacts them.
    pub fn with_redactor(mut self, source: RedactorSource) -> EventMapper {
        self.scrub.source = Some(source);
        self
    }

    /// `true` once when the mapper's own redaction replaced a credential since the last call:
    /// a credential in the agent's output, as when the event sink finds one.
    pub fn take_leak(&mut self) -> bool {
        std::mem::take(&mut self.scrub.leak)
    }

    /// Updates before this call are the history replay of `session/load` and are ignored.
    pub fn start_prompt(&mut self) {
        self.active = true;
    }

    /// The agent that `initialize` named reports usage in `mode` ([`UsageMode::for_agent`]).
    pub fn set_usage_mode(&mut self, mode: UsageMode) {
        self.usage.set_mode(mode);
    }

    /// The run resumed the previous run's session (`session/load`).
    pub fn session_resumed(&mut self) {
        self.usage.on_session_resumed();
    }

    pub fn tool_calls(&self) -> u32 {
        self.tool_calls
    }

    /// Cumulative usage of the run so far, if the agent reported any.
    pub fn usage(&self) -> Option<UsagePayload> {
        self.usage.has_data().then(|| self.usage.snapshot())
    }

    /// The prompt turn ended; `usage` is `PromptResponse.usage` (or its `_meta` fallback).
    /// Emits the final cumulative USAGE event if the totals changed.
    pub fn finish_prompt(&mut self, usage: Option<&Value>, out: &mut Vec<EventPayload>) {
        self.flush(out);
        self.usage.on_prompt_response(usage);
        self.emit_usage(out);
    }

    fn emit_usage(&mut self, out: &mut Vec<EventPayload>) {
        if !self.usage.has_data() {
            return;
        }
        let payload = self.usage.snapshot();
        if self.last_usage.as_ref() != Some(&payload) {
            self.flush(out);
            self.last_usage = Some(payload.clone());
            out.push(EventPayload::Usage(payload));
        }
    }

    /// The last reset time and window seen in any rate-limit update.
    pub fn last_rate_limit_reset(&self) -> (Option<DateTime<Utc>>, Option<String>) {
        (self.last_resets_at, self.last_window.clone())
    }

    /// The agent's last message (Markdown), redacted, then cut to at most 64 KiB.
    pub fn summary(&self) -> Option<String> {
        let current = self.current_message.trim();
        let text = if !current.is_empty() {
            current
        } else {
            self.last_message.trim()
        };
        (!text.is_empty()).then(|| truncate_with_marker(&self.scrub.quiet(text), SUMMARY_MAX))
    }

    /// Handles the params of one `session/update` notification.
    pub fn handle_update(
        &mut self,
        params: &Value,
        now: Instant,
        out: &mut Vec<EventPayload>,
    ) -> Vec<Signal> {
        let mut signals = Vec::new();
        if !self.active {
            return signals;
        }
        let Some(update) = params.get("update") else {
            return signals;
        };
        for meta in [update.get("_meta"), params.get("_meta")]
            .into_iter()
            .flatten()
        {
            if let Some(rate_limit) = meta.get("_claude/rateLimit") {
                self.flush(out);
                self.handle_rate_limit(rate_limit, out, &mut signals);
            }
        }
        match update.get("sessionUpdate").and_then(Value::as_str) {
            Some("agent_message_chunk") => {
                self.usage.on_activity();
                if let Some(text) = content_text(update.get("content")) {
                    self.push_text(TextKind::Message, &text, now, out);
                }
            }
            Some("agent_thought_chunk") => {
                self.usage.on_activity();
                if let Some(text) = content_text(update.get("content")) {
                    self.push_text(TextKind::Thought, &text, now, out);
                }
            }
            Some("tool_call") => {
                self.usage.on_activity();
                self.flush(out);
                self.end_message();
                self.handle_tool_call(update, out, &mut signals);
            }
            Some("tool_call_update") => {
                self.usage.on_activity();
                self.flush(out);
                self.handle_tool_call_update(update, out, &mut signals);
            }
            Some("plan") => {
                self.flush(out);
                self.handle_plan(update, out);
            }
            Some("usage_update") => {
                self.handle_usage(update, out);
            }
            _ => {}
        }
        signals
    }

    /// Flushes buffered text if it is older than the coalescing interval, but for its last
    /// [`Redactor::hold_back`] bytes, which wait for the next chunk or the end of the message,
    /// or [`HELD_TEXT_RELEASE`] without a chunk.
    pub fn flush_due(&mut self, now: Instant, out: &mut Vec<EventPayload>) {
        let Some(pending) = self.pending.as_ref() else {
            return;
        };
        if now.duration_since(pending.last_chunk) >= HELD_TEXT_RELEASE {
            self.flush_text(None, out);
        } else if now.duration_since(pending.since) >= COALESCE_INTERVAL {
            self.flush_text(Some(now), out);
        }
    }

    /// Flushes buffered text now, all of it: the message (or thought) has ended.
    pub fn flush(&mut self, out: &mut Vec<EventPayload>) {
        self.flush_text(None, out);
    }

    /// Redacts the buffered text, then sends it. `keep_from` (a flush in the middle of a
    /// stream) keeps the last [`Redactor::hold_back`] bytes buffered, as of that instant.
    fn flush_text(&mut self, keep_from: Option<Instant>, out: &mut Vec<EventPayload>) {
        let Some(mut pending) = self.pending.take() else {
            return;
        };
        let kind = pending.kind;
        let hold = self.scrub.hold_back();
        // Redact what was sent last of this stream with the new text: a credential that began
        // in a released tail is found too. What was sent stays sent; the rest is redacted.
        let before = match self.sent_tail.take() {
            Some((tail_kind, tail)) if tail_kind == kind => tail,
            _ => String::new(),
        };
        let joined = self.scrub.text(&format!("{before}{}", pending.text));
        let start = if joined.starts_with(&before) {
            before.len()
        } else {
            common_prefix(&joined, &before)
        };
        let mut text = joined[start..].to_string();
        if let Some(now) = keep_from {
            let cut = if hold >= text.len() {
                0
            } else {
                floor_char_boundary(&text, text.len() - hold)
            };
            let tail = text.split_off(cut);
            if !tail.is_empty() {
                pending.text = tail;
                pending.since = now;
                self.pending = Some(pending);
            }
        }
        if hold > 0 {
            let mut sent = format!("{}{text}", &joined[..start]);
            let keep = floor_char_boundary(&sent, sent.len().saturating_sub(hold));
            sent.drain(..keep);
            self.sent_tail = Some((kind, sent));
        }
        // Large texts go out in pieces of at most TEXT_EVENT_MAX bytes (frames stay small).
        let mut rest = text.as_str();
        while !rest.is_empty() {
            let mut cut = floor_char_boundary(rest, TEXT_EVENT_MAX);
            if cut == 0 {
                cut = rest.len();
            }
            let payload = TextPayload {
                text: rest[..cut].to_string(),
            };
            out.push(match kind {
                TextKind::Message => EventPayload::AgentMessage(payload),
                TextKind::Thought => EventPayload::AgentThought(payload),
            });
            rest = &rest[cut..];
        }
    }

    fn push_text(&mut self, kind: TextKind, text: &str, now: Instant, out: &mut Vec<EventPayload>) {
        if text.is_empty() {
            return;
        }
        if self.pending.as_ref().is_some_and(|p| p.kind != kind) {
            self.flush(out);
        }
        if kind == TextKind::Message {
            self.current_message.push_str(text);
        }
        let pending = self.pending.get_or_insert_with(|| Pending {
            kind,
            text: String::new(),
            since: now,
            last_chunk: now,
        });
        pending.last_chunk = now;
        pending.text.push_str(text);
        if pending.text.len() >= TEXT_EVENT_MAX {
            self.flush_text(Some(now), out);
        }
    }

    fn end_message(&mut self) {
        if !self.current_message.trim().is_empty() {
            self.last_message = std::mem::take(&mut self.current_message);
        } else {
            self.current_message.clear();
        }
    }

    fn handle_tool_call(
        &mut self,
        update: &Value,
        out: &mut Vec<EventPayload>,
        signals: &mut Vec<Signal>,
    ) {
        let Some(id) = update.get("toolCallId").and_then(Value::as_str) else {
            return;
        };
        let title = update
            .get("title")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let kind = update
            .get("kind")
            .and_then(Value::as_str)
            .map(ToolKind::from_acp)
            .unwrap_or(ToolKind::Other);
        let status = update
            .get("status")
            .and_then(Value::as_str)
            .and_then(ToolCallStatus::from_acp)
            .unwrap_or(ToolCallStatus::Pending);
        let raw_input = update.get("rawInput").filter(|v| !v.is_null());
        let identity = raw_input.map(|input| tool_identity(kind, &title, input));

        if !self.tools.contains_key(id) {
            self.tool_calls += 1;
            self.tool_order.push(id.to_string());
        }
        self.tools.insert(
            id.to_string(),
            ToolState {
                kind: Some(kind),
                title: title.clone(),
                status: Some(status),
                identity,
            },
        );

        out.push(EventPayload::ToolCall(ToolCallPayload {
            tool_call_id: id.to_string(),
            title,
            kind,
            status,
            input: raw_input.map(|input| tool_input(input, &mut self.scrub)),
            locations: locations(update.get("locations")),
        }));
        let (output, diffs) = tool_content(update, &mut self.scrub);
        if output.is_some() || !diffs.is_empty() {
            out.push(EventPayload::ToolCallUpdate(ToolCallUpdatePayload {
                tool_call_id: id.to_string(),
                status: None,
                title: None,
                output,
                diffs: (!diffs.is_empty()).then_some(diffs),
            }));
        }
        self.check_tool_guards(signals);
    }

    fn handle_tool_call_update(
        &mut self,
        update: &Value,
        out: &mut Vec<EventPayload>,
        signals: &mut Vec<Signal>,
    ) {
        let Some(id) = update.get("toolCallId").and_then(Value::as_str) else {
            return;
        };
        if !self.tools.contains_key(id) {
            // An update for a call we never saw still counts as a tool call.
            self.tool_calls += 1;
            self.tool_order.push(id.to_string());
            self.tools.insert(id.to_string(), ToolState::default());
        }
        let state = self.tools.get_mut(id).expect("inserted above");
        let status = update
            .get("status")
            .and_then(Value::as_str)
            .and_then(ToolCallStatus::from_acp);
        let title = update
            .get("title")
            .and_then(Value::as_str)
            .map(str::to_string);
        if let Some(kind) = update.get("kind").and_then(Value::as_str) {
            state.kind = Some(ToolKind::from_acp(kind));
        }
        let mut identity_changed = false;
        if let Some(input) = update.get("rawInput").filter(|v| !v.is_null()) {
            let new_title = title.clone().unwrap_or_else(|| state.title.clone());
            state.identity = Some(tool_identity(
                state.kind.unwrap_or(ToolKind::Other),
                &new_title,
                input,
            ));
            identity_changed = true;
        }
        let status_changed = status.is_some() && status != state.status;
        let title_changed = title.as_ref().is_some_and(|t| *t != state.title);
        if let Some(s) = status {
            state.status = Some(s);
        }
        if let Some(t) = &title {
            state.title = t.clone();
        }
        let (output, diffs) = tool_content(update, &mut self.scrub);
        if status_changed || title_changed || output.is_some() || !diffs.is_empty() {
            out.push(EventPayload::ToolCallUpdate(ToolCallUpdatePayload {
                tool_call_id: id.to_string(),
                status: status.filter(|_| status_changed),
                title: title.filter(|_| title_changed),
                output,
                diffs: (!diffs.is_empty()).then_some(diffs),
            }));
        }
        if identity_changed {
            self.check_tool_guards(signals);
        }
    }

    fn check_tool_guards(&self, signals: &mut Vec<Signal>) {
        if self.tool_calls > self.max_turns {
            signals.push(Signal::Guard(format!(
                "the agent made more than {} tool calls (maxTurns)",
                self.max_turns
            )));
        }
        let mut streak = 0usize;
        let mut last: Option<u64> = None;
        for id in self.tool_order.iter().rev() {
            match self.tools.get(id).and_then(|t| t.identity) {
                Some(identity) if last.is_none() || last == Some(identity) => {
                    last = Some(identity);
                    streak += 1;
                }
                _ => break,
            }
        }
        if streak >= self.repeat_limit {
            signals.push(Signal::Guard(format!(
                "the agent repeated the same tool call {streak} times in a row"
            )));
        }
    }

    fn handle_plan(&mut self, update: &Value, out: &mut Vec<EventPayload>) {
        let entries = update
            .get("entries")
            .and_then(Value::as_array)
            .map(|entries| {
                entries
                    .iter()
                    .filter_map(|e| {
                        let content = e.get("content")?.as_str()?.to_string();
                        let status = match e.get("status").and_then(Value::as_str) {
                            Some("in_progress") => PlanEntryStatus::InProgress,
                            Some("completed") => PlanEntryStatus::Completed,
                            _ => PlanEntryStatus::Pending,
                        };
                        let priority = match e.get("priority").and_then(Value::as_str) {
                            Some("high") => Some(PlanEntryPriority::High),
                            Some("medium") => Some(PlanEntryPriority::Medium),
                            Some("low") => Some(PlanEntryPriority::Low),
                            _ => None,
                        };
                        Some(PlanEntry {
                            content,
                            status,
                            priority,
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();
        out.push(EventPayload::Plan(PlanPayload { entries }));
    }

    fn handle_usage(&mut self, update: &Value, out: &mut Vec<EventPayload>) {
        self.usage.on_usage_update(update);
        self.emit_usage(out);
    }

    fn handle_rate_limit(
        &mut self,
        info: &Value,
        out: &mut Vec<EventPayload>,
        signals: &mut Vec<Signal>,
    ) {
        let status = match info.get("status").and_then(Value::as_str) {
            Some("allowed") => RateLimitStatus::Allowed,
            Some("allowed_warning") => RateLimitStatus::AllowedWarning,
            Some("rejected") => RateLimitStatus::Rejected,
            _ => return,
        };
        let utilization = info.get("utilization").and_then(Value::as_f64).map(|u| {
            let fraction = if u > 1.0 { u / 100.0 } else { u };
            fraction.clamp(0.0, 1.0)
        });
        let resets_at = info.get("resetsAt").and_then(unix_seconds);
        let window = info
            .get("rateLimitType")
            .or_else(|| info.get("window"))
            .and_then(Value::as_str)
            .map(str::to_string);
        if resets_at.is_some() {
            self.last_resets_at = resets_at;
            self.last_window = window.clone();
        }
        let payload = RateLimitPayload {
            status,
            utilization,
            resets_at,
            window: window.clone(),
        };
        if self.last_rate_limit.as_ref() != Some(&payload) {
            self.last_rate_limit = Some(payload.clone());
            out.push(EventPayload::RateLimit(payload));
        }
        if status == RateLimitStatus::Rejected {
            signals.push(Signal::UsageLimit { resets_at, window });
        }
    }
}

/// The length of the longest common prefix of `a` and `b`, on a character boundary.
fn common_prefix(a: &str, b: &str) -> usize {
    let mut len = a.bytes().zip(b.bytes()).take_while(|(x, y)| x == y).count();
    while !a.is_char_boundary(len) {
        len -= 1;
    }
    len
}

/// Stable identity of a tool call for the repeated-call guard.
fn tool_identity(kind: ToolKind, title: &str, input: &Value) -> u64 {
    let mut hasher = DefaultHasher::new();
    format!("{kind:?}").hash(&mut hasher);
    title.hash(&mut hasher);
    // serde_json maps are sorted, so this is canonical.
    input.to_string().hash(&mut hasher);
    hasher.finish()
}

/// The tool's input as the event carries it: redacted, then each string cut to 4 KiB.
fn tool_input(input: &Value, scrub: &mut Scrub) -> Value {
    let mut value = if input.is_object() {
        input.clone()
    } else {
        json!({ "value": input })
    };
    scrub.value(&mut value);
    truncate_json_strings(&mut value, TOOL_INPUT_STRING_MAX);
    value
}

fn locations(value: Option<&Value>) -> Option<Vec<Location>> {
    let items = value?.as_array()?;
    let out: Vec<Location> = items
        .iter()
        .filter_map(|l| {
            Some(Location {
                path: l.get("path")?.as_str()?.to_string(),
                line: l.get("line").and_then(Value::as_u64),
            })
        })
        .collect();
    (!out.is_empty()).then_some(out)
}

/// Text of an ACP `ContentBlock`, if it is text.
fn content_text(block: Option<&Value>) -> Option<String> {
    let block = block?;
    match block.get("type").and_then(Value::as_str) {
        Some("text") => block
            .get("text")
            .and_then(Value::as_str)
            .map(str::to_string),
        Some("resource_link") => block
            .get("uri")
            .and_then(Value::as_str)
            .map(|uri| format!("[{uri}]")),
        _ => None,
    }
}

/// Output text and diffs of a tool call (from `content`, falling back to `rawOutput`),
/// redacted, then cut.
fn tool_content(update: &Value, scrub: &mut Scrub) -> (Option<String>, Vec<DiffPayload>) {
    let mut texts = Vec::new();
    let mut diffs = Vec::new();
    if let Some(items) = update.get("content").and_then(Value::as_array) {
        for item in items {
            match item.get("type").and_then(Value::as_str) {
                Some("content") => {
                    if let Some(text) = content_text(item.get("content")) {
                        texts.push(text);
                    }
                }
                Some("diff") => {
                    if let (Some(path), Some(new_text)) = (
                        item.get("path").and_then(Value::as_str),
                        item.get("newText").and_then(Value::as_str),
                    ) {
                        diffs.push(DiffPayload {
                            path: path.to_string(),
                            old_text: item
                                .get("oldText")
                                .and_then(Value::as_str)
                                .map(|t| truncate_with_marker(&scrub.text(t), DIFF_TEXT_MAX)),
                            new_text: truncate_with_marker(&scrub.text(new_text), DIFF_TEXT_MAX),
                        });
                    }
                }
                _ => {}
            }
        }
    }
    if texts.is_empty()
        && let Some(raw) = update.get("rawOutput").filter(|v| !v.is_null())
    {
        let text = match raw {
            Value::String(s) => s.clone(),
            other => other
                .get("output")
                .or_else(|| other.get("stdout"))
                .and_then(Value::as_str)
                .map(str::to_string)
                .unwrap_or_else(|| other.to_string()),
        };
        if !text.is_empty() {
            texts.push(text);
        }
    }
    let output = (!texts.is_empty())
        .then(|| truncate_with_marker(&scrub.text(&texts.join("\n")), TOOL_OUTPUT_MAX));
    (output, diffs)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn upd(update: Value) -> Value {
        json!({"sessionId": "s", "update": update})
    }

    fn chunk(text: &str) -> Value {
        upd(
            json!({"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": text}}),
        )
    }

    const LEAKED: &str = "glpat-TEST-ONLY-0123456789abcdefghij";

    fn redacting(max_turns: u32) -> EventMapper {
        let redactor = Arc::new(Redactor::new([LEAKED]));
        EventMapper::new(max_turns, UsageMode::TurnTotals)
            .with_redactor(Arc::new(move || redactor.clone()))
    }

    fn texts(out: &[EventPayload]) -> String {
        out.iter()
            .filter_map(|e| match e {
                EventPayload::AgentMessage(t) => Some(t.text.as_str()),
                _ => None,
            })
            .collect()
    }

    /// L26 (security review of 2026-10-27): a credential split across two chunks, with a
    /// flush between them, is redacted whole and counts as a leak.
    #[test]
    fn a_credential_split_across_chunks_is_redacted_whole() {
        let mut m = redacting(10);
        m.start_prompt();
        let mut out = Vec::new();
        let t0 = Instant::now();
        let (head, tail) = LEAKED.split_at(15);
        m.handle_update(&chunk(&format!("The token is {head}")), t0, &mut out);
        // The coalescing interval passes in the middle of the token.
        m.flush_due(t0 + Duration::from_millis(300), &mut out);
        for event in &out {
            if let EventPayload::AgentMessage(t) = event {
                assert!(!t.text.contains(&head[..8]), "{t:?}");
            }
        }
        m.handle_update(&chunk(&format!("{tail} and that is all.")), t0, &mut out);
        m.flush(&mut out);
        assert_eq!(texts(&out), "The token is [REDACTED] and that is all.");
        assert!(m.take_leak());
        assert!(!m.take_leak(), "reported once");
        // A long stream still goes out while it streams, all but its last bytes.
        let mut m = redacting(10);
        m.start_prompt();
        let mut out = Vec::new();
        m.handle_update(&chunk(&"word ".repeat(200)), t0, &mut out);
        m.flush_due(t0 + Duration::from_millis(300), &mut out);
        let sent = texts(&out).len();
        assert!(sent > 0 && sent <= 1000 - 255, "{sent}");
        m.flush(&mut out);
        assert_eq!(texts(&out), "word ".repeat(200));
        assert!(!m.take_leak());

        // A stream that pauses releases what it held after a second; a credential that began
        // there is still found when it ends, redacted in the rest and a leak.
        let mut m = redacting(10);
        m.start_prompt();
        let mut out = Vec::new();
        m.handle_update(&chunk(&format!("The token is {head}")), t0, &mut out);
        m.flush_due(t0 + Duration::from_millis(300), &mut out);
        assert!(out.is_empty());
        m.flush_due(t0 + HELD_TEXT_RELEASE, &mut out);
        assert_eq!(texts(&out), format!("The token is {head}"));
        assert!(!m.take_leak());
        let later = t0 + Duration::from_secs(2);
        m.handle_update(&chunk(&format!("{tail} and that is all.")), later, &mut out);
        m.flush(&mut out);
        assert_eq!(
            texts(&out),
            format!("The token is {head}[REDACTED] and that is all.")
        );
        assert!(m.take_leak());
    }

    /// L26: tool input, output and diffs are redacted before they are truncated, so a cut
    /// never leaves a part of a credential behind.
    #[test]
    fn tool_text_is_redacted_before_it_is_truncated() {
        let mut m = redacting(10);
        m.start_prompt();
        let mut out = Vec::new();
        let now = Instant::now();
        let input = format!("{}{LEAKED}", "a".repeat(TOOL_INPUT_STRING_MAX - 12));
        let output = format!("{}{LEAKED}", "b".repeat(TOOL_OUTPUT_MAX - 12));
        let diff = format!("{}{LEAKED}", "c".repeat(DIFF_TEXT_MAX - 12));
        m.handle_update(
            &upd(json!({
                "sessionUpdate": "tool_call", "toolCallId": "t1", "title": "Run", "kind": "execute",
                "status": "completed", "rawInput": {"command": input},
                "content": [
                    {"type": "content", "content": {"type": "text", "text": output}},
                    {"type": "diff", "path": "/workspace/a.txt", "newText": diff}
                ]
            })),
            now,
            &mut out,
        );
        let all = serde_json::to_string(&out).unwrap();
        assert!(!all.contains(&LEAKED[..10]), "{all}");
        assert!(all.contains("[REDACTED]"));
        assert!(m.take_leak());
    }

    #[test]
    fn ignores_replay_until_prompt_starts() {
        let mut m = EventMapper::new(10, UsageMode::TurnTotals);
        let mut out = Vec::new();
        m.handle_update(&chunk("history"), Instant::now(), &mut out);
        m.flush(&mut out);
        assert!(out.is_empty());
        assert_eq!(m.summary(), None);
    }

    #[test]
    fn coalesces_text_every_250ms() {
        let mut m = EventMapper::new(10, UsageMode::TurnTotals);
        m.start_prompt();
        let mut out = Vec::new();
        let t0 = Instant::now();
        m.handle_update(&chunk("Hello "), t0, &mut out);
        m.handle_update(&chunk("world"), t0 + Duration::from_millis(100), &mut out);
        m.flush_due(t0 + Duration::from_millis(200), &mut out);
        assert!(out.is_empty());
        m.flush_due(t0 + Duration::from_millis(260), &mut out);
        assert_eq!(
            out,
            vec![EventPayload::AgentMessage(TextPayload {
                text: "Hello world".into()
            })]
        );
        // A thought flushes the pending message first.
        out.clear();
        m.handle_update(&chunk("a"), t0, &mut out);
        m.handle_update(
            &upd(json!({"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "hmm"}})),
            t0,
            &mut out,
        );
        m.flush(&mut out);
        assert_eq!(out.len(), 2);
        assert!(matches!(out[1], EventPayload::AgentThought(_)));
    }

    #[test]
    fn maps_tool_calls_and_summary() {
        let mut m = EventMapper::new(10, UsageMode::TurnTotals);
        m.start_prompt();
        let mut out = Vec::new();
        let now = Instant::now();
        m.handle_update(&chunk("Before tools."), now, &mut out);
        m.handle_update(
            &upd(json!({
                "sessionUpdate": "tool_call", "toolCallId": "t1", "title": "Write a.txt",
                "kind": "edit", "status": "pending",
                "rawInput": {"path": "a.txt", "content": "x".repeat(5000)},
                "locations": [{"path": "/workspace/a.txt"}]
            })),
            now,
            &mut out,
        );
        m.handle_update(
            &upd(json!({
                "sessionUpdate": "tool_call_update", "toolCallId": "t1", "status": "completed",
                "content": [
                    {"type": "diff", "path": "/workspace/a.txt", "oldText": null, "newText": "x"},
                    {"type": "content", "content": {"type": "text", "text": "ok"}}
                ]
            })),
            now,
            &mut out,
        );
        // A progress-only update is not an event.
        m.handle_update(
            &upd(
                json!({"sessionUpdate": "tool_call_update", "toolCallId": "t1", "_meta": {"x": 1}}),
            ),
            now,
            &mut out,
        );
        m.handle_update(&chunk("Done: wrote a.txt."), now, &mut out);
        m.flush(&mut out);
        let types: Vec<&str> = out.iter().map(|e| e.event_type()).collect();
        assert_eq!(
            types,
            vec![
                "AGENT_MESSAGE",
                "TOOL_CALL",
                "TOOL_CALL_UPDATE",
                "AGENT_MESSAGE"
            ]
        );
        match &out[1] {
            EventPayload::ToolCall(tc) => {
                let content = tc.input.as_ref().unwrap()["content"].as_str().unwrap();
                assert!(content.len() < 4200 && content.contains("truncated"));
                assert_eq!(tc.kind, ToolKind::Edit);
                assert_eq!(tc.locations.as_ref().unwrap()[0].path, "/workspace/a.txt");
            }
            other => panic!("unexpected {other:?}"),
        }
        match &out[2] {
            EventPayload::ToolCallUpdate(u) => {
                assert_eq!(u.status, Some(ToolCallStatus::Completed));
                assert_eq!(u.output.as_deref(), Some("ok"));
                assert_eq!(u.diffs.as_ref().unwrap().len(), 1);
            }
            other => panic!("unexpected {other:?}"),
        }
        assert_eq!(m.summary().as_deref(), Some("Done: wrote a.txt."));
        assert_eq!(m.tool_calls(), 1);
    }

    #[test]
    fn repeated_tool_calls_trip_the_guard() {
        let mut m = EventMapper::new(100, UsageMode::TurnTotals);
        m.start_prompt();
        let mut out = Vec::new();
        let mut tripped = false;
        for i in 0..5 {
            let signals = m.handle_update(
                &upd(json!({
                    "sessionUpdate": "tool_call", "toolCallId": format!("t{i}"),
                    "title": "Read file.txt", "kind": "read", "rawInput": {"path": "file.txt"}
                })),
                Instant::now(),
                &mut out,
            );
            tripped = signals.iter().any(|s| matches!(s, Signal::Guard(_)));
            assert_eq!(tripped, i == 4, "iteration {i}");
        }
        assert!(tripped);
        // A different call breaks the streak.
        let signals = m.handle_update(
            &upd(json!({"sessionUpdate": "tool_call", "toolCallId": "x", "title": "Read other", "kind": "read", "rawInput": {"path": "o"}})),
            Instant::now(),
            &mut out,
        );
        assert!(signals.is_empty());
    }

    #[test]
    fn calls_without_input_do_not_count_as_identical() {
        let mut m = EventMapper::new(100, UsageMode::TurnTotals);
        m.start_prompt();
        let mut out = Vec::new();
        for i in 0..6 {
            let signals = m.handle_update(
                &upd(json!({"sessionUpdate": "tool_call", "toolCallId": format!("t{i}"), "title": "Bash"})),
                Instant::now(),
                &mut out,
            );
            assert!(signals.is_empty());
        }
    }

    #[test]
    fn max_turns_trips_the_guard() {
        let mut m = EventMapper::new(2, UsageMode::TurnTotals);
        m.start_prompt();
        let mut out = Vec::new();
        let mut last = Vec::new();
        for i in 0..3 {
            last = m.handle_update(
                &upd(json!({"sessionUpdate": "tool_call", "toolCallId": format!("t{i}"), "title": format!("step {i}"), "rawInput": {"i": i}})),
                Instant::now(),
                &mut out,
            );
        }
        assert!(matches!(&last[..], [Signal::Guard(msg)] if msg.contains("maxTurns")));
    }

    #[test]
    fn usage_and_rate_limits() {
        let mut m = EventMapper::new(10, UsageMode::TurnTotals);
        m.start_prompt();
        let mut out = Vec::new();
        let signals = m.handle_update(
            &upd(json!({
                "sessionUpdate": "usage_update", "used": 1000, "size": 200000,
                "cost": {"amount": 0.25, "currency": "USD"},
                "_meta": {"_claude/rateLimit": {"status": "rejected", "resetsAt": 1790000000, "rateLimitType": "five_hour", "utilization": 1.0}}
            })),
            Instant::now(),
            &mut out,
        );
        assert_eq!(out.len(), 2);
        match &out[0] {
            EventPayload::RateLimit(r) => {
                assert_eq!(r.status, RateLimitStatus::Rejected);
                assert_eq!(r.window.as_deref(), Some("five_hour"));
                assert_eq!(r.resets_at.unwrap().timestamp(), 1790000000);
            }
            other => panic!("unexpected {other:?}"),
        }
        match &out[1] {
            EventPayload::Usage(u) => {
                assert_eq!(u.context_used, Some(1000));
                assert_eq!(u.cost_usd, Some(0.25));
                assert_eq!(u.input_tokens, Some(0));
            }
            other => panic!("unexpected {other:?}"),
        }
        assert!(
            matches!(&signals[..], [Signal::UsageLimit { window: Some(w), .. }] if w == "five_hour")
        );
        // The same update again produces no duplicate events.
        out.clear();
        m.handle_update(
            &upd(json!({"sessionUpdate": "usage_update", "used": 1000, "size": 200000, "cost": {"amount": 0.25, "currency": "USD"}})),
            Instant::now(),
            &mut out,
        );
        assert!(out.is_empty());
    }

    #[test]
    fn usage_events_are_cumulative_for_codex() {
        let mut m = EventMapper::new(10, UsageMode::LastRequestOnly);
        m.start_prompt();
        let mut out = Vec::new();
        for (i, used) in [100u64, 300].into_iter().enumerate() {
            m.handle_update(&chunk(&format!("step {i}")), Instant::now(), &mut out);
            m.handle_update(
                &upd(json!({"sessionUpdate": "usage_update", "used": used, "size": 1000})),
                Instant::now(),
                &mut out,
            );
        }
        m.finish_prompt(
            Some(&json!({"inputTokens": 200, "outputTokens": 100, "cachedReadTokens": 0})),
            &mut out,
        );
        let usages: Vec<&UsagePayload> = out
            .iter()
            .filter_map(|e| match e {
                EventPayload::Usage(u) => Some(u),
                _ => None,
            })
            .collect();
        assert_eq!(usages.len(), 3);
        assert_eq!(usages[0].input_tokens, Some(100));
        assert_eq!(usages[1].input_tokens, Some(400));
        let last = usages[2];
        assert_eq!(
            last.input_tokens.unwrap() + last.output_tokens.unwrap(),
            400
        );
        assert_eq!(m.usage().as_ref(), Some(last));
    }

    #[test]
    fn large_text_is_split_into_bounded_events() {
        let mut m = EventMapper::new(10, UsageMode::TurnTotals);
        m.start_prompt();
        let mut out = Vec::new();
        let big = "é".repeat(50_000); // 100 000 bytes
        m.handle_update(&chunk(&big), Instant::now(), &mut out);
        m.flush(&mut out);
        let texts: Vec<&str> = out
            .iter()
            .map(|e| match e {
                EventPayload::AgentMessage(t) => t.text.as_str(),
                other => panic!("unexpected {other:?}"),
            })
            .collect();
        assert!(texts.len() >= 4);
        assert!(texts.iter().all(|t| t.len() <= TEXT_EVENT_MAX));
        assert_eq!(texts.concat(), big);
    }

    #[test]
    fn unknown_updates_are_ignored() {
        let mut m = EventMapper::new(10, UsageMode::TurnTotals);
        m.start_prompt();
        let mut out = Vec::new();
        m.handle_update(
            &upd(json!({"sessionUpdate": "brand_new_kind", "x": 1})),
            Instant::now(),
            &mut out,
        );
        m.handle_update(&json!({"garbage": true}), Instant::now(), &mut out);
        m.handle_update(
            &upd(json!({"sessionUpdate": "agent_message_chunk", "content": {"type": "image", "data": "..."}})),
            Instant::now(),
            &mut out,
        );
        m.flush(&mut out);
        assert!(out.is_empty());
    }
}
