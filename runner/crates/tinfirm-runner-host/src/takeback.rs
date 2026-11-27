//! A runner host that restarted takes its runs back (ADR-0101, threat model R36).
//!
//! From the start of a run's sandbox on the sandbox bridge until the run's report is made,
//! the host keeps a record of it in its data directory (`runs/<runId>.json`, 0600): the
//! assignment, the sandbox, the bridge session's key,
//! the repositories as they were cloned, and the agent's session once it is open. Where the
//! sandbox's front holds its bridge (ADR-0112, path C, part 2), the record keeps that bridge's
//! own TLS identity, its listener's address and port, and the address it connects from
//! ([`RunRecord::bridge`]): the bridge pins the one and dials the other, and a host that
//! restarts gives the run a new front with them. A record without them is of a host before
//! that: its bridge pins the shared listener's identity, kept beside the records with its port
//! (`runs/bridge.json`), which serves such runs for one release.
//!
//! In the run's own end, the work comes back from the sandbox and the record says so
//! ([`RunRecord::collected`], [`RunRecord::end`]) before the sandbox stops, with what is known
//! then: whether the agent's output leaked a credential value and whether the server cancelled
//! the run. Then the record keeps the agent's outcome, and each file of `/output` as it is
//! saved. A host that restarts in that end finishes the run from its own disk: it saves what is
//! not saved yet, pushes (nothing after a leak), stores the session and reports the outcome
//! the run had. Such a record is of version [`RECORD_VERSION_END`], which a host before it
//! removes instead of misreading.
//!
//! When the run's report (`run.finished`) is made, it takes the record's place: the host writes
//! it (`runs/<runId>.<epoch>.finished.json`, [`ReportRecord`]), syncs the directory, and then
//! removes the record, under one lock, so a crash or a power loss leaves one of the two. The
//! report holds the frame as it is sent, redacted, and no credential. It goes:
//! - once the outbox forgets the run: its end was written on a connection that then stayed up
//!   for the retention (`TINFIRM_RUNNER_FINISHED_RETENTION`, 3 minutes);
//! - on an orderly end of the process, once its connection has ended, when the end was written
//!   to a connection: what was written counts as sent, as the outbox counts it;
//! - when another execution of the run takes its place in the outbox (the server assigned the
//!   run again), or the execution is dropped.
//!
//! The protocol has no acknowledgement of `run.finished`, so that is as sure as the host can
//! be. A host that starts and finds a report puts it into its outbox: the run is in
//! `hello.activeRuns`, and its `run.finished` is written again; a server that ended the run
//! already ignores it. A record of a newer execution of the same run wins over the report,
//! which goes unsent. A report that cannot be read, or is of another format, is logged and
//! removed. A host before this one does not read such a name.
//!
//! Only the newest [`RecordHandle`] of a run id writes or removes its record and report
//! ([`Records`]): a stale execution of the run (an older epoch that the host aborts) leaves the
//! newer one's alone.
//!
//! A host that starts and finds records takes each run back before it says hello: the run is
//! listed in `hello.activeRuns`, so the server keeps it; its bridge comes back with the session
//! key; the agent that the earlier process started is stopped; and the run ends as a run that
//! the host stopped does: the work comes back from the sandbox and is pushed, the session's
//! bundle is stored, and the run is reported failed with `INFRA`. The next turn goes on from
//! the session. A run whose record keeps the agent's outcome reports that outcome instead. A
//! record that cannot be taken back (another executor now, or no bridge) is reported in the
//! log and removed.
//!
//! The record's git tokens are those of the run's holder ([`crate::git_tokens`]): the record is
//! written again whenever the holder changes ([`RecordHandle::sync_git`]). A repository whose
//! token the server revoked (ADR-0116) has no `git` in the record's assignment and is named in
//! `revoked` with the server's reason and message, so a host that restarts neither uses the
//! token nor pushes the repository. A record without `revoked` revoked nothing.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

use anyhow::{Context, Result};
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use tinfirm_bridge::control::Hidden;
use tinfirm_bridge::tls::Identity;
use tinfirm_protocol::{
    ArtifactResult, FailureClass, ResumeOutcome, RunAssign, RunResult, UsagePayload,
};
use uuid::Uuid;

use crate::git::MirrorRef;
use crate::git_tokens::{RevokedRepository, RunGitTokens};
use crate::paths::Paths;
use crate::sandbox_files::Collected;

/// The format of the records. A record of another one is not taken back.
pub const RECORD_VERSION: u32 = 1;

/// The format of a record that keeps a run's own end ([`RunRecord::end`]). This host reads it and
/// [`RECORD_VERSION`]; a host before it removes such a record and does not take the run back,
/// since it would read the record as one whose work came back before a restart (it would empty
/// the run's `/output` and push after a leak).
pub const RECORD_VERSION_END: u32 = 2;

/// The format of a run's kept report ([`ReportRecord`]).
pub const REPORT_VERSION: u32 = 1;

/// The event numbers that a record reserves at a time: a host that takes the run back numbers
/// its events after them, so it never reuses one the server has.
pub const SEQ_BLOCK: u64 = 1000;

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

/// What a host needs to take a run back.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RunRecord {
    pub version: u32,
    /// The run's assignment, its credentials included: the file is the host's, as its token
    /// is.
    pub assign: RunAssign,
    pub sandbox: SandboxRecord,
    /// The name the sandbox's bridge has with the listener (`<runId>.<epoch>`, or a VM's).
    pub bridge_id: String,
    /// The bridge session's key, which the sandbox's bridge presents when it comes back.
    pub session_key: Hidden,
    pub repos: Vec<RepoRecord>,
    /// What stays out of the session home when it comes back ([`crate::sandbox_files::home_exclude`]).
    pub home_exclude: Vec<String>,
    /// The agent's session, once it is open: the next turn resumes it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    /// Every event of the run has a lower number.
    pub seq_floor: u64,
    /// The repositories whose token the server revoked (ADR-0116): their `git` in `assign` is
    /// null, and a host that takes the run back pushes none of them.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub revoked: Vec<RevokedRepository>,
    /// The work came back from the sandbox, which stops once this is on disk (or, when it
    /// cannot be written, lives until the run ends): the run's repositories, its session home
    /// and its `/output` are on this host, and only the push and the report are left (ADR-0116, decision 10: they wait for the server). A host that takes
    /// such a run back needs no sandbox and no bridge. An older host ignores the field: it
    /// finds no sandbox, and reports the run's work as lost.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub collected: bool,
    /// The salted hashes of every git token the run has held (crate::secret_hashes): a host
    /// that takes the run back redacts and scans for its revoked and replaced tokens with
    /// them, which the record no longer holds. An older host ignores the field; a record
    /// without it reads as before, and its run's earlier tokens are not known.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub redaction: Option<crate::secret_hashes::SecretHashes>,
    /// The sandbox's bridge in its front: its own identity and listener. `None` in a record of
    /// a host whose sandboxes' bridges used its shared listener (`runs/bridge.json`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bridge: Option<BridgeRecord>,
    /// The run's own end: the agent's turn ended and the work came back from the sandbox in
    /// the run's end, not after a restart ([`RecordHandle::collected_at_the_end`]); then what
    /// that end has decided and saved so far. `collected` is set with it. An older host ignores
    /// the field and takes the run back as one whose work came back before a restart.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub end: Option<EndRecord>,
}

