//! The runner host's own receive-pack client (ADR-0112, path C): a push without the run's git
//! token in git's process tree. `run --push-client host` turns it on; the default is
//! `git push` ([`crate::git::Git::push`]). It has been checked against a git server run for the
//! test and, for a new branch, GitHub, and not yet against GitLab, Gitea or Azure DevOps
//! (runner/README.md, "The push").
//!
//! `git push` runs in the host's process tree with the token in its environment, and its
//! `pack-objects` reads objects that a sandbox made. Here the work is split:
//!
//! 1. `run` reads the run's token at the request ([`crate::git_tokens`], ADR-0116) and sends
//!    `GET <repository>/info/refs?service=git-receive-pack` itself. From the answer it parses
//!    pkt-lines alone, every one bounded and checked ([`AdvertisementParser`]): the
//!    capabilities, the branch's tip, and the tips of the clone's own refs at clone time
//!    (the mirror's, which the host controls) that the repository still has.
//! 2. It compares the branch's tip with the lease (the tip the branch had at clone time, or
//!    none) before it sends anything, as `git push --force-with-lease` does: a branch that moved,
//!    was created or was deleted during the run is refused with `[rejected] (stale info)`, and
//!    nothing reaches the repository.
//! 3. A process without any credential packs the objects: the reader's `pack_objects` job
//!    ([`crate::reader::Reader::pack_objects_command`]), confined as the reader's other git
//!    jobs are, runs `git pack-objects --revs --stdout` over HEAD minus the tips the repository
//!    has. Without the sandbox bridge there is no reader, and `git pack-objects` runs as a child
//!    of `run`'s without the token, as the host's other git commands on the workspace do.
//! 4. `run` reads the token again and POSTs `<old> <new> refs/heads/<branch>` with its
//!    capabilities, a flush, and then the pack as `pack-objects` writes it: unread, unparsed,
//!    and never held whole ([`PackBody`]). `old` is the lease (the zero id for a branch that did
//!    not exist at clone time), so the repository checks it again when it updates the ref. Never
//!    a plain force.
//! 5. It parses the report-status ([`ReportParser`]), out of the side-band when the repository
//!    speaks it, bounded and checked as the advertisement is.
//!
//! The outcome is given back as `git push --porcelain` would give it ([`Outcome::output`]), so
//! that the reasons a person sees stay the same set (`push_failure` in [`crate::run`]).
//!
//! Redirects are followed nowhere: a 3xx answer fails the push, so the token goes to the
//! repository's own URL and nowhere else (the git proxy's rule, [`crate::git_proxy`]).

use std::collections::HashSet;
use std::pin::Pin;
use std::sync::{Arc, Mutex, OnceLock};
use std::task::{Context as TaskContext, Poll};
use std::time::Duration;

use anyhow::{Result, anyhow};
use futures::Stream;
use http_body_util::{BodyExt, Full, StreamBody};
use hyper::body::{Bytes, Frame, Incoming};
use hyper::header::{self, HeaderValue};
use hyper::{Method, Request, StatusCode};
use hyper_util::rt::TokioIo;
use tokio::io::AsyncReadExt;
use tokio::net::TcpStream;

use crate::front::head::{BoxError, ProxyBody};
use crate::git::{GitOutput, MirrorRef};
use crate::git_proxy::Upstream;

/// Which client pushes a run's work (`run --push-client`).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum PushClient {
    /// `git push` with the run's token in its environment ([`crate::git::Git::push`]).
    #[default]
    Git,
    /// This module: the runner host's own receive-pack client, which packs in a process
    /// without a token.
    Host,
}

/// How a host pushes: its client, and whether plain `http://` repositories are taken (the
/// tests and local development, `TINFIRM_RUNNER_ALLOW_INSECURE_REPOS=1`).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PushSettings {
    pub client: PushClient,
    pub allow_http: bool,
}

// ----- limits ------------------------------------------------------------------------------

/// The longest pkt-line, its four length bytes included (git's `LARGE_PACKET_MAX`).
pub const MAX_PKT_LEN: usize = 65520;
/// The most bytes of a ref advertisement.
pub const MAX_ADVERTISEMENT_BYTES: usize = 32 << 20;
/// The most refs of a ref advertisement.
pub const MAX_ADVERTISED_REFS: usize = 262_144;
/// The longest ref name.
pub const MAX_REF_NAME: usize = 4096;
/// The most capabilities, and the longest one.
const MAX_CAPABILITIES: usize = 256;
const MAX_CAPABILITY: usize = 1024;
/// The most tips the pack leaves out: the branch's own and those of the clone's refs that the
/// repository still has, in the order the repository lists them (`HEAD` first, then branches,
/// then tags). They bound the job's argument too (64 KiB).
pub const MAX_EXCLUDED_TIPS: usize = 256;
/// The most tips a pack's job leaves out: the lease's and [`MAX_EXCLUDED_TIPS`]. The client and
/// the reader's job ([`crate::reader::Reader::pack_objects_command`]) share this bound.
pub const MAX_PACK_TIPS: usize = MAX_EXCLUDED_TIPS + 1;
/// The most `shallow` lines after the refs of an advertisement.
pub const MAX_SHALLOW_LINES: usize = 65_536;
/// The most bytes of a report-status answer, side-band and progress included.
pub const MAX_REPORT_BYTES: usize = 16 << 20;
/// The most bytes of the report itself (band 1, or the whole answer without side-band).
const MAX_REPORT_LINES_BYTES: usize = 1 << 20;
/// What is kept of the repository's messages (band 2), for the person.
const MAX_MESSAGES: usize = 16 * 1024;
/// The longest reason of an `ng` line or an `unpack` error that is kept.
const MAX_REASON: usize = 1024;
/// A pack up to this size goes with a `Content-Length`; a larger one in chunks, as git does
/// with its `http.postBuffer` (1 MiB by default).
pub const POST_BUFFER: usize = 1 << 20;
/// The size of the pack's chunks.
const CHUNK: usize = 64 * 1024;
/// Connecting to the repository.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
/// The whole ref advertisement: the request, its answer's head and its body.
const ADVERTISEMENT_TIMEOUT: Duration = Duration::from_secs(120);
/// The whole push, from the first reading of the token: the ref discovery, the check of the
/// clone's objects, packing, sending and the report.
pub const PUSH_TIMEOUT: Duration = Duration::from_secs(60 * 60);
/// What git's own pack writer is waited for once its output has ended.
const PACK_EXIT_TIMEOUT: Duration = Duration::from_secs(30);

// ----- pkt-lines ---------------------------------------------------------------------------

/// One pkt-line of protocol v0.
#[derive(Debug, PartialEq, Eq)]
pub enum Pkt<'a> {
    Flush,
    Data(&'a [u8]),
}

/// The first pkt-line of `buf` and its length on the wire, or `None` while `buf` does not hold
/// a whole one. `0001` and `0002` (protocol v2's delimiters) and `0003` are refused, and so is a
/// length over [`MAX_PKT_LEN`].
pub fn next_pkt(buf: &[u8]) -> Result<Option<(Pkt<'_>, usize)>, String> {
    if buf.len() < 4 {
        return Ok(None);
    }
    let mut len = 0usize;
    for &b in &buf[..4] {
        let digit = (b as char)
            .to_digit(16)
            .ok_or_else(|| "a pkt-line does not start with its length".to_string())?;
        len = len * 16 + digit as usize;
    }
    match len {
        0 => Ok(Some((Pkt::Flush, 4))),
        1..=3 => Err(format!("a pkt-line has the length {len:04x}")),
        n if n > MAX_PKT_LEN => Err(format!("a pkt-line is longer than {MAX_PKT_LEN} bytes")),
        n if buf.len() < n => Ok(None),
        n => Ok(Some((Pkt::Data(&buf[4..n]), n))),
    }
}

/// `payload` without the one LF it may end with.
fn chomp(payload: &[u8]) -> &[u8] {
    payload.strip_suffix(b"\n").unwrap_or(payload)
}

/// One pkt-line for `payload`.
fn pkt_line(payload: &[u8]) -> Vec<u8> {
    let mut line = format!("{:04x}", payload.len() + 4).into_bytes();
    line.extend_from_slice(payload);
    line
}

/// `text` without control characters and without the invisible characters that change how text
/// reads (format and direction marks, line and paragraph separators), at most `max` characters:
/// what the repository says, for the person.
fn clean(text: &[u8], max: usize) -> String {
    String::from_utf8_lossy(text)
        .chars()
        .take(max)
        .map(|c| if hidden(c) { '?' } else { c })
        .collect()
}

/// A character that must not reach a person as it is: a control character, a zero-width or
/// direction mark, an invisible operator, a byte-order mark, a line or paragraph separator.
fn hidden(c: char) -> bool {
    c.is_control()
        || matches!(
            c,
            '\u{061C}'
                | '\u{180E}'
                | '\u{200B}'..='\u{200F}'
                | '\u{2028}'..='\u{202E}'
                | '\u{2060}'..='\u{2069}'
                | '\u{FEFF}'
        )
}

// ----- object names ------------------------------------------------------------------------

/// The hash of a repository's object names.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ObjectFormat {
    Sha1,
    Sha256,
}

impl ObjectFormat {
    pub fn hex_len(self) -> usize {
        match self {
            ObjectFormat::Sha1 => 40,
            ObjectFormat::Sha256 => 64,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            ObjectFormat::Sha1 => "sha1",
            ObjectFormat::Sha256 => "sha256",
        }
    }

    /// The format of an object name of the host's, by its length.
    pub fn of(name: &str) -> Option<ObjectFormat> {
        if !name.bytes().all(|b| b.is_ascii_hexdigit()) {
            return None;
        }
        match name.len() {
            40 => Some(ObjectFormat::Sha1),
            64 => Some(ObjectFormat::Sha256),
            _ => None,
        }
    }

    fn zero(self) -> String {
        "0".repeat(self.hex_len())
    }
}

/// A lower-case hexadecimal object name of `len` digits.
fn is_hex_name(name: &[u8], len: usize) -> bool {
    name.len() == len && name.iter().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// A ref name as the advertisement may give it: `HEAD`, `.have` or under `refs/`, at most
/// [`MAX_REF_NAME`] bytes, and no control character, space or DEL.
fn plain_ref_name(name: &[u8]) -> bool {
    !name.is_empty()
        && name.len() <= MAX_REF_NAME
        && name.iter().all(|&b| b > b' ' && b != 0x7f)
        && (name == b"HEAD" || name == b".have" || name.starts_with(b"refs/"))
}

// ----- the ref advertisement ---------------------------------------------------------------

/// What `run` takes from a ref advertisement.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Advertisement {
    pub format: ObjectFormat,
    pub capabilities: Vec<String>,
    /// The branch's tip, `None` when the repository has no such branch.
    pub tip: Option<String>,
    /// Advertised tips that the caller knows the clone has (`known`), without duplicates, at
    /// most [`MAX_EXCLUDED_TIPS`], in the repository's order.
    pub known: Vec<String>,
    /// The refs advertised (`.have` lines included).
    pub refs: usize,
}

