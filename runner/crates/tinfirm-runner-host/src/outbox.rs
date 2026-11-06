//! Outbound message buffer that survives reconnects (docs/archive/v1/CONVENTIONS.md §15.4 step 11).
//!
//! Per run, it keeps `run.accepted`, the run's events and `run.finished` in order. While the
//! connection is down, events accumulate (at most 10 000 per run; LOG events are dropped
//! first). After a reconnect the unsent items are flushed, together with a window of the
//! most recently sent ones, because frames written just before a connection died may never
//! have reached the server. The server drops duplicates by `(runId, seq)`.
//!
//! The protocol has no ack of `run.finished`, so a finished run stays here, listed in
//! `hello.activeRuns` and its end written again after every reconnect, until its finish was
//! written on a connection that then stayed up for the retention ([`DEFAULT_FINISHED_RETENTION`],
//! `TINFIRM_RUNNER_FINISHED_RETENTION`). Time without a connection does not count: a finish
//! that a dying connection, or a server that went down, may have lost is still here when the
//! host reconnects, however long that takes, and the server ends the run with it.
//!
//! The connection takes one message at a time ([`Outbox::take_next`]) and writes it before
//! it takes the next, so what it has not written stays here, and a heartbeat can go out
//! between two messages (`run.heartbeat` is not part of any run's order).

use std::collections::VecDeque;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tinfirm_protocol::{ActiveRun, Message, RunAccepted, RunEvent, RunFinished};
use tokio::sync::Notify;
use uuid::Uuid;

pub const MAX_BUFFERED_EVENTS_PER_RUN: usize = 10_000;
/// Already-sent items per run that are sent again after a reconnect.
pub const RESEND_WINDOW: usize = 500;
/// How long a finished run stays in `hello.activeRuns` by default once its `run.finished` was
/// written, counting only time on a connection: longer than the server's reconnect grace
/// (2 minutes) and the host's silence limit (45 s), so that a finish lost in a dying
/// connection, or with a server that restarted, is delivered again.
pub const DEFAULT_FINISHED_RETENTION: Duration = Duration::from_secs(180);

#[derive(Debug, Clone)]
enum Item {
    Accepted { msg: RunAccepted, sent_once: bool },
    Event(RunEvent),
    Finished(RunFinished),
}

#[derive(Debug)]
struct RunQueue {
    run_id: Uuid,
    epoch: i64,
    items: VecDeque<Item>,
    next_unsent: usize,
    last_seq: u64,
    dropped: u64,
    event_items: usize,
    finished_pushed: bool,
    finished_sent_at: Option<Instant>,
}

#[derive(Debug, Default)]
struct Inner {
    control: VecDeque<Message>,
    runs: Vec<RunQueue>,
    /// The run whose turn it is in [`Outbox::take_next`] (round robin).
    turn: usize,
    /// When the connection ended; `None` while connected (or before the first connection).
    /// Time from then on does not count towards a finished run's retention.
    disconnected_at: Option<Instant>,
}

#[derive(Debug)]
pub struct Outbox {
    inner: Mutex<Inner>,
    notify: Notify,
    /// How long a finished run is kept once its finish was written, on a connection.
    retention: Duration,
}

impl Default for Outbox {
    fn default() -> Outbox {
        Outbox::with_retention(DEFAULT_FINISHED_RETENTION)
    }
}

impl Outbox {
    pub fn new() -> Outbox {
        Outbox::default()
    }

