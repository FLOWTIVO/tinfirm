//! The sandbox's bridge in its front (ADR-0095; ADR-0112, path C, part 2): the TLS, the
//! `hello`, the link, the session and its streams of one sandbox, in the confined process that
//! holds no credential.
//!
//! The front holds its sandbox's TLS identity, which no other sandbox's bridge pins. Its
//! connections come from a listener that `run` bound (a Docker sandbox, the local executor),
//! where a connection from another address than the sandbox's is closed before its TLS
//! handshake, or from `run`, which dials a VM's bridge and passes each connection. The front
//! does the handshake and reads the `hello`, and hands it to `run`, which decides with the
//! ticket and the session key it holds ([`tinfirm_bridge::host::judge`]): the front only
//! applies the decision. The adapter's stdin, stdout and stderr are three sockets that `run`
//! gave; its end, the session's end and the answers to `run`'s requests go on the control
//! channel.

use std::collections::HashMap;
use std::net::IpAddr;
use std::os::fd::OwnedFd;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine as _;
use tinfirm_bridge::control::{Exec, ProbeCheck, Signal, Start};
use tinfirm_bridge::host::{
    self, Exit, HostIo, HostTiming, Output, REPLAY_BYTES, SessionControl, SessionCore,
};
use tinfirm_bridge::link::{self, LinkHandle};
use tinfirm_bridge::tunnel::{TunnelConnection, TunnelEnd};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UnixStream};
use tokio::sync::{Semaphore, mpsc, oneshot, watch};
use tokio_rustls::TlsAcceptor;
use tokio_rustls::server::TlsStream;

use super::conn::{BoxConnection, Handoff};
use super::serve::Writer;
use super::wire::{
    self, Admission, Reply, Service, ToMain, WireHello, WireIdentity, WireTiming, WireTunnel,
};

/// The front's services that a tunnel may reach, by service: each one's [`Handoff`], while
/// it serves.
pub(super) type Services = Arc<Mutex<HashMap<Service, Handoff>>>;

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// What may be sent to the adapter at any time, in the order `run` sent it.
enum Op {
    Signal(Signal),
    Stop(u64),
}

/// The session once a bridge is admitted.
struct Live {
    link: LinkHandle,
    control: SessionControl,
}

/// The sandbox's bridge in its front.
pub(super) struct FrontBridge {
    writer: Arc<Writer>,
    acceptor: TlsAcceptor,
    timing: HostTiming,
    /// The address the sandbox's bridge connects from; connections wait for it.
    allowed: watch::Sender<Option<IpAddr>>,
    /// The connections whose `hello` waits for `run`'s decision, by request.
    admissions: Mutex<HashMap<u64, oneshot::Sender<Admission>>>,
    live: Mutex<Option<Live>>,
    /// The session's owner part: its start, its reclaim.
    core: tokio::sync::Mutex<Option<SessionCore>>,
    /// The connections in their handshake or waiting for `run`, at most [`wire::MAX_HELLOS`].
    handshakes: Arc<Semaphore>,
    ops: mpsc::UnboundedSender<Op>,
    services: Services,
}

impl FrontBridge {
    /// The bridge of `identity` with `timing`; with `listener`, its connections come from
    /// there.
    pub(super) fn start(
        writer: Arc<Writer>,
        services: Services,
        identity: &WireIdentity,
        timing: &WireTiming,
        listener: Option<OwnedFd>,
    ) -> Result<Arc<FrontBridge>, String> {
        let cert = B64
            .decode(&identity.cert)
            .map_err(|_| "the bridge's certificate is not base64")?;
        let key = B64
            .decode(&identity.key)
            .map_err(|_| "the bridge's key is not base64")?;
        let identity = tinfirm_bridge::tls::Identity::from_der(cert, key)
            .map_err(|e| format!("the bridge's identity: {e:#}"))?;
        let (ops_tx, ops_rx) = mpsc::unbounded_channel();
        let bridge = Arc::new(FrontBridge {
            writer,
            acceptor: TlsAcceptor::from(identity.server_config()),
            timing: timing.host_timing(),
            allowed: watch::Sender::new(None),
            admissions: Mutex::new(HashMap::new()),
            live: Mutex::new(None),
            core: tokio::sync::Mutex::new(None),
            handshakes: Arc::new(Semaphore::new(wire::MAX_HELLOS)),
            ops: ops_tx,
            services,
        });
        tokio::spawn(run_ops(Arc::downgrade(&bridge), ops_rx));
        if let Some(fd) = listener {
            let listener = std::net::TcpListener::from(fd);
            let listener = listener
                .set_nonblocking(true)
                .and_then(|()| TcpListener::from_std(listener))
                .map_err(|e| format!("the bridge's listener: {e}"))?;
            tokio::spawn(accept_loop(bridge.clone(), listener));
        }
        Ok(bridge)
    }

