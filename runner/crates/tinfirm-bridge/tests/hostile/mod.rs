//! A hostile sandbox's bytes (ADR-0112, decision 8, "Path C"), for the tests of the bridge's
//! host side: the bridge crate's own listener, and a sandbox's front in the runner host.
//!
//! The bridge runs as the agent's user in the sandbox, so the agent can speak to the host's
//! frame parser and link directly, with the run's ticket. Seeded and deterministic: a failure
//! names its seed.

#![allow(dead_code)]

use std::time::Duration;

use tinfirm_bridge::control::Hidden;
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;

/// xorshift64*.
pub struct Rng(pub u64);

impl Rng {
    pub fn new(seed: u64) -> Rng {
        Rng(seed.wrapping_mul(0x9e37_79b9_7f4a_7c15) | 1)
    }
    pub fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_f491_4f6c_dd1d)
    }
    pub fn below(&mut self, n: u64) -> u64 {
        self.next() % n.max(1)
    }
    pub fn bytes(&mut self, len: usize) -> Vec<u8> {
        (0..len).map(|_| self.next() as u8).collect()
    }
    /// Up to `max - 1` random bytes.
    pub fn noise(&mut self, max: u64) -> Vec<u8> {
        let len = self.below(max) as usize;
        self.bytes(len)
    }
}

pub fn hello(run_id: &str, ticket: &str) -> Vec<u8> {
    let hello = tinfirm_bridge::control::Control::Hello {
        version: tinfirm_bridge::PROTOCOL_VERSION,
        run_id: run_id.to_string(),
        ticket: Some(Hidden::new(ticket)),
        session: None,
        rx_seq: 0,
        tx_seq: Some(0),
        build: Some("hostile".into()),
    };
    tinfirm_bridge::frame::Frame::new(
        tinfirm_bridge::frame::Kind::Control,
        0,
        0,
        serde_json::to_vec(&hello).unwrap(),
    )
    .encode()
}

