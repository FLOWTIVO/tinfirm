//! The sandbox of a run (docs/archive/v1/CONVENTIONS.md §15.3): one Docker container whose main process is the
//! ACP adapter; the host attaches to its stdio through the Docker API.
//!
//! The Docker runtime of the containers, and whether the agent gets its own Docker daemon in
//! the sandbox, are settings of the host ([`SandboxSettings`], ADR-0025).
//!
//! A second executor, `Vm`, creates a VM per run through a driver (crate::vm, ADR-0096) and
//! runs the agent there over the bridge.
//!
//! A third executor, `Local`, runs an ACP agent as a plain child process. It has no
//! isolation at all and exists only for the host's own tests; the CLI never selects it.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::pin::Pin;
use std::process::Stdio;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use bollard::Docker;
use bollard::container::LogOutput;
use bollard::models::{ContainerCreateBody, HostConfig, HostConfigCgroupnsModeEnum};
use bollard::query_parameters::{
    AttachContainerOptionsBuilder, CreateContainerOptionsBuilder, LogsOptionsBuilder,
    StartContainerOptions, StopContainerOptionsBuilder, WaitContainerOptions,
};
use futures::StreamExt;
use tinfirm_bridge::control::{Hidden, ProbeCheck, Start};
use tinfirm_bridge::host::{
    Exit, HostIo, HostSession, Output, Registration, SandboxFiles, TunnelTarget,
};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite};
use tokio::sync::{mpsc, watch};

use crate::docker::remove_container;

#[derive(Debug, Clone)]
pub struct Bind {
    pub host: PathBuf,
    pub container: String,
    pub read_only: bool,
}

/// Resource limits of a sandbox (docs/archive/v1/CONVENTIONS.md §15.3 defaults).
#[derive(Debug, Clone, Copy)]
pub struct Resources {
    pub memory_bytes: i64,
    pub nano_cpus: i64,
    pub pids_limit: i64,
}

impl Default for Resources {
    fn default() -> Self {
        Resources {
            memory_bytes: 4 * 1024 * 1024 * 1024,
            nano_cpus: 2_000_000_000,
            pids_limit: 1024,
        }
    }
}

#[derive(Debug, Clone)]
pub struct SandboxSpec {
    pub name: String,
    pub image: String,
    pub env: Vec<(String, String)>,
    pub binds: Vec<Bind>,
    pub labels: HashMap<String, String>,
    pub resources: Resources,
    /// Docker network mode; `None` uses Docker's default.
    pub network_mode: Option<String>,
    /// `host:ip` entries for the container's `/etc/hosts` (the git proxy's host name).
    pub extra_hosts: Vec<String>,
    /// DNS servers of the container (empty: Docker's).
    pub dns: Vec<String>,
    /// Docker runtime (`HostConfig.runtime`); `None` uses Docker's default.
    pub runtime: Option<String>,
    /// The agent's own Docker daemon runs in the sandbox: the container starts as root and
    /// unconfined ([`container_body`]), and the image's entrypoint drops to [`USER`] after
    /// starting dockerd. Only with a VM runtime ([`SandboxSettings::is_vm`]).
    pub docker: bool,
    /// With the sandbox bridge (ADR-0095): the binary mounted read-only at
    /// [`crate::bridge::SANDBOX_PATH`] and run as the container's command, `bridge`. The
    /// container is then not attached; the adapter's stdio goes over the bridge.
    pub bridge_binary: Option<PathBuf>,
    /// The adapter to start in place of the image's own command: the multi-runtime image
    /// has none, so the host names the run's runtime's (ADR-0101). `None`: the image's.
    pub command: Option<Vec<String>>,
    /// Host paths for the local test executor: working directory and HOME.
    pub local_cwd: PathBuf,
    pub local_home: PathBuf,
}

/// Set to `1` in a sandbox with its own Docker daemon; the image's entrypoint then starts
/// dockerd (`runner/images/base/tinfirm-sandbox-entrypoint`).
pub const DOCKER_ENV: &str = "TINFIRM_SANDBOX_DOCKER";
/// `UID:GID` that the entrypoint of a sandbox with Docker runs the agent as, after dockerd
/// has started as root: [`USER`].
pub const AGENT_USER_ENV: &str = "TINFIRM_SANDBOX_AGENT_USER";

/// Isolation tier of the sandboxes (ADR-0006), given by the Docker runtime (ADR-0025).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tier {
    /// runc or another runtime that shares the host kernel.
    Container,
    /// gVisor (`runsc…`).
    Gvisor,
    /// Kata Containers (`kata…`): every sandbox is a microVM with its own kernel.
    Microvm,
}

impl Tier {
    pub fn as_str(self) -> &'static str {
        match self {
            Tier::Container => "container",
            Tier::Gvisor => "gvisor",
            Tier::Microvm => "microvm",
        }
    }

    /// The tier as `hello.isolationTiers` names it.
    pub fn isolation_tier(self) -> tinfirm_protocol::IsolationTier {
        match self {
            Tier::Container => tinfirm_protocol::IsolationTier::Container,
            Tier::Gvisor => tinfirm_protocol::IsolationTier::Gvisor,
            Tier::Microvm => tinfirm_protocol::IsolationTier::Microvm,
        }
    }
}

/// How the host creates its sandboxes (`run --sandbox-runtime`, `--sandbox-docker`,
/// `--sandbox-env`; docs/archive/v1/CONVENTIONS.md §15.3, ADR-0025). Built with [`SandboxSettings::new`], which
/// enforces the rules.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SandboxSettings {
    /// Docker runtime of the containers (`HostConfig.runtime`), e.g. `kata`; `None` is
    /// Docker's default runtime (normally `runc`).
    pub runtime: Option<String>,
    /// The agent gets its own Docker daemon in the sandbox. Only with a `kata…` runtime: the
    /// container then has every capability and no seccomp or AppArmor profile, which under
    /// runc is root on this machine.
    pub docker: bool,
    /// Extra environment of every sandbox, in order: mirror settings and the like. Never
    /// credentials: the values are visible to the agent and in `docker inspect`, and they are
    /// not redacted.
    pub env: Vec<(String, String)>,
    /// Every sandbox is a VM of its own, created for its run (the VM executor, crate::vm):
    /// there is no Docker runtime on this host, and the VM is the boundary.
    pub per_run_vm: bool,
}

impl SandboxSettings {
    /// Checks the settings: a valid runtime name, Docker in the sandbox only with a `kata…`
    /// runtime, and no variable twice in `env` (each entry is checked by [`parse_env_entry`]).
    pub fn new(
        runtime: Option<String>,
        docker: bool,
        env: Vec<(String, String)>,
    ) -> Result<SandboxSettings> {
        let runtime = runtime
            .map(|r| r.trim().to_string())
            .filter(|r| !r.is_empty());
        if let Some(name) = &runtime {
            validate_runtime_name(name)?;
        }
        let settings = SandboxSettings {
            runtime,
            docker,
            env,
            per_run_vm: false,
        };
        if settings.docker && !settings.is_vm() {
            bail!(
                "--sandbox-docker needs a Kata Containers runtime (--sandbox-runtime kata, or another name that starts with `kata`): the agent's Docker needs a container with every capability and no seccomp or AppArmor profile, and such a container under runc or any other runtime that shares this machine's kernel is root on this machine"
            );
        }
        let mut seen = HashSet::new();
        for (key, _) in &settings.env {
            if !seen.insert(key.as_str()) {
                bail!("--sandbox-env sets {key} more than once");
            }
        }
        Ok(settings)
    }

