//! The run's files with the sandbox bridge (ADR-0095, `run --sandbox-bridge`): the sandbox
//! gets no mounts of this host's directories (but a Codex ChatGPT login's, on Docker).
//!
//! - **In**, before the adapter starts: the workspace's clones, as the host made them (their
//!   `origin` is the run's tunnel to the git proxy); the session home without its caches; the
//!   skills; the attachments; an empty `/output`.
//! - **Out**, after the adapter has ended, while the sandbox still runs:
//!   - per repository, in the sandbox: the WIP commit the host would make, then a git bundle
//!     of HEAD without the commits the host has. The host fetches it into its own clone, which
//!     the agent never touched, with every object checked, and moves its HEAD there; the
//!     checks and the push then run as before ([`crate::run`]);
//!   - the session home (without the same paths) and `/output`, unpacked on the host as
//!     directories and regular files only, within limits ([`tinfirm_bridge::files`]).
//!
//! What the sandbox sends is the agent's: the host checks it as it checks a workspace.

use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, Result, bail};
use tinfirm_bridge::files::{Limits, Unpacked};
use tinfirm_bridge::host::{ExecOutput, SandboxFiles};

use crate::git::Git;
use crate::sandbox::Put;

/// The home's caches: they stay on neither side, the package mirrors serve them (the
/// Kubernetes runner analysis, §3.5).
pub const HOME_CACHES: &[&str] = &[
    ".cache",
    ".npm",
    ".m2/repository",
    ".gradle/caches",
    ".gradle/wrapper",
    ".cargo/registry",
    ".cargo/git",
    ".rustup",
    "go/pkg",
    ".local/share/pnpm",
    ".pnpm-store",
    ".yarn/cache",
    ".bun/install/cache",
    ".nuget/packages",
    ".ivy2/cache",
];

/// What a session home brings back at most.
pub const HOME_LIMITS: Limits = Limits {
    max_files: 100_000,
    max_bytes: 2 << 30,
    max_file_bytes: 1 << 30,
};

/// What `/output` brings back at most; a file over the size of an artifact is left out, as
/// [`crate::output`] leaves it.
pub const OUTPUT_LIMITS: Limits = Limits {
    max_files: 10_000,
    max_bytes: 4 << 30,
    max_file_bytes: crate::output::MAX_FILE_BYTES,
};

/// A repository's bundle at most.
pub const BUNDLE_LIMITS: Limits = Limits {
    max_files: 1,
    max_bytes: 8 << 30,
    max_file_bytes: 8 << 30,
};

/// One git step in the sandbox at most.
const GIT_TIMEOUT: Duration = Duration::from_secs(600);
/// One directory back from the sandbox at most.
const GET_TIMEOUT: Duration = Duration::from_secs(1200);

/// Settings of the host's git steps in the sandbox: no hooks, no signing, no prompts.
const SANDBOX_GIT_SETTINGS: [&str; 5] = [
    "core.hooksPath=/dev/null",
    "core.fsmonitor=false",
    "commit.gpgsign=false",
    "core.pager=cat",
    "credential.helper=",
];

/// Where the sandbox's directories are.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layout {
    pub workspace: String,
    pub home: String,
    pub attachments: String,
    pub output: String,
    /// Where the host's git steps make their bundles.
    pub scratch: String,
    /// The local test executor: the directory that stands for the sandbox, beside the run's
    /// workspace and removed with it.
    pub local_root: Option<PathBuf>,
}

impl Layout {
    /// A container's.
    pub fn container() -> Layout {
        Layout {
            workspace: crate::sandbox::WORKSPACE.to_string(),
            home: crate::sandbox::HOME.to_string(),
            attachments: crate::attachments::CONTAINER_DIR.to_string(),
            output: crate::sandbox::OUTPUT.to_string(),
            scratch: "/tmp/tinfirm".to_string(),
            local_root: None,
        }
    }

