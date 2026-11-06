//! Protocol v2: the `/output` folder of a run (runner/README.md, "`/output` (protocol v2)").
//!
//! A v2 run gets a writable bind at `/output` (the host directory `outputs/<runId>`, empty
//! when the run starts) for the documents it makes. At the end of the run, before the
//! workspace is cleaned up, the host walks the folder and saves each regular file as an
//! artifact of the job: it reads the file like an upload of the platform tools
//! ([`crate::secure_fs::open_beneath`], so a symlink the agent planted leads nowhere; the same
//! size cap), scans it for credentials, and posts it to the attachments endpoint with
//! `artifact=true&source=output` and the path relative to `/output` as the name
//! ([`crate::attachments::upload`]; the `source` tells the server the host named the file,
//! not the agent's `save_artifact`, which its policies may deny). The saved files are listed
//! in `run.finished.artifacts[]`.
//!
//! The folder is the host's, outside the workspace and every other directory the sandbox
//! writes to, and it is anchored before the sandbox starts ([`OutputRoot`]): the host opens
//! the directory and, when it reads the folder back, first checks that the path still names
//! that very directory. The host never resolves a path component the agent could have
//! replaced; an `/output` that was swapped for a link to somewhere else saves nothing.
//!
//! A file that holds a credential or a masked value, one over the cap, one that is not a
//! regular file, or one the server refuses, is skipped and named in a `LOG` event; the run
//! goes on. Nothing of this exists in protocol v1.

use std::fs::{self, File, OpenOptions};
use std::future::Future;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Component, Path, PathBuf};

use anyhow::{Context, Result, bail};

use hyper::body::Bytes;
use tinfirm_protocol::{ArtifactResult, LogLevel, MAX_ARTIFACTS};

use crate::attachments::{UploadResponse, safe_file_name};
use crate::mcp::{MAX_UPLOAD_BYTES, content_type};
use crate::secure_fs;

/// Largest file of `/output` that comes back from a bridged sandbox: the highest cap of an
/// upload. [`collect`] saves only files up to the host's upload limit
/// (`run --upload-max-bytes`).
pub const MAX_FILE_BYTES: u64 = MAX_UPLOAD_BYTES;

/// Most files of `/output` that are saved (`run.finished.artifacts` `maxItems`); the rest are
/// named in one LOG event.
pub const MAX_FILES: usize = MAX_ARTIFACTS;

/// Deepest directory under `/output` that is walked.
const MAX_DEPTH: usize = 32;

/// The run's `/output` on the host, anchored before the sandbox starts.
///
/// The directory is created empty in a private parent and held open: a sandbox that renames
/// its `/output` away and puts a symlink at its path (or anything else) cannot make the host
/// read somewhere else, since the collection reads the path only while it still names the
/// directory that was opened here (the same device and inode, no symlink). The sandbox writes
/// into that directory through its mount, whatever it does with the mount point in its own
/// namespace; with the bridge, what comes back is unpacked into the same directory.
#[derive(Debug)]
pub struct OutputRoot {
    path: PathBuf,
    /// The directory itself, open since before the sandbox started; the collection compares
    /// the path against it.
    dir: File,
}

