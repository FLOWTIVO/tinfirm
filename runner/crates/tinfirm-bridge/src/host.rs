//! The runner host's side of the bridge (ADR-0095): one listener for every run of the host,
//! a one-time ticket per run, and the run's session once its bridge has connected: the
//! adapter's stdio, the tunnels, and the sandbox's files ([`SandboxFiles`]).

use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::Path;
use std::pin::Pin;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot, watch};
use tokio_rustls::TlsAcceptor;
use tokio_rustls::server::TlsStream;
use tokio_util::sync::CancellationToken;

use crate::control::{Control, Exec, Hidden, ProbeCheck, RefuseCode, Signal, Start, Tunnel};
use crate::files::{self, Limits, ReadOne, Unpacked};
use crate::frame::{self, Frame, Kind};
use crate::link::{self, Inbound, InboundReceiver, LinkHandle, Timing};
use crate::tls::Identity;
use crate::tunnel::{HOST_STREAMS, HostTunnels, MAX_TUNNELS, Opened, Streams, next_event};
use crate::{PROTOCOL_VERSION, random_token, secret_eq};

/// The bytes the host keeps for a sandbox until they are acknowledged (the adapter's stdin).
pub const REPLAY_BYTES: usize = 4 * 1024 * 1024;
/// Chunks of the adapter's output waiting for the host to read them ([`HostIo::output`]).
/// When it is full the session reads nothing more from the sandbox, whose adapter then
/// waits on its own pipe (its bridge is out of credit).
pub const OUTPUT_QUEUE: usize = 256;

#[derive(Debug, Clone, Copy)]
pub struct HostTiming {
    pub link: Timing,
    /// A connection must finish its TLS handshake and `hello` within this time.
    pub handshake: Duration,
    /// A run whose bridge has had no connection for this long has lost its sandbox.
    pub gone_after: Duration,
    /// A tunnel's stream must reach the host's listener within this time.
    pub tunnel_connect: Duration,
    /// Dialing a sandbox's bridge ([`Registration::dial`]): between two attempts.
    pub dial_retry: Duration,
}

impl Default for HostTiming {
    fn default() -> HostTiming {
        HostTiming {
            link: Timing::default(),
            handshake: Duration::from_secs(10),
            gone_after: Duration::from_secs(30),
            tunnel_connect: Duration::from_secs(10),
            dial_retry: Duration::from_secs(1),
        }
    }
}

/// The host's bridge listener. Clones share it.
#[derive(Clone)]
pub struct BridgeServer {
    inner: Arc<Inner>,
}

struct Inner {
    acceptor: TlsAcceptor,
    pin: String,
    addr: SocketAddr,
    timing: HostTiming,
    runs: Mutex<HashMap<String, Entry>>,
    shutdown: CancellationToken,
}

enum Entry {
    Waiting {
        ticket: Hidden,
        ready: oneshot::Sender<Established>,
    },
    Established {
        session: Hidden,
        link: LinkHandle,
    },
    /// A session of the process this one restarted from (ADR-0101): its bridge comes back with
    /// the session key, and the session goes on with a link of this process's.
    Reclaiming {
        session: Hidden,
        ready: oneshot::Sender<Established>,
    },
}

struct Established {
    link: LinkHandle,
    inbound: InboundReceiver,
    /// The session's key, which the bridge presents on every reconnect.
    session: Hidden,
    /// What the bridge said of its binary's version.
    build: Option<String>,
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl BridgeServer {
    /// Creates the host's certificate, binds the listener and serves until
    /// [`BridgeServer::shutdown`].
    pub async fn start(bind: SocketAddr, timing: HostTiming) -> Result<BridgeServer> {
        let (server, _) =
            BridgeServer::start_with(bind, timing, &Identity::generate()?, Vec::new()).await?;
        Ok(server)
    }

    /// [`BridgeServer::start`] with `identity`, and with the sessions that a runner host that
    /// restarted takes back (ADR-0101): each run's id and session key, as its predecessor had
    /// them. Their registrations come back in order, made before the first connection is
    /// accepted, so that no bridge of theirs is refused for coming early.
    pub async fn start_with(
        bind: SocketAddr,
        timing: HostTiming,
        identity: &Identity,
        reclaim: Vec<(String, Hidden)>,
    ) -> Result<(BridgeServer, Vec<Registration>)> {
        let listener = TcpListener::bind(bind)
            .await
            .with_context(|| format!("the sandbox bridge cannot listen on {bind}"))?;
        let addr = listener.local_addr()?;
        let inner = Arc::new(Inner {
            acceptor: TlsAcceptor::from(identity.server_config()),
            pin: identity.pin_hex(),
            addr,
            timing,
            runs: Mutex::new(HashMap::new()),
            shutdown: CancellationToken::new(),
        });
        let mut registrations = Vec::with_capacity(reclaim.len());
        for (run_id, session) in reclaim {
            let (ready_tx, ready_rx) = oneshot::channel();
            let mut runs = lock(&inner.runs);
            if runs.contains_key(&run_id) {
                bail!("run {run_id} is taken back twice");
            }
            runs.insert(
                run_id.clone(),
                Entry::Reclaiming {
                    session,
                    ready: ready_tx,
                },
            );
            drop(runs);
            registrations.push(Registration {
                inner: inner.clone(),
                run_id,
                // A session taken back has no ticket: its bridge presents its session key.
                ticket: Hidden::new(String::new()),
                ready: Some(ready_rx),
                stop: CancellationToken::new(),
            });
        }
        tokio::spawn(accept_loop(listener, inner.clone()));
        Ok((BridgeServer { inner }, registrations))
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.inner.addr
    }

    /// The SHA-256 of the listener's certificate, in hex (`TINFIRM_BRIDGE_PIN`).
    pub fn pin(&self) -> &str {
        &self.inner.pin
    }

    /// Runs registered now, waiting for their bridge or connected.
    pub fn run_count(&self) -> usize {
        lock(&self.inner.runs).len()
    }

    /// Stops accepting connections. Established sessions go on until they end.
    pub fn shutdown(&self) {
        self.inner.shutdown.cancel();
    }

    /// Registers a run: its bridge may connect once with the returned ticket.
    pub fn register(&self, run_id: &str) -> Result<Registration> {
        let ticket = Hidden::new(random_token()?);
        let (ready_tx, ready_rx) = oneshot::channel();
        let mut runs = lock(&self.inner.runs);
        if runs.contains_key(run_id) {
            bail!("run {run_id} is registered with the sandbox bridge already");
        }
        runs.insert(
            run_id.to_string(),
            Entry::Waiting {
                ticket: ticket.clone(),
                ready: ready_tx,
            },
        );
        Ok(Registration {
            inner: self.inner.clone(),
            run_id: run_id.to_string(),
            ticket,
            ready: Some(ready_rx),
            stop: CancellationToken::new(),
        })
    }
}

/// A run registered with the listener. Dropping it ends the run's bridge: its ticket, its
/// session and its link.
pub struct Registration {
    inner: Arc<Inner>,
    run_id: String,
    ticket: Hidden,
    ready: Option<oneshot::Receiver<Established>>,
    /// Ends the dialer of [`Registration::dial`].
    stop: CancellationToken,
}

impl std::fmt::Debug for Registration {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Registration")
            .field("run_id", &self.run_id)
            .finish_non_exhaustive()
    }
}

