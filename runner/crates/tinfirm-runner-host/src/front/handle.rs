//! `run`'s side of a front process ([`super::wire`]): it starts the front, holds the control
//! channel, sends descriptors, and ends the front when it is dropped.
//!
//! What a front sends is read as hostile. A frame over [`wire::MAX_TO_MAIN`] (a command's end
//! over [`wire::MAX_EXEC_FRAME`]), a payload that is not one of the wire's messages, a message
//! out of its bounds, a second `ready`, or an answer to nothing `run` asked ends that front at
//! once: its process group is killed, and its handle says why ([`Ended::Broken`]). Nothing else is touched: every front has its own channel,
//! process group and handle. `run` reads the channel with plain reads, so a descriptor that a
//! front sends along is closed by the kernel and never reaches `run`.
//!
//! A front that ends by itself (a crash, or its own refusal) is [`Ended::Exited`], with its
//! exit status and the start of what it wrote on stderr. A front that does not answer within
//! its time is ended too, and so is one whose channel ends inside a frame.
//!
//! Where back-pressure is right and where a deadline is: the control channel carries small
//! messages only, so a message that cannot be written within [`SEND_TIMEOUT`] (the wait for
//! the channel's lock included) ends the front as a broken wire: it does not read, so it is
//! stuck or hostile. A sandbox's bytes (tunnels, request and answer bodies, the bridge's
//! streams in later slices) never go on this channel: each goes on a descriptor of its own,
//! where a front that reads slowly, because its sandbox is slow, only slows that stream down.
//!
//! Its process group is signalled only while the front is not yet reaped: every signal, and
//! the step that reaps the front, happen under one lock, which the reaping step clears, so a
//! group id that the system gave to another process since is never signalled. The reader always runs to the end and reaps the front, also after the
//! handle is dropped.

use std::collections::HashMap;
use std::os::fd::{AsFd, OwnedFd};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, bail};
use tokio::io::{AsyncReadExt, AsyncWriteExt, Interest};
use tokio::net::UnixStream;
use tokio::net::unix::{OwnedReadHalf, OwnedWriteHalf};
use tokio::sync::{oneshot, watch};
use uuid::Uuid;

use crate::confine::{self, Status};

use super::wire::{self, MAX_TO_FRONT, ToFront, ToMain};

/// How long a front has to say that it is ready.
pub const READY_TIMEOUT: Duration = Duration::from_secs(10);
/// How long a round trip or a check may take.
pub const ANSWER_TIMEOUT: Duration = Duration::from_secs(10);
/// How long a message to the front may take to be written, the wait for the channel's lock
/// included: the control channel carries small messages only, so a front that does not take
/// one within this time is stuck or hostile.
pub const SEND_TIMEOUT: Duration = Duration::from_secs(10);
/// How much of a front's stderr is kept for its end.
const STDERR_KEPT: usize = 4 * 1024;

/// How a front is started: this binary's `front` in production; another program in tests.
#[derive(Debug, Clone)]
pub struct FrontCommand {
    pub program: PathBuf,
    pub args: Vec<String>,
    /// The front's whole environment: none in production.
    pub env: Vec<(String, String)>,
    /// Test-only: what `run` sends the front is recorded ([`super::tap`]). `run` never sets
    /// it, and no flag or variable does.
    #[doc(hidden)]
    pub tap: Option<super::tap::FrontTap>,
}

impl FrontCommand {
    /// This binary, `front`.
    pub fn this_binary() -> Result<FrontCommand> {
        Ok(FrontCommand {
            program: std::env::current_exe().context("cannot find this executable")?,
            args: vec![super::SUBCOMMAND.to_string()],
            env: Vec::new(),
            tap: None,
        })
    }
}

/// How a front ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ended {
    /// It broke the wire; `run` ended it.
    Broken(String),
    /// It exited by itself, or after its channel closed.
    Exited {
        code: Option<i32>,
        signal: Option<i32>,
        /// The start of its stderr.
        stderr: String,
    },
}

impl std::fmt::Display for Ended {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Ended::Broken(why) => write!(f, "the front broke its wire and was ended: {why}"),
            Ended::Exited {
                code,
                signal,
                stderr,
            } => {
                match (code, signal) {
                    (Some(code), _) => write!(f, "the front exited with code {code}")?,
                    (None, Some(signal)) => write!(f, "the front was ended by signal {signal}")?,
                    (None, None) => write!(f, "the front ended")?,
                }
                if !stderr.trim().is_empty() {
                    write!(f, ": {}", stderr.trim())?;
                }
                Ok(())
            }
        }
    }
}

