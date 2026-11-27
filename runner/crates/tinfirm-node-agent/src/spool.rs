//! A VM's user data: the one free-form thing that reaches the helper. The listener writes it to
//! a file of its own in its spool directory; the helper opens that one name, derived from the
//! VM's id, without following a link, and checks on the open file what it is before it reads
//! a byte. It never parses the bytes and never uses them as a name.

use std::fs::{File, Metadata};
use std::io::Read;
use std::os::unix::fs::{FileTypeExt, MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};

use crate::id::VmId;
use crate::local::{self, DirFacts};

/// The most user data a VM gets, in bytes: DigitalOcean's limit (ADR-0096), which the runner
/// host's user data keeps.
pub const MAX_USER_DATA: u64 = 64 * 1024;

/// The file of `id`'s user data in the spool directory: `<spool>/<id>.userdata`.
pub fn user_data_path(spool: &Path, id: &VmId) -> PathBuf {
    spool.join(format!("{id}.userdata"))
}

/// What kind of file a name is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Regular,
    Directory,
    Symlink,
    Fifo,
    Socket,
    Device,
}

/// What the check reads of a file: of the open file's `fstat` in the helper, or made up in a
/// test where one user cannot make the case.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Facts {
    pub kind: Kind,
    pub uid: u32,
    pub links: u64,
    pub len: u64,
}

impl Facts {
    pub fn of(meta: &Metadata) -> Facts {
        let t = meta.file_type();
        let kind = if t.is_symlink() {
            Kind::Symlink
        } else if t.is_dir() {
            Kind::Directory
        } else if t.is_fifo() {
            Kind::Fifo
        } else if t.is_socket() {
            Kind::Socket
        } else if t.is_file() {
            Kind::Regular
        } else {
            Kind::Device
        };
        Facts {
            kind,
            uid: meta.uid(),
            links: meta.nlink(),
            len: meta.len(),
        }
    }
}

/// Why user data was refused. It never holds a byte of the data.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Refused {
    Missing,
    /// The name is a symbolic link (the open did not follow it).
    Link,
    NotRegular(Kind),
    Owner(u32),
    Links(u64),
    Empty,
    TooLarge,
    /// The file changed between its `fstat` and its read.
    Changed,
    Io(String),
    /// The spool directory, or its parent, is not as it must be.
    Spool(String),
}

impl std::fmt::Display for Refused {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Refused::Missing => write!(f, "the user data file is missing"),
            Refused::Link => write!(f, "the user data file is a symbolic link"),
            Refused::NotRegular(kind) => {
                write!(f, "the user data file is not a regular file ({kind:?})")
            }
            Refused::Owner(uid) => write!(
                f,
                "the user data file belongs to uid {uid}, not to the listener"
            ),
            Refused::Links(n) => write!(f, "the user data file has {n} names, not one"),
            Refused::Empty => write!(f, "the user data file is empty"),
            Refused::TooLarge => write!(f, "the user data file is over {MAX_USER_DATA} bytes"),
            Refused::Changed => write!(f, "the user data file changed while it was read"),
            Refused::Io(e) => write!(f, "cannot read the user data file: {e}"),
            Refused::Spool(why) => f.write_str(why),
        }
    }
}

/// The check of the spool and its parent: the spool a directory, not a link, the listener's,
/// 0700; its parent a directory, not a link, the helper's own (root's), that neither its group
/// nor others can write. Only the parent's owner can then rename the spool's entry, so the
/// directory the helper opens a name in is the listener's spool and nothing swapped in.
pub fn check_spool_facts(
    spool: &DirFacts,
    parent: &DirFacts,
    listener_uid: u32,
    parent_owner: u32,
) -> Result<(), String> {
    local::check_private_dir(spool, listener_uid).map_err(|p| format!("the spool {p}"))?;
    if !parent.is_dir {
        return Err("the spool's parent is not a directory".into());
    }
    if parent.uid != parent_owner {
        return Err(format!(
            "the spool's parent belongs to uid {}, not to the helper's user {parent_owner}",
            parent.uid
        ));
    }
    if parent.mode & 0o022 != 0 {
        return Err(format!(
            "the spool's parent has mode {:04o}: its group and others may not write it",
            parent.mode
        ));
    }
    Ok(())
}

/// [`check_spool_facts`] on the spool `spool`, read without following a link.
pub fn check_spool(spool: &Path, listener_uid: u32, parent_owner: u32) -> Result<(), Refused> {
    let parent = spool
        .parent()
        .ok_or_else(|| Refused::Spool("the spool has no parent".into()))?;
    let facts = |path: &Path| {
        DirFacts::of(path)
            .map_err(|e| Refused::Spool(format!("cannot read {}: {}", path.display(), e.kind())))
    };
    check_spool_facts(&facts(spool)?, &facts(parent)?, listener_uid, parent_owner)
        .map_err(|why| Refused::Spool(format!("{} ({})", why, spool.display())))
}

