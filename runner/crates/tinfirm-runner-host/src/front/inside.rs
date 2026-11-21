//! The probe of a front for the test of path C (ADR-0112 decision 8): `front --probe JOB` with
//! [`super::ProbeJob::inside`], which `tests/path_c.rs` starts as `run` starts a front, with
//! [`super::handle::FrontHandle::spawn`]: the control channel on its stdin, nothing on stdout,
//! a pipe to `run` on stderr, an empty environment and a process group of its own.
//!
//! It confines itself as a front does ([`super::child_main`]), says that it is ready, and takes
//! what `run` sends it with every descriptor, as a front does, until a `ping`. Then, from
//! inside that confinement and holding those descriptors, it tries what code that took the
//! front over would try ([`Attempts`], and the probe's own attempts), and answers one line of
//! JSON on its stderr ([`Inside`]) and exits: the front's handle keeps the start of its stderr
//! for its end. It reads no file to find what it tries: its job names it all.

use std::collections::BTreeMap;
use std::io::{IoSliceMut, Write};
use std::net::SocketAddr;
use std::os::fd::{AsFd, AsRawFd, OwnedFd};
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use super::wire::{self, ToFront, ToMain};
use super::{Probe, ProbeJob};
use crate::confine::Status;

/// What the probe tries besides the probe's own attempts and its job's `deny_read` and
/// `signal`, which it tries with the errors it gets.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Attempts {
    /// Paths it must not open for writing, nor create.
    #[serde(default)]
    pub deny_write: Vec<PathBuf>,
    /// TCP addresses it must not connect to: with a socket of its own, and with each socket
    /// that it holds.
    #[serde(default)]
    pub connect: Vec<SocketAddr>,
    /// Abstract Unix sockets it must not connect to or bind: with a socket of its own, and
    /// with each socket that it holds.
    #[serde(default)]
    pub abstract_names: Vec<String>,
}

/// What the probe found.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Inside {
    /// The probe's own attempts (a socket, a program, its own group, its runtime), without
    /// paths or processes: those are counted here.
    pub probe: Probe,
    /// How many attempts it made here.
    pub tried: usize,
    /// Each attempt that got through, which must be none.
    pub got_through: Vec<String>,
    /// The others, by kind of attempt and the error each got.
    pub refused: BTreeMap<String, BTreeMap<String, usize>>,
    /// The descriptors it held while it tried: its own three and each that `run` sent.
    pub held: Vec<Held>,
    /// The kinds of `run`'s messages it took before the `ping`.
    pub taken: Vec<String>,
}

/// A descriptor the probe held.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Held {
    pub fd: i32,
    /// `file`, `dir`, `fifo`, `char`, `unix stream`, `inet stream`, ...
    pub kind: String,
    /// A Unix socket's peer.
    pub peer: Option<u32>,
    /// An internet socket's own address, and its peer's.
    pub addr: Option<String>,
    pub peer_addr: Option<String>,
    pub listening: bool,
}

