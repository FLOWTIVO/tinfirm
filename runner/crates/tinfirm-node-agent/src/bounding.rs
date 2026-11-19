//! The VMM's capability bounding set (ADR-0112 decision 4): the one place of this crate with
//! unsafe code.
//!
//! The jailer drops to the VM's user and execs Firecracker. The effective and permitted sets
//! are then empty, but the bounding set is inherited as the jailer had it, and the jailer has
//! no argument for it. A bounding set can only be lowered, and only by a process that holds
//! `CAP_SETPCAP`, so the helper lowers it in the child it starts the jailer from, between the
//! fork and the exec: every capability but the six the jailer needs itself is dropped with
//! `prctl(PR_CAPBSET_DROP)`. The helper's own set is not touched.
//!
//! Safe Rust's `std` has neither `prctl` nor a hook between fork and exec, so this needs
//! `CommandExt::pre_exec` and a call into libc. The closure calls `prctl` and nothing else: no
//! allocation, no lock, which is all that may run in a forked child of a threaded process.

use std::io;
use std::os::unix::process::CommandExt;
use std::process::Command;

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

/// Makes `command`'s child drop every capability but [`JAILER_CAPABILITIES`] from its bounding
/// set before it execs. The exec fails, and so does the spawn, when a drop is refused (the
/// caller does not hold `CAP_SETPCAP`): a jailer with a wider set is never started.
#[allow(unsafe_code)]
pub fn cut_bounding_set(command: &mut Command) -> &mut Command {
    // SAFETY: the closure runs in the forked child before the exec. It calls prctl(2), which
    // is async-signal-safe, reads two constants, and touches no memory that another thread
    // of the parent could have left locked or half written.
    unsafe {
        command.pre_exec(|| {
            let mut capability = 0;
            while capability <= HIGHEST_ASKED {
                let mut keep = false;
                let mut i = 0;
                while i < JAILER_CAPABILITIES.len() {
                    keep |= JAILER_CAPABILITIES[i] == capability;
                    i += 1;
                }
                if !keep
                    && libc::prctl(
                        libc::PR_CAPBSET_DROP,
                        libc::c_ulong::from(capability),
                        0,
                        0,
                        0,
                    ) != 0
                {
                    let error = io::Error::last_os_error();
                    // A number beyond the kernel's last capability: nothing is left to drop.
                    if error.raw_os_error() == Some(libc::EINVAL) {
                        break;
                    }
                    return Err(error);
                }
                capability += 1;
            }
            Ok(())
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_jailers_bounding_set_is_the_six_capabilities_and_no_other() {
        assert_eq!(jailer_bounding_set(), 0x0000_0000_0820_00c3);
        assert_eq!(
            format!("{:016x}", jailer_bounding_set()),
            "00000000082000c3"
        );
        assert_eq!(jailer_bounding_set().count_ones(), 6);
    }

    /// Reads the bounding set of a child started through [`cut_bounding_set`]. A process
    /// without `CAP_SETPCAP` cannot drop anything: there the spawn itself must fail, which is
    /// the other half of the promise.
    #[test]
    fn a_child_started_through_the_cut_has_the_six_or_does_not_start() {
        let mut command = Command::new("/bin/sh");
        command
            .args(["-c", "grep ^CapBnd: /proc/self/status"])
            .stdin(std::process::Stdio::null());
        match cut_bounding_set(&mut command).output() {
            Ok(output) => {
                let text = String::from_utf8_lossy(&output.stdout);
                let set = text.split_whitespace().nth(1).unwrap_or("");
                let set = u64::from_str_radix(set, 16).unwrap();
                // A parent whose own bounding set is narrower leaves fewer; never more.
                assert_eq!(set & !jailer_bounding_set(), 0, "{text}");
            }
            Err(e) => assert_eq!(e.raw_os_error(), Some(libc::EPERM), "{e}"),
        }
    }
}
