//! The reliable link of one run (ADR-0095), on each side of the bridge.
//!
//! Numbered frames are kept until the peer acknowledges them, and sent again in order after
//! a reconnect; a frame that arrives twice is dropped. The bytes not yet acknowledged are
//! bounded: a sender waits for room, so a full buffer slows its source down and nothing is
//! dropped. The connection comes and goes: [`LinkHandle::attach`] gives the link a new one,
//! after its handshake, with the number the peer reported there.
//!
//! What a receiver holds is bounded too: it acknowledges only what it has handed on to its
//! consumer, and reads at most [`INBOUND_BYTES`] ahead of it (plus a few frames in flight).
//! A consumer that stalls leaves the next frame waiting in the link, the connection unread
//! and the peer's sender without credit, so a flooding peer is held back by TCP and nothing
//! piles up in this process. The connection's comings and goings are bounded as well: a
//! connection is attached only with room for its two events among [`STATE_EVENTS`], so a
//! peer that connects again and again while the consumer stalls piles up nothing either.

use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncWrite, ReadHalf, WriteHalf};
use tokio::sync::{OwnedSemaphorePermit, Semaphore, mpsc, oneshot, watch};
use tokio::task::JoinHandle;
use tokio::time::{Instant, MissedTickBehavior};
use tokio_util::sync::CancellationToken;

use crate::control::Control;
use crate::frame::{self, Frame, Kind};

/// A connection of the link: TLS over TCP, or an in-memory pipe in tests.
pub trait Stream: AsyncRead + AsyncWrite + Send + Unpin {}
impl<T: AsyncRead + AsyncWrite + Send + Unpin> Stream for T {}
pub type Conn = Box<dyn Stream>;

/// An acknowledgement goes out after this many frames, or at the next tick.
const ACK_EVERY: u64 = 64;
/// The bytes of numbered frames handed on to the consumer and not yet taken by it (ADR-0095:
/// the unacknowledged cap). The next frame waits in the link until there is room.
pub const INBOUND_BYTES: usize = 8 * 1024 * 1024;
/// Frames a connection's reader decodes ahead of the link; a full queue stops the reading.
const READ_AHEAD: usize = 16;
/// Connection-state events ([`Inbound::Connected`], [`Inbound::Disconnected`]) handed on to
/// the consumer and not yet taken by it. A connection holds the share of its two events from
/// the moment it is attached; one that finds no room is not attached.
pub const STATE_EVENTS: usize = 64;

#[derive(Debug, Clone, Copy)]
pub struct Timing {
    /// How often the link checks its connection and sends what is due.
    pub tick: Duration,
    /// A `PING` goes out when nothing was sent for this long.
    pub ping_after: Duration,
    /// A connection that delivered nothing for this long is closed.
    pub dead_after: Duration,
    /// A write that does not finish in this time closes the connection.
    pub write_timeout: Duration,
}

impl Default for Timing {
    fn default() -> Timing {
        Timing {
            tick: Duration::from_millis(200),
            ping_after: Duration::from_secs(5),
            dead_after: Duration::from_secs(15),
            write_timeout: Duration::from_secs(10),
        }
    }
}

/// What the link delivers, in order.
pub enum Inbound {
    Control(Control),
    Data {
        stream: u32,
        bytes: Vec<u8>,
    },
    Eof {
        stream: u32,
    },
    /// A tunnel's stream may carry `bytes` more ([`crate::tunnel`]).
    Credit {
        stream: u32,
        bytes: u32,
    },
    /// A connection was attached.
    Connected,
    /// The connection was lost. Numbered frames wait for the next one.
    Disconnected,
}

impl std::fmt::Debug for Inbound {
    // Never the bytes or the control message: they may hold the run's credentials.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Inbound::Control(_) => f.write_str("Control"),
            Inbound::Data { stream, bytes } => write!(f, "Data({stream}, {} bytes)", bytes.len()),
            Inbound::Eof { stream } => write!(f, "Eof({stream})"),
            Inbound::Credit { stream, bytes } => write!(f, "Credit({stream}, {bytes})"),
            Inbound::Connected => f.write_str("Connected"),
            Inbound::Disconnected => f.write_str("Disconnected"),
        }
    }
}

#[derive(Debug, thiserror::Error)]
#[error("the bridge link is closed")]
pub struct Closed;