/// The front's side: confined already, its stdin the control channel.
pub(super) fn child_main(status: Status, job: &ProbeJob, attempts: &Attempts) -> ExitCode {
    let Ok(channel) = std::io::stdin().as_fd().try_clone_to_owned() else {
        return super::fail("stdin must be the control channel");
    };
    let ready = ToMain::Ready {
        status: status.clone(),
    };
    let sent = wire::encode(&ready, wire::MAX_TO_MAIN)
        .map_err(|e| e.to_string())
        .and_then(|frame| {
            wire::send_frame(channel.as_fd(), &frame, &[]).map_err(|e| e.to_string())
        });
    if let Err(e) = sent {
        return super::fail(&format!("cannot say that it is ready: {e}"));
    }
    let mut received = Vec::new();
    let mut taken = Vec::new();
    if let Err(e) = take_until_ping(&channel, &mut received, &mut taken) {
        return super::fail(&e);
    }
    let mut tally = Tally::default();
    tally.files(job, attempts);
    for &pid in &job.signal {
        tally.process(pid);
    }
    tally.sockets(attempts);
    for fd in &received {
        tally.held_socket(fd, attempts);
    }
    let stdio = [
        std::io::stdin().as_fd().try_clone_to_owned(),
        std::io::stdout().as_fd().try_clone_to_owned(),
        std::io::stderr().as_fd().try_clone_to_owned(),
    ];
    let mut held: Vec<Held> = stdio
        .iter()
        .zip(0..)
        .filter_map(|(fd, number)| fd.as_ref().ok().map(|fd| describe(fd, number)))
        .collect();
    held.extend(received.iter().map(|fd| describe(fd, fd.as_raw_fd())));
    // Last: where it gets through, this process is `true` from here on and answers nothing.
    let probe = super::run_probe(status, &ProbeJob::default());
    let answer = Inside {
        probe,
        tried: tally.tried,
        got_through: tally.got_through,
        refused: tally.refused,
        held,
        taken,
    };
    let mut err = std::io::stderr().lock();
    let written = serde_json::to_writer(&mut err, &answer)
        .map_err(std::io::Error::from)
        .and_then(|()| err.write_all(b"\n"))
        .and_then(|()| err.flush());
    drop(received);
    if written.is_ok() {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(2)
    }
}

/// Takes `run`'s messages, and the descriptors that come with them, until a `ping` or the
/// channel's end.
fn take_until_ping(
    channel: &OwnedFd,
    received: &mut Vec<OwnedFd>,
    taken: &mut Vec<String>,
) -> Result<(), String> {
    use rustix::net::{RecvAncillaryBuffer, RecvAncillaryMessage, RecvFlags, recvmsg};
    let mut bytes = Vec::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        while bytes.len() >= wire::HEADER {
            let header: [u8; wire::HEADER] = bytes[..wire::HEADER].try_into().expect("checked");
            let length =
                wire::frame_length(header, wire::MAX_TO_FRONT).map_err(|e| e.to_string())?;
            if bytes.len() < wire::HEADER + length {
                break;
            }
            let payload: Vec<u8> = bytes
                .drain(..wire::HEADER + length)
                .skip(wire::HEADER)
                .collect();
            let message = wire::decode_to_front(&payload).map_err(|e| e.to_string())?;
            if let ToFront::Ping { .. } = message {
                return Ok(());
            }
            let kind = serde_json::to_value(&message)
                .ok()
                .and_then(|v| v["type"].as_str().map(String::from))
                .unwrap_or_default();
            taken.push(kind);
        }
        let mut space = [std::mem::MaybeUninit::uninit(); rustix::cmsg_space!(ScmRights(8))];
        let mut control = RecvAncillaryBuffer::new(&mut space);
        let got = loop {
            match recvmsg(
                channel,
                &mut [IoSliceMut::new(&mut buffer)],
                &mut control,
                RecvFlags::CMSG_CLOEXEC,
            ) {
                Ok(got) => break got,
                Err(rustix::io::Errno::INTR) => continue,
                Err(e) => return Err(format!("cannot read the control channel: {e}")),
            }
        };
        for message in control.drain() {
            if let RecvAncillaryMessage::ScmRights(fds) = message {
                received.extend(fds);
            }
        }
        if got.bytes == 0 {
            return Ok(());
        }
        bytes.extend_from_slice(&buffer[..got.bytes]);
    }
}

/// The attempts, as they went.
#[derive(Default)]
struct Tally {
    tried: usize,
    got_through: Vec<String>,
    refused: BTreeMap<String, BTreeMap<String, usize>>,
}

impl Tally {
    fn note<T>(&mut self, kind: &str, what: impl std::fmt::Display, result: std::io::Result<T>) {
        self.tried += 1;
        match result {
            Ok(_) => self.got_through.push(format!("{kind}: {what}")),
            Err(e) => {
                *self
                    .refused
                    .entry(kind.to_string())
                    .or_default()
                    .entry(error_name(&e))
                    .or_default() += 1;
            }
        }
    }

