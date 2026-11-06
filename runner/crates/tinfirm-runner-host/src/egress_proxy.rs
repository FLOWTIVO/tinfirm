//! Egress proxy for the sandbox (ADR-0044, threat model 7.1, 7.2 and R24).
//!
//! With `run --egress-proxy` a sandbox has no route to the network at all: it runs on an
//! internal Docker network ([`crate::docker::ensure_internal_network`]), and the only thing
//! it can reach is this proxy on the network's gateway address. The proxy lets a connection
//! out only when its destination is on the allowlist, so a prompt-injected agent cannot send
//! what it read, or the key in its environment, to a host of its choosing.
//!
//! - `CONNECT host:port` (HTTPS, and anything else tunnelled) and plain `http://` requests in
//!   absolute form are served. Every other request is refused.
//! - The decision is made on the destination name the client asked for: the allowlist is
//!   the host-wide rules (`--egress-allow`, the package mirrors of `--sandbox-env`) plus the
//!   run's own (its model provider or gateway). Everything else is `403`.
//! - The proxy resolves the name itself and connects to an address that it checked, so a DNS
//!   answer cannot lead into the runner host's own network: loopback, link-local (cloud
//!   metadata), multicast and unspecified addresses are always refused, and private ones
//!   (RFC 1918, 100.64.0.0/10, fc00::/7) unless `--egress-allow-private` says that the
//!   allowed hosts are internal ones.
//! - Nothing is inspected or decrypted: a tunnel is bytes. The allowlist limits where data
//!   can go, not what it is (exfiltration to an allowed host stays possible).
//!
//! Every run gets a listener of its own, on a port of the network's gateway address, which
//! stands for the run: its rules, its list of denied destinations (shown in the run's log,
//! so an operator sees what to allow) and its end, when the guard is dropped. The listener
//! asks for a credential of the run (`Proxy-Authorization: Basic`, in the proxy URL of the
//! sandbox's environment), so a sandbox cannot use the rules of another run, which may
//! include hosts that a member approved for that run only. The proxy refuses to connect to
//! its own address, so an allowed name that points at the runner host reaches none of the
//! host's listeners.
//!
//! A ready sandbox VM (crate::pool) gets its listener before any run: the VM's own settings
//! name it when the VM is created. Such a listener ([`EgressProxy::start_slot`]) refuses every
//! destination, the host-wide ones included, until the run that claims the VM binds its rules
//! to it ([`EgressGuard::bind`]).

use std::collections::BTreeSet;
use std::convert::Infallible;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use anyhow::{Context, Result};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use http_body_util::combinators::UnsyncBoxBody;
use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper::header::{self, HeaderMap, HeaderName, HeaderValue};
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::rt::{TokioIo, TokioTimer};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Semaphore;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::git_proxy::{Endpoint, new_secret};
use crate::secret_eq::secret_eq;

/// Open connections per run; more wait until one closes.
const MAX_CONNECTIONS: usize = 256;
/// Open tunnels per run (each holds two sockets of the runner host).
const MAX_TUNNELS: usize = 256;
/// The user name of the proxy credential.
const PROXY_USER: &str = "tinfirm";
const HEADER_READ_TIMEOUT: Duration = Duration::from_secs(30);
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(10);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
/// Until the upstream of a plain HTTP request answers with its headers.
const RESPONSE_TIMEOUT: Duration = Duration::from_secs(300);
/// Denied destinations remembered per run (for the run's log).
const MAX_DENIED_REMEMBERED: usize = 64;
/// The port of a rule without one: HTTPS.
const DEFAULT_PORT: u16 = 443;
/// Variables that point a sandbox at the proxy. `--sandbox-env` may not set them.
pub const PROXY_ENV: [&str; 8] = [
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "NO_PROXY",
    "ALL_PROXY",
    "http_proxy",
    "https_proxy",
    "no_proxy",
    "all_proxy",
];

type BoxError = Box<dyn std::error::Error + Send + Sync>;
type ProxyBody = UnsyncBoxBody<Bytes, BoxError>;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

// ----- the allowlist ---------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
enum HostPattern {
    /// `registry.example.com`
    Exact(String),
    /// `*.example.com`: any subdomain, not `example.com` itself. Holds `example.com`.
    Subdomains(String),
    /// `192.0.2.10`, `[2001:db8::1]`
    Ip(IpAddr),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Ports {
    One(u16),
    Any,
}

/// One allowlist entry: `HOST[:PORT]`. `HOST` is a name, `*.` and a name (the subdomains),
/// or an IP address. The port is 443 unless given; `:*` is every port.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rule {
    host: HostPattern,
    ports: Ports,
}

impl Rule {
    /// Whether the rule names an IP address, not a host name.
    pub fn is_ip(&self) -> bool {
        matches!(self.host, HostPattern::Ip(_))
    }

    /// `example.com` of the rule `*.example.com`; `None` for any other rule.
    pub fn wildcard_base(&self) -> Option<&str> {
        match &self.host {
            HostPattern::Subdomains(domain) => Some(domain),
            _ => None,
        }
    }

    pub fn parse(entry: &str) -> std::result::Result<Rule, String> {
        let entry = entry.trim();
        if entry.is_empty() {
            return Err("an allowlist entry is empty".into());
        }
        if entry.contains("://")
            || entry.contains(['/', '@', '?', '#'])
            || entry.chars().any(char::is_whitespace)
        {
            return Err(
                "an allowlist entry is HOST or HOST:PORT, without a scheme, a user or a path"
                    .into(),
            );
        }
        let (host, port) = split_port(entry)?;
        let ports = match port {
            None => Ports::One(DEFAULT_PORT),
            Some("*") => Ports::Any,
            Some(port) => match port.parse::<u16>() {
                Ok(port) if port != 0 => Ports::One(port),
                _ => return Err(format!("`{port}` is not a port (1 to 65535, or `*`)")),
            },
        };
        let host = if let Ok(ip) = host.parse::<IpAddr>() {
            HostPattern::Ip(ip.to_canonical())
        } else {
            let host = host.trim_end_matches('.').to_ascii_lowercase();
            if host == "*" {
                return Err("`*` allows every host, which is no allowlist".into());
            }
            if let Some(domain) = host.strip_prefix("*.") {
                if !domain.contains('.') {
                    return Err(format!(
                        "`{host}` would allow a whole top-level domain; name a domain below it"
                    ));
                }
                check_hostname(domain)?;
                HostPattern::Subdomains(domain.to_string())
            } else {
                check_hostname(&host)?;
                HostPattern::Exact(host)
            }
        };
        Ok(Rule { host, ports })
    }

    fn matches(&self, target: &Target) -> bool {
        let port_ok = match self.ports {
            Ports::Any => true,
            Ports::One(port) => port == target.port,
        };
        port_ok
            && match (&self.host, target.ip) {
                (HostPattern::Ip(ip), Some(target_ip)) => *ip == target_ip,
                (HostPattern::Ip(_), None) | (_, Some(_)) => false,
                (HostPattern::Exact(name), None) => *name == target.host,
                (HostPattern::Subdomains(domain), None) => target
                    .host
                    .strip_suffix(domain.as_str())
                    .is_some_and(|rest| rest.ends_with('.') && rest.len() > 1),
            }
    }

    /// The rule for the origin of an `http(s)` URL (a mirror, a gateway); `None` for a URL
    /// that is no http(s) URL with a host.
    pub fn for_url(url: &str) -> Option<Rule> {
        let url = url::Url::parse(url.trim()).ok()?;
        if !matches!(url.scheme(), "http" | "https") {
            return None;
        }
        let host = match url.host()? {
            url::Host::Domain(domain) => HostPattern::Exact(domain.to_ascii_lowercase()),
            url::Host::Ipv4(ip) => HostPattern::Ip(IpAddr::V4(ip)),
            url::Host::Ipv6(ip) => HostPattern::Ip(IpAddr::V6(ip).to_canonical()),
        };
        Some(Rule {
            host,
            ports: Ports::One(url.port_or_known_default()?),
        })
    }
}

impl std::fmt::Display for Rule {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match &self.host {
            HostPattern::Exact(name) => write!(f, "{name}")?,
            HostPattern::Subdomains(domain) => write!(f, "*.{domain}")?,
            HostPattern::Ip(IpAddr::V4(ip)) => write!(f, "{ip}")?,
            HostPattern::Ip(IpAddr::V6(ip)) => write!(f, "[{ip}]")?,
        }
        match self.ports {
            Ports::One(port) => write!(f, ":{port}"),
            Ports::Any => write!(f, ":*"),
        }
    }
}