/// The answers `run` waits for, by id.
#[derive(Default)]
struct Pending {
    pongs: HashMap<u64, oneshot::Sender<()>>,
    checks: HashMap<u64, oneshot::Sender<()>>,
    /// The front's own requests that are open.
    open: HashMap<u64, Open>,
    /// How many of them are requests, by service.
    requests: HashMap<super::wire::Service, usize>,
    /// The last request id the front used: ids only grow.
    last_req: u64,
    /// `run`'s requests about the sandbox's bridge that wait for their answer, by id.
    answers: HashMap<u64, oneshot::Sender<wire::Reply>>,
    /// The bridge requests that `run` gave up waiting for, oldest first, at most
    /// [`MAX_ABANDONED`]: a late answer to one of them is dropped once.
    abandoned: std::collections::VecDeque<u64>,
    /// The bridge's connections whose `hello` `run` has not yet seen attached: `true` once
    /// `run` admitted it.
    hellos: HashMap<u64, bool>,
    /// The adapter's end and the session's end were said: each once.
    exited: bool,
    session_ended: bool,
}

/// A request of the front's that is open.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Open {
    /// A `connect`: `given` once `run` gave the connection, until the front says it ended.
    Connect { given: bool },
    /// A `request` (of the model proxy, the git proxy or the platform tools), until `run`
    /// ends it: its service, whether its socket went to the front, its answer's head did, its
    /// body ended, and the front gave it up.
    Request {
        service: super::wire::Service,
        body: bool,
        responded: bool,
        body_ended: bool,
        aborted: bool,
    },
}

/// What a front asks `run` for ([`FrontHandle::take_requests`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrontRequest {
    /// A connection to `host:port`, for the egress proxy: answer with
    /// [`FrontHandle::grant`] or [`FrontHandle::refuse`].
    Connect { req: u64, host: String, port: u16 },
    /// The connection given for `req` has ended.
    Closed { req: u64 },
    /// A model request: answer with [`FrontHandle::body`], [`FrontHandle::respond`] and
    /// [`FrontHandle::end`].
    Request {
        req: u64,
        head: crate::model_proxy::RequestHead,
    },
    /// The body of request `req` is whole on its socket.
    BodyEnd { req: u64 },
    /// The front gave request `req` up: end it.
    Abort { req: u64 },
    /// The front answered a request itself.
    Declined {
        service: super::wire::Service,
        why: crate::model_proxy::Declined,
    },
    /// A connection to the sandbox's bridge said `hello`: answer with [`FrontHandle::admit`].
    Hello {
        req: u64,
        hello: super::wire::WireHello,
    },
    /// The connection of `req` was admitted, and its link took it or not.
    Admitted { req: u64, attached: bool },
    /// The adapter ended.
    Exited {
        code: Option<i32>,
        signal: Option<i32>,
    },
    /// The bridge's session ended.
    SessionEnded,
    /// The session's link has a connection to the sandbox's bridge, or has lost it.
    Link { up: bool },
}

/// The bridge requests given up on by a timeout whose late answers `run` still takes (and
/// drops). Past this, the oldest is forgotten, and its answer, should it still come, ends the
/// front: a front that leaves this many requests unanswered beyond their time is stuck.
pub const MAX_ABANDONED: usize = 64;

/// The front's requests that wait for their consumer.
const REQUEST_QUEUE: usize = 64;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// The front's process group while it may be signalled, and why `run` broke its wire.
struct Life {
    /// `Some` until the front is about to be reaped; signals go only under this lock.
    group: Mutex<Option<u32>>,
    /// Set when `run` ends the front for a reason of its own (a request without an answer).
    broken: Mutex<Option<String>>,
    /// Set when `run` itself ends the front because it no longer wants it (its last owner went):
    /// what the front sends or how its channel ends after that is no fault of the front's.
    ending: std::sync::atomic::AtomicBool,
}

impl Life {
    /// Kills the front's group, if it is not reaped yet.
    fn kill(&self) {
        if let Some(pgid) = *lock(&self.group) {
            confine::kill_group(pgid);
        }
    }

    /// Ends the front for `why`, which its end then reports.
    fn break_wire(&self, why: String) {
        lock(&self.broken).get_or_insert(why);
        self.kill();
    }
}

/// A front process and its control channel.
pub struct FrontHandle {
    pgid: u32,
    status: Status,
    writer: tokio::sync::Mutex<OwnedWriteHalf>,
    pending: Arc<Mutex<Pending>>,
    next_id: Arc<AtomicU64>,
    ended: watch::Receiver<Option<Ended>>,
    life: Arc<Life>,
    answer_timeout: Duration,
    send_timeout: Duration,
    requests: Mutex<Option<tokio::sync::mpsc::Receiver<FrontRequest>>>,
    tap: Option<super::tap::TapFront>,
}

impl std::fmt::Debug for FrontHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("FrontHandle")
            .field("pgid", &self.pgid)
            .finish_non_exhaustive()
    }
}

