//! The front's side of the control channel: its stdin, a Unix stream socket whose other end
//! is `run`'s ([`super::wire`]).
//!
//! The front is confined already when this starts. It says so first ([`ToMain::Ready`]),
//! then builds its tokio runtime and reads `run`'s messages on a thread of their own until
//! `run` closes its end, and then exits; SIGTERM does not end it ([`serve`]). One dispatcher
//! ([`Front::dispatch`]) hands each message, in order, to the handler of its service, which
//! keeps that service's state:
//! - the egress proxy, served on the runtime ([`crate::egress_proxy::serve`]): each connection
//!   it needs is asked of `run` ([`ToMain::Connect`]) and given back as a descriptor or
//!   refused;
//! - the model proxy, and a run's git proxy and platform tools, served there too
//!   ([`crate::model_proxy::serve`], [`crate::git_proxy::serve_in_front`]): each request they
//!   let through goes to `run` ([`ToMain::Request`]), its body and its answer's body on a
//!   socket `run` gives for it, and `run`'s answers to them come back to their handler;
//! - the sandbox's bridge, its files and its tunnels ([`super::bridge::FrontBridge`]).
//!
//! The services are reached through the bridge's tunnels, and, for the tests of the front's
//! services, on a listener that `run` passes with the message that sets the service up. The
//! front's frames go out through one writer, under a lock, so they never interleave, and a new
//! request takes its id under that lock, so the ids reach `run` in the order they grow. A
//! message that is not one of the wire's, that breaks its bounds, that wants a descriptor that
//! did not come with it, that comes before `init` or a second time, or that answers a request
//! the front did not make ends the front with exit code 3: `run`'s messages are trusted, so
//! such a message means a fault, and the front does not guess.

use std::collections::{HashMap, VecDeque};
use std::io::IoSliceMut;
use std::os::fd::{AsFd, OwnedFd};
use std::process::ExitCode;
use std::sync::Arc;

use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};

use hyper::body::{Bytes, Frame};
use tokio::sync::oneshot;

use crate::confine::Status;

use super::head::{Backend, BoxError, Declined, ProxyBody, RequestHead, ResponseHead};
use super::wire::{self, CHECK_REPLY, MAX_TO_FRONT, Service, ToFront, ToMain};
use crate::egress_proxy::{Connector, FrontHalf, Granted, Refusal};
use crate::model_proxy::proxy_error;

/// How much a read takes at most.
const READ: usize = 64 * 1024;
/// The most descriptors one read may bring.
const DESCRIPTORS_PER_READ: usize = 8;

/// The control channel, as the front reads it: bytes, and the descriptors that came with them,
/// in order.
struct Channel {
    socket: OwnedFd,
    bytes: Vec<u8>,
    descriptors: VecDeque<OwnedFd>,
}

impl Channel {
    /// Reads once more: `false` at the end of the channel.
    fn read(&mut self) -> std::io::Result<bool> {
        use rustix::net::{RecvAncillaryBuffer, RecvAncillaryMessage, recvmsg};
        let mut buffer = vec![0u8; READ];
        let mut space =
            [std::mem::MaybeUninit::uninit(); rustix::cmsg_space!(ScmRights(DESCRIPTORS_PER_READ))];
        let mut control = RecvAncillaryBuffer::new(&mut space);
        let received = loop {
            match recvmsg(
                &self.socket,
                &mut [IoSliceMut::new(&mut buffer)],
                &mut control,
                wire::RECV_FLAGS,
            ) {
                Ok(received) => break received,
                Err(rustix::io::Errno::INTR) => continue,
                Err(e) => return Err(e.into()),
            }
        };
        for message in control.drain() {
            if let RecvAncillaryMessage::ScmRights(fds) = message {
                for fd in fds {
                    // On an error the rest of `fds` and `fd` are dropped, and so closed.
                    wire::close_on_exec(&fd)?;
                    self.descriptors.push_back(fd);
                }
            }
        }
        if received.bytes == 0 {
            return Ok(false);
        }
        self.bytes.extend_from_slice(&buffer[..received.bytes]);
        Ok(true)
    }

    /// The next message: `Ok(None)` at the end of the channel.
    fn next(&mut self) -> Result<Option<ToFront>, String> {
        loop {
            if self.bytes.len() >= wire::HEADER {
                let header: [u8; wire::HEADER] =
                    self.bytes[..wire::HEADER].try_into().expect("checked");
                let length = wire::frame_length(header, MAX_TO_FRONT).map_err(|e| e.to_string())?;
                if self.bytes.len() >= wire::HEADER + length {
                    let payload: Vec<u8> = self
                        .bytes
                        .drain(..wire::HEADER + length)
                        .skip(wire::HEADER)
                        .collect();
                    return wire::decode_to_front(&payload)
                        .map(Some)
                        .map_err(|e| e.to_string());
                }
            }
            match self.read() {
                Ok(true) => {}
                Ok(false) if self.bytes.is_empty() => return Ok(None),
                Ok(false) => return Err("the channel ended within a frame".into()),
                Err(e) => return Err(format!("cannot read the control channel: {e}")),
            }
        }
    }
}