    /// An outbox that keeps a finished run for `retention` of connected time after its finish
    /// was written.
    pub fn with_retention(retention: Duration) -> Outbox {
        Outbox {
            inner: Mutex::default(),
            notify: Notify::new(),
            retention,
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Wakes the connection writer.
    pub async fn notified(&self) {
        self.notify.notified().await
    }

    /// A message that is not tied to a run's ordering (rejections, credential status, errors).
    pub fn push_control(&self, message: Message) {
        self.lock().control.push_back(message);
        self.notify.notify_one();
    }

    /// Drops the control messages that are not yet written and fail `keep` (answers that
    /// belong to a connection that has ended).
    pub fn retain_control(&self, keep: impl Fn(&Message) -> bool) {
        self.lock().control.retain(|m| keep(m));
    }

    /// Starts the queue of a run with its `run.accepted`.
    pub fn open_run(&self, accepted: RunAccepted) {
        let mut inner = self.lock();
        inner.runs.retain(|q| q.run_id != accepted.run_id);
        inner.runs.push(RunQueue {
            run_id: accepted.run_id,
            epoch: accepted.epoch,
            items: VecDeque::from([Item::Accepted {
                msg: accepted,
                sent_once: false,
            }]),
            next_unsent: 0,
            last_seq: 0,
            dropped: 0,
            event_items: 0,
            finished_pushed: false,
            finished_sent_at: None,
        });
        drop(inner);
        self.notify.notify_one();
    }

    /// Starts the queue of a run that this host takes back after a restart (ADR-0101): the
    /// server has its `run.accepted` and its events up to `last_seq` from the earlier process,
    /// and `hello.activeRuns` lists it.
    pub fn resume_run(&self, run_id: Uuid, epoch: i64, last_seq: u64) {
        let mut inner = self.lock();
        inner.runs.retain(|q| q.run_id != run_id);
        inner.runs.push(RunQueue {
            run_id,
            epoch,
            items: VecDeque::new(),
            next_unsent: 0,
            last_seq,
            dropped: 0,
            event_items: 0,
            finished_pushed: false,
            finished_sent_at: None,
        });
        drop(inner);
        self.notify.notify_one();
    }

    /// Sends `run.accepted` again (duplicate `run.assign` for the same epoch), if that
    /// execution was accepted: one still being admitted answers once it is decided.
    pub fn reaccept(&self, run_id: Uuid, epoch: i64) {
        let mut inner = self.lock();
        if inner
            .runs
            .iter()
            .any(|q| q.run_id == run_id && q.epoch == epoch)
        {
            inner
                .control
                .push_back(Message::RunAccepted(RunAccepted { run_id, epoch }));
        }
        drop(inner);
        self.notify.notify_one();
    }

    pub fn push_event(&self, event: RunEvent) {
        let mut inner = self.lock();
        let Some(q) = inner
            .runs
            .iter_mut()
            .find(|q| q.run_id == event.run_id && q.epoch == event.epoch)
        else {
            tracing::debug!(run_id = %event.run_id, epoch = event.epoch, "event of a stale or unknown execution dropped");
            return;
        };
        q.last_seq = q.last_seq.max(event.seq);
        q.items.push_back(Item::Event(event));
        q.event_items += 1;
        q.enforce_cap();
        drop(inner);
        self.notify.notify_one();
    }

    pub fn push_finished(&self, finished: RunFinished) {
        let mut inner = self.lock();
        if let Some(q) = inner
            .runs
            .iter_mut()
            .find(|q| q.run_id == finished.run_id && q.epoch == finished.epoch)
        {
            q.finished_pushed = true;
            q.items.push_back(Item::Finished(finished));
        }
        drop(inner);
        self.notify.notify_one();
    }

    /// Forgets one execution (stale epoch: the server wants nothing more from it).
    pub fn drop_run(&self, run_id: Uuid, epoch: i64) {
        self.lock()
            .runs
            .retain(|q| !(q.run_id == run_id && q.epoch == epoch));
    }

    /// `true` while the outbox still holds a run (executing or recently finished).
    pub fn knows_run(&self, run_id: Uuid) -> bool {
        self.lock().runs.iter().any(|q| q.run_id == run_id)
    }

    /// The next message to write, marked as written: control messages first, then any run's
    /// `run.accepted` (the server takes an assignment back after 10 s without one), then the
    /// runs' items in turn, one run after the other. Each run's own items keep their order.
    pub fn take_next(&self) -> Option<Message> {
        let mut inner = self.lock();
        if let Some(message) = inner.control.pop_front() {
            return Some(message);
        }
        let now = Instant::now();
        if let Some(q) = inner.runs.iter_mut().find(|q| q.accepted_unsent()) {
            return q.take_next(now);
        }
        let count = inner.runs.len();
        for i in 0..count {
            let index = (inner.turn + i) % count;
            if let Some(message) = inner.runs[index].take_next(now) {
                inner.turn = (index + 1) % count;
                return Some(message);
            }
        }
        None
    }

    /// The connection ended: until the next `welcome` no time counts towards the retention of
    /// finished runs, whose end may have been lost with the connection.
    pub fn on_disconnect(&self) {
        self.on_disconnect_at(Instant::now());
    }

    fn on_disconnect_at(&self, now: Instant) {
        self.lock().disconnected_at.get_or_insert(now);
    }

    /// After a new `welcome`: rewind each run so that the last [`RESEND_WINDOW`] sent items
    /// go out again. Nothing is forgotten here: a finished run's end is the last item of its
    /// queue, so it is always in the window and written again, and its retention counts from
    /// that write on this connection.
    pub fn on_reconnect(&self) {
        let mut inner = self.lock();
        inner.disconnected_at = None;
        for q in &mut inner.runs {
            q.next_unsent = q.next_unsent.saturating_sub(RESEND_WINDOW);
            q.finished_sent_at = None;
        }
    }

    /// `hello.activeRuns`: the runs that execute, and the finished ones whose end may not have
    /// reached the server (see [`Outbox::holds_runs`]).
    pub fn active_runs(&self) -> Vec<ActiveRun> {
        self.active_runs_at(Instant::now())
    }

    fn active_runs_at(&self, now: Instant) -> Vec<ActiveRun> {
        let mut inner = self.lock();
        inner.gc(now, self.retention);
        inner
            .runs
            .iter()
            .map(|q| ActiveRun {
                run_id: q.run_id,
                epoch: q.epoch,
                last_seq: q.last_seq,
            })
            .collect()
    }

    /// `true` while a run is executing, or finished and its end not yet written on a connection
    /// that then stayed up for the retention: that end is written again after a reconnect.
    pub fn holds_runs(&self) -> bool {
        self.holds_runs_at(Instant::now())
    }

    fn holds_runs_at(&self, now: Instant) -> bool {
        let mut inner = self.lock();
        inner.gc(now, self.retention);
        !inner.runs.is_empty()
    }

    /// `true` when nothing is waiting to be written.
    pub fn is_drained(&self) -> bool {
        let inner = self.lock();
        inner.control.is_empty() && inner.runs.iter().all(|q| q.next_unsent >= q.items.len())
    }

    /// `true` when every run's finish has been written.
    pub fn all_finished_sent(&self) -> bool {
        let inner = self.lock();
        inner
            .runs
            .iter()
            .all(|q| q.finished_pushed && q.next_unsent >= q.items.len())
    }

    /// Number of events dropped because a run's buffer was full.
    pub fn dropped(&self, run_id: Uuid) -> u64 {
        self.lock()
            .runs
            .iter()
            .find(|q| q.run_id == run_id)
            .map(|q| q.dropped)
            .unwrap_or(0)
    }

    #[cfg(test)]
    fn buffered(&self, run_id: Uuid) -> usize {
        self.lock()
            .runs
            .iter()
            .find(|q| q.run_id == run_id)
            .map(|q| {
                q.items
                    .iter()
                    .filter(|i| matches!(i, Item::Event(_)))
                    .count()
            })
            .unwrap_or(0)
    }
}

impl Inner {
    /// Forgets the finished runs whose end was written on a connection that then stayed up for
    /// `retention`. While disconnected, the connection's time ended with it.
    fn gc(&mut self, now: Instant, retention: Duration) {
        let connected_until = self.disconnected_at.unwrap_or(now);
        self.runs.retain(|q| {
            !(q.finished_pushed
                && q.next_unsent >= q.items.len()
                && q.finished_sent_at
                    .is_some_and(|t| connected_until.saturating_duration_since(t) > retention))
        });
    }
}

impl RunQueue {
    /// `true` when `run.accepted` is the next item and has never been written.
    fn accepted_unsent(&self) -> bool {
        self.next_unsent == 0
            && matches!(
                self.items.front(),
                Some(Item::Accepted {
                    sent_once: false,
                    ..
                })
            )
    }

    /// The next item that is not written yet; `run.accepted` is written only once, also
    /// after a reconnect.
    fn take_next(&mut self, now: Instant) -> Option<Message> {
        while let Some(item) = self.items.get_mut(self.next_unsent) {
            self.next_unsent += 1;
            match item {
                Item::Accepted { msg, sent_once } => {
                    if !*sent_once {
                        *sent_once = true;
                        return Some(Message::RunAccepted(msg.clone()));
                    }
                }
                Item::Event(e) => return Some(Message::RunEvent(e.clone())),
                Item::Finished(f) => {
                    self.finished_sent_at.get_or_insert(now);
                    return Some(Message::RunFinished(f.clone()));
                }
            }
        }
        None
    }

    fn enforce_cap(&mut self) {
        while self.event_items > MAX_BUFFERED_EVENTS_PER_RUN {
            // 1. the oldest item that was already written;
            let victim = (0..self.next_unsent)
                .find(|&i| matches!(self.items[i], Item::Event(_)))
                // 2. the oldest unsent LOG event;
                .or_else(|| {
                    (self.next_unsent..self.items.len())
                        .find(|&i| matches!(&self.items[i], Item::Event(e) if e.event.is_log()))
                })
                // 3. the oldest unsent event.
                .or_else(|| {
                    (self.next_unsent..self.items.len())
                        .find(|&i| matches!(self.items[i], Item::Event(_)))
                });
            let Some(i) = victim else { break };
            self.items.remove(i);
            self.event_items -= 1;
            if i < self.next_unsent {
                self.next_unsent -= 1;
            } else {
                self.dropped += 1;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Utc;
    use tinfirm_protocol::{EventPayload, LogLevel, LogPayload, Outcome, TextPayload};

    fn event(run_id: Uuid, seq: u64, log: bool) -> RunEvent {
        RunEvent {
            run_id,
            epoch: 1,
            seq,
            at: Utc::now(),
            event: if log {
                EventPayload::Log(LogPayload {
                    level: LogLevel::Debug,
                    message: "l".into(),
                })
            } else {
                EventPayload::AgentMessage(TextPayload { text: "m".into() })
            },
        }
    }

    fn finished(run_id: Uuid, last_seq: u64) -> RunFinished {
        RunFinished {
            run_id,
            epoch: 1,
            outcome: Outcome::Succeeded,
            last_seq,
            failure_class: None,
            failure_message: None,
            summary: None,
            result: None,
            session_id: None,
            git: None,
            usage: None,
            artifacts: vec![],
            resumed: None,
            resume_outcome: None,
            session_bundle: None,
        }
    }

    /// Everything the connection would write now, in order.
    fn take_all(outbox: &Outbox) -> Vec<Message> {
        std::iter::from_fn(|| outbox.take_next()).collect()
    }

    /// `(runId, seq)` of the events among `messages`.
    fn event_ids(messages: &[Message]) -> Vec<(Uuid, u64)> {
        messages
            .iter()
            .filter_map(|m| match m {
                Message::RunEvent(e) => Some((e.run_id, e.seq)),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn what_is_not_taken_stays_unsent() {
        let outbox = Outbox::new();
        let run = Uuid::new_v4();
        outbox.open_run(RunAccepted {
            run_id: run,
            epoch: 1,
        });
        for seq in 1..=3 {
            outbox.push_event(event(run, seq, false));
        }
        assert!(matches!(outbox.take_next(), Some(Message::RunAccepted(_))));
        assert!(matches!(outbox.take_next(), Some(Message::RunEvent(e)) if e.seq == 1));
        // The connection has written two messages; the others still wait here.
        assert!(!outbox.is_drained());
        assert!(!outbox.all_finished_sent());
        outbox.push_finished(finished(run, 3));
        let rest = take_all(&outbox);
        assert_eq!(event_ids(&rest), vec![(run, 2), (run, 3)]);
        assert!(matches!(rest.last(), Some(Message::RunFinished(_))));
        assert!(outbox.is_drained());
        assert!(outbox.all_finished_sent());
        assert!(outbox.take_next().is_none());
    }

    #[test]
    fn control_and_accepted_go_first_and_runs_take_turns() {
        let outbox = Outbox::new();
        let (a, b) = (Uuid::new_v4(), Uuid::new_v4());
        outbox.open_run(RunAccepted {
            run_id: a,
            epoch: 1,
        });
        for seq in 1..=3 {
            outbox.push_event(event(a, seq, false));
        }
        assert!(matches!(outbox.take_next(), Some(Message::RunAccepted(r)) if r.run_id == a));
        assert!(matches!(outbox.take_next(), Some(Message::RunEvent(e)) if e.seq == 1));
        // Run b is assigned while run a has a backlog: its run.accepted overtakes that
        // backlog, and control messages overtake everything.
        outbox.open_run(RunAccepted {
            run_id: b,
            epoch: 1,
        });
        for seq in 1..=3 {
            outbox.push_event(event(b, seq, false));
        }
        outbox.push_control(Message::Error(tinfirm_protocol::ErrorMessage {
            code: "X".into(),
            message: "x".into(),
            run_id: None,
            epoch: None,
        }));
        assert!(matches!(outbox.take_next(), Some(Message::Error(_))));
        assert!(matches!(outbox.take_next(), Some(Message::RunAccepted(r)) if r.run_id == b));
        let rest = take_all(&outbox);
        let ids = event_ids(&rest);
        // The runs take turns; each run's events stay in order.
        assert_eq!(ids.len(), 5);
        assert_ne!(ids[0].0, ids[1].0, "{ids:?}");
        for run in [a, b] {
            let seqs: Vec<u64> = ids
                .iter()
                .filter(|(r, _)| *r == run)
                .map(|(_, s)| *s)
                .collect();
            let expected: Vec<u64> = if run == a { vec![2, 3] } else { vec![1, 2, 3] };
            assert_eq!(seqs, expected);
        }
        assert!(outbox.is_drained());
    }

    #[test]
    fn keeps_order_and_resends_window_after_reconnect() {
        let outbox = Outbox::new();
        let run = Uuid::new_v4();
        outbox.open_run(RunAccepted {
            run_id: run,
            epoch: 1,
        });
        for seq in 1..=3 {
            outbox.push_event(event(run, seq, false));
        }
        let sent = take_all(&outbox);
        assert_eq!(sent.len(), 4);
        assert!(matches!(sent[0], Message::RunAccepted(_)));
        assert!(outbox.is_drained());

        outbox.push_event(event(run, 4, false));
        outbox.on_reconnect();
        let active = outbox.active_runs();
        assert_eq!(active.len(), 1);
        assert_eq!(active[0].last_seq, 4);
        let resent = take_all(&outbox);
        // Accepted is not repeated; events 1..=4 are.
        let seqs: Vec<u64> = resent
            .iter()
            .filter_map(|m| match m {
                Message::RunEvent(e) => Some(e.seq),
                _ => None,
            })
            .collect();
        assert_eq!(seqs, vec![1, 2, 3, 4]);
        assert!(!resent.iter().any(|m| matches!(m, Message::RunAccepted(_))));

        outbox.push_finished(finished(run, 4));
        let last = take_all(&outbox);
        assert!(matches!(last.last(), Some(Message::RunFinished(_))));
        assert!(outbox.all_finished_sent());
    }

    #[test]
    fn a_run_taken_back_is_listed_and_sends_no_second_accepted() {
        let outbox = Outbox::new();
        let run = Uuid::new_v4();
        outbox.resume_run(run, 3, 1000);
        let active = outbox.active_runs();
        assert_eq!(
            (active[0].run_id, active[0].epoch, active[0].last_seq),
            (run, 3, 1000)
        );
        outbox.push_event(RunEvent {
            epoch: 3,
            ..event(run, 1001, false)
        });
        outbox.push_finished(RunFinished {
            epoch: 3,
            ..finished(run, 1001)
        });
        let sent = take_all(&outbox);
        assert!(matches!(&sent[0], Message::RunEvent(e) if e.seq == 1001));
        assert!(matches!(sent.last(), Some(Message::RunFinished(_))));
        assert!(!sent.iter().any(|m| matches!(m, Message::RunAccepted(_))));
    }

    /// The run ids of `hello.activeRuns`.
    fn ids(active: Vec<ActiveRun>) -> Vec<Uuid> {
        active.into_iter().map(|a| a.run_id).collect()
    }

    /// A run whose end has been written, with one event before it; the time of the write.
    fn finished_and_written(outbox: &Outbox) -> (Uuid, Instant) {
        let run = Uuid::new_v4();
        outbox.open_run(RunAccepted {
            run_id: run,
            epoch: 1,
        });
        outbox.push_event(event(run, 1, false));
        outbox.push_finished(finished(run, 1));
        let _ = take_all(outbox);
        (run, Instant::now())
    }

    #[test]
    fn holds_runs_until_their_end_was_written_long_enough_on_a_connection() {
        let outbox = Outbox::new();
        assert!(!outbox.holds_runs());
        let (run, written) = finished_and_written(&outbox);
        assert!(
            outbox.holds_runs(),
            "a finish just written may be sent again"
        );
        let second = Duration::from_secs(1);
        assert!(outbox.holds_runs_at(written + DEFAULT_FINISHED_RETENTION - second));
        // The connection stayed up for the retention after the write: the end arrived.
        assert!(!outbox.holds_runs_at(written + DEFAULT_FINISHED_RETENTION + second));
        assert!(!outbox.knows_run(run));
    }

    /// The server went down just after the kernel acknowledged a `run.finished` its process
    /// never read, and came back 45 s later: the host still lists the run in `hello` and writes
    /// its end again, so the server ends it with the host's outcome instead of losing it.
    #[test]
    fn a_finish_lost_with_the_connection_is_listed_and_sent_again_after_a_reconnect() {
        let outbox = Outbox::new();
        let (run, written) = finished_and_written(&outbox);
        outbox.on_disconnect_at(written);

        let hello = outbox.active_runs_at(written + Duration::from_secs(45));
        assert_eq!(ids(hello), vec![run]);
        outbox.on_reconnect();
        let resent = take_all(&outbox);
        assert_eq!(event_ids(&resent), vec![(run, 1)]);
        assert!(matches!(resent.last(), Some(Message::RunFinished(f)) if f.run_id == run));
        // Its retention counts again from this write, on this connection.
        let rewritten = Instant::now();
        assert!(
            outbox.holds_runs_at(rewritten + DEFAULT_FINISHED_RETENTION - Duration::from_secs(1))
        );
        assert!(
            !outbox.holds_runs_at(rewritten + DEFAULT_FINISHED_RETENTION + Duration::from_secs(1))
        );
    }

    /// The host notices a dead connection only after its silence limit, and the server stays
    /// away for ten minutes: time without a connection does not count, so the run is still
    /// held while the host tries to reconnect, and listed and sent again when it succeeds.
    #[test]
    fn time_without_a_connection_does_not_count() {
        let outbox = Outbox::new();
        let (run, written) = finished_and_written(&outbox);
        outbox.on_disconnect_at(written + Duration::from_secs(45));
        // A failed attempt to connect ends no connection: the first end counts.
        outbox.on_disconnect_at(written + Duration::from_secs(300));

        assert!(outbox.holds_runs_at(written + Duration::from_secs(300)));
        assert_eq!(
            ids(outbox.active_runs_at(written + Duration::from_secs(600))),
            vec![run]
        );
        outbox.on_reconnect();
        assert!(
            matches!(take_all(&outbox).last(), Some(Message::RunFinished(f)) if f.run_id == run)
        );
    }

    /// A finish the connection carried for longer than the retention reached the server: after
    /// a later reconnect the run is neither listed nor sent again.
    #[test]
    fn a_finish_older_than_the_retention_is_not_sent_again() {
        let outbox = Outbox::with_retention(Duration::from_secs(60));
        let (run, written) = finished_and_written(&outbox);
        outbox.on_disconnect_at(written + Duration::from_secs(61));

        assert!(
            outbox
                .active_runs_at(written + Duration::from_secs(90))
                .is_empty()
        );
        outbox.on_reconnect();
        assert!(take_all(&outbox).is_empty());
        assert!(!outbox.knows_run(run));
    }

    #[test]
    fn caps_buffer_dropping_logs_first() {
        let outbox = Outbox::new();
        let run = Uuid::new_v4();
        outbox.open_run(RunAccepted {
            run_id: run,
            epoch: 1,
        });
        let _ = take_all(&outbox);
        let mut seq = 0;
        for i in 0..(MAX_BUFFERED_EVENTS_PER_RUN + 100) {
            seq += 1;
            outbox.push_event(event(run, seq, i % 2 == 0));
        }
        assert_eq!(outbox.buffered(run), MAX_BUFFERED_EVENTS_PER_RUN);
        assert_eq!(outbox.dropped(run), 100);
        let sent = take_all(&outbox);
        let logs = sent
            .iter()
            .filter(|m| matches!(m, Message::RunEvent(e) if e.event.is_log()))
            .count();
        // 100 LOG events were dropped; every message event survived.
        assert_eq!(logs, (MAX_BUFFERED_EVENTS_PER_RUN + 100) / 2 - 100);
    }

    #[test]
    fn sent_items_are_dropped_before_unsent_ones() {
        let outbox = Outbox::new();
        let run = Uuid::new_v4();
        outbox.open_run(RunAccepted {
            run_id: run,
            epoch: 1,
        });
        for seq in 1..=10 {
            outbox.push_event(event(run, seq, false));
        }
        let _ = take_all(&outbox);
        for seq in 11..=(MAX_BUFFERED_EVENTS_PER_RUN as u64 + 5) {
            outbox.push_event(event(run, seq, false));
        }
        assert_eq!(outbox.dropped(run), 0);
        let unsent = take_all(&outbox);
        assert_eq!(unsent.len(), MAX_BUFFERED_EVENTS_PER_RUN - 5);
    }
}
