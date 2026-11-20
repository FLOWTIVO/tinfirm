//! The reader (ADR-0112, open question 1; runner/README.md, "The reader"): the process that
//! parses what a sandbox sends back over the bridge, so that the runner host does not.
//!
//! The runner host holds every run's git token, its own token to the server and the one-run
//! model tokens. A flaw in the code that reads a hostile sandbox's archives or git pack
//! would give all of them, since that code ran in the host's own process. Now:
//!
//! - **One process per reading.** For each archive and each bundle of a run, the host starts
//!   this binary as `reader` (hidden from the help) and hands it the sandbox's bytes on its
//!   stdin, as they come over the bridge. The process is given, in its argument, only what
//!   that one reading needs: the directory it writes (a run's incoming session home, its
//!   `/output`, the scratch directory of one bundle) and, for a bundle, that run's clone. It
//!   gets an empty environment but `PATH`, no descriptor but its stdin, stdout and stderr, and
//!   no token: there is none in its argument or its input.
//! - **Confined before it reads a byte** ([`crate::confine`]): not dumpable, Landlock (only
//!   the directories of its job, the system's for git, nothing of the host's or of another
//!   run's; no TCP; no signal to the host), a seccomp filter (no socket of any kind, no ptrace,
//!   no namespaces), `no_new_privs`. It runs in a process group of its own, which the host
//!   kills when the job is over or its time is up, with whatever it started.
//! - **A narrow, typed answer.** One line of JSON on its stdout, at most 1 MiB, of a closed
//!   set of fields, followed only by a file's bytes for [`Reader::read_one`]. The host reads it
//!   as hostile too: a reader that a flaw took over may lie. So the host bounds and checks every
//!   field, and once the reader is gone it walks what it wrote ([`verify_tree`]): only
//!   directories and regular files with one link each, within the limits, and the counts the
//!   reader claimed. Anything else (a symbolic link to the host's token, a hard link, a
//!   device, a file over its limit) fails the reading, and the directory is emptied.
//! - **git on objects that came from a sandbox.** The fetch from a bundle (`index-pack` of a
//!   hostile pack, `fsck`, the connectivity check) and the checkout of the new HEAD run in the
//!   reader. After that the host's clone holds objects of the sandbox's, and so every later
//!   git command on it that reads objects runs through the reader too, confined the same way
//!   ([`Reader::git_command`]): the ahead count, the HEAD and the credential scan's listings.
//!   Only the push runs as before, with the run's git token ([`crate::git::Git::push`]),
//!   after [`crate::git::Git::sanitize`] has put back the clone's own configuration.
//!
//! Where the reader cannot be confined (no Landlock 2, macOS), it still runs as a process of its
//! own without a token, but nothing keeps it from the host's files: [`Reader::probe`] tells, and
//! `run` refuses such a host with `--sandbox-bridge` or `--executor vm` unless
//! `--allow-unconfined-reader` says otherwise (runner/README.md).

use std::io::{self, BufRead, Read, Write};
use std::net::SocketAddr;
use std::path::{Component, Path, PathBuf};
use std::process::{ExitCode, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, mpsc};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use tinfirm_bridge::files::{self, Limits, ReadOne, Unpacked};

use crate::confine::{self, Plan, Status};

/// The hidden subcommand of this binary that is the reader.
pub const SUBCOMMAND: &str = "reader";

/// The longest job, in the reader's argument.
const MAX_JOB: usize = 64 * 1024;
/// The longest answer line.
const MAX_ANSWER: usize = 1 << 20;
/// What the host keeps of the reader's stderr, for its log.
const MAX_STDERR: usize = 16 * 1024;
/// The longest name of an entry left out that the host takes from a reader.
const MAX_NAME: usize = 4096;
/// The most a reader's file of [`Reader::read_one`] may be: the largest upload.
const MAX_READ_ONE: u64 = crate::mcp::MAX_UPLOAD_BYTES;
/// What the reader may hold in memory (`RLIMIT_DATA`) while it unpacks or reads one file.
#[cfg(target_os = "linux")]
const READER_MEMORY: u64 = 1 << 30;
/// Where git's reflog says the host's own steps come from.
const READER_IDENTITY: (&str, &str) = ("Tinfirm runner host", "runner@tinfirm.invalid");
/// The longest bundle header ([`check_bundle_header`]), and its most lines.
const MAX_BUNDLE_HEADER: u64 = 1 << 20;
const MAX_BUNDLE_LINES: usize = 10_000;
/// Where the reader fetches the sandbox's HEAD to, for a moment.
const IMPORTED_REF: &str = "refs/tinfirm/sandbox";

/// What a reader is asked to do: the job of one process.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Job {
    /// The host takes an unconfined reader on this system (`--allow-unconfined-reader`, the
    /// tests). Otherwise a reader that cannot confine itself refuses.
    allow_unconfined: bool,
    work: Work,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
