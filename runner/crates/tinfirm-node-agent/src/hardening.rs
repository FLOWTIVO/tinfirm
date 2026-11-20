//! The hardening mark of ADR-0113, decision 5: what the helper reads of the machine, and
//! whether each line of the mark holds. The helper only reads: it writes no setting, as it
//! reads `net.ipv4.ip_forward` and never writes it. The role sets the lines up.
//!
//! Every fact is read under a root path: `/` on a node, a temporary directory in the tests. A
//! fact that cannot be read fails its line and says so; nothing here panics on a file's
//! content. Each line's `found` is one short sentence of what was read, at most
//! [`MAX_FOUND`] characters, and every byte the machine gave is escaped before it goes in.
//!
//! The mark is `yes` when every line holds, and `no` otherwise (`Hardening::of`). A line that
//! stops holding lowers the mark and stops nothing: it is no problem of `check`.

use std::io::Read;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use crate::plan::HardeningConfig;

/// The lines of the mark, in the order of the answer.
pub mod line {
    pub const USER_NAMESPACES: &str = "user-namespaces";
    pub const UNPRIVILEGED_BPF: &str = "unprivileged-bpf";
    pub const IO_URING: &str = "io-uring";
    pub const VMM_MAC: &str = "vmm-mac";
    pub const PANIC_ON_OOPS: &str = "panic-on-oops";
    pub const MODULES_DISABLED: &str = "modules-disabled";
    pub const KEXEC_DISABLED: &str = "kexec-disabled";
    pub const LOCKDOWN: &str = "lockdown";
    pub const DISTRIBUTION_HARDENING: &str = "distribution-hardening";
    pub const NO_DOCKER: &str = "no-docker";
    pub const NO_SETUID: &str = "no-setuid";
    pub const LISTENING_SOCKETS: &str = "listening-sockets";
    pub const MAX_UPTIME: &str = "max-uptime";
}

/// Every line, in the order of the answer.
pub const LINES: [&str; 13] = [
    line::USER_NAMESPACES,
    line::UNPRIVILEGED_BPF,
    line::IO_URING,
    line::VMM_MAC,
    line::PANIC_ON_OOPS,
    line::MODULES_DISABLED,
    line::KEXEC_DISABLED,
    line::LOCKDOWN,
    line::DISTRIBUTION_HARDENING,
    line::NO_DOCKER,
    line::NO_SETUID,
    line::LISTENING_SOCKETS,
    line::MAX_UPTIME,
];

/// The AppArmor profile every VMM runs under.
pub const PROFILE: &str = "tinfirm-vmm";
/// The longest `found`, in characters.
pub const MAX_FOUND: usize = 200;
/// How long a scan for setuid and setgid files stands before the next `check` walks again.
pub const SCAN_EVERY: Duration = Duration::from_secs(10 * 60);
/// The most entries one scan walks; a tree with more fails the line.
pub const MAX_SCAN_ENTRIES: u64 = 2_000_000;
/// The directories the scan walks, under the root. `bin` and `sbin` only where they are
/// directories of their own: on a merged-`/usr` distribution they are links into `/usr`.
pub const SCANNED: [&str; 4] = ["usr", "opt", "bin", "sbin"];
/// Where Docker, containerd and podman put their sockets.
pub const SOCKETS: [&str; 4] = [
    "run/docker.sock",
    "var/run/docker.sock",
    "run/containerd/containerd.sock",
    "run/podman/podman.sock",
];
/// The longest file of one setting the helper reads.
const MAX_SETTING: u64 = 4096;
/// The longest listing it reads (`/proc/net/tcp`, AppArmor's list of profiles).
const MAX_LISTING: u64 = 8 * 1024 * 1024;
/// The longest piece of a file's content or of a path that goes into a `found`.
const MAX_SHOWN: usize = 64;

/// The mark: `yes` or `no`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Hardened {
    Yes,
    No,
}

/// One line of the mark: its id, whether it holds, and what was found.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Line {
    pub line: String,
    pub holds: bool,
    pub found: String,
}

impl Line {
    /// A line whose `found` is cut to [`MAX_FOUND`] characters.
    pub fn new(line: &str, holds: bool, found: impl Into<String>) -> Line {
        let found: String = found.into();
        let found = if found.chars().count() > MAX_FOUND {
            let mut cut: String = found.chars().take(MAX_FOUND - 1).collect();
            cut.push('…');
            cut
        } else {
            found
        };
        Line {
            line: line.to_string(),
            holds,
            found,
        }
    }
}

/// What `check` answers of the hardening, on the helper's wire and the agent's.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Hardening {
    pub hardened: Hardened,
    pub lines: Vec<Line>,
}

impl Hardening {
    /// `yes` when there are lines and every one holds.
    pub fn of(lines: Vec<Line>) -> Hardening {
        let yes = !lines.is_empty() && lines.iter().all(|l| l.holds);
        Hardening {
            hardened: if yes { Hardened::Yes } else { Hardened::No },
            lines,
        }
    }
}

/// A piece of what the machine gave, as a `found` may hold it: escaped, and cut.
fn shown(text: &str) -> String {
    let escaped: String = text.escape_debug().collect();
    if escaped.chars().count() > MAX_SHOWN {
        let mut cut: String = escaped.chars().take(MAX_SHOWN - 1).collect();
        cut.push('…');
        cut
    } else {
        escaped
    }
}

/// Why a file could not be read.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Unread {
    /// It does not exist.
    Missing,
    /// Another reason, as words.
    Other(String),
}

impl std::fmt::Display for Unread {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Unread::Missing => f.write_str("does not exist"),
            Unread::Other(why) => f.write_str(why),
        }
    }
}

/// A file's text, of at most `limit` bytes.
fn read_text(path: &Path, limit: u64) -> Result<String, Unread> {
    let file = std::fs::File::open(path).map_err(|e| match e.kind() {
        std::io::ErrorKind::NotFound => Unread::Missing,
        kind => Unread::Other(format!("cannot be read ({kind})")),
    })?;
    let mut bytes = Vec::new();
    file.take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| Unread::Other(format!("cannot be read ({})", e.kind())))?;
    if bytes.len() as u64 > limit {
        return Err(Unread::Other(format!("is longer than {limit} bytes")));
    }
    String::from_utf8(bytes).map_err(|_| Unread::Other("is not text".into()))
}

/// A setting under `/proc/sys`, named as `sysctl` names it (`kernel.modules_disabled`).
fn setting_path(root: &Path, name: &str) -> PathBuf {
    root.join("proc/sys").join(name.replace('.', "/"))
}

/// A setting's value as a number, or the sentence of why it is not one.
fn setting(root: &Path, name: &str) -> Result<i64, (Unread, String)> {
    let text = read_text(&setting_path(root, name), MAX_SETTING)
        .map_err(|e| (e.clone(), format!("{name} {e}")))?;
    let value = text.trim();
    value.parse().map_err(|_| {
        let why = format!("{name} is \"{}\", not a number", shown(value));
        (Unread::Other(why.clone()), why)
    })
}

/// A line of one setting: it holds when `wanted` takes its value.
fn setting_line(
    root: &Path,
    id: &str,
    name: &str,
    wanted: impl Fn(i64) -> bool,
    words: &str,
) -> Line {
    match setting(root, name) {
        Ok(v) if wanted(v) => Line::new(id, true, format!("{name} is {v}")),
        Ok(v) => Line::new(id, false, format!("{name} is {v}, not {words}")),
        Err((_, found)) => Line::new(id, false, found),
    }
}

