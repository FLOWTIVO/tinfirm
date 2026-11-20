//! The sandbox's bridge as `run` holds it when the bridge is in the sandbox's front (ADR-0095;
//! ADR-0112, path C, part 2): the run's one entry and its decisions, and [`FrontSession`],
//! which offers what `run` uses of the bridge crate's [`tinfirm_bridge::host::HostSession`].
//!
//! `run` keeps the ticket and the session key, and decides each `hello` that the front hands
//! it with the bridge crate's own rule ([`tinfirm_bridge::host::judge`]): the front applies the
//! decision and holds the link. What comes back from the front is checked again here: an
//! answer of the wrong kind, a command's output over its bound or not text, a probe's name
//! that was not asked, or tunnels on other addresses than asked end the front.
//!
//! The sandbox's files go in on a socket of their own, which `run` packs into, and come out on
//! a pipe whose other end is a reader's stdin ([`crate::reader::Input::Pipe`]): an archive of
//! the sandbox's never passes through `run`. A tunnel names a service of the front, never an
//! address.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::os::fd::OwnedFd;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context as TaskContext, Poll};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine as _;
use tinfirm_bridge::control::{Exec, Hidden, ProbeCheck, Signal, Start};
use tinfirm_bridge::host::{self, ExecOutput, Exit, Expect, HostTiming, Output, Verdict};
use tokio::io::{AsyncReadExt, AsyncWrite};
use tokio::net::{TcpListener, UnixStream};
use tokio::sync::{mpsc, oneshot, watch};
use tokio_util::sync::CancellationToken;

use super::sandbox::SandboxFront;
use super::wire::{
    Admission, Reply, Service, ToFront, WireHello, WireIdentity, WireTiming, WireTunnel,
};
use crate::reader::Input;

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

/// How long a start may take to reach the sandbox.
const START_TIMEOUT: Duration = Duration::from_secs(30);
/// What a request's answer may take beyond its own time in the sandbox.
const MARGIN: Duration = Duration::from_secs(10);
/// Chunks of the adapter's output waiting for the run's loop: as the bridge crate's queue.
const OUTPUT_QUEUE: usize = tinfirm_bridge::host::OUTPUT_QUEUE;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// What the run's bridge first presents.
pub enum Expected {
    /// A new sandbox: its bridge's one-time ticket.
    Ticket(Hidden),
    /// A sandbox of an earlier host (ADR-0101): its session's key.
    Reclaim(Hidden),
}

impl Expected {
    /// A new sandbox, with a new ticket.
    pub fn new_ticket() -> Result<Expected> {
        Ok(Expected::Ticket(Hidden::new(
            tinfirm_bridge::random_token()?
        )))
    }
}

/// Where the front gets the bridge's connections.
pub enum Peer {
    /// From this listener, which `run` bound: the sandbox's bridge connects.
    Listen(TcpListener),
    /// From `run`, which dials the bridge at this address (a VM's, which listens), and dials
    /// again whenever the link has no connection, while the front lives
    /// ([`BridgeRegistration::dialed`]).
    Dial(String),
}

enum Stage {
    Waiting { ticket: Hidden },
    Established { session: Hidden },
    Reclaiming { session: Hidden },
}

/// A `hello` that `run` admitted and whose end the front has not said yet.
struct Admitted {
    /// What the run's entry was before, should the link not take the connection.
    revert: Option<Stage>,
    /// The session that opens once the link has taken it: its key, and the bridge's build.
    opens: Option<(Hidden, Option<String>)>,
}

struct Entry {
    stage: Stage,
    ready: Option<oneshot::Sender<(Hidden, Option<String>)>>,
    admitted: HashMap<u64, Admitted>,
}

/// The run's entry and the session's ends, which the front's dispatcher keeps.
pub(crate) struct BridgeState {
    run_id: String,
    entry: Mutex<Entry>,
    exit: watch::Sender<Option<Exit>>,
    ended: CancellationToken,
    /// Whether the session's link has a connection, as the front says.
    link: watch::Sender<bool>,
}

impl BridgeState {
    pub(crate) fn link(&self, up: bool) {
        self.link.send_replace(up);
    }