/// `HOST` and the port part of `HOST[:PORT]`, with `[v6]` brackets removed.
fn split_port(entry: &str) -> std::result::Result<(&str, Option<&str>), String> {
    if let Some(rest) = entry.strip_prefix('[') {
        let (host, after) = rest
            .split_once(']')
            .ok_or_else(|| "a `[` without its `]`".to_string())?;
        return match after {
            "" => Ok((host, None)),
            _ => after
                .strip_prefix(':')
                .map(|port| (host, Some(port)))
                .ok_or_else(|| "only a `:PORT` may follow `]`".to_string()),
        };
    }
    match entry.rsplit_once(':') {
        None => Ok((entry, None)),
        // More than one colon: an IPv6 address without brackets, which has no port.
        Some((host, _)) if host.contains(':') => Ok((entry, None)),
        Some((host, port)) => Ok((host, Some(port))),
    }
}

fn check_hostname(host: &str) -> std::result::Result<(), String> {
    let valid = !host.is_empty()
        && host.len() <= 253
        && host.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        });
    if valid {
        Ok(())
    } else {
        Err(format!("`{host}` is not a host name"))
    }
}

/// Where a client wants to connect.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Target {
    /// Lowercase, without a trailing dot or brackets.
    host: String,
    /// The host when it is an IP address.
    ip: Option<IpAddr>,
    port: u16,
}

impl Target {
    fn new(host: &str, port: u16) -> Target {
        let host = host
            .trim_start_matches('[')
            .trim_end_matches(']')
            .trim_end_matches('.')
            .to_ascii_lowercase();
        let ip = host.parse::<IpAddr>().ok().map(|ip| ip.to_canonical());
        Target { host, ip, port }
    }

    fn from_authority(
        authority: &hyper::http::uri::Authority,
        default_port: Option<u16>,
    ) -> Option<Target> {
        let port = authority.port_u16().or(default_port)?;
        Some(Target::new(authority.host(), port))
    }

    fn label(&self) -> String {
        match self.ip {
            Some(IpAddr::V6(ip)) => format!("[{ip}]:{}", self.port),
            _ => format!("{}:{}", self.host, self.port),
        }
    }
}

/// The host-wide settings of the egress proxy.
#[derive(Debug, Clone, Default)]
pub struct EgressSettings {
    /// `--egress-allow`, and the package mirrors of `--sandbox-env`.
    pub allow: Vec<Rule>,
    /// `--egress-allow-private`: allowed hosts may have private addresses.
    pub allow_private: bool,
    /// Loopback addresses are allowed (the test executor only; never a command-line option).
    pub allow_loopback: bool,
}

impl EgressSettings {
    /// The settings of `run`'s options: `Ok(None)` without `--egress-proxy`. The allowlist is
    /// `--egress-allow` and the mirrors of `--sandbox-env`; a `--sandbox-env` that sets a
    /// proxy variable is refused, because it would send the tools around (or past) the proxy.
    pub fn from_options(
        enabled: bool,
        allow: &[String],
        allow_private: bool,
        sandbox_env: &[(String, String)],
    ) -> Result<Option<EgressSettings>> {
        let entries = || allow.iter().filter(|e| !e.trim().is_empty());
        if !enabled {
            anyhow::ensure!(
                entries().next().is_none() && !allow_private,
                "--egress-allow and --egress-allow-private need --egress-proxy"
            );
            return Ok(None);
        }
        for (key, _) in sandbox_env {
            anyhow::ensure!(
                !PROXY_ENV.contains(&key.as_str()),
                "--sandbox-env sets {key}, which --egress-proxy sets for every sandbox"
            );
        }
        let mut rules = entries()
            .map(|entry| Rule::parse(entry).map_err(|e| anyhow::anyhow!("--egress-allow: {e}")))
            .collect::<Result<Vec<_>>>()?;
        rules.extend(rules_from_sandbox_env(sandbox_env));
        Ok(Some(EgressSettings {
            allow: rules,
            allow_private,
            allow_loopback: false,
        }))
    }
}

/// Whether `ip` is an address of this machine: one that a socket can be bound to.
pub(crate) fn is_local_address(ip: IpAddr) -> bool {
    std::net::UdpSocket::bind(SocketAddr::new(ip, 0)).is_ok()
}

/// Cloud metadata services outside the link-local range (which is refused as a whole): they
/// hold the host's credentials, so no allowlist entry and no `--egress-allow-private` opens them.
const METADATA_ADDRESSES: [&str; 3] = [
    // Alibaba Cloud (inside 100.64.0.0/10, which `--egress-allow-private` opens).
    "100.100.100.200",
    // Azure's wire server, a public address.
    "168.63.129.16",
    // AWS's IPv6 instance metadata (inside fc00::/7).
    "fd00:ec2::254",
];

/// Why a connection to `ip` is refused whatever the allowlist says, or `None`. The model proxy
/// applies the same check to its gateway ([`crate::model_proxy`]).
pub(crate) fn refused_address(ip: IpAddr, settings: &EgressSettings) -> Option<&'static str> {
    if METADATA_ADDRESSES
        .iter()
        .any(|m| m.parse::<IpAddr>().is_ok_and(|m| m == ip.to_canonical()))
    {
        return Some("cloud metadata");
    }
    match ip.to_canonical() {
        IpAddr::V4(ip) => {
            let o = ip.octets();
            if ip.is_unspecified() || o[0] == 0 {
                Some("unspecified")
            } else if ip.is_loopback() {
                (!settings.allow_loopback).then_some("loopback")
            } else if ip.is_link_local() {
                Some("link-local (cloud metadata)")
            } else if ip.is_multicast() || ip.is_broadcast() {
                Some("multicast")
            } else if (o[0] == 192 && o[1] == 0 && (o[2] == 0 || o[2] == 2))
                || (o[0] == 198 && o[1] & 0xfe == 18)
                || (o[0] == 198 && o[1] == 51 && o[2] == 100)
                || (o[0] == 203 && o[1] == 0 && o[2] == 113)
                || (o[0] == 192 && o[1] == 88 && o[2] == 99)
                || o[0] >= 240
            {
                // IETF protocol assignments, the three documentation ranges, the 6to4 relay
                // anycast range, the benchmarking range and the reserved class E.
                Some("reserved")
            } else if ip.is_private() || (o[0] == 100 && o[1] & 0xc0 == 64) {
                (!settings.allow_private).then_some("private")
            } else {
                None
            }
        }
        IpAddr::V6(ip) => {
            // 6to4 (2002::/16) and NAT64 (64:ff9b::/96) carry an IPv4 address: it is the
            // one that counts.
            let s = ip.segments();
            // The local-use NAT64 prefix (RFC 8215, 64:ff9b:1::/48) embeds an address at a length
            // that depends on the network: it is refused as a whole.
            if s[0] == 0x64 && s[1] == 0xff9b && s[2] == 1 {
                return Some("translated");
            }
            let embedded = if s[0] == 0x2002 {
                Some([(s[1] >> 8) as u8, s[1] as u8, (s[2] >> 8) as u8, s[2] as u8])
            } else if s[0] == 0x64 && s[1] == 0xff9b && s[2..6] == [0; 4] {
                Some([(s[6] >> 8) as u8, s[6] as u8, (s[7] >> 8) as u8, s[7] as u8])
            } else {
                None
            };
            if let Some(v4) = embedded {
                return refused_address(IpAddr::V4(std::net::Ipv4Addr::from(v4)), settings)
                    .or(Some("translated"));
            }
            if ip.is_unspecified() {
                Some("unspecified")
            } else if ip.is_loopback() {
                (!settings.allow_loopback).then_some("loopback")
            } else if ip.segments()[0] & 0xffc0 == 0xfe80 {
                Some("link-local")
            } else if (ip.segments()[0] == 0x2001 && ip.segments()[1] == 0x0db8)
                || (ip.segments()[0] == 0x0100 && ip.segments()[1..4] == [0; 3])
                || (ip.segments()[0] == 0x2001 && ip.segments()[1] < 0x0200)
            {
                // Documentation (2001:db8::/32), the discard prefix (100::/64) and the IETF
                // protocol assignments (2001::/23: Teredo, benchmarking, ORCHID and others).
                Some("reserved")
            } else if ip.segments()[0] & 0xffc0 == 0xfec0 {
                // Deprecated site-local addresses behave like private ones.
                (!settings.allow_private).then_some("private")
            } else if ip.is_multicast() {
                Some("multicast")
            } else if ip.segments()[0] & 0xfe00 == 0xfc00 {
                (!settings.allow_private).then_some("private")
            } else {
                None
            }
        }
    }
}

