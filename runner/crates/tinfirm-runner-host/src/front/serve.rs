//! The front's side of the control channel: its stdin, a Unix stream socket whose other end
//! is `run`'s ([`super::wire`]).
//!
//! The front is confined already when this starts. It says so first ([`ToMain::Ready`]),
//! then answers `run`'s messages in order until `run` closes its end, and then exits. A
//! message that is not one of the wire's, that breaks its bounds, or that wants a descriptor
//! that did not come with it ends the front with exit code 3: `run`'s messages are trusted,
//! so such a message means a fault, and the front does not guess.

use std::collections::VecDeque;
use std::io::IoSliceMut;
use std::os::fd::{AsFd, OwnedFd};
use std::process::ExitCode;

use crate::confine::Status;

use super::wire::{self, CHECK_REPLY, MAX_TO_FRONT, MAX_TO_MAIN, ToFront, ToMain};

/// How much a read takes at most.
const READ: usize = 64 * 1024;
/// The most descriptors one read may bring.
const DESCRIPTORS_PER_READ: usize = 4;

/// The control channel, as the front reads it: bytes, and the descriptors that came with them,
/// in order.
struct Channel {
    socket: OwnedFd,
    bytes: Vec<u8>,
    descriptors: VecDeque<OwnedFd>,
}

impl Channel {
    /// Reads once more: `false` at the end of the channel.
    fn read(&mut self) -> std::io::Result<bool> {
        use rustix::net::{RecvAncillaryBuffer, RecvAncillaryMessage, RecvFlags, recvmsg};
        let mut buffer = vec![0u8; READ];
        let mut space =
            [std::mem::MaybeUninit::uninit(); rustix::cmsg_space!(ScmRights(DESCRIPTORS_PER_READ))];
        let mut control = RecvAncillaryBuffer::new(&mut space);
        let received = loop {
            match recvmsg(
                &self.socket,
                &mut [IoSliceMut::new(&mut buffer)],
                &mut control,
                RecvFlags::CMSG_CLOEXEC,
            ) {
                Ok(received) => break received,
                Err(rustix::io::Errno::INTR) => continue,
                Err(e) => return Err(e.into()),
            }
        };
        for message in control.drain() {
            if let RecvAncillaryMessage::ScmRights(fds) = message {
                self.descriptors.extend(fds);
            }
        }
        if received.bytes == 0 {
            return Ok(false);
        }
        self.bytes.extend_from_slice(&buffer[..received.bytes]);
        Ok(true)
    }

    /// The next message: `Ok(None)` at the end of the channel.
    fn next(&mut self) -> Result<Option<ToFront>, String> {
        loop {
            if self.bytes.len() >= wire::HEADER {
                let header: [u8; wire::HEADER] =
                    self.bytes[..wire::HEADER].try_into().expect("checked");
                let length = wire::frame_length(header, MAX_TO_FRONT).map_err(|e| e.to_string())?;
                if self.bytes.len() >= wire::HEADER + length {
                    let payload: Vec<u8> = self
                        .bytes
                        .drain(..wire::HEADER + length)
                        .skip(wire::HEADER)
                        .collect();
                    return wire::decode_to_front(&payload)
                        .map(Some)
                        .map_err(|e| e.to_string());
                }
            }
            match self.read() {
                Ok(true) => {}
                Ok(false) if self.bytes.is_empty() => return Ok(None),
                Ok(false) => return Err("the channel ended within a frame".into()),
                Err(e) => return Err(format!("cannot read the control channel: {e}")),
            }
        }
    }

    fn send(&self, message: &ToMain) -> Result<(), String> {
        let frame = wire::encode(message, MAX_TO_MAIN).map_err(|e| e.to_string())?;
        wire::send_frame(self.socket.as_fd(), &frame, None)
            .map_err(|e| format!("cannot write the control channel: {e}"))
    }
}

/// Serves the control channel until `run` closes it.
pub fn serve(status: Status) -> ExitCode {
    let socket = match std::io::stdin().as_fd().try_clone_to_owned() {
        Ok(socket) => socket,
        Err(e) => return super::fail(&format!("no control channel on stdin: {e}")),
    };
    let mut channel = Channel {
        socket,
        bytes: Vec::new(),
        descriptors: VecDeque::new(),
    };
    if let Err(e) = channel.send(&ToMain::Ready { status }) {
        return super::fail(&format!(
            "{e} (stdin must be the control channel `run` opens)"
        ));
    }
    let mut init = false;
    loop {
        let message = match channel.next() {
            Ok(Some(message)) => message,
            Ok(None) => return ExitCode::SUCCESS,
            Err(e) => return refuse(&e),
        };
        let descriptor = if message.carries_descriptor() {
            match channel.descriptors.pop_front() {
                Some(fd) => Some(fd),
                None => return refuse("a message came without its descriptor"),
            }
        } else {
            None
        };
        let answer = match message {
            ToFront::Init { .. } if init => return refuse("a second init"),
            ToFront::Init { .. } => {
                init = true;
                continue;
            }
            _ if !init => return refuse("a message before init"),
            ToFront::Ping { id } => ToMain::Pong { id },
            ToFront::Check { id } => {
                let fd = descriptor.expect("taken above");
                let mut file = std::fs::File::from(fd);
                if std::io::Write::write_all(&mut file, CHECK_REPLY).is_err() {
                    return refuse("cannot write on the descriptor of a check");
                }
                drop(file);
                ToMain::Checked { id }
            }
        };
        if let Err(e) = channel.send(&answer) {
            return super::fail(&e);
        }
    }
}

fn refuse(why: &str) -> ExitCode {
    eprintln!("tinfirm-runner-host front: {why}");
    ExitCode::from(3)
}
