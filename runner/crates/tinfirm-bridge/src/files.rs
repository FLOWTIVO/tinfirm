//! Files over the bridge (ADR-0095): directories travel as tar archives on a stream that the
//! host opens ([`crate::tunnel::Streams`], numbers from [`crate::tunnel::HOST_STREAMS`]).
//!
//! - [`pack`] never follows a symbolic link: it packs the link itself, and leaves out sockets,
//!   pipes, devices and what it may not read.
//! - In the sandbox, an archive of the host's is unpacked as it is ([`unpack_trusted`]).
//! - On the host, an archive of the sandbox's is unpacked by [`unpack_untrusted`]: only
//!   directories and regular files, at relative paths without `..`, into a directory that is
//!   new or empty, with caps on the files and the bytes. Links and the rest are left out, by
//!   name. [`read_one`] reads the one regular file of an archive.

use std::fs::OpenOptions;
use std::io::{self, ErrorKind, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use tokio::io::DuplexStream;
use tokio_util::io::SyncIoBridge;

use crate::tunnel::{Opened, Streams};

/// The buffer between a stream and the archive's reader or writer.
const PIPE: usize = 64 * 1024;

/// What [`unpack_untrusted`] takes at most: more files or bytes fail it; a larger file is
/// left out.
#[derive(Debug, Clone, Copy)]
pub struct Limits {
    /// The most regular files, and the most other entries: directories and the entries left
    /// out (links, devices).
    pub max_files: usize,
    pub max_bytes: u64,
    pub max_file_bytes: u64,
}

/// What [`unpack_untrusted`] wrote, and the entries it left out: links, devices, and files
/// larger than [`Limits::max_file_bytes`].
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Unpacked {
    pub files: usize,
    pub bytes: u64,
    pub left_out: Vec<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum UnpackError {
    #[error("the archive has more than {0} files")]
    TooManyFiles(usize),
    #[error("the archive has more than {0} directories, links and other entries")]
    TooManyEntries(usize),
    #[error("the archive holds more than {0} bytes")]
    TooLarge(u64),
    #[error("`{0}` is not a relative path below the directory")]
    BadPath(String),
    #[error("`{0}` is in the archive twice, or under a file")]
    Clash(String),
    #[error(transparent)]
    Io(#[from] io::Error),
}

/// A path below a directory: relative, and only names (`.` is dropped). `None` otherwise.
pub fn relative(path: &Path) -> Option<PathBuf> {
    if path.is_absolute() {
        return None;
    }
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Normal(part) => out.push(part),
            Component::CurDir => {}
            _ => return None,
        }
    }
    (!out.as_os_str().is_empty()).then_some(out)
}

/// `true` when every ancestor of `rel` below `dir` (`dir/a` and `dir/a/b` for `a/b/c`) is a
/// directory and not a link, by `lstat`. A missing ancestor is `false` too. `rel` itself is not
/// looked at.
///
/// The ancestors of a session folder (`.codex`, `.local/share`) belong to the agent: a link at
/// `~/.codex` would otherwise make the host pack whatever it points at (L25 of the security
/// review of 2026-10-27).
pub fn ancestors_are_plain_dirs(dir: &Path, rel: &Path) -> bool {
    let Some(parent) = rel.parent() else {
        return true;
    };
    let mut path = dir.to_path_buf();
    for component in parent.components() {
        match component {
            Component::Normal(name) => path.push(name),
            Component::CurDir => continue,
            _ => return false,
        }
        match std::fs::symlink_metadata(&path) {
            Ok(meta) if meta.file_type().is_dir() => {}
            _ => return false,
        }
    }
    true
}

/// Packs `paths` of `dir` (each relative to it; all of `dir` when empty) as a tar archive
/// into `out`, without `exclude` (relative paths, with everything below them). A path that
/// does not exist is left out, and so is one with an ancestor below `dir` that is not a plain
/// directory ([`ancestors_are_plain_dirs`]): nothing is packed through a link.
pub fn pack<W: Write>(dir: &Path, paths: &[String], exclude: &[String], out: W) -> io::Result<W> {
    let exclude: Vec<PathBuf> = exclude
        .iter()
        .filter_map(|e| relative(Path::new(e)))
        .collect();
    let roots: Vec<PathBuf> = if paths.is_empty() {
        match std::fs::read_dir(dir) {
            Ok(entries) => {
                let mut names: Vec<PathBuf> = entries
                    .filter_map(Result::ok)
                    .map(|e| PathBuf::from(e.file_name()))
                    .collect();
                names.sort();
                names
            }
            Err(e) if e.kind() == ErrorKind::NotFound => Vec::new(),
            Err(e) => return Err(e),
        }
    } else {
        paths
            .iter()
            .map(|p| {
                relative(Path::new(p)).ok_or_else(|| {
                    io::Error::new(
                        ErrorKind::InvalidInput,
                        format!("`{p}` is not a relative path"),
                    )
                })
            })
            .collect::<io::Result<_>>()?
    };
    let mut builder = tar::Builder::new(out);
    builder.follow_symlinks(false);
    for rel in roots {
        if !ancestors_are_plain_dirs(dir, &rel) {
            continue;
        }
        add(&mut builder, dir, &rel, &exclude)?;
    }
    builder.into_inner()
}

fn add<W: Write>(
    builder: &mut tar::Builder<W>,
    dir: &Path,
    rel: &Path,
    exclude: &[PathBuf],
) -> io::Result<()> {
    if exclude.iter().any(|e| rel.starts_with(e)) {
        return Ok(());
    }
    let full = dir.join(rel);
    let meta = match std::fs::symlink_metadata(&full) {
        Ok(meta) => meta,
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e),
    };
    let kind = meta.file_type();
    if kind.is_dir() {
        builder.append_dir(rel, &full)?;
        let entries = match std::fs::read_dir(&full) {
            Ok(entries) => entries,
            Err(e) if e.kind() == ErrorKind::PermissionDenied => return Ok(()),
            Err(e) => return Err(e),
        };
        let mut children: Vec<_> = entries
            .filter_map(Result::ok)
            .map(|e| e.file_name())
            .collect();
        children.sort();
        for child in children {
            add(builder, dir, &rel.join(child), exclude)?;
        }
    } else if kind.is_file() || kind.is_symlink() {
        // A file it may not read is left out: the header goes in only once it is open.
        match builder.append_path_with_name(&full, rel) {
            Err(e) if e.kind() == ErrorKind::PermissionDenied => {}
            other => other?,
        }
    }
    Ok(())
}