impl Advertisement {
    pub fn has(&self, capability: &str) -> bool {
        self.capabilities.iter().any(|c| c == capability)
    }

    /// The value of the first `name=value` capability.
    pub fn value(&self, name: &str) -> Option<&str> {
        self.capabilities
            .iter()
            .find_map(|c| c.strip_prefix(name).and_then(|rest| rest.strip_prefix('=')))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AdState {
    /// The `# service=git-receive-pack` line.
    Service,
    /// The flush after it.
    ServiceFlush,
    /// The refs, until their flush. `first`: none yet.
    Refs {
        first: bool,
    },
    /// After the `capabilities^{}` line, which stands alone.
    NoRefs,
    /// After the refs, the `shallow <oid>` lines of a shallow repository, until the flush.
    Shallow,
    Done,
}

/// Reads a ref advertisement of `git-receive-pack` (protocol v0 or v1) as its bytes come, and
/// keeps only what [`Advertisement`] holds, so that its memory is bounded by one pkt-line
/// whatever the repository sends.
pub struct AdvertisementParser<'a> {
    branch_ref: String,
    known: &'a HashSet<String>,
    buf: Vec<u8>,
    total: usize,
    state: AdState,
    format: ObjectFormat,
    capabilities: Vec<String>,
    tip: Option<String>,
    kept: HashSet<String>,
    known_tips: Vec<String>,
    refs: usize,
    shallow: usize,
}

impl<'a> AdvertisementParser<'a> {
    /// For a push to `refs/heads/<branch>`; `known` are the object names the clone has.
    pub fn new(branch: &str, known: &'a HashSet<String>) -> AdvertisementParser<'a> {
        AdvertisementParser {
            branch_ref: format!("refs/heads/{branch}"),
            known,
            buf: Vec::new(),
            total: 0,
            state: AdState::Service,
            format: ObjectFormat::Sha1,
            capabilities: Vec::new(),
            tip: None,
            kept: HashSet::new(),
            known_tips: Vec::new(),
            refs: 0,
            shallow: 0,
        }
    }

    /// Takes the next bytes of the answer.
    pub fn feed(&mut self, chunk: &[u8]) -> Result<(), String> {
        self.total = self.total.saturating_add(chunk.len());
        if self.total > MAX_ADVERTISEMENT_BYTES {
            return Err(format!(
                "the ref advertisement is larger than {} MiB",
                MAX_ADVERTISEMENT_BYTES >> 20
            ));
        }
        if self.state == AdState::Done {
            return Err("the ref advertisement goes on after its end".into());
        }
        self.buf.extend_from_slice(chunk);
        // gitprotocol-http: the first five bytes are `^[0-9a-f]{4}#`, or this is not a smart
        // server's answer (a login page, a dumb server).
        if self.state == AdState::Service && self.buf.len() >= 5 {
            let head = &self.buf[..5];
            if !head[..4].iter().all(u8::is_ascii_hexdigit) || head[4] != b'#' {
                return Err("the answer is not a smart git server's ref advertisement".into());
            }
        }
        let mut at = 0;
        while let Some((pkt, len)) = next_pkt(&self.buf[at..])? {
            let line = match pkt {
                Pkt::Flush => None,
                Pkt::Data(data) => Some(data.to_vec()),
            };
            at += len;
            self.line(line.as_deref())?;
            if self.state == AdState::Done {
                if at != self.buf.len() {
                    return Err("the ref advertisement goes on after its end".into());
                }
                break;
            }
        }
        self.buf.drain(..at);
        Ok(())
    }

    fn line(&mut self, line: Option<&[u8]>) -> Result<(), String> {
        match (self.state, line) {
            (AdState::Service, Some(data)) => {
                if chomp(data) != b"# service=git-receive-pack" {
                    return Err(
                        "the first line of the answer is not `# service=git-receive-pack`".into(),
                    );
                }
                self.state = AdState::ServiceFlush;
            }
            (AdState::Service, None) => {
                return Err("the answer starts with a flush, not the service line".into());
            }
            (AdState::ServiceFlush, None) => self.state = AdState::Refs { first: true },
            (AdState::ServiceFlush, Some(_)) => {
                return Err("no flush after the service line".into());
            }
            (AdState::Refs { first }, Some(data)) => {
                let data = chomp(data);
                if let Some(message) = data.strip_prefix(b"ERR ") {
                    return Err(format!(
                        "the git server refused: {}",
                        clean(message, MAX_REASON)
                    ));
                }
                if first && data == b"version 1" {
                    return Ok(());
                }
                if !first && data.starts_with(b"shallow ") {
                    return self.shallow_line(data);
                }
                self.ref_line(data, first)?;
            }
            (AdState::NoRefs | AdState::Shallow, Some(data))
                if chomp(data).starts_with(b"shallow ") =>
            {
                return self.shallow_line(chomp(data));
            }
            (AdState::Shallow, Some(_)) => {
                return Err("a ref follows the `shallow` lines".into());
            }
            (AdState::Shallow, None) => self.state = AdState::Done,
            (AdState::Refs { first: true }, None) => {
                // An empty list is the `capabilities^{}` line, never no line at all.
                return Err("the ref advertisement has no line".into());
            }
            (AdState::Refs { first: false }, None) | (AdState::NoRefs, None) => {
                self.state = AdState::Done;
            }
            (AdState::NoRefs, Some(_)) => {
                return Err("a ref follows `capabilities^{}`".into());
            }
            (AdState::Done, _) => {
                return Err("the ref advertisement goes on after its end".into());
            }
        }
        Ok(())
    }

    /// `shallow <oid>` (gitprotocol-pack: after the refs, before their flush): a shallow
    /// repository's boundary, which a push does not use, as git's send-pack does not. Checked,
    /// counted and dropped.
    fn shallow_line(&mut self, data: &[u8]) -> Result<(), String> {
        let oid = &data[b"shallow ".len()..];
        if !is_hex_name(oid, self.format.hex_len()) {
            return Err(format!(
                "a `shallow` line names no {} object",
                self.format.name()
            ));
        }
        self.shallow += 1;
        if self.shallow > MAX_SHALLOW_LINES {
            return Err(format!(
                "the repository advertises more than {MAX_SHALLOW_LINES} `shallow` lines"
            ));
        }
        self.state = AdState::Shallow;
        Ok(())
    }

    fn ref_line(&mut self, data: &[u8], first: bool) -> Result<(), String> {
        let (record, capabilities) = match data.iter().position(|&b| b == 0) {
            Some(nul) if first => (&data[..nul], Some(&data[nul + 1..])),
            Some(_) => return Err("capabilities on a line but the first".into()),
            None if first => return Err("the first ref has no capabilities".into()),
            None => (data, None),
        };
        if let Some(capabilities) = capabilities {
            self.capabilities(capabilities)?;
        }
        let space = record
            .iter()
            .position(|&b| b == b' ')
            .ok_or("a ref line has no space")?;
        let (oid, name) = (&record[..space], &record[space + 1..]);
        if !is_hex_name(oid, self.format.hex_len()) {
            return Err(format!("a ref line names no {} object", self.format.name()));
        }
        self.refs += 1;
        if self.refs > MAX_ADVERTISED_REFS {
            return Err(format!(
                "the repository advertises more than {MAX_ADVERTISED_REFS} refs"
            ));
        }
        if first && name == b"capabilities^{}" {
            if oid.iter().any(|&b| b != b'0') {
                return Err("`capabilities^{}` names an object".into());
            }
            self.state = AdState::NoRefs;
            return Ok(());
        }
        self.state = AdState::Refs { first: false };
        // A peeled tag (`<name>^{}`) is the object its tag points at.
        let bare = name.strip_suffix(b"^{}").unwrap_or(name);
        if !plain_ref_name(bare) {
            return Err(format!(
                "the repository advertises a ref named `{}`",
                clean(name, 256)
            ));
        }
        let oid = String::from_utf8_lossy(oid).into_owned();
        if name == self.branch_ref.as_bytes() {
            if self.tip.is_some() {
                return Err("the repository advertises the branch twice".into());
            }
            self.tip = Some(oid.clone());
        }
        if self.known.contains(&oid)
            && self.known_tips.len() < MAX_EXCLUDED_TIPS
            && self.kept.insert(oid.clone())
        {
            self.known_tips.push(oid);
        }
        Ok(())
    }

    fn capabilities(&mut self, list: &[u8]) -> Result<(), String> {
        for token in list.split(|&b| b == b' ').filter(|t| !t.is_empty()) {
            if token.len() > MAX_CAPABILITY || !token.iter().all(|&b| b > b' ' && b < 0x7f) {
                return Err("a capability is not printable, or too long".into());
            }
            if self.capabilities.len() >= MAX_CAPABILITIES {
                return Err(format!("more than {MAX_CAPABILITIES} capabilities"));
            }
            self.capabilities
                .push(String::from_utf8_lossy(token).into_owned());
        }
        // The first `object-format` is the advertisement's (gitprotocol-capabilities).
        if let Some(format) = self
            .capabilities
            .iter()
            .find_map(|c| c.strip_prefix("object-format="))
        {
            self.format = match format {
                "sha1" => ObjectFormat::Sha1,
                "sha256" => ObjectFormat::Sha256,
                _ => {
                    return Err(format!(
                        "the repository's object format `{}` is not one the host knows",
                        clean(format.as_bytes(), 64)
                    ));
                }
            };
        }
        Ok(())
    }

    /// The advertisement, once its last byte came.
    pub fn finish(self) -> Result<Advertisement, String> {
        if self.state != AdState::Done {
            return Err("the ref advertisement ended before its flush".into());
        }
        Ok(Advertisement {
            format: self.format,
            capabilities: self.capabilities,
            tip: self.tip,
            known: self.known_tips,
            refs: self.refs,
        })
    }
}

/// [`AdvertisementParser`] over a whole answer.
pub fn parse_advertisement(
    body: &[u8],
    branch: &str,
    known: &HashSet<String>,
) -> Result<Advertisement, String> {
    let mut parser = AdvertisementParser::new(branch, known);
    parser.feed(body)?;
    parser.finish()
}

// ----- the report --------------------------------------------------------------------------

/// What the repository did with the branch.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RefStatus {
    Ok,
    /// `ng <ref> <reason>`.
    Rejected(String),
}

/// A report-status, and what the repository said on the way (band 2), line by line.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Report {
    /// `Err(reason)` for `unpack <reason>`.
    pub unpack: Result<(), String>,
    /// `None` when the report names no status for the branch.
    pub status: Option<RefStatus>,
    pub messages: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ReportState {
    Unpack,
    Status,
    Done,
}

/// Reads the answer to a push: a report-status for one ref (`report-status`, not `-v2`),
/// inside side-band-64k when it was asked for. Bounded as [`AdvertisementParser`] is.
pub struct ReportParser {
    sideband: bool,
    branch_ref: String,
    total: usize,
    outer: Vec<u8>,
    inner: Vec<u8>,
    inner_total: usize,
    state: ReportState,
    unpack: Option<Result<(), String>>,
    status: Option<RefStatus>,
    messages: std::collections::VecDeque<String>,
    message_bytes: usize,
    partial: Vec<u8>,
    /// The last message kept ended with `\r`: a progress line that the next one replaces.
    progress: bool,
}

impl ReportParser {
    pub fn new(branch: &str, sideband: bool) -> ReportParser {
        ReportParser {
            sideband,
            branch_ref: format!("refs/heads/{branch}"),
            total: 0,
            outer: Vec::new(),
            inner: Vec::new(),
            inner_total: 0,
            state: ReportState::Unpack,
            unpack: None,
            status: None,
            messages: std::collections::VecDeque::new(),
            message_bytes: 0,
            partial: Vec::new(),
            progress: false,
        }
    }