    /// `run`'s decision on a `hello` of the front's request `req`, recorded until its end.
    pub(crate) fn decide(&self, req: u64, hello: &WireHello) -> Result<Admission> {
        let hello = hello.hello();
        let mut entry = lock(&self.entry);
        let expect = (hello.run_id == self.run_id).then(|| match &entry.stage {
            Stage::Waiting { ticket } => Expect::Ticket(ticket),
            Stage::Established { session } => Expect::Session(session),
            Stage::Reclaiming { session } => Expect::Reclaim(session),
        });
        let verdict = host::judge(expect, &hello);
        let (admission, admitted) = match verdict {
            Verdict::New => {
                let session = Hidden::new(tinfirm_bridge::random_token()?);
                let before = std::mem::replace(
                    &mut entry.stage,
                    Stage::Established {
                        session: session.clone(),
                    },
                );
                (
                    Admission::New {
                        session: session.clone(),
                    },
                    Admitted {
                        revert: Some(before),
                        opens: Some((session, hello.build.clone())),
                    },
                )
            }
            Verdict::Resume => (
                Admission::Resume,
                Admitted {
                    revert: None,
                    opens: None,
                },
            ),
            Verdict::Reclaim { rx_after } => {
                let Stage::Reclaiming { session } = &entry.stage else {
                    bail!("a reclaim of a session that is not taken back");
                };
                let session = session.clone();
                let before = std::mem::replace(
                    &mut entry.stage,
                    Stage::Established {
                        session: session.clone(),
                    },
                );
                (
                    Admission::Reclaim { rx_after },
                    Admitted {
                        revert: Some(before),
                        opens: Some((session, hello.build.clone())),
                    },
                )
            }
            Verdict::Refuse(code, message) => {
                tracing::warn!(run_id = %self.run_id, %message, "a sandbox bridge connection was refused");
                (
                    Admission::Refuse {
                        code,
                        message: message.to_string(),
                    },
                    Admitted {
                        revert: None,
                        opens: None,
                    },
                )
            }
        };
        entry.admitted.insert(req, admitted);
        Ok(admission)
    }

    /// The end of the front's `hello` `req`: the session opens when the link took it; the
    /// entry is as before when it did not.
    pub(crate) fn admitted(&self, req: u64, attached: bool) {
        let mut entry = lock(&self.entry);
        let Some(admitted) = entry.admitted.remove(&req) else {
            return;
        };
        match (attached, admitted) {
            (
                true,
                Admitted {
                    opens: Some(opens), ..
                },
            ) => {
                if let Some(ready) = entry.ready.take() {
                    let _ = ready.send(opens);
                }
            }
            (
                false,
                Admitted {
                    revert: Some(before),
                    ..
                },
            ) => entry.stage = before,
            _ => {}
        }
    }

    pub(crate) fn exited(&self, code: Option<i32>, signal: Option<i32>) {
        self.exit.send_if_modified(|exit| {
            if exit.is_none() {
                *exit = Some(Exit::Exited { code, signal });
                true
            } else {
                false
            }
        });
    }

    /// The session has ended, or the front has.
    pub(crate) fn end(&self) {
        self.exit.send_if_modified(|exit| {
            if exit.is_none() {
                *exit = Some(Exit::Lost);
                true
            } else {
                false
            }
        });
        self.ended.cancel();
        let mut entry = lock(&self.entry);
        entry.admitted.clear();
        entry.ready.take();
    }

    /// The `hello`s that `run` admitted and whose end the front has not said yet.
    fn admitted_waiting(&self) -> usize {
        lock(&self.entry).admitted.len()
    }
}

