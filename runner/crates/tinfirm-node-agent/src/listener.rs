//! The listener: an unprivileged user. It takes the control host's connection (TLS with a
//! pinned client certificate, or a local socket on a single machine), reads the requests of
//! the agent's wire ([`crate::wire`]), and asks the helper over its socket. It keeps what the
//! helper must not hold: each VM's name and labels, in a record of its own, and each VM's user
//! data, in a spool file, for the moment of its make. It never logs a ticket or user data.

use std::collections::{BTreeMap, BTreeSet};
use std::io::Write;
use std::net::SocketAddr;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, UnixListener};
use tokio::sync::{Semaphore, broadcast, mpsc};

use crate::helper::{self, HelperClient, HelperError, VmState};
use crate::id::{TemplateName, VmId};
use crate::local::{self, LineError};
use crate::spool;
use crate::wire::{self, RpcError, RpcRequest, Vm, code};

/// How many connections each transport serves at once: on TLS, connections whose client
/// presented the pinned certificate; on the local socket, connections of the runner host's
/// user. More are closed at once.
pub const MAX_CONNECTIONS: usize = 16;
/// How many TLS handshakes may be pending at once. A new connection beyond them drops the
/// oldest pending one.
pub const HANDSHAKE_SLOTS: usize = 8;
/// How long a TLS client may take to finish its handshake.
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(3);
/// At most one summary of refusals in this time.
pub const REFUSAL_SUMMARY_EVERY: Duration = Duration::from_secs(60);
/// How many requests one connection may have in flight.
pub const MAX_IN_FLIGHT: usize = 64;
/// The most VMs the listener keeps a record of.
pub const MAX_RECORDS: usize = 1024;
/// The directory of the records, in the listener's state directory.
pub const RECORDS: &str = "vms";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BuildPolicy {
    /// A driver of another build is served, with a warning.
    #[default]
    Warn,
    /// A driver of another build is refused at `hello`.
    Refuse,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TlsSettings {
    /// Where the listener takes the control host's TLS connections.
    pub listen: SocketAddr,
    /// The node's certificate (DER), which the driver pins.
    pub certificate: PathBuf,
    /// Its key (PKCS #8 DER), the listener's own with mode 0600.
    pub key: PathBuf,
    /// The SHA-256 of the control host's client certificate, in hex: the only client.
    pub client_sha256: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SocketSettings {
    /// The listener's socket on a single machine.
    pub path: PathBuf,
    /// The runner host's user: the only peer.
    pub peer_uid: u32,
}

/// The listener's configuration file.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ListenerConfig {
    /// The helper's socket.
    pub helper_socket: PathBuf,
    /// The spool directory, the listener's own (0700), where it writes each VM's user data.
    pub spool: PathBuf,
    /// The listener's own directory (0700): its records of the VMs.
    pub state: PathBuf,
    /// The node's address, as the control host dials it: what `describe` answers.
    pub address: String,
    #[serde(default)]
    pub tls: Option<TlsSettings>,
    #[serde(default)]
    pub socket: Option<SocketSettings>,
    #[serde(default)]
    pub peer_build: BuildPolicy,
    /// How often the listener asks the helper for VMs whose VMM died, in milliseconds.
    #[serde(default = "default_watch")]
    pub watch_ms: u64,
    /// Test-only: the listener may run as root, as the tests do.
    #[serde(skip)]
    #[doc(hidden)]
    pub allow_root: bool,
}

fn default_watch() -> u64 {
    1000
}

impl ListenerConfig {
    pub fn load(path: &Path) -> Result<ListenerConfig> {
        let text = crate::plan::read_bounded(path, 1024 * 1024)?;
        let config: ListenerConfig = serde_json::from_slice(&text)
            .with_context(|| format!("{} is not a valid listener configuration", path.display()))?;
        config.validate()?;
        Ok(config)
    }

    pub fn validate(&self) -> Result<()> {
        if self.tls.is_none() && self.socket.is_none() {
            bail!("the configuration names neither `tls` nor `socket`");
        }
        let address_ok = !self.address.is_empty()
            && self.address.len() <= 253
            && self
                .address
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b':'));
        if !address_ok {
            bail!("`address` must be the node's IP address or host name");
        }
        if !(100..=60_000).contains(&self.watch_ms) {
            bail!("watchMs must be 100 to 60000");
        }
        if let Some(tls) = &self.tls {
            crate::tls::parse_pin(&tls.client_sha256).context("tls.clientSha256")?;
        }
        if let Some(socket) = &self.socket
            && socket.peer_uid == 0
            && !self.allow_root
        {
            bail!("socket.peerUid is 0: the runner host must not be root");
        }
        for path in [&self.helper_socket, &self.spool, &self.state] {
            if !path.is_absolute() {
                bail!("{} is not an absolute path", path.display());
            }
        }
        Ok(())
    }
}

