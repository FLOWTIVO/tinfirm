//! The agent's wire between the control host's `node` driver and the listener: JSON-RPC 2.0, one
//! message per line, the driver protocol's methods with their parameters and results
//! (ADR-0096, `ee/runner/tinfirm-runner-host/src/vm.rs`), and `hello`, which checks the wire's
//! version and each side's build. The README specifies it.

use std::collections::BTreeMap;
use std::fmt;

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

/// The version of this wire (`hello.wire`).
pub const WIRE: u32 = 1;
/// The agent's name in `hello`.
pub const AGENT: &str = "tinfirm-node-agent";
/// The longest line either side sends: the user data's 64 KiB, escaped, and the rest.
pub const MAX_LINE: usize = 512 * 1024;
/// The label of the runner host whose VM it is: a delete must name it (ADR-0096).
pub const HOST_LABEL: &str = "tinfirm-host";
/// At most this many labels, each key of at most 63 bytes and each value of at most 128.
pub const MAX_LABELS: usize = 16;
pub const MAX_LABEL_KEY: usize = 63;
pub const MAX_LABEL_VALUE: usize = 128;
/// The longest VM name.
pub const MAX_NAME: usize = 63;
/// The notification of a VM whose VMM died.
pub const VM_FAILED: &str = "vmFailed";

/// This build: the release's build id when the release sets `TINFIRM_BUILD_ID` at compile
/// time, else the crate's version.
pub fn build_id() -> &'static str {
    match option_env!("TINFIRM_BUILD_ID") {
        Some(build) => build,
        None => env!("CARGO_PKG_VERSION"),
    }
}

/// The release of a build id: what comes before its build metadata (`+…`), as semantic
/// versioning compares. The runner host's build id carries a digest of its runner images
/// there (`1.0.0+images.1a2b3c4d5e6f`), which is no concern of the agent's: two builds of one
/// release are the same to the wire's check. The `node` driver of the runner host compares the
/// same way (`runner/crates/tinfirm-runner-host/src/node.rs`).
pub fn release_of(build: &str) -> &str {
    build.split('+').next().unwrap_or(build)
}

/// A JSON-RPC error: its code, and a message that never holds user data.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RpcError {
    pub code: i64,
    pub message: String,
}

impl RpcError {
    pub fn new(code: i64, message: impl Into<String>) -> RpcError {
        RpcError {
            code,
            message: message.into(),
        }
    }
}

impl fmt::Display for RpcError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} ({})", self.message, self.code)
    }
}

/// The error codes.
pub mod code {
    pub const PARSE: i64 = -32700;
    pub const INVALID_REQUEST: i64 = -32600;
    pub const NO_METHOD: i64 = -32601;
    pub const INVALID_PARAMS: i64 = -32602;
    /// The helper or its backend failed.
    pub const FAILED: i64 = -32000;
    /// A method before `hello`.
    pub const HELLO_FIRST: i64 = -32001;
    /// A delete of a VM without each of the labels it names.
    pub const REFUSED: i64 = -32002;
    /// Another wire version, or another build where the listener refuses one.
    pub const MISMATCH: i64 = -32003;
    /// A backend that is built in parts; the Linux backend never answers so.
    pub const NOT_BUILT: i64 = -32004;
    pub const UNKNOWN_TEMPLATE: i64 = -32005;
    /// The node runs as many VMs as it may.
    pub const FULL: i64 = -32006;
    /// Too many requests at once on one connection.
    pub const BUSY: i64 = -32007;
}

/// A request line.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RpcRequest {
    pub jsonrpc: String,
    #[serde(default)]
    pub id: Option<Value>,
    pub method: String,
    #[serde(default)]
    pub params: Option<Value>,
}

/// A request's parameters may hold user data: they are overwritten when the request is dropped,
/// whichever way it was answered.
impl Drop for RpcRequest {
    fn drop(&mut self) {
        if let Some(params) = &mut self.params {
            wipe_value(params);
        }
    }
}

/// The methods of the wire. A method's name goes into a log or a message only as one of these.
pub const METHODS: [&str; 6] = ["hello", "verify", "create", "describe", "delete", "list"];