impl SandboxFront {
    /// The sandbox's bridge goes to this front: its TLS identity, its timing, where its
    /// connections come from, and what the run's bridge first presents. The front serves one
    /// bridge in its life.
    pub async fn bridge(
        self: &Arc<Self>,
        identity: &tinfirm_bridge::tls::Identity,
        timing: &HostTiming,
        peer: Peer,
        run_id: &str,
        expected: Expected,
    ) -> Result<BridgeRegistration> {
        let (ticket, stage) = match expected {
            Expected::Ticket(ticket) => (Some(ticket.clone()), Stage::Waiting { ticket }),
            Expected::Reclaim(session) => (None, Stage::Reclaiming { session }),
        };
        let (ready_tx, ready) = oneshot::channel();
        let state = Arc::new(BridgeState {
            run_id: run_id.to_string(),
            entry: Mutex::new(Entry {
                stage,
                ready: Some(ready_tx),
                admitted: HashMap::new(),
            }),
            exit: watch::Sender::new(None),
            ended: CancellationToken::new(),
            link: watch::Sender::new(false),
        });
        self.serve_bridge(state.clone())?;
        let (cert, key) = identity.to_der();
        let message = ToFront::BridgeInit {
            identity: WireIdentity {
                cert: B64.encode(cert),
                key: B64.encode(key),
            },
            timing: WireTiming::of(timing),
            listen: matches!(peer, Peer::Listen(_)),
        };
        let (descriptors, target) = match peer {
            Peer::Listen(listener) => (vec![OwnedFd::from(listener.into_std()?)], None),
            Peer::Dial(target) => (Vec::new(), Some(target)),
        };
        self.handle().send_all(&message, descriptors).await?;
        if let Some(target) = target {
            tokio::spawn(dial(Arc::downgrade(self), state.clone(), target, *timing));
        }
        Ok(BridgeRegistration {
            front: self.clone(),
            state,
            ready,
            ticket,
        })
    }
}

/// Dials the sandbox's bridge at `target` for its front, which holds no network of its own:
/// once at first, and again whenever the link has no connection, at most once per
/// `dial_retry`, until the front or the session ends. A connection passed to the front is
/// waited on, up to the handshake's time, before the next: one that the bridge has taken shows
/// as up a moment later, and dialing again before then would leave a connection in the
/// bridge's backlog that it would take for the next one.
async fn dial(
    front: std::sync::Weak<SandboxFront>,
    state: Arc<BridgeState>,
    target: String,
    timing: HostTiming,
) {
    let mut link = state.link.subscribe();
    loop {
        if *link.borrow_and_update() {
            tokio::select! {
                () = state.ended.cancelled() => return,
                down = link.wait_for(|up| !*up) => if down.is_err() {
                    return;
                },
            }
            continue;
        }
        let connected = tokio::select! {
            () = state.ended.cancelled() => return,
            connected = tokio::time::timeout(timing.handshake, tokio::net::TcpStream::connect(target.as_str())) => connected,
        };
        match connected {
            Ok(Ok(tcp)) => {
                let Some(front) = front.upgrade() else {
                    return;
                };
                let passed = match tcp.into_std() {
                    Ok(tcp) => front
                        .handle()
                        .send(&ToFront::Dialed {}, Some(OwnedFd::from(tcp)))
                        .await
                        .is_ok(),
                    Err(_) => false,
                };
                drop(front);
                if passed {
                    let up = tokio::select! {
                        () = state.ended.cancelled() => return,
                        up = tokio::time::timeout(timing.handshake, link.wait_for(|up| *up)) => up,
                    };
                    if matches!(up, Ok(Ok(_))) {
                        continue;
                    }
                }
            }
            Ok(Err(e)) => {
                tracing::debug!(run_id = %state.run_id, %target, error = %e, "the sandbox's bridge does not answer yet");
            }
            Err(_) => {
                tracing::debug!(run_id = %state.run_id, %target, "dialing the sandbox's bridge timed out");
            }
        }
        tokio::select! {
            () = state.ended.cancelled() => return,
            () = tokio::time::sleep(timing.dial_retry) => {}
        }
    }
}

/// The run's bridge, registered with its front, until it connects.
pub struct BridgeRegistration {
    front: Arc<SandboxFront>,
    state: Arc<BridgeState>,
    ready: oneshot::Receiver<(Hidden, Option<String>)>,
    ticket: Option<Hidden>,
}

impl BridgeRegistration {
    /// The bridge's one-time ticket (`TINFIRM_BRIDGE_TICKET`); none for a session taken back.
    pub fn ticket(&self) -> Option<&str> {
        self.ticket.as_ref().map(Hidden::expose)
    }