    /// Takes the next bytes of the answer; `Ok(true)` once the report is whole. Whatever comes
    /// after it is not read.
    pub fn feed(&mut self, chunk: &[u8]) -> Result<bool, String> {
        if self.state == ReportState::Done {
            return Ok(true);
        }
        self.total = self.total.saturating_add(chunk.len());
        if self.total > MAX_REPORT_BYTES {
            return Err(format!(
                "the answer to the push is larger than {} MiB",
                MAX_REPORT_BYTES >> 20
            ));
        }
        if !self.sideband {
            self.inner_bytes(chunk)?;
            return Ok(self.state == ReportState::Done);
        }
        self.outer.extend_from_slice(chunk);
        let mut at = 0;
        while self.state != ReportState::Done {
            let Some((pkt, len)) = next_pkt(&self.outer[at..])? else {
                break;
            };
            let data = match pkt {
                // The side-band's own end comes after the report's.
                Pkt::Flush => return Err("the side-band ended before the report".into()),
                Pkt::Data(data) => data.to_vec(),
            };
            at += len;
            match data.split_first() {
                Some((1, rest)) => self.inner_bytes(rest)?,
                Some((2, rest)) => self.message(rest),
                Some((3, rest)) => {
                    return Err(format!("remote error: {}", clean(rest, MAX_REASON)));
                }
                Some((band, _)) => return Err(format!("a side-band packet of band {band}")),
                None => return Err("an empty side-band packet".into()),
            }
        }
        self.outer.drain(..at);
        Ok(self.state == ReportState::Done)
    }

    /// The repository's progress and messages (band 2), in lines, as git shows them. A line that
    /// ends with `\r` is a state of a progress meter, which the meter's next state replaces, so
    /// only its last state is kept. When more than [`MAX_MESSAGES`] bytes came, the oldest lines go: the
    /// repository's errors come last, and they decide a push's reason.
    fn message(&mut self, text: &[u8]) {
        for &b in text {
            if b == b'\n' || b == b'\r' {
                let line = std::mem::take(&mut self.partial);
                self.keep_message(&line, b == b'\r');
            } else if self.partial.len() < MAX_REASON {
                self.partial.push(b);
            }
        }
    }

    fn keep_message(&mut self, line: &[u8], progress: bool) {
        let line = clean(line, MAX_REASON);
        let line = line.trim_end();
        if line.is_empty() {
            return;
        }
        // The next state of the same meter (the same words before its first `:`) replaces it.
        let meter = |text: &str| text.split(':').next().unwrap_or_default().to_string();
        if self.progress
            && self
                .messages
                .back()
                .is_some_and(|last| meter(last) == meter(line))
            && let Some(replaced) = self.messages.pop_back()
        {
            self.message_bytes -= replaced.len();
        }
        self.progress = progress;
        self.message_bytes += line.len();
        self.messages.push_back(line.to_string());
        while self.message_bytes > MAX_MESSAGES {
            match self.messages.pop_front() {
                Some(old) => self.message_bytes -= old.len(),
                None => break,
            }
        }
    }

    fn inner_bytes(&mut self, bytes: &[u8]) -> Result<(), String> {
        self.inner_total = self.inner_total.saturating_add(bytes.len());
        if self.inner_total > MAX_REPORT_LINES_BYTES {
            return Err(format!(
                "the report is larger than {} KiB",
                MAX_REPORT_LINES_BYTES >> 10
            ));
        }
        self.inner.extend_from_slice(bytes);
        let mut at = 0;
        while self.state != ReportState::Done {
            let Some((pkt, len)) = next_pkt(&self.inner[at..])? else {
                break;
            };
            let line = match pkt {
                Pkt::Flush => None,
                Pkt::Data(data) => Some(chomp(data).to_vec()),
            };
            at += len;
            self.report_line(line.as_deref())?;
        }
        self.inner.drain(..at);
        Ok(())
    }

    fn report_line(&mut self, line: Option<&[u8]>) -> Result<(), String> {
        let Some(line) = line else {
            return match self.state {
                ReportState::Status => {
                    self.state = ReportState::Done;
                    Ok(())
                }
                _ => Err("the report ends before its `unpack` line".into()),
            };
        };
        if let Some(message) = line.strip_prefix(b"ERR ") {
            return Err(format!(
                "the git server refused: {}",
                clean(message, MAX_REASON)
            ));
        }
        match self.state {
            ReportState::Unpack => {
                let result = line
                    .strip_prefix(b"unpack ")
                    .ok_or("the report does not start with its `unpack` line")?;
                self.unpack = Some(if result == b"ok" {
                    Ok(())
                } else {
                    Err(clean(result, MAX_REASON))
                });
                self.state = ReportState::Status;
            }
            ReportState::Status => {
                let (status, rest) = if let Some(rest) = line.strip_prefix(b"ok ") {
                    (None, rest)
                } else if let Some(rest) = line.strip_prefix(b"ng ") {
                    let space = rest
                        .iter()
                        .position(|&b| b == b' ')
                        .ok_or("an `ng` line gives no reason")?;
                    (Some(&rest[space + 1..]), &rest[..space])
                } else {
                    return Err(format!(
                        "the report has a line the host does not know: `{}`",
                        clean(line, 128)
                    ));
                };
                if rest != self.branch_ref.as_bytes() {
                    return Err(format!(
                        "the report names a ref that was not pushed: `{}`",
                        clean(rest, 256)
                    ));
                }
                if self.status.is_some() {
                    return Err("the report names the branch twice".into());
                }
                self.status = Some(match status {
                    None => RefStatus::Ok,
                    Some(reason) => RefStatus::Rejected(clean(reason, MAX_REASON)),
                });
            }
            ReportState::Done => {}
        }
        Ok(())
    }

    /// The report, once it is whole; an error when the answer ended before.
    pub fn finish(mut self) -> Result<Report, String> {
        if !self.partial.is_empty() {
            let line = std::mem::take(&mut self.partial);
            self.keep_message(&line, false);
        }
        if self.state != ReportState::Done {
            return Err("the answer to the push ended before its report".into());
        }
        Ok(Report {
            unpack: self.unpack.unwrap_or(Ok(())),
            status: self.status,
            messages: self.messages.into(),
        })
    }
}

/// [`ReportParser`] over a whole answer.
pub fn parse_report(body: &[u8], branch: &str, sideband: bool) -> Result<Report, String> {
    let mut parser = ReportParser::new(branch, sideband);
    parser.feed(body)?;
    parser.finish()
}

// ----- the outcome, as `git push --porcelain` gives it ---------------------------------------

/// How a push ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// The branch is at HEAD already: nothing was sent (git's `[up to date]`).
    UpToDate,
    /// The repository took the push. `created`: the branch did not exist.
    Pushed {
        created: bool,
        old: String,
        new: String,
    },
    /// The branch's tip is not the lease's: it moved, was created or was deleted during the run.
    /// Nothing was sent (git's `[rejected] (stale info)`).
    Stale,
    /// The repository refused the update (`ng <ref> <reason>`).
    Rejected {
        reason: String,
        messages: Vec<String>,
    },
    /// The repository could not unpack the pack (`unpack <reason>`).
    UnpackFailed {
        reason: String,
        messages: Vec<String>,
    },
    /// Anything else: the repository's answer, the connection, the pack's writer.
    Failed {
        error: String,
        messages: Vec<String>,
    },
}

impl Outcome {
    fn failed(error: impl Into<String>) -> Outcome {
        Outcome::Failed {
            error: error.into(),
            messages: Vec::new(),
        }
    }

    /// The outcome's kind in fixed words, for the host's log.
    pub fn kind(&self) -> &'static str {
        match self {
            Outcome::UpToDate => "up to date",
            Outcome::Pushed { created: true, .. } => "pushed a new branch",
            Outcome::Pushed { .. } => "pushed",
            Outcome::Stale => "stale lease",
            Outcome::Rejected { .. } => "rejected by the repository",
            Outcome::UnpackFailed { .. } => "not unpacked by the repository",
            Outcome::Failed { .. } => "failed",
        }
    }

    pub fn succeeded(&self) -> bool {
        matches!(self, Outcome::UpToDate | Outcome::Pushed { .. })
    }

    /// What `git push --porcelain -- <url> HEAD:<refname>` would have written: its status
    /// line on stdout, the repository's messages (`remote: …`) and the errors on stderr, and an
    /// exit status of 0 or 1. So `run`'s reasons for a failed push are the same set as with
    /// `git push`.
    pub fn output(&self, url: &str, refname: &str) -> GitOutput {
        let spec = format!("HEAD:{refname}");
        let failed_refs = format!("error: failed to push some refs to '{url}'\n");
        let remote = |messages: &[String]| -> String {
            messages.iter().map(|m| format!("remote: {m}\n")).collect()
        };
        let (status, stdout, stderr) = match self {
            Outcome::UpToDate => (
                0,
                format!("To {url}\n=\t{spec}\t[up to date]\nDone\n"),
                String::new(),
            ),
            Outcome::Pushed { created: true, .. } => (
                0,
                format!("To {url}\n*\t{spec}\t[new branch]\nDone\n"),
                String::new(),
            ),
            Outcome::Pushed { old, new, .. } => (
                0,
                format!("To {url}\n+\t{spec}\t{old}...{new} (update with a lease)\nDone\n"),
                String::new(),
            ),
            Outcome::Stale => (
                1,
                format!("To {url}\n!\t{spec}\t[rejected] (stale info)\nDone\n"),
                failed_refs,
            ),
            Outcome::Rejected { reason, messages } => (
                1,
                format!("To {url}\n!\t{spec}\t[remote rejected] ({reason})\nDone\n"),
                format!("{}{failed_refs}", remote(messages)),
            ),
            Outcome::UnpackFailed { reason, messages } => (
                1,
                format!("To {url}\n!\t{spec}\t[remote rejected] (unpacker error)\nDone\n"),
                format!(
                    "{}error: remote unpack failed: {reason}\n{failed_refs}",
                    remote(messages)
                ),
            ),
            Outcome::Failed { error, messages } => (
                1,
                String::new(),
                format!("{}fatal: {error}\n", remote(messages)),
            ),
        };
        GitOutput {
            status: exit_status(status),
            stdout,
            stderr,
        }
    }
}

