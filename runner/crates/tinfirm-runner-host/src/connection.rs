//! The WebSocket connection to the server (docs/archive/v1/CONVENTIONS.md §15.1, §15.4 step 11).
//!
//! `hello` → `welcome` → `host.config` (answered with `host.config.applied`, §15.7), then
//! messages both ways, `run.heartbeat` while runs are active and WebSocket pings to notice
//! dead connections. On any failure the host reconnects with exponential backoff (1 s → 30 s,
//! with jitter; at most [`HOLDING_RUNS_MAX_BACKOFF`] while it holds runs) while runs keep
//! going; their events are buffered in the [`Outbox`](crate::outbox::Outbox) and flushed
//! after the next `welcome`. Close code 4401
//! (bad token) and 4409 (replaced by another connection) are fatal. When everything is
//! delivered after a shutdown or a drain, the host closes with 1000.
//!
//! The protocol version is the host's ([`Shared::protocol_version`]): `hello` names it, and
//! frames are parsed with that version's message types, so a frame of the other version is
//! answered with `error` `UNKNOWN_TYPE`. A host that speaks v2 needs a `welcome` that says
//! `protocolVersion: 2`; one without the field, or with another version, is a server that
//! does not speak v2, and the host ends the connection with an `error` and close code 1002
//! instead of going on in v1 ([`Fatal`]: no silent downgrade within a session).
//!
//! Heartbeats renew the leases of the active runs, and a run whose lease runs out is lost
//! (docs/archive/v1/CONVENTIONS.md §14.4), so they go out on time whatever else is going on:
//! - `run.heartbeat` belongs to no run's order, so it overtakes the outbox. One goes out
//!   right after `welcome`, before the backlog of a reconnect; later ones go out between two
//!   frames of the outbox as soon as they are due.
//! - The outbox is written in batches ([`WRITE_BATCH_BYTES`], [`WRITE_BATCH_TIME`]), and
//!   the loop reads, pings and checks its heartbeat between two batches.
//! - Little waits below the outbox, where a heartbeat cannot overtake it: the WebSocket
//!   buffer is flushed every [`FLUSH_BYTES`], and the kernel's send buffer is small
//!   ([`SEND_BUFFER_BYTES`]).
//! - Server messages are handled without waiting ([`Shared::handle_message`]); what waits
//!   for Docker or another task runs in a task of its own.
//! - Heartbeats go out every `heartbeatIntervalSeconds`, but at least three times per
//!   `leaseTtlSeconds` ([`Shared::heartbeat_interval`]).
//!
//! [`Shared::handle_message`]: crate::host::Shared::handle_message
//! [`Shared::heartbeat_interval`]: crate::host::Shared::heartbeat_interval
//! [`Shared::protocol_version`]: crate::host::Shared::protocol_version

use std::future::Future;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow};
use futures::{SinkExt, StreamExt};
use tinfirm_protocol::{ErrorMessage, Message, RunHeartbeat};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::{TcpSocket, TcpStream};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::handshake::client::Request;
use tokio_tungstenite::tungstenite::http::HeaderValue;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::{CloseFrame, WebSocketConfig};
use tokio_tungstenite::tungstenite::{self, Message as WsMessage};
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream};
use tokio_util::sync::CancellationToken;

use crate::host::Host;

type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;

pub const CLOSE_BAD_TOKEN: u16 = 4401;
pub const CLOSE_REPLACED: u16 = 4409;
/// Frames above 1 MiB are closed with 1009 by the server; never send one near that.
pub const MAX_FRAME_BYTES: usize = 900 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(20);
const WELCOME_TIMEOUT: Duration = Duration::from_secs(30);
const PING_INTERVAL: Duration = Duration::from_secs(15);
const SILENCE_LIMIT: Duration = Duration::from_secs(45);
/// The most bytes of the outbox written in one go; then the loop reads, pings and checks
/// the drain before it writes more.
const WRITE_BATCH_BYTES: usize = 256 * 1024;
/// The longest the outbox is written in one go.
const WRITE_BATCH_TIME: Duration = Duration::from_millis(250);
/// The writer flushes the WebSocket after this many bytes, so that a heartbeat never waits
/// behind more of them.
const FLUSH_BYTES: usize = 64 * 1024;
/// The kernel's send buffer of the connection. What waits there goes out before anything
/// written later, heartbeats included; a larger backlog waits in the outbox instead, which
/// heartbeats overtake. At 50 ms round trip this still carries about 5 MB/s.
const SEND_BUFFER_BYTES: u32 = 256 * 1024;
/// A frame, flush or close that takes longer than this ends the connection: the link is
/// dead or too slow to keep the leases (the host reconnects).
const WRITE_TIMEOUT: Duration = SILENCE_LIMIT;
/// The longest wait between two connection attempts while the host holds runs. The server
/// keeps a host's runs for a grace period after a disconnect (2 minutes by default,
/// docs/archive/v1/CONVENTIONS.md §15.1); they should be back well within it.
const HOLDING_RUNS_MAX_BACKOFF: Duration = Duration::from_secs(5);

