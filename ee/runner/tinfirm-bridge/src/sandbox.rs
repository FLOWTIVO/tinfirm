//! The bridge in the sandbox (ADR-0095), `tinfirm-runner-host bridge`: it connects to the
//! runner host with the run's ticket, listens for the tunnels the host names and unpacks the
//! files it sends, waits for `start`, runs the adapter in a process group of its own, carries
//! its stdio and the tunnels' connections, reconnects with the session key when the
//! connection is lost, and stops the agent when the host has been gone for 25 s. After the
//! adapter, it serves the host's requests for files and commands until the host closes.

use std::net::SocketAddr;
use std::os::unix::process::ExitStatusExt;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use std::sync::atomic::AtomicU32;
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio::time::{Instant, sleep, sleep_until, timeout};
use tokio_rustls::TlsConnector;

use crate::control::{Control, Exec, Hidden, RefuseCode, Signal, Start};
use crate::files;
use crate::frame::{self, Frame, Kind};
use crate::link::{self, Inbound, InboundReceiver, LinkHandle, Timing};
use crate::tunnel::{self, Streams, next_event};
use crate::{
    ENV_ADDR, ENV_COMMAND, ENV_LISTEN, ENV_PIN, ENV_PREFIX, ENV_RUN, ENV_TICKET, PROTOCOL_VERSION,
    tls,
};

/// The bytes the bridge keeps until the host acknowledges them (the adapter's output).
pub const REPLAY_BYTES: usize = 8 * 1024 * 1024;

/// The host could not be reached at all.
pub const EXIT_UNREACHABLE: i32 = 69;
/// The bridge failed for another reason.
pub const EXIT_FAILED: i32 = 70;
/// The host was gone for too long, or forgot the session; the agent was stopped.
pub const EXIT_HOST_GONE: i32 = 75;
/// The host refused the ticket.
pub const EXIT_REFUSED: i32 = 77;
/// The adapter could not be started.
pub const EXIT_START_FAILED: i32 = 127;

#[derive(Debug, Clone, Copy)]
pub struct SandboxTiming {
    pub link: Timing,
    /// With no connection for this long, the bridge stops the agent (ADR-0002).
    pub gone_after: Duration,
    /// Once the agent is stopped or has ended: how long the bridge keeps the sandbox, and its
    /// files, for a runner host that restarted to take the session back (ADR-0101).
    pub reclaim_wait: Duration,
    /// SIGINT, then SIGTERM after this, then SIGKILL after this again.
    pub stop_step: Duration,
    /// Between two attempts to connect.
    pub retry: Duration,
    /// One attempt: TCP, TLS and the handshake.
    pub connect_timeout: Duration,
    /// After the adapter exits: for its output to end and for the host's acknowledgement.
    pub drain_timeout: Duration,
    /// After the adapter exits, the bridge waits this long for each request of the host's,
    /// and ends without one.
    pub idle_after_exit: Duration,
    /// Listening ([`Config::listen`]): how long the bridge waits for the host's first
    /// connection, which comes once the host sees the VM.
    pub listen_wait: Duration,
}

impl Default for SandboxTiming {
    fn default() -> SandboxTiming {
        SandboxTiming {
            link: Timing::default(),
            gone_after: Duration::from_secs(25),
            reclaim_wait: Duration::from_secs(900),
            stop_step: Duration::from_secs(5),
            retry: Duration::from_millis(500),
            connect_timeout: Duration::from_secs(10),
            drain_timeout: Duration::from_secs(5),
            idle_after_exit: Duration::from_secs(600),
            listen_wait: Duration::from_secs(900),
        }
    }
}

pub struct Config {
    /// `HOST:PORT` of the host's listener.
    pub addr: String,
    /// Listen here for the host to dial in, in place of dialing `addr`: a VM per run, whose
    /// firewall lets nothing out. The host stays the TLS server.
    pub listen: Option<SocketAddr>,
    pub run_id: String,
    pub ticket: Hidden,
    pub pin: [u8; 32],
    pub timing: SandboxTiming,
    /// What built this binary, named in the `hello`: [`crate::BUILD`], or what the runner host
    /// says of itself, its version and the digest of its images' definitions (ADR-0101).
    pub build: String,
}