#[cfg(unix)]
fn exit_status(code: i32) -> std::process::ExitStatus {
    use std::os::unix::process::ExitStatusExt;
    std::process::ExitStatus::from_raw(code << 8)
}

#[cfg(windows)]
fn exit_status(code: i32) -> std::process::ExitStatus {
    use std::os::windows::process::ExitStatusExt;
    std::process::ExitStatus::from_raw(code as u32)
}

// ----- the client --------------------------------------------------------------------------

/// What `git` itself would say to a code host that sniffs it, with this client's own name, as
/// libgit2 does (`git/2.0 (libgit2 …)`).
fn user_agent() -> String {
    format!(
        "git/2.0 (tinfirm-runner-host {})",
        crate::version::RUNNER_VERSION
    )
}

/// The `agent=` capability: printable ASCII without a space.
fn agent_capability() -> String {
    let version: String = crate::version::RUNNER_VERSION
        .chars()
        .map(|c| if c.is_ascii_graphic() { c } else { '-' })
        .collect();
    format!("agent=tinfirm-runner-host/{version}")
}

fn tls() -> Arc<rustls::ClientConfig> {
    static TLS: OnceLock<Arc<rustls::ClientConfig>> = OnceLock::new();
    TLS.get_or_init(crate::git_proxy::tls_config).clone()
}

/// The connection's task, ended when this is dropped: nothing of a push outlives it.
struct Connection(tokio::task::JoinHandle<()>);

impl Drop for Connection {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// One request on a new connection to `upstream` (HTTP/1.1, TLS for https). No redirect is
/// followed: the caller refuses a 3xx.
async fn send(
    upstream: &Upstream,
    request: Request<ProxyBody>,
) -> Result<(hyper::Response<Incoming>, Connection), String> {
    let tcp = tokio::time::timeout(
        CONNECT_TIMEOUT,
        TcpStream::connect((upstream.host.as_str(), upstream.port)),
    )
    .await
    .map_err(|_| "cannot reach the git server: the connection timed out".to_string())?
    .map_err(|e| format!("cannot reach the git server: {e}"))?;
    let _ = tcp.set_nodelay(true);
    if upstream.https {
        let name = rustls::pki_types::ServerName::try_from(upstream.host.clone())
            .map_err(|_| "the repository's host is not a TLS server name".to_string())?;
        let tls = tokio_rustls::TlsConnector::from(tls())
            .connect(name, tcp)
            .await
            .map_err(|e| format!("cannot reach the git server over TLS: {e}"))?;
        exchange(TokioIo::new(tls), request).await
    } else {
        exchange(TokioIo::new(tcp), request).await
    }
}

async fn exchange<T>(
    io: T,
    request: Request<ProxyBody>,
) -> Result<(hyper::Response<Incoming>, Connection), String>
where
    T: hyper::rt::Read + hyper::rt::Write + Unpin + Send + 'static,
{
    let (mut sender, connection) = hyper::client::conn::http1::handshake(io)
        .await
        .map_err(|e| format!("cannot reach the git server: {e}"))?;
    let connection = Connection(tokio::spawn(async move {
        if let Err(e) = connection.await {
            tracing::debug!(error = %e, "push: the connection to the git server ended with an error");
        }
    }));
    let response = sender
        .send_request(request)
        .await
        .map_err(|e| format!("the request to the git server failed: {e}"))?;
    Ok((response, connection))
}

/// A request to the repository with the host's headers and, with `authorization`, the token.
fn request(
    upstream: &Upstream,
    method: Method,
    target: &str,
    authorization: Option<&tinfirm_protocol::Secret>,
    body: ProxyBody,
) -> Result<Request<ProxyBody>, String> {
    let mut request = Request::new(body);
    *request.method_mut() = method;
    *request.uri_mut() = format!("{}{target}", upstream.path)
        .parse()
        .map_err(|_| "the repository's path is not a request target".to_string())?;
    let headers = request.headers_mut();
    headers.insert(
        header::HOST,
        HeaderValue::from_str(&upstream.authority)
            .map_err(|_| "the repository's host is not a header value".to_string())?,
    );
    if let Ok(agent) = HeaderValue::from_str(&user_agent()) {
        headers.insert(header::USER_AGENT, agent);
    }
    // gitprotocol-http: a client never takes a cached answer.
    headers.insert(header::PRAGMA, HeaderValue::from_static("no-cache"));
    if let Some(authorization) = authorization {
        let value = crate::git_proxy::authorization_value(authorization)
            .ok_or_else(|| "the git credentials are not a header value".to_string())?;
        headers.insert(header::AUTHORIZATION, value);
    }
    Ok(request)
}

/// The answer's status as git would word it: only 200 goes on. A redirect is followed nowhere.
fn check_answer(
    response: &hyper::Response<Incoming>,
    url: &str,
    content_type: &str,
) -> Result<(), String> {
    let status = response.status();
    if status.is_redirection() {
        return Err(format!(
            "the git server answered with a redirect ({}); the runner host's push follows no redirect, so that the token goes to the repository's own URL alone",
            status.as_u16()
        ));
    }
    if status != StatusCode::OK {
        return Err(format!(
            "unable to access '{url}': The requested URL returned error: {}",
            status.as_u16()
        ));
    }
    let headers = response.headers();
    let media = headers
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.split(';').next())
        .map(|v| v.trim().to_ascii_lowercase());
    if media.as_deref() != Some(content_type) {
        return Err(format!(
            "the git server's answer is not `{content_type}` (it is no smart git server's answer)"
        ));
    }
    if headers
        .get(header::CONTENT_ENCODING)
        .is_some_and(|v| !v.as_bytes().eq_ignore_ascii_case(b"identity"))
    {
        return Err("the git server's answer is encoded, which the host did not ask for".into());
    }
    Ok(())
}

/// `GET info/refs?service=git-receive-pack` and its advertisement, within
/// [`ADVERTISEMENT_TIMEOUT`].
async fn advertise(
    upstream: &Upstream,
    url: &str,
    authorization: Option<&tinfirm_protocol::Secret>,
    branch: &str,
    known: &HashSet<String>,
) -> Result<Advertisement, String> {
    let work = async {
        let mut get = request(
            upstream,
            Method::GET,
            "/info/refs?service=git-receive-pack",
            authorization,
            http_body_util::Empty::new()
                .map_err(|never| match never {})
                .boxed_unsync(),
        )?;
        get.headers_mut()
            .insert(header::ACCEPT, HeaderValue::from_static("*/*"));
        let (response, _connection) = send(upstream, get).await?;
        check_answer(
            &response,
            url,
            "application/x-git-receive-pack-advertisement",
        )?;
        let mut parser = AdvertisementParser::new(branch, known);
        let mut body = response.into_body();
        while let Some(frame) = body.frame().await {
            let frame = frame.map_err(|e| format!("the ref advertisement broke off: {e}"))?;
            if let Some(data) = frame.data_ref() {
                parser.feed(data)?;
            }
        }
        parser.finish()
    };
    tokio::time::timeout(ADVERTISEMENT_TIMEOUT, work)
        .await
        .map_err(|_| {
            format!(
                "the git server did not send its refs within {} s",
                ADVERTISEMENT_TIMEOUT.as_secs()
            )
        })?
}

/// The process that writes the pack (a reader, or `git pack-objects` itself), killed with its
/// process group when it is dropped before its end.
struct PackWriter {
    child: Option<tokio::process::Child>,
    pgid: Option<u32>,
    stderr: Option<tokio::task::JoinHandle<Vec<u8>>>,
}

impl PackWriter {
    fn start(
        mut command: tokio::process::Command,
        revisions: Option<String>,
    ) -> Result<PackWriter> {
        if revisions.is_some() {
            command.stdin(std::process::Stdio::piped());
        }
        let mut child = command
            .spawn()
            .map_err(|e| anyhow!("cannot start the pack's writer: {e}"))?;
        let pgid = child.id();
        if let (Some(revisions), Some(mut stdin)) = (revisions, child.stdin.take()) {
            tokio::spawn(async move {
                use tokio::io::AsyncWriteExt;
                let _ = stdin.write_all(revisions.as_bytes()).await;
            });
        }
        let stderr = child.stderr.take().map(|mut stderr| {
            tokio::spawn(async move {
                let mut kept = Vec::new();
                let mut buf = [0u8; 8192];
                // The rest is read and dropped, so that a writer that writes on never blocks.
                while let Ok(n) = stderr.read(&mut buf).await {
                    if n == 0 {
                        break;
                    }
                    let room = MAX_MESSAGES.saturating_sub(kept.len()).min(n);
                    kept.extend_from_slice(&buf[..room]);
                }
                kept
            })
        });
        Ok(PackWriter {
            child: Some(child),
            pgid,
            stderr,
        })
    }

    fn stdout(&mut self) -> Option<tokio::process::ChildStdout> {
        self.child.as_mut().and_then(|c| c.stdout.take())
    }

    /// Waits for the writer once its output has ended, then kills its group (while the leader
    /// is not reaped yet, so that the group's id is still its own): `Ok` when it succeeded,
    /// else what it said.
    async fn finish(mut self) -> Result<(), String> {
        let mut child = self.child.take().ok_or("the pack's writer is gone")?;
        let pgid = self.pgid.take();
        let waited = match pgid {
            Some(pgid) => tokio::time::timeout(
                PACK_EXIT_TIMEOUT,
                tokio::task::spawn_blocking(move || crate::confine::wait_unreaped(pgid)),
            )
            .await
            .ok()
            .and_then(|r| r.ok())
            .unwrap_or(false),
            None => false,
        };
        if let Some(pgid) = pgid {
            crate::confine::kill_group(pgid);
        }
        let status = if waited {
            child.wait().await
        } else {
            match tokio::time::timeout(PACK_EXIT_TIMEOUT, child.wait()).await {
                Ok(status) => status,
                Err(_) => {
                    let _ = child.kill().await;
                    return Err("the pack's writer did not end".into());
                }
            }
        }
        .map_err(|e| format!("cannot wait for the pack's writer: {e}"))?;
        // What it said, within the same limit: its group is gone, so its stderr ends.
        let said = match self.stderr.take() {
            Some(mut task) => match tokio::time::timeout(PACK_EXIT_TIMEOUT, &mut task).await {
                Ok(said) => said.unwrap_or_default(),
                Err(_) => {
                    task.abort();
                    Vec::new()
                }
            },
            None => Vec::new(),
        };
        if status.success() {
            Ok(())
        } else {
            let said = clean(&said, MAX_MESSAGES);
            Err(format!(
                "git pack-objects failed ({status}){}",
                if said.trim().is_empty() {
                    String::new()
                } else {
                    format!(": {}", said.trim())
                }
            ))
        }
    }
}