/// The listener's record of one VM: what the control host named it and labelled it with.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Record {
    pub id: VmId,
    pub name: String,
    pub template: TemplateName,
    pub labels: BTreeMap<String, String>,
}

/// The listener.
pub struct Listener {
    config: ListenerConfig,
    helper: HelperClient,
    records: Mutex<BTreeMap<VmId, Record>>,
    /// The VMs whose death the listener reported.
    reported: Mutex<BTreeSet<VmId>>,
    events: broadcast::Sender<Value>,
    refusals: Refusals,
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

fn from_helper(e: HelperError) -> RpcError {
    let code = match e.code {
        helper::Code::BadRequest => code::INVALID_PARAMS,
        helper::Code::UnknownTemplate => code::UNKNOWN_TEMPLATE,
        helper::Code::Full => code::FULL,
        helper::Code::NotBuilt => code::NOT_BUILT,
        helper::Code::Exists | helper::Code::UserData | helper::Code::Failed => code::FAILED,
    };
    RpcError::new(code, e.message)
}

fn parse_id(text: &str) -> Result<VmId, RpcError> {
    VmId::parse(text.as_bytes()).ok_or_else(|| {
        RpcError::new(
            code::INVALID_PARAMS,
            "not a VM id of this node (vm- and 32 lower-case hex digits)",
        )
    })
}

fn parse_template(text: &str) -> Result<TemplateName, RpcError> {
    TemplateName::parse(text.as_bytes()).ok_or_else(|| {
        RpcError::new(
            code::INVALID_PARAMS,
            "not a template name (a-z, 0-9 and -, at most 63 bytes)",
        )
    })
}

impl Listener {
    /// Checks the listener's directories, reads its records, and makes them agree with the
    /// helper: a record whose VM is gone goes, and a VM that no record holds is deleted.
    pub async fn start(config: ListenerConfig) -> Result<Arc<Listener>> {
        config.validate()?;
        let own = local::own_uid()?;
        if own == 0 && !config.allow_root {
            bail!("the listener must not run as root");
        }
        for (what, dir) in [("spool", &config.spool), ("state", &config.state)] {
            let facts = local::DirFacts::of(dir)
                .with_context(|| format!("cannot read the {what} directory {}", dir.display()))?;
            if let Err(problem) = local::check_private_dir(&facts, own) {
                bail!("the {what} directory {} {problem}", dir.display());
            }
        }
        // No VM is being made yet: whatever is in the spool is user data an earlier listener
        // left when it stopped between a write and its remove, and may hold a ticket.
        let cleared = clear_spool(&config.spool)?;
        if cleared > 0 {
            tracing::warn!(
                entries = cleared,
                "removed what an earlier listener left in its spool"
            );
        }
        let records_dir = config.state.join(RECORDS);
        match std::fs::create_dir(&records_dir) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(e) => {
                return Err(e).with_context(|| format!("cannot make {}", records_dir.display()));
            }
        }
        let records = load_records(&records_dir)?;
        let (events, _) = broadcast::channel(256);
        let listener = Arc::new(Listener {
            helper: HelperClient::new(config.helper_socket.clone()),
            config,
            records: Mutex::new(records),
            reported: Mutex::new(BTreeSet::new()),
            events,
            refusals: Refusals::default(),
        });
        let listed = listener
            .helper
            .list()
            .await
            .map_err(|e| anyhow::anyhow!("cannot list the helper's VMs: {e}"))?;
        let on_node: BTreeSet<VmId> = listed.vms.iter().map(|vm| vm.id.clone()).collect();
        let stale: Vec<VmId> = lock(&listener.records)
            .keys()
            .filter(|id| !on_node.contains(*id))
            .cloned()
            .collect();
        for id in stale {
            tracing::info!(vm = %id, "dropping the record of a VM that is gone");
            listener.forget(&id);
        }
        listener.delete_unrecorded(&listed.vms).await;
        Ok(listener)
    }

    pub fn config(&self) -> &ListenerConfig {
        &self.config
    }

    fn record_path(&self, id: &VmId) -> PathBuf {
        self.config.state.join(RECORDS).join(format!("{id}.json"))
    }

