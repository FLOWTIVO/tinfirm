//! The wire between `run` and a front process (ADR-0112, path C, part 2).
//!
//! The control channel is a Unix stream socket: one end is `run`'s, the other the front's
//! stdin. Each message is a frame: its length as 4 bytes, big-endian, then that many bytes of
//! JSON. A message is one of the closed types below, with no unknown field, and passes its own
//! [`ToMain::check`] or [`ToFront::check`] before anyone acts on it. The bounds are the
//! receiver's: a frame from a front is at most [`MAX_TO_MAIN`] bytes, one from `run` at most
//! [`MAX_TO_FRONT`].
//!
//! Descriptors go one way only, from `run` to the front, with `SCM_RIGHTS` on the first byte
//! of the frame they belong to ([`send_frame`]). `run` reads its end with plain reads: a
//! descriptor that a front sends the other way is closed by the kernel, never received.
//!
//! The messages of this version: the front's [`ToMain::Ready`] with its confinement, then a
//! round trip ([`ToFront::Ping`]) and a check that descriptors arrive ([`ToFront::Check`]);
//! the egress proxy's listener ([`ToFront::ListenEgress`]), and its destinations, which the
//! front asks `run` for ([`ToMain::Connect`]) and `run` connects or refuses; the model proxy's
//! listener ([`ToFront::ListenModel`]), and its requests ([`ToMain::Request`]), whose bodies
//! go each on a socket of its own ([`ToFront::Body`]) while the control channel carries their
//! heads and ends; a run's listener of the git proxy and the platform tools
//! ([`ToFront::ListenGit`]) and its routes ([`ToFront::GitRoutes`]), whose requests go the same
//! way; and the sandbox's bridge ([`ToFront::BridgeInit`]): each connection's `hello`, which
//! `run` decides ([`ToMain::Hello`], [`ToFront::Admit`]), the adapter's start with its stdio on
//! three sockets, its signals and stop, the reclaim, the close, the host's commands and the
//! network probe, and the ends of the adapter and of the session; the sandbox's files, which go
//! in on a socket of their own ([`ToFront::Put`]) and come out on a pipe that is a reader's
//! stdin in `run` ([`ToFront::Get`]); and the tunnels, each to a service of the front
//! ([`ToFront::OpenTunnels`]).
//!
//! Every message from a front is at most [`MAX_TO_MAIN`] bytes but a command's end, which
//! holds its two outputs in base64 and may take [`MAX_EXEC_FRAME`].
//!
//! Every request of a front has an id that grows with each request ([`ToMain::Connect`] and
//! [`ToMain::Request`] share them), so that `run` can tell an answer to a request it ended
//! from one to a request that never was.

use std::io::{IoSlice, Write};
use std::os::fd::{AsFd, BorrowedFd, OwnedFd};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::confine::Status;
use crate::model_proxy::{ModelApi, RequestHead, ResponseHead};

/// The length prefix of a frame.
pub const HEADER: usize = 4;
/// The largest frame a front may send, but for a command's end ([`MAX_EXEC_FRAME`]).
pub const MAX_TO_MAIN: usize = 64 * 1024;
/// The largest frame of a command's end ([`Reply::ExecDone`]): its two outputs, in base64,
/// and room for the rest. Every other message stays within [`MAX_TO_MAIN`].
pub const MAX_EXEC_FRAME: usize = 192 * 1024;
/// The largest frame `run` may send.
pub const MAX_TO_FRONT: usize = 1024 * 1024;
/// The longest text a front may put in a message (its confinement's problem).
pub const MAX_TEXT: usize = 4 * 1024;
/// What a front writes on the descriptor of a [`ToFront::Check`], then closes it.
pub const CHECK_REPLY: &[u8] = b"tinfirm-front-check\n";