impl Drop for PackWriter {
    fn drop(&mut self) {
        // Not reaped yet: the group's id is still the writer's.
        if let Some(pgid) = self.pgid.take() {
            crate::confine::kill_group(pgid);
        }
    }
}

/// The request's body: the commands, then the pack as the writer writes it, in chunks of at most
/// [`CHUNK`] bytes that hyper takes when it can send them, so that the pack is never held whole.
///
/// The most recent chunk is held back until the next one comes, and the last one until the
/// writer has ended well ([`PackWriter::finish`]). A receive-pack takes a pack as soon as its
/// checksum, at the pack's end, has come, without waiting for the end of the request. So the
/// end of a pack whose writer failed, or did not end in time, is never sent: the body ends with
/// an error instead, hyper breaks the request off, and the repository never has a whole pack to
/// take.
struct PackBody {
    stream: Pin<Box<dyn Stream<Item = Result<Frame<Bytes>, BoxError>> + Send>>,
}

impl PackBody {
    fn new(
        first: Bytes,
        stdout: tokio::process::ChildStdout,
        writer: PackWriter,
        failure: Arc<Mutex<Option<String>>>,
    ) -> PackBody {
        type Chunks = tokio_util::io::ReaderStream<tokio::process::ChildStdout>;
        enum State {
            /// The chunks still to read, the writer, and the chunk held back.
            Pack(Box<(Chunks, PackWriter, Bytes)>),
            Done,
        }
        let reader = tokio_util::io::ReaderStream::with_capacity(stdout, CHUNK);
        let fail = move |failure: &Mutex<Option<String>>, error: String| {
            *failure.lock().unwrap_or_else(|p| p.into_inner()) = Some(error.clone());
            Some((Err(BoxError::from(error)), State::Done))
        };
        let stream = futures::stream::unfold(
            State::Pack(Box::new((reader, writer, first))),
            move |state| {
                let failure = failure.clone();
                async move {
                    use futures::StreamExt;
                    match state {
                        State::Pack(pack) => {
                            let (mut reader, writer, held) = *pack;
                            match reader.next().await {
                                Some(Ok(chunk)) => Some((
                                    Ok(Frame::data(held)),
                                    State::Pack(Box::new((reader, writer, chunk))),
                                )),
                                Some(Err(e)) => {
                                    fail(&failure, format!("cannot read the pack: {e}"))
                                }
                                None => {
                                    drop(reader);
                                    match writer.finish().await {
                                        Ok(()) => Some((Ok(Frame::data(held)), State::Done)),
                                        Err(error) => fail(&failure, error),
                                    }
                                }
                            }
                        }
                        State::Done => None,
                    }
                }
            },
        );
        PackBody {
            stream: Box::pin(stream),
        }
    }
}

impl Stream for PackBody {
    type Item = Result<Frame<Bytes>, BoxError>;

