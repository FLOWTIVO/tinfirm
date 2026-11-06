//! Usage-limit detection and failure classification of agent errors
//! (docs/archive/v1/CONVENTIONS.md §15.4 step 9).

use chrono::{DateTime, Duration, Utc};
use serde_json::Value;
use tinfirm_protocol::FailureClass;

/// Start of the synthetic messages the Claude CLI writes when a plan limit is hit
/// (`USAGE_LIMIT_ERROR_PREFIXES` in the Agent SDK), plus older and Codex wordings.
pub const USAGE_LIMIT_PREFIXES: &[&str] = &[
    "You've hit your",
    "You've reached your",
    "You're out of usage credits",
    "You're out of extra usage",
    "Your org is out of usage",
    "Your usage allocation has been disabled",
    "Your group's usage limit is set to $0",
    "Claude AI usage limit reached",
    "Claude usage limit reached",
];

/// Cooldown used when the vendor does not say when the limit resets.
pub const DEFAULT_COOLDOWN_MINUTES: i64 = 60;

/// `true` when `text` is a CLI usage-limit notice (not ordinary prose about limits).
pub fn is_usage_limit_text(text: &str) -> bool {
    let normalized = text.trim().replace('\u{2019}', "'");
    USAGE_LIMIT_PREFIXES
        .iter()
        .any(|prefix| normalized.starts_with(prefix))
        || normalized
            .to_ascii_lowercase()
            .contains("usage limit reached")
        || normalized.contains("UsageLimitExceeded")
        || normalized.contains("usageLimitExceeded")
        || is_gateway_budget_text(&normalized)
}

/// A model gateway refused the request because a budget is spent (ADR-0027). Since ADR-0094 the
/// server's model relay answers a spent budget itself, whatever the gateway, with the error type
/// `"budget_exceeded"` and the message `Budget has been exceeded: the model budget that pays for
/// this run is spent…`. Servers before it passed on LiteLLM's own answer: the same type and
/// `Budget has been exceeded! …` (the key's, team's or user's budget) or `ExceededBudget: Key
/// over 1d budget. …` (a budget window). The run cannot go on until the budget resets or is
/// raised, like after a usage limit. The bare word `budget_exceeded` is not enough: an agent may
/// name a variable so.
fn is_gateway_budget_text(text: &str) -> bool {
    text.to_ascii_lowercase()
        .contains("budget has been exceeded")
        || text.contains("ExceededBudget:")
        || text.contains("\"budget_exceeded\"")
}

/// Parses "try again in 2 hours 5 minutes" / "resets in 3 days" into an instant.
pub fn parse_retry_after(text: &str, now: DateTime<Utc>) -> Option<DateTime<Utc>> {
    let lower = text.to_ascii_lowercase();
    let idx = lower.find(" in ")?;
    let rest = &lower[idx + 4..];
    let mut total = Duration::zero();
    let mut found = false;
    let words: Vec<&str> = rest
        .split(|c: char| c.is_whitespace() || c == ',' || c == '.')
        .filter(|w| !w.is_empty())
        .take(8)
        .collect();
    let mut i = 0;
    while i + 1 < words.len() {
        if let Ok(n) = words[i].parse::<i64>() {
            let unit = words[i + 1];
            let d = if unit.starts_with("day") {
                Some(Duration::days(n))
            } else if unit.starts_with("hour") || unit == "h" {
                Some(Duration::hours(n))
            } else if unit.starts_with("min") || unit == "m" {
                Some(Duration::minutes(n))
            } else if unit.starts_with("sec") || unit == "s" {
                Some(Duration::seconds(n))
            } else {
                None
            };
            if let Some(d) = d {
                total += d;
                found = true;
                i += 2;
                continue;
            }
        }
        if found {
            break;
        }
        i += 1;
    }
    (found && total > Duration::zero()).then(|| now + total)
}

/// The error of an ACP request, flattened.
#[derive(Debug, Clone, Default)]
pub struct AgentError {
    pub code: Option<i32>,
    pub message: String,
    pub data: Option<Value>,
    /// The agent process went away (stdout closed) before answering.
    pub transport_closed: bool,
}