/// `run` to a front.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", deny_unknown_fields)]
pub enum ToFront {
    /// The run the front serves; the first message, once.
    Init {
        run: Uuid,
    },
    /// Answer [`ToMain::Pong`] with the same id.
    Ping {
        id: u64,
    },
    /// With a descriptor: write [`CHECK_REPLY`] on it, close it, and answer
    /// [`ToMain::Checked`] with the same id.
    Check {
        id: u64,
    },
    /// Serve the egress proxy's HTTP, for clients that send
    /// `Proxy-Authorization: <authorization>` (crate::egress_proxy): with `listener`, on the
    /// TCP listener that `run` bound and passes along; always for the tunnels that name it.
    /// Every destination is `run`'s to decide ([`ToMain::Connect`]).
    ListenEgress {
        authorization: String,
        listener: bool,
    },
    /// With a descriptor, the TCP connection `run` made for [`ToMain::Connect`] `req`.
    Connected {
        req: u64,
    },
    /// `run` refused [`ToMain::Connect`] `req`: the front answers its client with `status`
    /// and `message`, and with the header `x-tinfirm-egress: denied` when `off_allowlist`
    /// (a `403` only: the destination is on none of the run's allowlists).
    Refused {
        req: u64,
        status: u16,
        message: String,
        off_allowlist: bool,
    },
    /// Serve the model proxy's HTTP for the run whose secret is `secret` (crate::model_proxy):
    /// with `listener`, on the TCP listener that `run` passes along; always for the tunnels
    /// that name it. Its requests go to `run` ([`ToMain::Request`]); the key never comes here.
    ListenModel {
        secret: String,
        api: ModelApi,
        base_path: String,
        listener: bool,
    },
    /// Stop serving `service`'s listener and end its connections.
    Unlisten {
        service: Service,
    },
    /// With a descriptor, one end of a socket pair for [`ToMain::Request`] `req`: the front
    /// writes the request's body on it, then shuts its writing down; `run` writes the answer's
    /// body on it.
    Body {
        req: u64,
    },
    /// The answer's status and headers for `req`; its body follows on the request's socket.
    Response {
        req: u64,
        head: ResponseHead,
    },
    /// `run` is done with `req`: the answer's body ended whole (`ok`), or was cut off. Nothing
    /// of `req` comes after this.
    End {
        req: u64,
        ok: bool,
    },
    /// Serve one run's git proxy fetches and platform tools' endpoint (crate::git_proxy),
    /// within `limits`, for the routes of [`ToFront::GitRoutes`]: with `listener`, on the TCP
    /// listener that `run` passes along; always for the tunnels that name it. Their requests
    /// go to `run` ([`ToMain::Request`]); no git token and no tool comes here.
    ListenGit {
        limits: FrontLimits,
        listener: bool,
    },
    /// The run's routes as they are now, which replace those the front had: each with its
    /// number, which is all that the front hands `run` of it.
    GitRoutes {
        routes: Vec<GitRouteSpec>,
        mcp: Vec<McpRouteSpec>,
    },
    /// The sandbox's bridge (ADR-0095) is the front's: its TLS identity, which no other
    /// sandbox's bridge pins, and its timing. With `listen`, a TCP listener that `run` bound
    /// comes with it, on which the sandbox's bridge connects; without, `run` dials the bridge
    /// and passes each connection ([`ToFront::Dialed`]).
    BridgeInit {
        identity: WireIdentity,
        timing: WireTiming,
        listen: bool,
    },
    /// The address that the sandbox's bridge connects from: a connection from any other is
    /// closed before its TLS handshake.
    AllowPeer {
        ip: std::net::IpAddr,
    },
    /// With a descriptor: a TCP connection to the sandbox's bridge that `run` dialed.
    Dialed {},
    /// `run`'s decision on the `hello` of [`ToMain::Hello`] `req`.
    Admit {
        req: u64,
        admission: Admission,
    },
    /// With three descriptors, the adapter's stdin, stdout and stderr: start the adapter
    /// ([`Reply::Done`] once its `start` went to the sandbox).
    Start {
        id: u64,
        start: tinfirm_bridge::control::Start,
    },
    Signal {
        signal: tinfirm_bridge::control::Signal,
    },
    Stop {
        grace_seconds: u64,
    },
    /// Stop the agent of an earlier host ([`Reply::Reclaimed`]).
    Reclaim {
        id: u64,
        timeout_ms: u64,
    },
    /// The adapter has ended and `run` has what it needs: the bridge ends ([`Reply::Done`]).
    Close {
        id: u64,
        timeout_ms: u64,
    },
    /// Run a command in the sandbox ([`Reply::ExecDone`]); its `id` is the request's.
    Exec {
        exec: tinfirm_bridge::control::Exec,
    },
    /// With a descriptor, one end of a socket pair on which `run` writes a tar archive of its
    /// own: into the sandbox's directory `dir` ([`Reply::Done`] once it is unpacked).
    Put {
        id: u64,
        dir: String,
        timeout_ms: u64,
    },
    /// With a descriptor, the writing end of a pipe whose reading end is a reader's stdin in
    /// `run`: the sandbox's `paths` of `dir` (all when empty), without `exclude`, as a tar
    /// archive, written on it ([`Reply::Done`] once it went whole).
    Get {
        id: u64,
        dir: String,
        paths: Vec<String>,
        exclude: Vec<String>,
        timeout_ms: u64,
    },
    /// Before the start: the sandbox listens on each tunnel's address, and the connections it
    /// gets there go to the front's service the tunnel names, never to an address
    /// ([`Reply::Listening`]).
    OpenTunnels {
        id: u64,
        tunnels: Vec<WireTunnel>,
        timeout_ms: u64,
    },
    /// Probe the sandbox's network from inside ([`Reply::Probed`]).
    Probe {
        id: u64,
        checks: Vec<tinfirm_bridge::control::ProbeCheck>,
        timeout_ms: u64,
    },
}

/// A tunnel: where the sandbox listens, and the front's service its connections reach.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WireTunnel {
    pub listen: std::net::SocketAddr,
    pub service: Service,
}

/// The longest path of the sandbox's a `put` or a `get` names, and the most paths of a `get`.
pub const MAX_FILES_PATH: usize = 4096;
pub const MAX_FILES_PATHS: usize = 1024;

/// A bridge's TLS identity on the wire: its certificate and its PKCS #8 key, DER in base64.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WireIdentity {
    pub cert: String,
    pub key: String,
}

/// The longest certificate or key of a [`WireIdentity`], in base64.
pub const MAX_IDENTITY_PART: usize = 16 * 1024;

/// The bridge's timing ([`tinfirm_bridge::host::HostTiming`]) in milliseconds.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WireTiming {
    pub tick_ms: u64,
    pub ping_after_ms: u64,
    pub dead_after_ms: u64,
    pub write_timeout_ms: u64,
    pub handshake_ms: u64,
    pub gone_after_ms: u64,
    pub tunnel_connect_ms: u64,
}

/// The longest time of a [`WireTiming`]: a day.
const MAX_TIMING_MS: u64 = 24 * 60 * 60 * 1000;