/// The kernel's release, as `uname -r` gives it, or the sentence of why it cannot be read.
fn release(root: &Path) -> Result<String, String> {
    read_text(&root.join("proc/sys/kernel/osrelease"), MAX_SETTING)
        .map(|r| shown(r.trim()))
        .map_err(|e| format!("kernel.osrelease {e}"))
}

/// What `checked` says of the kernel: its release and when the machine booted. Whether the
/// kernel is patched stays the operator's duty (ADR-0113, decision 5); these show what runs.
pub fn kernel_line(root: &Path) -> String {
    let release = release(root).unwrap_or_else(|why| format!("of unknown release ({why})"));
    let booted = read_text(&root.join("proc/stat"), MAX_LISTING)
        .ok()
        .and_then(|stat| {
            stat.lines()
                .find_map(|l| l.strip_prefix("btime "))
                .and_then(|s| s.trim().parse::<i64>().ok())
        })
        .map_or_else(
            || "at a time /proc/stat does not give".to_string(),
            utc_text,
        );
    format!("the kernel {release}, booted {booted}")
}

/// Seconds since the epoch as `YYYY-MM-DD hh:mm:ss UTC`.
pub fn utc_text(seconds: i64) -> String {
    let days = seconds.div_euclid(86_400);
    let rest = seconds.rem_euclid(86_400);
    // Howard Hinnant's days-to-civil, for the proleptic Gregorian calendar.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02} {:02}:{:02}:{:02} UTC",
        rest / 3600,
        rest % 3600 / 60,
        rest % 60
    )
}

/// The TCP sockets in the LISTEN state of one of the kernel's `/proc/net/tcp` or `tcp6`
/// listings, with their local address and port; or the number of the first line that is not of
/// the kernel's form. The kernel prints each 32-bit word of an address as a number in the
/// machine's own byte order, so the word's bytes in memory are the address's.
pub fn listening(text: &str, v6: bool) -> Result<Vec<(IpAddr, u16)>, usize> {
    let mut lines = text.lines();
    if !lines.next().is_some_and(|h| h.contains("local_address")) {
        return Err(1);
    }
    let word = |hex: &str| -> Option<[u8; 4]> {
        (hex.len() == 8 && hex.bytes().all(|b| b.is_ascii_hexdigit()))
            .then(|| u32::from_str_radix(hex, 16).ok())
            .flatten()
            .map(u32::to_ne_bytes)
    };
    let mut found = Vec::new();
    for (i, text) in lines.enumerate() {
        let number = i + 2;
        if text.trim().is_empty() {
            continue;
        }
        let words: Vec<&str> = text.split_whitespace().collect();
        let (Some(local), Some(state)) = (words.get(1), words.get(3)) else {
            return Err(number);
        };
        let (address, port) = local.split_once(':').ok_or(number)?;
        if port.len() != 4 || !port.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(number);
        }
        let port = u16::from_str_radix(port, 16).map_err(|_| number)?;
        if state.len() != 2 || !state.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(number);
        }
        let state = u8::from_str_radix(state, 16).map_err(|_| number)?;
        let ip = if v6 {
            if address.len() != 32 || !address.is_char_boundary(8) {
                return Err(number);
            }
            let mut bytes = [0u8; 16];
            for (k, chunk) in bytes.chunks_mut(4).enumerate() {
                let piece = address.get(k * 8..k * 8 + 8).ok_or(number)?;
                chunk.copy_from_slice(&word(piece).ok_or(number)?);
            }
            IpAddr::V6(Ipv6Addr::from(bytes))
        } else {
            IpAddr::V4(Ipv4Addr::from(word(address).ok_or(number)?))
        };
        // TCP_LISTEN.
        if state == 0x0A {
            found.push((ip, port));
        }
    }
    Ok(found)
}

/// An address of the machine's loopback: `127.0.0.0/8`, `::1`, or `::ffff:127.x.y.z`.
fn is_loopback(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback(),
        IpAddr::V6(v6) => {
            v6.is_loopback() || v6.to_ipv4_mapped().is_some_and(|v4| v4.is_loopback())
        }
    }
}

/// Who of those that must not may run a file of this mode, owner and group: `Some(None)` for
/// others, `Some(Some(id))` for the VMM's user `id`, `None` for nobody. A VMM's user and group
/// are one number of `vmm_ids` (first and last; `plan::HelperConfig::vm_uid_base` and its
/// slots), and it is in no other group. The kernel takes the first class that fits a process:
/// the owner's bits, else the group's, else the others'.
fn who_may_run(mode: u32, uid: u32, gid: u32, vmm_ids: Option<(u32, u32)>) -> Option<Option<u32>> {
    let of_a_vmm = |id: u32| vmm_ids.is_some_and(|(first, last)| (first..=last).contains(&id));
    if mode & 0o001 != 0 {
        Some(None)
    } else if of_a_vmm(uid) && mode & 0o100 != 0 {
        Some(Some(uid))
    } else if of_a_vmm(gid) && uid != gid && mode & 0o010 != 0 {
        Some(Some(gid))
    } else {
        None
    }
}

/// What one scan for setuid and setgid files found: the line, and how many entries it walked.
/// `vmm_ids` are the VMMs' users and groups, first and last, where they are known.
fn scan_setuid(root: &Path, vmm_ids: Option<(u32, u32)>) -> Line {
    let id = line::NO_SETUID;
    let mut stack: Vec<(PathBuf, u64)> = Vec::new();
    for dir in SCANNED {
        let path = root.join(dir);
        match path.symlink_metadata() {
            Ok(meta) if meta.file_type().is_dir() => stack.push((path, meta.dev())),
            // A link (merged `/usr`), or not there.
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => {
                return Line::new(
                    id,
                    false,
                    format!("/{dir} cannot be looked at ({})", e.kind()),
                );
            }
        }
    }
    let shown_path = |path: &Path| {
        let relative = path.strip_prefix(root).unwrap_or(path);
        shown(&format!("/{}", relative.to_string_lossy()))
    };
    let mut walked: u64 = 0;
    // The first such file: its path, mode, owner and group, and who may run it.
    let mut first: Option<(PathBuf, u32, u32, u32, Option<u32>)> = None;
    let mut more: u64 = 0;
    while let Some((dir, dev)) = stack.pop() {
        let entries = match std::fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(e) => {
                return Line::new(
                    id,
                    false,
                    format!("{} cannot be read ({})", shown_path(&dir), e.kind()),
                );
            }
        };
        for entry in entries {
            walked += 1;
            if walked > MAX_SCAN_ENTRIES {
                return Line::new(
                    id,
                    false,
                    format!("the scan stopped after {MAX_SCAN_ENTRIES} entries: too many to check"),
                );
            }
            let Ok(entry) = entry else {
                return Line::new(
                    id,
                    false,
                    format!("an entry of {} cannot be read", shown_path(&dir)),
                );
            };
            let path = entry.path();
            // Not followed: a link is not the file it names, and the file is walked where it is.
            let meta = match path.symlink_metadata() {
                Ok(meta) => meta,
                // Gone while the scan ran.
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
                Err(e) => {
                    return Line::new(
                        id,
                        false,
                        format!("{} cannot be looked at ({})", shown_path(&path), e.kind()),
                    );
                }
            };
            let kind = meta.file_type();
            if kind.is_dir() {
                // As `find -xdev`: another file system mounted inside is not walked.
                if meta.dev() == dev {
                    stack.push((path, dev));
                }
            } else if kind.is_file()
                && meta.mode() & 0o6000 != 0
                && let Some(who) = who_may_run(meta.mode(), meta.uid(), meta.gid(), vmm_ids)
            {
                if first.is_none() {
                    first = Some((path, meta.mode() & 0o7777, meta.uid(), meta.gid(), who));
                } else {
                    more += 1;
                }
            }
        }
    }
    match first {
        None => Line::new(
            id,
            true,
            format!(
                "no setuid or setgid file that others or a VMM's user may run, in {walked} entries under /usr, /opt, /bin and /sbin"
            ),
        ),
        Some((path, mode, uid, gid, who)) => {
            let and = if more > 0 {
                format!(" (and {more} more)")
            } else {
                String::new()
            };
            let found = match who {
                None => format!(
                    "{} is setuid or setgid (mode {mode:o}) and others may run it{and}",
                    shown_path(&path)
                ),
                Some(vmm) => format!(
                    "{} is setuid or setgid (mode {mode:o}, owner {uid}, group {gid}) and the VMM's user {vmm} may run it{and}",
                    shown_path(&path)
                ),
            };
            Line::new(id, false, found)
        }
    }
}