impl std::fmt::Debug for Config {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Config")
            .field("addr", &self.addr)
            .field("listen", &self.listen)
            .field("run_id", &self.run_id)
            .finish_non_exhaustive()
    }
}

impl Config {
    /// From `TINFIRM_BRIDGE_ADDR` (or `_LISTEN`), `_RUN`, `_TICKET` and `_PIN`.
    pub fn from_env() -> Result<Config> {
        let var = |name: &str| std::env::var(name).with_context(|| format!("{name} is not set"));
        let listen = match std::env::var(ENV_LISTEN) {
            Ok(value) if !value.is_empty() => Some(
                value
                    .parse::<SocketAddr>()
                    .with_context(|| format!("{ENV_LISTEN} is not an address"))?,
            ),
            _ => None,
        };
        Ok(Config {
            addr: if listen.is_some() {
                String::new()
            } else {
                var(ENV_ADDR)?
            },
            listen,
            run_id: var(ENV_RUN)?,
            ticket: Hidden::new(var(ENV_TICKET)?),
            pin: tls::parse_pin(&var(ENV_PIN)?).context(ENV_PIN)?,
            timing: SandboxTiming::default(),
            build: crate::BUILD.to_string(),
        })
    }
}

/// Runs the bridge and returns its exit code: the adapter's, or one of the `EXIT_*` codes.
pub async fn run(config: Config) -> i32 {
    match Bridge::new(config).run().await {
        Ok(code) => code,
        Err(e) => {
            eprintln!("tinfirm-bridge: {e:#}");
            EXIT_FAILED
        }
    }
}

enum Auth {
    Ticket(Hidden),
    Session(Hidden),
}

enum DialError {
    Refused(RefuseCode, String),
    Failed(anyhow::Error),
}

/// What it takes to open a connection: shared by the first attempt and the reconnects.
#[derive(Clone)]
struct Dial {
    addr: String,
    /// Listening: the host's connections arrive here, and none is dialed.
    listener: Option<Arc<TcpListener>>,
    run_id: String,
    connector: TlsConnector,
    connect_timeout: Duration,
    link: LinkHandle,
    build: String,
}

impl Dial {
    /// One attempt. On success the connection is the link's, and the session key of a
    /// first connection is returned.
    async fn connect(&self, auth: &Auth) -> Result<Option<Hidden>, DialError> {
        let tcp = match &self.listener {
            Some(listener) => match timeout(self.connect_timeout, listener.accept()).await {
                Ok(Ok((tcp, _))) => tcp,
                Ok(Err(e)) => return Err(DialError::Failed(e.into())),
                Err(_) => {
                    return Err(DialError::Failed(anyhow!(
                        "the runner host has not connected"
                    )));
                }
            },
            None => match timeout(self.connect_timeout, TcpStream::connect(&self.addr)).await {
                Ok(Ok(tcp)) => tcp,
                Ok(Err(e)) => {
                    return Err(DialError::Failed(
                        anyhow::Error::from(e).context(format!("cannot connect to {}", self.addr)),
                    ));
                }
                Err(_) => return Err(DialError::Failed(anyhow!("the attempt timed out"))),
            },
        };
        let attempt = async {
            let _ = tcp.set_nodelay(true);
            let mut tls = self
                .connector
                .connect(tls::server_name(), tcp)
                .await
                .context("the TLS handshake with the runner host failed")?;
            let rx_seq = self.link.rx_seq().await?;
            let tx_seq = self.link.tx_seq().await?;
            let (ticket, session) = match auth {
                Auth::Ticket(t) => (Some(t.clone()), None),
                Auth::Session(s) => (None, Some(s.clone())),
            };
            let hello = Control::Hello {
                version: PROTOCOL_VERSION,
                run_id: self.run_id.clone(),
                ticket,
                session,
                rx_seq,
                tx_seq: Some(tx_seq),
                build: Some(self.build.clone()),
            };
            let payload = serde_json::to_vec(&hello).expect("control messages serialize");
            frame::write_bytes(
                &mut tls,
                &Frame::new(Kind::Control, frame::STREAM_CONTROL, 0, payload).encode(),
            )
            .await?;
            let reply = frame::read_frame(&mut tls)
                .await?
                .context("the runner host closed the connection without an answer")?;
            let reply: Control = serde_json::from_slice(&reply.payload)
                .map_err(|_| anyhow!("the runner host's answer is not readable"))?;
            Ok::<_, anyhow::Error>((tls, reply))
        };
        let (tls, reply) = match timeout(self.connect_timeout, attempt).await {
            Ok(Ok(done)) => done,
            Ok(Err(e)) => return Err(DialError::Failed(e)),
            Err(_) => return Err(DialError::Failed(anyhow!("the attempt timed out"))),
        };
        match reply {
            Control::Welcome {
                version,
                session,
                rx_seq,
            } if version == PROTOCOL_VERSION => {
                self.link
                    .attach(Box::new(tls), rx_seq)
                    .map_err(|e| DialError::Failed(e.into()))?;
                Ok(session)
            }
            Control::Refused { code, message } => Err(DialError::Refused(code, message)),
            _ => Err(DialError::Failed(anyhow!(
                "the runner host answered with something other than welcome"
            ))),
        }
    }

