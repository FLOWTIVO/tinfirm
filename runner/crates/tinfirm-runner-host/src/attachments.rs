//! Attachments of the work item (ADR-0014) and uploads of the agent's files.
//!
//! `run.assign.attachments` lists the files of the work item (v1: the task). Before the
//! sandbox starts, the host downloads each one from
//! `GET /api/runner/v1/runs/{runId}/attachments/{id}?epoch={epoch}` with its host token,
//! checks the size and SHA-256 from the assignment, and stores it in
//! `<data>/attachments/<runId>/`. That directory is mounted read-only at `/attachments` and
//! removed when the run ends. The server has already scanned every file for credentials.
//!
//! A 404 means the file was deleted after the assignment: it is skipped, and the run goes on.
//!
//! The other way, [`upload`] sends a file of the agent to
//! `POST /api/runner/v1/runs/{runId}/attachments?epoch={epoch}`: in v1 one the agent attaches
//! with `upload_attachment` ([`crate::mcp`], ADR-0021); in v2 one it saves with
//! `save_artifact`, or left in `/output` ([`crate::output`]), with the query flag
//! `artifact=true`, which makes the server link the attachment to the job as a DOCUMENT
//! artifact, and `source=save_artifact` or `source=output`, which says who named the file
//! ([`UploadSource`]): the server refuses a `save_artifact` file under the agent's
//! `saveArtifact` policy and saves `/output` whatever it says. The server scans the file and
//! answers for the agent.

use std::collections::HashSet;
use std::convert::Infallible;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use http_body_util::{BodyExt, Empty, StreamBody};
use hyper::body::{Bytes, Frame, Incoming};
use hyper::{Request, Response, StatusCode, header};
use hyper_util::rt::TokioIo;
use tinfirm_protocol::{AttachmentSpec, RunAssign};
use tokio::net::TcpStream;
use tokio::sync::{Semaphore, SemaphorePermit};
use uuid::Uuid;

/// The largest file the host reads for an upload (`save_artifact`, `upload_attachment`, a
/// file of `/output`) unless `run --upload-max-bytes` says otherwise: the server's default
/// `TINFIRM_ATTACHMENT_MAX_FILE_SIZE` (25MB, which Spring reads as 25 MiB). A larger file is
/// refused on its size before a byte of it is read.
pub const DEFAULT_UPLOAD_MAX_BYTES: u64 = 25 * 1024 * 1024;

/// `bytes` for people: whole MiB when it is a multiple of one, else bytes.
pub(crate) fn size_text(bytes: u64) -> String {
    const MIB: u64 = 1024 * 1024;
    if bytes >= MIB && bytes.is_multiple_of(MIB) {
        format!("{} MiB", bytes / MIB)
    } else {
        format!("{bytes} bytes")
    }
}

/// Uploads that read, scan and send a file at once on this host; more wait (M5 of the
/// security review of 2026-10-27: the files are held in memory while they are scanned).
pub const MAX_PARALLEL_UPLOADS: usize = 3;

static UPLOAD_SLOTS: Semaphore = Semaphore::const_new(MAX_PARALLEL_UPLOADS);

/// Waits for one of the host's [`MAX_PARALLEL_UPLOADS`] upload slots. Take it before the file
/// is read and keep it until the server answered.
pub async fn upload_slot() -> SemaphorePermit<'static> {
    UPLOAD_SLOTS
        .acquire()
        .await
        .expect("the upload semaphore is never closed")
}

/// Where the sandbox sees the attachments (the server's prompt names this path).
pub const CONTAINER_DIR: &str = "/attachments";

/// Largest total the host accepts for one run: the server's default per-task limit is 250 MB.
const MAX_TOTAL_BYTES: u64 = 1024 * 1024 * 1024;