    fn poll_next(mut self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<Option<Self::Item>> {
        self.stream.as_mut().poll_next(cx)
    }
}

/// The push of `head` to `refs/heads/<branch>` of `git`'s repository, with the lease of the
/// branch's tip at clone time ([`crate::git::Git::push`] states the rule), by this client. A
/// revoked token is an `Err` (`not pushed: …`), as with `git push`; everything else is an
/// [`Outcome`] in `git push --porcelain`'s words.
pub async fn push(
    git: &crate::git::Git,
    branch: &str,
    head: &str,
    allow_http: bool,
) -> Result<GitOutput> {
    let url = git.repo_url();
    let refname = format!("refs/heads/{branch}");
    let outcome = push_outcome(git, branch, head, allow_http).await?;
    // Fixed words alone: the texts of the repository and of the pack's writer reach the run's
    // events through the output, where `run` redacts them, and never this host's log.
    match &outcome {
        Outcome::Failed { messages, .. } => tracing::warn!(
            messages = messages.len(),
            "push: the runner host's own push failed; the run's events say why"
        ),
        other => tracing::debug!(outcome = other.kind(), "push: the runner host's own push"),
    }
    Ok(outcome.output(url, &refname))
}

/// [`push`], as an [`Outcome`].
pub async fn push_outcome(
    git: &crate::git::Git,
    branch: &str,
    head: &str,
    allow_http: bool,
) -> Result<Outcome> {
    push_outcome_within(git, branch, head, allow_http, PUSH_TIMEOUT).await
}

/// [`push_outcome`] with `deadline` for the whole push, from the first reading of the token to
/// the report ([`PUSH_TIMEOUT`] in a run; the tests give a short one). Once it passes, whatever
/// is still going (the ref discovery, the pack's writer and its group, the request) is ended,
/// and the push failed.
pub async fn push_outcome_within(
    git: &crate::git::Git,
    branch: &str,
    head: &str,
    allow_http: bool,
    deadline: Duration,
) -> Result<Outcome> {
    let url = git.repo_url();
    let refname = format!("refs/heads/{branch}");
    let scheme = url::Url::parse(url)
        .map(|u| u.scheme().to_string())
        .unwrap_or_default();
    if !(scheme == "https" || (allow_http && scheme == "http")) {
        return Ok(Outcome::failed(
            "the runner host's own push takes only HTTPS repositories",
        ));
    }
    let upstream = match Upstream::parse(url, allow_http) {
        Ok(upstream) => upstream,
        Err(e) => return Ok(Outcome::failed(format!("{e:#}"))),
    };
    let Some(format) = ObjectFormat::of(head) else {
        return Ok(Outcome::failed(format!(
            "`{}` is not an object name",
            clean(head.as_bytes(), 80)
        )));
    };
    let lease = match crate::git::branch_tip(git.base_refs(), branch) {
        Some(tip) if ObjectFormat::of(tip) == Some(format) => Some(tip.to_string()),
        Some(_) => {
            return Ok(Outcome::failed(
                "the lease is not an object name of HEAD's format",
            ));
        }
        None => None,
    };
    let known: HashSet<String> = known_tips(git.base_refs(), format);
    // The token, read at this request (ADR-0116): a revoked one pushes nothing.
    let authorization = git
        .authorization()
        .map_err(|revocation| anyhow!("not pushed: {revocation}"))?;
    let work = async {
        let ad = match advertise(&upstream, url, authorization.as_ref(), branch, &known).await {
            Ok(ad) => ad,
            Err(error) => return Ok(Outcome::failed(error)),
        };
        if ad.format != format {
            return Ok(Outcome::failed(
                "the receiving end does not support this repository's hash algorithm",
            ));
        }
        // As git decides before it sends anything: HEAD there already is up to date, whatever the
        // lease; a tip other than the lease's is stale.
        if ad.tip.as_deref() == Some(head) {
            return Ok(Outcome::UpToDate);
        }
        if ad.tip != lease {
            return Ok(Outcome::Stale);
        }
        if !ad.has("report-status") {
            return Ok(Outcome::failed(
                "the git server does not report a push's outcome (no report-status); the runner host does not push without it",
            ));
        }
        // The tips to leave out: the branch's own, then those the repository lists that the clone
        // has, at most MAX_EXCLUDED_TIPS of them; the clone is asked once more which it has.
        let mut exclude: Vec<String> = lease.iter().cloned().collect();
        for tip in &ad.known {
            if exclude.len() >= MAX_PACK_TIPS {
                break;
            }
            if !exclude.contains(tip) {
                exclude.push(tip.clone());
            }
        }
        let present = match git.present(&exclude).await {
            Ok(present) => present,
            Err(e) => {
                return Ok(Outcome::failed(format!(
                    "cannot list the clone's objects: {e:#}"
                )));
            }
        };
        exclude.retain(|tip| present.contains(tip));
        let delta_base_offset = ad.has("ofs-delta");
        let (command, revisions) = match git.reader() {
            Some(reader) => (
                reader.pack_objects_command(git.dir(), head, &exclude, delta_base_offset)?,
                None,
            ),
            None => {
                let mut command = crate::git::command(git.dir(), None);
                command.args(["pack-objects", "--revs", "--stdout", "-q"]);
                if delta_base_offset {
                    command.arg("--delta-base-offset");
                }
                #[cfg(unix)]
                command.process_group(0);
                let mut revisions = format!("{head}\n");
                for tip in &exclude {
                    revisions.push_str(&format!("^{tip}\n"));
                }
                (command, Some(revisions))
            }
        };
        let mut writer = PackWriter::start(command, revisions)?;
        let Some(mut stdout) = writer.stdout() else {
            return Ok(Outcome::failed("the pack's writer has no output"));
        };
        // The commands: `<old> <new> <ref>\0 <capabilities>`, as git's send-pack writes them.
        let old = lease.clone().unwrap_or_else(|| format.zero());
        let mut capabilities = String::from(" report-status");
        let sideband = ad.has("side-band-64k");
        if sideband {
            capabilities.push_str(" side-band-64k");
        }
        if ad.has("quiet") {
            capabilities.push_str(" quiet");
        }
        if ad.value("object-format").is_some() {
            capabilities.push_str(&format!(" object-format={}", format.name()));
        }
        if ad.has("agent") || ad.value("agent").is_some() {
            capabilities.push(' ');
            capabilities.push_str(&agent_capability());
        }
        let mut first = pkt_line(format!("{old} {head} {refname}\0{capabilities}").as_bytes());
        first.extend_from_slice(b"0000");
        let commands_len = first.len();
        // As git with its http.postBuffer: a pack that fits in the buffer goes with a
        // Content-Length, a larger one in chunks.
        let mut buffered = vec![0u8; POST_BUFFER];
        let mut filled = 0;
        let whole = loop {
            if filled == buffered.len() {
                break false;
            }
            match stdout.read(&mut buffered[filled..]).await {
                Ok(0) => break true,
                Ok(n) => filled += n,
                Err(e) => return Ok(Outcome::failed(format!("cannot read the pack: {e}"))),
            }
        };
        buffered.truncate(filled);
        first.extend_from_slice(&buffered);
        drop(buffered);
        let failure = Arc::new(Mutex::new(None));
        let body: ProxyBody = if whole {
            drop(stdout);
            if let Err(error) = writer.finish().await {
                return Ok(Outcome::failed(error));
            }
            Full::new(Bytes::from(first))
                .map_err(|never| match never {})
                .boxed_unsync()
        } else {
            StreamBody::new(PackBody::new(
                Bytes::from(first),
                stdout,
                writer,
                failure.clone(),
            ))
            .boxed_unsync()
        };
        tracing::debug!(
            commands = commands_len,
            chunked = !whole,
            exclude = exclude.len(),
            "push: sending the pack"
        );
        // The token again, at this request.
        let authorization = git
            .authorization()
            .map_err(|revocation| anyhow!("not pushed: {revocation}"))?;
        let mut post = match request(
            &upstream,
            Method::POST,
            "/git-receive-pack",
            authorization.as_ref(),
            body,
        ) {
            Ok(post) => post,
            Err(error) => return Ok(Outcome::failed(error)),
        };
        let headers = post.headers_mut();
        headers.insert(
            header::CONTENT_TYPE,
            HeaderValue::from_static("application/x-git-receive-pack-request"),
        );
        headers.insert(
            header::ACCEPT,
            HeaderValue::from_static("application/x-git-receive-pack-result"),
        );
        let exchange = async {
            let (response, connection) = send(&upstream, post).await?;
            check_answer(&response, url, "application/x-git-receive-pack-result")?;
            let mut parser = ReportParser::new(branch, sideband);
            let mut body = response.into_body();
            while let Some(frame) = body.frame().await {
                let frame = frame.map_err(|e| format!("the answer to the push broke off: {e}"))?;
                if let Some(data) = frame.data_ref()
                    && parser.feed(data)?
                {
                    break;
                }
            }
            // Nothing after the report is read.
            drop(body);
            drop(connection);
            parser.finish()
        };
        let report = match exchange.await {
            Ok(report) => report,
            Err(error) => {
                let failure = failure.lock().unwrap_or_else(|p| p.into_inner()).clone();
                return Ok(Outcome::failed(failure.unwrap_or(error)));
            }
        };
        let messages = report.messages;
        Ok(match (report.unpack, report.status) {
            (Err(reason), _) => Outcome::UnpackFailed { reason, messages },
            (Ok(()), Some(RefStatus::Ok)) => Outcome::Pushed {
                created: lease.is_none(),
                old,
                new: head.to_string(),
            },
            (Ok(()), Some(RefStatus::Rejected(reason))) => Outcome::Rejected { reason, messages },
            (Ok(()), None) => Outcome::Failed {
                error: "the git server's report names no status for the branch".into(),
                messages,
            },
        })
    };
    match tokio::time::timeout(deadline, work).await {
        Ok(outcome) => outcome,
        Err(_) => Ok(Outcome::failed(format!(
            "the push did not end within {}",
            duration_words(deadline)
        ))),
    }
}

/// `60 min`, or `2 s`.
fn duration_words(duration: Duration) -> String {
    let seconds = duration.as_secs();
    if seconds >= 60 && seconds.is_multiple_of(60) {
        format!("{} min", seconds / 60)
    } else {
        format!("{seconds} s")
    }
}

/// The object names of the clone's refs at clone time, of `format`: what the pack may leave out
/// where the repository still has them.
fn known_tips(base: &[MirrorRef], format: ObjectFormat) -> HashSet<String> {
    crate::git::ref_shas(base)
        .into_iter()
        .filter(|sha| ObjectFormat::of(sha) == Some(format))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const A: &str = "1111111111111111111111111111111111111111";
    const B: &str = "2222222222222222222222222222222222222222";
    const C: &str = "3333333333333333333333333333333333333333";

    fn pkt(text: &str) -> Vec<u8> {
        pkt_line(text.as_bytes())
    }

    /// An advertisement as `git http-backend` gives it: the service line, a flush, the refs with
    /// the capabilities behind a NUL on the first, a flush.
    fn advertisement(refs: &[(&str, &str)], capabilities: &str) -> Vec<u8> {
        let mut out = pkt("# service=git-receive-pack\n");
        out.extend_from_slice(b"0000");
        for (i, (oid, name)) in refs.iter().enumerate() {
            if i == 0 {
                out.extend(pkt_line(
                    format!("{oid} {name}\0{capabilities}\n").as_bytes(),
                ));
            } else {
                out.extend(pkt(&format!("{oid} {name}\n")));
            }
        }
        out.extend_from_slice(b"0000");
        out
    }

    const CAPS: &str = "report-status report-status-v2 delete-refs side-band-64k quiet atomic ofs-delta object-format=sha1 agent=git/2.43.0";

    fn known(names: &[&str]) -> HashSet<String> {
        names.iter().map(|n| n.to_string()).collect()
    }

    #[test]
    fn pkt_lines_are_read_whole_and_bounded() {
        assert_eq!(next_pkt(b"000").unwrap(), None);
        assert_eq!(next_pkt(b"0000rest").unwrap(), Some((Pkt::Flush, 4)));
        assert_eq!(next_pkt(b"0006a\n").unwrap(), Some((Pkt::Data(b"a\n"), 6)));
        assert_eq!(
            next_pkt(b"0006A\nmore").unwrap(),
            Some((Pkt::Data(b"A\n"), 6))
        );
        // Upper-case length digits, as git's own reader takes them.
        assert_eq!(
            next_pkt(b"000Aabcdef").unwrap(),
            Some((Pkt::Data(b"abcdef"), 10))
        );
        assert_eq!(next_pkt(b"0009abc").unwrap(), None);
        assert_eq!(next_pkt(b"0004").unwrap(), Some((Pkt::Data(b""), 4)));
        for bad in [
            &b"0001"[..],
            b"0002",
            b"0003",
            b"fff1",
            b"ffff",
            b"00g4",
            b"<htm",
            b" 004",
        ] {
            assert!(next_pkt(bad).is_err(), "{bad:?}");
        }
        let longest = pkt_line(&vec![b'x'; MAX_PKT_LEN - 4]);
        assert_eq!(next_pkt(&longest).unwrap().unwrap().1, MAX_PKT_LEN);
    }

    #[test]
    fn reads_an_advertisement_of_branches_and_tags() {
        let body = advertisement(
            &[
                (A, "HEAD"),
                (A, "refs/heads/main"),
                (B, "refs/heads/agent/X-1"),
                (C, "refs/tags/v1"),
                (A, "refs/tags/v1^{}"),
                (C, ".have"),
            ],
            CAPS,
        );
        let ad = parse_advertisement(&body, "agent/X-1", &known(&[A, C])).unwrap();
        assert_eq!(ad.format, ObjectFormat::Sha1);
        assert_eq!(ad.tip.as_deref(), Some(B));
        assert_eq!(ad.known, vec![A.to_string(), C.to_string()]);
        assert_eq!(ad.refs, 6);
        assert!(ad.has("report-status") && ad.has("side-band-64k") && ad.has("quiet"));
        assert_eq!(ad.value("agent"), Some("git/2.43.0"));
        assert_eq!(ad.value("object-format"), Some("sha1"));
        // A branch the repository does not have.
        let ad = parse_advertisement(&body, "agent/X-2", &known(&[])).unwrap();
        assert_eq!(ad.tip, None);
        assert!(ad.known.is_empty());
        // Protocol v1's version line before the refs.
        let mut v1 = pkt("# service=git-receive-pack\n");
        v1.extend_from_slice(b"0000");
        v1.extend(pkt("version 1\n"));
        v1.extend_from_slice(&body[35..]);
        let ad = parse_advertisement(&v1, "agent/X-1", &known(&[])).unwrap();
        assert_eq!(ad.tip.as_deref(), Some(B));
    }

    #[test]
    fn reads_an_empty_repository_and_sha256() {
        let zero = "0".repeat(40);
        let body = advertisement(&[(&zero, "capabilities^{}")], CAPS);
        let ad = parse_advertisement(&body, "main", &known(&[])).unwrap();
        assert_eq!(ad.tip, None);
        assert_eq!(ad.refs, 1);
        assert!(ad.has("report-status"));
        let tip = "ab".repeat(32);
        let body = advertisement(
            &[(&tip, "refs/heads/main")],
            "report-status side-band-64k object-format=sha256",
        );
        let ad = parse_advertisement(&body, "main", &known(&[&tip])).unwrap();
        assert_eq!(ad.format, ObjectFormat::Sha256);
        assert_eq!(ad.tip.as_deref(), Some(tip.as_str()));
        let zero = "0".repeat(64);
        let body = advertisement(&[(&zero, "capabilities^{}")], "object-format=sha256");
        assert_eq!(
            parse_advertisement(&body, "main", &known(&[]))
                .unwrap()
                .format,
            ObjectFormat::Sha256
        );
    }

    #[test]
    fn refuses_what_is_not_a_receive_pack_advertisement() {
        let good = advertisement(&[(A, "refs/heads/main")], CAPS);
        let zero = "0".repeat(40);
        let mut cases: Vec<(&str, Vec<u8>)> = vec![
            (
                "an HTML page",
                b"<!DOCTYPE html><html><body>Sign in</body></html>".to_vec(),
            ),
            (
                "a dumb server's info/refs",
                format!("{A}\trefs/heads/main\n").into_bytes(),
            ),
            ("the upload-pack service", {
                let mut b = pkt("# service=git-upload-pack\n");
                b.extend_from_slice(&good[31..]);
                b
            }),
            ("no flush after the service line", {
                let mut b = pkt("# service=git-receive-pack\n");
                b.extend(pkt(&format!("{A} refs/heads/main\0report-status\n")));
                b
            }),
            ("a flush first", b"0000".to_vec()),
            ("no end", good[..good.len() - 4].to_vec()),
            ("trailing bytes", [good.clone(), b"0000".to_vec()].concat()),
            ("an ERR line", {
                let mut b = pkt("# service=git-receive-pack\n");
                b.extend_from_slice(b"0000");
                b.extend(pkt("ERR access denied\n"));
                b
            }),
            (
                "no capabilities",
                advertisement(&[(A, "refs/heads/main")], "")
                    .iter()
                    .copied()
                    .filter(|&b| b != 0)
                    .collect(),
            ),
            (
                "a control character in a ref name",
                advertisement(&[(A, "refs/heads/ma\x1bin")], CAPS),
            ),
            (
                "a DEL in a ref name",
                advertisement(&[(A, "refs/heads/ma\x7fin")], CAPS),
            ),
            (
                "a space in a ref name",
                advertisement(&[(A, "refs/heads/a b")], CAPS),
            ),
            ("a ref outside refs/", advertisement(&[(A, "main")], CAPS)),
            ("an empty ref name", advertisement(&[(A, "")], CAPS)),
            (
                "a ref name too long",
                advertisement(
                    &[(A, &format!("refs/heads/{}", "x".repeat(MAX_REF_NAME)))],
                    CAPS,
                ),
            ),
            (
                "upper-case hex",
                advertisement(&[(&A.replace('1', "A"), "refs/heads/main")], CAPS),
            ),
            (
                "a short object name",
                advertisement(&[(&A[..39], "refs/heads/main")], CAPS),
            ),
            (
                "a sha256 name in a sha1 advertisement",
                advertisement(&[(&"a".repeat(64), "refs/heads/main")], CAPS),
            ),
            (
                "a sha1 name in a sha256 advertisement",
                advertisement(&[(A, "refs/heads/main")], "object-format=sha256"),
            ),
            (
                "an unknown object format",
                advertisement(&[(A, "refs/heads/main")], "object-format=md5"),
            ),
            (
                "a capability with a control character",
                advertisement(&[(A, "refs/heads/main")], "report-status\x01"),
            ),
            (
                "too many capabilities",
                advertisement(
                    &[(A, "refs/heads/main")],
                    &vec!["x"; MAX_CAPABILITIES + 1].join(" "),
                ),
            ),
            (
                "a capability too long",
                advertisement(&[(A, "refs/heads/main")], &"x".repeat(MAX_CAPABILITY + 1)),
            ),
            ("capabilities on the second line", {
                let mut b = advertisement(&[(A, "refs/heads/main")], CAPS);
                b.truncate(b.len() - 4);
                b.extend(pkt_line(
                    format!("{B} refs/heads/x\0report-status\n").as_bytes(),
                ));
                b.extend_from_slice(b"0000");
                b
            }),
            (
                "the branch twice",
                advertisement(&[(A, "refs/heads/main"), (B, "refs/heads/main")], CAPS),
            ),
            (
                "capabilities^{} with an object",
                advertisement(&[(A, "capabilities^{}")], CAPS),
            ),
            (
                "a ref after capabilities^{}",
                advertisement(&[(&zero, "capabilities^{}"), (A, "refs/heads/main")], CAPS),
            ),
            (
                "capabilities^{} on a later line",
                advertisement(&[(A, "refs/heads/main"), (&zero, "capabilities^{}")], CAPS),
            ),
            (
                "a line without a space",
                advertisement(&[(&format!("{A}refs/heads/main"), "")], CAPS),
            ),
            ("a delimiter of protocol v2", {
                let mut b = pkt("# service=git-receive-pack\n");
                b.extend_from_slice(b"00000001");
                b
            }),
        ];
        // A line over the pkt-line's limit.
        let mut long = pkt("# service=git-receive-pack\n");
        long.extend_from_slice(b"0000fff4");
        long.extend(vec![b'a'; 70_000]);
        cases.push(("a line over 65520 bytes", long));
        for (what, body) in cases {
            let result = parse_advertisement(&body, "main", &known(&[A]));
            assert!(result.is_err(), "{what}: {result:?}");
        }
    }

    #[test]
    fn bounds_the_refs_the_bytes_and_the_tips_kept() {
        // One ref more than the limit.
        let mut body = pkt("# service=git-receive-pack\n");
        body.extend_from_slice(b"0000");
        body.extend(pkt_line(
            format!("{A} refs/heads/main\0report-status\n").as_bytes(),
        ));
        let line = pkt(&format!("{B} refs/tags/t\n"));
        for _ in 0..MAX_ADVERTISED_REFS {
            body.extend_from_slice(&line);
        }
        body.extend_from_slice(b"0000");
        let error = parse_advertisement(&body, "main", &known(&[])).unwrap_err();
        assert!(error.contains("more than 262144 refs"), "{error}");
        // More bytes than the limit, refused as soon as they pass it.
        let none = known(&[]);
        let mut parser = AdvertisementParser::new("main", &none);
        parser.feed(&body[..1 << 20]).unwrap();
        parser.total = MAX_ADVERTISEMENT_BYTES;
        assert!(
            parser
                .feed(b"0")
                .unwrap_err()
                .contains("larger than 32 MiB")
        );
        // 1000 refs the clone has: the first 256 are kept, without duplicates.
        let names: Vec<String> = (0..1000).map(|i| format!("{i:040x}")).collect();
        let refs: Vec<(String, String)> = names
            .iter()
            .enumerate()
            .flat_map(|(i, n)| {
                [
                    (n.clone(), format!("refs/heads/b{i}")),
                    (n.clone(), format!("refs/tags/t{i}")),
                ]
            })
            .collect();
        let refs: Vec<(&str, &str)> = refs.iter().map(|(a, b)| (a.as_str(), b.as_str())).collect();
        let body = advertisement(&refs, CAPS);
        let all: HashSet<String> = names.iter().cloned().collect();
        let ad = parse_advertisement(&body, "b7", &all).unwrap();
        assert_eq!(ad.known.len(), MAX_EXCLUDED_TIPS);
        assert_eq!(ad.known[..3], names[..3]);
        assert_eq!(ad.tip.as_deref(), Some(names[7].as_str()));
        assert_eq!(ad.refs, 2000);
    }

    /// The report as `git receive-pack` writes it: the lines, a flush; with side-band, inside
    /// band 1, and then the side-band's own flush.
    fn report(lines: &[&str], sideband: bool) -> Vec<u8> {
        let mut inner = Vec::new();
        for line in lines {
            inner.extend(pkt(&format!("{line}\n")));
        }
        inner.extend_from_slice(b"0000");
        if !sideband {
            return inner;
        }
        let mut out = Vec::new();
        // A keepalive, progress over two packets, then the report.
        out.extend(pkt_line(b"\x02"));
        out.extend(pkt_line(b"\x02Resolving deltas: 50% (1/2)\r"));
        out.extend(pkt_line(b"\x02Resolving deltas: 100% (2/2), done.\n"));
        let mut band = vec![1u8];
        band.extend_from_slice(&inner);
        out.extend(pkt_line(&band));
        out.extend_from_slice(b"0000");
        out
    }

    #[test]
    fn reads_reports_with_and_without_the_side_band() {
        for sideband in [true, false] {
            let ok = parse_report(
                &report(&["unpack ok", "ok refs/heads/agent/X-1"], sideband),
                "agent/X-1",
                sideband,
            )
            .unwrap();
            assert_eq!(ok.unpack, Ok(()));
            assert_eq!(ok.status, Some(RefStatus::Ok));
            if sideband {
                // A progress meter's last state alone.
                assert_eq!(
                    ok.messages,
                    vec!["Resolving deltas: 100% (2/2), done.".to_string()]
                );
            }
            let ng = parse_report(
                &report(
                    &["unpack ok", "ng refs/heads/agent/X-1 failed to update ref"],
                    sideband,
                ),
                "agent/X-1",
                sideband,
            )
            .unwrap();
            assert_eq!(
                ng.status,
                Some(RefStatus::Rejected("failed to update ref".into()))
            );
            let unpack = parse_report(
                &report(
                    &[
                        "unpack index-pack abnormal exit",
                        "ng refs/heads/agent/X-1 unpacker error",
                    ],
                    sideband,
                ),
                "agent/X-1",
                sideband,
            )
            .unwrap();
            assert_eq!(unpack.unpack, Err("index-pack abnormal exit".into()));
            assert_eq!(
                unpack.status,
                Some(RefStatus::Rejected("unpacker error".into()))
            );
            // No status for the branch.
            let none =
                parse_report(&report(&["unpack ok"], sideband), "agent/X-1", sideband).unwrap();
            assert_eq!(none.status, None);
        }
        // Once the report is whole, what follows is not read.
        let mut body = report(&["unpack ok", "ok refs/heads/main"], true);
        body.extend_from_slice(b"garbage that is never parsed");
        let mut parser = ReportParser::new("main", true);
        assert!(parser.feed(&body).unwrap());
        assert!(parser.feed(b"\xff\xff").unwrap());
        assert_eq!(parser.finish().unwrap().status, Some(RefStatus::Ok));
    }

    #[test]
    fn refuses_reports_that_are_not_one() {
        let band = |b: u8, text: &str| {
            let mut data = vec![b];
            data.extend_from_slice(text.as_bytes());
            pkt_line(&data)
        };
        let cases: Vec<(&str, Vec<u8>, bool)> = vec![
            (
                "a fatal message (band 3)",
                band(3, "fatal: out of disk\n"),
                true,
            ),
            ("band 4", band(4, "x"), true),
            ("an empty side-band packet", b"0004".to_vec(), true),
            ("the side-band's flush first", b"0000".to_vec(), true),
            (
                "no unpack line",
                report(&["ok refs/heads/main"], true),
                true,
            ),
            (
                "no unpack line, plain",
                report(&["ok refs/heads/main"], false),
                false,
            ),
            (
                "an unknown line",
                report(&["unpack ok", "option refname refs/heads/x"], true),
                true,
            ),
            (
                "another ref",
                report(&["unpack ok", "ok refs/heads/other"], true),
                true,
            ),
            (
                "the branch twice",
                report(
                    &["unpack ok", "ok refs/heads/main", "ok refs/heads/main"],
                    false,
                ),
                false,
            ),
            (
                "an ng line without a reason",
                report(&["unpack ok", "ng refs/heads/main"], false),
                false,
            ),
            ("an ERR line", pkt("ERR denied\n"), false),
            ("a flush first", b"0000".to_vec(), false),
            ("an HTML page", b"<html>500</html>".to_vec(), false),
            (
                "cut short",
                report(&["unpack ok", "ok refs/heads/main"], true)[..20].to_vec(),
                true,
            ),
            ("nothing", Vec::new(), true),
        ];
        for (what, body, sideband) in cases {
            let result = parse_report(&body, "main", sideband);
            assert!(result.is_err(), "{what}: {result:?}");
        }
        // Band 3's message is shown, cleaned.
        let error = parse_report(&band(3, "fatal: \x1b[31mno\n"), "main", true).unwrap_err();
        assert_eq!(error, "remote error: fatal: ?[31mno?");
        // The answer's size, and the report's.
        let mut parser = ReportParser::new("main", true);
        let keepalives: Vec<u8> = pkt_line(b"\x02").repeat(MAX_REPORT_BYTES / 5 + 1);
        assert!(
            parser
                .feed(&keepalives)
                .unwrap_err()
                .contains("larger than 16 MiB")
        );
        let mut parser = ReportParser::new("main", false);
        parser.inner_total = MAX_REPORT_LINES_BYTES;
        assert!(parser.feed(b"0").unwrap_err().contains("report is larger"));
        // What the repository says is kept to 16 KiB, each line to 1 KiB.
        let mut parser = ReportParser::new("main", true);
        for _ in 0..100 {
            parser
                .feed(&band(2, &format!("{}\n", "m".repeat(5000))))
                .unwrap();
        }
        let rest = report(&["unpack ok", "ok refs/heads/main"], true);
        parser.feed(&rest).unwrap();
        let done = parser.finish().unwrap();
        assert!(done.messages.iter().all(|m| m.len() <= MAX_REASON));
        assert!(done.messages.iter().map(String::len).sum::<usize>() <= MAX_MESSAGES);
    }

    /// A shallow repository's `shallow` lines after the refs (receive-pack's
    /// `advertise_shallow_grafts`) are checked and dropped; anywhere else, or malformed, they are
    /// refused.
    #[test]
    fn shallow_lines_after_the_refs_are_read_and_dropped() {
        let with = |lines: &[String]| {
            let mut body =
                advertisement(&[(A, "refs/heads/main"), (B, "refs/heads/agent/X-1")], CAPS);
            body.truncate(body.len() - 4);
            for line in lines {
                body.extend(pkt(line));
            }
            body.extend_from_slice(b"0000");
            body
        };
        let ad = parse_advertisement(
            &with(&[format!("shallow {C}\n"), format!("shallow {A}")]),
            "agent/X-1",
            &known(&[A]),
        )
        .unwrap();
        assert_eq!(ad.tip.as_deref(), Some(B));
        assert_eq!(ad.refs, 2);
        assert_eq!(ad.known, vec![A.to_string()]);
        // After an empty repository's `capabilities^{}` too.
        let zero = "0".repeat(40);
        let mut empty = advertisement(&[(&zero, "capabilities^{}")], CAPS);
        empty.truncate(empty.len() - 4);
        empty.extend(pkt(&format!("shallow {C}\n")));
        empty.extend_from_slice(b"0000");
        assert!(parse_advertisement(&empty, "main", &known(&[])).is_ok());
        for (what, body) in [
            (
                "a ref after them",
                with(&[format!("shallow {C}\n"), format!("{A} refs/heads/late\n")]),
            ),
            ("a short name", with(&[format!("shallow {}\n", &C[..39])])),
            (
                "a sha256 name in a sha1 advertisement",
                with(&[format!("shallow {}\n", "c".repeat(64))]),
            ),
            ("more on the line", with(&[format!("shallow {C} x\n")])),
            (
                "upper-case hex",
                with(&[format!("shallow {}\n", C.replace('3', "C"))]),
            ),
            ("before any ref", {
                let mut b = pkt("# service=git-receive-pack\n");
                b.extend_from_slice(b"0000");
                b.extend(pkt(&format!("shallow {C}\n")));
                b.extend_from_slice(b"0000");
                b
            }),
        ] {
            assert!(
                parse_advertisement(&body, "agent/X-1", &known(&[])).is_err(),
                "{what}"
            );
        }
        let many: Vec<String> = (0..=MAX_SHALLOW_LINES)
            .map(|_| format!("shallow {C}\n"))
            .collect();
        let error = parse_advertisement(&with(&many), "agent/X-1", &known(&[])).unwrap_err();
        assert!(error.contains("`shallow` lines"), "{error}");
    }

    /// When the repository says more than 16 KiB, its newest lines are kept: the lock error
    /// that decides a push's reason comes last. A progress meter keeps its last state alone.
    #[test]
    fn the_newest_messages_are_kept_and_a_progress_meter_once() {
        let band = |text: &str| {
            let mut data = vec![2u8];
            data.extend_from_slice(text.as_bytes());
            pkt_line(&data)
        };
        let mut body = Vec::new();
        for i in 0..2000 {
            body.extend(band(&format!("hook says line {i} of its chatter\n")));
        }
        for i in 0..5000 {
            body.extend(band(&format!("Writing objects: {}% ({i}/5000)\r", i / 50)));
        }
        body.extend(band(
            "error: cannot lock ref 'refs/heads/main': is at 1111 but expected 2222\n",
        ));
        body.extend(report(
            &["unpack ok", "ng refs/heads/main failed to update ref"],
            true,
        ));
        let report = parse_report(&body, "main", true).unwrap();
        // The report's own helper adds a meter after it; the error is still there.
        assert!(
            report
                .messages
                .iter()
                .any(|m| m
                    == "error: cannot lock ref 'refs/heads/main': is at 1111 but expected 2222"),
            "{:?}",
            report.messages
        );
        assert!(report.messages.iter().map(String::len).sum::<usize>() <= MAX_MESSAGES);
        // The meter's 5000 states left one line; the error, which ends with LF, follows it.
        let meters: Vec<&String> = report
            .messages
            .iter()
            .filter(|m| m.starts_with("Writing objects"))
            .collect();
        assert_eq!(meters.len(), 1, "{meters:?}");
        assert!(
            report
                .messages
                .iter()
                .any(|m| m == "hook says line 1999 of its chatter")
        );
        assert!(
            !report
                .messages
                .iter()
                .any(|m| m == "hook says line 0 of its chatter")
        );
    }

    /// Invisible characters that change how a text reads are replaced, as control characters
    /// are.
    #[test]
    fn invisible_and_direction_characters_are_replaced() {
        for c in [
            '\u{200B}', '\u{200E}', '\u{200F}', '\u{202A}', '\u{202E}', '\u{2060}', '\u{2066}',
            '\u{2069}', '\u{FEFF}', '\u{2028}', '\u{2029}', '\u{061C}', '\u{1b}', '\u{7f}',
        ] {
            let text = format!("ok{c}ok");
            assert_eq!(clean(text.as_bytes(), 100), "ok?ok", "U+{:04X}", c as u32);
        }
        assert_eq!(clean("über ✓ 日本".as_bytes(), 100), "über ✓ 日本");
        let error = parse_report(&pkt("ERR evil\u{202E}txt.exe\n"), "main", false).unwrap_err();
        assert!(!error.contains('\u{202E}'), "{error}");
    }

    // ----- seeded generation of hostile answers ---------------------------------------------
    //
    // As the bridge's frames are tested (tinfirm-bridge, frame.rs): a seeded generator, so that a
    // failure names its seed and is replayed exactly.

    /// xorshift64*.
    struct Rng(u64);

    impl Rng {
        fn new(seed: u64) -> Rng {
            Rng(seed.wrapping_mul(0x9e37_79b9_7f4a_7c15) | 1)
        }
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 >> 12;
            self.0 ^= self.0 << 25;
            self.0 ^= self.0 >> 27;
            self.0.wrapping_mul(0x2545_f491_4f6c_dd1d)
        }
        fn below(&mut self, n: u64) -> u64 {
            self.next() % n.max(1)
        }
    }

