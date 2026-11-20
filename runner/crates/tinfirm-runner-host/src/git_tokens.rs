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
//! A token that the server minted for the run has an expiry (`git.expiresAt`, the feature
//! `run.git.refresh`): [`crate::git_refresh`] renews it through the server before it is due, and
//! the holder takes the new token ([`RunGitTokens::renew`]). Once a token's expiry has passed
//! without a new one, every user gets the error [`EXPIRED`] instead of it, as after a
//! revocation; unlike a revocation, a renewal that comes later makes it usable again. Such a
//! token's mirror is named by `git.mirrorKey`, the credential's, not by the token
//! ([`RunGitTokens::mirror_key`]).
//!
//! The redaction is not the holder's: every token a run was given stays on the run's redaction
//! list until the run ends ([`crate::host::Shared::add_run_secrets`]), revoked ones included.
//!
//! Nothing of the holder reaches a reader process ([`crate::reader`]): the reader's job
//! description names directories and refs, never a repository's credentials.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use base64::Engine;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tinfirm_protocol::{GitCredentials, GitToken, LogLevel, RunAssign, Secret, WorkspaceSpec};
use uuid::Uuid;

use crate::run::EventSink;
use crate::secret_hashes::SecretHashes;
use crate::takeback::{RecordHandle, Records, RunRecord};

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

/// What a [`RunGitTokens::revoke`] did.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Revoked {
    /// The repositories whose token went now, in the order the frame named them.
    pub now: Vec<Uuid>,
    /// Every repository the frame named that the run has, revoked now or before, each once,
    /// in the frame's order: what `run.git.revoked` confirms.
    pub named: Vec<Uuid>,
    /// The take-back record on disk holds none of these tokens (or the run has no record).
    pub recorded: bool,
}

/// The reason code of a token whose expiry passed without a new one.
pub const EXPIRED: &str = "EXPIRED";
/// The message of such a token (ADR-0116, decision 9).
pub const EXPIRED_MESSAGE: &str = "the git token expired and Tinfirm could not renew it";

/// The holder asks the server for a repository's new token at most this often.
pub const ASK_EVERY: Duration = Duration::from_secs(60);

/// A token the run holds for one repository.
#[derive(Clone)]
struct Held {
    username: String,
    token: Secret,
    /// `Basic base64(username:token)`.
    authorization: Secret,
    /// When it stops working; `None`: it lives until the run ends.
    expires_at: Option<DateTime<Utc>>,
    /// The credential's key, which names the mirror (with `expires_at`).
    mirror_key: Option<String>,
}

impl Held {
    fn of(git: &GitCredentials) -> Held {
        Held {
            username: git.username.clone(),
            token: git.token.clone(),
            authorization: basic_authorization(&git.username, &git.token),
            expires_at: git.expires_at,
            mirror_key: git.mirror_key.clone(),
        }
    }

    fn expired(&self, now: DateTime<Utc>) -> bool {
        self.expires_at.is_some_and(|at| at <= now)
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
    /// When the holder last asked the server for a new token ([`ASK_EVERY`]).
    asked_at: Option<Instant>,
    /// The expiry of the held token was logged already.
    expiry_logged: bool,
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
    /// The salted hashes of every token the run has held, revoked and replaced ones included,
    /// which its take-back record keeps (crate::secret_hashes).
    hashes: Mutex<SecretHashes>,
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
        RunGitTokens::of(assign.run_id, assign.epoch, &assign.workspaces, &[], None)
    }

    /// The tokens of a run that this host takes back (ADR-0101): those of the record's
    /// assignment, and none for the repositories it names as revoked. `hashes` are those the
    /// record keeps of every token the run has held; a record without them starts new ones.
    pub fn restored(
        assign: &RunAssign,
        revoked: &[RevokedRepository],
        hashes: Option<&SecretHashes>,
    ) -> Arc<RunGitTokens> {
        RunGitTokens::of(
            assign.run_id,
            assign.epoch,
            &assign.workspaces,
            revoked,
            hashes.cloned(),
        )
    }

    /// A holder of no repository (tests).
    pub fn new_empty(run_id: Uuid, epoch: i64) -> Arc<RunGitTokens> {
        RunGitTokens::of(run_id, epoch, &[], &[], None)
    }

    /// A holder of one repository's token alone, outside any run (tests and tools).
    pub fn standalone(spec: &WorkspaceSpec) -> RepoTokens {
        RunGitTokens::of(Uuid::nil(), 0, std::slice::from_ref(spec), &[], None)
            .repo(spec.component_id)
    }

