//! The VMM's capability bounding set (ADR-0112 decision 4), and `start-jailer`, the hidden
//! subcommand that cuts it.
//!
//! The jailer drops to the VM's user and execs Firecracker. The effective and permitted sets
//! are then empty, but the bounding set is inherited as the jailer had it, and the jailer has
//! no argument for it. A bounding set belongs to a thread, can only be lowered, and only by a
//! thread that holds `CAP_SETPCAP`. So the helper does not start the jailer itself: it starts
//! its own binary again as `tinfirm-node-agent start-jailer JAILER ARGS…`, and that process
//! drops every capability but the six the jailer needs from its own bounding set, then
//! replaces itself with the jailer. The helper's own set is not touched.
//!
//! No unsafe code: `prctl(PR_CAPBSET_DROP)` is rustix's, and the exec is `std`'s.
//!
//! **What `start-jailer` accepts** ([`check_start`]), from whoever runs it:
//!
//! - `JAILER`: an absolute path to a file named `jailer` that is a regular file of root's
//!   which neither its group nor others can write, not a link;
//! - `ARGS`: the jailer's own arguments, of which the first two must be `--id` and a jail's
//!   name of the helper's form (`tfvm-`, 12 hex digits, `-`, 4 hex digits).
//!
//! Anything else is refused before anything is done. The subcommand reads no configuration
//! and no environment, and hands the jailer an empty environment.
//!
//! **What a caller gets who is not the helper.** The binary is not set-uid and has no file
//! capability, so the subcommand runs with its caller's rights. A caller without
//! `CAP_SETPCAP` (every unprivileged user) gets "cannot cut the capability bounding set",
//! exit status 1, and no jailer is started. Root gets what it could do itself: the jailer of
//! root's own file system, started with fewer capabilities than root has.

use std::ffi::OsString;
use std::io;
use std::os::unix::fs::MetadataExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::Command;

use rustix::thread::{CapabilitySet, remove_capability_from_bounding_set};

use crate::plan::Names;

/// The subcommand's name, as the helper passes it to its own binary.
pub const START_JAILER: &str = "start-jailer";

/// The capabilities the jailer needs itself, by number (`linux/capability.h`): `CAP_CHOWN`,
/// `CAP_DAC_OVERRIDE`, `CAP_SETGID`, `CAP_SETUID`, `CAP_SYS_ADMIN`, `CAP_MKNOD`. Without any
/// one of them the jailer fails (the owner's sixth sandbox round removed them one at a time).
pub const JAILER_CAPABILITIES: [u32; 6] = [0, 1, 6, 7, 21, 27];

/// The highest capability number this asks the kernel about. The kernel answers `EINVAL` for a
/// number it does not know, which ends the loop sooner.
const HIGHEST_ASKED: u32 = 63;

/// The bounding set that [`JAILER_CAPABILITIES`] leaves, as `/proc/<pid>/status` shows it in
/// `CapBnd`: `00000000082000c3`.
pub const fn jailer_bounding_set() -> u64 {
    let mut set = 0u64;
    let mut i = 0;
    while i < JAILER_CAPABILITIES.len() {
        set |= 1 << JAILER_CAPABILITIES[i];
        i += 1;
    }
    set
}

/// What is known of the file that `start-jailer` is asked to exec.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JailerFacts {
    pub is_file: bool,
    pub uid: u32,
    pub mode: u32,
}

impl JailerFacts {
    /// The facts of `path` itself: a link is not followed, so a link is not a file.
    pub fn of(path: &Path) -> io::Result<JailerFacts> {
        let meta = std::fs::symlink_metadata(path)?;
        Ok(JailerFacts {
            is_file: meta.file_type().is_file(),
            uid: meta.uid(),
            mode: meta.mode() & 0o7777,
        })
    }
}

/// What `start-jailer` accepts: the module's head says it in words. Pure, on the facts of the
/// file; nothing a caller wrote goes into a refusal.
pub fn check_start(
    jailer: &Path,
    facts: Option<JailerFacts>,
    args: &[OsString],
) -> Result<(), &'static str> {
    if !jailer.is_absolute() || jailer.file_name() != Some(std::ffi::OsStr::new("jailer")) {
        return Err("the program is not an absolute path to a file named `jailer`");
    }
    match facts {
        Some(facts) if facts.is_file && facts.uid == 0 && facts.mode & 0o022 == 0 => {}
        _ => return Err("the jailer is not a regular file of root's that only root can write"),
    }
    let id = match args {
        [flag, id, ..] if flag == "--id" => id.to_str(),
        _ => None,
    };
    if id.and_then(Names::from_jail).is_none() {
        return Err("the arguments do not start with `--id` and a jail's name of the helper's");
    }
    Ok(())
}