    fn save_record(&self, record: &Record) -> Result<()> {
        let path = self.record_path(&record.id);
        let next = path.with_extension("json.next");
        let _ = std::fs::remove_file(&next);
        let mut file = std::fs::File::options()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&next)?;
        file.write_all(&serde_json::to_vec(record).expect("records serialize"))?;
        file.sync_all()?;
        std::fs::rename(&next, &path)?;
        Ok(())
    }

    fn forget(&self, id: &VmId) {
        lock(&self.records).remove(id);
        lock(&self.reported).remove(id);
        let _ = std::fs::remove_file(self.record_path(id));
    }

    /// Deletes the helper's VMs that no record holds: what an earlier listener left.
    async fn delete_unrecorded(&self, vms: &[helper::HelperVm]) {
        let unrecorded: Vec<VmId> = {
            let records = lock(&self.records);
            vms.iter()
                .filter(|vm| !records.contains_key(&vm.id))
                .map(|vm| vm.id.clone())
                .collect()
        };
        for id in unrecorded {
            tracing::warn!(vm = %id, "deleting a VM that no record holds");
            if let Err(e) = self.helper.delete(&id).await {
                tracing::warn!(vm = %id, error = %e, "cannot delete it");
            }
        }
    }

    // ----- the methods ---------------------------------------------------------------------

    async fn call(&self, request: &RpcRequest) -> Result<Value, RpcError> {
        match request.method.as_str() {
            "verify" => self.verify(request.params()?).await,
            "create" => self.create(request.params()?).await,
            "describe" => self.describe(request.params()?).await,
            "delete" => self.delete(request.params()?).await,
            "list" => self.list(request.params()?).await,
            _ => Err(RpcError::new(code::NO_METHOD, "no such method")),
        }
    }

    async fn verify(&self, params: wire::VerifyParams) -> Result<Value, RpcError> {
        let template = parse_template(&params.template)?;
        let checked = self.helper.check(&template).await.map_err(from_helper)?;
        Ok(json!({
            "problems": checked.problems,
            "maxVms": checked.free,
            "checked": checked.checked,
        }))
    }

    async fn create(&self, params: wire::CreateParams) -> Result<Value, RpcError> {
        let template = parse_template(&params.template)?;
        wire::check_name(&params.name)?;
        wire::check_labels(&params.labels)?;
        if !params.labels.contains_key(wire::HOST_LABEL) {
            return Err(RpcError::new(
                code::INVALID_PARAMS,
                format!("a VM needs the label {}", wire::HOST_LABEL),
            ));
        }
        let user_data = params.user_data.0.as_bytes();
        if user_data.is_empty() || user_data.len() as u64 > spool::MAX_USER_DATA {
            return Err(RpcError::new(
                code::INVALID_PARAMS,
                format!("the user data is 1 to {} bytes", spool::MAX_USER_DATA),
            ));
        }
        let id = VmId::generate().map_err(|e| RpcError::new(code::FAILED, e.to_string()))?;
        let record = Record {
            id: id.clone(),
            name: params.name.clone(),
            template: template.clone(),
            labels: params.labels.clone(),
        };
        {
            let mut records = lock(&self.records);
            if records.len() >= MAX_RECORDS {
                return Err(RpcError::new(code::FULL, "the listener holds too many VMs"));
            }
            records.insert(id.clone(), record.clone());
        }
        // The record is on disk before the VM exists: a VM that no record holds is a leftover.
        if let Err(e) = self.save_record(&record) {
            self.forget(&id);
            return Err(RpcError::new(
                code::FAILED,
                format!("cannot keep the VM's record: {}", e),
            ));
        }
        let spooled = spool::user_data_path(&self.config.spool, &id);
        let made = match write_spool(&spooled, user_data) {
            Ok(()) => self.helper.make(&id, &template).await.map_err(from_helper),
            Err(e) => Err(RpcError::new(
                code::FAILED,
                format!("cannot spool the user data: {}", e.kind()),
            )),
        };
        let _ = std::fs::remove_file(&spooled);
        match made {
            Ok(made) => {
                tracing::info!(vm = %id, name = %params.name, template = %template, port = made.port, "VM created");
                Ok(json!({ "id": id }))
            }
            Err(e) => {
                tracing::warn!(name = %params.name, template = %template, code = e.code, error = %e.message, "cannot create a VM");
                self.forget(&id);
                Err(e)
            }
        }
    }

    fn view(&self, record: &Record, vm: Option<&helper::HelperVm>) -> Vm {
        match vm {
            Some(vm) => Vm {
                id: record.id.to_string(),
                name: Some(record.name.clone()),
                state: match vm.state {
                    VmState::Running => "running",
                    VmState::Failed => "failed",
                }
                .into(),
                address: Some(self.config.address.clone()),
                port: Some(vm.port),
                labels: record.labels.clone(),
            },
            None => Vm {
                id: record.id.to_string(),
                name: Some(record.name.clone()),
                state: "gone".into(),
                address: None,
                port: None,
                labels: record.labels.clone(),
            },
        }
    }

    async fn describe(&self, params: wire::DescribeParams) -> Result<Value, RpcError> {
        let id = parse_id(&params.id)?;
        let Some(record) = lock(&self.records).get(&id).cloned() else {
            return Ok(json!({ "id": id, "state": "gone" }));
        };
        let listed = self.helper.list().await.map_err(from_helper)?;
        let vm = listed.vms.iter().find(|vm| vm.id == id);
        Ok(serde_json::to_value(self.view(&record, vm)).expect("a VM serializes"))
    }

    async fn delete(&self, params: wire::DeleteParams) -> Result<Value, RpcError> {
        if !params
            .labels
            .get(wire::HOST_LABEL)
            .is_some_and(|host| !host.is_empty())
        {
            return Err(RpcError::new(
                code::REFUSED,
                format!(
                    "a delete must name the host's label {}: not this host's to delete",
                    wire::HOST_LABEL
                ),
            ));
        }
        wire::check_labels(&params.labels)?;
        let id = parse_id(&params.id)?;
        let Some(record) = lock(&self.records).get(&id).cloned() else {
            return Ok(json!({}));
        };
        for (key, value) in &params.labels {
            if record.labels.get(key) != Some(value) {
                return Err(RpcError::new(
                    code::REFUSED,
                    format!(
                        "the VM {id} does not carry the label {key}: not this host's to delete"
                    ),
                ));
            }
        }
        self.helper.delete(&id).await.map_err(from_helper)?;
        self.forget(&id);
        tracing::info!(vm = %id, name = %record.name, "VM deleted");
        Ok(json!({}))
    }

    async fn list(&self, params: wire::ListParams) -> Result<Value, RpcError> {
        wire::check_labels(&params.labels)?;
        let listed = self.helper.list().await.map_err(from_helper)?;
        self.delete_unrecorded(&listed.vms).await;
        let records = lock(&self.records).clone();
        let vms: Vec<Vm> = listed
            .vms
            .iter()
            .filter_map(|vm| records.get(&vm.id).map(|record| (record, vm)))
            .filter(|(record, _)| {
                params
                    .labels
                    .iter()
                    .all(|(k, v)| record.labels.get(k) == Some(v))
            })
            .map(|(record, vm)| self.view(record, Some(vm)))
            .collect();
        Ok(json!({ "vms": vms }))
    }

    // ----- the death of a VMM ----------------------------------------------------------------

    /// Asks the helper once for VMs whose VMM died, and reports each new one on every
    /// connection that said `hello`.
    pub async fn watch_once(&self) {
        let Ok(listed) = self.helper.list().await else {
            return;
        };
        let records = lock(&self.records).clone();
        let mut reported = lock(&self.reported);
        reported.retain(|id| listed.vms.iter().any(|vm| &vm.id == id));
        for vm in &listed.vms {
            if vm.state == VmState::Failed
                && records.contains_key(&vm.id)
                && reported.insert(vm.id.clone())
            {
                tracing::warn!(vm = %vm.id, "the VM's VMM died");
                let _ = self.events.send(wire::notification(
                    wire::VM_FAILED,
                    json!({ "id": vm.id, "state": "failed", "reason": "the VM's VMM ended" }),
                ));
            }
        }
    }

    pub async fn watch(self: Arc<Self>) {
        let every = Duration::from_millis(self.config.watch_ms);
        loop {
            tokio::time::sleep(every).await;
            self.watch_once().await;
            if let Some(summary) = self.refusals.flush_at(Instant::now()) {
                tracing::warn!("{summary}");
            }
        }
    }

    // ----- a connection -----------------------------------------------------------------------

    /// Serves one connection of the control host until it closes.
    pub async fn serve_connection<S>(self: Arc<Self>, stream: S, peer: String)
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let (read, mut write) = tokio::io::split(stream);
        let (tx, mut rx) = mpsc::channel::<Value>(256);
        let writer = tokio::spawn(async move {
            while let Some(message) = rx.recv().await {
                let mut line = serde_json::to_vec(&message).expect("messages serialize");
                line.push(b'\n');
                if write.write_all(&line).await.is_err() || write.flush().await.is_err() {
                    break;
                }
            }
            let _ = write.shutdown().await;
        });
        let mut reader = BufReader::new(read);
        let in_flight = Arc::new(Semaphore::new(MAX_IN_FLIGHT));
        let mut forwarder: Option<tokio::task::JoinHandle<()>> = None;
        loop {
            let line = match local::read_line(&mut reader, wire::MAX_LINE).await {
                Ok(Some(line)) => line,
                Ok(None) | Err(LineError::Io(_)) => break,
                Err(LineError::TooLong) => {
                    tracing::warn!(
                        peer,
                        "a line over {} bytes; the connection is closed",
                        wire::MAX_LINE
                    );
                    let _ = tx
                        .send(wire::answer(
                            &Value::Null,
                            Err(RpcError::new(code::PARSE, "the line is too long")),
                        ))
                        .await;
                    break;
                }
            };
            let request = match RpcRequest::parse(&line) {
                Ok(request) => request,
                Err((id, e)) => {
                    tracing::info!(peer, code = e.code, "refused a request");
                    let _ = tx.send(wire::answer(&id, Err(e))).await;
                    continue;
                }
            };
            // A notification asks for no answer, and the listener takes none.
            let Some(id) = request.id.clone() else {
                continue;
            };
            if request.method == "hello" {
                let answer = self.hello(&request, &peer);
                if answer.is_ok() && forwarder.is_none() {
                    let mut events = self.events.subscribe();
                    let tx = tx.clone();
                    forwarder = Some(tokio::spawn(async move {
                        loop {
                            match events.recv().await {
                                Ok(event) => {
                                    if tx.send(event).await.is_err() {
                                        break;
                                    }
                                }
                                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                                Err(broadcast::error::RecvError::Closed) => break,
                            }
                        }
                    }));
                }
                let _ = tx.send(wire::answer(&id, answer)).await;
                continue;
            }
            if forwarder.is_none() {
                let _ = tx
                    .send(wire::answer(
                        &id,
                        Err(RpcError::new(code::HELLO_FIRST, "say hello first")),
                    ))
                    .await;
                continue;
            }
            let Ok(permit) = in_flight.clone().try_acquire_owned() else {
                let _ = tx
                    .send(wire::answer(
                        &id,
                        Err(RpcError::new(code::BUSY, "too many requests at once")),
                    ))
                    .await;
                continue;
            };
            let (listener, tx, peer) = (self.clone(), tx.clone(), peer.clone());
            tokio::spawn(async move {
                let result = listener.call(&request).await;
                if let Err(e) = &result {
                    // The code and the method only: a message may echo what the peer sent.
                    tracing::info!(
                        peer,
                        method = request.method_name(),
                        code = e.code,
                        "a request failed"
                    );
                }
                let _ = tx.send(wire::answer(&id, result)).await;
                drop(permit);
            });
        }
        if let Some(forwarder) = forwarder {
            forwarder.abort();
        }
        drop(tx);
        let _ = writer.await;
    }

    fn hello(&self, request: &RpcRequest, peer: &str) -> Result<Value, RpcError> {
        let params: wire::HelloParams = request.params()?;
        if params.wire != wire::WIRE {
            return Err(RpcError::new(
                code::MISMATCH,
                format!(
                    "this agent speaks wire {}, the driver {}",
                    wire::WIRE,
                    params.wire
                ),
            ));
        }
        let own = wire::build_id();
        if params.build != own {
            let build: String = params
                .build
                .chars()
                .take(64)
                .filter(|c| c.is_ascii_graphic())
                .collect();
            match self.config.peer_build {
                BuildPolicy::Refuse => {
                    tracing::warn!(peer, driver = %build, agent = own, "refused a driver of another build");
                    return Err(RpcError::new(
                        code::MISMATCH,
                        format!("the agent is {own}, the driver {build}: upgrade them together"),
                    ));
                }
                BuildPolicy::Warn => {
                    tracing::warn!(peer, driver = %build, agent = own, "a driver of another build");
                }
            }
        }
        Ok(json!({ "wire": wire::WIRE, "agent": wire::AGENT, "build": own }))
    }

    // ----- the transports -------------------------------------------------------------------

    /// Serves TLS connections. A connection first takes one of [`HANDSHAKE_SLOTS`] places for
    /// its handshake, which it must finish within [`HANDSHAKE_TIMEOUT`]; when they are all
    /// taken, the oldest pending handshake is dropped for the new one, so idle connections
    /// cannot keep the control host out. Only a client that presented the pinned certificate
    /// takes one of the [`MAX_CONNECTIONS`] served places, and nothing ever displaces it.
    pub async fn serve_tls(
        self: Arc<Self>,
        listener: TcpListener,
        config: Arc<rustls::ServerConfig>,
    ) {
        let acceptor = tokio_rustls::TlsAcceptor::from(config);
        let served = Arc::new(Semaphore::new(MAX_CONNECTIONS));
        let pending = Arc::new(Mutex::new(Pending::default()));
        loop {
            let (stream, addr) = match listener.accept().await {
                Ok(accepted) => accepted,
                Err(_) => {
                    self.refusals.note("an accept failed");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    continue;
                }
            };
            let (ticket, mut evicted) = {
                let mut pending = lock(&pending);
                let (ticket, evicted, displaced) = pending.enter();
                if displaced {
                    self.refusals
                        .note("a pending handshake was dropped for a newer one");
                }
                (ticket, evicted)
            };
            let (acceptor, listener, served, pending) = (
                acceptor.clone(),
                self.clone(),
                served.clone(),
                pending.clone(),
            );
            tokio::spawn(async move {
                let outcome = tokio::select! {
                    shaken = tokio::time::timeout(HANDSHAKE_TIMEOUT, acceptor.accept(stream)) => Some(shaken),
                    _ = &mut evicted => None,
                };
                lock(&pending).leave(ticket);
                let tls = match outcome {
                    Some(Ok(Ok(tls))) => tls,
                    Some(Ok(Err(_))) => {
                        listener
                            .refusals
                            .note("a TLS handshake failed (no or another certificate)");
                        return;
                    }
                    Some(Err(_)) => {
                        listener.refusals.note("a TLS handshake took too long");
                        return;
                    }
                    None => return,
                };
                let Ok(permit) = served.try_acquire_owned() else {
                    listener
                        .refusals
                        .note("the control host has every served connection open");
                    return;
                };
                tracing::info!(peer = %addr, "the control host connected");
                listener.serve_connection(tls, addr.to_string()).await;
                drop(permit);
            });
        }
    }

    /// Serves the local socket of a single machine: the runner host's user alone.
    pub async fn serve_unix(self: Arc<Self>, listener: UnixListener, peer_uid: u32) {
        let slots = Arc::new(Semaphore::new(MAX_CONNECTIONS));
        loop {
            let stream = match listener.accept().await {
                Ok((stream, _)) => stream,
                Err(_) => {
                    self.refusals.note("an accept failed");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    continue;
                }
            };
            let peer = stream.peer_cred().ok().map(|c| c.uid());
            if !local::peer_allowed(peer, peer_uid) {
                self.refusals
                    .note("a local peer that is not the runner host's user");
                continue;
            }
            let Ok(permit) = slots.clone().try_acquire_owned() else {
                self.refusals
                    .note("the runner host has every local connection open");
                continue;
            };
            let listener = self.clone();
            tokio::spawn(async move {
                listener
                    .serve_connection(stream, format!("uid {peer_uid}"))
                    .await;
                drop(permit);
            });
        }
    }
}

