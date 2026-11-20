//! The client of the Kubernetes API that the pod driver calls from inside the runner pod
//! (ADR-0115 decision 2). Part of elastic runners, under the Tinfirm Enterprise licence
//! (`ee/LICENSE`).
//!
//! It is plain HTTPS over [`crate::attachments::send_with`], as DigitalOcean's driver calls its
//! API, and adds no crate.
//!
//! - The API server is `https://$KUBERNETES_SERVICE_HOST:$KUBERNETES_SERVICE_PORT`
//!   ([`Api::in_cluster`]). The certificates of the CA file are the only roots the client
//!   trusts, and the server's certificate must name the host it dials, an IP address included.
//! - The bearer token is read from its file for every request, because the kubelet rotates it.
//!   It never appears in an error, a log line or a `Debug` output.
//! - Every call names its namespace, and has a timeout ([`DEFAULT_TIMEOUT`]) over connecting,
//!   the TLS handshake and the whole answer.
//! - The calls: create, get, list by labels and delete for pods; get for network policies and
//!   resource quotas; create and delete for Secrets. A create can be a dry run
//!   ([`DryRun::All`]). No call reads a Secret, by name or in a list: an answer would carry the
//!   Secret's data, and the driver must never be able to read a run's ticket or any other
//!   Secret. For the same reason, [`Api::create_secret`] and [`Api::delete_secret`] drop the
//!   server's answer, which carries the Secret's data, without parsing it. No call creates,
//!   lists or deletes a network policy: the chart installs the runs' policies, and the driver
//!   only reads them to check them.
//! - Objects are [`serde_json::Value`]: the driver builds its pods and Secrets as JSON, and
//!   reads a few fields of the answers.
//! - Answers are bounded: [`MAX_OBJECT_BODY`] for one object, [`MAX_LIST_BODY`] for a list. A
//!   failure is an [`ApiError`], parsed from the API's `Status` object.
//! - A call that is not a create is sent again after a 429 or a 5xx answer, at most
//!   [`MAX_ATTEMPTS`] times in all. A create is sent once: the server may have acted on it.
//! - Nothing here logs an answer's body.

use std::collections::BTreeMap;
use std::fmt;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, bail};
use http_body_util::{BodyExt, Full, LengthLimitError, Limited};
use hyper::body::Bytes;
use hyper::header::HeaderValue;
use hyper::{Method, Request, StatusCode, header};
use serde::Deserialize;
use serde_json::Value;
use tinfirm_protocol::Secret;
use tokio::io::AsyncReadExt;

/// The CA file of a pod's ServiceAccount, where the kubelet mounts it.
pub const DEFAULT_CA_FILE: &str = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
/// The token file of a pod's ServiceAccount, where the kubelet mounts it.
pub const DEFAULT_TOKEN_FILE: &str = "/var/run/secrets/kubernetes.io/serviceaccount/token";
/// The time one attempt of a call may take, from connecting to the answer's last byte.
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);
/// The largest answer for one object. etcd refuses a request over 1.5 MiB by default, so no
/// stored object is larger; the rest leaves room for the fields the server adds.
pub const MAX_OBJECT_BODY: usize = 2 * 1024 * 1024;
/// The largest answer for a list. The largest list is that of a host's pods, which the
/// namespace's ResourceQuota keeps to tens or hundreds; a run pod with its status and managed
/// fields is about 10 KiB, so this holds some 1,500.
pub const MAX_LIST_BODY: usize = 16 * 1024 * 1024;
/// How many times a call that is not a create is sent, at most, after a 429 or a 5xx answer.
pub const MAX_ATTEMPTS: u32 = 3;
/// The longest wait for a `Retry-After` that the client honours; a longer one is cut to it.
const MAX_RETRY_AFTER: Duration = Duration::from_secs(10);
/// The wait before attempt `n + 1` without a `Retry-After`: `n` times this.
const BACKOFF: Duration = Duration::from_millis(500);
/// A projected ServiceAccount token is about 1 KiB.
const MAX_TOKEN_FILE: u64 = 64 * 1024;
/// A CA bundle holds a few certificates.
const MAX_CA_FILE: u64 = 1024 * 1024;
/// The longest message of a `Status` or of one of its causes that an error keeps, in
/// characters.
const MAX_MESSAGE: usize = 512;
/// The most causes of an `Invalid` status that an error keeps.
const MAX_CAUSES: usize = 16;
const USER_AGENT: &str = "tinfirm-runner-host vm-driver pod";

/// Whether a create is a dry run: the server runs admission, and stores nothing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DryRun {
    /// A real create.
    No,
    /// `?dryRun=All`.
    All,
}

/// A call that failed: the request (method, path and query) and why.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiError {
    pub call: String,
    pub failure: Failure,
}

impl ApiError {
    pub fn is_not_found(&self) -> bool {
        matches!(self.failure, Failure::NotFound(_))
    }

    pub fn is_already_exists(&self) -> bool {
        matches!(self.failure, Failure::AlreadyExists(_))
    }
}

impl fmt::Display for ApiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.call, self.failure)
    }
}

impl std::error::Error for ApiError {}

/// Why a call failed. The messages come from the server, cut to [`MAX_MESSAGE`] characters,
/// with control characters replaced and the token, should the server echo it, removed.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum Failure {
    /// A `Status` with reason `NotFound`.
    #[error("not found: {0}")]
    NotFound(String),
    /// A `Status` with reason `AlreadyExists`.
    #[error("already exists: {0}")]
    AlreadyExists(String),
    /// A `Status` with reason `Forbidden`: RBAC, or an admission policy that refused.
    #[error("forbidden: {0}")]
    Forbidden(String),
    /// A `Status` with reason `Invalid`, and its causes (at most [`MAX_CAUSES`]).
    #[error("invalid: {message}")]
    Invalid { message: String, causes: Vec<Cause> },
    /// Any other `Status`.
    #[error("HTTP {code} {reason}: {message}")]
    Status {
        code: u16,
        reason: String,
        message: String,
    },
    /// An answer that is not a `Status` (a proxy's page, say). Its body is not kept.
    #[error("HTTP {code}, and the answer is not a Status")]
    Http { code: u16 },
    /// A successful answer that is not the JSON object it should be.
    #[error("the answer is not a JSON object of the expected shape")]
    Malformed,
    /// An answer over its limit, refused before it was read to its end.
    #[error("the answer is larger than {limit} bytes")]
    TooLarge { limit: usize },
    /// No complete answer within the timeout.
    #[error("no answer within {0:?}")]
    Timeout(Duration),
    /// The connection or TLS failed.
    #[error("{0}")]
    Transport(String),
    /// The request was not sent: an argument is not valid, or the token cannot be read.
    #[error("not sent: {0}")]
    NotSent(String),
}

/// One cause of an `Invalid` status.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Cause {
    pub reason: String,
    pub field: String,
    pub message: String,
}

/// The resources the client knows.
#[derive(Debug, Clone, Copy)]
enum Kind {
    Pods,
    Secrets,
    NetworkPolicies,
    ResourceQuotas,
}