    /// Tries until it connects with the session key, or the host refuses it.
    async fn reconnect(self, session: Hidden, retry: Duration) -> Result<(), String> {
        let auth = Auth::Session(session);
        loop {
            match self.connect(&auth).await {
                Ok(_) => return Ok(()),
                Err(DialError::Refused(code, message)) => {
                    return Err(format!("{code:?}: {message}"));
                }
                Err(DialError::Failed(_)) => sleep(retry).await,
            }
        }
    }
}

/// The steps of a stop: a signal now, and the next ones at their times.
struct Stopping {
    next: Instant,
    signals: Vec<Signal>,
    step: Duration,
}

impl Stopping {
    fn after(step: Duration, signals: Vec<Signal>) -> Stopping {
        Stopping {
            next: Instant::now() + step,
            signals,
            step,
        }
    }
}

/// The link's connection, as the bridge sees it.
struct Presence {
    gone_at: Option<Instant>,
    reconnect: Option<JoinHandle<Result<(), String>>>,
    /// Between two attempts to connect again.
    retry: Duration,
}

impl Presence {
    /// The connection was lost: the bridge tries to connect again until `wait` has passed.
    fn lost(&mut self, dial: &Dial, session: &Hidden, wait: Duration) {
        if self.gone_at.is_none() {
            self.gone_at = Some(Instant::now() + wait);
        }
        if self.reconnect.as_ref().is_none_or(|r| r.is_finished()) {
            self.reconnect = Some(tokio::spawn(
                dial.clone().reconnect(session.clone(), self.retry),
            ));
        }
    }

    fn back(&mut self) {
        self.gone_at = None;
        if let Some(task) = self.reconnect.take() {
            task.abort();
        }
    }

    fn give_up(&mut self) {
        self.gone_at = None;
        if let Some(task) = self.reconnect.take() {
            task.abort();
        }
    }
}

/// What the bridge serves the host besides the adapter: the tunnels, the files and the
/// host's commands. It ends with the bridge.
struct Services {
    link: LinkHandle,
    streams: Arc<Streams>,
    next: Arc<AtomicU32>,
    listeners: Vec<JoinHandle<()>>,
    tunnels_opened: bool,
    started: bool,
}

impl Services {
    fn new(link: LinkHandle) -> Services {
        Services {
            streams: Streams::new(link.clone()),
            link,
            next: Arc::new(AtomicU32::new(frame::STREAM_DYNAMIC)),
            listeners: Vec::new(),
            tunnels_opened: false,
            started: false,
        }
    }

    /// Serves a request of the host's; gives back every other event.
    async fn take(&mut self, event: Inbound) -> Option<Inbound> {
        match event {
            Inbound::Control(Control::Tunnels { id, tunnels }) => {
                self.open_tunnels(id, &tunnels).await;
            }
            Inbound::Control(Control::Put { id, stream, dir }) => self.put(id, stream, dir),
            Inbound::Control(Control::Get {
                id,
                stream,
                dir,
                paths,
                exclude,
            }) => self.get(id, stream, dir, paths, exclude),
            Inbound::Control(Control::Exec(exec)) => {
                let link = self.link.clone();
                tokio::spawn(async move {
                    let answer = run_exec(exec).await;
                    let _ = link.send_control(&answer).await;
                });
            }
            Inbound::Control(Control::Probe { id, checks }) => {
                let link = self.link.clone();
                tokio::spawn(async move {
                    let open = crate::probe::run(&checks).await;
                    let _ = link.send_control(&Control::Probed { id, open }).await;
                });
            }
            other => return Some(other),
        }
        None
    }

