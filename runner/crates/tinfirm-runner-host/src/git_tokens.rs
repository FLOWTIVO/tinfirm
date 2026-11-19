//! The one holder of a run's git tokens (ADR-0116, decision 3).
//!
//! Each repository of a run (`run.assign.workspaces[]`, by `componentId`) has one entry: its
//! token with the `Authorization` header made from it, no token (a public repository), or why
//! its token is gone. Every user of a token reads it here at the moment of use, never a copy
//! taken at the start: the mirror fetch and the clone ([`crate::mirror`], [`crate::git::Git`]),
//! the git proxy's route for each request of the sandbox ([`crate::git_proxy`]), the mirror
//! fetch before the push and the push itself ([`crate::run`]), and the take-back record
//! ([`crate::takeback::RecordHandle::sync_git`]), which is written again whenever the holder
//! changes.
//!
//! A `run.git.revoke` of the run's current epoch ([`RunGitTokens::revoke`]) drops a
//! repository's token from memory and keeps the server's reason and message instead. From then
//! on the git proxy answers the sandbox's requests for the repository with 403 and the message,
//! no clone, mirror fetch or push of it is attempted, and the take-back record holds no token
//! for it but names it as revoked, so a host that restarts does not take the token back. A
//! revocation is for the rest of the run: nothing gives a revoked repository a token again.
//!
//! The redaction is not the holder's: every token a run was given stays on the run's redaction
//! list until the run ends ([`crate::host::Shared::add_run_secrets`]), revoked ones included.
//!
//! Nothing of the holder reaches a reader process ([`crate::reader`]): the reader's job
//! description names directories and refs, never a repository's credentials.

use std::sync::{Arc, Mutex};

use base64::Engine;
use serde::{Deserialize, Serialize};
use tinfirm_protocol::{GitCredentials, LogLevel, RunAssign, Secret, WorkspaceSpec};
use uuid::Uuid;

use crate::run::EventSink;
use crate::takeback::RecordHandle;

/// Why a repository's token is gone: the server's reason code and its message for the run's
/// log and the agent. Its `Display` is the message.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Revocation {
    pub reason: String,
    pub message: String,
}

impl std::fmt::Display for Revocation {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for Revocation {}

/// A repository named in the take-back record as revoked (`runs/<runId>.json`, `revoked[]`):
/// its `git` in the record's assignment is null.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RevokedRepository {
    pub component_id: Uuid,
    pub reason: String,
    pub message: String,
}

/// A token the run holds for one repository.
#[derive(Clone)]
struct Held {
    username: String,
    token: Secret,
    /// `Basic base64(username:token)`.
    authorization: Secret,
}

impl Held {
    fn new(username: &str, token: &Secret) -> Held {
        Held {
            username: username.to_string(),
            token: token.clone(),
            authorization: basic_authorization(username, token),
        }
    }
}

#[derive(Clone)]
enum Slot {
    /// No token: a public repository.
    Public,
    Held(Held),
    Revoked(Revocation),
}

struct Entry {
    component_id: Uuid,
    slot: Slot,
}

/// Where the holder logs a revocation: the run's events once the run has its sink, and until
/// then a list that the sink takes when it comes.
enum Log {
    Waiting(Vec<String>),
    Sink(EventSink),
    Closed,
}

/// The git tokens of one execution of a run (a run and its epoch).
pub struct RunGitTokens {
    run_id: Uuid,
    epoch: i64,
    entries: Mutex<Vec<Entry>>,
    /// The take-back record while the run's sandbox lives (ADR-0101).
    record: Mutex<Option<Arc<RecordHandle>>>,
    log: Mutex<Log>,
}

impl std::fmt::Debug for RunGitTokens {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RunGitTokens")
            .field("run_id", &self.run_id)
            .field("epoch", &self.epoch)
            .finish_non_exhaustive()
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// The `Authorization` header value (`Basic …`) of `username` and `token`.
pub fn basic_authorization(username: &str, token: &Secret) -> Secret {
    let basic = base64::engine::general_purpose::STANDARD.encode(format!(
        "{}:{}",
        username,
        token.expose()
    ));
    Secret::new(format!("Basic {basic}"))
}

impl RunGitTokens {
    /// The tokens of `assign`'s repositories, as the server sent them.
    pub fn new(assign: &RunAssign) -> Arc<RunGitTokens> {
        RunGitTokens::of(assign.run_id, assign.epoch, &assign.workspaces, &[])
    }