enum Work {
    /// Confine as a reader does, then try what must be refused: open each of `deny_read`,
    /// create a socket, connect to `connect`, and, with `signal`, signal and limit that
    /// process, which is outside the reader's process group ([`Signals`]).
    Probe {
        deny_read: Vec<PathBuf>,
        connect: Option<SocketAddr>,
        #[serde(default)]
        signal: Option<u32>,
    },
    /// Unpack the archive on stdin (gzipped with `gzip`) into `dest`, an empty directory:
    /// directories and regular files only, within `limits` ([`files::unpack_untrusted`]).
    Unpack {
        dest: PathBuf,
        gzip: bool,
        limits: JobLimits,
    },
    /// The one regular file of the archive on stdin, at most `max` bytes ([`files::read_one`]).
    ReadOne { max: u64 },
    /// The archive on stdin holds the sandbox's bundle `name`: unpack it into `scratch`, check
    /// its header, fetch its HEAD into `repo` with every object checked, and move `repo`'s HEAD
    /// and work tree there, if it is `head`.
    Bundle {
        repo: PathBuf,
        scratch: PathBuf,
        name: String,
        head: String,
        limits: JobLimits,
    },
    /// Run git in `repo` (read only) with `args`, after the host's safe settings.
    Git { repo: PathBuf, args: Vec<String> },
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct JobLimits {
    max_files: usize,
    max_bytes: u64,
    max_file_bytes: u64,
}

impl From<&Limits> for JobLimits {
    fn from(limits: &Limits) -> JobLimits {
        JobLimits {
            max_files: limits.max_files,
            max_bytes: limits.max_bytes,
            max_file_bytes: limits.max_file_bytes,
        }
    }
}

impl From<JobLimits> for Limits {
    fn from(limits: JobLimits) -> Limits {
        Limits {
            max_files: limits.max_files,
            max_bytes: limits.max_bytes,
            max_file_bytes: limits.max_file_bytes,
        }
    }
}

/// The reader's answer: the first line of its stdout.
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
enum Answer {
    Ok(Done),
    Error(String),
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
enum Done {
    Probe {
        status: Status,
        /// Per path of `deny_read`: it could be opened.
        read: Vec<bool>,
        /// A socket could be made.
        socket: bool,
        /// `connect` got through.
        connect: Option<bool>,
        /// What the probe could do to the process `signal` named.
        #[serde(default)]
        signals: Option<Signals>,
    },
    Unpacked {
        files: u64,
        bytes: u64,
        left_out: Vec<String>,
    },
    /// The file's `size` bytes follow the line, for `file`.
    ReadOne {
        state: OneState,
        size: u64,
    },
    Bundle {
        head: String,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
enum OneState {
    Missing,
    NotRegular,
    TooLarge,
    File,
}

/// What a reader could do to a process outside its process group, of the same user
/// ([`Reader::probe_signals`]): before Linux 6.12, Landlock has no signal scope, and only the
/// seccomp filter keeps a reader from ending `run` ([`confine`]).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Signals {
    /// What got through to that process: `SIGTERM` by its pid (`kill`), to its group (`kill of
    /// its group`), its limits (`prlimit64`), a pidfd of it (`pidfd_open`). Empty when all were
    /// refused.
    pub reached: Vec<String>,
    /// The reader could still signal its own process group and itself (`SIGCONT`), as git does.
    pub own_group: bool,
}

/// What [`Reader::probe`] found.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Probe {
    pub status: Status,
    /// Every path the probe was to be refused was refused.
    pub reads_refused: bool,
    /// The probe could make no socket, and its connection (if any) did not get through.
    pub network_refused: bool,
    /// This process (the host) is not dumpable; `None` where that cannot be told.
    pub host_not_dumpable: Option<bool>,
}

impl Probe {
    /// The reader is confined, and it was refused what it must be refused.
    pub fn confined(&self) -> bool {
        self.status.confined() && self.reads_refused && self.network_refused
    }

    /// One line, for the log and `doctor`.
    pub fn describe(&self) -> String {
        let mut text = self.status.describe();
        if self.status.confined() && !self.reads_refused {
            text.push_str("; but it could read what it must not (the host's directories)");
        }
        if self.status.confined() && !self.network_refused {
            text.push_str("; but it could open a socket");
        }
        match self.host_not_dumpable {
            Some(true) => text.push_str("; the host process is not dumpable"),
            Some(false) => text.push_str(
                "; the host process is dumpable, so a process of its user can read its memory",
            ),
            None => {}
        }
        text
    }
}

/// How the host starts its readers.
/// What a reader reads: a stream that this process copies to the reader's stdin, or a pipe that
/// becomes the reader's stdin, so that what comes on it never passes through this process.
pub enum Input<'a> {
    Read(&'a mut dyn Read),
    Pipe(std::os::fd::OwnedFd),
}

#[derive(Debug, Clone)]
pub struct Reader {
    binary: PathBuf,
    allow_unconfined: bool,
}

impl Reader {
    /// Readers of `binary` (this host's own executable, or a build of it), which refuse to
    /// work unconfined.
    pub fn new(binary: impl Into<PathBuf>) -> Reader {
        Reader {
            binary: binary.into(),
            allow_unconfined: false,
        }
    }

    /// Readers of this process's own executable.
    pub fn from_current_exe() -> Result<Reader> {
        Ok(Reader::new(
            std::env::current_exe().context("cannot find this executable")?,
        ))
    }

    /// Whether a reader that cannot confine itself here works anyway (`--allow-unconfined-reader`,
    /// the tests), as a process of its own without a token.
    pub fn allowing_unconfined(mut self, allow: bool) -> Reader {
        self.allow_unconfined = allow;
        self
    }

    pub fn allows_unconfined(&self) -> bool {
        self.allow_unconfined
    }

    pub fn binary(&self) -> &Path {
        &self.binary
    }

    /// Starts a reader that confines itself and tries to end, signal and limit `target`, a
    /// process outside its process group, and to signal its own group: what keeps a reader
    /// from ending `run` (ADR-0112, open question 1). With the reader's status.
    pub fn probe_signals(&self, target: u32, timeout: Duration) -> Result<(Status, Signals)> {
        let work = Work::Probe {
            deny_read: Vec::new(),
            connect: None,
            signal: Some(target),
        };
        let (done, rest) = self.exchange(&work, None, 0, timeout)?;
        match done {
            Done::Probe {
                status,
                signals: Some(signals),
                ..
            } if rest.is_empty() => Ok((status, signals)),
            _ => bail!("the reader's probe answered something else"),
        }
    }

    /// Starts a reader that confines itself and tries to open each of `deny_read` and to make
    /// a socket (and to connect to `connect`): what `run` and `doctor` say about the reader.
    /// It reads no input. Blocking.
    pub fn probe(
        &self,
        deny_read: &[PathBuf],
        connect: Option<SocketAddr>,
        timeout: Duration,
    ) -> Result<Probe> {
        let work = Work::Probe {
            deny_read: deny_read.to_vec(),
            connect,
            signal: None,
        };
        let (done, rest) = self.exchange(&work, None, 0, timeout)?;
        let Done::Probe {
            mut status,
            read,
            socket,
            connect: connected,
            ..
        } = done
        else {
            bail!("the reader answered the probe with something else");
        };
        if !rest.is_empty() || read.len() != deny_read.len() {
            bail!("the reader's answer to the probe is malformed");
        }
        status.problem = status.problem.map(|p| clean(&p, 512));
        Ok(Probe {
            status,
            reads_refused: read.iter().all(|allowed| !allowed),
            network_refused: !socket && connected != Some(true),
            host_not_dumpable: confine::host_dumpable().map(|d| !d),
        })
    }

