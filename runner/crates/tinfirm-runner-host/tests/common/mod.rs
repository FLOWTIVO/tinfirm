//! Test harness: a fake Tinfirm server (WebSocket), a local bare git repository, and a
//! runner host started in-process. All credential values here are TEST-ONLY fakes.
#![allow(dead_code)]

use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use futures::{SinkExt, StreamExt};
use serde_json::{Value, json};
use tempfile::TempDir;
use tinfirm_protocol::{CredentialKind, Runtime, Secret};
use tinfirm_runner_host::bridge::BridgeOptions;
use tinfirm_runner_host::config::HostConfig;
use tinfirm_runner_host::credentials::{Credential, CredentialStore};
pub use tinfirm_runner_host::egress_proxy::EgressSettings;
use tinfirm_runner_host::host::{self, Host, HostOptions, Shared};
pub use tinfirm_runner_host::model_proxy::ModelProxySettings;
use tinfirm_runner_host::paths::Paths;
use tinfirm_runner_host::sandbox::Executor;
pub use tinfirm_runner_host::sandbox::SandboxSettings;
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::tungstenite::handshake::server::{Request, Response};
use tokio_tungstenite::tungstenite::protocol::CloseFrame;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

pub mod git_http;

// TEST-ONLY values. None of them is a real credential.
pub const TEST_CLAUDE_TOKEN: &str = "sk-ant-oat01-TEST-ONLY-fake-claude-token-0123456789abcdef";
pub const TEST_OPENAI_KEY: &str = "sk-TEST-ONLY-fake-openai-key-0123456789abcdef";
pub const TEST_HOST_TOKEN: &str = "tfr_TESTONLYfakehosttoken0123456789abcdefghijk";
pub const TEST_GIT_TOKEN: &str = "github_pat_TEST_ONLY_fake_git_token_0123456789";
/// The key of a company model credential in `run.assign.modelAccess` (ADR-0027).
pub const TEST_MODEL_KEY: &str = "sk-litellm-TEST-ONLY-fake-model-key-0123456789abcdef";
/// Its gateway (never contacted: the fake agent only reports what it was given).
pub const TEST_MODEL_BASE_URL: &str = "https://litellm.example.test";

pub const COMPANY_ID: &str = "0192f1a4-0000-7000-8000-000000000002";

pub fn fake_agent_script() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fake-agent/fake-agent.mjs")
}

pub fn local_executor() -> Executor {
    Executor::Local {
        program: "node".into(),
        args: vec![fake_agent_script().to_string_lossy().into_owned()],
    }
}

/// The fake VM driver (`ee/runner/tinfirm-runner-host/tests/fake-vm-driver`, the driver protocol of ADR-0096).
pub fn fake_vm_driver_script() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../ee/runner/tinfirm-runner-host/tests/fake-vm-driver/fake-driver.mjs")
}

/// The VM executor with the fake driver, whose "VMs" are this crate's binary as `bridge` on a
/// loopback address of their own, running the fake agent. Every call of the driver is written
/// to `log`; `env` sets more of the driver's settings (`FAKE_VM_*`). The VMs get no probe:
/// they share this machine's network.
pub fn vm_executor(log: &Path, env: &[(&str, &str)]) -> Executor {
    Executor::Vm(tinfirm_runner_host::vm::Vms::new(vm_options(log, env)))
}

/// The options of [`vm_executor`], for a test to change.
pub fn vm_options(log: &Path, env: &[(&str, &str)]) -> tinfirm_runner_host::vm::VmOptions {
    use tinfirm_runner_host::vm::{DriverCommand, VmOptions};
    let agent = serde_json::to_string(&["node", &fake_agent_script().to_string_lossy()]).unwrap();
    let mut driver_env = vec![
        (
            "FAKE_VM_BRIDGE".to_string(),
            env!("CARGO_BIN_EXE_tinfirm-runner-host").to_string(),
        ),
        ("FAKE_VM_AGENT".to_string(), agent),
        (
            "FAKE_VM_LOG".to_string(),
            log.to_string_lossy().into_owned(),
        ),
    ];
    driver_env.extend(env.iter().map(|(k, v)| (k.to_string(), v.to_string())));
    let template = env
        .iter()
        .find(|(k, _)| *k == "FAKE_VM_TEMPLATE")
        .map_or("sandbox-test", |(_, v)| v);
    let mut options = VmOptions::new(
        DriverCommand {
            program: PathBuf::from("node"),
            args: vec![fake_vm_driver_script().to_string_lossy().into_owned()],
            env: driver_env,
        },
        template.to_string(),
    );
    options.start_timeout = Duration::from_secs(30);
    options.poll = Duration::from_millis(50);
    options.probe = Vec::new();
    options.on_this_machine = true;
    options
}

/// The calls the fake VM driver got, in order, from its log.
pub fn vm_driver_calls(log: &Path) -> Vec<Value> {
    std::fs::read_to_string(log)
        .unwrap_or_default()
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
}

/// The fake agent without MCP over HTTP (`agentCapabilities.mcpCapabilities.http` false).
pub fn local_executor_without_mcp_http() -> Executor {
    local_executor_with("--no-mcp-http")
}

/// The fake agent with one of its flags (`--no-load-session`, `--fail-load-session`, ...).
pub fn local_executor_with(flag: &str) -> Executor {
    Executor::Local {
        program: "node".into(),
        args: vec![
            fake_agent_script().to_string_lossy().into_owned(),
            flag.into(),
        ],
    }
}

// ---------------------------------------------------------------------------------------
// Schema validation of every frame the host sends.
// ---------------------------------------------------------------------------------------

fn validator(version: u32) -> &'static jsonschema::Validator {
    static V1: OnceLock<jsonschema::Validator> = OnceLock::new();
    static V2: OnceLock<jsonschema::Validator> = OnceLock::new();
    let build = |schema: &str| {
        let schema: Value = serde_json::from_str(schema).unwrap();
        jsonschema::options()
            .should_validate_formats(true)
            .build(&schema)
            .unwrap()
    };
    match version {
        2 => V2.get_or_init(|| build(tinfirm_protocol::SCHEMA_JSON_V2)),
        _ => V1.get_or_init(|| build(tinfirm_protocol::SCHEMA_JSON)),
    }
}

/// `frame` matches the v1 schema.
pub fn assert_schema_valid(frame: &Value) {
    assert_schema_valid_version(frame, 1);
}

/// `frame` matches the schema of protocol `version`.
pub fn assert_schema_valid_version(frame: &Value, version: u32) {
    let errors: Vec<String> = validator(version)
        .iter_errors(frame)
        .map(|e| format!("{e} at {}", e.instance_path()))
        .collect();
    assert!(
        errors.is_empty(),
        "frame does not match the v{version} schema: {errors:#?}\n{frame:#}"
    );
}

// ---------------------------------------------------------------------------------------
// Fake server
// ---------------------------------------------------------------------------------------

enum ConnCommand {
    Send(WsMessage),
    Kill,
}

/// The server closes frames over 1 MiB with 1009.
pub const SERVER_MAX_FRAME: usize = 1024 * 1024;

/// A download request the fake server received: path with query, and the Authorization header.
#[derive(Debug, Clone)]
pub struct Download {
    pub path: String,
    pub authorization: String,
}

/// An attachment upload (`POST /api/runner/v1/runs/{runId}/attachments`), as received.
#[derive(Debug, Clone)]
pub struct Upload {
    pub path: String,
    pub authorization: String,
    pub content_type: String,
    pub body: Vec<u8>,
    /// The `attachmentId` the fake server answered with (unique per upload).
    pub attachment_id: Uuid,
}