    /// The tokens of a run that this host takes back (ADR-0101): those of the record's
    /// assignment, and none for the repositories it names as revoked.
    pub fn restored(assign: &RunAssign, revoked: &[RevokedRepository]) -> Arc<RunGitTokens> {
        RunGitTokens::of(assign.run_id, assign.epoch, &assign.workspaces, revoked)
    }

    /// A holder of no repository (tests).
    pub fn new_empty(run_id: Uuid, epoch: i64) -> Arc<RunGitTokens> {
        RunGitTokens::of(run_id, epoch, &[], &[])
    }

    /// A holder of one repository's token alone, outside any run (tests and tools).
    pub fn standalone(spec: &WorkspaceSpec) -> RepoTokens {
        RunGitTokens::of(Uuid::nil(), 0, std::slice::from_ref(spec), &[]).repo(spec.component_id)
    }

    fn of(
        run_id: Uuid,
        epoch: i64,
        workspaces: &[WorkspaceSpec],
        revoked: &[RevokedRepository],
    ) -> Arc<RunGitTokens> {
        let entries = workspaces
            .iter()
            .map(|ws| {
                let slot = match revoked.iter().find(|r| r.component_id == ws.component_id) {
                    Some(r) => Slot::Revoked(Revocation {
                        reason: r.reason.clone(),
                        message: r.message.clone(),
                    }),
                    None => match &ws.git {
                        Some(git) => Slot::Held(Held::new(&git.username, &git.token)),
                        None => Slot::Public,
                    },
                };
                Entry {
                    component_id: ws.component_id,
                    slot,
                }
            })
            .collect();
        Arc::new(RunGitTokens {
            run_id,
            epoch,
            entries: Mutex::new(entries),
            record: Mutex::new(None),
            log: Mutex::new(Log::Waiting(Vec::new())),
        })
    }

    pub fn run_id(&self) -> Uuid {
        self.run_id
    }

    pub fn epoch(&self) -> i64 {
        self.epoch
    }

    /// The handle through which one repository's users read its token.
    pub fn repo(self: &Arc<Self>, component_id: Uuid) -> RepoTokens {
        RepoTokens {
            tokens: self.clone(),
            component_id,
        }
    }

    fn slot(&self, component_id: Uuid) -> Option<Slot> {
        lock(&self.entries)
            .iter()
            .find(|e| e.component_id == component_id)
            .map(|e| e.slot.clone())
    }

    /// The `Authorization` header for `component_id` right now: `Ok(None)` for a public
    /// repository (or one the run does not have), `Err` once its token is revoked.
    pub fn authorization(&self, component_id: Uuid) -> Result<Option<Secret>, Revocation> {
        match self.slot(component_id) {
            Some(Slot::Held(held)) => Ok(Some(held.authorization)),
            Some(Slot::Revoked(revocation)) => Err(revocation),
            Some(Slot::Public) | None => Ok(None),
        }
    }

    /// Why `component_id`'s token is gone, if it is.
    pub fn revocation(&self, component_id: Uuid) -> Option<Revocation> {
        match self.slot(component_id) {
            Some(Slot::Revoked(revocation)) => Some(revocation),
            _ => None,
        }
    }

    /// Drops the tokens of `component_ids` (ADR-0116, decision 3): an id the run does not have
    /// is ignored, and a repository revoked already keeps its first reason. Each repository
    /// revoked now is logged as a warning in the run's events, and the take-back record is
    /// written again without its token. Returns the repositories revoked now.
    pub fn revoke(&self, component_ids: &[Uuid], reason: &str, message: &str) -> Vec<Uuid> {
        let revocation = Revocation {
            reason: reason.to_string(),
            message: message.to_string(),
        };
        let mut revoked = Vec::new();
        {
            let mut entries = lock(&self.entries);
            for entry in entries.iter_mut() {
                if component_ids.contains(&entry.component_id)
                    && !matches!(entry.slot, Slot::Revoked(_))
                {
                    // The token's value goes with the slot; the redaction list keeps its own.
                    entry.slot = Slot::Revoked(revocation.clone());
                    revoked.push(entry.component_id);
                }
            }
        }
        if revoked.is_empty() {
            tracing::debug!(run_id = %self.run_id, epoch = self.epoch, reason, "run.git.revoke names no repository whose token this run still holds");
            return revoked;
        }
        tracing::warn!(run_id = %self.run_id, epoch = self.epoch, reason, repositories = revoked.len(), "the server revoked git tokens of the run");
        self.sync_record();
        self.log_warn(message);
        revoked
    }

