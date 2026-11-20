//! Removing a jail's directory, whatever a VM's user left in it.
//!
//! The chroot of a jail belongs to the VM's user, so a process that got out of a VMM can
//! build anything in it before its VM is deleted: a tree thousands of directories deep, or a
//! directory of a million files. A removal that goes down such a tree with one open directory
//! and one stack frame per level ends at the helper's limit of open files, or at the end of
//! its stack, and then the jail is never removed and holds its slot for good.
//!
//! [`remove_tree`] never goes deeper than one level. It moves every directory it meets up to
//! the top, beside the others, under a name of its own, and empties each from there: two
//! directories are open at a time, no path grows with the tree's depth, and no recursion
//! happens. It follows no link: a link is removed, never what it names.

use std::ffi::{CStr, CString};
use std::io;
use std::os::fd::{AsFd, OwnedFd};
use std::path::Path;

use rustix::fs::{AtFlags, CWD, Dir, Mode, OFlags, openat, renameat, unlinkat};
use rustix::io::Errno;

/// How many entries of a directory are read before they are removed: a directory of any size
/// is emptied in rounds of this many, so its names are never all in memory.
const ROUND: usize = 1024;

fn open_dir<Fd: AsFd, P: rustix::path::Arg>(parent: Fd, name: P) -> rustix::io::Result<OwnedFd> {
    openat(
        parent,
        name,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )
}

/// At most [`ROUND`] names of `dir`, without `.` and `..`.
fn some_names(dir: &OwnedFd) -> io::Result<Vec<CString>> {
    let mut names = Vec::new();
    for entry in Dir::read_from(dir)? {
        let entry = entry?;
        let name = entry.file_name();
        if name.to_bytes() != b"." && name.to_bytes() != b".." {
            names.push(name.to_owned());
            if names.len() == ROUND {
                break;
            }
        }
    }
    Ok(names)
}

/// Removes `name` from `dir` when it is no directory; `true` when it is one, and is still there.
fn remove_unless_directory(dir: &OwnedFd, name: &CStr) -> io::Result<bool> {
    match unlinkat(dir, name, AtFlags::empty()) {
        Ok(()) | Err(Errno::NOENT) => Ok(false),
        // Linux says EISDIR for a directory; POSIX allows EPERM.
        Err(Errno::ISDIR) | Err(Errno::PERM) => Ok(true),
        Err(e) => Err(e.into()),
    }
}

/// Removes `top` and everything in it; a `top` that is not there is no error. `top` itself
/// must be a directory that only the caller can write (a jail's own directory is root's):
/// the directories found below it are moved there under the names `.remove-<n>`.
pub fn remove_tree(top: &Path) -> io::Result<()> {
    let top_dir = match open_dir(CWD, top) {
        Ok(dir) => dir,
        Err(Errno::NOENT) => return Ok(()),
        Err(e) => return Err(e.into()),
    };
    // The directories that wait, by their names in `top`.
    let mut waiting: Vec<CString> = Vec::new();
    let mut moved: u64 = 0;
    // `top` itself is read whole: only the caller writes it, so it holds few entries.
    loop {
        let names = some_names(&top_dir)?;
        let mut kept = 0;
        for name in &names {
            if waiting.contains(name) {
                kept += 1;
            } else if remove_unless_directory(&top_dir, name)? {
                waiting.push(name.clone());
                kept += 1;
            }
        }
        if kept == names.len() {
            break;
        }
    }
    while let Some(name) = waiting.pop() {
        let dir = match open_dir(&top_dir, &name) {
            Ok(dir) => dir,
            Err(Errno::NOENT) => continue,
            Err(e) => return Err(e.into()),
        };
        loop {
            let names = some_names(&dir)?;
            if names.is_empty() {
                break;
            }
            for inner in &names {
                if !remove_unless_directory(&dir, inner)? {
                    continue;
                }
                // A directory: up to the top it goes, under a name that is free there.
                loop {
                    moved += 1;
                    let fresh = CString::new(format!(".remove-{moved}")).expect("no NUL in it");
                    match renameat(&dir, inner, &top_dir, &fresh) {
                        Ok(()) => {
                            waiting.push(fresh);
                            break;
                        }
                        Err(Errno::NOENT) => break,
                        Err(Errno::NOTEMPTY) | Err(Errno::EXIST) | Err(Errno::NOTDIR) => continue,
                        Err(e) => return Err(e.into()),
                    }
                }
            }
        }
        drop(dir);
        unlinkat(&top_dir, &name, AtFlags::REMOVEDIR)?;
    }
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
        // 5000 directories below one another, each with a file beside the next: more levels
        // than a process may have open files, and a path far beyond the longest a call takes.
        let mut at = open_dir(CWD, top.join("root/run")).unwrap();
        for level in 0..5000 {
            mkdirat(&at, "d", Mode::from_raw_mode(0o700)).unwrap();
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
            at = open_dir(&at, "d").unwrap();
        }
        drop(at);
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