    /// Unpacks the archive `input` (gzipped with `gzip`) into `dest`, which exists and is
    /// empty: directories and regular files only, within `limits`, entries left out by name
    /// ([`files::unpack_untrusted`], in a reader). Blocking.
    ///
    /// Whatever the reader answers, what is in `dest` once it is gone is walked again
    /// ([`verify_tree`]). A tree that breaks the rules, or does not match what the reader said
    /// it wrote, fails the reading, and `dest` is emptied; a reading that failed in the reader
    /// (too many files, a path outside) leaves what it wrote before, as an unpacking in the host
    /// did, once that has been walked too.
    pub fn unpack(
        &self,
        input: &mut dyn Read,
        dest: &Path,
        limits: &Limits,
        gzip: bool,
        timeout: Duration,
    ) -> Result<Unpacked> {
        self.unpack_from(Input::Read(input), dest, limits, gzip, timeout)
    }

    /// [`Reader::unpack`] of `input`, which may be a pipe that the reader reads itself.
    pub fn unpack_from(
        &self,
        input: Input<'_>,
        dest: &Path,
        limits: &Limits,
        gzip: bool,
        timeout: Duration,
    ) -> Result<Unpacked> {
        let meta = std::fs::symlink_metadata(dest)
            .with_context(|| format!("{} does not exist", dest.display()))?;
        if !meta.is_dir() {
            bail!("{} is not a directory", dest.display());
        }
        let work = Work::Unpack {
            dest: dest.to_path_buf(),
            gzip,
            limits: limits.into(),
        };
        let answer = self.exchange(&work, Some(input), 0, timeout);
        let found = match verify_tree(dest, limits) {
            Ok(found) => found,
            Err(e) => {
                clear_dir(dest);
                return Err(e.context("the reader left what it must not"));
            }
        };
        let (done, rest) = answer?;
        let check = (|| {
            let Done::Unpacked {
                files,
                bytes,
                left_out,
            } = done
            else {
                bail!("the reader answered an unpacking with something else");
            };
            if !rest.is_empty() {
                bail!("the reader wrote more than its answer");
            }
            if files != found.files as u64 || bytes != found.bytes {
                bail!(
                    "the reader says it wrote {files} files of {bytes} bytes, and {} files of {} bytes are there",
                    found.files,
                    found.bytes
                );
            }
            if left_out.len() > limits.max_files {
                bail!("the reader left out more entries than an archive may have");
            }
            let mut names = Vec::with_capacity(left_out.len());
            for name in left_out {
                if name.len() > MAX_NAME {
                    bail!("the reader names an entry longer than {MAX_NAME} bytes");
                }
                names.push(clean(&name, MAX_NAME));
            }
            Ok(Unpacked {
                files: found.files,
                bytes: found.bytes,
                left_out: names,
            })
        })();
        if check.is_err() {
            clear_dir(dest);
        }
        check
    }

    /// The one regular file of the archive `input`, at most `max` bytes ([`files::read_one`],
    /// in a reader). Blocking.
    pub fn read_one(&self, input: &mut dyn Read, max: u64, timeout: Duration) -> Result<ReadOne> {
        self.read_one_from(Input::Read(input), max, timeout)
    }

    /// [`Reader::read_one`] of `input`, which may be a pipe that the reader reads itself.
    pub fn read_one_from(&self, input: Input<'_>, max: u64, timeout: Duration) -> Result<ReadOne> {
        let max = max.min(MAX_READ_ONE);
        let extra = usize::try_from(max).context("too large")?;
        let (done, rest) = self.exchange(&Work::ReadOne { max }, Some(input), extra, timeout)?;
        let Done::ReadOne { state, size } = done else {
            bail!("the reader answered a file's reading with something else");
        };
        match state {
            OneState::File => {
                if size > max || rest.len() as u64 != size {
                    bail!("the reader's file does not have the size it says, or is too large");
                }
                Ok(ReadOne::File(rest))
            }
            _ if !rest.is_empty() || size != 0 => bail!("the reader wrote more than its answer"),
            OneState::Missing => Ok(ReadOne::Missing),
            OneState::NotRegular => Ok(ReadOne::NotRegular),
            OneState::TooLarge => Ok(ReadOne::TooLarge),
        }
    }

    /// The archive `input` holds the sandbox's bundle `name`: a reader unpacks it into
    /// `scratch` (which exists and is empty), checks its header (one ref, `HEAD`, which must be
    /// `head`), fetches it into the clone `repo` with every object checked, and moves `repo`'s
    /// HEAD and work tree to `head`. Blocking.
    ///
    /// The reader writes into `repo`: the caller sanitizes its `.git` afterwards and reads it
    /// only through readers ([`crate::git::Git::read_through`]).
    #[allow(clippy::too_many_arguments)]
    pub fn import_bundle(
        &self,
        input: &mut dyn Read,
        repo: &Path,
        scratch: &Path,
        name: &str,
        head: &str,
        limits: &Limits,
        timeout: Duration,
    ) -> Result<()> {
        self.import_bundle_from(
            Input::Read(input),
            repo,
            scratch,
            name,
            head,
            limits,
            timeout,
        )
    }

    /// [`Reader::import_bundle`] of `input`, which may be a pipe that the reader reads itself.
    #[allow(clippy::too_many_arguments)]
    pub fn import_bundle_from(
        &self,
        input: Input<'_>,
        repo: &Path,
        scratch: &Path,
        name: &str,
        head: &str,
        limits: &Limits,
        timeout: Duration,
    ) -> Result<()> {
        if !is_object_name(head) {
            bail!("`{head}` is not an object name");
        }
        if !is_bundle_name(name) {
            bail!("`{name}` is not the name of a bundle");
        }
        let work = Work::Bundle {
            repo: repo.to_path_buf(),
            scratch: scratch.to_path_buf(),
            name: name.to_string(),
            head: head.to_string(),
            limits: limits.into(),
        };
        let (done, rest) = self.exchange(&work, Some(input), 0, timeout)?;
        let Done::Bundle { head: imported } = done else {
            bail!("the reader answered a bundle with something else");
        };
        if !rest.is_empty() || imported != head {
            bail!("the reader did not import the HEAD that the sandbox named");
        }
        Ok(())
    }

    /// git with `args` in the clone `repo`, in a reader that may only read `repo`: for every
    /// git command of the host's that reads objects a sandbox sent. Its stdout and stderr are
    /// git's. It runs in a process group of its own: the caller kills the group once it has
    /// ended ([`confine::kill_group`]).
    pub fn git_command(&self, repo: &Path, args: &[&str]) -> tokio::process::Command {
        let job = Job {
            allow_unconfined: self.allow_unconfined,
            work: Work::Git {
                repo: repo.to_path_buf(),
                args: args.iter().map(|a| a.to_string()).collect(),
            },
        };
        let job = serde_json::to_string(&job).unwrap_or_default();
        let mut command = tokio::process::Command::new(&self.binary);
        command
            .arg(SUBCOMMAND)
            .arg(job)
            .env_clear()
            .env("PATH", scrubbed_path())
            .current_dir(repo)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(unix)]
        command.process_group(0);
        command
    }