impl Registration {
    pub fn run_id(&self) -> &str {
        &self.run_id
    }

    /// The run's one-time ticket (`TINFIRM_BRIDGE_TICKET`).
    pub fn ticket(&self) -> &str {
        self.ticket.expose()
    }

    /// The listener's pin (`TINFIRM_BRIDGE_PIN`).
    pub fn pin(&self) -> &str {
        &self.inner.pin
    }

    /// The listener's port.
    pub fn port(&self) -> u16 {
        self.inner.addr.port()
    }

    /// The direction of a VM per run, whose firewall lets nothing out: the host dials the
    /// sandbox's bridge at `addr`, where it listens, and dials again whenever the connection is
    /// lost, until the registration is dropped. The host stays the TLS server, and the
    /// handshake is the one of a bridge that dials in.
    pub fn dial(&self, addr: String) {
        let (inner, run_id, stop) = (self.inner.clone(), self.run_id.clone(), self.stop.clone());
        tokio::spawn(async move {
            let timing = inner.timing;
            loop {
                let link = match lock(&inner.runs).get(&run_id) {
                    Some(Entry::Established { link, .. }) => Some(link.clone()),
                    Some(Entry::Waiting { .. } | Entry::Reclaiming { .. }) => None,
                    None => return,
                };
                // A connection that is up is waited on until it is lost. One just attached
                // shows as up a moment later; dialing again before then would leave a
                // connection in the bridge's backlog that it would take for the next one.
                if let Some(link) = link {
                    if link.is_closed() {
                        return;
                    }
                    let mut connected = link.connected();
                    let up = tokio::select! {
                        _ = stop.cancelled() => return,
                        up = tokio::time::timeout(timing.dial_retry, connected.wait_for(|up| *up)) => up.map(|up| up.is_ok()),
                    };
                    match up {
                        Ok(true) => {
                            tokio::select! {
                                _ = stop.cancelled() => return,
                                lost = connected.wait_for(|up| !*up) => if lost.is_err() {
                                    return;
                                },
                            }
                            continue;
                        }
                        // The link is gone, and with it the session.
                        Ok(false) => return,
                        Err(_) => {}
                    }
                }
                let attempt = async {
                    let tcp = TcpStream::connect(addr.as_str())
                        .await
                        .with_context(|| format!("cannot connect to {addr}"))?;
                    handshake(tcp, &inner).await
                };
                let done = tokio::select! {
                    _ = stop.cancelled() => return,
                    done = tokio::time::timeout(timing.handshake, attempt) => done,
                };
                match done {
                    Ok(Ok(())) => continue,
                    Ok(Err(e)) => {
                        tracing::debug!(%run_id, error = %format!("{e:#}"), "the sandbox's bridge does not answer yet");
                    }
                    Err(_) => tracing::debug!(%run_id, "dialing the sandbox's bridge timed out"),
                }
                tokio::select! {
                    _ = stop.cancelled() => return,
                    _ = tokio::time::sleep(timing.dial_retry) => {}
                }
            }
        });
    }

    /// Waits for the bridge's first connection.
    pub async fn connected(mut self, timeout: Duration) -> Result<HostSession> {
        let ready = self
            .ready
            .take()
            .context("connected() was called already")?;
        match tokio::time::timeout(timeout, ready).await {
            Ok(Ok(established)) => Ok(HostSession::new(self, established)),
            Ok(Err(_)) => bail!("the sandbox bridge's listener stopped"),
            Err(_) => bail!(
                "the sandbox's bridge did not connect within {} s",
                timeout.as_secs()
            ),
        }
    }
}

impl Drop for Registration {
    fn drop(&mut self) {
        self.stop.cancel();
        if let Some(Entry::Established { link, .. }) = lock(&self.inner.runs).remove(&self.run_id) {
            link.close();
        }
    }
}

/// The adapter's output, as the sandbox sent it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Output {
    Stdout(Vec<u8>),
    Stderr(Vec<u8>),
}

/// How the adapter ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Exit {
    Exited {
        code: Option<i32>,
        signal: Option<i32>,
    },
    /// The bridge has been gone for longer than [`HostTiming::gone_after`], or the link closed.
    Lost,
}

/// The adapter's stdio once it is started.
pub struct HostIo {
    /// Writes go to the adapter's stdin; dropping it ends that stdin.
    pub stdin: DuplexStream,
    /// Closes when the adapter has exited or is lost. Holds at most [`OUTPUT_QUEUE`] chunks:
    /// a reader that falls behind holds the sandbox back, and one that is gone drops what
    /// the adapter still writes.
    pub output: mpsc::Receiver<Output>,
    pub exit: watch::Receiver<Option<Exit>>,
}

