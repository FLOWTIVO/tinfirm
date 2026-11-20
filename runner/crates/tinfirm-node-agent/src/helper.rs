//! The helper: root, no network code. It does four things over a local socket that only the
//! listener's user may open (check the node, make a VM, delete a VM, list the VMs), and nothing
//! else. A caller names a VM's id ([`VmId`]) and a template of the helper's own configuration
//! ([`TemplateName`]); the helper derives everything else ([`crate::plan`]) and reads the VM's
//! user data from the one file of the listener's that the id names ([`crate::spool`]).
//!
//! The wire: one request line of at most [`MAX_REQUEST`] bytes, one answer line, and the
//! connection closes. A peer that is not the listener's uid gets nothing: the connection is
//! closed before a byte is read.

use std::collections::{BTreeMap, BTreeSet};
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use tokio::io::{AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};

use crate::backend::{Backend, BackendError, Present};
use crate::id::{TemplateName, VmId};
use crate::local::{self, LineError};
use crate::plan::{self, HelperConfig, MAX_SERIAL, Names};
use crate::spool;

/// The version of the helper's wire (`v` in every request).
pub const HELPER_WIRE: u64 = 1;
/// The longest request line, in bytes.
pub const MAX_REQUEST: usize = 4096;
/// The longest answer line, in bytes.
pub const MAX_ANSWER: usize = 1024 * 1024;
/// How long a peer may take to send its request.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
/// How many connections the helper serves at once; more are closed at once.
pub const MAX_CONNECTIONS: usize = 32;
/// The helper's record of its VMs, in its state directory.
pub const STATE_FILE: &str = "vms.json";
/// The lock that one helper holds on its state directory.
pub const LOCK_FILE: &str = "lock";

/// A request. Its fields are exactly these: any other is refused.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "lowercase", deny_unknown_fields)]
pub enum Request {
    Check { template: TemplateName },
    Make { id: VmId, template: TemplateName },
    Delete { id: VmId },
    List {},
}

impl Request {
    /// The request's line: the request and `v`, and a newline.
    pub fn to_line(&self) -> Vec<u8> {
        let mut value = serde_json::to_value(self).expect("requests serialize");
        if let Value::Object(map) = &mut value {
            map.insert("v".into(), HELPER_WIRE.into());
        }
        let mut line = serde_json::to_vec(&value).expect("requests serialize");
        line.push(b'\n');
        line
    }

    /// A request line: a JSON object with `v` equal to [`HELPER_WIRE`] and the fields of one
    /// request, each once, and nothing else. Ids and template names are checked as they are
    /// read. Every refusal is one of a few fixed messages: none holds a byte the peer sent, so
    /// neither the log nor the answer does.
    pub fn parse(line: &[u8]) -> Result<Request, HelperError> {
        let bad = |why: &'static str| HelperError::new(Code::BadRequest, why);
        let mut map = crate::strict::object(line)
            .map_err(|_| bad("the request is not one JSON object with each key once"))?;
        match map.remove("v") {
            Some(v) if v.as_u64() == Some(HELPER_WIRE) => {}
            Some(_) => return Err(bad("the helper speaks wire 1")),
            None => return Err(bad("the request has no `v`")),
        }
        serde_json::from_value(Value::Object(map)).map_err(|_| {
            bad("the request is not one of check, make, delete and list with exactly its fields")
        })
    }
}

/// What the helper reports of one VM.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HelperVm {
    pub id: VmId,
    pub template: TemplateName,
    pub state: VmState,
    /// The node's port on which its bridge is published.
    pub port: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum VmState {
    Running,
    /// Its VMM died; it stays until it is deleted.
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CheckAnswer {
    pub problems: Vec<String>,
    pub checked: Vec<String>,
    /// How many more VMs the node may make now.
    pub free: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MakeAnswer {
    pub port: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ListAnswer {
    pub vms: Vec<HelperVm>,
    pub free: u32,
}

/// What went wrong, as the answer names it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Code {
    BadRequest,
    UnknownTemplate,
    Exists,
    Full,
    UserData,
    NotBuilt,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HelperError {
    pub code: Code,
    pub message: String,
}

impl HelperError {
    pub fn new(code: Code, message: impl Into<String>) -> HelperError {
        HelperError {
            code,
            message: message.into(),
        }
    }
}

impl std::fmt::Display for HelperError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} ({:?})", self.message, self.code)
    }
}

impl From<BackendError> for HelperError {
    fn from(e: BackendError) -> HelperError {
        match e {
            BackendError::NotBuilt(_) => HelperError::new(Code::NotBuilt, e.to_string()),
            BackendError::Failed(_) => HelperError::new(Code::Failed, e.to_string()),
        }
    }
}

/// An answer line: `{"ok": …}` or `{"error": {"code", "message"}}`.
pub fn answer_line(answer: &Result<Value, HelperError>) -> Vec<u8> {
    let value = match answer {
        Ok(ok) => json!({ "ok": ok }),
        Err(e) => json!({ "error": e }),
    };
    let mut line = serde_json::to_vec(&value).expect("answers serialize");
    line.push(b'\n');
    line
}

// ----- the record of the VMs -------------------------------------------------------------

/// One VM the helper made.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Held {
    id: VmId,
    template: TemplateName,
    slot: u32,
    serial: u64,
}

