//! Steering (the protocol v2 feature `run.steer`): what a person writes while a run works,
//! which the agent takes into the turn it runs instead of the next turn.
//!
//! The server sends `run.steer`; [`Steering`] keeps each one for its execution until the
//! agent's session takes it:
//! - before the prompt is sent, [`Steering::start_prompt`] hands over what arrived, which the
//!   session appends to the prompt: delivered with it;
//! - while the prompt runs, the session takes each new one ([`Steering::take`]) and gives it
//!   to the agent's steering (ACP `_session/steering`), then answers it ([`Steering::answer`]);
//! - once the prompt is over or ending, [`Steering::close`] refuses what is left, and every
//!   later steer.
//!
//! Every `steerId` gets exactly one `run.steer.ack`, pushed as a control message of the
//! outbox, which goes out before any later item of the run, `run.finished` included. A
//! repeated `steerId` gets the same ack again, or none while its first one is on its way.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};

use tinfirm_protocol::{
    Message, RunSteer, RunSteerAck, STEER_ACK_MESSAGE_MAX_CHARS, SteerStatus, steer_code,
};
use tokio::sync::Notify;
use uuid::Uuid;

use crate::outbox::Outbox;

/// Where the execution's prompt is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    /// Not sent yet: steers wait to be appended to it.
    BeforePrompt,
    /// Running: steers go to the agent's steering.
    Prompting,
    /// Over or ending: steers are refused.
    Closed,
}

#[derive(Debug)]
struct State {
    phase: Phase,
    /// Arrived, not yet taken.
    pending: VecDeque<RunSteer>,
    /// Taken by the session, not yet answered.
    taken: HashSet<Uuid>,
    /// Answered, for a repeated `steerId`.
    answered: HashMap<Uuid, RunSteerAck>,
}

/// The steers of one execution of a run (`run_id`, `epoch`).
#[derive(Debug)]
pub struct Steering {
    run_id: Uuid,
    epoch: i64,
    outbox: Arc<Outbox>,
    state: Mutex<State>,
    notify: Notify,
}