/// Why [`LinkHandle::attach`] did not take a connection.
#[derive(Debug, thiserror::Error)]
pub enum NotAttached {
    #[error("the bridge link is closed")]
    Closed,
    /// The consumer has not taken the events of the connections before it
    /// ([`STATE_EVENTS`]); the peer tries again later.
    #[error("the link's consumer has not taken the events of the connections before this one")]
    NoRoom,
}

enum Cmd {
    Send {
        frame: Frame,
        permits: u32,
    },
    Attach {
        conn: Conn,
        peer_rx: u64,
        /// The connection's shares of [`STATE_EVENTS`]: its `Connected`, its `Disconnected`.
        connected: OwnedSemaphorePermit,
        disconnect: OwnedSemaphorePermit,
    },
    RxSeq(oneshot::Sender<u64>),
    TxSeq(oneshot::Sender<u64>),
    Drained(oneshot::Sender<()>),
    Detach,
}

/// The handle of a link; clones share it.
#[derive(Clone)]
pub struct LinkHandle {
    cmd: mpsc::UnboundedSender<Cmd>,
    credit: Arc<Semaphore>,
    capacity: u32,
    closed: CancellationToken,
    connected: watch::Receiver<bool>,
    states: Arc<Semaphore>,
}

impl LinkHandle {
    /// Whether a connection is attached now, and when that changes.
    pub fn connected(&self) -> watch::Receiver<bool> {
        self.connected.clone()
    }

    /// Whether a connection attached now would be taken: false while the consumer has not
    /// taken the connection-state events before it ([`STATE_EVENTS`]). A handshake checks it
    /// before it answers, so that the peer tries again later instead of losing a connection it
    /// was welcomed on; [`LinkHandle::attach`] decides for itself.
    pub fn can_attach(&self) -> bool {
        self.states.available_permits() >= 2
    }

    /// Sends a numbered frame. Waits while the bytes not yet acknowledged fill the buffer.
    /// A frame that its receiver would refuse for its size is not sent: it would close every
    /// connection it was sent again on.
    pub async fn send(&self, kind: Kind, stream: u32, payload: Vec<u8>) -> Result<(), Closed> {
        debug_assert!(kind.numbered());
        let limit = match kind {
            Kind::Control => frame::MAX_CONTROL,
            _ => frame::MAX_DATA,
        };
        if payload.len() > limit {
            tracing::warn!(
                len = payload.len(),
                "a bridge frame over its size limit is not sent"
            );
            return Err(Closed);
        }
        let permits = u32::try_from(payload.len().max(1))
            .unwrap_or(u32::MAX)
            .min(self.capacity);
        let permit = tokio::select! {
            permit = self.credit.clone().acquire_many_owned(permits) => permit.map_err(|_| Closed)?,
            _ = self.closed.cancelled() => return Err(Closed),
        };
        permit.forget();
        self.cmd
            .send(Cmd::Send {
                frame: Frame::new(kind, stream, 0, payload),
                permits,
            })
            .map_err(|_| Closed)
    }

    pub async fn send_control(&self, control: &Control) -> Result<(), Closed> {
        let payload = serde_json::to_vec(control).expect("control messages serialize");
        self.send(Kind::Control, frame::STREAM_CONTROL, payload)
            .await
    }

    /// Gives the link a connection whose handshake is done; `peer_rx` is the highest number
    /// the peer reported to have received. An earlier connection is dropped. A connection
    /// for which the consumer has not taken the events of the earlier ones ([`STATE_EVENTS`])
    /// is dropped instead, here and unread, and the earlier one is kept: its share is taken
    /// before the connection is handed to the link, so that connections not yet taken hold
    /// nothing beyond it either.
    pub fn attach(&self, conn: Conn, peer_rx: u64) -> Result<(), NotAttached> {
        let shares = (
            self.states.clone().try_acquire_owned(),
            self.states.clone().try_acquire_owned(),
        );
        let (Ok(connected), Ok(disconnect)) = shares else {
            tracing::warn!(
                unread = STATE_EVENTS,
                "a bridge connection is not attached: its consumer has not taken the connection events before it"
            );
            return Err(NotAttached::NoRoom);
        };
        self.cmd
            .send(Cmd::Attach {
                conn,
                peer_rx,
                connected,
                disconnect,
            })
            .map_err(|_| NotAttached::Closed)
    }