impl WireTiming {
    pub fn of(timing: &tinfirm_bridge::host::HostTiming) -> WireTiming {
        let ms = |d: std::time::Duration| u64::try_from(d.as_millis()).unwrap_or(u64::MAX);
        WireTiming {
            tick_ms: ms(timing.link.tick),
            ping_after_ms: ms(timing.link.ping_after),
            dead_after_ms: ms(timing.link.dead_after),
            write_timeout_ms: ms(timing.link.write_timeout),
            handshake_ms: ms(timing.handshake),
            gone_after_ms: ms(timing.gone_after),
            tunnel_connect_ms: ms(timing.tunnel_connect),
        }
    }

    pub fn host_timing(&self) -> tinfirm_bridge::host::HostTiming {
        let d = std::time::Duration::from_millis;
        tinfirm_bridge::host::HostTiming {
            link: tinfirm_bridge::link::Timing {
                tick: d(self.tick_ms),
                ping_after: d(self.ping_after_ms),
                dead_after: d(self.dead_after_ms),
                write_timeout: d(self.write_timeout_ms),
            },
            handshake: d(self.handshake_ms),
            gone_after: d(self.gone_after_ms),
            tunnel_connect: d(self.tunnel_connect_ms),
            // `run` dials; the front never does.
            dial_retry: d(1000),
        }
    }

    fn check(&self) -> Result<(), String> {
        let all = [
            self.tick_ms,
            self.ping_after_ms,
            self.dead_after_ms,
            self.write_timeout_ms,
            self.handshake_ms,
            self.gone_after_ms,
            self.tunnel_connect_ms,
        ];
        if all.iter().all(|&ms| (1..=MAX_TIMING_MS).contains(&ms)) {
            Ok(())
        } else {
            Err("a bridge's time out of bounds".into())
        }
    }
}

/// `run`'s decision on a bridge's `hello` ([`tinfirm_bridge::host::judge`]).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum Admission {
    /// A new session, with its key.
    New {
        session: tinfirm_bridge::control::Hidden,
    },
    /// The session goes on with the front's link.
    Resume,
    /// The session of an earlier host goes on with a new link, after `rx_after`.
    Reclaim { rx_after: u64 },
    Refuse {
        code: tinfirm_bridge::control::RefuseCode,
        message: String,
    },
}

/// A bridge's `hello`, as a front hands it to `run` ([`tinfirm_bridge::host::Hello`]).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WireHello {
    pub run_id: String,
    pub ticket: Option<tinfirm_bridge::control::Hidden>,
    pub session: Option<tinfirm_bridge::control::Hidden>,
    pub rx_seq: u64,
    pub tx_seq: Option<u64>,
    pub build: Option<String>,
}

/// The longest run id, ticket or session key of a [`WireHello`], and its longest build.
pub const MAX_HELLO_NAME: usize = 128;
pub const MAX_HELLO_BUILD: usize = 64;

impl WireHello {
    pub fn of(hello: &tinfirm_bridge::host::Hello) -> WireHello {
        WireHello {
            run_id: hello.run_id.clone(),
            ticket: hello.ticket.clone(),
            session: hello.session.clone(),
            rx_seq: hello.rx_seq,
            tx_seq: hello.tx_seq,
            build: hello.build.clone(),
        }
    }

    pub fn hello(&self) -> tinfirm_bridge::host::Hello {
        tinfirm_bridge::host::Hello {
            run_id: self.run_id.clone(),
            ticket: self.ticket.clone(),
            session: self.session.clone(),
            rx_seq: self.rx_seq,
            tx_seq: self.tx_seq,
            build: self.build.clone(),
        }
    }

    /// Its bounds: `run` ends a front that sends a `hello` out of them, so a front refuses such
    /// a connection itself.
    pub fn check(&self) -> Result<(), String> {
        let name =
            |text: &str| text.len() <= MAX_HELLO_NAME && text.bytes().all(|b| b.is_ascii_graphic());
        let secret = |secret: &Option<tinfirm_bridge::control::Hidden>| {
            secret.as_ref().is_none_or(|s| name(s.expose()))
        };
        if !name(&self.run_id) || !secret(&self.ticket) || !secret(&self.session) {
            return Err("a hello's name or secret out of bounds".into());
        }
        if self
            .build
            .as_ref()
            .is_some_and(|b| b.len() > MAX_HELLO_BUILD || !b.bytes().all(|b| b.is_ascii_graphic()))
        {
            return Err("a hello's build out of bounds".into());
        }
        Ok(())
    }
}

/// A front's answer to a request of `run`'s about the sandbox's bridge.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum Reply {
    /// The start or the close is done.
    Done,
    /// The agent of an earlier host stopped, if it still ran.
    Reclaimed {
        running: bool,
    },
    /// The command's end, and its output, each at most
    /// [`tinfirm_bridge::control::Exec::MAX_OUTPUT`] bytes, in base64: the output is text
    /// that JSON would escape up to six times its length, base64 four thirds of it.
    ExecDone {
        code: Option<i32>,
        signal: Option<i32>,
        stdout: String,
        stderr: String,
    },
    /// The addresses the sandbox listens on for its tunnels, in the order asked.
    Listening {
        addrs: Vec<std::net::SocketAddr>,
    },
    /// The names of the checks that got through.
    Probed {
        open: Vec<String>,
    },
    Failed {
        message: String,
    },
}

