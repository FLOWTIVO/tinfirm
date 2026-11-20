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
//! - restricts itself with Landlock: reading and executing the system's directories (for git),
//!   reading the directories its job reads, writing only the directories its job writes (and
//!   never executing there), `/dev/null`, reading `/dev/urandom`; every other path is refused,
//!   `/proc` included. Where
//!   the kernel has them, TCP `bind` and `connect` are refused (Landlock 4, Linux 6.7), and so
//!   are signals to and abstract sockets of processes outside its domain (Landlock 6, Linux
//!   6.12). Landlock also refuses it `ptrace` of any process outside its domain. This sets
//!   `no_new_privs`;
//! - installs a seccomp filter that refuses, with `EPERM`: creating a socket of any family, so
//!   no network and no Unix socket, whatever Landlock's version; `ptrace`,
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
//!   kill of the group does it.
//!
//! Landlock 2 (Linux 5.19) is the least that counts as confined: before it, Landlock refuses
//! every rename between two directories, which git needs to store an object. The child
//! inherits all of it across `execve` (git and what git starts), except the dumpable flag,
//! which `execve` sets again: Landlock's scope is what keeps those processes from the host.
//!
//! The runner host makes itself not dumpable at its start ([`protect_host_process`]), so that
//! no other process of its user (a git it starts, a process that a flaw of one gave someone)
//! reads its memory through `/proc/<pid>/mem` or `ptrace`. Root still can.
//!
//! Elsewhere (macOS, Windows, a Linux kernel without Landlock 2) nothing of this exists, and
//! [`confine`] says so: the host decides what to do with such a reader ([`crate::reader`]).

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

/// What a reader or a front may touch.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Plan {
    /// Read beneath, and nothing else.
    pub read: Vec<PathBuf>,
    /// Read and write beneath (never execute).
    pub write: Vec<PathBuf>,
    /// The job runs git, so the system's directories may be read and executed.
    pub git: bool,
    /// Which seccomp filter applies.
    pub profile: Profile,
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
    /// of Unix sockets reaches no other process. Its plan reads and writes nothing.
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

/// The directory that holds `git` (and its own `libexec`), when it is outside
/// [`SYSTEM_DIRS`]: the grandparent of the resolved binary (`/opt/git` for `/opt/git/bin/git`).
#[cfg(target_os = "linux")]
fn git_home() -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    let git = std::env::split_paths(&path)
        .map(|dir| dir.join("git"))
        .find(|candidate| candidate.is_file())?;
    let git = std::fs::canonicalize(git).ok()?;
    if SYSTEM_DIRS.iter().any(|dir| git.starts_with(dir)) {
        return None;
    }
    git.parent()?.parent().map(PathBuf::from)
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
    if plan.git {
        read_exec.extend(SYSTEM_DIRS.iter().map(PathBuf::from));
        read_exec.extend(git_home());
        read_files.extend(SYSTEM_FILES.iter().map(PathBuf::from));
    }
    let mut problem = None;
    let landlock = (|| {
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
    })();
    let (landlock_abi, landlock, landlock_net, landlock_scope, no_new_privs) = match landlock {
        Ok(status) => {
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
        Err(e) => {
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
    /// or listener of its own (`run` hands it the descriptors it needs).
    const FRONT_DENIED: &[i64] = &[
        libc::SYS_execve,
        libc::SYS_execveat,
        libc::SYS_connect,
        libc::SYS_bind,
        libc::SYS_listen,
        #[cfg(target_arch = "x86_64")]
        libc::SYS_fork,
        #[cfg(target_arch = "x86_64")]
        libc::SYS_vfork,
    ];

    fn filters(
        arch: TargetArch,
        own: i32,
        group: i32,
        profile: Profile,
    ) -> Result<(BpfProgram, BpfProgram), seccompiler::Error> {
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
        Ok((denied, clone3))
    }

    pub fn apply(profile: Profile) -> Result<(), String> {
        let arch = ARCH.ok_or("this architecture has no seccomp filter of Tinfirm's")?;
        let own = rustix::process::getpid().as_raw_nonzero().get();
        let group = rustix::process::getpgrp().as_raw_nonzero().get();
        let (denied, clone3) = filters(arch, own, group, profile).map_err(|e| e.to_string())?;
        seccompiler::apply_filter(&denied).map_err(|e| e.to_string())?;
        seccompiler::apply_filter(&clone3).map_err(|e| e.to_string())
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn the_filters_compile_for_this_architecture() {
            assert!(ARCH.is_some());
            if let Some(arch) = ARCH {
                for profile in [Profile::Reader, Profile::Front] {
                    let (denied, clone3) = filters(arch, 4242, 4242, profile).unwrap();
                    assert!(!denied.is_empty() && !clone3.is_empty());
                }
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
}
