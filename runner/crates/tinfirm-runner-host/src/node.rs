//! `vm-driver node`: the VM driver for sandbox nodes (ADR-0112 decision 4, "Who speaks with
//! it"). The runner host speaks the driver protocol with it on stdin and stdout, as with every
//! driver (crate::vm); it relays `verify`, `create`, `describe`, `delete` and `list` to the node
//! agent's listener of each of its nodes, over the agent's wire (JSON-RPC 2.0, one message per
//! line, after the wire's `hello`; `runner/crates/tinfirm-node-agent/README.md`). It is in every
//! edition (ADR-0114), and lists no paid feature.
//!
//! Its configuration (`--config`, or `TINFIRM_NODE_DRIVER_CONFIG`) names its nodes, each with
//! the templates it offers, and either an address with the node's pinned certificate and this
//! host's client certificate and key (a separated node), or the listener's local socket (a
//! single machine):
//!
//! ```json
//! {
//!   "nodes": [
//!     {
//!       "name": "node-1",
//!       "address": "10.0.0.20:7700",
//!       "nodeSha256": "64 hex digits",
//!       "certificate": "/etc/tinfirm/node-driver/client.crt",
//!       "key": "/etc/tinfirm/node-driver/client.key",
//!       "templates": ["sandbox-4c8g"]
//!     }
//!   ],
//!   "peerBuild": "warn"
//! }
//! ```
//!
//! Several nodes behind one driver (ADR-0112, "To specify"):
//!
//! - **Placement.** A new VM goes to a node that reports itself hardened before one that does
//!   not, then to the node with the most free slots, by each node's `verify`, asked at the
//!   create, among the nodes that offer the template, answer and report no problem; the first
//!   in the configuration's order on a tie. It spreads the runs, and the count is the agent's
//!   own, which no other host's create can make stale for long.
//! - **Hardening** (ADR-0113, decision 5). Each node's `verify` carries its hardening mark,
//!   `hardening: {hardened, lines}`. The driver's `verify` answers one mark for the template,
//!   `hardened`: `yes` when every node that offers the template answered `yes`; `no` when any
//!   of them answered `no`, or answered without the mark (an agent older than this host); left
//!   out, for "not known", when none of them said `no` but one did not answer. `notHardened`
//!   names each line that does not hold, as `node <name>: <line>: <found>`, and why a node
//!   counts as not hardened or not known. The driver reads `hardened` and `holds`, never the
//!   lines' ids, so a newer agent can add a line ([`hardening_of`]).
//! - **Capacity.** `verify`'s `maxVms` is the sum of the free slots of the nodes that answer
//!   with no problem. A node that does not answer is not counted, and `checked` names it: a
//!   host with one node down still starts. A node that reports a problem, or a template that no
//!   node offers, is a problem, which refuses the host.
//! - **Ids.** The driver's id of a VM is `<node>:<the agent's id>`: it names the node, so
//!   `describe` and `delete` reach the right one after the driver was started again.
//! - **A node down.** `describe` or `delete` of one of its VMs is an error, never `gone`.
//!   `list` lists the VMs of the nodes that answer, and writes the others to stderr (the host's
//!   log). The host uses `list` for one thing: deleting the listed VMs it does not hold. A node
//!   that is not listed then keeps its VMs until it answers again; none is deleted or forgotten.
//! - **A VMM that died.** The agent pushes `vmFailed`; the driver protocol has no
//!   notifications, and the host polls `describe`. The driver keeps the pushed ids and answers
//!   `describe` of such a VM `failed` at once.
//! - **Reconnects.** A connection that was lost (an agent that was restarted) is made again at
//!   the next call; a call that cannot have changed anything (all but `create`) is tried once
//!   more on a new connection.
//! - **Builds.** The wire's `hello` sends this host's build id ([`crate::images::build_id`])
//!   and reads the agent's. Builds of another release are a warning by default (`peerBuild`),
//!   as on the wire; the images' digest after `+` is not compared.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::net::{IpAddr, ToSocketAddrs};
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, bail};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::sync::{mpsc, oneshot};
use tokio_util::sync::CancellationToken;

use crate::vm::{DRIVER_PROTOCOL, HOST_LABEL};

/// `--vm-driver node`.
pub const BUILTIN_NODE: &str = "node";
/// Where the driver's configuration is when neither `--config` nor
/// `TINFIRM_NODE_DRIVER_CONFIG` names it.
pub const DEFAULT_CONFIG: &str = "/etc/tinfirm/node-driver.json";

/// The configuration `vm-driver node` reads without `--config`: `TINFIRM_NODE_DRIVER_CONFIG`,
/// or [`DEFAULT_CONFIG`]. `--vm-driver node` names it to the driver, and the host reads the
/// same file for its level of isolation (crate::isolation).
pub fn config_path() -> PathBuf {
    std::env::var_os("TINFIRM_NODE_DRIVER_CONFIG")
        .filter(|v| !v.is_empty())
        .map_or_else(|| PathBuf::from(DEFAULT_CONFIG), PathBuf::from)
}
/// The version of the agent's wire this driver speaks.
pub const WIRE: u32 = 1;
/// The name in the node's certificate, which the driver sends; nothing resolves it.
pub const SERVER_NAME: &str = "tinfirm-node-agent";
/// The longest line on either side of the wire, and of the driver protocol.
const MAX_LINE: u64 = 1024 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// A make may take the helper up to its own 60 s, and more for the listener's spool.
const CALL_TIMEOUT: Duration = Duration::from_secs(150);
/// The most nodes behind one driver.
pub const MAX_NODES: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BuildPolicy {
    /// A node agent of another release is used, with a warning in the host's log.
    #[default]
    Warn,
    /// A node agent of another release is refused.
    Refuse,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ConfigFile {
    nodes: Vec<NodeFile>,
    #[serde(default)]
    peer_build: BuildPolicy,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct NodeFile {
    name: String,
    #[serde(default)]
    address: Option<String>,
    #[serde(default)]
    node_sha256: Option<String>,
    #[serde(default)]
    certificate: Option<PathBuf>,
    #[serde(default)]
    key: Option<PathBuf>,
    #[serde(default)]
    socket: Option<PathBuf>,
    templates: Vec<String>,
}

/// How the driver reaches a node's listener.
#[derive(Clone)]
pub enum Target {
    /// A separated node: TLS to `address` (`host:port`), the node's certificate pinned by its
    /// SHA-256, and this host's client certificate and key (DER).
    Tls {
        address: String,
        pin: [u8; 32],
        cert: Vec<u8>,
        key: Vec<u8>,
    },
    /// A single machine: the listener's local socket.
    Socket(PathBuf),
}

impl std::fmt::Debug for Target {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Target::Tls { address, .. } => write!(f, "Tls({address})"),
            Target::Socket(path) => write!(f, "Socket({})", path.display()),
        }
    }
}

/// One node of the configuration.
#[derive(Debug, Clone)]
pub struct NodeSpec {
    pub name: String,
    pub target: Target,
    pub templates: BTreeSet<String>,
}

/// The driver's configuration.
#[derive(Debug, Clone)]
pub struct NodeConfig {
    pub nodes: Vec<NodeSpec>,
    pub peer_build: BuildPolicy,
}

/// What a runner host with this driver can see of its sandboxes' isolation (ADR-0113 decision
/// 3); `crate::isolation` names the levels.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Isolation {
    /// Every node is reached over a local socket: the VMs run on this host's machine, each in
    /// a jail of its own (`vm-jail`).
    VmJail,
    /// Every node is reached over TLS at an address that is not this host's own: the VMs run on
    /// other machines (`machine`).
    Machine,
}

impl Isolation {
    pub fn as_str(self) -> &'static str {
        match self {
            Isolation::VmJail => "vm-jail",
            Isolation::Machine => "machine",
        }
    }
}