impl AgentError {
    /// All human-readable text of the error.
    pub fn text(&self) -> String {
        let mut parts = vec![self.message.clone()];
        if let Some(data) = &self.data {
            for key in ["message", "additionalDetails", "details", "reason"] {
                if let Some(s) = data.get(key).and_then(|v| v.as_str()) {
                    parts.push(s.to_string());
                }
            }
            if let Some(s) = data.as_str() {
                parts.push(s.to_string());
            }
        }
        parts.retain(|p| !p.is_empty());
        parts.dedup();
        parts.join(": ")
    }

    fn codex_error_kinds(&self) -> Vec<String> {
        match self.data.as_ref().and_then(|d| d.get("codexErrorInfo")) {
            Some(Value::String(s)) => vec![s.clone()],
            Some(Value::Object(map)) => map.keys().cloned().collect(),
            _ => Vec::new(),
        }
    }

    fn claude_error_kind(&self) -> Option<String> {
        self.data
            .as_ref()
            .and_then(|d| d.get("errorKind"))
            .and_then(|v| v.as_str())
            .map(str::to_string)
    }

    /// OpenCode 2's kind of a provider error (`data.errorName`, such as `provider.quota`),
    /// next to the provider's own message (ADR-0061).
    fn opencode_error_name(&self) -> Option<&str> {
        self.data
            .as_ref()
            .and_then(|d| d.get("errorName"))
            .and_then(|v| v.as_str())
            .filter(|name| name.starts_with("provider."))
    }
}

/// Codex's words for ChatGPT's `refresh_token_reused`: the refresh token this process held was
/// used by another, which renewed the login (ADR-0085, decision 5).
pub fn is_refresh_race(error: &AgentError) -> bool {
    error
        .text()
        .to_ascii_lowercase()
        .contains("refresh token was already used")
}

