//! The front process of a sandbox (ADR-0112, decision 1, path C, part 2): the long-lived,
//! confined process that is to take the parsing of what a sandbox sends (the bridge's TLS,
//! frames and tunnels, and the HTTP of the git, model and egress proxies and of the platform
//! tools) out of `run`, which holds the credentials. `run` is to start one per sandbox and to
//! talk with it over typed, bounded messages; it attaches every credential itself.
//!
//! This version builds the front's confinement and its probe, nothing more: `run` starts no
//! front yet. `front --probe JOB` confines itself as a front does
//! ([`crate::confine::Profile::Front`]) and then tries what a front must be refused, and answers
//! one line of JSON on stdout ([`Probe`]). `doctor` runs it ([`probe`]).
//!
//! What a front applies to itself, before it builds any runtime or reads a byte:
//! - resource limits: 512 MiB of data, 1024 descriptors, no core file;
//! - not dumpable; Landlock with no directory at all, so it opens only `/dev/null` and
//!   `/dev/urandom`, and, where the kernel has them, no TCP `bind` or `connect` and no signal
//!   or abstract socket outside its domain;
//! - the reader's seccomp filter, and on top of it: no `execve`, `fork`, `vfork` or `clone`
//!   of a process (threads still start), no `connect`, `bind` or `listen`, and no signal
//!   outside its own process group, not even the signal 0. A pair of Unix sockets
//!   (`socketpair`) stays allowed: tokio's signal driver makes one, and it reaches no other
//!   process.
//!
//! It confines itself before it builds its tokio runtime, whose threads inherit all of it. The
//! runtime gets a fixed number of workers: counting the CPUs reads cgroup files that Landlock
//! now refuses.
//!
//! Its argument names no secret: `/proc/<pid>/cmdline` is readable by every user.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode, Stdio};
use std::sync::mpsc;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};

use crate::confine::{self, Plan, Status};

/// The hidden subcommand that starts a front.
pub const SUBCOMMAND: &str = "front";

/// The most a front's answer to its probe may be.
const MAX_ANSWER: u64 = 64 * 1024;

/// The data a front may use: the link's buffers (12 MiB), its streams' (16 MiB at most) and
/// the HTTP servers', with room.
pub const FRONT_MEMORY: u64 = 512 * 1024 * 1024;
/// The descriptors a front may hold.
pub const FRONT_FILES: u64 = 1024;
/// The worker threads of a front's runtime.
pub const FRONT_WORKERS: usize = 2;

/// What `front --probe` is to try, besides its own confinement.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProbeJob {
    /// Paths it must not open for reading.
    #[serde(default)]
    pub deny_read: Vec<PathBuf>,
    /// Processes outside its group that it must not signal, limit or open a pidfd of.
    #[serde(default)]
    pub signal: Vec<u32>,
    /// After its answer, stay until its stdin ends: a sibling front for another's probe.
    #[serde(default)]
    pub linger: bool,
}

/// What a front's probe found. Every field but `status`, `own_group` and `runtime` is what got
/// through, which must be nothing.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Probe {
    pub status: Status,
    /// Per path of `deny_read`: it could be opened.
    pub read: Vec<bool>,
    /// A socket could be made.
    pub socket: bool,
    /// Per process of `signal`: what got through (`signal 0`, `SIGCONT`, `prlimit64`,
    /// `pidfd_open`).
    pub reached: Vec<Vec<String>>,
    /// A program could be started (`fork` or `clone`, and `execve`).
    pub spawn: bool,
    /// This process could replace itself with a program (`execve`). Where it could, there is no
    /// answer at all.
    pub exec: bool,
    /// It could still signal its own process group and itself.
    pub own_group: bool,
    /// A tokio runtime with its workers ran a task under the confinement.
    pub runtime: bool,
}

impl Probe {
    /// The front is confined, and was refused everything it must be refused.
    pub fn confined(&self) -> bool {
        self.status.confined() && self.refused_all() && self.own_group && self.runtime
    }

    /// Nothing of what it tried got through.
    pub fn refused_all(&self) -> bool {
        !self.read.contains(&true)
            && !self.socket
            && self.reached.iter().all(Vec::is_empty)
            && !self.spawn
            && !self.exec
    }