    fn of(
        run_id: Uuid,
        epoch: i64,
        workspaces: &[WorkspaceSpec],
        revoked: &[RevokedRepository],
        hashes: Option<SecretHashes>,
    ) -> Arc<RunGitTokens> {
        let mut hashes = hashes.unwrap_or_default();
        // The token alone: its `Basic` header holds the token's base64, which the token's own
        // forms match, so the header's forms would only give more away.
        for git in workspaces.iter().filter_map(|ws| ws.git.as_ref()) {
            hashes.add(git.token.expose());
        }
        let entries = workspaces
            .iter()
            .map(|ws| {
                let slot = match revoked.iter().find(|r| r.component_id == ws.component_id) {
                    Some(r) => Slot::Revoked(Revocation {
                        reason: r.reason.clone(),
                        message: r.message.clone(),
                    }),
                    None => match &ws.git {
                        Some(git) => Slot::Held(Held::of(git)),
                        None => Slot::Public,
                    },
                };
                Entry {
                    component_id: ws.component_id,
                    slot,
                    asked_at: None,
                    expiry_logged: false,
                }
            })
            .collect();
        Arc::new(RunGitTokens {
            run_id,
            epoch,
            entries: Mutex::new(entries),
            record: Mutex::new(None),
            log: Mutex::new(Log::Waiting(Vec::new())),
            hashes: Mutex::new(hashes),
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
    /// repository (or one the run does not have), `Err` once its token is revoked, or while its
    /// expiry has passed without a new one ([`EXPIRED`]).
    pub fn authorization(&self, component_id: Uuid) -> Result<Option<Secret>, Revocation> {
        self.note_expired();
        match self.slot(component_id) {
            Some(Slot::Held(held)) if held.expired(Utc::now()) => Err(Revocation {
                reason: EXPIRED.to_string(),
                message: EXPIRED_MESSAGE.to_string(),
            }),
            Some(Slot::Held(held)) => Ok(Some(held.authorization)),
            Some(Slot::Revoked(revocation)) => Err(revocation),
            Some(Slot::Public) | None => Ok(None),
        }
    }

    /// Why `component_id`'s token cannot be used now, if it cannot: revoked, or expired.
    pub fn revocation(&self, component_id: Uuid) -> Option<Revocation> {
        self.authorization(component_id).err()
    }

    /// Whether the run holds a token for `component_id`, usable or expired (not revoked).
    pub fn holds(&self, component_id: Uuid) -> bool {
        matches!(self.slot(component_id), Some(Slot::Held(_)))
    }

    /// The credential's key that names `component_id`'s mirror (`git.mirrorKey`), while the
    /// run holds such a token.
    pub fn mirror_key(&self, component_id: Uuid) -> Option<String> {
        match self.slot(component_id) {
            Some(Slot::Held(held)) => held.mirror_key,
            _ => None,
        }
    }

    /// Whether a token of the run expires (and so is renewed).
    pub fn expires(&self) -> bool {
        lock(&self.entries)
            .iter()
            .any(|e| matches!(&e.slot, Slot::Held(h) if h.expires_at.is_some()))
    }

    /// The repositories whose token to renew now: each held token with an expiry that is
    /// `within` or less away (`None`: any), for which the holder did not ask during the last
    /// [`ASK_EVERY`]. They count as asked from now.
    pub fn due(&self, within: Option<Duration>) -> Vec<Uuid> {
        let now = Utc::now();
        let instant = Instant::now();
        let mut due = Vec::new();
        for entry in lock(&self.entries).iter_mut() {
            let Slot::Held(Held {
                expires_at: Some(expires_at),
                ..
            }) = &entry.slot
            else {
                continue;
            };
            let left = (*expires_at - now).to_std().unwrap_or(Duration::ZERO);
            let soon = within.is_none_or(|within| left <= within);
            let paced = entry
                .asked_at
                .is_some_and(|at| instant.duration_since(at) < ASK_EVERY);
            if soon && !paced {
                entry.asked_at = Some(instant);
                due.push(entry.component_id);
            }
        }
        due
    }

    /// Takes the server's new tokens (a GIT_TOKEN request's answer): each replaces the held
    /// token of its repository, its mirror key kept; a repository revoked meanwhile, or one the
    /// run does not hold a token of, keeps what it has. The take-back record is written again.
    /// Returns the values to add to the run's redaction (each token and its header); the old
    /// ones stay there.
    pub fn renew(&self, renewed: &[GitToken]) -> Vec<String> {
        let mut secrets = Vec::new();
        {
            let mut entries = lock(&self.entries);
            for token in renewed {
                let Some(entry) = entries
                    .iter_mut()
                    .find(|e| e.component_id == token.component_id)
                else {
                    continue;
                };
                let Slot::Held(held) = &mut entry.slot else {
                    continue;
                };
                *held = Held::of(&GitCredentials {
                    username: token.username.clone(),
                    token: token.token.clone(),
                    expires_at: Some(token.expires_at),
                    mirror_key: held.mirror_key.clone(),
                });
                entry.expiry_logged = false;
                secrets.push(held.token.expose().to_string());
                secrets.push(held.authorization.expose().to_string());
            }
        }
        {
            // The new tokens (each the first of its pair) join the hashes of every token the
            // run has held; the old ones stay there.
            let mut hashes = lock(&self.hashes);
            for token in secrets.iter().step_by(2) {
                hashes.add(token);
            }
        }
        if !secrets.is_empty() {
            tracing::info!(run_id = %self.run_id, epoch = self.epoch, repositories = secrets.len() / 2, "renewed git tokens of the run");
            self.sync_record();
        }
        secrets
    }

    /// Logs, once per token, that a token's expiry has passed without a new one.
    fn note_expired(&self) {
        let now = Utc::now();
        let mut expired = false;
        for entry in lock(&self.entries).iter_mut() {
            if let Slot::Held(held) = &entry.slot
                && held.expired(now)
                && !entry.expiry_logged
            {
                entry.expiry_logged = true;
                expired = true;
            }
        }
        if expired {
            tracing::warn!(run_id = %self.run_id, epoch = self.epoch, "a git token of the run expired without a new one");
            self.log_warn(EXPIRED_MESSAGE);
        }
    }

    /// Drops the tokens of `component_ids` (ADR-0116, decision 3): an id the run does not have
    /// is ignored, and a repository revoked already keeps its first reason. Each repository
    /// revoked now is logged as a warning in the run's events, and the take-back record is
    /// written again without its token.
    pub fn revoke(&self, component_ids: &[Uuid], reason: &str, message: &str) -> Revoked {
        let revocation = Revocation {
            reason: reason.to_string(),
            message: message.to_string(),
        };
        let mut now = Vec::new();
        let mut named = Vec::new();
        {
            let mut entries = lock(&self.entries);
            for id in component_ids {
                let Some(entry) = entries.iter_mut().find(|e| e.component_id == *id) else {
                    continue;
                };
                if named.contains(id) {
                    continue;
                }
                named.push(*id);
                if !matches!(entry.slot, Slot::Revoked(_)) {
                    // The token's value goes with the slot; the redaction list keeps its own.
                    entry.slot = Slot::Revoked(revocation.clone());
                    now.push(*id);
                }
            }
        }
        // Also when nothing changed now: a record whose last write failed is written again.
        let recorded = self.sync_record();
        if now.is_empty() {
            tracing::debug!(run_id = %self.run_id, epoch = self.epoch, reason, "run.git.revoke names no repository whose token this run still holds");
        } else {
            tracing::warn!(run_id = %self.run_id, epoch = self.epoch, reason, repositories = now.len(), "the server revoked git tokens of the run");
            self.log_warn(message);
        }
        if !recorded {
            tracing::warn!(run_id = %self.run_id, epoch = self.epoch, "the run's take-back record could not be written without the revoked tokens");
        }
        Revoked {
            now,
            named,
            recorded,
        }
    }

    /// Makes the run's take-back record and keeps it in step with the holder from now on. The
    /// snapshot of the tokens, the record's first write and its attachment to the holder are
    /// one step under the holder's record lock: a revocation that comes meanwhile waits for it,
    /// and then writes the record again without the token. So a revocation that finds no record
    /// attached is one whose record, if the run ever gets one, is made after it and never holds
    /// the token; only then is "no record" an answer that a confirmation may rest on.
    /// `build` makes the record from the assignment as the holder has it now and the
    /// repositories it names as revoked.
    pub fn make_record(
        &self,
        records: &Records,
        assign: &RunAssign,
        build: impl FnOnce(RunAssign, Vec<RevokedRepository>) -> RunRecord,
    ) -> anyhow::Result<Arc<RecordHandle>> {
        let mut slot = lock(&self.record);
        let mut kept = assign.clone();
        let revoked = self.write_to(&mut kept);
        let mut record = build(kept, revoked);
        record.redaction = Some(self.hashes());
        let record = Arc::new(RecordHandle::create(records, record)?);
        *slot = Some(record.clone());
        Ok(record)
    }

    /// Keeps a record that is on disk already (a run taken back) in step with the holder from
    /// now on, and writes it at once if the holder changed since it was written. Attached
    /// before any revocation of the run can be handled ([`crate::host::Shared`] does so when it
    /// registers the run).
    pub fn keep_record(&self, record: Arc<RecordHandle>) {
        *lock(&self.record) = Some(record);
        self.sync_record();
    }

    /// The salted hashes of every token the run has held, as the take-back record keeps them.
    pub fn hashes(&self) -> SecretHashes {
        lock(&self.hashes).clone()
    }

    /// The record is gone with the sandbox.
    pub fn drop_record(&self) {
        lock(&self.record).take();
    }

    /// Writes the take-back record again if the holder changed since. `true` when the record
    /// on disk is in step with the holder, or the run has none.
    fn sync_record(&self) -> bool {
        // Waits while the record is being made ([`RunGitTokens::make_record`]).
        let record = lock(&self.record).clone();
        match record {
            Some(record) => record.sync_git(self),
            None => true,
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
                        expires_at: held.expires_at,
                        mirror_key: held.mirror_key.clone(),
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

    /// The key of this repository's mirror now ([`crate::mirror::key_for`]): the credential's
    /// (`git.mirrorKey`) when the token has one, else that of `authorization`, the header the
    /// fetch uses.
    pub fn mirror_key(&self, repo_url: &str, authorization: Option<&Secret>) -> String {
        let credential = self.tokens.mirror_key(self.component_id);
        crate::mirror::key_for(repo_url, credential.as_deref(), authorization)
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
        assert_eq!(revoked.now, vec![ids[0]]);
        assert_eq!(revoked.named, vec![ids[0]]);
        assert!(revoked.recorded, "no record to write");
        let err = a.authorization().unwrap_err();
        assert_eq!(err.message, "no more");
        assert_eq!(err.reason, "GRANT_REMOVED");
        assert_eq!(a.revocation(), Some(err));
        assert!(tokens.authorization(ids[1]).unwrap().is_some(), "b goes on");
        // Applied once: a second frame changes nothing, the first reason stays, and it names
        // the same repository, once, for its confirmation.
        let again = tokens.revoke(&[ids[0], Uuid::new_v4(), ids[0]], "SYSTEM_PAUSED", "again");
        assert!(again.now.is_empty(), "{again:?}");
        assert_eq!(again.named, vec![ids[0]]);
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
        let restored = RunGitTokens::restored(&view, &named, None);
        assert_eq!(restored.revocation(ids[0]).unwrap().message, "no more");
        assert!(restored.authorization(ids[1]).unwrap().is_some());
    }

    fn expiring(dir: &str, token: &str, expires_at: DateTime<Utc>) -> serde_json::Value {
        let mut ws = workspace(dir, Some(token));
        ws["git"]["expiresAt"] = serde_json::json!(expires_at.to_rfc3339());
        ws["git"]["mirrorKey"] = serde_json::json!("3f".repeat(32));
        ws
    }

    fn renewed(component_id: Uuid, token: &str, expires_at: DateTime<Utc>) -> GitToken {
        GitToken {
            component_id,
            username: "x-access-token".into(),
            token: Secret::new(token),
            expires_at,
        }
    }

    #[test]
    fn tokens_that_expire_are_due_paced_renewed_and_unusable_once_expired() {
        let now = Utc::now();
        // Two repositories of one credential, as a READ and a WRITE token of it (ADR-0116,
        // decision 8): each its own token, the same mirror key.
        let assign = assign(serde_json::json!([
            expiring(
                "write",
                "ghs_write_0123456789",
                now + chrono::Duration::minutes(5)
            ),
            expiring(
                "read",
                "ghs_read_0123456789",
                now + chrono::Duration::minutes(50)
            ),
            workspace("pat", Some("github_pat_0123456789")),
        ]));
        let ids: Vec<Uuid> = assign.workspaces.iter().map(|w| w.component_id).collect();
        let tokens = RunGitTokens::new(&assign);
        assert!(tokens.expires());
        assert_eq!(tokens.mirror_key(ids[0]), Some("3f".repeat(32)));
        assert_eq!(tokens.mirror_key(ids[1]), Some("3f".repeat(32)));
        assert_eq!(tokens.mirror_key(ids[2]), None);
        // Within 10 minutes: the first only; asked, it is not due again within the minute.
        assert_eq!(tokens.due(Some(Duration::from_secs(600))), vec![ids[0]]);
        assert!(tokens.due(Some(Duration::from_secs(600))).is_empty());
        // Every token that expires (a run taken back): the second; a token without an
        // expiry never.
        assert_eq!(tokens.due(None), vec![ids[1]]);

        let secrets = tokens.renew(&[renewed(
            ids[0],
            "ghs_renewed_0123456789",
            now + chrono::Duration::minutes(60),
        )]);
        assert_eq!(secrets.len(), 2);
        assert_eq!(secrets[0], "ghs_renewed_0123456789");
        let header = basic_authorization("x-access-token", &Secret::new("ghs_renewed_0123456789"));
        assert_eq!(secrets[1], header.expose());
        assert_eq!(
            tokens.authorization(ids[0]).unwrap().unwrap().expose(),
            header.expose()
        );
        assert_eq!(tokens.mirror_key(ids[0]), Some("3f".repeat(32)), "kept");
        let mut view = assign.clone();
        tokens.write_to(&mut view);
        let git = view.workspaces[0].git.as_ref().unwrap();
        assert_eq!(git.token.expose(), "ghs_renewed_0123456789");
        assert_eq!(git.expires_at, Some(now + chrono::Duration::minutes(60)));
        assert_eq!(git.mirror_key, Some("3f".repeat(32)));

        // A token whose expiry passed is not used, and a renewal makes it usable again.
        tokens.renew(&[renewed(
            ids[1],
            "ghs_late_0123456789",
            now - chrono::Duration::seconds(1),
        )]);
        let expired = tokens.authorization(ids[1]).unwrap_err();
        assert_eq!(expired.reason, EXPIRED);
        assert_eq!(expired.message, EXPIRED_MESSAGE);
        assert!(tokens.holds(ids[1]));
        assert!(
            matches!(&*lock(&tokens.log), Log::Waiting(w) if w == &[EXPIRED_MESSAGE.to_string()])
        );
        tokens.authorization(ids[1]).unwrap_err();
        assert!(
            matches!(&*lock(&tokens.log), Log::Waiting(w) if w.len() == 1),
            "logged once"
        );
        tokens.renew(&[renewed(
            ids[1],
            "ghs_again_0123456789",
            now + chrono::Duration::minutes(60),
        )]);
        assert!(tokens.authorization(ids[1]).unwrap().is_some());

        // A revoked repository takes no new token.
        tokens.revoke(&[ids[0]], "SUSPENDED", "gone");
        assert!(
            tokens
                .renew(&[renewed(ids[0], "ghs_after_0123456789", now)])
                .is_empty()
        );
        assert_eq!(tokens.authorization(ids[0]).unwrap_err().message, "gone");
        assert!(tokens.due(None).iter().all(|id| *id != ids[0]));
    }

    #[test]
    fn a_revocation_while_the_record_is_made_waits_and_leaves_no_token_on_disk() {
        let assign = assign(serde_json::json!([workspace(
            "a",
            Some("token-of-a-0123456789")
        )]));
        let component = assign.workspaces[0].component_id;
        let tokens = RunGitTokens::new(&assign);
        let dir = tempfile::tempdir().unwrap();
        let paths = crate::paths::Paths::new(dir.path().join("config"), dir.path().join("data"));
        let records = Records::new(&paths);
        let path = dir.path().join(format!("data/runs/{}.json", assign.run_id));
        std::thread::scope(|scope| {
            let mut revoking = None;
            tokens
                .make_record(&records, &assign, |kept, revoked| {
                    // The snapshot is taken and holds the token; the revocation comes now,
                    // before the record's first write.
                    assert!(kept.workspaces[0].git.is_some());
                    let handle =
                        scope.spawn(|| tokens.revoke(&[component], "SUSPENDED", "suspended"));
                    std::thread::sleep(Duration::from_millis(300));
                    // The token is gone from the holder, but the answer waits for the record.
                    assert!(tokens.revocation(component).is_some());
                    assert!(!handle.is_finished(), "answered before the record was made");
                    revoking = Some(handle);
                    crate::takeback::RunRecord {
                        version: crate::takeback::RECORD_VERSION,
                        assign: kept,
                        sandbox: crate::takeback::SandboxRecord::Local,
                        bridge_id: "run.1".into(),
                        session_key: tinfirm_bridge::control::Hidden::new("k".repeat(64)),
                        repos: Vec::new(),
                        home_exclude: Vec::new(),
                        session_id: None,
                        seq_floor: 1000,
                        revoked,
                        collected: false,
                        redaction: None,
                    }
                })
                .unwrap();
            let answered = revoking.unwrap().join().unwrap();
            // When the host may confirm, the record on disk no longer holds the token.
            assert!(answered.recorded && answered.named == vec![component]);
            let text = std::fs::read_to_string(&path).unwrap();
            assert!(!text.contains("token-of-a"), "{text}");
            assert!(text.contains("\"revoked\""), "{text}");
        });
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
