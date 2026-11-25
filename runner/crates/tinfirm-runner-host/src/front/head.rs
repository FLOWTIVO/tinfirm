//! What the front half of a proxy and its run half say to each other (ADR-0112, path C,
//! part 2): a request's head, typed and bounded, its answer's head, and the run half's trait.
//! The same types serve both when they run in one process (no bridge) and when the front half
//! runs in a sandbox's front and the head goes over its wire ([`super::wire`]), so the rules
//! are the same code in both.

use std::future::Future;
use std::pin::Pin;
use std::task::{Context as TaskContext, Poll};

use http_body_util::combinators::UnsyncBoxBody;
use hyper::body::{Body, Bytes, Frame};
use hyper::header::{HeaderMap, HeaderName, HeaderValue};
use hyper::{Method, Response, StatusCode};
use serde::{Deserialize, Serialize};

use super::wire::Service;
use crate::model_proxy::ModelEndpoint;

pub type BoxError = Box<dyn std::error::Error + Send + Sync>;
pub type ProxyBody = UnsyncBoxBody<Bytes, BoxError>;

/// The headers a request may carry to the run half, and their bounds: a client's head that
/// is larger is refused with `431` by the front half.
pub const MAX_HEADERS: usize = 64;
/// The longest header name of a request.
pub const MAX_HEADER_NAME: usize = 64;
/// The longest header value of a request.
pub const MAX_HEADER_VALUE: usize = 8 * 1024;
/// All of a request's header names and values together; within a front's message.
pub const MAX_HEADER_BYTES: usize = 24 * 1024;
/// The longest query of a request (`414` above).
pub const MAX_QUERY: usize = 4 * 1024;
/// The headers of an answer that go back to the front half; more are dropped.
pub const MAX_RESPONSE_HEADERS: usize = 100;

/// A header value as its bytes: HTTP allows any byte but the control bytes (UTF-8 text among
/// them, as obs-text), so a value is not always text. On a front's wire it is a string whose
/// characters are the bytes, U+0000 to U+00FF one for one; any other character is not a
/// header value. Its length is the value's, in bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HeaderBytes(pub Vec<u8>);

impl HeaderBytes {
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }

    pub fn len(&self) -> usize {
        self.0.len()
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

impl From<String> for HeaderBytes {
    fn from(value: String) -> HeaderBytes {
        HeaderBytes(value.into_bytes())
    }
}

impl From<&str> for HeaderBytes {
    fn from(value: &str) -> HeaderBytes {
        HeaderBytes(value.as_bytes().to_vec())
    }
}

impl Serialize for HeaderBytes {
    fn serialize<S: serde::Serializer>(
        &self,
        serializer: S,
    ) -> std::result::Result<S::Ok, S::Error> {
        let text: String = self.0.iter().map(|&byte| char::from(byte)).collect();
        serializer.serialize_str(&text)
    }
}

impl<'de> Deserialize<'de> for HeaderBytes {
    fn deserialize<D: serde::Deserializer<'de>>(
        deserializer: D,
    ) -> std::result::Result<HeaderBytes, D::Error> {
        let text = String::deserialize(deserializer)?;
        text.chars()
            .map(|c| u8::try_from(u32::from(c)).ok())
            .collect::<Option<Vec<u8>>>()
            .map(HeaderBytes)
            .ok_or_else(|| serde::de::Error::custom("a header value's character above U+00FF"))
    }
}

/// How long a request's body is, as the front half read it from the client's head.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum BodyLength {
    /// No body.
    Empty,
    /// `Content-Length`: exactly this many bytes.
    Exact(u64),
    /// Chunked: the body's end says where it ends.
    Streamed,
}

/// What a request is for, as the front half mapped it from the path and the secret, which
/// never reach the run half: a model endpoint, or one of a run's routes of the git proxy and
/// of the platform tools, by the route's number. The run half takes only its own run's.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub enum Target {
    Model(ModelEndpoint),
    /// `GET <repository>/info/refs?service=git-upload-pack`.
    GitInfoRefs {
        route: u64,
    },
    /// `POST <repository>/git-upload-pack`.
    GitUploadPack {
        route: u64,
    },
    /// The run's platform tools, `POST /<secret>/mcp`.
    Mcp {
        route: u64,
    },
}

impl Target {
    /// The listener that serves it.
    pub fn service(self) -> Service {
        match self {
            Target::Model(_) => Service::Model,
            _ => Service::Git,
        }
    }
}

/// A request of the sandbox, as the front half hands it on: the endpoint it maps to, never
/// the path or the secret.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RequestHead {
    pub target: Target,
    pub method: String,
    pub query: Option<String>,
    pub headers: Vec<(String, HeaderBytes)>,
    pub length: BodyLength,
}