    /// The settings of the VM executor's sandboxes, each a VM of its own: no Docker runtime
    /// to name, and the agent may have its own Docker in it (`docker`), as under Kata.
    pub fn for_vms(
        runtime: Option<String>,
        docker: bool,
        env: Vec<(String, String)>,
    ) -> Result<SandboxSettings> {
        if runtime.as_deref().is_some_and(|r| !r.trim().is_empty()) {
            bail!(
                "--sandbox-runtime names a Docker runtime of this host; with --executor vm every sandbox is a VM of its own, created per run, and this host runs none"
            );
        }
        let mut settings = SandboxSettings::new(None, false, env)?;
        settings.docker = docker;
        settings.per_run_vm = true;
        Ok(settings)
    }

    /// Whether every sandbox is a VM of its own: a VM per run, or a Kata Containers runtime,
    /// by name.
    pub fn is_vm(&self) -> bool {
        self.per_run_vm
            || self
                .runtime
                .as_deref()
                .is_some_and(|r| r.starts_with("kata"))
    }

    /// The isolation tier that the runtime gives.
    pub fn tier(&self) -> Tier {
        if self.per_run_vm {
            return Tier::Microvm;
        }
        match self.runtime.as_deref() {
            Some(r) if r.starts_with("kata") => Tier::Microvm,
            Some(r) if r.starts_with("runsc") => Tier::Gvisor,
            _ => Tier::Container,
        }
    }

    /// The names of the extra variables, for logs (the values stay out of them).
    pub fn env_keys(&self) -> Vec<&str> {
        self.env.iter().map(|(k, _)| k.as_str()).collect()
    }
}

/// A Docker runtime name as `daemon.json` registers it: letters, digits, `.`, `_` and `-`.
fn validate_runtime_name(name: &str) -> Result<()> {
    let valid = name.len() <= 128
        && name.starts_with(|c: char| c.is_ascii_alphanumeric())
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
    if !valid {
        bail!(
            "--sandbox-runtime `{name}` is not a Docker runtime name (letters, digits, `.`, `_` and `-`)"
        );
    }
    Ok(())
}

/// The runtime the sandboxes get: the configured one, which Docker must know (`known`, the
/// runtimes of `docker info`), or Docker's default.
pub fn effective_runtime(
    settings: &SandboxSettings,
    known: Option<&[String]>,
    default: Option<&str>,
) -> Result<String> {
    match &settings.runtime {
        Some(name) => {
            if let Some(known) = known
                && !known.iter().any(|k| k == name)
            {
                let mut names: Vec<&str> = known.iter().map(String::as_str).collect();
                names.sort_unstable();
                bail!(
                    "Docker has no runtime `{name}` (it has: {}). Register it in /etc/docker/daemon.json and restart Docker (runner/README.md, \"Kata Containers and Docker in the sandbox\")",
                    names.join(", ")
                );
            }
            Ok(name.clone())
        }
        None => Ok(default.unwrap_or("runc").to_string()),
    }
}

/// Asks Docker for its runtimes and returns [`effective_runtime`]: `run` refuses to start
/// with a runtime that Docker does not know, instead of failing every run.
pub async fn check_runtime(docker: &Docker, settings: &SandboxSettings) -> Result<String> {
    let info = docker
        .info()
        .await
        .context("cannot read the runtimes of Docker")?;
    let known: Option<Vec<String>> = info.runtimes.map(|r| r.into_keys().collect());
    effective_runtime(settings, known.as_deref(), info.default_runtime.as_deref())
}

/// Parses one `--sandbox-env KEY=VALUE`. The key must be a plain variable name that the host
/// does not set itself, and neither the key nor the value may look like a credential: these
/// values reach every sandbox unredacted.
pub fn parse_env_entry(entry: &str) -> std::result::Result<(String, String), String> {
    // The error never repeats the value: it may be a secret that does not belong here.
    let Some((key, value)) = entry.split_once('=') else {
        return Err("expected KEY=VALUE".into());
    };
    let valid_name = !key.is_empty()
        && key.len() <= 128
        && !key.starts_with(|c: char| c.is_ascii_digit())
        && key.chars().all(|c| c.is_ascii_alphanumeric() || c == '_');
    if !valid_name {
        return Err(format!(
            "`{key}` is not a variable name (letters, digits and `_`, not starting with a digit)"
        ));
    }
    if reserved_env_key(key) {
        return Err(format!(
            "{key} is set by the runner host itself and cannot be changed with --sandbox-env"
        ));
    }
    if looks_like_credential_name(key) {
        return Err(format!(
            "{key} looks like a credential; --sandbox-env reaches every sandbox unredacted and must never carry credentials"
        ));
    }
    if value.chars().any(|c| c.is_control()) {
        return Err(format!("the value of {key} contains a control character"));
    }
    if has_url_userinfo(value) {
        return Err(format!(
            "the value of {key} contains a URL with a user name or password; --sandbox-env must never carry credentials"
        ));
    }
    Ok((key.to_string(), value.to_string()))
}

/// Variables that the host sets for a run, or that the image needs as they are.
fn reserved_env_key(key: &str) -> bool {
    const RESERVED: &[&str] = &[
        "HOME",
        "PATH",
        "DISABLE_AUTOUPDATER",
        "DEFAULT_AUTH_REQUEST",
        "ANTHROPIC_MODEL",
        // The agent's effort (crate::runtime_env).
        "CLAUDE_CODE_EFFORT_LEVEL",
        // The endpoint of a company model credential (ADR-0027), and the provider settings of
        // the runtimes (ADR-0041, ADR-0042; crate::runtime_env).
        "ANTHROPIC_BASE_URL",
        "OPENAI_BASE_URL",
        "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
        "CLAUDE_CODE_USE_BEDROCK",
        "AWS_REGION",
        "AWS_DEFAULT_REGION",
        "AWS_PROFILE",
        "CODEX_HOME",
        // Where Claude Code keeps a login (ADR-0085; crate::run under the local executor).
        "CLAUDE_CONFIG_DIR",
        "CODEX_CONFIG",
        "OPENCODE_CONFIG",
        "OPENCODE_CONFIG_CONTENT",
        "OPENCODE_CONFIG_DIR",
        // Where OpenCode reads skills (ADR-0064; crate::runtime_env).
        "OPENCODE_DISABLE_CLAUDE_CODE_SKILLS",
        "PI_CODING_AGENT_DIR",
        "PI_OFFLINE",
        "GIT_TERMINAL_PROMPT",
        "GIT_AUTHOR_NAME",
        "GIT_AUTHOR_EMAIL",
        "GIT_COMMITTER_NAME",
        "GIT_COMMITTER_EMAIL",
        DOCKER_ENV,
        AGENT_USER_ENV,
    ];
    RESERVED.contains(&key)
        || key.starts_with("GIT_CONFIG")
        // TINFIRM_SANDBOX_* configures the image's entrypoint; the rest is the host's.
        || (key.starts_with("TINFIRM_") && !key.starts_with("TINFIRM_SANDBOX_"))
}

/// A name such as `NPM_TOKEN`, `ANTHROPIC_API_KEY` or `NPM_CONFIG__AUTH`.
fn looks_like_credential_name(key: &str) -> bool {
    let upper = key.to_ascii_uppercase();
    const PARTS: &[&str] = &[
        "TOKEN",
        "SECRET",
        "PASSWORD",
        "PASSWD",
        "CREDENTIAL",
        "APIKEY",
        "PRIVATE",
    ];
    const WORDS: &[&str] = &["KEY", "AUTH", "PASS", "PAT", "PWD"];
    PARTS.iter().any(|p| upper.contains(p)) || upper.split('_').any(|w| WORDS.contains(&w))
}

/// Whether a URL in `value` (a single URL or a list) has a user name or password.
pub(crate) fn has_url_userinfo(value: &str) -> bool {
    value.match_indices("://").any(|(at, sep)| {
        let rest = &value[at + sep.len()..];
        let authority = rest
            .split(|c: char| matches!(c, '/' | '?' | '#' | ',' | ';') || c.is_whitespace())
            .next()
            .unwrap_or("");
        authority.contains('@')
    })
}

