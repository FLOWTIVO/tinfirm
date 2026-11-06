//! `tinfirm-runner-host`: the Tinfirm runner host CLI (docs/archive/v1/CONVENTIONS.md §15.2).

use std::io::{BufRead, IsTerminal, Write};
use std::path::PathBuf;
use std::process::ExitCode;

use anyhow::{Context, Result, bail};
use chrono::Utc;
use clap::{Args, Parser, Subcommand, ValueEnum};
use tinfirm_protocol::{CredentialKind, MAX_CAPACITY, MAX_HOST_CAPACITY, Runtime, Secret};
use tinfirm_runner_host::bridge::BridgeOptions;
use tinfirm_runner_host::config::{self, HostConfig};
use tinfirm_runner_host::credentials::{
    self, Credential, CredentialStore, DEFAULT_API_KEY_POOL, DEFAULT_CLAUDE_POOL,
    DEFAULT_CODEX_POOL,
};
use tinfirm_runner_host::egress_proxy::EgressSettings;
use tinfirm_runner_host::host::{self, Host, HostOptions, Shared};
use tinfirm_runner_host::maintenance::{self, DeleteReason, MirrorReport, Policy};
use tinfirm_runner_host::mirror::Mirrors;
use tinfirm_runner_host::model_proxy::ModelProxySettings;
use tinfirm_runner_host::paths::Paths;
use tinfirm_runner_host::sandbox::{self, Executor, SandboxSettings};
use tinfirm_runner_host::util::format_bytes;
use tinfirm_runner_host::{connection, docker, git, images, secure_fs};
use tokio_util::sync::CancellationToken;

#[derive(Parser)]
#[command(
    name = "tinfirm-runner-host",
    version = tinfirm_runner_host::version::display(),
    about = "Tinfirm runner host: runs Tinfirm agents in local Docker sandboxes"
)]
struct Cli {
    /// Config directory (default: $XDG_CONFIG_HOME/tinfirm-runner or ~/.config/tinfirm-runner).
    #[arg(long, global = true, env = "TINFIRM_RUNNER_CONFIG_DIR")]
    config_dir: Option<PathBuf>,
    /// Data directory (default: $XDG_DATA_HOME/tinfirm-runner or ~/.local/share/tinfirm-runner).
    #[arg(long, global = true, env = "TINFIRM_RUNNER_DATA_DIR")]
    data_dir: Option<PathBuf>,
    /// Log filter (tracing EnvFilter syntax).
    #[arg(
        long,
        global = true,
        env = "TINFIRM_RUNNER_LOG",
        default_value = "info"
    )]
    log: String,
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Store the server URL and the runner host token shown at registration.
    Login {
        #[arg(long)]
        server: String,
        /// The `tfr_…` token. Better omitted: it is then read from the terminal without echo,
        /// or from stdin (`… login --server URL < token-file`). On the command line it shows in
        /// the process list and the shell's history.
        #[arg(long)]
        token: Option<String>,
    },
    /// Manage the model credentials of this host.
    #[command(subcommand)]
    Credential(CredentialCommand),
    /// Build the runner images locally.
    #[command(subcommand)]
    Images(ImagesCommand),
    /// Check Docker, images, credentials and the server connection.
    Doctor,
    /// Inspect and prune the host's repository mirrors.
    #[command(subcommand)]
    Mirrors(MirrorsCommand),
    /// Connect to the server and run agents. SIGTERM or Ctrl-C cancels the active runs;
    /// SIGHUP (or `drain`) lets them finish, then exits with status 0.
    Run {
        /// The most runs at once on this host: a ceiling, 1 to 16 with Docker, up to 1000 with
        /// `--executor vm` (each run a VM of its own, so the provider's quota bounds it). Members
        /// can lower it in Tinfirm, never raise it.
        #[arg(long, default_value_t = 2, value_parser = clap::value_parser!(u32).range(1..=i64::from(MAX_HOST_CAPACITY)))]
        max_concurrent_runs: u32,
        /// The runner protocol version to speak with the server: 2 (the generic request
        /// frame, server-executed tools, /output) or 1 (for a server from before
        /// protocol v2). Default: `protocolVersion` of config.json, or 2.
        #[arg(long, env = "TINFIRM_RUNNER_PROTOCOL_VERSION", value_parser = clap::value_parser!(u32).range(1..=2))]
        protocol_version: Option<u32>,
        /// The server's URL, with --token-file, in place of `login`'s config.json: for a host
        /// whose settings come from its environment, such as the Helm chart's runner pod
        /// (ADR-0101). https only, as `login` takes it.
        #[arg(
            long,
            env = "TINFIRM_RUNNER_SERVER",
            value_name = "URL",
            requires = "token_file"
        )]
        server: Option<String>,
        /// The file that holds the host's token (`tfr_…`), with --server: a mounted secret. It is
        /// read once, at the start, and the token is in no setting of the process.
        #[arg(
            long,
            env = "TINFIRM_RUNNER_TOKEN_FILE",
            value_name = "FILE",
            requires = "server"
        )]
        token_file: Option<PathBuf>,
        /// Do not rebuild the runner images when they were built from other Dockerfiles or
        /// build arguments than this binary's (for hosts whose images are built otherwise).
        #[arg(
            long,
            env = "TINFIRM_RUNNER_NO_IMAGE_REBUILD",
            value_parser = clap::builder::BoolishValueParser::new()
        )]
        no_image_rebuild: bool,
        /// The largest file an agent's upload may have (`save_artifact`, a file of /output), in
        /// bytes: a larger one is refused on its size before it is read. Default: 26214400
        /// (25 MiB), the server's default TINFIRM_ATTACHMENT_MAX_FILE_SIZE; set it to the
        /// server's value when the server's differs. At most 104857600 (100 MiB).
        #[arg(
            long,
            env = "TINFIRM_RUNNER_UPLOAD_MAX_BYTES",
            value_name = "BYTES",
            default_value_t = tinfirm_runner_host::attachments::DEFAULT_UPLOAD_MAX_BYTES,
            value_parser = clap::value_parser!(u64).range(1..=tinfirm_runner_host::mcp::MAX_UPLOAD_BYTES)
        )]
        upload_max_bytes: u64,
        #[command(flatten)]
        sandbox: SandboxArgs,
        // Boxed: the variant would be far larger than the enum's others.
        #[command(flatten)]
        vm: Box<VmArgs>,
    },
    /// Ask the `run` of this config directory to drain: take no new runs, let the active
    /// ones finish, then exit. Waits until it has exited.
    Drain {
        /// Return right after asking; do not wait for `run` to exit.
        #[arg(long)]
        no_wait: bool,
    },
    /// The sandbox's side of the sandbox bridge (ADR-0095). `run --sandbox-bridge` starts it in
    /// each sandbox, with its settings in the TINFIRM_BRIDGE_* variables; it is not run by hand.
    #[command(hide = true)]
    Bridge,
    /// A VM driver of this binary (ADR-0096): `run --executor vm --vm-driver digitalocean`
    /// starts it and speaks the driver protocol on its stdin and stdout; it is not run by hand.
    #[command(hide = true)]
    VmDriver {
        #[command(subcommand)]
        driver: VmDriverCommand,
    },
    /// In a sandbox VM (ADR-0096): reads the VM's user data and runs the run's container with
    /// the sandbox bridge. The VM's image starts it at boot; it is not run by hand.
    #[command(hide = true)]
    VmBoot {
        /// Where the user data is: the metadata service's URL (http://), or a file.
        #[arg(long, default_value = tinfirm_runner_host::vm_boot::DIGITALOCEAN_USER_DATA)]
        user_data: String,
        /// The bridge binary to mount into the container (default: this one).
        #[arg(long)]
        bridge: Option<PathBuf>,
    },
}

#[derive(Subcommand)]
enum VmDriverCommand {
    /// DigitalOcean: a Droplet per run, from a private snapshot, in a VPC of the sandboxes'
    /// own, behind a Cloud Firewall on the tag `tinfirm-sandbox`.
    Digitalocean {
        /// The driver's configuration: its API token's file and its templates (runner/README.md).
        #[arg(
            long,
            env = "TINFIRM_DO_CONFIG",
            default_value = "/etc/tinfirm/digitalocean.json"
        )]
        config: PathBuf,
    },
}

/// How `run` creates the sandboxes (docs/archive/v1/CONVENTIONS.md §15.3, ADR-0025).
#[derive(Args)]
struct SandboxArgs {
    /// Docker runtime of the sandbox containers, for example `kata` (Kata Containers: every
    /// sandbox is a microVM). Docker must have it registered. Default: Docker's default
    /// runtime (runc).
    #[arg(long, env = "TINFIRM_RUNNER_SANDBOX_RUNTIME", value_name = "NAME")]
    sandbox_runtime: Option<String>,
    /// Give the agent its own Docker daemon in the sandbox (Testcontainers, docker compose,
    /// image builds). The container then has every capability and no seccomp or AppArmor
    /// profile, so this needs a `kata…` --sandbox-runtime: under runc that is root on this
    /// machine.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_SANDBOX_DOCKER",
        value_parser = clap::builder::BoolishValueParser::new()
    )]
    sandbox_docker: bool,
    /// Extra environment of every sandbox, such as TINFIRM_SANDBOX_* mirror settings or
    /// HTTPS_PROXY; repeatable. NEVER credentials: the agent can read the values, they show
    /// in `docker inspect`, and they are not redacted. The environment variable takes one
    /// KEY=VALUE per line.
    // Parsed in `run`, not by clap: clap's error repeats a refused value, and a value refused
    // for looking like a credential must not end up in a terminal or a journal.
    #[arg(
        long = "sandbox-env",
        env = "TINFIRM_RUNNER_SANDBOX_ENV",
        value_name = "KEY=VALUE",
        value_delimiter = '\n'
    )]
    sandbox_env: Vec<String>,
    /// Let the sandboxes reach the network only through an egress proxy of this host
    /// (ADR-0044): they run on an internal Docker network, and a connection leaves only to the
    /// agent's model, the package mirrors of --sandbox-env and the --egress-allow hosts.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_EGRESS_PROXY",
        value_parser = clap::builder::BoolishValueParser::new()
    )]
    egress_proxy: bool,
    /// A host that sandboxes may connect to, in addition: HOST[:PORT] (443 unless given;
    /// `:*` is every port), `*.example.com` for its subdomains, or an IP address; repeatable.
    /// The environment variable takes one entry per line. Needs --egress-proxy.
    #[arg(
        long = "egress-allow",
        env = "TINFIRM_RUNNER_EGRESS_ALLOW",
        value_name = "HOST[:PORT]",
        value_delimiter = '\n'
    )]
    egress_allow: Vec<String>,
    /// Let the model gateway that the model proxy reaches, and with --egress-proxy the allowed
    /// hosts (registries), have private addresses (RFC 1918, 100.64.0.0/10, fc00::/7), for hosts
    /// inside your network. Loopback and link-local addresses (cloud metadata) stay refused (a
    /// gateway on this machine: --model-proxy-local-gateway).
    #[arg(
        long,
        env = "TINFIRM_RUNNER_EGRESS_ALLOW_PRIVATE",
        value_parser = clap::builder::BoolishValueParser::new()
    )]
    egress_allow_private: bool,
    /// Deprecated, no effect: the model proxy is always on, and nothing turns it off. A run whose
    /// model access has a base URL (an anthropic or openai gateway, such as LiteLLM) reaches it
    /// through a listener of this host, which adds the key; the sandbox gets that listener and a
    /// dummy key. Still accepted, with a line in the log, so that service files that set it keep
    /// working; it goes in a later release.
    // `Option`: an operator who sets it to 0 to turn the proxy off is told too.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_MODEL_PROXY",
        value_name = "BOOL",
        num_args = 0..=1,
        require_equals = true,
        default_missing_value = "true",
        value_parser = clap::builder::BoolishValueParser::new()
    )]
    model_proxy: Option<bool>,
    /// The one model gateway on this machine, such as the bundled LiteLLM: its https origin
    /// (https://HOST[:PORT], no path). A run whose gateway has exactly this origin may reach it
    /// on loopback and this machine's own addresses, which the model proxy refuses for every other
    /// gateway; the listener's own address and cloud metadata stay refused.
    // Checked in `run`, not by clap: its error would repeat a value that may hold a password.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_MODEL_PROXY_LOCAL_GATEWAY",
        value_name = "ORIGIN"
    )]
    model_proxy_local_gateway: Option<String>,
    /// Run the agent over the sandbox bridge (ADR-0095): the container runs this binary,
    /// mounted read-only, as `bridge`, which connects back to this host over pinned TLS and
    /// starts the adapter. The run's credentials then reach the adapter over the bridge, not
    /// through the container's settings (`docker inspect`). Needs a static Linux build of the
    /// runner host for the sandboxes' architecture (the release binaries are), or
    /// TINFIRM_RUNNER_BRIDGE_BINARY naming one.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_SANDBOX_BRIDGE",
        value_parser = clap::builder::BoolishValueParser::new()
    )]
    sandbox_bridge: bool,
}

