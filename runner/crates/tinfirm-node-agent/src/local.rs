//! What both processes need of the local machine: their own uid, the checks of a directory
//! they keep a socket or files in, the check of a socket's peer, and reading one bounded line.

use std::io;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::Path;

use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncReadExt};
use tokio::net::UnixListener;

/// This process's effective uid: the owner of `/proc/self`.
pub fn own_uid() -> io::Result<u32> {
    std::fs::metadata("/proc/self").map(|m| m.uid())
}

/// What the check of a directory reads.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DirFacts {
    /// A directory, and not a link to one.
    pub is_dir: bool,
    pub uid: u32,
    pub mode: u32,
}

impl DirFacts {
    pub fn of(path: &Path) -> io::Result<DirFacts> {
        let meta = std::fs::symlink_metadata(path)?;
        Ok(DirFacts {
            is_dir: meta.file_type().is_dir(),
            uid: meta.uid(),
            mode: meta.mode() & 0o7777,
        })
    }
}

/// A directory that holds a socket: the process's own, which nobody else can write and others
/// cannot enter. Its group (the one peer's) may enter it: `0750` or `0700`.
pub fn check_socket_dir(facts: &DirFacts, owner: u32) -> Result<(), String> {
    if !facts.is_dir {
        return Err("is not a directory".into());
    }
    if facts.uid != owner {
        return Err(format!("belongs to uid {}, not {owner}", facts.uid));
    }
    if facts.mode & 0o027 != 0 {
        return Err(format!(
            "has mode {:04o}: its group may not write and others may not enter (0750 or 0700)",
            facts.mode
        ));
    }
    Ok(())
}

/// A directory of the process's own files: its own, `0700`.
pub fn check_private_dir(facts: &DirFacts, owner: u32) -> Result<(), String> {
    if !facts.is_dir {
        return Err("is not a directory".into());
    }
    if facts.uid != owner {
        return Err(format!("belongs to uid {}, not {owner}", facts.uid));
    }
    if facts.mode & 0o077 != 0 {
        return Err(format!("has mode {:04o}, not 0700", facts.mode));
    }
    Ok(())
}

/// Whether a socket's peer is the one user that may connect. A peer whose credentials the
/// kernel did not give is refused.
pub fn peer_allowed(peer_uid: Option<u32>, expected: u32) -> bool {
    peer_uid == Some(expected)
}

/// Binds the socket `path` in a directory that [`check_socket_dir`] accepts. A socket left by
/// an earlier process is removed first; any other file there is not. The socket's mode lets
/// anyone who can enter the directory connect; the directory and the peer check decide who.
pub fn bind(path: &Path, owner: u32) -> anyhow::Result<UnixListener> {
    use anyhow::{Context, bail};
    let dir = path
        .parent()
        .context("the socket's path has no directory")?;
    let facts = DirFacts::of(dir)
        .with_context(|| format!("cannot read the directory {}", dir.display()))?;
    if let Err(problem) = check_socket_dir(&facts, owner) {
        bail!("the socket's directory {} {problem}", dir.display());
    }
    match std::fs::symlink_metadata(path) {
        Ok(meta) if std::os::unix::fs::FileTypeExt::is_socket(&meta.file_type()) => {
            std::fs::remove_file(path)
                .with_context(|| format!("cannot remove the old socket {}", path.display()))?;
        }
        Ok(_) => bail!("{} exists and is not a socket", path.display()),
        Err(e) if e.kind() == io::ErrorKind::NotFound => {}
        Err(e) => return Err(e).with_context(|| format!("cannot read {}", path.display())),
    }
    let listener =
        UnixListener::bind(path).with_context(|| format!("cannot listen on {}", path.display()))?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o666))
        .with_context(|| format!("cannot set the mode of {}", path.display()))?;
    Ok(listener)
}

/// Why a line was not read.
#[derive(Debug)]
pub enum LineError {
    /// No newline came within the bound; the stream is useless after it.
    TooLong,
    Io(io::Error),
}

