//! Tunnels (ADR-0095): TCP connections in the sandbox, carried as streams of the run's link
//! to the runner host's listeners.
//!
//! - The host names the tunnels before `start`: a number, and the address to listen on in the
//!   sandbox. The sandbox listens there and opens one stream per connection (`open`, with the
//!   tunnel's number). The host connects the stream to that tunnel's listener and to nothing
//!   else: the sandbox never names an address of the host.
//! - Each direction of a stream has a window of [`WINDOW`] bytes. A sender waits for the
//!   receiver's `CREDIT`, which the receiver sends as it writes the bytes on, so one slow
//!   reader holds back its own stream, never the link. A receiver resets a stream whose peer
//!   sends past the window.
//! - `EOF` ends one direction (a half close); `reset` ends both at once.
//! - At most [`MAX_STREAMS`] streams are open at once, and a run has at most [`MAX_TUNNELS`]
//!   tunnels.
//! - The same streams carry the host's files ([`crate::files`]): those the host numbers, from
//!   [`HOST_STREAMS`] up, and the sandbox's tunnels stay below.

use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{Semaphore, mpsc};
use tokio_util::sync::CancellationToken;

use crate::control::{Control, Tunnel};
use crate::frame::{Frame, Kind, STREAM_DYNAMIC};
use crate::link::{Inbound, InboundReceiver, LinkHandle};

/// The bytes one direction of a stream may carry before its receiver's next credit.
pub const WINDOW: u32 = 128 * 1024;
/// The streams open at once, per run. With [`WINDOW`], what a host holds for one sandbox's
/// streams is bounded by 16 MiB.
pub const MAX_STREAMS: usize = 128;
/// The tunnels of one run.
pub const MAX_TUNNELS: usize = 16;
/// The first number of the streams that the host opens (files); the sandbox's tunnels take
/// the numbers below, from [`STREAM_DYNAMIC`].
pub const HOST_STREAMS: u32 = 0x8000_0000;
/// The most bytes read from a connection into one frame.
const CHUNK: usize = 32 * 1024;

enum Incoming {
    Data(Vec<u8>),
    Eof,
}

struct Entry {
    incoming: mpsc::UnboundedSender<Incoming>,
    /// What the peer may still send before our next credit.
    budget: u32,
    /// Bytes written on since our last credit.
    consumed: u32,
    /// What we may still send before the peer's next credit.
    window: Arc<Semaphore>,
    cancel: CancellationToken,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// The streams of one link, on either side: the tunnels' and the files'.
pub struct Streams {
    link: LinkHandle,
    table: Mutex<HashMap<u32, Entry>>,
    closed: CancellationToken,
}

/// A registered stream whose connection does not run yet: its bytes wait.
pub struct Opened {
    id: u32,
    incoming: mpsc::UnboundedReceiver<Incoming>,
    window: Arc<Semaphore>,
    cancel: CancellationToken,
}

impl Opened {
    pub fn id(&self) -> u32 {
        self.id
    }
}

impl Streams {
    pub fn new(link: LinkHandle) -> Arc<Streams> {
        Arc::new(Streams {
            link,
            table: Mutex::new(HashMap::new()),
            closed: CancellationToken::new(),
        })
    }