    /// The address the sandbox's bridge connects from, from now on.
    pub(super) fn allow_peer(&self, ip: IpAddr) {
        self.allowed.send_replace(Some(ip.to_canonical()));
    }

    /// A connection to the sandbox's bridge that `run` dialed.
    pub(super) fn dialed(self: &Arc<Self>, fd: OwnedFd) -> Result<(), String> {
        let stream = std::net::TcpStream::from(fd);
        let stream = stream
            .set_nonblocking(true)
            .and_then(|()| TcpStream::from_std(stream))
            .map_err(|e| format!("a dialed connection: {e}"))?;
        tokio::spawn(self.clone().connection(stream, None));
        Ok(())
    }

    /// `run`'s decision on `hello` `req`. A connection that is gone meanwhile is reported as
    /// not attached, so that `run` knows the end of every `hello`.
    pub(super) fn admit(&self, req: u64, admission: Admission) {
        match lock(&self.admissions).remove(&req) {
            Some(waiting) => {
                if let Err(admission) = waiting.send(admission) {
                    drop(admission);
                    let _ = self.writer.send(&ToMain::Admitted {
                        req,
                        attached: false,
                    });
                }
            }
            None => {
                let _ = self.writer.send(&ToMain::Admitted {
                    req,
                    attached: false,
                });
            }
        }
    }

    /// One connection: the peer's address, the TLS handshake and the `hello`, `run`'s
    /// decision, and its applying.
    async fn connection(self: Arc<Self>, tcp: TcpStream, peer: Option<IpAddr>) {
        // A sandbox that opens more connections than these at once loses the others.
        let Ok(_place) = self.handshakes.clone().try_acquire_owned() else {
            return;
        };
        let handshake = self.timing.handshake;
        if let Some(peer) = peer {
            let mut allowed = self.allowed.subscribe();
            let known = tokio::time::timeout(handshake, allowed.wait_for(Option::is_some)).await;
            let allowed = match known {
                Ok(Ok(allowed)) => *allowed,
                _ => None,
            };
            // Closed before a byte of it is read.
            if allowed != Some(peer.to_canonical()) {
                return;
            }
        }
        let accepted =
            tokio::time::timeout(handshake, host::accept_hello(&self.acceptor, tcp)).await;
        let Ok(Ok((mut tls, hello))) = accepted else {
            return;
        };
        // A `hello` out of the wire's bounds never reaches `run`, which would end this front.
        let wire_hello = WireHello::of(&hello);
        if wire_hello.check().is_err() {
            host::refuse(
                &mut tls,
                tinfirm_bridge::control::RefuseCode::BadRequest,
                "the hello is out of bounds",
            )
            .await;
            return;
        }
        let (tx, rx) = oneshot::channel();
        let (req, sent) = self.writer.send_new(|req| {
            lock(&self.admissions).insert(req, tx);
            ToMain::Hello {
                req,
                hello: wire_hello,
            }
        });
        if sent.is_err() {
            lock(&self.admissions).remove(&req);
            return;
        }
        // `run` decides at once; the wait ends with the front.
        let Ok(admission) = rx.await else {
            return;
        };
        let attached = self.apply(tls, &hello, admission).await;
        let _ = self.writer.send(&ToMain::Admitted { req, attached });
    }