/// Checks `run.assign.attachments` before the run is accepted: safe, unique file names,
/// well-formed hashes, a bounded total size.
pub fn validate(attachments: &[AttachmentSpec]) -> Result<(), String> {
    let mut names = HashSet::new();
    let mut total: u64 = 0;
    for attachment in attachments {
        if !safe_file_name(&attachment.file_name) {
            return Err(format!(
                "attachment file name `{}` is not a plain file name",
                attachment.file_name.escape_debug()
            ));
        }
        if !names.insert(attachment.file_name.as_str()) {
            return Err(format!(
                "attachment file name `{}` appears twice",
                attachment.file_name
            ));
        }
        if attachment.sha256.len() != 64
            || !attachment
                .sha256
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(format!(
                "attachment `{}` has an invalid sha256",
                attachment.file_name
            ));
        }
        total = total.saturating_add(attachment.size_bytes);
    }
    if total > MAX_TOTAL_BYTES {
        return Err(format!(
            "the attachments have {total} bytes, more than the host accepts"
        ));
    }
    Ok(())
}

/// One path segment: not empty, not `.` or `..`, no separators, no control characters, no
/// Unicode format or bidirectional control characters ([`is_format_char`]), at most 255 bytes
/// (the usual file system limit).
pub fn safe_file_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 255
        && name != "."
        && name != ".."
        && !name.contains(['/', '\\'])
        && !name.chars().any(|c| c.is_control() || is_format_char(c))
}

/// Unicode format characters (general category Cf, Unicode 16), the bidirectional controls
/// among them: a name like `report\u{202E}fdp.exe` shows as
/// `reportexe.pdf` to the members who download it (I8 of the security review of 2026-10-27).
pub fn is_format_char(c: char) -> bool {
    matches!(
        c,
        '\u{00AD}'
            | '\u{0600}'..='\u{0605}'
            | '\u{061C}'
            | '\u{06DD}'
            | '\u{070F}'
            | '\u{0890}'..='\u{0891}'
            | '\u{08E2}'
            | '\u{180E}'
            | '\u{200B}'..='\u{200F}'
            | '\u{202A}'..='\u{202E}'
            | '\u{2060}'..='\u{2064}'
            | '\u{2066}'..='\u{206F}'
            | '\u{FEFF}'
            | '\u{FFF9}'..='\u{FFFB}'
            | '\u{110BD}'
            | '\u{110CD}'
            | '\u{13430}'..='\u{1343F}'
            | '\u{1BCA0}'..='\u{1BCA3}'
            | '\u{1D173}'..='\u{1D17A}'
            | '\u{E0001}'
            | '\u{E0020}'..='\u{E007F}'
    )
}

/// The per-run directory; deleted when dropped, so every way out of a run cleans it up.
pub struct AttachmentsDir {
    path: PathBuf,
}

