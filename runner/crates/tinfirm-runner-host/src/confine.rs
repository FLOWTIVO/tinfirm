//! The reader's confinement (ADR-0112, open question 1; runner/README.md, "The reader"): what an
//! unprivileged process applies to itself on Linux before it reads a byte of a sandbox's
//! input, and the runner host's own protection from a process of its user.
//!
//! A process of the runner host's user could otherwise read the host's token file (0600, the
//! same user), the take-back records, `/proc/<host>/mem` and the other runs' directories. The
//! reader ([`crate::reader`]) therefore, in this order, before it reads its input:
//!
//! - makes itself not dumpable (`PR_SET_DUMPABLE` 0): no process of its user traces it or
//!   reads its memory;
//! - restricts itself with Landlock: reading and executing the system's directories (for git;
//!   and, where git is installed outside them, the directory that holds it and the one that
//!   holds its other programs, with nothing around them: [`Guarded`]),
//!   reading the directories its job reads, writing only the directories its job writes (and
//!   never executing there), `/dev/null`, reading `/dev/urandom`. Of every other path, `/proc`
//!   included, it is refused what Landlock checks: opening a file, listing a directory,
//!   executing, and creating, removing, renaming or linking anything; from Landlock 3 (Linux
//!   6.2) also truncating a file. Landlock checks no path's metadata, in any version: a reader
//!   can still `stat` any path its user can reach and read its extended attributes, and change
//!   the mode, the times, the extended attributes and (as far as its user may) the owner or
//!   group of that user's files, the host's among them (`chmod`, `utimensat`, `setxattr`,
//!   `chown`). So it can lock the host out of its own files or open one to the machine's other
//!   users, but it reads and writes the content of none. The filter below does not refuse
//!   those calls: it cannot tell one path from another, and git changes modes by path itself
//!   (its lock files). Where the kernel has them, TCP `bind` and `connect` are refused
//!   (Landlock 4, Linux 6.7), and so are signals to and abstract sockets of processes outside
//!   its domain (Landlock 6, Linux 6.12). Landlock also refuses it `ptrace` of any process
//!   outside its domain. This sets `no_new_privs`;
//! - installs a seccomp filter that refuses, with `EPERM`: creating a socket of any family, so
//!   no network and no Unix socket, whatever Landlock's version; `truncate` by path, which
//!   Landlock checks only from its version 3 (Linux 6.2); `ptrace`,
//!   `process_vm_readv`/`writev`, `pidfd_getfd`, `kcmp`; namespaces (`unshare`, `setns`,
//!   `clone` with a `CLONE_NEW*` flag; `clone3` answers `ENOSYS`, so the C library falls back
//!   to `clone`); mounts and `chroot`; `io_uring`, whose operations seccomp does not see;
//!   `bpf`, `perf_event_open`, `userfaultfd`, the kernel keyring, modules, `kexec`,
//!   `open_by_handle_at`; `setsid` and `setpgid`, so that nothing it starts leaves the process
//!   group that the host kills when the job is over; `PR_SET_DUMPABLE`, so it cannot make
//!   itself traceable again; and a signal to any process outside its process group, whatever
//!   Landlock's version (`kill`, `tkill`, `tgkill`, `rt_sigqueueinfo`, `rt_tgsigqueueinfo` to
//!   another target than its group or itself, `pidfd_open`, `pidfd_send_signal`, and `F_SETOWN`
//!   or `FIOSETOWN`, which would have `SIGIO` sent), and acting on another process's limits or
//!   priority (`prlimit64`, the `sched_set*` calls, `setpriority`, `ioprio_set`). A positive pid
//!   of its own group cannot be told from another in a seccomp filter, so only the reader's own
//!   pid is allowed: git's cleanup of its children after an error is refused, and the host's
//!   kill of the group does it. The list names its calls by their numbers in the 64-bit ABI. An
//!   x86-64 kernel built with the x32 ABI takes each of them under a second number as well,
//!   which no rule of the list matches: on x86-64 a further filter refuses every call of that
//!   ABI. A call of a 32-bit ABI ends the process.
//!
//! Landlock 2 (Linux 5.19) is the least that counts as confined: before it, Landlock refuses
//! every rename between two directories, which git needs to store an object. The child
//! inherits all of it across `execve` (git and what git starts), except the dumpable flag,
//! which `execve` sets again: Landlock's scope is what keeps those processes from the host.
//!
//! The runner host makes itself not dumpable at its start ([`protect_host_process`]), so that
//! no other process of its user (a git it starts, a process that a flaw of one gave someone)
//! reads its memory through `/proc/<pid>/mem` or `ptrace`. Root still can. A host with fronts
//! also raises its own limit of open files, which Linux measures the descriptors on their way
//! over Unix sockets against, far above what its readers and fronts may hold
//! ([`raise_descriptor_limit`]).
//!
//! Elsewhere (macOS, Windows, a Linux kernel without Landlock 2) nothing of this exists, and
//! [`confine`] says so: the host decides what to do with such a reader ([`crate::reader`]).

#[cfg(target_os = "linux")]
use std::path::Path;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

/// What a reader or a front may touch.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Plan {
    /// Read beneath, and nothing else.
    pub read: Vec<PathBuf>,
    /// Read and write beneath (never execute).
    pub write: Vec<PathBuf>,
    /// The job runs git, so the system's directories may be read and executed, and git's own
    /// two where it is installed outside them.
    pub git: bool,
    /// What the directories of such a git must stay clear of.
    pub guarded: Guarded,
    /// Which seccomp filter applies.
    pub profile: Profile,
}

/// The host's own places. A reader that runs a git from outside the system's directories reads
/// and executes everything beneath the directory that holds it and the one that holds its other
/// programs, so neither may reach these: such a git is refused, and its reader is not confined
/// ([`confine`]). The host gives them resolved (no symbolic link), as git's directories are.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Guarded {
    /// The host's config and data directories: no directory of git's holds one or lies in one.
    pub dirs: Vec<PathBuf>,
    /// The home of the host's user: no directory of git's is it or holds it.
    pub home: Option<PathBuf>,
}

/// The kind of process that confines itself, which decides its seccomp filter.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Profile {
    /// A reader ([`crate::reader`]): it runs git, which starts processes of its own.
    #[default]
    Reader,
    /// A sandbox's front process ([`crate::front`]): long-lived, it starts nothing and makes no
    /// connection. On top of the reader's filter it is refused `execve` and `execveat`, `fork`
    /// and `vfork`, `clone` without `CLONE_THREAD` (threads still start), `connect`, `bind` and
    /// `listen`, and every signal outside its own process group, the signal 0 (a check that a
    /// process exists) included. `socketpair` stays: tokio's signal driver makes one, and a pair
    /// of Unix sockets reaches no other process. `sendmsg` and `sendmmsg` are refused, the
    /// calls that pass a descriptor: `run` passes a front every descriptor and takes none back
    /// ([`crate::front::wire`]), and a front that left descriptors on their way on a pair of its
    /// own would count against `run` ([`raise_descriptor_limit`]). A filter cannot read the
    /// message that a call points to, so it cannot refuse only those that carry a descriptor:
    /// a front writes with `write` and `sendto`. Its plan reads and writes nothing.
    Front,
}