/// Why `--egress-proxy` cannot work when sandboxes reach the host's listeners as `sandbox_host`, or
/// `None`. With Docker Desktop (or any engine in a VM) that name is `host.docker.internal`, an
/// address outside the subnet of an internal network: no sandbox could reach the proxy, the git
/// proxy or the platform tools, and every run would fail at its first connection, with timeouts.
pub fn unsupported_sandbox_host(sandbox_host: &str) -> Option<String> {
    (sandbox_host == crate::git_proxy::HOST_DOCKER_INTERNAL).then(|| {
        format!(
            "--egress-proxy needs Docker Engine on Linux: here the sandboxes would reach this machine as {sandbox_host}, which an internal Docker network cannot route to (Docker Desktop, or an engine in a VM), so every run would fail at its first connection. Run the runner host on a Linux machine with Docker Engine, or leave --egress-proxy off (ADR-0044)"
        )
    })
}

/// The rules for the package mirrors and registries that `--sandbox-env` names
/// (`TINFIRM_SANDBOX_*`, ADR-0025): the sandbox is set up to use them, so it may reach them.
pub fn rules_from_sandbox_env(env: &[(String, String)]) -> Vec<Rule> {
    const URLS: [&str; 3] = [
        "TINFIRM_SANDBOX_MAVEN_MIRROR",
        "TINFIRM_SANDBOX_NPM_REGISTRY",
        "TINFIRM_SANDBOX_PIP_INDEX",
    ];
    let mut rules = Vec::new();
    for (key, value) in env {
        if URLS.contains(&key.as_str()) {
            rules.extend(Rule::for_url(value));
        } else if key == "TINFIRM_SANDBOX_REGISTRY_MIRRORS" {
            rules.extend(value.split(',').filter_map(Rule::for_url));
        }
    }
    rules.dedup();
    rules
}

// ----- the proxy -------------------------------------------------------------------------

/// The runner host's egress proxy: the settings, and where runs' listeners bind.
pub struct EgressProxy {
    settings: Arc<EgressSettings>,
    bind_ip: IpAddr,
    sandbox_host: String,
    extra_hosts: Vec<String>,
}

impl EgressProxy {
    /// The proxy's listeners bind the address of the git proxy's [`Endpoint`] (the network's
    /// gateway), on a port of their own, and sandboxes reach them by the same host name.
    pub fn new(settings: EgressSettings, endpoint: &Endpoint) -> EgressProxy {
        EgressProxy {
            settings: Arc::new(settings),
            bind_ip: endpoint.bind.ip(),
            sandbox_host: endpoint.sandbox_host.clone(),
            extra_hosts: endpoint.extra_hosts.clone(),
        }
    }

    /// Starts a run's listener: the host-wide rules and `run_rules` apply until the guard is
    /// dropped.
    pub async fn start_run(&self, run_id: Uuid, run_rules: Vec<Rule>) -> Result<EgressGuard> {
        self.start_run_at(run_id, run_rules, None).await
    }

    /// [`EgressProxy::start_run`], with a proxy URL that names `sandbox_host` in place of the
    /// endpoint's (a local test executor's sandbox over the bridge).
    pub async fn start_run_at(
        &self,
        run_id: Uuid,
        run_rules: Vec<Rule>,
        sandbox_host: Option<&str>,
    ) -> Result<EgressGuard> {
        let guard = self.start_slot(sandbox_host).await?;
        guard.bind(run_id, run_rules);
        Ok(guard)
    }

    /// A listener of no run yet, for a sandbox that waits for one (a ready VM, crate::pool):
    /// it refuses every destination until [`EgressGuard::bind`].
    pub async fn start_slot(&self, sandbox_host: Option<&str>) -> Result<EgressGuard> {
        anyhow::ensure!(
            !self.bind_ip.is_unspecified(),
            "the egress proxy must listen on the sandbox network's gateway, not on every address; leave TINFIRM_RUNNER_GIT_PROXY_BIND to an address of that network"
        );
        let secret = new_secret()?;
        let listener = TcpListener::bind(SocketAddr::new(self.bind_ip, 0))
            .await
            .with_context(|| format!("cannot listen on {} for the egress proxy", self.bind_ip))?;
        let port = listener.local_addr()?.port();
        let state = Arc::new(RunState {
            binding: RwLock::new(None),
            settings: self.settings.clone(),
            bind_ip: self.bind_ip,
            expected_auth: basic_auth(&secret),
            tunnels: Arc::new(Semaphore::new(MAX_TUNNELS)),
            denied: Mutex::new(BTreeSet::new()),
            allowed_count: AtomicU64::new(0),
            denied_count: AtomicU64::new(0),
            shutdown: CancellationToken::new(),
        });
        tokio::spawn(accept_loop(listener, state.clone()));
        let sandbox_host = sandbox_host.unwrap_or(&self.sandbox_host).to_string();
        let host = match sandbox_host.parse::<IpAddr>() {
            Ok(IpAddr::V6(ip)) => format!("[{ip}]"),
            _ => sandbox_host.clone(),
        };
        tracing::debug!(port, "egress proxy listening for a sandbox");
        Ok(EgressGuard {
            state,
            host,
            secret,
            sandbox_host,
            port,
            extra_hosts: self.extra_hosts.clone(),
        })
    }
}

/// A run's listener. Dropping it stops the listener and ends the run's open tunnels.
pub struct EgressGuard {
    state: Arc<RunState>,
    host: String,
    secret: String,
    sandbox_host: String,
    port: u16,
    extra_hosts: Vec<String>,
}

impl EgressGuard {
    /// The run whose sandbox this listener serves: from now on the host-wide rules and
    /// `run_rules` apply. A listener is bound once; a second bind changes nothing.
    pub fn bind(&self, run_id: Uuid, run_rules: Vec<Rule>) {
        let mut binding = self
            .state
            .binding
            .write()
            .unwrap_or_else(|e| e.into_inner());
        if binding.is_some() {
            tracing::warn!(%run_id, "the egress proxy's listener serves a run already");
            return;
        }
        *binding = Some(Binding {
            run_id,
            rules: run_rules,
        });
        tracing::info!(%run_id, port = self.port, "egress proxy listening for a run");
    }

    /// The proxy's URL for the sandbox, with the run's credential. It contains a secret:
    /// never log it.
    pub fn url(&self) -> String {
        format!(
            "http://{PROXY_USER}:{}@{}:{}",
            self.secret, self.host, self.port
        )
    }

    /// The run's proxy secret, for the redactor.
    pub fn secret(&self) -> &str {
        &self.secret
    }

    /// The listener's address on this host, which a sandbox's tunnel reaches.
    pub fn local_addr(&self) -> SocketAddr {
        SocketAddr::new(self.state.bind_ip, self.port)
    }

    /// The `Proxy-Authorization` header value that the listener accepts.
    pub fn proxy_authorization(&self) -> &str {
        &self.state.expected_auth
    }

    /// Docker `extra_hosts` entries the sandbox needs to resolve the proxy's host name.
    pub fn extra_hosts(&self) -> &[String] {
        &self.extra_hosts
    }

    /// The variables that point the sandbox's tools at the proxy. The sandbox's own
    /// listeners (the git proxy and the platform endpoint, on the same host) stay direct.
    pub fn env(&self) -> Vec<(String, String)> {
        let url = self.url();
        let no_proxy = format!("localhost,127.0.0.1,::1,{}", self.sandbox_host);
        let mut env = Vec::new();
        for key in ["HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy"] {
            env.push((key.to_string(), url.clone()));
        }
        for key in ["NO_PROXY", "no_proxy"] {
            env.push((key.to_string(), no_proxy.clone()));
        }
        env.push(("JAVA_TOOL_OPTIONS".to_string(), self.java_options()));
        env
    }

