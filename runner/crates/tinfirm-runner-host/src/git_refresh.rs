//! The renewal of a run's git tokens that expire (ADR-0116, decision 9; the feature
//! `run.git.refresh`).
//!
//! A token that the server minted for the run carries `git.expiresAt`. The host asks the server
//! for a new one with `run.request` kind `GIT_TOKEN`:
//! - while the run works, when a token has [`DUE`] or less left ([`spawn`]);
//! - before the push at the run's end, when it has [`BEFORE_PUSH`] or less left;
//! - for a run taken back after a restart, once the server's `welcome.done` has come, for every
//!   token that expires.
//!
//! One request names every repository that is due, and the holder asks at most once a minute
//! per repository ([`crate::git_tokens::ASK_EVERY`]). The answer:
//! - DONE: each token of `git` goes into the holder (so the git proxy's routes, the host's git
//!   and the take-back record use it from then on) and onto the run's redaction list, where the
//!   old one stays. A repository in `refused` with `GIT_REVOKED` is revoked as by a
//!   `run.git.revoke` with the server's message; any other code (`GIT_HELD`, `GIT_UNAVAILABLE`,
//!   or one this host does not know) keeps the token until it expires, and the host asks again.
//! - REJECTED `GIT_REVOKED`: every repository named is revoked, with the message.
//! - Any other refusal, no answer within 30 s, or no connection: the token is kept, and the
//!   host asks again later.
//!
//! A token whose expiry passes without a new one cannot be used ([`crate::git_tokens::EXPIRED`]),
//! but a renewal that comes later still makes it usable again.

use std::sync::Arc;
use std::time::Duration;

use tinfirm_protocol::{
    GitTokenRequest, RequestPayload, RequestStatus, RunRequest, RunRequestAck, request_code,
};
use uuid::Uuid;

use crate::git_tokens::RunGitTokens;
use crate::host::Shared;
use crate::mcp::RequestOutcome;

/// While the run works, a token with this long or less left is renewed.
pub const DUE: Duration = Duration::from_secs(10 * 60);
/// Before the push at the run's end, a token with this long or less left is renewed.
pub const BEFORE_PUSH: Duration = Duration::from_secs(20 * 60);
/// How often the run's renewal looks at its tokens.
pub const CHECK_EVERY: Duration = Duration::from_secs(15);
/// The reason a refusal for good is applied with, as by the server's own `run.git.revoke`.
pub const REFRESH_REFUSED: &str = "REFRESH_REFUSED";

/// The message of a refusal for good that came without one.
const REFUSED_MESSAGE: &str = "Tinfirm refused to renew the run's git token";

/// Whether this host renews tokens: it lists `run.git.refresh`, so the server sends `expiresAt`.
pub fn applies(shared: &Shared) -> bool {
    shared.speaks_v2()
}

/// Asks the server for the new tokens of the repositories of `tokens` that are due: those whose
/// token expires within `within` (`None`: every token that expires), and applies the answer.
/// Without a connection it asks nothing.
pub async fn renew(shared: &Arc<Shared>, tokens: &Arc<RunGitTokens>, within: Option<Duration>) {
    if !applies(shared) || !shared.is_connected() {
        return;
    }
    let component_ids = tokens.due(within);
    if component_ids.is_empty() {
        return;
    }
    let frame = RunRequest {
        run_id: tokens.run_id(),
        epoch: tokens.epoch(),
        request_id: Uuid::new_v4(),
        request: RequestPayload::GitToken(GitTokenRequest {
            component_ids: component_ids.clone(),
        }),
    };
    tracing::info!(run_id = %tokens.run_id(), repositories = component_ids.len(), "asking the server for new git tokens");
    let outcome = shared.platform_requester().send_request(frame).await;
    match outcome {
        RequestOutcome::Answered(ack) => apply(shared, tokens, &component_ids, &ack),
        RequestOutcome::NotConnected | RequestOutcome::TimedOut | RequestOutcome::RunEnded => {
            tracing::warn!(run_id = %tokens.run_id(), "no answer to a renewal of git tokens; the tokens are kept until they expire");
        }
    }
}

/// Applies the answer to a GIT_TOKEN request that named `asked`.
fn apply(shared: &Arc<Shared>, tokens: &Arc<RunGitTokens>, asked: &[Uuid], ack: &RunRequestAck) {
    let message = ack
        .message
        .clone()
        .filter(|m| !m.trim().is_empty())
        .unwrap_or_else(|| REFUSED_MESSAGE.to_string());
    match ack.status {
        RequestStatus::Done => {
            let Some(answer) = ack.git_tokens() else {
                tracing::warn!(run_id = %tokens.run_id(), "the server's answer to a renewal of git tokens is not one; the tokens are kept");
                return;
            };
            // Only the repositories asked for.
            let renewed: Vec<_> = answer
                .git
                .into_iter()
                .filter(|t| asked.contains(&t.component_id))
                .collect();
            let secrets = tokens.renew(&renewed);
            if !secrets.is_empty() {
                shared.add_run_secrets(tokens.run_id(), tokens.epoch(), secrets);
            }
            for refused in answer.refused {
                if !asked.contains(&refused.component_id) {
                    continue;
                }
                if refused.code == request_code::GIT_REVOKED {
                    tokens.revoke(&[refused.component_id], REFRESH_REFUSED, &refused.message);
                } else {
                    tracing::info!(run_id = %tokens.run_id(), component_id = %refused.component_id, code = %refused.code, "the server did not renew a git token for now; it is kept until it expires");
                }
            }
        }
        RequestStatus::Rejected if ack.code.as_deref() == Some(request_code::GIT_REVOKED) => {
            tokens.revoke(asked, REFRESH_REFUSED, &message);
        }
        _ => {
            tracing::info!(run_id = %tokens.run_id(), code = ?ack.code, "the server did not renew the git tokens for now; they are kept until they expire");
        }
    }
}

/// Renews the run's tokens while the run works: at once, then every [`CHECK_EVERY`], those with
/// [`DUE`] or less left. Stops when the returned guard is dropped. `None` when no token of the
/// run expires.
pub fn spawn(shared: &Arc<Shared>, tokens: &Arc<RunGitTokens>) -> Option<Renewal> {
    if !applies(shared) || !tokens.expires() {
        return None;
    }
    let shared = Arc::downgrade(shared);
    let tokens = tokens.clone();
    let task = tokio::spawn(async move {
        loop {
            let Some(host) = shared.upgrade() else {
                return;
            };
            renew(&host, &tokens, Some(DUE)).await;
            drop(host);
            tokio::time::sleep(CHECK_EVERY).await;
        }
    });
    Some(Renewal(task))
}

/// The renewal of a run's tokens while it works; dropping it stops it.
pub struct Renewal(tokio::task::JoinHandle<()>);

impl Drop for Renewal {
    fn drop(&mut self) {
        self.0.abort();
    }
}