    /// Applies `run`'s decision: whether the session's link took the connection.
    async fn apply(
        self: &Arc<Self>,
        mut tls: TlsStream<TcpStream>,
        hello: &host::Hello,
        admission: Admission,
    ) -> bool {
        let link_timing = self.timing.link;
        match admission {
            Admission::Refuse { code, message } => {
                host::refuse(&mut tls, code, &message).await;
                false
            }
            Admission::New { session } => {
                if lock(&self.live).is_some() {
                    return false;
                }
                let (link, inbound) = link::spawn(REPLAY_BYTES, link_timing);
                if host::welcome(&mut tls, Some(session), 0).await.is_err()
                    || link.attach(Box::new(tls), hello.rx_seq).is_err()
                {
                    link.close();
                    return false;
                }
                self.established(link, inbound).await;
                true
            }
            Admission::Resume => {
                let Some(link) = lock(&self.live).as_ref().map(|live| live.link.clone()) else {
                    return false;
                };
                // No answer: the bridge tries again after its retry, instead of losing a
                // connection it was welcomed on.
                if !link.can_attach() {
                    return false;
                }
                let Ok(ours) = link.rx_seq().await else {
                    return false;
                };
                host::welcome(&mut tls, None, ours).await.is_ok()
                    && link.attach(Box::new(tls), hello.rx_seq).is_ok()
            }
            Admission::Reclaim { rx_after } => {
                if lock(&self.live).is_some()
                    || hello.rx_seq > link::MAX_SEQ
                    || rx_after > link::MAX_SEQ
                {
                    return false;
                }
                let (link, inbound) =
                    link::spawn_after(REPLAY_BYTES, link_timing, hello.rx_seq, rx_after);
                if host::welcome(&mut tls, None, rx_after).await.is_err()
                    || link.attach(Box::new(tls), hello.rx_seq).is_err()
                {
                    link.close();
                    return false;
                }
                self.established(link, inbound).await;
                true
            }
        }
    }

    /// The session of a link that took its first connection.
    async fn established(self: &Arc<Self>, link: LinkHandle, inbound: link::InboundReceiver) {
        let core = SessionCore::new(link.clone(), inbound, &self.timing);
        let exit = core.exit();
        let ended = core.ended();
        *lock(&self.live) = Some(Live {
            link,
            control: core.control(),
        });
        *self.core.lock().await = Some(core);
        tokio::spawn(report_ends(self.writer.clone(), exit, ended));
    }

    fn control(&self) -> Option<SessionControl> {
        lock(&self.live).as_ref().map(|live| live.control.clone())
    }

    pub(super) fn signal(&self, signal: Signal) {
        let _ = self.ops.send(Op::Signal(signal));
    }

    pub(super) fn stop(&self, grace_seconds: u64) {
        let _ = self.ops.send(Op::Stop(grace_seconds));
    }

    /// Starts the adapter, its stdio on `stdio` (stdin, stdout, stderr).
    pub(super) fn start_adapter(self: &Arc<Self>, id: u64, start: Start, stdio: [OwnedFd; 3]) {
        let bridge = self.clone();
        tokio::spawn(async move {
            let started = {
                let mut core = bridge.core.lock().await;
                match core.as_mut() {
                    Some(core) => core.start(start).await.map_err(|e| format!("{e:#}")),
                    None => Err(NOT_CONNECTED.to_string()),
                }
            };
            let reply = match started.and_then(|io| pump_stdio(io, stdio)) {
                Ok(()) => Reply::Done,
                Err(message) => failed(&message),
            };
            let _ = bridge.writer.send(&ToMain::Answer { id, reply });
        });
    }

    pub(super) fn reclaim(self: &Arc<Self>, id: u64, timeout: Duration) {
        let bridge = self.clone();
        tokio::spawn(async move {
            let reclaimed = {
                let mut core = bridge.core.lock().await;
                match core.as_mut() {
                    Some(core) => core.reclaim(timeout).await.map_err(|e| format!("{e:#}")),
                    None => Err(NOT_CONNECTED.to_string()),
                }
            };
            let reply = match reclaimed {
                Ok(running) => Reply::Reclaimed { running },
                Err(message) => failed(&message),
            };
            let _ = bridge.writer.send(&ToMain::Answer { id, reply });
        });
    }

    pub(super) fn close(self: &Arc<Self>, id: u64, timeout: Duration) {
        let (writer, control) = (self.writer.clone(), self.control());
        tokio::spawn(async move {
            let reply = match control {
                Some(control) => match control.close(timeout).await {
                    Ok(()) => Reply::Done,
                    Err(e) => failed(&format!("{e:#}")),
                },
                None => failed(NOT_CONNECTED),
            };
            let _ = writer.send(&ToMain::Answer { id, reply });
        });
    }