    /// The JVM (Maven, Gradle) ignores the variables above. Basic authentication of a
    /// `CONNECT` is off in the JVM unless its disabled schemes are emptied.
    pub fn java_options(&self) -> String {
        let (host, port, user, pass) = (&self.host, self.port, PROXY_USER, &self.secret);
        format!(
            "-Dhttp.proxyHost={host} -Dhttp.proxyPort={port} -Dhttps.proxyHost={host} \
             -Dhttps.proxyPort={port} -Dhttp.proxyUser={user} -Dhttp.proxyPassword={pass} \
             -Dhttps.proxyUser={user} -Dhttps.proxyPassword={pass} \
             -Djdk.http.auth.tunneling.disabledSchemes= \
             -Dhttp.nonProxyHosts=localhost|127.*|[::1]|{}",
            self.sandbox_host
        )
    }

    /// The destinations this run asked for and was refused, as `host:port`, sorted.
    pub fn denied(&self) -> Vec<String> {
        lock(&self.state.denied).iter().cloned().collect()
    }

    /// Connections let through and refused so far.
    pub fn counts(&self) -> (u64, u64) {
        (
            self.state.allowed_count.load(Ordering::Relaxed),
            self.state.denied_count.load(Ordering::Relaxed),
        )
    }
}

impl Drop for EgressGuard {
    fn drop(&mut self) {
        self.state.shutdown.cancel();
        tracing::info!(run_id = %self.state.run_label(), "egress proxy stopped for a run");
    }
}

impl std::fmt::Debug for EgressGuard {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EgressGuard")
            .field("port", &self.port)
            .finish()
    }
}

/// The run a listener serves, and its own rules.
struct Binding {
    run_id: Uuid,
    rules: Vec<Rule>,
}

struct RunState {
    /// `None` until a run binds the listener ([`EgressGuard::bind`]): every destination is
    /// refused then.
    binding: RwLock<Option<Binding>>,
    settings: Arc<EgressSettings>,
    /// The listener's own address: never a destination.
    bind_ip: IpAddr,
    /// The `Proxy-Authorization` value of the run.
    expected_auth: String,
    /// Bounds the open tunnels; a tunnel holds its permit until it ends.
    tunnels: Arc<Semaphore>,
    denied: Mutex<BTreeSet<String>>,
    allowed_count: AtomicU64,
    denied_count: AtomicU64,
    shutdown: CancellationToken,
}

impl RunState {
    fn allows(&self, target: &Target) -> bool {
        let binding = self.binding.read().unwrap_or_else(|e| e.into_inner());
        let Some(binding) = binding.as_ref() else {
            return false;
        };
        self.settings
            .allow
            .iter()
            .chain(&binding.rules)
            .any(|rule| rule.matches(target))
    }

    /// The run, for the log: `unbound` before one binds the listener.
    fn run_label(&self) -> String {
        match self
            .binding
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            Some(binding) => binding.run_id.to_string(),
            None => "unbound".to_string(),
        }
    }

    fn deny(&self, target: &Target) {
        self.denied_count.fetch_add(1, Ordering::Relaxed);
        let label = target.label();
        let mut denied = lock(&self.denied);
        if denied.len() < MAX_DENIED_REMEMBERED && denied.insert(label.clone()) {
            tracing::info!(run_id = %self.run_label(), destination = %label, "egress denied");
        }
    }
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
                    tracing::warn!(error = %e, "egress proxy: accept failed");
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
            let connection = builder
                .serve_connection(TokioIo::new(stream), service)
                .with_upgrades();
            tokio::select! {
                result = connection => {
                    if let Err(e) = result {
                        tracing::debug!(error = %e, "egress proxy: connection ended with an error");
                    }
                }
                _ = shutdown.cancelled() => {}
            }
        });
    }
}

fn basic_auth(secret: &str) -> String {
    format!(
        "Basic {}",
        STANDARD.encode(format!("{PROXY_USER}:{secret}"))
    )
}