#[derive(Clone, Copy, PartialEq, Eq, ValueEnum)]
enum ExecutorChoice {
    /// Containers of this machine's Docker.
    Docker,
    /// A fresh VM per run, created and deleted through --vm-driver (ADR-0096).
    Vm,
}

#[derive(Clone, Copy, PartialEq, Eq, ValueEnum)]
enum ImageVersionChoice {
    /// The run goes on, and the host warns once for each build it sees.
    Warn,
    /// The VM is deleted, and its run fails.
    Refuse,
}

/// Where `run` creates the sandboxes: Docker on this machine, or a VM per run (ADR-0096).
#[derive(Args)]
struct VmArgs {
    /// Where the sandboxes run: `docker`, containers of this machine's Docker, or `vm`, a
    /// fresh VM per run from --vm-template, created and deleted through --vm-driver and
    /// never reused. With `vm` this host needs no Docker, every agent runs over the sandbox
    /// bridge, which this host dials, and the egress proxy is always on.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_EXECUTOR",
        value_enum,
        default_value_t = ExecutorChoice::Docker
    )]
    executor: ExecutorChoice,
    /// The VM driver (--executor vm): `digitalocean`, this binary's own driver, or the command
    /// line of another (split at white space). It holds the provider's credential; this host
    /// holds none.
    #[arg(long, env = "TINFIRM_RUNNER_VM_DRIVER", value_name = "COMMAND")]
    vm_driver: Option<String>,
    /// The driver's template that every VM is created from (--executor vm): image, size,
    /// network and firewall, as the driver's configuration names them.
    #[arg(long, env = "TINFIRM_RUNNER_VM_TEMPLATE", value_name = "NAME")]
    vm_template: Option<String>,
    /// The port where the sandbox's bridge listens in the VM, which the template's firewall
    /// opens to this host alone.
    #[arg(long, env = "TINFIRM_RUNNER_VM_PORT", default_value_t = tinfirm_runner_host::vm::DEFAULT_PORT)]
    vm_port: u16,
    /// How long a VM may take from its creation to its bridge's first connection, in seconds.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_VM_START_TIMEOUT",
        value_name = "SECONDS",
        default_value_t = 900,
        value_parser = clap::value_parser!(u64).range(30..=3600)
    )]
    vm_start_timeout: u64,
    /// Something more that a VM must not reach, tried from inside before each agent starts,
    /// besides the internet and the metadata services' credentials: `tcp:HOST:PORT`,
    /// `dns:NAME` or `http://HOST[:PORT]/PATH` (a database, another network of yours);
    /// repeatable. The environment variable takes one per line.
    #[arg(
        long = "vm-probe",
        env = "TINFIRM_RUNNER_VM_PROBE",
        value_name = "CHECK",
        value_delimiter = '\n'
    )]
    vm_probe: Vec<String>,
    /// What to do with a VM whose image another build of the runner host made, so whose
    /// runner images are not those this host would build (ADR-0101): `warn` (once for each
    /// build), or `refuse` (the VM is deleted, and its run fails). Rebuild the VM image with
    /// this host's binary after every upgrade.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_VM_IMAGE_VERSION",
        value_enum,
        default_value_t = ImageVersionChoice::Warn
    )]
    vm_image_version: ImageVersionChoice,
    /// The most ready VMs that Tinfirm may ask this host to keep (--executor vm, ADR-0098):
    /// each is created, booted and probed before a run comes, which then starts in seconds,
    /// and each costs while it waits. Tinfirm's admins set how many, never more than this; 0
    /// turns the ready VMs off. They count against --max-concurrent-runs.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_POOL_MAX_READY",
        default_value_t = 4,
        value_parser = clap::value_parser!(u32).range(0..=1000)
    )]
    pool_max_ready: u32,
    /// The runtime whose runner image the ready VMs start: a run of another runtime starts
    /// cold. `any`: every run starts from the multi-runtime image (`images build --runtime
    /// multi`, ADR-0101), so the ready VMs serve every runtime.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_POOL_RUNTIME",
        value_enum,
        default_value_t = PoolRuntime::Claude
    )]
    pool_runtime: PoolRuntime,
    /// One more ready VM per this many busy ones, on top of Tinfirm's count; 0 is off.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_POOL_HEADROOM",
        value_name = "BUSY",
        default_value_t = 0
    )]
    pool_headroom: u32,
    /// A ready VM older than this is replaced by a new one, in minutes.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_POOL_MAX_AGE",
        value_name = "MINUTES",
        default_value_t = 360,
        value_parser = clap::value_parser!(u64).range(10..=10080)
    )]
    pool_max_age: u64,
    /// Ready VMs above Tinfirm's count for this long are deleted, in minutes: the end of a
    /// schedule's window scales the pool down by itself.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_POOL_COOL_DOWN",
        value_name = "MINUTES",
        default_value_t = 10,
        value_parser = clap::value_parser!(u64).range(0..=1440)
    )]
    pool_cool_down: u64,
    /// How early a scheduled window of ready VMs may start filling, in minutes: the host's
    /// measured cold start, at most this.
    #[arg(
        long,
        env = "TINFIRM_RUNNER_POOL_LEAD_TIME_MAX",
        value_name = "MINUTES",
        default_value_t = 30,
        value_parser = clap::value_parser!(u64).range(0..=240)
    )]
    pool_lead_time_max: u64,
}

/// `--pool-runtime`.
#[derive(Clone, Copy, PartialEq, Eq, ValueEnum)]
enum PoolRuntime {
    Claude,
    Codex,
    Pi,
    Opencode,
    Any,
}

impl PoolRuntime {
    /// The runtime of the pool's image; `None` for `any`, the multi-runtime image.
    fn runtime(self) -> Option<Runtime> {
        match self {
            PoolRuntime::Claude => Some(Runtime::ClaudeCode),
            PoolRuntime::Codex => Some(Runtime::Codex),
            PoolRuntime::Pi => Some(Runtime::Pi),
            PoolRuntime::Opencode => Some(Runtime::Opencode),
            PoolRuntime::Any => None,
        }
    }
}

impl VmArgs {
    /// The VM executor's options and its pool's, with `--executor vm`.
    fn options(
        &self,
    ) -> Result<
        Option<(
            tinfirm_runner_host::vm::VmOptions,
            tinfirm_runner_host::pool::PoolOptions,
        )>,
    > {
        use tinfirm_runner_host::vm::{DriverCommand, VmOptions, parse_probe};
        if self.executor == ExecutorChoice::Docker {
            if self.vm_driver.is_some() || self.vm_template.is_some() {
                tracing::warn!(
                    "--vm-driver and --vm-template have no effect without --executor vm"
                );
            }
            if self.pool_runtime == PoolRuntime::Any {
                // A Docker host starts each run's own image; only a pool needs them all in one.
                tracing::warn!("--pool-runtime any has no effect without --executor vm");
            }
            return Ok(None);
        }
        let driver = self.vm_driver.as_deref().context(
            "--executor vm needs --vm-driver (`digitalocean`, or the command of a driver)",
        )?;
        let template = self
            .vm_template
            .clone()
            .filter(|t| !t.trim().is_empty())
            .context("--executor vm needs --vm-template, the driver's template of the VMs")?;
        if self.vm_port == 0 {
            bail!("--vm-port must not be 0");
        }
        let mut options = VmOptions::new(DriverCommand::parse(driver)?, template);
        options.port = self.vm_port;
        options.start_timeout = std::time::Duration::from_secs(self.vm_start_timeout);
        options.image_version = match self.vm_image_version {
            ImageVersionChoice::Warn => tinfirm_runner_host::vm::ImageVersion::Warn,
            ImageVersionChoice::Refuse => tinfirm_runner_host::vm::ImageVersion::Refuse,
        };
        for check in self.vm_probe.iter().filter(|c| !c.trim().is_empty()) {
            options
                .probe
                .push(parse_probe(check).map_err(|e| anyhow::anyhow!("--vm-probe: {e}"))?);
        }
        let pool = tinfirm_runner_host::pool::PoolOptions {
            max_ready: self.pool_max_ready,
            headroom: self.pool_headroom,
            max_age: std::time::Duration::from_secs(self.pool_max_age * 60),
            cool_down: std::time::Duration::from_secs(self.pool_cool_down * 60),
            runtime: self.pool_runtime.runtime().unwrap_or(Runtime::ClaudeCode),
            multi_runtime: self.pool_runtime.runtime().is_none(),
            lead_time_max: std::time::Duration::from_secs(self.pool_lead_time_max * 60),
            ..tinfirm_runner_host::pool::PoolOptions::default()
        };
        Ok(Some((options, pool)))
    }
}