    /// Streams open now.
    pub fn len(&self) -> usize {
        lock(&self.table).len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Registers stream `id`. `None` when the id is in use, [`MAX_STREAMS`] are open, or the
    /// streams are closed.
    pub fn open(&self, id: u32) -> Option<Opened> {
        if id < STREAM_DYNAMIC || self.closed.is_cancelled() {
            return None;
        }
        let mut table = lock(&self.table);
        if table.len() >= MAX_STREAMS || table.contains_key(&id) {
            return None;
        }
        let (tx, rx) = mpsc::unbounded_channel();
        let window = Arc::new(Semaphore::new(WINDOW as usize));
        let cancel = self.closed.child_token();
        table.insert(
            id,
            Entry {
                incoming: tx,
                budget: WINDOW,
                consumed: 0,
                window: window.clone(),
                cancel: cancel.clone(),
            },
        );
        Some(Opened {
            id,
            incoming: rx,
            window,
            cancel,
        })
    }

    /// Carries `socket` as the stream, until both directions have ended or it is reset.
    pub fn run(self: &Arc<Self>, opened: Opened, socket: TcpStream) {
        let _ = socket.set_nodelay(true);
        let (reader, writer) = socket.into_split();
        self.run_io(opened, reader, writer);
    }

    /// Carries what `reader` gives to the peer, and what the peer sends into `writer`, until
    /// both have ended or the stream is reset. `writer` is shut down at the peer's `EOF`.
    pub fn run_io<R, W>(self: &Arc<Self>, opened: Opened, reader: R, writer: W)
    where
        R: AsyncRead + Unpin + Send + 'static,
        W: AsyncWrite + Unpin + Send + 'static,
    {
        tokio::spawn(self.clone().carry(opened, reader, writer));
    }

    /// Hands a tunnel's frames to its stream, and gives back what is not a tunnel's. A frame of
    /// a stream that is gone (reset, or never opened) is dropped.
    pub fn route(&self, event: Inbound) -> Option<Inbound> {
        match event {
            Inbound::Data { stream, bytes } if stream >= STREAM_DYNAMIC => self.data(stream, bytes),
            Inbound::Eof { stream } if stream >= STREAM_DYNAMIC => {
                if let Some(entry) = lock(&self.table).get(&stream) {
                    let _ = entry.incoming.send(Incoming::Eof);
                }
            }
            Inbound::Credit { stream, bytes } => self.credit(stream, bytes),
            Inbound::Control(Control::Reset { stream }) => {
                if let Some(entry) = lock(&self.table).remove(&stream) {
                    entry.cancel.cancel();
                }
            }
            other => return Some(other),
        }
        None
    }

    fn data(&self, id: u32, bytes: Vec<u8>) {
        let mut table = lock(&self.table);
        let Some(entry) = table.get_mut(&id) else {
            return;
        };
        let len = u32::try_from(bytes.len()).unwrap_or(u32::MAX);
        if len > entry.budget {
            tracing::warn!(
                stream = id,
                "a tunnel's peer sent more than its window; resetting the stream"
            );
            if let Some(entry) = table.remove(&id) {
                entry.cancel.cancel();
            }
            drop(table);
            self.refuse(id);
            return;
        }
        entry.budget -= len;
        let _ = entry.incoming.send(Incoming::Data(bytes));
    }

    fn credit(&self, id: u32, bytes: u32) {
        if let Some(entry) = lock(&self.table).get(&id) {
            // An honest peer never grants more than was sent; the window never grows past
            // its size.
            let room = (WINDOW as usize).saturating_sub(entry.window.available_permits());
            entry.window.add_permits((bytes as usize).min(room));
        }
    }

    /// Tells the peer that `id` is gone: an `open` refused, or a stream that failed here.
    pub fn refuse(&self, id: u32) {
        if self.closed.is_cancelled() {
            return;
        }
        let link = self.link.clone();
        // Never awaited where events are dispatched: a full link must not hold them up.
        tokio::spawn(async move {
            let _ = link.send_control(&Control::Reset { stream: id }).await;
        });
    }

    /// Ends stream `id` from this side.
    pub fn abort(&self, id: u32) {
        if let Some(entry) = lock(&self.table).remove(&id) {
            entry.cancel.cancel();
        }
        self.refuse(id);
    }

    /// Ends every stream; none opens any more.
    pub fn close(&self) {
        self.closed.cancel();
        lock(&self.table).clear();
    }

    /// `n` bytes of `id` were written on: once half the window is, the peer gets it back.
    async fn consumed(&self, id: u32, n: u32) {
        let grant = {
            let mut table = lock(&self.table);
            let Some(entry) = table.get_mut(&id) else {
                return;
            };
            entry.consumed += n;
            if entry.consumed < WINDOW / 2 {
                return;
            }
            let grant = entry.consumed;
            entry.consumed = 0;
            entry.budget += grant;
            grant
        };
        let _ = self
            .link
            .send(Kind::Credit, id, Frame::credit_payload(grant))
            .await;
    }

    async fn carry<R, W>(self: Arc<Self>, opened: Opened, mut reader: R, mut writer: W)
    where
        R: AsyncRead + Unpin + Send + 'static,
        W: AsyncWrite + Unpin + Send + 'static,
    {
        let Opened {
            id,
            mut incoming,
            window,
            cancel,
        } = opened;
        let link = self.link.clone();
        let outbound = async {
            let mut buf = vec![0u8; CHUNK];
            loop {
                let n = reader.read(&mut buf).await.map_err(|_| ())?;
                if n == 0 {
                    break;
                }
                let permits = u32::try_from(n).expect("a chunk fits");
                window.acquire_many(permits).await.map_err(|_| ())?.forget();
                link.send(Kind::Data, id, buf[..n].to_vec())
                    .await
                    .map_err(|_| ())?;
            }
            link.send(Kind::Eof, id, Vec::new()).await.map_err(|_| ())
        };
        let streams = &self;
        let inbound = async {
            while let Some(item) = incoming.recv().await {
                match item {
                    Incoming::Data(bytes) => {
                        writer.write_all(&bytes).await.map_err(|_| ())?;
                        let n = u32::try_from(bytes.len()).expect("a frame fits");
                        streams.consumed(id, n).await;
                    }
                    Incoming::Eof => {
                        let _ = writer.shutdown().await;
                        return Ok(());
                    }
                }
            }
            // Reset, or closed.
            Err(())
        };
        tokio::select! {
            _ = cancel.cancelled() => {}
            done = async { tokio::try_join!(outbound, inbound) } => match done {
                Ok(_) => {
                    lock(&self.table).remove(&id);
                }
                Err(()) => self.abort(id),
            },
        }
    }

    /// The sandbox's side: accepts the connections of tunnel `tunnel` and opens a stream for
    /// each, numbered from `next`. A connection beyond [`MAX_STREAMS`] is closed at once.
    pub async fn serve(self: Arc<Self>, listener: TcpListener, tunnel: u32, next: Arc<AtomicU32>) {
        loop {
            let accepted = tokio::select! {
                _ = self.closed.cancelled() => return,
                accepted = listener.accept() => accepted,
            };
            let socket = match accepted {
                Ok((socket, _)) => socket,
                Err(e) => {
                    tracing::debug!(error = %e, "a tunnel's listener cannot accept");
                    tokio::time::sleep(Duration::from_millis(50)).await;
                    continue;
                }
            };
            let id = next.fetch_add(1, Ordering::Relaxed);
            if !(STREAM_DYNAMIC..HOST_STREAMS).contains(&id) {
                // Two billion connections: the numbers are used up.
                return;
            }
            let Some(opened) = self.open(id) else {
                continue;
            };
            if self
                .link
                .send_control(&Control::Open { stream: id, tunnel })
                .await
                .is_err()
            {
                return;
            }
            self.run(opened, socket);
        }
    }
}

/// The next event that is not a tunnel's: those go to their streams. `None` once the link is
/// closed. Cancel-safe, as `recv` is: nothing waits between receiving and routing.
pub async fn next_event(inbound: &mut InboundReceiver, streams: &Streams) -> Option<Inbound> {
    loop {
        if let Some(event) = streams.route(inbound.recv().await?) {
            return Some(event);
        }
    }
}

/// The sandbox's side of `tunnels`: binds each address. The listeners, with their tunnels'
/// numbers and the addresses they got, or why one cannot listen.
pub async fn listen(tunnels: &[Tunnel]) -> Result<Vec<(Tunnel, TcpListener)>, String> {
    if tunnels.len() > MAX_TUNNELS {
        return Err(format!(
            "{} tunnels, more than {MAX_TUNNELS}",
            tunnels.len()
        ));
    }
    let mut ids = HashSet::new();
    let mut out = Vec::new();
    for tunnel in tunnels {
        if !ids.insert(tunnel.id) {
            return Err(format!("tunnel {} is named twice", tunnel.id));
        }
        let addr: SocketAddr = tunnel
            .addr
            .parse()
            .map_err(|_| format!("tunnel {}: `{}` is not an address", tunnel.id, tunnel.addr))?;
        if addr.ip().is_unspecified() {
            return Err(format!(
                "tunnel {}: {addr} is every address; a tunnel listens on one",
                tunnel.id
            ));
        }
        let listener = TcpListener::bind(addr)
            .await
            .map_err(|e| format!("tunnel {}: cannot listen on {addr}: {e}", tunnel.id))?;
        let bound = listener
            .local_addr()
            .map_err(|e| format!("tunnel {}: {e}", tunnel.id))?;
        out.push((
            Tunnel {
                id: tunnel.id,
                addr: bound.to_string(),
            },
            listener,
        ));
    }
    Ok(out)
}

/// The host's side: the listener of each tunnel, and the streams the sandbox opens.
pub struct HostTunnels {
    targets: HashMap<u32, SocketAddr>,
    streams: Arc<Streams>,
    last_open: u32,
    connect_timeout: Duration,
}

impl HostTunnels {
    pub fn new(
        streams: Arc<Streams>,
        targets: HashMap<u32, SocketAddr>,
        connect_timeout: Duration,
    ) -> HostTunnels {
        HostTunnels {
            targets,
            streams,
            last_open: STREAM_DYNAMIC - 1,
            connect_timeout,
        }
    }