/// A tunnel the host asks for: where it listens in the sandbox, and the host's listener that
/// its connections reach.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TunnelTarget {
    pub listen: SocketAddr,
    pub target: SocketAddr,
}

/// How a command of the host's ended in the sandbox ([`SandboxFiles::exec`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExecOutput {
    pub code: Option<i32>,
    pub signal: Option<i32>,
    /// At most [`Exec::MAX_OUTPUT`] bytes, as text.
    pub stdout: String,
    pub stderr: String,
}

impl ExecOutput {
    pub fn success(&self) -> bool {
        self.code == Some(0)
    }
}

/// What a session's dispatcher and its handles share.
struct State {
    link: LinkHandle,
    streams: Arc<Streams>,
    /// The requests that wait for their answer, by id.
    waiters: Mutex<HashMap<u64, oneshot::Sender<Control>>>,
    next_request: AtomicU64,
    next_stream: AtomicU32,
    tunnels: Mutex<Option<HostTunnels>>,
    /// The session has ended: no request gets an answer any more.
    ended: CancellationToken,
}

impl State {
    /// A request id, and where its answer arrives.
    fn pending(&self) -> (u64, oneshot::Receiver<Control>) {
        let id = self.next_request.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = oneshot::channel();
        lock(&self.waiters).insert(id, tx);
        (id, rx)
    }

    async fn send(&self, id: u64, control: &Control) -> Result<()> {
        if self.ended.is_cancelled() {
            lock(&self.waiters).remove(&id);
            bail!("the sandbox's bridge is gone");
        }
        if self.link.send_control(control).await.is_err() {
            lock(&self.waiters).remove(&id);
            bail!("the sandbox bridge's link is closed");
        }
        Ok(())
    }

    /// The answer to request `id`: `failed` is an error.
    async fn answer(
        &self,
        id: u64,
        answer: oneshot::Receiver<Control>,
        timeout: Duration,
    ) -> Result<Control> {
        let answer = tokio::select! {
            answer = tokio::time::timeout(timeout, answer) => answer,
            _ = self.ended.cancelled() => {
                lock(&self.waiters).remove(&id);
                bail!("the sandbox's bridge is gone");
            }
        };
        match answer {
            Ok(Ok(Control::Failed { message, .. })) => bail!("{message}"),
            Ok(Ok(reply)) => Ok(reply),
            Ok(Err(_)) => bail!("the sandbox's bridge is gone"),
            Err(_) => {
                lock(&self.waiters).remove(&id);
                bail!(
                    "the sandbox's bridge did not answer within {} s",
                    timeout.as_secs()
                )
            }
        }
    }

    async fn request(
        &self,
        make: impl FnOnce(u64) -> Control,
        timeout: Duration,
    ) -> Result<Control> {
        let (id, answer) = self.pending();
        self.send(id, &make(id)).await?;
        self.answer(id, answer, timeout).await
    }

    /// A new stream of the host's ([`HOST_STREAMS`] and up).
    fn stream(&self) -> Result<Opened> {
        let id = self.next_stream.fetch_add(1, Ordering::Relaxed);
        anyhow::ensure!(id >= HOST_STREAMS, "the host's stream numbers are used up");
        self.streams
            .open(id)
            .context("too many streams of the sandbox are open")
    }
}

/// A run's bridge, connected. Dropping it ends the session.
pub struct HostSession {
    registration: Registration,
    /// The session's key: a runner host keeps it on its disk while the sandbox lives, so that
    /// it takes the session back after a restart (ADR-0101).
    key: Hidden,
    /// The version the bridge named in its first `hello` ([`crate::BUILD`]); none from an older one.
    build: Option<String>,
    state: Arc<State>,
    output: Option<mpsc::Receiver<Output>>,
    exit: watch::Receiver<Option<Exit>>,
    started: bool,
}

impl HostSession {
    fn new(registration: Registration, established: Established) -> HostSession {
        let key = established.session;
        let build = established.build;
        let link = established.link;
        let state = Arc::new(State {
            streams: Streams::new(link.clone()),
            link,
            waiters: Mutex::new(HashMap::new()),
            next_request: AtomicU64::new(1),
            next_stream: AtomicU32::new(HOST_STREAMS),
            tunnels: Mutex::new(None),
            ended: CancellationToken::new(),
        });
        let (out_tx, output) = mpsc::channel(OUTPUT_QUEUE);
        let (exit_tx, exit) = watch::channel(None);
        tokio::spawn(dispatch(
            established.inbound,
            state.clone(),
            out_tx,
            exit_tx,
            registration.inner.timing.gone_after,
        ));
        HostSession {
            registration,
            key,
            build,
            state,
            output: Some(output),
            exit,
            started: false,
        }
    }

    pub fn run_id(&self) -> &str {
        self.registration.run_id()
    }

    /// The session's key, which the bridge presents on a reconnect: a host that restarted takes
    /// the session back with it ([`BridgeServer::start_with`]).
    pub fn session_key(&self) -> &Hidden {
        &self.key
    }

    /// The version of the bridge's binary, as its first `hello` named it; `None` from a bridge
    /// that names none.
    pub fn bridge_build(&self) -> Option<&str> {
        self.build.as_deref()
    }

    /// How the adapter ended, once it has: with [`HostSession::start`], or the one that
    /// [`HostSession::reclaim`] stopped.
    pub fn exit(&self) -> watch::Receiver<Option<Exit>> {
        self.exit.clone()
    }

    /// Whether the session has ended: the bridge had no connection for longer than
    /// [`HostTiming::gone_after`], or its link closed. A session that waits for its `start`
    /// (a ready sandbox) ends the same way.
    pub fn has_ended(&self) -> bool {
        self.state.ended.is_cancelled()
    }

    /// Resolves once the session has ended ([`HostSession::has_ended`]).
    pub fn ended(&self) -> tokio_util::sync::WaitForCancellationFutureOwned {
        self.state.ended.clone().cancelled_owned()
    }