    /// The local test executor's without the bridge: the host's own directories.
    pub fn host(workspace: &Path, home: &Path, attachments: &Path, output: &Path) -> Layout {
        let at = |path: &Path| path.to_string_lossy().into_owned();
        Layout {
            workspace: at(workspace),
            home: at(home),
            attachments: at(attachments),
            output: at(output),
            scratch: std::env::temp_dir().to_string_lossy().into_owned(),
            local_root: None,
        }
    }

    /// The local test executor's with the bridge: a directory of its own beside
    /// `workspace_dir`, so that the files really travel.
    pub fn local(workspace_dir: &Path) -> Layout {
        let mut name = workspace_dir
            .file_name()
            .map(|n| n.to_os_string())
            .unwrap_or_default();
        name.push(".sandbox");
        let root = workspace_dir.with_file_name(name);
        let at = |sub: &str| root.join(sub).to_string_lossy().into_owned();
        Layout {
            workspace: at("workspace"),
            home: at("home"),
            attachments: at("attachments"),
            output: at("output"),
            scratch: at("tmp"),
            local_root: Some(root),
        }
    }
}

/// What stays out of the session home on the way in and on the way back: the caches, the
/// skills (they come from the host's cache), and a login mounted on its own (`login`, relative
/// to the home: a Codex ChatGPT login's `.codex`, a Claude login's `.claude/.credentials.json`),
/// which a copy would overwrite.
pub fn home_exclude(skill_dirs: &[String], login: Option<&str>) -> Vec<String> {
    let mut exclude: Vec<String> = HOME_CACHES.iter().map(|c| c.to_string()).collect();
    exclude.extend(skill_dirs.iter().cloned());
    if let Some(login) = login {
        exclude.push(login.to_string());
    }
    exclude
}

/// The directories that go into the sandbox before the adapter starts, in order: the
/// workspace (without its `output` folder, which is `/output`), the home, each skill at its
/// place in the home, the attachments and `/output`.
pub fn puts(
    layout: &Layout,
    workspace_dir: &Path,
    home_dir: &Path,
    home_exclude: Vec<String>,
    skills: Vec<(PathBuf, String)>,
    attachments: Option<&Path>,
    output: Option<&Path>,
) -> Vec<Put> {
    let mut puts = vec![
        Put {
            host: workspace_dir.to_path_buf(),
            sandbox: layout.workspace.clone(),
            exclude: vec!["output".to_string()],
        },
        Put {
            host: home_dir.to_path_buf(),
            sandbox: layout.home.clone(),
            exclude: home_exclude,
        },
    ];
    puts.extend(skills.into_iter().map(|(host, sandbox)| Put {
        host,
        sandbox,
        exclude: Vec::new(),
    }));
    if let Some(dir) = attachments {
        puts.push(Put {
            host: dir.to_path_buf(),
            sandbox: layout.attachments.clone(),
            exclude: Vec::new(),
        });
    }
    if let Some(dir) = output {
        puts.push(Put {
            host: dir.to_path_buf(),
            sandbox: layout.output.clone(),
            exclude: Vec::new(),
        });
    }
    puts
}

/// How a repository came back from the sandbox.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Collected {
    /// The sandbox committed the agent's uncommitted changes.
    pub wip: bool,
    pub head: String,
}

/// A repository of the run, as it is in the sandbox.
pub struct SandboxRepo<'a> {
    pub files: &'a SandboxFiles,
    /// Its directory in the sandbox.
    pub dir: String,
    pub scratch: &'a str,
    /// Names the bundle, among the run's repositories.
    pub index: usize,
}

