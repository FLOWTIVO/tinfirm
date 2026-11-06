//! Fetch-only git proxy for the sandbox (docs/archive/v1/CONVENTIONS.md §15.4 step 2).
//!
//! Git credentials never enter the sandbox, but the agent needs `git fetch`, and a partial
//! clone (`--filter=blob:none`) fetches missing blobs from `origin` whenever git needs them.
//! So while the agent runs, the workspace's `origin` points at this proxy:
//! `http://<proxy host>:<port>/<run secret>/<repository path>`. The proxy runs in the runner
//! host process and forwards git's smart HTTP fetch requests to the run's repository, with
//! the host's `Authorization` header added on the way out:
//!
//! - `GET <prefix>/info/refs?service=git-upload-pack` and `POST <prefix>/git-upload-pack`
//!   are forwarded. Only a few request headers go upstream (`Git-Protocol`, `Content-Type`,
//!   `Accept`, `Content-Encoding`, `User-Agent`); the client's `Authorization` never does.
//! - `git-receive-pack` (push) is refused with 403 and a plain-text reason. Any other path
//!   is 404, and so is an unknown secret.
//! - Redirects are not followed, and an upstream 401 is not passed on (it would make git in
//!   the sandbox ask for credentials).
//!
//! One listener serves every run. A run registers one route per repository when its
//! workspace is ready; the [`RouteGuard`] removes it when the run ends. The route's secret
//! (32 random bytes, base64url) is compared with [`secret_eq`] and maps to exactly one
//! repository, so a sandbox reaches only the repositories of its own run, and only while its
//! run lasts.
//!
//! The same listener serves each run's platform MCP endpoint ([`crate::mcp`]) at
//! `/<mcp secret>/mcp`, with its own per-run secret; the [`McpGuard`] removes it when the run
//! ends. So every run needs the listener, with or without repositories.
//!
//! The listener is shared by every run on the host, so no run may hold it for the others
//! (M5 of the security review of 2026-10-27). [`ListenerLimits`] bounds it:
//! - at most 256 connections at once (more wait until one closes), and at most 8 from one
//!   peer address (more are closed at once). Loopback peers are exempt from the per-peer cap:
//!   with the sandbox bridge every run's tunnel connects from loopback, and the bridge's own
//!   per-sandbox stream cap bounds them;
//! - a whole-request deadline: the headers within 20 s, then the body within 40 s (408);
//! - at most 8 requests at once per route (a repository of a run, or a run's MCP endpoint),
//!   held until the response's body ends; more are refused with 429;
//! - an idle timeout: a connection that moved no byte for 120 s while no request of it is
//!   being handled is closed (a response body the sandbox stopped reading, or an upstream
//!   that stopped sending).
//!
//! The listener must be reachable from the sandboxes but not from the network. On Docker
//! Engine (Linux) it binds the gateway IP of the sandbox network (`docker0` for the default
//! `bridge`). With Docker Desktop (and other engines in a VM, such as on macOS) it binds
//! `127.0.0.1`, which the sandboxes reach as `host.docker.internal`.
//! `TINFIRM_RUNNER_GIT_PROXY_BIND` (listen address, with an optional port) and
//! `TINFIRM_RUNNER_GIT_PROXY_HOST` (the host name or IP the sandbox uses) override that.

use std::collections::HashMap;
use std::convert::Infallible;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::task::{Context as TaskContext, Poll};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use bollard::Docker;
use bollard::models::NetworkInspect;
use http_body_util::combinators::UnsyncBoxBody;
use http_body_util::{BodyExt, Empty, Full, LengthLimitError, Limited};
use hyper::body::{Body, Bytes, Frame, Incoming, SizeHint};
use hyper::header::{self, HeaderName, HeaderValue};
use hyper::{Method, Request, Response, StatusCode, Uri};
use hyper_util::rt::{TokioIo, TokioTimer};
use tinfirm_protocol::Secret;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::mcp::{self, RunTools};
use crate::sandbox::Executor;
use crate::secret_eq::secret_eq;

/// Largest request body passed upstream. A fetch negotiation is far smaller.
pub const MAX_REQUEST_BODY: u64 = 64 * 1024 * 1024;
/// Random bytes in a run secret.
const SECRET_BYTES: usize = 32;
/// Open connections to the listener; more wait until one closes.
const MAX_CONNECTIONS: usize = 256;
/// Open connections from one peer address that is not loopback; more are closed at once.
const MAX_CONNECTIONS_PER_PEER: usize = 8;
/// Requests of one route (a repository of a run, or a run's MCP endpoint) at once; more are
/// refused with 429.
const MAX_REQUESTS_PER_ROUTE: usize = 8;
/// The request's headers; with [`BODY_READ_TIMEOUT`], the whole request within 60 s.
const HEADER_READ_TIMEOUT: Duration = Duration::from_secs(20);
/// The request's body, from the end of its headers.
const BODY_READ_TIMEOUT: Duration = Duration::from_secs(40);
/// A connection that moved no byte for this long, while none of its requests is handled, is
/// closed.
const IDLE_TIMEOUT: Duration = Duration::from_secs(120);
/// The largest read buffer of a connection (hyper's default is about 400 KiB): the headers of
/// a git or MCP request are far smaller.
const MAX_BUF_SIZE: usize = 64 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
/// Until the upstream answers with its headers; the body then streams without a limit.
const RESPONSE_TIMEOUT: Duration = Duration::from_secs(300);
/// The host name Docker Desktop gives containers for the host machine.
pub const HOST_DOCKER_INTERNAL: &str = "host.docker.internal";

const GIT_PROTOCOL: HeaderName = HeaderName::from_static("git-protocol");
/// Request headers passed upstream. Everything else is dropped, `Authorization` included.
const REQUEST_HEADERS: [HeaderName; 5] = [
    GIT_PROTOCOL,
    header::CONTENT_TYPE,
    header::ACCEPT,
    header::CONTENT_ENCODING,
    header::USER_AGENT,
];
/// Response headers passed back to the sandbox.
const RESPONSE_HEADERS: [HeaderName; 5] = [
    header::CONTENT_TYPE,
    header::CONTENT_ENCODING,
    header::CACHE_CONTROL,
    header::EXPIRES,
    header::PRAGMA,
];

type BoxError = Box<dyn std::error::Error + Send + Sync>;
type ProxyBody = UnsyncBoxBody<Bytes, BoxError>;

/// The bounds of the shared listener (see the module docs). Tests shorten them.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ListenerLimits {
    pub max_connections: usize,
    pub max_connections_per_peer: usize,
    /// Apply the per-peer cap to loopback peers too (tests, which have no other address).
    pub limit_loopback_peers: bool,
    pub max_requests_per_route: usize,
    pub header_read_timeout: Duration,
    pub body_read_timeout: Duration,
    pub idle_timeout: Duration,
}

impl Default for ListenerLimits {
    fn default() -> ListenerLimits {
        ListenerLimits {
            max_connections: MAX_CONNECTIONS,
            max_connections_per_peer: MAX_CONNECTIONS_PER_PEER,
            limit_loopback_peers: false,
            max_requests_per_route: MAX_REQUESTS_PER_ROUTE,
            header_read_timeout: HEADER_READ_TIMEOUT,
            body_read_timeout: BODY_READ_TIMEOUT,
            idle_timeout: IDLE_TIMEOUT,
        }
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

// ----- where the proxy listens ---------------------------------------------------------

/// Where the proxy listens, and how a sandbox reaches it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Endpoint {
    pub bind: SocketAddr,
    /// Host name or IP that the sandbox connects to.
    pub sandbox_host: String,
    /// Docker `extra_hosts` entries the sandbox needs to resolve `sandbox_host`.
    pub extra_hosts: Vec<String>,
}

/// `TINFIRM_RUNNER_GIT_PROXY_BIND` and `TINFIRM_RUNNER_GIT_PROXY_HOST`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Overrides {
    pub bind: Option<SocketAddr>,
    pub host: Option<String>,
}

impl Overrides {
    pub fn from_env() -> Overrides {
        Overrides::parse(
            std::env::var("TINFIRM_RUNNER_GIT_PROXY_BIND")
                .ok()
                .as_deref(),
            std::env::var("TINFIRM_RUNNER_GIT_PROXY_HOST")
                .ok()
                .as_deref(),
        )
    }

    fn parse(bind: Option<&str>, host: Option<&str>) -> Overrides {
        let bind = bind
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .and_then(|v| {
                let parsed = v
                    .parse::<SocketAddr>()
                    .ok()
                    .or_else(|| v.parse::<IpAddr>().ok().map(|ip| SocketAddr::new(ip, 0)));
                if parsed.is_none() {
                    tracing::warn!(
                        value = v,
                        "invalid TINFIRM_RUNNER_GIT_PROXY_BIND (an IP address, optionally with a port); detecting the address"
                    );
                }
                parsed
            });
        let host = host
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .and_then(|v| {
                let valid = v.parse::<IpAddr>().is_ok()
                    || v.bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-');
                if !valid {
                    tracing::warn!(
                        value = v,
                        "invalid TINFIRM_RUNNER_GIT_PROXY_HOST (a host name or an IP address); detecting the host"
                    );
                }
                valid.then(|| v.to_string())
            });
        Overrides { bind, host }
    }
}

/// The proxy's endpoint for sandboxes of `executor` on the Docker network `network` (`None`
/// is Docker's default, `bridge`). `Ok(None)` when the sandboxes have no network (`none`).
pub async fn endpoint(
    executor: &Executor,
    network: Option<&str>,
    overrides: &Overrides,
) -> Result<Option<Endpoint>> {
    let loopback = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0);
    let (bind, host, desktop) = match executor {
        // Test executor: the agent is a process on this machine. A VM reaches the listeners
        // only through its bridge's tunnels (crate::bridge::listeners_endpoint).
        Executor::Local { .. } | Executor::Vm(_) => (loopback, "127.0.0.1".to_string(), false),
        Executor::Docker(docker) => {
            if network == Some("none") {
                return Ok(None);
            }
            let info = docker.info().await.context("cannot query Docker")?;
            let desktop = info.operating_system.as_deref() == Some("Docker Desktop");
            if desktop || !cfg!(target_os = "linux") {
                // The engine runs in a VM: its networks are not on this machine, but its
                // containers reach this machine's loopback as host.docker.internal.
                (loopback, HOST_DOCKER_INTERNAL.to_string(), desktop)
            } else if network == Some("host") {
                (loopback, "127.0.0.1".to_string(), false)
            } else if let (Some(bind), Some(host)) = (overrides.bind, &overrides.host) {
                (bind, host.clone(), false)
            } else {
                let name = network.unwrap_or("bridge");
                let gateway = network_gateway(docker, name).await?;
                (SocketAddr::new(gateway, 0), gateway.to_string(), false)
            }
        }
    };
    let bind = overrides.bind.unwrap_or(bind);
    let sandbox_host = overrides.host.clone().unwrap_or(host);
    // Docker Desktop resolves host.docker.internal itself; Docker Engine needs the entry.
    let extra_hosts = if matches!(executor, Executor::Docker(_))
        && sandbox_host == HOST_DOCKER_INTERNAL
        && !desktop
    {
        vec![format!("{HOST_DOCKER_INTERNAL}:host-gateway")]
    } else {
        Vec::new()
    };
    Ok(Some(Endpoint {
        bind,
        sandbox_host,
        extra_hosts,
    }))
}