/// The helper's record on disk: its VMs, and the next serial, which only grows.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct State {
    version: u32,
    next_serial: u64,
    vms: Vec<Held>,
}

impl State {
    fn load(dir: &Path) -> Result<State> {
        let path = dir.join(STATE_FILE);
        if !path.exists() {
            return Ok(State {
                version: 1,
                ..State::default()
            });
        }
        let bytes = plan::read_bounded(&path, 16 * 1024 * 1024)?;
        let state: State = serde_json::from_slice(&bytes)
            .with_context(|| format!("{} is not the helper's record", path.display()))?;
        if state.version != 1 {
            bail!("{} is of version {}, not 1", path.display(), state.version);
        }
        let (mut slots, mut serials, mut ids) = (
            std::collections::BTreeSet::new(),
            std::collections::BTreeSet::new(),
            std::collections::BTreeSet::new(),
        );
        for vm in &state.vms {
            if vm.serial >= state.next_serial
                || vm.slot >= plan::MAX_SLOTS
                || !slots.insert(vm.slot)
                || !serials.insert(vm.serial)
                || !ids.insert(vm.id.clone())
            {
                bail!("{} is inconsistent: VM {}", path.display(), vm.id);
            }
        }
        Ok(state)
    }

    /// Written whole to a new file, then renamed over the old one.
    fn save(&self, dir: &Path) -> Result<()> {
        let path = dir.join(STATE_FILE);
        let next = dir.join(format!("{STATE_FILE}.next"));
        let _ = std::fs::remove_file(&next);
        let mut file = std::fs::File::options()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&next)
            .with_context(|| format!("cannot write {}", next.display()))?;
        file.write_all(&serde_json::to_vec(self).expect("the record serializes"))?;
        file.sync_all()?;
        std::fs::rename(&next, &path)
            .with_context(|| format!("cannot replace {}", path.display()))?;
        Ok(())
    }
}

// ----- the helper ------------------------------------------------------------------------

/// The helper: its configuration, its backend and its record. One operation at a time.
pub struct Helper<B: Backend> {
    config: HelperConfig,
    backend: B,
    state: Mutex<State>,
    /// The helper's own uid (root): the owner the spool's parent must have.
    own_uid: u32,
    /// The lock on the state directory, held while this helper lives: a second helper on the
    /// same state would sweep this one's VMs as leftovers, and this one the other's.
    _lock: std::fs::File,
}

/// What the backend finds on the node beyond the helper's record.
#[derive(Debug, Default, PartialEq, Eq)]
struct Leftovers {
    /// The slots of VMs that no record holds and that are still on the node: never handed out.
    held: BTreeSet<u32>,
    /// Jails of a form the helper does not know, escaped: while one is there, no slot is
    /// handed out, since the slot it holds cannot be known.
    unknown: Vec<String>,
    /// The highest serial on the node.
    highest: Option<u64>,
}

/// A jail's name as the operator may read it: escaped, and at most 80 characters.
fn shown(jail: &str) -> String {
    jail.escape_debug().take(80).collect()
}

/// The leftovers in `present`, against the record's serials. Pure: it removes nothing.
fn leftovers(present: &[Present], recorded: &BTreeSet<u64>) -> Leftovers {
    let mut found = Leftovers::default();
    for p in present {
        match p.names {
            None => found.unknown.push(shown(&p.jail)),
            Some(names) => {
                found.highest = found.highest.max(Some(names.serial));
                if !recorded.contains(&names.serial) {
                    found.held.insert(names.slot);
                }
            }
        }
    }
    found
}

impl<B: Backend> Helper<B> {
    /// Checks the helper's directories and the listener's spool, reads its record, and removes
    /// what an earlier helper left on the node that the record does not hold. What it cannot
    /// remove keeps its slot (read from its jail's name) until a later sweep removes it.
    pub fn start(config: HelperConfig, backend: B) -> Result<Helper<B>> {
        config.validate()?;
        let own = local::own_uid()?;
        let dir = local::DirFacts::of(&config.state)
            .with_context(|| format!("cannot read {}", config.state.display()))?;
        if let Err(problem) = local::check_private_dir(&dir, own) {
            bail!(
                "the helper's state directory {} {problem}",
                config.state.display()
            );
        }
        spool::check_spool(&config.spool, config.listener_uid, own)
            .map_err(|e| anyhow::anyhow!("{e}"))?;
        let lock = std::fs::File::options()
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(config.state.join(LOCK_FILE))
            .with_context(|| format!("cannot open the lock in {}", config.state.display()))?;
        if lock.try_lock().is_err() {
            bail!(
                "another helper holds the state directory {}: two helpers on one state would remove each other's VMs",
                config.state.display()
            );
        }
        let mut state = State::load(&config.state)?;
        let helper = Helper {
            config,
            backend,
            state: Mutex::new(State::default()),
            own_uid: own,
            _lock: lock,
        };
        let found = helper
            .sweep(&state)
            .map_err(|e| anyhow::anyhow!("cannot list the node's VMs: {e}"))?;
        for jail in &found.unknown {
            tracing::error!(jail = %jail, "a jail of a form the helper does not know is on the node: no VM is made until it is removed");
        }
        // A name that is on the node is never handed out again, even with the record lost.
        if let Some(highest) = found.highest
            && highest >= state.next_serial
        {
            state.next_serial = highest + 1;
            state.save(&helper.config.state)?;
        }
        *helper.state() = state;
        Ok(helper)
    }