    /// One line, for `doctor`.
    pub fn describe(&self) -> String {
        let mut text = self.status.describe();
        if self.status.confined() {
            let mut got = Vec::new();
            if self.read.contains(&true) {
                got.push("read the host's files");
            }
            if self.socket {
                got.push("made a socket");
            }
            if !self.reached.iter().all(Vec::is_empty) {
                got.push("reached another process");
            }
            if self.spawn || self.exec {
                got.push("started a program");
            }
            if !got.is_empty() {
                text.push_str("; but it ");
                text.push_str(&got.join(", "));
            }
            if !self.runtime {
                text.push_str("; but its runtime did not run");
            }
        }
        text
    }
}

// ----- the front's side --------------------------------------------------------------------

/// `front [--probe JOB]`: the front process. Confines itself first. Never run by hand.
pub fn child_main(probe: Option<&str>) -> ExitCode {
    let job = probe.map(serde_json::from_str::<ProbeJob>);
    limit_resources();
    let status = confine::confine(&Plan::front());
    let job = match job {
        Some(Ok(job)) => job,
        Some(Err(e)) => return fail(&format!("the probe's job is not valid: {e}")),
        None => {
            return fail("this version's front only runs its probe (--probe); `run` starts none");
        }
    };
    let answer = run_probe(status, &job);
    let mut out = std::io::stdout().lock();
    let written = serde_json::to_writer(&mut out, &answer)
        .map_err(std::io::Error::from)
        .and_then(|()| out.write_all(b"\n"))
        .and_then(|()| out.flush());
    drop(out);
    if job.linger {
        let _ = std::io::copy(&mut std::io::stdin().lock(), &mut std::io::sink());
    }
    if written.is_ok() {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(2)
    }
}

fn fail(message: &str) -> ExitCode {
    eprintln!("tinfirm-runner-host front: {message}");
    ExitCode::from(2)
}

/// The kernel's bounds for a front, whatever its code then does.
fn limit_resources() {
    #[cfg(target_os = "linux")]
    {
        use rustix::process::{Resource, Rlimit, setrlimit};
        for (resource, value) in [
            (Resource::Data, FRONT_MEMORY),
            (Resource::Nofile, FRONT_FILES),
            (Resource::Core, 0),
        ] {
            let _ = setrlimit(
                resource,
                Rlimit {
                    current: Some(value),
                    maximum: Some(value),
                },
            );
        }
    }
}

fn run_probe(status: Status, job: &ProbeJob) -> Probe {
    let read = job
        .deny_read
        .iter()
        .map(|path| std::fs::File::open(path).is_ok())
        .collect();
    let socket = std::net::UdpSocket::bind("127.0.0.1:0").is_ok();
    let reached = job.signal.iter().map(|&pid| reach(pid)).collect();
    let own_group = own_group();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(FRONT_WORKERS)
        .enable_all()
        .build()
        .is_ok_and(|runtime| {
            runtime.block_on(async {
                tokio::spawn(async {
                    tokio::time::sleep(Duration::from_millis(1)).await;
                    7
                })
                .await
                .is_ok_and(|n| n == 7)
            })
        });
    let spawn = Command::new("/bin/true")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok();
    // Last: where it gets through, this process is `true` from here on and answers nothing.
    let exec = {
        use std::os::unix::process::CommandExt;
        let _error = Command::new("/bin/true").exec();
        false
    };
    Probe {
        status,
        read,
        socket,
        reached,
        spawn,
        exec,
        own_group,
        runtime,
    }
}

/// What gets through to `pid`, a process outside this front's group.
#[cfg(target_os = "linux")]
fn reach(pid: u32) -> Vec<String> {
    use rustix::process::{
        Pid, PidfdFlags, Resource, Signal, getrlimit, kill_process, pidfd_open, prlimit,
        test_kill_process,
    };
    let Some(pid) = i32::try_from(pid).ok().and_then(Pid::from_raw) else {
        return Vec::new();
    };
    let mut reached = Vec::new();
    if test_kill_process(pid).is_ok() {
        reached.push("signal 0".to_string());
    }
    if kill_process(pid, Signal::CONT).is_ok() {
        reached.push("SIGCONT".to_string());
    }
    // Its core size set to the front's own (0): harmless where it gets through.
    if prlimit(Some(pid), Resource::Core, getrlimit(Resource::Core)).is_ok() {
        reached.push("prlimit64".to_string());
    }
    if pidfd_open(pid, PidfdFlags::empty()).is_ok() {
        reached.push("pidfd_open".to_string());
    }
    reached
}

