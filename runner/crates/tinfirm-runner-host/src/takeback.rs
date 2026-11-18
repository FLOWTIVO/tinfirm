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

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use anyhow::{Context, Result};
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use tinfirm_bridge::control::Hidden;
use tinfirm_bridge::tls::Identity;
use tinfirm_protocol::RunAssign;
use uuid::Uuid;

use crate::git::MirrorRef;
use crate::paths::Paths;

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
    /// label, and where its bridge listens.
    Vm {
        id: String,
        host: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        label: Option<String>,
        address: String,
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
        }
    }

    fn update(&self, change: impl FnOnce(&mut RunRecord) -> bool) {
        let mut record = self.record.lock().unwrap_or_else(|e| e.into_inner());
        if change(&mut record)
            && let Err(e) = self.records.save(&record)
        {
            tracing::warn!(run_id = %record.run_id(), error = %format!("{e:#}"), "cannot update the run's record");
        }
    }

    /// The agent's session is open.
    pub fn session_opened(&self, session_id: &str) {
        self.update(|record| {
            if record.session_id.as_deref() == Some(session_id) {
                return false;
            }
            record.session_id = Some(session_id.to_string());
            true
        });
    }

    /// The run has numbered an event `seq`: the record reserves the next block when it is due.
    pub fn numbered(&self, seq: u64) {
        self.update(|record| {
            if seq < record.seq_floor {
                return false;
            }
            record.seq_floor = seq + SEQ_BLOCK;
            true
        });
    }

    /// The sandbox has stopped: nothing is left to take back.
    pub fn remove(&self) {
        let record = self.record.lock().unwrap_or_else(|e| e.into_inner());
        self.records.remove(record.run_id());
    }
}

#[cfg(test)]
mod tests {
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
