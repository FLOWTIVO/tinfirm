//! Model proxy for the sandbox (platform redesign, Part 7; threat model 7.13 and R23).
//!
//! The key of a run's model gateway never enters the sandbox. A run whose `modelAccess` has a
//! base URL (an `anthropic` or `openai` gateway, such as LiteLLM; see [`applies`]) gets a listener
//! of its own, and its runtime gets that listener as its base URL and [`DUMMY_KEY`] as its key
//! ([`crate::runtime_env`]). The listener swaps the real key in and forwards to the gateway, so a
//! prompt-injected agent finds only the dummy, and can use the model only through its own run's
//! listener, only while the run lasts.
//!
//! **Always on** (ADR-0059, decision 5): no option turns it off, because the server cannot tell
//! whether a host proxies (the protocol does not change), so a host must never be able to put a
//! gateway's key into the sandbox. A gateway's run whose listener cannot start fails; `run` checks
//! at start that the listeners can bind where the sandboxes reach this host ([`check_endpoint`]),
//! so that a setup where they never could is refused then. `run --model-proxy`
//! (`TINFIRM_RUNNER_MODEL_PROXY`), the opt-in of version 0.1.0, is accepted for one release and
//! does nothing.
//!
//! - **One listener per run**, plain HTTP on a port of the address where the sandbox reaches the
//!   host's other listeners (the git proxy's [`Endpoint`]: the sandbox network's gateway), started
//!   with the run and stopped when the [`ModelProxyGuard`] is dropped; afterwards nothing answers
//!   on its port. It refuses to listen on an unspecified address, like the egress proxy.
//! - **A random per-run secret path prefix.** The sandbox's base URL is
//!   `http://<host>:<port>/<secret>` followed by the path of the real base URL (an Anthropic
//!   gateway is a root; an OpenAI one ends in `/v1`). Any other first path segment is `404`.
//! - **The path rule.** A request for `/<secret><tail>` is forwarded to the gateway's origin with
//!   `<tail>` (and the request's query) only when `<tail>` is the real base path followed by one
//!   of the model API endpoints the runtimes use, compared byte for byte (no normalisation, so
//!   `..`, `//` and percent-escapes never match):
//!
//!   | Format | Endpoint after the base path | Method |
//!   |---|---|---|
//!   | `anthropic` | `/v1/messages`, `/v1/messages/count_tokens` | `POST` |
//!   | `anthropic` | `/v1/models` | `GET` |
//!   | `openai` | `/responses`, `/chat/completions` | `POST` |
//!   | `openai` | `/models` | `GET` |
//!
//!   Any other path is `404` and another method `405`, so the sandbox cannot reach the gateway's
//!   other endpoints (key management, spend, files, batches) with the principal's key.
//! - **Credentials.** Every credential header the sandbox sends is dropped ([`CREDENTIAL_HEADERS`]:
//!   `Authorization`, `x-api-key`, `api-key`, `Proxy-Authorization`, LiteLLM's and Gemini's key
//!   headers, cookies, and Tinfirm's [`HOST_TOKEN_HEADER`]), and so are the hop-by-hop and
//!   forwarding headers; then `Authorization: Bearer <real key>` is set. Other headers
//!   (`anthropic-version`, `anthropic-beta`, `openai-beta`, the SDKs' own) pass.
//! - **The host's own token** (since 0.3.0; package 2's open point 6). To a gateway whose origin is
//!   this host's server's (the origin of `login --server`, where the host's WebSocket and its
//!   token already go: the Tinfirm server's model relay), the proxy also sends the host's runner
//!   token in [`HOST_TOKEN_HEADER`], so that the relay takes a run's token only from the host it
//!   was made for: a copied run token is worthless without the host's token, which never leaves
//!   this process for the sandbox. Any other gateway never gets it ([`HostToken`]), and a relay's
//!   base URL at another origin is logged, since the server refuses this version's model requests
//!   without the token.
//! - **Outbound.** https only, to the base URL's origin (plain http only for tests), with the
//!   system's root certificates. The proxy resolves the name itself and connects to an address it
//!   checked, with the egress proxy's rules: loopback, link-local (cloud metadata), multicast,
//!   reserved and this machine's own addresses are refused, private ones unless
//!   `--egress-allow-private`. One exception, the operator's: the gateway whose origin
//!   `--model-proxy-local-gateway` names ([`local_gateway_origin`]) may be on loopback and this
//!   machine's own addresses, such as a LiteLLM next to the runner host. The listener's own address
//!   and the addresses no gateway has (cloud metadata, link-local, reserved) stay refused for it
//!   too. Redirects are not followed (`502`).
//! - **Streaming.** The answer goes back as it arrives (SSE); nothing is buffered and a streaming
//!   answer has no total timeout, only an idle one ([`IDLE_TIMEOUT`]) between two of its parts.
//!   The request body streams too, up to [`MAX_REQUEST_BODY`]. At most [`MAX_CONNECTIONS`]
//!   connections per run are served at once; more wait.
//! - **No WebSockets.** A request to upgrade the connection is refused with `501`: neither the
//!   proxy nor LiteLLM serves the Responses API over a WebSocket (Codex is told so in its config).
//! - **The key never goes back.** It is never logged. The gateway's answer is scanned for it:
//!   a header that holds it turns the answer into a `502`, and a body that holds it is cut off
//!   before its last byte reaches the sandbox. The host also keeps the key in the run's
//!   redactor.

use std::convert::Infallible;
use std::future::Future;
use std::net::{IpAddr, SocketAddr};
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::task::{Context as TaskContext, Poll};
use std::time::Duration;

use aho_corasick::AhoCorasick;
use anyhow::{Context, Result, anyhow, bail};
use http_body_util::combinators::UnsyncBoxBody;
use http_body_util::{BodyExt, Full, LengthLimitError, Limited};
use hyper::body::{Body, Bytes, Frame, Incoming};
use hyper::header::{self, HeaderMap, HeaderValue};
use hyper::{Method, Request, Response, StatusCode, Uri};
use hyper_util::rt::{TokioIo, TokioTimer};
use tinfirm_protocol::{ModelAccess, ModelFormat};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::egress_proxy::{EgressSettings, is_local_address, refused_address, strip_hop_by_hop};
use crate::git_proxy::{Endpoint, host_port, new_secret, tls_config};
use crate::secret_eq::secret_eq;

/// The key the sandbox's runtime gets instead of the real one: no secret, and never sent on.
pub const DUMMY_KEY: &str = "tinfirm-model-proxy-dummy-key";
/// Largest request body passed to the gateway (prompts with files and images run to megabytes;
/// Anthropic's own limit is 32 MB).
pub const MAX_REQUEST_BODY: u64 = 64 * 1024 * 1024;
/// Open connections per run; more wait until one closes.
pub const MAX_CONNECTIONS: usize = 64;
/// Between two parts of the gateway's answer (and until its headers): a streaming answer has no
/// total limit, but one that stops moving ends. Above the runtimes' own idle limits (Codex: 5 min).
pub const IDLE_TIMEOUT: Duration = Duration::from_secs(600);
const HEADER_READ_TIMEOUT: Duration = Duration::from_secs(30);
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(10);
/// The TCP connection and the TLS handshake.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

/// The header in which the proxy sends the host's own runner token to the Tinfirm server's model
/// relay, next to the run's token as the bearer ([`HostToken`]).
pub const HOST_TOKEN_HEADER: &str = "x-tinfirm-runner-token";
/// The path of the Tinfirm server's model relay, below the server's base URL.
const RELAY_PATH: &str = "/api/runner/v1/model/";

/// Request headers that carry a credential: none of the sandbox's reaches the gateway.
pub const CREDENTIAL_HEADERS: [&str; 9] = [
    "authorization",
    "proxy-authorization",
    "x-api-key",
    "api-key",
    "x-litellm-api-key",
    "x-goog-api-key",
    "cookie",
    "cookie2",
    HOST_TOKEN_HEADER,
];
/// Request headers that claim where a request comes from: a gateway may trust them.
const FORWARDING_HEADERS: [&str; 6] = [
    "forwarded",
    "x-forwarded-for",
    "x-forwarded-host",
    "x-forwarded-proto",
    "x-real-ip",
    "via",
];

type BoxError = Box<dyn std::error::Error + Send + Sync>;
type ProxyBody = UnsyncBoxBody<Bytes, BoxError>;

/// Whether a run with this model access goes through the model proxy: a gateway (a base URL) of
/// the `anthropic` or `openai` format. A provider's own API, other formats and the host's own
/// credentials keep the key in the sandbox's environment, as without the proxy.
pub fn applies(access: &ModelAccess) -> bool {
    access.base_url.is_some()
        && matches!(access.format, ModelFormat::Anthropic | ModelFormat::Openai)
}

/// The host-wide settings of the model proxy, which is always on: they only widen where a gateway
/// may be.
#[derive(Debug, Clone, Default)]
pub struct ModelProxySettings {
    /// `--egress-allow-private`: the gateway may have a private address.
    pub allow_private: bool,
    /// `--model-proxy-local-gateway`: the one gateway origin that may be on loopback and this
    /// machine's own addresses ([`local_gateway_origin`]).
    pub local_gateway: Option<url::Origin>,
    /// Loopback and this machine's addresses may be reached (tests only; never an option).
    pub allow_loopback: bool,
    /// Plain `http://` gateways (tests only; never an option, and `run.assign` must be https).
    pub allow_http: bool,
}

impl ModelProxySettings {
    /// The settings of `run`'s options (`--egress-allow-private`, `--model-proxy-local-gateway`):
    /// an error for a `--model-proxy-local-gateway` that is not an https origin. A blank
    /// `local_gateway` is none.
    pub fn from_options(
        allow_private: bool,
        local_gateway: Option<&str>,
    ) -> Result<ModelProxySettings> {
        let local_gateway = local_gateway
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .map(|value| local_gateway_origin(value).context("--model-proxy-local-gateway"))
            .transpose()?;
        Ok(ModelProxySettings {
            allow_private,
            local_gateway,
            allow_loopback: false,
            allow_http: false,
        })
    }

    /// The address rules of the egress proxy, with these exceptions.
    fn addresses(&self) -> EgressSettings {
        EgressSettings {
            allow: Vec::new(),
            allow_private: self.allow_private,
            allow_loopback: self.allow_loopback,
        }
    }
}

/// The origin of `--model-proxy-local-gateway`: `https://`, a host and an optional port, as in
/// `https://litellm.example.com:4000`, and nothing else (no path, user name, password, query or
/// fragment). The URL parser normalises it (a lower-case host, no default port), so it compares
/// equal to the origin of every base URL with the same scheme, host and port. https only, like
/// every gateway of the model proxy: the server accepts no other base URL, and over plain http
/// whatever listens on the port first (another local process, while the gateway restarts) would
/// get the key with no certificate to show for it. Errors never repeat the value.
pub fn local_gateway_origin(value: &str) -> Result<url::Origin> {
    const EXAMPLE: &str = "such as https://litellm.example.com:4000";
    let url = url::Url::parse(value.trim())
        .map_err(|_| anyhow!("expected the gateway's https origin, {EXAMPLE}"))?;
    anyhow::ensure!(
        url.scheme() == "https",
        "the model proxy reaches a gateway only over https: name its https origin, {EXAMPLE}"
    );
    anyhow::ensure!(
        url.username().is_empty() && url.password().is_none(),
        "an origin has no user name or password; the model proxy sends the run's key"
    );
    anyhow::ensure!(
        url.path() == "/" && url.query().is_none() && url.fragment().is_none(),
        "an origin is the scheme, host and port only, without a path, query or fragment, {EXAMPLE}"
    );
    // An https URL always has a host: the parser refuses one without.
    Ok(url.origin())
}