impl FrontHandle {
    /// Starts a front for `run_id`: an empty environment (but `command.env`), its own process
    /// group, the control channel as its stdin, nothing on stdout. Waits for its `ready`, then
    /// sends `init`.
    pub async fn spawn(command: &FrontCommand, run_id: Uuid) -> Result<FrontHandle> {
        let (ours, theirs) =
            std::os::unix::net::UnixStream::pair().context("cannot make the control channel")?;
        let mut process = tokio::process::Command::new(&command.program);
        process
            .args(&command.args)
            .env_clear()
            .envs(command.env.iter().map(|(k, v)| (k, v)))
            .current_dir("/")
            .stdin(Stdio::from(OwnedFd::from(theirs)))
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .process_group(0);
        let mut child = process
            .spawn()
            .with_context(|| format!("cannot start the front {}", command.program.display()))?;
        // The command holds the front's end of the channel until it is dropped: without this,
        // a front that exits would not end its channel.
        drop(process);
        let pgid = child.id().context("the front has no pid")?;
        let life = Arc::new(Life {
            group: Mutex::new(Some(pgid)),
            broken: Mutex::new(None),
            ending: std::sync::atomic::AtomicBool::new(false),
        });
        let stderr = Arc::new(Mutex::new(Vec::new()));
        if let Some(mut pipe) = child.stderr.take() {
            let kept = stderr.clone();
            tokio::spawn(async move {
                let mut buffer = [0u8; 1024];
                while let Ok(read) = pipe.read(&mut buffer).await {
                    if read == 0 {
                        break;
                    }
                    let mut kept = lock(&kept);
                    let room = STDERR_KEPT.saturating_sub(kept.len());
                    kept.extend_from_slice(&buffer[..read.min(room)]);
                }
            });
        }
        ours.set_nonblocking(true)?;
        let (mut reader, writer) = UnixStream::from_std(ours)?.into_split();
        let ready = match tokio::time::timeout(READY_TIMEOUT, read_message(&mut reader)).await {
            Ok(Ok(Some(ToMain::Ready { status }))) => Ok(status),
            Ok(Ok(Some(other))) => Err(format!(
                "the front's first message was not `ready` but {}",
                kind(&other)
            )),
            Ok(Ok(None)) => Err("the front ended before it was ready".to_string()),
            Ok(Err(e)) => Err(format!(
                "the front broke its wire before it was ready: {e:#}"
            )),
            Err(_) => Err(format!(
                "the front was not ready within {} s",
                READY_TIMEOUT.as_secs()
            )),
        };
        let status = match ready {
            Ok(status) => status,
            Err(why) => {
                // Ended and reaped here: nothing of it outlives the failed start.
                let _ = reap(&life, &mut child, true).await;
                let stderr = String::from_utf8_lossy(&lock(&stderr)).trim().to_string();
                if stderr.is_empty() {
                    bail!("{why}");
                }
                bail!("{why}: {stderr}");
            }
        };
        let pending = Arc::new(Mutex::new(Pending::default()));
        let (ended_tx, ended) = watch::channel(None);
        let (requests_tx, requests) = tokio::sync::mpsc::channel(REQUEST_QUEUE);
        let next_id = Arc::new(AtomicU64::new(1));
        tokio::spawn(read_loop(
            reader,
            child,
            pending.clone(),
            ended_tx,
            stderr,
            life.clone(),
            requests_tx,
            next_id.clone(),
        ));
        let handle = FrontHandle {
            pgid,
            status,
            writer: tokio::sync::Mutex::new(writer),
            pending,
            next_id,
            ended,
            life,
            answer_timeout: ANSWER_TIMEOUT,
            send_timeout: SEND_TIMEOUT,
            requests: Mutex::new(Some(requests)),
            tap: command.tap.as_ref().map(|tap| tap.front(pgid)),
        };
        handle.send(&ToFront::Init { run: run_id }, None).await?;
        Ok(handle)
    }

    /// The front's confinement, as it reported it.
    pub fn status(&self) -> &Status {
        &self.status
    }

    /// The front's process group.
    pub fn pgid(&self) -> u32 {
        self.pgid
    }

    /// How long a round trip or a check may take from now on (tests shorten it).
    pub fn set_answer_timeout(&mut self, timeout: Duration) {
        self.answer_timeout = timeout;
    }

    /// How long a message may take to be written from now on (tests shorten it).
    pub fn set_send_timeout(&mut self, timeout: Duration) {
        self.send_timeout = timeout;
    }

    /// The process group that the handle would still signal: `None` once the front is reaped.
    pub fn signalled_group(&self) -> Option<u32> {
        *lock(&self.life.group)
    }

    /// The requests that wait for the front's answer.
    pub fn waiting(&self) -> usize {
        let pending = lock(&self.pending);
        pending.pongs.len() + pending.checks.len()
    }

    /// How the front ended, once it has.
    pub fn ended(&self) -> Option<Ended> {
        self.ended.borrow().clone()
    }

    /// Resolves once the front has ended.
    pub async fn wait_ended(&self) -> Ended {
        let mut ended = self.ended.clone();
        loop {
            if let Some(how) = ended.borrow_and_update().clone() {
                return how;
            }
            if ended.changed().await.is_err() {
                return Ended::Broken("its handle's reader stopped".into());
            }
        }
    }