impl Upload {
    /// The file name of the multipart `file` part, as `filename*` carries it.
    pub fn file_name(&self) -> String {
        let body = String::from_utf8_lossy(&self.body);
        body.split("filename*=UTF-8''")
            .nth(1)
            .and_then(|rest| rest.split("\r\n").next())
            .map(percent_decode)
            .unwrap_or_default()
    }

    /// The content of the multipart `file` part.
    pub fn file_content(&self) -> Vec<u8> {
        let marker = b"\r\n\r\n";
        let start = self
            .body
            .windows(marker.len())
            .position(|w| w == marker)
            .map(|i| i + marker.len())
            .unwrap_or(0);
        let boundary_at = self.body[start..]
            .windows(4)
            .position(|w| w == b"\r\n--")
            .map(|i| start + i)
            .unwrap_or(self.body.len());
        self.body[start..boundary_at].to_vec()
    }
}

fn percent_decode(text: &str) -> String {
    let mut bytes = Vec::new();
    let mut chars = text.bytes();
    while let Some(b) = chars.next() {
        if b == b'%' {
            let hex: String = chars.by_ref().take(2).map(|c| c as char).collect();
            bytes.push(u8::from_str_radix(&hex, 16).unwrap_or(b'?'));
        } else {
            bytes.push(b);
        }
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

pub struct FakeServer {
    pub url: String,
    /// Attachment contents by attachment id, and skill zips by skill id; a missing id answers
    /// 404.
    pub files: Arc<Mutex<HashMap<String, Vec<u8>>>>,
    /// Every attachment and skill download request, in order.
    pub downloads: Arc<Mutex<Vec<Download>>>,
    /// Every attachment upload, in order; each is answered STORED.
    pub uploads: Arc<Mutex<Vec<Upload>>>,
    /// `welcome.runnerHostId`; unique per server so parallel tests never share container
    /// labels (orphan cleanup is per runner host id).
    pub runner_host_id: Uuid,
    /// While set, session bundle uploads are answered `REFUSED` (ADR-0097).
    pub refuse_session_bundles: Arc<std::sync::atomic::AtomicBool>,
    conns: mpsc::UnboundedReceiver<Conn>,
    refusing: Arc<std::sync::atomic::AtomicBool>,
}

pub struct Conn {
    pub authorization: String,
    pub runner_host_id: Uuid,
    rx: mpsc::UnboundedReceiver<Option<(Value, usize)>>,
    tx: mpsc::UnboundedSender<ConnCommand>,
    /// Pongs received (answers to the server's pings).
    pub pongs: Arc<std::sync::atomic::AtomicUsize>,
    /// Largest text frame received so far.
    pub max_frame: usize,
    /// Every text frame received on this connection, in order.
    pub frames: Vec<Value>,
    /// Frames received but passed over by [`Conn::take`]; [`Conn::next`] returns them first.
    backlog: VecDeque<Value>,
    pub closed: bool,
    pub close_frame: Arc<Mutex<Option<(u16, String)>>>,
    /// The protocol version of the host's hello (1 until the hello arrives); every frame is
    /// checked against that version's schema.
    pub protocol_version: u32,
}

impl FakeServer {
    // The handshake callback's error type is tungstenite's (large) HTTP error response.
    #[allow(clippy::result_large_err)]
    pub async fn start() -> FakeServer {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (conn_tx, conns) = mpsc::unbounded_channel();
        let runner_host_id = Uuid::new_v4();
        let refusing = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let refusing_task = refusing.clone();
        let files: Arc<Mutex<HashMap<String, Vec<u8>>>> = Arc::default();
        let downloads: Arc<Mutex<Vec<Download>>> = Arc::default();
        let uploads: Arc<Mutex<Vec<Upload>>> = Arc::default();
        let files_task = files.clone();
        let downloads_task = downloads.clone();
        let uploads_task = uploads.clone();
        let refuse_session_bundles = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let refuse_task = refuse_session_bundles.clone();
        tokio::spawn(async move {
            loop {
                let Ok((stream, _)) = listener.accept().await else {
                    break;
                };
                if refusing_task.load(std::sync::atomic::Ordering::SeqCst) {
                    drop(stream);
                    continue;
                }
                if is_download(&stream).await {
                    let files = files_task.clone();
                    let downloads = downloads_task.clone();
                    tokio::spawn(serve_download(stream, files, downloads));
                    continue;
                }
                if is_upload(&stream).await {
                    tokio::spawn(serve_upload(
                        stream,
                        uploads_task.clone(),
                        refuse_task.clone(),
                    ));
                    continue;
                }
                let auth = Arc::new(Mutex::new(String::new()));
                let auth_cb = auth.clone();
                let callback = move |req: &Request, resp: Response| {
                    assert_eq!(req.uri().path(), "/api/runner/v1/connect");
                    *auth_cb.lock().unwrap() = req
                        .headers()
                        .get("authorization")
                        .and_then(|v| v.to_str().ok())
                        .unwrap_or_default()
                        .to_string();
                    Ok(resp)
                };
                let Ok(mut ws) = tokio_tungstenite::accept_hdr_async(stream, callback).await else {
                    continue;
                };
                let (frame_tx, frame_rx) = mpsc::unbounded_channel();
                let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel::<ConnCommand>();
                let close_frame = Arc::new(Mutex::new(None));
                let close_frame_task = close_frame.clone();
                let pongs = Arc::new(std::sync::atomic::AtomicUsize::new(0));
                let pongs_task = pongs.clone();
                tokio::spawn(async move {
                    loop {
                        tokio::select! {
                            frame = ws.next() => match frame {
                                Some(Ok(WsMessage::Text(text))) => {
                                    let value: Value = serde_json::from_str(&text).expect("host frames are JSON");
                                    let _ = frame_tx.send(Some((value, text.len())));
                                }
                                Some(Ok(WsMessage::Pong(_))) => {
                                    pongs_task.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                                }
                                Some(Ok(WsMessage::Close(frame))) => {
                                    if let Some(f) = frame {
                                        *close_frame_task.lock().unwrap() = Some((u16::from(f.code), f.reason.to_string()));
                                    }
                                    let _ = frame_tx.send(None);
                                    break;
                                }
                                Some(Ok(_)) => {}
                                _ => { let _ = frame_tx.send(None); break; }
                            },
                            cmd = cmd_rx.recv() => match cmd {
                                Some(ConnCommand::Send(m)) => { if ws.send(m).await.is_err() { let _ = frame_tx.send(None); break; } }
                                Some(ConnCommand::Kill) | None => { let _ = frame_tx.send(None); break; }
                            }
                        }
                    }
                });
                let authorization = auth.lock().unwrap().clone();
                let _ = conn_tx.send(Conn {
                    authorization,
                    runner_host_id,
                    rx: frame_rx,
                    tx: cmd_tx,
                    pongs,
                    max_frame: 0,
                    frames: Vec::new(),
                    backlog: VecDeque::new(),
                    closed: false,
                    close_frame,
                    protocol_version: 1,
                });
            }
        });
        FakeServer {
            url: format!("http://{addr}"),
            files,
            downloads,
            uploads,
            runner_host_id,
            refuse_session_bundles,
            conns,
            refusing,
        }
    }

    /// While refusing, new TCP connections are dropped before the WebSocket handshake.
    pub fn set_refusing(&self, refusing: bool) {
        self.refusing
            .store(refusing, std::sync::atomic::Ordering::SeqCst);
    }

    pub async fn accept(&mut self) -> Conn {
        tokio::time::timeout(Duration::from_secs(60), self.conns.recv())
            .await
            .expect("the host did not connect in time")
            .expect("server stopped")
    }
}

/// Attachment downloads (`GET /api/runner/v1/runs/…`) share the port with the WebSocket.
async fn is_download(stream: &tokio::net::TcpStream) -> bool {
    const PREFIX: &[u8] = b"GET /api/runner/v1/runs/";
    let mut buf = [0u8; 24];
    for _ in 0..100 {
        match stream.peek(&mut buf).await {
            Ok(n) if n >= PREFIX.len() => return buf.starts_with(PREFIX),
            Ok(0) | Err(_) => return false,
            Ok(_) => tokio::time::sleep(Duration::from_millis(10)).await,
        }
    }
    false
}

async fn serve_download(
    stream: tokio::net::TcpStream,
    files: Arc<Mutex<HashMap<String, Vec<u8>>>>,
    downloads: Arc<Mutex<Vec<Download>>>,
) {
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
    let mut reader = BufReader::new(stream);
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).await.is_err() {
        return;
    }
    let path = request_line
        .split_whitespace()
        .nth(1)
        .unwrap_or_default()
        .to_string();
    let mut authorization = String::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).await.unwrap_or(0) == 0 || line == "\r\n" {
            break;
        }
        if let Some((name, value)) = line.split_once(':')
            && name.eq_ignore_ascii_case("authorization")
        {
            authorization = value.trim().to_string();
        }
    }
    downloads.lock().unwrap().push(Download {
        path: path.clone(),
        authorization,
    });
    // `…/attachments/{id}?epoch=`, or a skill's `…/skills/{skillId}/versions/{version}?epoch=`:
    // `files` holds both, by id; a run's session bundle (`…/runs/{runId}/session-bundle?epoch=`)
    // as `session-bundle/{runId}`.
    let id = if path.contains("/session-bundle") {
        let run = path
            .trim_start_matches("/api/runner/v1/runs/")
            .split('/')
            .next()
            .unwrap_or_default();
        format!("session-bundle/{run}")
    } else {
        path.split("/attachments/")
            .nth(1)
            .or_else(|| path.split("/skills/").nth(1))
            .and_then(|rest| rest.split(['?', '/']).next())
            .unwrap_or_default()
            .to_string()
    };
    let body = files.lock().unwrap().get(&id).cloned();
    let mut stream = reader.into_inner();
    let head = match &body {
        Some(content) => format!(
            "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            content.len()
        ),
        None => {
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_string()
        }
    };
    let _ = stream.write_all(head.as_bytes()).await;
    if let Some(content) = body {
        let _ = stream.write_all(&content).await;
    }
    let _ = stream.shutdown().await;
}