    /// Starts a reader with `work`, feeds it `input`, and reads its answer: the line and what
    /// follows it (at most `extra` bytes). The reader and everything it started are killed
    /// once it has ended, or after `timeout`.
    fn exchange(
        &self,
        work: &Work,
        input: Option<Input<'_>>,
        extra: usize,
        timeout: Duration,
    ) -> Result<(Done, Vec<u8>)> {
        let job = serde_json::to_string(&Job {
            allow_unconfined: self.allow_unconfined,
            work: work.clone(),
        })?;
        let mut command = std::process::Command::new(&self.binary);
        command
            .arg(SUBCOMMAND)
            .arg(&job)
            .env_clear()
            .env("PATH", scrubbed_path())
            .current_dir("/")
            .stdin(match &input {
                Some(Input::Read(_)) => Stdio::piped(),
                Some(Input::Pipe(_)) | None => Stdio::null(),
            })
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.process_group(0);
        }
        // A pipe is the reader's stdin itself: what comes on it never passes through this
        // process. This process's copy of it is closed once the reader has it.
        let (input, pipe) = match input {
            Some(Input::Pipe(fd)) => (None, Some(fd)),
            other => (other, None),
        };
        if let Some(fd) = &pipe {
            command.stdin(Stdio::from(fd.try_clone()?));
        }
        let spawned = command.spawn();
        drop(command);
        drop(pipe);
        let mut child = spawned
            .with_context(|| format!("cannot start the reader {}", self.binary.display()))?;
        let pgid = child.id();
        // Whatever happens below, nothing of the reader outlives this call.
        let mut group = Group(Some(pgid));
        let stdout = child.stdout.take().context("the reader has no stdout")?;
        let stderr = child.stderr.take().context("the reader has no stderr")?;
        let cap = MAX_ANSWER.saturating_add(extra);
        // Set before the leader is reaped: from then on only this thread signals the group.
        let reaping = Arc::new(AtomicBool::new(false));
        let (out_tx, out_rx) = mpsc::channel();
        let kill_on_overflow = Some((pgid, reaping.clone()));
        std::thread::spawn(move || {
            let _ = out_tx.send(read_bounded(stdout, cap, kill_on_overflow));
        });
        let (err_tx, err_rx) = mpsc::channel();
        std::thread::spawn(move || {
            // The rest is read and dropped, so that a reader that writes on never blocks.
            let kept = read_bounded(stderr, MAX_STDERR, None);
            let _ = err_tx.send(kept);
        });
        let (stop_tx, stop_rx) = mpsc::channel::<()>();
        let watchdog = std::thread::spawn(move || {
            matches!(
                stop_rx.recv_timeout(timeout),
                Err(mpsc::RecvTimeoutError::Timeout)
            ) && {
                confine::kill_group(pgid);
                true
            }
        });
        if let (Some(Input::Read(input)), Some(mut stdin)) = (input, child.stdin.take()) {
            // A reader that stops reading (it refused, or died) ends the copy; its answer says
            // why. The caller reads the rest of the stream.
            let _ = io::copy(input, &mut stdin);
            drop(stdin);
        }
        // The group goes while its leader is not reaped yet, so that its id is still its own:
        // the watchdog stops first, and nothing signals the group once the leader is reaped.
        let status = if confine::wait_unreaped(pgid) {
            let _ = stop_tx.send(());
            reaping.store(true, Ordering::SeqCst);
            group.kill();
            child.wait()
        } else {
            let status = child.wait();
            let _ = stop_tx.send(());
            reaping.store(true, Ordering::SeqCst);
            group.kill();
            status
        }
        .context("cannot wait for the reader")?;
        let timed_out = watchdog.join().unwrap_or(true);
        let out = out_rx
            .recv_timeout(Duration::from_secs(10))
            .map_err(|_| anyhow!("the reader's output did not end"))?;
        let err = err_rx
            .recv_timeout(Duration::from_secs(10))
            .ok()
            .and_then(|r| r.ok())
            .unwrap_or_default();
        let stderr = clean(&String::from_utf8_lossy(&err), 2048);
        if timed_out {
            bail!(
                "the reader did not finish within {} s, and was killed",
                timeout.as_secs()
            );
        }
        let out = out.map_err(|e| anyhow!("{e}"))?;
        let (line, rest) = match out.iter().position(|&b| b == b'\n') {
            Some(end) if end <= MAX_ANSWER => (&out[..end], out[end + 1..].to_vec()),
            _ => {
                bail!(
                    "the reader ended ({status}) without an answer{}",
                    if stderr.trim().is_empty() {
                        String::new()
                    } else {
                        format!(": {}", stderr.trim())
                    }
                )
            }
        };
        let answer: Answer = serde_json::from_slice(line)
            .map_err(|_| anyhow!("the reader's answer is not one the host knows ({status})"))?;
        match answer {
            Answer::Error(message) => bail!("{}", clean(&message, 2048)),
            Answer::Ok(_) if !status.success() => {
                bail!("the reader answered, but ended with {status}")
            }
            Answer::Ok(done) => Ok((done, rest)),
        }
    }
}

/// Kills its process group when dropped, unless it was killed already.
struct Group(Option<u32>);

impl Group {
    fn kill(&mut self) {
        if let Some(pgid) = self.0.take() {
            confine::kill_group(pgid);
        }
    }
}

impl Drop for Group {
    fn drop(&mut self) {
        self.kill();
    }
}

/// Reads `source` to its end, keeping at most `cap` bytes: more is an error, and the group of
/// `kill` (the reader that writes too much) is killed at once, unless its leader is being
/// reaped.
fn read_bounded(
    mut source: impl Read,
    cap: usize,
    kill: Option<(u32, Arc<AtomicBool>)>,
) -> Result<Vec<u8>, String> {
    let mut kept = Vec::new();
    let mut buf = [0u8; 64 * 1024];
    let mut over = false;
    loop {
        match source.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                if over {
                    continue;
                }
                if kept.len() + n > cap {
                    over = true;
                    if let Some((pgid, reaping)) = &kill {
                        if !reaping.load(Ordering::SeqCst) {
                            confine::kill_group(*pgid);
                        }
                        return Err(format!(
                            "the reader wrote more than {cap} bytes, and was killed"
                        ));
                    }
                    let room = cap - kept.len();
                    kept.extend_from_slice(&buf[..room]);
                    continue;
                }
                kept.extend_from_slice(&buf[..n]);
            }
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => break,
        }
    }
    Ok(kept)
}