    fn answer(&self, answer: Control) {
        let link = self.link.clone();
        tokio::spawn(async move {
            let _ = link.send_control(&answer).await;
        });
    }

    /// Listens on the tunnels the host names, once and before `start`, and answers.
    async fn open_tunnels(&mut self, id: u64, tunnels: &[crate::control::Tunnel]) {
        let refused = if self.started {
            Some("the tunnels are opened before start")
        } else if self.tunnels_opened {
            Some("the tunnels were opened already")
        } else {
            None
        };
        if let Some(message) = refused {
            self.answer(Control::Failed {
                id,
                message: message.to_string(),
            });
            return;
        }
        self.tunnels_opened = true;
        match tunnel::listen(tunnels).await {
            Ok(bound) => {
                let mut reply = Vec::with_capacity(bound.len());
                for (tunnel, listener) in bound {
                    self.listeners.push(tokio::spawn(self.streams.clone().serve(
                        listener,
                        tunnel.id,
                        self.next.clone(),
                    )));
                    reply.push(tunnel);
                }
                self.answer(Control::Listening { id, tunnels: reply });
            }
            Err(message) => self.answer(Control::Failed { id, message }),
        }
    }

    /// Unpacks the archive the host sends on `stream` into `dir`.
    fn put(&self, id: u64, stream: u32, dir: String) {
        // Registered at once: the stream's bytes follow this request.
        let Some(opened) = self.streams.open(stream) else {
            self.answer(Control::Failed {
                id,
                message: format!("stream {stream} cannot be opened"),
            });
            return;
        };
        let (streams, link) = (self.streams.clone(), self.link.clone());
        tokio::spawn(async move {
            let target = PathBuf::from(&dir);
            let unpacked = files::receive(&streams, opened, move |input| {
                files::unpack_trusted(input, &target)
            })
            .await;
            let answer = match unpacked {
                Ok(()) => Control::Done { id },
                Err(e) => Control::Failed {
                    id,
                    message: format!("cannot unpack into {dir}: {e}"),
                },
            };
            let _ = link.send_control(&answer).await;
        });
    }

    /// Packs `paths` of `dir` onto `stream`, for the host.
    fn get(&self, id: u64, stream: u32, dir: String, paths: Vec<String>, exclude: Vec<String>) {
        let Some(opened) = self.streams.open(stream) else {
            self.answer(Control::Failed {
                id,
                message: format!("stream {stream} cannot be opened"),
            });
            return;
        };
        let (streams, link) = (self.streams.clone(), self.link.clone());
        tokio::spawn(async move {
            let source = PathBuf::from(&dir);
            let sent = files::send(&streams, opened, move |out| {
                files::pack(&source, &paths, &exclude, out).map(|_| ())
            })
            .await;
            let answer = match sent {
                Ok(()) => Control::Done { id },
                Err(e) => Control::Failed {
                    id,
                    message: format!("cannot pack {dir}: {e}"),
                },
            };
            let _ = link.send_control(&answer).await;
        });
    }

    /// No new connections to the tunnels; those open go on.
    fn stop_listening(&mut self) {
        for listener in self.listeners.drain(..) {
            listener.abort();
        }
    }
}

impl Drop for Services {
    fn drop(&mut self) {
        self.streams.close();
        self.stop_listening();
    }
}