    pub(super) fn exec(self: &Arc<Self>, exec: Exec) {
        let (writer, control) = (self.writer.clone(), self.control());
        tokio::spawn(async move {
            let reply = match control {
                Some(control) => {
                    let argv: Vec<&str> = exec.argv.iter().map(String::as_str).collect();
                    let env: Vec<(&str, &str)> = exec
                        .env
                        .iter()
                        .map(|(k, v)| (k.as_str(), v.expose()))
                        .collect();
                    let ran = control
                        .files()
                        .exec(
                            &argv,
                            &env,
                            exec.cwd.as_deref(),
                            exec.stdin.clone(),
                            Duration::from_secs(exec.timeout_seconds.max(1)),
                        )
                        .await;
                    match ran {
                        Ok(out) => Reply::ExecDone {
                            code: out.code,
                            signal: out.signal,
                            stdout: B64.encode(cut(&out.stdout)),
                            stderr: B64.encode(cut(&out.stderr)),
                        },
                        Err(e) => failed(&format!("{e:#}")),
                    }
                }
                None => failed(NOT_CONNECTED),
            };
            let _ = writer.send(&ToMain::Answer { id: exec.id, reply });
        });
    }

    pub(super) fn probe(self: &Arc<Self>, id: u64, checks: Vec<ProbeCheck>, timeout: Duration) {
        let (writer, control) = (self.writer.clone(), self.control());
        tokio::spawn(async move {
            let reply = match control {
                Some(control) => match control.files().probe(checks, timeout).await {
                    Ok(mut open) => {
                        open.truncate(wire::MAX_PROBED);
                        for name in &mut open {
                            truncate(name, wire::MAX_PROBE_NAME);
                        }
                        Reply::Probed { open }
                    }
                    Err(e) => failed(&format!("{e:#}")),
                },
                None => failed(NOT_CONNECTED),
            };
            let _ = writer.send(&ToMain::Answer { id, reply });
        });
    }
}

impl FrontBridge {
    /// Puts the archive that comes on `socket` into the sandbox's directory `dir`.
    pub(super) fn put(self: &Arc<Self>, id: u64, dir: String, socket: OwnedFd, timeout: Duration) {
        let (writer, control) = (self.writer.clone(), self.control());
        tokio::spawn(async move {
            let socket = std::os::unix::net::UnixStream::from(socket);
            let socket = socket
                .set_nonblocking(true)
                .and_then(|()| UnixStream::from_std(socket));
            let reply = match (control, socket) {
                (Some(control), Ok(socket)) => {
                    match control.files().put_from(&dir, socket, timeout).await {
                        Ok(()) => Reply::Done,
                        Err(e) => failed(&format!("{e:#}")),
                    }
                }
                (None, _) => failed(NOT_CONNECTED),
                (_, Err(e)) => failed(&format!("the archive's socket: {e}")),
            };
            let _ = writer.send(&ToMain::Answer { id, reply });
        });
    }

    /// Writes the sandbox's archive of `paths` of `dir` on `pipe`, whose reader is a reader of
    /// `run`'s. A reader that stops reading (it refused, or it found what it needed) does not
    /// cut the sandbox's stream: the rest is read and dropped, as in `run`.
    pub(super) fn get(
        self: &Arc<Self>,
        id: u64,
        dir: String,
        paths: Vec<String>,
        exclude: Vec<String>,
        pipe: OwnedFd,
        timeout: Duration,
    ) {
        let (writer, control) = (self.writer.clone(), self.control());
        tokio::spawn(async move {
            let pipe = tokio::net::unix::pipe::Sender::from_owned_fd(pipe);
            let reply = match (control, pipe) {
                (Some(control), Ok(pipe)) => {
                    let into = Drain {
                        inner: pipe,
                        broken: false,
                    };
                    match control
                        .files()
                        .get_into(&dir, paths, exclude, into, timeout)
                        .await
                    {
                        Ok(()) => Reply::Done,
                        Err(e) => failed(&format!("{e:#}")),
                    }
                }
                (None, _) => failed(NOT_CONNECTED),
                (_, Err(e)) => failed(&format!("the archive's pipe: {e}")),
            };
            let _ = writer.send(&ToMain::Answer { id, reply });
        });
    }

