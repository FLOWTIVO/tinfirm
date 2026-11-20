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
//! exit status and the start of what it wrote on stderr.

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
use tokio::task::JoinHandle;
use uuid::Uuid;

use crate::confine::{self, Status};

use super::wire::{self, MAX_TO_FRONT, MAX_TO_MAIN, ToFront, ToMain};

/// How long a front has to say that it is ready.
pub const READY_TIMEOUT: Duration = Duration::from_secs(10);
/// How long a round trip or a check may take.
pub const ANSWER_TIMEOUT: Duration = Duration::from_secs(10);
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
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// A front process and its control channel.
pub struct FrontHandle {
    pgid: u32,
    status: Status,
    writer: tokio::sync::Mutex<OwnedWriteHalf>,
    pending: Arc<Mutex<Pending>>,
    next_id: AtomicU64,
    ended: watch::Receiver<Option<Ended>>,
    reader: JoinHandle<()>,
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
        // From here on, an error kills the front's group.
        let guard = KillOnDrop(Some(pgid));
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
        let status = match tokio::time::timeout(READY_TIMEOUT, read_message(&mut reader)).await {
            Ok(Ok(Some(ToMain::Ready { status }))) => status,
            Ok(Ok(Some(other))) => bail!(
                "the front's first message was not `ready` but {}",
                kind(&other)
            ),
            Ok(Ok(None)) => {
                let _ = child.wait().await;
                let stderr = String::from_utf8_lossy(&lock(&stderr)).trim().to_string();
                bail!("the front ended before it was ready: {stderr}")
            }
            Ok(Err(e)) => bail!("the front broke its wire before it was ready: {e}"),
            Err(_) => bail!(
                "the front was not ready within {} s",
                READY_TIMEOUT.as_secs()
            ),
        };
        let pending = Arc::new(Mutex::new(Pending::default()));
        let (ended_tx, ended) = watch::channel(None);
        let reader = tokio::spawn(read_loop(
            reader,
            child,
            pgid,
            pending.clone(),
            ended_tx,
            stderr,
        ));
        std::mem::forget(guard);
        let handle = FrontHandle {
            pgid,
            status,
            writer: tokio::sync::Mutex::new(writer),
            pending,
            next_id: AtomicU64::new(1),
            ended,
            reader,
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
            .context("cannot write to the front")?;
        // The descriptor is the front's now; `run`'s copy goes.
        drop(descriptor);
        Ok(())
    }

    /// One round trip.
    pub async fn ping(&self) -> Result<()> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        lock(&self.pending).pongs.insert(id, tx);
        self.send(&ToFront::Ping { id }, None).await?;
        self.answer(rx).await
    }

    /// Sends the front a descriptor and reads back what it writes on it: descriptors arrive.
    pub async fn check_descriptor(&self) -> Result<()> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (ours, theirs) = std::os::unix::net::UnixStream::pair()?;
        ours.set_nonblocking(true)?;
        let mut ours = UnixStream::from_std(ours)?;
        let (tx, rx) = oneshot::channel();
        lock(&self.pending).checks.insert(id, tx);
        self.send(&ToFront::Check { id }, Some(OwnedFd::from(theirs)))
            .await?;
        self.answer(rx).await?;
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

    async fn answer(&self, rx: oneshot::Receiver<()>) -> Result<()> {
        tokio::select! {
            answered = tokio::time::timeout(ANSWER_TIMEOUT, rx) => match answered {
                Ok(Ok(())) => Ok(()),
                Ok(Err(_)) => bail!("{}", self.ended().map_or_else(|| "the front ended".to_string(), |e| e.to_string())),
                Err(_) => bail!("the front did not answer within {} s", ANSWER_TIMEOUT.as_secs()),
            },
            ended = self.wait_ended() => bail!("{ended}"),
        }
    }
}

impl Drop for FrontHandle {
    fn drop(&mut self) {
        // Its channel closes with the handle, and the front exits on that; its group goes too,
        // whatever it is doing.
        confine::kill_group(self.pgid);
        self.reader.abort();
    }
}

/// Kills a group that has not been handed on yet.
struct KillOnDrop(Option<u32>);

impl Drop for KillOnDrop {
    fn drop(&mut self) {
        if let Some(pgid) = self.0.take() {
            confine::kill_group(pgid);
        }
    }
}

fn kind(message: &ToMain) -> &'static str {
    match message {
        ToMain::Ready { .. } => "ready",
        ToMain::Pong { .. } => "pong",
        ToMain::Checked { .. } => "checked",
    }
}

/// The next message from the front: `Ok(None)` at the end of its channel.
async fn read_message(reader: &mut OwnedReadHalf) -> Result<Option<ToMain>> {
    let mut header = [0u8; wire::HEADER];
    match reader.read_exact(&mut header).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e.into()),
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
    pgid: u32,
    pending: Arc<Mutex<Pending>>,
    ended: watch::Sender<Option<Ended>>,
    stderr: Arc<Mutex<Vec<u8>>>,
) {
    let broken = loop {
        let message = match read_message(&mut reader).await {
            Ok(Some(message)) => message,
            Ok(None) => break None,
            Err(e) => break Some(format!("{e:#}")),
        };
        let answered = {
            let mut pending = lock(&pending);
            match message {
                ToMain::Ready { .. } => None,
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
        tracing::warn!(pgid, why = %why, "a front broke its wire; it is ended");
        confine::kill_group(pgid);
    }
    let status = child.wait().await;
    // Its stderr is read to its end, or the group's kill ended it.
    tokio::time::sleep(Duration::from_millis(50)).await;
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
    drop(pending);
    let _ = ended.send(Some(how));
}