    /// Sends `message`, with `descriptor` when the message carries one.
    pub async fn send(&self, message: &ToFront, descriptor: Option<OwnedFd>) -> Result<()> {
        self.send_all(message, descriptor.into_iter().collect())
            .await
    }

    /// Sends `message` with the descriptors it carries, in order.
    pub async fn send_all(&self, message: &ToFront, descriptors: Vec<OwnedFd>) -> Result<()> {
        if message.descriptors() != descriptors.len() {
            bail!("a message of the wire with descriptors it does not carry, or without them");
        }
        // The front checks the same bounds and exits on a message out of them. A field that
        // `run` did not make itself (a path of a tool call, an upstream's status) can be out of
        // them: the message does not go, its caller fails, and the front lives.
        message.check().map_err(wire::WireError::OutOfBounds)?;
        if let Some(ended) = self.ended() {
            bail!("{ended}");
        }
        let frame = wire::encode(message, MAX_TO_FRONT)?;
        let descriptors = match &self.tap {
            Some(tap) => tap.sent(message, &frame, descriptors)?,
            None => descriptors,
        };
        let write = async {
            let mut writer = self.writer.lock().await;
            let stream: &UnixStream = (*writer).as_ref();
            let written = stream
                .async_io(Interest::WRITABLE, || {
                    wire::send_with(stream.as_fd(), &frame, &descriptors)
                })
                .await
                .context("cannot write to the front")?;
            writer
                .write_all(&frame[written..])
                .await
                .context("cannot write to the front")
        };
        match tokio::time::timeout(self.send_timeout, write).await {
            Ok(written) => written?,
            Err(_) => {
                // A front that does not read its channel: back-pressure is for the streams of
                // a sandbox's bytes, each on its own descriptor, never for this channel.
                let why = format!(
                    "the front did not take a message within {:?}",
                    self.send_timeout
                );
                self.life.break_wire(why.clone());
                bail!("{why}");
            }
        }
        // The descriptors are the front's now; `run`'s copies go.
        drop(descriptors);
        Ok(())
    }

    /// The front's requests, in order: taken once, by whoever serves them. They wait (and hold
    /// the front back) while no one takes them.
    pub fn take_requests(&self) -> Option<tokio::sync::mpsc::Receiver<FrontRequest>> {
        lock(&self.requests).take()
    }

    /// Gives the front the connection for its request `req`.
    pub async fn grant(&self, req: u64, connection: OwnedFd) -> Result<()> {
        match lock(&self.pending).open.get_mut(&req) {
            Some(Open::Connect {
                given: given @ false,
            }) => *given = true,
            _ => bail!("no open request {req} of the front waits for a connection"),
        }
        self.send(&ToFront::Connected { req }, Some(connection))
            .await
    }

    /// Refuses the front's request `req`, with what its client is to get.
    pub async fn refuse(&self, req: u64, refusal: &crate::egress_proxy::Refusal) -> Result<()> {
        {
            let mut pending = lock(&self.pending);
            match pending.open.get(&req) {
                Some(Open::Connect { given: false }) => {
                    pending.open.remove(&req);
                }
                _ => bail!("no open request {req} of the front waits for an answer"),
            }
        }
        let mut message: String = refusal.message.chars().take(wire::MAX_TEXT / 4).collect();
        message.truncate(wire::MAX_TEXT);
        self.send(
            &ToFront::Refused {
                req,
                status: refusal.status,
                message,
                off_allowlist: refusal.off_allowlist,
            },
            None,
        )
        .await
    }

    /// The front's requests that are open: asked, or given and not yet ended.
    pub fn open_requests(&self) -> usize {
        lock(&self.pending).open.len()
    }

    /// Gives the front the socket of its model request `req`, on which it writes the body.
    pub async fn body(&self, req: u64, socket: OwnedFd) -> Result<()> {
        match lock(&self.pending).open.get_mut(&req) {
            Some(Open::Request {
                body: body @ false, ..
            }) => *body = true,
            _ => bail!("no open model request {req} of the front waits for its socket"),
        }
        self.send(&ToFront::Body { req }, Some(socket)).await
    }

    /// The answer's head for the model request `req`, once its socket is the front's.
    pub async fn respond(&self, req: u64, head: crate::model_proxy::ResponseHead) -> Result<()> {
        match lock(&self.pending).open.get_mut(&req) {
            Some(Open::Request {
                body: true,
                responded: responded @ false,
                ..
            }) => *responded = true,
            _ => bail!("no open model request {req} of the front waits for its answer"),
        }
        self.send(&ToFront::Response { req, head }, None).await
    }