fn text(status: StatusCode, message: impl Into<String>) -> Response<ProxyBody> {
    let body = Full::new(Bytes::from(message.into()))
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

fn denied_response(target: &Target) -> Response<ProxyBody> {
    let mut response = text(
        StatusCode::FORBIDDEN,
        format!(
            "tinfirm egress proxy: {} is not on this sandbox's allowlist\n",
            target.label()
        ),
    );
    response
        .headers_mut()
        .insert("x-tinfirm-egress", HeaderValue::from_static("denied"));
    response
}

async fn handle(state: Arc<RunState>, req: Request<Incoming>) -> Response<ProxyBody> {
    let authorized = req
        .headers()
        .get(header::PROXY_AUTHORIZATION)
        .is_some_and(|v| secret_eq(v.as_bytes(), state.expected_auth.as_bytes()));
    if !authorized {
        let mut response = text(
            StatusCode::PROXY_AUTHENTICATION_REQUIRED,
            "tinfirm egress proxy: this sandbox's proxy credential is missing or wrong\n",
        );
        response.headers_mut().insert(
            header::PROXY_AUTHENTICATE,
            HeaderValue::from_static("Basic realm=\"tinfirm egress\""),
        );
        return response;
    }
    if req.method() == Method::CONNECT {
        return connect(state, req).await;
    }
    forward(state, req).await
}

/// Resolves the target and connects to an address that is allowed to be reached.
async fn connect_vetted(
    state: &RunState,
    target: &Target,
) -> std::result::Result<TcpStream, Box<Response<ProxyBody>>> {
    let bad_gateway = |why: String| {
        Box::new(text(
            StatusCode::BAD_GATEWAY,
            format!("tinfirm egress proxy: {why}\n"),
        ))
    };
    let addrs: Vec<SocketAddr> = match target.ip {
        Some(ip) => vec![SocketAddr::new(ip, target.port)],
        None => {
            let lookup = tokio::net::lookup_host((target.host.as_str(), target.port));
            match tokio::time::timeout(RESOLVE_TIMEOUT, lookup).await {
                Ok(Ok(addrs)) => addrs.collect(),
                Ok(Err(e)) => {
                    return Err(bad_gateway(format!("cannot resolve {}: {e}", target.host)));
                }
                Err(_) => return Err(bad_gateway(format!("resolving {} timed out", target.host))),
            }
        }
    };
    if addrs.is_empty() {
        return Err(bad_gateway(format!("{} has no address", target.host)));
    }
    let mut refused = None;
    let mut last_error = None;
    for addr in addrs {
        if addr.ip().to_canonical() == state.bind_ip.to_canonical() {
            refused = Some("this host's own");
            continue;
        }
        if let Some(why) = refused_address(addr.ip(), &state.settings) {
            refused = Some(why);
            continue;
        }
        // Any other address of this machine would reach its services (SSH, a Docker API on
        // TCP) as the bridge address does. (The test setting that allows loopback also allows
        // the machine's own addresses, as the test servers run on it.)
        if !state.settings.allow_loopback && is_local_address(addr.ip()) {
            refused = Some("this host's own");
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
    match (last_error, refused) {
        (Some(error), _) => Err(bad_gateway(format!(
            "cannot connect to {}: {error}",
            target.label()
        ))),
        (None, why) => {
            let why = why.unwrap_or("refused");
            Err(Box::new(text(
                StatusCode::FORBIDDEN,
                format!(
                    "tinfirm egress proxy: {} resolves to a {why} address, which no allowlist entry opens\n",
                    target.label()
                ),
            )))
        }
    }
}

async fn connect(state: Arc<RunState>, req: Request<Incoming>) -> Response<ProxyBody> {
    let Some(target) = req
        .uri()
        .authority()
        .and_then(|authority| Target::from_authority(authority, None))
    else {
        return text(
            StatusCode::BAD_REQUEST,
            "tinfirm egress proxy: CONNECT needs host:port\n",
        );
    };
    if !state.allows(&target) {
        state.deny(&target);
        return denied_response(&target);
    }
    let Ok(permit) = state.tunnels.clone().try_acquire_owned() else {
        return text(
            StatusCode::SERVICE_UNAVAILABLE,
            "tinfirm egress proxy: too many open tunnels\n",
        );
    };
    let upstream = match connect_vetted(&state, &target).await {
        Ok(stream) => stream,
        Err(response) => {
            state.deny(&target);
            return *response;
        }
    };
    state.allowed_count.fetch_add(1, Ordering::Relaxed);
    tracing::debug!(run_id = %state.run_label(), destination = %target.label(), "egress tunnel");
    let shutdown = state.shutdown.clone();
    tokio::spawn(async move {
        let _permit = permit;
        let upgraded = match hyper::upgrade::on(req).await {
            Ok(upgraded) => upgraded,
            Err(e) => {
                tracing::debug!(error = %e, "egress proxy: the tunnel was not upgraded");
                return;
            }
        };
        let mut client = TokioIo::new(upgraded);
        let mut upstream = upstream;
        tokio::select! {
            result = tokio::io::copy_bidirectional(&mut client, &mut upstream) => {
                if let Err(e) = result {
                    tracing::debug!(error = %e, "egress proxy: a tunnel ended with an error");
                }
            }
            _ = shutdown.cancelled() => {}
        }
    });
    Response::new(
        http_body_util::Empty::<Bytes>::new()
            .map_err(|never| match never {})
            .boxed_unsync(),
    )
}

/// Headers that belong to one connection, not to the message: dropped on the way through,
/// with the ones that the `Connection` header names.
pub(crate) fn strip_hop_by_hop(headers: &mut HeaderMap) {
    let named: Vec<HeaderName> = headers
        .get_all(header::CONNECTION)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .filter_map(|name| HeaderName::from_bytes(name.trim().as_bytes()).ok())
        .collect();
    for name in named {
        headers.remove(name);
    }
    for name in [
        header::CONNECTION,
        header::PROXY_AUTHENTICATE,
        header::PROXY_AUTHORIZATION,
        header::TE,
        header::TRAILER,
        header::TRANSFER_ENCODING,
        header::UPGRADE,
        HeaderName::from_static("keep-alive"),
        HeaderName::from_static("proxy-connection"),
    ] {
        headers.remove(name);
    }
}

/// A plain `http://` request in absolute form, sent on in origin form.
async fn forward(state: Arc<RunState>, req: Request<Incoming>) -> Response<ProxyBody> {
    let uri = req.uri().clone();
    let (Some("http"), Some(authority)) = (uri.scheme_str(), uri.authority()) else {
        return text(
            StatusCode::BAD_REQUEST,
            "tinfirm egress proxy: this is a proxy; send CONNECT host:port, or a request with an http:// URL\n",
        );
    };
    let Some(target) = Target::from_authority(authority, Some(80)) else {
        return text(StatusCode::BAD_REQUEST, "tinfirm egress proxy: no host\n");
    };
    if !state.allows(&target) {
        state.deny(&target);
        return denied_response(&target);
    }
    let stream = match connect_vetted(&state, &target).await {
        Ok(stream) => stream,
        Err(response) => {
            state.deny(&target);
            return *response;
        }
    };
    state.allowed_count.fetch_add(1, Ordering::Relaxed);
    let (mut parts, body) = req.into_parts();
    let origin_form = uri
        .path_and_query()
        .map_or("/", hyper::http::uri::PathAndQuery::as_str);
    parts.uri = match origin_form.parse() {
        Ok(uri) => uri,
        Err(_) => return text(StatusCode::BAD_REQUEST, "tinfirm egress proxy: bad path\n"),
    };
    strip_hop_by_hop(&mut parts.headers);
    // The destination that was checked is the one the request names, not the one the client's
    // `Host` header claims: another virtual host of the same server must not be reachable.
    if let Ok(host) = HeaderValue::from_str(authority.as_str()) {
        parts.headers.insert(header::HOST, host);
    }
    let bad_gateway = |why: String| {
        text(
            StatusCode::BAD_GATEWAY,
            format!("tinfirm egress proxy: {why}\n"),
        )
    };
    let (mut sender, connection) =
        match hyper::client::conn::http1::handshake(TokioIo::new(stream)).await {
            Ok(pair) => pair,
            Err(e) => return bad_gateway(format!("{}: {e}", target.label())),
        };
    let shutdown = state.shutdown.clone();
    tokio::spawn(async move {
        tokio::select! {
            _ = connection => {}
            _ = shutdown.cancelled() => {}
        }
    });
    let outgoing = Request::from_parts(parts, body);
    match tokio::time::timeout(RESPONSE_TIMEOUT, sender.send_request(outgoing)).await {
        Ok(Ok(response)) => {
            let (mut parts, body) = response.into_parts();
            strip_hop_by_hop(&mut parts.headers);
            Response::from_parts(
                parts,
                body.map_err(|e| Box::new(e) as BoxError).boxed_unsync(),
            )
        }
        Ok(Err(e)) => bad_gateway(format!("{}: {e}", target.label())),
        Err(_) => bad_gateway(format!("{} did not answer", target.label())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    fn guard_addr(guard: &EgressGuard) -> SocketAddr {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2)), guard.port)
    }

    fn rule(entry: &str) -> Rule {
        Rule::parse(entry).unwrap()
    }

    fn target(host: &str, port: u16) -> Target {
        Target::new(host, port)
    }

    #[test]
    fn rules_parse_and_match() {
        assert!(rule("api.anthropic.com").matches(&target("api.anthropic.com", 443)));
        assert!(rule("API.Anthropic.com.").matches(&target("api.anthropic.com", 443)));
        assert!(!rule("api.anthropic.com").matches(&target("api.anthropic.com", 80)));
        assert!(!rule("api.anthropic.com").matches(&target("evil-api.anthropic.com", 443)));
        assert!(!rule("api.anthropic.com").matches(&target("anthropic.com", 443)));
        assert!(rule("registry.corp:8443").matches(&target("registry.corp", 8443)));
        assert!(rule("registry.corp:*").matches(&target("registry.corp", 1)));
        assert!(rule("mirror.corp:80").matches(&target("mirror.corp", 80)));
        assert!(rule("203.0.113.7").matches(&target("203.0.113.7", 443)));
        assert!(!rule("203.0.113.7").matches(&target("203.0.113.8", 443)));
        assert!(rule("[2001:db8::1]:8443").matches(&target("[2001:db8::1]", 8443)));
        // A name never matches an IP rule, and an IP never matches a name rule.
        assert!(!rule("203.0.113.7").matches(&target("203.0.113.7.example.com", 443)));
        assert!(!rule("example.com").matches(&target("203.0.113.7", 443)));
    }

    #[test]
    fn a_wildcard_means_the_subdomains_only() {
        let wild = rule("*.example.com");
        assert!(wild.matches(&target("a.example.com", 443)));
        assert!(wild.matches(&target("a.b.example.com", 443)));
        assert!(!wild.matches(&target("example.com", 443)));
        assert!(!wild.matches(&target("badexample.com", 443)));
        assert!(!wild.matches(&target(".example.com", 443)));
    }

    #[test]
    fn bad_entries_are_refused() {
        for bad in [
            "",
            "*",
            "*.com",
            "https://example.com",
            "example.com/path",
            "user@example.com",
            "example.com:0",
            "example.com:99999",
            "example.com:https",
            "exa mple.com",
            "-.com..",
            "a..b",
            "[::1",
            "*.*.example.com",
        ] {
            assert!(Rule::parse(bad).is_err(), "{bad:?} must be refused");
        }
    }

    #[test]
    fn rules_print_as_they_parse() {
        for entry in [
            "example.com:443",
            "*.example.com:8443",
            "[2001:db8::1]:443",
            "10.0.0.1:*",
        ] {
            assert_eq!(rule(entry).to_string(), entry);
        }
        assert_eq!(rule("example.com").to_string(), "example.com:443");
    }

    #[test]
    fn urls_become_rules() {
        assert_eq!(
            Rule::for_url("https://nexus.corp/repository/maven-public/").unwrap(),
            rule("nexus.corp:443")
        );
        assert_eq!(
            Rule::for_url("http://10.1.2.3:8081/x").unwrap(),
            rule("10.1.2.3:8081")
        );
        assert!(Rule::for_url("file:///etc/passwd").is_none());
        assert!(Rule::for_url("not a url").is_none());
    }

    #[test]
    fn sandbox_mirrors_are_allowed() {
        let env = |k: &str, v: &str| (k.to_string(), v.to_string());
        let rules = rules_from_sandbox_env(&[
            env("TINFIRM_SANDBOX_MAVEN_MIRROR", "https://nexus.corp/maven/"),
            env("TINFIRM_SANDBOX_NPM_REGISTRY", "https://nexus.corp/npm/"),
            env(
                "TINFIRM_SANDBOX_REGISTRY_MIRRORS",
                "https://a.corp, http://b.corp:5000",
            ),
            env("OTHER", "https://elsewhere.example"),
        ]);
        assert_eq!(
            rules,
            vec![rule("nexus.corp"), rule("a.corp"), rule("b.corp:5000")]
        );
    }

    #[test]
    fn dangerous_addresses_are_refused_whatever_the_rules_say() {
        let strict = EgressSettings::default();
        let private = EgressSettings {
            allow_private: true,
            ..EgressSettings::default()
        };
        let ip = |s: &str| s.parse::<IpAddr>().unwrap();
        for always in [
            "0.0.0.0",
            "127.0.0.1",
            "169.254.169.254",
            "224.0.0.1",
            "255.255.255.255",
            "::",
            "::1",
            "fe80::1",
            "ff02::1",
            "::ffff:127.0.0.1",
            "::ffff:169.254.169.254",
        ] {
            assert!(refused_address(ip(always), &strict).is_some(), "{always}");
            assert!(refused_address(ip(always), &private).is_some(), "{always}");
        }
        for internal in [
            "10.0.0.1",
            "172.16.5.4",
            "172.31.255.255",
            "192.168.1.1",
            "100.64.0.1",
            "100.127.255.255",
            "fd00::1",
            "::ffff:10.0.0.1",
        ] {
            assert!(
                refused_address(ip(internal), &strict).is_some(),
                "{internal}"
            );
            assert!(
                refused_address(ip(internal), &private).is_none(),
                "{internal}"
            );
        }
        for public in [
            "93.184.216.34",
            "172.32.0.1",
            "100.128.0.1",
            "2606:4700::1111",
        ] {
            assert!(refused_address(ip(public), &strict).is_none(), "{public}");
        }
    }

    #[test]
    fn docker_desktop_is_refused_for_the_egress_proxy() {
        let reason = unsupported_sandbox_host("host.docker.internal").unwrap();
        assert!(reason.contains("Docker Engine on Linux"), "{reason}");
        // The gateway of a Linux network, or an address the operator chose, is fine.
        assert!(unsupported_sandbox_host("172.20.0.1").is_none());
        assert!(unsupported_sandbox_host("127.0.0.1").is_none());
    }

    #[test]
    fn options_make_settings() {
        let env = |k: &str, v: &str| (k.to_string(), v.to_string());
        let allow = vec![
            "registry.corp".to_string(),
            "".to_string(),
            "*.example.corp:8443".to_string(),
        ];
        // Without the proxy, no option applies.
        assert!(
            EgressSettings::from_options(false, &[], false, &[])
                .unwrap()
                .is_none()
        );
        for (allow, private) in [(allow.as_slice(), false), (&[][..], true)] {
            let err = EgressSettings::from_options(false, allow, private, &[]).unwrap_err();
            assert!(err.to_string().contains("need --egress-proxy"), "{err}");
        }
        let settings = EgressSettings::from_options(
            true,
            &allow,
            true,
            &[env("TINFIRM_SANDBOX_NPM_REGISTRY", "https://npm.corp/")],
        )
        .unwrap()
        .unwrap();
        assert_eq!(
            settings.allow,
            vec![
                rule("registry.corp"),
                rule("*.example.corp:8443"),
                rule("npm.corp")
            ]
        );
        assert!(settings.allow_private && !settings.allow_loopback);
        // A bad entry is named, with its reason.
        let err =
            EgressSettings::from_options(true, &["https://x".into()], false, &[]).unwrap_err();
        assert!(err.to_string().starts_with("--egress-allow:"), "{err}");
        // The proxy variables belong to the proxy.
        for key in PROXY_ENV {
            let err = EgressSettings::from_options(true, &[], false, &[env(key, "http://p:1")])
                .unwrap_err();
            assert!(err.to_string().contains(key), "{err}");
        }
        // Without the proxy they stay free (an operator's own proxy).
        assert!(
            EgressSettings::from_options(false, &[], false, &[env("HTTPS_PROXY", "http://p:1")])
                .unwrap()
                .is_none()
        );
    }

    // ----- the proxy, end to end -----------------------------------------------------

    /// A proxy for a test: loopback is reachable, as the allowlist says.
    async fn proxy(allow: &[&str], run_rules: &[&str]) -> (EgressGuard, SocketAddr) {
        let settings = EgressSettings {
            allow: allow.iter().map(|r| rule(r)).collect(),
            allow_private: false,
            allow_loopback: true,
        };
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let guard = EgressProxy::new(settings, &endpoint)
            .start_run(Uuid::new_v4(), run_rules.iter().map(|r| rule(r)).collect())
            .await
            .unwrap();
        let addr = guard_addr(&guard);
        (guard, addr)
    }

    /// Echoes what it reads, once per connection; returns its address.
    async fn echo_server() -> SocketAddr {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            while let Ok((mut stream, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let mut buf = [0u8; 1024];
                    while let Ok(n) = stream.read(&mut buf).await {
                        if n == 0 || stream.write_all(&buf[..n]).await.is_err() {
                            break;
                        }
                    }
                });
            }
        });
        addr
    }

    /// Reads a response head: everything up to and including the blank line.
    async fn read_head(stream: &mut TcpStream) -> String {
        let mut head = Vec::new();
        let mut byte = [0u8; 1];
        while !head.ends_with(b"\r\n\r\n") {
            stream.read_exact(&mut byte).await.unwrap();
            head.push(byte[0]);
        }
        String::from_utf8(head).unwrap()
    }

    async fn send_connect(guard: &EgressGuard, authority: &str) -> (TcpStream, String) {
        send_connect_with(guard, authority, Some(guard.proxy_authorization())).await
    }

    async fn send_connect_with(
        guard: &EgressGuard,
        authority: &str,
        auth: Option<&str>,
    ) -> (TcpStream, String) {
        let mut stream = TcpStream::connect(guard_addr(guard)).await.unwrap();
        let auth = auth.map_or(String::new(), |a| format!("Proxy-Authorization: {a}\r\n"));
        stream
            .write_all(
                format!("CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n{auth}\r\n")
                    .as_bytes(),
            )
            .await
            .unwrap();
        let head = read_head(&mut stream).await;
        (stream, head)
    }

    /// One plain request (with the run's credential) and the whole answer.
    async fn raw_request(guard: &EgressGuard, request_line_and_headers: &str) -> String {
        let mut stream = TcpStream::connect(guard_addr(guard)).await.unwrap();
        let auth = guard.proxy_authorization();
        stream
            .write_all(
                format!("{request_line_and_headers}Proxy-Authorization: {auth}\r\nConnection: close\r\n\r\n")
                    .as_bytes(),
            )
            .await
            .unwrap();
        let mut answer = String::new();
        stream.read_to_string(&mut answer).await.unwrap();
        answer
    }

    #[tokio::test]
    async fn an_allowed_tunnel_carries_bytes_both_ways() {
        let echo = echo_server().await;
        let (guard, _) = proxy(&[&format!("127.0.0.1:{}", echo.port())], &[]).await;
        let (mut stream, head) = send_connect(&guard, &echo.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
        stream.write_all(b"hello through the proxy").await.unwrap();
        let mut buf = [0u8; 23];
        stream.read_exact(&mut buf).await.unwrap();
        assert_eq!(&buf, b"hello through the proxy");
        assert_eq!(guard.counts(), (1, 0));
        assert!(guard.denied().is_empty());
    }

    #[tokio::test]
    async fn a_slot_refuses_everything_until_a_run_binds_it() {
        let echo = echo_server().await;
        let entry = format!("127.0.0.1:{}", echo.port());
        let settings = EgressSettings {
            allow: vec![rule(&entry)],
            allow_private: false,
            allow_loopback: true,
        };
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let slot = EgressProxy::new(settings, &endpoint)
            .start_slot(None)
            .await
            .unwrap();
        // Unbound: not even a host-wide destination.
        let (_stream, head) = send_connect(&slot, &echo.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
        slot.bind(Uuid::new_v4(), Vec::new());
        let (_stream, head) = send_connect(&slot, &echo.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
        // A second bind changes nothing.
        slot.bind(Uuid::new_v4(), vec![rule("attacker.example")]);
        let (_stream, head) = send_connect(&slot, "attacker.example:443").await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
    }

    #[tokio::test]
    async fn a_destination_off_the_list_is_refused_and_remembered() {
        let echo = echo_server().await;
        let (guard, _) = proxy(&["api.anthropic.com"], &[]).await;
        let (_stream, head) = send_connect(&guard, &echo.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
        assert!(
            head.to_ascii_lowercase()
                .contains("x-tinfirm-egress: denied"),
            "{head}"
        );
        let (_stream, head) = send_connect(&guard, "attacker.example:443").await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
        assert_eq!(
            guard.denied(),
            vec![echo.to_string(), "attacker.example:443".to_string()]
        );
        assert_eq!(guard.counts(), (0, 2));
    }

    #[tokio::test]
    async fn a_port_off_the_list_is_refused() {
        let echo = echo_server().await;
        // The host is allowed, on 443 only (the default).
        let (_guard, _) = proxy(&["127.0.0.1"], &[]).await;
        let (_stream, head) = send_connect(&_guard, &echo.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
    }

    #[tokio::test]
    async fn an_allowed_name_that_resolves_inside_is_refused() {
        let echo = echo_server().await;
        // Not the test proxy: loopback is refused, as in production.
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let settings = EgressSettings {
            allow: vec![rule(&format!("localhost:{}", echo.port()))],
            ..EgressSettings::default()
        };
        let guard = EgressProxy::new(settings, &endpoint)
            .start_run(Uuid::new_v4(), Vec::new())
            .await
            .unwrap();
        let (_stream, head) = send_connect(&guard, &format!("localhost:{}", echo.port())).await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
        assert_eq!(guard.denied().len(), 1);
        // And an IP literal in the rules does not open the metadata address.
        let guard = EgressProxy::new(
            EgressSettings {
                allow: vec![rule("169.254.169.254:80")],
                ..EgressSettings::default()
            },
            &endpoint,
        )
        .start_run(Uuid::new_v4(), Vec::new())
        .await
        .unwrap();
        let (_stream, head) = send_connect(&guard, "169.254.169.254:80").await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
    }

    #[tokio::test]
    async fn a_runs_own_rules_apply_to_that_run_only() {
        let echo = echo_server().await;
        let entry = format!("127.0.0.1:{}", echo.port());
        let settings = EgressSettings {
            allow: Vec::new(),
            allow_private: false,
            allow_loopback: true,
        };
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let egress = EgressProxy::new(settings, &endpoint);
        let with = egress
            .start_run(Uuid::new_v4(), vec![rule(&entry)])
            .await
            .unwrap();
        let without = egress.start_run(Uuid::new_v4(), Vec::new()).await.unwrap();
        let (_s, head) = send_connect(&with, &echo.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
        let (_s, head) = send_connect(&without, &echo.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
    }

    #[tokio::test]
    async fn plain_http_is_forwarded_in_origin_form() {
        // An upstream that answers with what it was asked.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let upstream = listener.local_addr().unwrap();
        let seen = Arc::new(Mutex::new(String::new()));
        let seen_by_server = seen.clone();
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut head = Vec::new();
            let mut byte = [0u8; 1];
            while !head.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).await.unwrap();
                head.push(byte[0]);
            }
            *lock(&seen_by_server) = String::from_utf8(head).unwrap();
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\nX-Up: 1\r\n\r\nok")
                .await
                .unwrap();
        });
        let (guard, _) = proxy(&[&format!("127.0.0.1:{}", upstream.port())], &[]).await;
        let answer = raw_request(
            &guard,
            &format!(
                "GET http://{upstream}/repo/a.jar?x=1 HTTP/1.1\r\nHost: {upstream}\r\nProxy-Connection: keep-alive\r\n"
            ),
        )
        .await;
        assert!(answer.starts_with("HTTP/1.1 200"), "{answer}");
        assert!(answer.ends_with("ok"), "{answer}");
        assert!(answer.to_ascii_lowercase().contains("x-up: 1"), "{answer}");
        let request = lock(&seen).to_ascii_lowercase();
        assert!(
            request.starts_with("get /repo/a.jar?x=1 http/1.1"),
            "{request}"
        );
        assert!(!request.contains("proxy-"), "{request}");
        assert_eq!(guard.counts(), (1, 0));

        // The same through a destination that is not allowed.
        let answer = raw_request(
            &guard,
            "GET http://attacker.example/steal HTTP/1.1\r\nHost: attacker.example\r\n",
        )
        .await;
        assert!(answer.starts_with("HTTP/1.1 403"), "{answer}");
        assert_eq!(guard.denied(), vec!["attacker.example:80".to_string()]);
    }

    #[tokio::test]
    async fn a_request_that_is_no_proxy_request_is_refused() {
        let (guard, _) = proxy(&["example.com"], &[]).await;
        let answer = raw_request(&guard, "GET /path HTTP/1.1\r\nHost: example.com\r\n").await;
        assert!(answer.starts_with("HTTP/1.1 400"), "{answer}");
        // An https:// URL in absolute form is no proxy request either: HTTPS is CONNECT.
        let answer = raw_request(
            &guard,
            "GET https://example.com/ HTTP/1.1\r\nHost: example.com\r\n",
        )
        .await;
        assert!(answer.starts_with("HTTP/1.1 400"), "{answer}");
    }

    #[tokio::test]
    async fn dropping_the_guard_ends_the_listener_and_its_tunnels() {
        let echo = echo_server().await;
        let (guard, _) = proxy(&[&format!("127.0.0.1:{}", echo.port())], &[]).await;
        let (mut stream, head) = send_connect(&guard, &echo.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
        let addr = guard_addr(&guard);
        drop(guard);
        // The open tunnel ends...
        let mut buf = [0u8; 1];
        let read = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buf))
            .await
            .expect("the tunnel stays open");
        assert!(matches!(read, Ok(0) | Err(_)), "{read:?}");
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
    async fn a_listener_wants_its_runs_credential() {
        let echo = echo_server().await;
        let entry = format!("127.0.0.1:{}", echo.port());
        let (guard, _) = proxy(&[&entry], &[]).await;
        let (other, _) = proxy(&[&entry], &[]).await;
        for auth in [None, Some("Basic eDp5"), Some(other.proxy_authorization())] {
            let (_s, head) = send_connect_with(&guard, &echo.to_string(), auth).await;
            assert!(head.starts_with("HTTP/1.1 407"), "{auth:?}: {head}");
            assert!(
                head.to_ascii_lowercase()
                    .contains("proxy-authenticate: basic"),
                "{head}"
            );
        }
        // Plain requests too.
        let mut stream = TcpStream::connect(guard_addr(&guard)).await.unwrap();
        stream
            .write_all(
                format!("GET http://{echo}/ HTTP/1.1\r\nHost: {echo}\r\nConnection: close\r\n\r\n")
                    .as_bytes(),
            )
            .await
            .unwrap();
        let mut answer = String::new();
        stream.read_to_string(&mut answer).await.unwrap();
        assert!(answer.starts_with("HTTP/1.1 407"), "{answer}");
        // Nothing reached the destination or the lists.
        assert_eq!(guard.counts(), (0, 0));
        assert!(guard.denied().is_empty());
        // The credential is in the URL and in the JVM options.
        assert!(
            guard
                .url()
                .contains(&format!("tinfirm:{}@", guard.secret()))
        );
        assert!(
            guard
                .java_options()
                .contains(&format!("-Dhttps.proxyPassword={}", guard.secret()))
        );
    }

    #[tokio::test]
    async fn the_proxy_never_connects_to_its_own_address() {
        let listener = TcpListener::bind("127.0.0.2:0").await.unwrap();
        let own = listener.local_addr().unwrap();
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let guard = EgressProxy::new(
            EgressSettings {
                allow: vec![rule(&format!("127.0.0.2:{}", own.port()))],
                allow_private: true,
                allow_loopback: true,
            },
            &endpoint,
        )
        .start_run(Uuid::new_v4(), Vec::new())
        .await
        .unwrap();
        let (_s, head) = send_connect(&guard, &own.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
        assert!(head.contains("x-tinfirm") || head.contains("403"), "{head}");
    }

    #[tokio::test]
    async fn the_proxy_will_not_listen_on_every_address() {
        let endpoint = Endpoint {
            bind: "0.0.0.0:0".parse().unwrap(),
            sandbox_host: "10.0.0.1".into(),
            extra_hosts: Vec::new(),
        };
        let err = EgressProxy::new(EgressSettings::default(), &endpoint)
            .start_run(Uuid::new_v4(), Vec::new())
            .await
            .unwrap_err();
        assert!(err.to_string().contains("not on every address"), "{err}");
    }

    #[tokio::test]
    async fn open_tunnels_are_bounded() {
        let echo = echo_server().await;
        let (guard, _) = proxy(&[&format!("127.0.0.1:{}", echo.port())], &[]).await;
        let mut held = Vec::new();
        for _ in 0..MAX_TUNNELS {
            let (stream, head) = send_connect(&guard, &echo.to_string()).await;
            assert!(head.starts_with("HTTP/1.1 200"), "{head}");
            held.push(stream);
        }
        let (_s, head) = send_connect(&guard, &echo.to_string()).await;
        assert!(head.starts_with("HTTP/1.1 503"), "{head}");
        // A closed tunnel frees its place.
        held.pop();
        let mut freed = false;
        for _ in 0..50 {
            let (_s, head) = send_connect(&guard, &echo.to_string()).await;
            if head.starts_with("HTTP/1.1 200") {
                freed = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert!(freed, "a closed tunnel did not free its place");
    }

    #[tokio::test]
    async fn an_ipv4_mapped_target_is_the_ipv4_address() {
        let echo = echo_server().await;
        let (guard, _) = proxy(&[&format!("127.0.0.1:{}", echo.port())], &[]).await;
        let (_s, head) = send_connect(&guard, &format!("[::ffff:127.0.0.1]:{}", echo.port())).await;
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
        // A rule for another address does not open it.
        let (guard, _) = proxy(&[&format!("127.0.0.3:{}", echo.port())], &[]).await;
        let (_s, head) = send_connect(&guard, &format!("[::ffff:127.0.0.1]:{}", echo.port())).await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
    }

    #[tokio::test]
    async fn a_name_goes_on_to_an_address_that_is_allowed() {
        // `localhost` resolves to ::1 and 127.0.0.1 (in either order); loopback is allowed
        // by the test settings, so one of them connects whichever comes first.
        let echo = echo_server().await;
        let (guard, _) = proxy(&[&format!("localhost:{}", echo.port())], &[]).await;
        let (_s, head) = send_connect(&guard, &format!("localhost:{}", echo.port())).await;
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
    }

    #[test]
    fn cloud_metadata_is_refused_even_with_private_addresses_allowed() {
        let private = EgressSettings {
            allow_private: true,
            ..EgressSettings::default()
        };
        for metadata in METADATA_ADDRESSES {
            assert!(
                refused_address(metadata.parse().unwrap(), &private).is_some(),
                "{metadata}"
            );
        }
        // The rest of the private ranges stay open to the operator who allows them.
        for ok in ["fd00:ec2::255", "100.100.100.201", "fd12:3456::1"] {
            assert!(
                refused_address(ok.parse().unwrap(), &private).is_none(),
                "{ok}"
            );
        }
    }

    #[tokio::test]
    async fn plain_http_goes_to_the_host_that_was_checked() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let upstream = listener.local_addr().unwrap();
        let seen = Arc::new(Mutex::new(String::new()));
        let seen_by_server = seen.clone();
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut head = Vec::new();
            let mut byte = [0u8; 1];
            while !head.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).await.unwrap();
                head.push(byte[0]);
            }
            *lock(&seen_by_server) = String::from_utf8(head).unwrap();
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .await
                .unwrap();
        });
        let (guard, _) = proxy(&[&format!("127.0.0.1:{}", upstream.port())], &[]).await;
        // The URL names the allowed host; the header names another virtual host.
        let answer = raw_request(
            &guard,
            &format!("GET http://{upstream}/ HTTP/1.1\r\nHost: attacker.example\r\n"),
        )
        .await;
        assert!(answer.starts_with("HTTP/1.1 200"), "{answer}");
        let request = lock(&seen).to_ascii_lowercase();
        assert!(request.contains(&format!("host: {upstream}")), "{request}");
        assert!(!request.contains("attacker.example"), "{request}");
    }

    /// An address of this machine that is not loopback, when there is one (the one that the
    /// default route would use; no packet is sent).
    fn own_non_loopback_address() -> Option<IpAddr> {
        let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
        socket.connect("192.0.2.1:9").ok()?;
        let ip = socket.local_addr().ok()?.ip();
        (!ip.is_loopback() && !ip.is_unspecified()).then_some(ip)
    }

    #[tokio::test]
    async fn the_machines_other_addresses_are_refused_too() {
        let Some(own) = own_non_loopback_address() else {
            eprintln!("no non-loopback address on this machine; nothing to check");
            return;
        };
        assert!(is_local_address(own));
        assert!(!is_local_address("203.0.113.77".parse().unwrap()));
        // Private addresses are allowed and loopback is not: `own` is as good as any other
        // address of a runner host, and the proxy must not connect to it.
        let listener = TcpListener::bind(SocketAddr::new(own, 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let endpoint = Endpoint {
            bind: "127.0.0.2:0".parse().unwrap(),
            sandbox_host: "127.0.0.2".into(),
            extra_hosts: Vec::new(),
        };
        let settings = EgressSettings {
            allow: vec![rule(&SocketAddr::new(own, port).to_string())],
            allow_private: true,
            allow_loopback: false,
        };
        let guard = EgressProxy::new(settings, &endpoint)
            .start_run(Uuid::new_v4(), Vec::new())
            .await
            .unwrap();
        let (_s, head) = send_connect(&guard, &SocketAddr::new(own, port).to_string()).await;
        assert!(head.starts_with("HTTP/1.1 403"), "{head}");
        assert!(head.contains("x-tinfirm") || head.contains("403"), "{head}");
    }

    #[test]
    fn the_local_use_nat64_prefix_is_refused() {
        let private = EgressSettings {
            allow_private: true,
            ..EgressSettings::default()
        };
        for bad in [
            "64:ff9b:1::a00:1",
            "64:ff9b:1:ffff::1",
            "64:ff9b:1::8.8.8.8",
        ] {
            assert!(
                refused_address(bad.parse().unwrap(), &private).is_some(),
                "{bad}"
            );
        }
    }

    #[test]
    fn documentation_and_protocol_ranges_are_refused() {
        let private = EgressSettings {
            allow_private: true,
            ..EgressSettings::default()
        };
        for bad in [
            "192.0.2.1",
            "198.51.100.7",
            "203.0.113.9",
            "192.88.99.1",
            "192.0.0.8",
            "2001:db8::1",
            "2001:db8:ffff::1",
            "100::1",
            "2001::1",
            "2001:1::1",
            "2001:1ff::1",
        ] {
            assert!(
                refused_address(bad.parse().unwrap(), &private).is_some(),
                "{bad}"
            );
        }
        // fec0::/10 is private, so `--egress-allow-private` opens it.
        assert!(refused_address("fec0::1".parse().unwrap(), &EgressSettings::default()).is_some());
        assert!(refused_address("fec0::1".parse().unwrap(), &private).is_none());
        // Neighbours of the reserved ranges stay public.
        for ok in [
            "192.0.3.1",
            "198.51.101.1",
            "203.0.114.1",
            "2001:200::1",
            "2001:4860:4860::8888",
        ] {
            assert!(
                refused_address(ok.parse().unwrap(), &private).is_none(),
                "{ok}"
            );
        }
    }

    #[test]
    fn translated_and_reserved_addresses_are_refused() {
        let strict = EgressSettings {
            allow_private: true,
            ..EgressSettings::default()
        };
        for bad in [
            "198.18.0.1",
            "198.19.255.255",
            "240.0.0.1",
            "2002:7f00:1::",
            "2002:a00:1::",
            "64:ff9b::7f00:1",
            "64:ff9b::a00:1",
        ] {
            assert!(
                refused_address(bad.parse().unwrap(), &strict).is_some(),
                "{bad}"
            );
        }
        assert!(refused_address("198.20.0.1".parse().unwrap(), &strict).is_none());
    }

    #[tokio::test]
    async fn the_environment_points_tools_at_the_proxy_and_spares_the_hosts_listeners() {
        let (guard, _) = proxy(&[], &[]).await;
        let env = guard.env();
        let get = |k: &str| {
            env.iter()
                .find(|(key, _)| key == k)
                .map(|(_, v)| v.as_str())
        };
        let url = guard.url();
        assert_eq!(get("HTTPS_PROXY"), Some(url.as_str()));
        assert_eq!(get("https_proxy"), Some(url.as_str()));
        assert_eq!(get("HTTP_PROXY"), Some(url.as_str()));
        assert_eq!(get("NO_PROXY"), Some("localhost,127.0.0.1,::1,127.0.0.2"));
        let java = get("JAVA_TOOL_OPTIONS").unwrap();
        assert!(
            java.contains(&format!("-Dhttps.proxyPort={}", guard.port)),
            "{java}"
        );
    }
}