/// Runs a command of the host's ([`Exec`]) and answers with its exit and its output.
async fn run_exec(exec: Exec) -> Control {
    let id = exec.id;
    let failed = |message: String| Control::Failed { id, message };
    let Some(program) = exec.argv.first() else {
        return failed("the command has no program".to_string());
    };
    let mut cmd = Command::new(program);
    cmd.args(&exec.argv[1..]);
    for (key, _) in std::env::vars_os() {
        if key.to_string_lossy().starts_with(ENV_PREFIX) {
            cmd.env_remove(&key);
        }
    }
    for (key, value) in &exec.env {
        cmd.env(key, value.expose());
    }
    if let Some(cwd) = &exec.cwd {
        cmd.current_dir(cwd);
    }
    cmd.stdin(if exec.stdin.is_some() {
        Stdio::piped()
    } else {
        Stdio::null()
    })
    .stdout(Stdio::piped())
    .stderr(Stdio::piped())
    .kill_on_drop(true);
    let mut child = match cmd.spawn() {
        Ok(child) => child,
        Err(e) => return failed(format!("cannot run {program}: {e}")),
    };
    if let (Some(input), Some(mut stdin)) = (exec.stdin, child.stdin.take()) {
        tokio::spawn(async move {
            let _ = stdin.write_all(input.as_bytes()).await;
        });
    }
    let seconds = exec.timeout_seconds.max(1);
    match timeout(Duration::from_secs(seconds), child.wait_with_output()).await {
        Err(_) => failed(format!("{program} did not end within {seconds} s")),
        Ok(Err(e)) => failed(format!("cannot wait for {program}: {e}")),
        Ok(Ok(output)) => Control::ExecDone {
            id,
            code: output.status.code(),
            signal: output.status.signal(),
            stdout: cut(&output.stdout),
            stderr: cut(&output.stderr),
        },
    }
}

fn cut(bytes: &[u8]) -> String {
    String::from_utf8_lossy(&bytes[..bytes.len().min(Exec::MAX_OUTPUT)]).into_owned()
}

struct Bridge {
    config: Config,
}

impl Bridge {
    fn new(config: Config) -> Bridge {
        Bridge { config }
    }