/// The front's side of writing to `run`: whole frames, one at a time, from any thread.
pub(super) struct Writer {
    socket: OwnedFd,
    /// Held while a frame is written; it holds the last request id the front used.
    lock: std::sync::Mutex<u64>,
}

impl Writer {
    pub(super) fn send(&self, message: &ToMain) -> Result<(), String> {
        let frame = wire::encode(message, message.max_frame()).map_err(|e| e.to_string())?;
        let _one = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        wire::send_frame(self.socket.as_fd(), &frame, &[])
            .map_err(|e| format!("cannot write the control channel: {e}"))
    }

    /// Sends a new request, `make` of the next id: the id is taken under the lock the frame
    /// is written under, so that ids reach `run` in the order they grow, whichever of the
    /// runtime's threads makes them. The id comes back with whether the frame went.
    pub(super) fn send_new(&self, make: impl FnOnce(u64) -> ToMain) -> (u64, Result<(), String>) {
        let mut last = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        *last += 1;
        let req = *last;
        let message = make(req);
        let sent = wire::encode(&message, message.max_frame())
            .map_err(|e| e.to_string())
            .and_then(|frame| {
                wire::send_frame(self.socket.as_fd(), &frame, &[])
                    .map_err(|e| format!("cannot write the control channel: {e}"))
            });
        (req, sent)
    }
}

/// The front's connections for the egress proxy: each destination is asked of `run`
/// ([`ToMain::Connect`]), which connects and hands the connection's descriptor over, or
/// refuses. The front tells `run` when a connection it was given has ended.
struct WireConnector {
    writer: Arc<Writer>,
    waiting: std::sync::Mutex<HashMap<u64, Answer>>,
}

type Answer = tokio::sync::oneshot::Sender<Result<Given, Refusal>>;

/// A connection `run` gave, with the notice that it ended. Whoever drops it, the client's
/// handler or the answer channel when the client left before the answer came, `run` hears
/// of it: a connection the front was given always gives its place back.
struct Given {
    fd: OwnedFd,
    notice: ClosedNotice,
}

/// Tells `run` that the connection given for `req` has ended, when it is dropped.
struct ClosedNotice {
    writer: Arc<Writer>,
    req: u64,
}

impl Drop for ClosedNotice {
    fn drop(&mut self) {
        let _ = self.writer.send(&ToMain::Closed { req: self.req });
    }
}

impl WireConnector {
    /// `run`'s answer to `req`; `false` when no request of that id waits.
    ///
    /// A connection is wrapped with its [`ClosedNotice`] at once, before it is handed on: if
    /// the client left while `run` connected, the handler is gone, the send fails, and the
    /// dropped answer closes the descriptor and tells `run`.
    fn answer(&self, req: u64, answer: Result<OwnedFd, Refusal>) -> bool {
        let waiting = self
            .waiting
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&req);
        match waiting {
            Some(tx) => {
                let answer = answer.map(|fd| Given {
                    fd,
                    notice: ClosedNotice {
                        writer: self.writer.clone(),
                        req,
                    },
                });
                drop(tx.send(answer));
                true
            }
            None => false,
        }
    }
}

impl Connector for WireConnector {
    fn connect(
        &self,
        host: String,
        port: u16,
    ) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Granted, Refusal>> + Send>> {
        // The proxy's HTTP side hands on whatever host and port a client's request parses to:
        // the port 0, no host, a host longer than a DNS name. `run` ends a front that asks it
        // for such a destination, so the client is answered here, with the answer the broker
        // has for it.
        if wire::check_destination(&host, port).is_err() {
            return Box::pin(async { Err(Refusal::bad_destination()) });
        }
        let (tx, rx) = tokio::sync::oneshot::channel();
        let (req, sent) = self.writer.send_new(|req| {
            self.waiting
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(req, tx);
            ToMain::Connect { req, host, port }
        });
        if sent.is_err() {
            // `run` never heard of it, so it waits for no answer.
            self.waiting
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&req);
        }
        Box::pin(async move {
            let gone = || Refusal {
                status: 502,
                message: "tinfirm egress proxy: the runner host did not answer\n".into(),
                off_allowlist: false,
            };
            sent.map_err(|_| gone())?;
            // The notice goes with the descriptor from here on: a descriptor that cannot be
            // used is closed and its place given back.
            let Given { fd, notice } = rx.await.map_err(|_| gone())??;
            let stream = std::net::TcpStream::from(fd);
            let stream = stream
                .set_nonblocking(true)
                .and_then(|()| tokio::net::TcpStream::from_std(stream))
                .map_err(|_| gone())?;
            Ok(Granted {
                stream,
                held: Box::new(notice),
            })
        })
    }
}

