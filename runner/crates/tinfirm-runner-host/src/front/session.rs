//! The sandbox's bridge as `run` holds it when the bridge is in the sandbox's front (ADR-0095;
//! ADR-0112, path C, part 2): the run's one entry and its decisions, and [`FrontSession`],
//! which offers what `run` uses of the bridge crate's [`tinfirm_bridge::host::HostSession`].
//!
//! `run` keeps the ticket and the session key, and decides each `hello` that the front hands
//! it with the bridge crate's own rule ([`tinfirm_bridge::host::judge`]): the front applies the
//! decision and holds the link. What comes back from the front is checked again here: an
//! answer of the wrong kind, a command's output over its bound or not text, or a probe's name
//! that was not asked ends the front.

use std::collections::HashMap;
use std::net::IpAddr;
use std::os::fd::OwnedFd;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine as _;
use tinfirm_bridge::control::{Exec, Hidden, ProbeCheck, Signal, Start};
use tinfirm_bridge::host::{self, ExecOutput, Exit, Expect, HostTiming, Output, Verdict};
use tokio::io::AsyncReadExt;
use tokio::net::{TcpListener, UnixStream};
use tokio::sync::{mpsc, oneshot, watch};
use tokio_util::sync::CancellationToken;

use super::sandbox::SandboxFront;
use super::wire::{Admission, Reply, ToFront, WireHello, WireIdentity, WireTiming};

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
    /// From `run`, which dials the bridge ([`BridgeRegistration::dialed`]).
    Dial,
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
}

impl BridgeState {
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
        let descriptors = match peer {
            Peer::Listen(listener) => vec![OwnedFd::from(listener.into_std()?)],
            Peer::Dial => Vec::new(),
        };
        self.handle().send_all(&message, descriptors).await?;
        Ok(BridgeRegistration {
            front: self.clone(),
            state,
            ready,
            ticket,
        })
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
    /// other before its TLS handshake.
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

/// The adapter's stdio once it is started, as [`tinfirm_bridge::host::HostIo`] has it.
pub struct FrontIo {
    /// Writes go to the adapter's stdin; dropping it ends that stdin.
    pub stdin: UnixStream,
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
            stdin,
            output,
            exit: self.exit(),
        })
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