    /// Keeps the run's take-back record in step with the holder from now on, and writes it at
    /// once if the holder changed since the record was made.
    pub fn keep_record(&self, record: Arc<RecordHandle>) {
        *lock(&self.record) = Some(record);
        self.sync_record();
    }

    /// The record is gone with the sandbox.
    pub fn drop_record(&self) {
        lock(&self.record).take();
    }

    fn sync_record(&self) {
        let record = lock(&self.record).clone();
        if let Some(record) = record {
            record.sync_git(self);
        }
    }

    /// Writes the holder into `assign` as the take-back record keeps it: each held token in
    /// its repository's `git`, no `git` for a revoked repository, which the returned list
    /// names instead.
    pub fn write_to(&self, assign: &mut RunAssign) -> Vec<RevokedRepository> {
        let entries = lock(&self.entries);
        let mut revoked = Vec::new();
        for ws in &mut assign.workspaces {
            let Some(entry) = entries.iter().find(|e| e.component_id == ws.component_id) else {
                continue;
            };
            match &entry.slot {
                Slot::Held(held) => {
                    ws.git = Some(GitCredentials {
                        username: held.username.clone(),
                        token: held.token.clone(),
                    });
                }
                Slot::Revoked(revocation) => {
                    ws.git = None;
                    revoked.push(RevokedRepository {
                        component_id: ws.component_id,
                        reason: revocation.reason.clone(),
                        message: revocation.message.clone(),
                    });
                }
                Slot::Public => ws.git = None,
            }
        }
        revoked
    }

    /// Logs revocations in the run's events from now on (and those that came before).
    pub fn attach_log(&self, sink: EventSink) {
        let mut log = lock(&self.log);
        let waiting = match std::mem::replace(&mut *log, Log::Closed) {
            Log::Waiting(waiting) => waiting,
            Log::Sink(_) | Log::Closed => Vec::new(),
        };
        let mut sink = sink;
        for message in waiting {
            sink.log(LogLevel::Warn, message);
        }
        *log = Log::Sink(sink);
    }

    /// The run reports its end: nothing is logged after it.
    pub fn close_log(&self) {
        *lock(&self.log) = Log::Closed;
    }

    fn log_warn(&self, message: &str) {
        match &mut *lock(&self.log) {
            Log::Waiting(waiting) => waiting.push(message.to_string()),
            Log::Sink(sink) => sink.log(LogLevel::Warn, message),
            Log::Closed => {}
        }
    }
}

/// One repository's view of its run's holder: what its users read the token through.
#[derive(Clone)]
pub struct RepoTokens {
    tokens: Arc<RunGitTokens>,
    component_id: Uuid,
}

impl std::fmt::Debug for RepoTokens {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RepoTokens")
            .field("run_id", &self.tokens.run_id)
            .field("component_id", &self.component_id)
            .finish()
    }
}

impl RepoTokens {
    pub fn component_id(&self) -> Uuid {
        self.component_id
    }

    pub fn run(&self) -> &Arc<RunGitTokens> {
        &self.tokens
    }

    /// The `Authorization` header right now; see [`RunGitTokens::authorization`].
    pub fn authorization(&self) -> Result<Option<Secret>, Revocation> {
        self.tokens.authorization(self.component_id)
    }

    /// Why the token is gone, if it is.
    pub fn revocation(&self) -> Option<Revocation> {
        self.tokens.revocation(self.component_id)
    }
}