impl Plan {
    /// A front's plan: no directory at all, so only `/dev/null` and `/dev/urandom`.
    pub fn front() -> Plan {
        Plan {
            profile: Profile::Front,
            ..Plan::default()
        }
    }
}

/// How far a reader is confined, as it found when it confined itself.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Status {
    /// The Landlock ABI the kernel gives, 0 for none.
    pub landlock_abi: u32,
    /// Landlock's file-system rules hold (ABI 2 at least).
    pub landlock: bool,
    /// Landlock refuses TCP `bind` and `connect` (ABI 4).
    pub landlock_net: bool,
    /// Landlock refuses signals and abstract sockets outside the domain (ABI 6).
    pub landlock_scope: bool,
    pub seccomp: bool,
    pub no_new_privs: bool,
    pub not_dumpable: bool,
    /// Why something did not apply, for the host's log. Bounded by the host when it reads it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub problem: Option<String>,
}

impl Status {
    /// Nothing of the confinement applied.
    pub fn none(problem: impl Into<String>) -> Status {
        Status {
            landlock_abi: 0,
            landlock: false,
            landlock_net: false,
            landlock_scope: false,
            seccomp: false,
            no_new_privs: false,
            not_dumpable: false,
            problem: Some(problem.into()),
        }
    }

    /// The least that counts as confined: Landlock's file rules, the seccomp filter (which
    /// refuses every socket, whatever Landlock's version), `no_new_privs` and not dumpable.
    pub fn confined(&self) -> bool {
        self.landlock && self.seccomp && self.no_new_privs && self.not_dumpable
    }

    /// For the log and `doctor`.
    pub fn describe(&self) -> String {
        if !self.landlock && !self.seccomp {
            return format!(
                "not confined: {}",
                self.problem
                    .as_deref()
                    .unwrap_or("no Landlock and no seccomp here")
            );
        }
        let mut parts = Vec::new();
        if self.landlock {
            let mut landlock = format!("Landlock ABI {} (files", self.landlock_abi);
            if self.landlock_net {
                landlock.push_str(", TCP");
            }
            if self.landlock_scope {
                landlock.push_str(", signals and abstract sockets");
            }
            landlock.push(')');
            parts.push(landlock);
        } else {
            parts.push(format!(
                "no Landlock ({})",
                self.problem
                    .as_deref()
                    .unwrap_or("the kernel has no Landlock 2")
            ));
        }
        parts.push(if self.seccomp {
            "seccomp filter".to_string()
        } else {
            "no seccomp filter".to_string()
        });
        if self.no_new_privs {
            parts.push("no_new_privs".to_string());
        }
        if self.not_dumpable {
            parts.push("not dumpable".to_string());
        }
        parts.join(", ")
    }
}

/// The system's directories that git and its libraries are read and executed from.
#[cfg(target_os = "linux")]
const SYSTEM_DIRS: &[&str] = &[
    "/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32",
];

/// Files of `/etc` that the dynamic loader reads. Nothing else of `/etc`: it can hold a
/// driver's token (`/etc/tinfirm`).
#[cfg(target_os = "linux")]
const SYSTEM_FILES: &[&str] = &["/etc/ld.so.cache", "/etc/ld.so.conf", "/etc/ld.so.conf.d"];

/// Whether `path` is beneath one of [`SYSTEM_DIRS`].
#[cfg(target_os = "linux")]
fn in_system_dirs(path: &Path) -> bool {
    SYSTEM_DIRS.iter().any(|dir| path.starts_with(dir))
}

/// The directories that a reader's git is read and executed from on top of [`SYSTEM_DIRS`]:
/// none for the system's git. For a git installed elsewhere, the directory that holds the first
/// `git` on `PATH` and the one that holds its other programs, which `git --exec-path` names
/// (`libexec/git-core` beside its directory where git does not answer). Only these two: the
/// directory above them (`/opt/git` for `/opt/git/bin/git`) is the user's home for a git that
/// was built there (`~/bin/git`), with the host's token in it. `Err` says why such a git is
/// refused ([`git_dirs_of`]).
#[cfg(target_os = "linux")]
fn git_dirs(guarded: &Guarded) -> Result<Vec<PathBuf>, String> {
    let Some(path) = std::env::var_os("PATH") else {
        return Ok(Vec::new());
    };
    let Some(git) = std::env::split_paths(&path)
        .map(|dir| dir.join("git"))
        .find(|candidate| candidate.is_file())
        .and_then(|git| std::fs::canonicalize(git).ok())
    else {
        return Ok(Vec::new());
    };
    if in_system_dirs(&git) {
        return Ok(Vec::new());
    }
    let exec = git_exec_path(&git, &path).or_else(|| {
        let beside = git.parent()?.parent()?.join("libexec/git-core");
        std::fs::canonicalize(beside)
            .ok()
            .filter(|dir| dir.is_dir())
    });
    git_dirs_of(&git, exec.as_deref(), guarded)
}

/// What `git --exec-path` answers, resolved: the directory of git's other programs. It is asked
/// before the confinement and before any input is read, in `/` and not in a clone, with no
/// environment but `PATH`.
#[cfg(target_os = "linux")]
fn git_exec_path(git: &Path, path: &std::ffi::OsStr) -> Option<PathBuf> {
    use std::os::unix::ffi::OsStrExt;
    use std::process::Stdio;
    let out = std::process::Command::new(git)
        .arg("--exec-path")
        .env_clear()
        .env("PATH", path)
        .current_dir("/")
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let line = out.stdout.strip_suffix(b"\n").unwrap_or(&out.stdout);
    let dir = Path::new(std::ffi::OsStr::from_bytes(line));
    if !dir.is_absolute() {
        return None;
    }
    std::fs::canonicalize(dir).ok().filter(|dir| dir.is_dir())
}