    async fn run(self) -> Result<i32> {
        let timing = self.config.timing;
        let (link, mut inbound) = link::spawn(REPLAY_BYTES, timing.link);
        let mut services = Services::new(link.clone());
        let streams = services.streams.clone();
        let listener = match self.config.listen {
            Some(addr) => {
                Some(Arc::new(TcpListener::bind(addr).await.with_context(
                    || format!("the bridge cannot listen on {addr}"),
                )?))
            }
            None => None,
        };
        let listening = listener.is_some();
        let dial = Dial {
            addr: self.config.addr.clone(),
            listener,
            run_id: self.config.run_id.clone(),
            connector: TlsConnector::from(tls::client_config(self.config.pin)),
            connect_timeout: timing.connect_timeout,
            link: link.clone(),
            build: self.config.build.clone(),
        };

        // 1. The first connection, with the ticket.
        let deadline = Instant::now()
            + if listening {
                timing.listen_wait
            } else {
                timing.gone_after
            };
        let ticket = Auth::Ticket(self.config.ticket.clone());
        let session = loop {
            match dial.connect(&ticket).await {
                Ok(Some(session)) => break session,
                Ok(None) => {
                    eprintln!("tinfirm-bridge: the runner host sent no session key");
                    return Ok(EXIT_FAILED);
                }
                Err(DialError::Refused(code, message)) => {
                    eprintln!(
                        "tinfirm-bridge: the runner host refused the bridge ({code:?}): {message}"
                    );
                    return Ok(EXIT_REFUSED);
                }
                Err(DialError::Failed(e)) => {
                    if Instant::now() >= deadline {
                        match self.config.listen {
                            Some(addr) => eprintln!(
                                "tinfirm-bridge: the runner host did not connect to {addr}: {e:#}"
                            ),
                            None => eprintln!(
                                "tinfirm-bridge: cannot reach the runner host at {}: {e:#}",
                                self.config.addr
                            ),
                        }
                        return Ok(EXIT_UNREACHABLE);
                    }
                    sleep(timing.retry).await;
                }
            }
        };
        let mut presence = Presence {
            gone_at: None,
            reconnect: None,
            retry: timing.retry,
        };

        // 2. The tunnels and the files the host sends, and `start`.
        let start = loop {
            tokio::select! {
                event = next_event(&mut inbound, &streams) => {
                    let event = match event {
                        Some(event) => services.take(event).await,
                        None => return Ok(EXIT_FAILED),
                    };
                    match event {
                        Some(Inbound::Control(Control::Start(start))) => break start,
                        Some(Inbound::Control(Control::Close { id })) => {
                            let _ = link.send_control(&Control::Done { id }).await;
                            let _ = timeout(timing.drain_timeout, link.drained()).await;
                            link.close();
                            return Ok(0);
                        }
                        Some(Inbound::Control(Control::Stop { .. })) => {
                            let _ = timeout(timing.drain_timeout, link.drained()).await;
                            link.close();
                            return Ok(0);
                        }
                        Some(Inbound::Control(Control::Reclaim { id })) => {
                            let _ = link.send_control(&Control::Reclaimed { id, running: false }).await;
                        }
                        Some(Inbound::Disconnected) => presence.lost(&dial, &session, timing.gone_after),
                        Some(Inbound::Connected) => presence.back(),
                        _ => {}
                    }
                }
                _ = sleep_until(presence.gone_at.unwrap_or_else(Instant::now)), if presence.gone_at.is_some() => {
                    presence.give_up();
                    link.close();
                    return Ok(EXIT_HOST_GONE);
                }
                refused = join(&mut presence.reconnect), if presence.reconnect.is_some() => {
                    if refused.is_some() {
                        presence.give_up();
                        link.close();
                        return Ok(EXIT_HOST_GONE);
                    }
                }
            }
        };
        services.started = true;

        // 3. The adapter.
        let mut child = match spawn_adapter(&start) {
            Ok(child) => child,
            Err(e) => {
                let message = format!("cannot start the adapter: {e:#}");
                let _ = link.send_control(&Control::Error { message }).await;
                let _ = link
                    .send_control(&Control::Exited {
                        code: Some(EXIT_START_FAILED),
                        signal: None,
                    })
                    .await;
                self.after_exit(&mut inbound, &mut services, &mut presence, &dial, &session)
                    .await;
                return Ok(EXIT_START_FAILED);
            }
        };
        let pid = child.id().unwrap_or(0);
        let _ = link.send_control(&Control::Started { pid }).await;
        let (stdin_tx, stdin_rx) = mpsc::unbounded_channel();
        let mut stdin_tx = Some(stdin_tx);
        tokio::spawn(feed_stdin(child.stdin.take(), stdin_rx));
        let mut stdout = tokio::spawn(pump(
            child.stdout.take(),
            frame::STREAM_STDOUT,
            link.clone(),
        ));
        let mut stderr = tokio::spawn(pump(
            child.stderr.take(),
            frame::STREAM_STDERR,
            link.clone(),
        ));

        let mut stopping: Option<Stopping> = None;
        // The host is gone for good (the link ended, or the host forgot the session): the
        // bridge ends with the agent.
        let mut host_gone = false;
        // The host has had no connection for `gone_after`: the agent stops, and the sandbox
        // waits for a host that restarted to take the session back (ADR-0101).
        let mut orphaned = false;
        let status = loop {
            tokio::select! {
                status = child.wait() => break status.context("cannot wait for the adapter")?,
                event = next_event(&mut inbound, &streams) => {
                    let event = match event {
                        Some(event) => services.take(event).await,
                        None => {
                            host_gone = true;
                            break stop_now(&mut child, pid, timing.stop_step).await;
                        }
                    };
                    match event {
                        Some(Inbound::Data { stream: frame::STREAM_STDIN, bytes }) => {
                            if let Some(tx) = &stdin_tx {
                                let _ = tx.send(bytes);
                            }
                        }
                        Some(Inbound::Eof { stream: frame::STREAM_STDIN }) => stdin_tx = None,
                        Some(Inbound::Control(Control::Signal { signal })) => signal_group(pid, signal).await,
                        Some(Inbound::Control(Control::Stop { grace_seconds })) => {
                            signal_group(pid, Signal::Term).await;
                            stopping = Some(Stopping::after(
                                Duration::from_secs(grace_seconds.max(1)),
                                vec![Signal::Kill],
                            ));
                        }
                        Some(Inbound::Control(Control::Reclaim { id })) => {
                            // A host that restarted: the agent its predecessor started stops,
                            // and this host gets its end and the files.
                            let _ = link.send_control(&Control::Reclaimed { id, running: true }).await;
                            orphaned = false;
                            if stopping.is_none() {
                                signal_group(pid, Signal::Int).await;
                                stopping = Some(Stopping::after(timing.stop_step, vec![Signal::Term, Signal::Kill]));
                            }
                        }
                        Some(Inbound::Disconnected) => presence.lost(&dial, &session, timing.gone_after),
                        Some(Inbound::Connected) => presence.back(),
                        _ => {}
                    }
                }
                _ = sleep_until(presence.gone_at.unwrap_or_else(Instant::now)), if presence.gone_at.is_some() && !host_gone && !orphaned => {
                    // The reconnects go on: a host that restarted may still take it back.
                    presence.gone_at = None;
                    orphaned = true;
                    if stopping.is_none() {
                        signal_group(pid, Signal::Int).await;
                        stopping = Some(Stopping::after(timing.stop_step, vec![Signal::Term, Signal::Kill]));
                    }
                }
                refused = join(&mut presence.reconnect), if presence.reconnect.is_some() => {
                    if refused.is_some() && !host_gone {
                        // The host forgot the session: it will not come back for this run.
                        presence.give_up();
                        host_gone = true;
                        if stopping.is_none() {
                            signal_group(pid, Signal::Int).await;
                            stopping = Some(Stopping::after(timing.stop_step, vec![Signal::Term, Signal::Kill]));
                        }
                    }
                }
                _ = sleep_until(stopping.as_ref().map_or_else(Instant::now, |s| s.next)), if stopping.is_some() => {
                    let plan = stopping.as_mut().expect("guarded");
                    let signal = plan.signals.remove(0);
                    signal_group(pid, signal).await;
                    if plan.signals.is_empty() {
                        stopping = None;
                    } else {
                        plan.next = Instant::now() + plan.step;
                    }
                }
            }
        };
        let (code, signal) = (status.code(), status.signal());
        if host_gone {
            stdout.abort();
            stderr.abort();
            link.close();
            return Ok(EXIT_HOST_GONE);
        }
        if orphaned {
            // The agent's last output is no one's now. The files stay for a host that restarted.
            stdout.abort();
            stderr.abort();
            if !*link.connected().borrow() {
                presence.lost(&dial, &session, timing.reclaim_wait);
            }
            let closed = self
                .after_exit(&mut inbound, &mut services, &mut presence, &dial, &session)
                .await;
            return Ok(if closed {
                code.unwrap_or_else(|| 128 + signal.unwrap_or(0))
            } else {
                EXIT_HOST_GONE
            });
        }
        // A process the agent left running may hold stdout open: wait a bounded time.
        let ended = timeout(timing.drain_timeout, async {
            let _ = (&mut stdout).await;
            let _ = (&mut stderr).await;
        })
        .await;
        if ended.is_err() {
            stdout.abort();
            stderr.abort();
        }
        let _ = timeout(
            timing.drain_timeout,
            link.send_control(&Control::Exited { code, signal }),
        )
        .await;
        self.after_exit(&mut inbound, &mut services, &mut presence, &dial, &session)
            .await;
        Ok(code.unwrap_or_else(|| 128 + signal.unwrap_or(0)))
    }