impl Kind {
    fn collection(self, namespace: &str) -> String {
        let (group, plural) = match self {
            Kind::Pods => ("/api/v1", "pods"),
            Kind::Secrets => ("/api/v1", "secrets"),
            Kind::NetworkPolicies => ("/apis/networking.k8s.io/v1", "networkpolicies"),
            Kind::ResourceQuotas => ("/api/v1", "resourcequotas"),
        };
        format!("{group}/namespaces/{namespace}/{plural}")
    }
}

/// The client: the API server, the TLS configuration with the CA's roots, and the token's
/// file.
pub struct Api {
    server: url::Url,
    tls: Arc<rustls::ClientConfig>,
    token_file: PathBuf,
    timeout: Duration,
}

impl fmt::Debug for Api {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Api")
            .field("server", &self.server.as_str())
            .field("token_file", &self.token_file)
            .field("timeout", &self.timeout)
            .finish_non_exhaustive()
    }
}

impl Api {
    /// The client of the cluster this pod runs in: the server from `KUBERNETES_SERVICE_HOST`
    /// and `KUBERNETES_SERVICE_PORT`, with the CA and the token from these files
    /// ([`DEFAULT_CA_FILE`] and [`DEFAULT_TOKEN_FILE`] where the kubelet mounts them).
    pub fn in_cluster(ca_file: &Path, token_file: &Path) -> anyhow::Result<Api> {
        let host = std::env::var("KUBERNETES_SERVICE_HOST")
            .context("KUBERNETES_SERVICE_HOST is not set: not in a pod")?;
        let port = std::env::var("KUBERNETES_SERVICE_PORT")
            .context("KUBERNETES_SERVICE_PORT is not set: not in a pod")?;
        Api::new(server_url(&host, &port)?, ca_file, token_file)
    }

    /// The client of the API server at `server` (`https`, no path), which trusts only the
    /// certificates of `ca_file` (PEM). The token file is read for each request, not here.
    pub fn new(server: url::Url, ca_file: &Path, token_file: &Path) -> anyhow::Result<Api> {
        if server.scheme() != "https"
            || server.host().is_none()
            || server.path() != "/"
            || server.query().is_some()
            || !server.username().is_empty()
            || server.password().is_some()
        {
            bail!("the API server must be an https URL with a host and nothing else");
        }
        Ok(Api {
            server,
            tls: tls_config(ca_file)?,
            token_file: token_file.to_path_buf(),
            timeout: DEFAULT_TIMEOUT,
        })
    }

    /// The same client, with another time for one attempt of a call.
    pub fn with_timeout(mut self, timeout: Duration) -> Api {
        self.timeout = timeout;
        self
    }

    pub fn server(&self) -> &url::Url {
        &self.server
    }

    /// Creates a pod; the server's pod (with its `metadata.uid`).
    pub async fn create_pod(
        &self,
        namespace: &str,
        pod: &Value,
        dry_run: DryRun,
    ) -> Result<Value, ApiError> {
        self.create(Kind::Pods, namespace, pod, dry_run).await
    }

    pub async fn get_pod(&self, namespace: &str, name: &str) -> Result<Value, ApiError> {
        self.get(Kind::Pods, namespace, name).await
    }

    /// The pods that carry every one of `labels` (at least one).
    pub async fn list_pods(
        &self,
        namespace: &str,
        labels: &BTreeMap<String, String>,
    ) -> Result<Vec<Value>, ApiError> {
        self.list(Kind::Pods, namespace, labels).await
    }

    /// Deletes a pod, with `gracePeriodSeconds` when given (else the pod's own).
    pub async fn delete_pod(
        &self,
        namespace: &str,
        name: &str,
        grace_period_seconds: Option<u32>,
    ) -> Result<(), ApiError> {
        self.delete(Kind::Pods, namespace, name, grace_period_seconds)
            .await
    }

    /// Creates a Secret. The server's answer, which carries the Secret's data, is dropped
    /// without being parsed. There is no call that gets or lists Secrets (the module's head
    /// says why).
    pub async fn create_secret(
        &self,
        namespace: &str,
        secret: &Value,
        dry_run: DryRun,
    ) -> Result<(), ApiError> {
        let collection = self.collection(Kind::Secrets, namespace)?;
        let query = dry_run_query(dry_run);
        self.call(
            Method::POST,
            &collection,
            &query,
            Some(secret),
            MAX_OBJECT_BODY,
        )
        .await
        .map(drop)
    }

    /// Deletes a Secret. The server's answer, the deleted Secret with its data, is dropped
    /// without being parsed.
    pub async fn delete_secret(&self, namespace: &str, name: &str) -> Result<(), ApiError> {
        self.delete(Kind::Secrets, namespace, name, None).await
    }

    pub async fn get_network_policy(&self, namespace: &str, name: &str) -> Result<Value, ApiError> {
        self.get(Kind::NetworkPolicies, namespace, name).await
    }

    pub async fn get_resource_quota(&self, namespace: &str, name: &str) -> Result<Value, ApiError> {
        self.get(Kind::ResourceQuotas, namespace, name).await
    }

    async fn create(
        &self,
        kind: Kind,
        namespace: &str,
        object: &Value,
        dry_run: DryRun,
    ) -> Result<Value, ApiError> {
        let collection = self.collection(kind, namespace)?;
        let query = dry_run_query(dry_run);
        let (call, bytes) = self
            .call(
                Method::POST,
                &collection,
                &query,
                Some(object),
                MAX_OBJECT_BODY,
            )
            .await?;
        object_of(call, &bytes)
    }

    async fn get(&self, kind: Kind, namespace: &str, name: &str) -> Result<Value, ApiError> {
        let path = self.object(kind, namespace, name)?;
        let (call, bytes) = self
            .call(Method::GET, &path, &[], None, MAX_OBJECT_BODY)
            .await?;
        object_of(call, &bytes)
    }

    async fn list(
        &self,
        kind: Kind,
        namespace: &str,
        labels: &BTreeMap<String, String>,
    ) -> Result<Vec<Value>, ApiError> {
        let collection = self.collection(kind, namespace)?;
        let selector = label_selector(labels).map_err(|e| not_sent(&collection, e))?;
        let (call, bytes) = self
            .call(
                Method::GET,
                &collection,
                &[("labelSelector", selector)],
                None,
                MAX_LIST_BODY,
            )
            .await?;
        match object_of(call.clone(), &bytes)?
            .get_mut("items")
            .map(Value::take)
        {
            Some(Value::Array(items)) => Ok(items),
            _ => Err(ApiError {
                call,
                failure: Failure::Malformed,
            }),
        }
    }

    async fn delete(
        &self,
        kind: Kind,
        namespace: &str,
        name: &str,
        grace_period_seconds: Option<u32>,
    ) -> Result<(), ApiError> {
        let path = self.object(kind, namespace, name)?;
        let query: Vec<_> = grace_period_seconds
            .map(|s| ("gracePeriodSeconds", s.to_string()))
            .into_iter()
            .collect();
        self.call(Method::DELETE, &path, &query, None, MAX_OBJECT_BODY)
            .await
            .map(drop)
    }