    /// Ends the model request `req`: its answer's body ended whole (`ok`), or not.
    pub async fn end(&self, req: u64, ok: bool) -> Result<()> {
        {
            let mut pending = lock(&self.pending);
            match pending.open.get(&req) {
                Some(Open::Request { service, .. }) => {
                    let service = *service;
                    pending.open.remove(&req);
                    if let Some(open) = pending.requests.get_mut(&service) {
                        *open -= 1;
                    }
                }
                _ => bail!("no open model request {req} of the front to end"),
            }
        }
        self.send(&ToFront::End { req, ok }, None).await
    }

    /// `run`'s decision on the `hello` of the front's request `req`.
    pub async fn admit(&self, req: u64, admission: super::wire::Admission) -> Result<()> {
        match lock(&self.pending).hellos.get_mut(&req) {
            Some(admitted @ false) => *admitted = true,
            _ => bail!("no hello {req} of the front waits for its admission"),
        }
        self.send(&ToFront::Admit { req, admission }, None).await
    }

    /// A request about the sandbox's bridge, `make` of its id, with its descriptors: the
    /// front's answer within `timeout`. A request that is not answered in time fails, and the
    /// front lives on: its sandbox may be slow, and an answer that comes later is dropped.
    pub async fn ask(
        &self,
        make: impl FnOnce(u64) -> ToFront,
        descriptors: Vec<OwnedFd>,
        timeout: Duration,
    ) -> Result<wire::Reply> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        lock(&self.pending).answers.insert(id, tx);
        let mut timed_out = false;
        let answered = match self.send_all(&make(id), descriptors).await {
            Ok(()) => {
                tokio::select! {
                    answered = tokio::time::timeout(timeout, rx) => match answered {
                        Ok(Ok(reply)) => Ok(reply),
                        Ok(Err(_)) => Err(anyhow::anyhow!("{}", self.ended().map_or_else(|| "the front ended".to_string(), |e| e.to_string()))),
                        Err(_) => {
                            timed_out = true;
                            Err(anyhow::anyhow!("the sandbox's front did not answer within {} s", timeout.as_secs()))
                        }
                    },
                    ended = self.wait_ended() => Err(anyhow::anyhow!("{ended}")),
                }
            }
            Err(e) => Err(e),
        };
        let mut pending = lock(&self.pending);
        // A request given up on by its timeout may still get its answer, once: its sandbox is
        // slow, not the front wrong. One whose answer came meanwhile has none to wait for.
        if pending.answers.remove(&id).is_some() && timed_out {
            if pending.abandoned.len() >= MAX_ABANDONED {
                pending.abandoned.pop_front();
            }
            pending.abandoned.push_back(id);
        }
        drop(pending);
        answered
    }

    /// Ends the front for `why`, a rule of `run`'s that it broke.
    pub fn break_wire(&self, why: String) {
        self.life.break_wire(why);
    }

    /// `run`'s requests about the bridge that wait for an answer, and the `hello`s that wait
    /// for their end.
    pub fn bridge_waiting(&self) -> (usize, usize) {
        let pending = lock(&self.pending);
        (pending.answers.len(), pending.hellos.len())
    }

    /// The bridge requests given up on whose late answer `run` would still drop once.
    pub fn abandoned(&self) -> usize {
        lock(&self.pending).abandoned.len()
    }

    /// One round trip.
    pub async fn ping(&self) -> Result<()> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        lock(&self.pending).pongs.insert(id, tx);
        let answered = match self.send(&ToFront::Ping { id }, None).await {
            Ok(()) => self.answer(rx).await,
            Err(e) => Err(e),
        };
        // An answer that comes after this is to nothing asked: the wire is broken then.
        lock(&self.pending).pongs.remove(&id);
        answered
    }

    /// Sends the front a descriptor and reads back what it writes on it: descriptors arrive.
    pub async fn check_descriptor(&self) -> Result<()> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (ours, theirs) = std::os::unix::net::UnixStream::pair()?;
        ours.set_nonblocking(true)?;
        let mut ours = UnixStream::from_std(ours)?;
        let (tx, rx) = oneshot::channel();
        lock(&self.pending).checks.insert(id, tx);
        let answered = match self
            .send(&ToFront::Check { id }, Some(OwnedFd::from(theirs)))
            .await
        {
            Ok(()) => self.answer(rx).await,
            Err(e) => Err(e),
        };
        lock(&self.pending).checks.remove(&id);
        answered?;
        let mut reply = Vec::new();
        tokio::time::timeout(
            ANSWER_TIMEOUT,
            (&mut ours)
                .take(wire::CHECK_REPLY.len() as u64 + 1)
                .read_to_end(&mut reply),
        )
        .await
        .context("the front did not close the descriptor of its check")??;
        if reply != wire::CHECK_REPLY {
            bail!("the front wrote something else on the descriptor of its check");
        }
        Ok(())
    }

    /// Waits for an answer. A front that does not answer in time is ended: what it would
    /// still send could only be an answer to nothing asked.
    async fn answer(&self, rx: oneshot::Receiver<()>) -> Result<()> {
        tokio::select! {
            answered = tokio::time::timeout(self.answer_timeout, rx) => match answered {
                Ok(Ok(())) => Ok(()),
                Ok(Err(_)) => bail!("{}", self.ended().map_or_else(|| "the front ended".to_string(), |e| e.to_string())),
                Err(_) => {
                    let why = format!("the front did not answer within {:?}", self.answer_timeout);
                    self.life.break_wire(why.clone());
                    bail!("{why}")
                }
            },
            ended = self.wait_ended() => bail!("{ended}"),
        }
    }
}