/// The `PATH` a reader gets: the host's, which names no secret, or the usual one.
fn scrubbed_path() -> std::ffi::OsString {
    std::env::var_os("PATH")
        .filter(|p| !p.is_empty())
        .unwrap_or_else(|| "/usr/local/bin:/usr/bin:/bin".into())
}

/// `text` without control characters, at most `max` characters: what a reader says goes into
/// the host's log.
fn clean(text: &str, max: usize) -> String {
    text.chars()
        .take(max)
        .map(|c| if c.is_control() && c != '\n' { '?' } else { c })
        .collect()
}

/// What [`verify_tree`] found.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Found {
    pub files: usize,
    pub bytes: u64,
}

/// Walks `dir` without following anything: only directories and regular files of one link,
/// at most `limits.max_files` of each, no file over `limits.max_file_bytes`, at most
/// `limits.max_bytes` in all. Write bits for others and set-id bits are taken off. What a
/// reader left is checked here, once it is gone and nothing writes there any more.
pub fn verify_tree(dir: &Path, limits: &Limits) -> Result<Found> {
    let meta = std::fs::symlink_metadata(dir)
        .with_context(|| format!("cannot look at {}", dir.display()))?;
    if !meta.is_dir() {
        bail!("{} is no longer a directory", dir.display());
    }
    let mut found = Found { files: 0, bytes: 0 };
    let mut dirs = 0usize;
    let mut stack = vec![dir.to_path_buf()];
    while let Some(current) = stack.pop() {
        for entry in std::fs::read_dir(&current)
            .with_context(|| format!("cannot read {}", current.display()))?
        {
            let path = entry?.path();
            let meta = std::fs::symlink_metadata(&path)?;
            let kind = meta.file_type();
            let shown = path
                .strip_prefix(dir)
                .unwrap_or(&path)
                .display()
                .to_string();
            if kind.is_dir() {
                dirs += 1;
                if dirs > limits.max_files {
                    bail!("more than {} directories", limits.max_files);
                }
                tidy_mode(&path, &meta)?;
                stack.push(path);
            } else if kind.is_file() {
                found.files += 1;
                if found.files > limits.max_files {
                    bail!("more than {} files", limits.max_files);
                }
                #[cfg(unix)]
                {
                    use std::os::unix::fs::MetadataExt;
                    if meta.nlink() != 1 {
                        bail!("`{}` is a hard link", clean(&shown, 256));
                    }
                }
                if meta.len() > limits.max_file_bytes {
                    bail!("`{}` is larger than its limit", clean(&shown, 256));
                }
                found.bytes = found.bytes.saturating_add(meta.len());
                if found.bytes > limits.max_bytes {
                    bail!("more than {} bytes", limits.max_bytes);
                }
                tidy_mode(&path, &meta)?;
            } else {
                bail!(
                    "`{}` is a link, a device, a pipe or a socket",
                    clean(&shown, 256)
                );
            }
        }
    }
    Ok(found)
}

/// Takes the set-id, sticky and group or other write bits off what a reader made.
fn tidy_mode(path: &Path, meta: &std::fs::Metadata) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = meta.permissions().mode() & 0o7777;
        if mode & 0o7022 != 0 {
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode & 0o755))?;
        }
    }
    #[cfg(not(unix))]
    let _ = (path, meta);
    Ok(())
}

/// Removes everything in `dir`, but not `dir` (which may be anchored, as `/output` is).
/// Nothing is followed: a link is removed as a link.
fn clear_dir(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        match std::fs::symlink_metadata(&path) {
            Ok(meta) if meta.is_dir() => {
                let _ = std::fs::remove_dir_all(&path);
            }
            _ => {
                let _ = std::fs::remove_file(&path);
            }
        }
    }
}

/// A full hexadecimal object name (SHA-1 or SHA-256).
fn is_object_name(value: &str) -> bool {
    (value.len() == 40 || value.len() == 64) && value.bytes().all(|b| b.is_ascii_hexdigit())
}

/// One plain file name that ends in `.bundle`.
fn is_bundle_name(name: &str) -> bool {
    name.len() <= 128
        && name.ends_with(".bundle")
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
        && !name.starts_with('.')
}

// ----- the reader's side -------------------------------------------------------------------

/// `reader JOB`: the reader process. Confines itself, does its one job with its stdin, answers
/// on its stdout, and exits. Never run by hand.
pub fn child_main(job: &str) -> ExitCode {
    let job = match parse_job(job) {
        Ok(job) => job,
        Err(e) => return answer(Answer::Error(e), None),
    };
    let plan = plan(&job.work);
    #[cfg(target_os = "linux")]
    limit_resources(&job.work);
    let status = confine::confine(&plan);
    if let Work::Probe {
        deny_read,
        connect,
        signal,
    } = &job.work
    {
        return answer(
            Answer::Ok(probe(status, deny_read, *connect, *signal)),
            None,
        );
    }
    if !status.confined() && !job.allow_unconfined {
        return answer(
            Answer::Error(format!(
                "the reader cannot be confined here, and the host takes no unconfined reader: {}",
                status.describe()
            )),
            None,
        );
    }
    match job.work {
        Work::Probe { .. } => unreachable!("answered above"),
        Work::Git { repo, args } => exec_git(&repo, &args),
        Work::Unpack { dest, gzip, limits } => answer(unpack(&dest, gzip, limits.into()), None),
        Work::ReadOne { max } => match read_one(max) {
            Ok((done, content)) => answer(Answer::Ok(done), content),
            Err(e) => answer(Answer::Error(e), None),
        },
        Work::Bundle {
            repo,
            scratch,
            name,
            head,
            limits,
        } => answer(
            match bundle(&repo, &scratch, &name, &head, limits.into()) {
                Ok(done) => Answer::Ok(done),
                Err(e) => Answer::Error(e),
            },
            None,
        ),
    }
}