#[cfg(not(target_os = "linux"))]
fn reach(_pid: u32) -> Vec<String> {
    vec!["not probed: not Linux".to_string()]
}

#[cfg(target_os = "linux")]
fn own_group() -> bool {
    use rustix::process::{Signal, getpid, kill_current_process_group, kill_process};
    kill_current_process_group(Signal::CONT).is_ok() && kill_process(getpid(), Signal::CONT).is_ok()
}

#[cfg(not(target_os = "linux"))]
fn own_group() -> bool {
    false
}

// ----- the host's side ---------------------------------------------------------------------

/// Starts `binary front --probe JOB`, as `run` starts a front: an empty environment, its own
/// process group, no descriptor but its three. Reads its one line, then kills its group.
pub fn probe(binary: &Path, job: &ProbeJob, timeout: Duration) -> Result<Probe> {
    let job = serde_json::to_string(job)?;
    let mut command = Command::new(binary);
    command
        .arg(SUBCOMMAND)
        .arg("--probe")
        .arg(job)
        .env_clear()
        .current_dir("/")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }
    let mut child = command
        .spawn()
        .with_context(|| format!("cannot start the front {}", binary.display()))?;
    let pgid = child.id();
    let stdout = child.stdout.take().context("the front has no stdout")?;
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let mut bytes = Vec::new();
        let read = stdout.take(MAX_ANSWER + 1).read_to_end(&mut bytes);
        let _ = tx.send(read.map(|_| bytes));
    });
    let answer = rx.recv_timeout(timeout);
    confine::kill_group(pgid);
    let _ = child.wait();
    let bytes = match answer {
        Ok(Ok(bytes)) => bytes,
        Ok(Err(e)) => return Err(e).context("cannot read the front's answer"),
        Err(_) => bail!("the front did not answer within {} s", timeout.as_secs()),
    };
    if bytes.len() as u64 > MAX_ANSWER {
        bail!("the front's answer is over {MAX_ANSWER} bytes");
    }
    let line = bytes
        .split(|&b| b == b'\n')
        .next()
        .filter(|line| !line.is_empty())
        .context("the front answered nothing (did it start a program in its place?)")?;
    serde_json::from_slice(line).context("the front's answer is not a probe's")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_probe_counts_as_confined_only_when_nothing_got_through() {
        let status = Status {
            landlock_abi: 7,
            landlock: true,
            landlock_net: true,
            landlock_scope: true,
            seccomp: true,
            no_new_privs: true,
            not_dumpable: true,
            problem: None,
        };
        let clean = Probe {
            status,
            read: vec![false, false],
            socket: false,
            reached: vec![Vec::new()],
            spawn: false,
            exec: false,
            own_group: true,
            runtime: true,
        };
        assert!(clean.confined());
        for broken in [
            Probe {
                read: vec![false, true],
                ..clean.clone()
            },
            Probe {
                socket: true,
                ..clean.clone()
            },
            Probe {
                reached: vec![vec!["signal 0".into()]],
                ..clean.clone()
            },
            Probe {
                spawn: true,
                ..clean.clone()
            },
            Probe {
                runtime: false,
                ..clean.clone()
            },
        ] {
            assert!(!broken.confined(), "{broken:?}");
            assert!(
                broken.describe().contains("; but it"),
                "{}",
                broken.describe()
            );
        }
    }

    #[test]
    fn a_probes_job_takes_no_unknown_field() {
        assert!(serde_json::from_str::<ProbeJob>(r#"{"deny_read":[],"x":1}"#).is_err());
        assert_eq!(
            serde_json::from_str::<ProbeJob>("{}").unwrap(),
            ProbeJob::default()
        );
    }
}