/// [`git_dirs`] of the resolved `git` whose other programs are in `exec`: the two directories,
/// without what the system's directories hold already. `Err` where one of them is the root, is
/// or holds the home of the host's user, or holds or lies in a directory of the host's
/// ([`Guarded`]): a reader would read the host's files there, or execute what a reader wrote.
#[cfg(target_os = "linux")]
fn git_dirs_of(git: &Path, exec: Option<&Path>, guarded: &Guarded) -> Result<Vec<PathBuf>, String> {
    let mut dirs: Vec<PathBuf> = Vec::new();
    for dir in [git.parent(), exec].into_iter().flatten() {
        if !in_system_dirs(dir) && !dirs.iter().any(|have| dir.starts_with(have)) {
            dirs.push(dir.to_path_buf());
        }
    }
    for dir in &dirs {
        let reaches = if dir.parent().is_none() {
            Some("is the whole file system".to_string())
        } else if guarded
            .home
            .as_ref()
            .is_some_and(|home| home.starts_with(dir))
        {
            Some("is or holds the home of this host's user".to_string())
        } else {
            guarded
                .dirs
                .iter()
                .find(|own| own.starts_with(dir) || dir.starts_with(own))
                .map(|own| format!("holds or lies in this host's own {}", own.display()))
        };
        if let Some(reaches) = reaches {
            return Err(format!(
                "git is {}, and a reader that runs it would read and execute all of {}, which {reaches}; install git in a directory of its own (as /opt/git/bin/git) or use the system's",
                git.display(),
                dir.display(),
            ));
        }
    }
    Ok(dirs)
}

/// Confines this process as `plan` says, as far as this system can ([`Status`]). Called once,
/// by a single-threaded process, before it reads its input.
#[cfg(target_os = "linux")]
pub fn confine(plan: &Plan) -> Status {
    use landlock::{
        ABI, Access, AccessFs, AccessNet, CompatLevel, Compatible, LandlockStatus, Ruleset,
        RulesetAttr, RulesetCreatedAttr, RulesetStatus, Scope, path_beneath_rules,
    };
    use rustix::process::{DumpableBehavior, set_dumpable_behavior};

    let not_dumpable = set_dumpable_behavior(DumpableBehavior::NotDumpable).is_ok();
    // The newest ABI this was tested with (Linux 6.15 to 6.18); newer kernels apply it.
    let abi = ABI::V7;
    let mut read_exec: Vec<PathBuf> = Vec::new();
    let mut read_files: Vec<PathBuf> = Vec::new();
    let mut problem = None;
    if plan.git {
        read_exec.extend(SYSTEM_DIRS.iter().map(PathBuf::from));
        match git_dirs(&plan.guarded) {
            Ok(dirs) => read_exec.extend(dirs),
            Err(refusal) => problem = Some(refusal),
        }
        read_files.extend(SYSTEM_FILES.iter().map(PathBuf::from));
    }
    // A git that is refused its directories leaves the reader without Landlock, as a kernel
    // without it does: the reader then refuses its job, unless the host takes an unconfined one.
    let git_refused = problem.is_some();
    let landlock = (!git_refused).then(|| {
        let created = Ruleset::default()
            // Landlock 2 at least, or no Landlock at all: before it, git cannot store objects.
            .set_compatibility(CompatLevel::HardRequirement)
            .handle_access(AccessFs::from_all(ABI::V2))?
            .set_compatibility(CompatLevel::BestEffort)
            .handle_access(AccessFs::from_all(abi))?
            .handle_access(AccessNet::from_all(abi))?
            .scope(Scope::from_all(abi))?
            .create()?;
        let write = AccessFs::from_all(abi) & !AccessFs::Execute;
        let read = AccessFs::ReadFile | AccessFs::ReadDir;
        created
            .add_rules(path_beneath_rules(&read_exec, AccessFs::from_read(abi)))?
            .add_rules(path_beneath_rules(&read_files, read))?
            .add_rules(path_beneath_rules(&plan.read, read))?
            .add_rules(path_beneath_rules(&plan.write, write))?
            .add_rules(path_beneath_rules(
                ["/dev/null"],
                AccessFs::ReadFile | AccessFs::WriteFile,
            ))?
            // git's temporary names come from the system's random source.
            .add_rules(path_beneath_rules(["/dev/urandom"], AccessFs::ReadFile))?
            .restrict_self()
    });
    let (landlock_abi, landlock, landlock_net, landlock_scope, no_new_privs) = match landlock {
        None => (0, false, false, false, false),
        Some(Ok(status)) => {
            let effective = match status.landlock {
                LandlockStatus::Available { effective_abi, .. } => effective_abi as u32,
                _ => 0,
            };
            let enforced = status.ruleset != RulesetStatus::NotEnforced && effective >= 2;
            if !enforced {
                problem = Some(format!("Landlock is not enforced ({:?})", status.landlock));
            }
            (
                effective,
                enforced,
                enforced && effective >= 4,
                enforced && effective >= 6,
                status.no_new_privs,
            )
        }
        Some(Err(e)) => {
            problem = Some(format!("no Landlock 2 (Linux 5.19) here: {e}"));
            (0, false, false, false, false)
        }
    };
    let seccomp = match seccomp::apply(plan.profile) {
        Ok(()) => true,
        Err(e) => {
            problem.get_or_insert(format!("no seccomp filter: {e}"));
            false
        }
    };
    // The filter sets no_new_privs too.
    let no_new_privs = no_new_privs || seccomp;
    Status {
        landlock_abi,
        landlock,
        landlock_net,
        landlock_scope,
        seccomp,
        no_new_privs,
        not_dumpable,
        problem,
    }
}

/// Not Linux: nothing to confine with.
#[cfg(not(target_os = "linux"))]
pub fn confine(_plan: &Plan) -> Status {
    Status::none("this system has no Landlock and no seccomp (Linux only)")
}

/// Makes the runner host not dumpable (`PR_SET_DUMPABLE` 0): no other process of its user
/// traces it or reads its memory, its environment or its descriptors under `/proc/<pid>`, and
/// it writes no core dump that would hold its tokens. `Err` with the reason where it cannot.
#[cfg(target_os = "linux")]
pub fn protect_host_process() -> Result<(), String> {
    use rustix::process::{DumpableBehavior, set_dumpable_behavior};
    set_dumpable_behavior(DumpableBehavior::NotDumpable)
        .map_err(|e| format!("PR_SET_DUMPABLE failed: {e}"))
}

#[cfg(not(target_os = "linux"))]
pub fn protect_host_process() -> Result<(), String> {
    Err("only Linux can keep a process of the same user from reading this one".into())
}

/// The descriptors a reader or a front may hold: the limit of open files (`RLIMIT_NOFILE`, soft
/// and hard) that each sets for itself before it reads a byte.
pub const CONFINED_FILES: u64 = 1024;

/// The most descriptors one message passes over a Unix socket (the kernel's `SCM_MAX_FD`).
pub const MAX_PASSED_AT_ONCE: u64 = 253;

/// The limit of open files from which `run` has room to pass descriptors whatever its readers
/// and fronts do ([`raise_descriptor_limit`]): what they can leave on their way, and as much
/// again and more for what `run` itself has on its way to its fronts.
pub const FILES_TO_PASS_DESCRIPTORS: u64 = 4 * CONFINED_FILES;

const _: () = assert!(FILES_TO_PASS_DESCRIPTORS >= 2 * (CONFINED_FILES + MAX_PASSED_AT_ONCE));