impl crate::git_proxy::RouteCredentials for RepoTokens {
    fn authorization(&self) -> Result<Option<Secret>, String> {
        RepoTokens::authorization(self).map_err(|revocation| revocation.message)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assign(workspaces: serde_json::Value) -> RunAssign {
        serde_json::from_value(serde_json::json!({
            "runId": Uuid::new_v4(),
            "epoch": 3,
            "credentialLabel": "claude",
            "task": {"id": Uuid::new_v4(), "key": "T-1", "title": "t"},
            "agent": {"id": Uuid::new_v4(), "name": "a", "runtime": "CLAUDE_CODE"},
            "prompt": "p",
            "limits": {"maxWallClockSeconds": 60, "maxTurns": 3},
            "workspaces": workspaces,
            "requestableRepositories": [],
        }))
        .unwrap()
    }

    fn workspace(dir: &str, token: Option<&str>) -> serde_json::Value {
        serde_json::json!({
            "componentId": Uuid::new_v4(),
            "dir": dir,
            "name": dir,
            "repoUrl": format!("https://git.example.invalid/acme/{dir}.git"),
            "defaultBranch": "main",
            "branch": "agent/T-1",
            "git": token.map(|t| serde_json::json!({"username": "x-access-token", "token": t})),
        })
    }

    #[test]
    fn a_revoked_token_is_gone_for_every_user_and_from_the_record_view() {
        let assign = assign(serde_json::json!([
            workspace("a", Some("token-of-a-0123456789")),
            workspace("b", Some("token-of-b-0123456789")),
            workspace("c", None),
        ]));
        let ids: Vec<Uuid> = assign.workspaces.iter().map(|w| w.component_id).collect();
        let tokens = RunGitTokens::new(&assign);
        let a = tokens.repo(ids[0]);
        let expected = basic_authorization("x-access-token", &Secret::new("token-of-a-0123456789"));
        assert_eq!(
            a.authorization().unwrap().unwrap().expose(),
            expected.expose()
        );
        assert_eq!(tokens.authorization(ids[2]).unwrap(), None, "public");
        assert_eq!(tokens.authorization(Uuid::new_v4()).unwrap(), None);

        // An id of no workspace is ignored; a named one goes.
        let revoked = tokens.revoke(&[ids[0], Uuid::new_v4()], "GRANT_REMOVED", "no more");
        assert_eq!(revoked, vec![ids[0]]);
        let err = a.authorization().unwrap_err();
        assert_eq!(err.message, "no more");
        assert_eq!(err.reason, "GRANT_REMOVED");
        assert_eq!(a.revocation(), Some(err));
        assert!(tokens.authorization(ids[1]).unwrap().is_some(), "b goes on");
        // Applied once: a second frame changes nothing, the first reason stays.
        assert!(
            tokens
                .revoke(&[ids[0]], "SYSTEM_PAUSED", "again")
                .is_empty()
        );
        assert_eq!(a.revocation().unwrap().reason, "GRANT_REMOVED");

        let mut view = assign.clone();
        let named = tokens.write_to(&mut view);
        assert!(view.workspaces[0].git.is_none());
        assert_eq!(
            view.workspaces[1].git.as_ref().unwrap().token.expose(),
            "token-of-b-0123456789"
        );
        assert!(view.workspaces[2].git.is_none());
        assert_eq!(
            named,
            vec![RevokedRepository {
                component_id: ids[0],
                reason: "GRANT_REMOVED".into(),
                message: "no more".into(),
            }]
        );
        assert!(!serde_json::to_string(&view).unwrap().contains("token-of-a"));

        // A host that restarts with that record does not take the token back.
        let restored = RunGitTokens::restored(&view, &named);
        assert_eq!(restored.revocation(ids[0]).unwrap().message, "no more");
        assert!(restored.authorization(ids[1]).unwrap().is_some());
    }

    #[test]
    fn revocations_before_the_runs_events_wait_for_them() {
        let assign = assign(serde_json::json!([workspace(
            "a",
            Some("token-of-a-0123456789")
        )]));
        let tokens = RunGitTokens::new(&assign);
        tokens.revoke(&[assign.workspaces[0].component_id], "SUSPENDED", "m1");
        assert!(matches!(&*lock(&tokens.log), Log::Waiting(w) if w == &["m1".to_string()]));
        tokens.close_log();
        tokens.revoke(&[assign.workspaces[0].component_id], "SUSPENDED", "m2");
        assert!(matches!(&*lock(&tokens.log), Log::Closed));
    }
}