/// The TLS handshakes in progress, oldest first, each with the sender that drops it.
#[derive(Default)]
struct Pending {
    next: u64,
    queue: std::collections::VecDeque<(u64, tokio::sync::oneshot::Sender<()>)>,
}

impl Pending {
    /// A place for a new handshake: its ticket, the receiver that tells it it was dropped, and
    /// whether an older one was dropped to make the place.
    fn enter(&mut self) -> (u64, tokio::sync::oneshot::Receiver<()>, bool) {
        let mut displaced = false;
        while self.queue.len() >= HANDSHAKE_SLOTS {
            if let Some((_, oldest)) = self.queue.pop_front() {
                let _ = oldest.send(());
                displaced = true;
            }
        }
        self.next += 1;
        let (tx, rx) = tokio::sync::oneshot::channel();
        self.queue.push_back((self.next, tx));
        (self.next, rx, displaced)
    }

    fn leave(&mut self, ticket: u64) {
        self.queue.retain(|(t, _)| *t != ticket);
    }
}

/// Refusals, counted by kind and logged as one summary at most every [`REFUSAL_SUMMARY_EVERY`]:
/// a peer that is refused again and again cannot flood the journal. A summary names kinds and
/// counts only, never a peer's bytes.
#[derive(Default)]
pub struct Refusals {
    state: Mutex<RefusalState>,
}