impl AttachmentsDir {
    /// Creates an empty directory (readable by the sandbox user) in a private parent.
    pub fn create(path: PathBuf) -> Result<AttachmentsDir> {
        if path.exists() {
            std::fs::remove_dir_all(&path)?;
        }
        if let Some(parent) = path.parent() {
            crate::secure_fs::ensure_private_dir(parent)?;
        }
        std::fs::create_dir(&path)?;
        set_mode(&path, 0o755)?;
        Ok(AttachmentsDir { path })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for AttachmentsDir {
    fn drop(&mut self) {
        if let Err(e) = std::fs::remove_dir_all(&self.path)
            && e.kind() != std::io::ErrorKind::NotFound
        {
            tracing::warn!(path = %self.path.display(), error = %e, "cannot delete the attachments");
        }
    }
}

/// What the download of a run's attachments did.
#[derive(Debug, Default)]
pub struct Downloaded {
    pub stored: Vec<String>,
    /// Deleted on the server after the assignment (404).
    pub skipped: Vec<String>,
}

/// Connecting to the server, and waiting for the response headers of one download.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const RESPONSE_TIMEOUT: Duration = Duration::from_secs(60);
/// Between two body chunks of one download.
pub(crate) const CHUNK_TIMEOUT: Duration = Duration::from_secs(60);

/// Downloads every attachment of the assignment into `dir`.
pub async fn download_all(
    server_url: &str,
    host_token: &str,
    assign: &RunAssign,
    dir: &Path,
) -> Result<Downloaded> {
    let base = server_url.trim_end_matches('/');
    let mut downloaded = Downloaded::default();
    for attachment in &assign.attachments {
        let url = format!(
            "{base}/api/runner/v1/runs/{}/attachments/{}?epoch={}",
            assign.run_id, attachment.id, assign.epoch
        );
        let response = get(&url, host_token)
            .await
            .with_context(|| format!("cannot download attachment `{}`", attachment.file_name))?;
        if response.status() == StatusCode::NOT_FOUND {
            downloaded.skipped.push(attachment.file_name.clone());
            continue;
        }
        if !response.status().is_success() {
            bail!(
                "cannot download attachment `{}`: HTTP {}",
                attachment.file_name,
                response.status()
            );
        }
        let target = dir.join(&attachment.file_name);
        let mut file = std::fs::File::create(&target)
            .with_context(|| format!("cannot create {}", target.display()))?;
        let mut digest = ring::digest::Context::new(&ring::digest::SHA256);
        let mut size: u64 = 0;
        let mut body = response.into_body();
        loop {
            let frame = tokio::time::timeout(CHUNK_TIMEOUT, body.frame())
                .await
                .map_err(|_| anyhow!("the server stopped sending"))
                .and_then(|frame| frame.transpose().map_err(anyhow::Error::from))
                .with_context(|| {
                    format!("cannot download attachment `{}`", attachment.file_name)
                })?;
            let Some(frame) = frame else { break };
            let Ok(chunk) = frame.into_data() else {
                continue;
            };
            size += chunk.len() as u64;
            if size > attachment.size_bytes {
                bail!(
                    "attachment `{}` is larger than announced",
                    attachment.file_name
                );
            }
            digest.update(&chunk);
            file.write_all(&chunk)?;
        }
        file.sync_all()?;
        let actual = hex(digest.finish().as_ref());
        if size != attachment.size_bytes || actual != attachment.sha256 {
            bail!(
                "attachment `{}` does not match its size and sha256",
                attachment.file_name
            );
        }
        set_mode(&target, 0o444)?;
        downloaded.stored.push(attachment.file_name.clone());
    }
    Ok(downloaded)
}

/// One `GET` with the host token, over HTTP/1.1 (TLS for `https`), like the git proxy's
/// upstream requests. The server URL is validated at `login` (HTTPS, or plain HTTP only on
/// the loopback address). The download of skills ([`crate::skills`]) uses it too.
pub(crate) async fn get(url: &str, host_token: &str) -> Result<Response<Incoming>> {
    let url = url::Url::parse(url).context("invalid download URL")?;
    let request = Request::get(path_and_query(&url))
        .header(header::HOST, authority(&url))
        .header(header::AUTHORIZATION, format!("Bearer {host_token}"))
        .header(header::USER_AGENT, "tinfirm-runner-host")
        .body(Empty::<Bytes>::new())?;
    send(&url, request, RESPONSE_TIMEOUT).await
}

/// A file an agent attaches (v1, ADR-0021) or saves as an artifact (v2), as the host sends it.
#[derive(Debug, Clone)]
pub struct Upload {
    pub run_id: Uuid,
    pub epoch: i64,
    /// The name the server stores: a plain file name, or, for a file of `/output`, its path
    /// relative to `/output`.
    pub file_name: String,
    pub content_type: String,
    pub note: Option<String>,
    pub content: Bytes,
    /// What the file is, which sets the query of the request.
    pub source: UploadSource,
}

/// Where an upload comes from: the query of the request says it, so the server can refuse a
/// file the agent named with `save_artifact` under the agent's policies while it saves the
/// files of `/output`, which are the job's result and not a tool's.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UploadSource {
    /// v1: an attachment of the task (`upload_attachment`, ADR-0021); no query flag.
    Attachment,
    /// v2: a file the agent saved with `save_artifact`, an artifact of the job
    /// (`&artifact=true&source=save_artifact`).
    SaveArtifact,
    /// v2: a file of `/output` the host saves when the run ends, an artifact of the job
    /// (`&artifact=true&source=output`).
    Output,
}

impl UploadSource {
    /// The query of the upload after `epoch=`. A v1 server ignores it.
    fn query(self) -> &'static str {
        match self {
            UploadSource::Attachment => "",
            UploadSource::SaveArtifact => "&artifact=true&source=save_artifact",
            UploadSource::Output => "&artifact=true&source=output",
        }
    }
}

/// The server's answer to an upload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UploadResponse {
    /// `200` with the answer of the server: STORED or REJECTED, with a message for the agent,
    /// and the stored attachment's id when it has one.
    Answered {
        stored: bool,
        message: String,
        attachment_id: Option<Uuid>,
    },
    /// `404`: the run is not RUNNING with this epoch on this host.
    RunNotActive,
    /// `413`: larger than the server accepts.
    TooLarge,
}

