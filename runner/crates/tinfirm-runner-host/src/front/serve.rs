//! The front's side of the control channel: its stdin, a Unix stream socket whose other end
//! is `run`'s ([`super::wire`]).
//!
//! The front is confined already when this starts. It says so first ([`ToMain::Ready`]),
//! then builds its tokio runtime and reads `run`'s messages on a thread of their own until
//! `run` closes its end, and then exits. It answers `run`'s requests in order; an egress
//! listener that `run` passes it is served on the runtime ([`crate::egress_proxy::serve`]),
//! and each connection it needs is asked of `run` ([`ToMain::Connect`]) and given back as a
//! descriptor or refused; a model listener and a run's git listener are served there too
//! ([`crate::model_proxy::serve`], [`crate::git_proxy::serve_in_front`]), and each request
//! they let through goes to `run` ([`ToMain::Request`]), its body and its answer's body on a
//! socket `run` gives for it. Its frames go out through one writer, under a
//! lock, so they never interleave, and a new request takes its id under that lock, so the ids
//! reach `run` in the order they grow. A message that is not one of the wire's, that breaks its bounds, that wants a
//! descriptor that did not come with it, that comes before `init` or a second time, or that
//! answers a request the front did not make ends the front with exit code 3: `run`'s messages
//! are trusted, so such a message means a fault, and the front does not guess.

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
        use rustix::net::{RecvAncillaryBuffer, RecvAncillaryMessage, RecvFlags, recvmsg};
        let mut buffer = vec![0u8; READ];
        let mut space =
            [std::mem::MaybeUninit::uninit(); rustix::cmsg_space!(ScmRights(DESCRIPTORS_PER_READ))];
        let mut control = RecvAncillaryBuffer::new(&mut space);
        let received = loop {
            match recvmsg(
                &self.socket,
                &mut [IoSliceMut::new(&mut buffer)],
                &mut control,
                RecvFlags::CMSG_CLOEXEC,
            ) {
                Ok(received) => break received,
                Err(rustix::io::Errno::INTR) => continue,
                Err(e) => return Err(e.into()),
            }
        };
        for message in control.drain() {
            if let RecvAncillaryMessage::ScmRights(fds) = message {
                self.descriptors.extend(fds);
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
/// order, and the egress proxy's HTTP in its runtime.
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
    // `run`'s messages are read in a thread of their own, in order, descriptors with them.
    let (tx, mut rx) = tokio::sync::mpsc::channel::<Result<(ToFront, Vec<OwnedFd>), String>>(64);
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
    runtime.block_on(async move {
        let mut init = false;
        let mut egress: Option<Arc<WireConnector>> = None;
        let requests = RequestWire(Arc::new(WireInner {
            writer: writer.clone(),
            waiting: std::sync::Mutex::new(HashMap::new()),
        }));
        let mut model = false;
        let mut bridge: Option<Arc<super::bridge::FrontBridge>> = None;
        let services: super::bridge::Services = Default::default();
        let mut git: Option<Arc<crate::git_proxy::FrontRoutes>> = None;
        let egress_shutdown = tokio_util::sync::CancellationToken::new();
        let model_shutdown = tokio_util::sync::CancellationToken::new();
        let git_shutdown = tokio_util::sync::CancellationToken::new();
        while let Some(item) = rx.recv().await {
            let (message, mut descriptors) = match item {
                Ok(item) => item,
                Err(e) => return refuse(&e),
            };
            let answer = match message {
                ToFront::Init { .. } if init => return refuse("a second init"),
                ToFront::Init { .. } => {
                    init = true;
                    continue;
                }
                _ if !init => return refuse("a message before init"),
                ToFront::Ping { id } => ToMain::Pong { id },
                ToFront::Check { id } => {
                    let fd = descriptors.pop().expect("taken with the message");
                    let mut file = std::fs::File::from(fd);
                    if std::io::Write::write_all(&mut file, CHECK_REPLY).is_err() {
                        return refuse("cannot write on the descriptor of a check");
                    }
                    drop(file);
                    ToMain::Checked { id }
                }
                ToFront::ListenEgress { .. } if egress.is_some() => {
                    return refuse("a second egress listener");
                }
                ToFront::ListenEgress {
                    authorization,
                    listener,
                } => {
                    let listener = match take_listener(&mut descriptors, listener) {
                        Ok(listener) => listener,
                        Err(e) => return refuse(&format!("the egress listener: {e}")),
                    };
                    let connector = Arc::new(WireConnector {
                        writer: writer.clone(),
                        waiting: std::sync::Mutex::new(HashMap::new()),
                    });
                    egress = Some(connector.clone());
                    let half = FrontHalf::new(authorization, connector, egress_shutdown.clone());
                    let (acceptor, handoff) = crate::front::conn::Acceptor::new(listener, false);
                    services
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .insert(Service::Egress, handoff);
                    tokio::spawn(crate::egress_proxy::serve(acceptor, half));
                    continue;
                }
                ToFront::Connected { req } => {
                    let fd = descriptors.pop().expect("taken with the message");
                    if !egress.as_ref().is_some_and(|e| e.answer(req, Ok(fd))) {
                        return refuse("a connection for no request of this front");
                    }
                    continue;
                }
                ToFront::Refused {
                    req,
                    status,
                    message,
                    off_allowlist,
                } => {
                    let refusal = Refusal {
                        status,
                        message,
                        off_allowlist,
                    };
                    if !egress.as_ref().is_some_and(|e| e.answer(req, Err(refusal))) {
                        return refuse("a refusal of no request of this front");
                    }
                    continue;
                }
                ToFront::ListenModel { .. } if model => {
                    return refuse("a second model listener");
                }
                ToFront::ListenModel {
                    secret,
                    api,
                    base_path,
                    listener,
                } => {
                    let listener = match take_listener(&mut descriptors, listener) {
                        Ok(listener) => listener,
                        Err(e) => return refuse(&format!("the model listener: {e}")),
                    };
                    model = true;
                    let half = crate::model_proxy::FrontHalf::new(
                        secret,
                        api,
                        base_path,
                        Arc::new(ServiceWire::new(&requests, Service::Model)),
                        model_shutdown.clone(),
                    );
                    let (acceptor, handoff) = crate::front::conn::Acceptor::new(listener, true);
                    services
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .insert(Service::Model, handoff);
                    tokio::spawn(crate::model_proxy::serve(acceptor, half));
                    continue;
                }
                ToFront::ListenGit { .. } if git.is_some() => {
                    return refuse("a second git listener");
                }
                ToFront::ListenGit { limits, listener } => {
                    let listener = match take_listener(&mut descriptors, listener) {
                        Ok(listener) => listener,
                        Err(e) => return refuse(&format!("the git listener: {e}")),
                    };
                    let routes = Arc::new(crate::git_proxy::FrontRoutes::default());
                    git = Some(routes.clone());
                    let (acceptor, handoff) = crate::front::conn::Acceptor::new(listener, true);
                    services
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .insert(Service::Git, handoff);
                    tokio::spawn(crate::git_proxy::serve_in_front(
                        acceptor,
                        routes,
                        Arc::new(ServiceWire::new(&requests, Service::Git)),
                        limits,
                        git_shutdown.clone(),
                    ));
                    continue;
                }
                ToFront::GitRoutes { routes, mcp } => {
                    let Some(table) = &git else {
                        return refuse("routes for no git listener");
                    };
                    table.replace(routes, mcp);
                    continue;
                }
                ToFront::Unlisten { service } => {
                    services
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .remove(&service);
                    match service {
                        Service::Egress => egress_shutdown.cancel(),
                        Service::Model => model_shutdown.cancel(),
                        Service::Git => git_shutdown.cancel(),
                    }
                    continue;
                }
                ToFront::Body { req } => {
                    let fd = descriptors.pop().expect("taken with the message");
                    if !requests.body(req, fd) {
                        return refuse("a socket for no request of this front");
                    }
                    continue;
                }
                ToFront::Response { req, head } => {
                    if !requests.response(req, head) {
                        return refuse("an answer to no request of this front");
                    }
                    continue;
                }
                ToFront::BridgeInit { .. } if bridge.is_some() => {
                    return refuse("a second bridge");
                }
                ToFront::BridgeInit {
                    identity,
                    timing,
                    listen,
                } => {
                    let listener =
                        listen.then(|| descriptors.pop().expect("taken with the message"));
                    match super::bridge::FrontBridge::start(
                        writer.clone(),
                        services.clone(),
                        &identity,
                        &timing,
                        listener,
                    ) {
                        Ok(started) => bridge = Some(started),
                        Err(e) => return refuse(&e),
                    }
                    continue;
                }
                ToFront::AllowPeer { ip } => {
                    let Some(bridge) = &bridge else {
                        return refuse("a peer for no bridge");
                    };
                    bridge.allow_peer(ip);
                    continue;
                }
                ToFront::Dialed {} => {
                    let fd = descriptors.pop().expect("taken with the message");
                    let Some(bridge) = &bridge else {
                        return refuse("a connection for no bridge");
                    };
                    if let Err(e) = bridge.dialed(fd) {
                        return refuse(&e);
                    }
                    continue;
                }
                ToFront::Admit { req, admission } => {
                    let Some(bridge) = &bridge else {
                        return refuse("an admission for no bridge");
                    };
                    bridge.admit(req, admission);
                    continue;
                }
                ToFront::Start { id, start } => {
                    let Some(bridge) = &bridge else {
                        return refuse("a start for no bridge");
                    };
                    let mut stdio = descriptors.drain(..);
                    let (Some(stdin), Some(stdout), Some(stderr)) =
                        (stdio.next(), stdio.next(), stdio.next())
                    else {
                        return refuse("a start without its stdio");
                    };
                    bridge.start_adapter(id, start, [stdin, stdout, stderr]);
                    continue;
                }
                ToFront::Signal { signal } => {
                    let Some(bridge) = &bridge else {
                        return refuse("a signal for no bridge");
                    };
                    bridge.signal(signal);
                    continue;
                }
                ToFront::Stop { grace_seconds } => {
                    let Some(bridge) = &bridge else {
                        return refuse("a stop for no bridge");
                    };
                    bridge.stop(grace_seconds);
                    continue;
                }
                ToFront::Reclaim { id, timeout_ms } => {
                    let Some(bridge) = &bridge else {
                        return refuse("a reclaim for no bridge");
                    };
                    bridge.reclaim(id, std::time::Duration::from_millis(timeout_ms));
                    continue;
                }
                ToFront::Close { id, timeout_ms } => {
                    let Some(bridge) = &bridge else {
                        return refuse("a close for no bridge");
                    };
                    bridge.close(id, std::time::Duration::from_millis(timeout_ms));
                    continue;
                }
                ToFront::Exec { exec } => {
                    let Some(bridge) = &bridge else {
                        return refuse("a command for no bridge");
                    };
                    bridge.exec(exec);
                    continue;
                }
                ToFront::Put {
                    id,
                    dir,
                    timeout_ms,
                } => {
                    let socket = descriptors.pop().expect("taken with the message");
                    let Some(bridge) = &bridge else {
                        return refuse("a put for no bridge");
                    };
                    bridge.put(
                        id,
                        dir,
                        socket,
                        std::time::Duration::from_millis(timeout_ms),
                    );
                    continue;
                }
                ToFront::Get {
                    id,
                    dir,
                    paths,
                    exclude,
                    timeout_ms,
                } => {
                    let pipe = descriptors.pop().expect("taken with the message");
                    let Some(bridge) = &bridge else {
                        return refuse("a get for no bridge");
                    };
                    bridge.get(
                        id,
                        dir,
                        paths,
                        exclude,
                        pipe,
                        std::time::Duration::from_millis(timeout_ms),
                    );
                    continue;
                }
                ToFront::OpenTunnels {
                    id,
                    tunnels,
                    timeout_ms,
                } => {
                    let Some(bridge) = &bridge else {
                        return refuse("tunnels for no bridge");
                    };
                    bridge.open_tunnels(id, tunnels, std::time::Duration::from_millis(timeout_ms));
                    continue;
                }
                ToFront::Probe {
                    id,
                    checks,
                    timeout_ms,
                } => {
                    let Some(bridge) = &bridge else {
                        return refuse("a probe for no bridge");
                    };
                    bridge.probe(id, checks, std::time::Duration::from_millis(timeout_ms));
                    continue;
                }
                ToFront::End { req, ok } => {
                    if !requests.end(req, ok) {
                        return refuse("an end of no request of this front");
                    }
                    continue;
                }
            };
            if let Err(e) = writer.send(&answer) {
                return super::fail(&e);
            }
        }
        egress_shutdown.cancel();
        model_shutdown.cancel();
        git_shutdown.cancel();
        ExitCode::SUCCESS
    })
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
