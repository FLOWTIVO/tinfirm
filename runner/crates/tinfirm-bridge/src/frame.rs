//! The frames inside the bridge's TLS stream (ADR-0095):
//!
//! ```text
//! length: u32 BE | kind: u8 | stream: u32 BE | seq: u64 BE | payload
//! ```
//!
//! `length` counts the bytes after itself. Every frame is checked when it is read: its kind,
//! its stream, its number and the size of its payload.

use std::io;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// `kind`, `stream` and `seq`.
pub const HEADER: usize = 1 + 4 + 8;
/// The most bytes of one `DATA` frame.
pub const MAX_DATA: usize = 64 * 1024;
/// The most bytes of one `CONTROL` frame (a `start` with a large environment).
pub const MAX_CONTROL: usize = 1024 * 1024;

/// The control stream.
pub const STREAM_CONTROL: u32 = 0;
/// The adapter's stdin, host to sandbox.
pub const STREAM_STDIN: u32 = 1;
/// The adapter's stdout, sandbox to host.
pub const STREAM_STDOUT: u32 = 2;
/// The adapter's stderr, and the bridge's own messages, sandbox to host.
pub const STREAM_STDERR: u32 = 3;
/// The first stream of a tunnel's connection ([`crate::tunnel`]). The sandbox numbers them
/// from here up, one per connection.
pub const STREAM_DYNAMIC: u32 = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Control,
    Data,
    Eof,
    Ack,
    Ping,
    Pong,
    /// More room for a tunnel's stream: its receiver wrote this many bytes on.
    Credit,
}

impl Kind {
    fn code(self) -> u8 {
        match self {
            Kind::Control => 1,
            Kind::Data => 2,
            Kind::Eof => 3,
            Kind::Ack => 4,
            Kind::Ping => 5,
            Kind::Pong => 6,
            Kind::Credit => 7,
        }
    }

    fn from_code(code: u8) -> Option<Kind> {
        Some(match code {
            1 => Kind::Control,
            2 => Kind::Data,
            3 => Kind::Eof,
            4 => Kind::Ack,
            5 => Kind::Ping,
            6 => Kind::Pong,
            7 => Kind::Credit,
            _ => return None,
        })
    }