async fn network_gateway(docker: &Docker, name: &str) -> Result<IpAddr> {
    let network = docker
        .inspect_network(name, None)
        .await
        .with_context(|| format!("cannot inspect the Docker network `{name}`"))?;
    gateway_of(&network).ok_or_else(|| {
        anyhow!(
            "the Docker network `{name}` has no gateway address; set TINFIRM_RUNNER_GIT_PROXY_BIND and TINFIRM_RUNNER_GIT_PROXY_HOST"
        )
    })
}

/// The gateway of a Docker network (its IPAM config), IPv4 first.
fn gateway_of(network: &NetworkInspect) -> Option<IpAddr> {
    let gateways: Vec<IpAddr> = network
        .ipam
        .as_ref()?
        .config
        .as_ref()?
        .iter()
        .filter_map(|c| c.gateway.as_deref()?.parse().ok())
        .collect();
    gateways
        .iter()
        .find(|ip| ip.is_ipv4())
        .or(gateways.first())
        .copied()
}

// ----- routes --------------------------------------------------------------------------

/// The repository a route leads to, split for requests.
#[derive(Debug, Clone)]
struct Upstream {
    https: bool,
    /// Host name or IP to connect to (no brackets).
    host: String,
    port: u16,
    /// Value of the `Host` header.
    authority: String,
    /// Repository path without a trailing slash; empty for a repository at the root.
    path: String,
}

impl Upstream {
    fn parse(repo_url: &str, allow_http: bool) -> Result<Upstream> {
        let url = url::Url::parse(repo_url).context("invalid repository URL")?;
        let https = match url.scheme() {
            "https" => true,
            "http" if allow_http => false,
            other => bail!("the git proxy forwards only to HTTPS repositories, not `{other}`"),
        };
        if !url.username().is_empty() || url.password().is_some() {
            bail!("the repository URL must not contain credentials");
        }
        if url.query().is_some() || url.fragment().is_some() {
            bail!("the repository URL must not have a query or a fragment");
        }
        let host = match url.host().context("the repository URL has no host")? {
            url::Host::Domain(domain) => domain.to_string(),
            url::Host::Ipv4(ip) => ip.to_string(),
            url::Host::Ipv6(ip) => ip.to_string(),
        };
        let port = url
            .port_or_known_default()
            .context("the repository URL has no port")?;
        let host_str = url.host_str().unwrap_or_default();
        let authority = match url.port() {
            Some(port) => format!("{host_str}:{port}"),
            None => host_str.to_string(),
        };
        Ok(Upstream {
            https,
            host,
            port,
            authority,
            path: url.path().trim_end_matches('/').to_string(),
        })
    }
}

/// One run's route: its secret and the repository behind it.
struct Route {
    secret: Secret,
    run_id: Uuid,
    upstream: Upstream,
    /// Value of the `Authorization` header sent upstream.
    authorization: Option<Secret>,
    branch: String,
    /// Its requests in flight ([`ListenerLimits::max_requests_per_route`]).
    in_flight: Arc<Semaphore>,
}

/// One run's platform MCP endpoint.
struct McpRoute {
    secret: Secret,
    run_id: Uuid,
    tools: Arc<RunTools>,
    /// Its requests in flight ([`ListenerLimits::max_requests_per_route`]).
    in_flight: Arc<Semaphore>,
}

/// The shared listener of the git proxy and the platform MCP endpoints.
#[derive(Clone)]
pub struct GitProxy {
    inner: Arc<Inner>,
}

struct Inner {
    routes: Mutex<HashMap<u64, Arc<Route>>>,
    mcp_routes: Mutex<HashMap<u64, Arc<McpRoute>>>,
    next_route: AtomicU64,
    addr: SocketAddr,
    endpoint: Endpoint,
    /// Plain `http://` upstreams (tests and local development only).
    allow_http: bool,
    tls: OnceLock<Arc<rustls::ClientConfig>>,
    shutdown: CancellationToken,
    limits: ListenerLimits,
    /// Open connections per peer address ([`ListenerLimits::max_connections_per_peer`]).
    peers: Mutex<HashMap<IpAddr, usize>>,
}

/// A registered route. Dropping it removes the route: the run is over.
pub struct RouteGuard {
    inner: Arc<Inner>,
    id: u64,
    secret: Secret,
    sandbox_url: String,
}

impl RouteGuard {
    /// The `origin` URL for the sandbox. It contains the run secret: never log it.
    pub fn sandbox_url(&self) -> &str {
        &self.sandbox_url
    }

    /// The run secret, for the redactor.
    pub fn secret(&self) -> &Secret {
        &self.secret
    }

    /// Docker `extra_hosts` entries the sandbox needs to reach the proxy.
    pub fn extra_hosts(&self) -> &[String] {
        &self.inner.endpoint.extra_hosts
    }
}

impl Drop for RouteGuard {
    fn drop(&mut self) {
        if let Some(route) = lock(&self.inner.routes).remove(&self.id) {
            tracing::info!(run_id = %route.run_id, "git proxy route removed");
        }
    }
}

impl std::fmt::Debug for RouteGuard {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RouteGuard").field("id", &self.id).finish()
    }
}

/// A registered platform MCP endpoint. Dropping it removes the endpoint: the run is over.
pub struct McpGuard {
    inner: Arc<Inner>,
    id: u64,
    secret: Secret,
    url: String,
}

impl McpGuard {
    /// The endpoint URL for the sandbox. It contains the MCP secret: never log it.
    pub fn url(&self) -> &str {
        &self.url
    }

    /// The MCP secret, for the redactor.
    pub fn secret(&self) -> &Secret {
        &self.secret
    }

    /// Docker `extra_hosts` entries the sandbox needs to reach the endpoint.
    pub fn extra_hosts(&self) -> &[String] {
        &self.inner.endpoint.extra_hosts
    }
}

impl Drop for McpGuard {
    fn drop(&mut self) {
        if let Some(route) = lock(&self.inner.mcp_routes).remove(&self.id) {
            tracing::info!(run_id = %route.run_id, "platform MCP endpoint removed");
        }
    }
}

impl std::fmt::Debug for McpGuard {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("McpGuard").field("id", &self.id).finish()
    }
}

impl GitProxy {
    /// Binds the listener and serves until [`GitProxy::shutdown`].
    pub async fn start(endpoint: Endpoint, allow_http: bool) -> Result<GitProxy> {
        GitProxy::start_with_limits(endpoint, allow_http, ListenerLimits::default()).await
    }

    /// [`GitProxy::start`] with other bounds (tests).
    pub async fn start_with_limits(
        endpoint: Endpoint,
        allow_http: bool,
        limits: ListenerLimits,
    ) -> Result<GitProxy> {
        let listener = TcpListener::bind(endpoint.bind).await.with_context(|| {
            format!(
                "cannot listen on {}; set TINFIRM_RUNNER_GIT_PROXY_BIND and TINFIRM_RUNNER_GIT_PROXY_HOST",
                endpoint.bind
            )
        })?;
        let addr = listener.local_addr()?;
        let inner = Arc::new(Inner {
            routes: Mutex::new(HashMap::new()),
            mcp_routes: Mutex::new(HashMap::new()),
            next_route: AtomicU64::new(1),
            addr,
            endpoint,
            allow_http,
            tls: OnceLock::new(),
            shutdown: CancellationToken::new(),
            limits,
            peers: Mutex::new(HashMap::new()),
        });
        tokio::spawn(accept_loop(listener, inner.clone()));
        Ok(GitProxy { inner })
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.inner.addr
    }

    pub fn endpoint(&self) -> &Endpoint {
        &self.inner.endpoint
    }

    /// Git routes that are registered now.
    pub fn route_count(&self) -> usize {
        lock(&self.inner.routes).len()
    }

    /// Platform MCP endpoints that are registered now.
    pub fn mcp_route_count(&self) -> usize {
        lock(&self.inner.mcp_routes).len()
    }

    /// Stops accepting connections and ends the open ones.
    pub fn shutdown(&self) {
        self.inner.shutdown.cancel();
    }

    /// Registers a run: its sandbox may fetch `repo_url` through the proxy, with
    /// `authorization` added upstream, until the returned guard is dropped.
    pub fn register(
        &self,
        run_id: Uuid,
        repo_url: &str,
        authorization: Option<Secret>,
        branch: &str,
    ) -> Result<RouteGuard> {
        self.register_at(run_id, repo_url, authorization, branch, None)
    }

    /// [`GitProxy::register`], with a URL that names `sandbox_host` in place of the endpoint's
    /// (a local test executor's sandbox over the bridge, which has a loopback address of its
    /// own).
    pub fn register_at(
        &self,
        run_id: Uuid,
        repo_url: &str,
        authorization: Option<Secret>,
        branch: &str,
        sandbox_host: Option<&str>,
    ) -> Result<RouteGuard> {
        let upstream = Upstream::parse(repo_url, self.inner.allow_http)?;
        let secret = Secret::new(new_secret()?);
        let host = sandbox_host.unwrap_or(&self.inner.endpoint.sandbox_host);
        let sandbox_url = format!(
            "http://{}/{}{}",
            host_port(host, self.inner.addr.port()),
            secret.expose(),
            upstream.path
        );
        let id = self.inner.next_route.fetch_add(1, Ordering::Relaxed);
        lock(&self.inner.routes).insert(
            id,
            Arc::new(Route {
                secret: secret.clone(),
                run_id,
                upstream,
                authorization,
                branch: branch.to_string(),
                in_flight: Arc::new(Semaphore::new(self.inner.limits.max_requests_per_route)),
            }),
        );
        tracing::info!(run_id = %run_id, "git proxy route added");
        Ok(RouteGuard {
            inner: self.inner.clone(),
            id,
            secret,
            sandbox_url,
        })
    }