/// The checker: the root it reads under, and the last scan for setuid files.
pub struct Checker {
    root: PathBuf,
    scan_every: Duration,
    /// The VMMs' users and groups, first and last, for the setuid scan; `None` where no VMM
    /// runs under this checker's root (a test's).
    vmm_ids: Option<(u32, u32)>,
    /// The last scan's line and when it was made.
    scan: Mutex<Option<(Instant, Line)>>,
    /// How many scans walked the tree, for the tests.
    walks: AtomicU64,
}

impl Checker {
    /// A checker of the machine under `root`; `/` on a node.
    pub fn new(root: impl Into<PathBuf>) -> Checker {
        Checker {
            root: root.into(),
            scan_every: SCAN_EVERY,
            vmm_ids: None,
            scan: Mutex::new(None),
            walks: AtomicU64::new(0),
        }
    }

    /// The same checker, which also counts a setuid or setgid file that a VMM's user may run
    /// as its owner or through its group: the VMMs' users and groups are `first` to `last`.
    pub fn with_vmm_ids(mut self, first: u32, last: u32) -> Checker {
        self.vmm_ids = Some((first, last));
        self
    }

    /// How many times a check walked the tree for setuid files.
    pub fn walks(&self) -> u64 {
        self.walks.load(Ordering::Relaxed)
    }

    /// Every line of the mark. `wanted` is the configuration's (`hardening` in `helper.json`;
    /// `None` in an older configuration), `vmms` the pids of the VMMs that run, or why they
    /// cannot be listed, and `now` the time the setuid scan's cache is measured by.
    pub fn check(
        &self,
        wanted: Option<&HardeningConfig>,
        vmms: &Result<Vec<u32>, String>,
        now: Instant,
    ) -> Hardening {
        let root = self.root.as_path();
        let lines = vec![
            setting_line(
                root,
                line::USER_NAMESPACES,
                "user.max_user_namespaces",
                |v| v == 0,
                "0",
            ),
            setting_line(
                root,
                line::UNPRIVILEGED_BPF,
                "kernel.unprivileged_bpf_disabled",
                |v| v == 1 || v == 2,
                "1 or 2",
            ),
            self.io_uring(),
            self.vmm_mac(vmms),
            setting_line(
                root,
                line::PANIC_ON_OOPS,
                "kernel.panic_on_oops",
                |v| v == 1,
                "1",
            ),
            setting_line(
                root,
                line::MODULES_DISABLED,
                "kernel.modules_disabled",
                |v| v == 1,
                "1",
            ),
            setting_line(
                root,
                line::KEXEC_DISABLED,
                "kernel.kexec_load_disabled",
                |v| v == 1,
                "1",
            ),
            self.lockdown(),
            self.distribution(),
            self.no_docker(),
            self.no_setuid(now),
            self.listening_sockets(wanted),
            self.max_uptime(wanted),
        ];
        Hardening::of(lines)
    }

    fn io_uring(&self) -> Line {
        let id = line::IO_URING;
        let name = "kernel.io_uring_disabled";
        match setting(&self.root, name) {
            Ok(2) => Line::new(id, true, format!("{name} is 2")),
            Ok(v) => Line::new(id, false, format!("{name} is {v}, not 2")),
            Err((Unread::Missing, _)) => {
                let release = release(&self.root).unwrap_or_else(|_| "unknown".into());
                Line::new(
                    id,
                    false,
                    format!(
                        "{name} does not exist: the kernel {release} has no switch for io_uring, which came with Linux 6.6"
                    ),
                )
            }
            Err((_, found)) => Line::new(id, false, found),
        }
    }

    /// AppArmor on, the profile loaded in enforce mode, and every running VMM under it. A
    /// machine without AppArmor fails the line: the check of SELinux labels is planned (S5).
    fn vmm_mac(&self, vmms: &Result<Vec<u32>, String>) -> Line {
        let id = line::VMM_MAC;
        let no = |found: String| Line::new(id, false, found);
        let enabled_path = self.root.join("sys/module/apparmor/parameters/enabled");
        let enabled = match read_text(&enabled_path, MAX_SETTING) {
            Ok(text) => text,
            Err(Unread::Missing) => {
                let selinux = self.root.join("sys/fs/selinux/enforce").exists();
                return no(if selinux {
                    "AppArmor is not in this kernel; SELinux is, and the check of the VMMs' SELinux labels is planned".into()
                } else {
                    "AppArmor is not in this kernel (no /sys/module/apparmor), and nothing else confines the VMMs".into()
                });
            }
            Err(e) => return no(format!("/sys/module/apparmor/parameters/enabled {e}")),
        };
        if enabled.trim() != "Y" {
            return no(format!(
                "AppArmor is off: /sys/module/apparmor/parameters/enabled is \"{}\"",
                shown(enabled.trim())
            ));
        }
        let wanted = format!("{PROFILE} (enforce)");
        let profiles = match read_text(
            &self.root.join("sys/kernel/security/apparmor/profiles"),
            MAX_LISTING,
        ) {
            Ok(text) => text,
            Err(e) => return no(format!("/sys/kernel/security/apparmor/profiles {e}")),
        };
        if !profiles.lines().any(|l| l.trim_end() == wanted) {
            let other = profiles
                .lines()
                .find(|l| l.split_once(" (").map(|(name, _)| name) == Some(PROFILE));
            return no(match other {
                Some(l) => format!(
                    "the AppArmor profile {PROFILE} is loaded as \"{}\", not in enforce mode",
                    shown(l.trim())
                ),
                None => format!("the AppArmor profile {PROFILE} is not loaded"),
            });
        }
        let pids = match vmms {
            Ok(pids) => pids,
            Err(why) => {
                return no(format!(
                    "the VMMs that run cannot be listed: {}",
                    shown(why)
                ));
            }
        };
        for pid in pids {
            let proc = self.root.join(format!("proc/{pid}/attr"));
            let label = match read_text(&proc.join("apparmor/current"), MAX_SETTING) {
                Err(Unread::Missing) => read_text(&proc.join("current"), MAX_SETTING),
                other => other,
            };
            match label {
                Ok(label) if label.trim_end_matches(['\n', '\0']).trim() == wanted => {}
                Ok(label) => {
                    return no(format!(
                        "the VMM {pid} runs as \"{}\", not as {wanted}",
                        shown(label.trim_end_matches(['\n', '\0']).trim())
                    ));
                }
                Err(e) => return no(format!("the AppArmor label of the VMM {pid} {e}")),
            }
        }
        Line::new(
            id,
            true,
            format!(
                "AppArmor enforces {PROFILE}, and the {} VMM(s) that run are under it",
                pids.len()
            ),
        )
    }