/// The longest base64 of an exec's output.
pub const MAX_EXEC_OUTPUT_B64: usize = tinfirm_bridge::control::Exec::MAX_OUTPUT.div_ceil(3) * 4;
/// The most names in a [`Reply::Probed`], and the longest.
pub const MAX_PROBED: usize = 64;
pub const MAX_PROBE_NAME: usize = 256;
/// The `hello`s a front may have handed `run` that are not yet admitted and attached.
pub const MAX_HELLOS: usize = 8;

/// The bounds of a listener that a front serves: its open connections, the time a request's
/// headers have, and the time a connection may stay idle.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FrontLimits {
    pub max_connections: u32,
    pub header_read_ms: u64,
    pub idle_ms: u64,
}

/// A repository route of a run, as its front sees it: the secret and repository path of its
/// URL, and the branch the host pushes (for the refusal of a push).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GitRouteSpec {
    pub route: u64,
    pub secret: String,
    pub path: String,
    pub branch: String,
}

/// A run's platform tools' endpoint, as its front sees it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct McpRouteSpec {
    pub route: u64,
    pub secret: String,
}

/// The most routes of one listener, and the longest parts of one.
pub const MAX_ROUTES: usize = 64;
pub const MAX_ROUTE_SECRET: usize = 128;
pub const MAX_ROUTE_PATH: usize = 1024;
pub const MAX_ROUTE_BRANCH: usize = 256;

/// A listener of the front.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Service {
    Egress,
    Model,
    /// A run's git proxy and platform tools, which share a listener.
    Git,
}

/// The longest proxy credential `run` gives a front.
pub const MAX_AUTHORIZATION: usize = 1024;
/// The longest destination name a front may ask for: a DNS name's limit.
pub const MAX_HOST: usize = 253;
/// The connections a front may have asked for and not yet seen end: its open tunnels and
/// plain HTTP requests (256 each per run, as in-process), with room for those `run` decides.
pub const MAX_FRONT_REQUESTS: usize = 512;
/// The requests of one service (the model proxy's, or the git proxy's and the platform
/// tools') that a front may have open at `run`, from its `request` to `run`'s `end`; more wait
/// in the front.
pub const MAX_SERVICE_REQUESTS: usize = 64;
/// The longest secret and base path of a model listener.
pub const MAX_MODEL_LISTENER: usize = 1024;

impl ToFront {
    /// Whether this message comes with a descriptor.
    pub fn carries_descriptor(&self) -> bool {
        self.descriptors() > 0
    }

    /// How many descriptors come with this message.
    pub fn descriptors(&self) -> usize {
        match self {
            ToFront::ListenEgress { listener, .. }
            | ToFront::ListenModel { listener, .. }
            | ToFront::ListenGit { listener, .. } => usize::from(*listener),
            ToFront::Check { .. }
            | ToFront::Connected { .. }
            | ToFront::Body { .. }
            | ToFront::Dialed {}
            | ToFront::Put { .. }
            | ToFront::Get { .. } => 1,
            ToFront::BridgeInit { listen, .. } => usize::from(*listen),
            ToFront::Start { .. } => 3,
            _ => 0,
        }
    }

    /// The bounds of `run`'s messages, which a front checks too.
    pub fn check(&self) -> Result<(), String> {
        match self {
            ToFront::ListenEgress { authorization, .. }
                if authorization.len() > MAX_AUTHORIZATION =>
            {
                Err(format!("a proxy credential over {MAX_AUTHORIZATION} bytes"))
            }
            ToFront::Refused {
                status,
                message,
                off_allowlist,
                ..
            } => {
                if !(400..=599).contains(status) {
                    return Err(format!("a refusal with the status {status}"));
                }
                if *off_allowlist && *status != 403 {
                    return Err(format!(
                        "a refusal for the allowlist with the status {status}"
                    ));
                }
                if message.len() > MAX_TEXT {
                    return Err(format!("a refusal's message over {MAX_TEXT} bytes"));
                }
                Ok(())
            }
            ToFront::ListenModel {
                secret, base_path, ..
            } if secret.len() > MAX_MODEL_LISTENER || base_path.len() > MAX_MODEL_LISTENER => {
                Err("a model listener's secret or base path out of bounds".into())
            }
            ToFront::GitRoutes { routes, mcp } => {
                if routes.len() > MAX_ROUTES || mcp.len() > MAX_ROUTES {
                    return Err("too many routes".into());
                }
                let fits = |secret: &String| !secret.is_empty() && secret.len() <= MAX_ROUTE_SECRET;
                if !routes.iter().all(|r| {
                    fits(&r.secret)
                        && r.path.len() <= MAX_ROUTE_PATH
                        && r.branch.len() <= MAX_ROUTE_BRANCH
                }) || !mcp.iter().all(|r| fits(&r.secret))
                {
                    return Err("a route out of its bounds".into());
                }
                Ok(())
            }
            ToFront::BridgeInit {
                identity, timing, ..
            } => {
                if identity.cert.len() > MAX_IDENTITY_PART || identity.key.len() > MAX_IDENTITY_PART
                {
                    return Err("a bridge identity out of bounds".into());
                }
                timing.check()
            }
            ToFront::Admit {
                admission: Admission::Refuse { message, .. },
                ..
            } if message.len() > MAX_TEXT => {
                Err(format!("a refusal's message over {MAX_TEXT} bytes"))
            }
            ToFront::Start { start, .. } => start.check(),
            ToFront::Put { dir, .. } if dir.len() > MAX_FILES_PATH => {
                Err("a put's directory out of bounds".into())
            }
            ToFront::Get {
                dir,
                paths,
                exclude,
                ..
            } => {
                let fits = |p: &String| p.len() <= MAX_FILES_PATH;
                if !fits(dir)
                    || paths.len() > MAX_FILES_PATHS
                    || exclude.len() > MAX_FILES_PATHS
                    || !paths.iter().all(fits)
                    || !exclude.iter().all(fits)
                {
                    return Err("a get's paths out of bounds".into());
                }
                Ok(())
            }
            ToFront::OpenTunnels { tunnels, .. }
                if tunnels.len() > tinfirm_bridge::tunnel::MAX_TUNNELS =>
            {
                Err("too many tunnels".into())
            }
            ToFront::Response { head, .. } => {
                if !(100..=599).contains(&head.status) {
                    return Err(format!("an answer with the status {}", head.status));
                }
                if head.headers.len() > crate::model_proxy::MAX_RESPONSE_HEADERS {
                    return Err("an answer with too many headers".into());
                }
                Ok(())
            }
            _ => Ok(()),
        }
    }
}

