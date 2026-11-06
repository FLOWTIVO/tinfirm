//! Cumulative token accounting for a run (docs/archive/v1/CONVENTIONS.md §14.5: every USAGE
//! event and `run.finished.usage` carry the running totals of the whole run).
//!
//! The adapters report usage differently:
//! - `claude-agent-acp`: `PromptResponse.usage` holds the totals of the whole prompt turn
//!   (reset at each turn); `usage_update` carries only the context occupancy and cost.
//!   → [`UsageMode::TurnTotals`]: add each prompt response.
//! - `codex-acp`: `PromptResponse.usage` holds only the LAST model request of the turn
//!   (codex-acp #447), and each `usage_update` carries that request's total token count
//!   (`used`, which is `input + output` of the request). → [`UsageMode::LastRequestOnly`]:
//!   sum the per-request totals from `usage_update`, and split the requests whose split is
//!   unknown in the proportions of the last request, which the prompt response does split.
//!   Until the first split is known, unsplit request totals count as input tokens.
//! - `tinfirm-pi-acp` (pi, ADR-0041): `PromptResponse.usage` holds the tokens of the whole turn,
//!   the difference of pi's session stats before and after it. → [`UsageMode::TurnTotals`].
//! - `opencode acp` of OpenCode 2 (ADR-0061): `PromptResponse.usage` holds the tokens of every
//!   model request of the turn, and the one `usage_update` after the turn carries the context
//!   and the session's cost. → [`UsageMode::SessionTurnTotals`]: add each prompt response, and
//!   report no cost for a resumed session, whose cost includes the earlier runs.
//! - `opencode acp` of OpenCode 1 (ADR-0041), in an image built before ADR-0061: the prompt
//!   response holds only the turn's last model request. → [`UsageMode::TurnLastRequest`]: the
//!   same, and the totals are `estimated`, since a turn of several requests used more tokens
//!   than it reports. `initialize`'s `agentInfo.version` tells the two apart
//!   ([`UsageMode::for_agent`]).
//!
//! `totalTokens` is exact in every mode (the parts always add up to it). `inputTokens`
//! counts the uncached input tokens, `cachedInputTokens` the cache reads and
//! `cacheWriteTokens` the input tokens written to the cache (priced apart from both), so
//! `totalTokens = inputTokens + cachedInputTokens + cacheWriteTokens + outputTokens`.
//! Codex reports no cache writes, so its `cacheWriteTokens` stays 0. A v1 host counts the
//! cache writes as input ([`UsagePayload::into_v1`]). `estimated` is `true` once any part
//! of the split was apportioned rather than reported (Codex only).

use serde_json::Value;
use tinfirm_protocol::{Runtime, UsagePayload};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UsageMode {
    /// `PromptResponse.usage` = totals of the prompt turn.
    TurnTotals,
    /// `PromptResponse.usage` = last model request only; `usage_update.used` per request.
    LastRequestOnly,
    /// `PromptResponse.usage` = last model request only, and no per-request reports: the
    /// totals are a lower bound. `usage_update.cost` is the session's (OpenCode 1).
    TurnLastRequest,
    /// `PromptResponse.usage` = totals of the prompt turn; `usage_update.cost` is the
    /// session's (OpenCode 2).
    SessionTurnTotals,
}

impl UsageMode {
    /// The mode of a runtime's current image, before `initialize` named the agent.
    pub fn for_runtime(runtime: Runtime) -> UsageMode {
        match runtime {
            Runtime::ClaudeCode | Runtime::Pi => UsageMode::TurnTotals,
            Runtime::Codex => UsageMode::LastRequestOnly,
            Runtime::Opencode => UsageMode::SessionTurnTotals,
        }
    }

    /// The mode of the agent that `initialize` named (`agentInfo.version`). An image the host
    /// has not rebuilt yet may still hold OpenCode 1, which reports less (ADR-0061).
    pub fn for_agent(runtime: Runtime, version: Option<&str>) -> UsageMode {
        let major = version.and_then(|v| {
            v.trim()
                .trim_start_matches('v')
                .split('.')
                .next()?
                .parse::<u64>()
                .ok()
        });
        match runtime {
            Runtime::Opencode if major.is_some_and(|major| major < 2) => UsageMode::TurnLastRequest,
            _ => UsageMode::for_runtime(runtime),
        }
    }