/// How sandboxes are started.
#[derive(Clone)]
pub enum Executor {
    Docker(Docker),
    /// A VM per run, through a driver (`run --executor vm`, crate::vm).
    Vm(std::sync::Arc<crate::vm::Vms>),
    /// Test-only: run `program args…` as a child process on the host. No isolation.
    #[doc(hidden)]
    Local {
        program: String,
        args: Vec<String>,
    },
}

impl std::fmt::Debug for Executor {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Executor::Docker(_) => f.write_str("Docker"),
            Executor::Vm(vms) => write!(f, "{vms:?}"),
            Executor::Local { program, .. } => write!(f, "Local({program})"),
        }
    }
}

#[derive(Debug)]
pub enum SandboxOutput {
    Stdout(Vec<u8>),
    Stderr(Vec<u8>),
}

/// The stdio of a started sandbox. `output` closes when the process exits; it holds at most
/// [`OUTPUT_QUEUE`] chunks, so a reader that falls behind holds the sandbox's output back
/// where it is (the process's pipe, the bridge's buffers) instead of growing this process.
pub struct SandboxIo {
    pub stdin: Pin<Box<dyn AsyncWrite + Send>>,
    pub output: mpsc::Receiver<SandboxOutput>,
}

/// Chunks of a sandbox's output waiting for their reader ([`SandboxIo::output`]).
pub const OUTPUT_QUEUE: usize = 1024;

pub struct Sandbox {
    kind: SandboxKind,
}

enum SandboxKind {
    Docker {
        docker: Docker,
        name: String,
    },
    Local {
        child: tokio::process::Child,
    },
    /// A run's VM (crate::vm), claimed from the pool (crate::pool) and deleted when the
    /// sandbox stops; `busy` counts it in the pool until then.
    Vm {
        vm: crate::vm::VmGuard,
        busy: Option<crate::pool::Busy>,
    },
    /// A sandbox whose adapter runs over the bridge (ADR-0095); `inner` is its container or
    /// its local bridge process.
    Bridged {
        session: HostSession,
        exit: watch::Receiver<Option<Exit>>,
        inner: Box<SandboxKind>,
    },
    /// The local test executor's bridge process of the process that this host restarted from
    /// (ADR-0101): this one has no handle of it, and the bridge ends it.
    Adopted,
}

/// What a sandbox started with the bridge needs: its registration with the host's listener,
/// the address the sandbox connects to, the binary that runs the bridge there, the tunnels to
/// the host's listeners that the run uses, and the directories it gets before the adapter
/// starts.
pub struct Bridged {
    pub registration: Registration,
    pub addr: String,
    pub binary: PathBuf,
    pub connect_timeout: Duration,
    pub tunnels: Vec<TunnelTarget>,
    pub puts: Vec<Put>,
}

/// A directory of the host's that goes into the sandbox, in place of a mount (ADR-0095).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Put {
    pub host: PathBuf,
    pub sandbox: String,
    /// Paths below `host` that stay on the host.
    pub exclude: Vec<String>,
}

/// How long a sandbox's bridge has to listen for its tunnels once it is connected.
const TUNNELS_TIMEOUT: Duration = Duration::from_secs(30);
/// How long one directory may take to reach the sandbox.
const PUT_TIMEOUT: Duration = Duration::from_secs(600);
/// How long the network probe of a VM may take; its checks run at once.
const PROBE_TIMEOUT: Duration = Duration::from_secs(30);

pub const USER: &str = "1000:1000";
pub const WORKSPACE: &str = "/workspace";
pub const HOME: &str = "/home/agent";
/// Protocol v2: the writable folder for the documents of the run, saved as artifacts of the
/// job when the run ends ([`crate::output`]). Mounted from `outputs/<runId>`, a directory of
/// the host's outside the workspace, so that nothing the agent does to `/workspace` moves
/// what the host reads back.
pub const OUTPUT: &str = "/output";

impl Executor {
    /// The working directory the agent sees.
    pub fn agent_cwd(&self, spec: &SandboxSpec) -> String {
        match self {
            Executor::Docker(_) => WORKSPACE.to_string(),
            Executor::Vm(vms) if !vms.options.on_this_machine => WORKSPACE.to_string(),
            Executor::Vm(_) | Executor::Local { .. } => {
                spec.local_cwd.to_string_lossy().into_owned()
            }
        }
    }

    /// Whether the sandboxes are this machine's processes (the local test executor, and the
    /// VM executor's test mode), which share its network and file system.
    pub fn on_this_machine(&self) -> bool {
        match self {
            Executor::Docker(_) => false,
            Executor::Vm(vms) => vms.options.on_this_machine,
            Executor::Local { .. } => true,
        }
    }

    pub async fn start(&self, spec: &SandboxSpec) -> Result<(Sandbox, SandboxIo)> {
        match self {
            Executor::Docker(docker) => start_docker(docker, spec).await,
            Executor::Vm(_) => {
                bail!("the VM executor runs every agent over the sandbox bridge, which is off")
            }
            Executor::Local { program, args } => start_local(program, args, spec),
        }
    }

    /// Starts the sandbox with the bridge (ADR-0095): the sandbox's settings hold only what
    /// its entrypoint reads and the bridge's own settings; the run's other variables, its
    /// credentials among them, go to the adapter in `start`.
    pub async fn start_bridged(
        &self,
        spec: &SandboxSpec,
        bridged: Bridged,
    ) -> Result<(Sandbox, SandboxIo)> {
        let (settings, run_env) = crate::bridge::split_env(&spec.env);
        let bootstrap = crate::bridge::bootstrap_env(&bridged.registration, &bridged.addr);
        match self {
            Executor::Docker(docker) => {
                let argv = match &spec.command {
                    Some(command) => command.clone(),
                    None => image_command(docker, &spec.image).await?,
                };
                let mut container = spec.clone();
                container.env = settings.into_iter().chain(bootstrap).collect();
                container.bridge_binary = Some(bridged.binary.clone());
                create_container(docker, &container).await?;
                if let Err(e) = docker
                    .start_container(&container.name, None::<StartContainerOptions>)
                    .await
                {
                    let _ = remove_container(docker, &container.name).await;
                    return Err(e).context("cannot start the container");
                }
                let inner = SandboxKind::Docker {
                    docker: docker.clone(),
                    name: container.name.clone(),
                };
                let start = Start {
                    argv,
                    env: hide(run_env),
                    cwd: Some(WORKSPACE.to_string()),
                };
                finish_bridged(inner, bridged, start, &[]).await
            }
            Executor::Vm(_) => {
                bail!("a VM sandbox comes from the VM pool: the run claims it first")
            }
            Executor::Local { program, args } => {
                let mut cmd = tokio::process::Command::new(&bridged.binary);
                cmd.arg("bridge")
                    .env_clear()
                    .env("PATH", std::env::var("PATH").unwrap_or_default())
                    .envs(settings.iter().chain(&bootstrap).map(|(k, v)| (k, v)))
                    .stdin(Stdio::null())
                    .stdout(Stdio::null())
                    .kill_on_drop(true);
                let child = cmd.spawn().with_context(|| {
                    format!("cannot start the bridge {}", bridged.binary.display())
                })?;
                let mut argv = vec![program.clone()];
                argv.extend(args.iter().cloned());
                let mut env = run_env;
                env.push((
                    "HOME".to_string(),
                    spec.local_home.to_string_lossy().into_owned(),
                ));
                let start = Start {
                    argv,
                    env: hide(env),
                    cwd: Some(spec.local_cwd.to_string_lossy().into_owned()),
                };
                finish_bridged(SandboxKind::Local { child }, bridged, start, &[]).await
            }
        }
    }
}