/// The check on an open file's facts: a regular file of the listener's, with one name, of 1 to
/// [`MAX_USER_DATA`] bytes.
pub fn check(facts: &Facts, listener_uid: u32) -> Result<(), Refused> {
    match facts.kind {
        Kind::Regular => {}
        Kind::Symlink => return Err(Refused::Link),
        other => return Err(Refused::NotRegular(other)),
    }
    if facts.uid != listener_uid {
        return Err(Refused::Owner(facts.uid));
    }
    if facts.links != 1 {
        return Err(Refused::Links(facts.links));
    }
    if facts.len == 0 {
        return Err(Refused::Empty);
    }
    if facts.len > MAX_USER_DATA {
        return Err(Refused::TooLarge);
    }
    Ok(())
}

/// Checks the spool and its parent ([`check_spool`]), opens `id`'s user data without following
/// a link and without blocking on a FIFO, checks the open file, and reads its bytes.
pub fn read(
    spool: &Path,
    id: &VmId,
    listener_uid: u32,
    parent_owner: u32,
) -> Result<Wiped, Refused> {
    check_spool(spool, listener_uid, parent_owner)?;
    let path = user_data_path(spool, id);
    let file = File::options()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_NOCTTY)
        .open(&path)
        .map_err(|e| match e.raw_os_error() {
            Some(libc::ENOENT) => Refused::Missing,
            Some(libc::ELOOP) => Refused::Link,
            _ => Refused::Io(e.kind().to_string()),
        })?;
    let facts = Facts::of(
        &file
            .metadata()
            .map_err(|e| Refused::Io(e.kind().to_string()))?,
    );
    check(&facts, listener_uid)?;
    // Room for the most that is read, so that a file that grows after its size was checked
    // cannot make the buffer move; and overwritten on every way out of here.
    let mut bytes = Wiped(Vec::with_capacity(MAX_USER_DATA as usize + 1));
    file.take(MAX_USER_DATA + 1)
        .read_to_end(&mut bytes.0)
        .map_err(|e| Refused::Io(e.kind().to_string()))?;
    if bytes.len() as u64 > MAX_USER_DATA {
        return Err(Refused::TooLarge);
    }
    if bytes.len() as u64 != facts.len {
        return Err(Refused::Changed);
    }
    Ok(bytes)
}

/// Bytes that hold user data, or a disk made of it: overwritten when they are dropped
/// ([`wipe`]), on every path, the refusals and the errors included. The buffer is as large as
/// it will ever be from the first, so it never moves and leaves no copy behind.
pub struct Wiped(Vec<u8>);

impl Wiped {
    pub fn new(bytes: Vec<u8>) -> Wiped {
        Wiped(bytes)
    }
}

impl std::ops::Deref for Wiped {
    type Target = [u8];
    fn deref(&self) -> &[u8] {
        &self.0
    }
}

impl Drop for Wiped {
    fn drop(&mut self) {
        wipe(&mut self.0);
    }
}

impl std::fmt::Debug for Wiped {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Wiped({} bytes)", self.0.len())
    }
}