/// Maps an agent error to a failure class.
pub fn classify_error(error: &AgentError) -> FailureClass {
    if error.transport_closed {
        return FailureClass::ProtocolError;
    }
    // Codex, when two runs of one login renewed it at the same moment: the loser's refresh
    // token was just used by the winner, whose renewed `auth.json` the next run reads
    // (ADR-0085, decision 5). The login is whole, so the run can go again; before the
    // `unauthorized` kind Codex gives it, which would mark the login expired. The run
    // (crate::run) turns it back into LOGIN_EXPIRED when nothing on the host renewed the file.
    if is_refresh_race(error) {
        return FailureClass::ServerLimited;
    }
    for kind in error.codex_error_kinds() {
        match kind.as_str() {
            "usageLimitExceeded" => return FailureClass::UsageLimit,
            "contextWindowExceeded" => return FailureClass::ContextOverflow,
            "rateLimitExceeded" | "serverOverloaded" => return FailureClass::ServerLimited,
            "unauthorized" => return FailureClass::LoginExpired,
            "cyberPolicy" | "misalignmentPolicyViolation" => return FailureClass::VendorBlocked,
            _ => {}
        }
    }
    if let Some(kind) = error.claude_error_kind() {
        match kind.as_str() {
            "authentication_failed" | "oauth_org_not_allowed" => return FailureClass::LoginExpired,
            "billing_error" | "account_on_hold" => return FailureClass::UsageLimit,
            "rate_limit" | "overloaded" => return FailureClass::ServerLimited,
            "max_output_tokens" => return FailureClass::ContextOverflow,
            "verification_required" => return FailureClass::VendorBlocked,
            _ => {}
        }
    }
    if error.code == Some(-32000) {
        return FailureClass::LoginExpired;
    }
    let text = error.text();
    if is_usage_limit_text(&text) || text.lines().any(is_usage_limit_text) {
        return FailureClass::UsageLimit;
    }
    // After the texts above: a gateway's spent budget stays a usage limit, whatever kind
    // OpenCode gave the provider's answer. OpenCode retried a rate limit before it failed.
    match error.opencode_error_name() {
        Some("provider.quota") => return FailureClass::UsageLimit,
        Some("provider.rate-limit") => return FailureClass::ServerLimited,
        Some("provider.auth") => return FailureClass::LoginExpired,
        _ => {}
    }
    let lower = text.to_ascii_lowercase();
    // A provider's spent credit, as pi and OpenCode pass it on (ADR-0042): Anthropic's credit
    // balance, OpenAI's quota (a 429 that waiting does not cure).
    if lower.contains("credit balance is too low")
        || lower.contains("insufficient_quota")
        || lower.contains("exceeded your current quota")
    {
        return FailureClass::UsageLimit;
    }
    if lower.contains("prompt is too long")
        || lower.contains("context window")
        || lower.contains("context_length_exceeded")
        || lower.contains("maximum context length")
    {
        return FailureClass::ContextOverflow;
    }
    if lower.contains("overloaded")
        || lower.contains("temporarily limiting requests")
        || lower.contains(" 529")
        || lower.contains("rate limit")
        || lower.contains("rate_limit")
        || lower.contains("too many requests")
        || lower.contains("throttlingexception")
    {
        return FailureClass::ServerLimited;
    }
    // A provider's own wording of a refused key, as pi and OpenCode pass it on (ADR-0041):
    // Anthropic, OpenAI, the Gemini API and AWS (Bedrock).
    if lower.contains("please run /login")
        || lower.contains("oauth token has expired")
        || lower.contains("invalid api key")
        || lower.contains("api key is invalid")
        || lower.contains("incorrect api key")
        || lower.contains("invalid_api_key")
        || lower.contains("api key not valid")
        || lower.contains("invalid x-api-key")
        || lower.contains("authentication_error")
        || lower.contains("not logged in")
        || lower.contains("security token included in the request is invalid")
        || lower.contains("unrecognizedclientexception")
        || lower.contains("invalidsignatureexception")
        || lower.contains("401")
    {
        return FailureClass::LoginExpired;
    }
    if lower.contains("403") && (lower.contains("policy") || lower.contains("forbidden")) {
        return FailureClass::VendorBlocked;
    }
    // Last: a provider or the gateway in front of it that is down or failing for now (a 5xx,
    // a proxy with no upstream, Claude Code's `server_error`), as every runtime passes it on once
    // its own retries are spent. It may pass, so the server can retry the turn by itself
    // (ADR-0073); a more specific cause in the words above keeps its class.
    if is_unavailable_text(&lower) || error.claude_error_kind().as_deref() == Some("server_error") {
        return FailureClass::ServerLimited;
    }
    FailureClass::ProtocolError
}