impl Steering {
    pub fn new(run_id: Uuid, epoch: i64, outbox: Arc<Outbox>) -> Arc<Steering> {
        Arc::new(Steering {
            run_id,
            epoch,
            outbox,
            state: Mutex::new(State {
                phase: Phase::BeforePrompt,
                pending: VecDeque::new(),
                taken: HashSet::new(),
                answered: HashMap::new(),
            }),
            notify: Notify::new(),
        })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// A `run.steer` of this execution.
    pub fn offer(&self, steer: RunSteer) {
        let mut state = self.lock();
        if let Some(ack) = state.answered.get(&steer.steer_id) {
            self.outbox.push_control(Message::RunSteerAck(ack.clone()));
            return;
        }
        if state.taken.contains(&steer.steer_id)
            || state.pending.iter().any(|s| s.steer_id == steer.steer_id)
        {
            return;
        }
        if state.phase == Phase::Closed {
            let ack = RunSteerAck::refused(
                &steer,
                steer_code::NOT_RUNNING,
                "the agent's turn is over or ending",
            );
            self.record(&mut state, ack);
            return;
        }
        state.pending.push_back(steer);
        drop(state);
        self.notify.notify_one();
    }

    /// The prompt is about to be sent: the steers that arrived until now, in order, for the
    /// session to append to it and then to answer as delivered. Later ones go to [`take`].
    ///
    /// [`take`]: Steering::take
    pub fn start_prompt(&self) -> Vec<RunSteer> {
        let mut state = self.lock();
        if state.phase != Phase::BeforePrompt {
            return Vec::new();
        }
        state.phase = Phase::Prompting;
        let steers: Vec<RunSteer> = state.pending.drain(..).collect();
        for steer in &steers {
            state.taken.insert(steer.steer_id);
        }
        steers
    }

    /// The next steer that arrived while the prompt runs, now taken by the session, which
    /// answers it.
    pub fn take(&self) -> Option<RunSteer> {
        let mut state = self.lock();
        if state.phase != Phase::Prompting {
            return None;
        }
        let steer = state.pending.pop_front()?;
        state.taken.insert(steer.steer_id);
        Some(steer)
    }

    /// Wakes when a steer arrives (or arrived since the last wake).
    pub async fn arrived(&self) {
        self.notify.notified().await
    }

    /// The answer to a steer the session took; the first answer counts.
    pub fn answer(&self, ack: RunSteerAck) {
        let mut state = self.lock();
        if !state.taken.remove(&ack.steer_id) {
            return;
        }
        self.record(&mut state, ack);
    }

    /// The prompt is over or ending, or never comes: every steer not answered yet, and every
    /// later one, is refused with `NOT_RUNNING`.
    pub fn close(&self, why: &str) {
        let mut state = self.lock();
        state.phase = Phase::Closed;
        let pending: Vec<RunSteer> = state.pending.drain(..).collect();
        let taken: Vec<Uuid> = state.taken.drain().collect();
        for steer in pending {
            let ack = RunSteerAck::refused(&steer, steer_code::NOT_RUNNING, why);
            self.record(&mut state, ack);
        }
        for steer_id in taken {
            let ack = RunSteerAck {
                run_id: self.run_id,
                epoch: self.epoch,
                steer_id,
                status: SteerStatus::Refused,
                code: Some(steer_code::NOT_RUNNING.to_string()),
                message: Some(why.chars().take(STEER_ACK_MESSAGE_MAX_CHARS).collect()),
            };
            self.record(&mut state, ack);
        }
    }

    fn record(&self, state: &mut State, ack: RunSteerAck) {
        state.answered.insert(ack.steer_id, ack.clone());
        self.outbox.push_control(Message::RunSteerAck(ack));
    }
}

/// The answer to a `run.steer` of an execution this host does not run (or no longer runs).
pub fn refuse_unknown(outbox: &Outbox, steer: &RunSteer) {
    outbox.push_control(Message::RunSteerAck(RunSteerAck::refused(
        steer,
        steer_code::NOT_RUNNING,
        "this runner host does not run this execution of the run",
    )));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn steer(run_id: Uuid, text: &str) -> RunSteer {
        RunSteer {
            run_id,
            epoch: 3,
            steer_id: Uuid::new_v4(),
            text: text.into(),
        }
    }

    fn acks(outbox: &Outbox) -> Vec<RunSteerAck> {
        let mut out = Vec::new();
        while let Some(message) = outbox.take_next() {
            if let Message::RunSteerAck(ack) = message {
                out.push(ack);
            }
        }
        out
    }

    #[test]
    fn steers_before_the_prompt_go_with_it_and_later_ones_one_by_one() {
        let outbox = Arc::new(Outbox::new());
        let run_id = Uuid::new_v4();
        let steering = Steering::new(run_id, 3, outbox.clone());
        let (a, b, c) = (steer(run_id, "a"), steer(run_id, "b"), steer(run_id, "c"));
        steering.offer(a.clone());
        steering.offer(b.clone());
        assert!(
            steering.take().is_none(),
            "nothing is taken before the prompt"
        );
        let early = steering.start_prompt();
        assert_eq!(early, vec![a.clone(), b.clone()]);
        assert!(steering.start_prompt().is_empty(), "once");
        steering.offer(c.clone());
        steering.offer(c.clone());
        assert_eq!(
            steering.take(),
            Some(c.clone()),
            "a repeated steer is kept once"
        );
        assert_eq!(steering.take(), None);
        for s in [&a, &b, &c] {
            steering.answer(RunSteerAck::delivered(s));
        }
        steering.answer(RunSteerAck::refused(&c, steer_code::FAILED, "late"));
        let sent = acks(&outbox);
        assert_eq!(sent.len(), 3, "{sent:?}");
        assert!(sent.iter().all(|ack| ack.status == SteerStatus::Delivered));
        assert!(
            sent.iter()
                .all(|ack| ack.run_id == run_id && ack.epoch == 3)
        );
        // A repeated steer that was answered gets the same answer again.
        steering.offer(a.clone());
        assert_eq!(acks(&outbox), vec![RunSteerAck::delivered(&a)]);
    }

    #[test]
    fn closing_refuses_what_is_left_and_what_comes() {
        let outbox = Arc::new(Outbox::new());
        let run_id = Uuid::new_v4();
        let steering = Steering::new(run_id, 3, outbox.clone());
        steering.start_prompt();
        let (taken, waiting, late) = (steer(run_id, "t"), steer(run_id, "w"), steer(run_id, "l"));
        steering.offer(taken.clone());
        assert_eq!(steering.take(), Some(taken.clone()));
        steering.offer(waiting.clone());
        steering.close("the run ended");
        assert_eq!(steering.take(), None);
        steering.offer(late.clone());
        // The session's answer after the close does not count: the steer was refused.
        steering.answer(RunSteerAck::delivered(&taken));
        let sent = acks(&outbox);
        let ids: HashSet<Uuid> = sent.iter().map(|ack| ack.steer_id).collect();
        assert_eq!(
            ids,
            [taken.steer_id, waiting.steer_id, late.steer_id]
                .into_iter()
                .collect()
        );
        assert_eq!(sent.len(), 3);
        for ack in &sent {
            assert_eq!(ack.status, SteerStatus::Refused);
            assert_eq!(ack.code.as_deref(), Some(steer_code::NOT_RUNNING));
            assert_eq!((ack.run_id, ack.epoch), (run_id, 3));
        }
    }

    #[test]
    fn a_run_that_never_prompts_refuses_its_steers() {
        let outbox = Arc::new(Outbox::new());
        let run_id = Uuid::new_v4();
        let steering = Steering::new(run_id, 3, outbox.clone());
        let early = steer(run_id, "e");
        steering.offer(early.clone());
        steering.close("the run ended before its agent started");
        assert!(steering.start_prompt().is_empty());
        let sent = acks(&outbox);
        assert_eq!(sent.len(), 1);
        assert_eq!(sent[0].steer_id, early.steer_id);
        assert_eq!(sent[0].status, SteerStatus::Refused);
        let other = steer(Uuid::new_v4(), "x");
        refuse_unknown(&outbox, &other);
        let sent = acks(&outbox);
        assert_eq!(sent[0].code.as_deref(), Some(steer_code::NOT_RUNNING));
        assert_eq!(sent[0].run_id, other.run_id);
    }
}