/// Drops every capability but [`JAILER_CAPABILITIES`] from the calling thread's bounding set.
/// It fails when a drop is refused: the caller does not hold `CAP_SETPCAP`.
pub fn cut_bounding_set() -> io::Result<()> {
    for capability in 0..=HIGHEST_ASKED {
        if JAILER_CAPABILITIES.contains(&capability) {
            continue;
        }
        match remove_capability_from_bounding_set(CapabilitySet::from_bits_retain(1 << capability))
        {
            Ok(()) => {}
            // A number beyond the kernel's last capability: nothing is left to drop.
            Err(rustix::io::Errno::INVAL) => break,
            Err(e) => return Err(e.into()),
        }
    }
    Ok(())
}

/// `start-jailer`: checks what it was given, cuts this thread's bounding set, and replaces
/// this process with the jailer. It returns only with the reason why no jailer was started.
/// It must run on the thread that execs, before any other thread exists: `main` calls it
/// before it starts its runtime.
pub fn start_jailer(jailer: &Path, args: &[OsString]) -> anyhow::Error {
    if let Err(refused) = check_start(jailer, JailerFacts::of(jailer).ok(), args) {
        return anyhow::anyhow!("{refused}: no jailer was started");
    }
    if let Err(e) = cut_bounding_set() {
        return anyhow::anyhow!(
            "cannot cut the capability bounding set ({}): no jailer was started",
            e.kind()
        );
    }
    let error = Command::new(jailer)
        .args(args)
        .env_clear()
        .current_dir("/")
        .exec();
    anyhow::anyhow!("cannot exec the jailer: {}", error.kind())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(words: &[&str]) -> Vec<OsString> {
        words.iter().map(OsString::from).collect()
    }

    const ROOTS: Option<JailerFacts> = Some(JailerFacts {
        is_file: true,
        uid: 0,
        mode: 0o755,
    });

    #[test]
    fn the_jailers_bounding_set_is_the_six_capabilities_and_no_other() {
        assert_eq!(jailer_bounding_set(), 0x0000_0000_0820_00c3);
        assert_eq!(
            format!("{:016x}", jailer_bounding_set()),
            "00000000082000c3"
        );
        assert_eq!(jailer_bounding_set().count_ones(), 6);
    }

    #[test]
    fn start_jailer_takes_roots_jailer_and_a_jail_of_the_helpers_form_and_nothing_else() {
        let good = args(&["--id", "tfvm-00000000002a-0003", "--uid", "1100003"]);
        let jailer = Path::new("/usr/local/bin/jailer");
        assert_eq!(check_start(jailer, ROOTS, &good), Ok(()));

        // The program: absolute, named `jailer`.
        for path in [
            "jailer",
            "./jailer",
            "/bin/sh",
            "/usr/local/bin/jailer2",
            "/",
        ] {
            assert!(
                check_start(Path::new(path), ROOTS, &good)
                    .unwrap_err()
                    .contains("absolute path"),
                "{path}"
            );
        }
        // The file: root's, a regular file, written by root alone.
        for facts in [
            None,
            Some(JailerFacts {
                is_file: false,
                uid: 0,
                mode: 0o755,
            }),
            Some(JailerFacts {
                is_file: true,
                uid: 1000,
                mode: 0o755,
            }),
            Some(JailerFacts {
                is_file: true,
                uid: 0,
                mode: 0o775,
            }),
            Some(JailerFacts {
                is_file: true,
                uid: 0,
                mode: 0o757,
            }),
        ] {
            assert!(
                check_start(jailer, facts, &good)
                    .unwrap_err()
                    .contains("regular file of root's"),
                "{facts:?}"
            );
        }
        // The arguments: `--id` and a jail's name first.
        for bad in [
            args(&[]),
            args(&["--id"]),
            args(&["--uid", "0", "--id", "tfvm-00000000002a-0003"]),
            args(&["--id", "anything"]),
            args(&["--id", "tfvm-00000000002a-0003\n"]),
            args(&["--id", "../tfvm-00000000002a-0003"]),
            args(&["--id=tfvm-00000000002a-0003"]),
        ] {
            assert!(
                check_start(jailer, ROOTS, &bad)
                    .unwrap_err()
                    .contains("do not start with `--id`"),
                "{bad:?}"
            );
        }
    }
}
