//! The host's report that it can take no run: the frame `host.health` of the feature
//! `host.health` (ADR-0117).
//!
//! Today the one state is the VM driver's: while it can make no VM ([`Vms::refusal`]), the host
//! rejects each new run, and this module tells the server, which then assigns the host no run
//! and shows why on its page. The server keeps what it was told with the connection, and a
//! connection starts healthy on both sides. So the report goes once per connection with a
//! problem, at each change, and once when the host is healthy again; nothing while the host is
//! healthy and was healthy on that connection.
//!
//! The report goes only to a server whose `welcome.features` lists `host.health`, after its
//! answer to the hello is complete ([`HelloAnswer::complete`](crate::host::HelloAnswer::complete)): an older server would answer
//! the frame with an error. The host keeps rejecting a run that comes while it has a problem.
//!
//! [`Vms::refusal`]: crate::vm::Vms::refusal

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use chrono::{DateTime, Utc};
use tinfirm_protocol::{
    HOST_PROBLEM_MESSAGE_MAX, HostHealth, HostProblem, HostProblemCode, Message, feature,
};
use tokio::sync::watch;
use tokio_util::sync::CancellationToken;

use crate::host::Host;
use crate::sandbox::Executor;

/// The least time between two frames: a state that changes fast costs one frame, the latest.
/// The server takes 12 a minute from a connection; this host sends at most that many.
pub const MIN_INTERVAL: Duration = Duration::from_secs(5);

/// Whether the current connection's server takes `host.health`, set at each `welcome`.
#[derive(Debug, Default)]
pub struct ServerTakesHealth(AtomicBool);

impl ServerTakesHealth {
    pub fn on_welcome(&self, welcome: &tinfirm_protocol::Welcome) {
        self.0
            .store(welcome.lists(feature::HOST_HEALTH), Ordering::SeqCst);
    }

    pub fn get(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}

/// Whether this host lists `host.health` in its hello: a host that can detect a state of its
/// own, today one with a VM per run.
pub fn lists_feature(executor: &Executor) -> bool {
    matches!(executor, Executor::Vm(_))
}

/// The problems for the VM driver's state: `None` takes runs.
pub fn problems(no_vms: Option<&str>) -> Vec<HostProblem> {
    no_vms
        .map(|cause| HostProblem {
            code: HostProblemCode::VmDriverNoVm,
            message: match crate::util::one_line(cause.trim(), HOST_PROBLEM_MESSAGE_MAX) {
                // The schema wants a message; a driver may name an empty problem.
                text if text.trim().is_empty() => "the VM driver reports a problem".to_string(),
                text => text,
            },
        })
        .into_iter()
        .collect()
}

/// What the server holds of this host's health: which connection, and the problems it was
/// last sent there (empty: healthy, where every connection starts).
#[derive(Debug, Default, PartialEq)]
struct Held {
    connection: u64,
    problems: Vec<HostProblem>,
}

/// Reports the host's health to its server until `stop`; returns at once on a host that
/// detects no state of its own.
pub async fn run(host: Host, stop: CancellationToken) {
    let Executor::Vm(vms) = &host.opts.executor else {
        return;
    };
    let state = vms.no_vms_watch();
    report(host.clone(), state, stop).await;
}

/// The loop of [`run`]: `state` is the VM driver's cause while it can make no VM.
async fn report(host: Host, mut state: watch::Receiver<Option<String>>, stop: CancellationToken) {
    let mut answer = host.hello_answer();
    let mut held = Held::default();
    let mut since: Option<DateTime<Utc>> = None;
    let mut last_sent: Option<tokio::time::Instant> = None;
    loop {
        let wanted = problems(state.borrow_and_update().as_deref());
        // Kept across changes of the cause: the host has taken no run since then.
        since = match (since, wanted.is_empty()) {
            (_, true) => None,
            (None, false) => Some(Utc::now()),
            (kept, false) => kept,
        };
        let complete = answer.borrow_and_update().complete();
        // Counted at each welcome: a new value is a new connection, which starts healthy,
        // even when the old one ended between two looks.
        let connection = host.connections.load(Ordering::SeqCst);
        if held.connection != connection {
            held = Held {
                connection,
                problems: Vec::new(),
            };
        }
        if complete && host.server_takes_health.get() && wanted != held.problems {
            if let Some(at) = last_sent
                && at.elapsed() < MIN_INTERVAL
            {
                tokio::select! {
                    _ = stop.cancelled() => return,
                    _ = tokio::time::sleep_until(at + MIN_INTERVAL) => continue,
                }
            }
            let healthy = wanted.is_empty();
            host.outbox.push_control(Message::HostHealth(HostHealth {
                problems: wanted.clone(),
                since,
            }));
            last_sent = Some(tokio::time::Instant::now());
            if healthy {
                tracing::info!("told the server that this host takes runs again (host.health)");
            } else {
                tracing::info!("told the server that this host takes no run (host.health)");
            }
            held.problems = wanted;
        }
        tokio::select! {
            _ = stop.cancelled() => return,
            changed = state.changed() => if changed.is_err() { return },
            changed = answer.changed() => if changed.is_err() { return },
        }
    }
}

/// Drops the frames of an ended connection that were not written: the new connection starts
/// healthy, and [`report`] sends what holds now.
pub fn of_ended_connection(message: &Message) -> bool {
    matches!(message, Message::HostHealth(_))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_driver_state_is_one_problem_of_one_line_within_the_bound() {
        assert!(problems(None).is_empty());
        let one = problems(Some("node node-1:\nthe table is missing"));
        assert_eq!(one.len(), 1);
        assert_eq!(one[0].code, HostProblemCode::VmDriverNoVm);
        assert_eq!(one[0].message, "node node-1: the table is missing");
        let long = problems(Some(&"é".repeat(2000)));
        assert!(long[0].message.chars().count() <= HOST_PROBLEM_MESSAGE_MAX);
        assert!(long[0].message.ends_with('…'));
        // The schema wants a message.
        assert_eq!(
            problems(Some("  ")).first().map(|p| p.message.as_str()),
            Some("the VM driver reports a problem")
        );
    }
}