/// The limit of open files for the sandboxes' containers of a host that passes descriptors to
/// fronts and may itself have `own` files open. On a Docker host without user namespaces
/// whose user is uid 1000, a sandbox's processes are that same user to the kernel, and what
/// they leave on its way over Unix sockets counts with `run`'s own: with a limit of `n` files
/// they can leave `n` and one message more there ([`MAX_PASSED_AT_ONCE`]), and `run` passes a
/// descriptor while the count is not above its own limit ([`raise_descriptor_limit`]). So a
/// container gets at most `own` less one message, and [`crate::sandbox::SANDBOX_FILES`] where
/// that is more. Never less than [`CONFINED_FILES`], with which few programs still work: a
/// host whose own limit leaves no room for that has been warned at its start.
pub fn sandbox_files(own: u64) -> u64 {
    own.saturating_sub(MAX_PASSED_AT_ONCE)
        .clamp(CONFINED_FILES, crate::sandbox::SANDBOX_FILES)
}

/// Raises this process's soft limit of open files (`RLIMIT_NOFILE`) to its hard limit, and
/// gives the limit it then has; `None` where no such rule exists (not Linux).
///
/// `run` hands a front every connection as a descriptor over a Unix socket. Linux refuses to
/// pass one (`ETOOMANYREFS`) while more descriptors of the sender's user are on their way over
/// Unix sockets, sent and not yet received, than the sender's own soft limit of open files. The
/// count is the user's, of descriptors of any kind, and each process of the user may add to it
/// while it is not above that process's own limit, a message of at most
/// [`MAX_PASSED_AT_ONCE`] at a time. A reader and a front run as `run`'s user, and one that a
/// flaw took over can leave descriptors on their way for as long as it lives. Each has a limit
/// of [`CONFINED_FILES`], so all of them together bring the count to that and one message
/// more, and no further. That was above the 1024 that `run` itself starts with by default, and
/// every descriptor `run` then passed was refused, for every sandbox of the host. With its soft
/// limit at its hard limit (524288 under systemd) `run` stays above what they can reach; a
/// front is refused the passing itself as well ([`Profile::Front`]).
///
/// The rule as seen on Linux 6.18 with processes of one user without `CAP_SYS_RESOURCE` or
/// `CAP_SYS_ADMIN` (a sender with either is never refused): a process with a soft limit of 64
/// left 120 descriptors on their way, and a second one was then refused with a soft limit of up
/// to 119 and passed its descriptor with 120 or more.
///
/// What `run` starts afterwards inherits the raised limit, but for the readers and the fronts,
/// which set their own.
#[cfg(target_os = "linux")]
pub fn raise_descriptor_limit() -> Option<u64> {
    use rustix::process::{Resource, Rlimit, getrlimit, setrlimit};
    let limits = getrlimit(Resource::Nofile);
    // Up to the hard limit, a process may always raise its own soft limit.
    let _ = setrlimit(
        Resource::Nofile,
        Rlimit {
            current: limits.maximum,
            maximum: limits.maximum,
        },
    );
    Some(getrlimit(Resource::Nofile).current.unwrap_or(u64::MAX))
}

#[cfg(not(target_os = "linux"))]
pub fn raise_descriptor_limit() -> Option<u64> {
    None
}

/// Whether this process is dumpable (`PR_GET_DUMPABLE`): `None` where that cannot be told.
#[cfg(target_os = "linux")]
pub fn host_dumpable() -> Option<bool> {
    use rustix::process::{DumpableBehavior, dumpable_behavior};
    dumpable_behavior()
        .ok()
        .map(|d| d != DumpableBehavior::NotDumpable)
}

#[cfg(not(target_os = "linux"))]
pub fn host_dumpable() -> Option<bool> {
    None
}

/// Waits until the child `pid` has ended, and leaves it unreaped (`waitid` with `WNOWAIT`): as
/// long as it is not reaped, its id, which is its process group's, cannot be another process's,
/// so [`kill_group`] reaches its group and nothing else. `false` where this system cannot wait
/// so (not Linux): the caller then reaps first and kills after.
pub fn wait_unreaped(pid: u32) -> bool {
    #[cfg(target_os = "linux")]
    {
        use rustix::process::{Pid, WaitId, WaitIdOptions, waitid};
        let Some(pid) = i32::try_from(pid).ok().and_then(Pid::from_raw) else {
            return false;
        };
        loop {
            match waitid(
                WaitId::Pid(pid),
                WaitIdOptions::EXITED | WaitIdOptions::NOWAIT,
            ) {
                Err(rustix::io::Errno::INTR) => continue,
                Ok(_) => return true,
                Err(_) => return false,
            }
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = pid;
        false
    }
}

/// Kills every process of the process group `pgid` (the reader and what it started), where
/// this system can.
pub fn kill_group(pgid: u32) {
    #[cfg(target_os = "linux")]
    {
        use rustix::process::{Pid, Signal, kill_process_group};
        if let Some(pid) = i32::try_from(pgid).ok().and_then(Pid::from_raw) {
            let _ = kill_process_group(pid, Signal::KILL);
        }
    }
    #[cfg(all(unix, not(target_os = "linux")))]
    {
        // No unsafe code: the `kill` utility, as the bridge signals (ADR-0095).
        let _ = std::process::Command::new("kill")
            .args(["-KILL", "--", &format!("-{pgid}")])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status();
    }
    #[cfg(not(unix))]
    let _ = pgid;
}

/// The filter exists for the architectures of the release binaries (x86-64 and 64-bit ARM),
/// whose system call numbers it was written and checked against.
#[cfg(all(
    target_os = "linux",
    any(target_arch = "x86_64", target_arch = "aarch64")
))]
mod seccomp {
    use std::collections::BTreeMap;

    use super::Profile;

    use seccompiler::{
        BpfProgram, SeccompAction, SeccompCmpArgLen, SeccompCmpOp, SeccompCondition, SeccompFilter,
        SeccompRule, TargetArch,
    };

    #[cfg(target_arch = "x86_64")]
    const ARCH: Option<TargetArch> = Some(TargetArch::x86_64);
    #[cfg(target_arch = "aarch64")]
    const ARCH: Option<TargetArch> = Some(TargetArch::aarch64);