impl OutputRoot {
    /// Ensures the private parent, removes what an earlier run left at `path`, creates the
    /// directory empty and opens it.
    pub fn create(path: &Path) -> Result<OutputRoot> {
        if let Some(parent) = path.parent() {
            crate::secure_fs::ensure_private_dir(parent)?;
        }
        match fs::symlink_metadata(path) {
            Ok(meta) if meta.is_dir() => fs::remove_dir_all(path)
                .with_context(|| format!("cannot remove the old {}", path.display()))?,
            Ok(_) => fs::remove_file(path)
                .with_context(|| format!("cannot remove the old {}", path.display()))?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e).with_context(|| format!("cannot inspect {}", path.display())),
        }
        fs::create_dir(path).with_context(|| format!("cannot create {}", path.display()))?;
        let dir = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(path)
            .with_context(|| format!("cannot open {}", path.display()))?;
        if !dir.metadata()?.is_dir() {
            bail!("{} is not a directory", path.display());
        }
        Ok(OutputRoot {
            path: path.to_path_buf(),
            dir,
        })
    }

    /// The host directory, for the mount and the log lines.
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Whether the path still names the directory that was anchored: not a symlink, and the
    /// same device and inode.
    fn intact(&self) -> bool {
        let Ok(anchor) = self.dir.metadata() else {
            return false;
        };
        fs::symlink_metadata(&self.path)
            .is_ok_and(|now| now.is_dir() && now.dev() == anchor.dev() && now.ino() == anchor.ino())
    }
}

/// One file of `/output`, read and ready to upload.
#[derive(Debug, Clone)]
pub struct OutputFile {
    /// The path relative to `/output`, with `/` between the segments.
    pub name: String,
    pub content_type: String,
    pub content: Bytes,
}

/// What the collection of `/output` did: the artifacts for `run.finished`, and the lines for
/// the run's log (skipped files, server refusals).
#[derive(Debug, Default)]
pub struct Collected {
    pub artifacts: Vec<ArtifactResult>,
    pub log: Vec<(LogLevel, String)>,
}