    /// Registers a run's platform MCP endpoint, served until the returned guard is dropped.
    pub fn register_mcp(&self, tools: Arc<RunTools>) -> Result<McpGuard> {
        self.register_mcp_at(tools, None)
    }

    /// [`GitProxy::register_mcp`], with a URL that names `sandbox_host` (as
    /// [`GitProxy::register_at`]).
    pub fn register_mcp_at(
        &self,
        tools: Arc<RunTools>,
        sandbox_host: Option<&str>,
    ) -> Result<McpGuard> {
        let secret = Secret::new(new_secret()?);
        let host = sandbox_host.unwrap_or(&self.inner.endpoint.sandbox_host);
        let url = format!(
            "http://{}/{}/mcp",
            host_port(host, self.inner.addr.port()),
            secret.expose()
        );
        let id = self.inner.next_route.fetch_add(1, Ordering::Relaxed);
        let run_id = tools.run_id();
        lock(&self.inner.mcp_routes).insert(
            id,
            Arc::new(McpRoute {
                secret: secret.clone(),
                run_id,
                tools,
                in_flight: Arc::new(Semaphore::new(self.inner.limits.max_requests_per_route)),
            }),
        );
        tracing::info!(run_id = %run_id, "platform MCP endpoint added");
        Ok(McpGuard {
            inner: self.inner.clone(),
            id,
            secret,
            url,
        })
    }
}

/// 32 bytes from the system's secure random source (through the ring provider that rustls
/// already uses), base64url without padding.
pub(crate) fn new_secret() -> Result<String> {
    let mut bytes = [0u8; SECRET_BYTES];
    rustls::crypto::ring::default_provider()
        .secure_random
        .fill(&mut bytes)
        .map_err(|_| anyhow!("no secure random source"))?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}

/// `host:port`, with brackets around an IPv6 address.
pub(crate) fn host_port(host: &str, port: u16) -> String {
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V6(ip)) => format!("[{ip}]:{port}"),
        _ => format!("{host}:{port}"),
    }
}

impl Inner {
    /// The route of `secret`. Every route is compared, each with [`secret_eq`], so the time
    /// of the answer does not tell how much of a guess was right.
    fn route(&self, secret: &str) -> Option<Arc<Route>> {
        let routes = lock(&self.routes);
        let mut found = None;
        for route in routes.values() {
            if secret_eq(route.secret.expose().as_bytes(), secret.as_bytes()) {
                found = Some(route.clone());
            }
        }
        found
    }

    /// The MCP endpoint of `secret`, compared like [`Inner::route`].
    fn mcp_route(&self, secret: &str) -> Option<Arc<McpRoute>> {
        let routes = lock(&self.mcp_routes);
        let mut found = None;
        for route in routes.values() {
            if secret_eq(route.secret.expose().as_bytes(), secret.as_bytes()) {
                found = Some(route.clone());
            }
        }
        found
    }

    fn tls(&self) -> Arc<rustls::ClientConfig> {
        self.tls.get_or_init(tls_config).clone()
    }
}

/// TLS to the upstream: the system's root certificates, like the server connection. Also used
/// for attachment downloads ([`crate::attachments`]).
pub(crate) fn tls_config() -> Arc<rustls::ClientConfig> {
    let mut roots = rustls::RootCertStore::empty();
    let native = rustls_native_certs::load_native_certs();
    for error in &native.errors {
        tracing::warn!(error = %error, "cannot load a system root certificate");
    }
    let (added, _) = roots.add_parsable_certificates(native.certs);
    if added == 0 {
        tracing::warn!("no system root certificates; HTTPS through the git proxy will fail");
    }
    let mut config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .expect("the ring provider supports the default TLS versions")
    .with_root_certificates(roots)
    .with_no_client_auth();
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Arc::new(config)
}

// ----- requests ------------------------------------------------------------------------

/// The two requests of a fetch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum GitRequest {
    InfoRefs,
    UploadPack,
}

impl GitRequest {
    fn as_str(self) -> &'static str {
        match self {
            GitRequest::InfoRefs => "info/refs",
            GitRequest::UploadPack => "git-upload-pack",
        }
    }
}

enum Decision {
    Forward(Arc<Route>, GitRequest),
    Refuse(StatusCode, String),
}

fn not_found() -> Decision {
    Decision::Refuse(StatusCode::NOT_FOUND, "not found".into())
}

/// Decides a request from its method and URI alone.
fn decide(inner: &Inner, method: &Method, uri: &Uri) -> Decision {
    let path = uri.path();
    let Some((secret, _)) = path.strip_prefix('/').and_then(|p| p.split_once('/')) else {
        return not_found();
    };
    let Some(route) = inner.route(secret) else {
        tracing::debug!("git proxy request without a valid route");
        return not_found();
    };
    // The path after the secret, with its leading slash.
    let tail = &path[1 + secret.len()..];
    let service = |name: &str| {
        uri.query().is_some_and(|q| {
            url::form_urlencoded::parse(q.as_bytes()).any(|(k, v)| k == "service" && v == name)
        })
    };
    if tail.ends_with("/git-receive-pack") || service("git-receive-pack") {
        tracing::info!(run_id = %route.run_id, "git proxy refused a push from the sandbox");
        return Decision::Refuse(
            StatusCode::FORBIDDEN,
            format!(
                "pushing from the sandbox is not allowed; the runner host pushes {} when the run ends",
                route.branch
            ),
        );
    }
    let only_upload_pack_service = uri.query().is_some_and(|q| {
        let mut pairs = url::form_urlencoded::parse(q.as_bytes());
        pairs
            .next()
            .is_some_and(|(k, v)| k == "service" && v == "git-upload-pack")
            && pairs.next().is_none()
    });
    let request = match tail.strip_prefix(route.upstream.path.as_str()) {
        Some("/info/refs") if method == Method::GET && only_upload_pack_service => {
            GitRequest::InfoRefs
        }
        Some("/git-upload-pack") if method == Method::POST && uri.query().is_none() => {
            GitRequest::UploadPack
        }
        _ => return not_found(),
    };
    Decision::Forward(route, request)
}

/// The MCP endpoint that `path` (`/<secret>/mcp`) names, if any.
fn mcp_target(inner: &Inner, path: &str) -> Option<Arc<McpRoute>> {
    let (secret, rest) = path.strip_prefix('/')?.split_once('/')?;
    if rest != "mcp" {
        return None;
    }
    inner.mcp_route(secret)
}

async fn handle(inner: Arc<Inner>, req: Request<Incoming>) -> Response<ProxyBody> {
    if let Some(route) = mcp_target(&inner, req.uri().path()) {
        let Ok(_permit) = route.in_flight.clone().try_acquire_owned() else {
            tracing::warn!(run_id = %route.run_id, "platform MCP endpoint: too many requests at once");
            return too_many_requests();
        };
        return mcp::handle_http(route.tools.clone(), req, inner.limits.body_read_timeout)
            .await
            .map(|body| body.map_err(|never| match never {}).boxed_unsync());
    }
    match decide(&inner, req.method(), req.uri()) {
        Decision::Refuse(status, reason) => text(status, &reason),
        Decision::Forward(route, request) => {
            let Ok(permit) = route.in_flight.clone().try_acquire_owned() else {
                tracing::warn!(run_id = %route.run_id, "git proxy: too many requests at once");
                return too_many_requests();
            };
            // The permit is held until the response's body ends.
            forward(&inner, &route, request, req)
                .await
                .map(|body| Held::new(body, permit).boxed_unsync())
        }
    }
}

fn too_many_requests() -> Response<ProxyBody> {
    text(
        StatusCode::TOO_MANY_REQUESTS,
        "too many requests at once on this route; retry when one has finished",
    )
}

/// A body that holds a permit of its route until it ends or is dropped.
struct Held<B> {
    inner: B,
    _permit: OwnedSemaphorePermit,
}

impl<B> Held<B> {
    fn new(inner: B, permit: OwnedSemaphorePermit) -> Held<B> {
        Held {
            inner,
            _permit: permit,
        }
    }
}

impl<B: Body + Unpin> Body for Held<B> {
    type Data = B::Data;
    type Error = B::Error;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
    ) -> Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        Pin::new(&mut self.inner).poll_frame(cx)
    }

    fn is_end_stream(&self) -> bool {
        self.inner.is_end_stream()
    }

    fn size_hint(&self) -> SizeHint {
        self.inner.size_hint()
    }
}

/// The request body did not arrive within [`ListenerLimits::body_read_timeout`].
#[derive(Debug)]
pub(crate) struct BodyTimeout;

impl std::fmt::Display for BodyTimeout {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("the request body was not sent in time")
    }
}

impl std::error::Error for BodyTimeout {}

/// A request body that fails with [`BodyTimeout`] when it has not ended by its deadline.
struct Deadline<B> {
    inner: B,
    sleep: Pin<Box<tokio::time::Sleep>>,
}

impl<B> Deadline<B> {
    fn new(inner: B, timeout: Duration) -> Deadline<B> {
        Deadline {
            inner,
            sleep: Box::pin(tokio::time::sleep(timeout)),
        }
    }
}

impl<B> Body for Deadline<B>
where
    B: Body + Unpin,
    B::Error: Into<BoxError>,
{
    type Data = B::Data;
    type Error = BoxError;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
    ) -> Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        let this = &mut *self;
        if let Poll::Ready(frame) = Pin::new(&mut this.inner).poll_frame(cx) {
            return Poll::Ready(frame.map(|f| f.map_err(Into::into)));
        }
        if this.sleep.as_mut().poll(cx).is_ready() {
            return Poll::Ready(Some(Err(Box::new(BodyTimeout))));
        }
        Poll::Pending
    }

    fn is_end_stream(&self) -> bool {
        self.inner.is_end_stream()
    }

    fn size_hint(&self) -> SizeHint {
        self.inner.size_hint()
    }
}

fn text(status: StatusCode, message: &str) -> Response<ProxyBody> {
    let body = Full::new(Bytes::from(format!("{message}\n")))
        .map_err(|never| match never {})
        .boxed_unsync();
    let mut response = Response::new(body);
    *response.status_mut() = status;
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; charset=utf-8"),
    );
    response
}