/// A front to `run`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", deny_unknown_fields)]
pub enum ToMain {
    /// The front has confined itself, as `status` says; its first message, once.
    Ready {
        status: Status,
    },
    Pong {
        id: u64,
    },
    Checked {
        id: u64,
    },
    /// A client of the egress proxy wants `host:port` (a `CONNECT`, or a plain `http://`
    /// request): `run` decides, and answers [`ToFront::Connected`] or [`ToFront::Refused`].
    /// `req` grows with every request of the front.
    Connect {
        req: u64,
        host: String,
        port: u16,
    },
    /// The connection `run` gave for `req` has ended.
    Closed {
        req: u64,
    },
    /// A client of the model proxy sent a request that the front half let through: `run`
    /// checks the head again, and answers with [`ToFront::Body`], then [`ToFront::Response`]
    /// and [`ToFront::End`].
    Request {
        req: u64,
        head: RequestHead,
    },
    /// The request body of `req` is on its socket whole.
    BodyEnd {
        req: u64,
    },
    /// The client of `req` is gone, or its body broke: `run` ends the request and says
    /// [`ToFront::End`]. Said of a request `run` ended already, it is nothing.
    Abort {
        req: u64,
    },
    /// The front answered a request of `service` itself (its secret, path, method or head),
    /// for the run's counts and log.
    Declined {
        service: Service,
        why: crate::model_proxy::Declined,
    },
    /// A connection to the sandbox's bridge said `hello`: `run` decides ([`ToFront::Admit`]).
    /// `req` grows with the front's other requests.
    Hello {
        req: u64,
        hello: WireHello,
    },
    /// The connection of `req` was admitted and its link took it (`attached`), or not.
    Admitted {
        req: u64,
        attached: bool,
    },
    /// The adapter ended.
    Exited {
        code: Option<i32>,
        signal: Option<i32>,
    },
    /// The bridge's session has ended: its link closed, or it had no connection for too long.
    SessionEnded {},
    /// The answer to `run`'s request `id`.
    Answer {
        id: u64,
        reply: Reply,
    },
}

impl ToMain {
    /// The bounds of a front's message beyond its frame's.
    pub fn check(&self) -> Result<(), String> {
        match self {
            ToMain::Ready { status } => {
                if status.problem.as_ref().is_some_and(|p| p.len() > MAX_TEXT) {
                    return Err(format!(
                        "its confinement's problem is over {MAX_TEXT} bytes"
                    ));
                }
                Ok(())
            }
            ToMain::Connect { host, port, .. } => {
                if host.is_empty() || host.len() > MAX_HOST {
                    return Err(format!("a destination of {} bytes", host.len()));
                }
                if !host.bytes().all(|b| b.is_ascii_graphic()) {
                    return Err("a destination with a byte that no host name has".into());
                }
                if *port == 0 {
                    return Err("a destination with the port 0".into());
                }
                Ok(())
            }
            ToMain::Request { head, .. } => head.check(),
            ToMain::Hello { hello, .. } => hello.check(),
            ToMain::Answer { reply, .. } => match reply {
                Reply::ExecDone { stdout, stderr, .. }
                    if stdout.len() > MAX_EXEC_OUTPUT_B64 || stderr.len() > MAX_EXEC_OUTPUT_B64 =>
                {
                    Err("a command's output over its bound".into())
                }
                Reply::Listening { addrs } if addrs.len() > tinfirm_bridge::tunnel::MAX_TUNNELS => {
                    Err("too many tunnels".into())
                }
                Reply::Probed { open }
                    if open.len() > MAX_PROBED || open.iter().any(|n| n.len() > MAX_PROBE_NAME) =>
                {
                    Err("a probe's answer out of bounds".into())
                }
                Reply::Failed { message } if message.len() > MAX_TEXT => {
                    Err(format!("a failure's message over {MAX_TEXT} bytes"))
                }
                _ => Ok(()),
            },
            ToMain::Pong { .. }
            | ToMain::Checked { .. }
            | ToMain::Closed { .. }
            | ToMain::BodyEnd { .. }
            | ToMain::Abort { .. }
            | ToMain::Declined { .. }
            | ToMain::Admitted { .. }
            | ToMain::Exited { .. }
            | ToMain::SessionEnded {} => Ok(()),
        }
    }

    /// The largest frame this message may take: [`MAX_TO_MAIN`], or [`MAX_EXEC_FRAME`] for a
    /// command's end, whose two outputs it holds.
    pub fn max_frame(&self) -> usize {
        match self {
            ToMain::Answer {
                reply: Reply::ExecDone { .. },
                ..
            } => MAX_EXEC_FRAME,
            _ => MAX_TO_MAIN,
        }
    }
}

