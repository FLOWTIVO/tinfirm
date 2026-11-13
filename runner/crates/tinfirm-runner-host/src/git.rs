//! Host-side git for one repository of a run (docs/archive/v1/CONVENTIONS.md §15.4 steps 2 and 8).
//!
//! The git token of a repository is passed per command as `http.extraHeader` through the
//! `GIT_CONFIG_COUNT`/`GIT_CONFIG_KEY_n`/`GIT_CONFIG_VALUE_n` environment, and only to
//! commands for that repository, so it is never written to any `.git/config`, never appears
//! in the process list, and never enters the sandbox.
//!
//! The workspace is a plain local copy of the host's mirror of the repository
//! ([`crate::mirror`]), cloned with `--no-hardlinks` and without alternates, so nothing in it
//! points back to the mirror. After the clone, `origin` is the real repository URL.
//!
//! The workspace is writable by the agent, so after the agent ran the repository is
//! untrusted input for host-side git: hooks, `core.fsmonitor`, filter drivers, `url.*`
//! rewrites or an `http.proxy` in `.git/config` would all run code or leak the token on the
//! host, and refs, replace refs, grafts, shallow files or commit-graphs could hide commits
//! from the credential scan. Before finalizing, [`Git::sanitize`] restores the `.git/config`
//! written at clone time and removes alternates, grafts, `shallow`, `commondir` and symlinks
//! inside `.git`. Every command runs with hooks, fsmonitor, credential helpers, signing,
//! replace refs and commit-graphs disabled. The credential scan and the ahead count never use
//! the workspace's own refs (`refs/remotes/origin/*` is whatever the agent made of it): they
//! exclude only commits reachable from the mirror's ref tips, which the host controls. The
//! push names the repository URL explicitly, and its lease comes from the mirror's refs at
//! clone time ([`Git::push`]).
//!
//! While the agent runs, the workspace's `origin` points at the runner host's fetch-only
//! git proxy ([`crate::git_proxy`]), so the agent can fetch without credentials. `sanitize`
//! puts the clone-time `origin` back.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::process::Stdio;

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine;
use tinfirm_protocol::{Secret, WorkspaceSpec};
use tokio::io::AsyncWriteExt;
use tokio::process::Command;

use crate::redact::Redactor;

/// Output of a finished git command.
#[derive(Debug)]
pub struct GitOutput {
    pub status: std::process::ExitStatus,
    pub stdout: String,
    pub stderr: String,
}

/// Where [`Git::import_bundle`] fetches the sandbox's HEAD to, for a moment.
const IMPORTED_REF: &str = "refs/tinfirm/sandbox";

/// What `git config` writes as `origin` before [`Git::set_origin_url`] puts the real URL
/// into `.git/config`.
const ORIGIN_PLACEHOLDER: &str = "http://tinfirm.invalid/git-proxy-origin";

/// Settings of every host-side git command. They make git ignore what an agent could plant
/// in a workspace, and keep git from asking anyone for anything.
const SAFE_SETTINGS: [&str; 12] = [
    "core.hooksPath=/dev/null",
    "core.fsmonitor=false",
    "core.sshCommand=false",
    "core.commitGraph=false",
    "core.multiPackIndex=false",
    "credential.helper=",
    "commit.gpgsign=false",
    "tag.gpgsign=false",
    "core.pager=cat",
    "protocol.ext.allow=never",
    "advice.detachedHead=false",
    "init.defaultBranch=main",
];

/// git's pack window (`core.packedGitWindowSize`) on every host-side git command: larger than
/// any pack the host fetches or makes, so that git maps a whole pack at once and closes its
/// file descriptor right away. git's bundle transport (2.43 to 2.48; 2.49 is fixed) closes the bundle's descriptor a
/// second time once a fetch from a bundle has updated the refs, and by then the new pack can
/// hold that number: a later read past the pack's first window (1 GiB by default) then failed
/// with "packfile … cannot be mapped: Bad file descriptor", and a sandbox's commits of over
/// 1 GiB did not come back ([`Git::import_bundle`]). A bundle has 8 GiB at most
/// (`BUNDLE_LIMITS` of the sandbox bridge), plus the bases `index-pack --fix-thin` appends;
/// 1 TiB leaves room for both. git maps at most the pack's size, so the value costs only
/// address space. Not on Windows, whose git reads the value into a 32-bit `unsigned long`.
#[cfg(not(windows))]
pub const PACK_WINDOW_BYTES: u64 = 1 << 40;