/// Overwrites bytes that held user data, before their memory is given back: a ticket should
/// not stay readable in the helper's memory after its VM has it. The helper calls this on the
/// bytes it read and on the disk it made from them. It is a best effort in safe Rust: the
/// compiler is told that the zeros are read (`black_box`), which keeps the store in practice.
pub fn wipe(bytes: &mut [u8]) {
    bytes.fill(0);
    std::hint::black_box(bytes);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wiped_bytes_are_zero() {
        let mut bytes = b"a ticket".to_vec();
        wipe(&mut bytes);
        assert_eq!(bytes, [0; 8]);
    }

    const LISTENER: u32 = 991;

    fn facts() -> Facts {
        Facts {
            kind: Kind::Regular,
            uid: LISTENER,
            links: 1,
            len: 100,
        }
    }

    #[test]
    fn the_check_takes_only_a_regular_file_of_the_listener_with_one_name_and_a_bounded_size() {
        assert_eq!(check(&facts(), LISTENER), Ok(()));
        assert_eq!(
            check(
                &Facts {
                    len: MAX_USER_DATA,
                    ..facts()
                },
                LISTENER
            ),
            Ok(())
        );
        let cases = [
            (
                Facts {
                    kind: Kind::Symlink,
                    ..facts()
                },
                Refused::Link,
            ),
            (
                Facts {
                    kind: Kind::Directory,
                    ..facts()
                },
                Refused::NotRegular(Kind::Directory),
            ),
            (
                Facts {
                    kind: Kind::Fifo,
                    ..facts()
                },
                Refused::NotRegular(Kind::Fifo),
            ),
            (
                Facts {
                    kind: Kind::Socket,
                    ..facts()
                },
                Refused::NotRegular(Kind::Socket),
            ),
            (
                Facts {
                    kind: Kind::Device,
                    ..facts()
                },
                Refused::NotRegular(Kind::Device),
            ),
            (Facts { uid: 0, ..facts() }, Refused::Owner(0)),
            (
                Facts {
                    uid: 4242,
                    ..facts()
                },
                Refused::Owner(4242),
            ),
            (
                Facts {
                    links: 2,
                    ..facts()
                },
                Refused::Links(2),
            ),
            (
                Facts {
                    links: 0,
                    ..facts()
                },
                Refused::Links(0),
            ),
            (Facts { len: 0, ..facts() }, Refused::Empty),
            (
                Facts {
                    len: MAX_USER_DATA + 1,
                    ..facts()
                },
                Refused::TooLarge,
            ),
        ];
        for (facts, refused) in cases {
            assert_eq!(check(&facts, LISTENER), Err(refused), "{facts:?}");
        }
    }

    fn own_uid() -> u32 {
        std::fs::metadata("/proc/self").unwrap().uid()
    }

    /// A spool as the role makes it: a 0700 directory in a 0755 parent of this test's user.
    struct Spool {
        root: tempfile::TempDir,
        dir: std::path::PathBuf,
    }

    impl Spool {
        fn path(&self) -> &Path {
            &self.dir
        }
    }

    fn spool_dir() -> Spool {
        use std::os::unix::fs::PermissionsExt;
        let root = tempfile::tempdir().unwrap();
        std::fs::set_permissions(root.path(), std::fs::Permissions::from_mode(0o755)).unwrap();
        let dir = root.path().join("spool");
        std::fs::create_dir(&dir).unwrap();
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700)).unwrap();
        Spool { root, dir }
    }

    /// `read` with the helper's user this test's.
    fn read(spool: &Path, id: &VmId, listener_uid: u32) -> Result<Vec<u8>, Refused> {
        super::read(spool, id, listener_uid, own_uid()).map(|bytes| bytes.to_vec())
    }

    fn id() -> VmId {
        VmId::parse(b"vm-00000000000000000000000000000001").unwrap()
    }

    #[test]
    fn a_good_file_is_read_whole() {
        let dir = spool_dir();
        std::fs::write(user_data_path(dir.path(), &id()), b"{\"tinfirm\":1}").unwrap();
        assert_eq!(
            read(dir.path(), &id(), own_uid()).unwrap(),
            b"{\"tinfirm\":1}"
        );
        // Another uid than the file's is refused: here by the spool's own check, which comes
        // first; the open file's owner has its own test below.
        let other = own_uid().wrapping_add(1);
        assert!(matches!(
            read(dir.path(), &id(), other),
            Err(Refused::Spool(_))
        ));
    }

    #[test]
    fn a_missing_file_a_link_a_directory_a_fifo_two_names_and_too_many_bytes_are_refused() {
        let dir = spool_dir();
        let path = user_data_path(dir.path(), &id());
        let uid = own_uid();
        assert_eq!(read(dir.path(), &id(), uid), Err(Refused::Missing));

        // A symbolic link to a good file of the listener's.
        let target = dir.path().join("target");
        std::fs::write(&target, b"{}").unwrap();
        std::os::unix::fs::symlink(&target, &path).unwrap();
        assert_eq!(read(dir.path(), &id(), uid), Err(Refused::Link));
        std::fs::remove_file(&path).unwrap();

        // A dangling link, and a link to a directory.
        std::os::unix::fs::symlink("/nonexistent", &path).unwrap();
        assert_eq!(read(dir.path(), &id(), uid), Err(Refused::Link));
        std::fs::remove_file(&path).unwrap();
        std::os::unix::fs::symlink("/etc", &path).unwrap();
        assert_eq!(read(dir.path(), &id(), uid), Err(Refused::Link));
        std::fs::remove_file(&path).unwrap();

        // A directory.
        std::fs::create_dir(&path).unwrap();
        assert_eq!(
            read(dir.path(), &id(), uid),
            Err(Refused::NotRegular(Kind::Directory))
        );
        std::fs::remove_dir(&path).unwrap();

        // A FIFO: the open does not block, and the check refuses it.
        let made = std::process::Command::new("mkfifo").arg(&path).status();
        if made.is_ok_and(|s| s.success()) {
            assert_eq!(
                read(dir.path(), &id(), uid),
                Err(Refused::NotRegular(Kind::Fifo))
            );
            std::fs::remove_file(&path).unwrap();
        } else {
            eprintln!("no mkfifo here: the FIFO case is covered by the check's own test");
        }

        // A file with a second name.
        std::fs::write(&path, b"{}").unwrap();
        let second = dir.path().join("second");
        std::fs::hard_link(&path, &second).unwrap();
        assert_eq!(read(dir.path(), &id(), uid), Err(Refused::Links(2)));
        std::fs::remove_file(&second).unwrap();
        assert!(read(dir.path(), &id(), uid).is_ok());

        // Too many bytes, and none.
        std::fs::write(&path, vec![b'x'; MAX_USER_DATA as usize + 1]).unwrap();
        assert_eq!(read(dir.path(), &id(), uid), Err(Refused::TooLarge));
        std::fs::write(&path, b"").unwrap();
        assert_eq!(read(dir.path(), &id(), uid), Err(Refused::Empty));
    }

    #[test]
    fn a_file_of_another_owner_is_refused() {
        let dir = spool_dir();
        let path = user_data_path(dir.path(), &id());
        std::fs::write(&path, b"{}").unwrap();
        // Root can give the file away; another user cannot, and the check's own test covers it.
        if std::os::unix::fs::chown(&path, Some(4242), None).is_ok() {
            assert_eq!(
                read(dir.path(), &id(), own_uid()),
                Err(Refused::Owner(4242))
            );
            // The spool of the listener 4242, with the file of its own.
            std::os::unix::fs::chown(dir.path(), Some(4242), None).unwrap();
            assert!(read(dir.path(), &id(), 4242).is_ok());
        } else {
            eprintln!("not root: another owner is covered by the check's own test");
        }
    }

    #[test]
    fn the_spool_must_be_the_listeners_closed_directory_in_a_parent_of_the_helpers() {
        let good = |uid, mode| DirFacts {
            is_dir: true,
            uid,
            mode,
        };
        let check = |spool, parent| check_spool_facts(&spool, &parent, LISTENER, 0);
        assert!(check(good(LISTENER, 0o700), good(0, 0o755)).is_ok());
        let refused = [
            // A link (symlink_metadata sees no directory), another owner, open to others.
            (
                DirFacts {
                    is_dir: false,
                    ..good(LISTENER, 0o700)
                },
                good(0, 0o755),
            ),
            (good(4242, 0o700), good(0, 0o755)),
            (good(0, 0o700), good(0, 0o755)),
            (good(LISTENER, 0o750), good(0, 0o755)),
            // A parent the listener owns, or that its group or others may write.
            (good(LISTENER, 0o700), good(LISTENER, 0o755)),
            (good(LISTENER, 0o700), good(0, 0o775)),
            (good(LISTENER, 0o700), good(0, 0o757)),
            (good(LISTENER, 0o700), good(0, 0o1777)),
            (
                good(LISTENER, 0o700),
                DirFacts {
                    is_dir: false,
                    ..good(0, 0o755)
                },
            ),
        ];
        for (spool, parent) in refused {
            assert!(check(spool, parent).is_err(), "{spool:?} {parent:?}");
        }
    }

    #[test]
    fn a_spool_that_is_a_link_or_of_another_owner_or_in_a_writable_parent_is_refused() {
        use std::os::unix::fs::PermissionsExt;
        let uid = own_uid();
        let dir = spool_dir();
        std::fs::write(user_data_path(dir.path(), &id()), b"{}").unwrap();
        assert!(read(dir.path(), &id(), uid).is_ok());

        // The spool is a link to a good spool: refused before the file is opened.
        let link = dir.root.path().join("link");
        std::os::unix::fs::symlink(dir.path(), &link).unwrap();
        assert!(matches!(read(&link, &id(), uid), Err(Refused::Spool(_))));
        assert!(matches!(
            check_spool(&link, uid, uid),
            Err(Refused::Spool(_))
        ));

        // The parent is group-writable.
        std::fs::set_permissions(dir.root.path(), std::fs::Permissions::from_mode(0o775)).unwrap();
        assert!(matches!(
            read(dir.path(), &id(), uid),
            Err(Refused::Spool(_))
        ));
        std::fs::set_permissions(dir.root.path(), std::fs::Permissions::from_mode(0o755)).unwrap();
        assert!(read(dir.path(), &id(), uid).is_ok());

        // The spool belongs to another user: root can make the case; the facts' test covers it
        // otherwise.
        if std::os::unix::fs::chown(dir.path(), Some(4242), None).is_ok() {
            assert!(matches!(
                read(dir.path(), &id(), uid),
                Err(Refused::Spool(_))
            ));
        }
    }
}