    fn collection(&self, kind: Kind, namespace: &str) -> Result<String, ApiError> {
        let path = kind.collection(namespace);
        if !dns_label(namespace) {
            return Err(not_sent(&path, "the namespace is not a DNS label"));
        }
        Ok(path)
    }

    fn object(&self, kind: Kind, namespace: &str, name: &str) -> Result<String, ApiError> {
        let path = format!("{}/{name}", self.collection(kind, namespace)?);
        if !dns_subdomain(name) {
            return Err(not_sent(&path, "the name is not a DNS subdomain"));
        }
        Ok(path)
    }

    /// One call: sent again after a 429 or a 5xx answer unless it is a create; the call's
    /// description and the body of its 2xx answer.
    async fn call(
        &self,
        method: Method,
        path: &str,
        query: &[(&str, String)],
        body: Option<&Value>,
        limit: usize,
    ) -> Result<(String, Bytes), ApiError> {
        let mut url = self
            .server
            .join(path)
            .map_err(|_| not_sent(path, "not a path"))?;
        if !query.is_empty() {
            let mut pairs = url.query_pairs_mut();
            for (key, value) in query {
                pairs.append_pair(key, value);
            }
        }
        let call = format!("{method} {}", crate::attachments::path_and_query(&url));
        let fail = |failure| ApiError {
            call: call.clone(),
            failure,
        };
        let body = Bytes::from(
            body.map(|b| serde_json::to_vec(b).expect("JSON serializes"))
                .unwrap_or_default(),
        );
        let create = method == Method::POST;
        let mut attempt = 0;
        loop {
            attempt += 1;
            let token = self.token().await.map_err(|e| fail(Failure::NotSent(e)))?;
            let (status, retry_after, bytes) = tokio::time::timeout(
                self.timeout,
                self.attempt(&url, &method, &token, body.clone(), limit),
            )
            .await
            .map_err(|_| fail(Failure::Timeout(self.timeout)))?
            .map_err(fail)?;
            if status.is_success() {
                return Ok((call, bytes));
            }
            let again = !create
                && (status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error())
                && attempt < MAX_ATTEMPTS;
            if again {
                let wait = retry_after.map_or(BACKOFF * attempt, |w| w.min(MAX_RETRY_AFTER));
                tracing::debug!(call = %call, status = status.as_u16(), "the Kubernetes API asks to try again");
                tokio::time::sleep(wait).await;
                continue;
            }
            return Err(fail(failure_of(status, &bytes, &token)));
        }
    }

    /// One request: the answer's status, its `Retry-After` in seconds, and its body.
    async fn attempt(
        &self,
        url: &url::Url,
        method: &Method,
        token: &Secret,
        body: Bytes,
        limit: usize,
    ) -> Result<(StatusCode, Option<Duration>, Bytes), Failure> {
        let mut authorization = HeaderValue::from_str(&format!("Bearer {}", token.expose()))
            .map_err(|_| Failure::NotSent(self.bad_token()))?;
        authorization.set_sensitive(true);
        let mut request = Request::builder()
            .method(method.clone())
            .uri(crate::attachments::path_and_query(url))
            .header(header::HOST, crate::attachments::authority(url))
            .header(header::AUTHORIZATION, authorization)
            .header(header::USER_AGENT, USER_AGENT)
            .header(header::ACCEPT, "application/json");
        if !body.is_empty() {
            request = request.header(header::CONTENT_TYPE, "application/json");
        }
        let request = request
            .body(Full::new(body))
            .map_err(|_| Failure::NotSent("the request cannot be built".into()))?;
        // The caller's timeout covers the whole attempt; the one given here is longer, so that
        // the caller's ends the attempt first and reports it as a timeout.
        let response = crate::attachments::send_with(
            url,
            request,
            self.timeout + Duration::from_secs(1),
            Some(self.tls.clone()),
        )
        .await
        .map_err(|e| Failure::Transport(format!("{e:#}")))?;
        let status = response.status();
        let retry_after = response
            .headers()
            .get(header::RETRY_AFTER)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.trim().parse::<u64>().ok())
            .map(Duration::from_secs);
        let declared = response
            .headers()
            .get(header::CONTENT_LENGTH)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<u64>().ok());
        if declared.is_some_and(|length| length > limit as u64) {
            return Err(Failure::TooLarge { limit });
        }
        let bytes = Limited::new(response.into_body(), limit)
            .collect()
            .await
            .map_err(|e| {
                if e.downcast_ref::<LengthLimitError>().is_some() {
                    Failure::TooLarge { limit }
                } else {
                    Failure::Transport(format!("reading the answer: {e}"))
                }
            })?
            .to_bytes();
        Ok((status, retry_after, bytes))
    }

    /// The token, read from its file now: one word of visible ASCII.
    async fn token(&self) -> Result<Secret, String> {
        let file = tokio::fs::File::open(&self.token_file).await.map_err(|e| {
            format!(
                "cannot read the token file {}: {e}",
                self.token_file.display()
            )
        })?;
        let mut bytes = Vec::new();
        file.take(MAX_TOKEN_FILE + 1)
            .read_to_end(&mut bytes)
            .await
            .map_err(|e| {
                format!(
                    "cannot read the token file {}: {e}",
                    self.token_file.display()
                )
            })?;
        if bytes.len() as u64 > MAX_TOKEN_FILE {
            return Err(self.bad_token());
        }
        let text = String::from_utf8(bytes).map_err(|_| self.bad_token())?;
        let token = text.trim();
        if token.is_empty() || !token.chars().all(|c| c.is_ascii_graphic()) {
            return Err(self.bad_token());
        }
        Ok(Secret::new(token))
    }

    fn bad_token(&self) -> String {
        format!(
            "the token file {} does not hold one token",
            self.token_file.display()
        )
    }
}

/// The API server's URL from the service variables of a pod. The host is usually an IP
/// address; an IPv6 address goes in brackets.
fn server_url(host: &str, port: &str) -> anyhow::Result<url::Url> {
    let port = port
        .trim()
        .parse::<u16>()
        .ok()
        .filter(|p| *p != 0)
        .context("KUBERNETES_SERVICE_PORT is not a port")?;
    let host = host.trim();
    let host = match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => ip.to_string(),
        Ok(IpAddr::V6(ip)) => format!("[{ip}]"),
        Err(_) if dns_subdomain(host) => host.to_string(),
        Err(_) => bail!("KUBERNETES_SERVICE_HOST is neither an IP address nor a DNS name"),
    };
    url::Url::parse(&format!("https://{host}:{port}/")).context("not a URL of the API server")
}