    /// Refused with `EPERM`, whatever their arguments.
    const DENIED: &[i64] = &[
        libc::SYS_socket,
        libc::SYS_ptrace,
        libc::SYS_process_vm_readv,
        libc::SYS_process_vm_writev,
        libc::SYS_pidfd_getfd,
        libc::SYS_kcmp,
        libc::SYS_unshare,
        libc::SYS_setns,
        libc::SYS_mount,
        libc::SYS_umount2,
        libc::SYS_pivot_root,
        libc::SYS_chroot,
        libc::SYS_open_tree,
        libc::SYS_move_mount,
        libc::SYS_fsopen,
        libc::SYS_fsconfig,
        libc::SYS_fsmount,
        libc::SYS_fspick,
        libc::SYS_mount_setattr,
        libc::SYS_io_uring_setup,
        libc::SYS_io_uring_enter,
        libc::SYS_io_uring_register,
        libc::SYS_bpf,
        libc::SYS_perf_event_open,
        libc::SYS_userfaultfd,
        libc::SYS_keyctl,
        libc::SYS_add_key,
        libc::SYS_request_key,
        libc::SYS_init_module,
        libc::SYS_finit_module,
        libc::SYS_delete_module,
        // kexec_file_load needs CAP_SYS_BOOT as well, which a reader never has; the C library
        // of 64-bit ARM names no number for it.
        libc::SYS_kexec_load,
        libc::SYS_reboot,
        libc::SYS_swapon,
        libc::SYS_swapoff,
        libc::SYS_acct,
        libc::SYS_open_by_handle_at,
        libc::SYS_name_to_handle_at,
        libc::SYS_fanotify_init,
        libc::SYS_setsid,
        libc::SYS_setpgid,
        // By path it would empty any file of the reader's user, the host's among them: Landlock
        // checks a truncation only from its version 3 (Linux 6.2), and Landlock 2 counts as
        // confined. git and the standard library truncate only a file they have opened
        // (`ftruncate`), and Landlock checks the opening.
        libc::SYS_truncate,
    ];

    /// `clone` with one of these makes a namespace.
    const NAMESPACES: &[i32] = &[
        libc::CLONE_NEWNS,
        libc::CLONE_NEWCGROUP,
        libc::CLONE_NEWUTS,
        libc::CLONE_NEWIPC,
        libc::CLONE_NEWUSER,
        libc::CLONE_NEWPID,
        libc::CLONE_NEWNET,
    ];

    const PR_SET_DUMPABLE: u64 = 4;

    /// Refused whatever their arguments, because they act on another process of the same user
    /// or a whole user's processes, and no reader needs them: signals through a pidfd, and the
    /// scheduling and I/O priority of other processes (`setpriority` and `ioprio_set` take a
    /// user's processes too).
    const OTHERS_DENIED: &[i64] = &[
        libc::SYS_pidfd_open,
        libc::SYS_pidfd_send_signal,
        libc::SYS_setpriority,
        libc::SYS_ioprio_set,
    ];

    /// Act on the process their first argument names: allowed for 0 (the caller) and the
    /// reader's own id, refused for any other. `prlimit64` would let a process of the user set
    /// `run`'s limits (a CPU limit ends it), which Landlock's scope does not cover either.
    const SELF_ONLY: &[i64] = &[
        libc::SYS_prlimit64,
        libc::SYS_sched_setaffinity,
        libc::SYS_sched_setparam,
        libc::SYS_sched_setscheduler,
        libc::SYS_sched_setattr,
    ];

    /// `fcntl` commands that name a process to get `SIGIO` and `SIGURG`: a reader could have
    /// `run` signalled through its own file.
    const F_SETOWN: u64 = 8;
    const F_SETOWN_EX: u64 = 15;
    /// The same through `ioctl`, on a socket of `socketpair`.
    const FIOSETOWN: u64 = 0x8901;
    const SIOCSPGRP: u64 = 0x8902;

    /// A 32-bit argument (a pid, a signal) as seccomp compares its low half.
    fn dword(value: i32) -> u64 {
        u64::from(value as u32)
    }

    fn arg(
        index: u8,
        op: SeccompCmpOp,
        value: u64,
    ) -> Result<SeccompCondition, seccompiler::Error> {
        Ok(SeccompCondition::new(
            index,
            SeccompCmpArgLen::Dword,
            op,
            value,
        )?)
    }

    /// The rules that keep a reader's signals inside its process group (ADR-0112, open question
    /// 1), for kernels whose Landlock has no signal scope (before Linux 6.12), and for the ways
    /// to another process that Landlock's scope does not cover. `own` is the reader's pid, and
    /// `group` its process group's (the same: the host starts it as a group's leader).
    ///
    /// A signal by pid is allowed to 0 (the caller's own group), to `-group`, and to `own`; a
    /// signal 0 (a check that a process exists) to any. Any other target is refused, `run`
    /// and its other children among them. seccomp compares arguments with constants only, so a
    /// positive pid of a process in the group cannot be told from one outside it: those are
    /// refused too, in git as well. git signals its own children only to clean up after an
    /// error, and the host kills the whole group when the reading ends; a git that re-raises a
    /// signal to itself (`raise`, `tgkill` with its own pid) is refused that as well and goes on,
    /// until the host's kill.
    fn signal_rules(
        rules: &mut BTreeMap<i64, Vec<SeccompRule>>,
        own: i32,
        group: i32,
        profile: Profile,
    ) -> Result<(), seccompiler::Error> {
        use SeccompCmpOp::Ne;
        // A reader may check that any process exists (signal 0): git does, for its locks. A
        // front never needs to, so its rules leave that exception out.
        let rule = |mut conditions: Vec<SeccompCondition>,
                    sig: u8|
         -> Result<SeccompRule, seccompiler::Error> {
            if profile == Profile::Reader {
                conditions.push(arg(sig, Ne, 0)?);
            }
            Ok(SeccompRule::new(conditions)?)
        };
        // kill(pid, sig): only to its own group (0, -group) or itself.
        rules.insert(
            libc::SYS_kill,
            vec![rule(
                vec![
                    arg(0, Ne, 0)?,
                    arg(0, Ne, dword(-group))?,
                    arg(0, Ne, dword(own))?,
                ],
                1,
            )?],
        );
        // tkill(tid, sig): its main thread only.
        rules.insert(
            libc::SYS_tkill,
            vec![rule(vec![arg(0, Ne, dword(own))?], 1)?],
        );
        // tgkill(tgid, tid, sig), rt_sigqueueinfo(tgid, sig, info),
        // rt_tgsigqueueinfo(tgid, tid, sig, info): its own threads only.
        rules.insert(
            libc::SYS_tgkill,
            vec![rule(vec![arg(0, Ne, dword(own))?], 2)?],
        );
        rules.insert(
            libc::SYS_rt_sigqueueinfo,
            vec![rule(vec![arg(0, Ne, dword(own))?], 1)?],
        );
        rules.insert(
            libc::SYS_rt_tgsigqueueinfo,
            vec![rule(vec![arg(0, Ne, dword(own))?], 2)?],
        );
        for &call in OTHERS_DENIED {
            rules.insert(call, Vec::new());
        }
        for &call in SELF_ONLY {
            rules.insert(
                call,
                vec![SeccompRule::new(vec![
                    arg(0, Ne, 0)?,
                    arg(0, Ne, dword(own))?,
                ])?],
            );
        }
        rules.insert(
            libc::SYS_fcntl,
            vec![
                SeccompRule::new(vec![arg(1, SeccompCmpOp::Eq, F_SETOWN)?])?,
                SeccompRule::new(vec![arg(1, SeccompCmpOp::Eq, F_SETOWN_EX)?])?,
            ],
        );
        rules.insert(
            libc::SYS_ioctl,
            vec![
                SeccompRule::new(vec![arg(1, SeccompCmpOp::Eq, FIOSETOWN)?])?,
                SeccompRule::new(vec![arg(1, SeccompCmpOp::Eq, SIOCSPGRP)?])?,
            ],
        );
        Ok(())
    }