    fn files(&mut self, job: &ProbeJob, attempts: &Attempts) {
        for path in &job.deny_read {
            self.note("read", path.display(), std::fs::File::open(path));
        }
        for path in &attempts.deny_write {
            // Not truncated: where it gets through, a file of the host's keeps what it holds.
            let opened = std::fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(false)
                .open(path);
            self.note("write", path.display(), opened);
        }
    }

    /// A process outside the front's group: signals, its group, its limits, a pidfd.
    fn process(&mut self, pid: u32) {
        use rustix::process::{
            Pid, PidfdFlags, PidfdGetfdFlags, Resource, Signal, getrlimit, kill_process,
            kill_process_group, pidfd_getfd, pidfd_open, pidfd_send_signal, prlimit,
            test_kill_process,
        };
        let Some(target) = i32::try_from(pid).ok().and_then(Pid::from_raw) else {
            return;
        };
        self.note("signal", pid, test_kill_process(target).map_err(Into::into));
        self.note(
            "signal",
            pid,
            kill_process(target, Signal::CONT).map_err(Into::into),
        );
        self.note(
            "signal",
            format!("group {pid}"),
            kill_process_group(target, Signal::CONT).map_err(Into::into),
        );
        // Its core size set to the front's own (0): harmless where it gets through.
        self.note(
            "prlimit64",
            pid,
            prlimit(Some(target), Resource::Core, getrlimit(Resource::Core)).map_err(Into::into),
        );
        match pidfd_open(target, PidfdFlags::empty()) {
            Ok(pidfd) => {
                self.note("pidfd_open", pid, Ok(()));
                for fd in 0..8 {
                    self.note(
                        "pidfd_getfd",
                        format!("{pid} {fd}"),
                        pidfd_getfd(&pidfd, fd, PidfdGetfdFlags::empty()).map_err(Into::into),
                    );
                }
                self.note(
                    "signal",
                    format!("pidfd {pid}"),
                    pidfd_send_signal(&pidfd, Signal::CONT).map_err(Into::into),
                );
            }
            Err(e) => self.note("pidfd_open", pid, Err::<(), _>(e.into())),
        }
    }

    /// Sockets of its own: every family, and the connections and names of `attempts`.
    fn sockets(&mut self, attempts: &Attempts) {
        use rustix::net::{AddressFamily, SocketType, socket};
        for (family, kind, name) in [
            (AddressFamily::INET, SocketType::STREAM, "inet stream"),
            (AddressFamily::INET, SocketType::DGRAM, "inet dgram"),
            (AddressFamily::INET6, SocketType::STREAM, "inet6 stream"),
            (AddressFamily::UNIX, SocketType::STREAM, "unix stream"),
            (AddressFamily::UNIX, SocketType::DGRAM, "unix dgram"),
            (AddressFamily::NETLINK, SocketType::RAW, "netlink"),
            (AddressFamily::PACKET, SocketType::RAW, "packet"),
        ] {
            self.note(
                "socket",
                name,
                socket(family, kind, None).map_err(Into::into),
            );
        }
        for addr in &attempts.connect {
            self.note(
                "connect",
                addr,
                std::net::TcpStream::connect_timeout(addr, Duration::from_secs(1)),
            );
        }
        for name in &attempts.abstract_names {
            use std::os::linux::net::SocketAddrExt;
            let Ok(addr) = std::os::unix::net::SocketAddr::from_abstract_name(name.as_bytes())
            else {
                continue;
            };
            self.note(
                "connect abstract",
                name,
                std::os::unix::net::UnixStream::connect_addr(&addr),
            );
            self.note(
                "bind abstract",
                name,
                std::os::unix::net::UnixListener::bind_addr(&addr),
            );
        }
    }