/// Attachment uploads (`POST /api/runner/v1/runs/…`) share the port too.
async fn is_upload(stream: &tokio::net::TcpStream) -> bool {
    const PREFIX: &[u8] = b"POST /api/runner/v1/runs/";
    let mut buf = [0u8; 25];
    for _ in 0..100 {
        match stream.peek(&mut buf).await {
            Ok(n) if n >= PREFIX.len() => return buf.starts_with(PREFIX),
            Ok(0) | Err(_) => return false,
            Ok(_) => tokio::time::sleep(Duration::from_millis(10)).await,
        }
    }
    false
}

async fn serve_upload(
    stream: tokio::net::TcpStream,
    uploads: Arc<Mutex<Vec<Upload>>>,
    refuse_session_bundles: Arc<std::sync::atomic::AtomicBool>,
) {
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
    let mut reader = BufReader::new(stream);
    let mut request_line = String::new();
    if reader.read_line(&mut request_line).await.is_err() {
        return;
    }
    let path = request_line
        .split_whitespace()
        .nth(1)
        .unwrap_or_default()
        .to_string();
    let (mut authorization, mut content_type, mut length) = (String::new(), String::new(), 0);
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).await.unwrap_or(0) == 0 || line == "\r\n" {
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            let value = value.trim().to_string();
            match name.to_ascii_lowercase().as_str() {
                "authorization" => authorization = value,
                "content-type" => content_type = value,
                "content-length" => length = value.parse().unwrap_or(0),
                _ => {}
            }
        }
    }
    let mut body = vec![0u8; length];
    if reader.read_exact(&mut body).await.is_err() {
        return;
    }
    let attachment_id = Uuid::new_v4();
    let bundle = path.contains("/session-bundle");
    let sha256 = sha256_hex(&body);
    uploads.lock().unwrap().push(Upload {
        path,
        authorization,
        content_type,
        body,
        attachment_id,
    });
    let answer = if !bundle {
        format!(
            r#"{{"status":"STORED","attachmentId":"{attachment_id}","fileName":"report.md","message":"Attached the file."}}"#
        )
    } else if refuse_session_bundles.load(std::sync::atomic::Ordering::SeqCst) {
        r#"{"status":"REFUSED","message":"The bundle was not stored: it holds the value of the vault secret(s) DEPLOY_KEY."}"#.to_string()
    } else {
        format!(
            r#"{{"status":"STORED","message":"Stored the session bundle.","sha256":"{sha256}"}}"#
        )
    };
    let mut stream = reader.into_inner();
    let _ = stream
        .write_all(
            format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{answer}",
                answer.len()
            )
            .as_bytes(),
        )
        .await;
    let _ = stream.shutdown().await;
}