fn parse_job(text: &str) -> Result<Job, String> {
    if text.len() > MAX_JOB {
        return Err("the job is too long".into());
    }
    let job: Job = serde_json::from_str(text).map_err(|e| format!("not a job: {e}"))?;
    // Every path of a job is absolute and plain: no `..`, nothing relative to wherever.
    let plain = |p: &Path| {
        p.is_absolute()
            && p.components()
                .all(|c| matches!(c, Component::RootDir | Component::Normal(_)))
    };
    let paths: Vec<&Path> = match &job.work {
        Work::Probe { .. } | Work::ReadOne { .. } => Vec::new(),
        Work::Unpack { dest, .. } => vec![dest],
        Work::Bundle { repo, scratch, .. } => vec![repo, scratch],
        Work::Git { repo, .. } => vec![repo],
    };
    if paths.iter().any(|p| !plain(p) || p.parent().is_none()) {
        return Err("a path of the job is not an absolute, plain path below the root".into());
    }
    Ok(job)
}

fn plan(work: &Work) -> Plan {
    match work {
        Work::Probe { .. } | Work::ReadOne { .. } => Plan::default(),
        Work::Unpack { dest, .. } => Plan {
            write: vec![dest.clone()],
            ..Plan::default()
        },
        Work::Bundle { repo, scratch, .. } => Plan {
            write: vec![repo.clone(), scratch.clone()],
            git: true,
            ..Plan::default()
        },
        Work::Git { repo, .. } => Plan {
            read: vec![repo.clone()],
            git: true,
            ..Plan::default()
        },
    }
}

/// The kernel's own bounds for an unpacking: no file larger than its limit, and a bounded heap,
/// whatever the code in the reader then does.
#[cfg(target_os = "linux")]
fn limit_resources(work: &Work) {
    use rustix::process::{Resource, Rlimit, setrlimit};
    let limit = |resource, value: u64| {
        let _ = setrlimit(
            resource,
            Rlimit {
                current: Some(value),
                maximum: Some(value),
            },
        );
    };
    match work {
        Work::Unpack { limits, .. } => {
            limit(Resource::Fsize, limits.max_file_bytes.max(1));
            limit(Resource::Data, READER_MEMORY);
        }
        Work::ReadOne { .. } => {
            limit(Resource::Fsize, 0);
            limit(Resource::Data, READER_MEMORY);
        }
        Work::Probe { .. } | Work::Bundle { .. } | Work::Git { .. } => {}
    }
}

/// Writes the answer line (and `content` after it) on stdout; the exit code says the same.
fn answer(answer: Answer, content: Option<Vec<u8>>) -> ExitCode {
    let ok = matches!(answer, Answer::Ok(_));
    let mut out = io::stdout().lock();
    let line = serde_json::to_string(&answer)
        .unwrap_or_else(|_| r#"{"error":"the reader cannot write its answer"}"#.to_string());
    let written = out
        .write_all(line.as_bytes())
        .and_then(|()| out.write_all(b"\n"))
        .and_then(|()| match &content {
            Some(content) => out.write_all(content),
            None => Ok(()),
        })
        .and_then(|()| out.flush());
    if ok && written.is_ok() {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(2)
    }
}

fn probe(
    status: Status,
    deny_read: &[PathBuf],
    connect: Option<SocketAddr>,
    signal: Option<u32>,
) -> Done {
    let read = deny_read
        .iter()
        .map(|path| std::fs::File::open(path).is_ok())
        .collect();
    let socket = std::net::UdpSocket::bind("127.0.0.1:0").is_ok();
    let connect = connect
        .map(|addr| std::net::TcpStream::connect_timeout(&addr, Duration::from_secs(2)).is_ok());
    Done::Probe {
        status,
        read,
        socket,
        connect,
        signals: signal.map(probe_signals),
    }
}

/// Tries what would end or slow `target`, which is outside this reader's process group, and
/// whether this reader can still signal its own group.
#[cfg(target_os = "linux")]
fn probe_signals(target: u32) -> Signals {
    use rustix::process::{
        PidfdFlags, Resource, Signal, getpid, getrlimit, kill_current_process_group, kill_process,
        kill_process_group, pidfd_open, prlimit,
    };
    let mut reached = Vec::new();
    if let Some(pid) = i32::try_from(target)
        .ok()
        .and_then(rustix::process::Pid::from_raw)
    {
        if kill_process(pid, Signal::TERM).is_ok() {
            reached.push("kill".to_string());
        }
        if kill_process_group(pid, Signal::TERM).is_ok() {
            reached.push("kill of its group".to_string());
        }
        // Its core size set to the reader's own: harmless where it gets through.
        if prlimit(Some(pid), Resource::Core, getrlimit(Resource::Core)).is_ok() {
            reached.push("prlimit64".to_string());
        }
        if pidfd_open(pid, PidfdFlags::empty()).is_ok() {
            reached.push("pidfd_open".to_string());
        }
    }
    let own_group = kill_current_process_group(Signal::CONT).is_ok()
        && kill_process(getpid(), Signal::CONT).is_ok();
    Signals { reached, own_group }
}

#[cfg(not(target_os = "linux"))]
fn probe_signals(_target: u32) -> Signals {
    Signals {
        reached: vec!["not probed: not Linux".to_string()],
        own_group: false,
    }
}

fn unpack(dest: &Path, gzip: bool, limits: Limits) -> Answer {
    let stdin = io::stdin().lock();
    let unpacked = if gzip {
        files::unpack_untrusted(flate2::read::GzDecoder::new(stdin), dest, &limits)
    } else {
        files::unpack_untrusted(stdin, dest, &limits)
    };
    match unpacked {
        Ok(unpacked) => Answer::Ok(Done::Unpacked {
            files: unpacked.files as u64,
            bytes: unpacked.bytes,
            left_out: unpacked.left_out,
        }),
        Err(e) => Answer::Error(e.to_string()),
    }
}

fn read_one(max: u64) -> Result<(Done, Option<Vec<u8>>), String> {
    let max = max.min(MAX_READ_ONE);
    let read = files::read_one(io::stdin().lock(), max).map_err(|e| e.to_string())?;
    Ok(match read {
        ReadOne::File(content) => (
            Done::ReadOne {
                state: OneState::File,
                size: content.len() as u64,
            },
            Some(content),
        ),
        other => (
            Done::ReadOne {
                state: match other {
                    ReadOne::Missing => OneState::Missing,
                    ReadOne::NotRegular => OneState::NotRegular,
                    _ => OneState::TooLarge,
                },
                size: 0,
            },
            None,
        ),
    })
}

/// git in `repo`, with the host's safe settings and an environment of its own: no system or
/// global configuration, no prompt, no replace refs.
fn git(repo: &Path, home: &Path) -> std::process::Command {
    let mut command = std::process::Command::new("git");
    command
        .args(crate::git::safe_config_args())
        .current_dir(repo)
        .env_clear()
        .env("PATH", scrubbed_path())
        .env("HOME", home)
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_ASKPASS", "true")
        .env("SSH_ASKPASS", "true")
        .env("GIT_NO_REPLACE_OBJECTS", "1")
        .env("GIT_AUTHOR_NAME", READER_IDENTITY.0)
        .env("GIT_AUTHOR_EMAIL", READER_IDENTITY.1)
        .env("GIT_COMMITTER_NAME", READER_IDENTITY.0)
        .env("GIT_COMMITTER_EMAIL", READER_IDENTITY.1)
        .stdin(Stdio::null());
    command
}

fn git_ok(repo: &Path, home: &Path, args: &[&str]) -> Result<String, String> {
    let out = git(repo, home)
        .args(args)
        .output()
        .map_err(|e| format!("cannot run git: {e}"))?;
    if !out.status.success() {
        return Err(format!(
            "git {} failed: {}",
            args.iter()
                .find(|a| !a.starts_with('-') && !a.contains('='))
                .unwrap_or(&""),
            clean(String::from_utf8_lossy(&out.stderr).trim(), 1024)
        ));
    }
    Ok(String::from_utf8_lossy(&out.stdout).into_owned())
}

/// Replaces this process with git: the confinement goes with it.
fn exec_git(repo: &Path, args: &[String]) -> ExitCode {
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        let error = git(repo, Path::new("/nonexistent"))
            .args(args)
            .stdin(Stdio::inherit())
            .exec();
        eprintln!("tinfirm reader: cannot run git: {error}");
        ExitCode::from(127)
    }
    #[cfg(not(unix))]
    {
        let status = git(repo, Path::new("/nonexistent"))
            .args(args)
            .stdin(Stdio::inherit())
            .status();
        match status {
            Ok(status) if status.success() => ExitCode::SUCCESS,
            _ => ExitCode::from(1),
        }
    }
}