    /// Opens the tunnels: each to the front's service it names, which must serve now.
    pub(super) fn open_tunnels(
        self: &Arc<Self>,
        id: u64,
        tunnels: Vec<WireTunnel>,
        timeout: Duration,
    ) {
        let bridge = self.clone();
        tokio::spawn(async move {
            let unserved = {
                let services = lock(&bridge.services);
                tunnels
                    .iter()
                    .find(|t| !services.contains_key(&t.service))
                    .map(|t| t.service)
            };
            let reply = match unserved {
                Some(service) => failed(&format!(
                    "a tunnel to a service the front does not serve ({service:?})"
                )),
                None => {
                    let ends: Vec<(std::net::SocketAddr, Arc<dyn TunnelEnd>)> = tunnels
                        .iter()
                        .map(|t| {
                            let end: Arc<dyn TunnelEnd> = Arc::new(ServiceEnd {
                                services: bridge.services.clone(),
                                service: t.service,
                            });
                            (t.listen, end)
                        })
                        .collect();
                    let mut core = bridge.core.lock().await;
                    match core.as_mut() {
                        Some(core) => match core.open_tunnels(&ends, timeout).await {
                            Ok(addrs) => Reply::Listening { addrs },
                            Err(e) => failed(&format!("{e:#}")),
                        },
                        None => failed(NOT_CONNECTED),
                    }
                }
            };
            let _ = bridge.writer.send(&ToMain::Answer { id, reply });
        });
    }
}

/// A tunnel's end at one of the front's services: each stream is a new connection handed to
/// the service, over a pipe in this process.
struct ServiceEnd {
    services: Services,
    service: Service,
}

/// How much a tunnel's connection to a service holds in each direction.
const SERVICE_PIPE: usize = 64 * 1024;

impl TunnelEnd for ServiceEnd {
    fn connect(
        &self,
    ) -> std::pin::Pin<
        Box<dyn std::future::Future<Output = std::io::Result<TunnelConnection>> + Send>,
    > {
        let handoff = lock(&self.services).get(&self.service).cloned();
        Box::pin(async move {
            let handoff = handoff.ok_or_else(|| {
                std::io::Error::new(
                    std::io::ErrorKind::NotConnected,
                    "the service does not serve any more",
                )
            })?;
            let (ours, theirs) = tokio::io::duplex(SERVICE_PIPE);
            handoff
                .hand(Box::new(theirs) as BoxConnection)
                .await
                .map_err(|_| {
                    std::io::Error::new(std::io::ErrorKind::NotConnected, "the service is gone")
                })?;
            let (reader, writer) = tokio::io::split(ours);
            Ok((
                Box::new(reader) as Box<dyn tokio::io::AsyncRead + Unpin + Send>,
                Box::new(writer) as Box<dyn tokio::io::AsyncWrite + Unpin + Send>,
            ))
        })
    }

    fn describe(&self) -> String {
        format!("the front's {:?} service", self.service)
    }
}

/// A writer to a reader's pipe that, once the reader has stopped reading, takes and drops
/// the rest.
struct Drain<W> {
    inner: W,
    broken: bool,
}

impl<W: tokio::io::AsyncWrite + Unpin> tokio::io::AsyncWrite for Drain<W> {
    fn poll_write(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        if self.broken {
            return std::task::Poll::Ready(Ok(buf.len()));
        }
        match std::pin::Pin::new(&mut self.inner).poll_write(cx, buf) {
            std::task::Poll::Ready(Err(_)) => {
                self.broken = true;
                std::task::Poll::Ready(Ok(buf.len()))
            }
            other => other,
        }
    }

    fn poll_flush(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.broken {
            return std::task::Poll::Ready(Ok(()));
        }
        std::pin::Pin::new(&mut self.inner)
            .poll_flush(cx)
            .map(|_| Ok(()))
    }

    fn poll_shutdown(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.broken {
            return std::task::Poll::Ready(Ok(()));
        }
        std::pin::Pin::new(&mut self.inner)
            .poll_shutdown(cx)
            .map(|_| Ok(()))
    }
}

const NOT_CONNECTED: &str = "the sandbox's bridge has not connected";