impl RpcRequest {
    /// The request of a line, or the error to answer it with (and the id, where one was read).
    /// No object at any level may name a key twice. Every error is a fixed message: none holds
    /// a byte the peer sent.
    pub fn parse(line: &[u8]) -> Result<RpcRequest, (Value, RpcError)> {
        let value = crate::strict::value(line).map_err(|_| {
            (
                Value::Null,
                RpcError::new(code::PARSE, "not JSON, or a key twice in an object"),
            )
        })?;
        // An error echoes the id only when it is of a form an id may have.
        let id = match value.get("id") {
            Some(Value::Number(n)) => Value::Number(n.clone()),
            Some(Value::String(s)) if s.len() <= 64 => Value::String(s.clone()),
            _ => Value::Null,
        };
        let request: RpcRequest = serde_json::from_value(value).map_err(|_| {
            (
                id.clone(),
                RpcError::new(code::INVALID_REQUEST, "not a JSON-RPC 2.0 request"),
            )
        })?;
        if request.jsonrpc != "2.0" {
            return Err((
                id,
                RpcError::new(code::INVALID_REQUEST, "jsonrpc must be 2.0"),
            ));
        }
        match &request.id {
            None | Some(Value::Number(_)) => {}
            Some(Value::String(s)) if s.len() <= 64 => {}
            Some(_) => {
                return Err((
                    Value::Null,
                    RpcError::new(
                        code::INVALID_REQUEST,
                        "the id is a number or a short string",
                    ),
                ));
            }
        }
        Ok(request)
    }

    /// The method's name as a log may show it: one of [`METHODS`], or `other`.
    pub fn method_name(&self) -> &'static str {
        METHODS
            .iter()
            .find(|m| **m == self.method)
            .copied()
            .unwrap_or("other")
    }

    /// The parameters as `T`. `params` must be a JSON object: absent, an array or anything else
    /// is refused. The error is a fixed message, without serde's text, which would echo what
    /// the peer sent.
    pub fn params<T: serde::de::DeserializeOwned>(&self) -> Result<T, RpcError> {
        let method = self.method_name();
        let refused = || {
            RpcError::new(
                code::INVALID_PARAMS,
                format!(
                    "the parameters of `{method}` are not an object with exactly its fields, each of its form"
                ),
            )
        };
        match &self.params {
            Some(params @ Value::Object(_)) => {
                serde_json::from_value(params.clone()).map_err(|_| refused())
            }
            _ => Err(refused()),
        }
    }
}
pub fn answer(id: &Value, result: Result<Value, RpcError>) -> Value {
    match result {
        Ok(result) => json!({ "jsonrpc": "2.0", "id": id, "result": result }),
        Err(e) => json!({
            "jsonrpc": "2.0",
            "id": id,
            "error": { "code": e.code, "message": e.message },
        }),
    }
}

pub fn notification(method: &str, params: Value) -> Value {
    json!({ "jsonrpc": "2.0", "method": method, "params": params })
}

// ----- parameters ----------------------------------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HelloParams {
    pub wire: u32,
    pub build: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VerifyParams {
    pub template: String,
}

/// The user data: text the listener passes on as it is, and never logs.
#[derive(Clone, Deserialize)]
#[serde(transparent)]
pub struct UserData(pub String);

impl UserData {
    /// Overwrites the text and leaves it empty ([`crate::spool::wipe`]): the listener calls
    /// it once the user data is in the spool, and it happens when the value is dropped.
    pub fn wipe(&mut self) {
        let mut bytes = std::mem::take(&mut self.0).into_bytes();
        crate::spool::wipe(&mut bytes);
    }
}

impl Drop for UserData {
    fn drop(&mut self) {
        self.wipe();
    }
}

/// Overwrites every string of a JSON value, at any depth ([`crate::spool::wipe`]).
pub fn wipe_value(value: &mut Value) {
    match value {
        Value::String(text) => {
            let mut bytes = std::mem::take(text).into_bytes();
            crate::spool::wipe(&mut bytes);
        }
        Value::Array(items) => items.iter_mut().for_each(wipe_value),
        Value::Object(fields) => fields.values_mut().for_each(wipe_value),
        Value::Null | Value::Bool(_) | Value::Number(_) => {}
    }
}