    /// The `hello`s that `run` admitted and whose end the front has not said yet.
    pub fn admitted_waiting(&self) -> usize {
        self.state.admitted_waiting()
    }

    /// The address the sandbox's bridge connects from: the front closes a connection from any
    /// other before its TLS handshake. An unspecified address (`0.0.0.0`) takes any peer, where
    /// every sandbox comes from one address.
    pub async fn allow_peer(&self, ip: IpAddr) -> Result<()> {
        self.front
            .handle()
            .send(&ToFront::AllowPeer { ip }, None)
            .await
    }

    /// A connection to the sandbox's bridge that `run` dialed.
    pub async fn dialed(&self, tcp: std::net::TcpStream) -> Result<()> {
        self.front
            .handle()
            .send(&ToFront::Dialed {}, Some(OwnedFd::from(tcp)))
            .await
    }

    /// Waits for the bridge's first connection.
    pub async fn connected(self, timeout: Duration) -> Result<FrontSession> {
        let opened = tokio::select! {
            opened = tokio::time::timeout(timeout, self.ready) => opened,
            () = self.state.ended.cancelled() => bail!("the sandbox's front ended"),
        };
        match opened {
            Ok(Ok((key, build))) => Ok(FrontSession {
                front: self.front,
                state: self.state,
                key,
                build,
                started: false,
            }),
            Ok(Err(_)) => bail!("the sandbox's front ended"),
            Err(_) => bail!(
                "the sandbox's bridge did not connect within {} s",
                timeout.as_secs()
            ),
        }
    }
}

/// The adapter's stdin, on a socket of its own. Its shutdown ends that stdin on purpose: the
/// socket's write half ends, then `run` says so on the control channel
/// ([`ToFront::StdinEnd`]), and the front passes the end on after the socket's last bytes.
/// Dropped without a shutdown (a task cancelled, a panic), it says nothing, and neither does a
/// `run` that ended: the agent's stdin stays open, and the agent goes on until a host takes
/// the session back.
pub struct FrontStdin {
    socket: UnixStream,
    front: Arc<SandboxFront>,
    end: StdinEnd,
}

enum StdinEnd {
    Open,
    Saying(Pin<Box<dyn Future<Output = std::io::Result<()>> + Send>>),
    Said,
}

impl AsyncWrite for FrontStdin {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        Pin::new(&mut self.socket).poll_write(cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.socket).poll_flush(cx)
    }

    fn poll_shutdown(
        mut self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
    ) -> Poll<std::io::Result<()>> {
        loop {
            match &mut self.end {
                StdinEnd::Open => {
                    // The socket's end first: the front reads it to that end before it acts on
                    // the message, so no byte written before is lost.
                    std::task::ready!(Pin::new(&mut self.socket).poll_shutdown(cx))?;
                    let front = self.front.clone();
                    self.end = StdinEnd::Saying(Box::pin(async move {
                        front
                            .handle()
                            .send(&ToFront::StdinEnd {}, None)
                            .await
                            .map_err(std::io::Error::other)
                    }));
                }
                StdinEnd::Saying(saying) => {
                    let said = std::task::ready!(saying.as_mut().poll(cx));
                    self.end = StdinEnd::Said;
                    return Poll::Ready(said);
                }
                StdinEnd::Said => return Poll::Ready(Ok(())),
            }
        }
    }
}

/// The adapter's stdio once it is started, as [`tinfirm_bridge::host::HostIo`] has it.
pub struct FrontIo {
    /// Writes go to the adapter's stdin; its shutdown ends that stdin, and nothing else does.
    pub stdin: FrontStdin,
    /// Closes when the adapter has exited or is lost. Holds at most [`OUTPUT_QUEUE`] chunks:
    /// a reader that falls behind holds the sandbox back.
    pub output: mpsc::Receiver<Output>,
    pub exit: watch::Receiver<Option<Exit>>,
}

/// A run's bridge, connected, in the sandbox's front. Dropping it leaves the front, which its
/// other owners hold, as it is.
pub struct FrontSession {
    front: Arc<SandboxFront>,
    state: Arc<BridgeState>,
    key: Hidden,
    build: Option<String>,
    started: bool,
}