    pub fn config(&self) -> &HelperConfig {
        &self.config
    }

    pub fn backend(&self) -> &B {
        &self.backend
    }

    fn state(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Lists the node, tries to remove every VM that the record does not hold, and returns what
    /// is left: the slots those still hold, and the jails whose slot cannot be known.
    fn sweep(&self, state: &State) -> Result<Leftovers, BackendError> {
        let recorded: BTreeSet<u64> = state.vms.iter().map(|vm| vm.serial).collect();
        let present = self.backend.list()?;
        let mut found = leftovers(&present, &recorded);
        found.held.clear();
        for p in &present {
            let Some(names) = p.names else { continue };
            if recorded.contains(&names.serial) {
                continue;
            }
            tracing::warn!(jail = %names.jail(), "removing a VM that no record holds");
            if let Err(e) = self.backend.delete(&names) {
                tracing::warn!(jail = %names.jail(), slot = names.slot, error = %e, "cannot remove it: its slot stays held");
                found.held.insert(names.slot);
            }
        }
        Ok(found)
    }

    /// The slots in use: the record's, and those of leftovers.
    fn used(&self, state: &State, found: &Leftovers) -> BTreeSet<u32> {
        state
            .vms
            .iter()
            .map(|vm| vm.slot)
            .chain(found.held.iter().copied())
            .collect()
    }

    fn free(&self, state: &State, found: &Leftovers) -> u32 {
        if !found.unknown.is_empty() {
            return 0;
        }
        let used = self
            .used(state, found)
            .into_iter()
            .filter(|slot| *slot < self.config.max_vms)
            .count() as u32;
        self.config.max_vms.saturating_sub(used)
    }

    /// One request, as the server hands it on.
    pub fn handle(&self, request: Request) -> Result<Value, HelperError> {
        let value = |v: Result<Value, serde_json::Error>| {
            v.map_err(|_| HelperError::new(Code::Failed, "the answer cannot be written"))
        };
        match request {
            Request::Check { template } => value(serde_json::to_value(self.check(&template)?)),
            Request::Make { id, template } => {
                value(serde_json::to_value(self.make(&id, &template)?))
            }
            Request::Delete { id } => {
                self.delete(&id)?;
                Ok(json!({}))
            }
            Request::List {} => value(serde_json::to_value(self.list()?)),
        }
    }

    pub fn check(&self, template: &TemplateName) -> Result<CheckAnswer, HelperError> {
        let t = self.template(template)?;
        let mut checked = self.backend.check(t);
        match spool::check_spool(&self.config.spool, self.config.listener_uid, self.own_uid) {
            Ok(()) => checked.checked.push("the listener's spool".into()),
            Err(e) => checked.problems.push(e.to_string()),
        }
        let state = self.state();
        let found = self.sweep(&state)?;
        for jail in &found.unknown {
            checked.problems.push(format!(
                "a jail of a form the helper does not know, \"{jail}\", is on the node: its slot cannot be known, so no VM is made until it is removed by hand"
            ));
        }
        if !found.held.is_empty() {
            checked.problems.push(format!(
                "{} VM(s) that no record holds cannot be removed; their slots stay held",
                found.held.len()
            ));
        }
        Ok(CheckAnswer {
            problems: checked.problems,
            checked: checked.checked,
            free: self.free(&state, &found),
        })
    }

    fn template(&self, name: &TemplateName) -> Result<&plan::Template, HelperError> {
        self.config.templates.get(name).ok_or_else(|| {
            HelperError::new(
                Code::UnknownTemplate,
                format!("no template `{name}` in the helper's configuration"),
            )
        })
    }

    pub fn make(&self, id: &VmId, template_name: &TemplateName) -> Result<MakeAnswer, HelperError> {
        let template = self.template(template_name)?.clone();
        let mut state = self.state();
        if state.vms.iter().any(|vm| &vm.id == id) {
            return Err(HelperError::new(
                Code::Exists,
                format!("the VM {id} exists"),
            ));
        }
        let found = self.sweep(&state)?;
        if !found.unknown.is_empty() {
            return Err(HelperError::new(
                Code::Failed,
                "a jail of a form the helper does not know is on the node: no slot is handed out until it is removed",
            ));
        }
        let slot =
            plan::free_slot(self.used(&state, &found), self.config.max_vms).ok_or_else(|| {
                HelperError::new(
                    Code::Full,
                    format!("the node runs its {} VMs", self.config.max_vms),
                )
            })?;
        let user_data = spool::read(
            &self.config.spool,
            id,
            self.config.listener_uid,
            self.own_uid,
        )
        .map_err(|e| HelperError::new(Code::UserData, e.to_string()))?;
        let serial = state
            .next_serial
            .max(found.highest.map_or(0, |h| h.saturating_add(1)));
        if serial > MAX_SERIAL {
            return Err(HelperError::new(
                Code::Failed,
                "the node has used every serial",
            ));
        }
        let before = state.clone();
        state.next_serial = serial + 1;
        state.vms.push(Held {
            id: id.clone(),
            template: template_name.clone(),
            slot,
            serial,
        });
        if let Err(e) = state.save(&self.config.state) {
            *state = before;
            return Err(HelperError::new(Code::Failed, format!("{e:#}")));
        }
        let plan = plan::plan(&self.config, &template, slot, serial);
        let made = self.backend.make(&plan, &user_data);
        // The VM has the user data, or never will: the helper keeps no copy of it. The bytes
        // are overwritten when they are dropped, here and on every refusal above.
        drop(user_data);
        if let Err(e) = made {
            tracing::warn!(vm = %id, jail = %plan.names.jail(), error = %e, "cannot make the VM");
            match self.backend.delete(&plan.names) {
                Ok(()) => {
                    // The serial stays used: its name is never handed out again.
                    state.vms.retain(|vm| &vm.id != id);
                    let _ = state.save(&self.config.state);
                }
                Err(e) => {
                    // What the make left may still run: the VM stays in the record, `failed`,
                    // and holds its slot until a delete succeeds.
                    tracing::warn!(vm = %id, jail = %plan.names.jail(), error = %e, "cannot remove what the make left: its slot stays held until a delete succeeds");
                }
            }
            return Err(e.into());
        }
        tracing::info!(vm = %id, template = %template_name, slot, jail = %plan.names.jail(), port = plan.published_port, "VM made");
        Ok(MakeAnswer {
            port: plan.published_port,
        })
    }

    pub fn delete(&self, id: &VmId) -> Result<(), HelperError> {
        let mut state = self.state();
        let Some(held) = state.vms.iter().find(|vm| &vm.id == id).cloned() else {
            return Ok(());
        };
        let names = Names {
            serial: held.serial,
            slot: held.slot,
        };
        self.backend.delete(&names)?;
        state.vms.retain(|vm| &vm.id != id);
        state
            .save(&self.config.state)
            .map_err(|e| HelperError::new(Code::Failed, format!("{e:#}")))?;
        tracing::info!(vm = %id, jail = %names.jail(), "VM deleted");
        Ok(())
    }

    /// The record's VMs, each `running` while its VMM runs and `failed` otherwise. It removes
    /// nothing: the listener asks for it every second.
    pub fn list(&self) -> Result<ListAnswer, HelperError> {
        let state = self.state();
        let present = self.backend.list()?;
        let alive: BTreeMap<u64, bool> = present
            .iter()
            .filter_map(|p| p.names.map(|n| (n.serial, p.alive)))
            .collect();
        let recorded: BTreeSet<u64> = state.vms.iter().map(|vm| vm.serial).collect();
        let found = leftovers(&present, &recorded);
        let vms = state
            .vms
            .iter()
            .map(|vm| HelperVm {
                id: vm.id.clone(),
                template: vm.template.clone(),
                state: if alive.get(&vm.serial) == Some(&true) {
                    VmState::Running
                } else {
                    VmState::Failed
                },
                port: plan::published_port(self.config.network.first_port, vm.slot),
            })
            .collect();
        Ok(ListAnswer {
            vms,
            free: self.free(&state, &found),
        })
    }
}

// ----- the server ------------------------------------------------------------------------

/// Serves the helper's wire on `listener` for the peer `listener_uid` alone, until the process
/// ends.
pub async fn serve<B: Backend>(helper: Arc<Helper<B>>, listener: UnixListener) {
    let slots = Arc::new(tokio::sync::Semaphore::new(MAX_CONNECTIONS));
    loop {
        let stream = match listener.accept().await {
            Ok((stream, _)) => stream,
            Err(e) => {
                tracing::warn!(error = %e, "cannot accept a connection");
                tokio::time::sleep(Duration::from_millis(100)).await;
                continue;
            }
        };
        let Ok(permit) = slots.clone().try_acquire_owned() else {
            tracing::warn!("too many connections; one is closed");
            continue;
        };
        let helper = helper.clone();
        tokio::spawn(async move {
            serve_one(helper, stream).await;
            drop(permit);
        });
    }
}

async fn serve_one<B: Backend>(helper: Arc<Helper<B>>, stream: UnixStream) {
    let expected = helper.config.listener_uid;
    let peer = stream.peer_cred().ok().map(|c| c.uid());
    if !local::peer_allowed(peer, expected) {
        tracing::warn!(peer = ?peer, "refused a peer that is not the listener's user");
        return;
    }
    let (read, mut write) = stream.into_split();
    let mut reader = BufReader::new(read);
    let answer =
        match tokio::time::timeout(REQUEST_TIMEOUT, local::read_line(&mut reader, MAX_REQUEST))
            .await
        {
            Err(_) => Err(HelperError::new(Code::BadRequest, "no request in time")),
            Ok(Err(LineError::TooLong)) => Err(HelperError::new(
                Code::BadRequest,
                format!("the request is over {MAX_REQUEST} bytes"),
            )),
            Ok(Err(LineError::Io(_))) | Ok(Ok(None)) => return,
            Ok(Ok(Some(line))) => match Request::parse(&line) {
                Err(e) => Err(e),
                Ok(request) => {
                    let helper = helper.clone();
                    match tokio::task::spawn_blocking(move || helper.handle(request)).await {
                        Ok(answer) => answer,
                        Err(_) => Err(HelperError::new(Code::Failed, "the operation failed")),
                    }
                }
            },
        };
    if let Err(e) = &answer {
        tracing::info!(code = ?e.code, "refused a request");
    }
    let _ = write.write_all(&answer_line(&answer)).await;
    let _ = write.shutdown().await;
}

/// Starts the helper's socket and serves it: `tinfirm-node-agent helper`.
pub async fn run<B: Backend>(helper: Helper<B>) -> Result<()> {
    let own = local::own_uid()?;
    let socket = helper.config.socket.clone();
    let listener = local::bind(&socket, own)?;
    tracing::info!(socket = %socket.display(), listener_uid = helper.config.listener_uid, "the helper listens");
    serve(Arc::new(helper), listener).await;
    Ok(())
}

// ----- the listener's side ---------------------------------------------------------------

/// The listener's end of the helper's wire.
#[derive(Debug, Clone)]
pub struct HelperClient {
    socket: PathBuf,
    timeout: Duration,
}

impl HelperClient {
    pub fn new(socket: PathBuf) -> HelperClient {
        HelperClient {
            socket,
            timeout: Duration::from_secs(60),
        }
    }