// ----- the host's own token ---------------------------------------------------------------

/// The host's runner token (`tfr_…`) and the origin of its server, where the token already goes
/// with the WebSocket: the only origin the model proxy sends it to, in [`HOST_TOKEN_HEADER`].
/// Never printed.
#[derive(Clone)]
pub struct HostToken {
    /// Scheme, host and port of the server URL, normalised (`ws` as `http`, `wss` as `https`).
    origin: url::Origin,
    /// The token, marked sensitive.
    header: HeaderValue,
}

impl HostToken {
    /// From the host's configuration (`login --server`, the token). Errors never repeat the
    /// token.
    pub fn new(server_url: &str, token: &str) -> Result<HostToken> {
        let mut url = url::Url::parse(server_url.trim()).context("invalid server URL")?;
        let scheme = match url.scheme() {
            "ws" => Some("http"),
            "wss" => Some("https"),
            "http" | "https" => None,
            other => bail!("unsupported server URL scheme `{other}`"),
        };
        if let Some(scheme) = scheme {
            url.set_scheme(scheme)
                .map_err(|_| anyhow!("cannot read the server URL's origin"))?;
        }
        let origin = url.origin();
        anyhow::ensure!(origin.is_tuple(), "the server URL has no origin");
        anyhow::ensure!(!token.is_empty(), "the host token is empty");
        let mut header = HeaderValue::from_str(token)
            .map_err(|_| anyhow!("the host token cannot be sent in a header"))?;
        header.set_sensitive(true);
        Ok(HostToken { origin, header })
    }

    fn bytes(&self) -> &[u8] {
        self.header.as_bytes()
    }
}

impl std::fmt::Debug for HostToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HostToken")
            .field("origin", &self.origin.ascii_serialization())
            .finish_non_exhaustive()
    }
}

// ----- the gateway ---------------------------------------------------------------------

/// Where a run's requests go, and with which key.
struct Gateway {
    format: ModelFormat,
    /// Scheme, host and port, normalised, to compare with `--model-proxy-local-gateway`.
    origin: url::Origin,
    https: bool,
    /// Host name or IP to resolve and to name in TLS (no brackets).
    host: String,
    ip: Option<IpAddr>,
    port: u16,
    /// The `Host` header.
    authority: HeaderValue,
    /// The base URL's path without a trailing slash; empty for a root.
    base_path: String,
    /// `Bearer <key>`, marked sensitive.
    authorization: HeaderValue,
    /// The host's own token, marked sensitive, when the gateway is this host's server.
    host_token: Option<HeaderValue>,
    /// The gateway looks like a Tinfirm server's model relay ([`RELAY_PATH`]) at an origin that
    /// is not this host's server's, so the host's token is not sent to it.
    host_token_withheld: bool,
    /// Finds the key, and the host's token when it is sent, in the gateway's answers.
    key: AhoCorasick,
    /// What [`Gateway::key`] finds, for the scan's carry.
    secrets: Vec<Vec<u8>>,
}

impl Gateway {
    fn parse(
        access: &ModelAccess,
        allow_http: bool,
        host_token: Option<&HostToken>,
    ) -> Result<Gateway> {
        if !applies(access) {
            bail!("the model proxy serves only an anthropic or openai gateway");
        }
        let base_url = access.base_url.as_deref().unwrap_or_default();
        let url = url::Url::parse(base_url).context("invalid model access base URL")?;
        let https = match url.scheme() {
            "https" => true,
            "http" if allow_http => false,
            other => bail!("the model proxy forwards only to an https gateway, not `{other}`"),
        };
        if !url.username().is_empty() || url.password().is_some() {
            bail!("the model access base URL must not contain a user name or password");
        }
        if url.query().is_some() || url.fragment().is_some() {
            bail!("the model proxy forwards to a base URL without a query or a fragment");
        }
        let (host, ip) = match url
            .host()
            .context("the model access base URL has no host")?
        {
            url::Host::Domain(domain) => (domain.to_ascii_lowercase(), None),
            url::Host::Ipv4(ip) => (ip.to_string(), Some(IpAddr::V4(ip))),
            url::Host::Ipv6(ip) => (ip.to_string(), Some(IpAddr::V6(ip))),
        };
        let port = url
            .port_or_known_default()
            .context("the model access base URL has no port")?;
        let host_str = url.host_str().unwrap_or_default();
        let authority = match url.port() {
            Some(port) => format!("{host_str}:{port}"),
            None => host_str.to_string(),
        };
        let authority =
            HeaderValue::from_str(&authority).context("invalid model access base URL host")?;
        let key = access.api_key.expose();
        if key.is_empty() {
            bail!("the model access key is empty");
        }
        let mut authorization = HeaderValue::from_str(&format!("Bearer {key}"))
            .map_err(|_| anyhow!("the model access key cannot be sent in a header"))?;
        authorization.set_sensitive(true);
        let origin = url.origin();
        // Only to the origin the host token already goes to: its own server.
        let to_server = host_token.filter(|token| token.origin == origin);
        let host_token_withheld = to_server.is_none() && url.path().contains(RELAY_PATH);
        let mut secrets = vec![key.as_bytes().to_vec()];
        if let Some(token) = to_server {
            secrets.push(token.bytes().to_vec());
        }
        Ok(Gateway {
            format: access.format,
            origin,
            https,
            host,
            ip,
            port,
            authority,
            base_path: url.path().trim_end_matches('/').to_string(),
            authorization,
            host_token: to_server.map(|token| token.header.clone()),
            host_token_withheld,
            key: AhoCorasick::new(&secrets).context("cannot search for the key")?,
            secrets,
        })
    }

    /// The method of a model endpoint (the path after the base path), or `None` for any other
    /// path.
    fn endpoint_method(&self, endpoint: &str) -> Option<Method> {
        match (self.format, endpoint) {
            (ModelFormat::Anthropic, "/v1/messages" | "/v1/messages/count_tokens") => {
                Some(Method::POST)
            }
            (ModelFormat::Anthropic, "/v1/models") => Some(Method::GET),
            (ModelFormat::Openai, "/responses" | "/chat/completions") => Some(Method::POST),
            (ModelFormat::Openai, "/models") => Some(Method::GET),
            _ => None,
        }
    }

    fn label(&self) -> String {
        host_port(&self.host, self.port)
    }
}

// ----- the proxy -------------------------------------------------------------------------

/// Why a run's model requests may be refused when [`ModelProxyGuard::host_token_withheld`].
pub const HOST_TOKEN_WITHHELD: &str = "the run's model gateway is a Tinfirm model relay at another origin than this host's server URL, so the host's own token is not sent with its requests, and the server refuses them from runner hosts of 0.3.0 on; log this host in with the server's public URL (its TINFIRM_PUBLIC_URL)";

/// Why the listeners never bind every address: they serve the sandboxes, not the network.
const UNSPECIFIED_BIND: &str = "the model proxy must listen on the sandbox network's gateway, not on every address; set TINFIRM_RUNNER_GIT_PROXY_BIND to an address of that network";

/// Checked when `run` starts, because the model proxy is always on: whether its listeners can
/// bind where the sandboxes reach this host, `endpoint` (the git proxy's; `None` when the
/// sandboxes have no network), which it returns. Each error names what to change, so that the
/// operator learns it at start rather than from the first gateway run, which would fail. It binds
/// a port of that address and lets it go. Whether a sandbox then gets through (a host firewall,
/// an engine in a VM that does not forward `host.docker.internal`) only a run shows.
pub async fn check_endpoint(endpoint: Option<&Endpoint>) -> Result<&Endpoint> {
    const ALWAYS_ON: &str =
        "the model proxy is always on (it keeps a model gateway's key out of the sandboxes)";
    let Some(endpoint) = endpoint else {
        bail!(
            "{ALWAYS_ON}, and the sandboxes reach it on their Docker network: with TINFIRM_RUNNER_SANDBOX_NETWORK=none they have none, so no run with a model gateway could start; leave TINFIRM_RUNNER_SANDBOX_NETWORK unset, or name a Docker network"
        );
    };
    let ip = endpoint.bind.ip();
    anyhow::ensure!(
        !ip.is_unspecified(),
        "{ALWAYS_ON}, and {UNSPECIFIED_BIND} (not {ip})"
    );
    TcpListener::bind(SocketAddr::new(ip, 0))
        .await
        .with_context(|| {
            format!(
                "{ALWAYS_ON}, and it cannot listen on {ip}, where the sandboxes reach this host; set TINFIRM_RUNNER_GIT_PROXY_BIND and TINFIRM_RUNNER_GIT_PROXY_HOST to an address of this machine that the sandboxes reach"
            )
        })?;
    Ok(endpoint)
}

/// The runner host's model proxy: the settings, and where runs' listeners bind.
pub struct ModelProxy {
    settings: Arc<ModelProxySettings>,
    bind_ip: IpAddr,
    sandbox_host: String,
    /// The host's own token, for its server's model relay ([`ModelProxy::with_host_token`]).
    host_token: Option<HostToken>,
    /// TLS to the gateways, loaded at the first https gateway.
    tls: OnceLock<Arc<rustls::ClientConfig>>,
}

impl ModelProxy {
    /// The proxy's listeners bind the address of the git proxy's [`Endpoint`] (the sandbox
    /// network's gateway), each on a port of its own, and sandboxes reach them by the same name.
    pub fn new(settings: ModelProxySettings, endpoint: &Endpoint) -> ModelProxy {
        ModelProxy {
            settings: Arc::new(settings),
            bind_ip: endpoint.bind.ip(),
            sandbox_host: endpoint.sandbox_host.clone(),
            host_token: None,
            tls: OnceLock::new(),
        }
    }

    /// Sends the host's own token in [`HOST_TOKEN_HEADER`] to every gateway of its server's
    /// origin (the Tinfirm server's model relay), and to no other.
    pub fn with_host_token(mut self, host_token: HostToken) -> ModelProxy {
        self.host_token = Some(host_token);
        self
    }

    /// Starts a run's listener, which forwards to the gateway of `access` with its key until
    /// the guard is dropped.
    pub async fn start_run(&self, run_id: Uuid, access: &ModelAccess) -> Result<ModelProxyGuard> {
        self.start_run_at(run_id, access, None).await
    }

    /// [`ModelProxy::start_run`], with a base URL that names `sandbox_host` in place of the
    /// endpoint's (a local test executor's sandbox over the bridge).
    pub async fn start_run_at(
        &self,
        run_id: Uuid,
        access: &ModelAccess,
        sandbox_host: Option<&str>,
    ) -> Result<ModelProxyGuard> {
        anyhow::ensure!(!self.bind_ip.is_unspecified(), UNSPECIFIED_BIND);
        let gateway = Gateway::parse(access, self.settings.allow_http, self.host_token.as_ref())?;
        let local_gateway = self
            .settings
            .local_gateway
            .as_ref()
            .is_some_and(|origin| *origin == gateway.origin);
        let tls = gateway
            .https
            .then(|| self.tls.get_or_init(tls_config).clone());
        let secret = new_secret()?;
        let listener = TcpListener::bind(SocketAddr::new(self.bind_ip, 0))
            .await
            .with_context(|| format!("cannot listen on {} for the model proxy", self.bind_ip))?;
        let port = listener.local_addr()?.port();
        let base_url = format!(
            "http://{}/{secret}{}",
            host_port(sandbox_host.unwrap_or(&self.sandbox_host), port),
            gateway.base_path
        );
        let state = Arc::new(RunState {
            run_id,
            gateway,
            secret: secret.clone(),
            addresses: self.settings.addresses(),
            local_gateway,
            bind_ip: self.bind_ip,
            tls,
            forwarded: AtomicU64::new(0),
            refused: AtomicU64::new(0),
            shutdown: CancellationToken::new(),
        });
        tokio::spawn(accept_loop(listener, state.clone()));
        let host_token = state.gateway.host_token.is_some();
        tracing::info!(run_id = %run_id, port, gateway = %state.gateway.label(), local_gateway, host_token, "model proxy listening for a run");
        if state.gateway.host_token_withheld {
            tracing::warn!(run_id = %run_id, gateway = %state.gateway.label(), "{HOST_TOKEN_WITHHELD}");
        }
        Ok(ModelProxyGuard {
            state,
            base_url,
            secret,
            port,
        })
    }
}