fn hide(env: Vec<(String, String)>) -> Vec<(String, Hidden)> {
    env.into_iter().map(|(k, v)| (k, Hidden::new(v))).collect()
}

/// The image's command, the adapter: the bridge starts it, in place of the container.
async fn image_command(docker: &Docker, image: &str) -> Result<Vec<String>> {
    let inspect = docker
        .inspect_image(image)
        .await
        .with_context(|| format!("cannot inspect the image {image}"))?;
    let cmd = inspect.config.and_then(|c| c.cmd).unwrap_or_default();
    if cmd.is_empty() {
        bail!("the image {image} has no command (CMD) for the bridge to start");
    }
    Ok(cmd)
}

impl Executor {
    /// Starts the run's adapter in the VM it claimed from the pool (crate::pool): the VM's
    /// bridge is connected already. The VM's container started with the shape's settings and
    /// its egress proxy listener's, which must be the run's; the run's other variables, its
    /// credentials among them, go to the adapter in `start`.
    pub async fn start_claimed(
        &self,
        spec: &SandboxSpec,
        claimed: crate::pool::Claimed,
        reprobe_after: Duration,
        tunnels: Vec<TunnelTarget>,
        puts: Vec<Put>,
    ) -> Result<(Sandbox, SandboxIo)> {
        let Executor::Vm(vms) = self else {
            bail!("only the VM executor's sandboxes come from a pool");
        };
        if let Some(bind) = spec.binds.first() {
            bail!(
                "a VM sandbox takes no mounts, and this run needs one at {} (a Codex ChatGPT login is refreshed in place on this host: it needs the Docker executor)",
                bind.container
            );
        }
        let (mut settings, run_env) = crate::bridge::split_env(&spec.env);
        settings.sort();
        if spec.image != claimed.shape.image || settings != claimed.boot_settings {
            bail!(
                "the VM was created with other settings than the run's sandbox has; the run cannot use it"
            );
        }
        let options = &vms.options;
        let mut env = run_env;
        let cwd = if options.on_this_machine {
            env.push((
                "HOME".to_string(),
                spec.local_home.to_string_lossy().into_owned(),
            ));
            spec.local_cwd.to_string_lossy().into_owned()
        } else {
            WORKSPACE.to_string()
        };
        // No argv: the VM's container runs the image's own command; the multi-runtime image
        // has none, so the run's adapter is named.
        let start = Start {
            argv: spec.command.clone().unwrap_or_default(),
            env: hide(env),
            cwd: Some(cwd),
        };
        // A VM that has waited a while is probed again: the provider's firewall may have
        // changed since its first probe.
        let probe = if claimed.ready_at.elapsed() >= reprobe_after {
            options.probe.clone()
        } else {
            Vec::new()
        };
        let crate::pool::Claimed {
            vm, session, busy, ..
        } = claimed;
        finish_session(
            SandboxKind::Vm {
                vm,
                busy: Some(busy),
            },
            session,
            tunnels,
            puts,
            start,
            &probe,
        )
        .await
    }
}

/// Probes the sandbox's network from inside with `checks`, all at once: anything that gets
/// through is an error that names it.
pub async fn probe(session: &HostSession, checks: &[ProbeCheck]) -> Result<()> {
    if checks.is_empty() {
        return Ok(());
    }
    let open = session
        .files()
        .probe(checks.to_vec(), PROBE_TIMEOUT)
        .await?;
    if open.is_empty() {
        return Ok(());
    }
    bail!(
        "the sandbox reached what it must not ({}), so it was deleted: the VM template's network lets it out (its firewall, its routes or its cloud identity)",
        open.join(", ")
    )
}

/// Waits for the sandbox's bridge, then [`finish_session`].
async fn finish_bridged(
    mut inner: SandboxKind,
    bridged: Bridged,
    start: Start,
    probe: &[ProbeCheck],
) -> Result<(Sandbox, SandboxIo)> {
    let timeout = bridged.connect_timeout;
    let connected = tokio::select! {
        session = bridged.registration.connected(timeout) => session,
        early = exited(&mut inner) => Err(anyhow::anyhow!(
            "the sandbox ended before its bridge connected ({early})"
        )),
    };
    match connected {
        Ok(session) => {
            finish_session(inner, session, bridged.tunnels, bridged.puts, start, probe).await
        }
        Err(e) => Err(stopped(&mut inner, e).await),
    }
}

/// Stops the sandbox that failed with `e`, and adds its last log lines to the error.
async fn stopped(inner: &mut SandboxKind, e: anyhow::Error) -> anyhow::Error {
    let logs = last_logs(inner).await;
    stop_kind(inner, Duration::from_secs(1)).await;
    match logs {
        Some(logs) => e.context(format!("the sandbox's last log lines:\n{logs}")),
        None => e,
    }
}

/// With the sandbox's bridge connected: probes the sandbox's network with `probe` (when there
/// is any), opens the tunnels, puts the run's directories in, starts the adapter over it and
/// hands its stdio to the run.
async fn finish_session(
    mut inner: SandboxKind,
    mut session: HostSession,
    tunnels: Vec<TunnelTarget>,
    puts: Vec<Put>,
    start: Start,
    probe: &[ProbeCheck],
) -> Result<(Sandbox, SandboxIo)> {
    let started = async {
        self::probe(&session, probe).await?;
        if !tunnels.is_empty() {
            session.open_tunnels(&tunnels, TUNNELS_TIMEOUT).await?;
        }
        put_all(&session.files(), &puts).await?;
        session.start(start).await
    }
    .await;
    let (session, io) = match started.map(|io| (session, io)) {
        Ok(started) => started,
        Err(e) => return Err(stopped(&mut inner, e).await),
    };
    let HostIo {
        stdin,
        mut output,
        exit,
    } = io;
    let (tx, rx) = mpsc::channel(OUTPUT_QUEUE);
    tokio::spawn(async move {
        while let Some(item) = output.recv().await {
            let item = match item {
                Output::Stdout(bytes) => SandboxOutput::Stdout(bytes),
                Output::Stderr(bytes) => SandboxOutput::Stderr(bytes),
            };
            // A reader that is gone (the run's loop has ended) ends this; a full queue waits,
            // and the bridge's session holds its output back in turn.
            if tx.send(item).await.is_err() {
                break;
            }
        }
    });
    Ok((
        Sandbox {
            kind: SandboxKind::Bridged {
                session,
                exit,
                inner: Box::new(inner),
            },
        },
        SandboxIo {
            stdin: Box::pin(stdin),
            output: rx,
        },
    ))
}

/// Puts the run's directories into the sandbox, in order.
async fn put_all(files: &SandboxFiles, puts: &[Put]) -> Result<()> {
    for put in puts {
        files
            .put_dir(&put.host, &put.sandbox, put.exclude.clone(), PUT_TIMEOUT)
            .await
            .with_context(|| format!("cannot put {} into the sandbox", put.sandbox))?;
    }
    Ok(())
}

/// Resolves when the container or process of a sandbox has ended, with how.
async fn exited(kind: &mut SandboxKind) -> String {
    match kind {
        SandboxKind::Docker { docker, name } => {
            let mut wait = docker.wait_container(name, None::<WaitContainerOptions>);
            match wait.next().await {
                Some(Ok(response)) => format!("exit code {}", response.status_code),
                Some(Err(e)) => format!("{e}"),
                None => "it is gone".to_string(),
            }
        }
        SandboxKind::Local { child } => match child.wait().await {
            Ok(status) => status.to_string(),
            Err(e) => e.to_string(),
        },
        SandboxKind::Vm { vm, .. } => vm.vms().ended(&vm.id).await,
        SandboxKind::Bridged { .. } | SandboxKind::Adopted => std::future::pending().await,
    }
}