    /// One request and its answer.
    pub async fn call(&self, request: &Request) -> Result<Value, HelperError> {
        let unreachable = |why: String| HelperError::new(Code::Failed, why);
        let work = async {
            let stream = UnixStream::connect(&self.socket)
                .await
                .map_err(|e| unreachable(format!("cannot reach the helper: {}", e.kind())))?;
            let (read, mut write) = stream.into_split();
            write
                .write_all(&request.to_line())
                .await
                .map_err(|e| unreachable(format!("cannot ask the helper: {}", e.kind())))?;
            let mut reader = BufReader::new(read);
            let line = match local::read_line(&mut reader, MAX_ANSWER).await {
                Ok(Some(line)) => line,
                _ => return Err(unreachable("the helper did not answer".into())),
            };
            let mut answer: Map<String, Value> = crate::strict::object(&line)
                .map_err(|_| unreachable("the helper's answer is not one JSON object".into()))?;
            match (answer.remove("ok"), answer.remove("error")) {
                (Some(ok), None) if answer.is_empty() => Ok(ok),
                (None, Some(error)) if answer.is_empty() => Err(serde_json::from_value(error)
                    .unwrap_or_else(|_| unreachable("the helper's error is not readable".into()))),
                _ => Err(unreachable("the helper's answer is not readable".into())),
            }
        };
        match tokio::time::timeout(self.timeout, work).await {
            Ok(answer) => answer,
            Err(_) => Err(unreachable("the helper did not answer in time".into())),
        }
    }