/// A command's output as the bridge gives it, within the wire's bound, as text.
fn cut(text: &str) -> &[u8] {
    let mut end = text.len().min(Exec::MAX_OUTPUT);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text.as_bytes()[..end]
}

fn truncate(text: &mut String, max: usize) {
    if text.len() > max {
        let mut end = max;
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        text.truncate(end);
    }
}

fn failed(message: &str) -> Reply {
    let mut message = message.to_string();
    truncate(&mut message, wire::MAX_TEXT);
    Reply::Failed { message }
}

/// Signals and stops, in the order `run` sent them, while the front lives.
async fn run_ops(bridge: std::sync::Weak<FrontBridge>, mut ops: mpsc::UnboundedReceiver<Op>) {
    while let Some(op) = ops.recv().await {
        let Some(control) = bridge.upgrade().and_then(|b| b.control()) else {
            continue;
        };
        let _ = match op {
            Op::Signal(signal) => control.signal(signal).await,
            Op::Stop(grace) => control.stop(Duration::from_secs(grace)).await,
        };
    }
}

/// Tells `run` that the adapter ended, then that the session did, in that order.
async fn report_ends(
    writer: Arc<Writer>,
    mut exit: watch::Receiver<Option<Exit>>,
    ended: tokio_util::sync::WaitForCancellationFutureOwned,
) {
    let mut said = false;
    let mut say = |exit: Option<Exit>| {
        if let (false, Some(Exit::Exited { code, signal })) = (said, exit) {
            said = true;
            let _ = writer.send(&ToMain::Exited { code, signal });
        }
    };
    tokio::pin!(ended);
    loop {
        tokio::select! {
            changed = exit.changed() => {
                if changed.is_err() {
                    break;
                }
                say(*exit.borrow_and_update());
            }
            () = &mut ended => break,
        }
    }
    say(*exit.borrow());
    let _ = writer.send(&ToMain::SessionEnded {});
}

/// The adapter's stdio on `run`'s sockets: what comes on stdin goes to the adapter, and its
/// output goes on stdout and stderr at `run`'s pace, so that a slow reader holds the sandbox
/// back through the bridge's credit, as in `run`.
fn pump_stdio(io: HostIo, stdio: [OwnedFd; 3]) -> Result<(), String> {
    let [stdin, stdout, stderr] = stdio.map(|fd| {
        let socket = std::os::unix::net::UnixStream::from(fd);
        socket
            .set_nonblocking(true)
            .and_then(|()| UnixStream::from_std(socket))
    });
    let (stdin, stdout, stderr) = match (stdin, stdout, stderr) {
        (Ok(a), Ok(b), Ok(c)) => (a, b, c),
        _ => return Err("the adapter's stdio sockets cannot be used".into()),
    };
    let HostIo {
        stdin: mut to_adapter,
        mut output,
        ..
    } = io;
    tokio::spawn(async move {
        let (mut read, _write) = stdin.into_split();
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            match read.read(&mut buffer).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if to_adapter.write_all(&buffer[..n]).await.is_err() {
                        break;
                    }
                }
            }
        }
        // The adapter's stdin ends with `run`'s.
        let _ = to_adapter.shutdown().await;
    });
    tokio::spawn(async move {
        let mut stdout = Some(stdout);
        let mut stderr = Some(stderr);
        while let Some(item) = output.recv().await {
            let (socket, bytes) = match item {
                Output::Stdout(bytes) => (&mut stdout, bytes),
                Output::Stderr(bytes) => (&mut stderr, bytes),
            };
            // A reader that is gone drops what is still written for it.
            if let Some(writer) = socket
                && writer.write_all(&bytes).await.is_err()
            {
                *socket = None;
            }
        }
        for socket in [stdout, stderr].into_iter().flatten() {
            let mut socket = socket;
            let _ = socket.shutdown().await;
        }
    });
    Ok(())
}

/// Accepts the sandbox's bridge's connections until the front ends.
async fn accept_loop(bridge: Arc<FrontBridge>, listener: TcpListener) {
    loop {
        match listener.accept().await {
            Ok((tcp, peer)) => {
                tokio::spawn(bridge.clone().connection(tcp, Some(peer.ip())));
            }
            Err(_) => tokio::time::sleep(Duration::from_millis(100)).await,
        }
    }
}