/// Reads one line of at most `max` bytes, the newline excluded; `None` at the end of the input.
pub async fn read_line<R: AsyncBufRead + Unpin>(
    reader: &mut R,
    max: usize,
) -> Result<Option<Vec<u8>>, LineError> {
    let mut line = Vec::new();
    let read = (&mut *reader)
        .take(max as u64 + 1)
        .read_until(b'\n', &mut line)
        .await
        .map_err(LineError::Io)?;
    if read == 0 {
        return Ok(None);
    }
    if line.last() == Some(&b'\n') {
        line.pop();
        if line.last() == Some(&b'\r') {
            line.pop();
        }
        return Ok(Some(line));
    }
    if line.len() > max {
        return Err(LineError::TooLong);
    }
    // The input ended without a newline: the last line.
    Ok(Some(line))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_socket_directory_is_the_owners_and_closed_to_others() {
        let good = |mode| DirFacts {
            is_dir: true,
            uid: 0,
            mode,
        };
        assert!(check_socket_dir(&good(0o750), 0).is_ok());
        assert!(check_socket_dir(&good(0o700), 0).is_ok());
        assert!(check_socket_dir(&good(0o710), 0).is_ok());
        for mode in [0o755, 0o770, 0o777, 0o751, 0o752] {
            assert!(check_socket_dir(&good(mode), 0).is_err(), "{mode:o}");
        }
        assert!(check_socket_dir(&good(0o750), 991).is_err());
        assert!(
            check_socket_dir(
                &DirFacts {
                    is_dir: false,
                    ..good(0o700)
                },
                0
            )
            .is_err()
        );
        assert!(check_private_dir(&good(0o700), 0).is_ok());
        assert!(check_private_dir(&good(0o750), 0).is_err());
    }

    #[test]
    fn only_the_one_uid_is_a_peer() {
        assert!(peer_allowed(Some(991), 991));
        assert!(!peer_allowed(Some(0), 991));
        assert!(!peer_allowed(Some(992), 991));
        assert!(!peer_allowed(None, 991));
    }

    #[tokio::test]
    async fn lines_are_bounded() {
        let mut input: &[u8] = b"short\nexactly10\r\n12345678901\nend";
        let mut reader = tokio::io::BufReader::new(&mut input);
        assert_eq!(read_line(&mut reader, 10).await.unwrap().unwrap(), b"short");
        assert_eq!(
            read_line(&mut reader, 10).await.unwrap().unwrap(),
            b"exactly10"
        );
        assert!(matches!(
            read_line(&mut reader, 10).await,
            Err(LineError::TooLong)
        ));
        let mut input: &[u8] = b"end";
        let mut reader = tokio::io::BufReader::new(&mut input);
        assert_eq!(read_line(&mut reader, 10).await.unwrap().unwrap(), b"end");
        assert!(read_line(&mut reader, 10).await.unwrap().is_none());
    }

    #[test]
    fn a_socket_is_bound_only_in_a_closed_directory_and_never_over_another_file() {
        let dir = tempfile::tempdir().unwrap();
        let uid = own_uid().unwrap();
        let path = dir.path().join("s.sock");
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let _guard = runtime.enter();
        std::fs::set_permissions(dir.path(), std::fs::Permissions::from_mode(0o755)).unwrap();
        assert!(bind(&path, uid).is_err());
        std::fs::set_permissions(dir.path(), std::fs::Permissions::from_mode(0o750)).unwrap();
        assert!(bind(&path, uid.wrapping_add(1)).is_err());
        let first = bind(&path, uid).unwrap();
        drop(first);
        // The old socket goes; a regular file at the name stays.
        let again = bind(&path, uid).unwrap();
        drop(again);
        std::fs::remove_file(&path).unwrap();
        std::fs::write(&path, b"x").unwrap();
        assert!(bind(&path, uid).is_err());
        assert_eq!(std::fs::read(&path).unwrap(), b"x");
    }
}