/// TLS that trusts the certificates of `ca_file` (PEM) and no others.
fn tls_config(ca_file: &Path) -> anyhow::Result<Arc<rustls::ClientConfig>> {
    use rustls::pki_types::CertificateDer;
    use rustls::pki_types::pem::PemObject;
    use std::io::Read;
    let mut pem = Vec::new();
    std::fs::File::open(ca_file)
        .and_then(|file| file.take(MAX_CA_FILE + 1).read_to_end(&mut pem))
        .with_context(|| format!("cannot read the CA file {}", ca_file.display()))?;
    if pem.len() as u64 > MAX_CA_FILE {
        bail!(
            "the CA file {} is over {MAX_CA_FILE} bytes",
            ca_file.display()
        );
    }
    let mut roots = rustls::RootCertStore::empty();
    for cert in CertificateDer::pem_slice_iter(&pem) {
        let cert = cert.with_context(|| format!("the CA file {} is not PEM", ca_file.display()))?;
        roots.add(cert).with_context(|| {
            format!("the CA file {} holds a bad certificate", ca_file.display())
        })?;
    }
    if roots.is_empty() {
        bail!("the CA file {} holds no certificate", ca_file.display());
    }
    let mut config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .expect("the ring provider supports the default TLS versions")
    .with_root_certificates(roots)
    .with_no_client_auth();
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Arc::new(config))
}

fn dry_run_query(dry_run: DryRun) -> Vec<(&'static str, String)> {
    match dry_run {
        DryRun::No => Vec::new(),
        DryRun::All => vec![("dryRun", "All".to_string())],
    }
}

fn not_sent(path: &str, why: impl Into<String>) -> ApiError {
    ApiError {
        call: path.to_string(),
        failure: Failure::NotSent(why.into()),
    }
}

/// The JSON object of a 2xx answer.
fn object_of(call: String, bytes: &[u8]) -> Result<Value, ApiError> {
    match serde_json::from_slice::<Value>(bytes) {
        Ok(value) if value.is_object() => Ok(value),
        _ => Err(ApiError {
            call,
            failure: Failure::Malformed,
        }),
    }
}

/// The API's `Status` object, in the fields the client reads.
#[derive(Deserialize, Default)]
#[serde(default)]
struct Status {
    kind: String,
    reason: String,
    message: String,
    code: Option<u16>,
    details: Option<StatusDetails>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct StatusDetails {
    causes: Vec<StatusCause>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct StatusCause {
    reason: String,
    field: String,
    message: String,
}

/// The failure of an answer that is not 2xx.
fn failure_of(status: StatusCode, bytes: &[u8], token: &Secret) -> Failure {
    let parsed = serde_json::from_slice::<Status>(bytes)
        .ok()
        .filter(|s| s.kind == "Status");
    let Some(parsed) = parsed else {
        return Failure::Http {
            code: status.as_u16(),
        };
    };
    let message = bounded(&parsed.message, token);
    match parsed.reason.as_str() {
        "NotFound" => Failure::NotFound(message),
        "AlreadyExists" => Failure::AlreadyExists(message),
        "Forbidden" => Failure::Forbidden(message),
        "Invalid" => Failure::Invalid {
            message,
            causes: parsed
                .details
                .map(|d| d.causes)
                .unwrap_or_default()
                .iter()
                .take(MAX_CAUSES)
                .map(|c| Cause {
                    reason: bounded(&c.reason, token),
                    field: bounded(&c.field, token),
                    message: bounded(&c.message, token),
                })
                .collect(),
        },
        reason => Failure::Status {
            code: parsed.code.unwrap_or(status.as_u16()),
            reason: bounded(reason, token),
            message,
        },
    }
}

/// A text from the server as an error keeps it: without the token, without control
/// characters, and at most [`MAX_MESSAGE`] characters.
fn bounded(text: &str, token: &Secret) -> String {
    let text = if token.expose().is_empty() {
        text.to_string()
    } else {
        text.replace(token.expose(), "[REDACTED]")
    };
    let mut out: String = text
        .chars()
        .take(MAX_MESSAGE)
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect();
    if text.chars().count() > MAX_MESSAGE {
        out.push('…');
    }
    out
}

/// A label selector of equality requirements, one per label. Keys and values must follow
/// Kubernetes' label syntax, so that no character of theirs changes the selector.
fn label_selector(labels: &BTreeMap<String, String>) -> Result<String, String> {
    if labels.is_empty() {
        return Err("a list needs at least one label".into());
    }
    let mut parts = Vec::new();
    for (key, value) in labels {
        if !label_key(key) {
            return Err(format!("`{key}` is not a label key"));
        }
        if !label_value(value) {
            return Err(format!("the value of label `{key}` is not a label value"));
        }
        parts.push(format!("{key}={value}"));
    }
    Ok(parts.join(","))
}

/// A label's key: a name, after an optional prefix that is a DNS subdomain and a `/`.
pub(super) fn label_key(key: &str) -> bool {
    match key.split_once('/') {
        Some((prefix, name)) => dns_subdomain(prefix) && label_name(name),
        None => label_name(key),
    }
}

/// A label's value: empty, or as a name.
pub(super) fn label_value(value: &str) -> bool {
    value.is_empty() || label_name(value)
}

/// A label's name or value: at most 63 characters of letters, digits, `-`, `_` and `.`,
/// starting and ending with a letter or a digit.
pub(super) fn label_name(text: &str) -> bool {
    let bytes = text.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 63
        && bytes[0].is_ascii_alphanumeric()
        && bytes[bytes.len() - 1].is_ascii_alphanumeric()
        && bytes
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
}

/// An RFC 1123 label: at most 63 characters of lower-case letters, digits and `-`, starting
/// and ending with a letter or a digit. A namespace's name.
pub(super) fn dns_label(text: &str) -> bool {
    let bytes = text.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 63
        && bytes[0].is_ascii_alphanumeric()
        && bytes[bytes.len() - 1].is_ascii_alphanumeric()
        && bytes
            .iter()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || *b == b'-')
}

/// An RFC 1123 subdomain: labels joined by `.`, at most 253 characters. An object's name.
pub(super) fn dns_subdomain(text: &str) -> bool {
    !text.is_empty() && text.len() <= 253 && text.split('.').all(dns_label)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::convert::Infallible;
    use std::net::SocketAddr;
    use std::sync::Mutex;
    use std::time::Instant;

    use http_body_util::StreamBody;
    use http_body_util::combinators::BoxBody;
    use hyper::body::{Frame, Incoming};
    use hyper_util::rt::TokioIo;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer};
    use serde_json::json;

    const TOKEN: &str = "eyJTEST-ONLY.first-fake-token";
    const NEW_TOKEN: &str = "eyJTEST-ONLY.second-fake-token";
    const NS: &str = "tinfirm-runs";

    /// A request the fake API got.
    #[derive(Debug, Clone)]
    struct Seen {
        method: String,
        target: String,
        authorization: String,
        content_type: String,
        body: Value,
    }

    /// What the fake API answers.
    enum Reply {
        Json(u16, Value),
        /// With a `Retry-After` of so many seconds.
        RetryAfter(u16, u64, Value),
        Text(u16, String),
        /// Bytes without end, with no length.
        Endless,
        /// A body of a known length.
        Sized(usize),
        /// An answer after this long.
        Late(Duration),
    }

    type Handler = Arc<dyn Fn(&Seen, usize) -> Reply + Send + Sync>;

    struct Fake {
        addr: SocketAddr,
        seen: Arc<Mutex<Vec<Seen>>>,
    }

    impl Fake {
        fn seen(&self) -> Vec<Seen> {
            self.seen.lock().unwrap().clone()
        }

        fn url(&self) -> url::Url {
            url::Url::parse(&format!("https://{}/", self.addr)).unwrap()
        }
    }