/// The front's way to the run halves of its listeners (the model proxy's, the git proxy's and
/// the platform tools'): each request's head goes to `run` ([`ToMain::Request`]), its body on
/// the socket `run` gives for it ([`ToFront::Body`]), and the answer comes back the same way.
/// One for the front: `run`'s answers name the request, whichever listener it came from.
#[derive(Clone)]
struct RequestWire(Arc<WireInner>);

struct WireInner {
    writer: Arc<Writer>,
    waiting: std::sync::Mutex<HashMap<u64, Waiting>>,
}

/// One service's side of the [`RequestWire`]: at most [`wire::MAX_SERVICE_REQUESTS`] of its
/// requests are open at `run` at once, from the request to `run`'s end of it; more wait here.
struct ServiceWire {
    wire: RequestWire,
    service: Service,
    open: Arc<tokio::sync::Semaphore>,
}

impl ServiceWire {
    fn new(wire: &RequestWire, service: Service) -> ServiceWire {
        ServiceWire {
            wire: wire.clone(),
            service,
            open: Arc::new(tokio::sync::Semaphore::new(wire::MAX_SERVICE_REQUESTS)),
        }
    }
}

/// A request that `run` has not ended yet.
struct Waiting {
    body: Option<oneshot::Sender<OwnedFd>>,
    response: Option<oneshot::Sender<ResponseHead>>,
    end: Option<oneshot::Sender<bool>>,
    /// Set when `run` ended the request: no abort goes then.
    ended: Arc<AtomicBool>,
    _place: tokio::sync::OwnedSemaphorePermit,
}

/// Tells `run` to end a request that the front gave up (its client left, or its body broke),
/// when it is dropped before `run` ended the request. It goes with the request's handler and
/// then with the answer's body, so whatever drops them, `run` hears of it.
struct AbortNotice {
    writer: Arc<Writer>,
    req: u64,
    ended: Arc<AtomicBool>,
}

impl Drop for AbortNotice {
    fn drop(&mut self) {
        if !self.ended.load(Ordering::SeqCst) {
            let _ = self.writer.send(&ToMain::Abort { req: self.req });
        }
    }
}

impl RequestWire {
    fn waiting<T>(&self, req: u64, take: impl FnOnce(&mut Waiting) -> Option<T>) -> Option<T> {
        let mut waiting = self.0.waiting.lock().unwrap_or_else(|e| e.into_inner());
        waiting.get_mut(&req).and_then(take)
    }

    /// The socket of `req`: `false` when no request of that id waits for one.
    fn body(&self, req: u64, fd: OwnedFd) -> bool {
        match self.waiting(req, |w| w.body.take()) {
            Some(tx) => {
                // A client that left drops the receiver; the socket closes with the send.
                drop(tx.send(fd));
                true
            }
            None => false,
        }
    }

    /// The answer's head of `req`: `false` when no request of that id waits for one.
    fn response(&self, req: u64, head: ResponseHead) -> bool {
        match self.waiting(req, |w| w.response.take()) {
            Some(tx) => {
                drop(tx.send(head));
                true
            }
            None => false,
        }
    }

    /// `run`'s end of `req`: `false` when no request of that id is open.
    fn end(&self, req: u64, ok: bool) -> bool {
        let waiting = self
            .0
            .waiting
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&req);
        match waiting {
            Some(mut waiting) => {
                waiting.ended.store(true, Ordering::SeqCst);
                if let Some(end) = waiting.end.take() {
                    let _ = end.send(ok);
                }
                true
            }
            None => false,
        }
    }
}