enum Ended {
    /// Everything was delivered after a drain request.
    Drained,
    /// The connection was lost; `welcomed` says whether it had been established.
    Lost { reason: String, welcomed: bool },
}

#[derive(Debug, thiserror::Error)]
#[error("{0}")]
pub struct Fatal(pub String);

/// Installs the rustls crypto provider (once per process).
pub fn init_tls() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

/// Runs the connection until `drain` is requested and everything is delivered, or a fatal
/// error happens.
pub async fn run(host: Host, drain: CancellationToken) -> Result<()> {
    init_tls();
    let url = host.config.websocket_url()?;
    let mut backoff = host.opts.reconnect_min_backoff;
    loop {
        // After a shutdown, what was written before the connection broke counts as sent.
        // A draining host connects once more while it holds recent runs: their last frames
        // may have been lost with the connection, and the next one sends them again before
        // it closes with 1000.
        if drain.is_cancelled()
            && host.outbox.all_finished_sent()
            && host.outbox.is_drained()
            && !(host.is_draining() && host.outbox.holds_runs())
        {
            return Ok(());
        }
        let ended = session(&host, url.as_str(), &drain).await;
        host.on_disconnect();
        if matches!(ended, Ok(Ended::Lost { welcomed: true, .. })) {
            backoff = host.opts.reconnect_min_backoff;
        }
        let delay = retry_delay(backoff, holds_runs(&host));
        match ended {
            Ok(Ended::Drained) => return Ok(()),
            Ok(Ended::Lost { reason, .. }) => {
                tracing::warn!(%reason, retry_in_ms = delay.as_millis() as u64, "connection to the server lost");
            }
            Err(e) => {
                if e.downcast_ref::<Fatal>().is_some() {
                    tracing::error!(error = %e, "fatal connection error");
                    return Err(e);
                }
                tracing::warn!(error = %format!("{e:#}"), retry_in_ms = delay.as_millis() as u64, "cannot connect to the server");
            }
        }
        let jitter = Duration::from_millis((uuid::Uuid::new_v4().as_u128() % 250) as u64);
        tokio::time::sleep(delay + jitter).await;
        backoff = (backoff * 2).min(host.opts.reconnect_max_backoff);
    }
}

/// `true` while the host has runs the server keeps for it: runs being admitted or executed,
/// and finished runs whose end may not have reached the server.
fn holds_runs(host: &Host) -> bool {
    host.active_run_count() > 0 || host.outbox.holds_runs()
}

/// The wait before the next connection attempt: the backoff, but at most
/// [`HOLDING_RUNS_MAX_BACKOFF`] while the host holds runs.
fn retry_delay(backoff: Duration, holding_runs: bool) -> Duration {
    if holding_runs {
        backoff.min(HOLDING_RUNS_MAX_BACKOFF)
    } else {
        backoff
    }
}

async fn connect(host: &Host, url: &str) -> Result<Socket> {
    let mut request = url.into_client_request().context("invalid server URL")?;
    let bearer = format!("Bearer {}", host.config.token.expose());
    let mut auth =
        HeaderValue::from_str(&bearer).context("the host token contains invalid characters")?;
    auth.set_sensitive(true);
    request.headers_mut().insert("Authorization", auth);
    request.headers_mut().insert(
        "User-Agent",
        HeaderValue::from_str(&format!("tinfirm-runner-host/{}", host.opts.runner_version))?,
    );
    let config = WebSocketConfig::default()
        .max_message_size(Some(32 << 20))
        .max_frame_size(Some(32 << 20));
    let connecting = async {
        let stream = tcp_connect(&request).await?;
        tokio_tungstenite::client_async_tls_with_config(request, stream, Some(config), None).await
    };
    match tokio::time::timeout(CONNECT_TIMEOUT, connecting).await {
        Err(_) => Err(anyhow!("connecting to {url} timed out")),
        Ok(Ok((socket, _response))) => Ok(socket),
        Ok(Err(tungstenite::Error::Http(response))) => {
            let status = response.status().as_u16();
            if status == 401 || status == 403 {
                Err(Fatal(format!(
                    "the server rejected the runner host token (HTTP {status}); register the host again and run `login`"
                ))
                .into())
            } else {
                Err(anyhow!(
                    "the server answered the WebSocket upgrade with HTTP {status}"
                ))
            }
        }
        Ok(Err(e)) => Err(anyhow!(e).context(format!("cannot connect to {url}"))),
    }
}