fn bundle(
    repo: &Path,
    scratch: &Path,
    name: &str,
    head: &str,
    limits: Limits,
) -> Result<Done, String> {
    if !is_object_name(head) || !is_bundle_name(name) {
        return Err("the job names no object or no bundle".into());
    }
    let unpacked = files::unpack_untrusted(io::stdin().lock(), scratch, &limits)
        .map_err(|e| format!("the bundle's archive: {e}"))?;
    let path = scratch.join(name);
    let regular = std::fs::symlink_metadata(&path).is_ok_and(|m| m.is_file());
    if unpacked.files != 1 || !regular {
        return Err(format!(
            "the sandbox's bundle did not come back (it may be larger than {} GiB)",
            limits.max_file_bytes >> 30
        ));
    }
    check_bundle_header(&path, head)?;
    let bundle = path.to_string_lossy().into_owned();
    let refspec = format!("+HEAD:{IMPORTED_REF}");
    git_ok(
        repo,
        scratch,
        &[
            "-c",
            "fetch.fsckObjects=true",
            "-c",
            "transfer.fsckObjects=true",
            "fetch",
            "--quiet",
            "--no-tags",
            "--",
            &bundle,
            &refspec,
        ],
    )
    .map_err(|e| format!("the sandbox's commits do not fetch: {e}"))?;
    let fetched = git_ok(repo, scratch, &["rev-parse", "--verify", IMPORTED_REF])?;
    let _ = git_ok(repo, scratch, &["update-ref", "-d", IMPORTED_REF]);
    if fetched.trim() != head {
        return Err("the sandbox's bundle holds another HEAD than it reported".into());
    }
    let commit = format!("{head}^{{commit}}");
    git_ok(repo, scratch, &["cat-file", "-e", &commit])
        .map_err(|_| format!("the sandbox's HEAD {head} is not a commit"))?;
    git_ok(repo, scratch, &["reset", "--hard", "--quiet", head])?;
    let _ = std::fs::remove_file(&path);
    Ok(Done::Bundle {
        head: head.to_string(),
    })
}

