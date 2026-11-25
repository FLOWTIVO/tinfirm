//! Strict JSON for both wires: a value whose objects, at every level, name no key twice.
//! `serde_json` keeps the last of two equal keys, so `{"v":2,"v":1}` would read as `v: 1`; a
//! wire that a reviewer reads as "exactly these fields" must refuse it.

use std::fmt;

use serde::de::{self, Deserialize, Deserializer, MapAccess, SeqAccess, Visitor};
use serde_json::{Map, Number, Value};

/// A JSON value read with every object's keys checked for duplicates.
struct Strict(Value);

struct StrictVisitor;

impl<'de> Visitor<'de> for StrictVisitor {
    type Value = Strict;

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("JSON")
    }

    fn visit_bool<E>(self, v: bool) -> Result<Strict, E> {
        Ok(Strict(Value::Bool(v)))
    }

    fn visit_i64<E>(self, v: i64) -> Result<Strict, E> {
        Ok(Strict(Value::Number(v.into())))
    }

    fn visit_u64<E>(self, v: u64) -> Result<Strict, E> {
        Ok(Strict(Value::Number(v.into())))
    }

    fn visit_f64<E: de::Error>(self, v: f64) -> Result<Strict, E> {
        Number::from_f64(v)
            .map(|n| Strict(Value::Number(n)))
            .ok_or_else(|| E::custom("not a finite number"))
    }

    fn visit_str<E>(self, v: &str) -> Result<Strict, E> {
        Ok(Strict(Value::String(v.to_string())))
    }

    fn visit_string<E>(self, v: String) -> Result<Strict, E> {
        Ok(Strict(Value::String(v)))
    }

    fn visit_unit<E>(self) -> Result<Strict, E> {
        Ok(Strict(Value::Null))
    }

    fn visit_none<E>(self) -> Result<Strict, E> {
        Ok(Strict(Value::Null))
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Strict, A::Error> {
        let mut items = Vec::new();
        while let Some(Strict(item)) = seq.next_element()? {
            items.push(item);
        }
        Ok(Strict(Value::Array(items)))
    }

    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Strict, A::Error> {
        let mut object = Map::new();
        while let Some(key) = map.next_key::<String>()? {
            if object.contains_key(&key) {
                return Err(de::Error::custom("a key appears twice"));
            }
            let Strict(value) = map.next_value()?;
            object.insert(key, value);
        }
        Ok(Strict(Value::Object(object)))
    }
}

impl<'de> Deserialize<'de> for Strict {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Strict, D::Error> {
        d.deserialize_any(StrictVisitor)
    }
}

/// Why a line was not strict JSON. It holds no byte of the line.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NotStrict {
    /// Not JSON, or a key twice in an object.
    Json,
    /// JSON, but not an object.
    NotObject,
}

/// A line that is one JSON value, with no key twice in any object.
pub fn value(line: &[u8]) -> Result<Value, NotStrict> {
    serde_json::from_slice::<Strict>(line)
        .map(|Strict(v)| v)
        .map_err(|_| NotStrict::Json)
}

/// A line that is one JSON object, with no key twice in any object.
pub fn object(line: &[u8]) -> Result<Map<String, Value>, NotStrict> {
    match value(line)? {
        Value::Object(map) => Ok(map),
        _ => Err(NotStrict::NotObject),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_key_twice_is_refused_at_every_level() {
        assert!(object(br#"{"a":1,"b":{"c":[1,2.5,"x",null,true]}}"#).is_ok());
        for bad in [
            &br#"{"v":2,"v":1}"#[..],
            br#"{"op":"delete","op":"list"}"#,
            br#"{"a":{"b":1,"b":2}}"#,
            br#"{"a":[{"b":1,"b":2}]}"#,
            br#"{"a":1,"a":2}"#,
            // The same key, once spelt with a JSON escape: equal once it is read.
            b"{\"a\":1,\"\\u0061\":2}",
            b"{",
            b"",
        ] {
            assert_eq!(
                object(bad),
                Err(NotStrict::Json),
                "{}",
                String::from_utf8_lossy(bad)
            );
        }
        assert_eq!(object(b"[]"), Err(NotStrict::NotObject));
        assert_eq!(object(b"1"), Err(NotStrict::NotObject));
    }
}
