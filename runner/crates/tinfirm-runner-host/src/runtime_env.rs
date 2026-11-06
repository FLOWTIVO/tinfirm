//! The sandbox environment that gives a runtime its model: the credential, and the agent's model
//! (runner/README.md, "What the runtime gets"; ADR-0027, ADR-0041, ADR-0042).
//!
//! A run reaches its model through one of:
//! - a subscription of this host: a Claude login (Claude Code, whose `.credentials.json` the host
//!   mounts into `~/.claude`, ADR-0085) or a ChatGPT login (Codex, whose `CODEX_HOME` the host
//!   mounts); no variables for either;
//! - a provider's key, of this host (`anthropic-api-key`, `openai-api-key`) or of a company model
//!   credential, with the provider's own API or, for `anthropic` and `openai`, a gateway in front.
//!
//! Each runtime takes a provider's key the way its CLI reads one:
//!
//! | Runtime | Variables |
//! |---|---|
//! | Claude Code | `ANTHROPIC_API_KEY`; a gateway: `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`; Bedrock: `CLAUDE_CODE_USE_BEDROCK=1` and the AWS variables; the model in `ANTHROPIC_MODEL`, the effort in `CLAUDE_CODE_EFFORT_LEVEL` |
//! | Codex | `OPENAI_API_KEY` with the API-key login of `codex-acp`; a gateway: `OPENAI_BASE_URL`, and the gateway as a provider of its own in `CODEX_CONFIG`, without WebSockets ([`CODEX_GATEWAY_PROVIDER`]); the model and `model_reasoning_effort` in `CODEX_CONFIG` |
//! | pi | the provider's variable (`ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, the AWS variables, ...), `TINFIRM_MODEL_PROVIDER`, `TINFIRM_MODEL`, a gateway in `TINFIRM_MODEL_BASE_URL`, the effort in `TINFIRM_THINKING_LEVEL` (read by `tinfirm-pi-acp`) |
//! | OpenCode | the provider's variable, and `OPENCODE_CONFIG_CONTENT`: only the provider enabled, its model, a gateway's base URL; the effort goes over ACP instead ([`effort_in_env`]); `OPENCODE_DISABLE_CLAUDE_CODE_SKILLS=1` ([`OPENCODE_SKILLS_SWITCH`]) |
//!
//! The AWS variables of Bedrock are `AWS_REGION` and `AWS_DEFAULT_REGION`, and either
//! `AWS_BEARER_TOKEN_BEDROCK` (an Amazon Bedrock API key) or `AWS_ACCESS_KEY_ID` and
//! `AWS_SECRET_ACCESS_KEY`. A gateway also turns off the CLI's other traffic where the CLI has a
//! switch for it (`CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`, `PI_OFFLINE`), since the sandbox may
//! reach only the gateway; Claude Code on Bedrock gets the same switch.
//!
//! A gateway's key stays on the host ([`crate::model_proxy`], always on): the runtime gets the
//! run's model proxy as its base URL and [`DUMMY_KEY`] as its key, in the same variables
//! ([`Access::through_model_proxy`]), so a sandbox never gets a gateway's own URL or key.
//!
//! No runtime asks the model's provider to run a tool itself, in any run (ADR-0108): a
//! runtime's own switch for a provider-run tool is off whatever its model access, since through
//! a gateway the model relay and the model port refuse such a request with 400, and with a
//! provider's own key or a subscription nothing else would stop it. Codex's web search is off in `CODEX_CONFIG`
//! ([`codex_config`]); Claude Code's WebSearch, which is Anthropic's server-side `web_search`
//! tool, is a disallowed tool of the session ([`session_meta`]). OpenCode 2.0.29 and pi 1.0.4
//! send no provider-run tool by default (OpenCode's own web search calls its search providers
//! from the sandbox, through the egress proxy), so they need no switch.

use crate::egress_proxy::Rule;
use crate::model_proxy::DUMMY_KEY;
use serde_json::{Value, json};
use tinfirm_protocol::{CredentialKind, Effort, ModelFormat, Runtime};

/// `codex-acp` logs in with `OPENAI_API_KEY` when this asks it to.
pub const OPENAI_API_KEY_AUTH: &str = r#"{"methodId":"api-key"}"#;

/// Set to `1` for OpenCode: it reads a user's skills in `~/.claude/skills` as well as in
/// `~/.agents/skills`, where the host mounts them ([`crate::skills::mount_dir`]); with this
/// OpenCode 1 reads only the latter, so each skill reaches it once, from Tinfirm (ADR-0064).
/// OpenCode 2 (ADR-0061) reads both and has no such switch, so it ignores the variable; the
/// host mounts nothing in `~/.claude/skills` for it, which holds only what an earlier turn of
/// the agent wrote there itself.
pub const OPENCODE_SKILLS_SWITCH: &str = "OPENCODE_DISABLE_CLAUDE_CODE_SKILLS";

/// What a run reaches its model with.
#[derive(Debug, Clone, Copy)]
pub enum Access<'a> {
    /// A credential of this host that is a subscription, not a provider's key.
    Subscription {
        kind: CredentialKind,
        secret: &'a str,
    },
    /// A provider's key, of this host or of a company model credential.
    Provider(ProviderKey<'a>),
}

/// A provider's key and where it goes.
#[derive(Debug, Clone, Copy)]
pub struct ProviderKey<'a> {
    pub format: ModelFormat,
    /// A gateway in front of the provider (`anthropic`, `openai`).
    pub base_url: Option<&'a str>,
    /// `bedrock`: the AWS region.
    pub region: Option<&'a str>,
    /// `bedrock`: the access key ID whose secret access key is `key`.
    pub access_key_id: Option<&'a str>,
    pub key: &'a str,
    /// `base_url` is the run's model proxy and `key` its dummy key: the host holds the real one
    /// and connects to the gateway itself ([`Access::through_model_proxy`]).
    pub proxied: bool,
}