impl FrontSession {
    pub fn run_id(&self) -> &str {
        &self.state.run_id
    }

    /// The session's key, which `run` keeps on its disk while the sandbox lives (ADR-0101).
    pub fn session_key(&self) -> &Hidden {
        &self.key
    }

    /// The version of the bridge's binary, as its first `hello` named it.
    pub fn bridge_build(&self) -> Option<&str> {
        self.build.as_deref()
    }

    /// The `hello`s that `run` admitted and whose end the front has not said yet.
    pub fn admitted_waiting(&self) -> usize {
        self.state.admitted_waiting()
    }

    /// The sandbox's front.
    pub fn front(&self) -> &Arc<SandboxFront> {
        &self.front
    }

    pub fn exit(&self) -> watch::Receiver<Option<Exit>> {
        self.state.exit.subscribe()
    }

    /// Whether the session has ended: its link closed, it had no connection for too long, or
    /// the front ended.
    pub fn has_ended(&self) -> bool {
        self.state.ended.is_cancelled()
    }

    pub fn ended(&self) -> tokio_util::sync::WaitForCancellationFutureOwned {
        self.state.ended.clone().cancelled_owned()
    }

    pub fn files(&self) -> FrontFiles {
        FrontFiles {
            front: self.front.clone(),
            state: self.state.clone(),
        }
    }

    /// Starts the adapter with the run's environment; its stdio comes on sockets of its own.
    pub async fn start(&mut self, start: Start) -> Result<FrontIo> {
        start.check().map_err(|e| anyhow!(e))?;
        anyhow::ensure!(!self.started, "the adapter was started already");
        self.started = true;
        let pair = || -> Result<(UnixStream, OwnedFd)> {
            let (ours, theirs) = std::os::unix::net::UnixStream::pair()?;
            ours.set_nonblocking(true)?;
            Ok((UnixStream::from_std(ours)?, OwnedFd::from(theirs)))
        };
        let (stdin, stdin_theirs) = pair()?;
        let (stdout, stdout_theirs) = pair()?;
        let (stderr, stderr_theirs) = pair()?;
        let reply = self
            .front
            .handle()
            .ask(
                |id| ToFront::Start { id, start },
                vec![stdin_theirs, stdout_theirs, stderr_theirs],
                START_TIMEOUT,
            )
            .await
            .context("the adapter did not start")?;
        self.expect_done(reply)
            .context("the adapter did not start")?;
        let (tx, output) = mpsc::channel(OUTPUT_QUEUE);
        spawn_output(stdout, tx.clone(), Output::Stdout);
        spawn_output(stderr, tx, Output::Stderr);
        Ok(FrontIo {
            stdin: FrontStdin {
                socket: stdin,
                front: self.front.clone(),
                end: StdinEnd::Open,
            },
            output,
            exit: self.exit(),
        })
    }