/// What a run's own end has decided and saved, as its record keeps it ([`RunRecord::end`]).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EndRecord {
    /// The agent's output held a credential value (it was stopped for it): nothing of the run
    /// leaves the host. Written with the mark, before the sandbox stops.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub leak: bool,
    /// The server had cancelled the run when its work came back. Written with the mark.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub cancelled: bool,
    /// The agent's outcome, once the host has decided it: a host that takes the run back
    /// reports it. `None`: the host restarted before, and the run is reported as taken back.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outcome: Option<OutcomeRecord>,
    /// The files of `/output` saved as artifacts of the job so far, each written as it is
    /// saved: a host that takes the run back lists them and saves none of them again.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub artifacts: Vec<ArtifactResult>,
    /// Every file of `/output` was saved, or skipped: nothing of `/output` is left to save.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub output_saved: bool,
}

/// A run's report, kept from the moment it is made until it cannot be lost any more
/// ([`RecordHandle::report_made`]): `runs/<runId>.<epoch>.finished.json`, 0600. Its name is no
/// `<uuid>.json`, so a host before it leaves it alone. It holds the `run.finished` frame as
/// the host sends it, redacted, and nothing else.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReportRecord {
    pub version: u32,
    pub finished: tinfirm_protocol::RunFinished,
}

/// The agent's outcome as the run's end decided it, before `/output`, the push and the
/// session bundle: what the run reports, but for what the push adds. Every text in it is
/// redacted as the report's is; it holds no credential.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutcomeRecord {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub failure_class: Option<FailureClass>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub failure_message: Option<String>,
    /// The server cancelled the run.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub cancelled: bool,
    /// The agent's output held a credential value: nothing of the run leaves the host.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub leak: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<RunResult>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub usage: Option<UsagePayload>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    #[serde(default)]
    pub session: SessionRecord,
}

/// Whether the agent's session was opened, and whether it resumed the assignment's.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "state")]
pub enum SessionRecord {
    #[default]
    NotOpened,
    Resumed,
    NotResumed {
        outcome: ResumeOutcome,
    },
}

/// A sandbox's bridge that its front holds, as a record keeps it: the certificate and its
/// PKCS #8 key (base64 of DER), which the record's mode protects as it does the run's tokens;
/// for a bridge that dials its front's listener, the listener's address and port and the
/// address the bridge connects from (`0.0.0.0`: any). A VM's bridge listens, and the record's
/// sandbox names where this host dials it ([`SandboxRecord::Vm`]): no listener here.
/// The key is [`Hidden`], as the session key is: a `Debug` of a record never shows it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BridgeRecord {
    pub cert: String,
    pub key: Hidden,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ip: Option<std::net::IpAddr>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub peer: Option<std::net::IpAddr>,
}

impl BridgeRecord {
    pub fn of(keep: &crate::sandbox::BridgeKeep) -> BridgeRecord {
        let (ip, port, peer) = match &keep.reach {
            crate::sandbox::BridgeReach::Listen { ip, port, peer } => {
                (Some(*ip), Some(*port), Some(*peer))
            }
            crate::sandbox::BridgeReach::Dial { .. } => (None, None, None),
        };
        BridgeRecord {
            cert: B64.encode(&keep.cert),
            key: Hidden::new(B64.encode(&keep.key)),
            ip,
            port,
            peer,
        }
    }

    /// What a new front takes the bridge back with: its listener, or, for a bridge that this
    /// host dials, `dial`, the address the record's sandbox names.
    pub fn keep(&self, dial: Option<String>) -> Result<crate::sandbox::BridgeKeep> {
        let reach = match (self.ip, self.port, dial) {
            (Some(ip), Some(port), _) => crate::sandbox::BridgeReach::Listen {
                ip,
                port,
                peer: self
                    .peer
                    .unwrap_or(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED)),
            },
            (None, None, Some(target)) => crate::sandbox::BridgeReach::Dial { target },
            _ => anyhow::bail!(
                "the record's bridge names neither its listener nor where it is dialed"
            ),
        };
        Ok(crate::sandbox::BridgeKeep {
            cert: B64
                .decode(&self.cert)
                .context("the record's bridge certificate")?,
            key: B64
                .decode(self.key.expose())
                .context("the record's bridge key")?,
            reach,
        })
    }
}

impl RunRecord {
    pub fn run_id(&self) -> Uuid {
        self.assign.run_id
    }
}

/// The run's sandbox.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum SandboxRecord {
    /// A container of this machine's Docker, by its name.
    Docker { container: String },
    /// A VM of the VM executor: the driver's id, the host label it was created with, its own
    /// label, and where its bridge is dialed: the address, and the port the driver named in
    /// `describe`. A record without a port (one written before the driver protocol had it, or
    /// of a driver that names none) means `--vm-port`.
    Vm {
        id: String,
        host: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        label: Option<String>,
        address: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        port: Option<u16>,
    },
    /// The local test executor's bridge, a process of this machine's.
    Local,
}

/// A repository of the run as the host cloned it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RepoRecord {
    /// Its `workspaces[].dir`.
    pub dir: String,
    pub cloned_head: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cloned_commits: Option<u64>,
    /// The mirror's refs at the clone: name and object.
    pub base: Vec<(String, String)>,
    /// `.git/config` as the clone wrote it, base64 (it holds no secret).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub config_snapshot: Option<String>,
    /// How the repository came back from the sandbox, once it did ([`RunRecord::collected`]).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub collected: Option<CollectedRecord>,
}

/// How one repository came back from the sandbox: its WIP commit and head, or why it did not.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "result")]
pub enum CollectedRecord {
    #[serde(rename = "ok")]
    Ok { wip: bool, head: String },
    #[serde(rename = "failed")]
    Failed { message: String },
}

impl CollectedRecord {
    pub fn of(collected: &std::result::Result<Collected, String>) -> CollectedRecord {
        match collected {
            Ok(c) => CollectedRecord::Ok {
                wip: c.wip,
                head: c.head.clone(),
            },
            Err(message) => CollectedRecord::Failed {
                message: message.clone(),
            },
        }
    }

    pub fn restore(&self) -> std::result::Result<Collected, String> {
        match self {
            CollectedRecord::Ok { wip, head } => Ok(Collected {
                wip: *wip,
                head: head.clone(),
            }),
            CollectedRecord::Failed { message } => Err(message.clone()),
        }
    }
}