/// Waiting for the answer to an upload: the server scans the file before it answers.
const UPLOAD_RESPONSE_TIMEOUT: Duration = Duration::from_secs(120);

/// Sends a file of the agent to `POST /api/runner/v1/runs/{runId}/attachments?epoch=`
/// (`&artifact=true&source=save_artifact` or `&artifact=true&source=output` for an artifact,
/// [`UploadSource`]) with the host token, as `multipart/form-data` with the parts `file` and
/// `note`.
pub async fn upload(server_url: &str, host_token: &str, upload: Upload) -> Result<UploadResponse> {
    let base = server_url.trim_end_matches('/');
    let url = url::Url::parse(&format!(
        "{base}/api/runner/v1/runs/{}/attachments?epoch={}{}",
        upload.run_id,
        upload.epoch,
        upload.source.query()
    ))
    .context("invalid upload URL")?;
    let boundary = format!("tinfirm-{}", Uuid::new_v4().simple());
    let body = multipart(&boundary, &upload);
    let request = Request::post(path_and_query(&url))
        .header(header::HOST, authority(&url))
        .header(header::AUTHORIZATION, format!("Bearer {host_token}"))
        .header(header::USER_AGENT, "tinfirm-runner-host")
        .header(
            header::CONTENT_TYPE,
            format!("multipart/form-data; boundary={boundary}"),
        )
        .header(header::ACCEPT, "application/json")
        .header(header::CONTENT_LENGTH, body.len())
        .body(body.into_body())?;
    let response = send(&url, request, UPLOAD_RESPONSE_TIMEOUT).await?;
    let status = response.status();
    if status == StatusCode::NOT_FOUND {
        return Ok(UploadResponse::RunNotActive);
    }
    if status == StatusCode::PAYLOAD_TOO_LARGE {
        return Ok(UploadResponse::TooLarge);
    }
    if !status.is_success() {
        bail!("HTTP {status}");
    }
    let body = tokio::time::timeout(
        CHUNK_TIMEOUT,
        http_body_util::Limited::new(response.into_body(), 64 * 1024).collect(),
    )
    .await
    .map_err(|_| anyhow!("the server stopped sending"))?
    .map_err(|e| anyhow!("cannot read the answer: {e}"))?
    .to_bytes();
    let answer: serde_json::Value =
        serde_json::from_slice(&body).context("the answer is not JSON")?;
    let message = answer
        .get("message")
        .and_then(serde_json::Value::as_str)
        .context("the answer has no message")?
        .to_string();
    let attachment_id = answer
        .get("attachmentId")
        .and_then(serde_json::Value::as_str)
        .and_then(|id| Uuid::parse_str(id).ok());
    match answer.get("status").and_then(serde_json::Value::as_str) {
        Some("STORED") => Ok(UploadResponse::Answered {
            stored: true,
            message,
            attachment_id,
        }),
        Some("REJECTED") => Ok(UploadResponse::Answered {
            stored: false,
            message,
            attachment_id: None,
        }),
        _ => bail!("the answer has no known status"),
    }
}

type MultipartFrames = std::array::IntoIter<Result<Frame<Bytes>, Infallible>, 3>;
type MultipartBody = StreamBody<futures::stream::Iter<MultipartFrames>>;

/// The `multipart/form-data` body of an upload, in three parts: the head of the file part,
/// the file's content (shared, not copied), and the rest. It is streamed as these parts, so
/// the file is never in memory twice.
struct Multipart {
    head: Bytes,
    content: Bytes,
    tail: Bytes,
}

impl Multipart {
    fn len(&self) -> usize {
        self.head.len() + self.content.len() + self.tail.len()
    }

    fn into_body(self) -> MultipartBody {
        StreamBody::new(futures::stream::iter([
            Ok(Frame::data(self.head)),
            Ok(Frame::data(self.content)),
            Ok(Frame::data(self.tail)),
        ]))
    }

    #[cfg(test)]
    fn to_vec(&self) -> Vec<u8> {
        [&self.head[..], &self.content[..], &self.tail[..]].concat()
    }
}