/// What is wrong with a frame.
#[derive(Debug, thiserror::Error)]
pub enum WireError {
    #[error("a frame of {0} bytes is over the limit of {1}")]
    TooLong(usize, usize),
    #[error("an empty frame")]
    Empty,
    #[error("a frame is not a message of the wire: {0}")]
    NotAMessage(String),
    #[error("a message is out of bounds: {0}")]
    OutOfBounds(String),
}

/// The length of a frame from its header, within `max`.
pub fn frame_length(header: [u8; HEADER], max: usize) -> Result<usize, WireError> {
    let length = u32::from_be_bytes(header) as usize;
    if length == 0 {
        return Err(WireError::Empty);
    }
    if length > max {
        return Err(WireError::TooLong(length, max));
    }
    Ok(length)
}

/// A front's message from a frame's payload (of at most [`MAX_EXEC_FRAME`] bytes), checked:
/// only a command's end may be larger than [`MAX_TO_MAIN`].
pub fn decode_to_main(payload: &[u8]) -> Result<ToMain, WireError> {
    // serde_json names no value of the input in its errors' text, only where it stopped.
    let message: ToMain = serde_json::from_slice(payload).map_err(|e| {
        WireError::NotAMessage(format!("{:?} at column {}", e.classify(), e.column()))
    })?;
    if payload.len() > message.max_frame() {
        return Err(WireError::TooLong(payload.len(), message.max_frame()));
    }
    message.check().map_err(WireError::OutOfBounds)?;
    Ok(message)
}

/// `run`'s message from a frame's payload, checked.
pub fn decode_to_front(payload: &[u8]) -> Result<ToFront, WireError> {
    let message: ToFront = serde_json::from_slice(payload).map_err(|e| {
        WireError::NotAMessage(format!("{:?} at column {}", e.classify(), e.column()))
    })?;
    message.check().map_err(WireError::OutOfBounds)?;
    Ok(message)
}

/// `message` as a frame, within `max`.
pub fn encode(message: &impl Serialize, max: usize) -> Result<Vec<u8>, WireError> {
    let payload = serde_json::to_vec(message).map_err(|e| WireError::NotAMessage(e.to_string()))?;
    if payload.len() > max {
        return Err(WireError::TooLong(payload.len(), max));
    }
    let mut frame = Vec::with_capacity(HEADER + payload.len());
    frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    frame.extend_from_slice(&payload);
    Ok(frame)
}

/// Writes `frame` on the blocking socket `socket`, with `descriptors` on its first byte. A
/// write that takes part of the frame is followed by plain writes of the rest.
pub fn send_frame(
    socket: BorrowedFd<'_>,
    frame: &[u8],
    descriptors: &[OwnedFd],
) -> std::io::Result<()> {
    let written = send_with(socket, frame, descriptors)?;
    let mut rest = std::fs::File::from(socket.try_clone_to_owned()?);
    rest.write_all(&frame[written..])
}

/// The most descriptors one message carries.
pub const MAX_DESCRIPTORS: usize = 3;

