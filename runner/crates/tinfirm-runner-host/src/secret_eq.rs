//! Comparing a secret that a request carries with the expected one, so that the time of the
//! answer tells nothing about the secret.
//!
//! Both values are signed with HMAC-SHA256 under a random key of this process, and the tags
//! are compared. However the compiler or the CPU times that comparison, it can only tell how
//! much of two tags matches, and whoever sends a guess cannot compute its tag without the
//! key, so they cannot steer a guess towards the secret. A plain loop over the bytes stays
//! constant-time only while the optimizer leaves it as written, which `std::hint::black_box`
//! promises on a best-effort basis only.

use std::sync::OnceLock;

use ring::hmac;

/// Whether `a` and `b` are the same bytes. Its time depends on their lengths only, and how
/// it varies beyond that tells nothing about either. Unequal lengths answer at once, before
/// any hashing: a secret's length is no secret, and a long guess costs nothing.
pub fn secret_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let key = key();
    hmac::verify(key, b, hmac::sign(key, a).as_ref()).is_ok()
}

/// The key, drawn once per process from the system's secure random source; it never leaves
/// the process.
fn key() -> &'static hmac::Key {
    static KEY: OnceLock<hmac::Key> = OnceLock::new();
    KEY.get_or_init(|| {
        hmac::Key::generate(hmac::HMAC_SHA256, &ring::rand::SystemRandom::new())
            .expect("the system's secure random source")
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_bytes_and_lengths() {
        assert!(secret_eq(b"abc", b"abc"));
        assert!(!secret_eq(b"abc", b"abd"));
        assert!(!secret_eq(b"abc", b"ab"));
        assert!(!secret_eq(b"", b"a"));
        assert!(secret_eq(b"", b""));
    }
}