/// The last lines a container wrote (its entrypoint's, before the bridge took over).
async fn last_logs(kind: &SandboxKind) -> Option<String> {
    let SandboxKind::Docker { docker, name } = kind else {
        return None;
    };
    let options = LogsOptionsBuilder::default()
        .stdout(true)
        .stderr(true)
        .tail("40")
        .build();
    let mut logs = docker.logs(name, Some(options));
    let mut text = String::new();
    while let Some(Ok(line)) = logs.next().await {
        text.push_str(&String::from_utf8_lossy(&line.into_bytes()));
    }
    let text = text.trim_end().to_string();
    (!text.is_empty()).then_some(text)
}

/// Security options of a sandbox with Docker in it: no seccomp, AppArmor or SELinux profile.
/// The masked and read-only paths in `/proc` and `/sys` go away through empty `MaskedPaths`
/// and `ReadonlyPaths` in [`container_body`]: that is what the `docker` CLI sends for
/// `--security-opt systempaths=unconfined`, an option of the CLI that the Engine API rejects
/// ("invalid --security-opt 2").
pub const DOCKER_SECURITY_OPT: &[&str] =
    &["seccomp=unconfined", "apparmor=unconfined", "label=disable"];

/// Device cgroup rules of a sandbox with Docker in it: the loop devices (`b 7:*`) and
/// `loop-control` of the VM's kernel, on which the entrypoint mounts `/var/lib/docker`.
/// Rules only allow access; they add no device to the spec, so nothing of this machine is
/// passed into the VM.
pub const DOCKER_DEVICE_CGROUP_RULES: &[&str] = &["b 7:* rwm", "c 10:237 rwm"];

/// The container of a sandbox (docs/archive/v1/CONVENTIONS.md §15.3).
///
/// With Docker in the sandbox ([`SandboxSpec::docker`], ADR-0025) the container starts as
/// root with every capability and [`DOCKER_SECURITY_OPT`], in a cgroup namespace of its own,
/// so that the image's entrypoint can start dockerd; the entrypoint then runs the agent as
/// [`USER`]. That is what `--privileged` gives, except the host's devices: Docker lists every
/// host device in the spec of a privileged container, and Kata hot-plugs those block devices
/// into the VM, which would hand the agent this machine's disks. Docker has no
/// `privileged_without_host_devices`; the container keeps Docker's default devices, and
/// [`DOCKER_DEVICE_CGROUP_RULES`] let it use the VM's loop devices. The container gets no
/// `no-new-privileges` either: the agent controls a root daemon in the VM,
/// and it would only break setuid programs in the agent's own containers. The VM of the
/// runtime is the boundary then, which is why only a `kata…` runtime may have this
/// ([`SandboxSettings::new`]).
pub fn container_body(spec: &SandboxSpec) -> ContainerCreateBody {
    let mut binds: Vec<String> = spec
        .binds
        .iter()
        .map(|b| {
            format!(
                "{}:{}:{}",
                b.host.display(),
                b.container,
                if b.read_only { "ro" } else { "rw" }
            )
        })
        .collect();
    // With the bridge (ADR-0095) the container runs `bridge`, not the image's command, and is
    // not attached: the adapter's stdio goes over the bridge.
    let attached = spec.bridge_binary.is_none();
    if let Some(binary) = &spec.bridge_binary {
        binds.push(format!(
            "{}:{}:ro",
            binary.display(),
            crate::bridge::SANDBOX_PATH
        ));
    }
    let confined = !spec.docker;
    ContainerCreateBody {
        image: Some(spec.image.clone()),
        cmd: match &spec.bridge_binary {
            Some(_) => Some(vec![
                crate::bridge::SANDBOX_PATH.to_string(),
                "bridge".to_string(),
            ]),
            None => spec.command.clone(),
        },
        user: Some(if confined { USER } else { "0:0" }.to_string()),
        working_dir: Some(WORKSPACE.to_string()),
        env: Some(spec.env.iter().map(|(k, v)| format!("{k}={v}")).collect()),
        labels: Some(spec.labels.clone()),
        attach_stdin: Some(attached),
        attach_stdout: Some(attached),
        attach_stderr: Some(attached),
        open_stdin: Some(attached),
        // Closing our attach connection closes the adapter's stdin, so it exits cleanly.
        stdin_once: Some(attached),
        tty: Some(false),
        host_config: Some(HostConfig {
            binds: Some(binds),
            runtime: spec.runtime.clone(),
            // Never `privileged`: see above.
            privileged: None,
            cap_add: spec.docker.then(|| vec!["ALL".to_string()]),
            cap_drop: confined.then(|| vec!["ALL".to_string()]),
            security_opt: Some(if confined {
                vec!["no-new-privileges:true".to_string()]
            } else {
                DOCKER_SECURITY_OPT.iter().map(|o| o.to_string()).collect()
            }),
            // Empty lists, not absent ones: absent means Docker's default masks.
            masked_paths: spec.docker.then(Vec::new),
            readonly_paths: spec.docker.then(Vec::new),
            cgroupns_mode: spec.docker.then_some(HostConfigCgroupnsModeEnum::PRIVATE),
            device_cgroup_rules: spec.docker.then(|| {
                DOCKER_DEVICE_CGROUP_RULES
                    .iter()
                    .map(|r| r.to_string())
                    .collect()
            }),
            memory: Some(spec.resources.memory_bytes),
            memory_swap: Some(spec.resources.memory_bytes),
            nano_cpus: Some(spec.resources.nano_cpus),
            pids_limit: Some(spec.resources.pids_limit),
            network_mode: spec.network_mode.clone(),
            extra_hosts: (!spec.extra_hosts.is_empty()).then(|| spec.extra_hosts.clone()),
            dns: (!spec.dns.is_empty()).then(|| spec.dns.clone()),
            init: Some(true),
            ..Default::default()
        }),
        ..Default::default()
    }
}

/// How long Docker may take to remove a leftover container and to create the sandbox's: longer
/// than the client's 120 s, since a daemon still deleting a large container (a run's Docker
/// disk, a big workspace) can hold a create for minutes, and a run that waits beats one that
/// fails with `INFRA`.
const CREATE_TIMEOUT: Duration = Duration::from_secs(600);

async fn start_docker(docker: &Docker, spec: &SandboxSpec) -> Result<(Sandbox, SandboxIo)> {
    create_container(docker, spec).await?;
    let attach = docker
        .attach_container(
            &spec.name,
            Some(
                AttachContainerOptionsBuilder::default()
                    .stdin(true)
                    .stdout(true)
                    .stderr(true)
                    .stream(true)
                    .build(),
            ),
        )
        .await
        .context("cannot attach to the container")?;
    if let Err(e) = docker
        .start_container(&spec.name, None::<StartContainerOptions>)
        .await
    {
        let _ = remove_container(docker, &spec.name).await;
        return Err(e).context("cannot start the container");
    }
    let (tx, rx) = mpsc::channel(OUTPUT_QUEUE);
    let mut output = attach.output;
    tokio::spawn(async move {
        // A full queue stops the polling of the attach stream, so the container's stdout
        // pipe fills and the agent's writes block there, as they would on a slow terminal.
        while let Some(item) = output.next().await {
            let item = match item {
                Ok(LogOutput::StdOut { message }) | Ok(LogOutput::Console { message }) => {
                    SandboxOutput::Stdout(message.to_vec())
                }
                Ok(LogOutput::StdErr { message }) => SandboxOutput::Stderr(message.to_vec()),
                Ok(LogOutput::StdIn { .. }) => continue,
                Err(e) => {
                    tracing::debug!(error = %e, "container attach stream ended with an error");
                    break;
                }
            };
            if tx.send(item).await.is_err() {
                break;
            }
        }
    });
    Ok((
        Sandbox {
            kind: SandboxKind::Docker {
                docker: docker.clone(),
                name: spec.name.clone(),
            },
        },
        SandboxIo {
            stdin: attach.input,
            output: rx,
        },
    ))
}