impl Backend for ServiceWire {
    fn call(
        &self,
        head: RequestHead,
        body: ProxyBody,
    ) -> Pin<Box<dyn Future<Output = (ResponseHead, ProxyBody)> + Send>> {
        let inner = self.wire.0.clone();
        let open = self.open.clone();
        let service = self.service;
        Box::pin(async move {
            let gone = || match service {
                Service::Model => proxy_error(
                    hyper::StatusCode::BAD_GATEWAY,
                    "the Tinfirm model proxy's runner host did not answer",
                ),
                _ => crate::git_proxy::text_answer(
                    hyper::StatusCode::BAD_GATEWAY,
                    "the runner host did not answer",
                ),
            };
            let Ok(place) = open.acquire_owned().await else {
                return gone();
            };
            let (body_tx, body_rx) = oneshot::channel();
            let (response_tx, response_rx) = oneshot::channel();
            let (end_tx, end_rx) = oneshot::channel();
            let ended = Arc::new(AtomicBool::new(false));
            let (req, sent) = inner.writer.send_new(|req| {
                inner
                    .waiting
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .insert(
                        req,
                        Waiting {
                            body: Some(body_tx),
                            response: Some(response_tx),
                            end: Some(end_tx),
                            ended: ended.clone(),
                            _place: place,
                        },
                    );
                ToMain::Request { req, head }
            });
            if sent.is_err() {
                inner
                    .waiting
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove(&req);
                return gone();
            }
            let notice = AbortNotice {
                writer: inner.writer.clone(),
                req,
                ended,
            };
            let Ok(fd) = body_rx.await else {
                return gone();
            };
            let socket = std::os::unix::net::UnixStream::from(fd);
            let Ok(socket) = socket
                .set_nonblocking(true)
                .and_then(|()| tokio::net::UnixStream::from_std(socket))
            else {
                return gone();
            };
            let (read, mut write) = socket.into_split();
            // The body goes while the answer may come: `run` may answer before it read it all.
            let head = {
                let copy = copy_request(body, &mut write, &inner.writer, req);
                tokio::pin!(copy);
                let mut copied = false;
                let mut response_rx = response_rx;
                loop {
                    tokio::select! {
                        copy = &mut copy, if !copied => {
                            copied = true;
                            if copy == Copy::ClientBroke {
                                // `run` waits for a body that will not end: it ends the request.
                                let _ = inner.writer.send(&ToMain::Abort { req });
                            }
                            // A socket `run` closed: it answered without the rest of the body.
                        }
                        head = &mut response_rx => break head,
                    }
                }
            };
            drop(write);
            let Ok(head) = head else {
                return gone();
            };
            (head, answer_body(read, end_rx, notice))
        })
    }

    fn declined(&self, why: Declined) {
        let _ = self.wire.0.writer.send(&ToMain::Declined {
            service: self.service,
            why,
        });
    }
}

/// How the copy of a request body ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Copy {
    Whole,
    /// The client's body broke (it left, or its framing did).
    ClientBroke,
    /// `run` closed the socket: it answered already.
    SocketClosed,
}

/// Writes the client's request body on the request's socket, then shuts it down and tells
/// `run` the body is whole.
async fn copy_request(
    mut body: ProxyBody,
    write: &mut tokio::net::unix::OwnedWriteHalf,
    writer: &Writer,
    req: u64,
) -> Copy {
    use http_body_util::BodyExt;
    use tokio::io::AsyncWriteExt;
    while let Some(frame) = body.frame().await {
        let Ok(frame) = frame else {
            return Copy::ClientBroke;
        };
        if let Ok(data) = frame.into_data()
            && write.write_all(&data).await.is_err()
        {
            return Copy::SocketClosed;
        }
    }
    if write.shutdown().await.is_err() {
        return Copy::SocketClosed;
    }
    match writer.send(&ToMain::BodyEnd { req }) {
        Ok(()) => Copy::Whole,
        Err(_) => Copy::SocketClosed,
    }
}

/// The answer's body from the request's socket: whole only when `run` ends the request with
/// `ok` after the socket's end; otherwise an error, which cuts the client's answer off.
fn answer_body(
    read: tokio::net::unix::OwnedReadHalf,
    end: oneshot::Receiver<bool>,
    notice: AbortNotice,
) -> ProxyBody {
    use http_body_util::BodyExt;
    use tokio::io::AsyncReadExt;
    let stream = futures::stream::unfold(Some((read, end, notice)), |state| async move {
        let (mut read, end, notice) = state?;
        let mut buffer = vec![0u8; READ];
        match read.read(&mut buffer).await {
            Ok(n) if n > 0 => {
                buffer.truncate(n);
                Some((
                    Ok(Frame::data(Bytes::from(buffer))),
                    Some((read, end, notice)),
                ))
            }
            // The socket's end, or its reset when `run` closed it with bytes of the request
            // unread (it answered without them): `run`'s end says whether the answer is whole.
            _ => match end.await {
                Ok(true) => {
                    drop(notice);
                    None
                }
                _ => {
                    // What came before the cut goes out first, as when this process is `run`:
                    // the server writes what it holds while the body waits, not after an error.
                    tokio::task::yield_now().await;
                    Some((
                        Err::<Frame<Bytes>, BoxError>(
                            "the model gateway's answer was cut off".into(),
                        ),
                        None,
                    ))
                }
            },
        }
    });
    http_body_util::StreamBody::new(stream).boxed_unsync()
}