#[derive(Default)]
struct RefusalState {
    counts: BTreeMap<&'static str, u64>,
    last: Option<Instant>,
}

impl Refusals {
    pub fn note(&self, why: &'static str) {
        if let Some(summary) = self.note_at(why, Instant::now()) {
            tracing::warn!("{summary}");
        }
    }

    /// Counts one refusal; the summary to log, when one is due at `now`.
    pub fn note_at(&self, why: &'static str, now: Instant) -> Option<String> {
        let mut state = lock(&self.state);
        *state.counts.entry(why).or_default() += 1;
        Self::due(&mut state, now)
    }

    /// The summary of what was counted since the last one, when one is due at `now`.
    pub fn flush_at(&self, now: Instant) -> Option<String> {
        Self::due(&mut lock(&self.state), now)
    }

    fn due(state: &mut RefusalState, now: Instant) -> Option<String> {
        if state.counts.is_empty()
            || state
                .last
                .is_some_and(|last| now.duration_since(last) < REFUSAL_SUMMARY_EVERY)
        {
            return None;
        }
        state.last = Some(now);
        let summary = std::mem::take(&mut state.counts)
            .into_iter()
            .map(|(why, n)| format!("{n} × {why}"))
            .collect::<Vec<_>>()
            .join("; ");
        Some(format!("refused: {summary}"))
    }
}