impl RequestHead {
    /// The head's bounds, and that each part is one that HTTP allows (no CR, LF or NUL in a
    /// value): checked by the run half in every case, and on a front's wire before.
    pub fn check(&self) -> std::result::Result<(), String> {
        if self.method.is_empty()
            || self.method.len() > 16
            || Method::from_bytes(self.method.as_bytes()).is_err()
        {
            return Err("a method that is not one".into());
        }
        if let Some(query) = &self.query {
            if query.len() > MAX_QUERY {
                return Err(format!("a query over {MAX_QUERY} bytes"));
            }
            if !query.bytes().all(|b| b.is_ascii_graphic()) {
                return Err("a query with a byte that no query has".into());
            }
        }
        if self.headers.len() > MAX_HEADERS {
            return Err(format!("more than {MAX_HEADERS} headers"));
        }
        let mut total = 0;
        for (name, value) in &self.headers {
            if name.is_empty() || name.len() > MAX_HEADER_NAME || value.len() > MAX_HEADER_VALUE {
                return Err("a header out of its bounds".into());
            }
            total += name.len() + value.len();
            if HeaderName::from_bytes(name.as_bytes()).is_err()
                || HeaderValue::from_bytes(value.as_bytes()).is_err()
            {
                return Err("a header that HTTP does not allow".into());
            }
        }
        if total > MAX_HEADER_BYTES {
            return Err(format!("headers over {MAX_HEADER_BYTES} bytes"));
        }
        Ok(())
    }
}

/// The answer's status and headers, as the run half gives them back.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ResponseHead {
    pub status: u16,
    pub headers: Vec<(String, HeaderBytes)>,
}

/// Why the front half answered a request itself, for the run's log and counts.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Declined {
    /// Not the run's secret: `404`.
    Secret,
    /// A connection upgrade (WebSocket): `501`.
    Upgrade,
    /// A path outside what the listener serves: `404`.
    Path,
    /// Another method than the endpoint's: `405`.
    Method,
    /// A head over the bounds, or not text: `400`, `414` or `431`.
    Head,
    /// A body over the listener's bound: `413`.
    TooLarge,
    /// A push from the sandbox: `403`.
    Push,
}

/// The run half: what makes a request towards the gateway or the code host, or answers it,
/// in this process or in `run` behind a front's wire. The answer comes back whole, its body
/// streaming.
pub trait Backend: Send + Sync + 'static {
    fn call(
        &self,
        head: RequestHead,
        body: ProxyBody,
    ) -> Pin<Box<dyn Future<Output = (ResponseHead, ProxyBody)> + Send>>;

    /// The front half answered a request itself.
    fn declined(&self, why: Declined);
}

/// The request body of a head that said how long it is: exactly that many bytes, or an error.
pub(crate) struct ExactBody {
    pub(crate) inner: ProxyBody,
    pub(crate) left: u64,
}

/// The body did not have the length its head declared.
#[derive(Debug, thiserror::Error)]
#[error("the request body does not have the length its head declared")]
pub(crate) struct LengthMismatch;

impl Body for ExactBody {
    type Data = Bytes;
    type Error = BoxError;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
    ) -> Poll<Option<std::result::Result<Frame<Bytes>, BoxError>>> {
        let this = self.get_mut();
        match Pin::new(&mut this.inner).poll_frame(cx) {
            Poll::Ready(Some(Ok(frame))) => {
                if let Some(data) = frame.data_ref() {
                    let len = data.len() as u64;
                    if len > this.left {
                        return Poll::Ready(Some(Err(Box::new(LengthMismatch))));
                    }
                    this.left -= len;
                }
                Poll::Ready(Some(Ok(frame)))
            }
            Poll::Ready(None) if this.left > 0 => Poll::Ready(Some(Err(Box::new(LengthMismatch)))),
            other => other,
        }
    }

    fn size_hint(&self) -> hyper::body::SizeHint {
        hyper::body::SizeHint::with_exact(self.left)
    }
}