async fn forward(
    inner: &Inner,
    route: &Route,
    request: GitRequest,
    req: Request<Incoming>,
) -> Response<ProxyBody> {
    let (parts, body) = req.into_parts();
    let declared_length = parts
        .headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok());
    if declared_length.is_some_and(|len| len > MAX_REQUEST_BODY) {
        return text(
            StatusCode::PAYLOAD_TOO_LARGE,
            "the request body is too large",
        );
    }
    let upstream = &route.upstream;
    let (method, target, body) = match request {
        GitRequest::InfoRefs => (
            Method::GET,
            format!("{}/info/refs?service=git-upload-pack", upstream.path),
            Empty::new().map_err(|never| match never {}).boxed_unsync(),
        ),
        GitRequest::UploadPack => (
            Method::POST,
            format!("{}/git-upload-pack", upstream.path),
            Deadline::new(
                Limited::new(body, MAX_REQUEST_BODY as usize),
                inner.limits.body_read_timeout,
            )
            .boxed_unsync(),
        ),
    };
    let mut outgoing = Request::new(body);
    *outgoing.method_mut() = method;
    match target.parse::<Uri>() {
        Ok(uri) => *outgoing.uri_mut() = uri,
        Err(_) => return text(StatusCode::BAD_GATEWAY, "invalid repository path"),
    }
    let headers = outgoing.headers_mut();
    match HeaderValue::from_str(&upstream.authority) {
        Ok(host) => {
            headers.insert(header::HOST, host);
        }
        Err(_) => return text(StatusCode::BAD_GATEWAY, "invalid repository host"),
    }
    for name in REQUEST_HEADERS {
        for value in parts.headers.get_all(&name) {
            headers.append(name.clone(), value.clone());
        }
    }
    if let Some(authorization) = &route.authorization {
        match HeaderValue::from_str(authorization.expose()) {
            Ok(mut value) => {
                value.set_sensitive(true);
                headers.insert(header::AUTHORIZATION, value);
            }
            Err(_) => return text(StatusCode::BAD_GATEWAY, "invalid git credentials"),
        }
    }

    let response = match send(inner, upstream, outgoing).await {
        Ok(response) => response,
        Err(e) if body_too_large(e.as_ref()) => {
            return text(
                StatusCode::PAYLOAD_TOO_LARGE,
                "the request body is too large",
            );
        }
        Err(e) if body_timed_out(e.as_ref()) => {
            tracing::warn!(run_id = %route.run_id, "git proxy: the request body was not sent in time");
            return text(
                StatusCode::REQUEST_TIMEOUT,
                "the request body was not sent in time",
            );
        }
        Err(e) => {
            tracing::warn!(run_id = %route.run_id, request = request.as_str(), error = %e, "git proxy: the upstream request failed");
            return text(StatusCode::BAD_GATEWAY, "cannot reach the git server");
        }
    };
    let status = response.status();
    tracing::debug!(run_id = %route.run_id, request = request.as_str(), status = status.as_u16(), "git proxy request");
    if status.is_redirection() {
        tracing::warn!(run_id = %route.run_id, status = status.as_u16(), "git proxy: the git server answered with a redirect; not following it");
        return text(
            StatusCode::BAD_GATEWAY,
            "the git server answered with a redirect; the git proxy does not follow redirects",
        );
    }
    if status == StatusCode::UNAUTHORIZED || status == StatusCode::PROXY_AUTHENTICATION_REQUIRED {
        tracing::warn!(run_id = %route.run_id, status = status.as_u16(), "git proxy: the git server rejected the credentials");
        return text(
            StatusCode::BAD_GATEWAY,
            "the git server rejected the runner host's git credentials",
        );
    }
    let (upstream_parts, upstream_body) = response.into_parts();
    let mut out = Response::new(
        upstream_body
            .map_err(|e| Box::new(e) as BoxError)
            .boxed_unsync(),
    );
    *out.status_mut() = status;
    for name in RESPONSE_HEADERS {
        for value in upstream_parts.headers.get_all(&name) {
            out.headers_mut().append(name.clone(), value.clone());
        }
    }
    out
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

/// `true` when the request body missed its deadline ([`Deadline`]).
fn body_timed_out(error: &(dyn std::error::Error + 'static)) -> bool {
    let mut current = Some(error);
    while let Some(e) = current {
        if e.is::<BodyTimeout>() {
            return true;
        }
        current = e.source();
    }
    false
}

/// One request on a new connection to the upstream (git sends only a few per fetch).
async fn send(
    inner: &Inner,
    upstream: &Upstream,
    request: Request<ProxyBody>,
) -> Result<Response<Incoming>, BoxError> {
    let tcp = tokio::time::timeout(
        CONNECT_TIMEOUT,
        TcpStream::connect((upstream.host.as_str(), upstream.port)),
    )
    .await
    .map_err(|_| "connection timed out")??;
    let _ = tcp.set_nodelay(true);
    if upstream.https {
        let name = rustls::pki_types::ServerName::try_from(upstream.host.clone())?;
        let tls = tokio_rustls::TlsConnector::from(inner.tls())
            .connect(name, tcp)
            .await?;
        exchange(TokioIo::new(tls), request).await
    } else {
        exchange(TokioIo::new(tcp), request).await
    }
}

async fn exchange<T>(io: T, request: Request<ProxyBody>) -> Result<Response<Incoming>, BoxError>
where
    T: hyper::rt::Read + hyper::rt::Write + Unpin + Send + 'static,
{
    let (mut sender, connection) = hyper::client::conn::http1::handshake(io).await?;
    tokio::spawn(async move {
        if let Err(e) = connection.await {
            tracing::debug!(error = %e, "git proxy: upstream connection ended with an error");
        }
    });
    let response = tokio::time::timeout(RESPONSE_TIMEOUT, sender.send_request(request))
        .await
        .map_err(|_| "the git server did not answer in time")??;
    Ok(response)
}

async fn accept_loop(listener: TcpListener, inner: Arc<Inner>) {
    let limit = Arc::new(Semaphore::new(inner.limits.max_connections));
    loop {
        let permit = tokio::select! {
            _ = inner.shutdown.cancelled() => break,
            permit = limit.clone().acquire_owned() => match permit {
                Ok(permit) => permit,
                Err(_) => break,
            },
        };
        let (stream, peer) = tokio::select! {
            _ = inner.shutdown.cancelled() => break,
            accepted = listener.accept() => match accepted {
                Ok(accepted) => accepted,
                Err(e) => {
                    tracing::warn!(error = %e, "git proxy: accept failed");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    continue;
                }
            },
        };
        let Some(peer_slot) = PeerSlot::take(&inner, peer.ip()) else {
            tracing::warn!(peer = %peer.ip(), "git proxy: too many connections from one peer; closing a new one");
            drop(stream);
            continue;
        };
        let inner = inner.clone();
        tokio::spawn(async move {
            let _permit = permit;
            let _peer_slot = peer_slot;
            serve_connection(inner, stream).await;
        });
    }
    tracing::debug!("git proxy stopped");
}

/// One connection, until it ends, the listener shuts down, or it is idle for too long.
async fn serve_connection(inner: Arc<Inner>, stream: TcpStream) {
    let shutdown = inner.shutdown.clone();
    let limits = inner.limits;
    let activity = Arc::new(Activity::new());
    let service = {
        let activity = activity.clone();
        hyper::service::service_fn(move |req| {
            let inner = inner.clone();
            let busy = Busy::new(activity.clone());
            async move {
                let response = handle(inner, req).await;
                drop(busy);
                Ok::<_, Infallible>(response)
            }
        })
    };
    let mut builder = hyper::server::conn::http1::Builder::new();
    builder
        .timer(TokioTimer::new())
        .header_read_timeout(limits.header_read_timeout)
        .max_buf_size(MAX_BUF_SIZE);
    let io = Tracked {
        inner: stream,
        activity: activity.clone(),
    };
    let connection = builder.serve_connection(TokioIo::new(io), service);
    let idle = async {
        let check = (limits.idle_timeout / 4).max(Duration::from_millis(10));
        loop {
            tokio::time::sleep(check).await;
            if activity.idle_for() >= limits.idle_timeout {
                break;
            }
        }
    };
    tokio::select! {
        result = connection => {
            if let Err(e) = result {
                tracing::debug!(error = %e, "git proxy: connection ended with an error");
            }
        }
        _ = idle => {
            tracing::debug!("git proxy: closed an idle connection");
        }
        _ = shutdown.cancelled() => {}
    }
}

/// A connection's count against [`ListenerLimits::max_connections_per_peer`].
struct PeerSlot {
    inner: Arc<Inner>,
    ip: Option<IpAddr>,
}

impl PeerSlot {
    /// `None` when `ip` has its maximum of connections open.
    fn take(inner: &Arc<Inner>, ip: IpAddr) -> Option<PeerSlot> {
        let limits = &inner.limits;
        let ip = canonical_ip(ip);
        if ip.is_loopback() && !limits.limit_loopback_peers {
            return Some(PeerSlot {
                inner: inner.clone(),
                ip: None,
            });
        }
        let mut peers = lock(&inner.peers);
        let open = peers.entry(ip).or_insert(0);
        if *open >= limits.max_connections_per_peer {
            return None;
        }
        *open += 1;
        Some(PeerSlot {
            inner: inner.clone(),
            ip: Some(ip),
        })
    }
}

impl Drop for PeerSlot {
    fn drop(&mut self) {
        if let Some(ip) = self.ip {
            let mut peers = lock(&self.inner.peers);
            if let Some(open) = peers.get_mut(&ip) {
                *open -= 1;
                if *open == 0 {
                    peers.remove(&ip);
                }
            }
        }
    }
}

/// An IPv4 address mapped into IPv6 counts as the IPv4 address.
fn canonical_ip(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V6(v6) => v6.to_ipv4_mapped().map_or(ip, IpAddr::V4),
        v4 => v4,
    }
}

/// When a connection last moved a byte, and how many of its requests are being handled.
struct Activity {
    start: tokio::time::Instant,
    /// Milliseconds from `start` to the last byte read or written.
    last: AtomicU64,
    busy: AtomicUsize,
}

impl Activity {
    fn new() -> Activity {
        Activity {
            start: tokio::time::Instant::now(),
            last: AtomicU64::new(0),
            busy: AtomicUsize::new(0),
        }
    }

    fn touch(&self) {
        let now = u64::try_from(self.start.elapsed().as_millis()).unwrap_or(u64::MAX);
        self.last.store(now, Ordering::Relaxed);
    }

    /// How long nothing moved; zero while a request is handled.
    fn idle_for(&self) -> Duration {
        if self.busy.load(Ordering::SeqCst) > 0 {
            return Duration::ZERO;
        }
        let last = Duration::from_millis(self.last.load(Ordering::Relaxed));
        self.start.elapsed().saturating_sub(last)
    }
}