/// A git command in `cwd`. With `authorization`, the `Authorization` header goes in through
/// the `GIT_CONFIG_*` environment (never the command line or a config file).
pub(crate) fn command(cwd: &Path, authorization: Option<&Secret>) -> Command {
    configure(Command::new("git"), cwd, authorization)
}

/// Like [`command`] without credentials, at the lowest CPU priority (`nice -n 19 git …`),
/// for maintenance such as `git gc`. Spawning it fails with `NotFound` where there is no
/// `nice`.
pub(crate) fn low_priority_command(cwd: &Path) -> Command {
    let mut cmd = Command::new("nice");
    cmd.args(["-n", "19", "git"]);
    configure(cmd, cwd, None)
}

fn configure(mut cmd: Command, cwd: &Path, authorization: Option<&Secret>) -> Command {
    for setting in SAFE_SETTINGS {
        cmd.arg("-c").arg(setting);
    }
    #[cfg(not(windows))]
    cmd.arg("-c")
        .arg(format!("core.packedGitWindowSize={PACK_WINDOW_BYTES}"));
    cmd.current_dir(cwd)
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_ASKPASS", "true")
        .env("SSH_ASKPASS", "true")
        // `refs/replace/*` in a workspace could swap the objects the scan looks at.
        .env("GIT_NO_REPLACE_OBJECTS", "1")
        .env_remove("GIT_DIR")
        .env_remove("GIT_WORK_TREE")
        .env_remove("GIT_INDEX_FILE")
        .env_remove("GIT_OBJECT_DIRECTORY")
        .env_remove("GIT_ALTERNATE_OBJECT_DIRECTORIES")
        .env_remove("GIT_CONFIG_PARAMETERS")
        .env_remove("GIT_REPLACE_REF_BASE")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    match authorization {
        Some(value) => {
            cmd.env("GIT_CONFIG_COUNT", "1")
                .env("GIT_CONFIG_KEY_0", "http.extraHeader")
                .env(
                    "GIT_CONFIG_VALUE_0",
                    format!("Authorization: {}", value.expose()),
                );
        }
        None => {
            cmd.env_remove("GIT_CONFIG_COUNT");
        }
    }
    cmd
}

/// Runs git in `cwd` and collects its output.
pub(crate) async fn run(
    cwd: &Path,
    authorization: Option<&Secret>,
    args: &[&str],
) -> Result<GitOutput> {
    let output = command(cwd, authorization)
        .args(args)
        .output()
        .await
        .context("cannot run git; is it installed?")?;
    Ok(GitOutput {
        status: output.status,
        stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
    })
}

/// Like [`run`], but fails on a non-zero exit and returns stdout.
pub(crate) async fn run_ok(
    cwd: &Path,
    authorization: Option<&Secret>,
    args: &[&str],
) -> Result<String> {
    let out = run(cwd, authorization, args).await?;
    if !out.status.success() {
        bail!(
            "git {} failed: {}",
            args.first().unwrap_or(&""),
            out.stderr.trim()
        );
    }
    Ok(out.stdout)
}

/// Runs git with `input` on stdin (revision lists that can be long).
async fn run_with_input(cwd: &Path, args: &[&str], input: String) -> Result<GitOutput> {
    let mut child = command(cwd, None)
        .args(args)
        .stdin(Stdio::piped())
        .spawn()
        .context("cannot run git; is it installed?")?;
    let mut stdin = child.stdin.take().context("no stdin")?;
    let writer = tokio::spawn(async move {
        let _ = stdin.write_all(input.as_bytes()).await;
        let _ = stdin.shutdown().await;
    });
    let output = child.wait_with_output().await?;
    let _ = writer.await;
    Ok(GitOutput {
        status: output.status,
        stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
    })
}

/// The `Authorization` header value (`Basic …`) for a repository's credentials.
pub fn authorization_for(spec: &WorkspaceSpec) -> Option<Secret> {
    spec.git.as_ref().map(|g| {
        let basic = base64::engine::general_purpose::STANDARD.encode(format!(
            "{}:{}",
            g.username,
            g.token.expose()
        ));
        Secret::new(format!("Basic {basic}"))
    })
}

/// A branch or tag of a mirror and the object it points at.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MirrorRef {
    /// Full ref name, e.g. `refs/heads/main`.
    pub name: String,
    /// Object name (a commit, or an annotated tag).
    pub sha: String,
}