/// `true` when the request body did not have the length its head declared.
pub(crate) fn length_mismatch(error: &(dyn std::error::Error + 'static)) -> bool {
    let mut current = Some(error);
    while let Some(e) = current {
        if e.is::<LengthMismatch>() {
            return true;
        }
        current = e.source();
    }
    false
}

/// The head of a client's request as the run half gets it, `target` mapped by the front
/// half; or the status and text of its refusal when the head is over the bounds.
pub(crate) fn head_from_parts(
    target: Target,
    parts: &hyper::http::request::Parts,
    exact: Option<u64>,
) -> Result<RequestHead, (StatusCode, &'static str)> {
    let query = parts.uri.query().map(str::to_string);
    if query.as_ref().is_some_and(|q| q.len() > MAX_QUERY) {
        return Err((StatusCode::URI_TOO_LONG, "the request's query is too long"));
    }
    const TOO_LARGE: (StatusCode, &str) = (
        StatusCode::REQUEST_HEADER_FIELDS_TOO_LARGE,
        "the request's headers are too large",
    );
    if parts.headers.len() > MAX_HEADERS {
        return Err(TOO_LARGE);
    }
    let mut headers = Vec::with_capacity(parts.headers.len());
    let mut total = 0;
    for (name, value) in &parts.headers {
        if name.as_str().len() > MAX_HEADER_NAME || value.len() > MAX_HEADER_VALUE {
            return Err(TOO_LARGE);
        }
        total += name.as_str().len() + value.len();
        if total > MAX_HEADER_BYTES {
            return Err(TOO_LARGE);
        }
        headers.push((
            name.as_str().to_string(),
            HeaderBytes(value.as_bytes().to_vec()),
        ));
    }
    let length = match exact {
        Some(0) => BodyLength::Empty,
        Some(n) => BodyLength::Exact(n),
        None => BodyLength::Streamed,
    };
    let head = RequestHead {
        target,
        method: parts.method.as_str().to_string(),
        query,
        headers,
        length,
    };
    head.check()
        .map_err(|_| (StatusCode::BAD_REQUEST, "bad request target"))?;
    Ok(head)
}

/// A head's headers as a map; `None` when one is not a header (the run half refuses it).
pub(crate) fn header_map(headers: &[(String, HeaderBytes)]) -> Option<HeaderMap> {
    let mut map = HeaderMap::new();
    for (name, value) in headers {
        let name = HeaderName::from_bytes(name.as_bytes()).ok()?;
        let value = HeaderValue::from_bytes(value.as_bytes()).ok()?;
        map.append(name, value);
    }
    Some(map)
}

/// The length a head declared: `Some(n)`, or `None` for a body that streams.
pub(crate) fn declared(length: BodyLength) -> Option<u64> {
    match length {
        BodyLength::Empty => Some(0),
        BodyLength::Exact(n) => Some(n),
        BodyLength::Streamed => None,
    }
}

/// `body` held to the length its head declared.
pub(crate) fn exact_body(body: ProxyBody, length: BodyLength) -> ProxyBody {
    use http_body_util::BodyExt;
    match declared(length) {
        Some(left) => ExactBody { inner: body, left }.boxed_unsync(),
        None => body,
    }
}

/// The front half's answer from the run half's head and body. A header that is not one is
/// dropped (the run half sends none).
pub fn response_from(head: ResponseHead, body: ProxyBody) -> Response<ProxyBody> {
    let mut response = Response::new(body);
    *response.status_mut() = StatusCode::from_u16(head.status).unwrap_or(StatusCode::BAD_GATEWAY);
    for (name, value) in head.headers {
        if let (Ok(name), Ok(value)) = (
            HeaderName::from_bytes(name.as_bytes()),
            HeaderValue::from_bytes(value.as_bytes()),
        ) {
            response.headers_mut().append(name, value);
        }
    }
    response
}

/// The run half's answer as its head and body: its first [`MAX_RESPONSE_HEADERS`] headers,
/// byte for byte; the rest are dropped, and how many is the second value.
pub fn response_head(response: Response<ProxyBody>) -> (ResponseHead, ProxyBody, usize) {
    let (mut parts, body) = response.into_parts();
    // A body whose length is known keeps it, as a server in this process would send it: the
    // front half then answers with the same framing.
    if let Some(length) = body.size_hint().exact()
        && !parts.headers.contains_key(hyper::header::CONTENT_LENGTH)
        && !parts.headers.contains_key(hyper::header::TRANSFER_ENCODING)
        && parts.status != StatusCode::NO_CONTENT
        && parts.status != StatusCode::NOT_MODIFIED
        && !parts.status.is_informational()
    {
        parts
            .headers
            .insert(hyper::header::CONTENT_LENGTH, HeaderValue::from(length));
    }
    let mut headers = Vec::new();
    let mut dropped = 0;
    for (name, value) in &parts.headers {
        if headers.len() < MAX_RESPONSE_HEADERS {
            headers.push((
                name.as_str().to_string(),
                HeaderBytes(value.as_bytes().to_vec()),
            ));
        } else {
            dropped += 1;
        }
    }
    (
        ResponseHead {
            status: parts.status.as_u16(),
            headers,
        },
        body,
        dropped,
    )
}