    /// `body` changed in a few places: bytes flipped, inserted, removed, a cut, a pkt-line's
    /// length rewritten.
    fn mutate(rng: &mut Rng, body: &[u8]) -> Vec<u8> {
        let mut out = body.to_vec();
        for _ in 0..=rng.below(4) {
            if out.is_empty() {
                out.push(rng.next() as u8);
            }
            let at = rng.below(out.len() as u64) as usize;
            match rng.below(6) {
                0 => out[at] = rng.next() as u8,
                1 => out.insert(at, rng.next() as u8),
                2 => {
                    out.remove(at);
                }
                3 => out.truncate(at),
                4 => {
                    let len = format!("{:04x}", rng.below(0x10000));
                    let end = (at + 4).min(out.len());
                    out.splice(at..end, len.bytes());
                }
                _ => out.insert(at, [0u8, b'\n', b' ', 0x1b, b'^'][rng.below(5) as usize]),
            }
        }
        out
    }

    /// Feeds `body` in chunks of random sizes.
    fn feed_split<T>(
        rng: &mut Rng,
        body: &[u8],
        mut feed: impl FnMut(&[u8]) -> Result<T, String>,
    ) -> Result<(), String> {
        let mut at = 0;
        while at < body.len() {
            let n = 1 + rng.below(64) as usize;
            let end = (at + n).min(body.len());
            feed(&body[at..end])?;
            at = end;
        }
        Ok(())
    }