/// Removes a leftover container of the run and creates the sandbox's.
async fn create_container(docker: &Docker, spec: &SandboxSpec) -> Result<()> {
    let patient = docker.clone().with_timeout(CREATE_TIMEOUT);
    // A container left over from an earlier attempt of the same run would block the name.
    remove_container(&patient, &spec.name).await?;
    let body = container_body(spec);
    patient
        .create_container(
            Some(
                CreateContainerOptionsBuilder::default()
                    .name(&spec.name)
                    .build(),
            ),
            body,
        )
        .await
        .map_err(|e| match e {
            bollard::errors::Error::RequestTimeoutError => anyhow::anyhow!(
                "Docker did not create the container within {} s; the Docker daemon on this host is busy or stuck (see its log)",
                CREATE_TIMEOUT.as_secs()
            ),
            other => other.into(),
        })
        .with_context(|| format!("cannot create container from image {}", spec.image))?;
    Ok(())
}

fn start_local(program: &str, args: &[String], spec: &SandboxSpec) -> Result<(Sandbox, SandboxIo)> {
    let mut cmd = tokio::process::Command::new(program);
    cmd.args(args)
        .current_dir(&spec.local_cwd)
        .env_clear()
        .env("PATH", std::env::var("PATH").unwrap_or_default())
        .envs(spec.env.iter().map(|(k, v)| (k.as_str(), v.as_str())))
        .env("HOME", &spec.local_home)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    let mut child = cmd
        .spawn()
        .with_context(|| format!("cannot start {program}"))?;
    let stdin = child.stdin.take().context("no stdin")?;
    let stdout = child.stdout.take().context("no stdout")?;
    let stderr = child.stderr.take().context("no stderr")?;
    let (tx, rx) = mpsc::channel(OUTPUT_QUEUE);
    spawn_reader(stdout, tx.clone(), false);
    spawn_reader(stderr, tx, true);
    Ok((
        Sandbox {
            kind: SandboxKind::Local { child },
        },
        SandboxIo {
            stdin: Box::pin(stdin),
            output: rx,
        },
    ))
}

fn spawn_reader(
    mut reader: impl AsyncRead + Unpin + Send + 'static,
    tx: mpsc::Sender<SandboxOutput>,
    stderr: bool,
) {
    tokio::spawn(async move {
        let mut buf = vec![0u8; 16 * 1024];
        loop {
            match reader.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    let chunk = buf[..n].to_vec();
                    let item = if stderr {
                        SandboxOutput::Stderr(chunk)
                    } else {
                        SandboxOutput::Stdout(chunk)
                    };
                    if tx.send(item).await.is_err() {
                        break;
                    }
                }
            }
        }
    });
}

/// The sandbox of a run that this host takes back after a restart (ADR-0101), from the moment
/// the host starts: its VM is held, so the leftover cleanup leaves it. Dropped without its
/// bridge, it is stopped as any sandbox is ([`TakenBack::discard`]), a VM deleted.
pub struct TakenBack {
    inner: SandboxKind,
}

impl TakenBack {
    /// `record`'s sandbox, for this host's executor.
    pub fn hold(executor: &Executor, record: &crate::takeback::SandboxRecord) -> Result<TakenBack> {
        use crate::takeback::SandboxRecord;
        let inner = match (executor, record) {
            (Executor::Docker(docker), SandboxRecord::Docker { container }) => {
                SandboxKind::Docker {
                    docker: docker.clone(),
                    name: container.clone(),
                }
            }
            (
                Executor::Vm(vms),
                SandboxRecord::Vm {
                    id,
                    host,
                    label,
                    address,
                },
            ) => SandboxKind::Vm {
                vm: vms.adopt(id, host, label.as_deref(), address),
                busy: None,
            },
            (Executor::Local { .. }, SandboxRecord::Local) => SandboxKind::Adopted,
            (executor, record) => bail!(
                "the run's sandbox ({record:?}) is not of this host's executor ({executor:?})"
            ),
        };
        Ok(TakenBack { inner })
    }

    /// The sandbox, now that its bridge `session` has come back.
    pub fn connected(self, session: HostSession) -> Sandbox {
        let exit = session.exit();
        Sandbox {
            kind: SandboxKind::Bridged {
                session,
                exit,
                inner: Box::new(self.inner),
            },
        }
    }

    /// Its bridge did not come back: the container is removed, the VM deleted.
    pub async fn discard(mut self, grace: Duration) {
        stop_kind(&mut self.inner, grace).await;
    }
}

impl Sandbox {
    /// What a host that restarted needs to take the sandbox's run back (ADR-0101): the
    /// sandbox, its bridge's name with the listener, and its session's key. `None` without
    /// the bridge, and for a VM whose address is not known.
    pub fn take_back_record(&self) -> Option<(crate::takeback::SandboxRecord, String, Hidden)> {
        use crate::takeback::SandboxRecord;
        let SandboxKind::Bridged { session, inner, .. } = &self.kind else {
            return None;
        };
        let record = match inner.as_ref() {
            SandboxKind::Docker { name, .. } => SandboxRecord::Docker {
                container: name.clone(),
            },
            SandboxKind::Vm { vm, .. } => vm.record()?,
            SandboxKind::Local { .. } | SandboxKind::Adopted => SandboxRecord::Local,
            SandboxKind::Bridged { .. } => return None,
        };
        Some((
            record,
            session.run_id().to_string(),
            session.session_key().clone(),
        ))
    }

    /// For a sandbox taken back: the agent that the earlier process started stops. Whether it
    /// still ran.
    pub async fn reclaim(&mut self, timeout: Duration) -> Result<bool> {
        match &mut self.kind {
            SandboxKind::Bridged { session, .. } => session.reclaim(timeout).await,
            _ => bail!("only a sandbox on the bridge is taken back"),
        }
    }

    /// The sandbox's files over the bridge (ADR-0095); `None` for a sandbox without it.
    pub fn files(&self) -> Option<SandboxFiles> {
        match &self.kind {
            SandboxKind::Bridged { session, .. } => Some(session.files()),
            _ => None,
        }
    }

    /// Stops the agent (SIGTERM, then SIGKILL after `grace`). With the bridge the sandbox
    /// stays, so that the host can take its files, until [`Sandbox::stop`]; without it, this
    /// is [`Sandbox::stop`].
    pub async fn stop_agent(&mut self, grace: Duration) {
        match &mut self.kind {
            SandboxKind::Bridged { session, exit, .. } => {
                if exit.borrow().is_none() && session.stop(grace).await.is_ok() {
                    let _ = tokio::time::timeout(
                        grace + Duration::from_secs(2),
                        exit.wait_for(Option::is_some),
                    )
                    .await;
                }
            }
            _ => self.stop(grace).await,
        }
    }

    /// Stops the sandbox (SIGTERM, then SIGKILL after `grace`) and removes it. With the
    /// bridge, the agent first, and then the bridge, which ends the container or the process.
    pub async fn stop(&mut self, grace: Duration) {
        if let SandboxKind::Bridged { session, exit, .. } = &mut self.kind {
            if exit.borrow().is_none() && session.stop(grace).await.is_ok() {
                let _ = tokio::time::timeout(
                    grace + Duration::from_secs(2),
                    exit.wait_for(Option::is_some),
                )
                .await;
            }
            session.close(Duration::from_secs(5)).await;
        }
        stop_kind(&mut self.kind, grace).await;
    }
}