impl<'a> Access<'a> {
    /// The same access through the run's model proxy, whose base URL for the sandbox is `url`: a
    /// provider's key with a gateway gets `url` as its base URL and [`DUMMY_KEY`] as its key. Any
    /// other access stays as it is (the host proxies only gateways, see
    /// [`crate::model_proxy::applies`]).
    pub fn through_model_proxy(self, url: &'a str) -> Access<'a> {
        match self {
            Access::Provider(p) if p.base_url.is_some() => Access::Provider(ProviderKey {
                base_url: Some(url),
                key: DUMMY_KEY,
                proxied: true,
                ..p
            }),
            other => other,
        }
    }
}

/// The provider ID that pi and OpenCode both use for a format.
pub fn provider_id(format: ModelFormat) -> &'static str {
    match format {
        ModelFormat::Anthropic => "anthropic",
        ModelFormat::Openai => "openai",
        ModelFormat::Bedrock => "amazon-bedrock",
        ModelFormat::Google => "google",
        ModelFormat::Openrouter => "openrouter",
        ModelFormat::Mistral => "mistral",
        ModelFormat::Groq => "groq",
        ModelFormat::Xai => "xai",
        ModelFormat::Deepseek => "deepseek",
    }
}

/// Whether the runtime takes the agent's effort from the variables of [`runtime_env`]. OpenCode
/// does not: its levels are variants of each model, so the host sets the session's thought-level
/// option over ACP (`session/set_config_option`) to the nearest level the model offers.
pub fn effort_in_env(runtime: Runtime) -> bool {
    !matches!(runtime, Runtime::Opencode)
}