/// Unpacks an archive of the host's into `dir`, which is created: as it is, permissions
/// included, over what is there.
pub fn unpack_trusted<R: Read>(input: R, dir: &Path) -> io::Result<()> {
    std::fs::create_dir_all(dir)?;
    let mut archive = tar::Archive::new(input);
    archive.set_preserve_permissions(true);
    archive.set_preserve_mtime(true);
    archive.set_overwrite(true);
    archive.unpack(dir)
}

/// Unpacks an archive of the sandbox's into `dir` (new or empty): directories and regular
/// files only, within `limits`.
pub fn unpack_untrusted<R: Read>(
    input: R,
    dir: &Path,
    limits: &Limits,
) -> Result<Unpacked, UnpackError> {
    std::fs::create_dir_all(dir)?;
    let mut archive = tar::Archive::new(input);
    let mut done = Unpacked::default();
    let mut others = 0usize;
    for entry in archive.entries()? {
        let mut entry = entry?;
        let regular = matches!(
            entry.header().entry_type(),
            tar::EntryType::Regular | tar::EntryType::Continuous
        );
        // Directories and what is left out are bounded too: an archive of nothing but
        // directories would use up the host's inodes, of links its memory.
        if !regular {
            others += 1;
            if others > limits.max_files {
                return Err(UnpackError::TooManyEntries(limits.max_files));
            }
        }
        let raw = entry.path()?.into_owned();
        let name = raw.to_string_lossy().into_owned();
        let rel = relative(&raw).ok_or_else(|| UnpackError::BadPath(name.clone()))?;
        match entry.header().entry_type() {
            tar::EntryType::Directory => create_dirs(dir, &rel, &name)?,
            tar::EntryType::Regular | tar::EntryType::Continuous => {
                if done.files >= limits.max_files {
                    return Err(UnpackError::TooManyFiles(limits.max_files));
                }
                let size = entry.header().size()?;
                if size > limits.max_file_bytes {
                    // The reader skips its bytes on the way to the next entry.
                    done.left_out.push(name);
                    continue;
                }
                if done.bytes.saturating_add(size) > limits.max_bytes {
                    return Err(UnpackError::TooLarge(limits.max_bytes));
                }
                if let Some(parent) = rel.parent() {
                    create_dirs(dir, parent, &name)?;
                }
                let executable = entry.header().mode().is_ok_and(|m| m & 0o111 != 0);
                let mut file = match OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(dir.join(&rel))
                {
                    Ok(file) => file,
                    Err(e) if e.kind() == ErrorKind::AlreadyExists => {
                        return Err(UnpackError::Clash(name));
                    }
                    Err(e) => return Err(e.into()),
                };
                let copied = io::copy(&mut (&mut entry).take(size), &mut file)?;
                if executable {
                    use std::os::unix::fs::PermissionsExt;
                    file.set_permissions(std::fs::Permissions::from_mode(0o755))?;
                }
                done.files += 1;
                done.bytes += copied;
            }
            _ => done.left_out.push(name),
        }
    }
    Ok(done)
}