    /// The highest number received in order, for a handshake.
    pub async fn rx_seq(&self) -> Result<u64, Closed> {
        let (tx, rx) = oneshot::channel();
        self.cmd.send(Cmd::RxSeq(tx)).map_err(|_| Closed)?;
        rx.await.map_err(|_| Closed)
    }

    /// The highest number sent, for a handshake with a host that restarted (ADR-0101).
    pub async fn tx_seq(&self) -> Result<u64, Closed> {
        let (tx, rx) = oneshot::channel();
        self.cmd.send(Cmd::TxSeq(tx)).map_err(|_| Closed)?;
        rx.await.map_err(|_| Closed)
    }

    /// Resolves once the peer has acknowledged every frame sent so far.
    pub async fn drained(&self) -> Result<(), Closed> {
        let (tx, rx) = oneshot::channel();
        self.cmd.send(Cmd::Drained(tx)).map_err(|_| Closed)?;
        rx.await.map_err(|_| Closed)
    }

    /// Drops the connection, as a lost one; the link waits for the next.
    pub fn detach(&self) {
        let _ = self.cmd.send(Cmd::Detach);
    }

    /// Ends the link: the connection is dropped and every waiting sender fails.
    pub fn close(&self) {
        self.closed.cancel();
    }

    pub fn is_closed(&self) -> bool {
        self.closed.is_cancelled()
    }
}

/// The consumer's end of a link: what the peer sent, in order, and the connection's comings
/// and goings. An event's share of [`INBOUND_BYTES`], or of [`STATE_EVENTS`], is released as
/// it is taken.
pub struct InboundReceiver {
    rx: mpsc::UnboundedReceiver<(Inbound, Option<OwnedSemaphorePermit>)>,
}

impl InboundReceiver {
    /// The next event; `None` once the link has ended. Cancel-safe, as a channel's `recv`.
    pub async fn recv(&mut self) -> Option<Inbound> {
        self.rx.recv().await.map(|(event, _budget)| event)
    }
}

/// Starts a link that keeps up to `capacity` bytes until they are acknowledged, and hands
/// on at most [`INBOUND_BYTES`] before its consumer takes them.
pub fn spawn(capacity: usize, timing: Timing) -> (LinkHandle, InboundReceiver) {
    spawn_bounded(capacity, INBOUND_BYTES, timing)
}

/// The highest frame number a peer may name for [`spawn_after`]: far above any link's, and far
/// below where the link's counters would overflow. The peer names it, so it is checked.
pub const MAX_SEQ: u64 = 1 << 62;

/// [`spawn`] for a session that its peer has had with another process (ADR-0101): this
/// process restarted, and the peer's link goes on. The first frame sent is `tx_after + 1`, the
/// next the peer expects, and the first one taken is `rx_after + 1`, the one after what the
/// peer has sent: what the peer sent before is the earlier process's.
pub fn spawn_after(
    capacity: usize,
    timing: Timing,
    tx_after: u64,
    rx_after: u64,
) -> (LinkHandle, InboundReceiver) {
    assert!(
        tx_after <= MAX_SEQ && rx_after <= MAX_SEQ,
        "frame numbers are checked against MAX_SEQ first"
    );
    spawn_with(capacity, INBOUND_BYTES, timing, tx_after, rx_after)
}

/// [`spawn`] with its own bound on what is handed on and not yet taken (`inbound_bytes`).
pub fn spawn_bounded(
    capacity: usize,
    inbound_bytes: usize,
    timing: Timing,
) -> (LinkHandle, InboundReceiver) {
    spawn_with(capacity, inbound_bytes, timing, 0, 0)
}