/// The TCP connection to the server's host and port, with a send buffer of
/// [`SEND_BUFFER_BYTES`].
async fn tcp_connect(request: &Request) -> Result<TcpStream, tungstenite::Error> {
    let uri = request.uri();
    let host = uri.host().unwrap_or_default();
    // An IPv6 address comes in brackets.
    let host = host
        .strip_prefix('[')
        .and_then(|h| h.strip_suffix(']'))
        .unwrap_or(host);
    let port = uri
        .port_u16()
        .unwrap_or(if uri.scheme_str() == Some("wss") {
            443
        } else {
            80
        });
    let mut last_error = None;
    for addr in tokio::net::lookup_host((host, port)).await? {
        let socket = if addr.is_ipv4() {
            TcpSocket::new_v4()?
        } else {
            TcpSocket::new_v6()?
        };
        if let Err(e) = socket.set_send_buffer_size(SEND_BUFFER_BYTES) {
            tracing::debug!(error = %e, "cannot set the send buffer size");
        }
        match socket.connect(addr).await {
            Ok(stream) => return Ok(stream),
            Err(e) => last_error = Some(e),
        }
    }
    Err(last_error
        .unwrap_or_else(|| {
            std::io::Error::new(
                std::io::ErrorKind::NotFound,
                format!("no address for {host}"),
            )
        })
        .into())
}

/// The welcome's version against the host's: a v1 host takes any welcome (a v1 server sends
/// no version; a v2 server echoes 1); a v2 host needs a welcome that says 2. `Err` names the
/// mismatch.
fn check_welcome_version(host_version: u32, welcome_version: Option<u32>) -> Result<(), String> {
    if host_version < tinfirm_protocol::PROTOCOL_VERSION_V2 {
        return Ok(());
    }
    match welcome_version {
        Some(v) if v == host_version => Ok(()),
        Some(v) => Err(format!(
            "this runner host speaks runner protocol {host_version} (hello.protocolVersion), but the server answered with a welcome for protocol {v}; run `tinfirm-runner-host run --protocol-version {v}` for this server, or upgrade the server"
        )),
        None => Err(format!(
            "this runner host speaks runner protocol {host_version} (hello.protocolVersion), but the server answered with a welcome without protocolVersion, as a server that speaks protocol 1 only does; run `tinfirm-runner-host run --protocol-version 1` for this server, or upgrade the server"
        )),
    }
}

/// Close codes after which reconnecting cannot help.
fn close_error(frame: Option<&CloseFrame>) -> Option<Fatal> {
    let frame = frame?;
    let reason = &frame.reason;
    match u16::from(frame.code) {
        CLOSE_BAD_TOKEN => Some(Fatal(format!(
            "the server rejected the runner host token (close 4401: {reason}); register the host again and run `login`"
        ))),
        CLOSE_REPLACED => Some(Fatal(format!(
            "another connection for this runner host replaced this one (close 4409: {reason}); not reconnecting"
        ))),
        1002 => Some(Fatal(format!(
            "the server does not support this runner protocol version (close 1002: {reason}); update tinfirm-runner-host, or run it with --protocol-version 1 for a server that speaks v1 only"
        ))),
        1008 => Some(Fatal(format!(
            "the server refused this runner host's hello (close 1008: {reason})"
        ))),
        _ => None,
    }
}

/// Queues one frame on the WebSocket (a flush writes it); returns its size.
async fn write<S>(socket: &mut WebSocketStream<S>, message: &Message) -> Result<usize>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let json = message.to_json();
    if json.len() > MAX_FRAME_BYTES {
        // Events are capped far below this; anything this large is a bug. Sending it would
        // get the connection closed (1009) and resent after the reconnect, forever.
        tracing::error!(
            message_type = message.message_type(),
            bytes = json.len(),
            "dropping an oversized frame"
        );
        return Ok(0);
    }
    let bytes = json.len();
    in_time(socket.feed(WsMessage::text(json))).await?;
    Ok(bytes)
}

async fn flush<S>(socket: &mut WebSocketStream<S>) -> Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    in_time(socket.flush()).await
}

/// A write that ends within [`WRITE_TIMEOUT`].
async fn in_time(write: impl Future<Output = Result<(), tungstenite::Error>>) -> Result<()> {
    match tokio::time::timeout(WRITE_TIMEOUT, write).await {
        Ok(result) => Ok(result?),
        Err(_) => Err(anyhow!(
            "the write did not end within {} s",
            WRITE_TIMEOUT.as_secs()
        )),
    }
}

fn lost(reason: String) -> Ended {
    Ended::Lost {
        reason,
        welcomed: true,
    }
}

/// When `run.heartbeat` is due. It renews the leases of the active runs.
struct Heartbeat {
    every: Duration,
    next: tokio::time::Instant,
}

impl Heartbeat {
    fn new(every: Duration) -> Heartbeat {
        Heartbeat {
            every,
            next: tokio::time::Instant::now(),
        }
    }

    fn is_due(&self) -> bool {
        tokio::time::Instant::now() >= self.next
    }

    /// Writes and flushes a heartbeat now when runs are active, and schedules the next one.
    async fn send<S>(&mut self, socket: &mut WebSocketStream<S>, host: &Host) -> Result<(), Ended>
    where
        S: AsyncRead + AsyncWrite + Unpin,
    {
        self.next = tokio::time::Instant::now() + self.every;
        let runs = host.heartbeat_runs();
        if runs.is_empty() {
            return Ok(());
        }
        let beat = Message::RunHeartbeat(RunHeartbeat { runs });
        write(socket, &beat)
            .await
            .map_err(|e| lost(format!("heartbeat write failed: {e:#}")))?;
        flush(socket)
            .await
            .map_err(|e| lost(format!("heartbeat write failed: {e:#}")))
    }
}