/// A run's listener. Dropping it stops the listener and ends the run's open connections.
pub struct ModelProxyGuard {
    state: Arc<RunState>,
    base_url: String,
    secret: String,
    port: u16,
}

impl ModelProxyGuard {
    /// The base URL for the sandbox's runtime. It contains the run's secret: never log it.
    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    /// The run's secret, for the redactor.
    pub fn secret(&self) -> &str {
        &self.secret
    }

    /// The listener's port.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// The listener's address on this host, which a sandbox's tunnel reaches.
    pub fn local_addr(&self) -> SocketAddr {
        SocketAddr::new(self.state.bind_ip, self.port)
    }

    /// Whether the run's requests carry the host's own token: its gateway is this host's server.
    pub fn sends_host_token(&self) -> bool {
        self.state.gateway.host_token.is_some()
    }

    /// Whether the gateway looks like a Tinfirm server's model relay at an origin that is not
    /// this host's server's, so that the host's token is not sent ([`HOST_TOKEN_WITHHELD`]).
    pub fn host_token_withheld(&self) -> bool {
        self.state.gateway.host_token_withheld
    }

    /// Requests forwarded to the gateway, and requests refused, so far.
    pub fn counts(&self) -> (u64, u64) {
        (
            self.state.forwarded.load(Ordering::Relaxed),
            self.state.refused.load(Ordering::Relaxed),
        )
    }
}

impl Drop for ModelProxyGuard {
    fn drop(&mut self) {
        self.state.shutdown.cancel();
        let (forwarded, refused) = self.counts();
        tracing::info!(run_id = %self.state.run_id, forwarded, refused, "model proxy stopped for a run");
    }
}

impl std::fmt::Debug for ModelProxyGuard {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ModelProxyGuard")
            .field("port", &self.port)
            .finish()
    }
}

struct RunState {
    run_id: Uuid,
    gateway: Gateway,
    secret: String,
    addresses: EgressSettings,
    /// The gateway's origin is the one of `--model-proxy-local-gateway`: it may be on loopback
    /// and this machine's own addresses.
    local_gateway: bool,
    /// The listener's own address: never a destination.
    bind_ip: IpAddr,
    /// `None` for a plain-http gateway (tests).
    tls: Option<Arc<rustls::ClientConfig>>,
    forwarded: AtomicU64,
    refused: AtomicU64,
    shutdown: CancellationToken,
}

async fn accept_loop(listener: TcpListener, state: Arc<RunState>) {
    let limit = Arc::new(Semaphore::new(MAX_CONNECTIONS));
    loop {
        let permit = tokio::select! {
            _ = state.shutdown.cancelled() => break,
            permit = limit.clone().acquire_owned() => match permit {
                Ok(permit) => permit,
                Err(_) => break,
            },
        };
        let stream = tokio::select! {
            _ = state.shutdown.cancelled() => break,
            accepted = listener.accept() => match accepted {
                Ok((stream, _)) => stream,
                Err(e) => {
                    tracing::warn!(error = %e, "model proxy: accept failed");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    continue;
                }
            },
        };
        let state = state.clone();
        tokio::spawn(async move {
            let _permit = permit;
            let shutdown = state.shutdown.clone();
            let service = hyper::service::service_fn(move |req| {
                let state = state.clone();
                async move { Ok::<_, Infallible>(handle(state, req).await) }
            });
            let mut builder = hyper::server::conn::http1::Builder::new();
            builder
                .timer(TokioTimer::new())
                .header_read_timeout(HEADER_READ_TIMEOUT);
            let connection = builder.serve_connection(TokioIo::new(stream), service);
            tokio::select! {
                result = connection => {
                    if let Err(e) = result {
                        tracing::debug!(error = %e, "model proxy: connection ended with an error");
                    }
                }
                _ = shutdown.cancelled() => {}
            }
        });
    }
}

// ----- requests --------------------------------------------------------------------------

/// An answer of the proxy itself, in the error shape both APIs use (`error.message`).
fn error(status: StatusCode, message: &str) -> Response<ProxyBody> {
    let body = serde_json::json!({
        "type": "error",
        "error": {"type": "tinfirm_model_proxy", "message": message},
    });
    let body = Full::new(Bytes::from(body.to_string()))
        .map_err(|never| match never {})
        .boxed_unsync();
    let mut response = Response::new(body);
    *response.status_mut() = status;
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/json"),
    );
    response
}

enum Decision {
    /// The request target to send to the gateway (`<tail>?<query>`).
    Forward(Uri),
    Refuse(Box<Response<ProxyBody>>),
}

fn refuse(status: StatusCode, message: &str) -> Decision {
    Decision::Refuse(Box::new(error(status, message)))
}

/// Decides a request from its method, path and headers alone.
fn decide(state: &RunState, method: &Method, uri: &Uri, headers: &HeaderMap) -> Decision {
    let path = uri.path();
    let (candidate, tail) = match path.strip_prefix('/') {
        Some(rest) => match rest.find('/') {
            Some(i) => (&rest[..i], &rest[i..]),
            None => (rest, ""),
        },
        None => ("", ""),
    };
    if !secret_eq(candidate.as_bytes(), state.secret.as_bytes()) {
        tracing::debug!(run_id = %state.run_id, "model proxy request without the run's secret");
        return refuse(StatusCode::NOT_FOUND, "not found");
    }
    if headers.contains_key(header::UPGRADE) {
        tracing::info!(run_id = %state.run_id, "model proxy refused a connection upgrade (WebSocket)");
        return refuse(
            StatusCode::NOT_IMPLEMENTED,
            "the Tinfirm model proxy does not serve WebSocket connections; send the request over HTTP",
        );
    }
    let gateway = &state.gateway;
    let endpoint = tail.strip_prefix(gateway.base_path.as_str()).unwrap_or("");
    let Some(allowed) = gateway.endpoint_method(endpoint) else {
        let shown: String = tail.chars().take(200).collect();
        tracing::info!(run_id = %state.run_id, method = %method, path = %shown.escape_debug(), "model proxy refused a path outside the model API");
        return refuse(
            StatusCode::NOT_FOUND,
            "not found: the Tinfirm model proxy forwards only the model API (messages, responses, chat completions, models)",
        );
    };
    if *method != allowed {
        let mut response = error(
            StatusCode::METHOD_NOT_ALLOWED,
            "method not allowed on this endpoint of the model API",
        );
        response.headers_mut().insert(
            header::ALLOW,
            HeaderValue::from_str(allowed.as_str()).unwrap_or(HeaderValue::from_static("POST")),
        );
        return Decision::Refuse(Box::new(response));
    }
    let target = match uri.query() {
        Some(query) => format!("{tail}?{query}"),
        None => tail.to_string(),
    };
    match target.parse::<Uri>() {
        Ok(target) => Decision::Forward(target),
        Err(_) => refuse(StatusCode::BAD_REQUEST, "bad request target"),
    }
}

async fn handle(state: Arc<RunState>, req: Request<Incoming>) -> Response<ProxyBody> {
    match decide(&state, req.method(), req.uri(), req.headers()) {
        Decision::Refuse(response) => {
            state.refused.fetch_add(1, Ordering::Relaxed);
            *response
        }
        Decision::Forward(target) => forward(&state, target, req).await,
    }
}

/// The request's headers for the gateway: none of the sandbox's credentials (its own
/// [`HOST_TOKEN_HEADER`] included), connection or forwarding headers; the gateway's `Host`, the
/// real key, the host's own token for its server, and an answer the key scan can read.
fn upstream_headers(gateway: &Gateway, mut headers: HeaderMap) -> HeaderMap {
    strip_hop_by_hop(&mut headers);
    for name in CREDENTIAL_HEADERS.iter().chain(&FORWARDING_HEADERS) {
        headers.remove(*name);
    }
    // Framing comes from the body; `Expect` would wait for an answer the client already skipped.
    for name in [header::HOST, header::CONTENT_LENGTH, header::EXPECT] {
        headers.remove(name);
    }
    headers.insert(header::HOST, gateway.authority.clone());
    headers.insert(header::AUTHORIZATION, gateway.authorization.clone());
    if let Some(token) = &gateway.host_token {
        headers.insert(HOST_TOKEN_HEADER, token.clone());
    }
    headers.insert(
        header::ACCEPT_ENCODING,
        HeaderValue::from_static("identity"),
    );
    headers
}

async fn forward(state: &RunState, target: Uri, req: Request<Incoming>) -> Response<ProxyBody> {
    let (parts, body) = req.into_parts();
    let declared_length = parts
        .headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok());
    if declared_length.is_some_and(|len| len > MAX_REQUEST_BODY) {
        state.refused.fetch_add(1, Ordering::Relaxed);
        return error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "the request body is too large for the Tinfirm model proxy",
        );
    }
    let gateway = &state.gateway;
    let endpoint = target.path().to_string();
    let mut outgoing = Request::new(Limited::new(body, MAX_REQUEST_BODY as usize).boxed_unsync());
    *outgoing.method_mut() = parts.method.clone();
    *outgoing.uri_mut() = target;
    *outgoing.headers_mut() = upstream_headers(gateway, parts.headers);

    let stream = match connect(state).await {
        Ok(stream) => stream,
        Err(message) => {
            tracing::warn!(run_id = %state.run_id, gateway = %gateway.label(), error = %message, "model proxy: cannot reach the gateway");
            return error(
                StatusCode::BAD_GATEWAY,
                &format!("the Tinfirm model proxy cannot reach the model gateway: {message}"),
            );
        }
    };
    let response = match send(state, stream, outgoing).await {
        Ok(response) => response,
        Err(e) if body_too_large(e.as_ref()) => {
            return error(
                StatusCode::PAYLOAD_TOO_LARGE,
                "the request body is too large for the Tinfirm model proxy",
            );
        }
        Err(e) => {
            tracing::warn!(run_id = %state.run_id, gateway = %gateway.label(), error = %e, "model proxy: the gateway request failed");
            return error(
                StatusCode::BAD_GATEWAY,
                "the Tinfirm model proxy's request to the model gateway failed",
            );
        }
    };
    state.forwarded.fetch_add(1, Ordering::Relaxed);
    let status = response.status();
    tracing::debug!(run_id = %state.run_id, method = %parts.method, endpoint = %endpoint, status = status.as_u16(), "model proxy request");
    if status.is_redirection() {
        tracing::warn!(run_id = %state.run_id, status = status.as_u16(), "model proxy: the gateway answered with a redirect; not following it");
        return error(
            StatusCode::BAD_GATEWAY,
            "the model gateway answered with a redirect; the Tinfirm model proxy does not follow redirects",
        );
    }
    if status == StatusCode::PROXY_AUTHENTICATION_REQUIRED {
        return error(
            StatusCode::BAD_GATEWAY,
            "the model gateway asked for proxy authentication",
        );
    }
    let (mut head, body) = response.into_parts();
    if head
        .headers
        .values()
        .any(|value| gateway.key.is_match(value.as_bytes()))
    {
        tracing::warn!(run_id = %state.run_id, "model proxy: the gateway's answer named the key in a header; not passing it on");
        return error(
            StatusCode::BAD_GATEWAY,
            "the model gateway's answer contained the key; the Tinfirm model proxy did not pass it on",
        );
    }
    strip_hop_by_hop(&mut head.headers);
    head.headers.remove(header::SET_COOKIE);
    let body = GatewayBody {
        inner: body,
        idle: Box::pin(tokio::time::sleep(IDLE_TIMEOUT)),
        scan: KeyScan::new(gateway),
        run_id: state.run_id,
        ended: false,
    };
    Response::from_parts(head, body.boxed_unsync())
}