    /// The sandbox's files and the host's commands there, for as long as the session lasts.
    pub fn files(&self) -> SandboxFiles {
        SandboxFiles {
            state: self.state.clone(),
        }
    }

    /// Before [`HostSession::start`]: the sandbox listens on each tunnel's address and carries
    /// its connections to the tunnel's target, and no other. Returns the addresses the sandbox
    /// listens on, in order: the asked ones (or, for a port 0, the one the sandbox got).
    pub async fn open_tunnels(
        &mut self,
        tunnels: &[TunnelTarget],
        timeout: Duration,
    ) -> Result<Vec<SocketAddr>> {
        anyhow::ensure!(!self.started, "the adapter was started already");
        anyhow::ensure!(
            lock(&self.state.tunnels).is_none(),
            "the sandbox's tunnels were opened already"
        );
        anyhow::ensure!(
            tunnels.len() <= MAX_TUNNELS,
            "a sandbox has at most {MAX_TUNNELS} tunnels"
        );
        let asked: Vec<Tunnel> = (1u32..)
            .zip(tunnels)
            .map(|(id, t)| Tunnel {
                id,
                addr: t.listen.to_string(),
            })
            .collect();
        let reply = self
            .state
            .request(
                |id| Control::Tunnels {
                    id,
                    tunnels: asked.clone(),
                },
                timeout,
            )
            .await
            .context("the sandbox cannot listen for its tunnels")?;
        let Control::Listening { tunnels: reply, .. } = reply else {
            bail!("the sandbox answered the tunnels with something else");
        };
        let mut bound = Vec::with_capacity(asked.len());
        let mut targets = HashMap::new();
        for (tunnel, wanted) in asked.iter().zip(tunnels) {
            let got = reply
                .iter()
                .find(|t| t.id == tunnel.id)
                .with_context(|| format!("the sandbox did not listen on {}", wanted.listen))?;
            let addr: SocketAddr = got
                .addr
                .parse()
                .with_context(|| format!("the sandbox listens on `{}`", got.addr))?;
            if wanted.listen.port() != 0 && addr != wanted.listen {
                bail!("the sandbox listens on {addr} instead of {}", wanted.listen);
            }
            bound.push(addr);
            targets.insert(tunnel.id, wanted.target);
        }
        *lock(&self.state.tunnels) = Some(HostTunnels::new(
            self.state.streams.clone(),
            targets,
            self.registration.inner.timing.tunnel_connect,
        ));
        Ok(bound)
    }

    /// Starts the adapter in the sandbox, with the run's environment.
    pub async fn start(&mut self, start: Start) -> Result<HostIo> {
        start.check().map_err(|e| anyhow!(e))?;
        let output = self
            .output
            .take()
            .context("the adapter was started already")?;
        self.started = true;
        self.state
            .link
            .send_control(&Control::Start(start))
            .await
            .map_err(|_| anyhow!("the sandbox bridge's link is closed"))?;
        let (stdin, stdin_pump) = tokio::io::duplex(frame::MAX_DATA);
        tokio::spawn(pump_stdin(stdin_pump, self.state.link.clone()));
        Ok(HostIo {
            stdin,
            output,
            exit: self.exit.clone(),
        })
    }

    pub async fn signal(&self, signal: Signal) -> Result<()> {
        self.state
            .link
            .send_control(&Control::Signal { signal })
            .await
            .map_err(|_| anyhow!("the sandbox bridge's link is closed"))
    }

    /// SIGTERM to the adapter's process group, SIGKILL after `grace`.
    pub async fn stop(&self, grace: Duration) -> Result<()> {
        self.state
            .link
            .send_control(&Control::Stop {
                grace_seconds: grace.as_secs().max(1),
            })
            .await
            .map_err(|_| anyhow!("the sandbox bridge's link is closed"))
    }

    /// For a session taken back after a restart (ADR-0101): the agent that the host's
    /// predecessor started stops, and its further output is dropped. Waits up to `timeout`
    /// for the adapter to end, when it still ran, and returns whether it did.
    pub async fn reclaim(&mut self, timeout: Duration) -> Result<bool> {
        anyhow::ensure!(!self.started, "the adapter was started by this host");
        // Nobody reads the old agent's output: the dispatcher drops it.
        self.output.take();
        let reply = self
            .state
            .request(|id| Control::Reclaim { id }, timeout)
            .await
            .context("the sandbox's bridge does not answer the reclaim")?;
        let Control::Reclaimed { running, .. } = reply else {
            bail!("the sandbox answered the reclaim with something else");
        };
        if running {
            let mut exit = self.exit.clone();
            tokio::time::timeout(timeout, exit.wait_for(Option::is_some))
                .await
                .map_err(|_| anyhow!("the agent did not stop within {} s", timeout.as_secs()))?
                .map_err(|_| anyhow!("the sandbox's bridge is gone"))?;
        }
        Ok(running)
    }

    /// After the adapter has ended: the host has what it needs, and the bridge ends. Waits up
    /// to `timeout` for the bridge's answer.
    pub async fn close(&self, timeout: Duration) {
        if let Err(e) = self
            .state
            .request(|id| Control::Close { id }, timeout)
            .await
        {
            tracing::debug!(error = %e, "the sandbox's bridge did not answer the close");
        }
    }
}

/// The sandbox's files and the host's commands there ([`crate::files`]). Clones share the
/// session; once it has ended, every call fails.
#[derive(Clone)]
pub struct SandboxFiles {
    state: Arc<State>,
}

