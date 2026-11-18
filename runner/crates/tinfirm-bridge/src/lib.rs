//! The sandbox bridge (ADR-0095): one pinned TLS stream per run between the runner host and
//! its sandbox.
//!
//! - [`host`]: the runner host's listener, the runs registered there, and a run's session.
//! - [`sandbox`]: the bridge in the sandbox, which starts the runtime's ACP adapter and
//!   carries its stdio (`tinfirm-runner-host bridge`).
//! - [`link`]: the numbered, acknowledged frames that both sides keep across a reconnect.
//! - [`tunnel`]: TCP connections in the sandbox, carried to the host's listeners, and the
//!   streams that carry them and the files.
//! - [`files`]: directories as tar archives on those streams, packed and unpacked safely.
//! - [`frame`], [`control`], [`tls`]: the wire.
//!
//! The bridge is not a guard. It runs where the agent can be root, so the host checks what
//! comes over it as it checks a workspace the agent wrote.

pub mod control;
pub mod files;
pub mod frame;
pub mod host;
pub mod link;
pub mod probe;
pub mod sandbox;
pub mod tls;
pub mod tunnel;

/// The bridge protocol's version, in `hello` and `welcome`.
pub const PROTOCOL_VERSION: u32 = 1;

/// Environment of the bridge process: the host's address (`HOST:PORT`).
pub const ENV_ADDR: &str = "TINFIRM_BRIDGE_ADDR";
/// Environment of the bridge process: the run id.
pub const ENV_RUN: &str = "TINFIRM_BRIDGE_RUN";
/// Environment of the bridge process: the one-time ticket of the run.
pub const ENV_TICKET: &str = "TINFIRM_BRIDGE_TICKET";
/// Environment of the bridge process: the SHA-256 of the host's certificate, in hex.
pub const ENV_PIN: &str = "TINFIRM_BRIDGE_PIN";
/// Environment of the bridge process: listen on this address (`HOST:PORT`) for the host to
/// dial in, in place of dialing [`ENV_ADDR`] (a VM per run, whose firewall lets nothing out).
pub const ENV_LISTEN: &str = "TINFIRM_BRIDGE_LISTEN";
/// Environment of the bridge process: the command to start when `start` names none, as a
/// JSON array (the image's command, where the host cannot read it).
pub const ENV_COMMAND: &str = "TINFIRM_BRIDGE_COMMAND";
/// The prefix of every variable above. The adapter never sees them.
pub const ENV_PREFIX: &str = "TINFIRM_BRIDGE_";

/// The version of this crate, which is the workspace's: what the bridge names in its `hello`
/// unless the binary it runs in names more ([`sandbox::Config::build`], ADR-0101).
pub const BUILD: &str = env!("CARGO_PKG_VERSION");

/// 32 bytes from the system's secure random source, in lower-case hex.
pub fn random_token() -> anyhow::Result<String> {
    let mut bytes = [0u8; 32];
    ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut bytes)
        .map_err(|_| anyhow::anyhow!("no secure random source"))?;
    Ok(to_hex(&bytes))
}

/// Whether a ticket, session key or pin is the expected one, in time that tells nothing
/// about either value beyond their lengths.
///
/// Both values are signed with HMAC-SHA256 under a random key of this process, and the tags
/// are compared, as the runner host's proxies compare their secrets. However the compiler or
/// the CPU times that comparison, it can only tell how much of two tags matches, and whoever
/// sends a guess cannot compute its tag without the key. A plain loop over the bytes stays
/// constant-time only while the optimizer leaves it as written, which `std::hint::black_box`
/// promises on a best-effort basis only. Unequal lengths answer at once, before any hashing:
/// a ticket's, key's or pin's length is no secret, and a long guess costs nothing.
pub fn secret_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    static KEY: std::sync::OnceLock<ring::hmac::Key> = std::sync::OnceLock::new();
    let key = KEY.get_or_init(|| {
        ring::hmac::Key::generate(ring::hmac::HMAC_SHA256, &ring::rand::SystemRandom::new())
            .expect("the system's secure random source")
    });
    ring::hmac::verify(key, b, ring::hmac::sign(key, a).as_ref()).is_ok()
}

pub(crate) fn to_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(DIGITS[usize::from(b >> 4)] as char);
        out.push(DIGITS[usize::from(b & 0x0f)] as char);
    }
    out
}

pub(crate) fn from_hex(text: &str) -> Option<Vec<u8>> {
    let text = text.as_bytes();
    if !text.len().is_multiple_of(2) {
        return None;
    }
    let digit = |c: u8| match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    };
    text.chunks(2)
        .map(|pair| Some(digit(pair[0])? << 4 | digit(pair[1])?))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_are_32_random_bytes_in_hex() {
        let a = random_token().unwrap();
        let b = random_token().unwrap();
        assert_eq!(a.len(), 64);
        assert_ne!(a, b);
        assert_eq!(from_hex(&a).unwrap().len(), 32);
    }

    #[test]
    fn hex_round_trips_and_refuses_what_is_not_hex() {
        assert_eq!(to_hex(&[0, 1, 0xab, 0xff]), "0001abff");
        assert_eq!(from_hex("0001ABff").unwrap(), vec![0, 1, 0xab, 0xff]);
        assert!(from_hex("abc").is_none());
        assert!(from_hex("zz").is_none());
    }

    #[test]
    fn secret_eq_compares_bytes_and_lengths() {
        assert!(secret_eq(b"abc", b"abc"));
        assert!(!secret_eq(b"abc", b"abd"));
        assert!(!secret_eq(b"abc", b"ab"));
        assert!(!secret_eq(b"", b"a"));
        assert!(secret_eq(b"", b""));
    }
}