    /// Before [`FrontSession::start`]: the sandbox listens on each tunnel's address, and the
    /// connections it gets there go to the front's service that the tunnel names, never to an
    /// address. The addresses the sandbox listens on, in order: the asked ones, or for a port 0
    /// the one it got.
    pub async fn open_tunnels(
        &mut self,
        tunnels: &[(SocketAddr, Service)],
        timeout: Duration,
    ) -> Result<Vec<SocketAddr>> {
        anyhow::ensure!(!self.started, "the adapter was started already");
        anyhow::ensure!(
            tunnels.len() <= tinfirm_bridge::tunnel::MAX_TUNNELS,
            "a sandbox has at most {} tunnels",
            tinfirm_bridge::tunnel::MAX_TUNNELS
        );
        let asked: Vec<WireTunnel> = tunnels
            .iter()
            .map(|&(listen, service)| WireTunnel { listen, service })
            .collect();
        let reply = self
            .front
            .handle()
            .ask(
                |id| ToFront::OpenTunnels {
                    id,
                    tunnels: asked,
                    timeout_ms: millis(timeout),
                },
                Vec::new(),
                timeout + MARGIN,
            )
            .await
            .context("the sandbox cannot listen for its tunnels")?;
        match reply {
            Reply::Listening { addrs } => {
                // Exactly what was asked: the asked address, or for a port 0 the asked IP on a
                // port that the sandbox got.
                let fits = addrs.len() == tunnels.len()
                    && addrs.iter().zip(tunnels).all(|(got, (asked, _))| {
                        if asked.port() == 0 {
                            got.ip() == asked.ip() && got.port() != 0
                        } else {
                            got == asked
                        }
                    });
                if !fits {
                    let why = "the front answered the tunnels with other addresses than asked";
                    self.front.handle().break_wire(why.to_string());
                    bail!("{why}");
                }
                Ok(addrs)
            }
            Reply::Failed { message } => {
                // The ports are fixed: a program of the image that listens there first takes
                // one from the bridge, and the error names each.
                let named: Vec<String> = tunnels
                    .iter()
                    .map(|(addr, service)| {
                        let name = match service {
                            Service::Git => "the git proxy and the platform tools",
                            Service::Model => "the model proxy",
                            Service::Egress => "the egress proxy",
                        };
                        format!("{name} on {addr}")
                    })
                    .collect();
                Err(anyhow!("{message}")).with_context(|| {
                    format!(
                        "the sandbox's bridge cannot listen for {}; a program of the image may listen there",
                        named.join(", ")
                    )
                })
            }
            other => Err(self.broken("the tunnels", &other)),
        }
    }

    pub async fn signal(&self, signal: Signal) -> Result<()> {
        self.front
            .handle()
            .send(&ToFront::Signal { signal }, None)
            .await
    }

    /// SIGTERM to the adapter's process group, SIGKILL after `grace`.
    pub async fn stop(&self, grace: Duration) -> Result<()> {
        self.front
            .handle()
            .send(
                &ToFront::Stop {
                    grace_seconds: grace.as_secs().max(1),
                },
                None,
            )
            .await
    }

    /// For a session taken back (ADR-0101): the agent of the earlier host stops. Whether it
    /// still ran.
    pub async fn reclaim(&mut self, timeout: Duration) -> Result<bool> {
        anyhow::ensure!(!self.started, "the adapter was started by this host");
        let reply = self
            .front
            .handle()
            .ask(
                |id| ToFront::Reclaim {
                    id,
                    timeout_ms: millis(timeout),
                },
                Vec::new(),
                timeout + MARGIN,
            )
            .await
            .context("the sandbox's bridge does not answer the reclaim")?;
        let running = match reply {
            Reply::Reclaimed { running } => running,
            Reply::Failed { message } => bail!("{message}"),
            other => return Err(self.broken("the reclaim", &other)),
        };
        if running {
            // The front said the agent ended; its word on how comes right after.
            let mut exit = self.exit();
            let _ = tokio::time::timeout(MARGIN, exit.wait_for(Option::is_some)).await;
        }
        Ok(running)
    }

    /// After the adapter has ended: the bridge ends. Waits up to `timeout` for its answer.
    pub async fn close(&self, timeout: Duration) {
        let reply = self
            .front
            .handle()
            .ask(
                |id| ToFront::Close {
                    id,
                    timeout_ms: millis(timeout),
                },
                Vec::new(),
                timeout + MARGIN,
            )
            .await;
        match reply {
            Ok(reply) => {
                if let Err(e) = self.expect_done(reply) {
                    tracing::debug!(error = %format!("{e:#}"), "the sandbox's bridge did not answer the close");
                }
            }
            Err(e) => {
                tracing::debug!(error = %format!("{e:#}"), "the sandbox's bridge did not answer the close");
            }
        }
    }

    fn expect_done(&self, reply: Reply) -> Result<()> {
        match reply {
            Reply::Done => Ok(()),
            Reply::Failed { message } => bail!("{message}"),
            other => Err(self.broken("a start or a close", &other)),
        }
    }

    fn broken(&self, asked: &str, reply: &Reply) -> anyhow::Error {
        broken(&self.front, asked, reply)
    }
}

