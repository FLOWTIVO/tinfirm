//! The two values a caller names to the helper: a VM's id and a template's name. Each has one
//! fixed form, checked on the whole byte string, before anything else reads it.

use std::fmt;

use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// The prefix of every VM id.
pub const ID_PREFIX: &[u8] = b"vm-";
/// The number of lower-case hex digits after the prefix: 128 random bits.
pub const ID_HEX_DIGITS: usize = 32;
/// The length of every VM id, in bytes.
pub const ID_LEN: usize = ID_PREFIX.len() + ID_HEX_DIGITS;
/// The longest template name, in bytes.
pub const TEMPLATE_MAX: usize = 63;

/// A VM's id: `vm-` and 32 lower-case hex digits, 35 bytes, nothing before or after.
#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct VmId(String);

impl VmId {
    /// The id, if `bytes` is exactly of the form. Compares the whole byte string: a trailing
    /// newline, a NUL, an upper-case digit or one byte too many is refused.
    pub fn parse(bytes: &[u8]) -> Option<VmId> {
        if bytes.len() != ID_LEN || !bytes.starts_with(ID_PREFIX) {
            return None;
        }
        if !bytes[ID_PREFIX.len()..]
            .iter()
            .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
        {
            return None;
        }
        // Every byte is ASCII, so this cannot fail.
        std::str::from_utf8(bytes).ok().map(|s| VmId(s.to_string()))
    }

    /// A new id from the system's secure random source.
    pub fn generate() -> anyhow::Result<VmId> {
        use ring::rand::SecureRandom;
        let mut bytes = [0u8; ID_HEX_DIGITS / 2];
        ring::rand::SystemRandom::new()
            .fill(&mut bytes)
            .map_err(|_| anyhow::anyhow!("the system's secure random source failed"))?;
        let mut id = String::from_utf8(ID_PREFIX.to_vec()).expect("ASCII");
        for b in bytes {
            id.push_str(&format!("{b:02x}"));
        }
        Ok(VmId(id))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for VmId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl fmt::Debug for VmId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl Serialize for VmId {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for VmId {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<VmId, D::Error> {
        let text = String::deserialize(d)?;
        VmId::parse(text.as_bytes()).ok_or_else(|| {
            serde::de::Error::custom("not a VM id (vm- and 32 lower-case hex digits)")
        })
    }
}

/// A template's name: 1 to 63 bytes of `a-z`, `0-9` and `-`, starting with a letter or digit.
/// The helper looks it up in its own configuration and uses it for nothing else.
#[derive(Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct TemplateName(String);

impl TemplateName {
    pub fn parse(bytes: &[u8]) -> Option<TemplateName> {
        let first_ok = bytes
            .first()
            .is_some_and(|b| b.is_ascii_lowercase() || b.is_ascii_digit());
        if !first_ok
            || bytes.len() > TEMPLATE_MAX
            || !bytes
                .iter()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || *b == b'-')
        {
            return None;
        }
        std::str::from_utf8(bytes)
            .ok()
            .map(|s| TemplateName(s.to_string()))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for TemplateName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl fmt::Debug for TemplateName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl Serialize for TemplateName {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for TemplateName {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<TemplateName, D::Error> {
        let text = String::deserialize(d)?;
        TemplateName::parse(text.as_bytes())
            .ok_or_else(|| serde::de::Error::custom("not a template name (a-z, 0-9 and -)"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const GOOD: &str = "vm-0123456789abcdef0123456789abcdef";

    #[test]
    fn a_good_id_parses_and_generated_ids_are_of_the_form_and_differ() {
        assert_eq!(VmId::parse(GOOD.as_bytes()).unwrap().as_str(), GOOD);
        let a = VmId::generate().unwrap();
        let b = VmId::generate().unwrap();
        assert_ne!(a, b);
        assert!(VmId::parse(a.as_str().as_bytes()).is_some());
        assert_eq!(a.as_str().len(), ID_LEN);
    }

    #[test]
    fn every_malformed_id_is_refused() {
        let cases: Vec<Vec<u8>> = vec![
            format!("{GOOD}\n").into_bytes(),
            format!("{GOOD}\r\n").into_bytes(),
            format!("\n{GOOD}").into_bytes(),
            format!(" {GOOD}").into_bytes(),
            format!("{GOOD} ").into_bytes(),
            GOOD.to_uppercase().into_bytes(),
            "vm-0123456789ABCDEF0123456789abcdef".into(),
            "VM-0123456789abcdef0123456789abcdef".into(),
            "vm-0123456789abcdef0123456789abcde/".into(),
            "vm-../../../../../../../../etc/pass".into(),
            "vm/0123456789abcdef0123456789abcdef".into(),
            {
                let mut b = GOOD.as_bytes().to_vec();
                b[10] = 0;
                b
            },
            format!("{GOOD}\0").into_bytes(),
            format!("{GOOD}0").into_bytes(),
            "vm-0123456789abcdef0123456789abcde".into(),
            Vec::new(),
            b"vm-".to_vec(),
            "vm-0123456789abcdef0123456789abcdéf".into(),
            "vm-0123456789abcdef0123456789abcdeg".into(),
            "vm_0123456789abcdef0123456789abcdef".into(),
            "xx-0123456789abcdef0123456789abcdef".into(),
            "a".repeat(10_000).into_bytes(),
        ];
        for case in cases {
            assert!(VmId::parse(&case).is_none(), "{case:?}");
        }
    }

    #[test]
    fn an_id_in_json_is_checked_after_its_escapes_are_read() {
        let good: VmId = serde_json::from_str(&format!("\"{GOOD}\"")).unwrap();
        assert_eq!(good.as_str(), GOOD);
        for bad in [
            format!("\"{GOOD}\\n\""),
            format!("\"{GOOD}\\u0000\""),
            format!("\"{}\"", GOOD.replace("vm-", "vm\\/")),
            "17".to_string(),
            "null".to_string(),
        ] {
            assert!(serde_json::from_str::<VmId>(&bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn template_names_have_one_form() {
        for good in ["sandbox", "sandbox-4c8g", "4c", &"a".repeat(TEMPLATE_MAX)] {
            assert!(TemplateName::parse(good.as_bytes()).is_some(), "{good}");
        }
        for bad in [
            "",
            "-sandbox",
            "Sandbox",
            "sandbox\n",
            "sand box",
            "../etc",
            "sandbox/x",
            "sandbox_1",
            "sandbox.json",
            "sandbox\0",
            &"a".repeat(TEMPLATE_MAX + 1),
            "sändbox",
        ] {
            assert!(TemplateName::parse(bad.as_bytes()).is_none(), "{bad:?}");
        }
    }
}