/// A bundle's header, before git reads the bundle: version 2 or 3, no capability but the object
/// format, prerequisites, and exactly one ref, `HEAD`, at `head`. A ref the run may not write,
/// or a partial bundle (`@filter`), is refused here.
fn check_bundle_header(path: &Path, head: &str) -> Result<(), String> {
    let file = std::fs::File::open(path).map_err(|e| format!("cannot open the bundle: {e}"))?;
    let mut reader = io::BufReader::new(file.take(MAX_BUNDLE_HEADER));
    let mut line = Vec::new();
    let mut next = |line: &mut Vec<u8>| -> Result<bool, String> {
        line.clear();
        let n = reader
            .read_until(b'\n', line)
            .map_err(|e| format!("cannot read the bundle: {e}"))?;
        if n == 0 || line.last() != Some(&b'\n') {
            return Ok(false);
        }
        line.pop();
        Ok(true)
    };
    if !next(&mut line)? {
        return Err("the sandbox's bundle is not a bundle".into());
    }
    let v3 = match line.as_slice() {
        b"# v2 git bundle" => false,
        b"# v3 git bundle" => true,
        _ => return Err("the sandbox's bundle is not a git bundle of version 2 or 3".into()),
    };
    let mut refs: Vec<(String, String)> = Vec::new();
    for _ in 0..MAX_BUNDLE_LINES {
        if !next(&mut line)? {
            return Err("the sandbox's bundle has no end to its header".into());
        }
        let text = std::str::from_utf8(&line).map_err(|_| "the bundle's header is not text")?;
        if text.is_empty() {
            return match refs.as_slice() {
                [(oid, name)] if name == "HEAD" && oid == head => Ok(()),
                [(_, name)] if name == "HEAD" => {
                    Err("the sandbox's bundle holds another HEAD than it reported".into())
                }
                _ => Err(format!(
                    "the sandbox's bundle must hold its HEAD alone, and holds {}",
                    clean(
                        &refs
                            .iter()
                            .map(|(_, n)| n.as_str())
                            .collect::<Vec<_>>()
                            .join(", "),
                        512
                    )
                )),
            };
        }
        if let Some(capability) = text.strip_prefix('@') {
            if !v3 || !matches!(capability, "object-format=sha1" | "object-format=sha256") {
                return Err(format!(
                    "the sandbox's bundle asks for `{}`",
                    clean(capability, 128)
                ));
            }
            continue;
        }
        if let Some(prerequisite) = text.strip_prefix('-') {
            let oid = prerequisite.split(' ').next().unwrap_or("");
            if !is_object_name(oid) {
                return Err("a prerequisite of the sandbox's bundle is not an object".into());
            }
            continue;
        }
        let (oid, name) = text
            .split_once(' ')
            .ok_or("a line of the bundle's header is not a ref")?;
        if !is_object_name(oid) {
            return Err("a ref of the sandbox's bundle names no object".into());
        }
        if refs.len() >= 16 {
            return Err("the sandbox's bundle holds too many refs".into());
        }
        refs.push((oid.to_string(), name.to_string()));
    }
    Err("the sandbox's bundle has too long a header".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jobs_round_trip_and_refuse_what_is_not_theirs() {
        let job = Job {
            allow_unconfined: false,
            work: Work::Unpack {
                dest: "/data/homes/a/b".into(),
                gzip: false,
                limits: JobLimits {
                    max_files: 1,
                    max_bytes: 2,
                    max_file_bytes: 3,
                },
            },
        };
        let text = serde_json::to_string(&job).unwrap();
        assert!(parse_job(&text).is_ok());
        for bad in [
            r#"{"allow_unconfined":false,"work":{"kind":"unpack","dest":"relative","gzip":false,"limits":{"max_files":1,"max_bytes":1,"max_file_bytes":1}}}"#,
            r#"{"allow_unconfined":false,"work":{"kind":"unpack","dest":"/a/../etc","gzip":false,"limits":{"max_files":1,"max_bytes":1,"max_file_bytes":1}}}"#,
            r#"{"allow_unconfined":false,"work":{"kind":"unpack","dest":"/","gzip":false,"limits":{"max_files":1,"max_bytes":1,"max_file_bytes":1}}}"#,
            r#"{"allow_unconfined":false,"work":{"kind":"shell","command":"sh"}}"#,
            r#"{"allow_unconfined":false,"extra":1,"work":{"kind":"read_one","max":1}}"#,
        ] {
            assert!(parse_job(bad).is_err(), "{bad}");
        }
        assert!(parse_job(&"x".repeat(MAX_JOB + 1)).is_err());
    }

    #[test]
    fn answers_are_a_closed_set() {
        for bad in [
            r#"{"ok":{"kind":"unpacked","files":1,"bytes":1,"left_out":[],"more":1}}"#,
            r#"{"ok":{"kind":"shell"}}"#,
            r#"{"maybe":"yes"}"#,
            r#"{"ok":{"kind":"bundle","head":1}}"#,
        ] {
            assert!(serde_json::from_str::<Answer>(bad).is_err(), "{bad}");
        }
        let ok: Answer = serde_json::from_str(r#"{"ok":{"kind":"bundle","head":"ab"}}"#).unwrap();
        assert!(matches!(ok, Answer::Ok(Done::Bundle { .. })));
    }

    #[test]
    fn bundle_names_and_object_names_are_plain() {
        assert!(is_bundle_name("repository-0.bundle"));
        for bad in [
            "../x.bundle",
            ".bundle",
            "a/b.bundle",
            "x.tar",
            "a b.bundle",
        ] {
            assert!(!is_bundle_name(bad), "{bad}");
        }
        assert!(is_object_name(&"a".repeat(40)));
        assert!(!is_object_name(&"g".repeat(40)));
    }

    fn header(text: &str) -> Result<(), String> {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("b.bundle");
        std::fs::write(&path, text).unwrap();
        check_bundle_header(&path, &"a".repeat(40))
    }

    #[test]
    fn a_bundle_holds_its_head_alone() {
        let head = "a".repeat(40);
        let other = "b".repeat(40);
        assert!(
            header(&format!(
                "# v2 git bundle\n-{other} base\n{head} HEAD\n\nPACK"
            ))
            .is_ok()
        );
        assert!(
            header(&format!(
                "# v3 git bundle\n@object-format=sha1\n{head} HEAD\n\nPACK"
            ))
            .is_ok()
        );
        for bad in [
            format!("# v2 git bundle\n{head} HEAD\n{head} refs/heads/main\n\n"),
            format!("# v2 git bundle\n{head} refs/heads/main\n\n"),
            format!("# v2 git bundle\n{other} HEAD\n\n"),
            format!("# v3 git bundle\n@filter=blob:none\n{head} HEAD\n\n"),
            format!("# v2 git bundle\n@object-format=sha1\n{head} HEAD\n\n"),
            format!("# v2 git bundle\n{head} HEAD\n"),
            "PK\u{3}\u{4} not a bundle".to_string(),
            String::new(),
            format!("# v2 git bundle\n-xyz\n{head} HEAD\n\n"),
        ] {
            assert!(header(&bad).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn a_tree_is_walked_without_following_anything() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("a/b")).unwrap();
        std::fs::write(root.join("a/b/f"), "four").unwrap();
        let limits = Limits {
            max_files: 10,
            max_bytes: 100,
            max_file_bytes: 10,
        };
        assert_eq!(
            verify_tree(root, &limits).unwrap(),
            Found { files: 1, bytes: 4 }
        );
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(root.join("a/b/f"), std::fs::Permissions::from_mode(0o4777))
                .unwrap();
            verify_tree(root, &limits).unwrap();
            let mode = std::fs::metadata(root.join("a/b/f"))
                .unwrap()
                .permissions()
                .mode();
            assert_eq!(mode & 0o7777, 0o755);
            std::os::unix::fs::symlink("/etc/passwd", root.join("a/link")).unwrap();
            assert!(verify_tree(root, &limits).is_err());
            std::fs::remove_file(root.join("a/link")).unwrap();
            std::fs::hard_link(root.join("a/b/f"), root.join("a/hard")).unwrap();
            assert!(verify_tree(root, &limits).is_err());
            std::fs::remove_file(root.join("a/hard")).unwrap();
        }
        std::fs::write(root.join("big"), "x".repeat(11)).unwrap();
        assert!(verify_tree(root, &limits).is_err());
        clear_dir(root);
        assert_eq!(std::fs::read_dir(root).unwrap().count(), 0);
    }
}