/// Parses `KEY=VALUE` entries with `parse`, skipping blank ones (such as the line after the
/// last newline of `TINFIRM_RUNNER_SANDBOX_ENV`). Errors name the option, never the value.
fn parse_entries(
    option: &str,
    entries: &[String],
    parse: fn(&str) -> std::result::Result<(String, String), String>,
) -> Result<Vec<(String, String)>> {
    entries
        .iter()
        .filter(|entry| !entry.trim().is_empty())
        .map(|entry| parse(entry).map_err(|e| anyhow::anyhow!("{option}: {e}")))
        .collect()
}

#[derive(Subcommand)]
enum CredentialCommand {
    /// Add a credential.
    #[command(subcommand)]
    Add(AddCredential),
    /// List credentials (metadata only).
    List,
    /// Remove a credential.
    Remove { label: String },
}

#[derive(Args)]
struct LabelArgs {
    #[arg(long)]
    label: String,
}

#[derive(Subcommand)]
enum AddCredential {
    /// A Claude Pro/Max login: runs Claude Code's own sign-in (`claude auth login`) into a
    /// directory of this host, which each run mounts into its sandbox; the host holds no token.
    Claude {
        #[command(flatten)]
        label: LabelArgs,
        /// The most runs at once with this login, 1 to 16: a ceiling. Members can lower it
        /// in Tinfirm, never raise it. The runs share the one login; Claude Code renews it
        /// in place and every run reads the renewed file.
        #[arg(long, default_value_t = DEFAULT_CLAUDE_POOL)]
        pool: u32,
    },
    /// A ChatGPT Plus/Pro login for Codex (`codex login` into a dedicated CODEX_HOME).
    Codex {
        #[command(flatten)]
        label: LabelArgs,
        /// Use the device-code flow (for machines without a browser).
        #[arg(long)]
        device_auth: bool,
        /// The most runs at once with this login, 1 to 16: a ceiling. Members can lower it
        /// in Tinfirm, never raise it. The runs share the one login; Codex renews it in
        /// place and re-reads the file before each renewal, so one renewal serves them all.
        #[arg(long, default_value_t = DEFAULT_CODEX_POOL)]
        pool: u32,
    },
    /// An Anthropic API key (Claude Code, pi, OpenCode).
    AnthropicApiKey {
        #[command(flatten)]
        label: LabelArgs,
        /// The most runs at once with this key, 1 to 16 (a ceiling).
        #[arg(long, default_value_t = DEFAULT_API_KEY_POOL)]
        pool: u32,
    },
    /// An OpenAI API key (Codex, pi, OpenCode).
    OpenaiApiKey {
        #[command(flatten)]
        label: LabelArgs,
        /// The most runs at once with this key, 1 to 16 (a ceiling).
        #[arg(long, default_value_t = DEFAULT_API_KEY_POOL)]
        pool: u32,
    },
}

#[derive(Subcommand)]
enum MirrorsCommand {
    /// List the mirrors: key, size, last use, last gc and repository.
    List,
    /// Delete idle mirrors and collect garbage in the others, like `run` does every 6 hours.
    /// Refused while `run` is active.
    Prune {
        /// Delete mirrors not used for more than N days (default:
        /// TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS, or 30).
        #[arg(long, value_name = "N")]
        older_than_days: Option<u32>,
        /// Only show what would be deleted and collected.
        #[arg(long)]
        dry_run: bool,
    },
}

#[derive(Subcommand)]
enum ImagesCommand {
    /// Build tinfirm-runner-base:local and the runtime images from the embedded Dockerfiles.
    /// `--runtime all` builds each runtime's image; `multi` the image with every runtime, for
    /// a pool of ready VMs that serves them all (`run --pool-runtime any`, ADR-0101). The build
    /// arguments and images are recorded (images.json): `run` rebuilds these images with them
    /// when a new binary brings other Dockerfiles.
    Build {
        #[arg(long, value_enum, default_value_t = RuntimeChoice::All)]
        runtime: RuntimeChoice,
        #[arg(long)]
        no_cache: bool,
        /// Set a build argument of the images, such as APT_MIRROR, DOCKER_APT_URL,
        /// NPM_REGISTRY, PIP_INDEX_URL, MAVEN_REPOSITORY_URL or GRADLE_DOWNLOAD_URL for a host
        /// without internet access (runner/README.md); repeatable. Never a credential: the
        /// values stay in the image's history.
        #[arg(long = "build-arg", value_name = "KEY=VALUE")]
        build_args: Vec<String>,
    },
}

#[derive(Clone, Copy, ValueEnum)]
enum RuntimeChoice {
    Claude,
    Codex,
    Pi,
    Opencode,
    All,
    Multi,
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    init_tracing(&cli.log);
    if matches!(cli.command, Command::Bridge) {
        return bridge();
    }
    if let Command::VmDriver { driver } = &cli.command {
        let VmDriverCommand::Digitalocean { config } = driver;
        let config = config.clone();
        return in_runtime(async move {
            tinfirm_runner_host::digitalocean::run(&config).await?;
            Ok(0)
        });
    }
    if let Command::VmBoot { user_data, bridge } = &cli.command {
        let (source, bridge) = (user_data.clone(), bridge.clone());
        return in_runtime(async move { tinfirm_runner_host::vm_boot::run(&source, bridge).await });
    }
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            eprintln!("error: cannot start the async runtime: {e}");
            return ExitCode::FAILURE;
        }
    };
    match runtime.block_on(dispatch(cli)) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e:#}");
            ExitCode::FAILURE
        }
    }
}

/// Runs `task` to its end on a runtime of its own; its exit code, or 1 with its error.
fn in_runtime(task: impl std::future::Future<Output = Result<i32>>) -> ExitCode {
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            eprintln!("error: cannot start the async runtime: {e}");
            return ExitCode::FAILURE;
        }
    };
    match runtime.block_on(task) {
        Ok(code) => ExitCode::from(u8::try_from(code).unwrap_or(1)),
        Err(e) => {
            eprintln!("error: {e:#}");
            ExitCode::FAILURE
        }
    }
}

/// `bridge`, in the sandbox: connects to the runner host, starts the adapter when the host
/// says so, and exits with the adapter's code (or one of the bridge's own).
fn bridge() -> ExitCode {
    let code = match tinfirm_bridge::sandbox::Config::from_env() {
        Ok(mut config) => match tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        {
            Ok(runtime) => {
                // In a VM, what the host checks the VM's image by (ADR-0101).
                config.build = tinfirm_runner_host::images::build_id().to_string();
                runtime.block_on(tinfirm_bridge::sandbox::run(config))
            }
            Err(e) => {
                eprintln!("tinfirm-bridge: cannot start the async runtime: {e}");
                tinfirm_bridge::sandbox::EXIT_FAILED
            }
        },
        Err(e) => {
            eprintln!("tinfirm-bridge: {e:#}");
            tinfirm_bridge::sandbox::EXIT_FAILED
        }
    };
    ExitCode::from(u8::try_from(code).unwrap_or(1))
}

fn init_tracing(filter: &str) {
    let filter = tracing_subscriber::EnvFilter::try_new(filter)
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    // A log line that cannot be written is lost, nothing more. With internal errors on, the
    // subscriber reports the failed write with `eprintln!`, which panics when stderr is gone too
    // (a closed terminal: EIO), and so did the task that logged: a SIGHUP that drained half.
    let _ = tracing_subscriber::fmt()
        .json()
        .with_env_filter(filter)
        .with_writer(std::io::stderr)
        .with_current_span(false)
        .log_internal_errors(false)
        .try_init();
}

fn paths(cli: &Cli) -> Result<Paths> {
    let defaults = Paths::from_env()?;
    Ok(Paths::new(
        cli.config_dir.clone().unwrap_or(defaults.config_dir),
        cli.data_dir.clone().unwrap_or(defaults.data_dir),
    ))
}

async fn dispatch(cli: Cli) -> Result<()> {
    let paths = paths(&cli)?;
    match cli.command {
        Command::Login { server, token } => login(&paths, &server, token),
        Command::Credential(CredentialCommand::Add(add)) => add_credential(&paths, add).await,
        Command::Credential(CredentialCommand::List) => list_credentials(&paths),
        Command::Credential(CredentialCommand::Remove { label }) => {
            if CredentialStore::new(&paths).remove(&label)? {
                println!(
                    "Removed credential `{label}`. A running `run` refuses it at once; drain or restart `run` so the server sees the change."
                );
                Ok(())
            } else {
                bail!("no credential `{label}`")
            }
        }
        Command::Images(ImagesCommand::Build {
            runtime,
            no_cache,
            build_args,
        }) => build_images(&paths, runtime, no_cache, &build_args).await,
        Command::Doctor => doctor(&paths).await,
        Command::Mirrors(MirrorsCommand::List) => list_mirrors(&paths),
        Command::Mirrors(MirrorsCommand::Prune {
            older_than_days,
            dry_run,
        }) => prune_mirrors(&paths, older_than_days, dry_run).await,
        Command::Run {
            max_concurrent_runs,
            protocol_version,
            server,
            token_file,
            no_image_rebuild,
            upload_max_bytes,
            sandbox,
            vm,
        } => {
            let login = server.zip(token_file);
            run(
                &paths,
                max_concurrent_runs,
                protocol_version,
                login,
                no_image_rebuild,
                upload_max_bytes,
                sandbox,
                *vm,
            )
            .await
        }
        Command::Drain { no_wait } => drain(&paths, no_wait).await,
        Command::Bridge => bail!("`bridge` runs in a sandbox, started by `run --sandbox-bridge`"),
        Command::VmDriver { .. } | Command::VmBoot { .. } => {
            unreachable!("handled before the dispatch")
        }
    }
}

/// Reads a secret without echo, or one line from stdin when it is not a terminal.
fn read_secret(prompt: &str) -> Result<String> {
    let value = if std::io::stdin().is_terminal() {
        rpassword::prompt_password(prompt).context("cannot read from the terminal")?
    } else {
        let mut line = String::new();
        std::io::stdin().lock().read_line(&mut line)?;
        line
    };
    // Tokens and keys never contain whitespace, but a token copied from a wrapped terminal
    // line can: drop it rather than store a token that the model provider rejects.
    let value: String = value.chars().filter(|c| !c.is_whitespace()).collect();
    if value.is_empty() {
        bail!("nothing entered");
    }
    Ok(value)
}

/// What `login --token` prints (I8 of the security review of 2026-10-27).
const TOKEN_ON_COMMAND_LINE: &str = "warning: a token on the command line shows in the process list and the shell's history; omit --token and enter it at the prompt, or pipe it on stdin";