impl SandboxFiles {
    /// Packs `host_dir`, without `exclude`, into the sandbox's directory `dir`.
    pub async fn put_dir(
        &self,
        host_dir: &Path,
        dir: &str,
        exclude: Vec<String>,
        timeout: Duration,
    ) -> Result<()> {
        let opened = self.state.stream()?;
        let stream = opened.id();
        let (id, answer) = self.state.pending();
        let put = Control::Put {
            id,
            stream,
            dir: dir.to_string(),
        };
        if let Err(e) = self.state.send(id, &put).await {
            self.state.streams.abort(stream);
            return Err(e);
        }
        let host_dir = host_dir.to_path_buf();
        let sent = tokio::time::timeout(
            timeout,
            files::send(&self.state.streams, opened, move |out| {
                files::pack(&host_dir, &[], &exclude, out).map(|_| ())
            }),
        )
        .await;
        match sent {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                self.state.streams.abort(stream);
                return Err(e).with_context(|| format!("cannot send the files of {dir}"));
            }
            Err(_) => {
                self.state.streams.abort(stream);
                bail!(
                    "the files of {dir} did not reach the sandbox within {} s",
                    timeout.as_secs()
                );
            }
        }
        self.state
            .answer(id, answer, timeout)
            .await
            .with_context(|| format!("the sandbox cannot unpack {dir}"))?;
        Ok(())
    }

    /// Unpacks `paths` of the sandbox's directory `dir` (all of it when empty), without
    /// `exclude`, into `dest` on the host: directories and regular files only, within `limits`
    /// ([`files::unpack_untrusted`]).
    pub async fn get_dir(
        &self,
        dir: &str,
        paths: Vec<String>,
        exclude: Vec<String>,
        dest: &Path,
        limits: Limits,
        timeout: Duration,
    ) -> Result<Unpacked> {
        let dest = dest.to_path_buf();
        self.get(dir, paths, exclude, timeout, move |input| {
            files::unpack_untrusted(input, &dest, &limits).map_err(anyhow::Error::from)
        })
        .await
    }

    /// The regular file `path` (relative) of the sandbox's directory `dir`, at most `max`
    /// bytes.
    pub async fn read_file(
        &self,
        dir: &str,
        path: &str,
        max: u64,
        timeout: Duration,
    ) -> Result<ReadOne> {
        self.get(
            dir,
            vec![path.to_string()],
            Vec::new(),
            timeout,
            move |input| files::read_one(input, max).map_err(anyhow::Error::from),
        )
        .await
    }

    async fn get<T, F>(
        &self,
        dir: &str,
        paths: Vec<String>,
        exclude: Vec<String>,
        timeout: Duration,
        consume: F,
    ) -> Result<T>
    where
        T: Send + 'static,
        F: FnOnce(&mut dyn std::io::Read) -> Result<T> + Send + 'static,
    {
        let opened = self.state.stream()?;
        let stream = opened.id();
        let (id, answer) = self.state.pending();
        let get = Control::Get {
            id,
            stream,
            dir: dir.to_string(),
            paths,
            exclude,
        };
        if let Err(e) = self.state.send(id, &get).await {
            self.state.streams.abort(stream);
            return Err(e);
        }
        let received = tokio::time::timeout(
            timeout,
            files::receive(&self.state.streams, opened, consume),
        )
        .await;
        let value = match received {
            Ok(Ok(value)) => value,
            Ok(Err(e)) => {
                self.state.streams.abort(stream);
                lock(&self.state.waiters).remove(&id);
                return Err(e).with_context(|| format!("cannot take the files of {dir}"));
            }
            Err(_) => {
                self.state.streams.abort(stream);
                lock(&self.state.waiters).remove(&id);
                bail!(
                    "the files of {dir} did not arrive within {} s",
                    timeout.as_secs()
                );
            }
        };
        self.state
            .answer(id, answer, timeout)
            .await
            .with_context(|| format!("the sandbox cannot send {dir}"))?;
        Ok(value)
    }

    /// Tries `checks` from inside the sandbox ([`crate::probe`]); the names of those that got
    /// through. A sandbox must reach none of them.
    pub async fn probe(&self, checks: Vec<ProbeCheck>, timeout: Duration) -> Result<Vec<String>> {
        let reply = self
            .state
            .request(|id| Control::Probe { id, checks }, timeout)
            .await
            .context("the sandbox's network probe did not answer")?;
        match reply {
            Control::Probed { open, .. } => Ok(open),
            _ => bail!("the sandbox answered the probe with something else"),
        }
    }

    /// Runs `argv` in the sandbox, in `cwd`, with `env` added and `stdin` as its input.
    pub async fn exec(
        &self,
        argv: &[&str],
        env: &[(&str, &str)],
        cwd: Option<&str>,
        stdin: Option<String>,
        timeout: Duration,
    ) -> Result<ExecOutput> {
        let reply = self
            .state
            .request(
                |id| {
                    Control::Exec(Exec {
                        id,
                        argv: argv.iter().map(|a| a.to_string()).collect(),
                        env: env
                            .iter()
                            .map(|(k, v)| (k.to_string(), Hidden::new(*v)))
                            .collect(),
                        cwd: cwd.map(str::to_string),
                        stdin,
                        timeout_seconds: timeout.as_secs().max(1),
                    })
                },
                timeout + Duration::from_secs(10),
            )
            .await
            .with_context(|| {
                format!("cannot run {} in the sandbox", argv.first().unwrap_or(&""))
            })?;
        match reply {
            Control::ExecDone {
                code,
                signal,
                stdout,
                stderr,
                ..
            } => Ok(ExecOutput {
                code,
                signal,
                stdout,
                stderr,
            }),
            _ => bail!("the sandbox answered a command with something else"),
        }
    }
}

async fn pump_stdin(mut reader: DuplexStream, link: LinkHandle) {
    let mut buf = vec![0u8; frame::MAX_DATA];
    loop {
        match reader.read(&mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                if link
                    .send(Kind::Data, frame::STREAM_STDIN, buf[..n].to_vec())
                    .await
                    .is_err()
                {
                    return;
                }
            }
        }
    }
    let _ = link.send(Kind::Eof, frame::STREAM_STDIN, Vec::new()).await;
}

/// The id of an answer to a request of the host's.
fn answer_id(control: &Control) -> Option<u64> {
    match control {
        Control::Listening { id, .. }
        | Control::Done { id }
        | Control::ExecDone { id, .. }
        | Control::Probed { id, .. }
        | Control::Reclaimed { id, .. }
        | Control::Failed { id, .. } => Some(*id),
        _ => None,
    }
}