    /// Refused to a front whatever their arguments: it runs no program and makes no connection
    /// or listener of its own (`run` hands it the descriptors it needs), and it passes no
    /// descriptor to anyone.
    const FRONT_DENIED: &[i64] = &[
        libc::SYS_execve,
        libc::SYS_execveat,
        libc::SYS_connect,
        libc::SYS_bind,
        libc::SYS_listen,
        // The two calls that can carry a descriptor (`SCM_RIGHTS`); `io_uring`, which could
        // too, is refused to every profile. Whether a message carries one is behind a pointer,
        // which seccomp cannot read, so both go whole: a front writes its frames and its
        // streams with `write` and `sendto`.
        libc::SYS_sendmsg,
        libc::SYS_sendmmsg,
        #[cfg(target_arch = "x86_64")]
        libc::SYS_fork,
        #[cfg(target_arch = "x86_64")]
        libc::SYS_vfork,
    ];

    /// The bit that the numbers of the x32 ABI's system calls have set (`__X32_SYSCALL_BIT`).
    #[cfg(target_arch = "x86_64")]
    const X32_SYSCALL_BIT: u32 = 0x4000_0000;

    /// x86-64 as a filter reads it in `seccomp_data.arch` (`AUDIT_ARCH_X86_64`).
    #[cfg(target_arch = "x86_64")]
    const AUDIT_ARCH_X86_64: u32 = 0xc000_003e;

    /// Refuses every system call of the x32 ABI, with `EPERM`. An x86-64 kernel built with that
    /// ABI takes each call under a second number, the 64-bit one with [`X32_SYSCALL_BIT`] set,
    /// and names the same architecture for both. A [`SeccompFilter`] compares the whole number
    /// with each one it lists, so no rule of the other filters matches such a call: `socket`,
    /// `setsid`, `kill` and the rest would get through. It has no rule for a range of numbers
    /// either, so this program is written out instead. The reader, the front and git are 64-bit
    /// programs and make no such call: every number from the bit on is refused, whatever the
    /// call. Another architecture ends the process, as in seccompiler's own programs.
    #[cfg(target_arch = "x86_64")]
    fn x32_refused() -> BpfProgram {
        // Where `seccomp_data` holds the call's number and its architecture, 32 bits each.
        const NUMBER: u32 = 0;
        const ARCHITECTURE: u32 = 4;
        let instruction = |code: u32, jt: u8, jf: u8, k: u32| seccompiler::sock_filter {
            code: code as u16,
            jt,
            jf,
            k,
        };
        let load = libc::BPF_LD | libc::BPF_W | libc::BPF_ABS;
        let jump = libc::BPF_JMP | libc::BPF_K;
        let answer = libc::BPF_RET | libc::BPF_K;
        vec![
            instruction(load, 0, 0, ARCHITECTURE),
            instruction(jump | libc::BPF_JEQ, 1, 0, AUDIT_ARCH_X86_64),
            instruction(answer, 0, 0, SeccompAction::KillProcess.into()),
            instruction(load, 0, 0, NUMBER),
            // Unsigned, as the kernel compares: below the bit is a 64-bit call, which the other
            // filters judge.
            instruction(jump | libc::BPF_JGE, 0, 1, X32_SYSCALL_BIT),
            instruction(
                answer,
                0,
                0,
                SeccompAction::Errno(libc::EPERM as u32).into(),
            ),
            instruction(answer, 0, 0, SeccompAction::Allow.into()),
        ]
    }

    /// The programs of `profile`, in the order they are installed. The kernel runs every one
    /// of them on each system call and takes the answer that restricts most, so each adds its
    /// refusals to the others'.
    fn filters(
        arch: TargetArch,
        own: i32,
        group: i32,
        profile: Profile,
    ) -> Result<Vec<BpfProgram>, seccompiler::Error> {
        let mut rules: BTreeMap<i64, Vec<SeccompRule>> =
            DENIED.iter().map(|&call| (call, Vec::new())).collect();
        signal_rules(&mut rules, own, group, profile)?;
        if profile == Profile::Front {
            for &call in FRONT_DENIED {
                rules.insert(call, Vec::new());
            }
        }
        let mut clone = Vec::new();
        for &flag in NAMESPACES {
            let flag = u64::from(flag as u32);
            clone.push(SeccompRule::new(vec![SeccompCondition::new(
                0,
                SeccompCmpArgLen::Qword,
                SeccompCmpOp::MaskedEq(flag),
                flag,
            )?])?);
        }
        if profile == Profile::Front {
            // A new process, not a thread: no CLONE_THREAD in the flags.
            let thread = u64::from(libc::CLONE_THREAD as u32);
            clone.push(SeccompRule::new(vec![SeccompCondition::new(
                0,
                SeccompCmpArgLen::Qword,
                SeccompCmpOp::MaskedEq(thread),
                0,
            )?])?);
        }
        rules.insert(libc::SYS_clone, clone);
        rules.insert(
            libc::SYS_prctl,
            vec![SeccompRule::new(vec![SeccompCondition::new(
                0,
                SeccompCmpArgLen::Dword,
                SeccompCmpOp::Eq,
                PR_SET_DUMPABLE,
            )?])?],
        );
        let denied: BpfProgram = SeccompFilter::new(
            rules,
            SeccompAction::Allow,
            SeccompAction::Errno(libc::EPERM as u32),
            arch,
        )?
        .try_into()?;
        // clone3's flags are behind a pointer, which seccomp cannot read: "not implemented",
        // and the C library falls back to clone.
        let clone3: BpfProgram = SeccompFilter::new(
            BTreeMap::from([(libc::SYS_clone3, Vec::new())]),
            SeccompAction::Allow,
            SeccompAction::Errno(libc::ENOSYS as u32),
            arch,
        )?
        .try_into()?;
        Ok(vec![
            denied,
            clone3,
            #[cfg(target_arch = "x86_64")]
            x32_refused(),
        ])
    }