/// Ends a front that answered `asked` with another kind of answer.
fn broken(front: &SandboxFront, asked: &str, reply: &Reply) -> anyhow::Error {
    let kind = match reply {
        Reply::Done => "done",
        Reply::Reclaimed { .. } => "reclaimed",
        Reply::ExecDone { .. } => "execDone",
        Reply::Probed { .. } => "probed",
        Reply::Listening { .. } => "listening",
        Reply::Failed { .. } => "failed",
    };
    let why = format!("the front answered {asked} with `{kind}`");
    front.handle().break_wire(why.clone());
    anyhow!(why)
}

fn millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

/// Reads one of the adapter's output sockets into the output's queue, as it comes.
fn spawn_output(socket: UnixStream, tx: mpsc::Sender<Output>, make: fn(Vec<u8>) -> Output) {
    tokio::spawn(async move {
        let mut socket = socket;
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            match socket.read(&mut buffer).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    // A reader that is gone ends this; a full queue waits, and the front's
                    // socket, then the bridge's credit, hold the sandbox back in turn.
                    if tx.send(make(buffer[..n].to_vec())).await.is_err() {
                        break;
                    }
                }
            }
        }
    });
}

/// The sandbox's files and the host's commands there, through its front. Clones share the
/// session; once it has ended, every call fails.
#[derive(Clone)]
pub struct FrontFiles {
    front: Arc<SandboxFront>,
    state: Arc<BridgeState>,
}

impl FrontFiles {
    /// Runs `argv` in the sandbox, in `cwd`, with `env` added and `stdin` as its input.
    pub async fn exec(
        &self,
        argv: &[&str],
        env: &[(&str, &str)],
        cwd: Option<&str>,
        stdin: Option<String>,
        timeout: Duration,
    ) -> Result<ExecOutput> {
        anyhow::ensure!(
            !self.state.ended.is_cancelled(),
            "the sandbox's bridge is gone"
        );
        let exec = |id| ToFront::Exec {
            exec: Exec {
                id,
                argv: argv.iter().map(|a| a.to_string()).collect(),
                env: env
                    .iter()
                    .map(|(k, v)| (k.to_string(), Hidden::new(*v)))
                    .collect(),
                cwd: cwd.map(str::to_string),
                stdin,
                timeout_seconds: timeout.as_secs().max(1),
            },
        };
        let reply = self
            .front
            .handle()
            .ask(exec, Vec::new(), timeout + MARGIN + MARGIN)
            .await
            .with_context(|| {
                format!("cannot run {} in the sandbox", argv.first().unwrap_or(&""))
            })?;
        match reply {
            Reply::ExecDone {
                code,
                signal,
                stdout,
                stderr,
            } => {
                let text = |b64: &str| -> Option<String> {
                    let bytes = B64.decode(b64).ok()?;
                    if bytes.len() > Exec::MAX_OUTPUT {
                        return None;
                    }
                    String::from_utf8(bytes).ok()
                };
                match (text(&stdout), text(&stderr)) {
                    (Some(stdout), Some(stderr)) => Ok(ExecOutput {
                        code,
                        signal,
                        stdout,
                        stderr,
                    }),
                    _ => {
                        let why = "the front answered a command with an output that is not text within its bound";
                        self.front.handle().break_wire(why.to_string());
                        bail!("{why}")
                    }
                }
            }
            Reply::Failed { message } => Err(anyhow!("{message}")).with_context(|| {
                format!("cannot run {} in the sandbox", argv.first().unwrap_or(&""))
            }),
            other => Err(broken(&self.front, "a command", &other)),
        }
    }