/// A model request that failed because the provider, or a gateway or proxy on the way, is
/// unavailable for now: an HTTP status 500, 502, 503 or 504 standing as a number of its own, or
/// the wordings of such an answer (`text` in lower case).
fn is_unavailable_text(text: &str) -> bool {
    const WORDINGS: &[&str] = &[
        "internal server error",
        "bad gateway",
        "service unavailable",
        "gateway timeout",
        "no available server",
        "no healthy upstream",
        "upstream connect error",
    ];
    if WORDINGS.iter().any(|w| text.contains(w)) {
        return true;
    }
    let bytes = text.as_bytes();
    ["500", "502", "503", "504"].iter().any(|status| {
        text.match_indices(status).any(|(at, _)| {
            let before = at.checked_sub(1).map(|i| bytes[i]);
            let after = bytes.get(at + status.len()).copied();
            !before.is_some_and(|b| b.is_ascii_alphanumeric())
                && !after.is_some_and(|b| b.is_ascii_alphanumeric())
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn detects_limit_texts() {
        assert!(is_usage_limit_text(
            "You've hit your limit · resets 3pm (Europe/Prague)"
        ));
        assert!(is_usage_limit_text(
            "You\u{2019}ve hit your usage limit. Try again in 2 hours."
        ));
        assert!(is_usage_limit_text(
            "Claude AI usage limit reached|1790000000"
        ));
        assert!(!is_usage_limit_text(
            "I added a usage limit check to the API."
        ));
        // LiteLLM's answer when a virtual key's budget is spent (ADR-0027).
        assert!(is_usage_limit_text(
            r#"API Error: 400 {"error":{"message":"Budget has been exceeded! Current cost: 5.01, Max budget: 5.0","type":"budget_exceeded"}}"#
        ));
        assert!(is_usage_limit_text(
            "API Error: 400 ExceededBudget: Key over 1d budget. Spend=$5.0100, Limit=$5.00"
        ));
        assert!(is_usage_limit_text(
            r#"{"type":"error","error":{"type":"budget_exceeded","message":"over budget"}}"#
        ));
        // The server's relay's own answer (ADR-0094), whatever the gateway, as Claude Code and
        // Codex show it.
        assert!(is_usage_limit_text(
            r#"API Error: 429 {"type":"error","error":{"type":"budget_exceeded","message":"Budget has been exceeded: the model budget that pays for this run is spent; it resets at 2026-11-01T00:00:00Z."}}"#
        ));
        assert!(is_usage_limit_text(
            "Budget has been exceeded: the model budget that pays for this run is spent."
        ));
        assert!(!is_usage_limit_text("I raised the budget of the project."));
        assert!(!is_usage_limit_text(
            "Added a budget_exceeded flag to the invoice model."
        ));
    }

    #[test]
    fn parses_retry_after() {
        let now = Utc::now();
        let t = parse_retry_after(
            "You've hit your usage limit. Try again in 2 hours 5 minutes.",
            now,
        )
        .unwrap();
        assert_eq!((t - now).num_minutes(), 125);
        let t = parse_retry_after("resets in 3 days", now).unwrap();
        assert_eq!((t - now).num_days(), 3);
        assert!(parse_retry_after("try again later", now).is_none());
    }

    #[test]
    fn classifies_errors() {
        let codex = AgentError {
            code: Some(-32603),
            message: "Internal error".into(),
            data: Some(
                json!({"message": "You've hit your usage limit.", "codexErrorInfo": "usageLimitExceeded"}),
            ),
            transport_closed: false,
        };
        assert_eq!(classify_error(&codex), FailureClass::UsageLimit);
        let auth = AgentError {
            code: Some(-32000),
            message: "Authentication required".into(),
            ..Default::default()
        };
        assert_eq!(classify_error(&auth), FailureClass::LoginExpired);
        let claude = AgentError {
            code: Some(-32603),
            message: "Internal error".into(),
            data: Some(json!({"errorKind": "overloaded"})),
            transport_closed: false,
        };
        assert_eq!(classify_error(&claude), FailureClass::ServerLimited);
        let text = AgentError {
            code: Some(-32603),
            message: "You've hit your limit · resets 5pm".into(),
            ..Default::default()
        };
        assert_eq!(classify_error(&text), FailureClass::UsageLimit);
        let closed = AgentError {
            transport_closed: true,
            ..Default::default()
        };
        assert_eq!(classify_error(&closed), FailureClass::ProtocolError);
        let ctx = AgentError {
            message: "Prompt is too long".into(),
            ..Default::default()
        };
        assert_eq!(classify_error(&ctx), FailureClass::ContextOverflow);
    }

    /// A model gateway's answers for a company model credential (ADR-0027): only a refused key
    /// is `LOGIN_EXPIRED`, which makes the server mark the credential invalid; budgets and rate
    /// limits never are.
    #[test]
    fn classifies_a_gateway_that_refuses_the_key() {
        let error = |message: &str, data: serde_json::Value| AgentError {
            code: Some(-32603),
            message: message.into(),
            data: Some(data),
            transport_closed: false,
        };
        // Claude Code reports a gateway's 401 and 403 as authentication_failed.
        let claude_refused = error(
            "Failed to authenticate · check with your gateway administrator · API Error: 403",
            json!({"errorKind": "authentication_failed"}),
        );
        assert_eq!(classify_error(&claude_refused), FailureClass::LoginExpired);
        // Codex reports a 401 as unauthorized.
        let codex_refused = error(
            "unexpected status 401 Unauthorized: Authentication Error, Invalid proxy server token passed",
            json!({"codexErrorInfo": "unauthorized"}),
        );
        assert_eq!(classify_error(&codex_refused), FailureClass::LoginExpired);
        // Codex, when another run of the same login renewed it first: the login is whole and
        // the run goes again, although Codex gives it the same kind.
        let codex_raced = error(
            "Your access token could not be refreshed because your refresh token was already used. Please log out and sign in again.",
            json!({"codexErrorInfo": "unauthorized"}),
        );
        assert_eq!(classify_error(&codex_raced), FailureClass::ServerLimited);
        // LiteLLM's budget error (422) is a usage limit, whatever kind the runtime gives it.
        let budget = error(
            r#"API Error: 422 {"error":{"message":"Budget has been exceeded! Current cost: 5.01, Max budget: 5.0","type":"budget_exceeded"}}"#,
            json!({"errorKind": "invalid_request"}),
        );
        assert_eq!(classify_error(&budget), FailureClass::UsageLimit);
        // Rate limits stay SERVER_LIMITED.
        let codex_rate = error(
            "rate limited",
            json!({"codexErrorInfo": "rateLimitExceeded"}),
        );
        assert_eq!(classify_error(&codex_rate), FailureClass::ServerLimited);
        let claude_rate = error("API Error: 429", json!({"errorKind": "rate_limit"}));
        assert_eq!(classify_error(&claude_rate), FailureClass::ServerLimited);
    }

    /// The provider errors that pi (through tinfirm-pi-acp) and OpenCode pass on (ADR-0041).
    #[test]
    fn classifies_the_providers_errors_of_pi_and_opencode() {
        let error = |code: i32, message: &str| AgentError {
            code: Some(code),
            message: message.into(),
            data: None,
            transport_closed: false,
        };
        for refused in [
            // OpenCode 1, a wrong Anthropic key.
            "Internal error: API key is invalid.",
            // OpenAI.
            "Incorrect API key provided: sk-proj-****. You can find your API key at …",
            // Gemini.
            "API key not valid. Please pass a valid API key.",
            // Bedrock.
            "UnrecognizedClientException: The security token included in the request is invalid.",
        ] {
            assert_eq!(
                classify_error(&error(-32603, refused)),
                FailureClass::LoginExpired,
                "{refused}"
            );
        }
        // tinfirm-pi-acp sends -32000 when pi's provider refused the key.
        assert_eq!(
            classify_error(&error(-32000, "API key is invalid.")),
            FailureClass::LoginExpired
        );
        for spent in [
            r#"400 {"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}"#,
            "429 You exceeded your current quota, please check your plan and billing details.",
            r#"{"error":{"code":"insufficient_quota"}}"#,
        ] {
            assert_eq!(
                classify_error(&error(-32603, spent)),
                FailureClass::UsageLimit,
                "{spent}"
            );
        }
        for limited in [
            r#"429 {"type":"error","error":{"type":"rate_limit_error"}}"#,
            "Too Many Requests",
            "ThrottlingException: Rate exceeded",
        ] {
            assert_eq!(
                classify_error(&error(-32603, limited)),
                FailureClass::ServerLimited,
                "{limited}"
            );
        }
    }

    /// OpenCode 2 (ADR-0061): a refused key is ACP's "authentication required"; any other
    /// provider error is an internal error with the provider's message and OpenCode's kind of
    /// it in `data.errorName`.
    #[test]
    fn classifies_the_provider_errors_of_opencode_2() {
        let error = |code: i32, message: &str, name: &str| AgentError {
            code: Some(code),
            message: message.into(),
            data: Some(json!({"service": "session", "errorName": name})),
            transport_closed: false,
        };
        let refused = AgentError {
            code: Some(-32000),
            message: "provider authentication required".into(),
            data: Some(json!({})),
            transport_closed: false,
        };
        assert_eq!(classify_error(&refused), FailureClass::LoginExpired);
        // The kind decides when the message does not name the cause.
        for (name, class) in [
            ("provider.quota", FailureClass::UsageLimit),
            ("provider.rate-limit", FailureClass::ServerLimited),
            ("provider.auth", FailureClass::LoginExpired),
        ] {
            assert_eq!(
                classify_error(&error(-32603, "The provider refused the request.", name)),
                class,
                "{name}"
            );
        }
        // A gateway's spent budget is a usage limit, whatever the kind.
        assert_eq!(
            classify_error(&error(
                -32603,
                "Budget has been exceeded! Current cost: 5.01, Max budget: 5.0",
                "provider.rate-limit"
            )),
            FailureClass::UsageLimit
        );
        // The message still counts for the other kinds.
        assert_eq!(
            classify_error(&error(
                -32603,
                "prompt is too long: 210000 tokens > 200000 maximum",
                "provider.invalid-request"
            )),
            FailureClass::ContextOverflow
        );
        // A model the gateway does not serve stays a protocol error (the server's
        // MODEL_NOT_SERVED rule takes it from there).
        assert_eq!(
            classify_error(&error(
                -32603,
                "Model claude-x is not available",
                "provider.no-route"
            )),
            FailureClass::ProtocolError
        );
        // Another runtime's errorName is not OpenCode's.
        assert_eq!(
            classify_error(&error(-32603, "boom", "quota")),
            FailureClass::ProtocolError
        );
    }

    /// A provider or gateway that is down for now is SERVER_LIMITED, which the server may retry
    /// by itself (ADR-0073); the other classes keep their place before it.
    #[test]
    fn classifies_an_unavailable_provider_or_gateway() {
        let error = |message: &str| AgentError {
            code: Some(-32603),
            message: message.into(),
            data: None,
            transport_closed: false,
        };
        for unavailable in [
            // pi through tinfirm-pi-acp, an ingress in front of the gateway with no backend.
            "Prompt: 503 no available server",
            r#"500 {"type":"error","error":{"type":"api_error","message":"Internal server error"}}"#,
            "unexpected status 502 Bad Gateway",
            "API Error: 504 Gateway Timeout",
            "upstream connect error or disconnect/reset before headers. reset reason: overflow",
            "no healthy upstream",
            "Service Unavailable",
        ] {
            assert_eq!(
                classify_error(&error(unavailable)),
                FailureClass::ServerLimited,
                "{unavailable}"
            );
        }
        let claude = AgentError {
            code: Some(-32603),
            message: "API Error: Repeated server errors".into(),
            data: Some(json!({"errorKind": "server_error"})),
            transport_closed: false,
        };
        assert_eq!(classify_error(&claude), FailureClass::ServerLimited);
        // Its kind does not hide a more specific cause in its words.
        for (message, class) in [
            (
                "Budget has been exceeded! Current cost: 5.01, Max budget: 5.0",
                FailureClass::UsageLimit,
            ),
            ("invalid api key", FailureClass::LoginExpired),
            ("prompt is too long", FailureClass::ContextOverflow),
        ] {
            let specific = AgentError {
                code: Some(-32603),
                message: message.into(),
                data: Some(json!({"errorKind": "server_error"})),
                transport_closed: false,
            };
            assert_eq!(classify_error(&specific), class, "{message}");
        }
        // A number that only contains a status is not one.
        for other in ["boom 5030", "request id a503b", "exit code 1500"] {
            assert_eq!(
                classify_error(&error(other)),
                FailureClass::ProtocolError,
                "{other}"
            );
        }
        // A spent budget, a refused key and a full context keep their classes.
        assert_eq!(
            classify_error(&error(
                "500 Budget has been exceeded! Current cost: 5.01, Max budget: 5.0"
            )),
            FailureClass::UsageLimit
        );
        assert_eq!(
            classify_error(&error("503 invalid api key")),
            FailureClass::LoginExpired
        );
        assert_eq!(
            classify_error(&error("500 prompt is too long")),
            FailureClass::ContextOverflow
        );
        // The agent process going away is not the provider's.
        let closed = AgentError {
            message: "the agent process exited: 503".into(),
            transport_closed: true,
            ..Default::default()
        };
        assert_eq!(classify_error(&closed), FailureClass::ProtocolError);
    }
}