fn login(paths: &Paths, server: &str, token: Option<String>) -> Result<()> {
    let server = config::validate_server_url(server)?;
    let token = match token {
        Some(t) => {
            eprintln!("{TOKEN_ON_COMMAND_LINE}");
            t.trim().to_string()
        }
        None => read_secret("Runner host token (tfr_…): ")?,
    };
    if !config::token_looks_valid(&token) {
        eprintln!("warning: the token does not look like `tfr_` + 43 base64url characters");
    }
    secure_fs::ensure_private_dir(&paths.config_dir)?;
    HostConfig::new(server.clone(), Secret::new(token)).save(paths)?;
    println!(
        "Saved the server URL ({server}) and the host token to {}.",
        paths.config_file().display()
    );
    println!(
        "Next: add a credential (`credential add claude|codex …`), then `images build`, `doctor` and `run`."
    );
    Ok(())
}

fn check_pool(pool: u32) -> Result<u32> {
    if pool == 0 || pool > MAX_CAPACITY {
        bail!("--pool must be between 1 and {MAX_CAPACITY}");
    }
    Ok(pool)
}

fn save_secret_credential(
    paths: &Paths,
    label: &str,
    kind: CredentialKind,
    pool: u32,
    secret: String,
) -> Result<()> {
    credentials::validate_label(label)?;
    let problems = credentials::check_secret_format(kind, &secret);
    if !problems.is_empty() {
        eprintln!("warning: the value {}", problems.join(", "));
    }
    CredentialStore::new(paths).save(&Credential {
        label: label.to_string(),
        kind,
        pool_size: check_pool(pool)?,
        created_at: Utc::now(),
        secret: Some(Secret::new(secret)),
    })?;
    println!(
        "Stored credential `{label}` ({kind}, pool {pool}) in {}.",
        paths.credential_file(label).display()
    );
    println!(
        "If `run` is running, drain or restart it (`tinfirm-runner-host drain`), so the server sees the credential."
    );
    Ok(())
}

async fn add_credential(paths: &Paths, add: AddCredential) -> Result<()> {
    match add {
        AddCredential::Claude { label, pool } => add_claude(paths, &label.label, pool).await,
        AddCredential::AnthropicApiKey { label, pool } => {
            let key = read_secret("Anthropic API key (input hidden): ")?;
            save_secret_credential(
                paths,
                &label.label,
                CredentialKind::AnthropicApiKey,
                pool,
                key,
            )
        }
        AddCredential::OpenaiApiKey { label, pool } => {
            let key = read_secret("OpenAI API key (input hidden): ")?;
            save_secret_credential(paths, &label.label, CredentialKind::OpenaiApiKey, pool, key)
        }
        AddCredential::Codex {
            label,
            device_auth,
            pool,
        } => add_codex(paths, &label.label, device_auth, pool).await,
    }
}

/// `credential add claude`: Claude Code's own sign-in into the login's `CLAUDE_CONFIG_DIR`
/// (ADR-0085), with the `claude` CLI of this machine when there is one, else with the one in
/// the runner image. The login is Claude Code's `.credentials.json` in that directory: the
/// host checks that it is there and private, reads nothing from it, and mounts it into the
/// sandbox of each run that pays with this credential.
async fn add_claude(paths: &Paths, label: &str, pool: u32) -> Result<()> {
    credentials::validate_label(label)?;
    if !(1..=MAX_CAPACITY).contains(&pool) {
        bail!("--pool must be between 1 and {MAX_CAPACITY}");
    }
    let store = CredentialStore::new(paths);
    let login = store.claude_login(label);
    secure_fs::ensure_private_dir(&paths.data_dir)?;
    secure_fs::ensure_private_dir(&paths.claude_logins_dir())?;
    secure_fs::ensure_private_dir(&login)?;
    println!("Claude (Pro/Max) login for credential `{label}`");
    println!("CLAUDE_CONFIG_DIR for this credential: {}", login.display());
    println!("(This is a dedicated directory; your own ~/.claude is not touched.)");
    println!();
    let credentials_file = store.claude_credentials_file(label);
    if credentials_file.exists() {
        println!("A login is there already. Signing in again replaces it.");
    }
    if let Some(claude) = which("claude") {
        println!("Running `claude auth login` with CLAUDE_CONFIG_DIR set to that directory…");
        println!("Sign in with the Claude account this host should use. Without a browser on");
        println!("this machine, open the printed URL elsewhere and paste the code it shows.");
        println!();
        let status = tokio::process::Command::new(claude)
            .args(["auth", "login"])
            .env("CLAUDE_CONFIG_DIR", &login)
            .status()
            .await
            .context("cannot run `claude auth login`")?;
        if !status.success() {
            bail!("`claude auth login` failed ({status})");
        }
    } else if let Some(docker) = which("docker") {
        println!("The `claude` CLI is not on this machine's PATH: running Claude Code's sign-in");
        println!(
            "from the runner image {} instead (build it first",
            images::CLAUDE_IMAGE
        );
        println!("with `images build` if it is missing).");
        println!("Sign in with the Claude account this host should use: open the printed URL");
        println!("in a browser elsewhere and paste the code it shows.");
        println!();
        let status = tokio::process::Command::new(docker)
            .args(["run", "--rm", "-it"])
            .args(["-e", "CLAUDE_CONFIG_DIR=/login"])
            .arg("-v")
            .arg(format!("{}:/login", login.display()))
            .arg(images::CLAUDE_IMAGE)
            .args(["claude", "auth", "login"])
            .status()
            .await
            .context("cannot run `docker run`")?;
        if !status.success() {
            bail!("`claude auth login` in the runner image failed ({status})");
        }
    } else {
        println!("Neither the `claude` CLI nor `docker` is on this machine's PATH. Either:");
        println!("  a) install Claude Code (https://code.claude.com/docs/en/setup) and run this");
        println!("     command again, or");
        println!("  b) on a machine with Claude Code, run:");
        println!("       export CLAUDE_CONFIG_DIR=$(mktemp -d) && claude auth login");
        println!(
            "     then copy $CLAUDE_CONFIG_DIR/.credentials.json to {}",
            credentials_file.display()
        );
        println!("     and `chmod 600` it, and run this command again to register the login.");
        println!();
    }
    let mut problems = credentials::check_claude_login(&login);
    if problems.is_empty() && std::fs::metadata(&credentials_file).is_ok_and(|meta| meta.len() == 0)
    {
        problems.push(format!(
            "{} is empty; sign in again",
            credentials_file.display()
        ));
    }
    if !problems.is_empty() {
        bail!(
            "the Claude login is not usable, so the credential was not added: {}",
            problems.join("; ")
        );
    }
    // Whether the login is in force is Claude Code's to say: `auth status` exits 0 when it is
    // signed in. The host reads no byte of the credentials file itself.
    let status = if let Some(claude) = which("claude") {
        Some(
            tokio::process::Command::new(claude)
                .args(["auth", "status"])
                .env("CLAUDE_CONFIG_DIR", &login)
                .stdout(std::process::Stdio::null())
                .status()
                .await
                .context("cannot run `claude auth status`")?,
        )
    } else if let Some(docker) = which("docker") {
        Some(
            tokio::process::Command::new(docker)
                .args(["run", "--rm"])
                .args(["-e", "CLAUDE_CONFIG_DIR=/login"])
                .arg("-v")
                .arg(format!("{}:/login", login.display()))
                .arg(images::CLAUDE_IMAGE)
                .args(["claude", "auth", "status"])
                .stdout(std::process::Stdio::null())
                .status()
                .await
                .context("cannot run `docker run`")?,
        )
    } else {
        None
    };
    match status {
        Some(status) if !status.success() => bail!(
            "Claude Code is not signed in with the login in {} (`claude auth status` {status}); the credential was not added",
            login.display()
        ),
        Some(_) => println!("Claude Code confirms the login (`claude auth status`)."),
        None => println!(
            "Registered without asking Claude Code (`claude auth status` needs the CLI or Docker)."
        ),
    }
    store.save(&Credential {
        label: label.to_string(),
        kind: CredentialKind::ClaudeLogin,
        pool_size: pool,
        created_at: Utc::now(),
        secret: None,
    })?;
    println!(
        "Stored credential `{label}` (CLAUDE_LOGIN, pool {pool}). The login stays in {},",
        credentials_file.display()
    );
    println!("where Claude Code renews it; the host reads nothing from it.");
    println!(
        "If `run` is running, drain or restart it (`tinfirm-runner-host drain`), so the server sees the credential."
    );
    Ok(())
}

async fn add_codex(paths: &Paths, label: &str, device_auth: bool, pool: u32) -> Result<()> {
    credentials::validate_label(label)?;
    if !(1..=MAX_CAPACITY).contains(&pool) {
        bail!("--pool must be between 1 and {MAX_CAPACITY}");
    }
    let store = CredentialStore::new(paths);
    let codex_home = store.codex_home(label);
    secure_fs::ensure_private_dir(&paths.data_dir)?;
    secure_fs::ensure_private_dir(&paths.codex_homes_dir())?;
    secure_fs::ensure_private_dir(&codex_home)?;
    // Keep the login in auth.json (not the OS keychain), so the sandbox can use it.
    let config_toml = codex_home.join("config.toml");
    if !config_toml.exists() {
        secure_fs::write_private_file(&config_toml, b"cli_auth_credentials_store = \"file\"\n")?;
    }
    println!("Codex (ChatGPT Plus/Pro) login for credential `{label}`");
    println!("CODEX_HOME for this credential: {}", codex_home.display());
    println!("(This is a dedicated directory; your own ~/.codex is not touched.)");
    println!();
    let codex = which("codex");
    match codex {
        Some(codex) => {
            println!("Running `codex login` with CODEX_HOME set to that directory…");
            let mut cmd = tokio::process::Command::new(codex);
            cmd.arg("login")
                .arg("-c")
                .arg("cli_auth_credentials_store=\"file\"")
                .env("CODEX_HOME", &codex_home);
            if device_auth {
                cmd.arg("--device-auth");
            }
            let status = cmd.status().await.context("cannot run `codex login`")?;
            if !status.success() {
                bail!("`codex login` failed ({status})");
            }
        }
        None => {
            println!("The `codex` CLI is not installed on this host. Either:");
            println!(
                "  a) install it (`npm install -g @openai/codex`) and run this command again, or"
            );
            println!("  b) on a machine with the Codex CLI, run:");
            println!(
                "       export CODEX_HOME=$(mktemp -d) && codex login -c 'cli_auth_credentials_store=\"file\"'"
            );
            println!(
                "     then copy $CODEX_HOME/auth.json to {}/auth.json",
                codex_home.display()
            );
            println!("     and `chmod 600` it. Use that login only on this host afterwards:");
            println!("     ChatGPT refresh tokens are single-use, so two copies break each other.");
            println!();
            println!("Then run this command again to register the credential.");
        }
    }
    let auth = codex_home.join("auth.json");
    if !auth.exists() {
        bail!(
            "{} does not exist yet; the credential was not added",
            auth.display()
        );
    }
    std::fs::set_permissions(&auth, std::os::unix::fs::PermissionsExt::from_mode(0o600))?;
    let problems = credentials::check_codex_home(&codex_home);
    if !problems.is_empty() {
        bail!("the Codex login is not usable: {}", problems.join("; "));
    }
    store.save(&Credential {
        label: label.to_string(),
        kind: CredentialKind::CodexChatgptAuth,
        pool_size: pool,
        created_at: Utc::now(),
        secret: None,
    })?;
    println!(
        "Stored credential `{label}` (CODEX_CHATGPT_AUTH, pool {pool}). The runs of the pool share this one login; keep no copy of it elsewhere."
    );
    println!(
        "If `run` is running, drain or restart it (`tinfirm-runner-host drain`), so the server sees the credential."
    );
    Ok(())
}