    fn lockdown(&self) -> Line {
        let id = line::LOCKDOWN;
        match read_text(&self.root.join("sys/kernel/security/lockdown"), MAX_SETTING) {
            Err(Unread::Missing) => Line::new(
                id,
                false,
                "the kernel has no lockdown (no /sys/kernel/security/lockdown)",
            ),
            Err(e) => Line::new(id, false, format!("/sys/kernel/security/lockdown {e}")),
            Ok(text) => {
                let level = text
                    .split_whitespace()
                    .find_map(|w| w.strip_prefix('[')?.strip_suffix(']'));
                match level {
                    Some(level @ ("integrity" | "confidentiality")) => {
                        Line::new(id, true, format!("lockdown is {level}"))
                    }
                    Some(level) => Line::new(
                        id,
                        false,
                        format!(
                            "lockdown is {}, not integrity or confidentiality",
                            shown(level)
                        ),
                    ),
                    None => Line::new(
                        id,
                        false,
                        format!(
                            "/sys/kernel/security/lockdown is \"{}\", with no level in brackets",
                            shown(text.trim())
                        ),
                    ),
                }
            }
        }
    }

    /// The narrower facts that stand for "the distribution's hardening on" (the owner,
    /// 2026-11-20).
    fn distribution(&self) -> Line {
        type Rule = (&'static str, fn(i64) -> bool, &'static str);
        let rules: [Rule; 6] = [
            ("kernel.kptr_restrict", |v| v >= 1, "1 or more"),
            ("kernel.dmesg_restrict", |v| v == 1, "1"),
            ("kernel.perf_event_paranoid", |v| v >= 2, "2 or more"),
            ("kernel.randomize_va_space", |v| v == 2, "2"),
            ("fs.protected_symlinks", |v| v == 1, "1"),
            ("fs.protected_hardlinks", |v| v == 1, "1"),
        ];
        let failing: Vec<String> = rules
            .iter()
            .filter_map(|(name, wanted, words)| match setting(&self.root, name) {
                Ok(v) if wanted(v) => None,
                Ok(v) => Some(format!("{name} is {v}, not {words}")),
                Err((_, found)) => Some(found),
            })
            .collect();
        if failing.is_empty() {
            Line::new(
                line::DISTRIBUTION_HARDENING,
                true,
                "kptr_restrict, dmesg_restrict, perf_event_paranoid, randomize_va_space and the protected links are as required",
            )
        } else {
            Line::new(line::DISTRIBUTION_HARDENING, false, failing.join("; "))
        }
    }

    fn no_docker(&self) -> Line {
        let id = line::NO_DOCKER;
        let mut there = Vec::new();
        for socket in SOCKETS {
            match self.root.join(socket).symlink_metadata() {
                Ok(_) => there.push(format!("/{socket} is there")),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => there.push(format!("/{socket} cannot be looked at ({})", e.kind())),
            }
        }
        if there.is_empty() {
            Line::new(id, true, "no Docker, containerd or podman socket")
        } else {
            Line::new(id, false, there.join("; "))
        }
    }

    /// The setuid scan, made again once the last is [`SCAN_EVERY`] old. A second check that
    /// comes while a scan runs waits for it, and takes its line.
    fn no_setuid(&self, now: Instant) -> Line {
        let mut last = self.scan.lock().unwrap_or_else(|e| e.into_inner());
        if let Some((at, line)) = &*last
            && now.saturating_duration_since(*at) < self.scan_every
            && now >= *at
        {
            return line.clone();
        }
        self.walks.fetch_add(1, Ordering::Relaxed);
        let line = scan_setuid(&self.root, self.vmm_ids);
        *last = Some((now, line.clone()));
        line
    }

    fn listening_sockets(&self, wanted: Option<&HardeningConfig>) -> Line {
        let id = line::LISTENING_SOCKETS;
        let Some(wanted) = wanted else {
            return Line::new(
                id,
                false,
                "helper.json names no hardening.agentPort and hardening.sshPort: the ports allowed beyond loopback are not known",
            );
        };
        let (agent, ssh) = (wanted.agent_port, wanted.ssh_port);
        let mut sockets = Vec::new();
        for (file, v6) in [("proc/net/tcp", false), ("proc/net/tcp6", true)] {
            match read_text(&self.root.join(file), MAX_LISTING) {
                Ok(text) => match listening(&text, v6) {
                    Ok(found) => sockets.extend(found),
                    Err(number) => {
                        return Line::new(
                            id,
                            false,
                            format!("line {number} of /{file} is not of the kernel's form"),
                        );
                    }
                },
                // A kernel without IPv6 has no `tcp6`, and no IPv6 socket.
                Err(Unread::Missing) if v6 => {}
                Err(e) => return Line::new(id, false, format!("/{file} {e}")),
            }
        }
        let outside: Vec<&(IpAddr, u16)> = sockets
            .iter()
            .filter(|(ip, port)| !is_loopback(ip) && *port != agent && *port != ssh)
            .collect();
        match outside.first() {
            None => Line::new(
                id,
                true,
                format!("no TCP socket listens beyond loopback but on ports {agent} and {ssh}"),
            ),
            Some((ip, port)) => {
                let and = if outside.len() > 1 {
                    format!(" (and {} more)", outside.len() - 1)
                } else {
                    String::new()
                };
                Line::new(
                    id,
                    false,
                    format!("TCP port {port} listens on {ip}, beyond loopback{and}"),
                )
            }
        }
    }