impl RepoRecord {
    pub fn new(
        dir: &str,
        cloned_head: &str,
        cloned_commits: Option<u64>,
        base: &[MirrorRef],
        config_snapshot: Option<&[u8]>,
    ) -> RepoRecord {
        RepoRecord {
            dir: dir.to_string(),
            cloned_head: cloned_head.to_string(),
            cloned_commits,
            base: base
                .iter()
                .map(|r| (r.name.clone(), r.sha.clone()))
                .collect(),
            config_snapshot: config_snapshot.map(|bytes| B64.encode(bytes)),
            collected: None,
        }
    }

    pub fn base_refs(&self) -> Vec<MirrorRef> {
        self.base
            .iter()
            .map(|(name, sha)| MirrorRef {
                name: name.clone(),
                sha: sha.clone(),
            })
            .collect()
    }

    pub fn config_snapshot(&self) -> Option<Vec<u8>> {
        self.config_snapshot
            .as_deref()
            .and_then(|text| B64.decode(text).ok())
    }
}

/// The bridge listener's identity and port.
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ListenerRecord {
    cert: String,
    key: String,
    port: u16,
}

/// The records of a data directory. Its clones share which handle owns each run's record
/// ([`RecordHandle`]): only the newest handle of a run id writes or removes its record, and the
/// writes and removals of every handle are made one at a time, so no two share a temporary file.
#[derive(Debug, Clone)]
pub struct Records {
    dir: PathBuf,
    owners: std::sync::Arc<Mutex<Owners>>,
}

/// The newest handle of each run id with a record ([`Records::claim`]).
#[derive(Debug, Default)]
struct Owners {
    next: u64,
    by_run: std::collections::HashMap<Uuid, u64>,
}

impl Records {
    pub fn new(paths: &Paths) -> Records {
        Records {
            dir: paths.data_dir.join("runs"),
            owners: Default::default(),
        }
    }

    fn path(&self, run_id: Uuid) -> PathBuf {
        self.dir.join(format!("{run_id}.json"))
    }

    /// `runs/<runId>.<epoch>.finished.json`: one per execution, its epoch in its name.
    fn report_path(&self, run_id: Uuid, epoch: i64) -> PathBuf {
        self.dir.join(format!("{run_id}.{epoch}.finished.json"))
    }

    fn owners(&self) -> std::sync::MutexGuard<'_, Owners> {
        self.owners.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// A new handle of `run_id` owns its record from now on: an older one writes nothing more.
    fn claim(&self, run_id: Uuid) -> u64 {
        let mut owners = self.owners();
        owners.next += 1;
        let owner = owners.next;
        owners.by_run.insert(run_id, owner);
        owner
    }

    /// Writes a new record and claims its run for it, in that order and under one lock: when
    /// the write fails, an older execution of the run keeps its record on disk and stays its
    /// owner, so that it still rewrites it at a revocation and removes it at its end.
    fn create_owned(&self, record: &RunRecord) -> Result<u64> {
        let mut owners = self.owners();
        self.write(record)?;
        owners.next += 1;
        let owner = owners.next;
        owners.by_run.insert(record.run_id(), owner);
        Ok(owner)
    }

    /// Writes `record` when `owner` still owns it. `Ok(false)`: a newer handle owns it, and
    /// nothing was written.
    fn save_owned(&self, owner: u64, record: &RunRecord) -> Result<bool> {
        let owners = self.owners();
        if owners.by_run.get(&record.run_id()) != Some(&owner) {
            return Ok(false);
        }
        self.write(record)?;
        Ok(true)
    }

    /// Removes the record of `run_id` when `owner` still owns it.
    fn remove_owned(&self, owner: u64, run_id: Uuid) {
        let mut owners = self.owners();
        if owners.by_run.get(&run_id) == Some(&owner) {
            owners.by_run.remove(&run_id);
            self.remove_file(run_id);
        }
    }

    /// Writes the report and then removes the record, when `owner` still owns the record: a
    /// crash leaves one of the two at every moment. `Ok(false)`: a newer handle owns it.
    fn report_owned(
        &self,
        owner: u64,
        run_id: Uuid,
        finished: &tinfirm_protocol::RunFinished,
    ) -> Result<bool> {
        let mut owners = self.owners();
        if owners.by_run.get(&run_id) != Some(&owner) {
            return Ok(false);
        }
        self.save_report(finished)?;
        // The report's name must be on disk before the record's goes, also after a power loss:
        // the directory is synced once, here, between the two.
        if let Err(e) = std::fs::File::open(&self.dir).and_then(|dir| dir.sync_all()) {
            tracing::warn!(error = %e, "cannot sync the directory of the runs' records");
        }
        owners.by_run.remove(&run_id);
        self.remove_file(run_id);
        Ok(true)
    }

    fn listener_path(&self) -> PathBuf {
        self.dir.join("bridge.json")
    }

    /// Writes `record`, in place of the run's earlier one, whichever handle owns it.
    pub fn save(&self, record: &RunRecord) -> Result<()> {
        let _owners = self.owners();
        self.write(record)
    }

    /// The write itself: of [`RECORD_VERSION_END`] for a record that keeps a run's own end.
    fn write(&self, record: &RunRecord) -> Result<()> {
        crate::secure_fs::ensure_private_dir(&self.dir)?;
        let bytes = if record.end.is_some() && record.version != RECORD_VERSION_END {
            let mut record = record.clone();
            record.version = RECORD_VERSION_END;
            serde_json::to_vec(&record)
        } else {
            serde_json::to_vec(record)
        }
        .context("cannot encode the run's record")?;
        write_replacing(&self.path(record.run_id()), &bytes)
    }

    /// Removes the record of `run_id`, whichever handle owns it (a record that the host does
    /// not take back).
    pub fn remove(&self, run_id: Uuid) {
        let mut owners = self.owners();
        owners.by_run.remove(&run_id);
        self.remove_file(run_id);
    }

    fn remove_file(&self, run_id: Uuid) {
        let path = self.path(run_id);
        if let Err(e) = std::fs::remove_file(&path)
            && e.kind() != std::io::ErrorKind::NotFound
        {
            tracing::warn!(path = %path.display(), error = %e, "cannot remove a run's record");
        }
    }