fn which(program: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|dir| dir.join(program))
        .find(|candidate| candidate.is_file())
}

fn list_credentials(paths: &Paths) -> Result<()> {
    let list = CredentialStore::new(paths).list()?;
    if list.is_empty() {
        println!(
            "No credentials. Add one with `credential add claude|codex|anthropic-api-key|openai-api-key`."
        );
        return Ok(());
    }
    println!(
        "{:<24} {:<20} {:<24} {:<5} CREATED",
        "LABEL", "KIND", "RUNTIMES", "POOL"
    );
    for c in list {
        let runtimes: Vec<&str> = c.kind.runtimes().iter().map(|r| r.as_str()).collect();
        println!(
            "{:<24} {:<20} {:<24} {:<5} {}",
            c.label,
            c.kind.as_str(),
            runtimes.join(","),
            c.pool_ceiling(),
            c.created_at.format("%Y-%m-%d %H:%M UTC")
        );
    }
    Ok(())
}

async fn build_images(
    paths: &Paths,
    choice: RuntimeChoice,
    no_cache: bool,
    build_args: &[String],
) -> Result<()> {
    // Before Docker: a typo in a build argument needs no Docker to be found.
    let build_args = parse_entries("--build-arg", build_args, images::parse_build_arg)?;
    images::check_build_args(&build_args)?;
    let docker = docker::connect()?;
    docker.ping().await.context("Docker is not reachable")?;
    let defs: Vec<images::ImageDef> = match choice {
        RuntimeChoice::Claude => vec![images::CLAUDE],
        RuntimeChoice::Codex => vec![images::CODEX],
        RuntimeChoice::Pi => vec![images::PI],
        RuntimeChoice::Opencode => vec![images::OPENCODE],
        RuntimeChoice::All => images::RUNTIME_IMAGES.to_vec(),
        RuntimeChoice::Multi => vec![images::MULTI],
    };
    // Recorded before the build: a build that fails is retried by `run` with these.
    secure_fs::ensure_private_dir(&paths.config_dir)?;
    let images_file = paths.images_file();
    let mut settings = images::ImageSettings::load(&images_file).unwrap_or_else(|e| {
        eprintln!("warning: {e:#}; it is written anew");
        images::ImageSettings::default()
    });
    settings.record(&defs, &build_args);
    settings.save(&images_file)?;
    println!(
        "Recorded in {}: `run` rebuilds the images of {} with {} when a new binary brings other Dockerfiles.",
        images_file.display(),
        settings
            .runtimes
            .iter()
            .map(|r| r.as_str())
            .chain(settings.multi.then_some("the multi-runtime image"))
            .collect::<Vec<_>>()
            .join(", "),
        if settings.build_args.is_empty() {
            "no build arguments".to_string()
        } else {
            format!(
                "the build arguments {}",
                settings
                    .build_args
                    .keys()
                    .cloned()
                    .collect::<Vec<_>>()
                    .join(", ")
            )
        }
    );
    for def in defs
        .iter()
        .filter(|d| d.multi || d.runtime == Some(Runtime::ClaudeCode))
    {
        println!(
            "Note: {} contains Claude Code. It is built on this machine and must never be pushed to a registry.",
            def.tag
        );
    }
    let mut stdout = std::io::stdout();
    images::build_all(&docker, &defs, no_cache, &build_args, |text| {
        let _ = stdout.write_all(text.as_bytes());
        let _ = stdout.flush();
    })
    .await?;
    for def in std::iter::once(&images::BASE).chain(defs.iter()) {
        let status = images::inspect(&docker, def.tag).await?;
        match status {
            Some(s) => println!(
                "built {} ({}){}",
                def.tag,
                &s.id.trim_start_matches("sha256:")[..12.min(s.id.len())],
                s.version.map(|v| format!(" — {v}")).unwrap_or_default()
            ),
            None => bail!("{} is missing after the build", def.tag),
        }
    }
    Ok(())
}

struct Checks {
    failed: usize,
}

impl Checks {
    fn ok(&mut self, what: &str, detail: impl AsRef<str>) {
        println!("[ ok ] {what}: {}", detail.as_ref());
    }
    fn info(&mut self, what: &str, detail: impl AsRef<str>) {
        println!("[info] {what}: {}", detail.as_ref());
    }
    fn warn(&mut self, what: &str, detail: impl AsRef<str>) {
        println!("[warn] {what}: {}", detail.as_ref());
    }
    fn fail(&mut self, what: &str, detail: impl AsRef<str>) {
        self.failed += 1;
        println!("[FAIL] {what}: {}", detail.as_ref());
    }
}

async fn doctor(paths: &Paths) -> Result<()> {
    let mut checks = Checks { failed: 0 };
    // Directories.
    for (what, dir) in [
        ("config dir", &paths.config_dir),
        ("data dir", &paths.data_dir),
    ] {
        match secure_fs::mode_of(dir) {
            Some(0o700) => checks.ok(what, dir.display().to_string()),
            Some(mode) => checks.warn(
                what,
                format!("{} has mode {mode:o}; expected 700", dir.display()),
            ),
            None => checks.warn(what, format!("{} does not exist yet", dir.display())),
        }
    }
    // The mirrors hold full copies of the task repositories: private to this user.
    let mirrors = paths.mirrors_dir();
    match secure_fs::mode_of(&mirrors) {
        Some(0o700) if mirrors.is_dir() => checks.ok("mirrors dir", mirrors.display().to_string()),
        Some(mode) => checks.warn(
            "mirrors dir",
            format!(
                "{} has mode {mode:o} (or is not a directory); expected a 700 directory, because the mirrors hold the task repositories",
                mirrors.display()
            ),
        ),
        None => checks.ok(
            "mirrors dir",
            format!("{} is created by the first run", mirrors.display()),
        ),
    }
    match Mirrors::new(&mirrors).list() {
        Ok(list) => checks.info(
            "mirrors",
            format!(
                "{} ({} in total; `mirrors list` shows them)",
                list.len(),
                format_bytes(list.iter().map(|m| m.size_bytes).sum())
            ),
        ),
        Err(e) => checks.warn("mirrors", format!("{e:#}")),
    }
    // git.
    match git::git_version().await {
        Some(v) => checks.ok("git", v),
        None => checks.fail(
            "git",
            "not found on PATH (the host clones and pushes with git)",
        ),
    }
    // Docker and images.
    let docker = match docker::connect() {
        Ok(d) => match d.version().await {
            Ok(v) => {
                checks.ok(
                    "docker",
                    format!(
                        "Engine {} ({}/{})",
                        v.version.unwrap_or_default(),
                        v.os.unwrap_or_default(),
                        v.arch.unwrap_or_default()
                    ),
                );
                Some(d)
            }
            Err(e) => {
                checks.fail("docker", format!("not reachable: {e}"));
                None
            }
        },
        Err(e) => {
            checks.fail("docker", format!("{e:#}"));
            None
        }
    };
    if let Some(d) = &docker {
        // For `run --sandbox-runtime` (a Kata runtime shows here once it is registered).
        match d.info().await {
            Ok(info) => {
                let mut names: Vec<String> = info
                    .runtimes
                    .map(|r| r.into_keys().collect())
                    .unwrap_or_default();
                names.sort_unstable();
                checks.info(
                    "docker runtimes",
                    format!(
                        "{} (default {})",
                        names.join(", "),
                        info.default_runtime.as_deref().unwrap_or("runc")
                    ),
                );
            }
            Err(e) => checks.warn("docker runtimes", format!("cannot read them: {e}")),
        }
    }
    let overrides = images::overrides_from_env();
    let image_settings = match images::ImageSettings::load(&paths.images_file()) {
        Ok(settings) => {
            checks.info(
                "image build arguments",
                // Names only: a value may hold a token (a mirror URL's query), and doctor's
                // output gets shared; the values stay in images.json.
                if settings.build_args.is_empty() {
                    "none recorded (`images build` records its own)".to_string()
                } else {
                    format!(
                        "{} (values in {})",
                        settings
                            .build_args
                            .keys()
                            .cloned()
                            .collect::<Vec<_>>()
                            .join(", "),
                        paths.images_file().display()
                    )
                },
            );
            settings
        }
        Err(e) => {
            checks.warn("image build arguments", format!("{e:#}"));
            images::ImageSettings::default()
        }
    };
    if let Some(d) = &docker {
        // What `run` would rebuild when it starts (ADR-0062).
        let (args, _) = image_settings.args();
        let all: Vec<images::ImageDef> = images::RUNTIME_IMAGES
            .into_iter()
            .chain(std::iter::once(images::MULTI))
            .collect();
        let stale = match images::present_specs(d, &images::BASE, &all).await {
            Ok(present) => {
                images::plan_refresh(&images::BASE, &all, &args, &image_settings, &present).runtimes
            }
            Err(_) => Vec::new(),
        };
        for runtime in Runtime::ALL {
            let image = overrides
                .get(&runtime)
                .cloned()
                .unwrap_or_else(|| images::default_image(runtime).to_string());
            let staleness = stale
                .iter()
                .find(|(def, _)| def.runtime == Some(runtime) && !overrides.contains_key(&runtime))
                .map(|(_, staleness)| *staleness);
            match images::inspect(d, &image).await {
                Ok(Some(s)) => {
                    let version = s.version.unwrap_or_else(|| "no version label".into());
                    match staleness {
                        Some(_) => checks.warn(
                            &format!("image {runtime}"),
                            format!(
                                "{image} — {version}; built from other Dockerfiles or build arguments than this binary's: `run` rebuilds it when it starts (or run `images build`)"
                            ),
                        ),
                        None => checks.ok(&format!("image {runtime}"), format!("{image} — {version}")),
                    }
                }
                Ok(None) if staleness.is_some() => checks.warn(
                    &format!("image {runtime}"),
                    format!(
                        "{image} is missing: `run` builds it when it starts (or run `images build`)"
                    ),
                ),
                Ok(None) => checks.warn(
                    &format!("image {runtime}"),
                    format!("{image} is missing; run `images build`"),
                ),
                Err(e) => checks.fail(&format!("image {runtime}"), format!("{e:#}")),
            }
        }
        // The multi-runtime image (ADR-0101), only where it is built or recorded.
        let multi =
            images::multi_override_from_env().unwrap_or_else(|| images::MULTI_IMAGE.to_string());
        let staleness = stale
            .iter()
            .find(|(def, _)| def.multi)
            .map(|(_, staleness)| *staleness);
        match images::inspect(d, &multi).await {
            Ok(Some(s)) => {
                let version = s.version.unwrap_or_else(|| "no version label".into());
                match staleness {
                    Some(_) => checks.warn(
                        "image MULTI",
                        format!(
                            "{multi} — {version}; built from other Dockerfiles or build arguments than this binary's: `run` rebuilds it when it starts (or run `images build --runtime multi`)"
                        ),
                    ),
                    None => checks.ok("image MULTI", format!("{multi} — {version}")),
                }
            }
            Ok(None) if staleness.is_some() => checks.warn(
                "image MULTI",
                format!("{multi} is missing: `run` builds it when it starts"),
            ),
            Ok(None) => {}
            Err(e) => checks.fail("image MULTI", format!("{e:#}")),
        }
    }
    #[cfg(target_os = "linux")]
    {
        use std::os::unix::fs::MetadataExt;
        if let Ok(meta) = std::fs::metadata(dirs::home_dir().unwrap_or_default())
            && meta.uid() != 1000
        {
            checks.warn(
                "file ownership",
                "your uid is not 1000; the sandbox runs as 1000:1000 and may not be able to write the bind-mounted workspace",
            );
        }
    }
    // Credentials (presence and format only; no network calls).
    let store = CredentialStore::new(paths);
    match store.list() {
        Ok(list) if list.is_empty() => {
            checks.warn("credentials", "none; add one with `credential add`")
        }
        Ok(list) => {
            for c in list {
                let what = format!("credential {}", c.label);
                let mut problems = Vec::new();
                match c.kind {
                    CredentialKind::CodexChatgptAuth => {
                        problems.extend(credentials::check_codex_home(&store.codex_home(&c.label)))
                    }
                    CredentialKind::ClaudeLogin => problems.extend(
                        credentials::check_claude_login(&store.claude_login(&c.label)),
                    ),
                    CredentialKind::AnthropicApiKey | CredentialKind::OpenaiApiKey => {
                        match &c.secret {
                            Some(s) => problems
                                .extend(credentials::check_secret_format(c.kind, s.expose())),
                            None => problems.push("the secret is missing".into()),
                        }
                    }
                }
                if let Some(mode) = secure_fs::mode_of(&paths.credential_file(&c.label))
                    && mode != 0o600
                {
                    problems.push(format!("file mode {mode:o}; expected 600"));
                }
                if problems.is_empty() {
                    checks.ok(&what, format!("{} (pool {})", c.kind, c.pool_ceiling()));
                } else {
                    checks.fail(&what, format!("{}: {}", c.kind, problems.join("; ")));
                }
            }
        }
        Err(e) => checks.fail("credentials", format!("{e:#}")),
    }
    // A credential file a host of another version wrote (ADR-0085): skipped by `run`, named here.
    for (path, why) in store.unreadable() {
        let file = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("?")
            .to_string();
        checks.fail(
            &format!("credential file {file}"),
            format!("cannot be read ({why}); `credential remove <label>` deletes it, then add the credential again"),
        );
    }
    // Server connection: WebSocket upgrade with the token, then close.
    match HostConfig::load(paths) {
        Err(e) => checks.fail("server", format!("{e:#}")),
        Ok(cfg) => {
            if !config::token_looks_valid(cfg.token.expose()) {
                checks.warn(
                    "host token",
                    "does not look like `tfr_` + 43 base64url characters",
                );
            }
            match run_lock(paths, false) {
                // A second connection with the same token would replace (4409) the one
                // of the running `run` process, so do not open one.
                Ok(None) => checks.warn(
                    "server",
                    "a `run` process is active for this config; not opening a second connection (the server would replace the first)",
                ),
                _ => match probe_server(&cfg).await {
                    Ok(detail) => checks.ok("server", detail),
                    Err(e) => checks.fail("server", format!("{e:#}")),
                },
            }
        }
    }
    if checks.failed > 0 {
        bail!("{} check(s) failed", checks.failed);
    }
    println!("All checks passed.");
    Ok(())
}