    /// A CA: its certificate in PEM and its issuer.
    fn ca(name: &str) -> (String, rcgen::Issuer<'static, rcgen::KeyPair>) {
        let mut params = rcgen::CertificateParams::new(Vec::<String>::new()).unwrap();
        params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
        params
            .distinguished_name
            .push(rcgen::DnType::CommonName, name);
        params.key_usages = vec![
            rcgen::KeyUsagePurpose::KeyCertSign,
            rcgen::KeyUsagePurpose::DigitalSignature,
        ];
        let key = rcgen::KeyPair::generate().unwrap();
        let cert = params.self_signed(&key).unwrap();
        (pem(cert.der()), rcgen::Issuer::new(params, key))
    }

    fn pem(der: &[u8]) -> String {
        use base64::Engine as _;
        let text = base64::engine::general_purpose::STANDARD.encode(der);
        let mut out = String::from("-----BEGIN CERTIFICATE-----\n");
        for line in text.as_bytes().chunks(64) {
            out.push_str(std::str::from_utf8(line).unwrap());
            out.push('\n');
        }
        out.push_str("-----END CERTIFICATE-----\n");
        out
    }

    /// A server certificate for `names` (IP addresses or DNS names), from `issuer`.
    fn leaf(
        issuer: &rcgen::Issuer<'static, rcgen::KeyPair>,
        names: &[&str],
    ) -> (CertificateDer<'static>, PrivateKeyDer<'static>) {
        let mut params =
            rcgen::CertificateParams::new(names.iter().map(|n| n.to_string()).collect::<Vec<_>>())
                .unwrap();
        params.extended_key_usages = vec![rcgen::ExtendedKeyUsagePurpose::ServerAuth];
        let key = rcgen::KeyPair::generate().unwrap();
        let cert = params.signed_by(&key, issuer).unwrap();
        (
            cert.der().clone(),
            PrivateKeyDer::Pkcs8(key.serialize_der().into()),
        )
    }

    static CHUNK: [u8; 65536] = [b' '; 65536];

    fn respond(reply: Reply) -> hyper::Response<BoxBody<Bytes, Infallible>> {
        let full = |status: u16, bytes: Vec<u8>| {
            hyper::Response::builder()
                .status(status)
                .header(header::CONTENT_TYPE, "application/json")
                .body(Full::new(Bytes::from(bytes)).boxed())
                .unwrap()
        };
        match reply {
            Reply::Json(status, value) => full(status, serde_json::to_vec(&value).unwrap()),
            Reply::RetryAfter(status, seconds, value) => {
                let mut response = full(status, serde_json::to_vec(&value).unwrap());
                response
                    .headers_mut()
                    .insert(header::RETRY_AFTER, seconds.into());
                response
            }
            Reply::Text(status, text) => full(status, text.into_bytes()),
            Reply::Sized(length) => full(200, vec![b' '; length]),
            Reply::Endless => hyper::Response::builder()
                .status(200)
                .body(
                    StreamBody::new(futures::stream::repeat_with(|| {
                        Ok::<_, Infallible>(Frame::data(Bytes::from_static(&CHUNK)))
                    }))
                    .boxed(),
                )
                .unwrap(),
            Reply::Late(_) => unreachable!("answered by the service"),
        }
    }

