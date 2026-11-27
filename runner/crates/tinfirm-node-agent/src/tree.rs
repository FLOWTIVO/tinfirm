//! Removing a jail's directory, whatever a VM's user left in it.
//!
//! The chroot of a jail belongs to the VM's user, so a process that got out of a VMM can
//! build anything in it before its VM is deleted: a tree thousands of directories deep, a
//! directory of a million files or of a million directories, or a directory with the sticky
//! bit, in which only an entry's owner may remove it. A removal that goes down such a tree
//! with one open directory and one stack frame per level ends at the helper's limit of open
//! files, or at the end of its stack, and then the jail is never removed and holds its slot
//! for good.
//!
//! [`remove_tree`] never goes deeper than one level. It moves every directory it meets up to
//! the top, beside the others, under a name of its own, and empties each from there: two
//! directories are open at a time, no path grows with the tree's depth, and no recursion
//! happens. It keeps no list of what it moved: the moved directories are in the top under
//! their names, and the top is read again until it is empty, so the memory it uses is that
//! of one round of names ([`ROUND`]), whatever the tree holds. It makes itself the owner of
//! every directory before it removes anything from it, so a sticky bit does not stop it. It
//! follows no link: a link is removed, never what it names.
//!
//! Its time is bounded by what the tree holds: every entry is removed once, and a directory
//! is moved at most once, when the directory that holds it is emptied. Nothing can add to the
//! tree meanwhile: a jail is removed after its cgroup is empty, and no other process runs as
//! the VM's user. A jail's own file system bounds the number of entries (`crate::linux`).

use std::ffi::{CStr, CString};
use std::io;
use std::os::fd::{AsFd, OwnedFd};
use std::path::Path;

use rustix::fs::{AtFlags, CWD, Dir, Mode, OFlags, fchown, openat, renameat, unlinkat};
use rustix::io::Errno;

/// How many entries of a directory are read before they are removed: a directory of any size
/// is emptied in rounds of this many, so its names are never all in memory.
const ROUND: usize = 1024;