/// Serves the control channel until `run` closes it: the front's own messages in this thread's
/// order ([`Front::dispatch`]), and the services' HTTP and the bridge in its runtime.
pub fn serve(status: Status) -> ExitCode {
    let socket = match std::io::stdin().as_fd().try_clone_to_owned() {
        Ok(socket) => socket,
        Err(e) => return super::fail(&format!("no control channel on stdin: {e}")),
    };
    let writer = match socket.try_clone() {
        Ok(fd) => Arc::new(Writer {
            socket: fd,
            lock: std::sync::Mutex::new(0),
        }),
        Err(e) => return super::fail(&format!("no control channel on stdin: {e}")),
    };
    if let Err(e) = writer.send(&ToMain::Ready { status }) {
        return super::fail(&format!(
            "{e} (stdin must be the control channel `run` opens)"
        ));
    }
    // The runtime's threads inherit the confinement, which the front applied already.
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(super::FRONT_WORKERS)
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(e) => return super::fail(&format!("cannot build its runtime: {e}")),
    };
    let mut messages = read_messages(socket);
    runtime.block_on(async move {
        // A front ends with its channel, or with the kill of its group, and not on SIGTERM. A
        // service manager that stops the host may signal every process of the service, the
        // fronts with `run` (systemd's default, `KillMode=control-group`), and `run` then
        // still needs each front to stop its run's agent and to bring the work back. The
        // listener replaces the signal's default action for the life of the process; nothing
        // reads it. Where it cannot be made, the signal ends the front as before.
        let _sigterm = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate());
        let mut init = false;
        let mut front = Front::new(writer);
        while let Some(item) = messages.recv().await {
            let (message, descriptors) = match item {
                Ok(item) => item,
                Err(e) => return refuse(&e),
            };
            match message {
                ToFront::Init { .. } if init => return refuse("a second init"),
                ToFront::Init { .. } => {
                    init = true;
                    continue;
                }
                _ if !init => return refuse("a message before init"),
                _ => {}
            }
            match front.dispatch(message, descriptors) {
                Ok(Some(answer)) => {
                    if let Err(e) = front.writer.send(&answer) {
                        return super::fail(&e);
                    }
                }
                Ok(None) => {}
                Err(why) => return refuse(&why),
            }
        }
        front.end();
        ExitCode::SUCCESS
    })
}

/// `run`'s messages, read on a thread of their own, in order, each with its descriptors: the
/// channel ends with `run`'s end, or with the first message that cannot be read, which comes
/// as an error.
fn read_messages(
    socket: OwnedFd,
) -> tokio::sync::mpsc::Receiver<Result<(ToFront, Vec<OwnedFd>), String>> {
    let (tx, rx) = tokio::sync::mpsc::channel::<Result<(ToFront, Vec<OwnedFd>), String>>(64);
    std::thread::spawn(move || {
        let mut channel = Channel {
            socket,
            bytes: Vec::new(),
            descriptors: VecDeque::new(),
        };
        loop {
            let item = match channel.next() {
                Ok(Some(message)) => {
                    let wanted = message.descriptors();
                    if channel.descriptors.len() < wanted {
                        let _ = tx.blocking_send(Err(
                            "a message came without its descriptor".to_string()
                        ));
                        return;
                    }
                    let descriptors: Vec<OwnedFd> = channel.descriptors.drain(..wanted).collect();
                    Ok((message, descriptors))
                }
                Ok(None) => return,
                Err(e) => Err(e),
            };
            let end = item.is_err();
            if tx.blocking_send(item).is_err() || end {
                return;
            }
        }
    });
    rx
}

/// What a handler makes of one of `run`'s messages: the answer to write, if any; an error ends
/// the front ([`refuse`]).
type Handled = Result<Option<ToMain>, String>;

/// The front as `run`'s messages set it up after `init`: one handler per service, each with its
/// own state, and what they share, which each is given explicitly: the writer of the control
/// channel, the services that the bridge's tunnels reach ([`super::bridge::Services`]), and the
/// way of the model proxy's, the git proxy's and the platform tools' requests to `run`.
struct Front {
    writer: Arc<Writer>,
    services: super::bridge::Services,
    requests: RequestWire,
    egress: EgressService,
    model: ModelService,
    git: GitService,
    bridge: BridgeService,
}

impl Front {
    fn new(writer: Arc<Writer>) -> Front {
        Front {
            requests: RequestWire(Arc::new(WireInner {
                writer: writer.clone(),
                waiting: std::sync::Mutex::new(HashMap::new()),
            })),
            writer,
            services: Default::default(),
            egress: EgressService::default(),
            model: ModelService::default(),
            git: GitService::default(),
            bridge: BridgeService::default(),
        }
    }