/// The `multipart/form-data` body of an upload. The file name goes in an ASCII fallback and
/// in RFC 5987 form (`filename*`), which the server prefers.
fn multipart(boundary: &str, upload: &Upload) -> Multipart {
    let fallback: String = upload
        .file_name
        .chars()
        .map(|c| {
            if c.is_ascii_graphic() && c != '"' && c != '\\' || c == ' ' {
                c
            } else {
                '_'
            }
        })
        .collect();
    let encoded = percent_encoding::utf8_percent_encode(
        &upload.file_name,
        percent_encoding::NON_ALPHANUMERIC,
    );
    // The content type is checked by the caller: no line breaks.
    let head = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fallback}\"; filename*=UTF-8''{encoded}\r\nContent-Type: {}\r\n\r\n",
        upload.content_type
    );
    let mut tail = b"\r\n".to_vec();
    if let Some(note) = &upload.note {
        tail.extend_from_slice(
            format!(
                "--{boundary}\r\nContent-Disposition: form-data; name=\"note\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n{note}\r\n"
            )
            .as_bytes(),
        );
    }
    tail.extend_from_slice(format!("--{boundary}--\r\n").as_bytes());
    Multipart {
        head: Bytes::from(head),
        content: upload.content.clone(),
        tail: Bytes::from(tail),
    }
}

pub(crate) fn path_and_query(url: &url::Url) -> String {
    match url.query() {
        Some(query) => format!("{}?{query}", url.path()),
        None => url.path().to_string(),
    }
}

pub(crate) fn authority(url: &url::Url) -> String {
    match url.port() {
        Some(port) => format!("{}:{port}", url.host_str().unwrap_or_default()),
        None => url.host_str().unwrap_or_default().to_string(),
    }
}

/// Sends one request to the server, over HTTP/1.1 (TLS for `https`).
pub(crate) async fn send<B>(
    url: &url::Url,
    request: Request<B>,
    timeout: Duration,
) -> Result<Response<Incoming>>
where
    B: hyper::body::Body + Send + 'static,
    B::Data: Send,
    B::Error: Into<Box<dyn std::error::Error + Send + Sync>>,
{
    let https = url.scheme() == "https";
    let host = match url.host().context("the server URL has no host")? {
        url::Host::Domain(domain) => domain.to_string(),
        url::Host::Ipv4(ip) => ip.to_string(),
        url::Host::Ipv6(ip) => ip.to_string(),
    };
    let port = url
        .port_or_known_default()
        .context("the server URL has no port")?;
    let tcp = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect((host.as_str(), port)))
        .await
        .map_err(|_| anyhow!("connecting to the server timed out"))??;
    let _ = tcp.set_nodelay(true);
    if https {
        let name = rustls::pki_types::ServerName::try_from(host)?;
        let tls = tokio_rustls::TlsConnector::from(crate::git_proxy::tls_config())
            .connect(name, tcp)
            .await?;
        exchange(TokioIo::new(tls), request, timeout).await
    } else {
        exchange(TokioIo::new(tcp), request, timeout).await
    }
}

async fn exchange<T, B>(io: T, request: Request<B>, timeout: Duration) -> Result<Response<Incoming>>
where
    T: hyper::rt::Read + hyper::rt::Write + Unpin + Send + 'static,
    B: hyper::body::Body + Send + 'static,
    B::Data: Send,
    B::Error: Into<Box<dyn std::error::Error + Send + Sync>>,
{
    let (mut sender, connection) = hyper::client::conn::http1::handshake(io).await?;
    tokio::spawn(async move {
        if let Err(e) = connection.await {
            tracing::debug!(error = %e, "connection to the server ended with an error");
        }
    });
    tokio::time::timeout(timeout, sender.send_request(request))
        .await
        .map_err(|_| anyhow!("the server did not answer in time"))?
        .map_err(anyhow::Error::from)
}

pub(crate) fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
}