/// What a hostile sandbox sends after its hello: control messages of every kind, valid,
/// misdirected, malformed and oversized; data on streams that exist and that do not; numbers
/// in order and out of it; and at times plain noise.
pub fn hostile_frame(rng: &mut Rng, seq: &mut u64) -> Vec<u8> {
    use tinfirm_bridge::frame::{Frame, Kind, MAX_DATA, STREAM_DYNAMIC, STREAM_STDOUT};
    let numbered = |rng: &mut Rng, seq: &mut u64| {
        if rng.below(20) == 0 {
            rng.next()
        } else {
            *seq += 1;
            *seq
        }
    };
    let n = rng.next();
    let control = |text: String| text.into_bytes();
    match rng.below(14) {
        0 => {
            let payload = match rng.below(16) {
                0 => control(format!(
                    r#"{{"type":"open","stream":{},"tunnel":{}}}"#,
                    rng.next() as u32,
                    rng.below(40)
                )),
                1 => control(format!(
                    r#"{{"type":"reset","stream":{}}}"#,
                    rng.next() as u32
                )),
                2 => control(format!(r#"{{"type":"listening","id":{n},"tunnels":[]}}"#)),
                3 => control(format!(r#"{{"type":"started","pid":{}}}"#, n as u32)),
                4 => control(format!(
                    r#"{{"type":"exited","code":{},"signal":{}}}"#,
                    n as i32,
                    rng.next() as i32
                )),
                5 => control(format!(
                    r#"{{"type":"error","message":"{}"}}"#,
                    "x".repeat(rng.below(60_000) as usize)
                )),
                6 => control(format!(r#"{{"type":"done","id":{}}}"#, rng.below(4))),
                7 => control(format!(
                    r#"{{"type":"failed","id":{},"message":"no"}}"#,
                    rng.below(4)
                )),
                8 => control(format!(
                    r#"{{"type":"exec_done","id":{},"stdout":"{}","stderr":""}}"#,
                    rng.below(4),
                    "y".repeat(rng.below(1000) as usize)
                )),
                9 => control(format!(
                    r#"{{"type":"probed","id":{},"open":["1.1.1.1"]}}"#,
                    rng.below(4)
                )),
                10 => control(format!(r#"{{"type":"reclaimed","id":{n},"running":true}}"#)),
                11 => control(r#"{"type":"start","argv":["sh"],"env":{},"cwd":null}"#.to_string()),
                12 => control(format!(
                    r#"{{"type":"hello","version":1,"run_id":"other","rx_seq":{n}}}"#
                )),
                13 => control(format!("{}{}", "[".repeat(10_000), "]".repeat(10_000))),
                14 => control(r#"{"type":"open","stream":"#.to_string()),
                _ => rng.noise(500),
            };
            let seq = numbered(rng, seq);
            Frame::new(Kind::Control, 0, seq, payload).encode()
        }
        1..=4 => {
            let stream = match rng.below(4) {
                0 => STREAM_STDOUT,
                1 => 3,
                2 => STREAM_DYNAMIC + rng.below(200) as u32,
                _ => rng.next() as u32,
            };
            let len = rng.below(MAX_DATA as u64 + 1) as usize;
            let seq = numbered(rng, seq);
            Frame::new(Kind::Data, stream, seq, rng.bytes(len)).encode()
        }
        5 => {
            let seq = numbered(rng, seq);
            Frame::new(
                Kind::Eof,
                STREAM_DYNAMIC + rng.below(200) as u32,
                seq,
                Vec::new(),
            )
            .encode()
        }
        6 => {
            let seq = numbered(rng, seq);
            Frame::new(
                Kind::Credit,
                STREAM_DYNAMIC + rng.below(200) as u32,
                seq,
                Frame::credit_payload(rng.next() as u32),
            )
            .encode()
        }
        7 => Frame::ack(if rng.below(2) == 0 { rng.next() } else { *seq }).encode(),
        8 => Frame::ping(rng.next()).encode(),
        9 => Frame::pong(rng.next()).encode(),
        10 => {
            // A header that claims more than the limit, or less than a header.
            let len: u32 = if rng.below(2) == 0 { u32::MAX } else { 3 };
            len.to_be_bytes().to_vec()
        }
        _ if rng.below(10) == 0 => rng.noise(100),
        _ => {
            let seq = numbered(rng, seq);
            Frame::new(Kind::Data, STREAM_STDOUT, seq, b"ok\n".to_vec()).encode()
        }
    }
}

/// Writes `bytes` unless the host has stopped reading for a while: a host that holds a peer
/// back is doing its job.
pub async fn send_or_give_up(
    writer: &mut tokio::io::WriteHalf<tokio_rustls::client::TlsStream<TcpStream>>,
    bytes: &[u8],
) -> bool {
    matches!(
        tokio::time::timeout(Duration::from_millis(500), writer.write_all(bytes)).await,
        Ok(Ok(()))
    )
}

/// A first frame that is no bridge's `hello` for `run_id` with `ticket`: noise, another kind
/// of frame, another version, an oversized or unknown run, a wrong ticket or session.
pub fn first_frame(rng: &mut Rng, run_id: &str, ticket: &str) -> Vec<u8> {
    use tinfirm_bridge::frame::{Frame, Kind};
    match rng.below(8) {
        0 => {
            let mut noise = rng.noise(200);
            noise.push(0xff);
            noise
        }
        1 => Frame::new(Kind::Data, 2, 1, b"x".to_vec()).encode(),
        2 => Frame::new(Kind::Control, 0, 0, rng.bytes(64)).encode(),
        3 => Frame::new(
            Kind::Control,
            0,
            0,
            format!(r#"{{"type":"hello","version":99,"run_id":"{run_id}","rx_seq":0}}"#)
                .into_bytes(),
        )
        .encode(),
        4 => hello(&"r".repeat(rng.below(100_000) as usize + 1), "nope"),
        5 => hello(run_id, &"0".repeat(64)),
        6 => hello("run-unknown", ticket),
        _ => Frame::new(
            Kind::Control,
            0,
            0,
            format!(
                r#"{{"type":"hello","version":1,"run_id":"{run_id}","session":"{}","rx_seq":{}}}"#,
                "a".repeat(64),
                rng.next()
            )
            .into_bytes(),
        )
        .encode(),
    }
}