fn name_ok(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 32
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

fn template_ok(name: &str) -> bool {
    name.bytes()
        .next()
        .is_some_and(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
        && name.len() <= 63
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

/// The pin of a certificate: 64 hex digits.
pub fn parse_pin(text: &str) -> Result<[u8; 32]> {
    let text = text.trim();
    if text.len() != 64 || !text.bytes().all(|b| b.is_ascii_hexdigit()) {
        bail!("a pin is the SHA-256 of a certificate, in 64 hex digits");
    }
    let mut pin = [0u8; 32];
    for (i, byte) in pin.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&text[2 * i..2 * i + 2], 16)?;
    }
    Ok(pin)
}

/// Whether a key file's facts are a key that only its owner, this process's user, can read.
pub fn key_file_ok(owner: u32, mode: u32, own: u32) -> bool {
    owner == own && mode & 0o077 == 0
}

fn own_uid() -> Result<u32> {
    Ok(std::fs::metadata("/proc/self")
        .context("cannot read /proc/self")?
        .uid())
}

/// Reads a key file that only this process's user can read.
fn read_key(path: &Path) -> Result<Vec<u8>> {
    let meta =
        std::fs::metadata(path).with_context(|| format!("cannot read {}", path.display()))?;
    if !key_file_ok(meta.uid(), meta.mode(), own_uid()?) {
        bail!(
            "the client key {} must belong to this host's user and be readable by it alone (0600)",
            path.display()
        );
    }
    read_small(path)
}

fn read_small(path: &Path) -> Result<Vec<u8>> {
    let file =
        std::fs::File::open(path).with_context(|| format!("cannot read {}", path.display()))?;
    let mut bytes = Vec::new();
    std::io::Read::read_to_end(&mut std::io::Read::take(file, 64 * 1024 + 1), &mut bytes)
        .with_context(|| format!("cannot read {}", path.display()))?;
    if bytes.is_empty() || bytes.len() > 64 * 1024 {
        bail!("{} is empty or over 64 KiB", path.display());
    }
    Ok(bytes)
}

impl NodeConfig {
    pub fn load(path: &Path) -> Result<NodeConfig> {
        let text = read_small(path)?;
        let file: ConfigFile = serde_json::from_slice(&text).with_context(|| {
            format!(
                "{} is not a valid node driver configuration",
                path.display()
            )
        })?;
        NodeConfig::from_file(file, &read_key, &read_small)
    }

    fn from_file(
        file: ConfigFile,
        key: &dyn Fn(&Path) -> Result<Vec<u8>>,
        cert: &dyn Fn(&Path) -> Result<Vec<u8>>,
    ) -> Result<NodeConfig> {
        if file.nodes.is_empty() || file.nodes.len() > MAX_NODES {
            bail!("the configuration names 1 to {MAX_NODES} nodes");
        }
        let mut names = HashSet::new();
        let mut nodes = Vec::new();
        for node in file.nodes {
            let name = node.name.clone();
            if !name_ok(&name) || !names.insert(name.clone()) {
                bail!("node `{name}`: a name of 1 to 32 bytes of a-z, 0-9 and -, each once");
            }
            if node.templates.is_empty() || !node.templates.iter().all(|t| template_ok(t)) {
                bail!("node `{name}`: templates are 1 or more names of a-z, 0-9 and -");
            }
            let target = match (node.socket, node.address) {
                (Some(socket), None) => {
                    if node.node_sha256.is_some()
                        || node.certificate.is_some()
                        || node.key.is_some()
                    {
                        bail!("node `{name}`: a socket takes no certificate, key or pin");
                    }
                    if !socket.is_absolute() {
                        bail!("node `{name}`: the socket's path is not absolute");
                    }
                    Target::Socket(socket)
                }
                (None, Some(address)) => {
                    let (host, port) = address
                        .rsplit_once(':')
                        .with_context(|| format!("node `{name}`: the address is HOST:PORT"))?;
                    if host.is_empty() || port.parse::<u16>().map_or(true, |p| p == 0) {
                        bail!("node `{name}`: the address is HOST:PORT");
                    }
                    let pin = parse_pin(
                        node.node_sha256
                            .as_deref()
                            .with_context(|| format!("node `{name}`: nodeSha256 is missing"))?,
                    )
                    .with_context(|| format!("node `{name}`"))?;
                    let cert_path = node
                        .certificate
                        .with_context(|| format!("node `{name}`: certificate is missing"))?;
                    let key_path = node
                        .key
                        .with_context(|| format!("node `{name}`: key is missing"))?;
                    Target::Tls {
                        address,
                        pin,
                        cert: cert(&cert_path).with_context(|| format!("node `{name}`"))?,
                        key: key(&key_path).with_context(|| format!("node `{name}`"))?,
                    }
                }
                _ => bail!("node `{name}`: either `socket`, or `address` with its certificates"),
            };
            nodes.push(NodeSpec {
                name,
                target,
                templates: node.templates.into_iter().collect(),
            });
        }
        let config = NodeConfig {
            nodes,
            peer_build: file.peer_build,
        };
        let sockets = config
            .nodes
            .iter()
            .filter(|n| matches!(n.target, Target::Socket(_)))
            .count();
        if sockets != 0 && sockets != config.nodes.len() {
            bail!(
                "the nodes are reached over sockets and over TLS at once: a runner host has one isolation level, so its nodes are all local (sockets) or all other machines (TLS)"
            );
        }
        Ok(config)
    }

    /// What the host can see of its sandboxes' isolation, `own` telling whether a TLS node's
    /// host part (an address or a name) is this host's own: every node over a local socket is
    /// [`Isolation::VmJail`]; every node over TLS at an address that is not this host's own is
    /// [`Isolation::Machine`]; anything else is refused.
    pub fn isolation_with(&self, own: impl Fn(&str) -> bool) -> Result<Isolation> {
        let mut sockets = 0;
        for node in &self.nodes {
            match &node.target {
                Target::Socket(_) => sockets += 1,
                Target::Tls { address, .. } => {
                    let host = address
                        .rsplit_once(':')
                        .map_or(address.as_str(), |(h, _)| h)
                        .trim_start_matches('[')
                        .trim_end_matches(']');
                    if own(host) {
                        bail!(
                            "node `{}` is reached over TLS at this host's own address: reach a node on this machine over its local socket",
                            node.name
                        );
                    }
                }
            }
        }
        match sockets {
            0 => Ok(Isolation::Machine),
            n if n == self.nodes.len() => Ok(Isolation::VmJail),
            _ => bail!("the nodes are reached over sockets and over TLS at once"),
        }
    }

    /// [`NodeConfig::isolation_with`] on this machine: a host part is this host's own when it
    /// is loopback, unspecified, or an address this machine can bind, after the name is
    /// resolved.
    pub fn isolation(&self) -> Result<Isolation> {
        self.isolation_with(is_own_host)
    }
}

/// Whether `host` (an address or a name) is this machine's own: loopback, unspecified, or
/// one of its addresses (one this machine can bind a socket to).
pub fn is_own_host(host: &str) -> bool {
    let addresses: Vec<IpAddr> = match host.parse::<IpAddr>() {
        Ok(ip) => vec![ip],
        Err(_) => match (host, 0).to_socket_addrs() {
            Ok(found) => found.map(|a| a.ip()).collect(),
            // A name that does not resolve here is not this host's.
            Err(_) => return false,
        },
    };
    addresses.iter().any(|ip| {
        ip.is_loopback() || ip.is_unspecified() || std::net::UdpSocket::bind((*ip, 0)).is_ok()
    })
}

// ----- TLS ---------------------------------------------------------------------------------

/// The SHA-256 of a certificate's DER, in lower-case hex: its pin.
fn pin_hex(cert: &[u8]) -> String {
    ring::digest::digest(&ring::digest::SHA256, cert)
        .as_ref()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// `node identity`: writes a new key (PKCS #8 DER, mode 0600) to `key` and its self-signed
/// certificate (DER, mode 0644) to `cert`, and answers the certificate's pin. Neither file may
/// exist, and neither is followed if it is a symbolic link; where the certificate cannot be
/// written, the new key is removed again. The certificate names [`SERVER_NAME`], as
/// `tinfirm-node-agent identity` makes it: its files and this one's are the same kind, and
/// either binary reads the other's (only the pin is checked, never the name).
pub fn write_identity(cert: &Path, key: &Path) -> Result<String> {
    write_identity_with(cert, key, &|file, bytes| {
        use std::io::Write;
        file.write_all(bytes)?;
        file.sync_all()
    })
}

/// [`write_identity`], with `fill` writing a new file's bytes (the tests make it fail). On any
/// failure, every file this call created is removed again, and only those: a file that was there
/// before is never touched, so a retry starts clean.
fn write_identity_with(
    cert: &Path,
    key: &Path,
    fill: &dyn Fn(&mut std::fs::File, &[u8]) -> std::io::Result<()>,
) -> Result<String> {
    use std::os::unix::fs::OpenOptionsExt;
    for path in [cert, key] {
        if path.symlink_metadata().is_ok() {
            bail!(
                "{} exists: an identity is never overwritten",
                path.display()
            );
        }
    }
    let certified = rcgen::generate_simple_self_signed(vec![SERVER_NAME.to_string()])
        .context("cannot create a certificate")?;
    let cert_der = certified.cert.der().to_vec();
    let key_der = certified.signing_key.serialize_der();
    // Creates `path`, which must not exist, and fills it; a file it created and could not fill
    // is removed.
    let write = |path: &Path, bytes: &[u8], mode: u32| -> Result<()> {
        let mut file = std::fs::File::options()
            .write(true)
            .create_new(true)
            .mode(mode)
            .custom_flags(libc::O_NOFOLLOW)
            .open(path)
            .with_context(|| format!("cannot create {}", path.display()))?;
        if let Err(e) = fill(&mut file, bytes) {
            drop(file);
            let _ = std::fs::remove_file(path);
            return Err(e).with_context(|| format!("cannot write {}", path.display()));
        }
        Ok(())
    };
    write(key, &key_der, 0o600)?;
    if let Err(e) = write(cert, &cert_der, 0o644) {
        let _ = std::fs::remove_file(key);
        return Err(e);
    }
    Ok(pin_hex(&cert_der))
}

/// `node fingerprint`: the pin of a certificate file (DER, at most 64 KiB, not empty).
pub fn fingerprint(cert: &Path) -> Result<String> {
    Ok(pin_hex(&read_small(cert)?))
}

fn tls_config(pin: [u8; 32], cert: Vec<u8>, key: Vec<u8>) -> Result<Arc<rustls::ClientConfig>> {
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let verifier = Arc::new(PinnedNode {
        pin,
        provider: provider.clone(),
    });
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .context("TLS 1.3 is not available")?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_client_auth_cert(
            vec![CertificateDer::from(cert)],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
        )
        .context("cannot use this host's client certificate")?;
    Ok(Arc::new(config))
}

/// The node's certificate, by its SHA-256.
#[derive(Debug)]
struct PinnedNode {
    pin: [u8; 32],
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl rustls::client::danger::ServerCertVerifier for PinnedNode {
    fn verify_server_cert(
        &self,
        end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        let digest = ring::digest::digest(&ring::digest::SHA256, end_entity);
        if digest.as_ref() == self.pin {
            Ok(rustls::client::danger::ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "the node's certificate does not match its pin".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

// ----- one node's connection -----------------------------------------------------------------

/// Reads one line of at most [`MAX_LINE`] bytes; `None` at the end or past the bound.
async fn read_line<R: AsyncBufReadExt + Unpin>(reader: &mut R) -> Option<Vec<u8>> {
    let mut line = Vec::new();
    match (&mut *reader)
        .take(MAX_LINE + 1)
        .read_until(b'\n', &mut line)
        .await
    {
        Ok(0) | Err(_) => None,
        Ok(_) if line.last() == Some(&b'\n') => {
            line.pop();
            Some(line)
        }
        Ok(_) if line.len() as u64 > MAX_LINE => None,
        Ok(_) => Some(line),
    }
}

/// Why a call to a node failed.
#[derive(Debug)]
enum CallError {
    /// No connection, or it was lost before the answer: the call may be tried again.
    Lost(String),
    /// The node answered with an error, or out of form.
    Answer(String),
}

impl std::fmt::Display for CallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CallError::Lost(why) | CallError::Answer(why) => f.write_str(why),
        }
    }
}

type Pending = Arc<Mutex<HashMap<u64, oneshot::Sender<Result<Value, String>>>>>;

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

/// A connection to a node's listener, after its `hello`.
struct Conn {
    lines: mpsc::Sender<Vec<u8>>,
    pending: Pending,
    lost: CancellationToken,
    next: AtomicU64,
}

impl Conn {
    fn open<S>(stream: S, failed: Arc<Mutex<HashSet<String>>>, node: String) -> Conn
    where
        S: AsyncRead + AsyncWrite + Send + 'static,
    {
        let (read, mut write) = tokio::io::split(stream);
        let pending: Pending = Arc::default();
        let lost = CancellationToken::new();
        let (lines, mut rx) = mpsc::channel::<Vec<u8>>(64);
        {
            let lost = lost.clone();
            tokio::spawn(async move {
                while let Some(line) = rx.recv().await {
                    if write.write_all(&line).await.is_err() || write.flush().await.is_err() {
                        break;
                    }
                }
                lost.cancel();
            });
        }
        {
            let (pending, lost) = (pending.clone(), lost.clone());
            tokio::spawn(async move {
                let mut reader = BufReader::new(read);
                loop {
                    let line = tokio::select! {
                        line = read_line(&mut reader) => line,
                        _ = lost.cancelled() => None,
                    };
                    let Some(line) = line else { break };
                    let Ok(message) = serde_json::from_slice::<Value>(&line) else {
                        continue;
                    };
                    if let Some(id) = message.get("id").and_then(Value::as_u64) {
                        let Some(tx) = lock(&pending).remove(&id) else {
                            continue;
                        };
                        let answer = match (message.get("result"), message.get("error")) {
                            (Some(result), None) => Ok(result.clone()),
                            (_, Some(error)) => Err(error
                                .get("message")
                                .and_then(Value::as_str)
                                .unwrap_or("the node failed without a message")
                                .to_string()),
                            _ => Err("an answer with neither result nor error".to_string()),
                        };
                        let _ = tx.send(answer);
                    } else if message.get("method").and_then(Value::as_str) == Some("vmFailed")
                        && let Some(vm) = message["params"]["id"].as_str()
                    {
                        eprintln!("node {node}: the VMM of {vm} died");
                        lock(&failed).insert(vm.to_string());
                    }
                }
                lost.cancel();
                // Their senders go: every waiting call learns that the connection was lost.
                lock(&pending).clear();
            });
        }
        Conn {
            lines,
            pending,
            lost,
            next: AtomicU64::new(1),
        }
    }

    async fn call(&self, method: &str, params: Value) -> Result<Value, CallError> {
        if self.lost.is_cancelled() {
            return Err(CallError::Lost("the connection was lost".into()));
        }
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        lock(&self.pending).insert(id, tx);
        let mut line = serde_json::to_vec(&json!({
            "jsonrpc": "2.0", "id": id, "method": method, "params": params,
        }))
        .expect("requests serialize");
        line.push(b'\n');
        if self.lines.send(line).await.is_err() {
            lock(&self.pending).remove(&id);
            return Err(CallError::Lost("the connection was lost".into()));
        }
        match tokio::time::timeout(CALL_TIMEOUT, rx).await {
            Ok(Ok(Ok(value))) => Ok(value),
            Ok(Ok(Err(message))) => Err(CallError::Answer(message)),
            Ok(Err(_)) => Err(CallError::Lost(
                "the connection was lost before the answer".into(),
            )),
            Err(_) => {
                lock(&self.pending).remove(&id);
                Err(CallError::Lost(format!(
                    "no answer within {} s",
                    CALL_TIMEOUT.as_secs()
                )))
            }
        }
    }
}

/// The release of a build id: what comes before its build metadata (`+…`). The node agent
/// compares the same way (`runner/crates/tinfirm-node-agent/src/wire.rs`, `release_of`).
pub fn release_of(build: &str) -> &str {
    build.split('+').next().unwrap_or(build)
}

/// A node, and its connection once made.
struct Node {
    spec: NodeSpec,
    policy: BuildPolicy,
    tls: Option<Arc<rustls::ClientConfig>>,
    conn: tokio::sync::Mutex<Option<Arc<Conn>>>,
    /// The VMs (the agent's ids) whose VMM the agent reported dead.
    failed: Arc<Mutex<HashSet<String>>>,
}

impl Node {
    fn new(spec: NodeSpec, policy: BuildPolicy) -> Result<Node> {
        let tls = match &spec.target {
            Target::Tls { pin, cert, key, .. } => {
                Some(tls_config(*pin, cert.clone(), key.clone())?)
            }
            Target::Socket(_) => None,
        };
        Ok(Node {
            spec,
            policy,
            tls,
            conn: tokio::sync::Mutex::new(None),
            failed: Arc::default(),
        })
    }

    fn name(&self) -> &str {
        &self.spec.name
    }

    async fn connect(&self) -> Result<Conn, CallError> {
        let lost = |why: String| CallError::Lost(format!("cannot reach the node: {why}"));
        let (failed, node) = (self.failed.clone(), self.spec.name.clone());
        let conn = match &self.spec.target {
            Target::Socket(path) => {
                let stream =
                    tokio::time::timeout(CONNECT_TIMEOUT, tokio::net::UnixStream::connect(path))
                        .await
                        .map_err(|_| lost("no connection in time".into()))?
                        .map_err(|e| lost(e.kind().to_string()))?;
                Conn::open(stream, failed, node)
            }
            Target::Tls { address, .. } => {
                let tls = self.tls.clone().expect("a TLS node has its configuration");
                let connect = async {
                    let tcp = tokio::net::TcpStream::connect(address.as_str())
                        .await
                        .map_err(|e| lost(e.kind().to_string()))?;
                    let name = rustls::pki_types::ServerName::try_from(SERVER_NAME)
                        .expect("a valid DNS name");
                    tokio_rustls::TlsConnector::from(tls)
                        .connect(name, tcp)
                        .await
                        .map_err(|e| lost(format!("TLS: {e}")))
                };
                let stream = tokio::time::timeout(CONNECT_TIMEOUT, connect)
                    .await
                    .map_err(|_| lost("no connection in time".into()))??;
                Conn::open(stream, failed, node)
            }
        };
        let own = crate::images::build_id();
        let hello = conn
            .call("hello", json!({ "wire": WIRE, "build": own }))
            .await
            .map_err(|e| match e {
                CallError::Answer(why) => CallError::Lost(format!("the node refused hello: {why}")),
                lost => lost,
            })?;
        if hello["wire"].as_u64() != Some(u64::from(WIRE)) {
            return Err(CallError::Lost(format!(
                "the node speaks wire {}, this driver {WIRE}",
                hello["wire"]
            )));
        }
        let agent = hello["build"].as_str().unwrap_or("?");
        if release_of(agent) != release_of(own) {
            let shown: String = agent
                .chars()
                .take(64)
                .filter(|c| c.is_ascii_graphic())
                .collect();
            match self.policy {
                BuildPolicy::Refuse => {
                    return Err(CallError::Lost(format!(
                        "the node agent is {shown} and this host {own}: upgrade them together"
                    )));
                }
                BuildPolicy::Warn => eprintln!(
                    "node {}: the node agent is {shown} and this host {own}: upgrade them together",
                    self.name()
                ),
            }
        }
        Ok(conn)
    }

    /// The connection, made again if it was lost.
    async fn conn(&self) -> Result<Arc<Conn>, CallError> {
        let mut slot = self.conn.lock().await;
        if let Some(conn) = slot.as_ref()
            && !conn.lost.is_cancelled()
        {
            return Ok(conn.clone());
        }
        *slot = None;
        let conn = Arc::new(self.connect().await?);
        *slot = Some(conn.clone());
        Ok(conn)
    }

    /// One call; with `again`, a call on a connection that was lost is tried once more on a
    /// new one.
    async fn call(&self, method: &str, params: Value, again: bool) -> Result<Value> {
        let tries = if again { 2 } else { 1 };
        let mut last = String::new();
        for _ in 0..tries {
            match self.conn().await {
                Ok(conn) => match conn.call(method, params.clone()).await {
                    Ok(value) => return Ok(value),
                    Err(CallError::Answer(why)) => bail!("node {}: {why}", self.name()),
                    Err(CallError::Lost(why)) => last = why,
                },
                Err(e) => last = e.to_string(),
            }
        }
        bail!("node {}: {last}", self.name())
    }
}

// ----- the driver ------------------------------------------------------------------------------

/// The `node` driver: its nodes, in the configuration's order.
pub struct NodeDriver {
    nodes: Vec<Arc<Node>>,
    /// The nodes whose last `verify` answer carried no hardening mark, so that the driver says
    /// so once for each, not at every `verify`.
    unmarked: Mutex<HashSet<String>>,
}

/// The driver's id of a VM: the node's name and the agent's id.
fn driver_id(node: &str, id: &str) -> String {
    format!("{node}:{id}")
}

fn str_param<'a>(params: &'a Value, key: &str) -> Result<&'a str> {
    params[key]
        .as_str()
        .with_context(|| format!("the parameter `{key}` is missing"))
}

fn labels_param(params: &Value) -> Result<BTreeMap<String, String>> {
    match &params["labels"] {
        Value::Null => Ok(BTreeMap::new()),
        labels => serde_json::from_value(labels.clone()).context("`labels` is not a map of text"),
    }
}

/// Why a node that offers the template gets no new VM.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Skipped {
    /// It answered `verify` with problems, as it words them.
    Problems(Vec<String>),
    /// It gave no answer to `verify`: why.
    NoAnswer(String),
    /// It answered with no problem and no free slot.
    Full,
}

/// The most of one node's cause that the message of a create without a node carries: a node's
/// problems can be long, and the message goes to the server and into the run's page.
const CAUSE_MAX: usize = 400;
/// The most of that whole message, with up to [`MAX_NODES`] nodes in it.
const NO_PLACE_MAX: usize = 2000;

/// Where a new VM goes, from the `verify` answers of the nodes that offer its template, in the
/// configuration's order: among those that answer with no problem and have a free slot, a node
/// that reports itself hardened ([`node_hardening`]) before one that does not, then the node
/// with the most free slots, the first on a tie; else why each node was skipped.
fn choose(answers: &[(&str, Result<Value>)]) -> std::result::Result<usize, Vec<(String, Skipped)>> {
    let mut best: Option<(usize, (bool, u64))> = None;
    let mut skipped = Vec::new();
    for (i, (node, answer)) in answers.iter().enumerate() {
        let verified = match answer {
            Ok(verified) => verified,
            Err(e) => {
                // Node::call names the node first; the message names it once.
                let why = format!("{e:#}");
                let why = why
                    .strip_prefix(&format!("node {node}: "))
                    .unwrap_or(&why)
                    .to_string();
                skipped.push((node.to_string(), Skipped::NoAnswer(why)));
                continue;
            }
        };
        let problems: Vec<String> = verified["problems"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|p| p.as_str().map_or_else(|| p.to_string(), str::to_string))
            .collect();
        if !problems.is_empty() {
            skipped.push((node.to_string(), Skipped::Problems(problems)));
            continue;
        }
        let free = verified["maxVms"].as_u64().unwrap_or(0);
        // A hardened node first (ADR-0113, decision 4's order within one host), then the most
        // free slots; `true` orders after `false`, and an equal key keeps the earlier node.
        let key = (node_hardening(node, verified).is_ok(), free);
        if free == 0 {
            skipped.push((node.to_string(), Skipped::Full));
        } else if best.is_none_or(|(_, most)| key > most) {
            best = Some((i, key));
        }
    }
    best.map(|(i, _)| i).ok_or(skipped)
}

/// The most of a node's lines that do not hold that `notHardened` names; the rest are counted.
const MARK_LINES_MAX: usize = 32;

/// What one node's `verify` answer says of its hardening (ADR-0113, decision 5): `Ok` when the
/// node reports `yes` and no line of its mark fails, else the lines of `notHardened` that say
/// why it counts as not hardened, each `node <name>: …`. A node that sends no `hardening` (an
/// agent older than this host) or a mark that cannot be read counts as not hardened: the host
/// cannot vouch for it. The driver reads `hardened` and each line's `holds`, never a line's id,
/// and passes on the agent's words, on one line and cut.
fn node_hardening(node: &str, verified: &Value) -> std::result::Result<(), Vec<String>> {
    let hardening = &verified["hardening"];
    if hardening.is_null() {
        return Err(vec![format!(
            "node {node}: its node agent sent no hardening mark (an agent older than this host): counted as not hardened"
        )]);
    }
    let text = |value: &Value| {
        let text = value
            .as_str()
            .map_or_else(|| value.to_string(), str::to_string);
        crate::util::one_line(&text, CAUSE_MAX)
    };
    let failing: Vec<&Value> = hardening["lines"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|line| line["holds"].as_bool() != Some(true))
        .collect();
    let mut lines: Vec<String> = failing
        .iter()
        .take(MARK_LINES_MAX)
        .map(|line| {
            format!(
                "node {node}: {}: {}",
                text(&line["line"]),
                text(&line["found"])
            )
        })
        .collect();
    if failing.len() > MARK_LINES_MAX {
        lines.push(format!(
            "node {node}: {} more line(s) that do not hold",
            failing.len() - MARK_LINES_MAX
        ));
    }
    match hardening["hardened"].as_str() {
        Some("yes") if lines.is_empty() => Ok(()),
        // The mark and its lines disagree: the stricter reading.
        Some("yes") => Err(lines),
        Some("no") if lines.is_empty() => Err(vec![format!(
            "node {node}: reports not hardened, and names no line that does not hold"
        )]),
        Some("no") => Err(lines),
        _ => Err(vec![format!(
            "node {node}: its hardening mark cannot be read ({}): counted as not hardened",
            crate::util::one_line(&hardening["hardened"].to_string(), 64)
        )]),
    }
}

/// The driver's mark for a template, from the `verify` answers of the nodes that offer it, in
/// the configuration's order: `yes` when every one of them answered `yes`; `no` when one
/// answered `no` or without a readable mark; `None` ("not known") when none of them said `no`
/// but one did not answer. With it, `notHardened`: the lines of the nodes that are not hardened
/// ([`node_hardening`]), or, when the mark is not known, which nodes did not answer. No node at
/// all is `no`: nothing vouches for the template.
fn hardening_of(answers: &[(&str, &Result<Value>)]) -> (Option<&'static str>, Vec<String>) {
    if answers.is_empty() {
        return (
            Some("no"),
            vec!["no node of the driver offers the template".to_string()],
        );
    }
    let (mut not_hardened, mut silent) = (Vec::new(), Vec::new());
    for (node, answer) in answers {
        match answer {
            Ok(verified) => {
                if let Err(lines) = node_hardening(node, verified) {
                    not_hardened.extend(lines);
                }
            }
            Err(_) => silent.push(format!(
                "node {node}: did not answer, so its hardening is not known"
            )),
        }
    }
    if !not_hardened.is_empty() {
        (Some("no"), not_hardened)
    } else if !silent.is_empty() {
        (None, silent)
    } else {
        (Some("yes"), Vec::new())
    }
}

/// The error of a create that no node can take: why, for each node that offers the template.
/// Only when each of them is healthy and full is it about free slots; a node that reports a
/// problem or does not answer is named with its cause, each cut to [`CAUSE_MAX`], the whole to
/// [`NO_PLACE_MAX`]. The causes are what the nodes and the connections report: no credential
/// is in them.
fn no_place(template: &str, skipped: &[(String, Skipped)]) -> String {
    if skipped.iter().all(|(_, why)| *why == Skipped::Full) {
        return format!("no node of the driver has a free slot for the template `{template}`");
    }
    let causes: Vec<String> = skipped
        .iter()
        .map(|(node, why)| match why {
            Skipped::Problems(problems) => format!(
                "node {node} reports a problem: {}",
                crate::util::one_line(&problems.join("; "), CAUSE_MAX)
            ),
            Skipped::NoAnswer(error) => format!(
                "node {node} did not answer: {}",
                crate::util::one_line(error, CAUSE_MAX)
            ),
            Skipped::Full => format!("node {node} has no free slot"),
        })
        .collect();
    crate::util::one_line(
        &format!(
            "no node of the driver can take a VM of the template `{template}`: {}",
            causes.join("; ")
        ),
        NO_PLACE_MAX,
    )
}

impl NodeDriver {
    pub fn new(config: NodeConfig) -> Result<NodeDriver> {
        let policy = config.peer_build;
        let nodes = config
            .nodes
            .into_iter()
            .map(|spec| Node::new(spec, policy).map(Arc::new))
            .collect::<Result<_>>()?;
        Ok(NodeDriver {
            nodes,
            unmarked: Mutex::new(HashSet::new()),
        })
    }

    fn node(&self, name: &str) -> Result<&Arc<Node>> {
        self.nodes
            .iter()
            .find(|n| n.name() == name)
            .with_context(|| format!("no node `{name}` in the driver's configuration"))
    }

    /// The node and the agent's id of the driver's id `id`.
    fn split<'a>(&self, id: &'a str) -> Result<(&Arc<Node>, &'a str)> {
        let (node, agent_id) = id
            .split_once(':')
            .with_context(|| format!("`{id}` is not a VM id of the node driver (NODE:ID)"))?;
        Ok((self.node(node)?, agent_id))
    }

    fn offering(&self, template: &str) -> Vec<&Arc<Node>> {
        self.nodes
            .iter()
            .filter(|n| n.spec.templates.contains(template))
            .collect()
    }

    pub async fn handle(&self, method: &str, params: Value) -> Result<Value> {
        match method {
            "hello" => Ok(json!({
                "protocol": DRIVER_PROTOCOL,
                "driver": BUILTIN_NODE,
                "version": crate::images::build_id(),
            })),
            "verify" => self.verify(str_param(&params, "template")?).await,
            "create" => {
                self.create(
                    str_param(&params, "template")?,
                    str_param(&params, "name")?,
                    str_param(&params, "userData")?,
                    &labels_param(&params)?,
                )
                .await
            }
            "describe" => self.describe(str_param(&params, "id")?).await,
            "delete" => {
                self.delete(str_param(&params, "id")?, &labels_param(&params)?)
                    .await
            }
            "list" => self.list(&labels_param(&params)?).await,
            other => bail!("no method `{other}`"),
        }
    }

    /// Each node's `verify`, in the configuration's order: its answer, or why it gave none.
    async fn verify_each(&self, nodes: &[&Arc<Node>], template: &str) -> Vec<Result<Value>> {
        futures::future::join_all(
            nodes
                .iter()
                .map(|node| node.call("verify", json!({ "template": template }), true)),
        )
        .await
    }

    /// The nodes' problems with the template, and the free slots of those that answer with
    /// none. A node that does not answer is not counted, and `checked` names it. With them, the
    /// nodes' hardening mark for the template ([`hardening_of`]): `hardened`, left out when it
    /// is not known, and `notHardened`.
    pub async fn verify(&self, template: &str) -> Result<Value> {
        let nodes = self.offering(template);
        let mut problems = Vec::new();
        let mut checked = Vec::new();
        let mut max_vms: u64 = 0;
        let mut answered = 0;
        if nodes.is_empty() {
            problems.push(format!(
                "no node of the driver offers the template `{template}`"
            ));
        }
        let answers = self.verify_each(&nodes, template).await;
        for (node, answer) in nodes.iter().zip(&answers) {
            match answer {
                Ok(verified) => {
                    answered += 1;
                    let found: Vec<String> = verified["problems"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(Value::as_str)
                        .map(|p| format!("node {}: {p}", node.name()))
                        .collect();
                    if found.is_empty() {
                        let free = verified["maxVms"].as_u64().unwrap_or(0);
                        max_vms += free;
                        checked.push(format!("node {}: {free} free", node.name()));
                    }
                    problems.extend(found);
                    for line in verified["checked"].as_array().into_iter().flatten() {
                        if let Some(line) = line.as_str() {
                            checked.push(format!("node {}: {line}", node.name()));
                        }
                    }
                }
                Err(e) => checked.push(format!("{e:#}; not counted")),
            }
        }
        if !nodes.is_empty() && answered == 0 {
            problems.push("no node of the driver answered".to_string());
        }
        let named: Vec<(&str, &Result<Value>)> =
            nodes.iter().map(|node| node.name()).zip(&answers).collect();
        let (hardened, not_hardened) = hardening_of(&named);
        // An older agent is the one cause the host's log would not show otherwise: said once
        // for a node, and again only after an answer of it that carried the mark.
        for (node, answer) in &named {
            let Ok(verified) = answer else { continue };
            let mut unmarked = lock(&self.unmarked);
            if !verified["hardening"].is_null() {
                unmarked.remove(*node);
            } else if unmarked.insert(node.to_string()) {
                eprintln!(
                    "node {node}: its node agent sent no hardening mark in verify (an agent older than this host): it counts as not hardened until it is upgraded"
                );
            }
        }
        let mut verified = json!({
            "problems": problems,
            "maxVms": max_vms.min(u64::from(u32::MAX)),
            "checked": checked,
            "notHardened": not_hardened,
        });
        if let Some(hardened) = hardened {
            verified["hardened"] = json!(hardened);
        }
        Ok(verified)
    }

    /// The node a new VM of `template` goes to, among the nodes that offer it, answer and report
    /// no problem: a hardened node first, then the most free slots; the first on a tie
    /// ([`choose`]). When there is none, the error says why for each node ([`no_place`]).
    async fn place(&self, template: &str) -> Result<&Arc<Node>> {
        let nodes = self.offering(template);
        if nodes.is_empty() {
            bail!("no node of the driver offers the template `{template}`");
        }
        let answers: Vec<(&str, Result<Value>)> = nodes
            .iter()
            .map(|node| node.name())
            .zip(self.verify_each(&nodes, template).await)
            .collect();
        match choose(&answers) {
            Ok(i) => Ok(nodes[i]),
            Err(skipped) => bail!("{}", no_place(template, &skipped)),
        }
    }

    pub async fn create(
        &self,
        template: &str,
        name: &str,
        user_data: &str,
        labels: &BTreeMap<String, String>,
    ) -> Result<Value> {
        if !labels.contains_key(HOST_LABEL) {
            bail!("a sandbox needs the label {HOST_LABEL}");
        }
        let node = self.place(template).await?;
        // Not tried again: a create whose answer was lost may have made a VM, which the
        // host's leftover cleanup then finds.
        let created = node
            .call(
                "create",
                json!({ "template": template, "name": name, "userData": user_data, "labels": labels }),
                false,
            )
            .await?;
        let id = created["id"]
            .as_str()
            .with_context(|| format!("node {}: a create without an id", node.name()))?;
        let id = driver_id(node.name(), id);
        eprintln!("created {id} ({name}) from the template {template}");
        Ok(json!({ "id": id }))
    }

    /// A VM as the agent describes it, under the driver's id.
    fn view(node: &Node, mut vm: Value) -> Value {
        if let Some(id) = vm["id"].as_str() {
            vm["id"] = Value::String(driver_id(node.name(), id));
        }
        vm
    }

    pub async fn describe(&self, id: &str) -> Result<Value> {
        let (node, agent_id) = self.split(id)?;
        if lock(&node.failed).contains(agent_id) {
            return Ok(json!({ "id": id, "state": "failed" }));
        }
        let vm = node
            .call("describe", json!({ "id": agent_id }), true)
            .await?;
        Ok(Self::view(node, vm))
    }

    pub async fn delete(&self, id: &str, labels: &BTreeMap<String, String>) -> Result<Value> {
        if !labels.get(HOST_LABEL).is_some_and(|host| !host.is_empty()) {
            bail!("a delete must name the host's label {HOST_LABEL}: not this host's to delete");
        }
        let (node, agent_id) = self.split(id)?;
        node.call("delete", json!({ "id": agent_id, "labels": labels }), true)
            .await?;
        lock(&node.failed).remove(agent_id);
        eprintln!("deleted {id}");
        Ok(json!({}))
    }

    /// The VMs with each of `labels` on the nodes that answer. A node that does not is written
    /// to stderr and left out: the host deletes only VMs it is shown, so none of that node's is
    /// deleted or forgotten.
    pub async fn list(&self, labels: &BTreeMap<String, String>) -> Result<Value> {
        let answers = futures::future::join_all(
            self.nodes
                .iter()
                .map(|node| node.call("list", json!({ "labels": labels }), true)),
        )
        .await;
        let mut vms = Vec::new();
        for (node, answer) in self.nodes.iter().zip(answers) {
            match answer {
                Ok(listed) => vms.extend(
                    listed["vms"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .map(|vm| Self::view(node, vm.clone())),
                ),
                Err(e) => eprintln!("{e:#}; its VMs are not listed this time"),
            }
        }
        Ok(json!({ "vms": vms }))
    }
}

/// Serves the driver protocol on `input` and `output` until `input` ends
/// ([`crate::vm::serve_driver`]: a line that is not a request gets an error answer).
pub async fn serve<R, W>(driver: Arc<NodeDriver>, input: R, output: W) -> Result<()>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin + Send + 'static,
{
    crate::vm::serve_driver(input, output, move |method, params| {
        let driver = driver.clone();
        async move { driver.handle(&method, params).await }
    })
    .await
}

/// `vm-driver node`: serves the driver protocol on stdin and stdout.
pub async fn run(config_path: &Path) -> Result<()> {
    let config = NodeConfig::load(config_path)?;
    serve(
        Arc::new(NodeDriver::new(config)?),
        tokio::io::stdin(),
        tokio::io::stdout(),
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(value: Value) -> ConfigFile {
        serde_json::from_value(value).unwrap()
    }

    fn fake(_: &Path) -> Result<Vec<u8>> {
        Ok(vec![1, 2, 3])
    }

    fn config(value: Value) -> Result<NodeConfig> {
        NodeConfig::from_file(file(value), &fake, &fake)
    }

    fn tls_node(name: &str, address: &str) -> Value {
        json!({
            "name": name, "address": address, "nodeSha256": "ab".repeat(32),
            "certificate": "/etc/c.crt", "key": "/etc/c.key", "templates": ["sandbox"]
        })
    }

    fn socket_node(name: &str) -> Value {
        json!({ "name": name, "socket": "/run/l.sock", "templates": ["sandbox"] })
    }

    #[test]
    fn a_configuration_names_each_node_once_with_one_way_to_reach_it() {
        let c = config(
            json!({ "nodes": [tls_node("a", "10.0.0.20:7700"), tls_node("b", "10.0.0.21:7700")] }),
        )
        .unwrap();
        assert_eq!(c.nodes.len(), 2);
        assert_eq!(c.peer_build, BuildPolicy::Warn);
        for (bad, expected) in [
            (json!({ "nodes": [] }), "1 to"),
            (
                json!({ "nodes": [tls_node("a", "10.0.0.20:7700"), tls_node("a", "10.0.0.21:7700")] }),
                "each once",
            ),
            (
                json!({ "nodes": [tls_node("A", "10.0.0.20:7700")] }),
                "each once",
            ),
            (
                json!({ "nodes": [tls_node("a", "10.0.0.20")] }),
                "HOST:PORT",
            ),
            (
                json!({ "nodes": [tls_node("a", "10.0.0.20:0")] }),
                "HOST:PORT",
            ),
            (
                json!({ "nodes": [{ "name": "a", "templates": ["s"] }] }),
                "either",
            ),
            (
                json!({ "nodes": [{ "name": "a", "socket": "/s", "address": "h:1", "templates": ["s"] }] }),
                "either",
            ),
            (
                json!({ "nodes": [{ "name": "a", "socket": "/s", "key": "/k", "templates": ["s"] }] }),
                "no certificate",
            ),
            (
                json!({ "nodes": [{ "name": "a", "socket": "s", "templates": ["s"] }] }),
                "absolute",
            ),
            (
                json!({ "nodes": [{ "name": "a", "socket": "/s", "templates": [] }] }),
                "templates",
            ),
            (
                json!({ "nodes": [{ "name": "a", "socket": "/s", "templates": ["../x"] }] }),
                "templates",
            ),
            (
                json!({ "nodes": [{ "name": "a", "address": "h:1", "nodeSha256": "ab", "certificate": "/c", "key": "/k", "templates": ["s"] }] }),
                "64 hex",
            ),
            (
                json!({ "nodes": [{ "name": "a", "address": "h:1", "certificate": "/c", "key": "/k", "templates": ["s"] }] }),
                "nodeSha256",
            ),
            (
                json!({ "nodes": [socket_node("a"), tls_node("b", "10.0.0.21:7700")] }),
                "one isolation level",
            ),
        ] {
            let error = format!("{:#}", config(bad.clone()).unwrap_err());
            assert!(error.contains(expected), "{bad}: {error}");
        }
        assert!(serde_json::from_value::<ConfigFile>(json!({ "nodes": [], "extra": 1 })).is_err());
    }

    #[test]
    fn the_isolation_is_vm_jail_for_sockets_and_machine_for_other_machines() {
        let local = config(json!({ "nodes": [socket_node("a"), socket_node("b")] })).unwrap();
        assert_eq!(local.isolation_with(|_| false).unwrap(), Isolation::VmJail);
        assert_eq!(Isolation::VmJail.as_str(), "vm-jail");
        let remote = config(
            json!({ "nodes": [tls_node("a", "10.0.0.20:7700"), tls_node("b", "[fd00::5]:7700")] }),
        )
        .unwrap();
        assert_eq!(
            remote.isolation_with(|_| false).unwrap(),
            Isolation::Machine
        );
        assert_eq!(Isolation::Machine.as_str(), "machine");
        // A TLS node at this host's own address is not another machine.
        let error = remote
            .isolation_with(|host| host == "fd00::5")
            .unwrap_err()
            .to_string();
        assert!(error.contains("this host's own address"), "{error}");
        let lo = config(json!({ "nodes": [tls_node("a", "127.0.0.1:7700")] })).unwrap();
        assert!(lo.isolation().is_err());
        assert!(is_own_host("127.0.0.1") && is_own_host("::1") && is_own_host("0.0.0.0"));
        assert!(!is_own_host("192.0.2.77"));
        assert!(!is_own_host("no-such-host.invalid"));
    }

    #[test]
    fn an_identity_is_written_once_with_its_key_closed_and_its_pin_printed() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let cert = dir.path().join("client.crt");
        let key = dir.path().join("client.key");
        let pin = write_identity(&cert, &key).unwrap();
        assert_eq!(pin.len(), 64);
        assert_eq!(parse_pin(&pin).unwrap().len(), 32);
        assert_eq!(fingerprint(&cert).unwrap(), pin);
        let mode = |path: &Path| std::fs::metadata(path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(&key), 0o600);
        // This host's own rules for its client key and certificate take them, and so does TLS.
        let (cert_der, key_der) = (read_small(&cert).unwrap(), read_key(&key).unwrap());
        tls_config(parse_pin(&pin).unwrap(), cert_der, key_der).unwrap();
        // Never overwritten, and a second identity differs.
        let error = write_identity(&cert, &key).unwrap_err().to_string();
        assert!(error.contains("exists"), "{error}");
        assert_eq!(fingerprint(&cert).unwrap(), pin);
        let other = write_identity(&dir.path().join("b.crt"), &dir.path().join("b.key")).unwrap();
        assert_ne!(other, pin);
    }

    #[test]
    fn an_identity_follows_no_link_and_leaves_no_key_behind() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("target");
        let link = dir.path().join("client.key");
        std::os::unix::fs::symlink(&target, &link).unwrap();
        assert!(write_identity(&dir.path().join("client.crt"), &link).is_err());
        assert!(!target.exists());
        assert!(!dir.path().join("client.crt").exists());
        // A certificate that cannot be written: the new key goes again.
        let key = dir.path().join("new.key");
        let cert = dir.path().join("missing-dir/new.crt");
        assert!(write_identity(&cert, &key).is_err());
        assert!(!key.exists());
        // A write that fails after its file was made: the key's, then the certificate's. Every
        // file the call made goes again, so a retry works; a file that was there stays.
        let half = |file: &mut std::fs::File, bytes: &[u8]| -> std::io::Result<()> {
            use std::io::Write;
            file.write_all(&bytes[..bytes.len() / 2])?;
            Err(std::io::Error::other("the disk is full"))
        };
        let (cert, key) = (dir.path().join("full.crt"), dir.path().join("full.key"));
        let error = format!("{:#}", write_identity_with(&cert, &key, &half).unwrap_err());
        assert!(
            error.contains("full.key") && error.contains("the disk is full"),
            "{error}"
        );
        assert!(!key.exists() && !cert.exists());
        let calls = std::cell::Cell::new(0);
        let second_fails = |file: &mut std::fs::File, bytes: &[u8]| {
            calls.set(calls.get() + 1);
            if calls.get() == 2 {
                half(file, bytes)
            } else {
                std::io::Write::write_all(file, bytes)
            }
        };
        let error = format!(
            "{:#}",
            write_identity_with(&cert, &key, &second_fails).unwrap_err()
        );
        assert!(error.contains("full.crt"), "{error}");
        assert!(!key.exists() && !cert.exists());
        assert!(write_identity(&cert, &key).is_ok(), "a retry starts clean");
        // A certificate that someone made in between: refused, and left as it was.
        let other_key = dir.path().join("other.key");
        assert!(write_identity(&cert, &other_key).is_err());
        assert!(cert.exists() && !other_key.exists());
        // An empty certificate has no pin.
        let empty = dir.path().join("empty.crt");
        std::fs::write(&empty, b"").unwrap();
        assert!(fingerprint(&empty).is_err());
    }

    #[test]
    fn a_key_file_must_be_its_owners_alone() {
        assert!(key_file_ok(990, 0o100600, 990));
        assert!(key_file_ok(990, 0o100400, 990));
        assert!(!key_file_ok(990, 0o100640, 990));
        assert!(!key_file_ok(990, 0o100604, 990));
        assert!(!key_file_ok(0, 0o100600, 990));
        let dir = tempfile::tempdir().unwrap();
        let key = dir.path().join("client.key");
        std::fs::write(&key, b"key").unwrap();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&key, std::fs::Permissions::from_mode(0o600)).unwrap();
        assert_eq!(read_key(&key).unwrap(), b"key");
        std::fs::set_permissions(&key, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert!(read_key(&key).unwrap_err().to_string().contains("0600"));
    }

    fn healthy(free: u64) -> Result<Value> {
        Ok(json!({ "problems": [], "maxVms": free, "checked": [] }))
    }

    fn broken(problems: &[&str]) -> Result<Value> {
        Ok(json!({ "problems": problems, "maxVms": 0, "checked": [] }))
    }

    fn silent(node: &str) -> Result<Value> {
        Err(anyhow::anyhow!(
            "node {node}: cannot reach the node: connection refused"
        ))
    }

    const TABLE: &str = "the node's own firewall table (inet tinfirm_node) is not loaded, and this node's configuration requires it";

    fn message(template: &str, answers: &[(&str, Result<Value>)]) -> String {
        no_place(template, &choose(answers).unwrap_err())
    }

    #[test]
    fn a_vm_goes_to_the_healthy_node_with_the_most_free_slots() {
        assert_eq!(choose(&[("a", healthy(1)), ("b", healthy(2))]), Ok(1));
        assert_eq!(choose(&[("a", healthy(2)), ("b", healthy(2))]), Ok(0));
        // A node with a problem or without an answer is skipped; the healthy one works on.
        assert_eq!(choose(&[("a", broken(&[TABLE])), ("b", healthy(1))]), Ok(1));
        assert_eq!(choose(&[("a", silent("a")), ("b", healthy(1))]), Ok(1));
        // A node with problems is skipped even when it names free slots.
        let odd = Ok(json!({ "problems": ["x"], "maxVms": 3 }));
        assert_eq!(choose(&[("a", odd), ("b", healthy(1))]), Ok(1));
    }

    /// A healthy node with `free` slots and the hardening mark `hardening`.
    fn marked(free: u64, hardening: Value) -> Result<Value> {
        Ok(json!({ "problems": [], "maxVms": free, "checked": [], "hardening": hardening }))
    }

    fn yes() -> Value {
        json!({ "hardened": "yes", "lines": [
            { "line": "io-uring", "holds": true, "found": "kernel.io_uring_disabled is 2" },
        ]})
    }

    fn no() -> Value {
        json!({ "hardened": "no", "lines": [
            { "line": "user-namespaces", "holds": true, "found": "user.max_user_namespaces is 0" },
            { "line": "io-uring", "holds": false, "found": "kernel.io_uring_disabled is 0, not 2" },
            { "line": "max-uptime", "holds": false, "found": "up 41 days, more than 30" },
        ]})
    }

    fn mark(answers: &[(&str, Result<Value>)]) -> (Option<&'static str>, Vec<String>) {
        let answers: Vec<(&str, &Result<Value>)> = answers.iter().map(|(n, a)| (*n, a)).collect();
        hardening_of(&answers)
    }

    #[test]
    fn the_nodes_mark_is_yes_only_when_every_node_says_yes() {
        // Every node `yes`: `yes`, and nothing named.
        assert_eq!(
            mark(&[("n1", marked(1, yes())), ("n2", marked(0, yes()))]),
            (Some("yes"), Vec::new())
        );
        // One `no`: `no`, with each line of it that does not hold, in the agent's words.
        assert_eq!(
            mark(&[("n1", marked(1, yes())), ("n2", marked(1, no()))]),
            (
                Some("no"),
                vec![
                    "node n2: io-uring: kernel.io_uring_disabled is 0, not 2".to_string(),
                    "node n2: max-uptime: up 41 days, more than 30".to_string(),
                ]
            )
        );
        // A node without `hardening`, an agent older than this host: `no`, and why.
        let (hardened, why) = mark(&[("n1", marked(1, yes())), ("n2", healthy(1))]);
        assert_eq!(hardened, Some("no"));
        assert_eq!(
            why,
            [
                "node n2: its node agent sent no hardening mark (an agent older than this host): counted as not hardened"
            ]
        );
        // A node that does not answer, the rest `yes`: not known.
        assert_eq!(
            mark(&[("n1", marked(1, yes())), ("n2", silent("n2"))]),
            (
                None,
                vec!["node n2: did not answer, so its hardening is not known".to_string()]
            )
        );
        // A `no` wins over a node that does not answer.
        let (hardened, why) = mark(&[("n1", silent("n1")), ("n2", marked(1, no()))]);
        assert_eq!(hardened, Some("no"));
        assert!(why.iter().all(|w| w.starts_with("node n2: ")), "{why:?}");
        // A node that reports a problem still reports its mark.
        let broken_yes = Ok(json!({ "problems": [TABLE], "maxVms": 0, "hardening": yes() }));
        assert_eq!(mark(&[("n1", broken_yes)]), (Some("yes"), Vec::new()));
        // No node: nothing vouches for the template.
        assert_eq!(mark(&[]).0, Some("no"));
    }

    #[test]
    fn a_mark_that_cannot_be_read_or_disagrees_with_its_lines_is_no() {
        // `yes` beside a line that does not hold: the stricter reading.
        let odd = json!({ "hardened": "yes", "lines": [
            { "line": "lockdown", "holds": false, "found": "lockdown is none" },
        ]});
        assert_eq!(
            mark(&[("n1", marked(1, odd))]),
            (
                Some("no"),
                vec!["node n1: lockdown: lockdown is none".to_string()]
            )
        );
        // `no` without a line, a value the driver does not know, or no value at all.
        for (hardening, expected) in [
            (json!({ "hardened": "no", "lines": [] }), "names no line"),
            (
                json!({ "hardened": "maybe", "lines": [] }),
                "cannot be read (\"maybe\")",
            ),
            (json!({ "lines": [] }), "cannot be read (null)"),
            (json!("yes"), "cannot be read"),
        ] {
            let (hardened, why) = mark(&[("n1", marked(1, hardening.clone()))]);
            assert_eq!(hardened, Some("no"), "{hardening}");
            assert!(why[0].contains(expected), "{hardening}: {why:?}");
        }
        // A line id the driver does not know is passed on: the driver interprets no id. A
        // `found` that is long or has line breaks is put on one line and cut.
        let long = format!("{}\nsecond line", "x".repeat(2 * CAUSE_MAX));
        let newer = json!({ "hardened": "no", "lines": [
            { "line": "a-line-of-a-later-agent", "holds": false, "found": long },
        ]});
        let (_, why) = mark(&[("n1", marked(1, newer))]);
        assert!(
            why[0].starts_with("node n1: a-line-of-a-later-agent: x"),
            "{why:?}"
        );
        assert!(
            !why[0].contains('\n') && why[0].len() < CAUSE_MAX + 64,
            "{why:?}"
        );
        // Many lines that do not hold: the first ones, and a count of the rest.
        let many: Vec<Value> = (0..MARK_LINES_MAX + 5)
            .map(|i| json!({ "line": format!("l{i}"), "holds": false, "found": "off" }))
            .collect();
        let (_, why) = mark(&[("n1", marked(1, json!({ "hardened": "no", "lines": many })))]);
        assert_eq!(why.len(), MARK_LINES_MAX + 1);
        assert_eq!(
            why[MARK_LINES_MAX],
            "node n1: 5 more line(s) that do not hold"
        );
    }

    #[test]
    fn a_vm_goes_to_a_hardened_node_before_one_with_more_free_slots() {
        // `yes` with one slot before `no` with four, and before a node of an older agent.
        assert_eq!(
            choose(&[("a", marked(4, no())), ("b", marked(1, yes()))]),
            Ok(1)
        );
        assert_eq!(choose(&[("a", healthy(4)), ("b", marked(1, yes()))]), Ok(1));
        // Among hardened nodes, and among the others, the existing order holds: the most free
        // slots, then the first.
        assert_eq!(
            choose(&[
                ("a", marked(1, yes())),
                ("b", marked(3, yes())),
                ("c", marked(9, no()))
            ]),
            Ok(1)
        );
        assert_eq!(
            choose(&[("a", marked(2, yes())), ("b", marked(2, yes()))]),
            Ok(0)
        );
        assert_eq!(choose(&[("a", marked(2, no())), ("b", healthy(3))]), Ok(1));
        assert_eq!(choose(&[("a", marked(2, no())), ("b", healthy(2))]), Ok(0));
        // A hardened node that is full, broken or silent is still skipped.
        assert_eq!(
            choose(&[("a", marked(0, yes())), ("b", marked(1, no()))]),
            Ok(1)
        );
        let broken_yes = Ok(json!({ "problems": [TABLE], "maxVms": 3, "hardening": yes() }));
        assert_eq!(choose(&[("a", broken_yes), ("b", marked(1, no()))]), Ok(1));
    }

    #[test]
    fn a_create_without_a_node_names_the_cause_of_each() {
        // One node, with its problem: the message says so, not "no free slot".
        let one = message("sandbox-2c4g", &[("node-1", broken(&[TABLE]))]);
        assert!(
            one.starts_with(
                "no node of the driver can take a VM of the template `sandbox-2c4g`: node node-1 reports a problem: the node's own firewall table"
            ),
            "{one}"
        );
        assert!(!one.contains("free slot"), "{one}");
        // One node that does not answer: the error, the node named once.
        let one = message("sandbox-2c4g", &[("node-1", silent("node-1"))]);
        assert_eq!(
            one,
            "no node of the driver can take a VM of the template `sandbox-2c4g`: node node-1 did not answer: cannot reach the node: connection refused"
        );
        // Healthy and full, one node or two: the free slots.
        let full = "no node of the driver has a free slot for the template `sandbox-2c4g`";
        assert_eq!(message("sandbox-2c4g", &[("node-1", healthy(0))]), full);
        assert_eq!(
            message(
                "sandbox-2c4g",
                &[("node-1", healthy(0)), ("node-2", healthy(0))]
            ),
            full
        );
        // Two nodes, each with its own cause, in the configuration's order.
        let two = message(
            "sandbox-2c4g",
            &[
                ("node-1", healthy(0)),
                ("node-2", broken(&[TABLE, "a second one"])),
            ],
        );
        assert_eq!(
            two,
            format!(
                "no node of the driver can take a VM of the template `sandbox-2c4g`: node node-1 has no free slot; node node-2 reports a problem: {TABLE}; a second one"
            )
        );
        let two = message(
            "sandbox-2c4g",
            &[("node-1", silent("node-1")), ("node-2", broken(&[TABLE]))],
        );
        assert!(
            two.contains("node node-1 did not answer: cannot reach the node")
                && two.contains("; node node-2 reports a problem: the node's own"),
            "{two}"
        );
    }

    #[test]
    fn the_message_of_a_create_without_a_node_is_bounded_and_on_one_line() {
        let long = format!("{}\nsecond line", "x".repeat(5000));
        let one = message("t", &[("node-1", broken(&[&long]))]);
        assert!(!one.contains('\n'), "{one}");
        assert!(one.len() < CAUSE_MAX + 200, "{}", one.len());
        assert!(one.ends_with('…'), "{one}");
        // Many nodes: the whole is bounded too.
        let names: Vec<String> = (0..MAX_NODES).map(|i| format!("node-{i}")).collect();
        let answers: Vec<(&str, Result<Value>)> = names
            .iter()
            .map(|name| (name.as_str(), broken(&[&long])))
            .collect();
        let all = message("t", &answers);
        assert!(all.len() <= NO_PLACE_MAX, "{}", all.len());
        assert!(all.ends_with('…'), "{all}");
    }

    #[tokio::test]
    async fn a_line_that_is_no_request_gets_an_error_and_a_request_its_answer() {
        let driver = Arc::new(
            NodeDriver::new(config(json!({ "nodes": [socket_node("a")] })).unwrap()).unwrap(),
        );
        let serve_lines = |input: &'static str| {
            let driver = driver.clone();
            async move {
                let (mut ours, theirs) = tokio::io::duplex(64 * 1024);
                let served = serve(driver, input.as_bytes(), theirs).await;
                let mut out = String::new();
                ours.read_to_string(&mut out).await.unwrap();
                let answers: Vec<Value> = out
                    .lines()
                    .map(|l| serde_json::from_str(l).unwrap())
                    .collect();
                (served, answers)
            }
        };
        // Not JSON: a parse error with a null id, and a failure at the end.
        let (served, answers) = serve_lines("{'jsonrpc': '2.0'}\n").await;
        assert!(served.is_err());
        assert_eq!(answers.len(), 1);
        assert_eq!(answers[0]["error"]["code"], -32700);
        assert_eq!(answers[0]["id"], Value::Null);
        // JSON that is no request: invalid, with its id when it has one.
        let (served, answers) = serve_lines(
            "{\"jsonrpc\":\"2.0\"}\n[1]\n{\"jsonrpc\":\"1.0\",\"id\":4,\"method\":\"hello\"}\n{\"jsonrpc\":\"2.0\",\"method\":\"hello\"}\n",
        )
        .await;
        assert!(served.is_err());
        let codes: Vec<&Value> = answers.iter().map(|a| &a["error"]["code"]).collect();
        assert_eq!(codes, [&json!(-32600); 4]);
        assert_eq!(answers[2]["id"], 4);
        // A request among them: answered, and the end is no failure. An empty line gets
        // nothing.
        let (served, answers) =
            serve_lines("\nnot json\n{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"hello\"}\n").await;
        assert!(served.is_ok());
        assert_eq!(answers.len(), 2, "{answers:?}");
        let hello = answers.iter().find(|a| a["id"] == 7).unwrap();
        assert_eq!(hello["result"]["driver"], "node");
        // No input, or empty lines only: nothing, and no failure.
        for input in ["", "\n\n"] {
            let (served, answers) = serve_lines(input).await;
            assert!(served.is_ok() && answers.is_empty());
        }
    }

    #[test]
    fn ids_name_their_node_and_builds_compare_by_release() {
        assert_eq!(driver_id("node-1", "vm-0a"), "node-1:vm-0a");
        assert_eq!(release_of("1.0.0+images.1a2b"), "1.0.0");
        assert_eq!(release_of("1.0.0"), "1.0.0");
        assert_eq!(parse_pin(&"ab".repeat(32)).unwrap(), [0xab; 32]);
        assert!(parse_pin("ab").is_err());
    }
}