#[cfg(not(unix))]
fn set_mode(_path: &Path, _mode: u32) -> std::io::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// M5 (security review of 2026-10-27): at most [`MAX_PARALLEL_UPLOADS`] uploads read,
    /// scan and send a file at once on the host.
    #[tokio::test]
    async fn uploads_in_parallel_are_bounded() {
        let mut held = Vec::new();
        for _ in 0..MAX_PARALLEL_UPLOADS {
            held.push(
                tokio::time::timeout(Duration::from_secs(30), upload_slot())
                    .await
                    .expect("a slot"),
            );
        }
        assert!(
            tokio::time::timeout(Duration::from_millis(100), upload_slot())
                .await
                .is_err(),
            "a fourth upload waits"
        );
        held.pop();
        let fourth = tokio::time::timeout(Duration::from_secs(30), upload_slot()).await;
        assert!(fourth.is_ok(), "a slot that is given back is taken");
    }

    #[test]
    fn the_multipart_body_streams_the_content_without_copying_it() {
        let content = Bytes::from_static(b"hello\r\n");
        let upload = Upload {
            run_id: Uuid::nil(),
            epoch: 1,
            file_name: "r\u{e9}sum\u{e9}.md".into(),
            content_type: "text/markdown".into(),
            note: Some("a note".into()),
            content: content.clone(),
            source: UploadSource::SaveArtifact,
        };
        let body = multipart("B", &upload);
        assert_eq!(
            body.content.as_ptr(),
            content.as_ptr(),
            "shared, not copied"
        );
        let text = String::from_utf8(body.to_vec()).unwrap();
        assert_eq!(body.len(), text.len());
        assert_eq!(
            text,
            "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"r_sum_.md\"; filename*=UTF-8''r%C3%A9sum%C3%A9%2Emd\r\nContent-Type: text/markdown\r\n\r\nhello\r\n\r\n--B\r\nContent-Disposition: form-data; name=\"note\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\na note\r\n--B--\r\n"
        );
    }
    use uuid::Uuid;

    fn spec(name: &str) -> AttachmentSpec {
        AttachmentSpec {
            id: Uuid::new_v4(),
            file_name: name.into(),
            size_bytes: 1,
            sha256: "a".repeat(64),
        }
    }

    #[test]
    fn accepts_plain_file_names_only() {
        assert!(safe_file_name("design notes v2.pdf"));
        assert!(safe_file_name(".env.example"));
        assert!(safe_file_name("návrh.txt"));
        for bad in [
            "",
            ".",
            "..",
            "a/b",
            "a\\b",
            "../x",
            "bell\u{7}",
            "line\nbreak",
            // I8: right-to-left override, isolates, zero-width and other format characters.
            "report\u{202E}fdp.exe",
            "a\u{2066}b\u{2069}",
            "zero\u{200B}width",
            "bom\u{FEFF}.txt",
            "tag\u{E0041}",
        ] {
            assert!(!safe_file_name(bad), "{bad:?}");
        }
        // Emoji with a variation selector and scripts written right to left are fine.
        assert!(safe_file_name("notes \u{2764}\u{FE0F}.md"));
        assert!(safe_file_name("\u{05D3}\u{05D5}\u{05D7}.txt"));
        assert!(!safe_file_name(&"x".repeat(256)));
    }

    #[test]
    fn the_source_of_an_upload_sets_its_query() {
        assert_eq!(UploadSource::Attachment.query(), "");
        assert_eq!(
            UploadSource::SaveArtifact.query(),
            "&artifact=true&source=save_artifact"
        );
        assert_eq!(UploadSource::Output.query(), "&artifact=true&source=output");
    }

    #[test]
    fn validates_the_assignment() {
        assert!(validate(&[spec("a.txt"), spec("b.txt")]).is_ok());
        assert!(validate(&[spec("a.txt"), spec("a.txt")]).is_err());
        assert!(validate(&[spec("../etc/passwd")]).is_err());
        let mut bad_hash = spec("a.txt");
        bad_hash.sha256 = "A".repeat(64);
        assert!(validate(&[bad_hash]).is_err());
        let mut huge = spec("a.txt");
        huge.size_bytes = MAX_TOTAL_BYTES + 1;
        assert!(validate(&[huge]).is_err());
    }

    #[test]
    fn the_directory_is_removed_when_dropped() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("attachments").join("run");
        let dir = AttachmentsDir::create(path.clone()).unwrap();
        std::fs::write(dir.path().join("f.txt"), "x").unwrap();
        assert!(path.exists());
        drop(dir);
        assert!(!path.exists());
    }
}