    /// Hands each message after `init` to the handler of its service.
    fn dispatch(&mut self, message: ToFront, mut descriptors: Vec<OwnedFd>) -> Handled {
        let Front {
            writer,
            services,
            requests,
            egress,
            model,
            git,
            bridge,
        } = self;
        match message {
            ToFront::Init { .. } => Err("a second init".to_string()),
            ToFront::Ping { id } => Ok(Some(ToMain::Pong { id })),
            ToFront::Check { id } => check(id, &mut descriptors),
            ToFront::ListenEgress {
                authorization,
                listener,
            } => egress.listen(writer, services, authorization, listener, &mut descriptors),
            ToFront::Connected { req } => egress.connected(req, &mut descriptors),
            ToFront::Refused {
                req,
                status,
                message,
                off_allowlist,
            } => egress.refused(
                req,
                Refusal {
                    status,
                    message,
                    off_allowlist,
                },
            ),
            ToFront::ListenModel {
                secret,
                api,
                base_path,
                listener,
            } => model.listen(
                requests,
                services,
                (secret, api, base_path),
                listener,
                &mut descriptors,
            ),
            ToFront::ListenGit { limits, listener } => {
                git.listen(requests, services, limits, listener, &mut descriptors)
            }
            ToFront::GitRoutes { routes, mcp } => git.routes(routes, mcp),
            ToFront::Unlisten { service } => {
                lock(services).remove(&service);
                match service {
                    Service::Egress => egress.shutdown.cancel(),
                    Service::Model => model.shutdown.cancel(),
                    Service::Git => git.shutdown.cancel(),
                }
                Ok(None)
            }
            ToFront::Body { req } => request_body(requests, req, &mut descriptors),
            ToFront::Response { req, head } => request_response(requests, req, head),
            ToFront::End { req, ok } => request_end(requests, req, ok),
            ToFront::BridgeInit {
                identity,
                timing,
                listen,
            } => bridge.init(
                writer,
                services,
                &identity,
                &timing,
                listen,
                &mut descriptors,
            ),
            ToFront::AllowPeer { ip } => bridge.allow_peer(ip),
            ToFront::Dialed {} => bridge.dialed(&mut descriptors),
            ToFront::Admit { req, admission } => bridge.admit(req, admission),
            ToFront::Start { id, start } => bridge.start(id, start, &mut descriptors),
            ToFront::Signal { signal } => bridge.signal(signal),
            ToFront::Stop { grace_seconds } => bridge.stop(grace_seconds),
            ToFront::StdinEnd {} => bridge.stdin_end(),
            ToFront::Reclaim { id, timeout_ms } => bridge.reclaim(id, timeout_ms),
            ToFront::Close { id, timeout_ms } => bridge.close(id, timeout_ms),
            ToFront::Exec { exec } => bridge.exec(exec),
            ToFront::Probe {
                id,
                checks,
                timeout_ms,
            } => bridge.probe(id, checks, timeout_ms),
            ToFront::Put {
                id,
                dir,
                timeout_ms,
            } => files_put(bridge, id, dir, timeout_ms, &mut descriptors),
            ToFront::Get {
                id,
                dir,
                paths,
                exclude,
                timeout_ms,
            } => files_get(
                bridge,
                id,
                dir,
                paths,
                exclude,
                timeout_ms,
                &mut descriptors,
            ),
            ToFront::OpenTunnels {
                id,
                tunnels,
                timeout_ms,
            } => open_tunnels(bridge, id, tunnels, timeout_ms),
        }
    }

    /// `run` closed its end: every service stops.
    fn end(&self) {
        self.egress.shutdown.cancel();
        self.model.shutdown.cancel();
        self.git.shutdown.cancel();
    }
}