fn spawn_with(
    capacity: usize,
    inbound_bytes: usize,
    timing: Timing,
    tx_after: u64,
    rx_after: u64,
) -> (LinkHandle, InboundReceiver) {
    let capacity = u32::try_from(capacity.max(1)).unwrap_or(u32::MAX >> 4);
    let credit = Arc::new(Semaphore::new(capacity as usize));
    let budget_total = u32::try_from(inbound_bytes.max(1)).unwrap_or(u32::MAX >> 4);
    let states = Arc::new(Semaphore::new(STATE_EVENTS));
    let closed = CancellationToken::new();
    let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
    let (inbound_tx, inbound_rx) = mpsc::unbounded_channel();
    let (events_tx, events_rx) = mpsc::channel(READ_AHEAD);
    let (connected_tx, connected) = watch::channel(false);
    let now = Instant::now();
    let link = Link {
        connected: connected_tx,
        cmd_rx,
        inbound: inbound_tx,
        credit: credit.clone(),
        budget: Arc::new(Semaphore::new(budget_total as usize)),
        budget_total,
        pending: None,
        timing,
        closed: closed.clone(),
        conn: None,
        generation: 0,
        events_tx,
        events_rx,
        next_seq: tx_after + 1,
        unacked: VecDeque::new(),
        rx_seq: rx_after,
        acked: rx_after,
        last_recv: now,
        last_send: now,
        drain_waiters: Vec::new(),
        pings: 0,
    };
    tokio::spawn(link.run());
    (
        LinkHandle {
            cmd: cmd_tx,
            credit,
            capacity,
            closed,
            connected,
            states,
        },
        InboundReceiver { rx: inbound_rx },
    )
}

struct Active {
    writer: WriteHalf<Conn>,
    reader: JoinHandle<()>,
    /// The share of [`STATE_EVENTS`] of this connection's `Disconnected`, taken when it was
    /// attached so that its loss is always reported.
    disconnect: OwnedSemaphorePermit,
}

struct Kept {
    seq: u64,
    bytes: Vec<u8>,
    permits: u32,
}

struct Link {
    connected: watch::Sender<bool>,
    cmd_rx: mpsc::UnboundedReceiver<Cmd>,
    inbound: mpsc::UnboundedSender<(Inbound, Option<OwnedSemaphorePermit>)>,
    credit: Arc<Semaphore>,
    /// What was handed on and not yet taken by the consumer, in bytes ([`INBOUND_BYTES`]).
    budget: Arc<Semaphore>,
    budget_total: u32,
    /// A frame in order that waits for its share of the budget. While it waits, nothing more
    /// is read from the connection, and nothing is acknowledged past it.
    pending: Option<Frame>,
    timing: Timing,
    closed: CancellationToken,
    conn: Option<Active>,
    /// Events of an earlier connection carry an older generation and are ignored.
    generation: u64,
    events_tx: mpsc::Sender<(u64, Option<Frame>)>,
    events_rx: mpsc::Receiver<(u64, Option<Frame>)>,
    next_seq: u64,
    unacked: VecDeque<Kept>,
    rx_seq: u64,
    /// The `rx_seq` the peer last heard from us.
    acked: u64,
    last_recv: Instant,
    last_send: Instant,
    drain_waiters: Vec<oneshot::Sender<()>>,
    pings: u64,
}

impl Link {
    async fn run(mut self) {
        let mut tick = tokio::time::interval(self.timing.tick);
        tick.set_missed_tick_behavior(MissedTickBehavior::Delay);
        loop {
            let needed = self.pending.as_ref().map_or(0, |frame| self.cost(frame));
            tokio::select! {
                biased;
                _ = self.closed.cancelled() => break,
                cmd = self.cmd_rx.recv() => match cmd {
                    Some(cmd) => self.command(cmd).await,
                    None => break,
                },
                // The frame that waits for room is delivered before anything more is read.
                permit = self.budget.clone().acquire_many_owned(needed), if self.pending.is_some() => {
                    // The budget is never closed; should it be, the frame stays unacknowledged.
                    if let Ok(permit) = permit
                        && let Some(frame) = self.pending.take()
                    {
                        self.deliver(frame, Some(permit)).await;
                    }
                }
                Some((generation, frame)) = self.events_rx.recv(), if self.pending.is_none() => {
                    if generation == self.generation {
                        self.received(frame).await;
                    }
                }
                _ = tick.tick() => self.tick().await,
            }
        }
        self.drop_conn(false);
        self.credit.close();
        self.closed.cancel();
    }

    /// A numbered frame's share of the budget: its payload, at least one byte.
    fn cost(&self, frame: &Frame) -> u32 {
        u32::try_from(frame.payload.len().max(1))
            .unwrap_or(u32::MAX)
            .min(self.budget_total)
    }