/// One `sendmsg` of `frame`, with `descriptors` attached: how many bytes it took.
pub fn send_with(
    socket: BorrowedFd<'_>,
    frame: &[u8],
    descriptors: &[OwnedFd],
) -> std::io::Result<usize> {
    use rustix::net::{SendAncillaryBuffer, SendAncillaryMessage, SendFlags, sendmsg};
    if descriptors.len() > MAX_DESCRIPTORS {
        return Err(std::io::Error::other(
            "too many descriptors for one message",
        ));
    }
    let fds: Vec<BorrowedFd<'_>> = descriptors.iter().map(|fd| fd.as_fd()).collect();
    let mut space =
        [std::mem::MaybeUninit::uninit(); rustix::cmsg_space!(ScmRights(MAX_DESCRIPTORS))];
    let mut control = SendAncillaryBuffer::new(&mut space);
    if !fds.is_empty() && !control.push(SendAncillaryMessage::ScmRights(&fds)) {
        return Err(std::io::Error::other("no room for the descriptors"));
    }
    loop {
        match sendmsg(
            socket,
            &[IoSlice::new(frame)],
            &mut control,
            SendFlags::empty(),
        ) {
            Ok(written) => return Ok(written),
            Err(rustix::io::Errno::INTR) => continue,
            Err(e) => return Err(e.into()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_frame_is_bounded_by_its_receiver_and_a_message_takes_no_unknown_field() {
        let frame = encode(&ToFront::Ping { id: 7 }, MAX_TO_FRONT).unwrap();
        let length = frame_length(frame[..4].try_into().unwrap(), MAX_TO_FRONT).unwrap();
        assert_eq!(
            decode_to_front(&frame[4..4 + length]).unwrap(),
            ToFront::Ping { id: 7 }
        );
        assert!(matches!(
            frame_length([0, 1, 0, 1], MAX_TO_MAIN),
            Err(WireError::TooLong(65537, MAX_TO_MAIN))
        ));
        assert!(matches!(
            frame_length([0; 4], MAX_TO_MAIN),
            Err(WireError::Empty)
        ));
        for bad in [
            &br#"{"type":"pong","id":1,"x":2}"#[..],
            br#"{"type":"pong"}"#,
            br#"{"type":"pong","id":-1}"#,
            br#"{"type":"shell","id":1}"#,
            br#"{"type":"ready"}"#,
            b"\xff\xfe",
            b"[]",
        ] {
            assert!(
                decode_to_main(bad).is_err(),
                "{}",
                String::from_utf8_lossy(bad)
            );
        }
        let mut status = Status::none("x".repeat(MAX_TEXT + 1));
        let long = serde_json::to_vec(&ToMain::Ready {
            status: status.clone(),
        })
        .unwrap();
        assert!(matches!(
            decode_to_main(&long),
            Err(WireError::OutOfBounds(_))
        ));
        status.problem = Some("no Landlock".into());
        let fine = serde_json::to_vec(&ToMain::Ready { status }).unwrap();
        assert!(decode_to_main(&fine).is_ok());
        // A message too large for its receiver is not even encoded.
        let big = ToMain::Ready {
            status: Status::none("y".repeat(MAX_TO_MAIN)),
        };
        assert!(encode(&big, MAX_TO_MAIN).is_err());
    }

    #[test]
    fn a_connect_names_a_host_of_1_to_253_printable_bytes_and_a_port_other_than_0() {
        let connect = |host: &str, port: u16| {
            serde_json::to_vec(&ToMain::Connect {
                req: 1,
                host: host.into(),
                port,
            })
            .unwrap()
        };
        assert!(decode_to_main(&connect(&"a".repeat(MAX_HOST), 443)).is_ok());
        assert!(decode_to_main(&connect("[::1]", 1)).is_ok());
        for (host, port) in [
            ("", 443),
            (&*"a".repeat(MAX_HOST + 1), 443),
            ("example.com", 0),
            ("exa mple.com", 443),
            ("example.com\r\nx", 443),
            ("ex\u{e9}mple.com", 443),
            ("example.com\0", 443),
        ] {
            assert!(
                matches!(
                    decode_to_main(&connect(host, port)),
                    Err(WireError::OutOfBounds(_))
                ),
                "{host:?}:{port}"
            );
        }
    }

    #[test]
    fn only_a_403_says_that_a_destination_is_off_the_allowlist() {
        let refused = |status: u16| ToFront::Refused {
            req: 1,
            status,
            message: "no".into(),
            off_allowlist: true,
        };
        assert!(refused(403).check().is_ok());
        assert!(refused(400).check().is_err());
        assert!(refused(502).check().is_err());
    }

    #[test]
    fn a_model_request_is_a_head_that_http_allows_within_its_bounds() {
        use crate::model_proxy::{BodyLength, ModelEndpoint, RequestHead, ResponseHead, Target};
        let head = RequestHead {
            target: Target::Model(ModelEndpoint::AnthropicMessages),
            method: "POST".into(),
            query: Some("beta=true".into()),
            headers: vec![("anthropic-version".into(), "2023-06-01".into())],
            length: BodyLength::Exact(2),
        };
        let request = |head: &RequestHead| {
            serde_json::to_vec(&ToMain::Request {
                req: 1,
                head: head.clone(),
            })
            .unwrap()
        };
        assert!(decode_to_main(&request(&head)).is_ok());
        let mut bad = Vec::new();
        for value in ["a\r\nhost: elsewhere", "a\nb", "a\0b"] {
            let mut head = head.clone();
            head.headers.push(("x-a".into(), value.into()));
            bad.push(head);
        }
        let mut many = head.clone();
        many.headers = (0..65).map(|i| (format!("x-h{i}"), "v".into())).collect();
        bad.push(many);
        let mut long = head.clone();
        long.headers = vec![("x-a".into(), "v".repeat(8 * 1024 + 1).into())];
        bad.push(long);
        let mut name = head.clone();
        name.headers = vec![("x a".into(), "v".into())];
        bad.push(name);
        for query in ["a b", &"q".repeat(4097)] {
            let mut head = head.clone();
            head.query = Some(query.into());
            bad.push(head);
        }
        let mut method = head.clone();
        method.method = "PO ST".into();
        bad.push(method);
        for head in bad {
            assert!(
                matches!(
                    decode_to_main(&request(&head)),
                    Err(WireError::OutOfBounds(_))
                ),
                "{head:?}"
            );
        }
        let response = |status: u16, headers: usize| ToFront::Response {
            req: 1,
            head: ResponseHead {
                status,
                headers: (0..headers)
                    .map(|i| (format!("x-h{i}"), "v".into()))
                    .collect(),
            },
        };
        assert!(response(200, 100).check().is_ok());
        assert!(response(99, 0).check().is_err());
        assert!(response(600, 0).check().is_err());
        assert!(response(200, 101).check().is_err());
    }

    #[test]
    fn the_bridges_messages_keep_their_bounds_and_only_a_commands_end_is_larger() {
        use tinfirm_bridge::control::Hidden;
        let hello = |run_id: &str, ticket: &str, build: Option<&str>| ToMain::Hello {
            req: 1,
            hello: WireHello {
                run_id: run_id.into(),
                ticket: Some(Hidden::new(ticket)),
                session: None,
                rx_seq: 0,
                tx_seq: Some(0),
                build: build.map(str::to_string),
            },
        };
        let decode = |message: &ToMain| decode_to_main(&serde_json::to_vec(message).unwrap());
        assert!(decode(&hello("run-1", &"t".repeat(64), Some("1.0.0"))).is_ok());
        for bad in [
            hello(&"r".repeat(MAX_HELLO_NAME + 1), "t", None),
            hello("run 1", "t", None),
            hello("run-1", &"t".repeat(MAX_HELLO_NAME + 1), None),
            hello("run-1", "t\n", None),
            hello("run-1", "t", Some(&"b".repeat(MAX_HELLO_BUILD + 1))),
        ] {
            assert!(
                matches!(decode(&bad), Err(WireError::OutOfBounds(_))),
                "{bad:?}"
            );
        }
        let exec = |stdout: usize| ToMain::Answer {
            id: 1,
            reply: Reply::ExecDone {
                code: Some(0),
                signal: None,
                stdout: "A".repeat(stdout),
                stderr: "A".repeat(MAX_EXEC_OUTPUT_B64),
            },
        };
        // Both outputs at their bound fit the larger frame, which no other message gets.
        let full = exec(MAX_EXEC_OUTPUT_B64);
        let frame = encode(&full, full.max_frame()).unwrap();
        assert!(frame.len() > MAX_TO_MAIN && frame.len() <= MAX_EXEC_FRAME);
        assert!(decode(&full).is_ok());
        assert!(matches!(
            decode(&exec(MAX_EXEC_OUTPUT_B64 + 1)),
            Err(WireError::OutOfBounds(_))
        ));
        let mut padded = serde_json::to_vec(&hello("run-1", "t", None)).unwrap();
        padded.pop();
        padded.extend(vec![b' '; MAX_TO_MAIN]);
        padded.push(b'}');
        assert!(matches!(
            decode_to_main(&padded),
            Err(WireError::TooLong(_, MAX_TO_MAIN))
        ));
        for reply in [
            Reply::Probed {
                open: vec!["x".into(); MAX_PROBED + 1],
            },
            Reply::Probed {
                open: vec!["x".repeat(MAX_PROBE_NAME + 1)],
            },
            Reply::Failed {
                message: "m".repeat(MAX_TEXT + 1),
            },
        ] {
            assert!(matches!(
                decode(&ToMain::Answer { id: 1, reply }),
                Err(WireError::OutOfBounds(_))
            ));
        }
        // `run`'s side: an identity, a timing and a refusal within their bounds.
        let timing = WireTiming::of(&tinfirm_bridge::host::HostTiming::default());
        let init = |cert: usize, timing: WireTiming| ToFront::BridgeInit {
            identity: WireIdentity {
                cert: "c".repeat(cert),
                key: "k".into(),
            },
            timing,
            listen: true,
        };
        assert!(init(100, timing).check().is_ok());
        assert_eq!(init(100, timing).descriptors(), 1);
        assert!(init(MAX_IDENTITY_PART + 1, timing).check().is_err());
        assert!(
            init(
                100,
                WireTiming {
                    handshake_ms: 0,
                    ..timing
                }
            )
            .check()
            .is_err()
        );
        let refuse = |message: usize| ToFront::Admit {
            req: 1,
            admission: Admission::Refuse {
                code: tinfirm_bridge::control::RefuseCode::BadTicket,
                message: "m".repeat(message),
            },
        };
        assert!(refuse(MAX_TEXT).check().is_ok());
        assert!(refuse(MAX_TEXT + 1).check().is_err());
        assert_eq!(
            timing.host_timing().handshake,
            std::time::Duration::from_secs(10)
        );
    }

    #[test]
    fn the_files_and_tunnels_messages_keep_their_bounds() {
        let get = |dir: usize, paths: usize| ToFront::Get {
            id: 1,
            dir: "d".repeat(dir),
            paths: vec!["p".into(); paths],
            exclude: Vec::new(),
            timeout_ms: 1,
        };
        assert!(get(10, 10).check().is_ok());
        assert_eq!(get(10, 10).descriptors(), 1);
        assert!(get(MAX_FILES_PATH + 1, 0).check().is_err());
        assert!(get(1, MAX_FILES_PATHS + 1).check().is_err());
        let put = |dir: usize| ToFront::Put {
            id: 1,
            dir: "d".repeat(dir),
            timeout_ms: 1,
        };
        assert_eq!(put(1).descriptors(), 1);
        assert!(put(MAX_FILES_PATH + 1).check().is_err());
        let tunnel = WireTunnel {
            listen: "127.0.0.1:0".parse().unwrap(),
            service: Service::Egress,
        };
        let tunnels = |n: usize| ToFront::OpenTunnels {
            id: 1,
            tunnels: vec![tunnel; n],
            timeout_ms: 1,
        };
        assert!(tunnels(tinfirm_bridge::tunnel::MAX_TUNNELS).check().is_ok());
        assert!(
            tunnels(tinfirm_bridge::tunnel::MAX_TUNNELS + 1)
                .check()
                .is_err()
        );
        let listening = |n: usize| ToMain::Answer {
            id: 1,
            reply: Reply::Listening {
                addrs: vec![tunnel.listen; n],
            },
        };
        let decode = |message: &ToMain| decode_to_main(&serde_json::to_vec(message).unwrap());
        assert!(decode(&listening(2)).is_ok());
        assert!(matches!(
            decode(&listening(tinfirm_bridge::tunnel::MAX_TUNNELS + 1)),
            Err(WireError::OutOfBounds(_))
        ));
    }

    #[test]
    fn an_error_names_no_byte_of_the_input() {
        let secret = "ghs_TEST_ONLY_secret_input_0123";
        let frame = format!(r#"{{"type":"pong","id":"{secret}"}}"#);
        let error = decode_to_main(frame.as_bytes()).unwrap_err().to_string();
        assert!(!error.contains(secret), "{error}");
    }
}