    pub fn apply(profile: Profile) -> Result<(), String> {
        let arch = ARCH.ok_or("this architecture has no seccomp filter of Tinfirm's")?;
        let own = rustix::process::getpid().as_raw_nonzero().get();
        let group = rustix::process::getpgrp().as_raw_nonzero().get();
        for program in filters(arch, own, group, profile).map_err(|e| e.to_string())? {
            seccompiler::apply_filter(&program).map_err(|e| e.to_string())?;
        }
        Ok(())
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        /// This architecture as a filter reads it in `seccomp_data.arch` (`AUDIT_ARCH_*`).
        #[cfg(target_arch = "x86_64")]
        const AUDIT_ARCH: u32 = AUDIT_ARCH_X86_64;
        #[cfg(target_arch = "aarch64")]
        const AUDIT_ARCH: u32 = 0xc000_00b7;

        const ALLOW: u32 = libc::SECCOMP_RET_ALLOW;
        const EPERM: u32 = libc::SECCOMP_RET_ERRNO | libc::EPERM as u32;
        const ENOSYS: u32 = libc::SECCOMP_RET_ERRNO | libc::ENOSYS as u32;

        /// Runs `program` on a system call of `arch` with the number `nr`, as the kernel runs
        /// a filter, and returns its action. It knows the instructions that these programs
        /// reach for a call whose arguments no rule looks at; any other fails the test.
        fn run(program: &[seccompiler::sock_filter], arch: u32, nr: u32) -> u32 {
            const LOAD: u32 = libc::BPF_LD | libc::BPF_W | libc::BPF_ABS;
            const SKIP: u32 = libc::BPF_JMP | libc::BPF_JA;
            const IF_EQUAL: u32 = libc::BPF_JMP | libc::BPF_JEQ | libc::BPF_K;
            const IF_AT_LEAST: u32 = libc::BPF_JMP | libc::BPF_JGE | libc::BPF_K;
            const ANSWER: u32 = libc::BPF_RET | libc::BPF_K;
            let (mut at, mut loaded) = (0usize, 0u32);
            loop {
                let step = &program[at];
                at += 1;
                match u32::from(step.code) {
                    LOAD => {
                        loaded = match step.k {
                            0 => nr,
                            4 => arch,
                            offset => panic!("a load of offset {offset}, an argument"),
                        }
                    }
                    SKIP => at += step.k as usize,
                    IF_EQUAL => at += usize::from(if loaded == step.k { step.jt } else { step.jf }),
                    IF_AT_LEAST => {
                        at += usize::from(if loaded >= step.k { step.jt } else { step.jf })
                    }
                    ANSWER => return step.k,
                    code => panic!("an instruction this test does not know: {code:#x}"),
                }
            }
        }

        /// What the kernel does with the call `nr` once all of `programs` are installed: every
        /// one runs, and the action that restricts most is taken, which is the lowest as a
        /// signed number (seccomp(2)).
        fn decision(programs: &[BpfProgram], nr: i64) -> u32 {
            programs
                .iter()
                .map(|program| run(program, AUDIT_ARCH, nr as u32))
                .min_by_key(|action| (action & libc::SECCOMP_RET_ACTION_FULL) as i32)
                .unwrap()
        }

        #[test]
        fn the_filters_compile_for_this_architecture() {
            assert!(ARCH.is_some());
            if let Some(arch) = ARCH {
                for profile in [Profile::Reader, Profile::Front] {
                    let programs = filters(arch, 4242, 4242, profile).unwrap();
                    // The list, clone3's and, on x86-64, the x32 ABI's.
                    let expected = if cfg!(target_arch = "x86_64") { 3 } else { 2 };
                    assert_eq!(programs.len(), expected);
                    assert!(programs.iter().all(|program| !program.is_empty()));
                    // And they answer as they are meant to: a call of the list, clone3, and one
                    // that nothing refuses.
                    assert_eq!(decision(&programs, libc::SYS_socket), EPERM);
                    assert_eq!(decision(&programs, libc::SYS_clone3), ENOSYS);
                    assert_eq!(decision(&programs, libc::SYS_read), ALLOW);
                }
            }
        }

        #[test]
        fn a_front_is_refused_the_calls_that_pass_a_descriptor() {
            let front = filters(ARCH.unwrap(), 4242, 4242, Profile::Front).unwrap();
            assert_eq!(decision(&front, libc::SYS_sendmsg), EPERM);
            assert_eq!(decision(&front, libc::SYS_sendmmsg), EPERM);
            // What it writes with, and what it takes `run`'s descriptors with.
            for call in [
                libc::SYS_write,
                libc::SYS_writev,
                libc::SYS_sendto,
                libc::SYS_recvmsg,
            ] {
                assert_eq!(decision(&front, call), ALLOW, "{call}");
            }
            // Its runtime's signal driver makes a pair of Unix sockets after the filter is on.
            assert_eq!(decision(&front, libc::SYS_socketpair), ALLOW);
            // A reader is not refused them: its limit of open files bounds what it can leave on
            // its way.
            let reader = filters(ARCH.unwrap(), 4242, 4242, Profile::Reader).unwrap();
            assert_eq!(decision(&reader, libc::SYS_sendmsg), ALLOW);
        }

        #[test]
        fn a_file_is_truncated_through_a_descriptor_and_never_by_its_path() {
            for profile in [Profile::Reader, Profile::Front] {
                let programs = filters(ARCH.unwrap(), 4242, 4242, profile).unwrap();
                assert_eq!(decision(&programs, libc::SYS_truncate), EPERM);
                assert_eq!(decision(&programs, libc::SYS_ftruncate), ALLOW);
            }
        }

        #[cfg(target_arch = "x86_64")]
        #[test]
        fn a_call_of_the_x32_abi_is_refused_whatever_its_number() {
            // The 32-bit calls of an x86-64 kernel (`int 0x80`): `AUDIT_ARCH_I386`.
            const I386: u32 = 0x4000_0003;
            const X32: i64 = X32_SYSCALL_BIT as i64;
            let x32 = x32_refused();
            assert_eq!(run(&x32, AUDIT_ARCH, 0), ALLOW);
            assert_eq!(run(&x32, AUDIT_ARCH, X32_SYSCALL_BIT - 1), ALLOW);
            assert_eq!(run(&x32, AUDIT_ARCH, X32_SYSCALL_BIT), EPERM);
            assert_eq!(run(&x32, AUDIT_ARCH, u32::MAX), EPERM);
            assert_eq!(run(&x32, I386, 0), libc::SECCOMP_RET_KILL_PROCESS);
            for profile in [Profile::Reader, Profile::Front] {
                let programs = filters(ARCH.unwrap(), 4242, 4242, profile).unwrap();
                // What the list refuses under its 64-bit number is refused under its x32
                // number too, and so is what the list allows.
                for call in [
                    libc::SYS_socket,
                    libc::SYS_setsid,
                    libc::SYS_kill,
                    libc::SYS_clone3,
                    libc::SYS_read,
                ] {
                    assert_eq!(decision(&programs, X32 | call), EPERM, "{call}");
                }
                // A number that only the x32 ABI has (they begin at 512), and the highest of all.
                assert_eq!(decision(&programs, X32 | 512), EPERM);
                assert_eq!(decision(&programs, i64::from(u32::MAX)), EPERM);
                // The last number below the bit is a 64-bit call, and no filter refuses it.
                assert_eq!(decision(&programs, X32 - 1), ALLOW);
            }
        }
    }
}