/// Object names of `refs` without duplicates.
pub fn ref_shas(refs: &[MirrorRef]) -> Vec<String> {
    let mut seen = HashSet::new();
    refs.iter()
        .filter(|r| seen.insert(r.sha.as_str()))
        .map(|r| r.sha.clone())
        .collect()
}

/// The tip of `branch` among `refs`.
pub fn branch_tip<'a>(refs: &'a [MirrorRef], branch: &str) -> Option<&'a str> {
    let name = format!("refs/heads/{branch}");
    refs.iter().find(|r| r.name == name).map(|r| r.sha.as_str())
}

/// Host-side git of one repository in a run's workspace.
#[derive(Debug, Clone)]
pub struct Git {
    dir: PathBuf,
    repo_url: String,
    /// Value of the `Authorization` header (`Basic …`).
    authorization: Option<Secret>,
    author_name: String,
    author_email: String,
    config_snapshot: Option<Vec<u8>>,
    /// The mirror's refs when the workspace was cloned (host-controlled).
    base: Vec<MirrorRef>,
}

impl Git {
    /// `identity` is the (name, email) for commits the host makes (the WIP commit).
    pub fn new(dir: impl Into<PathBuf>, spec: &WorkspaceSpec, identity: (String, String)) -> Git {
        Git {
            dir: dir.into(),
            repo_url: spec.repo_url.clone(),
            authorization: authorization_for(spec),
            author_name: identity.0,
            author_email: identity.1,
            config_snapshot: None,
            base: Vec::new(),
        }
    }

    /// A clone of an earlier process of this host's, whose run it takes back (ADR-0101): `base`
    /// and `config_snapshot` as the clone kept them.
    pub fn restored(
        dir: impl Into<PathBuf>,
        spec: &WorkspaceSpec,
        identity: (String, String),
        base: Vec<MirrorRef>,
        config_snapshot: Option<Vec<u8>>,
    ) -> Git {
        Git {
            base,
            config_snapshot,
            ..Git::new(dir, spec, identity)
        }
    }

