//! A runner host that restarted takes its runs back (ADR-0101, threat model R36).
//!
//! While a run's sandbox lives on the sandbox bridge, the host keeps a record of it in its data
//! directory (`runs/<runId>.json`, 0600): the assignment, the sandbox, the bridge session's key,
//! the repositories as they were cloned, and the agent's session once it is open. The bridge
//! listener's identity and port are kept beside them (`runs/bridge.json`), since every bridge
//! pins the one and dials the other. A record goes once the sandbox has stopped.
//!
//! A host that starts and finds records takes each run back before it says hello: the run is
//! listed in `hello.activeRuns`, so the server keeps it; its bridge comes back with the session
//! key; the agent that the earlier process started is stopped; and the run ends as a run that
//! the host stopped does: the work comes back from the sandbox and is pushed, the session's
//! bundle is stored, and the run is reported failed with `INFRA`. The next turn goes on from
//! the session. A record that cannot be taken back (another executor now, or no bridge) is
//! reported in the log and removed.
//!
//! The record's git tokens are those of the run's holder ([`crate::git_tokens`]): the record is
//! written again whenever the holder changes ([`RecordHandle::sync_git`]). A repository whose
//! token the server revoked (ADR-0116) has no `git` in the record's assignment and is named in
//! `revoked` with the server's reason and message, so a host that restarts neither uses the
//! token nor pushes the repository. The format stays version 1: a record without `revoked`
//! revoked nothing.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

use anyhow::{Context, Result};
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use tinfirm_bridge::control::Hidden;
use tinfirm_bridge::tls::Identity;
use tinfirm_protocol::RunAssign;
use uuid::Uuid;

use crate::git::MirrorRef;
use crate::git_tokens::{RevokedRepository, RunGitTokens};
use crate::paths::Paths;
use crate::sandbox_files::Collected;

/// The format of the records. A record of another one is not taken back.
pub const RECORD_VERSION: u32 = 1;

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

/// The records of a data directory.
#[derive(Debug, Clone)]
pub struct Records {
    dir: PathBuf,
}

impl Records {
    pub fn new(paths: &Paths) -> Records {
        Records {
            dir: paths.data_dir.join("runs"),
        }
    }

    fn path(&self, run_id: Uuid) -> PathBuf {
        self.dir.join(format!("{run_id}.json"))
    }

    fn listener_path(&self) -> PathBuf {
        self.dir.join("bridge.json")
    }

    /// Writes `record`, in place of the run's earlier one.
    pub fn save(&self, record: &RunRecord) -> Result<()> {
        crate::secure_fs::ensure_private_dir(&self.dir)?;
        let bytes = serde_json::to_vec(record).context("cannot encode the run's record")?;
        write_replacing(&self.path(record.run_id()), &bytes)
    }

    pub fn remove(&self, run_id: Uuid) {
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
                Ok(record) if record.version == RECORD_VERSION && record.run_id() == id => {
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

/// A run's record while its sandbox lives: the run updates it as it goes.
pub struct RecordHandle {
    records: Records,
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
    /// Writes the record and keeps it.
    pub fn create(records: &Records, record: RunRecord) -> Result<RecordHandle> {
        records.save(&record)?;
        Ok(RecordHandle::kept(records, record))
    }

    /// A record that is written already: a run's that this host takes back.
    pub fn kept(records: &Records, record: RunRecord) -> RecordHandle {
        RecordHandle {
            records: records.clone(),
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
        match self.records.save(&record) {
            Ok(()) => {
                self.unsaved.store(false, Ordering::SeqCst);
                true
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
            if assign == record.assign && revoked == record.revoked {
                return false;
            }
            record.assign = assign;
            record.revoked = revoked;
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

    /// Nothing is left to take back: the run has ended, or its sandbox did not come back.
    pub fn remove(&self) {
        let record = self.record.lock().unwrap_or_else(|e| e.into_inner());
        self.removed.store(true, Ordering::SeqCst);
        self.records.remove(record.run_id());
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;

    fn record() -> RunRecord {
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
        let restored = RunGitTokens::restored(&loaded[0].assign, &loaded[0].revoked);
        assert!(restored.authorization(component_id).is_err());

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