impl SandboxRepo<'_> {
    async fn git(&self, args: &[&str], stdin: Option<String>) -> Result<ExecOutput> {
        let mut argv = vec!["git", "-C", self.dir.as_str()];
        for setting in &SANDBOX_GIT_SETTINGS {
            argv.extend(["-c", setting]);
        }
        argv.extend(args);
        self.files
            .exec(
                &argv,
                &[("GIT_TERMINAL_PROMPT", "0"), ("GIT_OPTIONAL_LOCKS", "0")],
                None,
                stdin,
                GIT_TIMEOUT,
            )
            .await
    }

    async fn git_ok(&self, args: &[&str], stdin: Option<String>) -> Result<String> {
        let out = self.git(args, stdin).await?;
        if !out.success() {
            bail!(
                "git {} in the sandbox failed: {}",
                args.first().unwrap_or(&""),
                out.stderr.trim()
            );
        }
        Ok(out.stdout)
    }

    /// The WIP commit of what the agent left uncommitted, as the host makes it
    /// ([`Git::commit_all`]); `true` when there was something.
    async fn commit_all(&self, message: &str, identity: (&str, &str)) -> Result<bool> {
        if self
            .git_ok(&["status", "--porcelain"], None)
            .await?
            .trim()
            .is_empty()
        {
            return Ok(false);
        }
        self.git_ok(&["add", "-A"], None).await?;
        let name = format!("user.name={}", identity.0);
        let email = format!("user.email={}", identity.1);
        self.git_ok(
            &[
                "-c",
                &name,
                "-c",
                &email,
                "commit",
                "--no-verify",
                "-q",
                "-m",
                message,
            ],
            None,
        )
        .await?;
        Ok(true)
    }
}

/// Brings a repository back from the sandbox into `git`, the host's own clone: the WIP commit
/// and the bundle in the sandbox, the fetch on the host. `known` are commits that the host's
/// clone has, which the bundle leaves out; `temp` is a directory of the host's for the bundle.
pub async fn collect_repository(
    repo: &SandboxRepo<'_>,
    git: &Git,
    message: &str,
    identity: (&str, &str),
    known: &[String],
    temp: &Path,
) -> Result<Collected> {
    let wip = repo.commit_all(message, identity).await?;
    let head = repo
        .git_ok(&["rev-parse", "--verify", "HEAD^{commit}"], None)
        .await?
        .trim()
        .to_string();
    let mut revisions = String::from("HEAD\n");
    for sha in known {
        revisions.push('^');
        revisions.push_str(sha);
        revisions.push('\n');
    }
    let new = repo
        .git_ok(&["rev-list", "--count", "--stdin"], Some(revisions.clone()))
        .await?;
    let new: u64 = new
        .trim()
        .parse()
        .with_context(|| format!("unexpected rev-list output `{}`", new.trim()))?;
    if new == 0 {
        git.import_head(&head).await?;
        return Ok(Collected { wip, head });
    }
    let name = format!("repository-{}.bundle", repo.index);
    let sandbox_bundle = format!("{}/{name}", repo.scratch);
    let mkdir = repo
        .files
        .exec(&["mkdir", "-p", repo.scratch], &[], None, None, GIT_TIMEOUT)
        .await?;
    if !mkdir.success() {
        bail!(
            "cannot create {} in the sandbox: {}",
            repo.scratch,
            mkdir.stderr.trim()
        );
    }
    repo.git_ok(
        &["bundle", "create", "--quiet", &sandbox_bundle, "--stdin"],
        Some(revisions),
    )
    .await?;
    let dest = temp.join(format!("bundle-{}", repo.index));
    let _ = std::fs::remove_dir_all(&dest);
    let unpacked = repo
        .files
        .get_dir(
            repo.scratch,
            vec![name.clone()],
            Vec::new(),
            &dest,
            BUNDLE_LIMITS,
            GET_TIMEOUT,
        )
        .await?;
    if unpacked.files != 1 {
        bail!(
            "the sandbox's bundle did not come back (it may be larger than {} GiB)",
            BUNDLE_LIMITS.max_file_bytes >> 30
        );
    }
    let imported = git.import_bundle(&dest.join(&name), &head).await;
    let _ = std::fs::remove_dir_all(&dest);
    imported?;
    Ok(Collected { wip, head })
}