    pub async fn check(&self, template: &TemplateName) -> Result<CheckAnswer, HelperError> {
        typed(
            self.call(&Request::Check {
                template: template.clone(),
            })
            .await?,
        )
    }

    pub async fn make(
        &self,
        id: &VmId,
        template: &TemplateName,
    ) -> Result<MakeAnswer, HelperError> {
        typed(
            self.call(&Request::Make {
                id: id.clone(),
                template: template.clone(),
            })
            .await?,
        )
    }

    pub async fn delete(&self, id: &VmId) -> Result<(), HelperError> {
        self.call(&Request::Delete { id: id.clone() })
            .await
            .map(|_| ())
    }

    pub async fn list(&self) -> Result<ListAnswer, HelperError> {
        typed(self.call(&Request::List {}).await?)
    }
}

fn typed<T: serde::de::DeserializeOwned>(value: Value) -> Result<T, HelperError> {
    serde_json::from_value(value)
        .map_err(|_| HelperError::new(Code::Failed, "the helper's answer is not readable"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::backend::FakeBackend;
    use crate::plan::tests::config;
    use std::os::unix::fs::PermissionsExt;

    const ID: &str = "vm-0123456789abcdef0123456789abcdef";

    #[test]
    fn requests_are_read_only_in_their_one_form() {
        let make = Request::parse(
            format!(r#"{{"v":1,"op":"make","id":"{ID}","template":"sandbox-4c8g"}}"#).as_bytes(),
        )
        .unwrap();
        assert_eq!(
            make,
            Request::Make {
                id: VmId::parse(ID.as_bytes()).unwrap(),
                template: TemplateName::parse(b"sandbox-4c8g").unwrap()
            }
        );
        assert_eq!(
            Request::parse(br#"{"v":1,"op":"list"}"#).unwrap(),
            Request::List {}
        );
        assert_eq!(
            Request::parse(&make.to_line()[..make.to_line().len() - 1]).unwrap(),
            make
        );

        let refused = [
            // An extra field, in each kind of request.
            format!(r#"{{"v":1,"op":"make","id":"{ID}","template":"sandbox-4c8g","path":"/etc"}}"#),
            format!(r#"{{"v":1,"op":"make","id":"{ID}","template":"sandbox-4c8g","memoryMib":1}}"#),
            format!(r#"{{"v":1,"op":"delete","id":"{ID}","force":true}}"#),
            r#"{"v":1,"op":"list","labels":{}}"#.to_string(),
            r#"{"v":1,"op":"check","template":"sandbox-4c8g","x":1}"#.to_string(),
            // A missing field, an unknown operation, no version, another version.
            r#"{"v":1,"op":"make","template":"sandbox-4c8g"}"#.to_string(),
            r#"{"v":1,"op":"exec","command":"sh"}"#.to_string(),
            format!(r#"{{"op":"delete","id":"{ID}"}}"#),
            format!(r#"{{"v":2,"op":"delete","id":"{ID}"}}"#),
            format!(r#"{{"v":"1","op":"delete","id":"{ID}"}}"#),
            // Malformed ids and templates.
            format!(r#"{{"v":1,"op":"delete","id":"{ID}\n"}}"#),
            format!(r#"{{"v":1,"op":"delete","id":"{}"}}"#, ID.to_uppercase()),
            r#"{"v":1,"op":"delete","id":"vm-../../etc"}"#.to_string(),
            r#"{"v":1,"op":"delete","id":""}"#.to_string(),
            format!(r#"{{"v":1,"op":"make","id":"{ID}","template":"../sandbox"}}"#),
            format!(r#"{{"v":1,"op":"make","id":"{ID}","template":"Sandbox"}}"#),
            // Not an object at all.
            "[]".to_string(),
            "".to_string(),
            "{".to_string(),
        ];
        for line in refused {
            let e = Request::parse(line.as_bytes()).unwrap_err();
            assert_eq!(e.code, Code::BadRequest, "{line}");
        }
    }

    pub(crate) struct Fixture {
        pub dir: tempfile::TempDir,
        pub config: HelperConfig,
        pub backend: FakeBackend,
    }

    /// A helper's configuration in a directory of its own, with the listener's uid this test's.
    pub(crate) fn fixture() -> Fixture {
        let dir = tempfile::tempdir().unwrap();
        // The spool's parent must not be writable by its group or by others, whatever the
        // umask the tests run under made of the new directory.
        std::fs::set_permissions(dir.path(), std::fs::Permissions::from_mode(0o700)).unwrap();
        let mut config = config();
        let uid = local::own_uid().unwrap();
        config.listener_uid = uid;
        config.allow_root_listener = true;
        for name in ["state", "spool", "sockets"] {
            std::fs::create_dir(dir.path().join(name)).unwrap();
            std::fs::set_permissions(
                dir.path().join(name),
                std::fs::Permissions::from_mode(0o700),
            )
            .unwrap();
        }
        config.state = dir.path().join("state");
        config.spool = dir.path().join("spool");
        config.socket = dir.path().join("sockets/helper.sock");
        config.max_vms = 3;
        Fixture {
            dir,
            config,
            backend: FakeBackend::new(),
        }
    }

    fn write_user_data(f: &Fixture, id: &VmId, bytes: &[u8]) {
        std::fs::write(spool::user_data_path(&f.config.spool, id), bytes).unwrap();
    }

    fn template() -> TemplateName {
        TemplateName::parse(b"sandbox-4c8g").unwrap()
    }

    #[test]
    fn a_second_helper_on_the_same_state_does_not_start() {
        let f = fixture();
        let first = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
        let second = Helper::start(f.config.clone(), f.backend.clone());
        assert!(
            second.is_err_and(|e| e.to_string().contains("another helper holds")),
            "a second helper started"
        );
        // Once the first is gone, the next one starts.
        drop(first);
        Helper::start(f.config.clone(), f.backend.clone()).unwrap();
    }

    #[test]
    fn make_list_and_delete_hand_the_backend_only_derived_values() {
        let f = fixture();
        let helper = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
        let a = VmId::generate().unwrap();
        let b = VmId::generate().unwrap();
        write_user_data(&f, &a, b"{\"tinfirm\":1}");
        write_user_data(&f, &b, b"{\"tinfirm\":2}");
        assert_eq!(helper.make(&a, &template()).unwrap().port, 20000);
        assert_eq!(helper.make(&b, &template()).unwrap().port, 20001);
        assert_eq!(helper.make(&a, &template()).unwrap_err().code, Code::Exists);
        let vms = f.backend.vms();
        assert_eq!(vms.len(), 2);
        assert_eq!(vms[&0].user_data, b"{\"tinfirm\":1}");
        assert_eq!(vms[&1].plan.uid, f.config.vm_uid_base + 1);
        // The backend's plan holds no trace of the id.
        assert!(!format!("{:?}", vms[&0].plan).contains(a.as_str()));

        let listed = helper.list().unwrap();
        assert_eq!(listed.free, 1);
        assert!(listed.vms.iter().all(|vm| vm.state == VmState::Running));
        f.backend.kill(1);
        let listed = helper.list().unwrap();
        assert_eq!(
            listed.vms.iter().find(|vm| vm.id == b).unwrap().state,
            VmState::Failed
        );

        helper.delete(&a).unwrap();
        helper.delete(&a).unwrap();
        assert_eq!(f.backend.vms().len(), 1);
        // The slot comes back; the serial, and so the jail, does not.
        let c = VmId::generate().unwrap();
        write_user_data(&f, &c, b"{}");
        assert_eq!(helper.make(&c, &template()).unwrap().port, 20000);
        let serials: Vec<u64> = f.backend.vms().keys().copied().collect();
        assert_eq!(serials, [1, 2]);
        let d = VmId::generate().unwrap();
        write_user_data(&f, &d, b"{}");
        helper.make(&d, &template()).unwrap();
        let e = VmId::generate().unwrap();
        write_user_data(&f, &e, b"{}");
        assert_eq!(helper.make(&e, &template()).unwrap_err().code, Code::Full);
    }

    #[test]
    fn an_unknown_template_missing_user_data_and_a_failed_make_are_refused_and_leave_nothing() {
        let f = fixture();
        let helper = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
        let a = VmId::generate().unwrap();
        let unknown = TemplateName::parse(b"no-such-template").unwrap();
        assert_eq!(
            helper.make(&a, &unknown).unwrap_err().code,
            Code::UnknownTemplate
        );
        assert_eq!(
            helper.check(&unknown).unwrap_err().code,
            Code::UnknownTemplate
        );
        assert_eq!(
            helper.make(&a, &template()).unwrap_err().code,
            Code::UserData
        );
        write_user_data(&f, &a, b"{}");
        f.backend.fail_next_make();
        assert_eq!(helper.make(&a, &template()).unwrap_err().code, Code::Failed);
        assert!(f.backend.vms().is_empty());
        assert!(helper.list().unwrap().vms.is_empty());
        // The failed make's serial is not handed out again.
        helper.make(&a, &template()).unwrap();
        assert_eq!(f.backend.vms().keys().copied().collect::<Vec<_>>(), [1]);
    }

    #[test]
    fn a_restarted_helper_keeps_its_vms_and_removes_what_no_record_holds() {
        let f = fixture();
        let a = VmId::generate().unwrap();
        {
            let helper = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
            write_user_data(&f, &a, b"{}");
            helper.make(&a, &template()).unwrap();
        }
        // A VM an earlier helper left, with a serial beyond the record's.
        let t = f.config.templates.values().next().unwrap().clone();
        f.backend.leave(plan::plan(&f.config, &t, 2, 40));
        let helper = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
        assert_eq!(f.backend.vms().keys().copied().collect::<Vec<_>>(), [0]);
        assert_eq!(helper.list().unwrap().vms[0].id, a);
        let b = VmId::generate().unwrap();
        write_user_data(&f, &b, b"{}");
        helper.make(&b, &template()).unwrap();
        // Its name is not handed out again.
        assert_eq!(f.backend.vms().keys().copied().collect::<Vec<_>>(), [0, 41]);
    }

    #[test]
    fn a_state_directory_others_can_read_is_refused() {
        let f = fixture();
        std::fs::set_permissions(&f.config.state, std::fs::Permissions::from_mode(0o755)).unwrap();
        let error = Helper::start(f.config.clone(), f.backend.clone())
            .err()
            .unwrap();
        assert!(format!("{error:#}").contains("0700"), "{error:#}");
    }

    async fn raw(socket: &Path, line: &[u8]) -> Vec<u8> {
        use tokio::io::AsyncReadExt;
        let mut stream = UnixStream::connect(socket).await.unwrap();
        let _ = stream.write_all(line).await;
        let mut answer = Vec::new();
        let _ = stream.read_to_end(&mut answer).await;
        answer
    }

    #[tokio::test]
    async fn the_server_answers_its_peer_and_refuses_bad_lines() {
        let f = fixture();
        let helper = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
        let socket = f.config.socket.clone();
        let task = tokio::spawn(run(helper));
        while !socket.exists() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let client = HelperClient::new(socket.clone());
        assert_eq!(client.list().await.unwrap().vms, []);
        let check = client.check(&template()).await.unwrap();
        assert_eq!(check.free, 3);
        assert!(check.checked.contains(&"the listener's spool".to_string()));

        let answer = raw(&socket, b"{\"v\":1,\"op\":\"list\",\"x\":1}\n").await;
        assert!(String::from_utf8_lossy(&answer).contains("bad-request"));
        let mut long = vec![b' '; MAX_REQUEST + 10];
        long.push(b'\n');
        let answer = raw(&socket, &long).await;
        assert!(String::from_utf8_lossy(&answer).contains("over 4096 bytes"));
        task.abort();
        let _ = f.dir;
    }

    #[tokio::test]
    async fn a_peer_that_is_not_the_listeners_user_gets_nothing() {
        let mut f = fixture();
        // The test runs as one user: the helper expects another.
        f.config.listener_uid = local::own_uid().unwrap().wrapping_add(4242);
        // The spool is the listener's: only root can give it to another user. Without that, the
        // helper refuses to start, and the peer check is covered by its own test.
        if std::os::unix::fs::chown(&f.config.spool, Some(f.config.listener_uid), None).is_err() {
            eprintln!("not root: the peer check's own test covers this");
            return;
        }
        let helper = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
        let socket = f.config.socket.clone();
        let task = tokio::spawn(run(helper));
        while !socket.exists() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let answer = raw(&socket, b"{\"v\":1,\"op\":\"list\"}\n").await;
        assert!(answer.is_empty(), "{}", String::from_utf8_lossy(&answer));
        assert!(HelperClient::new(socket).list().await.is_err());
        task.abort();
    }

    #[test]
    fn a_failed_make_whose_cleanup_fails_keeps_its_slot_until_a_delete_succeeds() {
        let f = fixture();
        let helper = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
        let a = VmId::generate().unwrap();
        write_user_data(&f, &a, b"{}");
        f.backend.fail_next_make();
        f.backend.fail_deletes(true);
        assert_eq!(helper.make(&a, &template()).unwrap_err().code, Code::Failed);
        // What the make left may run: it stays, failed, on its slot.
        let listed = helper.list().unwrap();
        assert_eq!(listed.vms.len(), 1);
        assert_eq!(
            (listed.vms[0].state, listed.vms[0].port),
            (VmState::Failed, 20000)
        );
        assert_eq!(listed.free, 2);
        let b = VmId::generate().unwrap();
        write_user_data(&f, &b, b"{}");
        f.backend.fail_deletes(false);
        assert_eq!(helper.make(&b, &template()).unwrap().port, 20001);
        // A delete that fails keeps it too; one that succeeds frees the slot.
        f.backend.fail_deletes(true);
        assert_eq!(helper.delete(&a).unwrap_err().code, Code::Failed);
        assert_eq!(helper.list().unwrap().vms.len(), 2);
        f.backend.fail_deletes(false);
        helper.delete(&a).unwrap();
        let c = VmId::generate().unwrap();
        write_user_data(&f, &c, b"{}");
        assert_eq!(helper.make(&c, &template()).unwrap().port, 20000);
    }

    #[test]
    fn a_leftover_that_cannot_be_removed_keeps_its_slot_and_one_of_unknown_form_blocks_all() {
        let f = fixture();
        let t = f.config.templates.values().next().unwrap().clone();
        // An earlier helper's VM on slot 0, which cannot be removed now.
        f.backend.leave(plan::plan(&f.config, &t, 0, 5));
        f.backend.fail_deletes(true);
        let helper = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
        let a = VmId::generate().unwrap();
        write_user_data(&f, &a, b"{}");
        // The make's sweep cannot remove it either: slot 0 stays held.
        assert_eq!(helper.make(&a, &template()).unwrap().port, 20001);
        assert!(
            f.backend.vms().contains_key(&6),
            "a serial beyond the leftover's"
        );
        let check = helper.check(&template()).unwrap();
        assert!(
            check
                .problems
                .iter()
                .any(|p| p.contains("cannot be removed")),
            "{check:?}"
        );
        assert_eq!(check.free, 1);
        // Once it can be removed, the next sweep removes it and its slot is free.
        f.backend.fail_deletes(false);
        let b = VmId::generate().unwrap();
        write_user_data(&f, &b, b"{}");
        assert_eq!(helper.make(&b, &template()).unwrap().port, 20000);
        assert!(!f.backend.vms().contains_key(&5));

        // A jail whose slot cannot be known: no slot is handed out, and check says why.
        f.backend.leave_unknown("tfvm-junk\n\u{1b}[31m");
        let c = VmId::generate().unwrap();
        write_user_data(&f, &c, b"{}");
        assert_eq!(helper.make(&c, &template()).unwrap_err().code, Code::Failed);
        let check = helper.check(&template()).unwrap();
        assert_eq!(check.free, 0);
        let problem = check
            .problems
            .iter()
            .find(|p| p.contains("does not know"))
            .unwrap();
        assert!(
            !problem.contains('\n') && !problem.contains('\u{1b}'),
            "{problem}"
        );
        assert_eq!(helper.list().unwrap().free, 0);
        f.backend.remove_unknown();
        assert_eq!(helper.make(&c, &template()).unwrap().port, 20002);
    }

    #[test]
    fn a_refusal_holds_no_byte_of_the_peer() {
        let lines: [&[u8]; 4] = [
            br#"{"v":1,"op":"list","evil\nforged line \u001b[31m":1}"#,
            br#"{"v":1,"op":"evil\nforged line \u001b[31m"}"#,
            br#"{"v":1,"op":"delete","id":"evil\nforged line \u001b[31m"}"#,
            br#"{"v":"evil\nforged line \u001b[31m","op":"list"}"#,
        ];
        for line in lines {
            let e = Request::parse(line).unwrap_err();
            let answer = String::from_utf8(answer_line(&Err(e.clone()))).unwrap();
            for text in [e.message.as_str(), answer.trim_end()] {
                assert!(
                    !text.contains("evil") && !text.contains('\n') && !text.contains('\u{1b}'),
                    "{text:?}"
                );
            }
        }
    }

    #[tokio::test]
    async fn the_helper_serves_at_most_its_connections_and_waits_for_a_request_only_so_long() {
        use tokio::io::AsyncReadExt;
        let f = fixture();
        let helper = Helper::start(f.config.clone(), f.backend.clone()).unwrap();
        let socket = f.config.socket.clone();
        let task = tokio::spawn(run(helper));
        while !socket.exists() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let mut idle = Vec::new();
        for _ in 0..MAX_CONNECTIONS {
            idle.push(UnixStream::connect(&socket).await.unwrap());
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
        // One more is closed at once, before it is read.
        let started = std::time::Instant::now();
        let mut extra = UnixStream::connect(&socket).await.unwrap();
        let mut buf = Vec::new();
        let _ = extra.read_to_end(&mut buf).await;
        assert!(buf.is_empty() && started.elapsed() < REQUEST_TIMEOUT);
        // An idle one gets its refusal once the request's time is up.
        let mut buf = Vec::new();
        idle[0].read_to_end(&mut buf).await.unwrap();
        assert!(String::from_utf8_lossy(&buf).contains("no request in time"));
        assert!(started.elapsed() >= REQUEST_TIMEOUT - Duration::from_millis(500));
        task.abort();
    }
}