    fn max_uptime(&self, wanted: Option<&HardeningConfig>) -> Line {
        let id = line::MAX_UPTIME;
        let Some(wanted) = wanted else {
            return Line::new(id, false, "helper.json names no hardening.maxUptimeDays");
        };
        let days = wanted.max_uptime_days;
        let text = match read_text(&self.root.join("proc/uptime"), MAX_SETTING) {
            Ok(text) => text,
            Err(e) => return Line::new(id, false, format!("/proc/uptime {e}")),
        };
        let first = text.split_whitespace().next().unwrap_or("");
        let seconds = match first.parse::<f64>() {
            Ok(s) if s.is_finite() && s >= 0.0 => s,
            _ => {
                return Line::new(
                    id,
                    false,
                    format!("/proc/uptime is \"{}\", not a time", shown(text.trim())),
                );
            }
        };
        let limit = f64::from(days) * 86_400.0;
        let up = format!("up {:.0} s ({:.1} days)", seconds, seconds / 86_400.0);
        if seconds <= limit {
            Line::new(id, true, format!("{up}, at most {days} days"))
        } else {
            Line::new(
                id,
                false,
                format!("{up}, over {days} days: reboot or re-image the node"),
            )
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    const PID: u32 = 4242;

    fn wanted() -> HardeningConfig {
        HardeningConfig {
            agent_port: 7400,
            ssh_port: 22,
            max_uptime_days: 30,
        }
    }

    fn put(root: &Path, path: &str, text: &str) {
        let path = root.join(path);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }

    fn sysctl(root: &Path, name: &str, value: &str) {
        put(
            root,
            &format!("proc/sys/{}", name.replace('.', "/")),
            &format!("{value}\n"),
        );
    }

    const TCP_HEADER: &str = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";
    const TCP6_HEADER: &str = "  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";

    /// A `/proc/net/tcp` line in the kernel's form, for an address given as its octets.
    fn tcp_line(octets: [u8; 4], port: u16, state: u8) -> String {
        let word = u32::from_ne_bytes(octets);
        format!(
            "   0: {word:08X}:{port:04X} 00000000:0000 {state:02X} 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000 100 0 0 10 0\n"
        )
    }

    fn tcp6_line(ip: Ipv6Addr, port: u16, state: u8) -> String {
        let octets = ip.octets();
        let address: String = octets
            .chunks(4)
            .map(|c| format!("{:08X}", u32::from_ne_bytes([c[0], c[1], c[2], c[3]])))
            .collect();
        format!(
            "   0: {address}:{port:04X} 00000000000000000000000000000000:0000 {state:02X} 00000000:00000000 00:00000000 00000000     0        0 12345 1 0000000000000000 100 0 0 10 0\n"
        )
    }

    /// A machine on which every line holds, with one VMM that runs.
    fn hardened_root() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        for (name, value) in [
            ("user.max_user_namespaces", "0"),
            ("kernel.unprivileged_bpf_disabled", "2"),
            ("kernel.io_uring_disabled", "2"),
            ("kernel.panic_on_oops", "1"),
            ("kernel.modules_disabled", "1"),
            ("kernel.kexec_load_disabled", "1"),
            ("kernel.kptr_restrict", "1"),
            ("kernel.dmesg_restrict", "1"),
            ("kernel.perf_event_paranoid", "3"),
            ("kernel.randomize_va_space", "2"),
            ("fs.protected_symlinks", "1"),
            ("fs.protected_hardlinks", "1"),
            ("kernel.osrelease", "6.8.0-49-generic"),
        ] {
            sysctl(root, name, value);
        }
        put(root, "sys/module/apparmor/parameters/enabled", "Y\n");
        put(
            root,
            "sys/kernel/security/apparmor/profiles",
            "/usr/bin/man (enforce)\ntinfirm-vmm (enforce)\nlsb_release (enforce)\n",
        );
        put(
            root,
            &format!("proc/{PID}/attr/apparmor/current"),
            "tinfirm-vmm (enforce)\n",
        );
        put(
            root,
            "sys/kernel/security/lockdown",
            "none [integrity] confidentiality\n",
        );
        put(root, "proc/uptime", "86400.25 300000.00\n");
        put(
            root,
            "proc/stat",
            "cpu  1 2 3\nbtime 1730455200\nprocesses 9\n",
        );
        let tcp = [
            tcp_line([0, 0, 0, 0], 22, 0x0A),
            tcp_line([10, 0, 0, 20], 7400, 0x0A),
            tcp_line([127, 0, 0, 53], 53, 0x0A),
            // An established connection on another port is no listener.
            tcp_line([10, 0, 0, 20], 41234, 0x01),
        ]
        .concat();
        put(root, "proc/net/tcp", &format!("{TCP_HEADER}{tcp}"));
        let tcp6 = [
            tcp6_line(Ipv6Addr::UNSPECIFIED, 22, 0x0A),
            tcp6_line(Ipv6Addr::LOCALHOST, 631, 0x0A),
        ]
        .concat();
        put(root, "proc/net/tcp6", &format!("{TCP6_HEADER}{tcp6}"));
        // A plain program, and one that others may not run.
        put(root, "usr/bin/ls", "");
        put(root, "usr/lib/sudo/sudo", "");
        std::fs::set_permissions(
            root.join("usr/lib/sudo/sudo"),
            std::fs::Permissions::from_mode(0o4750),
        )
        .unwrap();
        std::fs::create_dir_all(root.join("opt/tool")).unwrap();
        dir
    }

    fn check(root: &Path) -> Hardening {
        Checker::new(root).check(Some(&wanted()), &Ok(vec![PID]), Instant::now())
    }

    fn line_of<'a>(h: &'a Hardening, id: &str) -> &'a Line {
        h.lines.iter().find(|l| l.line == id).unwrap()
    }

    /// The lines that fail, by id.
    fn failing(h: &Hardening) -> Vec<&str> {
        h.lines
            .iter()
            .filter(|l| !l.holds)
            .map(|l| l.line.as_str())
            .collect()
    }

    #[test]
    fn a_machine_on_which_every_line_holds_is_hardened() {
        let dir = hardened_root();
        let h = check(dir.path());
        assert_eq!(failing(&h), Vec::<&str>::new(), "{h:#?}");
        assert_eq!(h.hardened, Hardened::Yes);
        let ids: Vec<&str> = h.lines.iter().map(|l| l.line.as_str()).collect();
        assert_eq!(ids, LINES);
        assert_eq!(
            line_of(&h, line::IO_URING).found,
            "kernel.io_uring_disabled is 2"
        );
        assert!(line_of(&h, line::VMM_MAC).found.contains("1 VMM(s)"));
        // The answer on the wire.
        let wire = serde_json::to_value(&h).unwrap();
        assert_eq!(wire["hardened"], "yes");
        assert_eq!(wire["lines"][0]["line"], "user-namespaces");
        assert_eq!(wire["lines"][0]["holds"], true);
    }

    #[test]
    fn each_line_off_in_turn_gives_no_and_names_exactly_that_line() {
        type Off = Box<dyn Fn(&Path)>;
        let cases: Vec<(&str, Off, &str)> = vec![
            (
                line::USER_NAMESPACES,
                Box::new(|r| sysctl(r, "user.max_user_namespaces", "63000")),
                "user.max_user_namespaces is 63000, not 0",
            ),
            (
                line::UNPRIVILEGED_BPF,
                Box::new(|r| sysctl(r, "kernel.unprivileged_bpf_disabled", "0")),
                "kernel.unprivileged_bpf_disabled is 0, not 1 or 2",
            ),
            (
                line::IO_URING,
                Box::new(|r| sysctl(r, "kernel.io_uring_disabled", "0")),
                "kernel.io_uring_disabled is 0, not 2",
            ),
            (
                line::VMM_MAC,
                Box::new(|r| {
                    put(
                        r,
                        &format!("proc/{PID}/attr/apparmor/current"),
                        "unconfined\n",
                    )
                }),
                "the VMM 4242 runs as \"unconfined\", not as tinfirm-vmm (enforce)",
            ),
            (
                line::VMM_MAC,
                Box::new(|r| {
                    put(
                        r,
                        "sys/kernel/security/apparmor/profiles",
                        "tinfirm-vmm (complain)\n",
                    )
                }),
                "the AppArmor profile tinfirm-vmm is loaded as \"tinfirm-vmm (complain)\", not in enforce mode",
            ),
            (
                line::VMM_MAC,
                Box::new(|r| {
                    put(
                        r,
                        "sys/kernel/security/apparmor/profiles",
                        "other (enforce)\n",
                    )
                }),
                "the AppArmor profile tinfirm-vmm is not loaded",
            ),
            (
                line::VMM_MAC,
                Box::new(|r| put(r, "sys/module/apparmor/parameters/enabled", "N\n")),
                "AppArmor is off: /sys/module/apparmor/parameters/enabled is \"N\"",
            ),
            (
                line::PANIC_ON_OOPS,
                Box::new(|r| sysctl(r, "kernel.panic_on_oops", "0")),
                "kernel.panic_on_oops is 0, not 1",
            ),
            (
                line::MODULES_DISABLED,
                Box::new(|r| sysctl(r, "kernel.modules_disabled", "0")),
                "kernel.modules_disabled is 0, not 1",
            ),
            (
                line::KEXEC_DISABLED,
                Box::new(|r| sysctl(r, "kernel.kexec_load_disabled", "0")),
                "kernel.kexec_load_disabled is 0, not 1",
            ),
            (
                line::LOCKDOWN,
                Box::new(|r| {
                    put(
                        r,
                        "sys/kernel/security/lockdown",
                        "[none] integrity confidentiality\n",
                    )
                }),
                "lockdown is none, not integrity or confidentiality",
            ),
            (
                line::LOCKDOWN,
                Box::new(|r| std::fs::remove_file(r.join("sys/kernel/security/lockdown")).unwrap()),
                "the kernel has no lockdown (no /sys/kernel/security/lockdown)",
            ),
            (
                line::DISTRIBUTION_HARDENING,
                Box::new(|r| {
                    sysctl(r, "kernel.dmesg_restrict", "0");
                    sysctl(r, "fs.protected_symlinks", "0");
                }),
                "kernel.dmesg_restrict is 0, not 1; fs.protected_symlinks is 0, not 1",
            ),
            (
                line::DISTRIBUTION_HARDENING,
                Box::new(|r| sysctl(r, "kernel.perf_event_paranoid", "1")),
                "kernel.perf_event_paranoid is 1, not 2 or more",
            ),
            (
                line::NO_DOCKER,
                Box::new(|r| put(r, "run/docker.sock", "")),
                "/run/docker.sock is there",
            ),
            (
                line::NO_DOCKER,
                Box::new(|r| put(r, "run/containerd/containerd.sock", "")),
                "/run/containerd/containerd.sock is there",
            ),
            (
                line::NO_SETUID,
                Box::new(|r| {
                    put(r, "usr/bin/passwd", "");
                    std::fs::set_permissions(
                        r.join("usr/bin/passwd"),
                        std::fs::Permissions::from_mode(0o4755),
                    )
                    .unwrap();
                }),
                "/usr/bin/passwd is setuid or setgid (mode 4755) and others may run it",
            ),
            (
                line::NO_SETUID,
                Box::new(|r| {
                    put(r, "opt/tool/wall", "");
                    std::fs::set_permissions(
                        r.join("opt/tool/wall"),
                        std::fs::Permissions::from_mode(0o2711),
                    )
                    .unwrap();
                }),
                "/opt/tool/wall is setuid or setgid (mode 2711) and others may run it",
            ),
            (
                line::LISTENING_SOCKETS,
                Box::new(|r| {
                    let tcp = format!("{TCP_HEADER}{}", tcp_line([0, 0, 0, 0], 2375, 0x0A));
                    put(r, "proc/net/tcp", &tcp);
                }),
                "TCP port 2375 listens on 0.0.0.0, beyond loopback",
            ),
            (
                line::LISTENING_SOCKETS,
                Box::new(|r| {
                    let ip: Ipv6Addr = "2001:db8::7".parse().unwrap();
                    let tcp6 = format!("{TCP6_HEADER}{}", tcp6_line(ip, 8080, 0x0A));
                    put(r, "proc/net/tcp6", &tcp6);
                }),
                "TCP port 8080 listens on 2001:db8::7, beyond loopback",
            ),
            (
                line::MAX_UPTIME,
                Box::new(|r| put(r, "proc/uptime", "2592001.00 1.00\n")),
                "up 2592001 s (30.0 days), over 30 days: reboot or re-image the node",
            ),
        ];
        for (id, off, found) in cases {
            let dir = hardened_root();
            off(dir.path());
            let h = check(dir.path());
            assert_eq!(h.hardened, Hardened::No, "{id}");
            assert_eq!(failing(&h), [id], "{id}: {h:#?}");
            assert_eq!(line_of(&h, id).found, found, "{id}");
        }
    }

    #[test]
    fn a_kernel_without_the_io_uring_switch_is_named() {
        let dir = hardened_root();
        sysctl(dir.path(), "kernel.osrelease", "6.1.0-27-amd64");
        std::fs::remove_file(dir.path().join("proc/sys/kernel/io_uring_disabled")).unwrap();
        let h = check(dir.path());
        assert_eq!(failing(&h), [line::IO_URING]);
        let found = &line_of(&h, line::IO_URING).found;
        assert!(
            found.contains("does not exist")
                && found.contains("6.1.0-27-amd64")
                && found.contains("6.6"),
            "{found}"
        );
    }

    #[test]
    fn a_machine_without_apparmor_fails_the_vmm_line_and_names_selinux_where_it_is() {
        let dir = hardened_root();
        std::fs::remove_dir_all(dir.path().join("sys/module/apparmor")).unwrap();
        let h = check(dir.path());
        assert_eq!(failing(&h), [line::VMM_MAC]);
        assert!(
            line_of(&h, line::VMM_MAC)
                .found
                .contains("AppArmor is not in this kernel")
        );
        put(dir.path(), "sys/fs/selinux/enforce", "1");
        let h = check(dir.path());
        assert!(
            line_of(&h, line::VMM_MAC).found.contains("SELinux is"),
            "{h:#?}"
        );
    }

    #[test]
    fn the_vmms_are_read_through_the_older_attr_file_and_a_list_that_failed_fails_the_line() {
        let dir = hardened_root();
        let root = dir.path();
        // A kernel without the per-LSM directory.
        std::fs::remove_dir_all(root.join(format!("proc/{PID}/attr/apparmor"))).unwrap();
        put(
            root,
            &format!("proc/{PID}/attr/current"),
            "tinfirm-vmm (enforce)\n",
        );
        assert_eq!(failing(&check(root)), Vec::<&str>::new());
        // A VMM whose label cannot be read is no proof it is confined.
        let checker = Checker::new(root);
        let h = checker.check(Some(&wanted()), &Ok(vec![PID, 777]), Instant::now());
        assert_eq!(failing(&h), [line::VMM_MAC]);
        assert!(line_of(&h, line::VMM_MAC).found.contains("777"), "{h:#?}");
        let h = checker.check(
            Some(&wanted()),
            &Err("cannot read /srv/tinfirm-jails/firecracker: permission denied".into()),
            Instant::now(),
        );
        assert_eq!(failing(&h), [line::VMM_MAC]);
        assert!(
            line_of(&h, line::VMM_MAC)
                .found
                .contains("cannot be listed")
        );
        // No VMM runs: the profile loaded in enforce mode is enough.
        let h = checker.check(Some(&wanted()), &Ok(vec![]), Instant::now());
        assert_eq!(failing(&h), Vec::<&str>::new());
    }

    #[test]
    fn listeners_on_loopback_on_the_agents_port_and_on_ssh_are_fine() {
        let dir = hardened_root();
        let root = dir.path();
        let tcp = [
            tcp_line([127, 0, 0, 1], 5432, 0x0A),
            tcp_line([127, 9, 9, 9], 9000, 0x0A),
            tcp_line([0, 0, 0, 0], 22, 0x0A),
            tcp_line([10, 0, 0, 20], 7400, 0x0A),
        ]
        .concat();
        put(root, "proc/net/tcp", &format!("{TCP_HEADER}{tcp}"));
        let mapped: Ipv6Addr = "::ffff:127.0.0.1".parse().unwrap();
        let tcp6 = [
            tcp6_line(Ipv6Addr::LOCALHOST, 8080, 0x0A),
            tcp6_line(mapped, 8081, 0x0A),
            tcp6_line(Ipv6Addr::UNSPECIFIED, 7400, 0x0A),
            tcp6_line(Ipv6Addr::UNSPECIFIED, 9999, 0x01),
        ]
        .concat();
        put(root, "proc/net/tcp6", &format!("{TCP6_HEADER}{tcp6}"));
        let h = check(root);
        assert_eq!(failing(&h), Vec::<&str>::new(), "{h:#?}");
        // A kernel without IPv6 has no tcp6.
        std::fs::remove_file(root.join("proc/net/tcp6")).unwrap();
        assert_eq!(failing(&check(root)), Vec::<&str>::new());
        // But a kernel without tcp is no answer.
        std::fs::remove_file(root.join("proc/net/tcp")).unwrap();
        let h = check(root);
        assert_eq!(failing(&h), [line::LISTENING_SOCKETS]);
        assert_eq!(
            line_of(&h, line::LISTENING_SOCKETS).found,
            "/proc/net/tcp does not exist"
        );
    }

    #[test]
    fn the_kernels_listing_is_read_by_its_words() {
        let ip: Ipv4Addr = "10.0.0.20".parse().unwrap();
        let text = format!("{TCP_HEADER}{}", tcp_line(ip.octets(), 7400, 0x0A));
        assert_eq!(listening(&text, false).unwrap(), [(IpAddr::V4(ip), 7400)]);
        // On a little-endian machine, as the kernel prints it.
        if cfg!(target_endian = "little") {
            let text = format!(
                "{TCP_HEADER}   0: 0100007F:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1 1\n"
            );
            assert_eq!(
                listening(&text, false).unwrap(),
                [(IpAddr::V4(Ipv4Addr::LOCALHOST), 22)]
            );
        }
        for bad in [
            String::new(),
            "no header\n".to_string(),
            format!("{TCP_HEADER}   0: 0100007F\n"),
            format!("{TCP_HEADER}   0: 0100007G:0016 00000000:0000 0A\n"),
            format!("{TCP_HEADER}   0: 0100007F:16 00000000:0000 0A\n"),
            format!("{TCP_HEADER}   0: 0100007F:0016 00000000:0000 A\n"),
            format!("{TCP_HEADER}   0: +100007F:0016 00000000:0000 0A\n"),
            format!("{TCP_HEADER}   0: 0100007F0100007F:0016 00000000:0000 0A\n"),
        ] {
            assert!(listening(&bad, false).is_err(), "{bad:?}");
        }
        // An IPv6 address of the wrong length, or with a byte that is not ASCII.
        assert_eq!(
            listening(&format!("{TCP6_HEADER}   0: 0100007F:0016 0:0 0A\n"), true),
            Err(2)
        );
        assert!(
            listening(
                &format!("{TCP6_HEADER}   0: 000000000000000000000000000000é:0016 0:0 0A\n"),
                true
            )
            .is_err()
        );
    }

    #[test]
    fn unreadable_and_malformed_facts_fail_their_line_and_say_so() {
        let cases: Vec<(&str, &str, &str, &str)> = vec![
            (
                line::USER_NAMESPACES,
                "proc/sys/user/max_user_namespaces",
                "zero\n",
                "user.max_user_namespaces is \"zero\", not a number",
            ),
            (
                line::PANIC_ON_OOPS,
                "proc/sys/kernel/panic_on_oops",
                "\u{1b}[31m1\n",
                "kernel.panic_on_oops is \"\\u{1b}[31m1\", not a number",
            ),
            (
                line::LOCKDOWN,
                "sys/kernel/security/lockdown",
                "integrity\n",
                "/sys/kernel/security/lockdown is \"integrity\", with no level in brackets",
            ),
            (
                line::MAX_UPTIME,
                "proc/uptime",
                "NaN 1\n",
                "/proc/uptime is \"NaN 1\", not a time",
            ),
            (
                line::MAX_UPTIME,
                "proc/uptime",
                "-5 1\n",
                "/proc/uptime is \"-5 1\", not a time",
            ),
            (
                line::LISTENING_SOCKETS,
                "proc/net/tcp",
                "garbage\n",
                "line 1 of /proc/net/tcp is not of the kernel's form",
            ),
        ];
        for (id, path, content, found) in cases {
            let dir = hardened_root();
            put(dir.path(), path, content);
            let h = check(dir.path());
            assert_eq!(failing(&h), [id], "{path}");
            assert_eq!(line_of(&h, id).found, found, "{path}");
        }

        // Not text, too long, missing, and a directory where a file should be (which no
        // permission makes readable, so the test means the same as root).
        let dir = hardened_root();
        let root = dir.path();
        std::fs::write(
            root.join("proc/sys/kernel/modules_disabled"),
            [0xff, 0xfe, 0],
        )
        .unwrap();
        std::fs::write(
            root.join("proc/sys/kernel/kexec_load_disabled"),
            "1".repeat(5000),
        )
        .unwrap();
        std::fs::remove_file(root.join("proc/sys/user/max_user_namespaces")).unwrap();
        std::fs::remove_file(root.join("sys/kernel/security/apparmor/profiles")).unwrap();
        std::fs::create_dir(root.join("sys/kernel/security/apparmor/profiles")).unwrap();
        let h = check(root);
        assert_eq!(
            failing(&h),
            [
                line::USER_NAMESPACES,
                line::VMM_MAC,
                line::MODULES_DISABLED,
                line::KEXEC_DISABLED
            ]
        );
        assert_eq!(
            line_of(&h, line::MODULES_DISABLED).found,
            "kernel.modules_disabled is not text"
        );
        assert_eq!(
            line_of(&h, line::KEXEC_DISABLED).found,
            "kernel.kexec_load_disabled is longer than 4096 bytes"
        );
        assert_eq!(
            line_of(&h, line::USER_NAMESPACES).found,
            "user.max_user_namespaces does not exist"
        );
        assert!(
            line_of(&h, line::VMM_MAC)
                .found
                .starts_with("/sys/kernel/security/apparmor/profiles cannot be read"),
            "{h:#?}"
        );
    }

    #[test]
    fn no_file_content_makes_the_check_panic_or_a_found_grow_past_its_bound() {
        let files = [
            "proc/sys/user/max_user_namespaces",
            "proc/sys/kernel/io_uring_disabled",
            "proc/sys/kernel/osrelease",
            "sys/module/apparmor/parameters/enabled",
            "sys/kernel/security/apparmor/profiles",
            "sys/kernel/security/lockdown",
            "proc/uptime",
            "proc/net/tcp",
            "proc/net/tcp6",
        ];
        let mut seed: u64 = 0x2545_f491_4f6c_dd1d;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        let dir = hardened_root();
        let root = dir.path();
        for round in 0..200 {
            for file in files {
                let len = (next() % 600) as usize;
                let bytes: Vec<u8> = (0..len)
                    .map(|_| {
                        // Mostly the bytes the kernel's files hold, sometimes any byte.
                        const LIKELY: &[u8] = b"0123456789ABCDEFabcdef :[]()\n.-yesY";
                        let r = next();
                        if r % 4 == 0 {
                            (r >> 8) as u8
                        } else {
                            LIKELY[(r >> 8) as usize % LIKELY.len()]
                        }
                    })
                    .collect();
                std::fs::write(root.join(file), bytes).unwrap();
            }
            let h = check(root);
            assert_eq!(h.lines.len(), LINES.len(), "round {round}");
            for line in &h.lines {
                assert!(line.found.chars().count() <= MAX_FOUND, "{line:?}");
                assert!(!line.found.contains('\n'), "{line:?}");
            }
            assert_eq!(h.hardened == Hardened::Yes, h.lines.iter().all(|l| l.holds));
        }
    }

    #[test]
    fn uptime_just_under_the_limit_holds_and_just_over_it_does_not() {
        let dir = hardened_root();
        let root = dir.path();
        put(root, "proc/uptime", "2591999.99 1.00\n");
        assert_eq!(failing(&check(root)), Vec::<&str>::new());
        put(root, "proc/uptime", "2592000.00 1.00\n");
        assert_eq!(failing(&check(root)), Vec::<&str>::new());
        put(root, "proc/uptime", "2592000.01 1.00\n");
        assert_eq!(failing(&check(root)), [line::MAX_UPTIME]);
    }

    #[test]
    fn an_older_configuration_without_the_hardening_fails_the_two_lines_that_need_it() {
        let dir = hardened_root();
        let h = Checker::new(dir.path()).check(None, &Ok(vec![PID]), Instant::now());
        assert_eq!(
            failing(&h),
            [line::LISTENING_SOCKETS, line::MAX_UPTIME],
            "{h:#?}"
        );
        assert!(
            line_of(&h, line::LISTENING_SOCKETS)
                .found
                .contains("hardening.agentPort")
        );
        assert!(
            line_of(&h, line::MAX_UPTIME)
                .found
                .contains("hardening.maxUptimeDays")
        );
    }

    #[test]
    fn a_vmms_user_may_run_a_file_as_its_owner_or_through_its_group() {
        let ids = Some((100_000, 100_063));
        for (mode, uid, gid, who) in [
            // Others may run it, whoever owns it.
            (0o4755, 0, 0, Some(None)),
            (0o2711, 0, 0, Some(None)),
            // `sudo` as the role leaves it: root's group only.
            (0o4750, 0, 27, None),
            // The group is a VMM's.
            (0o4750, 0, 100_003, Some(Some(100_003))),
            (0o4750, 0, 100_064, None),
            (0o4740, 0, 100_003, None),
            // The owner is a VMM's.
            (0o2700, 100_000, 0, Some(Some(100_000))),
            (0o2600, 100_000, 0, None),
            // Owner and group are the same VMM's: the owner's bits decide.
            (0o4070, 100_003, 100_003, None),
            (0o4170, 100_003, 100_003, Some(Some(100_003))),
        ] {
            assert_eq!(
                who_may_run(mode, uid, gid, ids),
                who,
                "{mode:o} {uid} {gid}"
            );
        }
        // Where the VMMs' range is not known, only the others' bit counts.
        assert_eq!(who_may_run(0o4750, 0, 100_003, None), None);
        assert_eq!(who_may_run(0o4751, 0, 100_003, None), Some(None));
    }

    #[test]
    fn the_scan_finds_a_setuid_file_that_only_a_vmms_group_may_run() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        put(root, "usr/bin/helper", "");
        let file = root.join("usr/bin/helper");
        std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o4750)).unwrap();
        let (uid, gid) = {
            let meta = file.metadata().unwrap();
            (meta.uid(), meta.gid())
        };
        // No VMM has this file's group or owner: nobody who must not may run it.
        let far = gid.max(uid) + 1000;
        assert!(scan_setuid(root, Some((far, far + 63))).holds);
        assert!(scan_setuid(root, None).holds);
        // The file's group is a VMM's. (Where the test's user and group are one number, the
        // file's owner is that VMM too, and the owner's bit lets it run.)
        let line = scan_setuid(root, Some((gid, gid)));
        assert!(!line.holds, "{}", line.found);
        assert!(
            line.found
                .contains("/usr/bin/helper is setuid or setgid (mode 4750, owner ")
                && line
                    .found
                    .contains(&format!("the VMM's user {gid} may run it")),
            "{}",
            line.found
        );
    }

    #[test]
    fn the_setuid_scan_is_walked_again_only_once_its_time_is_up() {
        let dir = hardened_root();
        let root = dir.path();
        let checker = Checker::new(root);
        let start = Instant::now();
        let at = |seconds: u64| start + Duration::from_secs(seconds);
        let check = |now| checker.check(Some(&wanted()), &Ok(vec![PID]), now);
        assert_eq!(failing(&check(at(0))), Vec::<&str>::new());
        assert_eq!(checker.walks(), 1);
        // A setuid file comes: the cached scan still stands within its time.
        put(root, "usr/bin/su", "");
        std::fs::set_permissions(
            root.join("usr/bin/su"),
            std::fs::Permissions::from_mode(0o4755),
        )
        .unwrap();
        assert_eq!(failing(&check(at(1))), Vec::<&str>::new());
        assert_eq!(failing(&check(at(599))), Vec::<&str>::new());
        assert_eq!(checker.walks(), 1);
        // Once it is 10 minutes old, the next check walks again and finds it.
        let h = check(at(600));
        assert_eq!(checker.walks(), 2);
        assert_eq!(failing(&h), [line::NO_SETUID]);
        assert!(
            line_of(&h, line::NO_SETUID)
                .found
                .starts_with("/usr/bin/su")
        );
        assert_eq!(failing(&check(at(601))), [line::NO_SETUID]);
        assert_eq!(checker.walks(), 2);
    }

    #[test]
    fn the_scan_does_not_follow_links_and_counts_what_it_finds() {
        let dir = hardened_root();
        let root = dir.path();
        // A link to a setuid file outside the scanned trees is not the file.
        put(root, "srv/su", "");
        std::fs::set_permissions(root.join("srv/su"), std::fs::Permissions::from_mode(0o4755))
            .unwrap();
        std::os::unix::fs::symlink(root.join("srv"), root.join("usr/srv-link")).unwrap();
        std::os::unix::fs::symlink(root.join("srv/su"), root.join("usr/bin/su-link")).unwrap();
        // `/bin` as a link into `/usr`, as on a merged-`/usr` distribution.
        std::os::unix::fs::symlink("usr/bin", root.join("bin")).unwrap();
        assert_eq!(failing(&check(root)), Vec::<&str>::new());
        // `/sbin` as a directory of its own is walked.
        for name in ["sbin/a", "sbin/b", "usr/bin/c"] {
            put(root, name, "");
            std::fs::set_permissions(root.join(name), std::fs::Permissions::from_mode(0o6755))
                .unwrap();
        }
        let h = check(root);
        assert_eq!(failing(&h), [line::NO_SETUID]);
        assert!(
            line_of(&h, line::NO_SETUID).found.ends_with("(and 2 more)"),
            "{h:#?}"
        );
    }

    #[test]
    fn a_found_is_cut_to_its_bound_and_a_path_is_escaped() {
        let line = Line::new("x", false, "a".repeat(500));
        assert_eq!(line.found.chars().count(), MAX_FOUND);
        assert!(line.found.ends_with('…'));
        let dir = hardened_root();
        let root = dir.path();
        let name = format!("usr/bin/{}/evil\n\u{1b}[31m", "z".repeat(250));
        put(root, &name, "");
        std::fs::set_permissions(root.join(&name), std::fs::Permissions::from_mode(0o4755))
            .unwrap();
        let h = check(root);
        let found = &line_of(&h, line::NO_SETUID).found;
        assert!(
            !found.contains('\n') && !found.contains('\u{1b}'),
            "{found}"
        );
        assert!(found.chars().count() <= MAX_FOUND);
    }

    #[test]
    fn the_kernels_release_and_boot_time_are_shown() {
        let dir = hardened_root();
        assert_eq!(
            kernel_line(dir.path()),
            "the kernel 6.8.0-49-generic, booted 2024-11-01 10:00:00 UTC"
        );
        assert_eq!(utc_text(0), "1970-01-01 00:00:00 UTC");
        assert_eq!(utc_text(951_782_400), "2000-02-29 00:00:00 UTC");
        assert_eq!(utc_text(1_798_761_599), "2026-12-31 23:59:59 UTC");
        let empty = tempfile::tempdir().unwrap();
        let line = kernel_line(empty.path());
        assert!(
            line.contains("unknown release") && line.contains("/proc/stat"),
            "{line}"
        );
    }

    #[test]
    fn the_mark_is_yes_only_with_lines_that_all_hold() {
        assert_eq!(Hardening::of(vec![]).hardened, Hardened::No);
        assert_eq!(
            Hardening::of(vec![Line::new("a", true, "")]).hardened,
            Hardened::Yes
        );
        assert_eq!(
            Hardening::of(vec![Line::new("a", true, ""), Line::new("b", false, "")]).hardened,
            Hardened::No
        );
        // The wire's form: exactly these fields.
        let parsed: Result<Hardening, _> = serde_json::from_value(serde_json::json!({
            "hardened": "yes", "lines": [], "extra": 1
        }));
        assert!(parsed.is_err());
        let parsed: Result<Hardening, _> =
            serde_json::from_value(serde_json::json!({"hardened": "maybe", "lines": []}));
        assert!(parsed.is_err());
    }
}