    #[test]
    fn hostile_advertisements_are_refused_or_read_within_bounds() {
        let tips = known(&[A, C]);
        let valid = [
            advertisement(
                &[
                    (A, "HEAD"),
                    (A, "refs/heads/main"),
                    (B, "refs/heads/agent/X-1"),
                    (C, "refs/tags/v1"),
                    (A, "refs/tags/v1^{}"),
                    (C, ".have"),
                ],
                CAPS,
            ),
            advertisement(&[(&"0".repeat(40), "capabilities^{}")], CAPS),
        ];
        let mut accepted = 0;
        for seed in 0..5000u64 {
            let mut rng = Rng::new(seed);
            let base = &valid[rng.below(2) as usize];
            let body = mutate(&mut rng, base);
            // One pass at once and one in random chunks: the same outcome.
            let whole = parse_advertisement(&body, "agent/X-1", &tips);
            let mut parser = AdvertisementParser::new("agent/X-1", &tips);
            let split =
                feed_split(&mut rng, &body, |c| parser.feed(c)).and_then(|()| parser.finish());
            assert_eq!(
                whole.is_ok(),
                split.is_ok(),
                "seed {seed}: {whole:?} / {split:?}"
            );
            if let Ok(ad) = whole {
                accepted += 1;
                assert_eq!(Ok(&ad), split.as_ref(), "seed {seed}");
                assert!(ad.known.len() <= MAX_EXCLUDED_TIPS, "seed {seed}");
                assert!(ad.known.iter().all(|k| tips.contains(k)), "seed {seed}");
                if let Some(tip) = &ad.tip {
                    assert!(
                        is_hex_name(tip.as_bytes(), ad.format.hex_len()),
                        "seed {seed}"
                    );
                }
                assert!(
                    ad.capabilities
                        .iter()
                        .all(|c| c.bytes().all(|b| b > b' ' && b < 0x7f)),
                    "seed {seed}"
                );
            }
        }
        // Some mutations leave a valid advertisement (a flipped digit of a name), most do not.
        assert!(accepted > 0 && accepted < 5000, "{accepted}");
        // The valid ones, in random chunks, read as at once.
        for seed in 0..500u64 {
            let mut rng = Rng::new(seed);
            let body = &valid[0];
            let mut parser = AdvertisementParser::new("agent/X-1", &tips);
            feed_split(&mut rng, body, |c| parser.feed(c)).unwrap();
            assert_eq!(
                parser.finish(),
                parse_advertisement(body, "agent/X-1", &tips)
            );
        }
    }

    #[test]
    fn hostile_reports_are_refused_or_read_within_bounds() {
        let valid = [
            (report(&["unpack ok", "ok refs/heads/main"], true), true),
            (
                report(
                    &["unpack ok", "ng refs/heads/main failed to update ref"],
                    true,
                ),
                true,
            ),
            (report(&["unpack ok", "ok refs/heads/main"], false), false),
        ];
        let mut accepted = 0;
        for seed in 0..5000u64 {
            let mut rng = Rng::new(seed);
            let (base, sideband) = &valid[rng.below(3) as usize];
            let body = mutate(&mut rng, base);
            let whole = parse_report(&body, "main", *sideband);
            let mut parser = ReportParser::new("main", *sideband);
            let split =
                feed_split(&mut rng, &body, |c| parser.feed(c)).and_then(|()| parser.finish());
            assert_eq!(
                whole.is_ok(),
                split.is_ok(),
                "seed {seed}: {whole:?} / {split:?}"
            );
            if let Ok(report) = whole {
                accepted += 1;
                assert_eq!(Ok(&report), split.as_ref(), "seed {seed}");
                assert!(
                    report
                        .messages
                        .iter()
                        .all(|m| m.len() <= MAX_REASON && !m.chars().any(char::is_control)),
                    "seed {seed}"
                );
                if let Some(RefStatus::Rejected(reason)) = &report.status {
                    assert!(
                        reason.len() <= MAX_REASON * 4 && !reason.chars().any(char::is_control),
                        "seed {seed}"
                    );
                }
            }
        }
        assert!(accepted > 0 && accepted < 5000, "{accepted}");
    }
}
