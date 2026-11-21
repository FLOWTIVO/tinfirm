//! The bridge's control messages (ADR-0095), JSON in `CONTROL` frames on stream 0.

use serde::{Deserialize, Serialize};

/// A value that never shows in `Debug` (tickets, session keys, the run's environment).
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Hidden(String);

impl Hidden {
    pub fn new(value: impl Into<String>) -> Hidden {
        Hidden(value.into())
    }

    pub fn expose(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for Hidden {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("[hidden]")
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Control {
    /// Sandbox to host, the first frame of every connection: the ticket on the first one,
    /// the session key on a reconnect.
    Hello {
        version: u32,
        run_id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        ticket: Option<Hidden>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        session: Option<Hidden>,
        /// The highest frame number the sandbox has received in order.
        rx_seq: u64,
        /// The highest frame number the sandbox has sent: a runner host that restarted starts
        /// its own link of the session there (ADR-0101). A bridge from before it says none.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        tx_seq: Option<u64>,
        /// What built the binary the bridge is ([`crate::sandbox::Config::build`]): in a VM, the
        /// VM image's copy of the runner host, which the host checks against its own
        /// (ADR-0101). A bridge from before it says none.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        build: Option<String>,
    },
    /// Host to sandbox: accepted. The session key on the first connection only.
    Welcome {
        version: u32,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        session: Option<Hidden>,
        /// The highest frame number the host has received in order.
        rx_seq: u64,
    },
    /// Host to sandbox: refused; the host closes the connection.
    Refused { code: RefuseCode, message: String },
    /// Host to sandbox, once: start the adapter.
    Start(Start),
    /// Host to sandbox: a signal for the adapter's process group.
    Signal { signal: Signal },
    /// Host to sandbox: SIGTERM to the adapter's process group, SIGKILL after the grace.
    Stop { grace_seconds: u64 },
    /// Sandbox to host: the adapter runs.
    Started { pid: u32 },
    /// Sandbox to host: the adapter has exited; its stdout and stderr have ended before.
    Exited {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        code: Option<i32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        signal: Option<i32>,
    },
    /// Sandbox to host: something the host should log.
    Error { message: String },
    /// Host to sandbox, before `start`: listen on these addresses in the sandbox, and carry
    /// each connection to the host's listener of its tunnel ([`crate::tunnel`]).
    Tunnels { id: u64, tunnels: Vec<Tunnel> },
    /// Sandbox to host: listening on every tunnel's address; or `failed` when it cannot.
    Listening { id: u64, tunnels: Vec<Tunnel> },
    /// Sandbox to host: a connection to tunnel `tunnel` came in; its bytes are `stream`'s.
    Open { stream: u32, tunnel: u32 },
    /// Either way: the connection of `stream` failed, or is refused; the stream is gone.
    Reset { stream: u32 },
    /// Host to sandbox: `stream` carries a tar archive to unpack into the directory `dir`,
    /// which is created ([`crate::files`]). Answered with `done` once it is unpacked.
    Put { id: u64, stream: u32, dir: String },
    /// Host to sandbox: send `paths` of the directory `dir` (all of it when empty), without
    /// `exclude`, as a tar archive on `stream`. Answered with `done` once it is sent.
    Get {
        id: u64,
        stream: u32,
        dir: String,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        paths: Vec<String>,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        exclude: Vec<String>,
    },
    /// Host to sandbox: run a command there (the host's git steps), answered with `exec_done`.
    Exec(Exec),
    /// Sandbox to host: the command of `exec` ended.
    ExecDone {
        id: u64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        code: Option<i32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        signal: Option<i32>,
        stdout: String,
        stderr: String,
    },
    /// Sandbox to host: the request `id` is done.
    Done { id: u64 },
    /// Sandbox to host: the request `id` failed.
    Failed { id: u64, message: String },
    /// Host to sandbox, after the adapter has ended: the host has what it needs. The bridge
    /// answers with `done` and ends, and with it the sandbox.
    Close { id: u64 },
    /// Host to sandbox, before `start`: try each check from inside the sandbox, which must
    /// reach nothing ([`crate::probe`]). Answered with `probed`.
    Probe { id: u64, checks: Vec<ProbeCheck> },
    /// Sandbox to host: the checks of `probe` that got through, by their names.
    Probed { id: u64, open: Vec<String> },
    /// Host to sandbox, from a runner host that restarted and took the session back
    /// (ADR-0101): the agent its predecessor started stops, and the host takes the sandbox's
    /// files. Answered with `reclaimed`; when the adapter still ran, `exited` follows once it
    /// has ended.
    Reclaim { id: u64 },
    /// Sandbox to host: whether the adapter still ran when `reclaim` came.
    Reclaimed { id: u64, running: bool },
}

/// One thing a sandbox must not reach ([`crate::probe`]).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ProbeCheck {
    /// A TCP connection to `addr` (`HOST:PORT`).
    Tcp { addr: String },
    /// The system's resolver answers for `name`.
    Dns { name: String },
    /// `GET path` at `addr` answers 200, with `headers` (a metadata service's credential).
    Http {
        addr: String,
        path: String,
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        headers: Vec<(String, String)>,
    },
}

impl ProbeCheck {
    /// How the check is named in `probed` and in the host's messages.
    pub fn name(&self) -> String {
        match self {
            ProbeCheck::Tcp { addr } => format!("a connection to {addr}"),
            ProbeCheck::Dns { name } => format!("a DNS answer for {name}"),
            ProbeCheck::Http { addr, path, .. } => format!("http://{addr}{path}"),
        }
    }
}

/// A command of the host for the sandbox, which is not the adapter: its argv, its
/// environment (added to the bridge's own, without its `TINFIRM_BRIDGE_*`), its working
/// directory and its input. Its output comes back cut at [`Exec::MAX_OUTPUT`] bytes each.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Exec {
    pub id: u64,
    pub argv: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub env: Vec<(String, Hidden)>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stdin: Option<String>,
    pub timeout_seconds: u64,
}

impl Exec {
    /// The most bytes of stdout and of stderr that come back.
    pub const MAX_OUTPUT: usize = 64 * 1024;
}

/// A tunnel: its number, and the address it listens on in the sandbox.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Tunnel {
    pub id: u32,
    pub addr: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RefuseCode {
    /// No run of this id waits for a bridge, or the ticket is not its ticket.
    BadTicket,
    /// The run's ticket was used: a reconnect must present the session key.
    TicketUsed,
    /// The host knows no such session, for example because it restarted.
    UnknownSession,
    UnsupportedVersion,
    BadRequest,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Signal {
    Int,
    Term,
    Kill,
}

impl Signal {
    /// The name for `kill -s`.
    pub fn name(self) -> &'static str {
        match self {
            Signal::Int => "INT",
            Signal::Term => "TERM",
            Signal::Kill => "KILL",
        }
    }
}

/// The adapter to start: its argv, its environment and its working directory.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Start {
    /// Empty: the sandbox's own command ([`crate::ENV_COMMAND`]).
    pub argv: Vec<String>,
    /// Added to the bridge's own environment (without its `TINFIRM_BRIDGE_*`), and wins over
    /// it. The run's credentials are here.
    pub env: Vec<(String, Hidden)>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
}