/// `2026-09-26 10:00 UTC`, or `fallback`.
fn format_time(time: Option<chrono::DateTime<Utc>>, fallback: &str) -> String {
    time.map(|t| t.format("%Y-%m-%d %H:%M UTC").to_string())
        .unwrap_or_else(|| fallback.to_string())
}

fn list_mirrors(paths: &Paths) -> Result<()> {
    let mirrors = Mirrors::new(paths.mirrors_dir());
    let list = mirrors.list()?;
    if list.is_empty() {
        println!("No mirrors in {}.", mirrors.dir().display());
        return Ok(());
    }
    println!(
        "{:<32}  {:>10}  {:<20}  {:<20}  REPOSITORY",
        "KEY", "SIZE", "LAST USED", "LAST GC"
    );
    for m in &list {
        let state = m.state.clone().unwrap_or_default();
        let known = m.state.is_some();
        println!(
            "{:<32}  {:>10}  {:<20}  {:<20}  {}",
            m.key,
            format_bytes(m.size_bytes),
            format_time(state.last_used_at, "unknown"),
            format_time(state.last_gc_at, if known { "never" } else { "unknown" }),
            state.repo_url.as_deref().unwrap_or("unknown")
        );
    }
    println!(
        "{} mirror(s), {} in {}",
        list.len(),
        format_bytes(list.iter().map(|m| m.size_bytes).sum()),
        mirrors.dir().display()
    );
    Ok(())
}

async fn prune_mirrors(paths: &Paths, older_than_days: Option<u32>, dry_run: bool) -> Result<()> {
    // `run` maintains the mirrors itself; its locks are in its own process, so a second
    // process must not touch them meanwhile. Holding `run.lock` also keeps `run` from starting.
    let _lock = if dry_run || !paths.config_dir.is_dir() {
        None
    } else {
        match run_lock(paths, true)? {
            Some(lock) => Some(lock),
            None => bail!(
                "a `tinfirm-runner-host run` process is active for {}; it prunes the mirrors itself every 6 hours. Stop it first to prune now.",
                paths.config_dir.display()
            ),
        }
    };
    let mut policy = Policy::from_env();
    if let Some(days) = older_than_days {
        policy.mirror_max_idle = chrono::TimeDelta::days(days.into());
    }
    let mirrors = Mirrors::new(paths.mirrors_dir());
    if !dry_run {
        // No `run` is active: temporary directories are left over from a crash.
        mirrors.remove_stale_temp_dirs();
    }
    let report = maintenance::maintain_mirrors(
        &mirrors,
        &policy,
        Utc::now(),
        dry_run,
        &CancellationToken::new(),
    )
    .await;
    print_prune_report(&report, &policy);
    if !report.failed.is_empty() {
        bail!("{} mirror(s) could not be pruned", report.failed.len());
    }
    Ok(())
}

fn print_prune_report(report: &MirrorReport, policy: &Policy) {
    let (delete, gc) = if report.dry_run {
        ("would delete", "would gc")
    } else {
        ("deleted", "gc")
    };
    for d in &report.deleted {
        let why = match d.reason {
            DeleteReason::Idle { days } => format!("not used for {days} days"),
            DeleteReason::OverCap => format!(
                "least recently used, over the cap of {}",
                format_bytes(policy.mirror_max_bytes.unwrap_or(0))
            ),
        };
        println!(
            "{delete:<12}  {}  {:>10}  {why}  {}",
            d.key,
            format_bytes(d.bytes),
            d.repo_url.as_deref().unwrap_or("unknown")
        );
    }
    for c in &report.collected {
        let freed = if report.dry_run {
            String::new()
        } else {
            format!(", freed {}", format_bytes(c.freed_bytes))
        };
        println!(
            "{gc:<12}  {}  ({}{freed})  {}",
            c.key,
            c.reason,
            c.repo_url.as_deref().unwrap_or("unknown")
        );
    }
    for key in &report.busy {
        println!("{:<12}  {key}  in use by a run; skipped", "busy");
    }
    for (key, error) in &report.failed {
        println!("{:<12}  {key}  {error}", "failed");
    }
    let deleted_bytes: u64 = report.deleted.iter().map(|d| d.bytes).sum();
    if report.dry_run {
        println!(
            "Dry run: would delete {} mirror(s) ({}) and collect garbage in {}; nothing was changed.",
            report.deleted.len(),
            format_bytes(deleted_bytes),
            report.collected.len()
        );
    } else {
        println!(
            "Deleted {} mirror(s) ({}), collected garbage in {} (freed {}); {} mirror(s), {} left.",
            report.deleted.len(),
            format_bytes(deleted_bytes),
            report.collected.len(),
            format_bytes(report.collected.iter().map(|c| c.freed_bytes).sum()),
            report.remaining,
            format_bytes(report.remaining_bytes)
        );
    }
}

/// Opens the runner WebSocket, waits for the upgrade, and closes it again without `hello`.
async fn probe_server(cfg: &HostConfig) -> Result<String> {
    use tokio_tungstenite::tungstenite::client::IntoClientRequest;
    connection::init_tls();
    let url = cfg.websocket_url()?;
    let mut request = url.as_str().into_client_request()?;
    let mut value = tokio_tungstenite::tungstenite::http::HeaderValue::from_str(&format!(
        "Bearer {}",
        cfg.token.expose()
    ))?;
    value.set_sensitive(true);
    request.headers_mut().insert("Authorization", value);
    let connect = tokio_tungstenite::connect_async(request);
    match tokio::time::timeout(std::time::Duration::from_secs(10), connect).await {
        Err(_) => bail!("{url}: timed out"),
        Ok(Err(tokio_tungstenite::tungstenite::Error::Http(response))) => {
            bail!("{url}: HTTP {} (bad token or wrong URL)", response.status())
        }
        Ok(Err(e)) => bail!("{url}: {e}"),
        Ok(Ok((mut socket, _))) => {
            let _ = socket.close(None).await;
            Ok(format!("{url} accepted the token"))
        }
    }
}

/// The lock that marks a running `run` process for this config directory. Returns
/// `Ok(None)` when another process holds it. Whoever takes it empties it: only `run` writes
/// its PID into it ([`RunLock::write_pid`]), so `drain` never finds a stale one while
/// `mirrors prune` or `doctor` holds the lock.
fn run_lock(paths: &Paths, create: bool) -> Result<Option<RunLock>> {
    use std::os::unix::fs::OpenOptionsExt;
    let path = paths.run_lock_file();
    if !create && !path.exists() {
        return Ok(Some(RunLock {
            file: tempfile_placeholder()?,
            owned: false,
        }));
    }
    let file = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .mode(0o600)
        .open(&path)
        .with_context(|| format!("cannot open {}", path.display()))?;
    match file.try_lock() {
        Ok(()) => {
            file.set_len(0)
                .with_context(|| format!("cannot empty {}", path.display()))?;
            Ok(Some(RunLock { file, owned: true }))
        }
        Err(std::fs::TryLockError::WouldBlock) => Ok(None),
        Err(std::fs::TryLockError::Error(e)) => Err(e).context("cannot lock run.lock"),
    }
}

