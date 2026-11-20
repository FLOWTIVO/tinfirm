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
//! front asks `run` for ([`ToMain::Connect`]) and `run` connects or refuses. The next slices
//! add the model's and git's proxies, the bridge's and the files' messages.

use std::io::{IoSlice, Write};
use std::os::fd::{AsFd, BorrowedFd, OwnedFd};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::confine::Status;

/// The length prefix of a frame.
pub const HEADER: usize = 4;
/// The largest frame a front may send.
pub const MAX_TO_MAIN: usize = 64 * 1024;
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
    Init { run: Uuid },
    /// Answer [`ToMain::Pong`] with the same id.
    Ping { id: u64 },
    /// With a descriptor: write [`CHECK_REPLY`] on it, close it, and answer
    /// [`ToMain::Checked`] with the same id.
    Check { id: u64 },
    /// With a descriptor, a TCP listener that `run` bound: serve the egress proxy's HTTP on
    /// it, for clients that send `Proxy-Authorization: <authorization>` (crate::egress_proxy).
    /// Every destination is `run`'s to decide ([`ToMain::Connect`]).
    ListenEgress { authorization: String },
    /// With a descriptor, the TCP connection `run` made for [`ToMain::Connect`] `req`.
    Connected { req: u64 },
    /// `run` refused [`ToMain::Connect`] `req`: the front answers its client with `status`
    /// and `message`, and with the header `x-tinfirm-egress: denied` when `off_allowlist`
    /// (a `403` only: the destination is on none of the run's allowlists).
    Refused {
        req: u64,
        status: u16,
        message: String,
        off_allowlist: bool,
    },
}

/// The longest proxy credential `run` gives a front.
pub const MAX_AUTHORIZATION: usize = 1024;
/// The longest destination name a front may ask for: a DNS name's limit.
pub const MAX_HOST: usize = 253;
/// The connections a front may have asked for and not yet seen end: its open tunnels and
/// plain HTTP requests (256 each per run, as in-process), with room for those `run` decides.
pub const MAX_FRONT_REQUESTS: usize = 512;

impl ToFront {
    /// Whether this message comes with a descriptor.
    pub fn carries_descriptor(&self) -> bool {
        matches!(
            self,
            ToFront::Check { .. } | ToFront::ListenEgress { .. } | ToFront::Connected { .. }
        )
    }

    /// The bounds of `run`'s messages, which a front checks too.
    pub fn check(&self) -> Result<(), String> {
        match self {
            ToFront::ListenEgress { authorization } if authorization.len() > MAX_AUTHORIZATION => {
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
            ToMain::Pong { .. } | ToMain::Checked { .. } | ToMain::Closed { .. } => Ok(()),
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

/// A front's message from a frame's payload, checked.
pub fn decode_to_main(payload: &[u8]) -> Result<ToMain, WireError> {
    // serde_json names no value of the input in its errors' text, only where it stopped.
    let message: ToMain = serde_json::from_slice(payload).map_err(|e| {
        WireError::NotAMessage(format!("{:?} at column {}", e.classify(), e.column()))
    })?;
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

/// Writes `frame` on the blocking socket `socket`, with `descriptor` on its first byte. A
/// write that takes part of the frame is followed by plain writes of the rest.
pub fn send_frame(
    socket: BorrowedFd<'_>,
    frame: &[u8],
    descriptor: Option<&OwnedFd>,
) -> std::io::Result<()> {
    let written = send_with(socket, frame, descriptor)?;
    let mut rest = std::fs::File::from(socket.try_clone_to_owned()?);
    rest.write_all(&frame[written..])
}

/// One `sendmsg` of `frame`, with `descriptor` attached: how many bytes it took.
pub fn send_with(
    socket: BorrowedFd<'_>,
    frame: &[u8],
    descriptor: Option<&OwnedFd>,
) -> std::io::Result<usize> {
    use rustix::net::{SendAncillaryBuffer, SendAncillaryMessage, SendFlags, sendmsg};
    let fds: Vec<BorrowedFd<'_>> = descriptor.iter().map(|fd| fd.as_fd()).collect();
    let mut space = [std::mem::MaybeUninit::uninit(); rustix::cmsg_space!(ScmRights(1))];
    let mut control = SendAncillaryBuffer::new(&mut space);
    if !fds.is_empty() && !control.push(SendAncillaryMessage::ScmRights(&fds)) {
        return Err(std::io::Error::other("no room for the descriptor"));
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
    fn an_error_names_no_byte_of_the_input() {
        let secret = "ghs_TEST_ONLY_secret_input_0123";
        let frame = format!(r#"{{"type":"pong","id":"{secret}"}}"#);
        let error = decode_to_main(frame.as_bytes()).unwrap_err().to_string();
        assert!(!error.contains(secret), "{error}");
    }
}