impl FrontHandle {
    /// `run` no longer wants the front: from now on its end is an ordinary one, whatever the
    /// front still sends (an end of a connection that its listener's close ended) and however
    /// its channel ends (a reset, when the kill finds bytes of `run`'s unread).
    pub fn ending(&self) {
        self.life
            .ending
            .store(true, std::sync::atomic::Ordering::SeqCst);
    }

    /// A watch on the front's end that outlives the handle.
    pub fn end_watch(&self) -> EndWatch {
        EndWatch(self.ended.clone())
    }
}

/// How a front ends, also once its handle is gone ([`FrontHandle::end_watch`]).
pub struct EndWatch(watch::Receiver<Option<Ended>>);

impl EndWatch {
    pub async fn wait(mut self) -> Ended {
        loop {
            if let Some(how) = self.0.borrow_and_update().clone() {
                return how;
            }
            if self.0.changed().await.is_err() {
                return Ended::Broken("its handle's reader stopped".into());
            }
        }
    }
}

impl Drop for FrontHandle {
    fn drop(&mut self) {
        // Its channel closes with the handle, and the front exits on that; its group goes too,
        // whatever it is doing, unless it is reaped already. The reader goes on and reaps it.
        // An end that `run` causes is no broken wire.
        self.ending();
        self.life.kill();
    }
}