impl fmt::Debug for UserData {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "UserData({} bytes)", self.0.len())
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CreateParams {
    pub template: String,
    pub name: String,
    pub user_data: UserData,
    #[serde(default)]
    pub labels: BTreeMap<String, String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DescribeParams {
    pub id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DeleteParams {
    pub id: String,
    #[serde(default)]
    pub labels: BTreeMap<String, String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ListParams {
    #[serde(default)]
    pub labels: BTreeMap<String, String>,
}

/// A VM as `describe` and `list` report it: the driver protocol's `Vm`, with `port`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Vm {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// `running`, `failed` or `gone`.
    pub state: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub address: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub labels: BTreeMap<String, String>,
}

// ----- the forms of names and labels -----------------------------------------------------------

/// A VM's name: 1 to 63 bytes of `a-z`, `0-9`, `-` and `.`.
pub fn check_name(name: &str) -> Result<(), RpcError> {
    if name.is_empty()
        || name.len() > MAX_NAME
        || !name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'.')
    {
        return Err(RpcError::new(
            code::INVALID_PARAMS,
            "the name is 1 to 63 bytes of a-z, 0-9, - and .",
        ));
    }
    Ok(())
}

/// Labels: at most [`MAX_LABELS`]; a key of `a-z`, `0-9` and `-`; a value of letters, digits,
/// `-`, `_` and `.`.
pub fn check_labels(labels: &BTreeMap<String, String>) -> Result<(), RpcError> {
    let bad = || {
        RpcError::new(
            code::INVALID_PARAMS,
            format!(
                "labels: at most {MAX_LABELS}, keys of a-z, 0-9 and - (1 to {MAX_LABEL_KEY} bytes), values of letters, digits, -, _ and . (1 to {MAX_LABEL_VALUE} bytes)"
            ),
        )
    };
    if labels.len() > MAX_LABELS {
        return Err(bad());
    }
    for (key, value) in labels {
        let key_ok = !key.is_empty()
            && key.len() <= MAX_LABEL_KEY
            && key
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-');
        let value_ok = !value.is_empty()
            && value.len() <= MAX_LABEL_VALUE
            && value
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'));
        if !key_ok || !value_ok {
            return Err(bad());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requests_parse_and_bad_ones_are_answered_with_their_code() {
        let r =
            RpcRequest::parse(br#"{"jsonrpc":"2.0","id":7,"method":"list","params":{}}"#).unwrap();
        assert_eq!(r.method, "list");
        assert_eq!(r.id, Some(json!(7)));
        for (line, expected) in [
            (&b"{"[..], code::PARSE),
            (
                br#"{"jsonrpc":"1.0","id":1,"method":"list"}"#,
                code::INVALID_REQUEST,
            ),
            (
                br#"{"jsonrpc":"2.0","id":1,"method":"list","extra":1}"#,
                code::INVALID_REQUEST,
            ),
            (
                br#"{"jsonrpc":"2.0","id":{},"method":"list"}"#,
                code::INVALID_REQUEST,
            ),
            (br#"{"jsonrpc":"2.0","id":1}"#, code::INVALID_REQUEST),
        ] {
            let (_, e) = RpcRequest::parse(line).unwrap_err();
            assert_eq!(e.code, expected, "{}", String::from_utf8_lossy(line));
        }
    }

    #[test]
    fn parameters_have_exactly_their_fields() {
        let create = |params: Value| {
            RpcRequest::parse(
                json!({"jsonrpc":"2.0","id":1,"method":"create","params":params})
                    .to_string()
                    .as_bytes(),
            )
            .unwrap()
            .params::<CreateParams>()
        };
        let good = json!({"template":"t","name":"n","userData":"secret-ticket","labels":{"tinfirm-host":"h"}});
        let params = create(good.clone()).unwrap();
        // The user data never shows in a log line.
        assert!(!format!("{params:?}").contains("secret-ticket"));
        let mut extra = good.clone();
        extra["memoryMib"] = json!(1);
        let e = create(extra).unwrap_err();
        assert_eq!(e.code, code::INVALID_PARAMS);
        assert!(!e.message.contains("secret-ticket"), "{}", e.message);
        let mut missing = good;
        missing.as_object_mut().unwrap().remove("userData");
        assert_eq!(create(missing).unwrap_err().code, code::INVALID_PARAMS);
        // A field of the wrong type, holding what serde's text would echo.
        let e = create(json!({"template":"t","name":"n","userData":"x","labels":"secret-ticket"}))
            .unwrap_err();
        assert!(!e.message.contains("secret-ticket"), "{}", e.message);
    }

    #[test]
    fn params_must_be_one_object_and_no_key_may_appear_twice() {
        let request = |line: &str| RpcRequest::parse(line.as_bytes());
        // Absent, an array (read by position otherwise), a string, null.
        for params in [
            "",
            r#","params":["t"]"#,
            r#","params":"t""#,
            r#","params":null"#,
        ] {
            let r = request(&format!(
                r#"{{"jsonrpc":"2.0","id":1,"method":"verify"{params}}}"#
            ))
            .unwrap();
            assert_eq!(
                r.params::<VerifyParams>().unwrap_err().code,
                code::INVALID_PARAMS,
                "{params}"
            );
        }
        // A key twice, at the top, in params, in labels: refused before anything is read.
        for line in [
            r#"{"jsonrpc":"2.0","id":1,"method":"delete","method":"list","params":{}}"#,
            r#"{"jsonrpc":"2.0","id":1,"id":2,"method":"list","params":{}}"#,
            r#"{"jsonrpc":"2.0","id":1,"method":"verify","params":{"template":"a","template":"b"}}"#,
            r#"{"jsonrpc":"2.0","id":1,"method":"list","params":{"labels":{"tinfirm-host":"a","tinfirm-host":"b"}}}"#,
        ] {
            let (id, e) = request(line).unwrap_err();
            assert_eq!(e.code, code::PARSE, "{line}");
            assert_eq!(id, Value::Null);
        }
    }

    #[test]
    fn a_method_name_reaches_a_message_only_as_one_of_the_wires() {
        let r = RpcRequest::parse(
            br#"{"jsonrpc":"2.0","id":1,"method":"evil\nforged log line \u001b[31m","params":[]}"#,
        )
        .unwrap();
        assert_eq!(r.method_name(), "other");
        let e = r.params::<ListParams>().unwrap_err();
        assert!(
            !e.message.contains('\n') && !e.message.contains('\u{1b}'),
            "{}",
            e.message
        );
        for method in METHODS {
            let line = format!(r#"{{"jsonrpc":"2.0","id":1,"method":"{method}","params":{{}}}}"#);
            assert_eq!(
                RpcRequest::parse(line.as_bytes()).unwrap().method_name(),
                method
            );
        }
    }

    #[test]
    fn builds_of_one_release_are_the_same_to_the_check() {
        assert_eq!(release_of("1.0.0+images.1a2b3c4d5e6f"), "1.0.0");
        assert_eq!(release_of("1.0.0-rc.2-SNAPSHOT"), "1.0.0-rc.2-SNAPSHOT");
        assert_eq!(
            release_of(&format!("{}+images.000000000000", build_id())),
            release_of(build_id())
        );
        assert_ne!(release_of("1.0.1+images.x"), release_of("1.0.0+images.x"));
    }

    #[test]
    fn names_and_labels_have_their_forms() {
        assert!(check_name("tinfirm-vm-0a1b2c3d").is_ok());
        for bad in ["", "Tinfirm", "a b", "a/b", "a\n", &"a".repeat(64)] {
            assert!(check_name(bad).is_err(), "{bad:?}");
        }
        let labels = |pairs: &[(&str, &str)]| -> BTreeMap<String, String> {
            pairs
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect()
        };
        assert!(
            check_labels(&labels(&[
                ("tinfirm-host", "6f1c-AB_x.1"),
                ("tinfirm-vm", "1")
            ]))
            .is_ok()
        );
        assert!(check_labels(&labels(&[])).is_ok());
        for bad in [
            labels(&[("Tinfirm-host", "h")]),
            labels(&[("tinfirm-host", "")]),
            labels(&[("", "h")]),
            labels(&[("tinfirm-host", "a b")]),
            labels(&[("tinfirm-host", "h\n")]),
            labels(&[("tinfirm-host", &"h".repeat(129))]),
        ] {
            assert!(check_labels(&bad).is_err(), "{bad:?}");
        }
        let many: BTreeMap<String, String> = (0..=MAX_LABELS)
            .map(|i| (format!("k{i}"), "v".to_string()))
            .collect();
        assert!(check_labels(&many).is_err());
    }
}