/// Creates `rel` below `dir`, one name at a time; something there that is not a directory
/// (a file, or a link that [`unpack_untrusted`] never makes) is a clash.
fn create_dirs(dir: &Path, rel: &Path, name: &str) -> Result<(), UnpackError> {
    let mut path = dir.to_path_buf();
    for component in rel.components() {
        path.push(component);
        match std::fs::symlink_metadata(&path) {
            Ok(meta) if meta.is_dir() => {}
            Ok(_) => return Err(UnpackError::Clash(name.to_string())),
            Err(e) if e.kind() == ErrorKind::NotFound => std::fs::create_dir(&path)?,
            Err(e) => return Err(e.into()),
        }
    }
    Ok(())
}

/// The one file of an archive that [`pack`] made of one path.
#[derive(Debug, PartialEq, Eq)]
pub enum ReadOne {
    /// The archive is empty: there is no such path.
    Missing,
    /// A directory, a link or something else that is not a regular file.
    NotRegular,
    TooLarge,
    File(Vec<u8>),
}

/// Reads the first entry of an archive: a regular file of at most `max` bytes.
pub fn read_one<R: Read>(input: R, max: u64) -> io::Result<ReadOne> {
    let mut archive = tar::Archive::new(input);
    let mut entries = archive.entries()?;
    let Some(entry) = entries.next() else {
        return Ok(ReadOne::Missing);
    };
    let mut entry = entry?;
    if !matches!(
        entry.header().entry_type(),
        tar::EntryType::Regular | tar::EntryType::Continuous
    ) {
        return Ok(ReadOne::NotRegular);
    }
    let size = entry.header().size()?;
    if size > max {
        return Ok(ReadOne::TooLarge);
    }
    let mut content = Vec::with_capacity(usize::try_from(size).unwrap_or(0));
    entry.read_to_end(&mut content)?;
    Ok(ReadOne::File(content))
}

/// Feeds what the peer sends on `opened` to `consume`, on a blocking thread, and returns its
/// result. The rest of the stream is read to its end, so that a consumer that stops at the end
/// of an archive does not reset the stream.
pub async fn receive<T, E, F>(streams: &Arc<Streams>, opened: Opened, consume: F) -> Result<T, E>
where
    T: Send + 'static,
    E: From<io::Error> + Send + 'static,
    F: FnOnce(&mut dyn Read) -> Result<T, E> + Send + 'static,
{
    let (writer, reader) = tokio::io::duplex(PIPE);
    streams.run_io(opened, tokio::io::empty(), writer);
    let mut reader = SyncIoBridge::new(reader);
    tokio::task::spawn_blocking(move || {
        let result = consume(&mut reader)?;
        io::copy(&mut reader, &mut io::sink())?;
        Ok(result)
    })
    .await
    .map_err(|e| E::from(io::Error::other(e)))?
}