    /// `usage_update.cost` is the session's, so a resumed session's would count earlier runs.
    fn cost_is_the_sessions(self) -> bool {
        matches!(
            self,
            UsageMode::TurnLastRequest | UsageMode::SessionTurnTotals
        )
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct Split {
    input: u64,
    output: u64,
    cached: u64,
    cache_write: u64,
}

/// The most tokens a count reaches: the sandbox reports usage, so its numbers are
/// untrusted; every sum saturates, and no count goes above what a signed 64-bit integer (the
/// server's `long`) holds (I7 of the security review of 2026-10-27).
pub const MAX_TOKENS: u64 = i64::MAX as u64;

/// `a + b`, saturating at [`MAX_TOKENS`].
fn plus(a: u64, b: u64) -> u64 {
    a.saturating_add(b).min(MAX_TOKENS)
}

/// The sum of `values`, saturating at [`MAX_TOKENS`].
fn sum(values: &[u64]) -> u64 {
    values.iter().fold(0, |acc, v| plus(acc, *v))
}

impl Split {
    fn total(&self) -> u64 {
        plus(
            plus(self.input, self.output),
            plus(self.cached, self.cache_write),
        )
    }

    fn add(&mut self, other: Split) {
        self.input = plus(self.input, other.input);
        self.output = plus(self.output, other.output);
        self.cached = plus(self.cached, other.cached);
        self.cache_write = plus(self.cache_write, other.cache_write);
    }

    /// `total` tokens divided in the proportions of `self`; the parts sum to `total`.
    fn apportion(&self, total: u64) -> Split {
        let base = self.total();
        if base == 0 {
            return Split {
                input: total,
                ..Split::default()
            };
        }
        let share = |part: u64| (total as u128 * part as u128 / base as u128) as u64;
        let output = share(self.output);
        let cached = share(self.cached);
        let cache_write = share(self.cache_write);
        Split {
            input: total
                .saturating_sub(output)
                .saturating_sub(cached)
                .saturating_sub(cache_write),
            output,
            cached,
            cache_write,
        }
    }
}

#[derive(Debug, Clone)]
pub struct UsageAccumulator {
    mode: UsageMode,
    /// Totals of finished prompt turns.
    committed: Split,
    /// LastRequestOnly: per-request totals seen in the current turn.
    turn_request_totals: Vec<u64>,
    /// LastRequestOnly: the split of the most recent fully reported request.
    last_split: Option<Split>,
    activity_since_request: bool,
    /// Some part of `committed` was apportioned, not reported.
    estimated: bool,
    context_used: Option<u64>,
    context_size: Option<u64>,
    cost_usd: Option<f64>,
    /// The session holds turns of earlier runs, so a session's cost is not this run's.
    resumed: bool,
}

impl UsageAccumulator {
    pub fn new(mode: UsageMode) -> UsageAccumulator {
        UsageAccumulator {
            mode,
            committed: Split::default(),
            turn_request_totals: Vec::new(),
            last_split: None,
            activity_since_request: true,
            estimated: false,
            context_used: None,
            context_size: None,
            cost_usd: None,
            resumed: false,
        }
    }

    /// The agent that `initialize` named reports usage in `mode`. Called before the prompt,
    /// when nothing was counted yet.
    pub fn set_mode(&mut self, mode: UsageMode) {
        self.mode = mode;
    }

    /// The run resumed a session (`session/load`). A runtime whose cost is the session's
    /// (OpenCode) then reports none: it would count the earlier runs again.
    pub fn on_session_resumed(&mut self) {
        self.resumed = true;
        if self.mode.cost_is_the_sessions() {
            self.cost_usd = None;
        }
    }

    /// The agent produced output (a model request happened since the last usage report).
    pub fn on_activity(&mut self) {
        self.activity_since_request = true;
    }

    /// A `usage_update` notification.
    pub fn on_usage_update(&mut self, update: &Value) {
        let used = update
            .get("used")
            .and_then(Value::as_u64)
            .map(|v| v.min(MAX_TOKENS));
        if let Some(used) = used {
            self.context_used = Some(used);
        }
        if let Some(size) = update.get("size").and_then(Value::as_u64) {
            self.context_size = Some(size.min(MAX_TOKENS));
        }
        // A cost that is not a finite, non-negative amount is dropped.
        if let Some(cost) = update.get("cost")
            && !(self.resumed && self.mode.cost_is_the_sessions())
            && cost.get("currency").and_then(Value::as_str) == Some("USD")
            && let Some(amount) = cost.get("amount").and_then(Value::as_f64)
            && amount.is_finite()
            && amount >= 0.0
        {
            self.cost_usd = Some(amount);
        }
        // A repeated report without new agent output is the same request, not a new one.
        if self.mode == UsageMode::LastRequestOnly
            && let Some(used) = used
            && self.activity_since_request
        {
            self.turn_request_totals.push(used);
            self.activity_since_request = false;
        }
    }

    /// The `usage` object of a `session/prompt` response (the turn ended).
    pub fn on_prompt_response(&mut self, usage: Option<&Value>) {
        let reported = usage.map(parse_split);
        match self.mode {
            UsageMode::TurnTotals | UsageMode::SessionTurnTotals => {
                if let Some(split) = reported {
                    self.committed.add(split);
                }
            }
            UsageMode::TurnLastRequest => {
                if let Some(split) = reported {
                    self.committed.add(split);
                    self.estimated = true;
                }
            }
            UsageMode::LastRequestOnly => {
                let mut totals = std::mem::take(&mut self.turn_request_totals);
                match reported {
                    Some(last) if last.total() > 0 => {
                        // The response describes the final request, which is the last
                        // `usage_update` of the turn (if that one was seen at all).
                        if totals.last() == Some(&last.total()) {
                            totals.pop();
                        }
                        let earlier = sum(&totals);
                        self.committed.add(last);
                        self.committed.add(last.apportion(earlier));
                        self.estimated |= earlier > 0;
                        self.last_split = Some(last);
                    }
                    _ => {
                        let earlier = sum(&totals);
                        let split = self.last_split.unwrap_or_default().apportion(earlier);
                        self.committed.add(split);
                        self.estimated |= earlier > 0;
                    }
                }
            }
        }
        self.activity_since_request = true;
    }

    /// Running totals of the run so far.
    pub fn snapshot(&self) -> UsagePayload {
        let mut totals = self.committed;
        let mut estimated = self.estimated;
        if self.mode == UsageMode::LastRequestOnly {
            let pending = sum(&self.turn_request_totals);
            totals.add(self.last_split.unwrap_or_default().apportion(pending));
            estimated |= pending > 0;
        }
        UsagePayload {
            input_tokens: Some(totals.input),
            output_tokens: Some(totals.output),
            cached_input_tokens: Some(totals.cached),
            cache_write_tokens: Some(totals.cache_write),
            context_used: self.context_used,
            context_size: self.context_size,
            cost_usd: self.cost_usd,
            total_tokens: Some(totals.total()),
            estimated: Some(estimated),
        }
    }

    /// `true` once anything was reported.
    pub fn has_data(&self) -> bool {
        self.committed.total() > 0
            || !self.turn_request_totals.is_empty()
            || self.context_used.is_some()
            || self.cost_usd.is_some()
    }
}

/// Reads `{inputTokens, outputTokens, cachedReadTokens, cachedWriteTokens}` (ACP `Usage`),
/// also accepting the codex-style `cachedInputTokens` and a `_meta.quota.token_count` shaped
/// object. ACP's `inputTokens` already leaves out the cache reads and writes.
fn parse_split(usage: &Value) -> Split {
    let n = |key: &str| {
        usage
            .get(key)
            .and_then(Value::as_u64)
            .unwrap_or(0)
            .min(MAX_TOKENS)
    };
    Split {
        input: n("inputTokens"),
        cache_write: n("cachedWriteTokens"),
        output: n("outputTokens"),
        cached: usage
            .get("cachedReadTokens")
            .or_else(|| usage.get("cachedInputTokens"))
            .and_then(Value::as_u64)
            .unwrap_or(0)
            .min(MAX_TOKENS),
    }
}

/// The usage object of a prompt response: `usage`, else `_meta.quota.token_count`.
pub fn prompt_usage<'a>(usage: Option<&'a Value>, meta: Option<&'a Value>) -> Option<&'a Value> {
    usage.or_else(|| meta.and_then(|m| m.pointer("/quota/token_count")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// I7 (security review of 2026-10-27): the sandbox's numbers never wrap, never exceed a
    /// signed 64-bit count, and a cost that is negative or not finite is dropped.
    #[test]
    fn usage_from_the_sandbox_saturates_and_bad_costs_are_dropped() {
        let mut acc = UsageAccumulator::new(UsageMode::TurnTotals);
        let huge = json!({"inputTokens": u64::MAX, "outputTokens": u64::MAX, "cachedReadTokens": u64::MAX, "cachedWriteTokens": 5});
        acc.on_prompt_response(Some(&huge));
        acc.on_prompt_response(Some(&huge));
        let usage = acc.snapshot();
        assert_eq!(usage.input_tokens, Some(MAX_TOKENS));
        assert_eq!(usage.output_tokens, Some(MAX_TOKENS));
        assert_eq!(usage.total_tokens, Some(MAX_TOKENS));
        assert_eq!(usage.cache_write_tokens, Some(10));
        for amount in [json!(-1.5), json!(-1e-9)] {
            let mut acc = UsageAccumulator::new(UsageMode::TurnTotals);
            acc.on_usage_update(
                &json!({"used": u64::MAX, "cost": {"amount": amount, "currency": "USD"}}),
            );
            assert_eq!(acc.snapshot().cost_usd, None);
            assert_eq!(acc.snapshot().context_used, Some(MAX_TOKENS));
        }
        // JSON holds no NaN or infinity (serde_json reads 1e400 as none); a finite amount is kept.
        let mut acc = UsageAccumulator::new(UsageMode::TurnTotals);
        acc.on_usage_update(&json!({"cost": {"amount": 0.25, "currency": "USD"}}));
        assert_eq!(acc.snapshot().cost_usd, Some(0.25));
        let mut acc = UsageAccumulator::new(UsageMode::LastRequestOnly);
        for used in [u64::MAX, u64::MAX] {
            acc.on_activity();
            acc.on_usage_update(&json!({"used": used}));
        }
        acc.on_prompt_response(None);
        assert!(acc.snapshot().total_tokens.unwrap() <= MAX_TOKENS);
    }

    #[test]
    fn the_mode_follows_the_opencode_version_that_initialize_names() {
        assert_eq!(
            UsageMode::for_runtime(Runtime::Opencode),
            UsageMode::SessionTurnTotals
        );
        for (version, mode) in [
            (Some("1.18.34"), UsageMode::TurnLastRequest),
            (Some("v1.19.0"), UsageMode::TurnLastRequest),
            (Some("2.0.29"), UsageMode::SessionTurnTotals),
            (Some("10.1.0"), UsageMode::SessionTurnTotals),
            // Unknown: the image this binary builds.
            (Some("dev"), UsageMode::SessionTurnTotals),
            (None, UsageMode::SessionTurnTotals),
        ] {
            assert_eq!(
                UsageMode::for_agent(Runtime::Opencode, version),
                mode,
                "{version:?}"
            );
        }
        // The other runtimes do not depend on the version.
        assert_eq!(
            UsageMode::for_agent(Runtime::Pi, Some("1.0.4")),
            UsageMode::TurnTotals
        );
        assert_eq!(
            UsageMode::for_agent(Runtime::Codex, Some("0.1.0")),
            UsageMode::LastRequestOnly
        );
    }

    #[test]
    fn opencode_2_turns_are_exact_and_a_resumed_session_reports_no_cost() {
        let mut acc = UsageAccumulator::new(UsageMode::SessionTurnTotals);
        acc.on_usage_update(
            &json!({"used": 9000, "size": 200000, "cost": {"amount": 0.2, "currency": "USD"}}),
        );
        // A turn of several model requests: the response sums them.
        acc.on_prompt_response(Some(&json!({"inputTokens": 400, "outputTokens": 600, "cachedReadTokens": 16000, "cachedWriteTokens": 900, "totalTokens": 17900})));
        let end = acc.snapshot();
        assert_eq!(end.total_tokens, Some(17900));
        assert_eq!(end.estimated, Some(false));
        assert_eq!(end.cost_usd, Some(0.2));

        let mut resumed = UsageAccumulator::new(UsageMode::SessionTurnTotals);
        resumed.on_session_resumed();
        resumed.on_usage_update(
            &json!({"used": 9000, "size": 200000, "cost": {"amount": 3.5, "currency": "USD"}}),
        );
        assert_eq!(resumed.snapshot().cost_usd, None);
        assert_eq!(resumed.snapshot().context_used, Some(9000));
    }

    #[test]
    fn opencode_1_turns_add_their_last_request_and_are_estimated() {
        assert_eq!(UsageMode::for_runtime(Runtime::Pi), UsageMode::TurnTotals);
        let mut acc = UsageAccumulator::new(UsageMode::TurnLastRequest);
        acc.on_prompt_response(None);
        assert_eq!(acc.snapshot().estimated, Some(false));
        acc.on_usage_update(
            &json!({"used": 9000, "size": 200000, "cost": {"amount": 0.2, "currency": "USD"}}),
        );
        acc.on_prompt_response(Some(&json!({"inputTokens": 40, "outputTokens": 60, "cachedReadTokens": 8000, "cachedWriteTokens": 900, "totalTokens": 9000})));
        acc.on_prompt_response(Some(
            &json!({"inputTokens": 10, "outputTokens": 20, "totalTokens": 30}),
        ));
        let end = acc.snapshot();
        assert_eq!(end.input_tokens, Some(50));
        assert_eq!(end.output_tokens, Some(80));
        assert_eq!(end.cached_input_tokens, Some(8000));
        assert_eq!(end.cache_write_tokens, Some(900));
        assert_eq!(end.total_tokens, Some(9030));
        assert_eq!(end.estimated, Some(true));
        assert_eq!(end.cost_usd, Some(0.2));

        // A resumed session's cost includes the earlier runs: none is reported.
        let mut resumed = UsageAccumulator::new(UsageMode::TurnLastRequest);
        resumed.on_session_resumed();
        resumed.on_usage_update(
            &json!({"used": 9000, "size": 200000, "cost": {"amount": 3.5, "currency": "USD"}}),
        );
        assert_eq!(resumed.snapshot().cost_usd, None);
        assert_eq!(resumed.snapshot().context_used, Some(9000));
        // Claude's cost stays.
        let mut claude = UsageAccumulator::new(UsageMode::TurnTotals);
        claude.on_session_resumed();
        claude.on_usage_update(
            &json!({"used": 1, "size": 2, "cost": {"amount": 0.5, "currency": "USD"}}),
        );
        assert_eq!(claude.snapshot().cost_usd, Some(0.5));
    }

    #[test]
    fn claude_turn_totals_are_added() {
        let mut acc = UsageAccumulator::new(UsageMode::TurnTotals);
        acc.on_activity();
        acc.on_usage_update(
            &json!({"used": 5000, "size": 200000, "cost": {"amount": 0.1, "currency": "USD"}}),
        );
        // Context is known mid-run; tokens are not yet.
        let mid = acc.snapshot();
        assert_eq!(mid.input_tokens, Some(0));
        assert_eq!(mid.context_used, Some(5000));
        acc.on_prompt_response(Some(&json!({"inputTokens": 100, "outputTokens": 50, "cachedReadTokens": 900, "cachedWriteTokens": 0, "totalTokens": 1050})));
        let end = acc.snapshot();
        assert_eq!(end.total_tokens, Some(1050));
        assert_eq!(end.estimated, Some(false));
        assert_eq!(end.input_tokens, Some(100));
        assert_eq!(end.output_tokens, Some(50));
        assert_eq!(end.cached_input_tokens, Some(900));
        assert_eq!(end.cost_usd, Some(0.1));
        // A second turn in the same run accumulates.
        acc.on_prompt_response(Some(
            &json!({"inputTokens": 10, "outputTokens": 5, "cachedReadTokens": 0}),
        ));
        assert_eq!(acc.snapshot().input_tokens, Some(110));
        assert_eq!(acc.snapshot().output_tokens, Some(55));
    }

    #[test]
    fn codex_requests_are_summed() {
        let mut acc = UsageAccumulator::new(UsageMode::LastRequestOnly);
        // Three model requests, each followed by a usage_update with its total.
        for used in [1000u64, 2000, 4000] {
            acc.on_activity();
            acc.on_usage_update(&json!({"used": used, "size": 272000}));
            // A duplicate report without new output is not a new request.
            acc.on_usage_update(&json!({"used": used, "size": 272000}));
        }
        // Mid-run: request totals count as input until a split is known.
        assert_eq!(acc.snapshot().input_tokens, Some(7000));
        // The prompt response describes only the last request (4000 = 1000 + 2600 + 400).
        acc.on_prompt_response(Some(&json!({
            "inputTokens": 1000, "cachedReadTokens": 2600, "outputTokens": 400, "totalTokens": 4000
        })));
        let end = acc.snapshot();
        let total = end.input_tokens.unwrap()
            + end.output_tokens.unwrap()
            + end.cached_input_tokens.unwrap();
        assert_eq!(total, 7000, "every request is counted once: {end:?}");
        // The 3000 earlier tokens are split like the last request (25% / 65% / 10%).
        assert_eq!(end.output_tokens, Some(400 + 300));
        assert_eq!(end.cached_input_tokens, Some(2600 + 1950));
        assert_eq!(end.input_tokens, Some(1000 + 750));
        assert_eq!(end.context_used, Some(4000));
        assert_eq!(end.total_tokens, Some(7000));
        assert_eq!(end.estimated, Some(true));
    }

    #[test]
    fn cache_writes_are_apart_from_input_and_totals_are_exact() {
        let mut acc = UsageAccumulator::new(UsageMode::TurnTotals);
        acc.on_prompt_response(Some(&json!({
            "inputTokens": 10, "outputTokens": 20, "cachedReadTokens": 30, "cachedWriteTokens": 40, "totalTokens": 100
        })));
        let s = acc.snapshot();
        assert_eq!(s.input_tokens, Some(10));
        assert_eq!(s.cached_input_tokens, Some(30));
        assert_eq!(s.cache_write_tokens, Some(40));
        assert_eq!(s.total_tokens, Some(100));
        assert_eq!(
            s.input_tokens.unwrap()
                + s.output_tokens.unwrap()
                + s.cached_input_tokens.unwrap()
                + s.cache_write_tokens.unwrap(),
            100
        );
        // v1 counts the cache writes as input.
        let v1 = s.into_v1();
        assert_eq!((v1.input_tokens, v1.cache_write_tokens), (Some(50), None));
        assert_eq!(v1.total_tokens, Some(100));
    }

    #[test]
    fn apportioned_requests_keep_cache_writes_in_proportion() {
        let last = Split {
            input: 100,
            output: 100,
            cached: 600,
            cache_write: 200,
        };
        let split = last.apportion(500);
        assert_eq!(split.total(), 500);
        assert_eq!(
            (split.input, split.output, split.cached, split.cache_write),
            (50, 50, 300, 100)
        );
        assert_eq!(
            Split::default().apportion(70),
            Split {
                input: 70,
                ..Split::default()
            }
        );
    }

    #[test]
    fn codex_single_request_turn_is_exact() {
        let mut acc = UsageAccumulator::new(UsageMode::LastRequestOnly);
        acc.on_activity();
        acc.on_usage_update(&json!({"used": 300}));
        acc.on_prompt_response(Some(
            &json!({"inputTokens": 100, "outputTokens": 50, "cachedReadTokens": 150}),
        ));
        let s = acc.snapshot();
        assert_eq!(s.total_tokens, Some(300));
        assert_eq!(
            s.estimated,
            Some(false),
            "the only request was reported with its split"
        );
        assert_eq!(
            (s.input_tokens, s.output_tokens, s.cached_input_tokens),
            (Some(100), Some(50), Some(150))
        );
    }

    #[test]
    fn codex_response_without_matching_update_still_counts() {
        let mut acc = UsageAccumulator::new(UsageMode::LastRequestOnly);
        acc.on_activity();
        acc.on_usage_update(&json!({"used": 500}));
        // The final request's usage_update never arrived.
        acc.on_prompt_response(Some(
            &json!({"inputTokens": 100, "outputTokens": 20, "cachedReadTokens": 80}),
        ));
        let end = acc.snapshot();
        let total = end.input_tokens.unwrap()
            + end.output_tokens.unwrap()
            + end.cached_input_tokens.unwrap();
        assert_eq!(total, 700);
    }

    #[test]
    fn reads_quota_meta_as_fallback() {
        let meta = json!({"quota": {"token_count": {"inputTokens": 3, "outputTokens": 4, "cachedInputTokens": 5}}});
        let usage = prompt_usage(None, Some(&meta)).unwrap();
        let mut acc = UsageAccumulator::new(UsageMode::TurnTotals);
        acc.on_prompt_response(Some(usage));
        let s = acc.snapshot();
        assert_eq!(
            (s.input_tokens, s.output_tokens, s.cached_input_tokens),
            (Some(3), Some(4), Some(5))
        );
    }
}