impl Start {
    /// Refuses what a process cannot be started with.
    pub fn check(&self) -> Result<(), String> {
        if self.argv.first().is_some_and(String::is_empty)
            || self.argv.iter().any(|a| a.contains('\0'))
        {
            return Err("start has an empty program or an argument with a NUL byte".into());
        }
        for (key, value) in &self.env {
            if key.is_empty() || key.contains('=') || key.contains('\0') {
                return Err(format!(
                    "start has an invalid variable name `{}`",
                    key.escape_debug()
                ));
            }
            if key.starts_with(crate::ENV_PREFIX) {
                return Err(format!("start may not set {key}"));
            }
            if value.expose().contains('\0') {
                return Err(format!("the value of {key} has a NUL byte"));
            }
        }
        if self
            .cwd
            .as_deref()
            .is_some_and(|c| c.is_empty() || c.contains('\0'))
        {
            return Err("start has an invalid working directory".into());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_round_trip_as_tagged_json() {
        let start = Control::Start(Start {
            argv: vec!["claude-agent-acp".into()],
            env: vec![("ANTHROPIC_API_KEY".into(), Hidden::new("sk-ant-1"))],
            cwd: Some("/workspace".into()),
        });
        let json = serde_json::to_string(&start).unwrap();
        assert!(json.contains(r#""type":"start""#), "{json}");
        assert!(
            json.contains(r#"["ANTHROPIC_API_KEY","sk-ant-1"]"#),
            "{json}"
        );
        assert_eq!(serde_json::from_str::<Control>(&json).unwrap(), start);

        let hello = Control::Hello {
            version: 1,
            run_id: "r".into(),
            ticket: Some(Hidden::new("t")),
            session: None,
            rx_seq: 0,
            tx_seq: Some(4),
            build: Some("0.3.0".into()),
        };
        let json = serde_json::to_string(&hello).unwrap();
        assert!(!json.contains("session"), "{json}");
        assert_eq!(serde_json::from_str::<Control>(&json).unwrap(), hello);

        let open = Control::Open {
            stream: 16,
            tunnel: 2,
        };
        let json = serde_json::to_string(&open).unwrap();
        assert_eq!(json, r#"{"type":"open","stream":16,"tunnel":2}"#);
        let tunnels = Control::Tunnels {
            id: 1,
            tunnels: vec![Tunnel {
                id: 1,
                addr: "127.0.0.1:8080".into(),
            }],
        };
        let json = serde_json::to_string(&tunnels).unwrap();
        assert_eq!(serde_json::from_str::<Control>(&json).unwrap(), tunnels);

        let get = Control::Get {
            id: 3,
            stream: 0x8000_0000,
            dir: "/workspace".into(),
            paths: vec!["a.txt".into()],
            exclude: Vec::new(),
        };
        let json = serde_json::to_string(&get).unwrap();
        assert!(!json.contains("exclude"), "{json}");
        assert_eq!(serde_json::from_str::<Control>(&json).unwrap(), get);
        let exec = Control::Exec(Exec {
            id: 4,
            argv: vec!["git".into(), "status".into()],
            env: Vec::new(),
            cwd: Some("/workspace/a".into()),
            stdin: None,
            timeout_seconds: 60,
        });
        let json = serde_json::to_string(&exec).unwrap();
        assert!(json.contains(r#""type":"exec""#), "{json}");
        assert_eq!(serde_json::from_str::<Control>(&json).unwrap(), exec);
        assert_eq!(
            serde_json::to_string(&Control::Close { id: 7 }).unwrap(),
            r#"{"type":"close","id":7}"#
        );

        let refused: Control =
            serde_json::from_str(r#"{"type":"refused","code":"TICKET_USED","message":"m"}"#)
                .unwrap();
        assert_eq!(
            refused,
            Control::Refused {
                code: RefuseCode::TicketUsed,
                message: "m".into()
            }
        );
    }

    #[test]
    fn debug_hides_tickets_sessions_and_the_environment() {
        let start = Control::Start(Start {
            argv: vec!["a".into()],
            env: vec![("KEY".into(), Hidden::new("sk-ant-secret"))],
            cwd: None,
        });
        let hello = Control::Hello {
            version: 1,
            run_id: "r".into(),
            ticket: Some(Hidden::new("ticket-secret")),
            session: Some(Hidden::new("session-secret")),
            rx_seq: 0,
            tx_seq: None,
            build: None,
        };
        let text = format!("{start:?} {hello:?}");
        assert!(!text.contains("secret"), "{text}");
        assert!(text.contains("KEY"), "{text}");
    }

    #[test]
    fn start_refuses_what_cannot_be_started() {
        let ok = Start {
            argv: vec!["sh".into()],
            env: vec![("A".into(), Hidden::new("1"))],
            cwd: Some("/workspace".into()),
        };
        assert!(ok.check().is_ok());
        // No program: the sandbox's own command.
        assert!(
            Start {
                argv: vec![],
                ..ok.clone()
            }
            .check()
            .is_ok()
        );
        let cases = [
            Start {
                argv: vec!["".into()],
                ..ok.clone()
            },
            Start {
                argv: vec!["a\0b".into()],
                ..ok.clone()
            },
            Start {
                env: vec![("A=B".into(), Hidden::new("1"))],
                ..ok.clone()
            },
            Start {
                env: vec![("".into(), Hidden::new("1"))],
                ..ok.clone()
            },
            Start {
                env: vec![("TINFIRM_BRIDGE_TICKET".into(), Hidden::new("1"))],
                ..ok.clone()
            },
            Start {
                env: vec![("A".into(), Hidden::new("1\0"))],
                ..ok.clone()
            },
            Start {
                cwd: Some(String::new()),
                ..ok.clone()
            },
        ];
        for case in cases {
            assert!(case.check().is_err(), "{case:?}");
        }
    }
}