    /// `.git/config` as the clone wrote it.
    pub fn config_snapshot(&self) -> Option<&[u8]> {
        self.config_snapshot.as_deref()
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn repo_url(&self) -> &str {
        &self.repo_url
    }

    /// The `Authorization` header value, for the redactor, the mirror and the git proxy.
    pub fn authorization(&self) -> Option<&Secret> {
        self.authorization.as_ref()
    }

    /// The mirror's refs at clone time.
    pub fn base_refs(&self) -> &[MirrorRef] {
        &self.base
    }

    async fn run(&self, auth: bool, args: &[&str]) -> Result<GitOutput> {
        let authorization = if auth {
            self.authorization.as_ref()
        } else {
            None
        };
        run(&self.dir, authorization, args).await
    }

    async fn run_ok(&self, auth: bool, args: &[&str]) -> Result<String> {
        let out = self.run(auth, args).await?;
        if !out.status.success() {
            bail!(
                "git {} failed: {}",
                args.first().unwrap_or(&""),
                out.stderr.trim()
            );
        }
        Ok(out.stdout)
    }

    /// Clones the workspace from the host's mirror (`mirror`, whose refs are `refs` right
    /// now), sets `origin` to the repository URL, then checks out `branch` from `origin` if
    /// it exists, or creates it from `origin/<default_branch>`. No network and no token: the
    /// mirror is up to date and complete.
    pub async fn clone_from_mirror(
        &mut self,
        mirror: &Path,
        refs: &[MirrorRef],
        spec: &WorkspaceSpec,
    ) -> Result<String> {
        let parent = self
            .dir
            .parent()
            .context("workspace has no parent directory")?
            .to_path_buf();
        let mirror_arg = mirror.to_string_lossy().into_owned();
        let dir_arg = self.dir.to_string_lossy().into_owned();
        // A local clone would hard-link the mirror's object files, and the agent could then
        // write into the mirror through them. `--no-hardlinks` copies them.
        let out = run(
            &parent,
            None,
            &[
                "clone",
                "--no-hardlinks",
                "--no-checkout",
                "--quiet",
                "--origin",
                "origin",
                "--",
                &mirror_arg,
                &dir_arg,
            ],
        )
        .await?;
        if !out.status.success() {
            bail!("git clone from the mirror failed: {}", out.stderr.trim());
        }
        let git_dir = self.dir.join(".git");
        for link in ["objects/info/alternates", "commondir"] {
            if git_dir.join(link).exists() {
                bail!("the workspace clone links back to the mirror ({link})");
            }
        }
        // From here on `origin` is the repository itself (the proxy URL replaces it while
        // the agent runs; `sanitize` brings it back).
        self.run_ok(false, &["config", "remote.origin.url", &self.repo_url])
            .await?;
        let remote_branch = format!("refs/remotes/origin/{}", spec.branch);
        let exists = self
            .run(false, &["rev-parse", "--verify", "--quiet", &remote_branch])
            .await?
            .status
            .success();
        let start = if exists {
            format!("origin/{}", spec.branch)
        } else {
            let default = format!("refs/remotes/origin/{}", spec.default_branch);
            if !self
                .run(false, &["rev-parse", "--verify", "--quiet", &default])
                .await?
                .status
                .success()
            {
                bail!(
                    "the default branch `{}` does not exist in the repository",
                    spec.default_branch
                );
            }
            format!("origin/{}", spec.default_branch)
        };
        let out = self
            .run(
                false,
                &["checkout", "--no-track", "-B", &spec.branch, &start],
            )
            .await?;
        if !out.status.success() {
            bail!("git checkout failed: {}", out.stderr.trim());
        }
        self.snapshot_config()?;
        self.base = refs.to_vec();
        self.head().await
    }

    /// Keeps the `.git/config` written at clone time (it holds no secrets).
    fn snapshot_config(&mut self) -> Result<()> {
        let config = self.dir.join(".git/config");
        self.config_snapshot = Some(
            std::fs::read(&config).with_context(|| format!("cannot read {}", config.display()))?,
        );
        Ok(())
    }

    /// Points `origin` at the git proxy for the agent. The URL holds the route secret, so
    /// git writes a placeholder and the URL goes into `.git/config` directly, never onto a
    /// command line. [`Git::sanitize`] restores the clone-time `origin` before the host runs
    /// git against the remote again.
    pub async fn set_origin_url(&self, url: &str) -> Result<()> {
        // Characters that git config would need quoted; proxy URLs never contain them.
        if url
            .chars()
            .any(|c| c.is_whitespace() || c.is_control() || matches!(c, '"' | '\\' | ';' | '#'))
        {
            bail!("the git proxy URL cannot be written to .git/config");
        }
        self.run_ok(false, &["config", "remote.origin.url", ORIGIN_PLACEHOLDER])
            .await?;
        let path = self.dir.join(".git/config");
        let config = std::fs::read_to_string(&path)
            .with_context(|| format!("cannot read {}", path.display()))?;
        if config.matches(ORIGIN_PLACEHOLDER).count() != 1 {
            bail!("cannot set the origin URL in .git/config");
        }
        std::fs::write(&path, config.replace(ORIGIN_PLACEHOLDER, url))
            .with_context(|| format!("cannot write {}", path.display()))?;
        Ok(())
    }

    /// Undoes what an agent could have planted in `.git` to attack host-side git.
    pub fn sanitize(&self) -> Result<()> {
        let git_dir = self.dir.join(".git");
        let meta = std::fs::symlink_metadata(&git_dir).context("the workspace has no .git")?;
        if !meta.is_dir() || meta.file_type().is_symlink() {
            bail!(".git in the workspace is not a directory");
        }
        remove_symlinks(&git_dir)?;
        let config = git_dir.join("config");
        let _ = std::fs::remove_file(&config);
        if let Some(snapshot) = &self.config_snapshot {
            std::fs::write(&config, snapshot)?;
        }
        for planted in [
            "objects/info/alternates",
            "objects/info/http-alternates",
            "objects/info/commit-graph",
            "objects/pack/multi-pack-index",
            "commondir",
            "info/grafts",
            "shallow",
        ] {
            let _ = std::fs::remove_file(git_dir.join(planted));
        }
        for planted in ["hooks", "objects/info/commit-graphs"] {
            let path = git_dir.join(planted);
            if path.exists() {
                let _ = std::fs::remove_dir_all(&path);
            }
        }
        Ok(())
    }

    pub async fn head(&self) -> Result<String> {
        Ok(self
            .run_ok(false, &["rev-parse", "HEAD"])
            .await?
            .trim()
            .to_string())
    }

    /// `git add -A` + commit when the worktree has changes. Returns `true` if it committed.
    pub async fn commit_all(&self, message: &str) -> Result<bool> {
        let status = self.run_ok(false, &["status", "--porcelain"]).await?;
        if status.trim().is_empty() {
            return Ok(false);
        }
        self.run_ok(false, &["add", "-A"]).await?;
        let name = format!("user.name={}", self.author_name);
        let email = format!("user.email={}", self.author_email);
        self.run_ok(
            false,
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
        )
        .await?;
        Ok(true)
    }

    /// Of `shas`, the objects that exist in the workspace. An object name is a hash of the
    /// content, so an object the host knows by name is that object.
    pub async fn present(&self, shas: &[String]) -> Result<HashSet<String>> {
        if shas.is_empty() {
            return Ok(HashSet::new());
        }
        let mut input = String::new();
        for sha in shas {
            if !is_object_name(sha) {
                bail!("`{sha}` is not an object name");
            }
            input.push_str(sha);
            input.push('\n');
        }
        let out = run_with_input(&self.dir, &["cat-file", "--batch-check"], input).await?;
        if !out.status.success() {
            bail!("git cat-file failed: {}", out.stderr.trim());
        }
        Ok(out
            .stdout
            .lines()
            .filter(|line| !line.ends_with(" missing"))
            .filter_map(|line| line.split(' ').next())
            .map(str::to_string)
            .collect())
    }

    /// With the sandbox bridge (ADR-0095): takes the commits of a bundle that the sandbox made
    /// into this clone, which the agent never touched, every object checked (`fsckObjects`),
    /// and moves HEAD (the task branch) and the work tree to `head`, which must be the
    /// bundle's HEAD.
    pub async fn import_bundle(&self, bundle: &Path, head: &str) -> Result<()> {
        if !is_object_name(head) {
            bail!("`{head}` is not an object name");
        }
        let bundle = bundle.to_string_lossy().into_owned();
        self.run_ok(
            false,
            &[
                "-c",
                "fetch.fsckObjects=true",
                "-c",
                "transfer.fsckObjects=true",
                "fetch",
                "--quiet",
                "--no-tags",
                "--",
                &bundle,
                &format!("+HEAD:{IMPORTED_REF}"),
            ],
        )
        .await
        .context("the sandbox's commits do not fetch")?;
        let fetched = self
            .run_ok(false, &["rev-parse", "--verify", IMPORTED_REF])
            .await?;
        let _ = self.run(false, &["update-ref", "-d", IMPORTED_REF]).await;
        if fetched.trim() != head {
            bail!("the sandbox's bundle holds another HEAD than it reported");
        }
        self.import_head(head).await
    }

    /// Moves HEAD (the task branch) and the work tree to `head`, a commit this clone has.
    pub async fn import_head(&self, head: &str) -> Result<()> {
        if !is_object_name(head) {
            bail!("`{head}` is not an object name");
        }
        let commit = format!("{head}^{{commit}}");
        if !self
            .run(false, &["cat-file", "-e", &commit])
            .await?
            .status
            .success()
        {
            bail!("the sandbox's HEAD {head} is not in the repository");
        }
        self.run_ok(false, &["reset", "--hard", "--quiet", head])
            .await?;
        Ok(())
    }

    /// Commits on HEAD that are not reachable from `base` (the default branch's tip as the
    /// host knows it).
    pub async fn count_ahead(&self, base: &str) -> Result<u64> {
        if !is_object_name(base) {
            bail!("`{base}` is not an object name");
        }
        let out = run_with_input(
            &self.dir,
            &["rev-list", "--count", "--stdin"],
            format!("HEAD\n^{base}\n"),
        )
        .await?;
        if !out.status.success() {
            bail!("git rev-list failed: {}", out.stderr.trim());
        }
        out.stdout
            .trim()
            .parse()
            .map_err(|_| anyhow!("unexpected rev-list output `{}`", out.stdout.trim()))
    }

    /// `true` when a credential value appears in anything that would be pushed: author
    /// names, commit messages and every object reachable from HEAD but not from `exclude`
    /// (the mirror's ref tips, which the host controls; never the workspace's own refs).
    pub async fn outgoing_contains_secret(
        &self,
        redactor: &Redactor,
        exclude: &[String],
    ) -> Result<bool> {
        let mut revisions = String::from("HEAD\n");
        for sha in exclude {
            if !is_object_name(sha) {
                bail!("`{sha}` is not an object name");
            }
            revisions.push('^');
            revisions.push_str(sha);
            revisions.push('\n');
        }
        let messages = run_with_input(
            &self.dir,
            &["log", "--format=%an%n%ae%n%cn%n%ce%n%B", "--stdin"],
            revisions.clone(),
        )
        .await?;
        if !messages.status.success() {
            bail!("git log failed: {}", messages.stderr.trim());
        }
        if redactor.contains(&messages.stdout) {
            return Ok(true);
        }
        let objects = run_with_input(
            &self.dir,
            &[
                "rev-list",
                "--objects",
                "--no-object-names",
                "--missing=allow-promisor",
                "--stdin",
            ],
            revisions,
        )
        .await?;
        if !objects.status.success() {
            bail!("git rev-list failed: {}", objects.stderr.trim());
        }
        if objects.stdout.trim().is_empty() {
            return Ok(false);
        }
        let content = run_with_input(&self.dir, &["cat-file", "--batch"], objects.stdout).await?;
        if !content.status.success() {
            bail!("git cat-file failed: {}", content.stderr.trim());
        }
        Ok(redactor.contains(&content.stdout))
    }

    /// Pushes HEAD to `refs/heads/<branch>` of the repository URL with a lease on the tip the
    /// branch had in the mirror at clone time: `--force-with-lease=refs/heads/<branch>:<tip>`,
    /// or `--force-with-lease=refs/heads/<branch>:` when the branch did not exist then, which
    /// requires that it still does not. So the agent may rewrite the branch (a rebase onto the
    /// default branch), but a push that someone else made during the run, or a deletion, is
    /// never overwritten: git refuses the push with `[rejected] (stale info)`, and the
    /// repository checks the expected value again when it updates the ref. Never a plain
    /// force. The URL is named explicitly, so nothing in `.git/config` decides where the push
    /// goes.
    pub async fn push(&self, branch: &str) -> Result<GitOutput> {
        let refname = format!("refs/heads/{branch}");
        let expected = match branch_tip(&self.base, branch) {
            Some(sha) if !is_object_name(sha) => bail!("`{sha}` is not an object name"),
            Some(sha) => sha,
            None => "",
        };
        let lease = format!("--force-with-lease={refname}:{expected}");
        let refspec = format!("HEAD:{refname}");
        self.run(
            true,
            &[
                "push",
                "--porcelain",
                "--no-verify",
                &lease,
                "--",
                &self.repo_url,
                &refspec,
            ],
        )
        .await
    }
}

/// A full hexadecimal object name (SHA-1 or SHA-256).
fn is_object_name(value: &str) -> bool {
    (value.len() == 40 || value.len() == 64) && value.bytes().all(|b| b.is_ascii_hexdigit())
}

fn remove_symlinks(dir: &Path) -> Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        if file_type.is_symlink() {
            std::fs::remove_file(entry.path())?;
        } else if file_type.is_dir() {
            remove_symlinks(&entry.path())?;
        }
    }
    Ok(())
}