    async fn command(&mut self, cmd: Cmd) {
        match cmd {
            Cmd::Send { mut frame, permits } => {
                frame.seq = self.next_seq;
                self.next_seq += 1;
                let bytes = frame.encode();
                self.write(&bytes).await;
                self.unacked.push_back(Kept {
                    seq: frame.seq,
                    bytes,
                    permits,
                });
            }
            Cmd::Attach {
                conn,
                peer_rx,
                connected,
                disconnect,
            } => {
                self.drop_conn(false);
                let (reader, writer) = tokio::io::split(conn);
                self.generation += 1;
                let reader =
                    tokio::spawn(read_loop(reader, self.generation, self.events_tx.clone()));
                self.conn = Some(Active {
                    writer,
                    reader,
                    disconnect,
                });
                let now = Instant::now();
                self.last_recv = now;
                self.last_send = now;
                self.acknowledged(peer_rx);
                // What the peer has not received, in order, then where we are.
                let pending: Vec<Vec<u8>> = self.unacked.iter().map(|k| k.bytes.clone()).collect();
                for bytes in pending {
                    self.write(&bytes).await;
                }
                self.send_ack().await;
                if self.conn.is_some() {
                    let _ = self.connected.send(true);
                    let _ = self.inbound.send((Inbound::Connected, Some(connected)));
                }
            }
            Cmd::RxSeq(reply) => {
                let _ = reply.send(self.rx_seq);
            }
            Cmd::TxSeq(reply) => {
                let _ = reply.send(self.next_seq - 1);
            }
            Cmd::Drained(reply) => {
                if self.unacked.is_empty() {
                    let _ = reply.send(());
                } else {
                    self.drain_waiters.push(reply);
                }
            }
            Cmd::Detach => self.drop_conn(true),
        }
    }

    async fn received(&mut self, frame: Option<Frame>) {
        let Some(frame) = frame else {
            self.drop_conn(true);
            return;
        };
        self.last_recv = Instant::now();
        match frame.kind {
            Kind::Ack => {
                if let Some(seq) = frame.number() {
                    self.acknowledged(seq);
                }
            }
            Kind::Ping => {
                if let Some(nonce) = frame.number() {
                    self.write(&Frame::pong(nonce).encode()).await;
                }
            }
            Kind::Pong => {}
            Kind::Control | Kind::Data | Kind::Eof | Kind::Credit => {
                if frame.seq <= self.rx_seq {
                    // Sent again after a reconnect, or a handshake frame: had it.
                    return;
                }
                if frame.seq != self.rx_seq + 1 {
                    tracing::warn!(
                        expected = self.rx_seq + 1,
                        got = frame.seq,
                        "a bridge frame out of order; dropping the connection"
                    );
                    self.drop_conn(true);
                    return;
                }
                // Handed on only within the budget: otherwise it waits, unacknowledged, and
                // the peer keeps it (and sends it again should the connection go meanwhile).
                match self
                    .budget
                    .clone()
                    .try_acquire_many_owned(self.cost(&frame))
                {
                    Ok(permit) => self.deliver(frame, Some(permit)).await,
                    Err(_) => self.pending = Some(frame),
                }
            }
        }
    }

    /// Hands a numbered frame, the next in order, to the consumer, and acknowledges it when
    /// enough have gone unacknowledged.
    async fn deliver(&mut self, frame: Frame, budget: Option<OwnedSemaphorePermit>) {
        self.rx_seq = frame.seq;
        let item = match frame.kind {
            Kind::Control => match serde_json::from_slice::<Control>(&frame.payload) {
                Ok(control) => Inbound::Control(control),
                Err(e) => {
                    // Never the error's text: serde quotes the value it could not read.
                    tracing::warn!(
                        category = ?e.classify(),
                        column = e.column(),
                        "an unreadable control message on the bridge"
                    );
                    self.ack_if_due().await;
                    return;
                }
            },
            Kind::Data => Inbound::Data {
                stream: frame.stream,
                bytes: frame.payload,
            },
            Kind::Eof => Inbound::Eof {
                stream: frame.stream,
            },
            Kind::Credit => Inbound::Credit {
                stream: frame.stream,
                bytes: frame.credit().unwrap_or(0),
            },
            Kind::Ack | Kind::Ping | Kind::Pong => return,
        };
        let _ = self.inbound.send((item, budget));
        self.ack_if_due().await;
    }

    async fn ack_if_due(&mut self) {
        if self.rx_seq - self.acked >= ACK_EVERY {
            self.send_ack().await;
        }
    }