/// The sandbox's events for as long as the session lasts: the adapter's output and exit, the
/// answers to the host's requests, and the streams of the tunnels and the files. The output
/// ends with the adapter; the rest goes on after it, for the host to take the sandbox's files,
/// until the link ends or the bridge is gone for [`HostTiming::gone_after`].
///
/// The output is handed on at the reader's pace: while [`HostIo::output`] is full, nothing is
/// taken from the link (the sandbox's bridge runs out of credit and the adapter's writes
/// wait), and the session goes on as it is, its waiters and tunnels included. Once the reader
/// is gone, the adapter's further output is dropped.
async fn dispatch(
    mut inbound: InboundReceiver,
    state: Arc<State>,
    output: mpsc::Sender<Output>,
    exit: watch::Sender<Option<Exit>>,
    gone_after: Duration,
) {
    let mut output = Some(output);
    let mut gone: Option<Pin<Box<tokio::time::Sleep>>> = None;
    loop {
        let event = tokio::select! {
            event = next_event(&mut inbound, &state.streams) => event,
            _ = async { gone.as_mut().expect("guarded").await }, if gone.is_some() => {
                tracing::warn!(
                    seconds = gone_after.as_secs(),
                    "the sandbox's bridge has had no connection for too long"
                );
                break;
            }
        };
        let Some(event) = event else {
            break;
        };
        match event {
            Inbound::Control(Control::Open { stream, tunnel }) => {
                match lock(&state.tunnels).as_mut() {
                    Some(tunnels) => tunnels.open(stream, tunnel),
                    None => state.streams.refuse(stream),
                }
            }
            Inbound::Control(answer) if answer_id(&answer).is_some() => {
                let id = answer_id(&answer).expect("checked");
                if let Some(waiter) = lock(&state.waiters).remove(&id) {
                    let _ = waiter.send(answer);
                }
            }
            Inbound::Data { stream, bytes } => {
                let item = if stream == frame::STREAM_STDERR {
                    Output::Stderr(bytes)
                } else {
                    Output::Stdout(bytes)
                };
                if let Some(tx) = &output
                    && tx.send(item).await.is_err()
                {
                    output = None;
                }
            }
            Inbound::Control(Control::Exited { code, signal }) => {
                // The output has ended before; the session goes on for the files.
                output = None;
                let _ = exit.send(Some(Exit::Exited { code, signal }));
            }
            Inbound::Control(Control::Error { message }) => {
                let line = format!("tinfirm-bridge: {message}\n");
                if let Some(tx) = &output
                    && tx.send(Output::Stderr(line.into_bytes())).await.is_err()
                {
                    output = None;
                }
            }
            Inbound::Control(Control::Started { pid }) => {
                tracing::debug!(pid, "the adapter started in the sandbox");
            }
            Inbound::Disconnected => {
                gone = Some(Box::pin(tokio::time::sleep(gone_after)));
            }
            Inbound::Connected => gone = None,
            _ => {}
        }
    }
    if exit.borrow().is_none() {
        let _ = exit.send(Some(Exit::Lost));
    }
    state.ended.cancel();
    state.streams.close();
    lock(&state.waiters).clear();
    lock(&state.tunnels).take();
}