/// Removes every entry of the spool: at the listener's start, when no VM is being made.
pub fn clear_spool(dir: &Path) -> Result<usize> {
    let mut removed = 0;
    for entry in std::fs::read_dir(dir).with_context(|| format!("cannot read {}", dir.display()))? {
        let path = entry?.path();
        let meta = std::fs::symlink_metadata(&path)?;
        if meta.file_type().is_dir() {
            std::fs::remove_dir_all(&path)?;
        } else {
            std::fs::remove_file(&path)?;
        }
        removed += 1;
    }
    Ok(removed)
}

fn write_spool(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let mut file = std::fs::File::options()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(path)?;
    file.write_all(bytes)?;
    file.flush()
}

/// The suffix of a record that could not be read, moved aside for the operator.
pub const BAD_RECORD: &str = "bad";

/// Reads the records. A half-written `*.json.next` is removed; a record that cannot be read,
/// parsed, or that names another VM is moved aside (`<name>.bad`) and the start goes on: its
/// VM, if any, is then one that no record holds, and is deleted.
fn load_records(dir: &Path) -> Result<BTreeMap<VmId, Record>> {
    let mut records = BTreeMap::new();
    for entry in std::fs::read_dir(dir).with_context(|| format!("cannot read {}", dir.display()))? {
        let entry = entry?;
        let name = entry.file_name();
        let text = name.to_str().unwrap_or("");
        if text.ends_with(".json.next") {
            let _ = std::fs::remove_file(entry.path());
            continue;
        }
        let Some(id) = text
            .strip_suffix(".json")
            .and_then(|n| VmId::parse(n.as_bytes()))
        else {
            continue;
        };
        let record = crate::plan::read_bounded(&entry.path(), 64 * 1024)
            .ok()
            .and_then(|bytes| crate::strict::value(&bytes).ok())
            .and_then(|value| serde_json::from_value::<Record>(value).ok())
            .filter(|record| record.id == id);
        match record {
            Some(record) => {
                records.insert(id, record);
            }
            None => {
                tracing::warn!(record = %id, "a record that cannot be read is moved aside");
                let aside = entry.path().with_extension(format!("json.{BAD_RECORD}"));
                if std::fs::rename(entry.path(), &aside).is_err() {
                    tracing::warn!(record = %id, "cannot move it aside");
                }
            }
        }
    }
    Ok(records)
}