/// `run.lock`, held until dropped. Dropping it empties the file first, so the PID of a `run`
/// that has ended is not left behind.
struct RunLock {
    file: std::fs::File,
    /// `false` for the stand-in when there is no config directory yet.
    owned: bool,
}

impl RunLock {
    /// Writes this process's PID (decimal, then a newline) for `drain`.
    fn write_pid(&mut self) -> Result<()> {
        use std::io::{Seek, SeekFrom};
        self.file.set_len(0)?;
        self.file.seek(SeekFrom::Start(0))?;
        writeln!(self.file, "{}", std::process::id())?;
        self.file.sync_data()?;
        Ok(())
    }
}

impl RunLock {
    /// For `run`: empties the file, and keeps the lock until the process ends, when the
    /// kernel lets it go. `drain` waits for the lock, so it returns only once `run` is gone.
    fn hold_until_exit(self) {
        if self.owned {
            let _ = self.file.set_len(0);
        }
        // Never closed: the process's end closes it.
        std::mem::forget(self);
    }
}

impl Drop for RunLock {
    fn drop(&mut self) {
        if self.owned {
            let _ = self.file.set_len(0);
        }
    }
}

/// A handle standing in for "no lock needed" (no config yet, so no `run` either).
fn tempfile_placeholder() -> Result<std::fs::File> {
    Ok(std::fs::File::open("/dev/null")?)
}

#[allow(clippy::too_many_arguments)]
async fn run(
    paths: &Paths,
    max_concurrent_runs: u32,
    protocol_version: Option<u32>,
    login: Option<(String, PathBuf)>,
    no_image_rebuild: bool,
    upload_max_bytes: u64,
    sandbox_args: SandboxArgs,
    vm_args: VmArgs,
) -> Result<()> {
    // First: a forbidden combination is refused before anything else happens.
    let vm_options = vm_args.options()?;
    if vm_options.is_none() && max_concurrent_runs > MAX_CAPACITY {
        bail!(
            "--max-concurrent-runs is at most {MAX_CAPACITY} with Docker: the machine runs every sandbox; more need --executor vm, a VM per run"
        );
    }
    let sandbox_env = parse_entries(
        "--sandbox-env",
        &sandbox_args.sandbox_env,
        sandbox::parse_env_entry,
    )?;
    let settings = if vm_options.is_some() {
        SandboxSettings::for_vms(
            sandbox_args.sandbox_runtime,
            sandbox_args.sandbox_docker,
            sandbox_env,
        )?
    } else {
        SandboxSettings::new(
            sandbox_args.sandbox_runtime,
            sandbox_args.sandbox_docker,
            sandbox_env,
        )?
    };
    // A VM's firewall lets nothing out: the egress proxy is its only way to the network.
    let egress_on = sandbox_args.egress_proxy || vm_options.is_some();
    // `--egress-allow-private` lets the model proxy, which is always on, reach a gateway inside
    // the network, and with `--egress-proxy` the allowed hosts too.
    let allow_private = sandbox_args.egress_allow_private;
    let egress = EgressSettings::from_options(
        egress_on,
        &sandbox_args.egress_allow,
        allow_private && egress_on,
        &settings.env,
    )?;
    let model_proxy = ModelProxySettings::from_options(
        allow_private,
        sandbox_args.model_proxy_local_gateway.as_deref(),
    )?;
    // The opt-in of 0.1.0, still accepted so that service files keep working.
    if let Some(value) = sandbox_args.model_proxy {
        tracing::warn!(
            value,
            "--model-proxy (TINFIRM_RUNNER_MODEL_PROXY) has no effect and goes in a later release: the model proxy is always on, and nothing turns it off; remove the option"
        );
    }
    let mut cfg = match &login {
        Some((server, token_file)) => HostConfig::from_token_file(server, token_file)?,
        None => HostConfig::load(paths)?,
    };
    if let Some(version) = protocol_version {
        cfg.protocol_version = config::validate_protocol_version(version)?;
    }
    if cfg.protocol_version < tinfirm_protocol::PROTOCOL_VERSION_V2
        && max_concurrent_runs > MAX_CAPACITY
    {
        bail!(
            "--max-concurrent-runs above {MAX_CAPACITY} needs runner protocol 2 (--protocol-version 2)"
        );
    }
    // The server's licence decides whether it takes such a host (ADR-0100): it sees the
    // bridge in `hello.features`, which protocol 1 has not.
    if cfg.protocol_version < tinfirm_protocol::PROTOCOL_VERSION_V2
        && (sandbox_args.sandbox_bridge || vm_options.is_some())
    {
        bail!("--sandbox-bridge and --executor vm need runner protocol 2 (--protocol-version 2)");
    }
    secure_fs::ensure_private_dir(&paths.data_dir)?;
    secure_fs::ensure_private_dir(&paths.config_dir)?;
    // One `run` per config: two would replace each other's connection (4409) forever.
    let Some(mut lock) = run_lock(paths, true)? else {
        bail!(
            "another `tinfirm-runner-host run` is already running with {}",
            paths.config_dir.display()
        );
    };
    // Before the PID is out: from then on `drain` may send SIGHUP, whose default is to end
    // the process.
    let mut signals = Signals::register()?;
    lock.write_pid().with_context(|| {
        format!(
            "cannot write the PID to {}",
            paths.run_lock_file().display()
        )
    })?;
    let started = match vm_options {
        Some((options, pool)) => {
            vm_executor(
                paths,
                options,
                pool,
                &cfg,
                max_concurrent_runs,
                &settings,
                &egress,
            )
            .await?
        }
        None => {
            let started = docker_executor(
                paths,
                &cfg,
                max_concurrent_runs,
                no_image_rebuild,
                sandbox_args.sandbox_bridge,
                &settings,
                &egress,
                &mut signals,
            )
            .await?;
            let Some(started) = started else {
                return Ok(());
            };
            started
        }
    };
    let (mut opts, endpoint) = started;
    opts.egress = egress;
    // The model proxy is always on: a setup where its listeners could never serve a sandbox is
    // refused now, rather than every run with a model gateway later.
    let listeners = tinfirm_runner_host::model_proxy::check_endpoint(endpoint.as_ref()).await?;
    tracing::info!(
        listen = %listeners.bind.ip(),
        sandbox_host = %listeners.sandbox_host,
        allow_private = model_proxy.allow_private,
        local_gateway = model_proxy
            .local_gateway
            .as_ref()
            .map_or_else(|| "-".to_string(), |origin| origin.ascii_serialization()),
        "model proxy always on: a gateway's key stays on this host, the sandboxes get a dummy key"
    );
    opts.model_proxy = model_proxy;
    opts.max_concurrent_runs = max_concurrent_runs;
    opts.upload_max_bytes = upload_max_bytes;
    opts.sandbox = settings;
    let host = Shared::new(opts, cfg);
    host.warn_operator_public_suffix_wildcards();
    let shutdown = CancellationToken::new();
    tokio::spawn(signals.handle(host.clone(), shutdown.clone()));
    let result = host::serve(host, shutdown).await;
    lock.hold_until_exit();
    result
}

/// The host's options and its sandboxes' endpoint with this machine's Docker: Docker and its
/// runtime are there, the bridge binary runs in the sandboxes, the images are current, and
/// with the egress proxy the sandboxes' network is internal. `None`: a signal came during the
/// image rebuild, and `run` ends.
#[allow(clippy::too_many_arguments)]
async fn docker_executor(
    paths: &Paths,
    cfg: &HostConfig,
    max_concurrent_runs: u32,
    no_image_rebuild: bool,
    sandbox_bridge: bool,
    settings: &SandboxSettings,
    egress: &Option<EgressSettings>,
    signals: &mut Signals,
) -> Result<
    Option<(
        HostOptions,
        Option<tinfirm_runner_host::git_proxy::Endpoint>,
    )>,
> {
    let docker = docker::connect()?;
    docker.ping().await.context("Docker is not reachable")?;
    let runtime = sandbox::check_runtime(&docker, settings).await?;
    // The bridge is this binary, mounted into every sandbox: it has to run there.
    let bridge = if sandbox_bridge {
        let binary = BridgeOptions::binary_from_env()?;
        let info = docker.info().await.context("cannot query Docker")?;
        let arch = info.architecture.unwrap_or_default();
        tinfirm_runner_host::bridge::check_static_linux(&binary, &arch)
            .context("--sandbox-bridge needs a bridge binary that runs in the sandboxes")?;
        Some(BridgeOptions::new(binary))
    } else {
        None
    };
    tracing::info!(
        runner_version = tinfirm_runner_host::version::display(),
        protocol_version = cfg.protocol_version,
        max_concurrent_runs,
        executor = "docker",
        sandbox_runtime = %runtime,
        isolation_tier = settings.tier().as_str(),
        sandbox_docker = settings.docker,
        sandbox_bridge = bridge.is_some(),
        sandbox_env = ?settings.env_keys(),
        "runner host starting"
    );
    // Before the connection: the server learns the runtimes and their versions from `hello`.
    // No run is active yet, so a signal (a stop, or the drain of an upgrade) ends the rebuild
    // and `run` at once.
    if no_image_rebuild {
        tracing::info!("image rebuild off (--no-image-rebuild): the images stay as they are");
    } else {
        tokio::select! {
            () = refresh_images(&docker, paths) => {}
            signal = signals.next() => {
                tracing::info!(signal, "image rebuild stopped; exiting before connecting");
                return Ok(None);
            }
        }
    }
    let mut opts = HostOptions::new(paths.clone(), Executor::Docker(docker.clone()));
    if egress.is_some() {
        // The sandbox's way out is the proxy: its network must have no other.
        let network = match opts.sandbox_network.clone() {
            None => docker::EGRESS_NETWORK.to_string(),
            Some(name) if name == "none" || name == "host" => bail!(
                "--egress-proxy needs a Docker network that the sandboxes reach the proxy on; TINFIRM_RUNNER_SANDBOX_NETWORK={name} has none (leave it unset)"
            ),
            Some(name) => name,
        };
        docker::ensure_internal_network(&docker, &network).await?;
        opts.sandbox_network = Some(network);
    }
    // Where the sandboxes reach this machine: the git proxy listens there, and so do the egress
    // and model proxies' listeners. It decides whether they can reach them.
    let endpoint = tinfirm_runner_host::git_proxy::endpoint(
        &opts.executor,
        opts.sandbox_network.as_deref(),
        &opts.git_proxy,
    )
    .await?;
    if let Some(egress) = egress {
        if let Some(reason) = endpoint.as_ref().and_then(|endpoint| {
            tinfirm_runner_host::egress_proxy::unsupported_sandbox_host(&endpoint.sandbox_host)
        }) {
            bail!("{reason}");
        }
        tracing::info!(
            network = opts.sandbox_network.as_deref().unwrap_or_default(),
            allowed = ?egress.allow.iter().map(ToString::to_string).collect::<Vec<_>>(),
            allow_private = egress.allow_private,
            "egress proxy on: sandboxes have no network but the proxy"
        );
    }
    opts.bridge = bridge;
    Ok(Some((opts, endpoint)))
}