async fn accept_loop(listener: TcpListener, inner: Arc<Inner>) {
    loop {
        let accepted = tokio::select! {
            _ = inner.shutdown.cancelled() => return,
            accepted = listener.accept() => accepted,
        };
        match accepted {
            Ok((tcp, peer)) => {
                let inner = inner.clone();
                tokio::spawn(async move {
                    match tokio::time::timeout(inner.timing.handshake, handshake(tcp, &inner)).await
                    {
                        Ok(Ok(())) => {}
                        Ok(Err(e)) => {
                            tracing::warn!(%peer, error = %e, "a sandbox bridge connection was refused");
                        }
                        Err(_) => {
                            tracing::warn!(%peer, "a sandbox bridge connection did not finish its handshake in time");
                        }
                    }
                });
            }
            Err(e) => {
                tracing::warn!(error = %e, "the sandbox bridge's listener cannot accept");
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
    }
}

enum Decision {
    New {
        ticket: Hidden,
        session: Hidden,
        ready: oneshot::Sender<Established>,
        link: LinkHandle,
        inbound: InboundReceiver,
    },
    Resume(LinkHandle),
    /// A session of the process this one restarted from: `link` starts where the bridge's is.
    Reclaim {
        session: Hidden,
        ready: oneshot::Sender<Established>,
        link: LinkHandle,
        inbound: InboundReceiver,
        rx_after: u64,
    },
    Refuse(RefuseCode, &'static str),
}

async fn handshake(tcp: TcpStream, inner: &Arc<Inner>) -> Result<()> {
    let _ = tcp.set_nodelay(true);
    let mut tls = inner
        .acceptor
        .accept(tcp)
        .await
        .context("the TLS handshake failed")?;
    let first = frame::read_frame(&mut tls)
        .await?
        .context("the connection closed before hello")?;
    let hello = (first.kind == Kind::Control && first.seq == 0)
        .then(|| serde_json::from_slice::<Control>(&first.payload).ok())
        .flatten();
    let Some(Control::Hello {
        version,
        run_id,
        ticket,
        session,
        rx_seq,
        tx_seq,
        build,
    }) = hello
    else {
        refuse(
            &mut tls,
            RefuseCode::BadRequest,
            "the first frame must be hello",
        )
        .await;
        bail!("the first frame was not hello");
    };
    if version != PROTOCOL_VERSION {
        refuse(
            &mut tls,
            RefuseCode::UnsupportedVersion,
            "this runner host speaks another version of the bridge protocol",
        )
        .await;
        bail!("the bridge speaks protocol {version}, this host {PROTOCOL_VERSION}");
    }
    let decision = decide(inner, &run_id, ticket, session, rx_seq, tx_seq)?;
    match decision {
        Decision::Refuse(code, message) => {
            refuse(&mut tls, code, message).await;
            bail!("run {run_id}: {message}");
        }
        Decision::New {
            ticket,
            session,
            ready,
            link,
            inbound,
        } => {
            let welcome = Control::Welcome {
                version: PROTOCOL_VERSION,
                session: Some(session.clone()),
                rx_seq: 0,
            };
            if let Err(e) = write_control(&mut tls, &welcome).await {
                // The bridge never learnt the session key: let it try its ticket again.
                link.close();
                lock(&inner.runs).insert(run_id.clone(), Entry::Waiting { ticket, ready });
                return Err(e).context("cannot answer the bridge");
            }
            link.attach(Box::new(tls), rx_seq)
                .context("the run's link did not take the connection")?;
            if ready
                .send(Established {
                    link: link.clone(),
                    inbound,
                    session: session.clone(),
                    build,
                })
                .is_err()
            {
                // Nobody waits for this bridge any more.
                link.close();
            }
        }
        Decision::Resume(link) => {
            if !link.can_attach() {
                // No answer: the bridge tries again after its retry, instead of losing a
                // connection it was welcomed on.
                bail!(
                    "the session has not taken the events of the bridge's earlier connections; \
                     this one is dropped"
                );
            }
            let ours = link
                .rx_seq()
                .await
                .map_err(|_| anyhow!("the run's link is closed"))?;
            let welcome = Control::Welcome {
                version: PROTOCOL_VERSION,
                session: None,
                rx_seq: ours,
            };
            write_control(&mut tls, &welcome)
                .await
                .context("cannot answer the bridge")?;
            link.attach(Box::new(tls), rx_seq)
                .context("the run's link did not take the connection")?;
            tracing::debug!(%run_id, "the sandbox bridge reconnected");
        }
        Decision::Reclaim {
            session,
            ready,
            link,
            inbound,
            rx_after,
        } => {
            // What the bridge sent to the process before this one is that one's: had it.
            let welcome = Control::Welcome {
                version: PROTOCOL_VERSION,
                session: None,
                rx_seq: rx_after,
            };
            if let Err(e) = write_control(&mut tls, &welcome).await {
                link.close();
                lock(&inner.runs).insert(run_id.clone(), Entry::Reclaiming { session, ready });
                return Err(e).context("cannot answer the bridge");
            }
            link.attach(Box::new(tls), rx_seq)
                .context("the run's link did not take the connection")?;
            if ready
                .send(Established {
                    link: link.clone(),
                    inbound,
                    session,
                    build,
                })
                .is_err()
            {
                link.close();
            }
            tracing::info!(%run_id, "the sandbox bridge of a run of this host's predecessor is back");
        }
    }
    Ok(())
}

/// Checks a `hello` against the registered runs, under the lock and without waiting.
fn decide(
    inner: &Inner,
    run_id: &str,
    ticket: Option<Hidden>,
    session: Option<Hidden>,
    rx_seq: u64,
    tx_seq: Option<u64>,
) -> Result<Decision> {
    let mut runs = lock(&inner.runs);
    let entry = runs.remove(run_id);
    let same = |a: &Hidden, b: &Hidden| secret_eq(a.expose().as_bytes(), b.expose().as_bytes());
    let decision = match (entry, ticket, session) {
        (
            Some(Entry::Waiting {
                ticket: expected,
                ready,
            }),
            Some(ticket),
            None,
        ) if same(&expected, &ticket) => {
            let (link, inbound) = link::spawn(REPLAY_BYTES, inner.timing.link);
            let session = Hidden::new(random_token()?);
            runs.insert(
                run_id.to_string(),
                Entry::Established {
                    session: session.clone(),
                    link: link.clone(),
                },
            );
            Decision::New {
                ticket: expected,
                session,
                ready,
                link,
                inbound,
            }
        }
        (
            Some(Entry::Established {
                session: expected,
                link,
            }),
            None,
            Some(session),
        ) if same(&expected, &session) => {
            let resumed = link.clone();
            runs.insert(
                run_id.to_string(),
                Entry::Established {
                    session: expected,
                    link,
                },
            );
            Decision::Resume(resumed)
        }
        (
            Some(Entry::Reclaiming {
                session: expected,
                ready,
            }),
            None,
            Some(session),
        ) if same(&expected, &session) => {
            let Some(rx_after) = tx_seq else {
                // A bridge that names no number cannot go on with a link of this process's.
                runs.insert(
                    run_id.to_string(),
                    Entry::Reclaiming {
                        session: expected,
                        ready,
                    },
                );
                return Ok(Decision::Refuse(
                    RefuseCode::UnsupportedVersion,
                    "this bridge cannot be taken back by a runner host that restarted",
                ));
            };
            if rx_seq > link::MAX_SEQ || rx_after > link::MAX_SEQ {
                // The agent may drive its bridge (threat model 6.13): no link has such numbers.
                runs.insert(
                    run_id.to_string(),
                    Entry::Reclaiming {
                        session: expected,
                        ready,
                    },
                );
                return Ok(Decision::Refuse(
                    RefuseCode::BadRequest,
                    "the hello names a frame number that no link reaches",
                ));
            }
            let (link, inbound) =
                link::spawn_after(REPLAY_BYTES, inner.timing.link, rx_seq, rx_after);
            runs.insert(
                run_id.to_string(),
                Entry::Established {
                    session: expected.clone(),
                    link: link.clone(),
                },
            );
            Decision::Reclaim {
                session: expected,
                ready,
                link,
                inbound,
                rx_after,
            }
        }
        (Some(entry), ticket, session) => {
            let refusal = match (&entry, ticket, session) {
                (Entry::Established { .. }, Some(_), _) => (
                    RefuseCode::TicketUsed,
                    "the run's ticket was used; a reconnect presents the session key",
                ),
                (_, _, Some(_)) => (RefuseCode::UnknownSession, "no such session"),
                _ => (RefuseCode::BadTicket, "no run waits for this ticket"),
            };
            runs.insert(run_id.to_string(), entry);
            Decision::Refuse(refusal.0, refusal.1)
        }
        (None, _, Some(_)) => Decision::Refuse(RefuseCode::UnknownSession, "no such session"),
        (None, _, None) => Decision::Refuse(RefuseCode::BadTicket, "no run waits for this ticket"),
    };
    Ok(decision)
}

async fn write_control(tls: &mut TlsStream<TcpStream>, control: &Control) -> Result<()> {
    let payload = serde_json::to_vec(control).expect("control messages serialize");
    let bytes = Frame::new(Kind::Control, frame::STREAM_CONTROL, 0, payload).encode();
    frame::write_bytes(tls, &bytes).await?;
    Ok(())
}

async fn refuse(tls: &mut TlsStream<TcpStream>, code: RefuseCode, message: &str) {
    let refused = Control::Refused {
        code,
        message: message.to_string(),
    };
    let _ = write_control(tls, &refused).await;
    let _ = tls.shutdown().await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_reclaim_that_names_a_frame_number_past_any_link_is_refused_and_waits_on() {
        let key = Hidden::new("k".repeat(64));
        let (server, _registrations) = BridgeServer::start_with(
            "127.0.0.1:0".parse().unwrap(),
            HostTiming::default(),
            &Identity::generate().unwrap(),
            vec![("run-1".to_string(), key.clone())],
        )
        .await
        .unwrap();
        for (rx_seq, tx_seq) in [(u64::MAX, 0), (0, u64::MAX), (link::MAX_SEQ + 1, 7)] {
            let decision = decide(
                &server.inner,
                "run-1",
                None,
                Some(key.clone()),
                rx_seq,
                Some(tx_seq),
            )
            .unwrap();
            assert!(
                matches!(decision, Decision::Refuse(RefuseCode::BadRequest, _)),
                "{rx_seq} {tx_seq}"
            );
        }
        // The run still waits for its bridge, which comes back with its own numbers.
        let decision = decide(&server.inner, "run-1", None, Some(key), 3, Some(5)).unwrap();
        assert!(matches!(decision, Decision::Reclaim { rx_after: 5, .. }));
    }

    /// A session over an in-memory pair of links, as after a handshake: the host's side, and
    /// the sandbox's link with what it receives.
    async fn session() -> (HostSession, LinkHandle, InboundReceiver) {
        let timing = HostTiming {
            link: Timing {
                tick: Duration::from_millis(20),
                ping_after: Duration::from_millis(200),
                dead_after: Duration::from_secs(30),
                write_timeout: Duration::from_secs(30),
            },
            handshake: Duration::from_secs(5),
            gone_after: Duration::from_secs(30),
            tunnel_connect: Duration::from_secs(2),
            dial_retry: Duration::from_millis(50),
        };
        let server = BridgeServer::start("127.0.0.1:0".parse().unwrap(), timing)
            .await
            .unwrap();
        let registration = server.register("run-1").unwrap();
        let (host, host_in) = link::spawn(REPLAY_BYTES, timing.link);
        let (sandbox, sandbox_in) = link::spawn(REPLAY_BYTES, timing.link);
        let (left, right) = tokio::io::duplex(256 * 1024);
        host.attach(Box::new(left), 0).unwrap();
        sandbox.attach(Box::new(right), 0).unwrap();
        let session = HostSession::new(
            registration,
            Established {
                link: host,
                inbound: host_in,
                session: Hidden::new("test"),
                build: None,
            },
        );
        (session, sandbox, sandbox_in)
    }

    #[tokio::test]
    async fn a_host_that_does_not_read_the_output_holds_the_sandbox_back_and_nothing_else() {
        let (mut session, sandbox, mut sandbox_in) = session().await;
        let mut io = session
            .start(Start {
                argv: vec!["true".into()],
                env: Vec::new(),
                cwd: None,
            })
            .await
            .unwrap();
        // The sandbox's adapter writes more chunks than the host's queue holds, and the host
        // reads none of them.
        let chunks = OUTPUT_QUEUE * 2;
        for i in 0..chunks {
            sandbox
                .send(
                    Kind::Data,
                    frame::STREAM_STDOUT,
                    format!("{i}\n").into_bytes(),
                )
                .await
                .unwrap();
        }
        // A request of the host's, sent while the output is held; its answer waits behind
        // the output, as everything of the link does, and is not lost.
        let state = session.state.clone();
        let waiter = tokio::spawn(async move {
            state
                .request(|id| Control::Close { id }, Duration::from_secs(20))
                .await
        });
        loop {
            match tokio::time::timeout(Duration::from_secs(5), sandbox_in.recv()).await {
                Ok(Some(Inbound::Control(Control::Close { id }))) => {
                    sandbox.send_control(&Control::Done { id }).await.unwrap();
                    break;
                }
                Ok(Some(_)) => {}
                other => panic!("no close request: {other:?}"),
            }
        }
        sandbox
            .send_control(&Control::Exited {
                code: Some(0),
                signal: None,
            })
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(300)).await;
        // Held, not ended: the adapter's exit has not come through, and the session is alive.
        assert!(io.exit.borrow().is_none());
        assert!(!session.has_ended());
        assert!(!waiter.is_finished());
        // Reading lets everything through, in order: the output, the answer, the exit.
        let mut out = Vec::new();
        while out.len() < chunks {
            match tokio::time::timeout(Duration::from_secs(5), io.output.recv()).await {
                Ok(Some(Output::Stdout(bytes))) => out.push(String::from_utf8(bytes).unwrap()),
                other => panic!("output ended early: {other:?}"),
            }
        }
        assert_eq!(out[0], "0\n");
        assert_eq!(out[chunks - 1], format!("{}\n", chunks - 1));
        assert!(matches!(
            tokio::time::timeout(Duration::from_secs(5), waiter)
                .await
                .unwrap()
                .unwrap(),
            Ok(Control::Done { .. })
        ));
        tokio::time::timeout(Duration::from_secs(5), io.exit.wait_for(Option::is_some))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            *io.exit.borrow(),
            Some(Exit::Exited {
                code: Some(0),
                signal: None
            })
        );
        assert!(
            io.output.recv().await.is_none(),
            "the output ends with the adapter"
        );
    }
}