    /// Serves the fake API over TLS on loopback with this certificate.
    async fn serve(
        cert: CertificateDer<'static>,
        key: PrivateKeyDer<'static>,
        handler: Handler,
    ) -> Fake {
        let config = rustls::ServerConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)
        .unwrap();
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(config));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let recorded = seen.clone();
        tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                let acceptor = acceptor.clone();
                let handler = handler.clone();
                let seen = recorded.clone();
                tokio::spawn(async move {
                    let Ok(tls) = acceptor.accept(stream).await else {
                        return;
                    };
                    let service = hyper::service::service_fn(move |req: Request<Incoming>| {
                        let handler = handler.clone();
                        let seen = seen.clone();
                        async move {
                            let text = |name: header::HeaderName| {
                                req.headers()
                                    .get(name)
                                    .and_then(|v: &HeaderValue| v.to_str().ok())
                                    .unwrap_or_default()
                                    .to_string()
                            };
                            let authorization = text(header::AUTHORIZATION);
                            let content_type = text(header::CONTENT_TYPE);
                            let method = req.method().to_string();
                            let target = req.uri().to_string();
                            let bytes = req.into_body().collect().await.unwrap().to_bytes();
                            let request = Seen {
                                method,
                                target,
                                authorization,
                                content_type,
                                body: serde_json::from_slice(&bytes).unwrap_or(Value::Null),
                            };
                            let index = {
                                let mut seen = seen.lock().unwrap();
                                seen.push(request.clone());
                                seen.len() - 1
                            };
                            let reply = handler(&request, index);
                            if let Reply::Late(after) = reply {
                                tokio::time::sleep(after).await;
                                return Ok::<_, Infallible>(respond(Reply::Json(200, json!({}))));
                            }
                            Ok::<_, Infallible>(respond(reply))
                        }
                    });
                    let _ = hyper::server::conn::http1::Builder::new()
                        .serve_connection(TokioIo::new(tls), service)
                        .await;
                });
            }
        });
        Fake { addr, seen }
    }

    /// A CA, a fake API with a certificate from it for 127.0.0.1, and a client of it.
    struct Setup {
        _dir: tempfile::TempDir,
        token_file: PathBuf,
        fake: Fake,
        api: Api,
    }

    async fn setup(handler: Handler) -> Setup {
        let dir = tempfile::tempdir().unwrap();
        let (ca_pem, issuer) = ca("test cluster CA");
        let ca_file = dir.path().join("ca.crt");
        std::fs::write(&ca_file, ca_pem).unwrap();
        let token_file = dir.path().join("token");
        std::fs::write(&token_file, format!("{TOKEN}\n")).unwrap();
        let (cert, key) = leaf(&issuer, &["127.0.0.1"]);
        let fake = serve(cert, key, handler).await;
        let api = Api::new(fake.url(), &ca_file, &token_file)
            .unwrap()
            .with_timeout(Duration::from_secs(10));
        Setup {
            _dir: dir,
            token_file,
            fake,
            api,
        }
    }

    fn status(code: u16, reason: &str, message: &str) -> Value {
        json!({"kind": "Status", "apiVersion": "v1", "metadata": {}, "status": "Failure",
               "message": message, "reason": reason, "code": code})
    }

    fn labels(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    /// Answers every call as the API would when it succeeds.
    fn ok_handler() -> Handler {
        Arc::new(|seen: &Seen, _: usize| match seen.method.as_str() {
            "GET" if seen.target.contains("labelSelector") => Reply::Json(
                200,
                json!({"kind": "List", "items": [{"metadata": {"name": "a"}}]}),
            ),
            "POST" => {
                let mut object = seen.body.clone();
                object["metadata"]["uid"] = json!("0a0b0c0d-0000-4000-8000-000000000001");
                Reply::Json(201, object)
            }
            _ => Reply::Json(200, json!({"metadata": {"name": "a"}})),
        })
    }

    #[tokio::test]
    async fn each_call_has_its_path_and_query() {
        let s = setup(ok_handler()).await;
        let pod = json!({"apiVersion": "v1", "kind": "Pod", "metadata": {"name": "tinfirm-vm-1"}});
        let secret =
            json!({"apiVersion": "v1", "kind": "Secret", "metadata": {"name": "tinfirm-ticket-1"}});
        let selector = labels(&[("tinfirm.example/host", "h-1"), ("tinfirm.example/vm", "")]);
        let api = &s.api;
        let created = api.create_pod(NS, &pod, DryRun::No).await.unwrap();
        assert_eq!(
            created["metadata"]["uid"],
            "0a0b0c0d-0000-4000-8000-000000000001"
        );
        api.create_pod(NS, &pod, DryRun::All).await.unwrap();
        api.get_pod(NS, "tinfirm-vm-1").await.unwrap();
        let pods = api.list_pods(NS, &selector).await.unwrap();
        assert_eq!(pods, vec![json!({"metadata": {"name": "a"}})]);
        api.delete_pod(NS, "tinfirm-vm-1", Some(5)).await.unwrap();
        api.delete_pod(NS, "tinfirm-vm-1", None).await.unwrap();
        api.create_secret(NS, &secret, DryRun::No).await.unwrap();
        api.create_secret(NS, &secret, DryRun::All).await.unwrap();
        api.delete_secret(NS, "tinfirm-ticket-1").await.unwrap();
        api.get_network_policy(NS, "p").await.unwrap();
        api.get_resource_quota(NS, "tinfirm-runs").await.unwrap();

        let pods = "/api/v1/namespaces/tinfirm-runs/pods";
        let secrets = "/api/v1/namespaces/tinfirm-runs/secrets";
        let policies = "/apis/networking.k8s.io/v1/namespaces/tinfirm-runs/networkpolicies";
        let encoded = "labelSelector=tinfirm.example%2Fhost%3Dh-1%2Ctinfirm.example%2Fvm%3D";
        let expected = [
            ("POST", pods.to_string()),
            ("POST", format!("{pods}?dryRun=All")),
            ("GET", format!("{pods}/tinfirm-vm-1")),
            ("GET", format!("{pods}?{encoded}")),
            (
                "DELETE",
                format!("{pods}/tinfirm-vm-1?gracePeriodSeconds=5"),
            ),
            ("DELETE", format!("{pods}/tinfirm-vm-1")),
            ("POST", secrets.to_string()),
            ("POST", format!("{secrets}?dryRun=All")),
            ("DELETE", format!("{secrets}/tinfirm-ticket-1")),
            ("GET", format!("{policies}/p")),
            (
                "GET",
                "/api/v1/namespaces/tinfirm-runs/resourcequotas/tinfirm-runs".to_string(),
            ),
        ];
        let seen = s.fake.seen();
        let got: Vec<(&str, String)> = seen
            .iter()
            .map(|r| (r.method.as_str(), r.target.clone()))
            .collect();
        assert_eq!(got, expected);
        for request in &seen {
            assert_eq!(request.authorization, format!("Bearer {TOKEN}"));
            if request.method == "POST" {
                assert_eq!(request.content_type, "application/json");
                assert!(request.body.is_object(), "{request:?}");
            } else {
                assert!(request.body.is_null(), "{request:?}");
            }
        }
        assert_eq!(seen[0].body, pod);
        assert_eq!(seen[6].body, secret);
    }

    #[tokio::test]
    async fn only_a_dry_run_create_says_dry_run() {
        let s = setup(ok_handler()).await;
        let pod = json!({"metadata": {"name": "tinfirm-vm-1"}});
        s.api.create_pod(NS, &pod, DryRun::No).await.unwrap();
        s.api.create_pod(NS, &pod, DryRun::All).await.unwrap();
        let seen = s.fake.seen();
        assert!(!seen[0].target.contains("dryRun"), "{:?}", seen[0]);
        assert!(seen[1].target.ends_with("?dryRun=All"), "{:?}", seen[1]);
    }

    #[tokio::test]
    async fn the_token_is_read_again_for_every_request() {
        let s = setup(ok_handler()).await;
        s.api.get_pod(NS, "a").await.unwrap();
        std::fs::write(&s.token_file, NEW_TOKEN).unwrap();
        s.api.get_pod(NS, "a").await.unwrap();
        let seen = s.fake.seen();
        assert_eq!(seen[0].authorization, format!("Bearer {TOKEN}"));
        assert_eq!(seen[1].authorization, format!("Bearer {NEW_TOKEN}"));
        // A file that holds no token: nothing is sent.
        for bad in ["", "two words", "line\nbreak"] {
            std::fs::write(&s.token_file, bad).unwrap();
            let error = s.api.get_pod(NS, "a").await.unwrap_err();
            assert!(matches!(error.failure, Failure::NotSent(_)), "{error}");
        }
        std::fs::remove_file(&s.token_file).unwrap();
        let error = s.api.get_pod(NS, "a").await.unwrap_err();
        assert!(matches!(error.failure, Failure::NotSent(_)), "{error}");
        assert_eq!(s.fake.seen().len(), 2);
    }

    /// A client of a server with this certificate, trusting `ca_pem` only.
    async fn get_from(
        ca_pem: &str,
        cert: CertificateDer<'static>,
        key: PrivateKeyDer<'static>,
    ) -> Result<Value, ApiError> {
        let dir = tempfile::tempdir().unwrap();
        let ca_file = dir.path().join("ca.crt");
        std::fs::write(&ca_file, ca_pem).unwrap();
        let token_file = dir.path().join("token");
        std::fs::write(&token_file, TOKEN).unwrap();
        let fake = serve(cert, key, ok_handler()).await;
        let api = Api::new(fake.url(), &ca_file, &token_file)
            .unwrap()
            .with_timeout(Duration::from_secs(10));
        api.get_pod(NS, "a").await
    }

    #[tokio::test]
    async fn the_server_must_have_a_certificate_of_the_ca_for_its_address() {
        let (ca_pem, issuer) = ca("test cluster CA");
        let (_, other_issuer) = ca("another CA");
        // The CA's certificate for the address dialled.
        let (cert, key) = leaf(&issuer, &["127.0.0.1"]);
        get_from(&ca_pem, cert, key).await.unwrap();
        // Another CA's, for the right address.
        let (cert, key) = leaf(&other_issuer, &["127.0.0.1"]);
        let error = get_from(&ca_pem, cert, key).await.unwrap_err();
        assert!(
            matches!(error.failure, Failure::Transport(ref m) if m.contains("invalid peer certificate")),
            "{error}"
        );
        // The CA's, for another address and for a name only.
        for names in [&["127.0.0.2"][..], &["kubernetes.default.svc"][..]] {
            let (cert, key) = leaf(&issuer, names);
            let error = get_from(&ca_pem, cert, key).await.unwrap_err();
            assert!(
                matches!(error.failure, Failure::Transport(ref m) if m.contains("invalid peer certificate")),
                "{names:?}: {error}"
            );
        }
        // A certificate signed by no one but itself.
        let key = rcgen::KeyPair::generate().unwrap();
        let cert = rcgen::CertificateParams::new(vec!["127.0.0.1".to_string()])
            .unwrap()
            .self_signed(&key)
            .unwrap();
        let error = get_from(
            &ca_pem,
            cert.der().clone(),
            PrivateKeyDer::Pkcs8(key.serialize_der().into()),
        )
        .await
        .unwrap_err();
        assert!(
            matches!(error.failure, Failure::Transport(ref m) if m.contains("invalid peer certificate")),
            "{error}"
        );
    }

    #[tokio::test]
    async fn a_status_becomes_its_failure() {
        let long = "x".repeat(2000);
        let handler: Handler = Arc::new(move |seen: &Seen, _: usize| {
            let name = seen.target.rsplit('/').next().unwrap_or_default();
            match name {
                "missing" => {
                    Reply::Json(404, status(404, "NotFound", "pods \"missing\" not found"))
                }
                "pods" => Reply::Json(
                    409,
                    status(409, "AlreadyExists", "pods \"a\" already exists"),
                ),
                "denied" => Reply::Json(
                    403,
                    status(
                        403,
                        "Forbidden",
                        "pods \"denied\" is forbidden: User cannot get",
                    ),
                ),
                "secrets" => Reply::Json(
                    422,
                    json!({"kind": "Status", "status": "Failure", "reason": "Invalid", "code": 422,
                    "message": "Secret \"bad\" is invalid: metadata.name: Invalid value",
                    "details": {"name": "bad", "kind": "secrets", "causes": [
                        {"reason": "FieldValueInvalid", "message": "Invalid value: \"Bad\"", "field": "metadata.name"},
                        {"reason": "FieldValueRequired", "message": "Required value", "field": "type"}
                    ]}}),
                ),
                "conflict" => {
                    Reply::Json(409, status(409, "Conflict", &format!("line\nbreak {long}")))
                }
                "proxy" => Reply::Text(418, "<html>not the API</html>".into()),
                "gone" => Reply::Text(404, "404 page not found".into()),
                _ => Reply::Json(200, json!({})),
            }
        });
        let s = setup(handler).await;
        let api = &s.api;
        let error = api.get_pod(NS, "missing").await.unwrap_err();
        assert!(error.is_not_found(), "{error}");
        assert_eq!(
            error.failure,
            Failure::NotFound("pods \"missing\" not found".into())
        );
        assert_eq!(
            error.call,
            format!("GET /api/v1/namespaces/{NS}/pods/missing")
        );
        let error = api
            .create_pod(NS, &json!({}), DryRun::No)
            .await
            .unwrap_err();
        assert!(error.is_already_exists(), "{error}");
        let error = api.get_pod(NS, "denied").await.unwrap_err();
        assert!(
            matches!(error.failure, Failure::Forbidden(ref m) if m.contains("forbidden")),
            "{error}"
        );
        let error = api
            .create_secret(NS, &json!({}), DryRun::No)
            .await
            .unwrap_err();
        let Failure::Invalid { message, causes } = &error.failure else {
            panic!("{error}");
        };
        assert!(
            message.starts_with("Secret \"bad\" is invalid"),
            "{message}"
        );
        assert_eq!(
            causes,
            &vec![
                Cause {
                    reason: "FieldValueInvalid".into(),
                    field: "metadata.name".into(),
                    message: "Invalid value: \"Bad\"".into()
                },
                Cause {
                    reason: "FieldValueRequired".into(),
                    field: "type".into(),
                    message: "Required value".into()
                },
            ]
        );
        let error = api.get_pod(NS, "conflict").await.unwrap_err();
        let Failure::Status {
            code,
            reason,
            message,
        } = &error.failure
        else {
            panic!("{error}");
        };
        assert_eq!((*code, reason.as_str()), (409, "Conflict"));
        assert!(message.starts_with("line break xxx"), "{message}");
        assert_eq!(
            message.chars().count(),
            MAX_MESSAGE + 1,
            "cut, with an ellipsis"
        );
        let error = api.get_pod(NS, "proxy").await.unwrap_err();
        assert_eq!(error.failure, Failure::Http { code: 418 });
        assert!(!error.to_string().contains("html"), "{error}");
        // A 404 that is not a Status is not the object's absence.
        let error = api.get_pod(NS, "gone").await.unwrap_err();
        assert!(!error.is_not_found(), "{error}");
        assert_eq!(error.failure, Failure::Http { code: 404 });
    }

    #[tokio::test]
    async fn an_answer_over_its_limit_is_refused_before_its_end() {
        let handler: Handler = Arc::new(|seen: &Seen, _: usize| {
            if seen.target.ends_with("/endless") {
                Reply::Endless
            } else if seen.target.ends_with("/sized") {
                Reply::Sized(MAX_OBJECT_BODY + 1)
            } else {
                Reply::Endless
            }
        });
        let s = setup(handler).await;
        let started = Instant::now();
        // Without a length: read up to the limit, then dropped. Read to its end, it would time
        // out instead.
        let error = s.api.get_pod(NS, "endless").await.unwrap_err();
        assert_eq!(
            error.failure,
            Failure::TooLarge {
                limit: MAX_OBJECT_BODY
            }
        );
        let error = s
            .api
            .list_pods(NS, &labels(&[("a", "b")]))
            .await
            .unwrap_err();
        assert_eq!(
            error.failure,
            Failure::TooLarge {
                limit: MAX_LIST_BODY
            }
        );
        // With a length over the limit: refused from its head.
        let error = s.api.get_pod(NS, "sized").await.unwrap_err();
        assert_eq!(
            error.failure,
            Failure::TooLarge {
                limit: MAX_OBJECT_BODY
            }
        );
        assert!(started.elapsed() < Duration::from_secs(10));
        assert_eq!(s.fake.seen().len(), 3, "not sent again");
    }

    #[tokio::test]
    async fn a_call_that_is_not_a_create_is_sent_again_after_503_or_429() {
        let handler: Handler = Arc::new(|seen: &Seen, index: usize| {
            let busy = status(503, "ServiceUnavailable", "the server is busy");
            let name = seen.target.rsplit('/').next().unwrap_or_default();
            match (seen.method.as_str(), name) {
                ("GET", "after-503") if index == 0 => Reply::Json(503, busy),
                ("GET", "after-429") if index == 0 => {
                    Reply::RetryAfter(429, 1, status(429, "TooManyRequests", "too many requests"))
                }
                ("GET", "always-503") => Reply::Json(503, busy),
                ("POST", _) if seen.target.contains("pods") => Reply::Json(503, busy),
                ("POST", _) => Reply::Json(429, status(429, "TooManyRequests", "slow down")),
                _ => Reply::Json(200, json!({"metadata": {"name": name}})),
            }
        });
        let pod = json!({"metadata": {"name": "tinfirm-vm-1"}});

        let s = setup(handler.clone()).await;
        let got = s.api.get_pod(NS, "after-503").await.unwrap();
        assert_eq!(got["metadata"]["name"], "after-503");
        assert_eq!(s.fake.seen().len(), 2);

        let s = setup(handler.clone()).await;
        let started = Instant::now();
        s.api.get_pod(NS, "after-429").await.unwrap();
        assert!(
            started.elapsed() >= Duration::from_secs(1),
            "Retry-After is honoured"
        );
        assert_eq!(s.fake.seen().len(), 2);

        let s = setup(handler.clone()).await;
        let error = s.api.get_pod(NS, "always-503").await.unwrap_err();
        assert!(
            matches!(error.failure, Failure::Status { code: 503, .. }),
            "{error}"
        );
        assert_eq!(s.fake.seen().len(), MAX_ATTEMPTS as usize);

        // A create is sent once, after a 5xx and after a 429 alike.
        let s = setup(handler.clone()).await;
        let error = s.api.create_pod(NS, &pod, DryRun::No).await.unwrap_err();
        assert!(
            matches!(error.failure, Failure::Status { code: 503, .. }),
            "{error}"
        );
        let error = s
            .api
            .create_secret(NS, &json!({}), DryRun::No)
            .await
            .unwrap_err();
        assert!(
            matches!(error.failure, Failure::Status { code: 429, .. }),
            "{error}"
        );
        assert_eq!(s.fake.seen().len(), 2);
    }

    #[tokio::test]
    async fn a_call_without_an_answer_times_out() {
        let handler: Handler = Arc::new(|_: &Seen, _: usize| Reply::Late(Duration::from_secs(30)));
        let s = setup(handler).await;
        let api = s.api.with_timeout(Duration::from_secs(1));
        let started = Instant::now();
        let error = api.get_pod(NS, "a").await.unwrap_err();
        assert_eq!(error.failure, Failure::Timeout(Duration::from_secs(1)));
        assert!(started.elapsed() < Duration::from_secs(5));
        assert!(
            s.fake.seen.lock().unwrap().len() <= 1,
            "a timeout is not sent again"
        );
    }

    #[tokio::test]
    async fn the_token_is_in_no_error_and_no_debug_text() {
        // A server that echoes the request's Authorization header back, in a Status and not.
        let handler: Handler = Arc::new(|seen: &Seen, _: usize| {
            let echo = seen.authorization.clone();
            if seen.target.ends_with("/status") {
                Reply::Json(400, status(400, "BadRequest", &format!("you sent {echo}")))
            } else if seen.target.ends_with("/invalid") {
                Reply::Json(
                    422,
                    json!({"kind": "Status", "reason": "Invalid", "message": echo,
                           "details": {"causes": [{"reason": echo, "field": echo, "message": echo}]}}),
                )
            } else {
                Reply::Text(400, format!("you sent {echo}"))
            }
        });
        let s = setup(handler).await;
        let mut texts = vec![format!("{:?}", s.api)];
        for name in ["status", "invalid", "text"] {
            let error = s.api.get_pod(NS, name).await.unwrap_err();
            texts.push(format!("{error}"));
            texts.push(format!("{error:?}"));
            texts.push(format!("{:#}", anyhow::Error::from(error)));
        }
        std::fs::write(&s.token_file, format!("{TOKEN} {TOKEN}")).unwrap();
        let error = s.api.get_pod(NS, "status").await.unwrap_err();
        texts.push(format!("{error:?}"));
        assert!(texts[1].contains("[REDACTED]"), "{}", texts[1]);
        for text in &texts {
            assert!(!text.contains(TOKEN), "{text}");
            assert!(!text.contains("TEST-ONLY"), "{text}");
        }
    }

    #[tokio::test]
    async fn names_and_labels_are_checked_before_anything_is_sent() {
        let s = setup(ok_handler()).await;
        for name in [
            "",
            "../x",
            "a/b",
            "Upper",
            "a?b",
            "-a",
            "a..b",
            &"a".repeat(254),
        ] {
            let error = s.api.get_pod(NS, name).await.unwrap_err();
            assert!(
                matches!(error.failure, Failure::NotSent(_)),
                "{name}: {error}"
            );
        }
        for namespace in ["", "a.b", "../kube-system", &"a".repeat(64)] {
            let error = s.api.get_pod(namespace, "a").await.unwrap_err();
            assert!(
                matches!(error.failure, Failure::NotSent(_)),
                "{namespace}: {error}"
            );
        }
        for selector in [
            labels(&[]),
            labels(&[("a", "b,c")]),
            labels(&[("a", "b=c")]),
            labels(&[("a!", "b")]),
            labels(&[("a", "b c")]),
            labels(&[("Bad_Prefix/a", "b")]),
            labels(&[("a", &"b".repeat(64))]),
        ] {
            let error = s.api.list_pods(NS, &selector).await.unwrap_err();
            assert!(
                matches!(error.failure, Failure::NotSent(_)),
                "{selector:?}: {error}"
            );
        }
        assert!(s.fake.seen().is_empty());
    }

    #[test]
    fn the_server_comes_from_the_service_variables() {
        assert_eq!(
            server_url("10.96.0.1", "443").unwrap().as_str(),
            "https://10.96.0.1/"
        );
        assert_eq!(
            server_url("10.96.0.1", "6443").unwrap().as_str(),
            "https://10.96.0.1:6443/"
        );
        assert_eq!(
            server_url("fd00:10:96::1", "443").unwrap().as_str(),
            "https://[fd00:10:96::1]/"
        );
        // The `Host` header of an IPv6 server keeps the brackets.
        assert_eq!(
            crate::attachments::authority(&server_url("fd00:10:96::1", "6443").unwrap()),
            "[fd00:10:96::1]:6443"
        );
        assert_eq!(
            crate::attachments::authority(&server_url("fd00:10:96::1", "443").unwrap()),
            "[fd00:10:96::1]"
        );
        assert_eq!(
            server_url("kubernetes.default.svc", "443")
                .unwrap()
                .as_str(),
            "https://kubernetes.default.svc/"
        );
        for (host, port) in [
            ("10.96.0.1", "0"),
            ("10.96.0.1", "x"),
            ("a/b", "443"),
            ("", "443"),
        ] {
            assert!(server_url(host, port).is_err(), "{host}:{port}");
        }
    }

    #[test]
    fn the_client_needs_an_https_server_and_a_ca() {
        let dir = tempfile::tempdir().unwrap();
        let (ca_pem, _) = ca("test cluster CA");
        let ca_file = dir.path().join("ca.crt");
        std::fs::write(&ca_file, ca_pem).unwrap();
        let token_file = dir.path().join("token");
        let url = |u: &str| url::Url::parse(u).unwrap();
        assert!(Api::new(url("https://10.96.0.1/"), &ca_file, &token_file).is_ok());
        for bad in [
            "http://10.96.0.1/",
            "https://10.96.0.1/api",
            "https://u:p@10.96.0.1/",
        ] {
            assert!(Api::new(url(bad), &ca_file, &token_file).is_err(), "{bad}");
        }
        let empty = dir.path().join("empty.crt");
        std::fs::write(&empty, "").unwrap();
        assert!(Api::new(url("https://10.96.0.1/"), &empty, &token_file).is_err());
        assert!(
            Api::new(
                url("https://10.96.0.1/"),
                &dir.path().join("none"),
                &token_file
            )
            .is_err()
        );
    }
}