    fn acknowledged(&mut self, seq: u64) {
        let seq = seq.min(self.next_seq - 1);
        let mut released = 0usize;
        while self.unacked.front().is_some_and(|k| k.seq <= seq) {
            let kept = self.unacked.pop_front().expect("checked");
            released += kept.permits as usize;
        }
        if released > 0 {
            self.credit.add_permits(released);
        }
        if self.unacked.is_empty() {
            for waiter in self.drain_waiters.drain(..) {
                let _ = waiter.send(());
            }
        }
    }

    async fn tick(&mut self) {
        if self.conn.is_none() {
            return;
        }
        let now = Instant::now();
        // A connection that is not read, because the consumer has not taken what it was
        // handed, is not silent.
        if self.pending.is_none() && now.duration_since(self.last_recv) > self.timing.dead_after {
            tracing::debug!("the bridge connection is silent; dropping it");
            self.drop_conn(true);
            return;
        }
        if self.rx_seq > self.acked {
            self.send_ack().await;
        } else if now.duration_since(self.last_send) > self.timing.ping_after {
            self.pings += 1;
            self.write(&Frame::ping(self.pings).encode()).await;
        }
    }

    async fn send_ack(&mut self) {
        self.write(&Frame::ack(self.rx_seq).encode()).await;
        if self.conn.is_some() {
            self.acked = self.rx_seq;
        }
    }

    async fn write(&mut self, bytes: &[u8]) {
        let Some(active) = self.conn.as_mut() else {
            return;
        };
        let written = tokio::time::timeout(
            self.timing.write_timeout,
            frame::write_bytes(&mut active.writer, bytes),
        )
        .await;
        match written {
            Ok(Ok(())) => self.last_send = Instant::now(),
            Ok(Err(e)) => {
                tracing::debug!(error = %e, "a write on the bridge failed");
                self.drop_conn(true);
            }
            Err(_) => {
                tracing::debug!("a write on the bridge timed out");
                self.drop_conn(true);
            }
        }
    }

    fn drop_conn(&mut self, notify: bool) {
        if let Some(active) = self.conn.take() {
            active.reader.abort();
            self.generation += 1;
            // Not acknowledged, so the peer sends it again on the next connection.
            self.pending = None;
            let _ = self.connected.send(false);
            if notify {
                let _ = self
                    .inbound
                    .send((Inbound::Disconnected, Some(active.disconnect)));
            }
        }
    }
}