/// Counts a request as handled while it exists; its end counts as activity.
struct Busy(Arc<Activity>);

impl Busy {
    fn new(activity: Arc<Activity>) -> Busy {
        activity.busy.fetch_add(1, Ordering::SeqCst);
        Busy(activity)
    }
}

impl Drop for Busy {
    fn drop(&mut self) {
        self.0.touch();
        self.0.busy.fetch_sub(1, Ordering::SeqCst);
    }
}

/// A connection's stream that records every byte read or written in its [`Activity`].
struct Tracked {
    inner: TcpStream,
    activity: Arc<Activity>,
}

impl AsyncRead for Tracked {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        let before = buf.filled().len();
        let polled = Pin::new(&mut self.inner).poll_read(cx, buf);
        if matches!(polled, Poll::Ready(Ok(()))) && buf.filled().len() > before {
            self.activity.touch();
        }
        polled
    }
}

impl AsyncWrite for Tracked {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        let polled = Pin::new(&mut self.inner).poll_write(cx, buf);
        if matches!(polled, Poll::Ready(Ok(n)) if n > 0) {
            self.activity.touch();
        }
        polled
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(
        mut self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
    ) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    const HOST_AUTHORIZATION: &str = "Basic eC1hY2Nlc3MtdG9rZW46VEVTVC1PTkxZ";

    /// What the fake upstream received.
    #[derive(Debug, Clone)]
    struct Seen {
        method: Method,
        uri: String,
        headers: hyper::HeaderMap,
        body: Bytes,
    }

    type Reply = Arc<dyn Fn(&Seen) -> Response<Full<Bytes>> + Send + Sync>;

    struct FakeUpstream {
        url: String,
        seen: Arc<Mutex<Vec<Seen>>>,
    }

    impl FakeUpstream {
        async fn start(reply: Reply) -> FakeUpstream {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let url = format!("http://{}", listener.local_addr().unwrap());
            let seen = Arc::new(Mutex::new(Vec::new()));
            let seen_task = seen.clone();
            tokio::spawn(async move {
                while let Ok((stream, _)) = listener.accept().await {
                    let seen = seen_task.clone();
                    let reply = reply.clone();
                    tokio::spawn(async move {
                        let service = hyper::service::service_fn(move |req: Request<Incoming>| {
                            let seen = seen.clone();
                            let reply = reply.clone();
                            async move {
                                let (parts, body) = req.into_parts();
                                let body = body.collect().await.unwrap().to_bytes();
                                let request = Seen {
                                    method: parts.method,
                                    uri: parts.uri.to_string(),
                                    headers: parts.headers,
                                    body,
                                };
                                let response = reply(&request);
                                lock(&seen).push(request);
                                Ok::<_, Infallible>(response)
                            }
                        });
                        let _ = hyper::server::conn::http1::Builder::new()
                            .serve_connection(TokioIo::new(stream), service)
                            .await;
                    });
                }
            });
            FakeUpstream { url, seen }
        }

        fn seen(&self) -> Vec<Seen> {
            lock(&self.seen).clone()
        }
    }

    fn reply_ok() -> Reply {
        Arc::new(|seen: &Seen| {
            let content_type = if seen.method == Method::GET {
                "application/x-git-upload-pack-advertisement"
            } else {
                "application/x-git-upload-pack-result"
            };
            let mut response = Response::new(Full::new(Bytes::from_static(b"0000")));
            let headers = response.headers_mut();
            headers.insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
            headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-cache"));
            headers.insert(header::SET_COOKIE, HeaderValue::from_static("tracking=1"));
            response
        })
    }