/// Hex SHA-256, as `run.assign.attachments[].sha256` carries it.
pub fn sha256_hex(content: &[u8]) -> String {
    ring::digest::digest(&ring::digest::SHA256, content)
        .as_ref()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

impl Conn {
    /// Next text frame, or `None` once the connection is closed.
    pub async fn next(&mut self) -> Option<Value> {
        if let Some(frame) = self.backlog.pop_front() {
            return Some(frame);
        }
        self.receive().await
    }

    /// The next frame that matches `wanted`. Other frames stay for [`Conn::next`], in order.
    pub async fn take_where(&mut self, what: &str, wanted: impl Fn(&Value) -> bool) -> Value {
        if let Some(i) = self.backlog.iter().position(&wanted) {
            return self.backlog.remove(i).unwrap();
        }
        loop {
            let frame = self
                .receive()
                .await
                .unwrap_or_else(|| panic!("connection closed while waiting for {what}"));
            if wanted(&frame) {
                return frame;
            }
            self.backlog.push_back(frame);
        }
    }

    /// The next frame of `type`, without skipping others (unlike [`Conn::expect`]).
    pub async fn take(&mut self, message_type: &str) -> Value {
        self.take_where(message_type, |f| f["type"] == message_type)
            .await
    }

    /// The `type` frame of one run (e.g. its `run.accepted` or `run.rejected`).
    pub async fn take_run(&mut self, message_type: &str, run_id: Uuid) -> Value {
        let id = run_id.to_string();
        self.take_where(message_type, |f| {
            f["type"] == message_type && f["runId"] == id
        })
        .await
    }

    /// Waits until a run's agent has said something (it is running).
    pub async fn wait_running(&mut self, run_id: Uuid) {
        let id = run_id.to_string();
        self.take_where("the run's first AGENT_MESSAGE", |f| {
            f["runId"] == id && f["eventType"] == "AGENT_MESSAGE"
        })
        .await;
    }

    async fn receive(&mut self) -> Option<Value> {
        if self.closed {
            return None;
        }
        let frame = tokio::time::timeout(Duration::from_secs(90), self.rx.recv())
            .await
            .expect("no frame from the host in time")
            .flatten();
        match frame {
            Some((value, size)) => {
                assert!(
                    size <= SERVER_MAX_FRAME,
                    "frame of {size} bytes exceeds the server limit"
                );
                self.max_frame = self.max_frame.max(size);
                if value["type"] == "hello"
                    && let Some(version) = value["protocolVersion"].as_u64()
                {
                    self.protocol_version = version as u32;
                }
                assert_schema_valid_version(&value, self.protocol_version);
                self.frames.push(value.clone());
                Some(value)
            }
            None => {
                self.closed = true;
                None
            }
        }
    }

    /// Skips frames until one of `type` arrives.
    pub async fn expect(&mut self, message_type: &str) -> Value {
        loop {
            let frame = self
                .next()
                .await
                .unwrap_or_else(|| panic!("connection closed while waiting for {message_type}"));
            if frame["type"] == message_type {
                return frame;
            }
        }
    }

    pub fn send(&self, value: Value) {
        let _ = self
            .tx
            .send(ConnCommand::Send(WsMessage::text(value.to_string())));
    }

    pub fn send_raw(&self, text: &str) {
        let _ = self
            .tx
            .send(ConnCommand::Send(WsMessage::text(text.to_string())));
    }

    pub fn close_with(&self, code: u16, reason: &str) {
        let _ = self
            .tx
            .send(ConnCommand::Send(WsMessage::Close(Some(CloseFrame {
                code: CloseCode::from(code),
                reason: reason.to_string().into(),
            }))));
    }

    /// A WebSocket ping, like the server sends every heartbeat interval.
    pub fn ping(&self) {
        let _ = self.tx.send(ConnCommand::Send(WsMessage::Ping(
            b"keepalive".to_vec().into(),
        )));
    }

    /// Drops the TCP connection without a close frame.
    pub fn kill(&self) {
        let _ = self.tx.send(ConnCommand::Kill);
    }

    /// Like the server: answers `hello` with `welcome` (echoing the hello's protocol version,
    /// as a v2 server does) and at once `host.config` (version 0, no desired values), and
    /// waits for `host.config.applied`, which must report the ceilings of `hello`. Returns
    /// the hello.
    pub async fn handshake(&mut self, heartbeat_seconds: u64) -> Value {
        let (hello, applied) = self
            .handshake_with(heartbeat_seconds, Some(host_config(0, None, &[])))
            .await;
        let applied = applied.unwrap();
        assert_eq!(applied["version"], 0);
        assert_applied_matches_hello(&hello, &applied);
        hello
    }

    /// Answers `hello` with `welcome`, then sends `config` (if any) and waits for its
    /// `host.config.applied`. Returns the hello and the applied frame.
    pub async fn handshake_with(
        &mut self,
        heartbeat_seconds: u64,
        config: Option<Value>,
    ) -> (Value, Option<Value>) {
        let hello = self.expect("hello").await;
        self.send(json!({
            "type": "welcome",
            "protocolVersion": hello["protocolVersion"],
            "runnerHostId": self.runner_host_id,
            "companyId": COMPANY_ID,
            "serverVersion": "test",
            "heartbeatIntervalSeconds": heartbeat_seconds,
            "leaseTtlSeconds": 30,
            "someFutureField": {"ignored": true}
        }));
        let applied = match config {
            Some(config) => {
                self.send(config);
                Some(self.take("host.config.applied").await)
            }
            None => None,
        };
        (hello, applied)
    }

    /// Sends `host.config` and returns its answer.
    pub async fn apply(&mut self, config: Value) -> Value {
        self.send(config);
        self.take("host.config.applied").await
    }

    /// Waits for a run's event numbered `seq` or later; returns the highest number seen.
    pub async fn until_seq(&mut self, run_id: Uuid, seq: u64) -> u64 {
        let id = run_id.to_string();
        let mut highest = 0;
        loop {
            let frame = self
                .next()
                .await
                .unwrap_or_else(|| panic!("connection closed before event {seq} of {id}"));
            if frame["runId"] == id
                && let Some(n) = frame["seq"].as_u64()
            {
                highest = highest.max(n);
                if n >= seq {
                    return highest;
                }
            }
        }
    }

    /// Frames of one run until its `run.finished` (inclusive).
    pub async fn until_finished(&mut self, run_id: Uuid) -> Transcript {
        let mut transcript = Transcript::default();
        let id = run_id.to_string();
        loop {
            let frame = self
                .next()
                .await
                .unwrap_or_else(|| panic!("connection closed before run.finished of {id}"));
            transcript.push(frame.clone());
            if frame["type"] == "run.finished" && frame["runId"] == id {
                return transcript;
            }
        }
    }
}

/// A `host.config` frame; `pools` are the desired pool sizes by label.
pub fn host_config(version: u64, desired_max: Option<u32>, pools: &[(&str, u32)]) -> Value {
    let credentials: Vec<Value> = pools
        .iter()
        .map(|(label, size)| json!({"label": label, "desiredPoolSize": size}))
        .collect();
    let mut config = json!({
        "type": "host.config",
        "version": version,
        "credentials": credentials,
        "someFutureField": true
    });
    if let Some(max) = desired_max {
        config["desiredMaxConcurrentRuns"] = json!(max);
    }
    config
}

/// `host.config.applied` reports the ceilings of `hello`, and every credential of it once.
pub fn assert_applied_matches_hello(hello: &Value, applied: &Value) {
    assert_eq!(
        applied["maxConcurrentRunsCeiling"], hello["maxConcurrentRuns"],
        "{applied}"
    );
    let ceilings: Vec<(Value, Value)> = hello["credentials"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| (c["label"].clone(), c["poolSize"].clone()))
        .collect();
    let reported: Vec<(Value, Value)> = applied["credentials"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| (c["label"].clone(), c["poolSizeCeiling"].clone()))
        .collect();
    assert_eq!(reported, ceilings, "{applied}");
    if applied["draining"] == true {
        assert_eq!(applied["maxConcurrentRuns"], 0, "{applied}");
    }
}

/// The effective pools of a `host.config.applied`, by label.
pub fn applied_pools(applied: &Value) -> Vec<(String, u64)> {
    applied["credentials"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| {
            (
                c["label"].as_str().unwrap().to_string(),
                c["poolSize"].as_u64().unwrap(),
            )
        })
        .collect()
}

/// The frames of a run as seen by the server.
#[derive(Debug, Default, Clone)]
pub struct Transcript {
    pub frames: Vec<Value>,
}

impl Transcript {
    pub fn push(&mut self, frame: Value) {
        self.frames.push(frame);
    }

    pub fn events(&self) -> Vec<&Value> {
        self.frames
            .iter()
            .filter(|f| f["type"] == "run.event")
            .collect()
    }

    pub fn of_type(&self, t: &str) -> Vec<&Value> {
        self.frames.iter().filter(|f| f["type"] == t).collect()
    }

    pub fn event_types(&self) -> Vec<String> {
        self.events()
            .iter()
            .map(|e| e["eventType"].as_str().unwrap().to_string())
            .collect()
    }

    pub fn events_of(&self, event_type: &str) -> Vec<&Value> {
        self.events()
            .into_iter()
            .filter(|e| e["eventType"] == event_type)
            .collect()
    }

    pub fn finished(&self) -> &Value {
        self.frames
            .iter()
            .rev()
            .find(|f| f["type"] == "run.finished")
            .expect("run.finished")
    }

    pub fn text(&self) -> String {
        self.frames
            .iter()
            .map(|f| f.to_string())
            .collect::<Vec<_>>()
            .join("\n")
    }

    /// AGENT_MESSAGE texts joined.
    pub fn agent_text(&self) -> String {
        self.events_of("AGENT_MESSAGE")
            .iter()
            .map(|e| e["payload"]["text"].as_str().unwrap().to_string())
            .collect()
    }

    pub fn statuses(&self) -> Vec<String> {
        self.events_of("STATUS")
            .iter()
            .map(|e| e["payload"]["phase"].as_str().unwrap().to_string())
            .collect()
    }

    /// Asserts seq 1..=n without gaps or duplicates and `lastSeq == n`.
    pub fn assert_contiguous_seq(&self) {
        let seqs: Vec<u64> = self
            .events()
            .iter()
            .map(|e| e["seq"].as_u64().unwrap())
            .collect();
        let expected: Vec<u64> = (1..=seqs.len() as u64).collect();
        assert_eq!(seqs, expected, "seq must start at 1 and increase by 1");
        assert_eq!(
            self.finished()["lastSeq"].as_u64().unwrap(),
            seqs.len() as u64
        );
    }

    pub fn assert_no_secrets(&self) {
        let text = self.text();
        for secret in [
            TEST_CLAUDE_TOKEN,
            TEST_OPENAI_KEY,
            TEST_HOST_TOKEN,
            TEST_GIT_TOKEN,
            TEST_MODEL_KEY,
        ] {
            assert!(
                !text.contains(secret),
                "a frame contains a credential value"
            );
        }
    }
}

// ---------------------------------------------------------------------------------------
// Bare repository
// ---------------------------------------------------------------------------------------

pub struct TestRepo {
    pub dir: TempDir,
    pub bare: PathBuf,
    pub url: String,
}

pub fn git(cwd: &Path, args: &[&str]) -> String {
    let out = Command::new("git")
        .args([
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.invalid",
            "-c",
            "commit.gpgsign=false",
            "-c",
            "init.defaultBranch=main",
        ])
        .args(args)
        .current_dir(cwd)
        .env("GIT_TERMINAL_PROMPT", "0")
        .output()
        .expect("git runs");
    assert!(
        out.status.success(),
        "git {args:?} failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8_lossy(&out.stdout).into_owned()
}

impl TestRepo {
    pub fn new() -> TestRepo {
        let dir = tempfile::tempdir().unwrap();
        let bare = dir.path().join("origin.git");
        git(
            dir.path(),
            &["init", "--bare", "-q", "-b", "main", bare.to_str().unwrap()],
        );
        git(&bare, &["config", "uploadpack.allowFilter", "true"]);
        let work = dir.path().join("seed");
        git(
            dir.path(),
            &[
                "clone",
                "-q",
                bare.to_str().unwrap(),
                work.to_str().unwrap(),
            ],
        );
        std::fs::write(work.join("README.md"), "# test repo\n").unwrap();
        git(&work, &["add", "README.md"]);
        git(&work, &["commit", "-q", "-m", "Initial commit"]);
        git(&work, &["push", "-q", "origin", "HEAD:refs/heads/main"]);
        let url = format!("file://{}", bare.display());
        TestRepo { dir, bare, url }
    }

    pub fn branch_head(&self, branch: &str) -> Option<String> {
        let out = Command::new("git")
            .args([
                "rev-parse",
                "--verify",
                "--quiet",
                &format!("refs/heads/{branch}"),
            ])
            .current_dir(&self.bare)
            .output()
            .unwrap();
        out.status
            .success()
            .then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
    }

    pub fn file_at(&self, branch: &str, path: &str) -> Option<String> {
        let out = Command::new("git")
            .args(["show", &format!("{branch}:{path}")])
            .current_dir(&self.bare)
            .output()
            .unwrap();
        out.status
            .success()
            .then(|| String::from_utf8_lossy(&out.stdout).into_owned())
    }

    /// Makes the repository refuse every push (a `pre-receive` hook). Only a server that runs
    /// hooks honours it: the `git http-backend` helper does.
    pub fn reject_pushes(&self) {
        use std::os::unix::fs::PermissionsExt;
        let hook = self.bare.join("hooks/pre-receive");
        std::fs::write(
            &hook,
            "#!/bin/sh\necho 'pushes are closed for this test' >&2\nexit 1\n",
        )
        .unwrap();
        std::fs::set_permissions(&hook, std::fs::Permissions::from_mode(0o755)).unwrap();
    }

    /// A new branch `branch` at `main`; returns its sha.
    pub fn create_branch(&self, branch: &str) -> String {
        git(&self.bare, &["branch", branch, "main"]);
        self.branch_head(branch).unwrap()
    }

    /// Deletes a branch of the repository.
    pub fn delete_branch(&self, branch: &str) {
        git(&self.bare, &["branch", "-D", branch]);
    }

    /// A new commit on `main` (as if someone pushed while a run is going); returns its sha.
    pub fn commit_to_main(&self, file: &str, content: &str, subject: &str) -> String {
        self.commit_to_branch("main", file, content, subject)
    }

    /// A new commit on `branch`, pushed like someone else would (a fast-forward); a branch
    /// that does not exist yet starts at `main`. Returns the commit's sha.
    pub fn commit_to_branch(
        &self,
        branch: &str,
        file: &str,
        content: &str,
        subject: &str,
    ) -> String {
        let work = self.dir.path().join(format!("upstream-{}", Uuid::new_v4()));
        git(
            self.dir.path(),
            &[
                "clone",
                "-q",
                self.bare.to_str().unwrap(),
                work.to_str().unwrap(),
            ],
        );
        let start = match self.branch_head(branch) {
            Some(_) => format!("origin/{branch}"),
            None => "origin/main".to_string(),
        };
        git(&work, &["checkout", "-q", "-B", branch, &start]);
        std::fs::write(work.join(file), content).unwrap();
        git(&work, &["add", file]);
        git(&work, &["commit", "-q", "-m", subject]);
        git(
            &work,
            &["push", "-q", "origin", &format!("HEAD:refs/heads/{branch}")],
        );
        git(&work, &["rev-parse", "HEAD"]).trim().to_string()
    }

    pub fn subjects(&self, branch: &str) -> Vec<String> {
        git(&self.bare, &["log", "--format=%s", branch])
            .lines()
            .map(str::to_string)
            .collect()
    }
}

// ---------------------------------------------------------------------------------------
// Runner host under test
// ---------------------------------------------------------------------------------------

pub struct HostSetup {
    pub executor: Executor,
    pub max_concurrent_runs: u32,
    pub wall_clock_cap: Option<Duration>,
    pub image_overrides: HashMap<Runtime, String>,
    pub sandbox_network: Option<String>,
    pub inactivity_timeout: Option<Duration>,
    pub repository_request_timeout: Option<Duration>,
    /// The wait for a server-executed tool call's ack (65 s by default).
    pub tool_request_timeout: Option<Duration>,
    /// Maintenance passes this often (and first after this long) instead of 60 s / 6 h.
    pub maintenance_every: Option<Duration>,
    /// Stored before the host starts, next to those of [`write_config`].
    pub credentials: Vec<Credential>,
    /// The runner protocol version the host speaks (`config.json`): 1 unless a test asks for 2
    /// ([`HostSetup::v2`]).
    pub protocol_version: u32,
    /// The model proxy's settings (crate::model_proxy). It is always on: a gateway's key never
    /// reaches the sandbox, whatever they say.
    pub model_proxy: ModelProxySettings,
    /// Run the agent over the sandbox bridge (ADR-0095, `run --sandbox-bridge`): the local
    /// executor then starts this binary as `bridge`, which starts the fake agent.
    pub bridge: Option<PathBuf>,
    /// The egress proxy (`run --egress-proxy`, ADR-0044).
    pub egress: Option<EgressSettings>,
    /// The sandboxes' settings (`run --sandbox-*`); `None`: the defaults.
    pub sandbox: Option<SandboxSettings>,
    /// The VM pool's ceilings and tuning (`run --pool-*`, ADR-0098); `None`: the defaults.
    pub pool: Option<tinfirm_runner_host::pool::PoolOptions>,
}

/// With this variable set, every test host runs its agents over the sandbox bridge
/// (`run --sandbox-bridge`, ADR-0095): the local tests then run with the files and the
/// listeners over the bridge, as `make runner-test` and CI run them a second time.
pub const BRIDGE_ENV: &str = "TINFIRM_TEST_SANDBOX_BRIDGE";

/// Whether [`BRIDGE_ENV`] is set, or [`VM_ENV`], whose runs go over the bridge too.
pub fn bridged_tests() -> bool {
    std::env::var_os(BRIDGE_ENV).is_some_and(|v| !v.is_empty()) || vm_tests()
}

/// With this variable set, every test host that would run its agents with the local executor
/// creates a "VM" per run through the fake driver instead (`run --executor vm`, ADR-0096).
pub const VM_ENV: &str = "TINFIRM_TEST_VM_EXECUTOR";

/// Whether [`VM_ENV`] is set.
pub fn vm_tests() -> bool {
    std::env::var_os(VM_ENV).is_some_and(|v| !v.is_empty())
}

/// Whether a host's login (a Claude login's credentials file, a Codex login's `CODEX_HOME`)
/// reaches the agent: not with a VM per run, which mounts nothing of this machine (ADR-0085,
/// ADR-0096); the company host a VM runs on holds no login.
pub fn logins_reach_the_sandbox() -> bool {
    !vm_tests()
}

/// How the sandbox's URLs to the host's listeners begin: `http://127.0.0.1:` where the local
/// executor's agent reaches them directly; over the bridge, a loopback address of the run's
/// own, where its tunnels listen.
pub fn listener_url() -> &'static str {
    if bridged_tests() {
        "http://127."
    } else {
        "http://127.0.0.1:"
    }
}

impl HostSetup {
    pub fn local() -> HostSetup {
        let bridge =
            bridged_tests().then(|| PathBuf::from(env!("CARGO_BIN_EXE_tinfirm-runner-host")));
        let executor = if vm_tests() {
            // These hosts never look at the driver's log.
            vm_executor(Path::new("/dev/null"), &[])
        } else {
            local_executor()
        };
        HostSetup {
            executor,
            max_concurrent_runs: 2,
            wall_clock_cap: None,
            image_overrides: HashMap::new(),
            sandbox_network: None,
            inactivity_timeout: None,
            repository_request_timeout: None,
            tool_request_timeout: None,
            maintenance_every: None,
            credentials: Vec::new(),
            protocol_version: 1,
            model_proxy: ModelProxySettings::default(),
            bridge,
            egress: None,
            sandbox: None,
            pool: None,
        }
    }

    /// A host whose agents run over the sandbox bridge.
    pub fn bridged() -> HostSetup {
        HostSetup {
            bridge: Some(PathBuf::from(env!("CARGO_BIN_EXE_tinfirm-runner-host"))),
            ..HostSetup::local()
        }
    }

    /// A host with the VM executor and the fake driver ([`vm_executor`]).
    pub fn vm(log: &Path, env: &[(&str, &str)]) -> HostSetup {
        HostSetup::vm_with(vm_options(log, env))
    }

    /// A host with the VM executor with these options.
    pub fn vm_with(options: tinfirm_runner_host::vm::VmOptions) -> HostSetup {
        HostSetup {
            executor: Executor::Vm(tinfirm_runner_host::vm::Vms::new(options)),
            sandbox: Some(SandboxSettings::for_vms(None, false, Vec::new()).unwrap()),
            ..HostSetup::bridged()
        }
    }

    /// A host that speaks protocol v2.
    pub fn v2() -> HostSetup {
        HostSetup {
            protocol_version: 2,
            ..HostSetup::local()
        }
    }
}

pub struct TestHost {
    pub dir: TempDir,
    pub paths: Paths,
    pub host: Host,
    pub shutdown: CancellationToken,
    pub handle: tokio::task::JoinHandle<anyhow::Result<()>>,
}

/// Writes a Claude login's `.credentials.json` the way `claude auth login` would, with a
/// test-only token, so that the host accepts runs with the credential.
pub fn write_fake_claude_login(paths: &Paths, label: &str) {
    let login = paths.claude_login(label);
    std::fs::create_dir_all(&login).unwrap();
    tinfirm_runner_host::secure_fs::write_private_file(
        &tinfirm_runner_host::credentials::claude_credentials_file(&login),
        format!(r#"{{"claudeAiOauth":{{"accessToken":"{TEST_CLAUDE_TOKEN}"}}}}"#).as_bytes(),
    )
    .unwrap();
}

/// A Codex login's `CODEX_HOME` with a fake `auth.json`, as `credential add codex` leaves it.
pub fn write_fake_codex_home(paths: &Paths, label: &str) {
    let home = paths.codex_home(label);
    std::fs::create_dir_all(&home).unwrap();
    tinfirm_runner_host::secure_fs::write_private_file(
        &home.join("auth.json"),
        br#"{"tokens":{"refresh_token":"fake-refresh-token","access_token":"fake-access-token"}}"#,
    )
    .unwrap();
}

/// Writes config and credentials the way `login` / `credential add` do.
pub fn write_config(paths: &Paths, server_url: &str) {
    HostConfig::new(server_url, Secret::new(TEST_HOST_TOKEN))
        .save(paths)
        .unwrap();
    let store = CredentialStore::new(paths);
    store
        .save(&Credential {
            label: "claude-test".into(),
            kind: CredentialKind::ClaudeLogin,
            pool_size: 2,
            created_at: chrono::Utc::now(),
            secret: None,
        })
        .unwrap();
    write_fake_claude_login(paths, "claude-test");
    store
        .save(&Credential {
            label: "openai-test".into(),
            kind: CredentialKind::OpenaiApiKey,
            pool_size: 1,
            created_at: chrono::Utc::now(),
            secret: Some(Secret::new(TEST_OPENAI_KEY)),
        })
        .unwrap();
}

pub async fn start_host(server_url: &str, setup: HostSetup) -> TestHost {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().to_path_buf();
    start_host_in(dir, &root, server_url, setup).await
}

/// [`start_host`] with its directories under `root`, which outlives the host: a host started
/// again on them is the earlier one restarted.
pub async fn start_host_at(root: &Path, server_url: &str, setup: HostSetup) -> TestHost {
    start_host_in(tempfile::tempdir().unwrap(), root, server_url, setup).await
}

async fn start_host_in(dir: TempDir, root: &Path, server_url: &str, setup: HostSetup) -> TestHost {
    let paths = Paths::new(root.join("config"), root.join("data"));
    write_config(&paths, server_url);
    for credential in &setup.credentials {
        CredentialStore::new(&paths).save(credential).unwrap();
    }
    let mut opts = HostOptions::new(paths.clone(), setup.executor);
    opts.max_concurrent_runs = setup.max_concurrent_runs;
    opts.max_wall_clock_cap = setup.wall_clock_cap;
    opts.image_overrides = setup.image_overrides;
    opts.sandbox_network = setup.sandbox_network;
    opts.model_proxy = setup.model_proxy;
    opts.egress = setup.egress;
    if let Some(sandbox) = setup.sandbox {
        opts.sandbox = sandbox;
    }
    if let Some(pool) = setup.pool {
        opts.pool = pool;
    }
    if let Some(binary) = setup.bridge {
        let mut bridge = BridgeOptions::new(binary);
        bridge.connect_timeout = Duration::from_secs(30);
        opts.bridge = Some(bridge);
    }
    if let Some(timeout) = setup.inactivity_timeout {
        opts.inactivity_timeout = timeout;
    }
    if let Some(timeout) = setup.repository_request_timeout {
        opts.repository_request_timeout = timeout;
    }
    if let Some(timeout) = setup.tool_request_timeout {
        opts.tool_request_timeout = timeout;
    }
    if let Some(every) = setup.maintenance_every {
        opts.maintenance.initial_delay = every;
        opts.maintenance.interval = every;
    }
    // The tests use file:// bare repositories.
    opts.allow_insecure_repo_urls = true;
    opts.reconnect_min_backoff = Duration::from_millis(100);
    opts.reconnect_max_backoff = Duration::from_millis(500);
    opts.cancel_grace = Duration::from_secs(5);
    let mut config = HostConfig::load(&paths).unwrap();
    // As `run --protocol-version` would.
    config.protocol_version = setup.protocol_version;
    let host = Shared::new(opts, config);
    let shutdown = CancellationToken::new();
    let handle = tokio::spawn(host::serve(host.clone(), shutdown.clone()));
    TestHost {
        dir,
        paths,
        host,
        shutdown,
        handle,
    }
}

/// One repository of a test run (`run.assign.workspaces[]`).
#[derive(Debug, Clone)]
pub struct WorkspaceEntry {
    pub component_id: Uuid,
    pub dir: String,
    pub name: String,
    pub repo_url: String,
    /// The git token sent with it; `None` sends no credentials.
    pub token: Option<String>,
    /// `readOnly`: the host must never push it.
    pub read_only: bool,
}

impl WorkspaceEntry {
    pub fn new(dir: &str, name: &str, repo_url: &str) -> WorkspaceEntry {
        WorkspaceEntry {
            component_id: Uuid::new_v4(),
            dir: dir.into(),
            name: name.into(),
            repo_url: repo_url.into(),
            token: Some(TEST_GIT_TOKEN.into()),
            read_only: false,
        }
    }
}

pub struct AssignSpec<'a> {
    pub run_id: Uuid,
    pub epoch: i64,
    pub agent_id: Uuid,
    pub task_id: Uuid,
    pub task_key: &'a str,
    pub scenario: &'a str,
    /// Appended to the prompt (markers such as `[commit:<dir>]`).
    pub prompt_extra: String,
    pub workspaces: Vec<WorkspaceEntry>,
    /// `run.assign.requestableRepositories`.
    pub requestable: Vec<Value>,
    pub credential: &'a str,
    pub runtime: &'a str,
    /// `run.assign.agent.model`.
    pub model: Option<&'a str>,
    /// v2: `run.assign.agent.effort` (`LOW` … `MAX`).
    pub effort: Option<&'a str>,
    pub max_turns: u32,
    pub resume_session_id: Option<String>,
    pub resume_prompt: Option<String>,
    pub attachments: Vec<Value>,
    /// `run.assign.modelAccess`; when set, the frame has no `credentialLabel` (ADR-0027).
    pub model_access: Option<Value>,
    /// The protocol version of the frame: 1 (`task`) or 2 (`workItem`, `job`, `tools`).
    pub protocol_version: u32,
    /// v2: `run.assign.tools`, the server-executed tools of the run.
    pub tools: Vec<Value>,
}

impl<'a> AssignSpec<'a> {
    /// A run with one repository (`/workspace/repo`, named `Repo`) or none.
    pub fn new(scenario: &'a str, repo_url: Option<&str>) -> AssignSpec<'a> {
        AssignSpec::with_workspaces(
            scenario,
            repo_url
                .map(|url| WorkspaceEntry::new("repo", "Repo", url))
                .into_iter()
                .collect(),
        )
    }

    pub fn with_workspaces(scenario: &'a str, workspaces: Vec<WorkspaceEntry>) -> AssignSpec<'a> {
        AssignSpec {
            run_id: Uuid::new_v4(),
            epoch: 1,
            agent_id: Uuid::new_v4(),
            task_id: Uuid::new_v4(),
            task_key: "TEST-1",
            scenario,
            prompt_extra: String::new(),
            workspaces,
            requestable: Vec::new(),
            credential: "claude-test",
            runtime: "CLAUDE_CODE",
            model: None,
            effort: None,
            max_turns: 200,
            resume_session_id: None,
            resume_prompt: None,
            attachments: Vec::new(),
            model_access: None,
            protocol_version: 1,
            tools: Vec::new(),
        }
    }

    /// A v2 assignment (`workItem` and `job` in place of `task`) with the fake Jira tools
    /// `jira_get` and `jira_search`.
    pub fn v2(scenario: &'a str, repo_url: Option<&str>) -> AssignSpec<'a> {
        let mut spec = AssignSpec::new(scenario, repo_url);
        spec.protocol_version = 2;
        spec.tools = vec![
            json!({
                "name": "jira_get",
                "description": "Reads one issue of the Jira connection `Jira` by key.",
                "inputSchema": {"type": "object", "properties": {"key": {"type": "string"}}, "required": ["key"]}
            }),
            json!({
                "name": "jira_search",
                "description": "Searches the issues of the Jira connection `Jira` with JQL.",
                "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}, "limit": {"type": "integer"}}, "required": ["query"]}
            }),
        ];
        spec
    }

    /// The run uses a company model credential of this format instead of a host credential.
    pub fn with_model_access(mut self, format: &str) -> AssignSpec<'a> {
        self.model_access = Some(model_access(format));
        self
    }

    pub fn to_json(&self) -> Value {
        let workspaces: Vec<Value> = self
            .workspaces
            .iter()
            .map(|w| {
                json!({
                    "componentId": w.component_id,
                    "dir": w.dir,
                    "name": w.name,
                    "repoUrl": w.repo_url,
                    "defaultBranch": "main",
                    "branch": format!("agent/{}", self.task_key),
                    "git": w.token.as_ref().map(|t| json!({"username": "x-access-token", "token": t})),
                    "authorName": "Tinfirm agent Tester",
                    "authorEmail": format!("agent+{}@tinfirm.invalid", self.agent_id),
                    "readOnly": w.read_only
                })
            })
            .collect();
        let mut assign = json!({
            "type": "run.assign",
            "runId": self.run_id,
            "epoch": self.epoch,
            "credentialLabel": self.credential,
            "agent": {"id": self.agent_id, "name": "Tester", "runtime": self.runtime, "model": self.model},
            "task": {"id": self.task_id, "key": self.task_key, "title": "Test task"},
            "prompt": format!("[scenario:{}] Do the test task.{}\nMore prompt text.", self.scenario, self.prompt_extra),
            "resumeSessionId": self.resume_session_id,
            "workspaces": workspaces,
            "requestableRepositories": self.requestable,
            "limits": {"maxWallClockSeconds": 600, "maxTurns": self.max_turns},
            "resumePrompt": self.resume_prompt,
            "futureField": 42
        });
        if !self.attachments.is_empty() {
            assign["attachments"] = Value::Array(self.attachments.clone());
        }
        if let Some(access) = &self.model_access {
            assign.as_object_mut().unwrap().remove("credentialLabel");
            assign["modelAccess"] = access.clone();
        }
        if self.protocol_version >= 2 {
            let subject = assign.as_object_mut().unwrap().remove("task").unwrap();
            assign["workItem"] = subject;
            assign["job"] = json!({"id": Uuid::new_v4()});
            if let Some(effort) = self.effort {
                assign["agent"]["effort"] = json!(effort);
            }
            if !self.tools.is_empty() {
                assign["tools"] = Value::Array(self.tools.clone());
            }
        }
        assign
    }
}

/// `run.assign.modelAccess` of a company model credential with the test key.
pub fn model_access(format: &str) -> Value {
    json!({"format": format, "baseUrl": TEST_MODEL_BASE_URL, "apiKey": TEST_MODEL_KEY})
}

/// Lets a run of the `gate` scenario end its turn.
pub fn release(paths: &Paths, run_id: Uuid) {
    // Over the bridge, the agent's workspace is the sandbox's (crate::sandbox_files).
    let workspace = paths.workspace(run_id);
    let sandbox = tinfirm_runner_host::sandbox_files::Layout::local(&workspace).workspace;
    let dir = if Path::new(&sandbox).exists() {
        PathBuf::from(sandbox)
    } else {
        workspace
    };
    std::fs::write(dir.join("release"), "go\n").unwrap();
}

/// A `requestableRepositories` entry.
pub fn requestable(name: &str, pending: bool) -> Value {
    json!({
        "componentId": Uuid::new_v4(),
        "name": name,
        "repoUrl": format!("https://git.example.invalid/acme/{}.git", name.to_lowercase()),
        "pendingRequest": pending
    })
}

/// The server's answer to a `run.task_create` frame.
pub fn task_ack(request: &Value, status: &str, code: Option<&str>, message: &str) -> Value {
    let mut ack = json!({
        "type": "run.task_create.ack",
        "runId": request["runId"],
        "requestId": request["requestId"],
        "status": status,
        "message": message
    });
    if status == "CREATED" {
        ack["taskId"] = json!(Uuid::new_v4());
        ack["taskKey"] = json!("ACME-8");
    }
    if let Some(code) = code {
        ack["code"] = json!(code);
    }
    ack
}

/// The server's answer to a `run.ask` frame.
pub fn ask_ack(request: &Value, status: &str, code: Option<&str>, message: &str) -> Value {
    let mut ack = json!({
        "type": "run.ask.ack",
        "runId": request["runId"],
        "requestId": request["requestId"],
        "status": status,
        "message": message
    });
    if status == "PENDING" {
        ack["askId"] = json!(Uuid::new_v4());
    }
    if let Some(code) = code {
        ack["code"] = json!(code);
    }
    ack
}

/// The server's answer to a v2 `run.request` frame: `payload` by status and kind (a decision
/// for PENDING, an artifact or a tool result for DONE).
pub fn request_ack(
    request: &Value,
    status: &str,
    payload: Option<Value>,
    code: Option<&str>,
    message: Option<&str>,
) -> Value {
    let mut ack = json!({
        "type": "run.request.ack",
        "runId": request["runId"],
        "requestId": request["requestId"],
        "status": status,
    });
    if let Some(payload) = payload {
        ack["payload"] = payload;
    }
    if let Some(code) = code {
        ack["code"] = json!(code);
    }
    if let Some(message) = message {
        ack["message"] = json!(message);
    }
    ack
}

/// The server's answer to a `run.repository_request` frame.
pub fn repository_ack(request: &Value, status: &str, code: Option<&str>, message: &str) -> Value {
    let mut ack = json!({
        "type": "run.repository_request.ack",
        "runId": request["runId"],
        "requestId": request["requestId"],
        "status": status,
        "message": message
    });
    if status == "PENDING" {
        ack["repositoryRequestId"] = json!(Uuid::new_v4());
    }
    if let Some(code) = code {
        ack["code"] = json!(code);
    }
    ack
}

/// The frames of one run, from every frame a connection received.
pub fn run_frames(conn: &Conn, run_id: Uuid) -> Transcript {
    let id = run_id.to_string();
    Transcript {
        frames: conn
            .frames
            .iter()
            .filter(|f| f["runId"] == id)
            .cloned()
            .collect(),
    }
}

/// Runs a test body with an overall timeout.
pub async fn with_timeout<F: std::future::Future<Output = T>, T>(secs: u64, f: F) -> T {
    tokio::time::timeout(Duration::from_secs(secs), f)
        .await
        .expect("test timed out")
}

/// A run whose agent fetches through the git proxy while `main` moves on, reads the new
/// file (a lazy blob fetch), and tries to push. The repository is served over smart HTTP
/// and requires the host's `Authorization`, so every fetch must go through the proxy.
pub async fn run_fetch_scenario(setup: HostSetup) -> Transcript {
    use git_http::{GitHttpServer, test_git_authorization};
    let repo = TestRepo::new();
    let authorization = test_git_authorization();
    let git_server = GitHttpServer::start(repo.dir.path(), Some(authorization.clone())).await;
    let repo_url = format!("{}/origin.git", git_server.url);
    let mut server = FakeServer::start().await;
    let host = start_host(&server.url, setup).await;
    let mut conn = server.accept().await;
    conn.handshake(5).await;
    let spec = AssignSpec::new("fetch", Some(&repo_url));
    conn.send(spec.to_json());
    loop {
        let frame = conn.next().await.expect("connection open");
        if frame["type"] == "run.finished" {
            panic!("the run finished before the agent fetched: {frame:#}");
        }
        if frame["eventType"] == "AGENT_MESSAGE"
            && frame["payload"]["text"]
                .as_str()
                .unwrap()
                .contains("waiting for upstream")
        {
            break;
        }
    }
    repo.commit_to_main("upstream.txt", "from upstream\n", "Upstream change");
    conn.until_finished(spec.run_id).await;
    let t = run_frames(&conn, spec.run_id);
    t.assert_contiguous_seq();
    t.assert_no_secrets();
    let finished = t.finished();
    let text = t.agent_text();
    // The proxy URL (with the run secret) is masked, and printing it is no leak.
    assert_eq!(finished["outcome"], "SUCCEEDED", "{finished:#}\n{text}");
    assert!(text.contains("/[REDACTED]/origin.git"), "{text}");
    assert!(text.contains("fetched: from upstream"), "{text}");
    assert!(text.contains("log: Upstream change"), "{text}");
    assert!(text.contains("push failed:"), "{text}");
    assert!(
        text.contains(
            "pushing from the sandbox is not allowed; the runner host pushes agent/TEST-1 when the run ends"
        ),
        "{text}"
    );
    assert!(repo.branch_head("agent/pushed-from-sandbox").is_none());
    // The host still pushes over the real URL, with its own credentials.
    assert_eq!(finished["git"][0]["pushed"], true);
    assert_eq!(
        repo.file_at("agent/TEST-1", "fetch.txt").as_deref(),
        Some("written after the fetch\n")
    );
    // Every request that reached the repository carried the host's header; none pushed
    // except the host's own push at the end.
    let seen = git_server.seen();
    assert!(
        seen.iter()
            .all(|r| r.authorization.as_deref() == Some(authorization.as_str()))
    );
    let pushes: Vec<_> = seen
        .iter()
        .filter(|r| r.path.ends_with("git-receive-pack") || r.query.contains("receive-pack"))
        .collect();
    assert!(!pushes.is_empty(), "the host pushes");
    let proxied_fetches = seen
        .iter()
        .filter(|r| r.path.ends_with("/git-upload-pack") && r.git_protocol.is_some())
        .count();
    assert!(proxied_fetches >= 3, "{seen:#?}");
    // The run is over: its route is gone.
    let proxy = host
        .host
        .git_proxy()
        .await
        .unwrap()
        .expect("the proxy was started");
    assert_eq!(proxy.route_count(), 0);
    host.shutdown.cancel();
    host.handle.await.unwrap().unwrap();
    t
}