/// Why a file of `/output` was not read.
#[derive(Debug, PartialEq, Eq)]
enum Skip {
    TooLarge(u64),
    /// Not a regular file (a symlink, a device), or a path that leads out of the folder.
    NotRegular(&'static str),
    Unreadable(String),
}

/// The regular files under `root`, as relative paths (sorted), without following symlinks.
/// A name that is not a plain path segment is reported in the second list.
pub fn list_files(root: &Path) -> (Vec<PathBuf>, Vec<String>) {
    let mut files = Vec::new();
    let mut refused = Vec::new();
    walk(root, &PathBuf::new(), 0, &mut files, &mut refused);
    files.sort();
    refused.sort();
    (files, refused)
}

fn walk(
    root: &Path,
    relative: &Path,
    depth: usize,
    files: &mut Vec<PathBuf>,
    refused: &mut Vec<String>,
) {
    let Ok(entries) = std::fs::read_dir(root.join(relative)) else {
        return;
    };
    for entry in entries.filter_map(|e| e.ok()) {
        let name = entry.file_name();
        let Some(name) = name.to_str().filter(|n| safe_file_name(n)) else {
            refused.push(format!(
                "{}/{}",
                relative.display(),
                entry.file_name().to_string_lossy().escape_debug()
            ));
            continue;
        };
        let child = relative.join(name);
        // Never through a symlink: the agent controls the folder.
        let Ok(meta) = std::fs::symlink_metadata(entry.path()) else {
            continue;
        };
        if meta.is_dir() {
            if depth < MAX_DEPTH {
                walk(root, &child, depth + 1, files, refused);
            }
        } else if meta.is_file() {
            files.push(child);
        }
        // Symlinks, devices, FIFOs and sockets are left where they are.
    }
}

/// The name of a relative path, with `/` between the segments.
fn name_of(relative: &Path) -> String {
    relative
        .components()
        .filter_map(|c| match c {
            Component::Normal(part) => Some(part.to_string_lossy().into_owned()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("/")
}

/// Reads `root/relative` ([`secure_fs::open_beneath`]), at most `max` bytes, refused on the
/// metadata's size before a byte is read.
fn read_file(root: &Path, relative: &Path, max: u64) -> std::result::Result<Vec<u8>, Skip> {
    use std::io::{ErrorKind, Read};
    let (file, meta) = secure_fs::open_beneath(root, relative).map_err(|e| match e.kind() {
        ErrorKind::PermissionDenied => Skip::NotRegular("it leads out of the folder"),
        ErrorKind::InvalidInput => Skip::NotRegular("it is not a regular file"),
        ErrorKind::NotFound => Skip::Unreadable("it disappeared".into()),
        _ => Skip::Unreadable(format!("the runner host cannot open it: {e}")),
    })?;
    if meta.len() > max {
        return Err(Skip::TooLarge(meta.len()));
    }
    let mut content = Vec::with_capacity(usize::try_from(meta.len()).unwrap_or(0));
    file.take(max + 1)
        .read_to_end(&mut content)
        .map_err(|e| Skip::Unreadable(format!("the runner host cannot read it: {e}")))?;
    if content.len() as u64 > max {
        return Err(Skip::TooLarge(content.len() as u64));
    }
    Ok(content)
}

/// Saves the files of `root` (the run's `/output`) as artifacts: each one is read beneath the
/// folder, scanned with `contains_secret` and handed to `upload`, which returns the server's
/// answer (or, as `Err`, why there is none). A file the scan refuses, one over the cap or one
/// the server refuses is skipped with a log line; after a `RunNotActive` answer the rest is
/// left alone, since the server would refuse it too. A root that no longer names the
/// anchored directory saves nothing, with one warning; the run goes on. A file over
/// `max_bytes` (the host's upload limit) is skipped on its size; each file is read, scanned and
/// uploaded under one of the host's upload slots ([`crate::attachments::upload_slot`]).
pub async fn collect<U, Fut>(
    root: &OutputRoot,
    max_bytes: u64,
    contains_secret: &(dyn Fn(&[u8]) -> bool + Sync),
    upload: U,
) -> Collected
where
    U: Fn(OutputFile) -> Fut + Sync,
    Fut: Future<Output = std::result::Result<UploadResponse, String>> + Send,
{
    let mut collected = Collected::default();
    if !root.intact() {
        tracing::warn!(path = %root.path.display(), "/output was replaced during the run; nothing is saved");
        collected.log.push((
            LogLevel::Warn,
            "/output was replaced during the run; nothing was saved".to_string(),
        ));
        return collected;
    }
    let root = root.path();
    let (files, refused) = {
        let root = root.to_path_buf();
        tokio::task::spawn_blocking(move || list_files(&root))
            .await
            .unwrap_or_default()
    };
    for name in refused {
        collected.log.push((
            LogLevel::Warn,
            format!("/output{name} was not saved: its name is not a plain file name"),
        ));
    }
    if files.len() > MAX_FILES {
        collected.log.push((
            LogLevel::Warn,
            format!(
                "/output holds {} files; only the first {MAX_FILES} (by path) are saved",
                files.len()
            ),
        ));
    }
    for relative in files.into_iter().take(MAX_FILES) {
        let name = name_of(&relative);
        let _slot = crate::attachments::upload_slot().await;
        let read = {
            let root = root.to_path_buf();
            let relative = relative.clone();
            tokio::task::spawn_blocking(move || read_file(&root, &relative, max_bytes)).await
        };
        let content = match read {
            Ok(Ok(content)) => content,
            Ok(Err(skip)) => {
                let why = match skip {
                    Skip::TooLarge(bytes) => {
                        format!(
                            "{bytes} bytes is more than the cap of {}",
                            crate::attachments::size_text(max_bytes)
                        )
                    }
                    Skip::NotRegular(why) => why.to_string(),
                    Skip::Unreadable(why) => why,
                };
                collected.log.push((
                    LogLevel::Warn,
                    format!("/output/{name} was not saved: {why}"),
                ));
                continue;
            }
            Err(_) => {
                collected.log.push((
                    LogLevel::Warn,
                    format!("/output/{name} was not saved: the runner host cannot read it"),
                ));
                continue;
            }
        };
        if contains_secret(&content) {
            tracing::warn!(file = %name, "a file of /output holds a credential; not saved");
            collected.log.push((
                LogLevel::Warn,
                format!(
                    "/output/{name} was not saved: it holds a credential or a secret of this run, so it does not leave the runner host"
                ),
            ));
            continue;
        }
        let size_bytes = content.len() as u64;
        let file = OutputFile {
            name: name.clone(),
            content_type: content_type(&name).to_string(),
            content: content.into(),
        };
        match upload(file).await {
            Ok(UploadResponse::Answered {
                stored: true,
                attachment_id: Some(attachment_id),
                ..
            }) => {
                collected.log.push((
                    LogLevel::Info,
                    format!("saved /output/{name} as an artifact ({size_bytes} bytes)"),
                ));
                collected.artifacts.push(ArtifactResult {
                    name,
                    attachment_id,
                    size_bytes,
                });
            }
            Ok(UploadResponse::Answered {
                stored: true,
                attachment_id: None,
                ..
            }) => collected.log.push((
                LogLevel::Warn,
                format!(
                    "/output/{name} was stored, but the server named no attachment id, so it is not listed as an artifact"
                ),
            )),
            Ok(UploadResponse::Answered {
                stored: false,
                message,
                ..
            }) => collected.log.push((
                LogLevel::Warn,
                format!("/output/{name} was not saved: {message}"),
            )),
            Ok(UploadResponse::TooLarge) => collected.log.push((
                LogLevel::Warn,
                format!("/output/{name} was not saved: it is larger than the server accepts"),
            )),
            Ok(UploadResponse::RunNotActive) => {
                collected.log.push((
                    LogLevel::Warn,
                    format!(
                        "/output/{name} was not saved: the server no longer takes files of this run; the remaining files are left alone"
                    ),
                ));
                break;
            }
            Err(why) => collected.log.push((
                LogLevel::Warn,
                format!("/output/{name} was not saved: {why}"),
            )),
        }
    }
    collected
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use uuid::Uuid;

    use super::*;
    use crate::attachments::DEFAULT_UPLOAD_MAX_BYTES;

    fn stored(id: Uuid) -> std::result::Result<UploadResponse, String> {
        Ok(UploadResponse::Answered {
            stored: true,
            message: "Saved.".into(),
            attachment_id: Some(id),
        })
    }

    /// The run's `/output`, as `prepare_dirs` makes it: created empty and anchored before the
    /// sandbox (the test) writes into it.
    fn anchored(tmp: &Path) -> OutputRoot {
        OutputRoot::create(&tmp.join("outputs").join("run")).unwrap()
    }

    #[tokio::test]
    async fn saves_the_regular_files_with_their_relative_paths() {
        let tmp = tempfile::tempdir().unwrap();
        let anchor = anchored(tmp.path());
        let root = anchor.path().to_path_buf();
        std::fs::create_dir_all(root.join("reports/2026")).unwrap();
        std::fs::write(root.join("summary.md"), "# Summary").unwrap();
        std::fs::write(root.join("reports/2026/q3.csv"), "a,b\n1,2\n").unwrap();
        std::fs::write(root.join("reports/.hidden"), "x").unwrap();
        std::fs::write(tmp.path().join("host-secret"), "secret").unwrap();
        std::os::unix::fs::symlink(tmp.path().join("host-secret"), root.join("leak.txt")).unwrap();
        std::os::unix::fs::symlink(tmp.path(), root.join("up")).unwrap();
        let uploads: Mutex<Vec<OutputFile>> = Mutex::new(Vec::new());
        let id = Uuid::new_v4();
        let collected = collect(&anchor, MAX_FILE_BYTES, &|_| false, |file| {
            uploads.lock().unwrap().push(file);
            async move { stored(id) }
        })
        .await;
        let names: Vec<&str> = collected
            .artifacts
            .iter()
            .map(|a| a.name.as_str())
            .collect();
        assert_eq!(
            names,
            vec!["reports/.hidden", "reports/2026/q3.csv", "summary.md"]
        );
        assert_eq!(collected.artifacts[1].size_bytes, 8);
        assert_eq!(collected.artifacts[1].attachment_id, id);
        let uploads = uploads.lock().unwrap();
        assert_eq!(uploads[1].content_type, "text/csv");
        assert_eq!(&uploads[1].content[..], b"a,b\n1,2\n");
        assert_eq!(uploads[2].content_type, "text/markdown");
        // Symlinks are neither followed nor saved, and nothing is logged about the host file.
        assert!(uploads.iter().all(|u| u.name != "leak.txt"));
        assert!(!format!("{collected:?}").contains("host-secret"));
        assert_eq!(
            collected
                .log
                .iter()
                .filter(|(level, _)| *level == LogLevel::Warn)
                .count(),
            0,
            "{:?}",
            collected.log
        );
        assert!(
            collected
                .log
                .iter()
                .any(|(_, m)| m == "saved /output/summary.md as an artifact (9 bytes)")
        );
    }

    #[tokio::test]
    async fn skips_files_with_secrets_and_over_the_cap_and_server_refusals() {
        let tmp = tempfile::tempdir().unwrap();
        let anchor = anchored(tmp.path());
        let root = anchor.path().to_path_buf();
        std::fs::write(root.join("env.txt"), "TOKEN=sk-SECRET-value\n").unwrap();
        std::fs::write(root.join("fine.txt"), "fine\n").unwrap();
        std::fs::write(root.join("refused.txt"), "refused\n").unwrap();
        std::fs::write(root.join("huge.bin"), "x").unwrap();
        // The size cap is checked on the metadata, so a sparse file stands in for a huge one.
        let huge = std::fs::OpenOptions::new()
            .write(true)
            .open(root.join("huge.bin"))
            .unwrap();
        huge.set_len(DEFAULT_UPLOAD_MAX_BYTES + 1).unwrap();
        let contains_secret = |content: &[u8]| {
            content
                .windows(b"sk-SECRET-value".len())
                .any(|w| w == b"sk-SECRET-value")
        };
        let uploaded: Mutex<Vec<String>> = Mutex::new(Vec::new());
        let collected = collect(
            &anchor,
            DEFAULT_UPLOAD_MAX_BYTES,
            &contains_secret,
            |file| {
                uploaded.lock().unwrap().push(file.name.clone());
                async move {
                    if file.name == "refused.txt" {
                        Ok(UploadResponse::Answered {
                            stored: false,
                            message: "The job already has an artifact named `refused.txt`.".into(),
                            attachment_id: None,
                        })
                    } else {
                        stored(Uuid::new_v4())
                    }
                }
            },
        )
        .await;
        assert_eq!(*uploaded.lock().unwrap(), vec!["fine.txt", "refused.txt"]);
        let names: Vec<&str> = collected
            .artifacts
            .iter()
            .map(|a| a.name.as_str())
            .collect();
        assert_eq!(names, vec!["fine.txt"]);
        let warnings: Vec<&str> = collected
            .log
            .iter()
            .filter(|(level, _)| *level == LogLevel::Warn)
            .map(|(_, m)| m.as_str())
            .collect();
        assert_eq!(
            warnings,
            vec![
                "/output/env.txt was not saved: it holds a credential or a secret of this run, so it does not leave the runner host",
                "/output/huge.bin was not saved: 26214401 bytes is more than the cap of 25 MiB",
                "/output/refused.txt was not saved: The job already has an artifact named `refused.txt`.",
            ]
        );
        assert!(!format!("{collected:?}").contains("sk-SECRET-value"));
    }

    #[tokio::test]
    async fn stops_after_the_server_says_the_run_is_over_and_caps_the_count() {
        let tmp = tempfile::tempdir().unwrap();
        let anchor = anchored(tmp.path());
        let root = anchor.path().to_path_buf();
        for i in 0..(MAX_FILES + 2) {
            std::fs::write(root.join(format!("f{i:04}.txt")), "x").unwrap();
        }
        let calls = Mutex::new(0usize);
        let collected = collect(&anchor, MAX_FILE_BYTES, &|_| false, |_| {
            *calls.lock().unwrap() += 1;
            async { Ok(UploadResponse::RunNotActive) }
        })
        .await;
        assert_eq!(*calls.lock().unwrap(), 1, "the rest is left alone");
        assert!(collected.artifacts.is_empty());
        assert!(
            collected
                .log
                .iter()
                .any(|(_, m)| m.contains("only the first 200"))
        );
        assert!(
            collected
                .log
                .iter()
                .any(|(_, m)| m.contains("no longer takes files of this run"))
        );
    }

    #[tokio::test]
    async fn an_output_folder_replaced_during_the_run_saves_nothing() {
        let tmp = tempfile::tempdir().unwrap();
        let anchor = anchored(tmp.path());
        let root = anchor.path().to_path_buf();
        std::fs::write(root.join("report.md"), "# Report").unwrap();
        // What the agent could do to an `/output` inside a writable mount: move it away and
        // put a link to the host's file system at its path.
        let elsewhere = tmp.path().join("elsewhere");
        std::fs::create_dir(&elsewhere).unwrap();
        std::fs::write(elsewhere.join("host-secret"), "secret").unwrap();
        std::fs::rename(&root, tmp.path().join("away")).unwrap();
        std::os::unix::fs::symlink(&elsewhere, &root).unwrap();
        let calls = Mutex::new(0usize);
        let collected = collect(&anchor, MAX_FILE_BYTES, &|_| false, |_| {
            *calls.lock().unwrap() += 1;
            async { stored(Uuid::new_v4()) }
        })
        .await;
        assert_eq!(*calls.lock().unwrap(), 0);
        assert!(collected.artifacts.is_empty());
        assert_eq!(
            collected.log,
            vec![(
                LogLevel::Warn,
                "/output was replaced during the run; nothing was saved".to_string()
            )]
        );
        assert!(!format!("{collected:?}").contains("host-secret"));

        // A fresh directory at the path is not the one that was anchored either.
        std::fs::remove_file(&root).unwrap();
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("planted.md"), "x").unwrap();
        let again = collect(&anchor, MAX_FILE_BYTES, &|_| false, |_| async {
            stored(Uuid::new_v4())
        })
        .await;
        assert!(again.artifacts.is_empty());
        assert_eq!(again.log.len(), 1);

        // A folder that is gone saves nothing, with the same warning.
        std::fs::remove_dir_all(&root).unwrap();
        let gone = collect(&anchor, MAX_FILE_BYTES, &|_| false, |_| async {
            stored(Uuid::new_v4())
        })
        .await;
        assert!(gone.artifacts.is_empty());
        assert_eq!(gone.log.len(), 1);
    }

    #[test]
    fn the_root_is_created_empty_in_a_private_parent() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("outputs").join("run");
        std::fs::create_dir_all(&path).unwrap();
        std::fs::write(path.join("stale.txt"), "left by an earlier process").unwrap();
        let root = OutputRoot::create(&path).unwrap();
        assert_eq!(root.path(), path);
        assert!(root.intact());
        assert_eq!(std::fs::read_dir(&path).unwrap().count(), 0, "empty again");
        assert_eq!(
            crate::secure_fs::mode_of(&tmp.path().join("outputs")),
            Some(0o700)
        );
        // A link left at the path is removed, never followed.
        let target = tmp.path().join("target");
        std::fs::create_dir(&target).unwrap();
        std::fs::write(target.join("keep.txt"), "x").unwrap();
        let linked = tmp.path().join("outputs").join("linked");
        std::os::unix::fs::symlink(&target, &linked).unwrap();
        let root = OutputRoot::create(&linked).unwrap();
        assert!(root.intact());
        assert!(target.join("keep.txt").exists());
        assert!(!linked.join("keep.txt").exists());
    }
}