/// Stops and removes a container, or waits for a process and kills it after `grace`.
async fn stop_kind(kind: &mut SandboxKind, grace: Duration) {
    let kind = match kind {
        SandboxKind::Bridged { inner, .. } => inner.as_mut(),
        other => other,
    };
    match kind {
        SandboxKind::Docker { docker, name } => {
            let options = StopContainerOptionsBuilder::default()
                .t(grace.as_secs().max(1) as i32)
                .build();
            if let Err(e) = docker.stop_container(name, Some(options)).await {
                tracing::debug!(container = %name, error = %e, "stop failed");
            }
            if let Err(e) = remove_container(docker, name).await {
                tracing::warn!(container = %name, error = %e, "cannot remove container");
            }
        }
        SandboxKind::Local { child } => {
            if tokio::time::timeout(grace, child.wait()).await.is_err() {
                let _ = child.kill().await;
            }
        }
        SandboxKind::Vm { vm, busy } => {
            vm.delete().await;
            busy.take();
        }
        SandboxKind::Bridged { .. } | SandboxKind::Adopted => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn env(entries: &[&str]) -> Vec<(String, String)> {
        entries
            .iter()
            .map(|e| parse_env_entry(e).unwrap())
            .collect()
    }

    fn spec(runtime: Option<&str>, docker: bool) -> SandboxSpec {
        SandboxSpec {
            name: "tinfirm-run-x".into(),
            image: "tinfirm-runner-claude:local".into(),
            env: vec![("A".into(), "1".into())],
            binds: vec![Bind {
                host: PathBuf::from("/data/workspaces/x"),
                container: WORKSPACE.into(),
                read_only: false,
            }],
            labels: HashMap::new(),
            resources: Resources::default(),
            network_mode: None,
            extra_hosts: Vec::new(),
            dns: Vec::new(),
            runtime: runtime.map(String::from),
            docker,
            bridge_binary: None,
            command: None,
            local_cwd: PathBuf::new(),
            local_home: PathBuf::new(),
        }
    }

    #[test]
    fn with_the_bridge_the_container_runs_the_mounted_bridge_and_is_not_attached() {
        let attached = container_body(&spec(None, false));
        assert_eq!(attached.cmd, None);
        assert_eq!(attached.attach_stdin, Some(true));
        assert_eq!(attached.open_stdin, Some(true));

        let mut bridged = spec(None, false);
        bridged.bridge_binary = Some(PathBuf::from("/usr/local/bin/tinfirm-runner-host"));
        let body = container_body(&bridged);
        assert_eq!(
            body.cmd,
            Some(vec![
                crate::bridge::SANDBOX_PATH.to_string(),
                "bridge".to_string()
            ])
        );
        for flag in [
            body.attach_stdin,
            body.attach_stdout,
            body.attach_stderr,
            body.open_stdin,
            body.stdin_once,
        ] {
            assert_eq!(flag, Some(false));
        }
        let binds = body.host_config.unwrap().binds.unwrap();
        assert!(
            binds.contains(&format!(
                "/usr/local/bin/tinfirm-runner-host:{}:ro",
                crate::bridge::SANDBOX_PATH
            )),
            "{binds:?}"
        );
        // The workspace is still mounted as before.
        assert!(
            binds.contains(&"/data/workspaces/x:/workspace:rw".to_string()),
            "{binds:?}"
        );
    }

    #[test]
    fn a_vm_per_run_is_a_microvm_that_may_run_docker_and_names_no_runtime() {
        let settings = SandboxSettings::for_vms(None, true, Vec::new()).unwrap();
        assert!(settings.is_vm() && settings.docker && settings.per_run_vm);
        assert_eq!(settings.tier(), Tier::Microvm);
        assert_eq!(settings.runtime, None);
        let plain = SandboxSettings::for_vms(Some(" ".into()), false, Vec::new()).unwrap();
        assert!(!plain.docker && plain.is_vm());
        let err = SandboxSettings::for_vms(Some("kata".into()), false, Vec::new())
            .unwrap_err()
            .to_string();
        assert!(err.contains("every sandbox is a VM of its own"), "{err}");
        // The environment's rules hold as for Docker.
        let twice = vec![
            ("A".to_string(), "1".to_string()),
            ("A".to_string(), "2".to_string()),
        ];
        assert!(SandboxSettings::for_vms(None, false, twice).is_err());
    }

    #[test]
    fn docker_in_the_sandbox_needs_a_kata_runtime() {
        for runtime in [
            None,
            Some("runc"),
            Some("runsc"),
            Some("io.containerd.kata.v2"),
        ] {
            let err = SandboxSettings::new(runtime.map(String::from), true, Vec::new())
                .unwrap_err()
                .to_string();
            assert!(err.contains("--sandbox-docker needs a Kata"), "{err}");
            assert!(err.contains("root on this machine"), "{err}");
        }
        for runtime in ["kata", "kata-clh", "kata-qemu"] {
            let settings = SandboxSettings::new(Some(runtime.into()), true, Vec::new()).unwrap();
            assert!(settings.is_vm() && settings.docker);
            assert_eq!(settings.tier(), Tier::Microvm);
            assert_eq!(
                settings.tier().isolation_tier(),
                tinfirm_protocol::IsolationTier::Microvm
            );
        }
        // Without Docker in the sandbox any runtime goes.
        let runc = SandboxSettings::new(Some(" runc ".into()), false, Vec::new()).unwrap();
        assert_eq!(runc.runtime.as_deref(), Some("runc"));
        assert_eq!(runc.tier(), Tier::Container);
        let gvisor = SandboxSettings::new(Some("runsc".into()), false, Vec::new()).unwrap();
        assert_eq!(gvisor.tier(), Tier::Gvisor);
        let default = SandboxSettings::new(Some("  ".into()), false, Vec::new()).unwrap();
        assert_eq!(default, SandboxSettings::default());
        assert_eq!(default.tier().as_str(), "container");
    }

    #[test]
    fn runtime_names_are_checked() {
        for bad in ["kata clh", "-kata", "kata/clh", "kata;rm"] {
            let err = SandboxSettings::new(Some(bad.into()), false, Vec::new())
                .unwrap_err()
                .to_string();
            assert!(err.contains("is not a Docker runtime name"), "{bad}: {err}");
        }
    }

    #[test]
    fn the_runtime_must_be_known_to_docker() {
        let kata = SandboxSettings::new(Some("kata".into()), true, Vec::new()).unwrap();
        let known = vec!["runc".to_string(), "io.containerd.runc.v2".to_string()];
        let err = effective_runtime(&kata, Some(&known), Some("runc"))
            .unwrap_err()
            .to_string();
        assert!(err.contains("Docker has no runtime `kata`"), "{err}");
        assert!(err.contains("io.containerd.runc.v2, runc"), "{err}");
        let known = vec!["runc".to_string(), "kata".to_string()];
        assert_eq!(
            effective_runtime(&kata, Some(&known), Some("runc")).unwrap(),
            "kata"
        );
        // A Docker that does not list its runtimes is not second-guessed.
        assert_eq!(effective_runtime(&kata, None, None).unwrap(), "kata");
        let default = SandboxSettings::default();
        assert_eq!(
            effective_runtime(&default, Some(&known), Some("crun")).unwrap(),
            "crun"
        );
        assert_eq!(effective_runtime(&default, None, None).unwrap(), "runc");
    }

    #[test]
    fn sandbox_env_entries_are_checked() {
        assert_eq!(
            parse_env_entry("TINFIRM_SANDBOX_REGISTRY_MIRRORS=https://a.example,https://b.example")
                .unwrap(),
            (
                "TINFIRM_SANDBOX_REGISTRY_MIRRORS".to_string(),
                "https://a.example,https://b.example".to_string()
            )
        );
        // The first `=` splits; the value may contain more, or be empty.
        assert_eq!(
            parse_env_entry("MAVEN_OPTS=-Dx=y -Xmx2g").unwrap().1,
            "-Dx=y -Xmx2g"
        );
        assert_eq!(parse_env_entry("NO_PROXY=").unwrap().1, "");
        assert!(parse_env_entry("HTTPS_PROXY=http://proxy.example:3128/").is_ok());

        let refused = |entry: &str, why: &str| {
            let err = parse_env_entry(entry).unwrap_err();
            assert!(err.contains(why), "{entry}: {err}");
        };
        refused("NO_EQUALS_SIGN", "expected KEY=VALUE");
        refused("=value", "is not a variable name");
        refused("1ABC=x", "is not a variable name");
        refused("A-B=x", "is not a variable name");
        refused("TINFIRM_SANDBOX_DOCKER=1", "set by the runner host");
        refused("TINFIRM_SANDBOX_AGENT_USER=0:0", "set by the runner host");
        refused("TINFIRM_RUN_ID=x", "set by the runner host");
        refused("GIT_CONFIG_COUNT=1", "set by the runner host");
        refused("PATH=/tmp", "set by the runner host");
        refused(
            "ANTHROPIC_BASE_URL=https://x.example",
            "set by the runner host",
        );
        refused(
            "OPENAI_BASE_URL=https://x.example/v1",
            "set by the runner host",
        );
        refused(
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1",
            "set by the runner host",
        );
        for reserved in [
            "CLAUDE_CODE_USE_BEDROCK=1",
            "AWS_REGION=eu-west-1",
            "OPENCODE_CONFIG_CONTENT={}",
            "PI_OFFLINE=1",
            "TINFIRM_MODEL=gpt",
            "CLAUDE_CODE_EFFORT_LEVEL=max",
            "TINFIRM_THINKING_LEVEL=max",
        ] {
            refused(reserved, "set by the runner host");
        }
        refused("AWS_ACCESS_KEY_ID=x", "looks like a credential");
        refused("AWS_BEARER_TOKEN_BEDROCK=x", "looks like a credential");
        refused("ANTHROPIC_AUTH_TOKEN=x", "looks like a credential");
        refused("NPM_TOKEN=x", "looks like a credential");
        refused("ANTHROPIC_API_KEY=x", "looks like a credential");
        refused("CLAUDE_CODE_OAUTH_TOKEN=x", "looks like a credential");
        refused("NPM_CONFIG__AUTH=x", "looks like a credential");
        refused("NEXUS_PASSWORD=x", "looks like a credential");
        refused("GITLAB_PAT=x", "looks like a credential");
        refused("A=line\nbreak", "control character");
        refused(
            "TINFIRM_SANDBOX_MAVEN_MIRROR=https://user:secret@nexus.example/maven",
            "user name or password",
        );
        refused(
            "TINFIRM_SANDBOX_REGISTRY_MIRRORS=https://a.example,https://tok@b.example",
            "user name or password",
        );
        // An `@` in a path is no user name.
        assert!(parse_env_entry("X_URL=https://registry.example/@scope/pkg").is_ok());
        // Errors never repeat a value.
        let err = parse_env_entry("NPM_TOKEN=zzzzzz").unwrap_err();
        assert!(!err.contains("zzzzzz"), "{err}");
        let err = parse_env_entry("X_URL=https://user:hunter2hunter2@nexus.example").unwrap_err();
        assert!(!err.contains("hunter2"), "{err}");
    }

    #[test]
    fn a_variable_may_be_set_only_once() {
        let err = SandboxSettings::new(
            Some("kata".into()),
            true,
            env(&["HTTPS_PROXY=http://a:1", "HTTPS_PROXY=http://b:1"]),
        )
        .unwrap_err()
        .to_string();
        assert!(err.contains("sets HTTPS_PROXY more than once"), "{err}");
        let settings = SandboxSettings::new(
            None,
            false,
            env(&[
                "TINFIRM_SANDBOX_NPM_REGISTRY=https://n.example/",
                "NO_PROXY=x",
            ]),
        )
        .unwrap();
        assert_eq!(
            settings.env_keys(),
            vec!["TINFIRM_SANDBOX_NPM_REGISTRY", "NO_PROXY"]
        );
    }

    #[test]
    fn a_plain_sandbox_drops_every_privilege() {
        let body = container_body(&spec(None, false));
        assert_eq!(body.user.as_deref(), Some(USER));
        let host = body.host_config.unwrap();
        assert_eq!(host.runtime, None);
        assert_eq!(host.privileged, None);
        assert_eq!(host.cap_add, None);
        assert_eq!(host.cap_drop, Some(vec!["ALL".to_string()]));
        assert_eq!(
            host.security_opt,
            Some(vec!["no-new-privileges:true".to_string()])
        );
        assert_eq!(host.cgroupns_mode, None);
        assert_eq!(host.masked_paths, None);
        assert_eq!(host.readonly_paths, None);
        assert_eq!(host.devices, None);
        assert_eq!(host.device_cgroup_rules, None);
        assert_eq!(
            host.binds,
            Some(vec!["/data/workspaces/x:/workspace:rw".to_string()])
        );
        assert_eq!(host.init, Some(true));
        assert_eq!(host.memory, Some(4 * 1024 * 1024 * 1024));
        assert_eq!(host.pids_limit, Some(1024));
    }

    #[test]
    fn the_runtime_and_docker_in_the_sandbox_reach_the_container() {
        let body = container_body(&spec(Some("kata"), false));
        let host = body.host_config.unwrap();
        assert_eq!(host.runtime.as_deref(), Some("kata"));
        assert_eq!(host.privileged, None);
        assert_eq!(host.cap_drop, Some(vec!["ALL".to_string()]));
        assert_eq!(body.user.as_deref(), Some(USER));

        let body = container_body(&spec(Some("kata"), true));
        // Root, so that the entrypoint can start dockerd; it then runs the agent as USER.
        assert_eq!(body.user.as_deref(), Some("0:0"));
        assert_eq!(body.env, Some(vec!["A=1".to_string()]));
        let host = body.host_config.unwrap();
        assert_eq!(host.runtime.as_deref(), Some("kata"));
        // Every capability and no confinement, but never `privileged`: Docker would list
        // every host device, and Kata would hot-plug the host's disks into the VM.
        assert_eq!(host.privileged, None);
        assert_eq!(host.devices, None);
        // Access to the VM's own loop devices, for the disk of /var/lib/docker.
        assert_eq!(
            host.device_cgroup_rules,
            Some(vec!["b 7:* rwm".to_string(), "c 10:237 rwm".to_string()])
        );
        assert_eq!(host.cap_add, Some(vec!["ALL".to_string()]));
        assert_eq!(host.cap_drop, None);
        assert_eq!(
            host.security_opt,
            Some(vec![
                "seccomp=unconfined".to_string(),
                "apparmor=unconfined".to_string(),
                "label=disable".to_string(),
            ])
        );
        // `systempaths=unconfined` of the CLI, as the Engine API takes it.
        assert_eq!(host.masked_paths, Some(Vec::new()));
        assert_eq!(host.readonly_paths, Some(Vec::new()));
        assert_eq!(
            host.cgroupns_mode,
            Some(HostConfigCgroupnsModeEnum::PRIVATE)
        );
        // The limits, the mounts and the init process stay as they are.
        assert_eq!(host.memory, Some(4 * 1024 * 1024 * 1024));
        assert_eq!(host.nano_cpus, Some(2_000_000_000));
        assert_eq!(host.pids_limit, Some(1024));
        assert_eq!(host.init, Some(true));
        assert_eq!(
            host.binds,
            Some(vec!["/data/workspaces/x:/workspace:rw".to_string()])
        );
    }
}
