//! A tap on what `run` hands its fronts, for the test of path C (ADR-0112 decision 8,
//! `tests/path_c.rs`): every frame that `run` writes to a front, and every byte that `run`
//! writes on a descriptor it fills for a front (a request's socket, a put's archive, the
//! adapter's stdin), so that a test can show that no credential of the host's goes to a front.
//!
//! Test-only: [`super::handle::FrontCommand::tap`] is `None` wherever `run` starts a front,
//! and no flag or variable sets it. A tapped front gets, in place of each descriptor that `run`
//! fills, one end of a socket pair of the tap's, which copies both ways, ends each way as the
//! other side does, and records what goes towards the front.

use std::os::fd::OwnedFd;
use std::sync::{Arc, Mutex};

use tokio::io::{AsyncReadExt, AsyncWriteExt};

use super::wire::ToFront;

/// The tap: shared by every front that a tapped command starts.
#[derive(Clone, Default)]
pub struct FrontTap {
    state: Arc<Mutex<State>>,
}

impl std::fmt::Debug for FrontTap {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("FrontTap")
    }
}

#[derive(Default)]
struct State {
    fronts: Vec<Front>,
    /// The long-lived descriptors are kept ([`FrontTap::keep`]).
    keep: bool,
}

struct Front {
    pgid: u32,
    frames: Vec<u8>,
    streams: Vec<Arc<Mutex<Vec<u8>>>>,
    kept: Vec<(ToFront, Vec<OwnedFd>)>,
}

/// What `run` sent one front.
#[derive(Debug, Clone)]
pub struct Tapped {
    /// The front's process group, which is its pid.
    pub pgid: u32,
    /// Its frames, one after the other, as `run` wrote them.
    pub frames: Vec<u8>,
    /// What `run` wrote on each descriptor that it filled for the front, in the order given.
    pub streams: Vec<Vec<u8>>,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl FrontTap {
    pub fn new() -> FrontTap {
        FrontTap::default()
    }

    /// Every front started so far with this tap, and what `run` sent it until now.
    pub fn fronts(&self) -> Vec<Tapped> {
        lock(&self.state)
            .fronts
            .iter()
            .map(|front| Tapped {
                pgid: front.pgid,
                frames: front.frames.clone(),
                streams: front.streams.iter().map(|s| lock(s).clone()).collect(),
            })
            .collect()
    }

    /// From now on, keeps a copy of each descriptor that `run` gives a front for the length
    /// of its sandbox (its listeners, its bridge's listener or connection, the adapter's
    /// stdin, stdout and stderr), with its message, until [`FrontTap::stop_keeping`]. A copy
    /// holds its socket open: a test takes the copies while the sandboxes run.
    pub fn keep(&self) {
        lock(&self.state).keep = true;
    }

    /// The messages kept for the front of process group `pgid`, with the copies of their
    /// descriptors, which the tap no longer holds.
    pub fn take_kept(&self, pgid: u32) -> Vec<(ToFront, Vec<OwnedFd>)> {
        lock(&self.state)
            .fronts
            .iter_mut()
            .filter(|front| front.pgid == pgid)
            .flat_map(|front| std::mem::take(&mut front.kept))
            .collect()
    }

    /// Keeps nothing more, and closes every copy still kept.
    pub fn stop_keeping(&self) {
        let mut state = lock(&self.state);
        state.keep = false;
        for front in &mut state.fronts {
            front.kept.clear();
        }
    }

    /// A front of this tap's, with its process group.
    pub(super) fn front(&self, pgid: u32) -> TapFront {
        let mut state = lock(&self.state);
        state.fronts.push(Front {
            pgid,
            frames: Vec::new(),
            streams: Vec::new(),
            kept: Vec::new(),
        });
        TapFront {
            tap: self.clone(),
            index: state.fronts.len() - 1,
        }
    }
}

/// One front's place in the tap ([`super::handle::FrontHandle`] holds it).
pub(super) struct TapFront {
    tap: FrontTap,
    index: usize,
}

impl TapFront {
    /// Records `frame`, the encoding of `message`, and gives back its descriptors, each that
    /// `run` fills replaced by the tap's end of a socket pair that copies it.
    pub(super) fn sent(
        &self,
        message: &ToFront,
        frame: &[u8],
        descriptors: Vec<OwnedFd>,
    ) -> std::io::Result<Vec<OwnedFd>> {
        // The descriptors on which `run` writes: a request's socket, a put's archive, the
        // adapter's stdin (the first of three).
        let filled = matches!(
            message,
            ToFront::Body { .. } | ToFront::Put { .. } | ToFront::Start { .. }
        );
        let lasting = matches!(
            message,
            ToFront::ListenEgress { .. }
                | ToFront::ListenModel { .. }
                | ToFront::ListenGit { .. }
                | ToFront::BridgeInit { .. }
                | ToFront::Dialed {}
                | ToFront::Start { .. }
        );
        let mut descriptors = descriptors;
        let mut state = lock(&self.tap.state);
        let keep = state.keep;
        let front = &mut state.fronts[self.index];
        front.frames.extend_from_slice(frame);
        if filled && !descriptors.is_empty() {
            let stream = Arc::new(Mutex::new(Vec::new()));
            let first = descriptors.remove(0);
            descriptors.insert(0, relay(first, stream.clone())?);
            front.streams.push(stream);
        }
        if lasting && keep {
            let copies = descriptors
                .iter()
                .map(OwnedFd::try_clone)
                .collect::<std::io::Result<Vec<_>>>()?;
            front.kept.push((message.clone(), copies));
        }
        Ok(descriptors)
    }
}

/// The tap's end of a socket pair in place of `theirs`, a stream socket whose other end is
/// `run`'s: what `run` writes goes on to the front and into `record`, what the front writes
/// goes back to `run`, and each way ends as its writer ends it.
fn relay(theirs: OwnedFd, record: Arc<Mutex<Vec<u8>>>) -> std::io::Result<OwnedFd> {
    let (ours, front) = std::os::unix::net::UnixStream::pair()?;
    let theirs = std::os::unix::net::UnixStream::from(theirs);
    theirs.set_nonblocking(true)?;
    ours.set_nonblocking(true)?;
    let (mut from_run, mut to_run) = tokio::net::UnixStream::from_std(theirs)?.into_split();
    let (mut from_front, mut to_front) = tokio::net::UnixStream::from_std(ours)?.into_split();
    tokio::spawn(async move {
        let mut buffer = vec![0u8; 64 * 1024];
        while let Ok(read @ 1..) = from_run.read(&mut buffer).await {
            lock(&record).extend_from_slice(&buffer[..read]);
            if to_front.write_all(&buffer[..read]).await.is_err() {
                break;
            }
        }
        let _ = to_front.shutdown().await;
    });
    tokio::spawn(async move {
        let _ = tokio::io::copy(&mut from_front, &mut to_run).await;
        let _ = to_run.shutdown().await;
    });
    Ok(OwnedFd::from(front))
}