/// `tinfirm-node-agent listener`: starts the listener's transports and its watch.
pub async fn run(config: ListenerConfig) -> Result<()> {
    let listener = Listener::start(config.clone()).await?;
    let own = local::own_uid()?;
    let mut tasks = tokio::task::JoinSet::new();
    tasks.spawn(listener.clone().watch());
    if let Some(tls) = &config.tls {
        let (cert, key) = crate::tls::load(&tls.certificate, &tls.key)?;
        let pin = crate::tls::parse_pin(&tls.client_sha256)?;
        let server = crate::tls::server_config(cert, key, pin)?;
        let tcp = TcpListener::bind(tls.listen)
            .await
            .with_context(|| format!("cannot listen on {}", tls.listen))?;
        tracing::info!(listen = %tls.listen, "the listener takes TLS");
        tasks.spawn(listener.clone().serve_tls(tcp, server));
    }
    if let Some(socket) = &config.socket {
        let unix = local::bind(&socket.path, own)?;
        tracing::info!(socket = %socket.path.display(), peer_uid = socket.peer_uid, "the listener takes its local socket");
        tasks.spawn(listener.clone().serve_unix(unix, socket.peer_uid));
    }
    while tasks.join_next().await.is_some() {}
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refusals_are_logged_as_one_summary_at_most_every_interval() {
        let refusals = Refusals::default();
        let t0 = Instant::now();
        let first = refusals
            .note_at("a TLS handshake took too long", t0)
            .unwrap();
        assert_eq!(first, "refused: 1 × a TLS handshake took too long");
        for i in 0..1000 {
            let at = t0 + Duration::from_millis(i);
            assert_eq!(refusals.note_at("a TLS handshake took too long", at), None);
        }
        assert_eq!(
            refusals.note_at("a local peer that is not the runner host's user", t0),
            None
        );
        assert_eq!(refusals.flush_at(t0 + Duration::from_secs(1)), None);
        let summary = refusals.flush_at(t0 + REFUSAL_SUMMARY_EVERY).unwrap();
        assert_eq!(
            summary,
            "refused: 1000 × a TLS handshake took too long; 1 × a local peer that is not the runner host's user"
        );
        // Nothing new, nothing to say.
        assert_eq!(refusals.flush_at(t0 + 3 * REFUSAL_SUMMARY_EVERY), None);
    }

    #[tokio::test]
    async fn a_new_handshake_drops_the_oldest_pending_one_when_all_places_are_taken() {
        let mut pending = Pending::default();
        let mut receivers = Vec::new();
        for _ in 0..HANDSHAKE_SLOTS {
            let (ticket, rx, displaced) = pending.enter();
            assert!(!displaced);
            receivers.push((ticket, rx));
        }
        let (newest, _rx, displaced) = pending.enter();
        assert!(displaced);
        assert_eq!(pending.queue.len(), HANDSHAKE_SLOTS);
        // The oldest is told to stop; the others are not.
        let (_, oldest) = receivers.remove(0);
        assert!(oldest.await.is_ok());
        let (second, mut rx) = receivers.remove(0);
        assert!(rx.try_recv().is_err());
        pending.leave(second);
        pending.leave(newest);
        assert_eq!(pending.queue.len(), HANDSHAKE_SLOTS - 2);
    }

    #[test]
    fn the_spool_is_emptied_whatever_is_in_it() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(
            dir.path()
                .join("vm-00000000000000000000000000000001.userdata"),
            b"ticket",
        )
        .unwrap();
        std::fs::create_dir(dir.path().join("a-dir")).unwrap();
        std::fs::write(dir.path().join("a-dir/x"), b"x").unwrap();
        std::os::unix::fs::symlink("/etc/passwd", dir.path().join("a-link")).unwrap();
        assert_eq!(clear_spool(dir.path()).unwrap(), 3);
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
        assert!(std::path::Path::new("/etc/passwd").exists());
    }

    #[test]
    fn a_bad_record_is_moved_aside_and_a_half_written_one_removed() {
        let dir = tempfile::tempdir().unwrap();
        let good = VmId::generate().unwrap();
        let record = Record {
            id: good.clone(),
            name: "n".into(),
            template: TemplateName::parse(b"sandbox-4c8g").unwrap(),
            labels: BTreeMap::from([("tinfirm-host".to_string(), "h".to_string())]),
        };
        std::fs::write(
            dir.path().join(format!("{good}.json")),
            serde_json::to_vec(&record).unwrap(),
        )
        .unwrap();
        let broken = VmId::generate().unwrap();
        std::fs::write(dir.path().join(format!("{broken}.json")), b"{not json").unwrap();
        let twice = VmId::generate().unwrap();
        std::fs::write(
            dir.path().join(format!("{twice}.json")),
            format!(r#"{{"id":"{twice}","id":"{twice}","name":"n","template":"t","labels":{{}}}}"#),
        )
        .unwrap();
        let other = VmId::generate().unwrap();
        std::fs::write(
            dir.path().join(format!("{other}.json")),
            serde_json::to_vec(&record).unwrap(),
        )
        .unwrap();
        std::fs::write(dir.path().join(format!("{good}.json.next")), b"{\"id\":").unwrap();
        let records = load_records(dir.path()).unwrap();
        assert_eq!(records.keys().collect::<Vec<_>>(), [&good]);
        for id in [&broken, &twice, &other] {
            assert!(!dir.path().join(format!("{id}.json")).exists());
            assert!(dir.path().join(format!("{id}.json.bad")).exists(), "{id}");
        }
        assert!(!dir.path().join(format!("{good}.json.next")).exists());
        // A second start reads the same and leaves the moved ones alone.
        assert_eq!(load_records(dir.path()).unwrap().len(), 1);
        assert!(dir.path().join(format!("{broken}.json.bad")).exists());
    }
}