/// Brings the session home back from the sandbox: what came replaces `home_dir`, so that the
/// next turn finds the runtime's session there. `tag` names the run's temporary directory.
pub async fn collect_home(
    files: &SandboxFiles,
    layout: &Layout,
    exclude: Vec<String>,
    home_dir: &Path,
    tag: &str,
) -> Result<Unpacked> {
    let parent = home_dir
        .parent()
        .context("the session home has no parent directory")?;
    let name = home_dir
        .file_name()
        .context("the session home has no name")?
        .to_string_lossy()
        .into_owned();
    // Named so that the startup cleanup removes them after a crash (crate::homes).
    let incoming = parent.join(format!("{}{name}-in-{tag}", crate::homes::DELETE_PREFIX));
    let outgoing = parent.join(format!("{}{name}-out-{tag}", crate::homes::DELETE_PREFIX));
    let _ = std::fs::remove_dir_all(&incoming);
    let unpacked = match files
        .get_dir(
            &layout.home,
            Vec::new(),
            exclude,
            &incoming,
            HOME_LIMITS,
            GET_TIMEOUT,
        )
        .await
    {
        Ok(unpacked) => unpacked,
        Err(e) => {
            let _ = std::fs::remove_dir_all(&incoming);
            return Err(e);
        }
    };
    if home_dir.exists() {
        std::fs::rename(home_dir, &outgoing).context("cannot move the old session home")?;
    }
    std::fs::rename(&incoming, home_dir).context("cannot move the session home in")?;
    let _ = std::fs::remove_dir_all(&outgoing);
    Ok(unpacked)
}

/// Brings `/output` back into `output_dir`, which is empty.
pub async fn collect_output(
    files: &SandboxFiles,
    layout: &Layout,
    output_dir: &Path,
) -> Result<Unpacked> {
    files
        .get_dir(
            &layout.output,
            Vec::new(),
            Vec::new(),
            output_dir,
            OUTPUT_LIMITS,
            GET_TIMEOUT,
        )
        .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_container_and_the_local_executor_have_their_directories() {
        let container = Layout::container();
        assert_eq!(container.workspace, "/workspace");
        assert_eq!(container.home, "/home/agent");
        assert_eq!(container.output, "/output");
        assert!(container.local_root.is_none());
        let local = Layout::local(Path::new("/data/workspaces/run-1"));
        assert_eq!(
            local.local_root.as_deref(),
            Some(Path::new("/data/workspaces/run-1.sandbox"))
        );
        assert_eq!(local.workspace, "/data/workspaces/run-1.sandbox/workspace");
        assert_eq!(local.scratch, "/data/workspaces/run-1.sandbox/tmp");
    }

    #[test]
    fn the_home_leaves_its_caches_skills_and_a_mounted_login_behind() {
        let exclude = home_exclude(&[".claude/skills/pdf".to_string()], Some(".codex"));
        for path in [".cache", ".m2/repository", ".claude/skills/pdf", ".codex"] {
            assert!(exclude.iter().any(|e| e == path), "{path}");
        }
        assert!(!home_exclude(&[], None).iter().any(|e| e == ".codex"));
        let claude = home_exclude(&[], Some(".claude/.credentials.json"));
        assert!(claude.iter().any(|e| e == ".claude/.credentials.json"));
        let puts = puts(
            &Layout::container(),
            Path::new("/w"),
            Path::new("/h"),
            exclude,
            vec![(
                PathBuf::from("/skills/pdf"),
                "/home/agent/.claude/skills/pdf".into(),
            )],
            Some(Path::new("/a")),
            Some(Path::new("/w/output")),
        );
        let places: Vec<&str> = puts.iter().map(|p| p.sandbox.as_str()).collect();
        assert_eq!(
            places,
            [
                "/workspace",
                "/home/agent",
                "/home/agent/.claude/skills/pdf",
                "/attachments",
                "/output"
            ]
        );
        assert_eq!(puts[0].exclude, ["output"]);
    }
}