/// Sends what `produce` writes on `opened`, from a blocking thread, and ends the stream when
/// it returns.
pub async fn send<F>(streams: &Arc<Streams>, opened: Opened, produce: F) -> io::Result<()>
where
    F: FnOnce(&mut SyncIoBridge<DuplexStream>) -> io::Result<()> + Send + 'static,
{
    let (writer, reader) = tokio::io::duplex(PIPE);
    streams.run_io(opened, reader, tokio::io::sink());
    let mut writer = SyncIoBridge::new(writer);
    tokio::task::spawn_blocking(move || {
        produce(&mut writer)?;
        writer.flush()
        // Dropping the writer ends the stream.
    })
    .await
    .map_err(io::Error::other)?
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::{PermissionsExt, symlink};

    /// L25 (security review of 2026-10-27): a link among the ancestors of a packed path is
    /// never followed.
    #[test]
    fn pack_follows_no_link_among_the_ancestors() {
        let outside = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(outside.path().join("sessions")).unwrap();
        std::fs::write(outside.path().join("sessions/other.jsonl"), "not yours").unwrap();
        let home = tempfile::tempdir().unwrap();
        symlink(outside.path(), home.path().join(".codex")).unwrap();
        std::fs::create_dir_all(home.path().join(".pi/agent/sessions")).unwrap();
        std::fs::write(home.path().join(".pi/agent/sessions/mine.jsonl"), "mine").unwrap();
        assert!(!ancestors_are_plain_dirs(
            home.path(),
            Path::new(".codex/sessions")
        ));
        assert!(ancestors_are_plain_dirs(
            home.path(),
            Path::new(".pi/agent/sessions")
        ));
        assert!(!ancestors_are_plain_dirs(
            home.path(),
            Path::new(".missing/sessions")
        ));
        assert!(ancestors_are_plain_dirs(home.path(), Path::new("top")));
        let tar = pack(
            home.path(),
            &[".codex/sessions".into(), ".pi/agent/sessions".into()],
            &[],
            Vec::new(),
        )
        .unwrap();
        let text = String::from_utf8_lossy(&tar);
        assert!(text.contains("mine"), "the plain folder is packed");
        assert!(!text.contains("not yours") && !text.contains("other.jsonl"));
    }

    fn limits() -> Limits {
        Limits {
            max_files: 100,
            max_bytes: 1 << 20,
            max_file_bytes: 1 << 19,
        }
    }

    fn tree() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("a/b")).unwrap();
        std::fs::write(root.join("a/one.txt"), "one").unwrap();
        std::fs::write(root.join("a/b/two.txt"), "two").unwrap();
        std::fs::write(root.join("run.sh"), "#!/bin/sh\n").unwrap();
        std::fs::set_permissions(root.join("run.sh"), std::fs::Permissions::from_mode(0o755))
            .unwrap();
        std::fs::create_dir_all(root.join(".cache/npm")).unwrap();
        std::fs::write(root.join(".cache/npm/big"), "cached").unwrap();
        symlink("/etc/passwd", root.join("a/link")).unwrap();
        dir
    }

    #[test]
    fn a_tree_round_trips_without_its_links_and_exclusions() {
        let source = tree();
        let archive = pack(source.path(), &[], &[".cache".into()], Vec::new()).unwrap();
        let dest = tempfile::tempdir().unwrap();
        let out = dest.path().join("out");
        let done = unpack_untrusted(archive.as_slice(), &out, &limits()).unwrap();
        assert_eq!(done.files, 3);
        assert_eq!(done.left_out, ["a/link"]);
        assert_eq!(
            std::fs::read_to_string(out.join("a/b/two.txt")).unwrap(),
            "two"
        );
        assert!(!out.join(".cache").exists());
        assert!(std::fs::symlink_metadata(out.join("a/link")).is_err());
        let mode = std::fs::metadata(out.join("run.sh"))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(mode & 0o111, 0o111);
        // The sandbox's side takes the host's archive as it is, links included.
        let trusted = dest.path().join("trusted");
        let archive = pack(source.path(), &[], &[], Vec::new()).unwrap();
        unpack_trusted(archive.as_slice(), &trusted).unwrap();
        assert_eq!(
            std::fs::read_link(trusted.join("a/link")).unwrap(),
            Path::new("/etc/passwd")
        );
        assert_eq!(
            std::fs::read_to_string(trusted.join(".cache/npm/big")).unwrap(),
            "cached"
        );
    }

    #[test]
    fn named_paths_are_packed_and_missing_ones_left_out() {
        let source = tree();
        let archive = pack(
            source.path(),
            &["a/one.txt".into(), "nothing-here".into()],
            &[],
            Vec::new(),
        )
        .unwrap();
        assert_eq!(
            read_one(archive.as_slice(), 10).unwrap(),
            ReadOne::File(b"one".to_vec())
        );
        let link = pack(source.path(), &["a/link".into()], &[], Vec::new()).unwrap();
        assert_eq!(read_one(link.as_slice(), 10).unwrap(), ReadOne::NotRegular);
        let missing = pack(source.path(), &["nope".into()], &[], Vec::new()).unwrap();
        assert_eq!(read_one(missing.as_slice(), 10).unwrap(), ReadOne::Missing);
        assert_eq!(read_one(archive.as_slice(), 2).unwrap(), ReadOne::TooLarge);
        for bad in ["/etc/passwd", "../x", "a/../../x", ""] {
            assert!(
                pack(source.path(), &[bad.into()], &[], Vec::new()).is_err(),
                "{bad}"
            );
        }
    }

    /// An archive with one entry, of any type and name, as a sandbox could send it.
    fn forged(name: &[u8], kind: tar::EntryType, content: &[u8]) -> Vec<u8> {
        let mut header = tar::Header::new_gnu();
        {
            let gnu = header.as_gnu_mut().unwrap();
            gnu.name[..name.len()].copy_from_slice(name);
        }
        header.set_entry_type(kind);
        header.set_size(content.len() as u64);
        header.set_mode(0o644);
        header.set_cksum();
        let mut builder = tar::Builder::new(Vec::new());
        builder.append(&header, content).unwrap();
        builder.into_inner().unwrap()
    }

    #[test]
    fn the_hosts_side_refuses_what_could_leave_its_directory_or_its_limits() {
        let dest = tempfile::tempdir().unwrap();
        let at = |n: &str| dest.path().join(n);
        for (name, message) in [
            (&b"../escape"[..], "not a relative path"),
            (b"/etc/cron.d/x", "not a relative path"),
            (b"a/../../x", "not a relative path"),
        ] {
            let error = unpack_untrusted(
                forged(name, tar::EntryType::Regular, b"x").as_slice(),
                &at("bad"),
                &limits(),
            )
            .unwrap_err();
            assert!(error.to_string().contains(message), "{error}");
        }
        let small = Limits {
            max_files: 1,
            max_bytes: 4,
            max_file_bytes: 2,
        };
        // A file over its limit is left out; over the archive's, the archive fails.
        let done = unpack_untrusted(
            forged(b"big", tar::EntryType::Regular, b"abc").as_slice(),
            &at("big"),
            &small,
        )
        .unwrap();
        assert_eq!(done.left_out, ["big"]);
        assert!(!at("big/big").exists());
        let mut builder = tar::Builder::new(Vec::new());
        for name in ["one", "two"] {
            let mut header = tar::Header::new_gnu();
            header.set_size(2);
            header.set_mode(0o644);
            builder.append_data(&mut header, name, &b"xy"[..]).unwrap();
        }
        let error = unpack_untrusted(
            builder.into_inner().unwrap().as_slice(),
            &at("many"),
            &small,
        )
        .unwrap_err();
        assert!(matches!(error, UnpackError::TooManyFiles(1)), "{error}");
        // As many directories as files, and no more.
        let mut builder = tar::Builder::new(Vec::new());
        for name in ["d1", "d2"] {
            let mut header = tar::Header::new_gnu();
            header.set_entry_type(tar::EntryType::Directory);
            header.set_size(0);
            header.set_mode(0o755);
            builder.append_data(&mut header, name, &b""[..]).unwrap();
        }
        let error = unpack_untrusted(
            builder.into_inner().unwrap().as_slice(),
            &at("dirs"),
            &small,
        )
        .unwrap_err();
        assert!(matches!(error, UnpackError::TooManyEntries(1)), "{error}");
        // Devices, links and hard links are left out, never made.
        for kind in [
            tar::EntryType::Symlink,
            tar::EntryType::Link,
            tar::EntryType::Char,
            tar::EntryType::Fifo,
        ] {
            let done = unpack_untrusted(
                forged(b"thing", kind, b"").as_slice(),
                &at("kinds"),
                &limits(),
            )
            .unwrap();
            assert_eq!(done.left_out, ["thing"], "{kind:?}");
        }
        assert_eq!(std::fs::read_dir(at("kinds")).unwrap().count(), 0);
        // A file under a file, or the same file twice, clashes.
        let mut builder = tar::Builder::new(Vec::new());
        for name in ["same", "same"] {
            let mut header = tar::Header::new_gnu();
            header.set_size(1);
            header.set_mode(0o644);
            builder.append_data(&mut header, name, &b"x"[..]).unwrap();
        }
        let twice = builder.into_inner().unwrap();
        let error = unpack_untrusted(twice.as_slice(), &at("twice"), &limits()).unwrap_err();
        assert!(matches!(error, UnpackError::Clash(_)), "{error}");
    }
}