/// Reaps the front, `kill`ing its group first if asked, so that no signal can reach a group id
/// given to another process since: each look at it (`try_wait`, which reaps) and each kill
/// happens under the group's lock, and the lock is cleared in the same step that reaps it. It
/// is called once the channel has ended, so the front is gone or going, and looks every 10 ms
/// until then.
async fn reap(
    life: &Life,
    child: &mut tokio::process::Child,
    kill: bool,
) -> std::io::Result<std::process::ExitStatus> {
    let mut killed = !kill;
    loop {
        {
            let mut group = lock(&life.group);
            if !killed {
                if let Some(pgid) = *group {
                    confine::kill_group(pgid);
                }
                killed = true;
            }
            match child.try_wait() {
                Ok(Some(status)) => {
                    group.take();
                    return Ok(status);
                }
                Ok(None) => {}
                Err(e) => {
                    group.take();
                    return Err(e);
                }
            }
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}

fn kind(message: &ToMain) -> &'static str {
    match message {
        ToMain::Ready { .. } => "ready",
        ToMain::Pong { .. } => "pong",
        ToMain::Checked { .. } => "checked",
        ToMain::Connect { .. } => "connect",
        ToMain::Closed { .. } => "closed",
        ToMain::Request { .. } => "request",
        ToMain::BodyEnd { .. } => "bodyEnd",
        ToMain::Abort { .. } => "abort",
        ToMain::Declined { .. } => "declined",
        ToMain::Hello { .. } => "hello",
        ToMain::Admitted { .. } => "admitted",
        ToMain::Exited { .. } => "exited",
        ToMain::SessionEnded {} => "sessionEnded",
        ToMain::Link { .. } => "link",
        ToMain::Answer { .. } => "answer",
    }
}

/// The next message from the front: `Ok(None)` at the end of its channel.
async fn read_message(reader: &mut OwnedReadHalf) -> Result<Option<ToMain>> {
    let mut header = [0u8; wire::HEADER];
    let mut got = 0;
    while got < wire::HEADER {
        match reader.read(&mut header[got..]).await? {
            // An end before any byte of a frame is the channel's end; within one it is not.
            0 if got == 0 => return Ok(None),
            0 => bail!("the channel ended inside a frame's header"),
            read => got += read,
        }
    }
    let length = wire::frame_length(header, wire::MAX_EXEC_FRAME)?;
    let mut payload = vec![0u8; length];
    reader
        .read_exact(&mut payload)
        .await
        .context("the channel ended within a frame")?;
    Ok(Some(wire::decode_to_main(&payload)?))
}

/// What a message about the front's own requests is.
enum Own {
    /// Not such a message: an answer to `run`.
    Not,
    /// One that `run` lets go: about a request it ended already.
    Nothing,
    /// One for whoever serves the front's requests.
    Request(FrontRequest),
}

/// What a message of the front's own requests asks `run` for; `Err` for one that breaks the
/// wire's rules.
fn own_request(pending: &mut Pending, message: &ToMain) -> std::result::Result<Own, String> {
    let new = |pending: &mut Pending, req: u64| {
        if req <= pending.last_req {
            return Err(format!("a request {req} after {}", pending.last_req));
        }
        pending.last_req = req;
        Ok(())
    };
    // An answer about a request that `run` has ended already (a client that left while `run`
    // answered) is nothing; one about a request that never was breaks the wire.
    let unknown = |pending: &Pending, req: u64, what: &str| {
        if req <= pending.last_req {
            Ok(Own::Nothing)
        } else {
            Err(format!("{what} of an unknown request {req}"))
        }
    };
    match message {
        ToMain::Connect { req, host, port } => {
            new(pending, *req)?;
            let connects = pending.open.len() - pending.requests.values().sum::<usize>();
            if connects >= wire::MAX_FRONT_REQUESTS {
                return Err(format!(
                    "more than {} open requests",
                    wire::MAX_FRONT_REQUESTS
                ));
            }
            pending.open.insert(*req, Open::Connect { given: false });
            Ok(Own::Request(FrontRequest::Connect {
                req: *req,
                host: host.clone(),
                port: *port,
            }))
        }
        ToMain::Closed { req } => match pending.open.get(req) {
            Some(Open::Connect { given: true }) => {
                pending.open.remove(req);
                Ok(Own::Request(FrontRequest::Closed { req: *req }))
            }
            Some(Open::Connect { given: false }) => {
                Err(format!("request {req} closed before it was given"))
            }
            _ => Err(format!("an unknown request {req} closed")),
        },
        ToMain::Request { req, head } => {
            new(pending, *req)?;
            let service = head.target.service();
            let open = pending.requests.entry(service).or_insert(0);
            if *open >= wire::MAX_SERVICE_REQUESTS {
                return Err(format!(
                    "more than {} open {} requests",
                    wire::MAX_SERVICE_REQUESTS,
                    match service {
                        super::wire::Service::Model => "model",
                        _ => "git",
                    }
                ));
            }
            *open += 1;
            pending.open.insert(
                *req,
                Open::Request {
                    service,
                    body: false,
                    responded: false,
                    body_ended: false,
                    aborted: false,
                },
            );
            Ok(Own::Request(FrontRequest::Request {
                req: *req,
                head: head.clone(),
            }))
        }
        ToMain::BodyEnd { req } => match pending.open.get_mut(req) {
            Some(Open::Request {
                body: true,
                body_ended: ended @ false,
                ..
            }) => {
                *ended = true;
                Ok(Own::Request(FrontRequest::BodyEnd { req: *req }))
            }
            Some(Open::Request { body: false, .. }) => Err(format!(
                "the body of request {req} ended before it had a socket"
            )),
            Some(Open::Request { .. }) => Err(format!("the body of request {req} ended twice")),
            Some(Open::Connect { .. }) => Err(format!("the body of a connect {req} ended")),
            None => unknown(pending, *req, "the body's end"),
        },
        ToMain::Abort { req } => match pending.open.get_mut(req) {
            Some(Open::Request {
                aborted: aborted @ false,
                ..
            }) => {
                *aborted = true;
                Ok(Own::Request(FrontRequest::Abort { req: *req }))
            }
            Some(Open::Request { .. }) => Ok(Own::Nothing),
            Some(Open::Connect { .. }) => Err(format!("an abort of a connect {req}")),
            None => unknown(pending, *req, "an abort"),
        },
        ToMain::Declined { service, why } => Ok(Own::Request(FrontRequest::Declined {
            service: *service,
            why: *why,
        })),
        ToMain::Hello { req, hello } => {
            new(pending, *req)?;
            if pending.hellos.len() >= wire::MAX_HELLOS {
                return Err(format!(
                    "more than {} hellos of the bridge at once",
                    wire::MAX_HELLOS
                ));
            }
            pending.hellos.insert(*req, false);
            Ok(Own::Request(FrontRequest::Hello {
                req: *req,
                hello: hello.clone(),
            }))
        }
        ToMain::Admitted { req, attached } => match pending.hellos.get(req) {
            Some(true) => {
                pending.hellos.remove(req);
                Ok(Own::Request(FrontRequest::Admitted {
                    req: *req,
                    attached: *attached,
                }))
            }
            Some(false) => Err(format!("hello {req} attached before it was admitted")),
            None => Err(format!("an unknown hello {req} attached")),
        },
        ToMain::Exited { code, signal } => {
            if pending.exited {
                return Err("the adapter ended twice".into());
            }
            pending.exited = true;
            Ok(Own::Request(FrontRequest::Exited {
                code: *code,
                signal: *signal,
            }))
        }
        ToMain::SessionEnded {} => {
            if pending.session_ended {
                return Err("the bridge's session ended twice".into());
            }
            pending.session_ended = true;
            Ok(Own::Request(FrontRequest::SessionEnded))
        }
        ToMain::Link { up } => {
            if pending.session_ended {
                return Err("the link of a session that ended".into());
            }
            Ok(Own::Request(FrontRequest::Link { up: *up }))
        }
        ToMain::Ready { .. }
        | ToMain::Pong { .. }
        | ToMain::Checked { .. }
        | ToMain::Answer { .. } => Ok(Own::Not),
    }
}

#[allow(clippy::too_many_arguments)]
async fn read_loop(
    mut reader: OwnedReadHalf,
    mut child: tokio::process::Child,
    pending: Arc<Mutex<Pending>>,
    ended: watch::Sender<Option<Ended>>,
    stderr: Arc<Mutex<Vec<u8>>>,
    life: Arc<Life>,
    requests: tokio::sync::mpsc::Sender<FrontRequest>,
    next_id: Arc<AtomicU64>,
) {
    let broken = loop {
        let message = match read_message(&mut reader).await {
            Ok(Some(message)) => message,
            Ok(None) => break None,
            Err(e) => break Some(format!("{e:#}")),
        };
        // The front's own requests: in order, bounded, and each closed only once given.
        let request = match own_request(&mut lock(&pending), &message) {
            Ok(request) => request,
            Err(why) => break Some(why),
        };
        match request {
            Own::Not => {}
            Own::Nothing => continue,
            Own::Request(request) => {
                if requests.send(request).await.is_err() {
                    break Some("no one serves the front's requests".to_string());
                }
                continue;
            }
        }
        // An answer about the bridge: to a request that waits, or to one `run` gave up
        // waiting for (its sandbox was slow), which is dropped; to one never asked, it breaks
        // the wire.
        if let ToMain::Answer { id, reply } = &message {
            let mut pending = lock(&pending);
            match pending.answers.remove(id) {
                Some(tx) => {
                    let _ = tx.send(reply.clone());
                    continue;
                }
                None => {
                    // A late answer to a request `run` gave up on: dropped, and its id
                    // forgotten. Any other answer is to nothing that waits for one: a request
                    // never asked, one answered already, or a message of another kind.
                    if let Some(at) = pending.abandoned.iter().position(|a| a == id) {
                        pending.abandoned.remove(at);
                        continue;
                    }
                    if *id >= next_id.load(Ordering::Relaxed) {
                        break Some(format!("an answer to nothing asked ({id})"));
                    }
                    break Some(format!("an answer to a request that waits for none ({id})"));
                }
            }
        }
        let answered = {
            let mut pending = lock(&pending);
            match message {
                ToMain::Ready { .. }
                | ToMain::Connect { .. }
                | ToMain::Closed { .. }
                | ToMain::Request { .. }
                | ToMain::BodyEnd { .. }
                | ToMain::Abort { .. }
                | ToMain::Declined { .. }
                | ToMain::Hello { .. }
                | ToMain::Admitted { .. }
                | ToMain::Exited { .. }
                | ToMain::SessionEnded {}
                | ToMain::Link { .. }
                | ToMain::Answer { .. } => None,
                ToMain::Pong { id } => pending.pongs.remove(&id),
                ToMain::Checked { id } => pending.checks.remove(&id),
            }
        };
        match answered {
            Some(tx) => {
                let _ = tx.send(());
            }
            None => break Some(format!("an unasked {}", kind(&message))),
        }
    };
    // Only a front that breaks the rules while `run` still wants it broke its wire; what the
    // read sees once `run` ended the front itself follows from that end.
    let broken = match broken {
        Some(why) if life.ending.load(std::sync::atomic::Ordering::SeqCst) => {
            tracing::debug!(pid = ?child.id(), why = %why, "a front that run ended stopped");
            None
        }
        other => other,
    };
    if let Some(why) = &broken {
        tracing::warn!(pid = ?child.id(), why = %why, "a front broke its wire; it is ended");
    }
    // A front whose wire broke is killed before it is reaped; one that closed its channel is
    // waited for, and killed if it stays (it may not keep anything running).
    let status = reap(&life, &mut child, true).await;
    // Its stderr is read to its end, or the group's kill ended it.
    tokio::time::sleep(Duration::from_millis(50)).await;
    // When `run` ended the front, its reason is the one to report: what the read saw after
    // the kill (a reset of the channel) follows from it.
    let broken = lock(&life.broken).take().or(broken);
    let how = match broken {
        Some(why) => Ended::Broken(why),
        None => {
            use std::os::unix::process::ExitStatusExt;
            let stderr = String::from_utf8_lossy(&lock(&stderr)).into_owned();
            match status {
                Ok(status) => Ended::Exited {
                    code: status.code(),
                    signal: status.signal(),
                    stderr,
                },
                Err(_) => Ended::Exited {
                    code: None,
                    signal: None,
                    stderr,
                },
            }
        }
    };
    let mut pending = lock(&pending);
    pending.pongs.clear();
    pending.checks.clear();
    pending.open.clear();
    pending.requests.clear();
    pending.answers.clear();
    pending.abandoned.clear();
    pending.hellos.clear();
    drop(pending);
    let _ = ended.send(Some(how));
}
