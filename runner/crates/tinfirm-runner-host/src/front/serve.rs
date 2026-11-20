//! The front's side of the control channel: its stdin, a Unix stream socket whose other end
//! is `run`'s ([`super::wire`]).
//!
//! The front is confined already when this starts. It says so first ([`ToMain::Ready`]),
//! then builds its tokio runtime and reads `run`'s messages on a thread of their own until
//! `run` closes its end, and then exits. It answers `run`'s requests in order; an egress
//! listener that `run` passes it is served on the runtime ([`crate::egress_proxy::serve`]),
//! and each connection it needs is asked of `run` ([`ToMain::Connect`]) and given back as a
//! descriptor or refused. Its frames go out through one writer, under a lock, so they never
//! interleave. A message that is not one of the wire's, that breaks its bounds, that wants a
//! descriptor that did not come with it, that comes before `init` or a second time, or that
//! answers a request the front did not make ends the front with exit code 3: `run`'s messages
//! are trusted, so such a message means a fault, and the front does not guess.

use std::collections::{HashMap, VecDeque};
use std::io::IoSliceMut;
use std::os::fd::{AsFd, OwnedFd};
use std::process::ExitCode;
use std::sync::Arc;

use crate::confine::Status;

use super::wire::{self, CHECK_REPLY, MAX_TO_FRONT, MAX_TO_MAIN, ToFront, ToMain};
use crate::egress_proxy::{Connector, FrontHalf, Granted, Refusal};

/// How much a read takes at most.
const READ: usize = 64 * 1024;
/// The most descriptors one read may bring.
const DESCRIPTORS_PER_READ: usize = 4;

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
struct Writer {
    socket: OwnedFd,
    lock: std::sync::Mutex<()>,
}

impl Writer {
    fn send(&self, message: &ToMain) -> Result<(), String> {
        let frame = wire::encode(message, MAX_TO_MAIN).map_err(|e| e.to_string())?;
        let _one = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        wire::send_frame(self.socket.as_fd(), &frame, None)
            .map_err(|e| format!("cannot write the control channel: {e}"))
    }
}

/// The front's connections for the egress proxy: each destination is asked of `run`
/// ([`ToMain::Connect`]), which connects and hands the connection's descriptor over, or
/// refuses. The front tells `run` when a connection it was given has ended.
struct WireConnector {
    writer: Arc<Writer>,
    next: std::sync::atomic::AtomicU64,
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
        let req = self.next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let (tx, rx) = tokio::sync::oneshot::channel();
        self.waiting
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(req, tx);
        let sent = self.writer.send(&ToMain::Connect { req, host, port });
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
            lock: std::sync::Mutex::new(()),
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
    let (tx, mut rx) = tokio::sync::mpsc::channel::<Result<(ToFront, Option<OwnedFd>), String>>(64);
    std::thread::spawn(move || {
        let mut channel = Channel {
            socket,
            bytes: Vec::new(),
            descriptors: VecDeque::new(),
        };
        loop {
            let item = match channel.next() {
                Ok(Some(message)) => {
                    let descriptor = if message.carries_descriptor() {
                        match channel.descriptors.pop_front() {
                            Some(fd) => Some(fd),
                            None => {
                                let _ = tx.blocking_send(Err(
                                    "a message came without its descriptor".to_string(),
                                ));
                                return;
                            }
                        }
                    } else {
                        None
                    };
                    Ok((message, descriptor))
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
        let shutdown = tokio_util::sync::CancellationToken::new();
        while let Some(item) = rx.recv().await {
            let (message, descriptor) = match item {
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
                    let fd = descriptor.expect("taken with the message");
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
                ToFront::ListenEgress { authorization } => {
                    let fd = descriptor.expect("taken with the message");
                    let listener = std::net::TcpListener::from(fd);
                    let listener = match listener
                        .set_nonblocking(true)
                        .and_then(|()| tokio::net::TcpListener::from_std(listener))
                    {
                        Ok(listener) => listener,
                        Err(e) => return refuse(&format!("the egress listener: {e}")),
                    };
                    let connector = Arc::new(WireConnector {
                        writer: writer.clone(),
                        next: std::sync::atomic::AtomicU64::new(1),
                        waiting: std::sync::Mutex::new(HashMap::new()),
                    });
                    egress = Some(connector.clone());
                    let half = FrontHalf::new(authorization, connector, shutdown.clone());
                    tokio::spawn(crate::egress_proxy::serve(listener, half));
                    continue;
                }
                ToFront::Connected { req } => {
                    let fd = descriptor.expect("taken with the message");
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
            };
            if let Err(e) = writer.send(&answer) {
                return super::fail(&e);
            }
        }
        shutdown.cancel();
        ExitCode::SUCCESS
    })
}

fn refuse(why: &str) -> ExitCode {
    eprintln!("tinfirm-runner-host front: {why}");
    ExitCode::from(3)
}
