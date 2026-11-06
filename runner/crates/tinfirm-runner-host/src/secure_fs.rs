//! File helpers for secrets: 0700 directories, 0600 files, no symlinks, atomic writes. And
//! [`open_beneath`], which reads a file of a directory the sandbox controls without following
//! the sandbox's symlinks out of it.

use std::fs::{self, DirBuilder, OpenOptions, Permissions};
use std::io::Write;
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path};

use anyhow::{Context, Result, bail};

pub const DIR_MODE: u32 = 0o700;
pub const FILE_MODE: u32 = 0o600;

/// Creates `dir` (and missing parents) with mode 0700, or tightens an existing directory to
/// 0700. Refuses symlinks and non-directories.
pub fn ensure_private_dir(dir: &Path) -> Result<()> {
    match fs::symlink_metadata(dir) {
        Ok(meta) => {
            if meta.file_type().is_symlink() {
                bail!("{} is a symlink; refusing to use it", dir.display());
            }
            if !meta.is_dir() {
                bail!("{} exists and is not a directory", dir.display());
            }
            if meta.permissions().mode() & 0o777 != DIR_MODE {
                fs::set_permissions(dir, Permissions::from_mode(DIR_MODE))
                    .with_context(|| format!("cannot set mode 0700 on {}", dir.display()))?;
            }
            Ok(())
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => DirBuilder::new()
            .recursive(true)
            .mode(DIR_MODE)
            .create(dir)
            .with_context(|| format!("cannot create {}", dir.display())),
        Err(e) => Err(e).with_context(|| format!("cannot inspect {}", dir.display())),
    }
}

/// Atomically writes `contents` to `path` as a 0600 file inside a 0700 directory.
pub fn write_private_file(path: &Path, contents: &[u8]) -> Result<()> {
    let dir = path
        .parent()
        .with_context(|| format!("{} has no parent directory", path.display()))?;
    ensure_private_dir(dir)?;
    if let Ok(meta) = fs::symlink_metadata(path)
        && meta.file_type().is_symlink()
    {
        bail!(
            "{} is a symlink; refusing to write through it",
            path.display()
        );
    }
    let tmp = dir.join(format!(
        ".{}.tmp-{}",
        path.file_name().and_then(|n| n.to_str()).unwrap_or("file"),
        uuid::Uuid::new_v4().simple()
    ));
    let result = (|| -> Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(FILE_MODE)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&tmp)
            .with_context(|| format!("cannot create {}", tmp.display()))?;
        file.write_all(contents)?;
        file.sync_all()?;
        fs::rename(&tmp, path)
            .with_context(|| format!("cannot move {} into place", path.display()))?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

/// Reads a private file. A file that other users can read is tightened to 0600 first
/// (with a warning); symlinks are refused.
pub fn read_private_file(path: &Path) -> Result<Vec<u8>> {
    let meta =
        fs::symlink_metadata(path).with_context(|| format!("cannot read {}", path.display()))?;
    if meta.file_type().is_symlink() {
        bail!("{} is a symlink; refusing to read it", path.display());
    }
    if meta.permissions().mode() & 0o077 != 0 {
        tracing::warn!(path = %path.display(), "secret file was readable by others; setting mode 0600");
        fs::set_permissions(path, Permissions::from_mode(FILE_MODE))?;
    }
    fs::read(path).with_context(|| format!("cannot read {}", path.display()))
}

/// Mode bits (`0o777` part) of a path, without following symlinks.
pub fn mode_of(path: &Path) -> Option<u32> {
    fs::symlink_metadata(path)
        .ok()
        .map(|m| m.permissions().mode() & 0o777)
}

/// Opens the regular file `root/relative` for reading, only if it really is beneath `root`.
///
/// `root` is a directory of the host that the sandbox writes to (a workspace, a session home),
/// so every path in it may be a symlink the agent planted, and may change at any time. The
/// file is opened without following a final symlink (and without blocking on a FIFO); then
/// the path is resolved again, and it must lie beneath `root` and be the very file that was
/// opened (same device and inode). A symlink that pointed out of `root` while the file was
/// opened cannot pass: the sandbox cannot make a file inside `root` with the inode of a host
/// file it cannot see.
///
/// `root` itself must be a directory, not a symlink: a root that the sandbox could replace
/// with a link (an `/output` it renamed away) would be resolved to wherever the link points,
/// and every file beneath that would pass the check.
///
/// `relative` may only have plain components (no `..`, no root). Errors: `NotFound`,
/// `PermissionDenied` for a path that leads out of `root` or a root that is a symlink,
/// `InvalidInput` for anything that is not a regular file or not a plain relative path.
pub fn open_beneath(root: &Path, relative: &Path) -> std::io::Result<(fs::File, fs::Metadata)> {
    use std::io::{Error, ErrorKind};
    if relative.as_os_str().is_empty()
        || !relative
            .components()
            .all(|c| matches!(c, Component::Normal(_) | Component::CurDir))
    {
        return Err(Error::new(
            ErrorKind::InvalidInput,
            "not a plain relative path",
        ));
    }
    if fs::symlink_metadata(root)?.file_type().is_symlink() {
        return Err(Error::new(
            ErrorKind::PermissionDenied,
            "the directory is a symlink",
        ));
    }
    let root = fs::canonicalize(root)?;
    let path = root.join(relative);
    // Not even opened when it is a device, a FIFO or a socket (checked again after the open).
    let kind = fs::symlink_metadata(&path)?.file_type();
    if !kind.is_file() && !kind.is_symlink() {
        return Err(Error::new(ErrorKind::InvalidInput, "not a regular file"));
    }
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(&path)
        .map_err(|e| {
            if e.raw_os_error() == Some(libc::ELOOP) {
                Error::new(ErrorKind::InvalidInput, "a symlink")
            } else {
                e
            }
        })?;
    let meta = file.metadata()?;
    if !meta.is_file() {
        return Err(Error::new(ErrorKind::InvalidInput, "not a regular file"));
    }
    let resolved = fs::canonicalize(&path)?;
    if !resolved.starts_with(&root) {
        return Err(Error::new(
            ErrorKind::PermissionDenied,
            "outside the directory",
        ));
    }
    let again = fs::metadata(&resolved)?;
    if again.dev() != meta.dev() || again.ino() != meta.ino() {
        return Err(Error::new(
            ErrorKind::PermissionDenied,
            "changed while it was opened",
        ));
    }
    Ok((file, meta))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opens_files_beneath_the_root_only() {
        use std::io::{ErrorKind, Read};
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("workspace");
        fs::create_dir_all(root.join("repo/out")).unwrap();
        fs::write(root.join("repo/out/report.md"), b"report").unwrap();
        fs::write(tmp.path().join("host-secret"), b"secret").unwrap();
        std::os::unix::fs::symlink(tmp.path().join("host-secret"), root.join("leak")).unwrap();
        std::os::unix::fs::symlink(tmp.path(), root.join("up")).unwrap();
        std::os::unix::fs::symlink("repo/out", root.join("inside")).unwrap();

        let (mut file, meta) = open_beneath(&root, Path::new("repo/out/report.md")).unwrap();
        let mut text = String::new();
        file.read_to_string(&mut text).unwrap();
        assert_eq!(text, "report");
        assert_eq!(meta.len(), 6);
        // A symlinked directory inside the root is fine.
        assert!(open_beneath(&root, Path::new("inside/report.md")).is_ok());
        assert!(open_beneath(&root, Path::new("./repo/out/report.md")).is_ok());

        let kind = |relative: &str| open_beneath(&root, Path::new(relative)).unwrap_err().kind();
        assert_eq!(kind("leak"), ErrorKind::InvalidInput, "a final symlink");
        assert_eq!(kind("up/host-secret"), ErrorKind::PermissionDenied);
        assert_eq!(kind("../host-secret"), ErrorKind::InvalidInput);
        assert_eq!(kind("repo/out"), ErrorKind::InvalidInput, "a directory");
        assert_eq!(kind("missing.txt"), ErrorKind::NotFound);
        assert_eq!(kind(""), ErrorKind::InvalidInput);
        assert_eq!(
            open_beneath(&root, &tmp.path().join("host-secret"))
                .unwrap_err()
                .kind(),
            ErrorKind::InvalidInput,
            "an absolute path"
        );
    }

    #[test]
    fn refuses_a_root_that_is_a_symlink() {
        use std::io::ErrorKind;
        let tmp = tempfile::tempdir().unwrap();
        let elsewhere = tmp.path().join("elsewhere");
        fs::create_dir(&elsewhere).unwrap();
        fs::write(elsewhere.join("host-secret"), b"secret").unwrap();
        // The sandbox renamed its folder away and put a link at its path.
        let root = tmp.path().join("output");
        std::os::unix::fs::symlink(&elsewhere, &root).unwrap();
        assert_eq!(
            open_beneath(&root, Path::new("host-secret"))
                .unwrap_err()
                .kind(),
            ErrorKind::PermissionDenied
        );
        assert_eq!(
            open_beneath(&tmp.path().join("missing"), Path::new("host-secret"))
                .unwrap_err()
                .kind(),
            ErrorKind::NotFound
        );
        // The real directory is read as before.
        assert!(open_beneath(&elsewhere, Path::new("host-secret")).is_ok());
    }

    #[test]
    fn creates_private_dirs_and_files() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("a/b");
        let file = dir.join("secret.json");
        write_private_file(&file, b"{}").unwrap();
        assert_eq!(mode_of(&dir), Some(0o700));
        assert_eq!(mode_of(&file), Some(0o600));
        assert_eq!(read_private_file(&file).unwrap(), b"{}");
        // Overwrite keeps the mode.
        write_private_file(&file, b"{\"a\":1}").unwrap();
        assert_eq!(mode_of(&file), Some(0o600));
    }

    #[test]
    fn tightens_loose_permissions() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("loose");
        fs::create_dir(&dir).unwrap();
        fs::set_permissions(&dir, Permissions::from_mode(0o755)).unwrap();
        ensure_private_dir(&dir).unwrap();
        assert_eq!(mode_of(&dir), Some(0o700));
        let file = dir.join("f");
        fs::write(&file, b"x").unwrap();
        fs::set_permissions(&file, Permissions::from_mode(0o644)).unwrap();
        read_private_file(&file).unwrap();
        assert_eq!(mode_of(&file), Some(0o600));
    }

    #[test]
    fn refuses_symlinks() {
        let tmp = tempfile::tempdir().unwrap();
        let target = tmp.path().join("target");
        fs::write(&target, b"x").unwrap();
        let link = tmp.path().join("link");
        std::os::unix::fs::symlink(&target, &link).unwrap();
        assert!(read_private_file(&link).is_err());
        assert!(write_private_file(&link, b"y").is_err());
        assert!(ensure_private_dir(&link).is_err());
    }
}