/// The variables of a run of `runtime` with `access`, the agent's `model` (trimmed, non-empty)
/// and its `effort` (v2; `None` leaves it to the runtime). Each runtime takes the effort its
/// own way, and falls back to the nearest level its model offers: Claude Code from
/// `CLAUDE_CODE_EFFORT_LEVEL`, Codex from `model_reasoning_effort`, pi from `--thinking`.
pub fn runtime_env(
    runtime: Runtime,
    access: Access<'_>,
    model: Option<&str>,
    effort: Option<Effort>,
) -> Vec<(String, String)> {
    let mut env: Vec<(String, String)> = Vec::new();
    let mut set = |key: &str, value: &str| env.push((key.to_string(), value.to_string()));
    match (runtime, access) {
        // A Claude login signs the runtime in through the mounted `.credentials.json`
        // (crate::credentials::CLAUDE_CREDENTIALS_IN_SANDBOX), a Codex login through the
        // mounted `CODEX_HOME`: neither needs a variable (ADR-0085).
        (Runtime::ClaudeCode | Runtime::Codex, Access::Subscription { .. }) => {}
        (Runtime::ClaudeCode, Access::Provider(p)) => match (p.format, p.base_url) {
            (ModelFormat::Anthropic, Some(url)) => {
                set("ANTHROPIC_BASE_URL", url);
                set("ANTHROPIC_AUTH_TOKEN", p.key);
                set("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
            }
            (ModelFormat::Bedrock, _) => {
                set("CLAUDE_CODE_USE_BEDROCK", "1");
                bedrock(&mut set, &p);
                set("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
            }
            _ => set("ANTHROPIC_API_KEY", p.key),
        },
        (Runtime::Codex, Access::Provider(p)) => {
            if let Some(url) = p.base_url {
                set("OPENAI_BASE_URL", url);
            }
            set("OPENAI_API_KEY", p.key);
            set("DEFAULT_AUTH_REQUEST", OPENAI_API_KEY_AUTH);
        }
        (Runtime::Pi, Access::Provider(p)) => {
            provider_key(&mut set, runtime, &p);
            set("TINFIRM_MODEL_PROVIDER", provider_id(p.format));
            if let Some(url) = p.base_url {
                set("TINFIRM_MODEL_BASE_URL", url);
                set("PI_OFFLINE", "1");
            }
            if let Some(model) = model {
                set("TINFIRM_MODEL", model);
            }
        }
        (Runtime::Opencode, Access::Provider(p)) => {
            provider_key(&mut set, runtime, &p);
            set(
                "OPENCODE_CONFIG_CONTENT",
                &opencode_config(&p, model).to_string(),
            );
        }
        // The host and the server never pair a subscription with another vendor's CLI.
        (Runtime::Pi | Runtime::Opencode, Access::Subscription { .. }) => {}
    }
    match runtime {
        Runtime::ClaudeCode => {
            if let Some(model) = model {
                env.push(("ANTHROPIC_MODEL".into(), model.into()));
            }
            if let Some(effort) = effort {
                env.push(("CLAUDE_CODE_EFFORT_LEVEL".into(), effort.level().into()));
            }
        }
        Runtime::Codex => {
            env.push(("CODEX_CONFIG".into(), codex_config(access, model, effort)));
        }
        Runtime::Pi => {
            if let Some(effort) = effort {
                env.push(("TINFIRM_THINKING_LEVEL".into(), effort.level().into()));
            }
        }
        Runtime::Opencode => {
            env.push((OPENCODE_SKILLS_SWITCH.into(), "1".into()));
        }
    }
    env
}

/// The AWS variables of a Bedrock key.
fn bedrock(set: &mut impl FnMut(&str, &str), p: &ProviderKey<'_>) {
    if let Some(region) = p.region {
        set("AWS_REGION", region);
        set("AWS_DEFAULT_REGION", region);
    }
    match p.access_key_id {
        Some(id) => {
            set("AWS_ACCESS_KEY_ID", id);
            set("AWS_SECRET_ACCESS_KEY", p.key);
        }
        None => set("AWS_BEARER_TOKEN_BEDROCK", p.key),
    }
}

/// The provider's own variable for pi and OpenCode, which read the same names (OpenCode's
/// Google provider reads `GOOGLE_GENERATIVE_AI_API_KEY` first). A gateway's key is a bearer token
/// for pi (`ANTHROPIC_AUTH_TOKEN`); OpenCode sends it from its config.
fn provider_key(set: &mut impl FnMut(&str, &str), runtime: Runtime, p: &ProviderKey<'_>) {
    match p.format {
        ModelFormat::Anthropic if p.base_url.is_some() && runtime == Runtime::Pi => {
            set("ANTHROPIC_AUTH_TOKEN", p.key);
        }
        ModelFormat::Anthropic => set("ANTHROPIC_API_KEY", p.key),
        ModelFormat::Openai => set("OPENAI_API_KEY", p.key),
        ModelFormat::Bedrock => bedrock(set, p),
        ModelFormat::Google => {
            set("GEMINI_API_KEY", p.key);
            if runtime == Runtime::Opencode {
                set("GOOGLE_GENERATIVE_AI_API_KEY", p.key);
            }
        }
        ModelFormat::Openrouter => set("OPENROUTER_API_KEY", p.key),
        ModelFormat::Mistral => set("MISTRAL_API_KEY", p.key),
        ModelFormat::Groq => set("GROQ_API_KEY", p.key),
        ModelFormat::Xai => set("XAI_API_KEY", p.key),
        ModelFormat::Deepseek => set("DEEPSEEK_API_KEY", p.key),
    }
}

/// The model in OpenCode's `provider/model` form: a model of the credential's provider, with
/// the provider in front unless it is there already (an OpenRouter model keeps its own slash:
/// `anthropic/claude-sonnet-4.5` is `openrouter/anthropic/claude-sonnet-4.5`).
pub fn opencode_model(format: ModelFormat, model: &str) -> String {
    let provider = provider_id(format);
    if model
        .strip_prefix(provider)
        .is_some_and(|rest| rest.starts_with('/'))
    {
        model.to_string()
    } else {
        format!("{provider}/{model}")
    }
}

/// `OPENCODE_CONFIG_CONTENT`: only the credential's provider (OpenCode would otherwise offer its
/// own free models, which need no key), the agent's model, no updates and no sharing, and a
/// gateway's base URL with the key as a bearer token (`{env:…}` is OpenCode's substitution, so
/// the key is not repeated here). OpenCode's Anthropic base URL ends in `/v1` (it appends
/// `/messages`), unlike the credential's, which is the root the other runtimes append
/// `/v1/messages` to; an OpenAI base URL ends in `/v1` for every runtime.
pub fn opencode_config(p: &ProviderKey<'_>, model: Option<&str>) -> Value {
    let provider = provider_id(p.format);
    let mut config = json!({
        "autoupdate": false,
        "share": "disabled",
        "enabled_providers": [provider],
    });
    if let Some(model) = model {
        config["model"] = json!(opencode_model(p.format, model));
    }
    if let Some(url) = p.base_url {
        let (key_env, base_url) = match p.format {
            ModelFormat::Anthropic => ("ANTHROPIC_API_KEY", format!("{url}/v1")),
            _ => ("OPENAI_API_KEY", url.to_string()),
        };
        config["provider"] = json!({
            provider: {
                "options": {
                    "baseURL": base_url,
                    "headers": {"Authorization": format!("Bearer {{env:{key_env}}}")},
                }
            }
        });
    }
    config
}

/// Claude Code's tools that no Tinfirm session offers the model: WebSearch is Anthropic's
/// server-side `web_search` tool, a tool the provider runs itself, which no Tinfirm run may use
/// (ADR-0108), whatever reaches the model: the model relay and the model port refuse it, and a
/// key of Anthropic's own, a Claude login or Bedrock would let it run. WebFetch stays: Claude
/// Code fetches in the sandbox, through its egress proxy.
pub const CLAUDE_CODE_DISALLOWED_TOOLS: &[&str] = &["WebSearch"];

/// The `_meta` of `session/new` and `session/load` for a run of `runtime`, or `None`. Claude
/// Code gets, whatever its model access
/// `{"claudeCode": {"options": {"disallowedTools": ["WebSearch"]}}}`: `claude-agent-acp` (0.87.0,
/// the version the CLAUDE image pins) passes `_meta.claudeCode.options` to the Agent SDK, whose
/// `disallowedTools` removes the tool from the model's context, the documented way to turn a
/// built-in tool off. The session's settings files cannot add it back: a disallowed tool stays
/// denied.
pub fn session_meta(runtime: Runtime) -> Option<serde_json::Map<String, Value>> {
    match runtime {
        Runtime::ClaudeCode => match json!({
            "claudeCode": {"options": {"disallowedTools": CLAUDE_CODE_DISALLOWED_TOOLS}}
        }) {
            Value::Object(meta) => Some(meta),
            _ => None,
        },
        _ => None,
    }
}

/// The name of the provider that Codex gets for an `openai` gateway.
pub const CODEX_GATEWAY_PROVIDER: &str = "tinfirm";

/// `CODEX_CONFIG` of `codex-acp`: a JSON object it merges into the config of every Codex
/// session. It carries the agent's model and effort (`model_reasoning_effort`) and, for an
/// `openai` gateway, the gateway as a provider of its own ([`CODEX_GATEWAY_PROVIDER`]): its base
/// URL (the gateway's, or the run's model proxy), its key from `OPENAI_API_KEY`, the Responses
/// API, and `supports_websockets: false`. Codex takes its base URL from its config, not from
/// `OPENAI_BASE_URL`, which the host sets as well for tools that do read it; and with the
/// built-in OpenAI provider (`openai_base_url`) Codex 0.156.1 first tries the Responses API over
/// a WebSocket, which the model proxy refuses and LiteLLM is not known to serve, for about 6 s
/// per session, and writes a fallback warning into the agent's reply. A provider's own key,
/// without a base URL, keeps the built-in provider.
///
/// Codex's own web search is off in every run (`web_search = "disabled"`, the top-level key of
/// Codex's config.toml, in Codex 0.156.1, the version the CODEX image pins; its default,
/// `cached`, adds the provider-run `web_search` tool to every request): no Tinfirm run may have
/// the provider run a tool (ADR-0108). Through a gateway the model relay and the model port
/// would refuse the request with 400; with a provider's own key or a ChatGPT login nothing
/// would, so the config is the only guard there.
fn codex_config(access: Access<'_>, model: Option<&str>, effort: Option<Effort>) -> String {
    let mut config = serde_json::Map::new();
    config.insert("web_search".into(), "disabled".into());
    if let Some(model) = model {
        config.insert("model".into(), model.into());
    }
    if let Some(effort) = effort {
        config.insert("model_reasoning_effort".into(), effort.level().into());
    }
    if let Access::Provider(ProviderKey {
        format: ModelFormat::Openai,
        base_url: Some(url),
        ..
    }) = access
    {
        config.insert("model_provider".into(), CODEX_GATEWAY_PROVIDER.into());
        config.insert(
            "model_providers".into(),
            json!({
                CODEX_GATEWAY_PROVIDER: {
                    "name": "Tinfirm gateway",
                    "base_url": url,
                    "env_key": "OPENAI_API_KEY",
                    "wire_api": "responses",
                    "supports_websockets": false,
                }
            }),
        );
    }
    Value::Object(config).to_string()
}

/// The hosts that a run of `runtime` with `access` talks to for its model: the egress proxy
/// lets a sandbox reach these and nothing else the operator did not allow (ADR-0044). A
/// gateway's host is the one in its URL; a provider's own key goes to the provider's API. A
/// gateway behind the run's model proxy needs none: the host connects to it, not the sandbox.
pub fn model_hosts(runtime: Runtime, access: Access<'_>) -> Vec<Rule> {
    let fixed = |hosts: &[&str]| -> Vec<Rule> {
        hosts
            .iter()
            .map(|h| Rule::parse(h).expect("a constant host is a valid rule"))
            .collect()
    };
    match access {
        Access::Subscription { .. } => match runtime {
            // A login's model requests, and its renewal at Anthropic's OAuth token endpoint
            // (ADR-0085): a `setup-token` never renewed, a login does.
            Runtime::ClaudeCode => fixed(&["api.anthropic.com", "platform.claude.com"]),
            Runtime::Codex => fixed(&["chatgpt.com", "auth.openai.com", "api.openai.com"]),
            Runtime::Pi | Runtime::Opencode => Vec::new(),
        },
        Access::Provider(p) => {
            if p.proxied {
                return Vec::new();
            }
            if let Some(url) = p.base_url {
                return Rule::for_url(url).into_iter().collect();
            }
            match p.format {
                ModelFormat::Anthropic => fixed(&["api.anthropic.com"]),
                ModelFormat::Openai => fixed(&["api.openai.com"]),
                // Without a region there is no host to name, and a wildcard over amazonaws.com
                // would open every bucket and API of AWS: the run has no model host (the
                // server requires a region for Bedrock, so this is a malformed assignment).
                ModelFormat::Bedrock => match p.region {
                    Some(region) if !region.is_empty() => {
                        Rule::parse(&format!("bedrock-runtime.{region}.amazonaws.com"))
                            .ok()
                            .into_iter()
                            .collect()
                    }
                    _ => Vec::new(),
                },
                ModelFormat::Google => fixed(&["generativelanguage.googleapis.com"]),
                ModelFormat::Openrouter => fixed(&["openrouter.ai"]),
                ModelFormat::Mistral => fixed(&["api.mistral.ai"]),
                ModelFormat::Groq => fixed(&["api.groq.com"]),
                ModelFormat::Xai => fixed(&["api.x.ai"]),
                ModelFormat::Deepseek => fixed(&["api.deepseek.com"]),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hosts(runtime: Runtime, access: Access<'_>) -> Vec<String> {
        model_hosts(runtime, access)
            .iter()
            .map(ToString::to_string)
            .collect()
    }

    #[test]
    fn model_hosts_are_the_providers_or_the_gateways() {
        let sub = |kind| Access::Subscription { kind, secret: "s" };
        assert_eq!(
            hosts(Runtime::ClaudeCode, sub(CredentialKind::ClaudeLogin)),
            ["api.anthropic.com:443", "platform.claude.com:443"]
        );
        assert_eq!(
            hosts(Runtime::Codex, sub(CredentialKind::CodexChatgptAuth)),
            [
                "chatgpt.com:443",
                "auth.openai.com:443",
                "api.openai.com:443"
            ]
        );
        let key = |format, base_url, region| {
            Access::Provider(ProviderKey {
                format,
                base_url,
                region,
                access_key_id: None,
                key: "k",
                proxied: false,
            })
        };
        assert_eq!(
            hosts(Runtime::Pi, key(ModelFormat::Anthropic, None, None)),
            ["api.anthropic.com:443"]
        );
        // A gateway replaces the provider's host, and its port counts.
        assert_eq!(
            hosts(
                Runtime::ClaudeCode,
                key(
                    ModelFormat::Anthropic,
                    Some("https://litellm.corp:8443/v1"),
                    None
                )
            ),
            ["litellm.corp:8443"]
        );
        assert_eq!(
            hosts(Runtime::Pi, key(ModelFormat::Groq, None, None)),
            ["api.groq.com:443"]
        );
        assert_eq!(
            hosts(
                Runtime::Opencode,
                key(ModelFormat::Bedrock, None, Some("eu-central-1"))
            ),
            ["bedrock-runtime.eu-central-1.amazonaws.com:443"]
        );
        // No region: no host, never a wildcard over AWS.
        for region in [None, Some("")] {
            assert!(hosts(Runtime::Pi, key(ModelFormat::Bedrock, None, region)).is_empty());
        }
    }

    const KEY: &str = "sk-TEST-ONLY-key";

    fn key(format: ModelFormat) -> ProviderKey<'static> {
        ProviderKey {
            format,
            base_url: None,
            region: None,
            access_key_id: None,
            key: KEY,
            proxied: false,
        }
    }

    fn gateway(format: ModelFormat, url: &'static str) -> ProviderKey<'static> {
        ProviderKey {
            base_url: Some(url),
            ..key(format)
        }
    }

    fn pairs(expected: &[(&str, &str)]) -> Vec<(String, String)> {
        expected
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    fn get<'a>(env: &'a [(String, String)], name: &str) -> Option<&'a str> {
        env.iter().find(|(k, _)| k == name).map(|(_, v)| v.as_str())
    }

    #[test]
    fn claude_code_takes_a_key_a_gateway_or_bedrock() {
        let env = |p, model| runtime_env(Runtime::ClaudeCode, Access::Provider(p), model, None);
        assert_eq!(
            env(key(ModelFormat::Anthropic), Some("claude-sonnet-4-5")),
            pairs(&[
                ("ANTHROPIC_API_KEY", KEY),
                ("ANTHROPIC_MODEL", "claude-sonnet-4-5")
            ])
        );
        assert_eq!(
            env(
                gateway(ModelFormat::Anthropic, "https://litellm.example.test"),
                None
            ),
            pairs(&[
                ("ANTHROPIC_BASE_URL", "https://litellm.example.test"),
                ("ANTHROPIC_AUTH_TOKEN", KEY),
                ("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"),
            ])
        );
        let bedrock_key = ProviderKey {
            region: Some("eu-central-1"),
            ..key(ModelFormat::Bedrock)
        };
        assert_eq!(
            env(bedrock_key, None),
            pairs(&[
                ("CLAUDE_CODE_USE_BEDROCK", "1"),
                ("AWS_REGION", "eu-central-1"),
                ("AWS_DEFAULT_REGION", "eu-central-1"),
                ("AWS_BEARER_TOKEN_BEDROCK", KEY),
                ("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"),
            ])
        );
        let access_keys = ProviderKey {
            access_key_id: Some("AKIAIOSFODNN7EXAMPLE"),
            ..bedrock_key
        };
        let with_keys = env(access_keys, Some("us.anthropic.claude-sonnet-4-5-v1:0"));
        assert_eq!(
            get(&with_keys, "AWS_ACCESS_KEY_ID"),
            Some("AKIAIOSFODNN7EXAMPLE")
        );
        assert_eq!(get(&with_keys, "AWS_SECRET_ACCESS_KEY"), Some(KEY));
        assert_eq!(get(&with_keys, "AWS_BEARER_TOKEN_BEDROCK"), None);
        assert_eq!(
            get(&with_keys, "ANTHROPIC_MODEL"),
            Some("us.anthropic.claude-sonnet-4-5-v1:0")
        );
        // A Claude login needs no variable: the mounted credentials file signs Claude Code in.
        assert!(
            runtime_env(
                Runtime::ClaudeCode,
                Access::Subscription {
                    kind: CredentialKind::ClaudeLogin,
                    secret: ""
                },
                None,
                None
            )
            .is_empty()
        );
    }

    #[test]
    fn claude_code_has_no_web_search_in_any_run() {
        // WebSearch, Anthropic's server-side tool, is a disallowed tool of every Claude Code
        // session: through a gateway the relay would refuse it, and with a key of Anthropic's
        // own, Bedrock or a login nothing else would stop it (ADR-0108).
        assert_eq!(
            session_meta(Runtime::ClaudeCode).map(Value::Object),
            Some(json!({"claudeCode": {"options": {"disallowedTools": ["WebSearch"]}}}))
        );
        // WebFetch runs in the sandbox, through its egress proxy: it stays.
        assert!(!CLAUDE_CODE_DISALLOWED_TOOLS.contains(&"WebFetch"));
        // The other runtimes take no session `_meta`: Codex's switch is in CODEX_CONFIG, and
        // OpenCode and pi send no provider-run tool.
        for runtime in [Runtime::Codex, Runtime::Opencode, Runtime::Pi] {
            assert_eq!(session_meta(runtime), None, "{runtime:?}");
        }
    }

    /// `CODEX_CONFIG`'s gateway provider with `base_url`, next to the other `fields`.
    fn codex_gateway(base_url: &str, fields: Value) -> Value {
        let mut config = json!({
            "model_provider": "tinfirm",
            "web_search": "disabled",
            "model_providers": {"tinfirm": {
                "name": "Tinfirm gateway",
                "base_url": base_url,
                "env_key": "OPENAI_API_KEY",
                "wire_api": "responses",
                "supports_websockets": false,
            }},
        });
        if let (Some(config), Value::Object(fields)) = (config.as_object_mut(), fields) {
            config.extend(fields);
        }
        config
    }

    fn codex_config_of(env: &[(String, String)]) -> Value {
        serde_json::from_str(get(env, "CODEX_CONFIG").unwrap()).unwrap()
    }

    #[test]
    fn codex_takes_a_key_or_a_gateway() {
        let env = |p, model| runtime_env(Runtime::Codex, Access::Provider(p), model, None);
        // A provider's own key: Codex's built-in OpenAI provider.
        assert_eq!(
            env(key(ModelFormat::Openai), Some("gpt-5.5")),
            pairs(&[
                ("OPENAI_API_KEY", KEY),
                ("DEFAULT_AUTH_REQUEST", OPENAI_API_KEY_AUTH),
                (
                    "CODEX_CONFIG",
                    r#"{"web_search":"disabled","model":"gpt-5.5"}"#
                ),
            ])
        );
        // A gateway: a provider of its own with the gateway's URL, without WebSockets, also
        // without the model proxy.
        let via_gateway = env(
            gateway(ModelFormat::Openai, "https://litellm.example.test/v1"),
            Some("gpt-5.5"),
        );
        assert_eq!(
            get(&via_gateway, "OPENAI_BASE_URL"),
            Some("https://litellm.example.test/v1")
        );
        assert_eq!(get(&via_gateway, "OPENAI_API_KEY"), Some(KEY));
        assert_eq!(
            get(&via_gateway, "DEFAULT_AUTH_REQUEST"),
            Some(OPENAI_API_KEY_AUTH)
        );
        assert_eq!(
            codex_config_of(&via_gateway),
            codex_gateway(
                "https://litellm.example.test/v1",
                json!({"model": "gpt-5.5"})
            )
        );
        assert!(!get(&via_gateway, "CODEX_CONFIG").unwrap().contains(KEY));
        // No run may have the provider run a tool (ADR-0108): Codex's web search is off
        // through a gateway, with a provider's own key, and with a ChatGPT login.
        assert_eq!(codex_config_of(&via_gateway)["web_search"], "disabled");
        assert_eq!(
            codex_config_of(&env(key(ModelFormat::Openai), Some("gpt-5.5")))["web_search"],
            "disabled"
        );
        assert!(
            !get(&via_gateway, "CODEX_CONFIG")
                .unwrap()
                .contains("openai_base_url")
        );
        let chatgpt = Access::Subscription {
            kind: CredentialKind::CodexChatgptAuth,
            secret: "",
        };
        assert_eq!(
            runtime_env(Runtime::Codex, chatgpt, None, None),
            pairs(&[("CODEX_CONFIG", r#"{"web_search":"disabled"}"#)])
        );
    }

    #[test]
    fn pi_takes_the_providers_variable_and_names_the_provider() {
        let env = |p, model| runtime_env(Runtime::Pi, Access::Provider(p), model, None);
        assert_eq!(
            env(
                key(ModelFormat::Openrouter),
                Some("anthropic/claude-sonnet-4.5")
            ),
            pairs(&[
                ("OPENROUTER_API_KEY", KEY),
                ("TINFIRM_MODEL_PROVIDER", "openrouter"),
                ("TINFIRM_MODEL", "anthropic/claude-sonnet-4.5"),
            ])
        );
        assert_eq!(
            env(
                gateway(ModelFormat::Anthropic, "https://litellm.example.test"),
                None
            ),
            pairs(&[
                ("ANTHROPIC_AUTH_TOKEN", KEY),
                ("TINFIRM_MODEL_PROVIDER", "anthropic"),
                ("TINFIRM_MODEL_BASE_URL", "https://litellm.example.test"),
                ("PI_OFFLINE", "1"),
            ])
        );
        let bedrock = env(
            ProviderKey {
                region: Some("us-west-2"),
                ..key(ModelFormat::Bedrock)
            },
            None,
        );
        assert_eq!(
            get(&bedrock, "TINFIRM_MODEL_PROVIDER"),
            Some("amazon-bedrock")
        );
        assert_eq!(get(&bedrock, "AWS_BEARER_TOKEN_BEDROCK"), Some(KEY));
        assert_eq!(get(&bedrock, "CLAUDE_CODE_USE_BEDROCK"), None);
        for (format, variable) in [
            (ModelFormat::Anthropic, "ANTHROPIC_API_KEY"),
            (ModelFormat::Openai, "OPENAI_API_KEY"),
            (ModelFormat::Google, "GEMINI_API_KEY"),
            (ModelFormat::Mistral, "MISTRAL_API_KEY"),
            (ModelFormat::Groq, "GROQ_API_KEY"),
            (ModelFormat::Xai, "XAI_API_KEY"),
            (ModelFormat::Deepseek, "DEEPSEEK_API_KEY"),
        ] {
            assert_eq!(
                get(&env(key(format), None), variable),
                Some(KEY),
                "{format}"
            );
        }
    }

    #[test]
    fn opencode_gets_its_provider_model_and_gateway_in_its_config() {
        let config = |p, model| {
            let env = runtime_env(Runtime::Opencode, Access::Provider(p), model, None);
            serde_json::from_str::<Value>(get(&env, "OPENCODE_CONFIG_CONTENT").unwrap()).unwrap()
        };
        assert_eq!(
            config(key(ModelFormat::Anthropic), Some("claude-sonnet-4-5")),
            json!({
                "autoupdate": false, "share": "disabled",
                "enabled_providers": ["anthropic"], "model": "anthropic/claude-sonnet-4-5",
            })
        );
        assert_eq!(
            config(
                key(ModelFormat::Openrouter),
                Some("anthropic/claude-sonnet-4.5")
            )["model"],
            "openrouter/anthropic/claude-sonnet-4.5"
        );
        assert_eq!(
            config(
                key(ModelFormat::Openrouter),
                Some("openrouter/anthropic/claude-sonnet-4.5")
            )["model"],
            "openrouter/anthropic/claude-sonnet-4.5"
        );
        assert_eq!(
            config(key(ModelFormat::Bedrock), None)["enabled_providers"],
            json!(["amazon-bedrock"])
        );
        let via_gateway = config(
            gateway(ModelFormat::Openai, "https://litellm.example.test/v1"),
            None,
        );
        assert_eq!(
            via_gateway["provider"],
            json!({"openai": {"options": {
                "baseURL": "https://litellm.example.test/v1",
                "headers": {"Authorization": "Bearer {env:OPENAI_API_KEY}"},
            }}})
        );
        // An Anthropic gateway's root gets OpenCode's `/v1`.
        assert_eq!(
            config(
                gateway(ModelFormat::Anthropic, "https://gw.example.test"),
                None
            )["provider"]["anthropic"]["options"]["baseURL"],
            "https://gw.example.test/v1"
        );
        // The key is in the provider's variable only, never in the config.
        let env = runtime_env(
            Runtime::Opencode,
            Access::Provider(gateway(ModelFormat::Anthropic, "https://gw.example.test")),
            None,
            None,
        );
        assert_eq!(get(&env, "ANTHROPIC_API_KEY"), Some(KEY));
        assert!(!get(&env, "OPENCODE_CONFIG_CONTENT").unwrap().contains(KEY));
        let google = runtime_env(
            Runtime::Opencode,
            Access::Provider(key(ModelFormat::Google)),
            None,
            None,
        );
        assert_eq!(get(&google, "GOOGLE_GENERATIVE_AI_API_KEY"), Some(KEY));
        assert_eq!(get(&google, "GEMINI_API_KEY"), Some(KEY));
    }

    #[test]
    fn opencode_reads_the_skills_of_agents_skills_only() {
        let env = |runtime, p| runtime_env(runtime, Access::Provider(p), None, None);
        for p in [
            key(ModelFormat::Anthropic),
            key(ModelFormat::Openai),
            gateway(ModelFormat::Openai, "https://litellm.example.test/v1"),
        ] {
            assert_eq!(
                get(&env(Runtime::Opencode, p), OPENCODE_SKILLS_SWITCH),
                Some("1")
            );
        }
        // The others read only their own skill directory already.
        for runtime in [Runtime::ClaudeCode, Runtime::Pi] {
            assert_eq!(
                get(
                    &env(runtime, key(ModelFormat::Anthropic)),
                    OPENCODE_SKILLS_SWITCH
                ),
                None
            );
        }
        assert_eq!(
            get(
                &env(Runtime::Codex, key(ModelFormat::Openai)),
                OPENCODE_SKILLS_SWITCH
            ),
            None
        );
        // `--sandbox-env` cannot turn it off again.
        assert!(
            crate::sandbox::parse_env_entry("OPENCODE_DISABLE_CLAUDE_CODE_SKILLS=0")
                .unwrap_err()
                .contains("set by the runner host")
        );
    }

    #[test]
    fn opencode_models_get_the_provider_once() {
        assert_eq!(
            opencode_model(ModelFormat::Anthropic, "claude-x"),
            "anthropic/claude-x"
        );
        assert_eq!(
            opencode_model(ModelFormat::Anthropic, "anthropic/claude-x"),
            "anthropic/claude-x"
        );
        assert_eq!(
            opencode_model(ModelFormat::Bedrock, "anthropic.claude-x"),
            "amazon-bedrock/anthropic.claude-x"
        );
        assert_eq!(
            opencode_model(ModelFormat::Openai, "openai-like/x"),
            "openai/openai-like/x"
        );
    }
    #[test]
    fn each_runtime_takes_the_effort_its_own_way() {
        let effort = Some(Effort::Xhigh);
        let claude = runtime_env(
            Runtime::ClaudeCode,
            Access::Provider(key(ModelFormat::Anthropic)),
            None,
            effort,
        );
        assert_eq!(get(&claude, "CLAUDE_CODE_EFFORT_LEVEL"), Some("xhigh"));
        let subscription = runtime_env(
            Runtime::ClaudeCode,
            Access::Subscription {
                kind: CredentialKind::ClaudeLogin,
                secret: "",
            },
            None,
            effort,
        );
        assert_eq!(
            get(&subscription, "CLAUDE_CODE_EFFORT_LEVEL"),
            Some("xhigh")
        );
        let codex = runtime_env(
            Runtime::Codex,
            Access::Provider(key(ModelFormat::Openai)),
            Some("gpt-5.5"),
            effort,
        );
        assert_eq!(
            serde_json::from_str::<Value>(get(&codex, "CODEX_CONFIG").unwrap()).unwrap(),
            json!({"web_search": "disabled", "model": "gpt-5.5", "model_reasoning_effort": "xhigh"})
        );
        // A ChatGPT login has no other variables, but the effort still needs its config.
        let chatgpt = Access::Subscription {
            kind: CredentialKind::CodexChatgptAuth,
            secret: "{}",
        };
        assert_eq!(
            get(
                &runtime_env(Runtime::Codex, chatgpt, None, effort),
                "CODEX_CONFIG"
            ),
            Some(r#"{"web_search":"disabled","model_reasoning_effort":"xhigh"}"#)
        );
        let pi = runtime_env(
            Runtime::Pi,
            Access::Provider(key(ModelFormat::Anthropic)),
            None,
            effort,
        );
        assert_eq!(get(&pi, "TINFIRM_THINKING_LEVEL"), Some("xhigh"));
        // OpenCode takes it over ACP; its config does not change.
        let opencode = runtime_env(
            Runtime::Opencode,
            Access::Provider(key(ModelFormat::Anthropic)),
            None,
            effort,
        );
        let without = runtime_env(
            Runtime::Opencode,
            Access::Provider(key(ModelFormat::Anthropic)),
            None,
            None,
        );
        assert_eq!(opencode, without);
        assert!(!effort_in_env(Runtime::Opencode));
        assert!(
            [Runtime::ClaudeCode, Runtime::Codex, Runtime::Pi]
                .into_iter()
                .all(effort_in_env)
        );
        // No effort: no variable, and Codex's config only when something else needs it.
        for runtime in Runtime::ALL {
            let env = runtime_env(
                runtime,
                Access::Provider(key(ModelFormat::Anthropic)),
                None,
                None,
            );
            assert!(
                env.iter().all(|(k, v)| k != "CLAUDE_CODE_EFFORT_LEVEL"
                    && k != "TINFIRM_THINKING_LEVEL"
                    && !v.contains("model_reasoning_effort")),
                "{runtime}: {env:?}"
            );
        }
    }

    /// The sandbox's base URL of a run's model proxy (crate::model_proxy) for a gateway at a root
    /// and one under `/v1`.
    const PROXY_ROOT: &str = "http://172.17.0.1:41234/run-secret-TEST";
    const PROXY_V1: &str = "http://172.17.0.1:41234/run-secret-TEST/v1";

    fn proxied(
        format: ModelFormat,
        gateway_url: &'static str,
        proxy_url: &'static str,
    ) -> Access<'static> {
        Access::Provider(gateway(format, gateway_url)).through_model_proxy(proxy_url)
    }

    /// No variable holds the real key or names the real gateway.
    fn assert_keyless(env: &[(String, String)]) {
        for (name, value) in env {
            assert!(!value.contains(KEY), "{name} holds the key");
            assert!(
                !value.contains("litellm.example.test"),
                "{name} names the gateway"
            );
        }
    }

    #[test]
    fn through_the_model_proxy_each_runtime_gets_the_proxy_and_a_dummy_key() {
        let effort = Some(Effort::High);
        // Claude Code: the proxy as its base URL, the dummy as its bearer token.
        let claude = runtime_env(
            Runtime::ClaudeCode,
            proxied(
                ModelFormat::Anthropic,
                "https://litellm.example.test",
                PROXY_ROOT,
            ),
            Some("claude-sonnet-4-5"),
            effort,
        );
        assert_keyless(&claude);
        assert_eq!(
            claude,
            pairs(&[
                ("ANTHROPIC_BASE_URL", PROXY_ROOT),
                ("ANTHROPIC_AUTH_TOKEN", DUMMY_KEY),
                ("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"),
                ("ANTHROPIC_MODEL", "claude-sonnet-4-5"),
                ("CLAUDE_CODE_EFFORT_LEVEL", "high"),
            ])
        );

        // Codex: the gateway as a provider of its own, WebSockets off, model and effort kept.
        let codex = runtime_env(
            Runtime::Codex,
            proxied(
                ModelFormat::Openai,
                "https://litellm.example.test/v1",
                PROXY_V1,
            ),
            Some("gpt-5.5"),
            effort,
        );
        assert_keyless(&codex);
        assert_eq!(get(&codex, "OPENAI_API_KEY"), Some(DUMMY_KEY));
        assert_eq!(get(&codex, "OPENAI_BASE_URL"), Some(PROXY_V1));
        assert_eq!(
            get(&codex, "DEFAULT_AUTH_REQUEST"),
            Some(OPENAI_API_KEY_AUTH)
        );
        assert_eq!(
            codex_config_of(&codex),
            codex_gateway(
                PROXY_V1,
                json!({"model": "gpt-5.5", "model_reasoning_effort": "high"})
            )
        );

        // pi: the proxy in TINFIRM_MODEL_BASE_URL, the dummy in the provider's variable.
        let pi = runtime_env(
            Runtime::Pi,
            proxied(
                ModelFormat::Anthropic,
                "https://litellm.example.test",
                PROXY_ROOT,
            ),
            Some("claude-sonnet-4-5"),
            None,
        );
        assert_keyless(&pi);
        assert_eq!(
            pi,
            pairs(&[
                ("ANTHROPIC_AUTH_TOKEN", DUMMY_KEY),
                ("TINFIRM_MODEL_PROVIDER", "anthropic"),
                ("TINFIRM_MODEL_BASE_URL", PROXY_ROOT),
                ("PI_OFFLINE", "1"),
                ("TINFIRM_MODEL", "claude-sonnet-4-5"),
            ])
        );
        let pi_openai = runtime_env(
            Runtime::Pi,
            proxied(
                ModelFormat::Openai,
                "https://litellm.example.test/v1",
                PROXY_V1,
            ),
            None,
            None,
        );
        assert_keyless(&pi_openai);
        assert_eq!(get(&pi_openai, "OPENAI_API_KEY"), Some(DUMMY_KEY));
        assert_eq!(get(&pi_openai, "TINFIRM_MODEL_BASE_URL"), Some(PROXY_V1));

        // OpenCode: the proxy in its config (with OpenCode's `/v1` for Anthropic), the dummy in
        // the provider's variable.
        let config = |env: &[(String, String)]| {
            serde_json::from_str::<Value>(get(env, "OPENCODE_CONFIG_CONTENT").unwrap()).unwrap()
        };
        let opencode = runtime_env(
            Runtime::Opencode,
            proxied(
                ModelFormat::Anthropic,
                "https://litellm.example.test",
                PROXY_ROOT,
            ),
            None,
            None,
        );
        assert_keyless(&opencode);
        assert_eq!(get(&opencode, "ANTHROPIC_API_KEY"), Some(DUMMY_KEY));
        assert_eq!(
            config(&opencode)["provider"]["anthropic"]["options"]["baseURL"],
            format!("{PROXY_ROOT}/v1")
        );
        let opencode_openai = runtime_env(
            Runtime::Opencode,
            proxied(
                ModelFormat::Openai,
                "https://litellm.example.test/v1",
                PROXY_V1,
            ),
            None,
            None,
        );
        assert_keyless(&opencode_openai);
        assert_eq!(get(&opencode_openai, "OPENAI_API_KEY"), Some(DUMMY_KEY));
        assert_eq!(
            config(&opencode_openai)["provider"]["openai"]["options"]["baseURL"],
            PROXY_V1
        );
    }

    #[test]
    fn the_model_proxy_changes_only_a_gateway() {
        // A provider's own API, Bedrock and a subscription keep their variables.
        for access in [
            Access::Provider(key(ModelFormat::Anthropic)),
            Access::Provider(ProviderKey {
                region: Some("eu-central-1"),
                ..key(ModelFormat::Bedrock)
            }),
            Access::Subscription {
                kind: CredentialKind::ClaudeLogin,
                secret: "",
            },
        ] {
            for runtime in Runtime::ALL {
                assert_eq!(
                    runtime_env(runtime, access.through_model_proxy(PROXY_ROOT), None, None),
                    runtime_env(runtime, access, None, None),
                    "{runtime}"
                );
            }
        }
        // Without the proxy Codex gets the same provider, with the gateway's own URL and key.
        let direct = Access::Provider(gateway(
            ModelFormat::Openai,
            "https://litellm.example.test/v1",
        ));
        let env = runtime_env(Runtime::Codex, direct, None, None);
        assert_eq!(
            codex_config_of(&env),
            codex_gateway("https://litellm.example.test/v1", json!({}))
        );
        assert_eq!(get(&env, "OPENAI_API_KEY"), Some(KEY));
    }

    #[test]
    fn a_proxied_gateway_leaves_the_egress_allowlist() {
        let direct = Access::Provider(gateway(ModelFormat::Openai, "https://litellm.corp:8443/v1"));
        assert_eq!(hosts(Runtime::Codex, direct), ["litellm.corp:8443"]);
        assert!(hosts(Runtime::Codex, direct.through_model_proxy(PROXY_V1)).is_empty());
        // A provider's own API stays on it.
        let own = Access::Provider(key(ModelFormat::Openai));
        assert_eq!(
            hosts(Runtime::Codex, own.through_model_proxy(PROXY_V1)),
            ["api.openai.com:443"]
        );
    }
}