    /// The sandbox opened `stream` on `tunnel`: connects it to the tunnel's listener. Stream
    /// numbers only grow, so a number is never used twice; anything else is refused.
    pub fn open(&mut self, stream: u32, tunnel: u32) {
        if stream < STREAM_DYNAMIC {
            tracing::warn!(
                stream,
                "the sandbox opened a tunnel's stream on a reserved number"
            );
            return;
        }
        if stream >= HOST_STREAMS {
            tracing::warn!(
                stream,
                "the sandbox opened a stream on a number of the host's"
            );
            return;
        }
        if stream <= self.last_open {
            tracing::warn!(
                stream,
                "the sandbox opened a tunnel's stream on a used number"
            );
            self.streams.refuse(stream);
            return;
        }
        self.last_open = stream;
        let Some(&target) = self.targets.get(&tunnel) else {
            tracing::warn!(
                stream,
                tunnel,
                "the sandbox opened a stream on an unknown tunnel"
            );
            self.streams.refuse(stream);
            return;
        };
        let Some(opened) = self.streams.open(stream) else {
            tracing::debug!(stream, "too many tunnel streams are open; refusing one");
            self.streams.refuse(stream);
            return;
        };
        let streams = self.streams.clone();
        let timeout = self.connect_timeout;
        tokio::spawn(async move {
            match tokio::time::timeout(timeout, TcpStream::connect(target)).await {
                Ok(Ok(socket)) => streams.run(opened, socket),
                Ok(Err(e)) => {
                    tracing::debug!(%target, error = %e, "a tunnel cannot reach its listener");
                    streams.abort(stream);
                }
                Err(_) => {
                    tracing::debug!(%target, "a tunnel's listener did not answer in time");
                    streams.abort(stream);
                }
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::link::{self, Timing};

    type Side = (LinkHandle, InboundReceiver);

    /// Two links over an in-memory pipe, as after a handshake.
    fn pair() -> (Side, Side) {
        let timing = Timing {
            tick: Duration::from_millis(20),
            ping_after: Duration::from_millis(200),
            dead_after: Duration::from_secs(5),
            write_timeout: Duration::from_secs(2),
        };
        let (a, a_in) = link::spawn(1 << 20, timing);
        let (b, b_in) = link::spawn(1 << 20, timing);
        let (left, right) = tokio::io::duplex(256 * 1024);
        a.attach(Box::new(left), 0).unwrap();
        b.attach(Box::new(right), 0).unwrap();
        ((a, a_in), (b, b_in))
    }

    /// The next control message, skipping the connection's events.
    async fn control(inbound: &mut InboundReceiver) -> Control {
        loop {
            match tokio::time::timeout(Duration::from_secs(5), inbound.recv()).await {
                Ok(Some(Inbound::Control(control))) => return control,
                Ok(Some(_)) => {}
                other => panic!("no control message: {other:?}"),
            }
        }
    }

    #[tokio::test]
    async fn the_host_refuses_streams_it_did_not_ask_for() {
        let target = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let ((host, _host_in), (peer, mut peer_in)) = pair();
        let streams = Streams::new(host);
        let mut tunnels = HostTunnels::new(
            streams.clone(),
            HashMap::from([(1, target.local_addr().unwrap())]),
            Duration::from_secs(2),
        );
        // A tunnel the host never named.
        tunnels.open(16, 9);
        assert_eq!(control(&mut peer_in).await, Control::Reset { stream: 16 });
        // A number of its own streams is ignored; one used before, or lower, is refused.
        tunnels.open(2, 1);
        tunnels.open(20, 1);
        tunnels.open(20, 1);
        assert_eq!(control(&mut peer_in).await, Control::Reset { stream: 20 });
        tunnels.open(18, 1);
        assert_eq!(control(&mut peer_in).await, Control::Reset { stream: 18 });
        // A number of the host's own streams is ignored.
        tunnels.open(HOST_STREAMS, 1);
        // The good one reached the tunnel's listener.
        let accepted = tokio::time::timeout(Duration::from_secs(5), target.accept()).await;
        assert!(accepted.is_ok(), "stream 20 did not reach its target");
        assert_eq!(streams.len(), 1);
        drop(peer);
    }

    #[tokio::test]
    async fn a_peer_that_sends_past_the_window_is_reset() {
        let ((ours, _ours_in), (peer, mut peer_in)) = pair();
        let streams = Streams::new(ours);
        // Registered, but its connection never runs: nothing is written on, so no credit.
        let _opened = streams.open(16).unwrap();
        let chunk = vec![7u8; 64 * 1024];
        for _ in 0..(WINDOW as usize / chunk.len()) {
            assert!(
                streams
                    .route(Inbound::Data {
                        stream: 16,
                        bytes: chunk.clone()
                    })
                    .is_none()
            );
        }
        assert_eq!(streams.len(), 1, "the window itself is allowed");
        streams.route(Inbound::Data {
            stream: 16,
            bytes: vec![1],
        });
        assert_eq!(control(&mut peer_in).await, Control::Reset { stream: 16 });
        assert!(streams.is_empty());
        drop(peer);
    }

    #[tokio::test]
    async fn at_most_max_streams_are_open_and_a_number_is_used_once() {
        let ((ours, _in), _peer) = pair();
        let streams = Streams::new(ours);
        let opened: Vec<Opened> = (0..MAX_STREAMS as u32)
            .map(|i| streams.open(STREAM_DYNAMIC + i).unwrap())
            .collect();
        assert!(streams.open(STREAM_DYNAMIC + MAX_STREAMS as u32).is_none());
        assert!(streams.open(STREAM_DYNAMIC).is_none());
        assert!(streams.open(3).is_none());
        streams.close();
        assert!(streams.is_empty());
        assert!(streams.open(1000).is_none(), "closed streams open nothing");
        drop(opened);
    }

    #[tokio::test]
    async fn the_sandbox_listens_only_on_what_it_can_and_names_the_ports() {
        let ok = listen(&[Tunnel {
            id: 1,
            addr: "127.0.0.1:0".into(),
        }])
        .await
        .unwrap();
        assert_ne!(ok[0].0.addr, "127.0.0.1:0");
        let taken = ok[0].0.addr.clone();
        for (tunnels, needle) in [
            (vec![("1", taken.as_str())], "cannot listen"),
            (vec![("1", "0.0.0.0:0")], "every address"),
            (vec![("1", "localhost:80")], "not an address"),
            (vec![("1", "127.0.0.1:0"), ("1", "127.0.0.1:0")], "twice"),
        ] {
            let tunnels: Vec<Tunnel> = tunnels
                .into_iter()
                .map(|(id, addr)| Tunnel {
                    id: id.parse().unwrap(),
                    addr: addr.into(),
                })
                .collect();
            let error = listen(&tunnels).await.expect_err("refused");
            assert!(error.contains(needle), "{error}");
        }
        let many: Vec<Tunnel> = (0..=MAX_TUNNELS as u32)
            .map(|id| Tunnel {
                id,
                addr: "127.0.0.1:0".into(),
            })
            .collect();
        assert!(listen(&many).await.is_err());
    }
}