    /// Every record there is. One that cannot be read, or is of another format, is logged and
    /// removed: its run cannot be taken back.
    pub fn load_all(&self) -> Vec<RunRecord> {
        let Ok(entries) = std::fs::read_dir(&self.dir) else {
            return Vec::new();
        };
        let mut records = Vec::new();
        for entry in entries.filter_map(|e| e.ok()) {
            let path = entry.path();
            let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
                continue;
            };
            let Some(id) = name
                .strip_suffix(".json")
                .and_then(|id| Uuid::parse_str(id).ok())
            else {
                continue;
            };
            let read = crate::secure_fs::read_private_file(&path)
                .and_then(|bytes| serde_json::from_slice::<RunRecord>(&bytes).map_err(Into::into));
            match read {
                Ok(record)
                    if (record.version == RECORD_VERSION
                        || record.version == RECORD_VERSION_END)
                        && record.run_id() == id =>
                {
                    records.push(record)
                }
                Ok(_) => {
                    tracing::warn!(run_id = %id, "a run's record of another format; it is not taken back");
                    self.remove(id);
                }
                Err(e) => {
                    tracing::warn!(run_id = %id, error = %format!("{e:#}"), "a run's record cannot be read; it is not taken back");
                    self.remove(id);
                }
            }
        }
        records.sort_by_key(RunRecord::run_id);
        records
    }

    /// Removes the temporary copies that a write of a record, a kept report or the bridge's
    /// identity leaves when its process is killed in it ([`write_replacing`]): each holds the
    /// whole file, credentials and all, and nothing else reads or removes it. Only for the start
    /// of `run`, which holds `run.lock`, before this process writes any of them: a copy that is
    /// there then is a dead process's, never a write in progress.
    pub fn remove_leftovers(&self) {
        let Ok(entries) = std::fs::read_dir(&self.dir) else {
            return;
        };
        for entry in entries.filter_map(|e| e.ok()) {
            let path = entry.path();
            let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
                continue;
            };
            // `<name>.json.tmp`, and `.<name>.json.tmp.tmp-<hex>` of its own write
            // (`secure_fs::write_private_file`).
            let leftover = name.ends_with(".json.tmp")
                || (name.starts_with('.') && name.contains(".json.tmp.tmp-"));
            if !leftover || !entry.file_type().is_ok_and(|t| t.is_file()) {
                continue;
            }
            match std::fs::remove_file(&path) {
                Ok(()) => {
                    tracing::info!(path = %path.display(), "removed what a killed process left of a write of a run's record")
                }
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => {
                    tracing::warn!(path = %path.display(), error = %e, "cannot remove what a killed process left of a write of a run's record")
                }
            }
        }
    }

    /// What `doctor` says of the records, without changing any: how many runs a start of
    /// `run` takes back, and how many of those have their work on this host already and wait
    /// for the server before they push it ([`RunRecord::collected`]). A record that cannot be
    /// read counts in neither.
    pub fn census(&self) -> (usize, usize) {
        let Ok(entries) = std::fs::read_dir(&self.dir) else {
            return (0, 0);
        };
        let (mut runs, mut waiting) = (0, 0);
        for entry in entries.filter_map(|e| e.ok()) {
            let path = entry.path();
            let is_record = path
                .file_name()
                .and_then(|n| n.to_str())
                .and_then(|n| n.strip_suffix(".json"))
                .is_some_and(|id| Uuid::parse_str(id).is_ok());
            if !is_record {
                continue;
            }
            let Ok(record) = crate::secure_fs::read_private_file(&path)
                .and_then(|bytes| serde_json::from_slice::<RunRecord>(&bytes).map_err(Into::into))
            else {
                continue;
            };
            runs += 1;
            if record.collected {
                waiting += 1;
            }
        }
        (runs, waiting)
    }

    /// Keeps a run's report until it cannot be lost any more ([`ReportRecord`]).
    pub fn save_report(&self, finished: &tinfirm_protocol::RunFinished) -> Result<()> {
        crate::secure_fs::ensure_private_dir(&self.dir)?;
        let kept = ReportRecord {
            version: REPORT_VERSION,
            finished: finished.clone(),
        };
        let bytes = serde_json::to_vec(&kept).context("cannot encode the run's report")?;
        write_replacing(&self.report_path(finished.run_id, finished.epoch), &bytes)
    }

    /// The report of that execution of the run can no longer be lost, or no longer matters
    /// (a newer execution of the run took its place): it goes, if there is one.
    pub fn remove_report(&self, run_id: Uuid, epoch: i64) {
        let path = self.report_path(run_id, epoch);
        if let Err(e) = std::fs::remove_file(&path)
            && e.kind() != std::io::ErrorKind::NotFound
        {
            tracing::warn!(path = %path.display(), error = %e, "cannot remove a run's kept report");
        }
    }

    /// Every kept report there is. One that cannot be read, or is of another format (a newer
    /// host's, after a downgrade), is logged and removed: its run's end is not sent again.
    pub fn load_reports(&self) -> Vec<tinfirm_protocol::RunFinished> {
        let Ok(entries) = std::fs::read_dir(&self.dir) else {
            return Vec::new();
        };
        let mut reports = Vec::new();
        for entry in entries.filter_map(|e| e.ok()) {
            let path = entry.path();
            let Some((id, epoch)) = path
                .file_name()
                .and_then(|n| n.to_str())
                .and_then(|n| n.strip_suffix(".finished.json"))
                .and_then(|n| n.split_once('.'))
                .and_then(|(id, epoch)| Some((Uuid::parse_str(id).ok()?, epoch.parse().ok()?)))
            else {
                continue;
            };
            let read = crate::secure_fs::read_private_file(&path).and_then(|bytes| {
                serde_json::from_slice::<ReportRecord>(&bytes).map_err(Into::into)
            });
            match read {
                Ok(kept)
                    if kept.version == REPORT_VERSION
                        && kept.finished.run_id == id
                        && kept.finished.epoch == epoch =>
                {
                    reports.push(kept.finished)
                }
                _ => {
                    tracing::warn!(run_id = %id, epoch, "a run's kept report cannot be read; it is not sent again");
                    self.remove_report(id, epoch);
                }
            }
        }
        reports.sort_by_key(|f| (f.run_id, f.epoch));
        reports
    }

    /// The bridge listener's identity and port, as the listener kept them.
    pub fn listener(&self) -> Option<(Identity, u16)> {
        let bytes = crate::secure_fs::read_private_file(&self.listener_path()).ok()?;
        let kept: ListenerRecord = serde_json::from_slice(&bytes).ok()?;
        let identity =
            Identity::from_der(B64.decode(kept.cert).ok()?, B64.decode(kept.key).ok()?).ok()?;
        Some((identity, kept.port))
    }

    /// Keeps the listener's identity and port, for the next process of this host.
    pub fn save_listener(&self, identity: &Identity, port: u16) -> Result<()> {
        crate::secure_fs::ensure_private_dir(&self.dir)?;
        let (cert, key) = identity.to_der();
        let kept = ListenerRecord {
            cert: B64.encode(cert),
            key: B64.encode(key),
            port,
        };
        let bytes = serde_json::to_vec(&kept).context("cannot encode the bridge's identity")?;
        write_replacing(&self.listener_path(), &bytes)
    }
}

/// Writes a private file whole: a crash leaves the earlier one or this one, never a part.
fn write_replacing(path: &Path, bytes: &[u8]) -> Result<()> {
    let temp = path.with_extension("json.tmp");
    crate::secure_fs::write_private_file(&temp, bytes)?;
    std::fs::rename(&temp, path).with_context(|| format!("cannot write {}", path.display()))
}