fn lock<T>(m: &std::sync::Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// The check that descriptors arrive: [`CHECK_REPLY`] on the one that came, then it closes.
fn check(id: u64, descriptors: &mut Vec<OwnedFd>) -> Handled {
    let fd = descriptors.pop().expect("taken with the message");
    let mut file = std::fs::File::from(fd);
    if std::io::Write::write_all(&mut file, CHECK_REPLY).is_err() {
        return Err("cannot write on the descriptor of a check".to_string());
    }
    drop(file);
    Ok(Some(ToMain::Checked { id }))
}

/// The egress proxy's HTTP, once in the front's life, and the connections `run` makes for it.
#[derive(Default)]
struct EgressService {
    connector: Option<Arc<WireConnector>>,
    shutdown: tokio_util::sync::CancellationToken,
}

impl EgressService {
    fn listen(
        &mut self,
        writer: &Arc<Writer>,
        services: &super::bridge::Services,
        authorization: String,
        listener: bool,
        descriptors: &mut Vec<OwnedFd>,
    ) -> Handled {
        if self.connector.is_some() {
            return Err("a second egress listener".to_string());
        }
        let listener = take_listener(descriptors, listener)
            .map_err(|e| format!("the egress listener: {e}"))?;
        let connector = Arc::new(WireConnector {
            writer: writer.clone(),
            waiting: std::sync::Mutex::new(HashMap::new()),
        });
        self.connector = Some(connector.clone());
        let half = FrontHalf::new(authorization, connector, self.shutdown.clone());
        let (acceptor, handoff) = crate::front::conn::Acceptor::new(listener, false);
        lock(services).insert(Service::Egress, handoff);
        tokio::spawn(crate::egress_proxy::serve(acceptor, half));
        Ok(None)
    }

    /// The connection `run` made for `req`.
    fn connected(&self, req: u64, descriptors: &mut Vec<OwnedFd>) -> Handled {
        let fd = descriptors.pop().expect("taken with the message");
        if !self
            .connector
            .as_ref()
            .is_some_and(|e| e.answer(req, Ok(fd)))
        {
            return Err("a connection for no request of this front".to_string());
        }
        Ok(None)
    }

    /// `run` refused `req`.
    fn refused(&self, req: u64, refusal: Refusal) -> Handled {
        if !self
            .connector
            .as_ref()
            .is_some_and(|e| e.answer(req, Err(refusal)))
        {
            return Err("a refusal of no request of this front".to_string());
        }
        Ok(None)
    }
}

/// The model proxy's HTTP, once in the front's life; its requests go to `run`.
#[derive(Default)]
struct ModelService {
    serving: bool,
    shutdown: tokio_util::sync::CancellationToken,
}

impl ModelService {
    /// Serves the run of `secret`, with the format `api` and the base path `base_path`.
    fn listen(
        &mut self,
        requests: &RequestWire,
        services: &super::bridge::Services,
        (secret, api, base_path): (String, crate::model_proxy::ModelApi, String),
        listener: bool,
        descriptors: &mut Vec<OwnedFd>,
    ) -> Handled {
        if self.serving {
            return Err("a second model listener".to_string());
        }
        let listener =
            take_listener(descriptors, listener).map_err(|e| format!("the model listener: {e}"))?;
        self.serving = true;
        let half = crate::model_proxy::FrontHalf::new(
            secret,
            api,
            base_path,
            Arc::new(ServiceWire::new(requests, Service::Model)),
            self.shutdown.clone(),
        );
        let (acceptor, handoff) = crate::front::conn::Acceptor::new(listener, true);
        lock(services).insert(Service::Model, handoff);
        tokio::spawn(crate::model_proxy::serve(acceptor, half));
        Ok(None)
    }
}

/// A run's git proxy fetches and platform tools, once in the front's life, for the routes `run`
/// gives; their requests go to `run`.
#[derive(Default)]
struct GitService {
    routes: Option<Arc<crate::git_proxy::FrontRoutes>>,
    shutdown: tokio_util::sync::CancellationToken,
}

impl GitService {
    fn listen(
        &mut self,
        requests: &RequestWire,
        services: &super::bridge::Services,
        limits: wire::FrontLimits,
        listener: bool,
        descriptors: &mut Vec<OwnedFd>,
    ) -> Handled {
        if self.routes.is_some() {
            return Err("a second git listener".to_string());
        }
        let listener =
            take_listener(descriptors, listener).map_err(|e| format!("the git listener: {e}"))?;
        let routes = Arc::new(crate::git_proxy::FrontRoutes::default());
        self.routes = Some(routes.clone());
        let (acceptor, handoff) = crate::front::conn::Acceptor::new(listener, true);
        lock(services).insert(Service::Git, handoff);
        tokio::spawn(crate::git_proxy::serve_in_front(
            acceptor,
            routes,
            Arc::new(ServiceWire::new(requests, Service::Git)),
            limits,
            self.shutdown.clone(),
        ));
        Ok(None)
    }

    /// The run's routes as they are now.
    fn routes(&self, routes: Vec<wire::GitRouteSpec>, mcp: Vec<wire::McpRouteSpec>) -> Handled {
        let Some(table) = &self.routes else {
            return Err("routes for no git listener".to_string());
        };
        table.replace(routes, mcp);
        Ok(None)
    }
}

/// `run`'s socket for the body of request `req` of the model proxy, the git proxy or the
/// platform tools.
fn request_body(requests: &RequestWire, req: u64, descriptors: &mut Vec<OwnedFd>) -> Handled {
    let fd = descriptors.pop().expect("taken with the message");
    if !requests.body(req, fd) {
        return Err("a socket for no request of this front".to_string());
    }
    Ok(None)
}

/// `run`'s answer's head for `req`.
fn request_response(requests: &RequestWire, req: u64, head: ResponseHead) -> Handled {
    if !requests.response(req, head) {
        return Err("an answer to no request of this front".to_string());
    }
    Ok(None)
}

/// `run`'s end of `req`.
fn request_end(requests: &RequestWire, req: u64, ok: bool) -> Handled {
    if !requests.end(req, ok) {
        return Err("an end of no request of this front".to_string());
    }
    Ok(None)
}

/// The sandbox's bridge, once in the front's life ([`super::bridge::FrontBridge`]), and `run`'s
/// requests to it about its link and its adapter.
#[derive(Default)]
struct BridgeService {
    bridge: Option<Arc<super::bridge::FrontBridge>>,
}

impl BridgeService {
    /// The bridge, for a message that names `what` it is.
    fn of(&self, what: &str) -> Result<&Arc<super::bridge::FrontBridge>, String> {
        self.bridge
            .as_ref()
            .ok_or_else(|| format!("{what} for no bridge"))
    }

    fn init(
        &mut self,
        writer: &Arc<Writer>,
        services: &super::bridge::Services,
        identity: &wire::WireIdentity,
        timing: &wire::WireTiming,
        listen: bool,
        descriptors: &mut Vec<OwnedFd>,
    ) -> Handled {
        if self.bridge.is_some() {
            return Err("a second bridge".to_string());
        }
        let listener = listen.then(|| descriptors.pop().expect("taken with the message"));
        let started = super::bridge::FrontBridge::start(
            writer.clone(),
            services.clone(),
            identity,
            timing,
            listener,
        )?;
        self.bridge = Some(started);
        Ok(None)
    }

    fn allow_peer(&self, ip: std::net::IpAddr) -> Handled {
        self.of("a peer")?.allow_peer(ip);
        Ok(None)
    }

    fn dialed(&self, descriptors: &mut Vec<OwnedFd>) -> Handled {
        let fd = descriptors.pop().expect("taken with the message");
        self.of("a connection")?.dialed(fd)?;
        Ok(None)
    }

    fn admit(&self, req: u64, admission: wire::Admission) -> Handled {
        self.of("an admission")?.admit(req, admission);
        Ok(None)
    }

    fn start(
        &self,
        id: u64,
        start: tinfirm_bridge::control::Start,
        descriptors: &mut Vec<OwnedFd>,
    ) -> Handled {
        let bridge = self.of("a start")?;
        let mut stdio = descriptors.drain(..);
        let (Some(stdin), Some(stdout), Some(stderr)) = (stdio.next(), stdio.next(), stdio.next())
        else {
            return Err("a start without its stdio".to_string());
        };
        bridge.start_adapter(id, start, [stdin, stdout, stderr]);
        Ok(None)
    }

    fn signal(&self, signal: tinfirm_bridge::control::Signal) -> Handled {
        self.of("a signal")?.signal(signal);
        Ok(None)
    }

    fn stop(&self, grace_seconds: u64) -> Handled {
        self.of("a stop")?.stop(grace_seconds);
        Ok(None)
    }

    fn stdin_end(&self) -> Handled {
        self.of("a stdin end")?.stdin_end();
        Ok(None)
    }

    fn reclaim(&self, id: u64, timeout_ms: u64) -> Handled {
        self.of("a reclaim")?.reclaim(id, millis(timeout_ms));
        Ok(None)
    }

    fn close(&self, id: u64, timeout_ms: u64) -> Handled {
        self.of("a close")?.close(id, millis(timeout_ms));
        Ok(None)
    }

    fn exec(&self, exec: tinfirm_bridge::control::Exec) -> Handled {
        self.of("a command")?.exec(exec);
        Ok(None)
    }

    fn probe(
        &self,
        id: u64,
        checks: Vec<tinfirm_bridge::control::ProbeCheck>,
        timeout_ms: u64,
    ) -> Handled {
        self.of("a probe")?.probe(id, checks, millis(timeout_ms));
        Ok(None)
    }
}

fn millis(ms: u64) -> std::time::Duration {
    std::time::Duration::from_millis(ms)
}

/// The sandbox's directory `dir` from the archive `run` writes on the socket that came.
fn files_put(
    bridge: &BridgeService,
    id: u64,
    dir: String,
    timeout_ms: u64,
    descriptors: &mut Vec<OwnedFd>,
) -> Handled {
    let socket = descriptors.pop().expect("taken with the message");
    bridge.of("a put")?.put(id, dir, socket, millis(timeout_ms));
    Ok(None)
}

/// The sandbox's `paths` of `dir`, without `exclude`, as an archive on the pipe that came.
fn files_get(
    bridge: &BridgeService,
    id: u64,
    dir: String,
    paths: Vec<String>,
    exclude: Vec<String>,
    timeout_ms: u64,
    descriptors: &mut Vec<OwnedFd>,
) -> Handled {
    let pipe = descriptors.pop().expect("taken with the message");
    bridge
        .of("a get")?
        .get(id, dir, paths, exclude, pipe, millis(timeout_ms));
    Ok(None)
}

/// The sandbox's tunnels, each to a service of this front.
fn open_tunnels(
    bridge: &BridgeService,
    id: u64,
    tunnels: Vec<wire::WireTunnel>,
    timeout_ms: u64,
) -> Handled {
    bridge
        .of("tunnels")?
        .open_tunnels(id, tunnels, millis(timeout_ms));
    Ok(None)
}

/// The TCP listener that came with a listen message, when it says one did.
fn take_listener(
    descriptors: &mut Vec<OwnedFd>,
    wanted: bool,
) -> std::io::Result<Option<tokio::net::TcpListener>> {
    if !wanted {
        return Ok(None);
    }
    let fd = descriptors.pop().expect("taken with the message");
    let listener = std::net::TcpListener::from(fd);
    listener.set_nonblocking(true)?;
    Ok(Some(tokio::net::TcpListener::from_std(listener)?))
}

fn refuse(why: &str) -> ExitCode {
    eprintln!("tinfirm-runner-host front: {why}");
    ExitCode::from(3)
}