/// Another Linux architecture: Landlock, but no filter of Tinfirm's, so no reader is confined.
#[cfg(all(
    target_os = "linux",
    not(any(target_arch = "x86_64", target_arch = "aarch64"))
))]
mod seccomp {
    pub fn apply(_profile: super::Profile) -> Result<(), String> {
        Err("this architecture has no seccomp filter of Tinfirm's".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_status_is_confined_only_with_everything_that_counts() {
        let full = Status {
            landlock_abi: 7,
            landlock: true,
            landlock_net: true,
            landlock_scope: true,
            seccomp: true,
            no_new_privs: true,
            not_dumpable: true,
            problem: None,
        };
        assert!(full.confined());
        assert!(full.describe().contains("Landlock ABI 7 (files, TCP"));
        for broken in [
            Status {
                landlock: false,
                ..full.clone()
            },
            Status {
                seccomp: false,
                ..full.clone()
            },
            Status {
                not_dumpable: false,
                ..full.clone()
            },
        ] {
            assert!(!broken.confined(), "{broken:?}");
        }
        let none = Status::none("macOS");
        assert!(!none.confined());
        assert_eq!(none.describe(), "not confined: macOS");
    }

    #[test]
    fn a_sandboxes_files_stay_one_message_under_the_hosts_own() {
        // The limit `run` has under systemd, and one a shell gives: 65536 for the sandbox.
        assert_eq!(sandbox_files(524_288), 65536);
        assert_eq!(sandbox_files(1_048_576), 65536);
        assert_eq!(sandbox_files(65536 + MAX_PASSED_AT_ONCE), 65536);
        // A host with fewer: what its sandboxes can leave on their way, their files and one
        // message more, is not above what the host may have itself.
        for own in [65536, 8192, 4096, CONFINED_FILES + MAX_PASSED_AT_ONCE] {
            let files = sandbox_files(own);
            assert_eq!(files, own - MAX_PASSED_AT_ONCE);
            assert!(files + MAX_PASSED_AT_ONCE <= own);
        }
        // Never fewer than a confined process has: that host was warned at its start.
        assert_eq!(sandbox_files(1024), CONFINED_FILES);
        assert_eq!(sandbox_files(0), CONFINED_FILES);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn the_hosts_soft_limit_of_open_files_goes_to_its_hard_limit() {
        use rustix::process::{Resource, getrlimit};
        let hard = getrlimit(Resource::Nofile).maximum;
        let now = raise_descriptor_limit().unwrap();
        assert_eq!(getrlimit(Resource::Nofile).current, hard);
        assert_eq!(Some(now), hard);
    }

    /// A host of the user `u` with its directories where they are by default.
    #[cfg(target_os = "linux")]
    fn guarded() -> Guarded {
        Guarded {
            dirs: vec![
                "/home/u/.config/tinfirm-runner".into(),
                "/home/u/.local/share/tinfirm-runner".into(),
            ],
            home: Some("/home/u".into()),
        }
    }

    #[cfg(target_os = "linux")]
    fn dirs_of(git: &str, exec: Option<&str>) -> Result<Vec<PathBuf>, String> {
        git_dirs_of(Path::new(git), exec.map(Path::new), &guarded())
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn a_git_outside_the_systems_directories_gives_its_two_directories_and_no_more() {
        let dirs = |list: &[&str]| Ok(list.iter().map(PathBuf::from).collect::<Vec<_>>());
        // The system's git needs nothing on top of the system's directories.
        assert_eq!(
            dirs_of("/usr/bin/git", Some("/usr/lib/git-core")),
            dirs(&[])
        );
        assert_eq!(dirs_of("/usr/local/bin/git", None), dirs(&[]));
        assert_eq!(
            dirs_of("/opt/git/bin/git", Some("/opt/git/libexec/git-core")),
            dirs(&["/opt/git/bin", "/opt/git/libexec/git-core"])
        );
        // A git built in the home (the prefix of its own `make install`): its two directories,
        // and not the home above them, which holds the host's token.
        assert_eq!(
            dirs_of("/home/u/bin/git", Some("/home/u/libexec/git-core")),
            dirs(&["/home/u/bin", "/home/u/libexec/git-core"])
        );
        assert_eq!(
            dirs_of(
                "/home/u/.local/bin/git",
                Some("/home/u/.local/libexec/git-core")
            ),
            dirs(&["/home/u/.local/bin", "/home/u/.local/libexec/git-core"])
        );
        // One directory for both, a wrapper around the system's git, and no answer from git.
        assert_eq!(
            dirs_of("/opt/git/git", Some("/opt/git")),
            dirs(&["/opt/git"])
        );
        assert_eq!(
            dirs_of("/opt/tools/git", Some("/usr/lib/git-core")),
            dirs(&["/opt/tools"])
        );
        assert_eq!(dirs_of("/x/git", None), dirs(&["/x"]));
        // Nothing of the host's to stay clear of: only the root is refused.
        assert_eq!(
            git_dirs_of(Path::new("/home/u/git"), None, &Guarded::default()),
            dirs(&["/home/u"])
        );
        assert!(git_dirs_of(Path::new("/git"), None, &Guarded::default()).is_err());
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn a_git_whose_directories_reach_the_hosts_own_is_refused() {
        for (git, exec, reaches) in [
            // The root, as the directory of git or of its programs.
            ("/git", None, "is the whole file system"),
            ("/opt/git/bin/git", Some("/"), "is the whole file system"),
            // The home itself, and a directory that holds it.
            ("/home/u/git", None, "is or holds the home"),
            ("/home/git", None, "is or holds the home"),
            ("/opt/git/bin/git", Some("/home/u"), "is or holds the home"),
            // A directory that holds the host's data or config directory.
            (
                "/home/u/.local/git",
                None,
                "/home/u/.local/share/tinfirm-runner",
            ),
            (
                "/home/u/.config/git",
                None,
                "/home/u/.config/tinfirm-runner",
            ),
            // The host's directory itself, and one inside it, where readers write.
            (
                "/home/u/.local/share/tinfirm-runner/git",
                None,
                "holds or lies in",
            ),
            (
                "/home/u/.local/share/tinfirm-runner/workspaces/r/repo/bin/git",
                None,
                "holds or lies in",
            ),
            (
                "/opt/git/bin/git",
                Some("/home/u/.config/tinfirm-runner/credentials"),
                "holds or lies in",
            ),
        ] {
            let refusal = dirs_of(git, exec).expect_err(git);
            assert!(refusal.contains(reaches), "{git}: {refusal}");
            assert!(refusal.contains(git), "{refusal}");
            // What the host keeps of it for its log and its refusal to start.
            assert!(refusal.len() < 512, "{refusal}");
        }
        // A sibling whose name only begins like the host's directory is none of it.
        assert!(dirs_of("/home/u/.config/tinfirm-runner-tools/git", None).is_ok());
    }
}