/// A connection to an address of the gateway that the address rules allow.
async fn connect(state: &RunState) -> std::result::Result<TcpStream, String> {
    let gateway = &state.gateway;
    let addrs: Vec<SocketAddr> = match gateway.ip {
        Some(ip) => vec![SocketAddr::new(ip, gateway.port)],
        None => {
            let lookup = tokio::net::lookup_host((gateway.host.as_str(), gateway.port));
            match tokio::time::timeout(RESOLVE_TIMEOUT, lookup).await {
                Ok(Ok(addrs)) => addrs.collect(),
                Ok(Err(e)) => return Err(format!("cannot resolve {}: {e}", gateway.host)),
                Err(_) => return Err(format!("resolving {} timed out", gateway.host)),
            }
        }
    };
    if addrs.is_empty() {
        return Err(format!("{} has no address", gateway.host));
    }
    let mut refused = None;
    let mut last_error = None;
    for addr in addrs {
        if let Some(why) = refusal(state, addr.ip()) {
            refused = Some(why);
            continue;
        }
        match tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(addr)).await {
            Ok(Ok(stream)) => {
                let _ = stream.set_nodelay(true);
                return Ok(stream);
            }
            Ok(Err(e)) => last_error = Some(e.to_string()),
            Err(_) => last_error = Some("timed out".to_string()),
        }
    }
    Err(match (last_error, refused) {
        (Some(error), _) => format!("cannot connect to {}: {error}", gateway.label()),
        (None, why) => {
            let why = why.unwrap_or("refused");
            let hint = match why {
                PRIVATE => "; a gateway inside your network needs --egress-allow-private",
                LOOPBACK | THIS_HOST if !state.local_gateway => {
                    "; a gateway on this machine needs --model-proxy-local-gateway with its origin"
                }
                _ => "",
            };
            format!(
                "{} resolves to an address that the runner host does not connect to ({why}){hint}",
                gateway.label()
            )
        }
    })
}

/// [`refused_address`]'s reasons that an option can lift.
const PRIVATE: &str = "private";
const LOOPBACK: &str = "loopback";
/// One of this machine's own addresses that is not loopback.
const THIS_HOST: &str = "this host's own";

/// Why the proxy does not connect to `ip` for the run's gateway, or `None`.
///
/// The listener's own address never: whatever listens there is this host's, never the gateway.
/// The gateway of `--model-proxy-local-gateway` may be on loopback and this machine's other
/// addresses, private ones too, but not on the addresses that no gateway has (cloud metadata,
/// link-local, reserved, unspecified); on any other address it has the rules of every gateway.
fn refusal(state: &RunState, ip: IpAddr) -> Option<&'static str> {
    let ip = ip.to_canonical();
    if ip == state.bind_ip.to_canonical() {
        return Some("the model proxy's own");
    }
    if state.local_gateway && (ip.is_loopback() || is_local_address(ip)) {
        let on_this_machine = EgressSettings {
            allow: Vec::new(),
            allow_private: true,
            allow_loopback: true,
        };
        return refused_address(ip, &on_this_machine);
    }
    if let Some(why) = refused_address(ip, &state.addresses) {
        return Some(why);
    }
    (!state.addresses.allow_loopback && is_local_address(ip)).then_some(THIS_HOST)
}

/// One request on its own connection to the gateway (TLS unless a test allowed plain http).
async fn send(
    state: &RunState,
    stream: TcpStream,
    request: Request<ProxyBody>,
) -> std::result::Result<Response<Incoming>, BoxError> {
    match &state.tls {
        Some(tls) => {
            let name = rustls::pki_types::ServerName::try_from(state.gateway.host.clone())?;
            let handshake = tokio_rustls::TlsConnector::from(tls.clone()).connect(name, stream);
            let tls = tokio::time::timeout(CONNECT_TIMEOUT, handshake)
                .await
                .map_err(|_| "the TLS handshake timed out")??;
            exchange(state, TokioIo::new(tls), request).await
        }
        None => exchange(state, TokioIo::new(stream), request).await,
    }
}

async fn exchange<T>(
    state: &RunState,
    io: T,
    request: Request<ProxyBody>,
) -> std::result::Result<Response<Incoming>, BoxError>
where
    T: hyper::rt::Read + hyper::rt::Write + Unpin + Send + 'static,
{
    let (mut sender, connection) = hyper::client::conn::http1::handshake(io).await?;
    let shutdown = state.shutdown.clone();
    tokio::spawn(async move {
        tokio::select! {
            result = connection => {
                if let Err(e) = result {
                    tracing::debug!(error = %e, "model proxy: gateway connection ended with an error");
                }
            }
            _ = shutdown.cancelled() => {}
        }
    });
    let response = tokio::time::timeout(IDLE_TIMEOUT, sender.send_request(request))
        .await
        .map_err(|_| "the model gateway did not answer in time")??;
    Ok(response)
}