/// Writes what waits in the outbox, in order, until it is empty or [`WRITE_BATCH_BYTES`] or
/// [`WRITE_BATCH_TIME`] are used up. A heartbeat that falls due meanwhile goes out between
/// two frames. Returns whether frames still wait.
async fn write_batch<S>(
    socket: &mut WebSocketStream<S>,
    host: &Host,
    heartbeat: &mut Heartbeat,
) -> Result<bool, Ended>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let started = tokio::time::Instant::now();
    let mut written = 0;
    let mut unflushed = 0;
    loop {
        if heartbeat.is_due() {
            heartbeat.send(socket, host).await?;
        }
        if written >= WRITE_BATCH_BYTES || started.elapsed() >= WRITE_BATCH_TIME {
            break;
        }
        // Taken one at a time: what is not written yet stays in the outbox.
        let Some(message) = host.outbox.take_next() else {
            break;
        };
        let bytes = write(socket, &message)
            .await
            .map_err(|e| lost(format!("write failed: {e:#}")))?;
        written += bytes;
        unflushed += bytes;
        if unflushed >= FLUSH_BYTES {
            flush(socket)
                .await
                .map_err(|e| lost(format!("flush failed: {e:#}")))?;
            unflushed = 0;
        }
    }
    if unflushed > 0 {
        flush(socket)
            .await
            .map_err(|e| lost(format!("flush failed: {e:#}")))?;
    }
    Ok(!host.outbox.is_drained())
}

async fn session(host: &Host, url: &str, drain: &CancellationToken) -> Result<Ended> {
    let socket = connect(host, url).await?;
    converse(host, socket, drain).await
}