/// A run's record while it has one: the run updates it as it goes.
pub struct RecordHandle {
    records: Records,
    /// Which handle of the run id this is ([`Records::claim`]).
    owner: u64,
    record: Mutex<RunRecord>,
    /// Set by [`RecordHandle::remove`]: a change that comes later writes nothing.
    removed: AtomicBool,
    /// The last write failed: the next update writes the record even if it changes nothing.
    unsaved: AtomicBool,
}

impl std::fmt::Debug for RecordHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RecordHandle").finish_non_exhaustive()
    }
}

impl RecordHandle {
    /// Writes the record and keeps it. The run is this handle's only once the record is on
    /// disk: a write that fails leaves an older execution's record and its handle as they were.
    pub fn create(records: &Records, record: RunRecord) -> Result<RecordHandle> {
        let owner = records.create_owned(&record)?;
        Ok(RecordHandle {
            records: records.clone(),
            owner,
            record: Mutex::new(record),
            removed: AtomicBool::new(false),
            unsaved: AtomicBool::new(false),
        })
    }

    /// A record that is written already: a run's that this host takes back.
    pub fn kept(records: &Records, record: RunRecord) -> RecordHandle {
        RecordHandle {
            records: records.clone(),
            owner: records.claim(record.run_id()),
            record: Mutex::new(record),
            removed: AtomicBool::new(false),
            unsaved: AtomicBool::new(false),
        }
    }

    /// Applies `change` and writes the record when it changed it, or when an earlier write
    /// failed. `true` when the record on disk is the one in memory now (or it was removed).
    fn update(&self, change: impl FnOnce(&mut RunRecord) -> bool) -> bool {
        let mut record = self.record.lock().unwrap_or_else(|e| e.into_inner());
        let changed = change(&mut record);
        if self.removed.load(Ordering::SeqCst) {
            return true;
        }
        if !changed && !self.unsaved.load(Ordering::SeqCst) {
            return true;
        }
        match self.records.save_owned(self.owner, &record) {
            Ok(true) => {
                self.unsaved.store(false, Ordering::SeqCst);
                true
            }
            Ok(false) => {
                // A newer execution of the run owns the record: this one writes nothing more.
                self.removed.store(true, Ordering::SeqCst);
                tracing::warn!(run_id = %record.run_id(), "the run's record belongs to a newer execution of the run; this one writes it no more");
                false
            }
            Err(e) => {
                self.unsaved.store(true, Ordering::SeqCst);
                tracing::warn!(run_id = %record.run_id(), error = %format!("{e:#}"), "cannot update the run's record");
                false
            }
        }
    }

    /// The agent's session is open.
    pub fn session_opened(&self, session_id: &str) {
        let _ = self.update(|record| {
            if record.session_id.as_deref() == Some(session_id) {
                return false;
            }
            record.session_id = Some(session_id.to_string());
            true
        });
    }

    /// The run has numbered an event `seq`: the record reserves the next block when it is due.
    pub fn numbered(&self, seq: u64) {
        let _ = self.update(|record| {
            if seq < record.seq_floor {
                return false;
            }
            record.seq_floor = seq + SEQ_BLOCK;
            true
        });
    }

    /// The run's git tokens as `tokens` holds them now: the record is written again, whole,
    /// when a token was revoked or renewed since it was last written. `true` when the record
    /// on disk holds them as `tokens` does.
    pub fn sync_git(&self, tokens: &RunGitTokens) -> bool {
        self.update(|record| {
            let mut assign = record.assign.clone();
            let revoked = tokens.write_to(&mut assign);
            let redaction = Some(tokens.hashes());
            if assign == record.assign && revoked == record.revoked && redaction == record.redaction
            {
                return false;
            }
            record.assign = assign;
            record.revoked = revoked;
            record.redaction = redaction;
            true
        })
    }

    /// The work came back from the sandbox ([`RunRecord::collected`]): `repos` is how each
    /// repository (by `dir`) came back. A host that restarts from now on takes the run back
    /// from this host's own disk. `true` once that is on disk: only then may the sandbox go.
    pub fn collected(&self, repos: &[(String, CollectedRecord)]) -> bool {
        self.update(|record| {
            record.collected = true;
            for kept in &mut record.repos {
                kept.collected = repos
                    .iter()
                    .find(|(dir, _)| *dir == kept.dir)
                    .map(|(_, collected)| collected.clone());
            }
            true
        })
    }

    /// In the run's own end ([`RunRecord::end`]): the work came back from the sandbox, as
    /// [`RecordHandle::collected`] says, and the record stays until the run's report is made;
    /// with it, what is known already: whether the run leaked a credential value and whether the
    /// server cancelled it. `true` once that is on disk: only then may the sandbox go with the
    /// record kept.
    pub fn collected_at_the_end(
        &self,
        repos: &[(String, CollectedRecord)],
        leak: bool,
        cancelled: bool,
    ) -> bool {
        self.update(|record| {
            record.collected = true;
            record.version = RECORD_VERSION_END;
            record.end = Some(EndRecord {
                leak,
                cancelled,
                ..EndRecord::default()
            });
            for kept in &mut record.repos {
                kept.collected = repos
                    .iter()
                    .find(|(dir, _)| *dir == kept.dir)
                    .map(|(_, collected)| collected.clone());
            }
            true
        })
    }

    /// The agent's outcome is decided ([`EndRecord::outcome`]). `true` once it is on disk.
    pub fn outcome_decided(&self, outcome: OutcomeRecord) -> bool {
        self.update(|record| match record.end.as_mut() {
            Some(end) if end.outcome.as_ref() != Some(&outcome) => {
                end.outcome = Some(outcome);
                true
            }
            _ => false,
        })
    }

    /// One file of `/output` is saved ([`EndRecord::artifacts`]). `true` once it is on disk.
    pub fn output_file_saved(&self, artifact: &ArtifactResult) -> bool {
        self.update(|record| match record.end.as_mut() {
            Some(end) if !end.artifacts.iter().any(|a| a.name == artifact.name) => {
                end.artifacts.push(artifact.clone());
                true
            }
            _ => false,
        })
    }

    /// Every file of `/output` is saved or skipped ([`EndRecord::output_saved`]): `artifacts` is
    /// the list the report gives. `true` once it is on disk.
    pub fn output_saved(&self, artifacts: &[ArtifactResult]) -> bool {
        self.update(|record| match record.end.as_mut() {
            Some(end) if !end.output_saved || end.artifacts != artifacts => {
                end.artifacts = artifacts.to_vec();
                end.output_saved = true;
                true
            }
            _ => false,
        })
    }

    /// The files of `/output` saved so far, by the record's run's own end.
    pub fn saved_artifacts(&self) -> Vec<ArtifactResult> {
        let record = self.record.lock().unwrap_or_else(|e| e.into_inner());
        record
            .end
            .as_ref()
            .map(|end| end.artifacts.clone())
            .unwrap_or_default()
    }