/// `true` when the request body hit [`MAX_REQUEST_BODY`] while it was sent.
fn body_too_large(error: &(dyn std::error::Error + 'static)) -> bool {
    let mut current = Some(error);
    while let Some(e) = current {
        if e.is::<LengthLimitError>() {
            return true;
        }
        current = e.source();
    }
    false
}

// ----- the answer --------------------------------------------------------------------------

/// Finds the run's key, and the host's token when it was sent, in an answer that streams by. It
/// holds back only the end of a part that could be the start of one of them (an SSE event ends
/// in a blank line, which neither starts with), so the answer still flows part by part, and
/// neither reaches the sandbox whole.
struct KeyScan {
    key: AhoCorasick,
    secrets: Vec<Vec<u8>>,
    carry: Vec<u8>,
}

impl KeyScan {
    fn new(gateway: &Gateway) -> KeyScan {
        KeyScan {
            key: gateway.key.clone(),
            secrets: gateway.secrets.clone(),
            carry: Vec::new(),
        }
    }

    /// The bytes that may go on now; `None` when the key is in the answer.
    fn feed(&mut self, data: Bytes) -> Option<Bytes> {
        let joined = if self.carry.is_empty() {
            data
        } else {
            let mut joined = std::mem::take(&mut self.carry);
            joined.extend_from_slice(&data);
            Bytes::from(joined)
        };
        if self.key.is_match(&joined[..]) {
            return None;
        }
        // The longest end of `joined` that is a proper start of a secret.
        let held = self
            .secrets
            .iter()
            .filter_map(|secret| {
                (1..secret.len().min(joined.len() + 1))
                    .rev()
                    .find(|&n| joined.ends_with(&secret[..n]))
            })
            .max()
            .unwrap_or(0);
        let split = joined.len() - held;
        self.carry = joined[split..].to_vec();
        Some(joined.slice(..split))
    }

    /// What was held back at the end of the answer: only ever a part of the key's start.
    fn finish(&mut self) -> Option<Bytes> {
        (!self.carry.is_empty()).then(|| Bytes::from(std::mem::take(&mut self.carry)))
    }
}

/// The gateway's answer on its way to the sandbox: each part as it arrives, with an idle limit
/// between parts and the key scan.
struct GatewayBody {
    inner: Incoming,
    idle: Pin<Box<tokio::time::Sleep>>,
    scan: KeyScan,
    run_id: Uuid,
    ended: bool,
}

impl Body for GatewayBody {
    type Data = Bytes;
    type Error = BoxError;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
    ) -> Poll<Option<std::result::Result<Frame<Bytes>, BoxError>>> {
        let this = self.get_mut();
        if this.ended {
            return Poll::Ready(None);
        }
        loop {
            match Pin::new(&mut this.inner).poll_frame(cx) {
                Poll::Ready(Some(Ok(frame))) => {
                    this.idle
                        .as_mut()
                        .reset(tokio::time::Instant::now() + IDLE_TIMEOUT);
                    // Trailers are dropped: the model APIs send none.
                    let Ok(data) = frame.into_data() else {
                        continue;
                    };
                    match this.scan.feed(data) {
                        None => {
                            this.ended = true;
                            tracing::warn!(run_id = %this.run_id, "model proxy: the gateway's answer contained the key; cut it off");
                            return Poll::Ready(Some(Err(
                                "the model gateway's answer contained the key".into(),
                            )));
                        }
                        Some(data) if data.is_empty() => continue,
                        Some(data) => return Poll::Ready(Some(Ok(Frame::data(data)))),
                    }
                }
                Poll::Ready(Some(Err(e))) => {
                    this.ended = true;
                    return Poll::Ready(Some(Err(Box::new(e))));
                }
                Poll::Ready(None) => {
                    this.ended = true;
                    return Poll::Ready(this.scan.finish().map(|rest| Ok(Frame::data(rest))));
                }
                Poll::Pending => {
                    if this.idle.as_mut().poll(cx).is_ready() {
                        this.ended = true;
                        tracing::warn!(run_id = %this.run_id, "model proxy: the gateway's answer stopped moving; ending it");
                        return Poll::Ready(Some(Err(
                            "the model gateway's answer stopped moving".into()
                        )));
                    }
                    return Poll::Pending;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use hyper::header::HeaderName;
    use std::sync::Mutex;
    use tinfirm_protocol::Secret;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::sync::mpsc;

    const REAL_KEY: &str = "sk-TEST-ONLY-real-gateway-key-7f3a";

    fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
        m.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// What the fake gateway received.
    #[derive(Debug, Clone)]
    struct Seen {
        method: Method,
        uri: String,
        headers: HeaderMap,
        body: Bytes,
    }

    /// The fake gateway's answer: a status, headers and the parts of its body. A part `None`
    /// waits for the test's go (see [`FakeGateway::go`]).
    #[derive(Clone)]
    struct Reply {
        status: StatusCode,
        headers: Vec<(&'static str, String)>,
        parts: Vec<Option<String>>,
    }

    impl Reply {
        fn ok(body: &str) -> Reply {
            Reply {
                status: StatusCode::OK,
                headers: vec![("content-type", "application/json".into())],
                parts: vec![Some(body.to_string())],
            }
        }
    }

    type Replier = Arc<dyn Fn(&Seen) -> Reply + Send + Sync>;

    struct FakeGateway {
        addr: SocketAddr,
        seen: Arc<Mutex<Vec<Seen>>>,
        go: mpsc::UnboundedSender<()>,
    }

    impl FakeGateway {
        async fn start(replier: Replier) -> FakeGateway {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let addr = listener.local_addr().unwrap();
            let seen = Arc::new(Mutex::new(Vec::new()));
            let (go, go_rx) = mpsc::unbounded_channel::<()>();
            let go_rx = Arc::new(tokio::sync::Mutex::new(go_rx));
            let seen_task = seen.clone();
            tokio::spawn(async move {
                while let Ok((stream, _)) = listener.accept().await {
                    let seen = seen_task.clone();
                    let replier = replier.clone();
                    let go_rx = go_rx.clone();
                    tokio::spawn(async move {
                        let service = hyper::service::service_fn(move |req: Request<Incoming>| {
                            let seen = seen.clone();
                            let replier = replier.clone();
                            let go_rx = go_rx.clone();
                            async move {
                                let (parts, body) = req.into_parts();
                                let body = body.collect().await.unwrap().to_bytes();
                                let request = Seen {
                                    method: parts.method,
                                    uri: parts.uri.to_string(),
                                    headers: parts.headers,
                                    body,
                                };
                                let reply = replier(&request);
                                lock(&seen).push(request);
                                let (tx, rx) = mpsc::unbounded_channel::<
                                    std::result::Result<Frame<Bytes>, Infallible>,
                                >();
                                tokio::spawn(async move {
                                    for part in reply.parts {
                                        match part {
                                            Some(text) => {
                                                let _ = tx.send(Ok(Frame::data(Bytes::from(text))));
                                            }
                                            None => {
                                                go_rx.lock().await.recv().await;
                                            }
                                        }
                                    }
                                });
                                let body = http_body_util::StreamBody::new(tokio_stream_from(rx));
                                let mut response = Response::new(body);
                                *response.status_mut() = reply.status;
                                for (name, value) in reply.headers {
                                    response
                                        .headers_mut()
                                        .append(name, HeaderValue::from_str(&value).unwrap());
                                }
                                Ok::<_, Infallible>(response)
                            }
                        });
                        let _ = hyper::server::conn::http1::Builder::new()
                            .serve_connection(TokioIo::new(stream), service)
                            .await;
                    });
                }
            });
            FakeGateway { addr, seen, go }
        }

        fn url(&self, path: &str) -> String {
            format!("http://{}{path}", self.addr)
        }

        fn seen(&self) -> Vec<Seen> {
            lock(&self.seen).clone()
        }

        /// Lets the answer go on past its next `None` part.
        fn go(&self) {
            self.go.send(()).unwrap();
        }
    }

    /// An unbounded receiver as a stream (no tokio-stream dependency).
    fn tokio_stream_from<T>(
        mut rx: mpsc::UnboundedReceiver<T>,
    ) -> impl futures::Stream<Item = T> + Send + 'static
    where
        T: Send + 'static,
    {
        futures::stream::poll_fn(move |cx| rx.poll_recv(cx))
    }

    fn access(format: ModelFormat, base_url: &str) -> ModelAccess {
        ModelAccess {
            format,
            base_url: Some(base_url.to_string()),
            region: None,
            access_key_id: None,
            api_key: Secret::new(REAL_KEY),
        }
    }

    /// The settings of a test: the fake gateway is plain http on loopback.
    fn test_settings() -> ModelProxySettings {
        ModelProxySettings {
            allow_private: false,
            local_gateway: None,
            allow_loopback: true,
            allow_http: true,
        }
    }

    fn endpoint() -> Endpoint {
        Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        }
    }

    async fn start(format: ModelFormat, base_url: &str) -> ModelProxyGuard {
        ModelProxy::new(test_settings(), &endpoint())
            .start_run(Uuid::new_v4(), &access(format, base_url))
            .await
            .unwrap()
    }

    fn listener_addr(guard: &ModelProxyGuard) -> SocketAddr {
        SocketAddr::new("127.0.0.2".parse().unwrap(), guard.port())
    }

    /// The path of the guard's base URL: `/<secret><base path>`.
    fn base_path(guard: &ModelProxyGuard) -> String {
        url::Url::parse(guard.base_url())
            .unwrap()
            .path()
            .to_string()
    }

    async fn request(
        guard: &ModelProxyGuard,
        method: Method,
        path: &str,
        headers: &[(&str, &str)],
        body: &str,
    ) -> (StatusCode, HeaderMap, String) {
        let stream = TcpStream::connect(listener_addr(guard)).await.unwrap();
        let (mut sender, connection) =
            hyper::client::conn::http1::handshake::<_, Full<Bytes>>(TokioIo::new(stream))
                .await
                .unwrap();
        tokio::spawn(connection);
        let mut req = Request::new(Full::new(Bytes::from(body.to_string())));
        *req.method_mut() = method;
        *req.uri_mut() = path.parse().unwrap();
        req.headers_mut()
            .insert(header::HOST, HeaderValue::from_static("proxy"));
        for (name, value) in headers {
            req.headers_mut().append(
                HeaderName::from_bytes(name.as_bytes()).unwrap(),
                HeaderValue::from_str(value).unwrap(),
            );
        }
        let response = sender.send_request(req).await.unwrap();
        let status = response.status();
        let headers = response.headers().clone();
        let body = response.into_body().collect().await;
        let body = match body {
            Ok(body) => String::from_utf8_lossy(&body.to_bytes()).into_owned(),
            Err(e) => format!("<cut off: {e}>"),
        };
        (status, headers, body)
    }

    #[test]
    fn only_gateways_of_the_two_formats_are_proxied() {
        let gateway = |format| access(format, "https://litellm.example.test");
        assert!(applies(&gateway(ModelFormat::Anthropic)));
        assert!(applies(&gateway(ModelFormat::Openai)));
        let mut own_api = gateway(ModelFormat::Anthropic);
        own_api.base_url = None;
        assert!(!applies(&own_api));
        for format in [
            ModelFormat::Bedrock,
            ModelFormat::Google,
            ModelFormat::Openrouter,
        ] {
            assert!(!applies(&gateway(format)), "{format}");
        }
        // Always on: the options only widen where a gateway may be.
        let settings = ModelProxySettings::from_options(true, None).unwrap();
        assert!(settings.allow_private && !settings.allow_loopback && !settings.allow_http);
        assert!(settings.local_gateway.is_none());
        let settings = ModelProxySettings::from_options(false, None).unwrap();
        assert!(!settings.allow_private && !settings.allow_loopback && !settings.allow_http);
    }

    #[test]
    fn the_local_gateway_option_is_an_https_origin() {
        let origin = |value: &str| {
            ModelProxySettings::from_options(false, Some(value))
                .map(|settings| settings.local_gateway.unwrap())
        };
        // Normalised: the host's case, the default port and a trailing slash do not count.
        let named = origin("https://LiteLLM.Example.test:443/").unwrap();
        assert_eq!(named.ascii_serialization(), "https://litellm.example.test");
        assert_eq!(named, origin(" https://litellm.example.test ").unwrap());
        let base_url = |url: &str| url::Url::parse(url).unwrap().origin();
        assert_eq!(named, base_url("https://litellm.example.test/v1"));
        assert_ne!(named, base_url("https://litellm.example.test:4000/v1"));
        assert_ne!(named, base_url("https://other.example.test/v1"));
        assert_ne!(named, base_url("http://litellm.example.test/v1"));
        let port = origin("https://localhost:4000").unwrap();
        assert_eq!(port, base_url("https://LOCALHOST:4000"));
        assert_ne!(port, base_url("https://127.0.0.1:4000"));
        assert_eq!(
            origin("https://[0:0:0:0:0:0:0:1]:4000").unwrap(),
            base_url("https://[::1]:4000")
        );
        // Blank is none.
        for blank in [None, Some(""), Some("  ")] {
            let settings = ModelProxySettings::from_options(false, blank).unwrap();
            assert!(settings.local_gateway.is_none());
        }
        // Malformed values are refused, and never repeated.
        for bad in [
            "http://localhost:4000",
            "localhost:4000",
            "litellm.example.test",
            "https://localhost:4000/v1",
            "https://localhost:4000/?x=1",
            "https://localhost:4000#x",
            "https://ci:hunter2@localhost:4000",
            "https://ci@localhost:4000",
            "https://",
            "ftp://localhost",
            "not an origin",
        ] {
            let err = origin(bad).unwrap_err();
            let message = format!("{err:#}");
            assert!(
                message.starts_with("--model-proxy-local-gateway: "),
                "{bad}: {message}"
            );
            assert!(!message.contains("hunter2"), "{message}");
            assert!(!message.contains("localhost"), "{message}");
        }
        assert!(
            format!("{:#}", origin("http://localhost:4000").unwrap_err()).contains("https"),
            "plain http is refused for https"
        );
        // With --egress-allow-private too: the two options are independent.
        let settings =
            ModelProxySettings::from_options(true, Some("https://localhost:4000")).unwrap();
        assert!(settings.allow_private);
        assert_eq!(settings.local_gateway, Some(port));
    }

    #[tokio::test]
    async fn run_refuses_at_start_a_setup_where_the_listeners_cannot_bind() {
        let at = |bind: &str| Endpoint {
            bind: bind.parse().unwrap(),
            sandbox_host: "172.17.0.1".into(),
            extra_hosts: Vec::new(),
        };
        // The sandbox network's gateway, a fixed port of TINFIRM_RUNNER_GIT_PROXY_BIND, Docker
        // Desktop's and `host`'s loopback: each listener takes a port of its own there.
        for bind in ["127.0.0.2:0", "127.0.0.1:0", "127.0.0.2:9", "[::1]:0"] {
            if bind.starts_with('[') && TcpListener::bind(bind).await.is_err() {
                continue; // No IPv6 on this machine.
            }
            check_endpoint(Some(&at(bind)))
                .await
                .unwrap_or_else(|e| panic!("{bind}: {e:#}"));
        }
        // TINFIRM_RUNNER_SANDBOX_NETWORK=none: the sandboxes have no way to this host.
        let message = format!("{:#}", check_endpoint(None).await.unwrap_err());
        assert!(
            message.contains("always on")
                && message.contains("TINFIRM_RUNNER_SANDBOX_NETWORK=none"),
            "{message}"
        );
        // TINFIRM_RUNNER_GIT_PROXY_BIND on every address.
        for bind in ["0.0.0.0:0", "0.0.0.0:7000", "[::]:0"] {
            let message = format!("{:#}", check_endpoint(Some(&at(bind))).await.unwrap_err());
            assert!(
                message.contains("always on")
                    && message.contains("not on every address")
                    && message.contains("TINFIRM_RUNNER_GIT_PROXY_BIND"),
                "{bind}: {message}"
            );
        }
        // An address that is not this machine's (a network whose gateway is elsewhere, or
        // rootless Docker's, which lives in its own namespace). Skipped where the kernel lets a
        // process bind any address (net.ipv4.ip_nonlocal_bind).
        let foreign = "192.0.2.1:0";
        if TcpListener::bind(foreign).await.is_err() {
            let message = format!(
                "{:#}",
                check_endpoint(Some(&at(foreign))).await.unwrap_err()
            );
            assert!(
                message.contains("always on")
                    && message.contains("cannot listen on 192.0.2.1")
                    && message.contains("TINFIRM_RUNNER_GIT_PROXY_HOST"),
                "{message}"
            );
        }
    }

    #[test]
    fn the_gateway_url_is_checked() {
        let parse = |url: &str, allow_http| {
            Gateway::parse(&access(ModelFormat::Openai, url), allow_http, None)
        };
        let gateway = parse("https://litellm.corp:8443/v1/", false).unwrap();
        assert!(gateway.https);
        assert_eq!(gateway.host, "litellm.corp");
        assert_eq!(gateway.port, 8443);
        assert_eq!(gateway.authority, "litellm.corp:8443");
        assert_eq!(gateway.base_path, "/v1");
        assert!(gateway.authorization.is_sensitive());
        assert_eq!(gateway.authorization, format!("Bearer {REAL_KEY}").as_str());
        let root = parse("https://[2001:db8::1]", false).unwrap();
        assert_eq!(root.base_path, "");
        assert_eq!(root.authority, "[2001:db8::1]");
        assert_eq!(root.ip, Some("2001:db8::1".parse().unwrap()));
        for bad in [
            "http://litellm.corp/v1",
            "ftp://litellm.corp",
            "https://user:pass@litellm.corp",
            "https://litellm.corp/v1?api-version=1",
            "https://litellm.corp/v1#x",
            "not a url",
        ] {
            assert!(parse(bad, false).is_err(), "{bad}");
        }
        assert!(parse("http://127.0.0.1:4000", true).is_ok());
        let bedrock = ModelAccess {
            format: ModelFormat::Bedrock,
            ..access(ModelFormat::Openai, "https://litellm.corp")
        };
        assert!(Gateway::parse(&bedrock, false, None).is_err());
        // The error never repeats the key.
        let mut bad_key = access(ModelFormat::Openai, "https://litellm.corp");
        bad_key.api_key = Secret::new("sk-bad\nkey");
        let err = Gateway::parse(&bad_key, false, None).err().unwrap();
        assert!(!format!("{err:#}").contains("sk-bad"), "{err:#}");
    }

    #[tokio::test]
    async fn the_base_url_is_the_listener_with_the_secret_and_the_base_path() {
        let anthropic = start(ModelFormat::Anthropic, "http://127.0.0.1:9").await;
        assert_eq!(
            anthropic.base_url(),
            format!(
                "http://127.0.0.2:{}/{}",
                anthropic.port(),
                anthropic.secret()
            )
        );
        assert_eq!(anthropic.secret().len(), 43);
        let openai = start(ModelFormat::Openai, "http://127.0.0.1:9/v1").await;
        assert_eq!(
            openai.base_url(),
            format!("http://127.0.0.2:{}/{}/v1", openai.port(), openai.secret())
        );
        assert_ne!(anthropic.secret(), openai.secret());
        assert_ne!(anthropic.port(), openai.port());
        assert_eq!(
            format!("{openai:?}"),
            format!("ModelProxyGuard {{ port: {} }}", openai.port())
        );
    }

    #[tokio::test]
    async fn forwards_with_the_real_key_only() {
        const ANSWER: &str = r#"{"id":"msg_1","content":[{"type":"text","text":"PONG"}]}"#;
        // A whole answer, with its length (as a gateway sends one that does not stream).
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply {
            headers: vec![
                ("content-type", "application/json".into()),
                ("content-length", ANSWER.len().to_string()),
                ("request-id", "req_1".into()),
                ("anthropic-ratelimit-requests-remaining", "99".into()),
                ("set-cookie", "session=gateway".into()),
            ],
            ..Reply::ok(ANSWER)
        }))
        .await;
        let guard = start(ModelFormat::Anthropic, &gateway.url("")).await;
        let (status, headers, body) = request(
            &guard,
            Method::POST,
            &format!("{}/v1/messages?beta=true", base_path(&guard)),
            &[
                // OpenCode sends the dummy as both; other clients send other credential headers.
                ("authorization", &format!("Bearer {DUMMY_KEY}")),
                ("x-api-key", DUMMY_KEY),
                ("api-key", DUMMY_KEY),
                ("proxy-authorization", "Basic eDp5"),
                ("x-litellm-api-key", "sk-another-key"),
                ("cookie", "session=1"),
                ("x-forwarded-for", "10.0.0.1"),
                ("connection", "keep-alive, x-hop"),
                ("x-hop", "1"),
                ("anthropic-version", "2023-06-01"),
                ("anthropic-beta", "prompt-caching-2024-07-31"),
                ("content-type", "application/json"),
                ("accept-encoding", "gzip, br"),
            ],
            r#"{"model":"claude-sonnet-4-5","max_tokens":10}"#,
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{body}");
        assert_eq!(body, ANSWER);
        assert_eq!(headers[header::CONTENT_LENGTH], ANSWER.len().to_string());
        assert!(headers.get(header::TRANSFER_ENCODING).is_none());
        assert_eq!(headers["request-id"], "req_1");
        assert_eq!(headers["anthropic-ratelimit-requests-remaining"], "99");
        assert!(headers.get(header::SET_COOKIE).is_none());

        let seen = gateway.seen();
        assert_eq!(seen.len(), 1);
        let got = &seen[0];
        assert_eq!(got.method, Method::POST);
        assert_eq!(got.uri, "/v1/messages?beta=true");
        assert_eq!(
            &got.body[..],
            br#"{"model":"claude-sonnet-4-5","max_tokens":10}"#
        );
        let auth: Vec<_> = got.headers.get_all(header::AUTHORIZATION).iter().collect();
        assert_eq!(auth, vec![format!("Bearer {REAL_KEY}").as_str()]);
        for name in CREDENTIAL_HEADERS.iter().filter(|n| **n != "authorization") {
            assert!(
                got.headers.get(*name).is_none(),
                "{name} reached the gateway"
            );
        }
        for name in ["x-forwarded-for", "x-hop", "connection"] {
            assert!(
                got.headers.get(name).is_none(),
                "{name} reached the gateway"
            );
        }
        assert_eq!(got.headers[header::HOST], gateway.addr.to_string());
        assert_eq!(got.headers["anthropic-version"], "2023-06-01");
        assert_eq!(got.headers["anthropic-beta"], "prompt-caching-2024-07-31");
        assert_eq!(got.headers[header::ACCEPT_ENCODING], "identity");
        assert!(
            !format!("{:?}", got.headers).contains(DUMMY_KEY),
            "the dummy key went on"
        );
        // The sandbox never sees the real key, in the answer's headers or body.
        assert!(!body.contains(REAL_KEY));
        assert!(!format!("{headers:?}").contains(REAL_KEY));
        assert_eq!(guard.counts(), (1, 0));
    }

    #[tokio::test]
    async fn wrong_or_missing_secrets_are_not_found() {
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        let guard = start(ModelFormat::Anthropic, &gateway.url("")).await;
        let other = start(ModelFormat::Anthropic, &gateway.url("")).await;
        let secret = guard.secret().to_string();
        let mut last_off = secret.clone();
        let last = last_off.pop().unwrap();
        last_off.push(if last == 'A' { 'B' } else { 'A' });
        for candidate in [
            last_off.as_str(),
            &secret[..42],
            &format!("{secret}x"),
            "",
            "v1",
            other.secret(),
        ] {
            let (status, _, body) = request(
                &guard,
                Method::POST,
                &format!("/{candidate}/v1/messages"),
                &[("content-type", "application/json")],
                "{}",
            )
            .await;
            assert_eq!(status, StatusCode::NOT_FOUND, "{candidate}");
            assert!(body.contains("not found"), "{body}");
        }
        let (status, _, _) = request(&guard, Method::GET, "/", &[], "").await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        let (status, _, _) = request(&guard, Method::GET, &format!("/{secret}"), &[], "").await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert!(gateway.seen().is_empty());
    }

    #[tokio::test]
    async fn only_the_model_endpoints_under_the_base_path_are_forwarded() {
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        // An Anthropic gateway at a root.
        let guard = start(ModelFormat::Anthropic, &gateway.url("")).await;
        let base = base_path(&guard);
        for (method, endpoint) in [
            (Method::POST, "/v1/messages"),
            (Method::POST, "/v1/messages/count_tokens"),
            (Method::GET, "/v1/models"),
            (Method::GET, "/v1/models?limit=1000"),
        ] {
            let (status, _, body) =
                request(&guard, method, &format!("{base}{endpoint}"), &[], "{}").await;
            assert_eq!(status, StatusCode::OK, "{endpoint}: {body}");
        }
        for endpoint in [
            "/key/generate",
            "/key/info",
            "/spend/logs",
            "/v1/complete",
            "/v1/messages/",
            "/v1/messages/batches",
            "/v1/models/claude-sonnet-4-5",
            "/v1/files",
            "/v1/responses",
            "//v1/messages",
            "/v1//messages",
            "/v1/../key/generate",
            "/v1/messages/../../key/generate",
            "/v1/%6Dessages",
            "/V1/messages",
            "",
        ] {
            let (status, _, body) = request(
                &guard,
                Method::POST,
                &format!("{base}{endpoint}"),
                &[],
                "{}",
            )
            .await;
            assert_eq!(status, StatusCode::NOT_FOUND, "{endpoint}: {body}");
        }
        let (status, headers, _) =
            request(&guard, Method::GET, &format!("{base}/v1/messages"), &[], "").await;
        assert_eq!(status, StatusCode::METHOD_NOT_ALLOWED);
        assert_eq!(headers[header::ALLOW], "POST");
        let (status, headers, _) = request(
            &guard,
            Method::DELETE,
            &format!("{base}/v1/models"),
            &[],
            "",
        )
        .await;
        assert_eq!(status, StatusCode::METHOD_NOT_ALLOWED);
        assert_eq!(headers[header::ALLOW], "GET");
        let forwarded: Vec<String> = gateway.seen().iter().map(|s| s.uri.clone()).collect();
        assert_eq!(
            forwarded,
            [
                "/v1/messages",
                "/v1/messages/count_tokens",
                "/v1/models",
                "/v1/models?limit=1000"
            ]
        );
        assert_eq!(guard.counts(), (4, 18));

        // An OpenAI gateway under `/v1`: its endpoints, under that path only.
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        let guard = start(ModelFormat::Openai, &gateway.url("/v1")).await;
        let base = base_path(&guard);
        assert!(base.ends_with("/v1"));
        for (method, endpoint) in [
            (Method::POST, "/responses"),
            (Method::POST, "/chat/completions"),
            (Method::GET, "/models"),
        ] {
            let (status, _, body) =
                request(&guard, method, &format!("{base}{endpoint}"), &[], "{}").await;
            assert_eq!(status, StatusCode::OK, "{endpoint}: {body}");
        }
        let secret = guard.secret().to_string();
        for path in [
            // Outside the base path.
            format!("/{secret}/responses"),
            format!("/{secret}/v2/responses"),
            format!("/{secret}/v1x/responses"),
            format!("/{secret}/key/generate"),
            // Other endpoints under it.
            format!("{base}/embeddings"),
            format!("{base}/files"),
            format!("{base}/batches"),
            format!("{base}/responses/resp_1"),
            format!("{base}/responses/compact"),
            format!("{base}/messages"),
            format!("{base}/models/gpt-5"),
        ] {
            let (status, _, body) = request(&guard, Method::POST, &path, &[], "{}").await;
            assert_eq!(status, StatusCode::NOT_FOUND, "{path}: {body}");
        }
        let forwarded: Vec<String> = gateway.seen().iter().map(|s| s.uri.clone()).collect();
        assert_eq!(
            forwarded,
            ["/v1/responses", "/v1/chat/completions", "/v1/models"]
        );
    }

    #[tokio::test]
    async fn streams_the_answer_as_it_arrives() {
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply {
            status: StatusCode::OK,
            headers: vec![("content-type", "text/event-stream".into())],
            parts: vec![
                Some("event: message_start\ndata: {\"type\":\"message_start\"}\n\n".into()),
                None,
                Some("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n".into()),
            ],
        }))
        .await;
        let guard = start(ModelFormat::Anthropic, &gateway.url("")).await;
        let mut stream = TcpStream::connect(listener_addr(&guard)).await.unwrap();
        let head = format!(
            "POST {}/v1/messages HTTP/1.1\r\nHost: proxy\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{{}}",
            base_path(&guard)
        );
        stream.write_all(head.as_bytes()).await.unwrap();
        // The first event arrives while the gateway still holds the second one back.
        let mut received = String::new();
        let mut buf = [0u8; 4096];
        while !received.contains("message_start\"}\n\n") {
            let n = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buf))
                .await
                .expect("the first event did not arrive before the answer ended")
                .unwrap();
            assert!(n > 0, "the connection closed: {received}");
            received.push_str(&String::from_utf8_lossy(&buf[..n]));
        }
        assert!(received.starts_with("HTTP/1.1 200"), "{received}");
        assert!(
            received
                .to_ascii_lowercase()
                .contains("content-type: text/event-stream")
        );
        assert!(!received.contains("message_stop"), "{received}");
        gateway.go();
        while !received.contains("message_stop\"}\n\n") {
            let n = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buf))
                .await
                .expect("the second event did not arrive")
                .unwrap();
            assert!(n > 0, "the connection closed: {received}");
            received.push_str(&String::from_utf8_lossy(&buf[..n]));
        }
    }

    #[tokio::test]
    async fn websocket_upgrades_are_refused() {
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        let guard = start(ModelFormat::Openai, &gateway.url("/v1")).await;
        let (status, _, body) = request(
            &guard,
            Method::GET,
            &format!("{}/responses", base_path(&guard)),
            &[
                ("connection", "Upgrade"),
                ("upgrade", "websocket"),
                ("sec-websocket-version", "13"),
                ("sec-websocket-key", "dGhlIHNhbXBsZSBub25jZQ=="), // gitleaks:allow (RFC 6455's sample nonce)
            ],
            "",
        )
        .await;
        assert_eq!(status, StatusCode::NOT_IMPLEMENTED);
        assert!(body.contains("WebSocket"), "{body}");
        assert!(gateway.seen().is_empty());
    }

    #[tokio::test]
    async fn nothing_answers_once_the_run_ends() {
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply {
            status: StatusCode::OK,
            headers: vec![("content-type", "text/event-stream".into())],
            parts: vec![Some("data: {}\n\n".into()), None],
        }))
        .await;
        let guard = start(ModelFormat::Anthropic, &gateway.url("")).await;
        let addr = listener_addr(&guard);
        // A streaming answer is open when the run ends.
        let mut stream = TcpStream::connect(addr).await.unwrap();
        let head = format!(
            "POST {}/v1/messages HTTP/1.1\r\nHost: proxy\r\nContent-Length: 2\r\n\r\n{{}}",
            base_path(&guard)
        );
        stream.write_all(head.as_bytes()).await.unwrap();
        let mut buf = [0u8; 4096];
        let n = stream.read(&mut buf).await.unwrap();
        assert!(String::from_utf8_lossy(&buf[..n]).starts_with("HTTP/1.1 200"));
        drop(guard);
        // The open answer ends...
        loop {
            let read = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buf))
                .await
                .expect("the answer stays open after the run ended");
            match read {
                Ok(0) | Err(_) => break,
                Ok(_) => continue,
            }
        }
        // ...and nothing listens any more.
        let mut gone = false;
        for _ in 0..50 {
            if TcpStream::connect(addr).await.is_err() {
                gone = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert!(gone, "the listener is still there");
    }

    #[tokio::test]
    async fn a_key_in_the_answer_never_reaches_the_sandbox() {
        // A gateway that echoes the key in its body, split across two parts.
        let (head, tail) = REAL_KEY.split_at(10);
        let echo = FakeGateway::start(Arc::new(move |_: &Seen| Reply {
            status: StatusCode::UNAUTHORIZED,
            headers: vec![("content-type", "application/json".into())],
            parts: vec![
                Some(format!(r#"{{"error":"bad key Bearer {head}"#)),
                Some(format!(r#"{tail}"}}"#)),
            ],
        }))
        .await;
        let guard = start(ModelFormat::Openai, &echo.url("/v1")).await;
        let (status, _, body) = request(
            &guard,
            Method::POST,
            &format!("{}/responses", base_path(&guard)),
            &[],
            "{}",
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert!(body.starts_with("<cut off"), "{body}");
        assert!(!body.contains(REAL_KEY), "{body}");

        // One that names it in a header.
        let header_echo = FakeGateway::start(Arc::new(|seen: &Seen| Reply {
            headers: vec![(
                "x-debug-auth",
                seen.headers[header::AUTHORIZATION]
                    .to_str()
                    .unwrap()
                    .to_string(),
            )],
            ..Reply::ok("{}")
        }))
        .await;
        let guard = start(ModelFormat::Openai, &header_echo.url("/v1")).await;
        let (status, headers, body) = request(
            &guard,
            Method::POST,
            &format!("{}/responses", base_path(&guard)),
            &[],
            "{}",
        )
        .await;
        assert_eq!(status, StatusCode::BAD_GATEWAY);
        assert!(!format!("{headers:?}{body}").contains(REAL_KEY));

        // Text that only starts like the key goes through whole.
        let mut scan = KeyScan {
            key: AhoCorasick::new([REAL_KEY]).unwrap(),
            secrets: vec![REAL_KEY.as_bytes().to_vec()],
            carry: Vec::new(),
        };
        let first = scan.feed(Bytes::from("data: sk-TEST")).unwrap();
        assert_eq!(first, "data: ");
        let second = scan.feed(Bytes::from("-ONLY but not the key\n\n")).unwrap();
        assert_eq!(second, "sk-TEST-ONLY but not the key\n\n");
        assert_eq!(scan.feed(Bytes::from("data: s")).unwrap(), "data: ");
        assert_eq!(scan.finish().unwrap(), "s");
        assert!(scan.finish().is_none());
    }

    #[tokio::test]
    async fn redirects_are_not_followed() {
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply {
            status: StatusCode::TEMPORARY_REDIRECT,
            headers: vec![("location", "https://elsewhere.example/v1/messages".into())],
            parts: vec![],
        }))
        .await;
        let guard = start(ModelFormat::Anthropic, &gateway.url("")).await;
        let (status, headers, _) = request(
            &guard,
            Method::POST,
            &format!("{}/v1/messages", base_path(&guard)),
            &[],
            "{}",
        )
        .await;
        assert_eq!(status, StatusCode::BAD_GATEWAY);
        assert!(headers.get(header::LOCATION).is_none());
        assert_eq!(gateway.seen().len(), 1);
    }

    #[tokio::test]
    async fn gateway_errors_pass_through() {
        // A spent budget or a revoked key: the runtime must see the gateway's answer.
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply {
            status: StatusCode::TOO_MANY_REQUESTS,
            headers: vec![
                ("content-type", "application/json".into()),
                ("retry-after", "30".into()),
            ],
            parts: vec![Some(
                r#"{"error":{"message":"Budget has been exceeded"}}"#.into(),
            )],
        }))
        .await;
        let guard = start(ModelFormat::Openai, &gateway.url("/v1")).await;
        let (status, headers, body) = request(
            &guard,
            Method::POST,
            &format!("{}/chat/completions", base_path(&guard)),
            &[],
            "{}",
        )
        .await;
        assert_eq!(status, StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(headers[header::RETRY_AFTER], "30");
        assert!(body.contains("Budget has been exceeded"), "{body}");
    }

    #[tokio::test]
    async fn oversized_request_bodies_are_refused() {
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        let guard = start(ModelFormat::Anthropic, &gateway.url("")).await;
        let mut stream = TcpStream::connect(listener_addr(&guard)).await.unwrap();
        let head = format!(
            "POST {}/v1/messages HTTP/1.1\r\nHost: proxy\r\nContent-Length: {}\r\n\r\n",
            base_path(&guard),
            MAX_REQUEST_BODY + 1
        );
        stream.write_all(head.as_bytes()).await.unwrap();
        let mut answer = vec![0u8; 1024];
        let n = stream.read(&mut answer).await.unwrap();
        let answer = String::from_utf8_lossy(&answer[..n]);
        assert!(answer.starts_with("HTTP/1.1 413"), "{answer}");
        assert!(gateway.seen().is_empty());
    }

    #[tokio::test]
    async fn the_gateways_address_is_checked() {
        // Production rules: loopback, link-local (metadata) and reserved addresses are refused,
        // and private ones without --egress-allow-private; nothing is connected to.
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        let strict = ModelProxySettings {
            allow_http: true,
            ..ModelProxySettings::default()
        };
        let proxy = ModelProxy::new(strict, &endpoint());
        for url in [
            gateway.url(""),
            "http://localhost:9".to_string(),
            "http://169.254.169.254".to_string(),
            "http://[::1]:9".to_string(),
            "http://192.0.2.10:8443".to_string(),
            "http://10.1.2.3:4000".to_string(),
        ] {
            let guard = proxy
                .start_run(Uuid::new_v4(), &access(ModelFormat::Anthropic, &url))
                .await
                .unwrap();
            let (status, _, body) = request(
                &guard,
                Method::POST,
                &format!("{}/v1/messages", base_path(&guard)),
                &[],
                "{}",
            )
            .await;
            assert_eq!(status, StatusCode::BAD_GATEWAY, "{url}: {body}");
            assert!(body.contains("does not connect to"), "{url}: {body}");
        }
        assert!(gateway.seen().is_empty());
        // The listener's own address is never the gateway, even with loopback allowed.
        let own = TcpListener::bind("127.0.0.2:0").await.unwrap();
        let guard = start(
            ModelFormat::Anthropic,
            &format!("http://{}", own.local_addr().unwrap()),
        )
        .await;
        let (status, _, body) = request(
            &guard,
            Method::POST,
            &format!("{}/v1/messages", base_path(&guard)),
            &[],
            "{}",
        )
        .await;
        assert_eq!(status, StatusCode::BAD_GATEWAY, "{body}");
    }

    #[tokio::test]
    async fn only_the_named_local_gateway_may_be_on_this_machine() {
        async fn post(guard: &ModelProxyGuard) -> (StatusCode, String) {
            let path = format!("{}/v1/messages", base_path(guard));
            let (status, _, body) = request(guard, Method::POST, &path, &[], "{}").await;
            (status, body)
        }
        // Production address rules (no loopback), with --model-proxy-local-gateway.
        let settings = |local: &str| ModelProxySettings {
            local_gateway: Some(url::Url::parse(local).unwrap().origin()),
            allow_http: true,
            ..ModelProxySettings::default()
        };
        let named = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        let other = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        let proxy = ModelProxy::new(settings(&named.url("")), &endpoint());
        let start = |url: String| {
            let proxy = &proxy;
            async move {
                proxy
                    .start_run(Uuid::new_v4(), &access(ModelFormat::Anthropic, &url))
                    .await
                    .unwrap()
            }
        };

        // The named origin connects on loopback, with the real key.
        let guard = start(named.url("")).await;
        let (status, body) = post(&guard).await;
        assert_eq!(status, StatusCode::OK, "{body}");
        let seen = named.seen();
        assert_eq!(seen.len(), 1);
        assert_eq!(
            seen[0].headers[header::AUTHORIZATION],
            format!("Bearer {REAL_KEY}").as_str()
        );

        // Another origin on loopback stays refused: another port, or the same port by another
        // name.
        let port = named.addr.port();
        for url in [other.url(""), format!("http://localhost:{port}")] {
            let guard = start(url.clone()).await;
            let (status, body) = post(&guard).await;
            assert_eq!(status, StatusCode::BAD_GATEWAY, "{url}: {body}");
            assert!(body.contains("does not connect to"), "{url}: {body}");
            assert!(
                body.contains("--model-proxy-local-gateway"),
                "{url}: {body}"
            );
        }
        assert!(other.seen().is_empty());
        assert_eq!(named.seen().len(), 1);

        // Named or not: never the listener's own address, cloud metadata, or a private address
        // of another machine without --egress-allow-private.
        let own_listener = TcpListener::bind("127.0.0.2:0").await.unwrap();
        let own = format!("http://{}", own_listener.local_addr().unwrap());
        for (url, why) in [
            (own.as_str(), "(the model proxy's own)"),
            ("http://169.254.169.254", "cloud metadata"),
            ("http://100.100.100.200", "(cloud metadata)"),
            ("http://10.1.2.3:4000", "(private)"),
        ] {
            let guard = ModelProxy::new(settings(url), &endpoint())
                .start_run(Uuid::new_v4(), &access(ModelFormat::Anthropic, url))
                .await
                .unwrap();
            let (status, body) = post(&guard).await;
            assert_eq!(status, StatusCode::BAD_GATEWAY, "{url}: {body}");
            assert!(body.contains(why), "{url}: {body}");
        }
    }

    const HOST_TOKEN: &str = "tfr_TEST-ONLY-host-token-0000000000000000000000";

    /// A proxy whose host logged in to `server_url` with [`HOST_TOKEN`].
    async fn start_with_host_token(
        format: ModelFormat,
        base_url: &str,
        server_url: &str,
    ) -> ModelProxyGuard {
        ModelProxy::new(test_settings(), &endpoint())
            .with_host_token(HostToken::new(server_url, HOST_TOKEN).unwrap())
            .start_run(Uuid::new_v4(), &access(format, base_url))
            .await
            .unwrap()
    }

    #[test]
    fn the_host_token_belongs_to_the_origin_of_the_server_url() {
        let origin = |server: &str| {
            HostToken::new(server, HOST_TOKEN)
                .unwrap()
                .origin
                .ascii_serialization()
        };
        // The scheme the WebSocket uses is the server's https; a base path, a trailing slash, the
        // host's case and a default port do not count.
        assert_eq!(
            origin("https://tinfirm.example.test"),
            "https://tinfirm.example.test"
        );
        assert_eq!(
            origin("https://Tinfirm.Example.test:443/base/"),
            "https://tinfirm.example.test"
        );
        assert_eq!(
            origin("wss://tinfirm.example.test/base"),
            "https://tinfirm.example.test"
        );
        assert_eq!(origin("http://localhost:8080"), "http://localhost:8080");
        assert_eq!(origin("ws://localhost:8080"), "http://localhost:8080");
        for bad in ["ftp://tinfirm.example.test", "not a url"] {
            assert!(HostToken::new(bad, HOST_TOKEN).is_err(), "{bad}");
        }
        // Never printed, and never repeated in an error.
        let token = HostToken::new("https://tinfirm.example.test", HOST_TOKEN).unwrap();
        assert!(token.header.is_sensitive());
        assert!(!format!("{token:?}").contains(HOST_TOKEN));
        let err = HostToken::new("https://tinfirm.example.test", "tfr_bad\nvalue")
            .err()
            .unwrap();
        assert!(!format!("{err:#}").contains("tfr_bad"), "{err:#}");
        // The relay's gateway at the server's origin gets it; any other origin does not.
        let relay = |base: &str, server: &str| {
            let token = HostToken::new(server, HOST_TOKEN).unwrap();
            Gateway::parse(&access(ModelFormat::Anthropic, base), false, Some(&token)).unwrap()
        };
        let to_server = relay(
            "https://tinfirm.example.test/api/runner/v1/model/anthropic",
            "wss://TINFIRM.example.test:443",
        );
        assert_eq!(to_server.host_token.as_ref().unwrap(), HOST_TOKEN);
        assert!(to_server.host_token.as_ref().unwrap().is_sensitive());
        assert!(!to_server.host_token_withheld);
        for (base, server, withheld) in [
            // The relay of another origin than the server URL's: another host, port or scheme.
            (
                "https://public.example.test/api/runner/v1/model/anthropic",
                "https://internal.example.test",
                true,
            ),
            (
                "https://tinfirm.example.test:8443/api/runner/v1/model/anthropic",
                "https://tinfirm.example.test",
                true,
            ),
            (
                "https://tinfirm.example.test/api/runner/v1/model/anthropic",
                "http://tinfirm.example.test",
                true,
            ),
            // A company's own gateway, as an older server sends it: not the server, not a relay.
            (
                "https://litellm.corp.example:4000",
                "https://tinfirm.example.test",
                false,
            ),
        ] {
            let gateway = relay(base, server);
            assert!(gateway.host_token.is_none(), "{base} for {server}");
            assert_eq!(gateway.host_token_withheld, withheld, "{base} for {server}");
        }
        // Without a host token, nothing is sent and nothing is withheld on purpose.
        let none = Gateway::parse(
            &access(
                ModelFormat::Anthropic,
                "https://tinfirm.example.test/api/runner/v1/model/anthropic",
            ),
            false,
            None,
        )
        .unwrap();
        assert!(none.host_token.is_none());
    }

    #[tokio::test]
    async fn the_host_token_goes_to_its_server_only_and_never_from_the_sandbox() {
        let gateway = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        // The gateway is the host's server: its own token goes with every request, and the one the
        // sandbox sends is dropped (a prompt-injected agent's guess, or a copied token).
        let guard = start_with_host_token(
            ModelFormat::Anthropic,
            &gateway.url("/api/runner/v1/model/anthropic"),
            &gateway.url("/"),
        )
        .await;
        assert!(guard.sends_host_token() && !guard.host_token_withheld());
        let base = base_path(&guard);
        for (method, endpoint, headers) in [
            (Method::POST, "/v1/messages", vec![]),
            (
                Method::POST,
                "/v1/messages",
                vec![(HOST_TOKEN_HEADER, "tfr_forged-by-the-sandbox")],
            ),
            (
                Method::POST,
                "/v1/messages",
                vec![
                    ("X-Tinfirm-Runner-Token", "tfr_forged-by-the-sandbox"),
                    ("x-tinfirm-runner-token", HOST_TOKEN),
                ],
            ),
            (Method::GET, "/v1/models", vec![(HOST_TOKEN_HEADER, "")]),
        ] {
            let (status, _, body) =
                request(&guard, method, &format!("{base}{endpoint}"), &headers, "{}").await;
            assert_eq!(status, StatusCode::OK, "{body}");
        }
        let seen = gateway.seen();
        assert_eq!(seen.len(), 4);
        for got in &seen {
            let sent: Vec<_> = got.headers.get_all(HOST_TOKEN_HEADER).iter().collect();
            assert_eq!(sent, vec![HOST_TOKEN], "{}", got.uri);
            assert_eq!(
                got.headers[header::AUTHORIZATION],
                format!("Bearer {REAL_KEY}").as_str()
            );
            assert!(!format!("{:?}", got.headers).contains("forged"));
        }

        // Another gateway, or the relay at an origin that is not the server URL's: the host's
        // token never goes there, nor the sandbox's.
        let other = FakeGateway::start(Arc::new(|_: &Seen| Reply::ok("{}"))).await;
        for base_url in [other.url(""), other.url("/api/runner/v1/model/anthropic")] {
            let guard =
                start_with_host_token(ModelFormat::Anthropic, &base_url, &gateway.url("")).await;
            assert!(!guard.sends_host_token());
            assert_eq!(guard.host_token_withheld(), base_url.contains(RELAY_PATH));
            let (status, _, body) = request(
                &guard,
                Method::POST,
                &format!("{}/v1/messages", base_path(&guard)),
                &[(HOST_TOKEN_HEADER, "tfr_forged-by-the-sandbox")],
                "{}",
            )
            .await;
            assert_eq!(status, StatusCode::OK, "{body}");
        }
        let seen = other.seen();
        assert_eq!(seen.len(), 2);
        for got in &seen {
            assert!(got.headers.get(HOST_TOKEN_HEADER).is_none(), "{}", got.uri);
            assert!(!format!("{:?}", got.headers).contains(HOST_TOKEN));
        }
    }

    #[tokio::test]
    async fn the_host_token_in_the_answer_never_reaches_the_sandbox() {
        // A server that echoes the host's token, split across two parts.
        let (head, tail) = HOST_TOKEN.split_at(12);
        let echo = FakeGateway::start(Arc::new(move |_: &Seen| Reply {
            status: StatusCode::UNAUTHORIZED,
            headers: vec![("content-type", "application/json".into())],
            parts: vec![
                Some(format!(r#"{{"error":"bad host {head}"#)),
                Some(format!(r#"{tail}"}}"#)),
            ],
        }))
        .await;
        let guard = start_with_host_token(
            ModelFormat::Openai,
            &echo.url("/api/runner/v1/model/openai/v1"),
            &echo.url(""),
        )
        .await;
        let (status, _, body) = request(
            &guard,
            Method::POST,
            &format!("{}/responses", base_path(&guard)),
            &[],
            "{}",
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert!(body.starts_with("<cut off"), "{body}");
        assert!(!body.contains(HOST_TOKEN), "{body}");

        // One that names it in a header.
        let header_echo = FakeGateway::start(Arc::new(|seen: &Seen| Reply {
            headers: vec![(
                "x-debug-host",
                seen.headers[HOST_TOKEN_HEADER]
                    .to_str()
                    .unwrap()
                    .to_string(),
            )],
            ..Reply::ok("{}")
        }))
        .await;
        let guard = start_with_host_token(
            ModelFormat::Openai,
            &header_echo.url("/api/runner/v1/model/openai/v1"),
            &header_echo.url(""),
        )
        .await;
        let (status, headers, body) = request(
            &guard,
            Method::POST,
            &format!("{}/responses", base_path(&guard)),
            &[],
            "{}",
        )
        .await;
        assert_eq!(status, StatusCode::BAD_GATEWAY);
        assert!(!format!("{headers:?}{body}").contains(HOST_TOKEN));

        // Text that only starts like the host's token goes through whole.
        let mut scan = KeyScan {
            key: AhoCorasick::new([REAL_KEY, HOST_TOKEN]).unwrap(),
            secrets: vec![REAL_KEY.as_bytes().to_vec(), HOST_TOKEN.as_bytes().to_vec()],
            carry: Vec::new(),
        };
        assert_eq!(scan.feed(Bytes::from("data: tfr_TEST")).unwrap(), "data: ");
        assert_eq!(
            scan.feed(Bytes::from("-ONLY but not the token\n\n"))
                .unwrap(),
            "tfr_TEST-ONLY but not the token\n\n"
        );
        assert!(
            scan.feed(Bytes::from(format!("x {HOST_TOKEN} y")))
                .is_none()
        );
    }

    #[tokio::test]
    async fn the_proxy_will_not_listen_on_every_address() {
        let endpoint = Endpoint {
            bind: "0.0.0.0:0".parse().unwrap(),
            sandbox_host: "10.0.0.1".into(),
            extra_hosts: Vec::new(),
        };
        let err = ModelProxy::new(test_settings(), &endpoint)
            .start_run(
                Uuid::new_v4(),
                &access(ModelFormat::Anthropic, "http://127.0.0.1:9"),
            )
            .await
            .unwrap_err();
        assert!(err.to_string().contains("not on every address"), "{err}");
    }
}