/// `true` when git is on PATH (for `doctor`).
pub async fn git_version() -> Option<String> {
    let out = Command::new("git").arg("--version").output().await.ok()?;
    out.status
        .success()
        .then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn object_names() {
        assert!(is_object_name(&"a".repeat(40)));
        assert!(is_object_name(&"0".repeat(64)));
        assert!(!is_object_name("HEAD"));
        assert!(!is_object_name(&format!("-{}", "a".repeat(39))));
        assert!(!is_object_name(&"g".repeat(40)));
    }

    #[test]
    fn refs_helpers() {
        let refs = vec![
            MirrorRef {
                name: "refs/heads/main".into(),
                sha: "a".repeat(40),
            },
            MirrorRef {
                name: "refs/tags/v1".into(),
                sha: "a".repeat(40),
            },
            MirrorRef {
                name: "refs/heads/agent/X-1".into(),
                sha: "b".repeat(40),
            },
        ];
        assert_eq!(ref_shas(&refs), vec!["a".repeat(40), "b".repeat(40)]);
        assert_eq!(branch_tip(&refs, "main"), Some("a".repeat(40).as_str()));
        assert_eq!(
            branch_tip(&refs, "agent/X-1"),
            Some("b".repeat(40).as_str())
        );
        assert_eq!(branch_tip(&refs, "v1"), None);
    }
}