/// Opens a directory by one name, following no link, and makes the caller its owner: in a
/// directory with the sticky bit only the owner of an entry or of the directory may remove
/// the entry, and the helper, root without `CAP_FOWNER`, is neither until it owns the
/// directory (`CAP_CHOWN` lets it).
fn take_dir<Fd: AsFd, P: rustix::path::Arg>(parent: Fd, name: P) -> rustix::io::Result<OwnedFd> {
    let dir = openat(
        parent,
        name,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?;
    fchown(&dir, Some(rustix::process::geteuid()), None)?;
    Ok(dir)
}

/// The next names of `stream`, at most [`ROUND`], without `.` and `..`; none at its end.
fn some_names(stream: &mut Dir) -> io::Result<Vec<CString>> {
    let mut names = Vec::new();
    while names.len() < ROUND {
        let Some(entry) = stream.read() else { break };
        let entry = entry?;
        let name = entry.file_name();
        if name.to_bytes() != b"." && name.to_bytes() != b".." {
            names.push(name.to_owned());
        }
    }
    Ok(names)
}

/// Removes `name` from `dir` when it is no directory; `true` when it is one, and is still there.
fn remove_unless_directory(dir: &OwnedFd, name: &CStr) -> io::Result<bool> {
    match unlinkat(dir, name, AtFlags::empty()) {
        Ok(()) | Err(Errno::NOENT) => Ok(false),
        // Linux says EISDIR for a directory. EPERM is no directory here: it is an entry that
        // may not be removed, and that is an error.
        Err(Errno::ISDIR) => Ok(true),
        Err(e) => Err(e.into()),
    }
}

/// Empties `dir`, one pass after another until a pass finds nothing: `each` gets every name
/// and must remove it from `dir` or fail.
fn empty(dir: &OwnedFd, mut each: impl FnMut(&CStr) -> io::Result<()>) -> io::Result<()> {
    let mut stream = Dir::read_from(dir)?;
    loop {
        let mut found = false;
        loop {
            let names = some_names(&mut stream)?;
            if names.is_empty() {
                break;
            }
            found = true;
            for name in &names {
                each(name)?;
            }
        }
        if !found {
            return Ok(());
        }
        // What was added while the pass ran (the directories moved up) is read by the next.
        stream.rewind();
    }
}

/// Removes `top` and everything in it; a `top` that is not there is no error. `top` itself
/// must be a directory that only the caller can write (a jail's own directory is root's):
/// the directories found below it are moved there under the names `.remove-<n>`.
pub fn remove_tree(top: &Path) -> io::Result<()> {
    let top_dir = match take_dir(CWD, top) {
        Ok(dir) => dir,
        Err(Errno::NOENT) => return Ok(()),
        Err(e) => return Err(e.into()),
    };
    let mut moved: u64 = 0;
    empty(&top_dir, |name| {
        if !remove_unless_directory(&top_dir, name)? {
            return Ok(());
        }
        // A directory of the top: its files go, its directories move up beside it, and then
        // it is empty and goes too.
        let dir = match take_dir(&top_dir, name) {
            Ok(dir) => dir,
            Err(Errno::NOENT) => return Ok(()),
            Err(e) => return Err(e.into()),
        };
        empty(&dir, |inner| {
            if !remove_unless_directory(&dir, inner)? {
                return Ok(());
            }
            // Up to the top it goes, under a name that is free there.
            loop {
                moved += 1;
                let fresh = CString::new(format!(".remove-{moved}")).expect("no NUL in it");
                match renameat(&dir, inner, &top_dir, &fresh) {
                    Ok(()) | Err(Errno::NOENT) => return Ok(()),
                    Err(Errno::NOTEMPTY) | Err(Errno::EXIST) | Err(Errno::NOTDIR) => continue,
                    Err(e) => return Err(e.into()),
                }
            }
        })?;
        drop(dir);
        match unlinkat(&top_dir, name, AtFlags::REMOVEDIR) {
            Ok(()) | Err(Errno::NOENT) => Ok(()),
            Err(e) => Err(e.into()),
        }
    })?;
    drop(top_dir);
    match std::fs::remove_dir(top) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustix::fs::mkdirat;

    #[test]
    fn a_tree_far_deeper_than_the_limit_of_open_files_is_removed_and_no_link_is_followed() {
        let dir = tempfile::tempdir().unwrap();
        let outside = dir.path().join("outside");
        std::fs::create_dir(&outside).unwrap();
        std::fs::write(outside.join("kept"), b"kept").unwrap();
        let top = dir.path().join("jail");
        std::fs::create_dir_all(top.join("root/run")).unwrap();
        std::fs::write(top.join("root/vmlinux"), b"kernel").unwrap();
        std::fs::write(top.join("mark"), b"").unwrap();
        // A link to a file and one to a directory outside: neither target may be touched.
        std::os::unix::fs::symlink(outside.join("kept"), top.join("root/link")).unwrap();
        std::os::unix::fs::symlink(&outside, top.join("root/run/dirlink")).unwrap();
        // 3000 directories below one another, each with a file beside the next: more levels
        // than a process may have open files by default (1024), and a path of 6000 bytes,
        // beyond the longest a call takes (4096). Not deeper: a process confined by AppArmor,
        // as a build container on a CI runner is, is refused every path that does not fit
        // AppArmor's own buffer (8192 bytes by default) with "File name too long". Where a
        // smaller limit refuses a level, the tree ends there, and must still be deep.
        let mut at = take_dir(CWD, top.join("root/run")).unwrap();
        let mut levels = 0;
        for level in 0..3000 {
            match mkdirat(&at, "d", Mode::from_raw_mode(0o700)) {
                Ok(()) => levels += 1,
                Err(Errno::NAMETOOLONG) => break,
                Err(e) => panic!("cannot make level {level}: {e}"),
            }
            if level % 500 == 0 {
                let file = openat(
                    &at,
                    "f",
                    OFlags::CREATE | OFlags::WRONLY | OFlags::CLOEXEC,
                    Mode::from_raw_mode(0o600),
                )
                .unwrap();
                drop(file);
            }
            at = take_dir(&at, "d").unwrap();
        }
        drop(at);
        assert!(levels > 1500, "only {levels} levels could be made");
        // A directory of more entries than one round reads.
        std::fs::create_dir(top.join("root/many")).unwrap();
        for n in 0..(ROUND + 300) {
            std::fs::write(top.join(format!("root/many/{n}")), b"").unwrap();
        }
        remove_tree(&top).unwrap();
        assert!(!top.exists());
        assert_eq!(std::fs::read(outside.join("kept")).unwrap(), b"kept");
        // One that is gone is no error.
        remove_tree(&top).unwrap();
    }

    #[test]
    fn a_directory_of_many_more_directories_than_one_round_is_removed_and_the_top_may_be_full() {
        let dir = tempfile::tempdir().unwrap();
        let top = dir.path().join("jail");
        std::fs::create_dir_all(top.join("root/wide")).unwrap();
        // Ten rounds of directories in one, each with a file and a directory of its own.
        for n in 0..(ROUND * 10 + 7) {
            let sub = top.join(format!("root/wide/{n}"));
            std::fs::create_dir(&sub).unwrap();
            if n % 16 == 0 {
                std::fs::create_dir(sub.join("deeper")).unwrap();
                std::fs::write(sub.join("deeper/file"), b"").unwrap();
            }
        }
        // What an attempt that was stopped left in the top: more than a round of moved
        // directories, each with something in it.
        for n in 0..(ROUND * 2 + 5) {
            let left = top.join(format!(".remove-{}", n + 3));
            std::fs::create_dir(&left).unwrap();
            std::fs::write(left.join("file"), b"").unwrap();
        }
        remove_tree(&top).unwrap();
        assert!(!top.exists());
    }

    /// As root only (the real tests' run): a sticky directory of another user's, with that
    /// user's file in it, is removed by a thread that is root without `CAP_FOWNER`, as the
    /// helper is under its unit.
    #[test]
    fn a_sticky_directory_of_the_vms_user_is_removed_by_root_without_cap_fowner() {
        use rustix::thread::{CapabilitySet, CapabilitySets, capabilities, set_capabilities};
        if !rustix::process::geteuid().is_root() {
            eprintln!("skipped: needs root");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let top = dir.path().join("jail");
        let user = Some(1_234_567);
        std::fs::create_dir_all(top.join("root/sticky/inner")).unwrap();
        std::fs::write(top.join("root/sticky/file"), b"").unwrap();
        std::fs::write(top.join("root/file"), b"").unwrap();
        for path in [
            "root/sticky/inner",
            "root/sticky/file",
            "root/sticky",
            "root/file",
            "root",
        ] {
            std::os::unix::fs::chown(top.join(path), user, user).unwrap();
        }
        for path in ["root/sticky", "root"] {
            std::fs::set_permissions(
                top.join(path),
                std::os::unix::fs::PermissionsExt::from_mode(0o1700),
            )
            .unwrap();
        }
        let result = std::thread::spawn(move || {
            let mut sets: CapabilitySets = capabilities(None).unwrap();
            sets.effective.remove(CapabilitySet::FOWNER);
            sets.permitted.remove(CapabilitySet::FOWNER);
            set_capabilities(None, sets).unwrap();
            // Without the directory's ownership the kernel refuses: the trap is real here.
            let refused = std::fs::remove_file(top.join("root/sticky/file"));
            (
                refused.map_err(|e| e.kind()),
                remove_tree(&top),
                top.exists(),
            )
        })
        .join()
        .unwrap();
        assert_eq!(result.0, Err(io::ErrorKind::PermissionDenied));
        result.1.unwrap();
        assert!(!result.2);
    }

    #[test]
    fn a_small_jail_is_removed_whole() {
        let dir = tempfile::tempdir().unwrap();
        let top = dir.path().join("tfvm-000000000001-0000");
        std::fs::create_dir_all(top.join("root/dev/net")).unwrap();
        std::fs::create_dir_all(top.join("root/run")).unwrap();
        for file in [
            "root/vmlinux",
            "root/vm.json",
            "root/dev/net/tun",
            "root/run/x",
        ] {
            std::fs::write(top.join(file), b"x").unwrap();
        }
        // A directory that already has one of the names a move would take.
        std::fs::create_dir_all(top.join(".remove-1/inner")).unwrap();
        remove_tree(&top).unwrap();
        assert!(!top.exists());
        assert!(dir.path().exists());
    }
}