/// Decodes the connection's frames for the link. Waits when the link has not taken the last
/// [`READ_AHEAD`], so a peer that sends faster than the consumer reads is held by TCP.
async fn read_loop(
    mut reader: ReadHalf<Conn>,
    generation: u64,
    events: mpsc::Sender<(u64, Option<Frame>)>,
) {
    loop {
        match frame::read_frame(&mut reader).await {
            Ok(Some(frame)) => {
                if events.send((generation, Some(frame))).await.is_err() {
                    return;
                }
            }
            Ok(None) => break,
            Err(e) => {
                tracing::debug!(error = %e, "the bridge connection failed");
                break;
            }
        }
    }
    let _ = events.send((generation, None)).await;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::STREAM_STDOUT;

    fn quick() -> Timing {
        Timing {
            tick: Duration::from_millis(20),
            ping_after: Duration::from_millis(100),
            dead_after: Duration::from_millis(300),
            write_timeout: Duration::from_secs(2),
        }
    }

    /// Joins two links with a fresh in-memory pipe, as a reconnect's handshake would.
    async fn join(a: &LinkHandle, b: &LinkHandle) {
        let (left, right) = tokio::io::duplex(256 * 1024);
        let a_rx = a.rx_seq().await.unwrap();
        let b_rx = b.rx_seq().await.unwrap();
        a.attach(Box::new(left), b_rx).unwrap();
        b.attach(Box::new(right), a_rx).unwrap();
    }

    async fn collect_data(inbound: &mut InboundReceiver, want: usize) -> Vec<u8> {
        let mut out = Vec::new();
        while out.len() < want {
            match tokio::time::timeout(Duration::from_secs(10), inbound.recv()).await {
                Ok(Some(Inbound::Data { bytes, .. })) => out.extend(bytes),
                Ok(Some(_)) => {}
                Ok(None) | Err(_) => panic!("got {} of {want} bytes", out.len()),
            }
        }
        out
    }

    #[tokio::test]
    async fn frames_arrive_in_order_in_both_directions() {
        let (a, mut a_in) = spawn(1 << 20, quick());
        let (b, mut b_in) = spawn(1 << 20, quick());
        join(&a, &b).await;
        a.send(Kind::Data, STREAM_STDOUT, b"one ".to_vec())
            .await
            .unwrap();
        a.send(Kind::Data, STREAM_STDOUT, b"two".to_vec())
            .await
            .unwrap();
        b.send_control(&Control::Started { pid: 7 }).await.unwrap();
        assert_eq!(collect_data(&mut b_in, 7).await, b"one two");
        loop {
            match a_in.recv().await.unwrap() {
                Inbound::Control(Control::Started { pid }) => {
                    assert_eq!(pid, 7);
                    break;
                }
                Inbound::Connected => {}
                other => panic!("{other:?}"),
            }
        }
        tokio::time::timeout(Duration::from_secs(5), a.drained())
            .await
            .unwrap()
            .unwrap();
    }

    #[tokio::test]
    async fn nothing_is_lost_or_doubled_across_reconnects() {
        let (a, _a_in) = spawn(64 * 1024, quick());
        let (b, mut b_in) = spawn(64 * 1024, quick());
        join(&a, &b).await;
        let sender = {
            let a = a.clone();
            tokio::spawn(async move {
                for i in 0..2000u32 {
                    a.send(Kind::Data, STREAM_STDOUT, format!("{i:05}\n").into_bytes())
                        .await
                        .unwrap();
                }
            })
        };
        let mut expected = Vec::new();
        for i in 0..2000u32 {
            expected.extend(format!("{i:05}\n").into_bytes());
        }
        let mut got = Vec::new();
        for round in 0..5 {
            got.extend(collect_data(&mut b_in, 2000).await);
            // Both sides lose the connection, as when a network drops.
            a.detach();
            b.detach();
            tokio::time::sleep(Duration::from_millis(30 * (round + 1))).await;
            join(&a, &b).await;
        }
        let rest = expected.len() - got.len();
        got.extend(collect_data(&mut b_in, rest).await);
        sender.await.unwrap();
        assert_eq!(got.len(), expected.len());
        assert!(got == expected, "the bytes differ");
    }

    #[tokio::test]
    async fn a_full_buffer_makes_the_sender_wait_until_the_peer_acknowledges() {
        let (a, _a_in) = spawn(1024, quick());
        let (b, mut b_in) = spawn(1024, quick());
        // No connection: the first 1 KiB fits, the next send waits.
        a.send(Kind::Data, STREAM_STDOUT, vec![b'x'; 1024])
            .await
            .unwrap();
        let blocked = tokio::time::timeout(
            Duration::from_millis(200),
            a.send(Kind::Data, STREAM_STDOUT, vec![b'y'; 10]),
        )
        .await;
        assert!(blocked.is_err(), "the send should wait for room");
        join(&a, &b).await;
        tokio::time::timeout(
            Duration::from_secs(5),
            a.send(Kind::Data, STREAM_STDOUT, vec![b'y'; 10]),
        )
        .await
        .expect("room after the acknowledgement")
        .unwrap();
        assert_eq!(collect_data(&mut b_in, 1034).await.len(), 1034);
    }

    #[tokio::test]
    async fn a_slow_consumer_holds_back_the_peer() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        // Long enough that neither side gives the connection up while the consumer stalls.
        let patient = Timing {
            write_timeout: Duration::from_secs(30),
            dead_after: Duration::from_secs(30),
            ..quick()
        };
        let (a, _a_in) = spawn_bounded(256 * 1024, 256 * 1024, patient);
        let (b, mut b_in) = spawn_bounded(256 * 1024, 256 * 1024, patient);
        join(&a, &b).await;
        let total = 4 * 1024 * 1024;
        let chunk = 64 * 1024;
        let sent = Arc::new(AtomicUsize::new(0));
        let sender = {
            let (a, sent) = (a.clone(), sent.clone());
            tokio::spawn(async move {
                for i in 0..(total / chunk) {
                    a.send(Kind::Data, STREAM_STDOUT, vec![(i % 251) as u8; chunk])
                        .await
                        .unwrap();
                    sent.fetch_add(chunk, Ordering::SeqCst);
                }
            })
        };
        // b never reads: a gets as far as the buffers in between (a's unacknowledged bytes,
        // b's budget, its read-ahead and the pipe), then waits.
        tokio::time::sleep(Duration::from_millis(500)).await;
        let stalled = sent.load(Ordering::SeqCst);
        assert!(
            stalled < total / 2 && !sender.is_finished(),
            "a sent {stalled} of {total} bytes while b read nothing"
        );
        assert!(*a.connected().borrow() && *b.connected().borrow());
        // Reading frees the budget, and everything arrives, in order.
        let got = collect_data(&mut b_in, total).await;
        tokio::time::timeout(Duration::from_secs(10), sender)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(got.len(), total);
        for (i, piece) in got.chunks(chunk).enumerate() {
            assert!(piece.iter().all(|b| *b == (i % 251) as u8), "chunk {i}");
        }
        tokio::time::timeout(Duration::from_secs(5), a.drained())
            .await
            .unwrap()
            .unwrap();
    }

    #[tokio::test]
    async fn a_peer_that_reconnects_while_the_consumer_stalls_piles_up_nothing() {
        let (a, mut a_in) = spawn(1 << 20, quick());
        let (b, mut b_in) = spawn(1 << 20, quick());
        // b takes its events; a's consumer takes nothing while its peer connects again and
        // again, as a hostile bridge with the session key would.
        let b_reader = tokio::spawn(async move { while b_in.recv().await.is_some() {} });
        let (mut taken, mut refused) = (0, 0);
        for _ in 0..200 {
            let (left, right) = tokio::io::duplex(256 * 1024);
            let b_rx = b.rx_seq().await.unwrap();
            // Refused at the handle, at once: the connection is dropped here and nothing is
            // queued for the link.
            match a.attach(Box::new(left), b_rx) {
                Ok(()) => taken += 1,
                Err(NotAttached::NoRoom) => {
                    refused += 1;
                    assert!(!a.can_attach());
                    continue;
                }
                Err(e) => panic!("{e}"),
            }
            b.attach(Box::new(right), 0).unwrap();
        }
        assert!(refused > 0, "none of 200 connections was refused");
        // The link answers still: a connection it does not take blocks nothing.
        tokio::time::timeout(Duration::from_secs(5), a.rx_seq())
            .await
            .unwrap()
            .unwrap();
        assert!(!a.can_attach());
        // Every connection taken was reported, and no refused one: those queued nothing.
        let (mut unread, mut connected) = (0, 0);
        while let Ok((event, _share)) = a_in.rx.try_recv() {
            match event {
                Inbound::Connected => connected += 1,
                Inbound::Disconnected => {}
                other => panic!("{other:?}"),
            }
            unread += 1;
        }
        assert_eq!(connected, taken);
        assert!(
            (STATE_EVENTS / 2..=STATE_EVENTS).contains(&unread),
            "{unread} connection events were handed on unread"
        );
        // Taken, there is room again: the next connection is attached and carries data.
        tokio::time::timeout(Duration::from_secs(5), async {
            while !a.can_attach() {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("room once the events are taken");
        join(&a, &b).await;
        b.send(Kind::Data, STREAM_STDOUT, b"after".to_vec())
            .await
            .unwrap();
        assert_eq!(collect_data(&mut a_in, 5).await, b"after");
        drop(b);
        b_reader.abort();
    }

    #[tokio::test]
    async fn a_silent_connection_is_dropped() {
        let (a, mut a_in) = spawn(1024, quick());
        // The other end stays open but never sends anything.
        let (left, _right) = tokio::io::duplex(1024);
        a.attach(Box::new(left), 0).unwrap();
        let mut events = Vec::new();
        while let Ok(Some(event)) = tokio::time::timeout(Duration::from_secs(3), a_in.recv()).await
        {
            let lost = matches!(event, Inbound::Disconnected);
            events.push(format!("{event:?}"));
            if lost {
                break;
            }
        }
        assert_eq!(events, ["Connected", "Disconnected"]);
    }

    #[tokio::test]
    async fn a_closed_link_fails_its_senders() {
        let (a, _a_in) = spawn(4, quick());
        a.send(Kind::Data, STREAM_STDOUT, vec![0; 4]).await.unwrap();
        let waiting = {
            let a = a.clone();
            tokio::spawn(async move { a.send(Kind::Data, STREAM_STDOUT, vec![0; 4]).await })
        };
        tokio::time::sleep(Duration::from_millis(50)).await;
        a.close();
        assert!(waiting.await.unwrap().is_err());
        assert!(a.is_closed());
    }
}