    /// After the adapter (step 4): the host takes the sandbox's files and runs its git steps,
    /// then sends `close`. Ends there, when the host is gone, or after
    /// [`SandboxTiming::idle_after_exit`] without a word from a connected host. Without a
    /// connection it waits [`SandboxTiming::reclaim_wait`] for one: a host that restarted
    /// takes the files then (ADR-0101). Returns whether the host closed the session.
    async fn after_exit(
        &self,
        inbound: &mut InboundReceiver,
        services: &mut Services,
        presence: &mut Presence,
        dial: &Dial,
        session: &Hidden,
    ) -> bool {
        let timing = self.config.timing;
        services.stop_listening();
        let streams = services.streams.clone();
        let mut idle = Box::pin(sleep(timing.idle_after_exit));
        let mut closed = false;
        loop {
            tokio::select! {
                event = next_event(inbound, &streams) => {
                    idle.as_mut().reset(Instant::now() + timing.idle_after_exit);
                    let event = match event {
                        Some(event) => services.take(event).await,
                        None => break,
                    };
                    match event {
                        Some(Inbound::Control(Control::Close { id })) => {
                            let _ = dial.link.send_control(&Control::Done { id }).await;
                            closed = true;
                            break;
                        }
                        Some(Inbound::Control(Control::Stop { .. })) => break,
                        Some(Inbound::Control(Control::Reclaim { id })) => {
                            let _ = dial.link.send_control(&Control::Reclaimed { id, running: false }).await;
                        }
                        Some(Inbound::Disconnected) => presence.lost(dial, session, timing.reclaim_wait),
                        Some(Inbound::Connected) => presence.back(),
                        _ => {}
                    }
                }
                _ = &mut idle, if presence.gone_at.is_none() => {
                    eprintln!("tinfirm-bridge: the runner host did not close the session");
                    break;
                }
                _ = sleep_until(presence.gone_at.unwrap_or_else(Instant::now)), if presence.gone_at.is_some() => {
                    presence.give_up();
                    break;
                }
                refused = join(&mut presence.reconnect), if presence.reconnect.is_some() => {
                    if refused.is_some() {
                        presence.give_up();
                        break;
                    }
                }
            }
        }
        let _ = timeout(timing.drain_timeout, dial.link.drained()).await;
        dial.link.close();
        closed
    }
}

