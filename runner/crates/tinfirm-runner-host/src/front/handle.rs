//! `run`'s side of a front process ([`super::wire`]): it starts the front, holds the control
//! channel, sends descriptors, and ends the front when it is dropped.
//!
//! What a front sends is read as hostile. A frame over [`MAX_TO_MAIN`], a payload that is not
//! one of the wire's messages, a message out of its bounds, a second `ready`, or an answer to
//! nothing `run` asked ends that front at once: its process group is killed, and its handle
//! says why ([`Ended::Broken`]). Nothing else is touched: every front has its own channel,
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

use super::wire::{self, MAX_TO_FRONT, MAX_TO_MAIN, ToFront, ToMain};

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
}

impl FrontCommand {
    /// This binary, `front`.
    pub fn this_binary() -> Result<FrontCommand> {
        Ok(FrontCommand {
            program: std::env::current_exe().context("cannot find this executable")?,
            args: vec![super::SUBCOMMAND.to_string()],
            env: Vec::new(),
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
    /// The front's own requests (`connect`) that are open: `true` once `run` gave the
    /// connection, until the front says it ended.
    connects: HashMap<u64, bool>,
    /// The last request id the front used: ids only grow.
    last_req: u64,
}

/// What a front asks `run` for ([`FrontHandle::take_requests`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrontRequest {
    /// A connection to `host:port`, for the egress proxy: answer with
    /// [`FrontHandle::grant`] or [`FrontHandle::refuse`].
    Connect { req: u64, host: String, port: u16 },
    /// The connection given for `req` has ended.
    Closed { req: u64 },
}

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
    next_id: AtomicU64,
    ended: watch::Receiver<Option<Ended>>,
    life: Arc<Life>,
    answer_timeout: Duration,
    send_timeout: Duration,
    requests: Mutex<Option<tokio::sync::mpsc::Receiver<FrontRequest>>>,
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
        tokio::spawn(read_loop(
            reader,
            child,
            pending.clone(),
            ended_tx,
            stderr,
            life.clone(),
            requests_tx,
        ));
        let handle = FrontHandle {
            pgid,
            status,
            writer: tokio::sync::Mutex::new(writer),
            pending,
            next_id: AtomicU64::new(1),
            ended,
            life,
            answer_timeout: ANSWER_TIMEOUT,
            send_timeout: SEND_TIMEOUT,
            requests: Mutex::new(Some(requests)),
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
        if message.carries_descriptor() != descriptor.is_some() {
            bail!("a message of the wire with a descriptor it does not carry, or without one");
        }
        if let Some(ended) = self.ended() {
            bail!("{ended}");
        }
        let frame = wire::encode(message, MAX_TO_FRONT)?;
        let write = async {
            let mut writer = self.writer.lock().await;
            let stream: &UnixStream = (*writer).as_ref();
            let written = stream
                .async_io(Interest::WRITABLE, || {
                    wire::send_with(stream.as_fd(), &frame, descriptor.as_ref())
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
        // The descriptor is the front's now; `run`'s copy goes.
        drop(descriptor);
        Ok(())
    }

    /// The front's requests, in order: taken once, by whoever serves them. They wait (and hold
    /// the front back) while no one takes them.
    pub fn take_requests(&self) -> Option<tokio::sync::mpsc::Receiver<FrontRequest>> {
        lock(&self.requests).take()
    }

    /// Gives the front the connection for its request `req`.
    pub async fn grant(&self, req: u64, connection: OwnedFd) -> Result<()> {
        match lock(&self.pending).connects.get_mut(&req) {
            Some(granted @ false) => *granted = true,
            _ => bail!("no open request {req} of the front waits for a connection"),
        }
        self.send(&ToFront::Connected { req }, Some(connection))
            .await
    }

    /// Refuses the front's request `req`, with what its client is to get.
    pub async fn refuse(&self, req: u64, refusal: &crate::egress_proxy::Refusal) -> Result<()> {
        match lock(&self.pending).connects.remove(&req) {
            Some(false) => {}
            _ => bail!("no open request {req} of the front waits for an answer"),
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
        lock(&self.pending).connects.len()
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

impl Drop for FrontHandle {
    fn drop(&mut self) {
        // Its channel closes with the handle, and the front exits on that; its group goes too,
        // whatever it is doing, unless it is reaped already. The reader goes on and reaps it.
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
    let length = wire::frame_length(header, MAX_TO_MAIN)?;
    let mut payload = vec![0u8; length];
    reader
        .read_exact(&mut payload)
        .await
        .context("the channel ended within a frame")?;
    Ok(Some(wire::decode_to_main(&payload)?))
}

async fn read_loop(
    mut reader: OwnedReadHalf,
    mut child: tokio::process::Child,
    pending: Arc<Mutex<Pending>>,
    ended: watch::Sender<Option<Ended>>,
    stderr: Arc<Mutex<Vec<u8>>>,
    life: Arc<Life>,
    requests: tokio::sync::mpsc::Sender<FrontRequest>,
) {
    let broken = loop {
        let message = match read_message(&mut reader).await {
            Ok(Some(message)) => message,
            Ok(None) => break None,
            Err(e) => break Some(format!("{e:#}")),
        };
        // The front's own requests: in order, bounded, and each closed only once given.
        let request = {
            let mut pending = lock(&pending);
            match &message {
                ToMain::Connect { req, host, port } => {
                    if *req <= pending.last_req {
                        break Some(format!("a request {req} after {}", pending.last_req));
                    }
                    if pending.connects.len() >= wire::MAX_FRONT_REQUESTS {
                        break Some(format!(
                            "more than {} open requests",
                            wire::MAX_FRONT_REQUESTS
                        ));
                    }
                    pending.last_req = *req;
                    pending.connects.insert(*req, false);
                    Some(FrontRequest::Connect {
                        req: *req,
                        host: host.clone(),
                        port: *port,
                    })
                }
                ToMain::Closed { req } => match pending.connects.remove(req) {
                    Some(true) => Some(FrontRequest::Closed { req: *req }),
                    Some(false) => {
                        break Some(format!("request {req} closed before it was given"));
                    }
                    None => break Some(format!("an unknown request {req} closed")),
                },
                _ => None,
            }
        };
        if let Some(request) = request {
            if requests.send(request).await.is_err() {
                break Some("no one serves the front's requests".to_string());
            }
            continue;
        }
        let answered = {
            let mut pending = lock(&pending);
            match message {
                ToMain::Ready { .. } | ToMain::Connect { .. } | ToMain::Closed { .. } => None,
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
    pending.connects.clear();
    drop(pending);
    let _ = ended.send(Some(how));
}