    /// A descriptor that `run` sent: where it is a socket, it must connect or bind nowhere.
    fn held_socket(&mut self, fd: &OwnedFd, attempts: &Attempts) {
        use rustix::net::{SocketAddrUnix, bind, connect};
        if rustix::net::sockopt::socket_type(fd).is_err() {
            return;
        }
        let number = fd.as_raw_fd();
        for addr in &attempts.connect {
            self.note(
                "connect held",
                format!("{number} {addr}"),
                connect(fd, addr).map_err(Into::into),
            );
        }
        for name in &attempts.abstract_names {
            let Ok(addr) = SocketAddrUnix::new_abstract_name(name.as_bytes()) else {
                continue;
            };
            self.note(
                "connect held abstract",
                format!("{number} {name}"),
                connect(fd, &addr).map_err(Into::into),
            );
            self.note(
                "bind held abstract",
                format!("{number} {name}"),
                bind(fd, &addr).map_err(Into::into),
            );
        }
    }
}

/// The name of an error's code, for the answer: `EPERM` is the seccomp filter's refusal,
/// `EACCES` Landlock's.
fn error_name(error: &std::io::Error) -> String {
    let Some(code) = error.raw_os_error() else {
        return format!("{:?}", error.kind());
    };
    let name = match code {
        libc::EPERM => "EPERM",
        libc::EACCES => "EACCES",
        libc::ENOENT => "ENOENT",
        libc::ESRCH => "ESRCH",
        libc::EBADF => "EBADF",
        libc::EINVAL => "EINVAL",
        libc::ENOSYS => "ENOSYS",
        libc::ENOTSOCK => "ENOTSOCK",
        libc::EAFNOSUPPORT => "EAFNOSUPPORT",
        libc::EOPNOTSUPP => "EOPNOTSUPP",
        libc::EISCONN => "EISCONN",
        libc::ECONNREFUSED => "ECONNREFUSED",
        libc::EADDRINUSE => "EADDRINUSE",
        libc::EROFS => "EROFS",
        other => return format!("errno {other}"),
    };
    name.to_string()
}

/// What a descriptor is.
fn describe(fd: &OwnedFd, number: i32) -> Held {
    use std::os::unix::fs::FileTypeExt;
    let mut held = Held {
        fd: number,
        kind: "unknown".into(),
        peer: None,
        addr: None,
        peer_addr: None,
        listening: false,
    };
    let Ok(copy) = fd.try_clone() else {
        return held;
    };
    let Ok(metadata) = std::fs::File::from(copy).metadata() else {
        return held;
    };
    let kind = metadata.file_type();
    held.kind = if kind.is_file() {
        "file"
    } else if kind.is_dir() {
        "dir"
    } else if kind.is_fifo() {
        "fifo"
    } else if kind.is_char_device() {
        "char"
    } else if kind.is_block_device() {
        "block"
    } else if kind.is_socket() {
        "socket"
    } else {
        "other"
    }
    .into();
    if !kind.is_socket() {
        return held;
    }
    use rustix::net::{AddressFamily, SocketType, sockopt};
    let family = match sockopt::socket_domain(fd) {
        Ok(AddressFamily::UNIX) => "unix",
        Ok(AddressFamily::INET) => "inet",
        Ok(AddressFamily::INET6) => "inet6",
        Ok(_) => "other",
        Err(_) => "unknown",
    };
    let socket_type = match sockopt::socket_type(fd) {
        Ok(SocketType::STREAM) => "stream",
        Ok(SocketType::DGRAM) => "dgram",
        Ok(SocketType::SEQPACKET) => "seqpacket",
        Ok(_) => "other",
        Err(_) => "unknown",
    };
    held.kind = format!("{family} {socket_type}");
    held.listening = sockopt::socket_acceptconn(fd).unwrap_or(false);
    if family == "unix" {
        held.peer = sockopt::socket_peercred(fd)
            .ok()
            .map(|cred| cred.pid.as_raw_nonzero().get().unsigned_abs());
    } else if let Ok(copy) = fd.try_clone() {
        let socket = std::net::TcpStream::from(copy);
        held.addr = socket.local_addr().ok().map(|a| a.to_string());
        held.peer_addr = socket.peer_addr().ok().map(|a| a.to_string());
    }
    held
}