/// `hello`, `welcome`, then messages both ways until the connection ends, or until
/// everything is delivered after `drain`.
async fn converse<S>(
    host: &Host,
    mut socket: WebSocketStream<S>,
    drain: &CancellationToken,
) -> Result<Ended>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let version = host.protocol_version();
    let hello = Message::Hello(host.hello().await);
    write(&mut socket, &hello).await?;
    flush(&mut socket).await?;

    // Wait for welcome.
    let deadline = tokio::time::Instant::now() + WELCOME_TIMEOUT;
    let mut early = Vec::new();
    let mut edition_required = None;
    let welcome = loop {
        let frame = tokio::time::timeout_at(deadline, socket.next())
            .await
            .map_err(|_| anyhow!("no welcome within {} s", WELCOME_TIMEOUT.as_secs()))?;
        match frame {
            Some(Ok(WsMessage::Text(text))) => match Message::parse_version(&text, version) {
                Ok(Message::Welcome(welcome)) => break welcome,
                Ok(Message::Error(error)) => {
                    tracing::warn!(code = %error.code, message = %error.message, "server error before welcome");
                    if error.code == tinfirm_protocol::EDITION_REQUIRED {
                        edition_required = Some(error.message);
                    }
                }
                Ok(other) => early.push(other),
                Err(e) => tracing::warn!(error = %e, "unreadable frame before welcome"),
            },
            Some(Ok(WsMessage::Close(frame))) => {
                // The server's licence does not take this host (ADR-0100): another connection
                // gets the same answer.
                if let Some(message) = edition_required {
                    return Err(Fatal(format!(
                        "the server refused this runner host's hello (EDITION_REQUIRED): {message}"
                    ))
                    .into());
                }
                if let Some(fatal) = close_error(frame.as_ref()) {
                    return Err(fatal.into());
                }
                return Ok(Ended::Lost {
                    reason: format!("closed before welcome: {frame:?}"),
                    welcomed: false,
                });
            }
            Some(Ok(_)) => {}
            Some(Err(e)) => return Err(anyhow!(e).context("connection failed before welcome")),
            None => {
                return Ok(Ended::Lost {
                    reason: "closed before welcome".into(),
                    welcomed: false,
                });
            }
        }
    };
    if let Err(mismatch) = check_welcome_version(version, welcome.protocol_version) {
        // No downgrade within a session: the server does not speak the version this host
        // was started with. Say so, close with 1002, and stop (the operator picks a version).
        tracing::error!(error = %mismatch, "protocol version mismatch");
        let error = Message::Error(ErrorMessage {
            code: "UNSUPPORTED_PROTOCOL".into(),
            message: mismatch.clone(),
            run_id: None,
            epoch: None,
        });
        if write(&mut socket, &error).await.is_ok() {
            let _ = flush(&mut socket).await;
        }
        let close = socket.close(Some(CloseFrame {
            code: CloseCode::Protocol,
            reason: "protocol version mismatch".into(),
        }));
        let _ = tokio::time::timeout(WRITE_TIMEOUT, close).await;
        return Err(Fatal(mismatch).into());
    }
    host.on_welcome(&welcome);
    host.outbox.on_reconnect();
    let mut heartbeat = Heartbeat::new(host.heartbeat_interval());
    tracing::info!(
        runner_host_id = %welcome.runner_host_id,
        heartbeat_s = welcome.heartbeat_interval_seconds,
        lease_s = welcome.lease_ttl_seconds,
        heartbeat_every_ms = heartbeat.every.as_millis() as u64,
        "connected to the server"
    );
    // At once: after a reconnect the leases may be close to their end, and the outbox may
    // hold a backlog that takes a while.
    if let Err(ended) = heartbeat.send(&mut socket, host).await {
        return Ok(ended);
    }
    {
        let host = host.clone();
        tokio::spawn(async move { host.cleanup_orphans().await });
    }
    for message in early {
        host.handle_message(message);
    }

    let mut ping = tokio::time::interval(PING_INTERVAL);
    ping.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    ping.tick().await;
    let mut last_received = Instant::now();
    let mut draining = drain.is_cancelled();

    loop {
        // Write what is pending, in order, a batch at a time.
        let pending = match write_batch(&mut socket, host, &mut heartbeat).await {
            Ok(pending) => pending,
            Err(ended) => return Ok(ended),
        };
        if draining && host.outbox.all_finished_sent() && host.outbox.is_drained() {
            let reason = if host.is_draining() {
                "runner host drained"
            } else {
                "runner host shutting down"
            };
            let close = socket.close(Some(CloseFrame {
                code: CloseCode::Normal,
                reason: reason.into(),
            }));
            let _ = tokio::time::timeout(WRITE_TIMEOUT, close).await;
            return Ok(Ended::Drained);
        }
        tokio::select! {
            biased;
            frame = socket.next() => {
                last_received = Instant::now();
                match frame {
                    Some(Ok(WsMessage::Text(text))) => match Message::parse_version(&text, version) {
                        Ok(message) => host.handle_message(message),
                        Err(e) => {
                            tracing::warn!(error = %e, "cannot process a frame from the server");
                            let is_error = text.contains("\"type\":\"error\"");
                            if !is_error {
                                host.outbox.push_control(Message::Error(ErrorMessage {
                                    code: e.code().to_string(),
                                    message: e.to_string(),
                                    run_id: None,
                                    epoch: None,
                                }));
                            }
                        }
                    },
                    Some(Ok(WsMessage::Close(frame))) => {
                        if let Some(fatal) = close_error(frame.as_ref()) {
                            return Err(fatal.into());
                        }
                        return Ok(lost(format!("server closed the connection: {frame:?}")));
                    }
                    Some(Ok(_)) => {}
                    Some(Err(e)) => return Ok(lost(format!("read failed: {e}"))),
                    None => return Ok(lost("connection closed".into())),
                }
            }
            _ = tokio::time::sleep_until(heartbeat.next) => {
                if let Err(ended) = heartbeat.send(&mut socket, host).await {
                    return Ok(ended);
                }
            }
            _ = ping.tick() => {
                if last_received.elapsed() > SILENCE_LIMIT {
                    return Ok(lost("no traffic from the server".into()));
                }
                if in_time(socket.send(WsMessage::Ping(Vec::new().into()))).await.is_err() {
                    return Ok(lost("ping failed".into()));
                }
            }
            _ = drain.cancelled(), if !draining => {
                draining = true;
            }
            // More of the outbox waits: write the next batch.
            _ = std::future::ready(()), if pending => {}
            _ = host.outbox.notified(), if !pending => {}
            _ = tokio::time::sleep(Duration::from_millis(500)), if draining => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicU64, Ordering};

    use chrono::Utc;
    use serde_json::{Value, json};
    use tinfirm_protocol::{
        CredentialKind, EventPayload, RunAccepted, RunEvent, Secret, TextPayload, Welcome,
    };
    use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream};
    use tokio_tungstenite::tungstenite::protocol::Role;
    use uuid::Uuid;

    use super::*;
    use crate::config::HostConfig;
    use crate::credentials::{Credential, CredentialStore};
    use crate::host::{HostOptions, Shared};
    use crate::paths::Paths;
    use crate::sandbox::Executor;

    /// `welcome.heartbeatIntervalSeconds` in these tests.
    const INTERVAL: Duration = Duration::from_secs(1);
    /// How late a heartbeat may arrive: the frame being written when it falls due, and what
    /// the link buffers ahead of it.
    const SLACK: Duration = Duration::from_millis(500);

    // TEST-ONLY values; none of them is a real credential.
    const TEST_HOST_TOKEN: &str = "tfr_TESTONLYconnectiontestshosttoken0123456789ab";

    /// A host with the credential `claude-test`, not connected to anything.
    fn test_host(dir: &std::path::Path, executor: Executor) -> Host {
        let paths = Paths::new(dir.join("config"), dir.join("data"));
        let store = CredentialStore::new(&paths);
        store
            .save(&Credential {
                label: "claude-test".into(),
                kind: CredentialKind::ClaudeLogin,
                pool_size: 2,
                created_at: Utc::now(),
                secret: None,
            })
            .unwrap();
        // The login Claude Code would have written (ADR-0085), with a test-only token.
        let login = store.claude_login("claude-test");
        std::fs::create_dir_all(&login).unwrap();
        crate::secure_fs::write_private_file(
            &crate::credentials::claude_credentials_file(&login),
            br#"{"claudeAiOauth":{"accessToken":"sk-ant-oat01-TEST-ONLY-connection-tests-0123456789"}}"#,
        )
        .unwrap();
        let mut config = HostConfig::new("http://127.0.0.1:9", Secret::new(TEST_HOST_TOKEN));
        // These tests speak protocol v1 (a welcome without a version, `task`); the default is v2.
        config.protocol_version = 1;
        Shared::new(HostOptions::new(paths, executor), config)
    }

    fn local_executor() -> Executor {
        Executor::Local {
            program: "false".into(),
            args: Vec::new(),
        }
    }

    /// An in-memory connection like a slow tunnel: the host → server direction carries
    /// `bytes_per_s` through small buffers; the other direction is fast. Returns the host's
    /// end and the server's end.
    fn slow_link(bytes_per_s: f64) -> (DuplexStream, DuplexStream) {
        let (host_end, near) = tokio::io::duplex(16 * 1024);
        let (far, server_end) = tokio::io::duplex(16 * 1024);
        let (mut near_read, mut near_write) = tokio::io::split(near);
        let (mut far_read, mut far_write) = tokio::io::split(far);
        tokio::spawn(async move {
            let started = tokio::time::Instant::now();
            let mut carried = 0usize;
            let mut buf = vec![0u8; 4096];
            loop {
                let n = match near_read.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => n,
                };
                if far_write.write_all(&buf[..n]).await.is_err() {
                    break;
                }
                carried += n;
                let due = started + Duration::from_secs_f64(carried as f64 / bytes_per_s);
                tokio::time::sleep_until(due).await;
            }
        });
        tokio::spawn(async move {
            let _ = tokio::io::copy(&mut far_read, &mut near_write).await;
        });
        (host_end, server_end)
    }

    /// The server's end of a connection to `host`, which runs [`converse`] in a task.
    struct Server {
        ws: WebSocketStream<DuplexStream>,
        session: tokio::task::JoinHandle<Result<Ended>>,
    }

    impl Server {
        async fn start(
            host: &Host,
            (host_end, server_end): (DuplexStream, DuplexStream),
        ) -> Server {
            let session = tokio::spawn({
                let host = host.clone();
                async move {
                    let socket =
                        WebSocketStream::from_raw_socket(host_end, Role::Client, None).await;
                    converse(&host, socket, &CancellationToken::new()).await
                }
            });
            let ws = WebSocketStream::from_raw_socket(server_end, Role::Server, None).await;
            Server { ws, session }
        }

        /// The next text frame, and when it arrived.
        async fn next(&mut self) -> (Instant, Value) {
            loop {
                let frame = tokio::time::timeout(Duration::from_secs(30), self.ws.next())
                    .await
                    .expect("no frame from the host in time")
                    .expect("the connection is open")
                    .expect("a readable frame");
                if let WsMessage::Text(text) = frame {
                    return (Instant::now(), serde_json::from_str(&text).unwrap());
                }
            }
        }

        async fn send(&mut self, frame: Value) {
            self.ws
                .send(WsMessage::text(frame.to_string()))
                .await
                .unwrap();
        }

        /// Answers `hello` with `welcome`; returns when.
        async fn welcome(&mut self) -> Instant {
            let (_, hello) = self.next().await;
            assert_eq!(hello["type"], "hello");
            self.send(json!({
                "type": "welcome",
                "runnerHostId": Uuid::new_v4(),
                "companyId": Uuid::new_v4(),
                "heartbeatIntervalSeconds": INTERVAL.as_secs(),
                "leaseTtlSeconds": 30
            }))
            .await;
            Instant::now()
        }
    }

    impl Drop for Server {
        fn drop(&mut self) {
            self.session.abort();
        }
    }

    fn assign(run_id: Uuid) -> Value {
        json!({
            "type": "run.assign",
            "runId": run_id,
            "epoch": 1,
            "credentialLabel": "claude-test",
            "agent": {"id": Uuid::new_v4(), "name": "Tester", "runtime": "CLAUDE_CODE", "model": null},
            "task": {"id": Uuid::new_v4(), "key": "TEST-1", "title": "Test task"},
            "prompt": "Do the test task.",
            "resumeSessionId": null,
            "workspaces": [],
            "requestableRepositories": [],
            "limits": {"maxWallClockSeconds": 600, "maxTurns": 10},
            "resumePrompt": null
        })
    }

    /// The longest time between two consecutive `times`.
    fn longest_gap(times: &[Instant]) -> Duration {
        times
            .windows(2)
            .map(|w| w[1] - w[0])
            .max()
            .unwrap_or_default()
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn heartbeats_overtake_a_backlog_on_a_slow_connection() {
        let dir = tempfile::tempdir().unwrap();
        let host = test_host(dir.path(), local_executor());
        let run_id = Uuid::new_v4();
        host.register_test_run(run_id, 1, "claude-test");
        // After a reconnect, 4 MiB of tool output waits; at 1 MiB/s that takes 4 s, while
        // the lease needs a heartbeat every second.
        const EVENTS: u64 = 128;
        host.outbox.open_run(RunAccepted { run_id, epoch: 1 });
        for seq in 1..=EVENTS {
            host.outbox.push_event(RunEvent {
                run_id,
                epoch: 1,
                seq,
                at: Utc::now(),
                event: EventPayload::AgentMessage(TextPayload {
                    text: "x".repeat(32 * 1024),
                }),
            });
        }
        let mut server = Server::start(&host, slow_link(1024.0 * 1024.0)).await;
        let welcomed = server.welcome().await;
        let mut types = Vec::new();
        let mut beats = vec![welcomed];
        let mut seqs = Vec::new();
        while (seqs.len() as u64) < EVENTS {
            let (at, frame) = server.next().await;
            match frame["type"].as_str().unwrap() {
                "run.heartbeat" => {
                    assert_eq!(frame["runs"], json!([{"runId": run_id, "epoch": 1}]));
                    beats.push(at);
                }
                "run.event" => seqs.push(frame["seq"].as_u64().unwrap()),
                _ => {}
            }
            types.push(frame["type"].as_str().unwrap().to_string());
        }
        let done = Instant::now();
        // The first heartbeat goes out right after welcome, before the backlog; the run's
        // own frames keep their order.
        assert_eq!(types[..2], ["run.heartbeat", "run.accepted"]);
        assert_eq!(seqs, (1..=EVENTS).collect::<Vec<_>>());
        let took = done - welcomed;
        assert!(
            took >= 3 * INTERVAL,
            "the backlog took only {took:?}: the link is not slow"
        );
        beats.push(done);
        let gap = longest_gap(&beats);
        assert!(
            gap < INTERVAL + SLACK,
            "{} heartbeats while the backlog took {took:?}; longest gap {gap:?}",
            beats.len() - 2
        );
    }

    /// A Docker Engine API that knows no image and no container, and answers every request
    /// after the delay set when it arrives.
    struct SlowDocker {
        url: String,
        delay_ms: Arc<AtomicU64>,
    }

    impl SlowDocker {
        async fn start() -> SlowDocker {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let url = format!("http://{}", listener.local_addr().unwrap());
            let delay_ms = Arc::new(AtomicU64::new(0));
            let delay = delay_ms.clone();
            tokio::spawn(async move {
                while let Ok((mut stream, _)) = listener.accept().await {
                    let delay = Duration::from_millis(delay.load(Ordering::SeqCst));
                    tokio::spawn(async move {
                        let mut head = Vec::new();
                        let mut buf = [0u8; 1024];
                        while !head.windows(4).any(|w| w == b"\r\n\r\n") {
                            match stream.read(&mut buf).await {
                                Ok(0) | Err(_) => return,
                                Ok(n) => head.extend_from_slice(&buf[..n]),
                            }
                        }
                        let request = String::from_utf8_lossy(&head);
                        let line = request.lines().next().unwrap_or_default();
                        tokio::time::sleep(delay).await;
                        let (status, body) =
                            if line.starts_with("GET ") && line.contains("/containers/json") {
                                ("200 OK", "[]")
                            } else {
                                ("404 Not Found", r#"{"message":"not found"}"#)
                            };
                        let response = format!(
                            "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                            body.len()
                        );
                        let _ = stream.write_all(response.as_bytes()).await;
                        let _ = stream.shutdown().await;
                    });
                }
            });
            SlowDocker { url, delay_ms }
        }

        fn set_delay(&self, delay: Duration) {
            self.delay_ms
                .store(delay.as_millis() as u64, Ordering::SeqCst);
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn slow_docker_calls_hold_back_neither_heartbeats_nor_other_messages() {
        let docker = SlowDocker::start().await;
        let client =
            bollard::Docker::connect_with_http(&docker.url, 30, bollard::API_DEFAULT_VERSION)
                .unwrap();
        let dir = tempfile::tempdir().unwrap();
        let host = test_host(dir.path(), Executor::Docker(client));
        let active = Uuid::new_v4();
        host.register_test_run(active, 1, "claude-test");
        let mut server = Server::start(&host, tokio::io::duplex(64 * 1024)).await;
        server.welcome().await;
        // From now on every Docker call takes 3 s: the image check of the assignment, and
        // the removal of the container of a run this host does not know.
        const DOCKER_DELAY: Duration = Duration::from_secs(3);
        docker.set_delay(DOCKER_DELAY);
        let assigned = Uuid::new_v4();
        let unknown = Uuid::new_v4();
        server.send(assign(assigned)).await;
        let sent = Instant::now();
        server
            .send(json!({"type": "run.cancel", "runId": unknown, "epoch": 1}))
            .await;
        let mut beats = vec![sent];
        let mut unknown_answered = None;
        let (rejected_at, rejected) = loop {
            let (at, frame) = server.next().await;
            match frame["type"].as_str().unwrap() {
                "run.heartbeat" => beats.push(at),
                "error" if frame["runId"] == unknown.to_string() => {
                    assert_eq!(frame["code"], "UNKNOWN_RUN");
                    unknown_answered = Some(at);
                }
                "run.rejected" => break (at, frame),
                _ => {}
            }
        };
        assert_eq!(rejected["runId"], assigned.to_string());
        assert_eq!(rejected["code"], "IMAGE_MISSING", "{rejected}");
        let took = rejected_at - sent;
        assert!(
            took >= DOCKER_DELAY,
            "the image check took only {took:?}: Docker was not slow"
        );
        let answered = unknown_answered.expect("run.cancel was answered before the rejection");
        assert!(
            answered - sent < SLACK,
            "run.cancel waited {:?} behind Docker",
            answered - sent
        );
        beats.push(rejected_at);
        let gap = longest_gap(&beats);
        assert!(
            gap < INTERVAL + SLACK,
            "{} heartbeats while Docker took {took:?}; longest gap {gap:?}",
            beats.len() - 2
        );
    }

    #[test]
    fn reconnects_within_5_s_while_the_host_holds_runs() {
        let s = Duration::from_secs;
        // Idle: 1 s → 30 s, as the backoff doubles.
        for backoff in [s(1), s(2), s(4), s(8), s(16), s(30)] {
            assert_eq!(retry_delay(backoff, false), backoff);
        }
        // Holding runs: never more than 5 s.
        let holding: Vec<Duration> = [s(1), s(2), s(4), s(8), s(16), s(30)]
            .into_iter()
            .map(|backoff| retry_delay(backoff, true))
            .collect();
        assert_eq!(holding, [s(1), s(2), s(4), s(5), s(5), s(5)]);

        let dir = tempfile::tempdir().unwrap();
        let idle = test_host(dir.path(), local_executor());
        assert!(!holds_runs(&idle));
        // A run that executes (or is being admitted).
        let dir = tempfile::tempdir().unwrap();
        let running = test_host(dir.path(), local_executor());
        running.register_test_run(Uuid::new_v4(), 1, "claude-test");
        assert!(holds_runs(&running));
        // A run that ended while the connection was down: its end still waits.
        let dir = tempfile::tempdir().unwrap();
        let finished = test_host(dir.path(), local_executor());
        let run_id = Uuid::new_v4();
        finished.outbox.open_run(RunAccepted { run_id, epoch: 1 });
        finished
            .outbox
            .push_finished(tinfirm_protocol::RunFinished {
                run_id,
                epoch: 1,
                outcome: tinfirm_protocol::Outcome::Succeeded,
                last_seq: 0,
                failure_class: None,
                failure_message: None,
                summary: None,
                result: None,
                session_id: None,
                git: None,
                usage: None,
                artifacts: vec![],
                resumed: None,
                resume_outcome: None,
                session_bundle: None,
            });
        assert_eq!(finished.active_run_count(), 0);
        assert!(holds_runs(&finished));
    }

    #[test]
    fn a_v2_host_needs_a_v2_welcome_and_a_v1_host_takes_any() {
        assert!(check_welcome_version(1, None).is_ok());
        assert!(check_welcome_version(1, Some(1)).is_ok());
        assert!(check_welcome_version(1, Some(2)).is_ok());
        assert!(check_welcome_version(2, Some(2)).is_ok());
        let err = check_welcome_version(2, None).unwrap_err();
        assert!(err.contains("without protocolVersion"), "{err}");
        assert!(err.contains("--protocol-version 1"), "{err}");
        let err = check_welcome_version(2, Some(1)).unwrap_err();
        assert!(err.contains("welcome for protocol 1"), "{err}");
    }

    #[test]
    fn heartbeats_come_at_least_three_times_per_lease() {
        let dir = tempfile::tempdir().unwrap();
        let host = test_host(dir.path(), local_executor());
        let welcome = |heartbeat: u64, lease: u64| Welcome {
            protocol_version: None,
            runner_host_id: Uuid::new_v4(),
            company_id: Uuid::new_v4(),
            server_version: None,
            heartbeat_interval_seconds: heartbeat,
            lease_ttl_seconds: lease,
        };
        host.on_welcome(&welcome(10, 30));
        assert_eq!(host.heartbeat_interval(), Duration::from_secs(10));
        host.on_welcome(&welcome(10, 12));
        assert_eq!(host.heartbeat_interval(), Duration::from_secs(4));
        host.on_welcome(&welcome(5, 60));
        assert_eq!(host.heartbeat_interval(), Duration::from_secs(5));
        host.on_welcome(&welcome(10, 2));
        assert_eq!(host.heartbeat_interval(), Duration::from_secs(1));
    }
}