/// The host's options with a VM per run (ADR-0096): the driver speaks this host's protocol,
/// the template keeps the contract of a sandbox VM, the account may have as many VMs as the
/// host's ceiling, and every agent runs over the bridge, which this host dials. No Docker.
async fn vm_executor(
    paths: &Paths,
    options: tinfirm_runner_host::vm::VmOptions,
    pool: tinfirm_runner_host::pool::PoolOptions,
    cfg: &HostConfig,
    max_concurrent_runs: u32,
    settings: &SandboxSettings,
    egress: &Option<EgressSettings>,
) -> Result<(
    HostOptions,
    Option<tinfirm_runner_host::git_proxy::Endpoint>,
)> {
    let start_timeout = options.start_timeout;
    let template = options.template.clone();
    let refuse_other_images =
        options.image_version == tinfirm_runner_host::vm::ImageVersion::Refuse;
    let vms = tinfirm_runner_host::vm::Vms::new(options);
    let (hello, verified) = vms
        .check(max_concurrent_runs)
        .await
        .context("--executor vm: the VM driver's checks failed")?;
    tracing::info!(
        runner_version = tinfirm_runner_host::version::display(),
        protocol_version = cfg.protocol_version,
        max_concurrent_runs,
        executor = "vm",
        // The VMs' images must have been built by this build (ADR-0101).
        runner_build = tinfirm_runner_host::images::build_id(),
        vm_image_version = if refuse_other_images { "refuse" } else { "warn" },
        vm_driver = %hello.driver,
        vm_driver_version = hello.version.as_deref().unwrap_or("-"),
        vm_template = %template,
        vm_limit = ?verified.max_vms,
        pool_max_ready = pool.max_ready,
        pool_runtime = if pool.multi_runtime { "any" } else { pool.runtime.as_str() },
        vm_checked = ?verified.checked,
        isolation_tier = settings.tier().as_str(),
        sandbox_docker = settings.docker,
        sandbox_env = ?settings.env_keys(),
        "runner host starting"
    );
    if let Some(egress) = egress {
        tracing::info!(
            allowed = ?egress.allow.iter().map(ToString::to_string).collect::<Vec<_>>(),
            allow_private = egress.allow_private,
            "egress proxy on: the VMs have no network but the proxy, through their bridge"
        );
    }
    let mut opts = HostOptions::new(paths.clone(), Executor::Vm(vms));
    // The VM's bridge is the image's; this host's listener only takes the connections it
    // dials itself.
    let mut bridge = BridgeOptions::new(BridgeOptions::binary_from_env()?);
    bridge.connect_timeout = start_timeout;
    opts.bridge = Some(bridge);
    opts.pool = pool;
    let endpoint = tinfirm_runner_host::git_proxy::endpoint(
        &opts.executor,
        opts.sandbox_network.as_deref(),
        &opts.git_proxy,
    )
    .await?;
    Ok((opts, endpoint))
}

/// Rebuilds the runner images that were built from other Dockerfiles or build arguments than
/// this binary's, with the build arguments that `images build` recorded (ADR-0062): after an
/// upgrade, `run` starts with the images of its own binary. A failure is logged, and the host
/// goes on with the images it has.
async fn refresh_images(docker: &bollard::Docker, paths: &Paths) {
    let settings = images::ImageSettings::load(&paths.images_file()).unwrap_or_else(|e| {
        tracing::warn!(
            error = %format!("{e:#}"),
            "cannot read the recorded image settings; rebuilding with the default build arguments"
        );
        images::ImageSettings::default()
    });
    let (args, dropped) = settings.args();
    if !dropped.is_empty() {
        tracing::warn!(
            ?dropped,
            "recorded build arguments that the images no longer declare are left out"
        );
    }
    // An image that TINFIRM_RUNNER_IMAGE_* replaces is not ours to rebuild.
    let overrides = images::overrides_from_env();
    let defs: Vec<images::ImageDef> = images::RUNTIME_IMAGES
        .into_iter()
        .filter(|def| def.runtime.is_some_and(|r| !overrides.contains_key(&r)))
        .chain(
            images::multi_override_from_env()
                .is_none()
                .then_some(images::MULTI),
        )
        .collect();
    let present = match images::present_specs(docker, &images::BASE, &defs).await {
        Ok(present) => present,
        Err(e) => {
            tracing::warn!(error = %format!("{e:#}"), "cannot inspect the runner images; they are not rebuilt");
            return;
        }
    };
    let plan = images::plan_refresh(&images::BASE, &defs, &args, &settings, &present);
    if plan.is_empty() {
        tracing::info!("runner images are current");
        return;
    }
    for (def, staleness) in plan
        .base
        .map(|staleness| (images::BASE, staleness))
        .into_iter()
        .chain(plan.runtimes.iter().copied())
    {
        tracing::info!(
            image = def.tag,
            reason = staleness.describe(),
            "rebuilding a runner image before connecting"
        );
    }
    let started = std::time::Instant::now();
    let out = images::refresh(docker, &images::BASE, &plan, &args, |text| {
        for line in text.lines().map(str::trim_end).filter(|l| !l.is_empty()) {
            tracing::debug!(build = line, "image build");
        }
    })
    .await;
    for (tag, staleness) in &out.built {
        tracing::info!(
            image = tag,
            reason = staleness.describe(),
            "rebuilt a runner image"
        );
    }
    for (tag, error) in &out.failed {
        tracing::error!(
            image = tag,
            %error,
            "cannot rebuild a runner image; runs use the one there is, if any (the next start, or `images build`, tries again)"
        );
    }
    tracing::info!(
        seconds = started.elapsed().as_secs(),
        built = out.built.len(),
        failed = out.failed.len(),
        "runner image rebuild done"
    );
}

/// SIGTERM and SIGINT cancel the runs (a second one exits at once); SIGHUP drains
/// (docs/archive/v1/CONVENTIONS.md §15.4 steps 12 and 13).
struct Signals {
    terminate: tokio::signal::unix::Signal,
    interrupt: tokio::signal::unix::Signal,
    hangup: tokio::signal::unix::Signal,
}

impl Signals {
    fn register() -> Result<Signals> {
        use tokio::signal::unix::{SignalKind, signal};
        Ok(Signals {
            terminate: signal(SignalKind::terminate()).context("cannot handle SIGTERM")?,
            interrupt: signal(SignalKind::interrupt()).context("cannot handle SIGINT")?,
            hangup: signal(SignalKind::hangup()).context("cannot handle SIGHUP")?,
        })
    }

    /// The next signal, by name.
    async fn next(&mut self) -> &'static str {
        tokio::select! {
            _ = self.hangup.recv() => "SIGHUP",
            _ = self.terminate.recv() => "SIGTERM",
            _ = self.interrupt.recv() => "SIGINT",
        }
    }

    async fn handle(mut self, host: Host, shutdown: CancellationToken) {
        loop {
            tokio::select! {
                _ = self.hangup.recv() => {
                    if shutdown.is_cancelled() {
                        tracing::info!("SIGHUP ignored: already shutting down");
                    } else if !host.request_drain() {
                        tracing::info!("SIGHUP ignored: already draining");
                    }
                    continue;
                }
                _ = self.terminate.recv() => {}
                _ = self.interrupt.recv() => {}
            }
            if shutdown.is_cancelled() {
                tracing::warn!("second signal: exiting immediately");
                std::process::exit(130);
            }
            tracing::info!("shutting down: cancelling runs, pushing WIP, reporting CANCELLED");
            shutdown.cancel();
        }
    }
}

/// How long `drain` waits for a `run` that holds `run.lock` to write its PID.
const PID_WAIT: std::time::Duration = std::time::Duration::from_secs(3);

/// `drain [--no-wait]` (docs/archive/v1/CONVENTIONS.md §15.2): SIGHUP to the `run` that holds `run.lock`, then
/// wait until it has exited.
async fn drain(paths: &Paths, no_wait: bool) -> Result<()> {
    let path = paths.run_lock_file();
    let nothing = || {
        println!(
            "Nothing to drain: no `run` is active for {}.",
            paths.config_dir.display()
        );
        Ok(())
    };
    let file = match std::fs::File::open(&path) {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return nothing(),
        Err(e) => return Err(e).with_context(|| format!("cannot open {}", path.display())),
    };
    match file.try_lock() {
        // Nobody holds it, so no `run` is active; returning lets it go at once.
        Ok(()) => return nothing(),
        Err(std::fs::TryLockError::WouldBlock) => {}
        Err(std::fs::TryLockError::Error(e)) => {
            return Err(e).with_context(|| format!("cannot lock {}", path.display()));
        }
    }
    let deadline = std::time::Instant::now() + PID_WAIT;
    let pid = loop {
        if let Some(pid) = read_pid(&path) {
            break pid;
        }
        if std::time::Instant::now() >= deadline {
            bail!(
                "{} is held, but holds no PID: `mirrors prune` or `doctor` may be running (try again later), or a `run` older than this command, which you have to stop yourself",
                path.display()
            );
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    };
    // No unsafe code: the `kill` utility sends the signal.
    let sent = std::process::Command::new("kill")
        .args(["-s", "HUP", &pid.to_string()])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::piped())
        .output()
        .context("cannot run `kill`")?;
    if !sent.status.success() {
        if matches!(file.try_lock(), Ok(())) {
            // It exited in the meantime.
            return nothing();
        }
        bail!(
            "cannot send SIGHUP to `run` (PID {pid}): {}",
            String::from_utf8_lossy(&sent.stderr).trim()
        );
    }
    println!(
        "Asked `run` (PID {pid}) to drain: it takes no new runs, lets the active ones finish, then exits."
    );
    if no_wait {
        return Ok(());
    }
    println!("Waiting for it to exit…");
    // `run` holds the lock until it exits; the lock is let go again right away.
    tokio::task::spawn_blocking(move || file.lock())
        .await
        .context("waiting for `run` failed")?
        .with_context(|| format!("cannot lock {}", path.display()))?;
    println!("`run` has exited.");
    Ok(())
}

/// The PID in `run.lock`, if one is there.
fn read_pid(path: &std::path::Path) -> Option<u32> {
    std::fs::read_to_string(path)
        .ok()?
        .trim()
        .parse::<u32>()
        .ok()
        .filter(|pid| *pid > 1)
}