    /// Packs `host_dir`, without `exclude`, into the sandbox's directory `dir`: the archive goes
    /// on a socket of its own, at the pace the sandbox takes it.
    pub async fn put_dir(
        &self,
        host_dir: &std::path::Path,
        dir: &str,
        exclude: Vec<String>,
        timeout: Duration,
    ) -> Result<()> {
        anyhow::ensure!(
            !self.state.ended.is_cancelled(),
            "the sandbox's bridge is gone"
        );
        let (ours, theirs) = std::os::unix::net::UnixStream::pair()?;
        // Shut down when the put ends, so that a packer whose reader stopped never waits.
        let guard = ours.try_clone()?;
        let host_dir = host_dir.to_path_buf();
        let packing = tokio::task::spawn_blocking(move || {
            // Its end is said with a shutdown, which the copy kept to stop it does not hold
            // back.
            let ours = tinfirm_bridge::files::pack(&host_dir, &[], &exclude, ours)?;
            ours.shutdown(std::net::Shutdown::Write)
        });
        let asked = self
            .front
            .handle()
            .ask(
                |id| ToFront::Put {
                    id,
                    dir: dir.to_string(),
                    timeout_ms: millis(timeout),
                },
                vec![OwnedFd::from(theirs)],
                timeout + MARGIN,
            )
            .await;
        let _ = guard.shutdown(std::net::Shutdown::Both);
        let packed = packing.await.map_err(|e| anyhow!("{e}"))?;
        match asked.with_context(|| format!("cannot put {dir} into the sandbox"))? {
            Reply::Done => {
                packed.with_context(|| format!("cannot pack the files of {dir}"))?;
                Ok(())
            }
            Reply::Failed { message } => Err(anyhow!("{message}"))
                .with_context(|| format!("the sandbox cannot unpack {dir}")),
            other => Err(broken(&self.front, "a put", &other)),
        }
    }

    /// Asks the sandbox for `paths` of its directory `dir` (all of it when empty), without
    /// `exclude`, as a tar archive, and hands `consume` a pipe on which the front writes it:
    /// a reader's stdin ([`Input::Pipe`]), so that the archive never passes through this
    /// process. Fails when `consume` fails, or when the archive did not come whole.
    pub async fn receive<T, F>(
        &self,
        dir: &str,
        paths: Vec<String>,
        exclude: Vec<String>,
        timeout: Duration,
        consume: F,
    ) -> Result<T>
    where
        T: Send + 'static,
        F: FnOnce(Input<'static>) -> Result<T> + Send + 'static,
    {
        anyhow::ensure!(
            !self.state.ended.is_cancelled(),
            "the sandbox's bridge is gone"
        );
        let (read, write) = std::io::pipe()?;
        let consuming =
            tokio::task::spawn_blocking(move || consume(Input::Pipe(OwnedFd::from(read))));
        let asked = self
            .front
            .handle()
            .ask(
                |id| ToFront::Get {
                    id,
                    dir: dir.to_string(),
                    paths,
                    exclude,
                    timeout_ms: millis(timeout),
                },
                vec![OwnedFd::from(write)],
                timeout + MARGIN,
            )
            .await;
        let consumed = consuming
            .await
            .map_err(|e| anyhow!("{e}"))?
            .with_context(|| format!("cannot take the files of {dir}"))?;
        match asked.with_context(|| format!("cannot take the files of {dir}"))? {
            Reply::Done => Ok(consumed),
            Reply::Failed { message } => {
                Err(anyhow!("{message}")).with_context(|| format!("the sandbox cannot send {dir}"))
            }
            other => Err(broken(&self.front, "a get", &other)),
        }
    }

    /// Tries `checks` from inside the sandbox; the names of those that got through, each one
    /// of the checks asked.
    pub async fn probe(&self, checks: Vec<ProbeCheck>, timeout: Duration) -> Result<Vec<String>> {
        anyhow::ensure!(
            !self.state.ended.is_cancelled(),
            "the sandbox's bridge is gone"
        );
        let asked: Vec<String> = checks.iter().map(ProbeCheck::name).collect();
        let reply = self
            .front
            .handle()
            .ask(
                |id| ToFront::Probe {
                    id,
                    checks,
                    timeout_ms: millis(timeout),
                },
                Vec::new(),
                timeout + MARGIN,
            )
            .await
            .context("the sandbox's network probe did not answer")?;
        match reply {
            Reply::Probed { open } => {
                if open.iter().any(|name| !asked.contains(name)) {
                    let why = "the front answered a probe with a check that was not asked";
                    self.front.handle().break_wire(why.to_string());
                    bail!("{why}");
                }
                Ok(open)
            }
            Reply::Failed { message } => {
                Err(anyhow!("{message}")).context("the sandbox's network probe did not answer")
            }
            other => Err(broken(&self.front, "a probe", &other)),
        }
    }
}