    fn reply_status(status: StatusCode, extra: (HeaderName, &'static str)) -> Reply {
        Arc::new(move |_: &Seen| {
            let mut response = Response::new(Full::new(Bytes::from_static(b"upstream body")));
            *response.status_mut() = status;
            response
                .headers_mut()
                .insert(extra.0.clone(), HeaderValue::from_static(extra.1));
            response
        })
    }

    async fn proxy() -> GitProxy {
        GitProxy::start(
            Endpoint {
                bind: "127.0.0.1:0".parse().unwrap(),
                sandbox_host: "127.0.0.1".into(),
                extra_hosts: Vec::new(),
            },
            true,
        )
        .await
        .unwrap()
    }

    fn register(proxy: &GitProxy, upstream: &FakeUpstream, repo: &str) -> RouteGuard {
        proxy
            .register(
                Uuid::new_v4(),
                &format!("{}{repo}", upstream.url),
                Some(Secret::new(HOST_AUTHORIZATION)),
                "agent/TEST-1",
            )
            .unwrap()
    }

    /// The path part of the sandbox URL: `/<secret>/<repository path>`.
    fn prefix(route: &RouteGuard) -> String {
        let url = url::Url::parse(route.sandbox_url()).unwrap();
        url.path().to_string()
    }

    async fn request(
        proxy: &GitProxy,
        method: Method,
        path: &str,
        headers: &[(&str, &str)],
        body: &'static [u8],
    ) -> (StatusCode, hyper::HeaderMap, String) {
        request_bytes(proxy, method, path, headers, Bytes::from_static(body)).await
    }

    async fn request_bytes(
        proxy: &GitProxy,
        method: Method,
        path: &str,
        headers: &[(&str, &str)],
        body: Bytes,
    ) -> (StatusCode, hyper::HeaderMap, String) {
        let stream = TcpStream::connect(proxy.local_addr()).await.unwrap();
        let (mut sender, connection) =
            hyper::client::conn::http1::handshake::<_, Full<Bytes>>(TokioIo::new(stream))
                .await
                .unwrap();
        tokio::spawn(connection);
        let mut req = Request::new(Full::new(body));
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
        let body = response.into_body().collect().await.unwrap().to_bytes();
        (status, headers, String::from_utf8_lossy(&body).into_owned())
    }

    #[tokio::test]
    async fn forwards_info_refs_with_the_host_authorization_only() {
        let upstream = FakeUpstream::start(reply_ok()).await;
        let proxy = proxy().await;
        let route = register(&proxy, &upstream, "/acme/widgets.git");
        let path = format!("{}/info/refs?service=git-upload-pack", prefix(&route));
        let (status, headers, body) = request(
            &proxy,
            Method::GET,
            &path,
            &[
                ("authorization", "Basic c2FuZGJveDpndWVzcw=="),
                ("git-protocol", "version=2"),
                ("user-agent", "git/2.54.0"),
                ("accept", "*/*"),
                ("cookie", "session=1"),
                ("x-forwarded-for", "10.0.0.1"),
            ],
            b"",
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, "0000");
        assert_eq!(
            headers[header::CONTENT_TYPE],
            "application/x-git-upload-pack-advertisement"
        );
        assert_eq!(headers[header::CACHE_CONTROL], "no-cache");
        assert!(headers.get(header::SET_COOKIE).is_none());

        let seen = upstream.seen();
        assert_eq!(seen.len(), 1);
        let got = &seen[0];
        assert_eq!(got.method, Method::GET);
        assert_eq!(
            got.uri,
            "/acme/widgets.git/info/refs?service=git-upload-pack"
        );
        // The client's Authorization is dropped and the host's is sent instead.
        let auth: Vec<_> = got.headers.get_all(header::AUTHORIZATION).iter().collect();
        assert_eq!(auth, vec![HOST_AUTHORIZATION]);
        assert_eq!(got.headers["git-protocol"], "version=2");
        assert_eq!(got.headers[header::USER_AGENT], "git/2.54.0");
        assert_eq!(got.headers[header::ACCEPT], "*/*");
        assert_eq!(
            got.headers[header::HOST],
            upstream.url.trim_start_matches("http://")
        );
        assert!(got.headers.get(header::COOKIE).is_none());
        assert!(got.headers.get("x-forwarded-for").is_none());
    }

    #[tokio::test]
    async fn forwards_upload_pack_posts_with_their_body() {
        let upstream = FakeUpstream::start(reply_ok()).await;
        let proxy = proxy().await;
        let route = register(&proxy, &upstream, "/acme/widgets.git/");
        let path = format!("{}/git-upload-pack", prefix(&route));
        let (status, headers, _) = request(
            &proxy,
            Method::POST,
            &path,
            &[
                ("content-type", "application/x-git-upload-pack-request"),
                ("accept", "application/x-git-upload-pack-result"),
                ("content-encoding", "gzip"),
                ("git-protocol", "version=2"),
            ],
            b"\x1f\x8bnot-really-gzip",
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            headers[header::CONTENT_TYPE],
            "application/x-git-upload-pack-result"
        );
        let seen = upstream.seen();
        assert_eq!(seen.len(), 1);
        let got = &seen[0];
        assert_eq!(got.method, Method::POST);
        assert_eq!(got.uri, "/acme/widgets.git/git-upload-pack");
        assert_eq!(&got.body[..], b"\x1f\x8bnot-really-gzip");
        assert_eq!(got.headers[header::CONTENT_ENCODING], "gzip");
        assert_eq!(
            got.headers[header::CONTENT_TYPE],
            "application/x-git-upload-pack-request"
        );
        assert_eq!(got.headers[header::AUTHORIZATION], HOST_AUTHORIZATION);
        assert_eq!(got.headers["git-protocol"], "version=2");
    }

    #[tokio::test]
    async fn refuses_pushes_with_a_reason() {
        let upstream = FakeUpstream::start(reply_ok()).await;
        let proxy = proxy().await;
        let route = register(&proxy, &upstream, "/acme/widgets.git");
        let base = prefix(&route);
        for (method, path) in [
            (
                Method::GET,
                format!("{base}/info/refs?service=git-receive-pack"),
            ),
            (Method::POST, format!("{base}/git-receive-pack")),
            (
                Method::POST,
                format!("{base}/../other.git/git-receive-pack"),
            ),
        ] {
            let (status, headers, body) = request(&proxy, method, &path, &[], b"0000").await;
            assert_eq!(status, StatusCode::FORBIDDEN, "{path}");
            assert_eq!(headers[header::CONTENT_TYPE], "text/plain; charset=utf-8");
            assert_eq!(
                body,
                "pushing from the sandbox is not allowed; the runner host pushes agent/TEST-1 when the run ends\n"
            );
        }
        assert!(upstream.seen().is_empty());
    }

    #[tokio::test]
    async fn anything_else_is_not_found() {
        let upstream = FakeUpstream::start(reply_ok()).await;
        let proxy = proxy().await;
        let route = register(&proxy, &upstream, "/acme/widgets.git");
        let base = prefix(&route);
        let secret = route.secret().expose().to_string();
        for (method, path) in [
            // Dumb HTTP and other files of the repository.
            (Method::GET, format!("{base}/info/refs")),
            (Method::GET, format!("{base}/objects/info/packs")),
            (Method::GET, format!("{base}/HEAD")),
            // Wrong method or extra parameters.
            (
                Method::POST,
                format!("{base}/info/refs?service=git-upload-pack"),
            ),
            (
                Method::HEAD,
                format!("{base}/info/refs?service=git-upload-pack"),
            ),
            (Method::GET, format!("{base}/git-upload-pack")),
            (Method::POST, format!("{base}/git-upload-pack?x=1")),
            (
                Method::GET,
                format!("{base}/info/refs?service=git-upload-pack&x=1"),
            ),
            // Another repository with a valid secret.
            (
                Method::GET,
                format!("/{secret}/acme/other.git/info/refs?service=git-upload-pack"),
            ),
            (
                Method::GET,
                format!("/{secret}/info/refs?service=git-upload-pack"),
            ),
            (Method::GET, format!("/{secret}")),
            (Method::GET, "/".to_string()),
        ] {
            let head = method == Method::HEAD;
            let (status, _, body) = request(&proxy, method, &path, &[], b"").await;
            assert_eq!(status, StatusCode::NOT_FOUND, "{path}");
            assert_eq!(body, if head { "" } else { "not found\n" });
        }
        assert!(upstream.seen().is_empty());
    }

    #[tokio::test]
    async fn wrong_or_missing_secrets_are_not_found() {
        let upstream = FakeUpstream::start(reply_ok()).await;
        let proxy = proxy().await;
        let route = register(&proxy, &upstream, "/acme/widgets.git");
        let secret = route.secret().expose().to_string();
        assert_eq!(secret.len(), 43);
        // The same length, one character off (at the end, where an early exit would show).
        let mut last_off = secret.clone();
        let last = last_off.pop().unwrap();
        last_off.push(if last == 'A' { 'B' } else { 'A' });
        let tail = "/acme/widgets.git/info/refs?service=git-upload-pack";
        for candidate in [
            last_off.as_str(),
            &secret[..42],
            &format!("{secret}x"),
            "",
            "acme",
        ] {
            let (status, _, _) = request(
                &proxy,
                Method::GET,
                &format!("/{candidate}{tail}"),
                &[],
                b"",
            )
            .await;
            assert_eq!(status, StatusCode::NOT_FOUND, "{candidate}");
        }
        // A push with a wrong secret is not found either: no hint that the path is right.
        let (status, _, _) = request(
            &proxy,
            Method::POST,
            &format!("/{last_off}/acme/widgets.git/git-receive-pack"),
            &[],
            b"",
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert!(upstream.seen().is_empty());
    }

    #[tokio::test]
    async fn each_secret_reaches_only_its_own_repository_until_the_run_ends() {
        let first_upstream = FakeUpstream::start(reply_ok()).await;
        let second_upstream = FakeUpstream::start(reply_ok()).await;
        let proxy = proxy().await;
        let first = register(&proxy, &first_upstream, "/acme/one.git");
        let second = register(&proxy, &second_upstream, "/acme/two.git");
        assert_ne!(first.secret().expose(), second.secret().expose());
        assert_eq!(proxy.route_count(), 2);
        let refs = "/info/refs?service=git-upload-pack";
        // The first secret with the second repository's path.
        let crossed = format!("/{}/acme/two.git{refs}", first.secret().expose());
        let (status, _, _) = request(&proxy, Method::GET, &crossed, &[], b"").await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        let (status, _, _) = request(
            &proxy,
            Method::GET,
            &format!("{}{refs}", prefix(&second)),
            &[],
            b"",
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(first_upstream.seen().is_empty());
        assert_eq!(second_upstream.seen().len(), 1);

        // The run of the second route ends: its route is gone, the first one stays.
        let second_path = format!("{}{refs}", prefix(&second));
        drop(second);
        assert_eq!(proxy.route_count(), 1);
        let (status, _, _) = request(&proxy, Method::GET, &second_path, &[], b"").await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert_eq!(second_upstream.seen().len(), 1);
        let (status, _, _) = request(
            &proxy,
            Method::GET,
            &format!("{}{refs}", prefix(&first)),
            &[],
            b"",
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        drop(first);
        assert_eq!(proxy.route_count(), 0);
        proxy.shutdown();
    }

    #[tokio::test]
    async fn oversized_request_bodies_are_refused() {
        let upstream = FakeUpstream::start(reply_ok()).await;
        let proxy = proxy().await;
        let route = register(&proxy, &upstream, "/acme/widgets.git");
        let mut stream = TcpStream::connect(proxy.local_addr()).await.unwrap();
        let head = format!(
            "POST {}/git-upload-pack HTTP/1.1\r\nHost: proxy\r\nContent-Length: {}\r\n\r\n",
            prefix(&route),
            MAX_REQUEST_BODY + 1
        );
        stream.write_all(head.as_bytes()).await.unwrap();
        let mut answer = vec![0u8; 1024];
        let n = stream.read(&mut answer).await.unwrap();
        let answer = String::from_utf8_lossy(&answer[..n]);
        assert!(answer.starts_with("HTTP/1.1 413"), "{answer}");
        assert!(upstream.seen().is_empty());
    }

    #[tokio::test]
    async fn redirects_and_credential_challenges_are_not_passed_on() {
        let redirect = FakeUpstream::start(reply_status(
            StatusCode::MOVED_PERMANENTLY,
            (
                header::LOCATION,
                "https://elsewhere.example/acme/widgets.git/info/refs",
            ),
        ))
        .await;
        let challenge = FakeUpstream::start(reply_status(
            StatusCode::UNAUTHORIZED,
            (header::WWW_AUTHENTICATE, "Basic realm=\"GitHub\""),
        ))
        .await;
        let proxy = proxy().await;
        let refs = "/info/refs?service=git-upload-pack";
        for upstream in [&redirect, &challenge] {
            let route = register(&proxy, upstream, "/acme/widgets.git");
            let (status, headers, _) = request(
                &proxy,
                Method::GET,
                &format!("{}{refs}", prefix(&route)),
                &[],
                b"",
            )
            .await;
            assert_eq!(status, StatusCode::BAD_GATEWAY);
            assert!(headers.get(header::LOCATION).is_none());
            assert!(headers.get(header::WWW_AUTHENTICATE).is_none());
            assert_eq!(upstream.seen().len(), 1, "no second request");
        }
        // Other statuses pass through (GitHub answers 404 for an unknown repository).
        let missing = FakeUpstream::start(reply_status(
            StatusCode::NOT_FOUND,
            (header::CONTENT_TYPE, "text/plain"),
        ))
        .await;
        let route = register(&proxy, &missing, "/acme/widgets.git");
        let (status, _, body) = request(
            &proxy,
            Method::GET,
            &format!("{}{refs}", prefix(&route)),
            &[],
            b"",
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert_eq!(body, "upstream body");
    }

    #[tokio::test]
    async fn an_unreachable_upstream_is_a_bad_gateway() {
        let closed = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/acme/widgets.git", closed.local_addr().unwrap());
        drop(closed);
        let proxy = proxy().await;
        let route = proxy
            .register(Uuid::new_v4(), &url, None, "agent/TEST-1")
            .unwrap();
        let (status, _, body) = request(
            &proxy,
            Method::GET,
            &format!("{}/info/refs?service=git-upload-pack", prefix(&route)),
            &[],
            b"",
        )
        .await;
        assert_eq!(status, StatusCode::BAD_GATEWAY);
        assert_eq!(body, "cannot reach the git server\n");
    }

    #[tokio::test]
    async fn registration_checks_the_repository_url() {
        let proxy = proxy().await;
        let https = GitProxy::start(
            Endpoint {
                bind: "127.0.0.1:0".parse().unwrap(),
                sandbox_host: "::1".into(),
                extra_hosts: Vec::new(),
            },
            false,
        )
        .await
        .unwrap();
        let id = Uuid::new_v4();
        assert!(
            https
                .register(id, "http://example.com/a.git", None, "agent/X-1")
                .is_err()
        );
        assert!(
            proxy
                .register(id, "file:///tmp/a.git", None, "agent/X-1")
                .is_err()
        );
        assert!(
            proxy
                .register(id, "https://u:p@example.com/a.git", None, "agent/X-1")
                .is_err()
        );
        assert!(
            proxy
                .register(id, "https://example.com/a.git?x=1", None, "agent/X-1")
                .is_err()
        );
        let route = https
            .register(id, "https://github.com/acme/widgets.git", None, "agent/X-1")
            .unwrap();
        let expected = format!(
            "http://[::1]:{}/{}/acme/widgets.git",
            https.local_addr().port(),
            route.secret().expose()
        );
        assert_eq!(route.sandbox_url(), expected);
        // A repository at the root of its host.
        let root = proxy
            .register(id, "https://git.example.com/", None, "agent/X-1")
            .unwrap();
        assert!(root.sandbox_url().ends_with(root.secret().expose()));
        assert_eq!(
            format!("{root:?}"),
            format!("RouteGuard {{ id: {} }}", root.id)
        );
    }

    #[test]
    fn secrets_are_random_base64url() {
        let a = new_secret().unwrap();
        let b = new_secret().unwrap();
        assert_ne!(a, b);
        assert_eq!(a.len(), 43);
        assert!(
            a.bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
        );
        assert_eq!(URL_SAFE_NO_PAD.decode(&a).unwrap().len(), SECRET_BYTES);
    }

    #[test]
    fn parses_the_overrides() {
        let parsed = Overrides::parse(Some("172.17.0.1"), Some("host.docker.internal"));
        assert_eq!(parsed.bind, Some("172.17.0.1:0".parse().unwrap()));
        assert_eq!(parsed.host.as_deref(), Some("host.docker.internal"));
        let parsed = Overrides::parse(Some(" 0.0.0.0:9419 "), Some("10.0.0.5"));
        assert_eq!(parsed.bind, Some("0.0.0.0:9419".parse().unwrap()));
        assert_eq!(parsed.host.as_deref(), Some("10.0.0.5"));
        assert_eq!(
            Overrides::parse(Some("[::1]:1"), Some("::1")).bind,
            Some("[::1]:1".parse().unwrap())
        );
        assert_eq!(
            Overrides::parse(Some("localhost"), Some("evil/host@x")),
            Overrides::default()
        );
        assert_eq!(Overrides::parse(Some(""), None), Overrides::default());
    }

    #[test]
    fn finds_the_gateway_of_a_network() {
        let network: NetworkInspect = serde_json::from_value(serde_json::json!({
            "Name": "bridge",
            "IPAM": {"Config": [
                {"Subnet": "fd00::/64", "Gateway": "fd00::1"},
                {"Subnet": "172.17.0.0/16", "Gateway": "172.17.0.1"}
            ]}
        }))
        .unwrap();
        assert_eq!(gateway_of(&network), Some("172.17.0.1".parse().unwrap()));
        let v6: NetworkInspect = serde_json::from_value(serde_json::json!({
            "IPAM": {"Config": [{"Subnet": "fd00::/64", "Gateway": "fd00::1"}]}
        }))
        .unwrap();
        assert_eq!(gateway_of(&v6), Some("fd00::1".parse().unwrap()));
        let none: NetworkInspect =
            serde_json::from_value(serde_json::json!({"IPAM": {"Config": [{}]}})).unwrap();
        assert_eq!(gateway_of(&none), None);
    }

    #[tokio::test]
    async fn the_local_executor_uses_the_loopback() {
        let local = Executor::Local {
            program: "node".into(),
            args: Vec::new(),
        };
        let endpoint = endpoint(&local, None, &Overrides::default())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(endpoint.bind, "127.0.0.1:0".parse().unwrap());
        assert_eq!(endpoint.sandbox_host, "127.0.0.1");
        assert!(endpoint.extra_hosts.is_empty());
        let overridden = endpoint_with(&local, "127.0.0.2:0", "proxy.test").await;
        assert_eq!(overridden.bind, "127.0.0.2:0".parse().unwrap());
        assert_eq!(overridden.sandbox_host, "proxy.test");
    }

    async fn endpoint_with(executor: &Executor, bind: &str, host: &str) -> Endpoint {
        let overrides = Overrides::parse(Some(bind), Some(host));
        endpoint(executor, None, &overrides).await.unwrap().unwrap()
    }

    #[tokio::test]
    async fn limits_open_connections_without_refusing_them() {
        // More connections than the limit: they queue, and each is served in turn.
        let upstream = FakeUpstream::start(reply_ok()).await;
        let limits = ListenerLimits {
            max_connections: 8,
            max_requests_per_route: 1000,
            ..ListenerLimits::default()
        };
        let proxy = proxy_with(limits).await;
        let route = register(&proxy, &upstream, "/acme/widgets.git");
        let path = format!("{}/info/refs?service=git-upload-pack", prefix(&route));
        let served = Arc::new(AtomicUsize::new(0));
        let mut tasks = Vec::new();
        for _ in 0..(limits.max_connections + 8) {
            let proxy = proxy.clone();
            let path = path.clone();
            let served = served.clone();
            tasks.push(tokio::spawn(async move {
                let (status, _, _) = request(&proxy, Method::GET, &path, &[], b"").await;
                assert_eq!(status, StatusCode::OK);
                served.fetch_add(1, Ordering::SeqCst);
            }));
        }
        for task in tasks {
            task.await.unwrap();
        }
        assert_eq!(served.load(Ordering::SeqCst), limits.max_connections + 8);
    }

    // ----- the listener's bounds (M5) --------------------------------------------------

    fn short_limits() -> ListenerLimits {
        ListenerLimits {
            max_connections: 64,
            max_connections_per_peer: 2,
            limit_loopback_peers: true,
            max_requests_per_route: 2,
            header_read_timeout: Duration::from_millis(300),
            body_read_timeout: Duration::from_millis(300),
            idle_timeout: Duration::from_millis(400),
        }
    }

    async fn proxy_with(limits: ListenerLimits) -> GitProxy {
        GitProxy::start_with_limits(
            Endpoint {
                bind: "127.0.0.1:0".parse().unwrap(),
                sandbox_host: "127.0.0.1".into(),
                extra_hosts: Vec::new(),
            },
            true,
            limits,
        )
        .await
        .unwrap()
    }

    /// An upstream that reads each request's first bytes, writes `prefix`, and then holds the
    /// connection without another byte.
    async fn hanging_upstream(prefix: &'static [u8]) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move {
            while let Ok((mut stream, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let mut buf = [0u8; 1024];
                    let _ = stream.read(&mut buf).await;
                    let _ = stream.write_all(prefix).await;
                    tokio::time::sleep(Duration::from_secs(3600)).await;
                    drop(stream);
                });
            }
        });
        url
    }

    /// Reads until the proxy closes the connection; what it sent. Panics after 5 s.
    async fn read_until_closed(stream: &mut TcpStream) -> String {
        let mut out = Vec::new();
        tokio::time::timeout(Duration::from_secs(5), async {
            let mut buf = [0u8; 4096];
            loop {
                match stream.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => out.extend_from_slice(&buf[..n]),
                }
            }
        })
        .await
        .expect("the proxy closes the connection");
        String::from_utf8_lossy(&out).into_owned()
    }

    #[tokio::test]
    async fn a_peer_gets_at_most_its_share_of_connections() {
        let proxy = proxy_with(short_limits()).await;
        let held_a = TcpStream::connect(proxy.local_addr()).await.unwrap();
        let held_b = TcpStream::connect(proxy.local_addr()).await.unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
        // A third connection from the same peer is closed at once, without an answer.
        let mut third = TcpStream::connect(proxy.local_addr()).await.unwrap();
        let _ = third
            .write_all(b"GET /x/mcp HTTP/1.1\r\nhost: p\r\n\r\n")
            .await;
        let started = std::time::Instant::now();
        assert_eq!(read_until_closed(&mut third).await, "");
        assert!(
            started.elapsed() < Duration::from_millis(250),
            "closed before any deadline"
        );
        // Once one closes, the peer may connect again.
        drop(held_a);
        drop(held_b);
        tokio::time::sleep(Duration::from_millis(100)).await;
        let (status, _, _) = request(&proxy, Method::GET, "/nothing/here", &[], b"").await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[test]
    fn loopback_peers_are_exempt_by_default_and_mapped_addresses_count_as_ipv4() {
        assert!(!ListenerLimits::default().limit_loopback_peers);
        assert_eq!(ListenerLimits::default().max_connections_per_peer, 8);
        assert_eq!(ListenerLimits::default().max_requests_per_route, 8);
        assert_eq!(
            ListenerLimits::default().header_read_timeout
                + ListenerLimits::default().body_read_timeout,
            Duration::from_secs(60)
        );
        assert_eq!(
            canonical_ip("::ffff:172.17.0.3".parse().unwrap()),
            "172.17.0.3".parse::<IpAddr>().unwrap()
        );
        assert_eq!(
            canonical_ip("fd00::3".parse().unwrap()),
            "fd00::3".parse::<IpAddr>().unwrap()
        );
    }

    #[tokio::test]
    async fn slow_headers_end_the_connection() {
        let proxy = proxy_with(short_limits()).await;
        let mut stream = TcpStream::connect(proxy.local_addr()).await.unwrap();
        stream.write_all(b"GET /x/mcp HTTP/1.1\r\n").await.unwrap();
        let started = std::time::Instant::now();
        let answer = read_until_closed(&mut stream).await;
        assert!(!answer.contains("200"), "{answer}");
        assert!(started.elapsed() >= Duration::from_millis(250));
    }

    #[tokio::test]
    async fn a_slow_git_body_is_cut_by_the_deadline() {
        let upstream = FakeUpstream::start(reply_ok()).await;
        let proxy = proxy_with(short_limits()).await;
        let route = register(&proxy, &upstream, "/acme/widgets.git");
        let path = format!("{}/git-upload-pack", prefix(&route));
        let mut stream = TcpStream::connect(proxy.local_addr()).await.unwrap();
        stream
            .write_all(
                format!("POST {path} HTTP/1.1\r\nhost: p\r\ncontent-length: 100\r\n\r\n0032want")
                    .as_bytes(),
            )
            .await
            .unwrap();
        let answer = read_until_closed(&mut stream).await;
        assert!(answer.starts_with("HTTP/1.1 408"), "{answer}");
    }

    #[tokio::test]
    async fn a_slow_mcp_body_is_cut_by_the_deadline() {
        let proxy = proxy_with(short_limits()).await;
        let guard = proxy
            .register_mcp(mcp_tools(pending("Requested.")))
            .unwrap();
        let path = mcp_path(&guard);
        let mut stream = TcpStream::connect(proxy.local_addr()).await.unwrap();
        stream
            .write_all(
                format!("POST {path} HTTP/1.1\r\nhost: p\r\ncontent-type: application/json\r\ncontent-length: 100\r\n\r\n{{\"jsonrpc\"")
                    .as_bytes(),
            )
            .await
            .unwrap();
        let answer = read_until_closed(&mut stream).await;
        assert!(answer.starts_with("HTTP/1.1 408"), "{answer}");
    }

    #[tokio::test]
    async fn a_route_serves_at_most_its_share_of_requests_at_once() {
        let hanging = hanging_upstream(b"").await;
        let proxy = proxy_with(ListenerLimits {
            limit_loopback_peers: false,
            idle_timeout: Duration::from_secs(60),
            ..short_limits()
        })
        .await;
        let stuck = proxy
            .register(
                Uuid::new_v4(),
                &format!("{hanging}/acme/stuck.git"),
                None,
                "agent/TEST-1",
            )
            .unwrap();
        let stuck_path = format!("{}/info/refs?service=git-upload-pack", prefix(&stuck));
        let mut held = Vec::new();
        for _ in 0..2 {
            let mut stream = TcpStream::connect(proxy.local_addr()).await.unwrap();
            stream
                .write_all(format!("GET {stuck_path} HTTP/1.1\r\nhost: p\r\n\r\n").as_bytes())
                .await
                .unwrap();
            held.push(stream);
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
        let (status, _, body) = request(&proxy, Method::GET, &stuck_path, &[], b"").await;
        assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{body}");
        // Another route, another run's, is not affected.
        let upstream = FakeUpstream::start(reply_ok()).await;
        let other = register(&proxy, &upstream, "/acme/widgets.git");
        let other_path = format!("{}/info/refs?service=git-upload-pack", prefix(&other));
        let (status, _, _) = request(&proxy, Method::GET, &other_path, &[], b"").await;
        assert_eq!(status, StatusCode::OK);
        // A request that ends gives its slot back.
        drop(held);
        tokio::time::sleep(Duration::from_millis(200)).await;
        let (status, _, _) = request(&proxy, Method::GET, &other_path, &[], b"").await;
        assert_eq!(status, StatusCode::OK);
    }

    #[tokio::test]
    async fn an_idle_connection_is_closed() {
        // The upstream sends the headers and three bytes of a 100-byte body, then nothing.
        let hanging = hanging_upstream(
            b"HTTP/1.1 200 OK\r\ncontent-type: application/x-git-upload-pack-advertisement\r\ncontent-length: 100\r\n\r\nabc",
        )
        .await;
        let proxy = proxy_with(short_limits()).await;
        let route = proxy
            .register(
                Uuid::new_v4(),
                &format!("{hanging}/acme/slow.git"),
                None,
                "agent/TEST-1",
            )
            .unwrap();
        let path = format!("{}/info/refs?service=git-upload-pack", prefix(&route));
        let mut stream = TcpStream::connect(proxy.local_addr()).await.unwrap();
        stream
            .write_all(format!("GET {path} HTTP/1.1\r\nhost: p\r\n\r\n").as_bytes())
            .await
            .unwrap();
        let started = std::time::Instant::now();
        let answer = read_until_closed(&mut stream).await;
        assert!(answer.starts_with("HTTP/1.1 200"), "{answer}");
        assert!(answer.ends_with("abc"), "{answer}");
        assert!(started.elapsed() >= Duration::from_millis(300));
    }

    // ----- platform MCP endpoint -------------------------------------------------------

    use crate::mcp::RequestOutcome;
    use crate::mcp::test_support::{FakeRequester, pending};
    use tinfirm_protocol::{
        RepositoryRequestStatus, RequestableRepository, RunRepositoryRequestAck,
    };

    fn mcp_tools(outcome: RequestOutcome) -> Arc<RunTools> {
        Arc::new(RunTools::new(
            Uuid::new_v4(),
            1,
            &[],
            &[RequestableRepository {
                component_id: Uuid::new_v4(),
                name: "Docs".into(),
                repo_url: "https://github.com/acme/docs.git".into(),
                pending_request: false,
            }],
            FakeRequester::new(outcome),
            "0.1.0-test",
        ))
    }

    const MCP_HEADERS: [(&str, &str); 2] = [
        ("content-type", "application/json"),
        ("accept", "application/json, text/event-stream"),
    ];

    async fn post_json(
        proxy: &GitProxy,
        path: &str,
        message: serde_json::Value,
    ) -> (StatusCode, hyper::HeaderMap, String) {
        request_bytes(
            proxy,
            Method::POST,
            path,
            &MCP_HEADERS,
            Bytes::from(message.to_string()),
        )
        .await
    }

    fn mcp_path(guard: &McpGuard) -> String {
        url::Url::parse(guard.url()).unwrap().path().to_string()
    }

    #[tokio::test]
    async fn serves_the_mcp_handshake_and_tools_of_a_run() {
        let proxy = proxy().await;
        let guard = proxy
            .register_mcp(mcp_tools(pending("Requested the repository `Docs`.")))
            .unwrap();
        let url = url::Url::parse(guard.url()).unwrap();
        assert_eq!(url.scheme(), "http");
        assert_eq!(url.host_str(), Some("127.0.0.1"));
        assert_eq!(url.port(), Some(proxy.local_addr().port()));
        let path = mcp_path(&guard);
        assert_eq!(path, format!("/{}/mcp", guard.secret().expose()));
        assert_eq!(guard.secret().expose().len(), 43);
        assert_eq!(proxy.mcp_route_count(), 1);
        assert_eq!(proxy.route_count(), 0);

        let (status, headers, body) = post_json(
            &proxy,
            &path,
            serde_json::json!({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "claude-code", "version": "2"}
            }}),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(headers[header::CONTENT_TYPE], "application/json");
        assert!(headers.get("mcp-session-id").is_none(), "stateless");
        let init: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(init["id"], 1);
        assert_eq!(init["result"]["protocolVersion"], "2025-06-18");
        assert_eq!(init["result"]["serverInfo"]["name"], "tinfirm");
        assert_eq!(init["result"]["serverInfo"]["version"], "0.1.0-test");

        let (status, _, body) = post_json(
            &proxy,
            &path,
            serde_json::json!({"jsonrpc": "2.0", "method": "notifications/initialized"}),
        )
        .await;
        assert_eq!(status, StatusCode::ACCEPTED);
        assert_eq!(body, "");

        let (status, _, body) = post_json(
            &proxy,
            &path,
            serde_json::json!({"jsonrpc": "2.0", "id": "two", "method": "tools/list"}),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let list: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(list["id"], "two");
        assert_eq!(list["result"]["tools"][1]["name"], "request_repository");
        assert_eq!(
            list["result"]["tools"][1]["inputSchema"]["required"],
            serde_json::json!(["name", "reason"])
        );

        let (_, _, body) = post_json(
            &proxy,
            &path,
            serde_json::json!({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "list_repositories", "arguments": {}}}),
        )
        .await;
        let listed: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(
            listed["result"]["structuredContent"],
            serde_json::json!({"repositories": [], "requestable": [{"name": "Docs", "repoUrl": "https://github.com/acme/docs.git", "pendingRequest": false}]})
        );

        let (_, _, body) = post_json(
            &proxy,
            &path,
            serde_json::json!({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "request_repository", "arguments": {"name": "docs", "reason": "API docs"}}}),
        )
        .await;
        let called: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(called["result"]["isError"], false);
        assert_eq!(
            called["result"]["content"],
            serde_json::json!([{"type": "text", "text": "Requested the repository `Docs`."}])
        );
        let (_, _, body) = post_json(
            &proxy,
            &path,
            serde_json::json!({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "list_repositories"}}),
        )
        .await;
        let listed: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(
            listed["result"]["structuredContent"]["requestable"][0]["pendingRequest"],
            true
        );

        let (_, _, body) = post_json(
            &proxy,
            &path,
            serde_json::json!({"jsonrpc": "2.0", "id": 6, "method": "ping"}),
        )
        .await;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&body).unwrap(),
            serde_json::json!({"jsonrpc": "2.0", "id": 6, "result": {}})
        );

        // The run ends: the endpoint is gone.
        drop(guard);
        assert_eq!(proxy.mcp_route_count(), 0);
        let (status, _, _) = post_json(
            &proxy,
            &path,
            serde_json::json!({"jsonrpc": "2.0", "id": 7, "method": "ping"}),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn rejected_and_unanswered_requests_are_tool_errors() {
        let rejected = RequestOutcome::Answered(RunRepositoryRequestAck {
            run_id: Uuid::nil(),
            request_id: Uuid::nil(),
            status: RepositoryRequestStatus::Rejected,
            repository_request_id: None,
            code: Some("ALREADY_PENDING".into()),
            message: "A request for the repository `Docs` is already pending.".into(),
        });
        let proxy = proxy().await;
        for (outcome, text) in [
            (
                rejected,
                "A request for the repository `Docs` is already pending.",
            ),
            (
                RequestOutcome::TimedOut,
                "Tinfirm did not answer the request in time. Say in your summary that you need the repository `Docs` and why.",
            ),
        ] {
            let guard = proxy.register_mcp(mcp_tools(outcome)).unwrap();
            let (status, _, body) = post_json(
                &proxy,
                &mcp_path(&guard),
                serde_json::json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "request_repository", "arguments": {"name": "Docs", "reason": "x"}}}),
            )
            .await;
            assert_eq!(status, StatusCode::OK);
            let called: serde_json::Value = serde_json::from_str(&body).unwrap();
            assert_eq!(called["result"]["isError"], true);
            assert_eq!(called["result"]["content"][0]["text"], text);
        }
    }

    #[tokio::test]
    async fn the_mcp_endpoint_refuses_everything_else() {
        let upstream = FakeUpstream::start(reply_ok()).await;
        let proxy = proxy().await;
        let guard = proxy.register_mcp(mcp_tools(pending("stored"))).unwrap();
        let route = register(&proxy, &upstream, "/acme/widgets.git");
        let path = mcp_path(&guard);
        let secret = guard.secret().expose().to_string();
        let ping = Bytes::from_static(br#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#);

        // No server-sent events, no sessions.
        for method in [Method::GET, Method::DELETE, Method::PUT] {
            let (status, headers, _) =
                request_bytes(&proxy, method.clone(), &path, &MCP_HEADERS, Bytes::new()).await;
            assert_eq!(status, StatusCode::METHOD_NOT_ALLOWED, "{method}");
            assert_eq!(headers[header::ALLOW], "POST");
        }
        // Wrong secrets, other paths, and a git route's secret are all not found.
        let mut last_off = secret.clone();
        let last = last_off.pop().unwrap();
        last_off.push(if last == 'A' { 'B' } else { 'A' });
        for wrong in [
            format!("/{last_off}/mcp"),
            format!("/{}/mcp", &secret[..42]),
            format!("/{secret}/mcp/"),
            format!("/{secret}/mcp/x"),
            format!("/{secret}/MCP"),
            format!("/{secret}"),
            "/mcp".to_string(),
            format!("/{}/mcp", route.secret().expose()),
        ] {
            let (status, _, _) =
                request_bytes(&proxy, Method::POST, &wrong, &MCP_HEADERS, ping.clone()).await;
            assert_eq!(status, StatusCode::NOT_FOUND, "{wrong}");
        }
        // The MCP secret does not open the git route either.
        let (status, _, _) = request(
            &proxy,
            Method::GET,
            &format!("/{secret}/acme/widgets.git/info/refs?service=git-upload-pack"),
            &[],
            b"",
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert!(upstream.seen().is_empty());
        // Browsers (anything with Origin) are refused.
        let (status, _, _) = request_bytes(
            &proxy,
            Method::POST,
            &path,
            &[
                ("content-type", "application/json"),
                ("origin", "http://evil.example"),
            ],
            ping.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);
        // Broken JSON and batches.
        let (status, _, body) = request_bytes(
            &proxy,
            Method::POST,
            &path,
            &MCP_HEADERS,
            Bytes::from_static(b"{not json"),
        )
        .await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert!(body.contains("-32700"), "{body}");
        let (status, _, body) = request_bytes(
            &proxy,
            Method::POST,
            &path,
            &MCP_HEADERS,
            Bytes::from_static(br#"[{"jsonrpc":"2.0","id":1,"method":"ping"}]"#),
        )
        .await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert!(body.contains("-32600"), "{body}");
        // The right request still works.
        let (status, _, _) = request_bytes(&proxy, Method::POST, &path, &MCP_HEADERS, ping).await;
        assert_eq!(status, StatusCode::OK);
    }
}