    /// Numbered frames are kept until the peer acknowledges them, and sent again after a
    /// reconnect. The others (`ACK`, `PING`, `PONG`) belong to one connection.
    pub fn numbered(self) -> bool {
        matches!(self, Kind::Control | Kind::Data | Kind::Eof | Kind::Credit)
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct Frame {
    pub kind: Kind,
    pub stream: u32,
    /// The frame's number in its direction, from 1; 0 for a frame that is not numbered, and
    /// for the handshake's control frames.
    pub seq: u64,
    pub payload: Vec<u8>,
}

impl std::fmt::Debug for Frame {
    // Never the payload: it may hold the run's credentials.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Frame")
            .field("kind", &self.kind)
            .field("stream", &self.stream)
            .field("seq", &self.seq)
            .field("len", &self.payload.len())
            .finish()
    }
}

#[derive(Debug, thiserror::Error)]
pub enum FrameError {
    #[error("a frame of {0} bytes is longer than the limit")]
    TooLong(usize),
    #[error("a frame of {0} bytes is shorter than its header")]
    TooShort(usize),
    #[error("unknown frame kind {0}")]
    UnknownKind(u8),
    #[error("invalid frame: {0}")]
    Invalid(&'static str),
    #[error("the connection ended inside a frame")]
    Truncated,
    #[error(transparent)]
    Io(#[from] io::Error),
}

impl Frame {
    pub fn new(kind: Kind, stream: u32, seq: u64, payload: Vec<u8>) -> Frame {
        Frame {
            kind,
            stream,
            seq,
            payload,
        }
    }

    pub fn ack(seq: u64) -> Frame {
        Frame::new(Kind::Ack, STREAM_CONTROL, 0, seq.to_be_bytes().to_vec())
    }

    pub fn ping(nonce: u64) -> Frame {
        Frame::new(Kind::Ping, STREAM_CONTROL, 0, nonce.to_be_bytes().to_vec())
    }

    pub fn pong(nonce: u64) -> Frame {
        Frame::new(Kind::Pong, STREAM_CONTROL, 0, nonce.to_be_bytes().to_vec())
    }

    /// The number an `ACK`, `PING` or `PONG` carries.
    pub fn number(&self) -> Option<u64> {
        let bytes: [u8; 8] = self.payload.as_slice().try_into().ok()?;
        Some(u64::from_be_bytes(bytes))
    }

    /// The payload of a `CREDIT`: how many bytes its stream may carry more.
    pub fn credit_payload(bytes: u32) -> Vec<u8> {
        bytes.to_be_bytes().to_vec()
    }

    /// The bytes a `CREDIT` grants.
    pub fn credit(&self) -> Option<u32> {
        let bytes: [u8; 4] = self.payload.as_slice().try_into().ok()?;
        Some(u32::from_be_bytes(bytes))
    }

    /// The frame on the wire, length first.
    pub fn encode(&self) -> Vec<u8> {
        let len = HEADER + self.payload.len();
        let mut out = Vec::with_capacity(4 + len);
        out.extend_from_slice(&(len as u32).to_be_bytes());
        out.push(self.kind.code());
        out.extend_from_slice(&self.stream.to_be_bytes());
        out.extend_from_slice(&self.seq.to_be_bytes());
        out.extend_from_slice(&self.payload);
        out
    }

    /// Checks the rules of ADR-0095 for a frame of this kind.
    pub fn check(&self) -> Result<(), FrameError> {
        match self.kind {
            Kind::Control => {
                if self.stream != STREAM_CONTROL {
                    return Err(FrameError::Invalid("a control frame on a data stream"));
                }
                if self.payload.len() > MAX_CONTROL {
                    return Err(FrameError::TooLong(self.payload.len()));
                }
            }
            Kind::Data | Kind::Eof => {
                if !matches!(self.stream, STREAM_STDIN | STREAM_STDOUT | STREAM_STDERR)
                    && self.stream < STREAM_DYNAMIC
                {
                    return Err(FrameError::Invalid("a data frame on an unknown stream"));
                }
                if self.seq == 0 {
                    return Err(FrameError::Invalid("a data frame without a number"));
                }
                if self.kind == Kind::Data && self.payload.len() > MAX_DATA {
                    return Err(FrameError::TooLong(self.payload.len()));
                }
                if self.kind == Kind::Eof && !self.payload.is_empty() {
                    return Err(FrameError::Invalid("an end of stream with a payload"));
                }
            }
            Kind::Credit => {
                if self.stream < STREAM_DYNAMIC || self.seq == 0 || self.payload.len() != 4 {
                    return Err(FrameError::Invalid(
                        "a credit that is not 4 bytes, numbered, on a tunnel's stream",
                    ));
                }
            }
            Kind::Ack | Kind::Ping | Kind::Pong => {
                if self.stream != STREAM_CONTROL || self.seq != 0 || self.payload.len() != 8 {
                    return Err(FrameError::Invalid(
                        "an acknowledgement or ping that is not 8 bytes on stream 0",
                    ));
                }
            }
        }
        Ok(())
    }
}

/// Reads one frame. `Ok(None)` when the stream ends between two frames.
pub async fn read_frame<R: AsyncRead + Unpin>(reader: &mut R) -> Result<Option<Frame>, FrameError> {
    let mut len = [0u8; 4];
    match reader.read_exact(&mut len).await {
        Ok(_) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e.into()),
    }
    let len = u32::from_be_bytes(len) as usize;
    if len > HEADER + MAX_CONTROL {
        return Err(FrameError::TooLong(len));
    }
    if len < HEADER {
        return Err(FrameError::TooShort(len));
    }
    let mut body = vec![0u8; len];
    reader.read_exact(&mut body).await.map_err(|e| {
        if e.kind() == io::ErrorKind::UnexpectedEof {
            FrameError::Truncated
        } else {
            FrameError::Io(e)
        }
    })?;
    let kind = Kind::from_code(body[0]).ok_or(FrameError::UnknownKind(body[0]))?;
    let stream = u32::from_be_bytes(body[1..5].try_into().expect("4 bytes"));
    let seq = u64::from_be_bytes(body[5..13].try_into().expect("8 bytes"));
    let frame = Frame::new(kind, stream, seq, body.split_off(HEADER));
    frame.check()?;
    Ok(Some(frame))
}

/// Writes the bytes of encoded frames and flushes them.
pub async fn write_bytes<W: AsyncWrite + Unpin>(writer: &mut W, bytes: &[u8]) -> io::Result<()> {
    writer.write_all(bytes).await?;
    writer.flush().await
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn decode(bytes: &[u8]) -> Result<Option<Frame>, FrameError> {
        let mut reader = bytes;
        read_frame(&mut reader).await
    }

    #[tokio::test]
    async fn frames_round_trip() {
        let frames = [
            Frame::new(
                Kind::Control,
                STREAM_CONTROL,
                0,
                br#"{"type":"hello"}"#.to_vec(),
            ),
            Frame::new(Kind::Data, STREAM_STDOUT, 7, b"line\n".to_vec()),
            Frame::new(Kind::Eof, STREAM_STDIN, 8, Vec::new()),
            Frame::new(Kind::Data, STREAM_DYNAMIC + 3, 9, b"GET /".to_vec()),
            Frame::new(
                Kind::Credit,
                STREAM_DYNAMIC,
                10,
                Frame::credit_payload(4096),
            ),
            Frame::ack(41),
            Frame::ping(3),
            Frame::pong(3),
        ];
        let mut wire = Vec::new();
        for frame in &frames {
            wire.extend(frame.encode());
        }
        let mut reader = wire.as_slice();
        for frame in &frames {
            assert_eq!(&read_frame(&mut reader).await.unwrap().unwrap(), frame);
        }
        assert!(read_frame(&mut reader).await.unwrap().is_none());
        assert_eq!(Frame::ack(41).number(), Some(41));
        assert_eq!(frames[4].credit(), Some(4096));
    }

    #[tokio::test]
    async fn a_truncated_frame_is_an_error_and_an_empty_stream_is_the_end() {
        assert!(decode(&[]).await.unwrap().is_none());
        let wire = Frame::new(Kind::Data, STREAM_STDOUT, 1, b"abc".to_vec()).encode();
        assert!(matches!(
            decode(&wire[..wire.len() - 1]).await,
            Err(FrameError::Truncated)
        ));
        assert!(matches!(decode(&wire[..2]).await, Ok(None) | Err(_)));
    }

    #[tokio::test]
    async fn oversized_and_undersized_lengths_are_refused_before_reading_the_body() {
        let mut too_long = ((HEADER + MAX_CONTROL + 1) as u32).to_be_bytes().to_vec();
        too_long.extend([0u8; 16]);
        assert!(matches!(
            decode(&too_long).await,
            Err(FrameError::TooLong(_))
        ));
        let mut too_short = 3u32.to_be_bytes().to_vec();
        too_short.extend([1u8, 0, 0]);
        assert!(matches!(
            decode(&too_short).await,
            Err(FrameError::TooShort(3))
        ));
    }

    #[tokio::test]
    async fn frames_that_break_the_rules_are_refused() {
        let bad = [
            // A data frame larger than the limit.
            Frame::new(Kind::Data, STREAM_STDOUT, 1, vec![0; MAX_DATA + 1]),
            // Data on the control stream, or on a stream below the tunnels' that is not stdio.
            Frame::new(Kind::Data, STREAM_CONTROL, 1, b"x".to_vec()),
            Frame::new(Kind::Data, STREAM_DYNAMIC - 1, 1, b"x".to_vec()),
            // A credit on stdio, unnumbered, or of the wrong size.
            Frame::new(Kind::Credit, STREAM_STDOUT, 1, Frame::credit_payload(1)),
            Frame::new(Kind::Credit, STREAM_DYNAMIC, 0, Frame::credit_payload(1)),
            Frame::new(Kind::Credit, STREAM_DYNAMIC, 1, vec![0; 8]),
            // Data without a number.
            Frame::new(Kind::Data, STREAM_STDOUT, 0, b"x".to_vec()),
            // Control on a data stream.
            Frame::new(Kind::Control, STREAM_STDOUT, 1, b"{}".to_vec()),
            // An end of stream with bytes.
            Frame::new(Kind::Eof, STREAM_STDOUT, 1, b"x".to_vec()),
            // An acknowledgement of the wrong size, or numbered.
            Frame::new(Kind::Ack, STREAM_CONTROL, 0, vec![0; 4]),
            Frame::new(Kind::Ack, STREAM_CONTROL, 5, vec![0; 8]),
        ];
        for frame in bad {
            let wire = frame.encode();
            assert!(decode(&wire).await.is_err(), "{frame:?} was accepted");
        }
        let mut unknown = Frame::ping(1).encode();
        unknown[4] = 99;
        assert!(matches!(
            decode(&unknown).await,
            Err(FrameError::UnknownKind(99))
        ));
    }

    #[test]
    fn debug_never_shows_the_payload() {
        let frame = Frame::new(Kind::Control, 0, 1, b"sk-ant-secret".to_vec());
        assert!(!format!("{frame:?}").contains("secret"));
    }
}