    /// The run's report is made: it is kept ([`ReportRecord`]) and then the record goes, in one
    /// step that a crash cannot split. `true` once the report is on disk; `false` when it cannot
    /// be written (the record goes all the same, a warning: a restart before the report reaches
    /// the server then loses the run, as without a record) or a newer execution owns the record.
    pub fn report_made(&self, finished: &tinfirm_protocol::RunFinished) -> bool {
        let record = self.record.lock().unwrap_or_else(|e| e.into_inner());
        if self.removed.swap(true, Ordering::SeqCst) {
            return false;
        }
        match self
            .records
            .report_owned(self.owner, record.run_id(), finished)
        {
            Ok(kept) => kept,
            Err(e) => {
                tracing::warn!(run_id = %record.run_id(), error = %format!("{e:#}"), "cannot keep the run's report; a restart before the server has it would lose the run");
                self.records.remove_owned(self.owner, record.run_id());
                false
            }
        }
    }

    /// Nothing is left to take back: the run has ended, or its sandbox did not come back.
    pub fn remove(&self) {
        let record = self.record.lock().unwrap_or_else(|e| e.into_inner());
        self.removed.store(true, Ordering::SeqCst);
        self.records.remove_owned(self.owner, record.run_id());
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use std::sync::Arc;

    use super::*;

    pub(crate) fn record() -> RunRecord {
        let assign: RunAssign = serde_json::from_value(serde_json::json!({
            "runId": Uuid::new_v4(),
            "epoch": 2,
            "credentialLabel": "claude",
            "task": {"id": Uuid::new_v4(), "key": "T-1", "title": "t"},
            "agent": {"id": Uuid::new_v4(), "name": "a", "runtime": "CLAUDE_CODE"},
            "prompt": "p",
            "limits": {"maxWallClockSeconds": 60, "maxTurns": 3},
            "workspaces": [],
            "requestableRepositories": [],
        }))
        .unwrap();
        RunRecord {
            version: RECORD_VERSION,
            assign,
            sandbox: SandboxRecord::Docker {
                container: "tinfirm-run-1".into(),
            },
            bridge_id: "run.2".into(),
            session_key: Hidden::new("k".repeat(64)),
            repos: vec![RepoRecord::new(
                "repo",
                "abc",
                Some(1),
                &[MirrorRef {
                    name: "refs/heads/main".into(),
                    sha: "abc".into(),
                }],
                Some(b"[core]\n"),
            )],
            home_exclude: vec![".cache".into()],
            session_id: None,
            seq_floor: SEQ_BLOCK,
            revoked: Vec::new(),
            collected: false,
            redaction: None,
            bridge: None,
            end: None,
        }
    }

    #[test]
    fn a_record_is_kept_private_updated_as_the_run_goes_and_removed() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let first = record();
        let run_id = first.run_id();
        let handle = RecordHandle::create(&records, first).unwrap();
        let path = dir.path().join(format!("data/runs/{run_id}.json"));
        assert_eq!(crate::secure_fs::mode_of(&path), Some(0o600));

        handle.session_opened("session-1");
        handle.numbered(5);
        assert_eq!(records.load_all()[0].seq_floor, SEQ_BLOCK);
        handle.numbered(SEQ_BLOCK);
        let loaded = records.load_all();
        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].session_id.as_deref(), Some("session-1"));
        assert_eq!(loaded[0].seq_floor, 2 * SEQ_BLOCK);
        assert_eq!(loaded[0].repos[0].base_refs()[0].name, "refs/heads/main");
        assert_eq!(
            loaded[0].repos[0].config_snapshot().as_deref(),
            Some(&b"[core]\n"[..])
        );
        assert_eq!(loaded[0].session_key.expose(), "k".repeat(64));

        handle.remove();
        assert!(records.load_all().is_empty());
    }

    #[test]
    fn a_revoked_token_leaves_the_record_and_a_removed_record_stays_removed() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let mut first = record();
        let component_id = Uuid::new_v4();
        first.assign.workspaces = vec![
            serde_json::from_value(serde_json::json!({
                "componentId": component_id,
                "dir": "repo",
                "name": "Repo",
                "repoUrl": "https://git.example.invalid/acme/repo.git",
                "defaultBranch": "main",
                "branch": "agent/T-1",
                "git": {"username": "x-access-token", "token": "revoked-token-0123456789"},
            }))
            .unwrap(),
        ];
        let tokens = RunGitTokens::new(&first.assign);
        let run_id = first.run_id();
        let path = dir.path().join(format!("data/runs/{run_id}.json"));
        let handle = Arc::new(RecordHandle::create(&records, first).unwrap());
        tokens.keep_record(handle.clone());
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.contains("revoked-token-0123456789"));
        assert!(!text.contains("\"revoked\""), "nothing revoked yet: {text}");

        tokens.revoke(&[component_id], "SUSPENDED", "the holder was suspended");
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(!text.contains("revoked-token-0123456789"), "{text}");
        assert_eq!(crate::secure_fs::mode_of(&path), Some(0o600));
        let loaded = records.load_all();
        assert_eq!(loaded[0].version, RECORD_VERSION);
        assert!(loaded[0].assign.workspaces[0].git.is_none());
        assert_eq!(
            loaded[0].revoked,
            vec![RevokedRepository {
                component_id,
                reason: "SUSPENDED".into(),
                message: "the holder was suspended".into(),
            }]
        );
        let restored = RunGitTokens::restored(&loaded[0].assign, &loaded[0].revoked, None);
        assert!(restored.authorization(component_id).is_err());
        // The revoked token is gone from the record, but its salted hashes are there: a host
        // that takes the run back still finds it, in plain text and in a `Basic` header.
        let hashes = loaded[0]
            .redaction
            .as_ref()
            .expect("the record keeps hashes");
        let matcher = crate::secret_hashes::HashedMatcher::new(hashes).unwrap();
        assert!(matcher.contains(b"pushed revoked-token-0123456789 by mistake"));
        let header = crate::git_tokens::basic_authorization(
            "x-access-token",
            &tinfirm_protocol::Secret::new("revoked-token-0123456789"),
        );
        assert!(matcher.contains(header.expose().as_bytes()));

        handle.remove();
        handle.numbered(10 * SEQ_BLOCK);
        tokens.revoke(&[Uuid::new_v4()], "SUSPENDED", "nothing");
        assert!(!path.exists(), "a removed record is not written again");
    }

    #[test]
    fn a_collected_record_keeps_how_each_repository_came_back_and_reads_as_before_without_it() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let first = record();
        let handle = RecordHandle::create(&records, first.clone()).unwrap();
        let text = serde_json::to_string(&records.load_all()[0]).unwrap();
        assert!(!text.contains("collected"), "{text}");
        assert!(handle.collected(&[(
            "repo".into(),
            CollectedRecord::Ok {
                wip: true,
                head: "def".into()
            }
        )]));
        assert_eq!(records.census(), (1, 1));
        let loaded = records.load_all();
        assert!(loaded[0].collected);
        assert_eq!(
            loaded[0].repos[0].collected.as_ref().unwrap().restore(),
            Ok(Collected {
                wip: true,
                head: "def".into()
            })
        );
        let failed = CollectedRecord::of(&Err("the bundle was refused".into()));
        assert_eq!(failed.restore(), Err("the bundle was refused".to_string()));
    }

    #[test]
    fn a_record_of_a_runs_end_keeps_its_outcome_and_its_files_and_reads_as_before_without_them() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let handle = RecordHandle::create(&records, record()).unwrap();
        let outcome = OutcomeRecord {
            summary: Some("Done.".into()),
            session_id: Some("session-1".into()),
            session: SessionRecord::NotResumed {
                outcome: ResumeOutcome::FirstRun,
            },
            ..OutcomeRecord::default()
        };
        // Before the run's end has marked the record, neither is written.
        assert!(handle.outcome_decided(outcome.clone()));
        assert!(handle.output_saved(&[]));
        assert!(records.load_all()[0].end.is_none());

        assert!(handle.collected_at_the_end(
            &[(
                "repo".into(),
                CollectedRecord::Ok {
                    wip: true,
                    head: "def".into()
                }
            )],
            false,
            false
        ));
        let loaded = records.load_all();
        assert!(loaded[0].collected);
        assert_eq!(loaded[0].end, Some(EndRecord::default()));
        assert_eq!(records.census(), (1, 1));

        assert!(handle.outcome_decided(outcome.clone()));
        let artifact = ArtifactResult {
            name: "report.md".into(),
            attachment_id: uuid::Uuid::new_v4(),
            size_bytes: 12,
        };
        // Each file as it is saved, then the whole of /output.
        assert!(handle.output_file_saved(&artifact));
        let end = records.load_all()[0].end.clone().unwrap();
        assert_eq!(end.artifacts, vec![artifact.clone()]);
        assert!(!end.output_saved);
        assert_eq!(handle.saved_artifacts(), vec![artifact.clone()]);
        assert!(handle.output_saved(std::slice::from_ref(&artifact)));
        let end = records.load_all()[0].end.clone().unwrap();
        assert_eq!(end.outcome, Some(outcome));
        assert_eq!(end.artifacts, vec![artifact]);
        assert!(end.output_saved);
        let text = std::fs::read_to_string(
            dir.path()
                .join(format!("data/runs/{}.json", loaded[0].run_id())),
        )
        .unwrap();
        assert!(
            text.contains(r#""session":{"state":"notResumed","outcome":"FIRST_RUN"}"#),
            "{text}"
        );
        // An outcome without a session, as JSON of the fields it has, reads back.
        let read: OutcomeRecord = serde_json::from_str(r#"{"cancelled":true}"#).unwrap();
        assert!(read.cancelled && read.session == SessionRecord::NotOpened);
    }

    #[test]
    fn a_record_of_a_runs_end_has_the_next_version_and_both_versions_are_read() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let first = record();
        let path = dir
            .path()
            .join(format!("data/runs/{}.json", first.run_id()));
        let handle = RecordHandle::create(&records, first).unwrap();
        let version = |path: &Path| {
            serde_json::from_slice::<serde_json::Value>(&std::fs::read(path).unwrap()).unwrap()
                ["version"]
                .clone()
        };
        // A record without `end` stays of version 1, which a host before this one reads.
        assert_eq!(version(&path), 1);
        assert_eq!(records.load_all().len(), 1);
        // With `end`, version 2: a host before this one removes it, and does not misread it.
        assert!(handle.collected_at_the_end(&[], true, false));
        assert_eq!(version(&path), 2);
        let loaded = records.load_all();
        assert_eq!(loaded.len(), 1);
        assert!(loaded[0].end.as_ref().unwrap().leak);
        // A record written by Records::save with `end` is of version 2 too.
        let mut other = record();
        other.end = Some(EndRecord::default());
        records.save(&other).unwrap();
        let other_path = dir
            .path()
            .join(format!("data/runs/{}.json", other.run_id()));
        assert_eq!(version(&other_path), 2);
        // A version this host does not know is not taken back.
        let mut unknown: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&other_path).unwrap()).unwrap();
        unknown["version"] = 3.into();
        std::fs::write(&other_path, serde_json::to_vec(&unknown).unwrap()).unwrap();
        assert_eq!(records.load_all().len(), 1);
        assert!(!other_path.exists());
    }

    /// A newer execution whose record cannot be written takes nothing from the older one: the
    /// older execution still writes and removes its own record, so that its credentials do not
    /// stay on disk until the next start.
    #[test]
    fn a_newer_record_that_cannot_be_written_leaves_the_older_one_with_its_owner() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let mut first = record();
        first.assign.epoch = 1;
        let run_id = first.run_id();
        let older = RecordHandle::create(&records, first.clone()).unwrap();
        // The newer execution's write fails: a directory stands where its temporary file goes.
        let blocker = dir.path().join(format!("data/runs/{run_id}.json.tmp"));
        std::fs::create_dir(&blocker).unwrap();
        let mut second = first.clone();
        second.assign.epoch = 2;
        assert!(RecordHandle::create(&records, second).is_err());
        std::fs::remove_dir(&blocker).unwrap();
        // The older execution's record is whole, and still its own to change and to remove.
        let loaded = records.load_all();
        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].assign.epoch, 1);
        assert!(older.update(|record| {
            record.session_id = Some("still mine".into());
            true
        }));
        assert_eq!(
            records.load_all()[0].session_id.as_deref(),
            Some("still mine")
        );
        older.remove();
        assert!(records.load_all().is_empty());
    }

    #[test]
    fn only_the_newest_handle_of_a_run_writes_or_removes_its_record() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let mut first = record();
        first.assign.epoch = 1;
        let run_id = first.run_id();
        let older = RecordHandle::create(&records, first.clone()).unwrap();
        // A newer execution of the same run, on the same host, gets its own record.
        let mut second = first.clone();
        second.assign.epoch = 2;
        let newer = RecordHandle::create(&records, second).unwrap();
        // The older one's updates and its removal change nothing.
        assert!(!older.update(|record| {
            record.session_id = Some("stale".into());
            true
        }));
        older.numbered(10 * SEQ_BLOCK);
        older.collected_at_the_end(&[], false, false);
        older.remove();
        let finished: tinfirm_protocol::RunFinished = serde_json::from_value(serde_json::json!({
            "runId": run_id, "epoch": 1, "outcome": "SUCCEEDED", "lastSeq": 3
        }))
        .unwrap();
        assert!(!older.report_made(&finished));
        let loaded = records.load_all();
        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].assign.epoch, 2);
        assert_eq!(loaded[0].session_id, None);
        assert_eq!(loaded[0].seq_floor, SEQ_BLOCK);
        assert!(!loaded[0].collected);
        assert!(records.load_reports().is_empty());
        // The newer one's go through.
        newer.session_opened("s");
        assert_eq!(records.load_all()[0].session_id.as_deref(), Some("s"));
        newer.remove();
        assert!(records.load_all().is_empty());
    }

    #[test]
    fn a_report_takes_the_records_place_and_is_kept_until_it_goes() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let first = record();
        let run_id = first.run_id();
        let handle = RecordHandle::create(&records, first).unwrap();
        let finished: tinfirm_protocol::RunFinished = serde_json::from_value(serde_json::json!({
            "runId": run_id, "epoch": 2, "outcome": "FAILED", "lastSeq": 1003,
            "failureClass": "INFRA", "failureMessage": "m"
        }))
        .unwrap();
        assert!(handle.report_made(&finished));
        // One step: the record is gone, and the report is there, 0600, under a name that a host
        // before this one does not read as a record.
        assert!(records.load_all().is_empty());
        let path = dir
            .path()
            .join(format!("data/runs/{run_id}.2.finished.json"));
        assert_eq!(crate::secure_fs::mode_of(&path), Some(0o600));
        assert_eq!(records.census(), (0, 0));
        assert_eq!(records.load_reports(), vec![finished.clone()]);
        // A later change of the handle writes nothing.
        handle.numbered(10 * SEQ_BLOCK);
        assert!(records.load_all().is_empty());
        // Another execution's removal leaves it; its own takes it.
        records.remove_report(run_id, 1);
        assert_eq!(records.load_reports().len(), 1);
        records.remove_report(run_id, 2);
        assert!(records.load_reports().is_empty());
        // One that cannot be read is removed, and so is one whose name and content disagree.
        std::fs::write(&path, b"{").unwrap();
        assert!(records.load_reports().is_empty());
        assert!(!path.exists());
        records.save_report(&finished).unwrap();
        let misnamed = dir
            .path()
            .join(format!("data/runs/{run_id}.3.finished.json"));
        std::fs::rename(&path, &misnamed).unwrap();
        assert!(records.load_reports().is_empty());
        assert!(!misnamed.exists());
    }

    #[test]
    fn a_record_whose_write_failed_is_written_again_by_the_next_update() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let first = record();
        let run_id = first.run_id();
        let handle = RecordHandle::create(&records, first).unwrap();
        // A directory where the temporary file goes: the next write fails.
        let blocker = dir.path().join(format!("data/runs/{run_id}.json.tmp"));
        std::fs::create_dir(&blocker).unwrap();
        assert!(!handle.update(|record| {
            record.session_id = Some("s".into());
            true
        }));
        assert_eq!(records.load_all()[0].session_id, None);
        std::fs::remove_dir(&blocker).unwrap();
        // Nothing changes now, but the record on disk is behind: it is written.
        assert!(handle.update(|_| false));
        assert_eq!(records.load_all()[0].session_id.as_deref(), Some("s"));
    }

    #[test]
    fn a_record_without_hashes_reads_as_before_and_one_with_them_keeps_them() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        // A record of the previous version: no `redaction`.
        let first = record();
        records.save(&first).unwrap();
        let text = std::fs::read_to_string(
            dir.path()
                .join(format!("data/runs/{}.json", first.run_id())),
        )
        .unwrap();
        assert!(!text.contains("redaction"), "{text}");
        let loaded = records.load_all();
        assert_eq!(loaded.len(), 1);
        assert!(loaded[0].redaction.is_none());
        // With hashes: written and read back as they are, beside the fields of before.
        let mut second = record();
        let mut hashes = crate::secret_hashes::SecretHashes::new();
        hashes.add("ghs_TEST_ONLY_an_earlier_token_0123");
        second.redaction = Some(hashes.clone());
        records.save(&second).unwrap();
        let loaded = records.load_all();
        let kept = loaded
            .iter()
            .find(|r| r.run_id() == second.run_id())
            .unwrap();
        assert_eq!(kept.redaction.as_ref(), Some(&hashes));
    }

    #[test]
    fn a_records_debug_never_shows_its_bridges_key_and_its_file_keeps_it() {
        let key: Vec<u8> = (0u8..=255).cycle().skip(7).take(120).collect();
        let keep = crate::sandbox::BridgeKeep {
            cert: b"certificate".to_vec(),
            key: key.clone(),
            reach: crate::sandbox::BridgeReach::Listen {
                ip: "10.1.2.3".parse().unwrap(),
                port: 41234,
                peer: "10.1.2.4".parse().unwrap(),
            },
        };
        let mut with = record();
        with.bridge = Some(BridgeRecord::of(&keep));
        let encoded = B64.encode(&key);
        let shown = format!("{with:?} {:?} {keep:?}", with.bridge);
        assert!(shown.contains("41234"), "{shown}");
        // No run of the key's text shows, however it is cut.
        for part in encoded.as_bytes().chunks(8) {
            let part = std::str::from_utf8(part).unwrap();
            assert!(!shown.contains(part), "{part} in {shown}");
        }
        // The file holds the key as before, as the base64 of its DER, and gives it back.
        let text = serde_json::to_string(&with).unwrap();
        assert!(text.contains(&format!("\"key\":\"{encoded}\"")), "{text}");
        let read: RunRecord = serde_json::from_str(&text).unwrap();
        assert_eq!(read.bridge.unwrap().keep(None).unwrap().key, key);
    }

    #[test]
    fn a_vms_record_keeps_its_port_and_one_without_a_port_reads_as_before() {
        let with = SandboxRecord::Vm {
            id: "vm-1".into(),
            host: "h".into(),
            label: Some("l".into()),
            address: "10.0.0.20".into(),
            port: Some(20001),
        };
        let text = serde_json::to_string(&with).unwrap();
        assert!(text.contains("\"port\":20001"), "{text}");
        assert_eq!(serde_json::from_str::<SandboxRecord>(&text).unwrap(), with);
        // A record written before the port existed: no port, which means `--vm-port`.
        let before: SandboxRecord = serde_json::from_str(
            r#"{"kind":"vm","id":"vm-1","host":"h","label":"l","address":"10.0.0.20"}"#,
        )
        .unwrap();
        assert_eq!(
            before,
            SandboxRecord::Vm {
                id: "vm-1".into(),
                host: "h".into(),
                label: Some("l".into()),
                address: "10.0.0.20".into(),
                port: None,
            }
        );
        assert!(!serde_json::to_string(&before).unwrap().contains("port"));
    }

    #[test]
    fn an_unreadable_record_is_removed_and_the_listener_keeps_its_identity() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        crate::secure_fs::ensure_private_dir(&paths.data_dir.join("runs")).unwrap();
        let broken = paths.data_dir.join(format!("runs/{}.json", Uuid::new_v4()));
        crate::secure_fs::write_private_file(&broken, b"{").unwrap();
        assert!(records.load_all().is_empty());
        assert!(!broken.exists());

        assert!(records.listener().is_none());
        let identity = Identity::generate().unwrap();
        records.save_listener(&identity, 40123).unwrap();
        let (kept, port) = records.listener().unwrap();
        assert_eq!(port, 40123);
        assert_eq!(kept.pin_hex(), identity.pin_hex());
    }
}