/// The result of a finished reconnect: `Some` when the host refused the session.
async fn join(task: &mut Option<JoinHandle<Result<(), String>>>) -> Option<String> {
    let result = task.as_mut().expect("guarded").await;
    *task = None;
    match result {
        Ok(Err(refused)) => Some(refused),
        _ => None,
    }
}

async fn stop_now(child: &mut Child, pid: u32, step: Duration) -> std::process::ExitStatus {
    for signal in [Signal::Int, Signal::Term, Signal::Kill] {
        signal_group(pid, signal).await;
        if let Ok(Ok(status)) = timeout(step, child.wait()).await {
            return status;
        }
    }
    child
        .wait()
        .await
        .unwrap_or_else(|_| std::process::ExitStatus::from_raw(9))
}

fn spawn_adapter(start: &Start) -> Result<Child> {
    start.check().map_err(|e| anyhow!(e))?;
    let argv = if start.argv.is_empty() {
        default_command()?
    } else {
        start.argv.clone()
    };
    let mut cmd = Command::new(&argv[0]);
    cmd.args(&argv[1..]);
    for (key, _) in std::env::vars_os() {
        if key.to_string_lossy().starts_with(ENV_PREFIX) {
            cmd.env_remove(&key);
        }
    }
    for (key, value) in &start.env {
        cmd.env(key, value.expose());
    }
    if let Some(cwd) = &start.cwd {
        cmd.current_dir(cwd);
    }
    cmd.stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .process_group(0)
        .kill_on_drop(true);
    cmd.spawn()
        .with_context(|| format!("cannot run {}", argv[0]))
}

/// The sandbox's own command ([`ENV_COMMAND`]), for a `start` that names none.
fn default_command() -> Result<Vec<String>> {
    let value = std::env::var(ENV_COMMAND)
        .with_context(|| format!("start names no command, and {ENV_COMMAND} is not set"))?;
    let argv: Vec<String> = serde_json::from_str(&value)
        .with_context(|| format!("{ENV_COMMAND} is not a JSON array of strings"))?;
    if argv.first().is_none_or(|program| program.is_empty()) {
        bail!("{ENV_COMMAND} names no program");
    }
    Ok(argv)
}

async fn pump(reader: Option<impl AsyncRead + Unpin>, stream: u32, link: LinkHandle) {
    let Some(mut reader) = reader else {
        return;
    };
    let mut buf = vec![0u8; 32 * 1024];
    loop {
        match reader.read(&mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                if link
                    .send(Kind::Data, stream, buf[..n].to_vec())
                    .await
                    .is_err()
                {
                    return;
                }
            }
        }
    }
    let _ = link.send(Kind::Eof, stream, Vec::new()).await;
}

async fn feed_stdin(stdin: Option<ChildStdin>, mut rx: mpsc::UnboundedReceiver<Vec<u8>>) {
    let Some(mut stdin) = stdin else {
        return;
    };
    while let Some(bytes) = rx.recv().await {
        if stdin.write_all(&bytes).await.is_err() || stdin.flush().await.is_err() {
            return;
        }
    }
    // The host ended stdin: dropping it closes the adapter's.
}

/// Signals the adapter's process group with the `kill` utility (no unsafe code), or the
/// shell's builtin where the utility is missing.
async fn signal_group(pid: u32, signal: Signal) {
    if pid == 0 {
        return;
    }
    let group = format!("-{pid}");
    let sent = Command::new("kill")
        .args(["-s", signal.name(), "--", &group])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await;
    if !matches!(sent, Ok(status) if status.success()) {
        let _ = Command::new("sh")
            .args([
                "-c",
                "kill -s \"$1\" -- \"-$2\"",
                "sh",
                signal.name(),
                &pid.to_string(),
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .await;
    }
}
