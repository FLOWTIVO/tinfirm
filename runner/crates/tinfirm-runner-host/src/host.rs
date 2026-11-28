//! The runner host process behind `run`: run registry, admission, capacity settings, server
//! messages, shutdown and drain (docs/archive/v1/CONVENTIONS.md §15.4 steps 1, 11, 12 and 13, §15.7).

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock, Weak};
use std::time::Duration;

use anyhow::{Context, Result};
use chrono::{DateTime, Utc};
use futures::future::BoxFuture;
use tinfirm_protocol::{
    AssignedCredential, CredentialKind, CredentialState, CredentialStatus, ErrorMessage,
    Executor as ExecutorKind, Hello, IsolationTier, MAX_WORKSPACES, Message, ModelAccess,
    PslUpdate, PublicSuffixListVersion, RequestKind, RunAccepted, RunAsk, RunAskAck, RunAssign,
    RunCancel, RunGitRevoke, RunGitRevoked, RunRef, RunRejected, RunRepositoryRequest, RunRequest,
    RunRequestAck, RunSteer, RunTaskCreate, RunTaskCreateAck, Runtime, RuntimeInfo, WorkspaceSpec,
    feature, is_valid_workspace_dir, reject_code,
};
use tokio::sync::{Notify, oneshot, watch};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::attachments::{self, Upload, UploadResponse};
use crate::bridge::BridgeListener;
use crate::capacity::{ApplyOutcome, Capacity};
use crate::config::{HostConfig, HostState, HostStateFile};
use crate::credentials::CredentialStore;
use crate::docker;
use crate::egress_proxy::{EgressProxy, EgressSettings, Rule};
use crate::git_proxy::{self, GitProxy};
use crate::git_tokens::RunGitTokens;
use crate::homes::SessionHomes;
use crate::images;
use crate::maintenance;
use crate::mcp::{PlatformRequester, RequestOutcome};
use crate::mirror::Mirrors;
use crate::model_proxy::{HostToken, ModelProxy, ModelProxySettings};
use crate::outbox::{DEFAULT_FINISHED_RETENTION, Outbox};
use crate::paths::Paths;
use crate::public_suffix::PublicSuffixStore;
use crate::redact::Redactor;
use crate::run::RunCredential;
use crate::sandbox::{Executor, Resources, SandboxSettings};
use crate::skills::SkillCache;
use crate::steer::{self, Steering};
use crate::{connection, run};

/// Settings of a serving host.
#[derive(Debug, Clone)]
pub struct HostOptions {
    pub paths: Paths,
    /// The host's ceiling (`run --max-concurrent-runs`, 1 to 16): the most runs at once. Tinfirm
    /// can lower it with `host.config`, never raise it (docs/archive/v1/CONVENTIONS.md §14.8).
    pub max_concurrent_runs: u32,
    pub executor: Executor,
    pub image_overrides: HashMap<Runtime, String>,
    /// `TINFIRM_RUNNER_IMAGE_MULTI` (development and tests): the multi-runtime image's.
    pub multi_image_override: Option<String>,
    pub resources: Resources,
    /// Docker network of the sandbox; `None` is Docker's default bridge (open egress, a v0
    /// shortcut). `TINFIRM_RUNNER_SANDBOX_NETWORK` sets it, e.g. `none` for offline tests.
    pub sandbox_network: Option<String>,
    /// Docker runtime, Docker in the sandbox and extra environment of the sandboxes
    /// (`run --sandbox-runtime`, `--sandbox-docker`, `--sandbox-env`; ADR-0025).
    pub sandbox: SandboxSettings,
    /// Failed workspaces kept for inspection.
    pub keep_failed_workspaces: usize,
    /// Upper bound for `limits.maxWallClockSeconds` (tests use a few seconds).
    pub max_wall_clock_cap: Option<Duration>,
    pub reconnect_min_backoff: Duration,
    pub reconnect_max_backoff: Duration,
    /// How long a cancelled agent gets to end its turn before the sandbox is stopped.
    pub cancel_grace: Duration,
    /// Inactivity guard: no ACP message for this long → `session/cancel`, GUARD_STOPPED.
    /// `TINFIRM_RUNNER_INACTIVITY_TIMEOUT` (seconds), default 1200.
    pub inactivity_timeout: Duration,
    /// How long a finished run stays in `hello.activeRuns`, its end written again after each
    /// reconnect, once that end was written on a connection that then stayed up
    /// (crate::outbox). `TINFIRM_RUNNER_FINISHED_RETENTION` (seconds), default 180: keep it
    /// above the server's reconnect grace (`TINFIRM_RUNNER_RECONNECT_GRACE`, 2 minutes).
    pub finished_retention: Duration,
    /// Accept `file://` and plain `http://` repository URLs (tests and local development;
    /// `TINFIRM_RUNNER_ALLOW_INSECURE_REPOS=1`). Production components are HTTPS only.
    pub allow_insecure_repo_urls: bool,
    /// `TINFIRM_RUNNER_GIT_PROXY_BIND` and `TINFIRM_RUNNER_GIT_PROXY_HOST`.
    pub git_proxy: git_proxy::Overrides,
    /// `run --egress-proxy`: the sandboxes reach the network only through the egress proxy
    /// (ADR-0044). `None`: the sandbox's network is whatever `sandbox_network` is.
    pub egress: Option<EgressSettings>,
    /// The model proxy, always on: a run whose model access is a gateway reaches it through a
    /// listener of this host, which holds the key (crate::model_proxy), and the sandbox gets a
    /// dummy key. These settings only widen where a gateway may be; nothing turns the proxy off.
    pub model_proxy: ModelProxySettings,
    /// How long a platform request waits for the server's ack: the v1 pairs
    /// (`request_repository`, `create_task`, `ask_human`) and, in v2, `run.request` of kind
    /// ASK, ACCESS, NETWORK and ARTIFACT (30 s).
    pub repository_request_timeout: Duration,
    /// How long a `run.request` of kind TOOL waits for its ack: the server's tool timeout
    /// (60 s by default) plus 5 s.
    pub tool_request_timeout: Duration,
    /// Deletion and gc of mirrors, deletion of session homes and of cached skills
    /// (`TINFIRM_RUNNER_MIRROR_MAX_IDLE_DAYS`, `TINFIRM_RUNNER_MIRROR_MAX_BYTES`,
    /// `TINFIRM_RUNNER_HOME_MAX_IDLE_DAYS`, `TINFIRM_RUNNER_SKILL_CACHE_MAX_IDLE_DAYS`).
    pub maintenance: maintenance::Settings,
    /// `run --sandbox-bridge` (ADR-0095): the adapter runs over the sandbox bridge, and the
    /// run's credentials leave the sandbox's settings. `None`: the host attaches to the
    /// adapter's stdio.
    pub bridge: Option<crate::bridge::BridgeOptions>,
    /// Ready VMs of the VM executor (crate::ready_vms, paid, ADR-0098, ADR-0114): the
    /// operator's ceilings and the tuning (`run --pool-*`). With `max_ready` 0, the default,
    /// the host keeps no VM ready and every run starts cold. Other executors have none.
    pub ready_vms: crate::ready_vms::ReadyVmOptions,
    /// The operator's statement of the level of isolation (`run --isolation-level`,
    /// `--isolation-hardened`, ADR-0113), for a VM driver of their own; `None`: none.
    pub isolation_stated: Option<crate::isolation::Stated>,
    /// A VM that came up this long before a run claims it is probed again then
    /// ([`crate::pool::REPROBE_AFTER`]; no flag sets it).
    pub vm_reprobe_after: Duration,
    /// The largest file an upload reads (`save_artifact`, `upload_attachment`, `/output`):
    /// `run --upload-max-bytes`, by default the server's own default limit (25 MiB). A larger
    /// file is refused on its size before it is read.
    pub upload_max_bytes: u64,
    /// `hello.runnerVersion`: the release's version ([`crate::version::RUNNER_VERSION`]),
    /// which the server shows and decides nothing from (ADR-0109).
    pub runner_version: String,
    /// `run --push-client`: which client pushes a run's work (crate::git_push). `git`, the
    /// default, is `git push` with the run's token in its environment; `host` is the runner
    /// host's own receive-pack client, which has been checked against a git server run for
    /// the test and, for a new branch, GitHub, and not yet against GitLab, Gitea or Azure DevOps.
    pub push_client: crate::git_push::PushClient,
    /// Tests only, and no flag sets it: a run's task waits for good at this moment of the
    /// run's end ([`crate::run::HaltAt`]), so that a test kills the host there.
    #[doc(hidden)]
    pub halt_at: Option<crate::run::HaltAt>,
}

impl HostOptions {
    pub fn new(paths: Paths, executor: Executor) -> HostOptions {
        HostOptions {
            paths,
            max_concurrent_runs: 2,
            executor,
            image_overrides: images::overrides_from_env(),
            multi_image_override: images::multi_override_from_env(),
            resources: Resources::default(),
            sandbox_network: std::env::var("TINFIRM_RUNNER_SANDBOX_NETWORK")
                .ok()
                .filter(|v| !v.trim().is_empty()),
            sandbox: SandboxSettings::default(),
            keep_failed_workspaces: 5,
            max_wall_clock_cap: None,
            reconnect_min_backoff: Duration::from_secs(1),
            reconnect_max_backoff: Duration::from_secs(30),
            cancel_grace: Duration::from_secs(10),
            inactivity_timeout: inactivity_timeout_from_env(),
            finished_retention: finished_retention_from_env(),
            allow_insecure_repo_urls: std::env::var("TINFIRM_RUNNER_ALLOW_INSECURE_REPOS")
                .is_ok_and(|v| v == "1"),
            git_proxy: git_proxy::Overrides::from_env(),
            egress: None,
            model_proxy: ModelProxySettings::default(),
            repository_request_timeout: REPOSITORY_REQUEST_TIMEOUT,
            tool_request_timeout: TOOL_REQUEST_TIMEOUT,
            maintenance: maintenance::Settings::from_env(),
            bridge: None,
            ready_vms: crate::ready_vms::ReadyVmOptions::default(),
            vm_reprobe_after: crate::pool::REPROBE_AFTER,
            isolation_stated: None,
            upload_max_bytes: crate::attachments::DEFAULT_UPLOAD_MAX_BYTES,
            runner_version: crate::version::RUNNER_VERSION.to_string(),
            push_client: crate::git_push::PushClient::Git,
            halt_at: None,
        }
    }

    /// How this host pushes ([`crate::git::Git::push_with`]).
    pub fn push_settings(&self) -> crate::git_push::PushSettings {
        crate::git_push::PushSettings {
            client: self.push_client,
            allow_http: self.allow_insecure_repo_urls,
        }
    }

    pub fn image_for(&self, runtime: Runtime) -> String {
        if self.multi_runtime_image() {
            return self
                .multi_image_override
                .clone()
                .unwrap_or_else(|| images::MULTI_IMAGE.to_string());
        }
        self.image_overrides
            .get(&runtime)
            .cloned()
            .unwrap_or_else(|| images::default_image(runtime).to_string())
    }

    /// Whether every run starts from the multi-runtime image: the VM executor with
    /// `--pool-runtime any` (ADR-0101).
    pub fn multi_runtime_image(&self) -> bool {
        self.ready_vms.multi_runtime && matches!(self.executor, Executor::Vm(_))
    }

    /// The level of isolation the host reports (crate::isolation, ADR-0113): derived from
    /// its executor (with Docker, its runtime; with `--vm-driver node`, its nodes'
    /// configuration), or the operator's statement for a VM driver of their own. An error when
    /// the statement contradicts what the host can see, or the node driver's configuration
    /// cannot be read or is refused.
    pub fn isolation(&self) -> Result<crate::isolation::Reported, String> {
        crate::isolation::Sandboxes::of(&self.executor, &self.sandbox)
            .and_then(|sandboxes| crate::isolation::derive(sandboxes, self.isolation_stated))
    }

    /// Whether the host keeps ready VMs (crate::ready_vms): the VM executor with
    /// `--pool-max-ready` above 0.
    pub fn keeps_ready_vms(&self) -> bool {
        self.ready_vms.max_ready > 0 && matches!(self.executor, Executor::Vm(_))
    }

    /// The command the host names for a run of `runtime`: its adapter, for the multi-runtime
    /// image, which has none of its own; else `None`, the image's own command.
    pub fn adapter_for(&self, runtime: Runtime) -> Option<Vec<String>> {
        self.multi_runtime_image().then(|| {
            images::adapter_command(runtime)
                .iter()
                .map(|a| a.to_string())
                .collect()
        })
    }
}

/// How long the platform tools `request_repository`, `create_task` and `ask_human` (v1), and
/// `ask`, `request_access` and `save_artifact` with a link (v2), wait for the server.
pub const REPOSITORY_REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// How long a server-executed tool call (v2 `run.request` kind TOOL) waits for the server:
/// its tool timeout (`tinfirm.run.tool-timeout`, 60 s by default) plus 5 s.
pub const TOOL_REQUEST_TIMEOUT: Duration = Duration::from_secs(65);

/// Default of `TINFIRM_RUNNER_INACTIVITY_TIMEOUT`: 20 minutes.
pub const DEFAULT_INACTIVITY_TIMEOUT: Duration = Duration::from_secs(1200);

/// Reads `TINFIRM_RUNNER_INACTIVITY_TIMEOUT` (whole seconds, at least 1).
pub fn inactivity_timeout_from_env() -> Duration {
    parse_inactivity_timeout(
        std::env::var("TINFIRM_RUNNER_INACTIVITY_TIMEOUT")
            .ok()
            .as_deref(),
    )
}

fn parse_inactivity_timeout(value: Option<&str>) -> Duration {
    match value.map(str::trim).filter(|v| !v.is_empty()) {
        None => DEFAULT_INACTIVITY_TIMEOUT,
        Some(v) => match v.parse::<u64>() {
            Ok(secs) if secs >= 1 => Duration::from_secs(secs),
            _ => {
                tracing::warn!(
                    value = v,
                    "invalid TINFIRM_RUNNER_INACTIVITY_TIMEOUT; using 1200 s"
                );
                DEFAULT_INACTIVITY_TIMEOUT
            }
        },
    }
}

/// Reads `TINFIRM_RUNNER_FINISHED_RETENTION` (whole seconds, at least 1).
pub fn finished_retention_from_env() -> Duration {
    parse_finished_retention(
        std::env::var("TINFIRM_RUNNER_FINISHED_RETENTION")
            .ok()
            .as_deref(),
    )
}

fn parse_finished_retention(value: Option<&str>) -> Duration {
    match value.map(str::trim).filter(|v| !v.is_empty()) {
        None => DEFAULT_FINISHED_RETENTION,
        Some(v) => match v.parse::<u64>() {
            Ok(secs) if secs >= 1 => Duration::from_secs(secs),
            _ => {
                tracing::warn!(
                    value = v,
                    "invalid TINFIRM_RUNNER_FINISHED_RETENTION; using 180 s"
                );
                DEFAULT_FINISHED_RETENTION
            }
        },
    }
}

/// How long a STALE_EPOCH error waits for the `run.cancel` that names the stale epoch.
const STALE_HINT_TTL: Duration = Duration::from_secs(10);

/// How long a newer epoch of a run waits for the stale copy to end before it starts anyway.
const STALE_COPY_WAIT: Duration = Duration::from_secs(20);

/// How long `hello` waits for Docker to report the runtime images.
const HELLO_IMAGE_CHECK: Duration = Duration::from_secs(5);

/// How often a draining host checks whether its last run has ended.
const DRAIN_POLL: Duration = Duration::from_millis(200);

/// `run.rejected.reason` while the host drains.
const DRAINING_REASON: &str =
    "the runner host is draining: it finishes its active runs and then restarts";

/// Where the current connection's answer to the hello is (ADR-0116, decision 10).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HelloAnswer {
    /// No connection, or no `welcome` yet.
    NotConnected,
    /// `welcome` came; `revokes`: the server lists `run.git.revoke` in `welcome.features`, so
    /// it sends `welcome.done` after every pending revocation of the runs the hello listed.
    Welcomed { revokes: bool },
    /// `welcome.done` came.
    Done,
}

impl HelloAnswer {
    /// Whether a run taken back may use its git tokens and push now: this process has been
    /// welcomed, and the server sent every revocation it keeps for the run (`welcome.done`), or
    /// it is an older one, which keeps none. There is no time after which a run goes on
    /// without it.
    pub fn complete(self) -> bool {
        matches!(
            self,
            HelloAnswer::Done | HelloAnswer::Welcomed { revokes: false }
        )
    }
}

/// Why a run is asked to stop from outside.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopRequest {
    /// `run.cancel` or host shutdown: cancel, push WIP, report CANCELLED.
    Cancel,
    /// Stale epoch: stop at once, push nothing, report nothing.
    Abort,
}

struct RunEntry {
    epoch: i64,
    /// A second run of the same work item (v1: task) is refused while this one is active
    /// (TASK_RUN_ACTIVE): it would share the session home and the branch.
    task_id: Uuid,
    /// The host credential the run uses; `None` for a run with model access (ADR-0027),
    /// which takes a slot of the host but of no credential's pool.
    credential_label: Option<String>,
    stop: watch::Sender<Option<StopRequest>>,
    /// The execution's `run.steer`s, for its agent session.
    steering: Arc<Steering>,
    /// The execution's git tokens, which a `run.git.revoke` drops (ADR-0116).
    git_tokens: Arc<RunGitTokens>,
    /// `run.git.revoked` frames of a run still being admitted, which has no queue in the
    /// outbox yet: [`Shared::admit`] queues them after its `run.accepted`.
    unqueued_revoked: Vec<RunGitRevoked>,
    handle: Option<JoinHandle<()>>,
}

/// A platform request that waits for its ack: a `run.repository_request`, `run.task_create`
/// or `run.ask` (v1), or a `run.request` (v2).
struct PendingRequest {
    run_id: Uuid,
    epoch: i64,
    frame: Message,
    /// Gets the ack of the frame's kind: [`Message::RunRepositoryRequestAck`],
    /// [`Message::RunTaskCreateAck`], [`Message::RunAskAck`] or [`Message::RunRequestAck`].
    answer: oneshot::Sender<Message>,
    /// Told when the frame is sent again after a reconnect.
    resent: Arc<Notify>,
}

/// State shared by the connection, the runs and the CLI entry point.
pub struct Shared {
    pub opts: HostOptions,
    pub config: HostConfig,
    pub credentials: CredentialStore,
    pub outbox: Arc<Outbox>,
    runs: Mutex<HashMap<Uuid, RunEntry>>,
    /// Ceilings, desired and effective values, draining (docs/archive/v1/CONVENTIONS.md §15.7). Lock order:
    /// `runs` before `capacity`, and `capacity` before the outbox.
    capacity: Mutex<Capacity>,
    /// Cancelled by the first [`Shared::request_drain`] (SIGHUP or `drain`).
    drain_requested: CancellationToken,
    cooldowns: Mutex<HashMap<String, DateTime<Utc>>>,
    invalid: Mutex<HashSet<String>>,
    redactor: RwLock<Arc<Redactor>>,
    /// Git tokens of active runs, keyed by (run, epoch).
    run_secrets: Mutex<HashMap<(Uuid, i64), Vec<String>>>,
    /// Git proxy secrets of active runs: redacted, but no credentials.
    run_masked: Mutex<HashMap<(Uuid, i64), Vec<String>>>,
    /// The git tokens of runs taken back that their records keep as salted hashes alone
    /// (crate::secret_hashes): redacted and scanned for as credentials.
    run_hashed: Mutex<HashMap<(Uuid, i64), Arc<crate::secret_hashes::HashedMatcher>>>,
    /// The fetch-only git proxy and platform MCP listener, started by the first run.
    git_proxy: tokio::sync::Mutex<Option<GitProxy>>,
    /// The sandbox bridge's listener (ADR-0095), started by the first run with `opts.bridge`.
    bridge: tokio::sync::Mutex<Option<BridgeListener>>,
    /// The egress proxy of the sandboxes, started by the first run when `opts.egress` is set.
    egress_proxy: tokio::sync::Mutex<Option<Arc<EgressProxy>>>,
    /// The model proxy of the sandboxes, created by the first run whose model access is a gateway.
    model_proxy: tokio::sync::Mutex<Option<Arc<ModelProxy>>>,
    /// The VM executor's pool (crate::pool): every VM of its runs comes from it.
    pool: Option<Arc<crate::pool::Pool>>,
    /// The pool's ready VMs (crate::ready_vms), with `--pool-max-ready` above 0.
    ready_vms: Option<Arc<crate::ready_vms::ReadyVms>>,
    /// What the host's level of isolation is derived from (crate::isolation), read once at
    /// its start: the node driver's configuration is read then. Each hello derives the level
    /// from it, with the node driver's hardening as the VM executor knows it at that time
    /// ([`Shared::isolation`]). `None`: the level was an error at the start, which `run`
    /// refuses before it starts; such a host reports `container`.
    sandboxes: Option<crate::isolation::Sandboxes>,
    /// The node driver's hardening mark that the last hello carried; `None` when it carried
    /// none (protocol 1, or no node driver).
    hello_mark: Mutex<Option<crate::vm::Mark>>,
    /// Counts the times the host asked for a new hello, which the connection makes by a
    /// reconnect: the node driver's hardening mark changed ([`tell_hardening`]).
    rehello: watch::Sender<u64>,
    /// The Public Suffix List the server sent (`psl.update`), if the host holds one.
    public_suffix: Arc<PublicSuffixStore>,
    /// Bare mirrors of the task repositories.
    pub mirrors: Mirrors,
    /// Session homes, and which of them runs hold.
    pub homes: SessionHomes,
    /// The cache of unpacked skills (ADR-0064), and which of its entries runs hold.
    pub skills: SkillCache,
    /// The records of the runs whose sandboxes live, which a restart takes back (ADR-0101).
    pub records: crate::takeback::Records,
    /// Platform requests (v1 `run.repository_request`, `run.task_create`, `run.ask`; v2
    /// `run.request`) waiting for their ack, by `requestId`.
    platform_requests: Mutex<HashMap<Uuid, PendingRequest>>,
    /// Between `welcome` and the end of that connection.
    connected: AtomicBool,
    /// Where the current connection's answer to the hello is (ADR-0116): set at each
    /// `welcome` and by `welcome.done`, cleared at a disconnect.
    welcome_done: watch::Sender<HelloAnswer>,
    /// Whether the current connection's server takes `host.health` (ADR-0117).
    pub server_takes_health: crate::health::ServerTakesHealth,
    heartbeat_secs: AtomicU64,
    lease_ttl_secs: AtomicU64,
    runner_host_id: Mutex<Option<Uuid>>,
    /// `state.json`, written off the connection's task.
    state_file: Arc<HostStateFile>,
    /// Runs the server called stale (`error STALE_EPOCH`); the `run.cancel` that follows
    /// such an error names the stale epoch.
    stale_hints: Mutex<HashMap<Uuid, std::time::Instant>>,
    shutting_down: AtomicBool,
    /// Sandboxes of runs taken back that hold work the take-back record cannot yet call
    /// collected, left for the next start of the host: kept until this process ends, so that
    /// their sessions are not ended and their bridges wait for the next process.
    left_sandboxes: Mutex<Vec<crate::sandbox::Sandbox>>,
    /// Set by the connection after each welcome; tests read it.
    pub connections: AtomicU64,
}

pub type Host = Arc<Shared>;

/// The runs that the sandbox bridge's shared listener serves ([`Shared::start_bridge`]): the
/// task of each holds one, and when the last goes, the listener stops accepting and leaves the
/// host, so that it listens only while the runs of the version before are taken back. A bridge
/// that dials it later is refused, as by a host that never opened it.
pub(crate) struct SharedListenerRuns {
    shared: Arc<Shared>,
    listener: BridgeListener,
}

impl Drop for SharedListenerRuns {
    fn drop(&mut self) {
        self.listener.server.shutdown();
        tracing::info!(
            addr = %self.listener.server.local_addr(),
            "the sandbox bridge's shared listener closes: the runs it served have ended"
        );
        match self.shared.bridge.try_lock() {
            Ok(mut slot) => *slot = None,
            // Read at this moment (`started_bridge`): it goes right after.
            Err(_) => {
                if let Ok(runtime) = tokio::runtime::Handle::try_current() {
                    let shared = self.shared.clone();
                    runtime.spawn(async move {
                        *shared.bridge.lock().await = None;
                    });
                }
            }
        }
    }
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl Shared {
    /// A host that starts now: the ceilings are read here, once (docs/archive/v1/CONVENTIONS.md §15.7).
    pub fn new(opts: HostOptions, config: HostConfig) -> Host {
        let (state_file, state) = HostStateFile::load(&opts.paths);
        let credentials = CredentialStore::new(&opts.paths);
        let startup_credentials = credentials.list().unwrap_or_else(|e| {
            tracing::warn!(error = %format!("{e:#}"), "cannot read the credentials; reporting none");
            Vec::new()
        });
        let mut capacity = Capacity::new(opts.max_concurrent_runs, &startup_credentials);
        if opts.keeps_ready_vms() {
            capacity = capacity.with_pool(opts.ready_vms.max_ready);
        }
        // What the start's check of the VM driver found of machines that do not answer: the
        // host's first `host.config.applied` carries its slots ([`Host::set_vm_room`]).
        if let Executor::Vm(vms) = &opts.executor {
            capacity.set_vm_room(*vms.room_watch().borrow());
        }
        let outbox = Arc::new(Outbox::with_retention(opts.finished_retention));
        // A statement that contradicts what the host sees is an error whatever the nodes'
        // hardening, so the start's derivation tells for every later one.
        let sandboxes = crate::isolation::Sandboxes::of(&opts.executor, &opts.sandbox)
            .and_then(|sandboxes| {
                crate::isolation::derive(sandboxes, opts.isolation_stated).map(|_| sandboxes)
            })
            .map_err(|e| {
                tracing::warn!(error = %e, "this host reports the level of isolation `container`");
            })
            .ok();
        let ready_vms = opts.keeps_ready_vms().then(|| {
            crate::ready_vms::ReadyVms::new(
                opts.ready_vms.clone(),
                crate::pool::Shape::for_runtime(&opts, opts.ready_vms.runtime),
            )
        });
        let pool = match (&opts.executor, &ready_vms) {
            (Executor::Vm(vms), Some(ready_vms)) => Some(ready_vms.pool(vms.clone())),
            (Executor::Vm(vms), None) => Some(crate::pool::Pool::new(vms.clone(), None)),
            _ => None,
        };
        let shared = Arc::new(Shared {
            credentials,
            capacity: Mutex::new(capacity),
            drain_requested: CancellationToken::new(),
            mirrors: Mirrors::new(opts.paths.mirrors_dir()),
            homes: SessionHomes::new(opts.paths.homes_dir()),
            skills: SkillCache::new(opts.paths.skills_dir()),
            records: crate::takeback::Records::new(&opts.paths),
            platform_requests: Mutex::new(HashMap::new()),
            public_suffix: Arc::new(PublicSuffixStore::load(&opts.paths)),
            connected: AtomicBool::new(false),
            welcome_done: watch::Sender::new(HelloAnswer::NotConnected),
            server_takes_health: Default::default(),
            opts,
            config,
            outbox,
            runs: Mutex::new(HashMap::new()),
            cooldowns: Mutex::new(HashMap::new()),
            invalid: Mutex::new(HashSet::new()),
            redactor: RwLock::new(Arc::new(Redactor::default())),
            run_secrets: Mutex::new(HashMap::new()),
            run_masked: Mutex::new(HashMap::new()),
            run_hashed: Mutex::new(HashMap::new()),
            git_proxy: tokio::sync::Mutex::new(None),
            bridge: tokio::sync::Mutex::new(None),
            egress_proxy: tokio::sync::Mutex::new(None),
            model_proxy: tokio::sync::Mutex::new(None),
            pool,
            ready_vms,
            sandboxes,
            hello_mark: Mutex::new(None),
            rehello: watch::Sender::new(0),
            heartbeat_secs: AtomicU64::new(10),
            lease_ttl_secs: AtomicU64::new(30),
            runner_host_id: Mutex::new(state.runner_host_id),
            state_file,
            stale_hints: Mutex::new(HashMap::new()),
            shutting_down: AtomicBool::new(false),
            left_sandboxes: Mutex::new(Vec::new()),
            connections: AtomicU64::new(0),
        });
        shared.refresh_redactor();
        shared
    }

    // ----- redaction -------------------------------------------------------------------

    /// The current redactor: every credential value on this host, the host token, and the
    /// git tokens of active runs; the git proxy secrets of active runs are masked.
    pub fn redactor(&self) -> Arc<Redactor> {
        self.redactor
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    pub fn refresh_redactor(&self) {
        let mut secrets = self.credentials.secret_values();
        secrets.push(self.config.token.expose().to_string());
        for values in lock(&self.run_secrets).values() {
            secrets.extend(values.iter().cloned());
        }
        let masked: Vec<String> = lock(&self.run_masked).values().flatten().cloned().collect();
        let hashed = lock(&self.run_hashed).values().cloned().collect();
        let redactor = Arc::new(Redactor::with_masked(secrets, masked).with_hashed(hashed));
        *self.redactor.write().unwrap_or_else(|e| e.into_inner()) = redactor;
    }

    pub fn add_run_secrets(&self, run_id: Uuid, epoch: i64, values: Vec<String>) {
        lock(&self.run_secrets)
            .entry((run_id, epoch))
            .or_default()
            .extend(values);
        self.refresh_redactor();
    }

    /// Values that are redacted like credentials but are none: finding one in the agent's
    /// output is no leak (the git proxy secret shows up in `git fetch` output).
    pub fn add_run_masked(&self, run_id: Uuid, epoch: i64, values: Vec<String>) {
        lock(&self.run_masked)
            .entry((run_id, epoch))
            .or_default()
            .extend(values);
        self.refresh_redactor();
    }

    /// The redactor of a run's bulk scans (its outgoing commits, its session bundle, its
    /// `/output`): every credential by value, as [`Shared::redactor`] has them, and of the
    /// hashed ones only those of `run_id`'s execution `epoch`. The hashes stand for that
    /// execution's earlier tokens, which no other run's sandbox ever had; and each run's hashes
    /// cost a pass of their own, so a scan with every run's would cost as many passes as there
    /// are runs taken back. The execution is named exactly: a newer epoch of a run may replace
    /// a copy this host still ends, and each scans with its own hashes.
    pub fn run_redactor(&self, run_id: Uuid, epoch: i64) -> Arc<Redactor> {
        let own = lock(&self.run_hashed).get(&(run_id, epoch)).cloned();
        Arc::new(self.redactor().with_only_hashed(own))
    }

    /// The tokens a run taken back held before its restart, known by their salted hashes alone
    /// (crate::secret_hashes): from now on until the run ends they are redacted and scanned for
    /// as its other credentials are.
    pub fn add_run_hashed(
        &self,
        run_id: Uuid,
        epoch: i64,
        hashed: Arc<crate::secret_hashes::HashedMatcher>,
    ) {
        lock(&self.run_hashed).insert((run_id, epoch), hashed);
        self.refresh_redactor();
    }

    fn remove_run_secrets(&self, run_id: Uuid, epoch: i64) {
        lock(&self.run_secrets).remove(&(run_id, epoch));
        lock(&self.run_masked).remove(&(run_id, epoch));
        lock(&self.run_hashed).remove(&(run_id, epoch));
        self.refresh_redactor();
    }

    // ----- git proxy -------------------------------------------------------------------

    /// The listener of the fetch-only git proxy and the platform MCP endpoints, started on
    /// first use. `Ok(None)` when the sandboxes have no network. A failed start is tried
    /// again by the next run.
    pub async fn git_proxy(&self) -> Result<Option<GitProxy>> {
        let mut slot = self.git_proxy.lock().await;
        if let Some(proxy) = slot.as_ref() {
            return Ok(Some(proxy.clone()));
        }
        let Some(endpoint) = self.proxies_endpoint().await? else {
            return Ok(None);
        };
        let proxy = GitProxy::start(endpoint, self.opts.allow_insecure_repo_urls).await?;
        tracing::info!(
            addr = %proxy.local_addr(),
            sandbox_host = %proxy.endpoint().sandbox_host,
            "git proxy listening"
        );
        *slot = Some(proxy.clone());
        Ok(Some(proxy))
    }

    /// The git proxy's shared listener, if it was started; never starts it.
    pub async fn started_git_proxy(&self) -> Option<GitProxy> {
        self.git_proxy.lock().await.clone()
    }

    /// The sandbox bridge's shared listener, if it was started; never starts it.
    pub async fn started_bridge(&self) -> Option<BridgeListener> {
        self.bridge.lock().await.clone()
    }

    /// Where the proxies' listeners bind and how a sandbox names them, without binding any:
    /// with the bridge, this machine's loopback, which a sandbox reaches only through its
    /// tunnels. `Ok(None)` when the sandboxes have no network.
    pub async fn proxies_endpoint(&self) -> Result<Option<git_proxy::Endpoint>> {
        let Some(endpoint) = git_proxy::endpoint(
            &self.opts.executor,
            self.opts.sandbox_network.as_deref(),
            &self.opts.git_proxy,
        )
        .await?
        else {
            return Ok(None);
        };
        // With the bridge, the sandboxes reach the listeners only through their tunnels.
        Ok(Some(if self.opts.bridge.is_some() {
            crate::bridge::listeners_endpoint(self.sandbox_shares_host_network())
        } else {
            endpoint
        }))
    }

    /// Where a sandbox's bridge listener binds, and how the sandbox names it: the sandbox
    /// network's gateway (or what `TINFIRM_RUNNER_GIT_PROXY_BIND` and `_HOST` say). `Ok(None)`
    /// when the sandboxes have no network.
    pub async fn bridge_endpoint(&self) -> Result<Option<git_proxy::Endpoint>> {
        git_proxy::endpoint(
            &self.opts.executor,
            self.opts.sandbox_network.as_deref(),
            &self.opts.git_proxy,
        )
        .await
    }

    /// The address a sandbox's bridge connects from, as this host sees it, where it can tell
    /// one sandbox from another: the local executor's processes connect from loopback, and a
    /// Docker container on the sandbox network from its own address on it (`network`), when
    /// the bridge listens on that network's gateway. `None` where every sandbox comes from one
    /// address (Docker Desktop, the host's network, a listener address of the operator's own):
    /// there the front takes a connection from any address, and `run` decides by the run's
    /// ticket alone.
    pub fn bridge_peer(&self, endpoint: &git_proxy::Endpoint) -> crate::sandbox::BridgePeer {
        use crate::sandbox::BridgePeer;
        match &self.opts.executor {
            Executor::Local { .. } => BridgePeer::Loopback,
            Executor::Docker(_)
                if !endpoint.bind.ip().is_loopback()
                    && self.opts.git_proxy.bind.is_none()
                    && self.opts.sandbox_network.as_deref() != Some("host") =>
            {
                BridgePeer::Container(
                    self.opts
                        .sandbox_network
                        .clone()
                        .unwrap_or_else(|| "bridge".to_string()),
                )
            }
            _ => BridgePeer::Any,
        }
    }

    /// Whether a sandbox shares this machine's network namespace: the local test executor (and
    /// the VM executor's test mode), or Docker's `host` network.
    fn sandbox_shares_host_network(&self) -> bool {
        self.opts.executor.on_this_machine() || self.opts.sandbox_network.as_deref() == Some("host")
    }

    /// The sandbox bridge's shared listener, which serves only the runs of records of the
    /// version before each sandbox's bridge was its front's ([`crate::bridge::BridgeListener`]):
    /// started where the sandboxes reach this host (the sandbox network's gateway), with the
    /// identity and on the port that the process before kept with its records (ADR-0101), which
    /// the bridges of `reclaim`'s runs pin and dial. Their registrations come back in order. Its
    /// identity and port are kept again, for the next process of this host. `Ok(None)` without
    /// `opts.bridge`, or when the sandboxes have no network. The listener closes once the last
    /// of those runs has ended ([`SharedListenerRuns`]).
    async fn start_bridge(
        &self,
        reclaim: Vec<(String, tinfirm_bridge::control::Hidden)>,
    ) -> Result<Option<(BridgeListener, Vec<tinfirm_bridge::host::Registration>)>> {
        let Some(options) = &self.opts.bridge else {
            return Ok(None);
        };
        let mut slot = self.bridge.lock().await;
        anyhow::ensure!(
            slot.is_none(),
            "the sandbox bridge listens already; runs are taken back before it starts"
        );
        let Some(endpoint) = git_proxy::endpoint(
            &self.opts.executor,
            self.opts.sandbox_network.as_deref(),
            &self.opts.git_proxy,
        )
        .await?
        else {
            return Ok(None);
        };
        let (identity, port) = match self.records.listener() {
            Some(kept) => kept,
            None => (tinfirm_bridge::tls::Identity::generate()?, 0),
        };
        let bind = std::net::SocketAddr::new(endpoint.bind.ip(), port);
        let started = tinfirm_bridge::host::BridgeServer::start_with(
            bind,
            options.timing,
            &identity,
            reclaim.clone(),
        )
        .await;
        let (server, registrations) = match started {
            Ok(started) => started,
            Err(e) if port != 0 => {
                tracing::warn!(error = %format!("{e:#}"), port, "the sandbox bridge cannot listen on the port of the process before this one: the sandboxes that dial in cannot come back");
                tinfirm_bridge::host::BridgeServer::start_with(
                    std::net::SocketAddr::new(endpoint.bind.ip(), 0),
                    options.timing,
                    &identity,
                    reclaim,
                )
                .await?
            }
            Err(e) => return Err(e),
        };
        if let Err(e) = self
            .records
            .save_listener(&identity, server.local_addr().port())
        {
            tracing::warn!(error = %format!("{e:#}"), "cannot keep the sandbox bridge's identity: a restart of this host would lose its runs");
        }
        tracing::info!(
            addr = %server.local_addr(),
            sandbox_host = %endpoint.sandbox_host,
            "sandbox bridge listening"
        );
        let listener = BridgeListener { server };
        *slot = Some(listener.clone());
        Ok(Some((listener, registrations)))
    }

    /// At the start, before the first hello: takes back the runs whose records the process
    /// before this one left (ADR-0101). Each is a run of this host's from now on, listed in
    /// `hello.activeRuns`, and its task waits for the sandbox's bridge to come back, then ends
    /// the run ([`run::take_back`]). A record that this host cannot take back is logged and
    /// removed, and so is what a killed process left of a record's write.
    pub async fn take_back_runs(self: &Arc<Self>) {
        self.records.remove_leftovers();
        self.send_kept_reports();
        let mut records = self.records.load_all();
        if records.is_empty() {
            return;
        }
        let mut held = Vec::new();
        for record in std::mem::take(&mut records) {
            let run_id = record.run_id();
            if record.collected {
                // Its work came back from the sandbox, in the run's own end or before an earlier
                // restart: only what is left of the run's end is, from this host's disk
                // (ADR-0116, decision 10). The sandbox was stopping when the process ended: a
                // container that is still there goes (a VM, which names no run, is a leftover
                // that `cleanup_orphans` deletes).
                if let (Executor::Docker(_), crate::takeback::SandboxRecord::Docker { .. }) =
                    (&self.opts.executor, &record.sandbox)
                    && let Ok(sandbox) =
                        crate::sandbox::TakenBack::hold(&self.opts.executor, &record.sandbox)
                {
                    sandbox.discard(Duration::from_secs(5)).await;
                }
                if self.opts.paths.workspace(run_id).is_dir() {
                    tracing::info!(%run_id, epoch = record.assign.epoch, "taking back a run whose work this host holds; it waits for the server before it pushes");
                    self.spawn_taken_back(record, None, None);
                } else {
                    tracing::warn!(%run_id, "a run of the process before this one cannot be taken back: its work is no longer on this host");
                    self.records.remove(run_id);
                }
                continue;
            }
            let sandbox = if self.opts.bridge.is_none() {
                Err(anyhow::anyhow!(
                    "this host runs no agent over the sandbox bridge now"
                ))
            } else {
                crate::sandbox::TakenBack::hold(&self.opts.executor, &record.sandbox)
            };
            match sandbox {
                Ok(sandbox) => held.push((record, sandbox)),
                Err(e) => {
                    tracing::warn!(%run_id, error = %format!("{e:#}"), "a run of the process before this one cannot be taken back");
                    self.records.remove(run_id);
                }
            }
        }
        // A run whose sandbox's front held its bridge comes back through a new front, with the
        // identity, the port and the peer that its record keeps.
        let (fronted, held): (Vec<_>, Vec<_>) = held
            .into_iter()
            .partition(|(record, _)| record.bridge.is_some());
        for (record, sandbox) in fronted {
            let run_id = record.run_id();
            match self.register_front_again(&record).await {
                Ok(registration) => {
                    tracing::info!(%run_id, epoch = record.assign.epoch, "taking back a run of the process before this one, through a new front");
                    self.spawn_taken_back(record, Some((sandbox, registration)), None);
                }
                Err(e) => {
                    tracing::warn!(%run_id, error = %format!("{e:#}"), "a run of the process before this one cannot be taken back");
                    self.records.remove(run_id);
                    sandbox.discard(Duration::from_secs(5)).await;
                }
            }
        }
        if held.is_empty() {
            return;
        }
        let reclaim = held
            .iter()
            .map(|(record, _)| (record.bridge_id.clone(), record.session_key.clone()))
            .collect();
        let (registrations, listener) = match self.start_bridge(reclaim).await {
            Ok(Some((listener, registrations))) => (registrations, Some(listener)),
            Ok(None) => {
                tracing::warn!(
                    "the sandboxes have no network, so the runs of the process before this one cannot be taken back"
                );
                (Vec::new(), None)
            }
            Err(e) => {
                tracing::warn!(error = %format!("{e:#}"), "the runs of the process before this one cannot be taken back");
                (Vec::new(), None)
            }
        };
        // Each run's task holds the listener open; when the last has ended, or when none is
        // taken back, it closes.
        let holds = listener.map(|listener| {
            Arc::new(SharedListenerRuns {
                shared: self.clone(),
                listener,
            })
        });
        if registrations.len() != held.len() {
            for (record, sandbox) in held {
                self.records.remove(record.run_id());
                sandbox.discard(Duration::from_secs(5)).await;
            }
            return;
        }
        for ((record, sandbox), registration) in held.into_iter().zip(registrations) {
            if let crate::takeback::SandboxRecord::Vm { address, port, .. } = &record.sandbox
                && let Executor::Vm(vms) = &self.opts.executor
            {
                // The VM's bridge listens: this host dials it, as it did before, at the port the
                // driver named, or at `--vm-port` for a record without one.
                registration.dial(git_proxy::host_port(
                    address,
                    port.unwrap_or(vms.options.port),
                ));
            }
            tracing::info!(run_id = %record.run_id(), epoch = record.assign.epoch, "taking back a run of the process before this one");
            self.spawn_taken_back(
                record,
                Some((sandbox, crate::sandbox::Registered::Shared(registration))),
                holds.clone(),
            );
        }
    }

    /// At the start, before the first hello: the reports that the process before this one made
    /// and kept ([`crate::takeback::ReportRecord`]) go into the outbox, as a finished run's end
    /// that may not have reached the server: the run is listed in `hello.activeRuns`, and its
    /// `run.finished` is written after the hello, until the outbox forgets it
    /// ([`Shared::forget_delivered_reports`]).
    ///
    /// A record of the same run decides by its epoch. A record of a newer execution is that
    /// execution's: it is taken back, and the older report goes, unsent. A record of the same
    /// or an older execution is a leftover of the moment between the report's write and the
    /// record's removal: it goes, and so does what is left of the run's directories. Of two
    /// reports of one run, only the newest execution's is sent.
    fn send_kept_reports(self: &Arc<Self>) {
        let records: HashMap<Uuid, i64> = self
            .records
            .load_all()
            .iter()
            .map(|record| (record.run_id(), record.assign.epoch))
            .collect();
        let reports = self.records.load_reports();
        for (i, finished) in reports.iter().enumerate() {
            let (run_id, epoch) = (finished.run_id, finished.epoch);
            let newer_record = records.get(&run_id).is_some_and(|kept| *kept > epoch);
            let newer_report = reports[i + 1..]
                .iter()
                .any(|other| other.run_id == run_id && other.epoch > epoch);
            if newer_record || newer_report {
                tracing::info!(%run_id, epoch, "a kept report of an execution that a newer one of the run replaced; it is not sent");
                self.records.remove_report(run_id, epoch);
                continue;
            }
            tracing::info!(%run_id, epoch, outcome = ?finished.outcome, "sending again the report of a run of the process before this one");
            if records.contains_key(&run_id) {
                self.records.remove(run_id);
            }
            let _ = std::fs::remove_dir_all(self.opts.paths.attachments(run_id));
            let failed = finished.outcome == tinfirm_protocol::Outcome::Failed
                || finished
                    .git
                    .iter()
                    .flatten()
                    .any(|g| !g.pushed && g.commits.unwrap_or(0) > 0);
            run::cleanup_kept_workspace(self, run_id, failed);
            self.outbox.resume_run(run_id, epoch, finished.last_seq);
            self.outbox.push_finished(finished.clone());
        }
    }

    /// The kept reports of the executions that the outbox has forgotten go: their end can no
    /// longer be lost, or no longer matters.
    pub fn forget_delivered_reports(&self) {
        for (run_id, epoch) in self.outbox.take_forgotten() {
            self.records.remove_report(run_id, epoch);
        }
    }

    /// On an orderly end of the process, once its connection has ended: a run's end that the
    /// outbox forgot, or that was written on a connection the server then closed in order
    /// ([`crate::outbox::Outbox::closed_in_order`]), is delivered, so its kept report goes. Any
    /// other stays for the next start, a report written on a connection that broke included:
    /// the server, or a proxy before it, may have gone down with it unread.
    pub fn forget_delivered_reports_at_the_end(&self) {
        self.forget_delivered_reports();
        for (run_id, epoch) in self.outbox.take_delivered_at_the_close() {
            self.records.remove_report(run_id, epoch);
        }
    }

    /// What the record of a run taken back keeps of its bridge in a front, if any: its identity,
    /// and its listener, or, for a VM's bridge, which listens, where this host dials it: where
    /// the record's sandbox says, at the port the driver named, or at `--vm-port` for a record
    /// without one.
    pub(crate) fn kept_bridge(
        &self,
        record: &crate::takeback::RunRecord,
    ) -> Option<Result<crate::sandbox::BridgeKeep>> {
        let dial = match (&record.sandbox, &self.opts.executor) {
            (crate::takeback::SandboxRecord::Vm { address, port, .. }, Executor::Vm(vms)) => Some(
                git_proxy::host_port(address, port.unwrap_or(vms.options.port)),
            ),
            _ => None,
        };
        record.bridge.as_ref().map(|bridge| bridge.keep(dial))
    }

    /// A new front for the bridge of a run taken back whose record keeps its identity: on the
    /// kept port (when it is taken now, on another, which the bridge does not find, as with the
    /// shared listener's port), or dialing the VM's bridge.
    async fn register_front_again(
        &self,
        record: &crate::takeback::RunRecord,
    ) -> Result<crate::sandbox::Registered> {
        let options = self
            .opts
            .bridge
            .as_ref()
            .context("this host runs no agent over the sandbox bridge now")?;
        let keep = self
            .kept_bridge(record)
            .context("the record keeps no bridge of a front")??;
        keep.register_again(
            options,
            &record.bridge_id,
            record.session_key.clone(),
            Duration::from_secs(2),
        )
        .await
    }

    /// Registers a run taken back as a run of this host's, listed in `hello.activeRuns`, and
    /// starts its task ([`run::take_back`]).
    fn spawn_taken_back(
        self: &Arc<Self>,
        record: crate::takeback::RunRecord,
        sandbox: Option<(crate::sandbox::TakenBack, crate::sandbox::Registered)>,
        holds: Option<Arc<SharedListenerRuns>>,
    ) {
        let (run_id, epoch) = (record.run_id(), record.assign.epoch);
        let (stop_tx, stop_rx) = watch::channel(None);
        let steering = Steering::new(run_id, epoch, self.outbox.clone());
        // No token that the server revoked comes back (ADR-0116).
        let git_tokens =
            RunGitTokens::restored(&record.assign, &record.revoked, record.redaction.as_ref());
        // The record on disk is the holder's before the run can get a revocation: one that
        // comes from now on writes it again before the host confirms it.
        let kept = Arc::new(crate::takeback::RecordHandle::kept(
            &self.records,
            record.clone(),
        ));
        git_tokens.keep_record(kept.clone());
        lock(&self.runs).insert(
            run_id,
            RunEntry {
                epoch,
                task_id: record.assign.work_item().id,
                credential_label: record.assign.credential_label.clone(),
                stop: stop_tx,
                steering: steering.clone(),
                git_tokens: git_tokens.clone(),
                unqueued_revoked: Vec::new(),
                handle: None,
            },
        );
        self.outbox.resume_run(run_id, epoch, record.seq_floor);
        let shared = self.clone();
        let handle = tokio::spawn(async move {
            let end = run::take_back(&shared, record, kept, &git_tokens, sandbox, stop_rx).await;
            git_tokens.close_log();
            steering.close("the run ended");
            match end {
                run::TakenBackEnd::Finished(finished) => {
                    tracing::info!(%run_id, outcome = ?finished.outcome, class = ?finished.failure_class, "run taken back and finished");
                    shared.outbox.push_finished(*finished);
                }
                run::TakenBackEnd::Aborted => {
                    tracing::info!(%run_id, epoch, "run taken back and aborted (stale epoch)");
                    shared.outbox.drop_run(run_id, epoch);
                }
                run::TakenBackEnd::Left(sandbox) => {
                    tracing::info!(%run_id, epoch, "run taken back and left for the next start of the host");
                    // A sandbox that still holds the work lives as long as this process: its
                    // bridge then waits for the next one, which takes the session back.
                    if let Some(sandbox) = sandbox {
                        lock(&shared.left_sandboxes).push(*sandbox);
                    }
                }
            }
            {
                let mut runs = lock(&shared.runs);
                if runs.get(&run_id).is_some_and(|e| e.epoch == epoch) {
                    runs.remove(&run_id);
                }
            }
            shared.drop_platform_requests(run_id, epoch);
            shared.remove_run_secrets(run_id, epoch);
            // The run no longer needs the shared listener.
            drop(holds);
        });
        if let Some(entry) = lock(&self.runs).get_mut(&run_id)
            && entry.epoch == epoch
        {
            entry.handle = Some(handle);
        }
    }

    /// The egress proxy, started on first use; it listens where the git proxy does, on the
    /// sandbox network's gateway. `Ok(None)` without `opts.egress`, or when the sandboxes
    /// have no network.
    pub async fn egress_proxy(&self) -> Result<Option<Arc<EgressProxy>>> {
        let Some(settings) = &self.opts.egress else {
            return Ok(None);
        };
        let mut slot = self.egress_proxy.lock().await;
        if let Some(proxy) = slot.as_ref() {
            return Ok(Some(proxy.clone()));
        }
        let Some(endpoint) = self.proxies_endpoint().await? else {
            return Ok(None);
        };
        let proxy = Arc::new(EgressProxy::new(settings.clone(), &endpoint));
        *slot = Some(proxy.clone());
        Ok(Some(proxy))
    }

    /// A new sandbox's front process, which holds that sandbox's bridge and serves the HTTP of
    /// its proxies (ADR-0112, path C, part 2): with the bridge only; `Ok(None)` without it,
    /// where this process serves them. One per sandbox: a VM's when the VM is created
    /// (crate::pool), any other's where its run decides what serves its proxies (crate::run).
    pub async fn sandbox_front(&self) -> Result<Option<Arc<crate::front::sandbox::SandboxFront>>> {
        match &self.opts.bridge {
            Some(bridge) => Ok(Some(
                crate::front::sandbox::SandboxFront::start(&bridge.front).await?,
            )),
            None => Ok(None),
        }
    }

    /// The model proxy, which is always on, created on first use; its runs' listeners bind where
    /// the git proxy listens, on the sandbox network's gateway. When the sandboxes have no network
    /// there is nowhere to listen for them, and when the git proxy cannot start there is no
    /// address: an error, so that the run fails rather than get the key (`run` refuses the first
    /// at start, [`crate::model_proxy::check_endpoint`]). It sends the host's own token to the
    /// model relay of this host's server, and to no other gateway ([`HostToken`]).
    pub async fn model_proxy(&self) -> Result<Arc<ModelProxy>> {
        let mut slot = self.model_proxy.lock().await;
        if let Some(proxy) = slot.as_ref() {
            return Ok(proxy.clone());
        }
        let Some(endpoint) = self.proxies_endpoint().await? else {
            anyhow::bail!("the sandboxes have no network, so they cannot reach the model proxy");
        };
        let host_token = HostToken::new(&self.config.server_url, self.config.token.expose())
            .context("the model proxy cannot send this host's token to its server")?;
        let proxy = Arc::new(
            ModelProxy::new(self.opts.model_proxy.clone(), &endpoint).with_host_token(host_token),
        );
        *slot = Some(proxy.clone());
        Ok(proxy)
    }

    async fn stop_git_proxy(&self) {
        if let Some(proxy) = self.git_proxy.lock().await.take() {
            proxy.shutdown();
        }
    }

    // ----- identity and connection state ------------------------------------------------

    /// The runner protocol version this host speaks (`config.json`, or `run
    /// --protocol-version`): what `hello` says, which frames it sends and which it accepts.
    pub fn protocol_version(&self) -> u32 {
        self.config.protocol_version
    }

    /// `true` when the host speaks protocol v2 (the generic request pair, server-executed
    /// tools, `/output`).
    pub fn speaks_v2(&self) -> bool {
        self.config.protocol_version >= tinfirm_protocol::PROTOCOL_VERSION_V2
    }

    /// Whether this host takes `run.assign.skills` (the feature `run.skills`, ADR-0064).
    pub fn takes_skills(&self) -> bool {
        features(
            self.speaks_v2(),
            &self.opts.executor,
            self.opts.bridge.is_some(),
        )
        .iter()
        .any(|f| f == feature::RUN_SKILLS)
    }

    pub fn runner_host_id(&self) -> Option<Uuid> {
        *lock(&self.runner_host_id)
    }

    /// Label value for our containers.
    pub fn host_label(&self) -> String {
        self.runner_host_id()
            .map(|id| id.to_string())
            .unwrap_or_else(|| "unregistered".to_string())
    }

    pub fn on_welcome(&self, welcome: &tinfirm_protocol::Welcome) {
        self.server_takes_health.on_welcome(welcome);
        self.welcome_done.send_replace(HelloAnswer::Welcomed {
            revokes: welcome.lists(feature::RUN_GIT_REVOKE),
        });
        self.connected.store(true, Ordering::SeqCst);
        // A new connection: forget the host.config version, and drop answers that the old
        // connection did not write. The server sends host.config after welcome.
        lock(&self.capacity).new_connection();
        self.outbox.retain_control(|m| {
            !matches!(m, Message::HostConfigApplied(_)) && !crate::health::of_ended_connection(m)
        });
        // Platform requests that still wait may have been lost with the old connection; the
        // server answers a repeated requestId again without storing it twice.
        for pending in lock(&self.platform_requests).values() {
            self.outbox.push_control(pending.frame.clone());
            pending.resent.notify_one();
        }
        *lock(&self.runner_host_id) = Some(welcome.runner_host_id);
        self.heartbeat_secs
            .store(welcome.heartbeat_interval_seconds.max(1), Ordering::Relaxed);
        self.lease_ttl_secs
            .store(welcome.lease_ttl_seconds.max(1), Ordering::Relaxed);
        // Written off this task: a slow disk must not hold back the heartbeats.
        self.state_file.want(HostState {
            runner_host_id: Some(welcome.runner_host_id),
            company_id: Some(welcome.company_id),
        });
        self.connections.fetch_add(1, Ordering::Relaxed);
    }

    /// How often `run.heartbeat` goes out: `welcome.heartbeatIntervalSeconds`, but at least
    /// three times per lease (`welcome.leaseTtlSeconds`), so that one late beat never lets a
    /// lease expire; never more often than once a second.
    pub fn heartbeat_interval(&self) -> Duration {
        let interval = Duration::from_secs(self.heartbeat_secs.load(Ordering::Relaxed).max(1));
        let lease = Duration::from_secs(self.lease_ttl_secs.load(Ordering::Relaxed).max(1));
        interval.min(lease / 3).max(Duration::from_secs(1))
    }

    /// `true` while a connection is established (after `welcome`).
    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::SeqCst)
    }

    /// Where the current connection's answer to the hello is, as it changes.
    pub fn hello_answer(&self) -> watch::Receiver<HelloAnswer> {
        self.welcome_done.subscribe()
    }

    /// The level of isolation the host reports now (crate::isolation, ADR-0113): what it read
    /// at its start, with the node driver's hardening as the VM executor knows it now
    /// ([`crate::vm::Vms::hardening`]).
    pub fn isolation(&self) -> crate::isolation::Reported {
        let Some(sandboxes) = self.sandboxes else {
            return crate::isolation::Reported::CONTAINER;
        };
        let sandboxes = match &self.opts.executor {
            Executor::Vm(vms) => sandboxes.hardened(vms.hardening().hardened),
            _ => sandboxes,
        };
        crate::isolation::derive(sandboxes, self.opts.isolation_stated)
            .unwrap_or(crate::isolation::Reported::CONTAINER)
    }

    /// The host's asks for a new hello, as they come: the connection ends with a close of
    /// its own and connects again at once ([`tell_hardening`]).
    pub fn rehello_watch(&self) -> watch::Receiver<u64> {
        self.rehello.subscribe()
    }

    /// How often the host asked for a new hello (tests).
    pub fn rehellos(&self) -> u64 {
        *self.rehello.borrow()
    }

    /// The connection ended.
    pub fn on_disconnect(&self) {
        self.connected.store(false, Ordering::SeqCst);
        self.welcome_done.send_replace(HelloAnswer::NotConnected);
        lock(&self.capacity).new_connection();
        self.outbox.on_disconnect();
    }

    // ----- platform requests (platform MCP tools) ---------------------------------------

    /// The host side of the platform tools for runs of this host: v1's `request_repository`,
    /// `create_task`, `upload_attachment` and `ask_human`, and v2's `ask`, `request_access`,
    /// `save_artifact` and the server-executed tools.
    pub fn platform_requester(self: &Arc<Self>) -> Arc<dyn PlatformRequester> {
        Arc::new(HostRequester {
            shared: Arc::downgrade(self),
        })
    }

    /// Hands an ack (`run.repository_request.ack`, `run.task_create.ack`, `run.ask.ack` or
    /// `run.request.ack`) to the tool call that waits for it, if one still does: exactly one
    /// ack answers a request, and only one of the request's kind.
    fn handle_platform_ack(&self, run_id: Uuid, request_id: Uuid, ack: Message) {
        let pending = {
            let mut requests = lock(&self.platform_requests);
            let answers = |p: &PendingRequest| {
                matches!(
                    (&p.frame, &ack),
                    (
                        Message::RunRepositoryRequest(_),
                        Message::RunRepositoryRequestAck(_)
                    ) | (Message::RunTaskCreate(_), Message::RunTaskCreateAck(_))
                        | (Message::RunAsk(_), Message::RunAskAck(_))
                        | (Message::RunRequest(_), Message::RunRequestAck(_))
                )
            };
            match requests.get(&request_id) {
                Some(p) if p.run_id == run_id && answers(p) => requests.remove(&request_id),
                _ => None,
            }
        };
        match pending {
            Some(pending) => {
                tracing::info!(%run_id, %request_id, ack = ack.message_type(), "platform request answered");
                let _ = pending.answer.send(ack);
            }
            None => {
                tracing::debug!(%run_id, %request_id, ack = ack.message_type(), "ack for a platform request that no longer waits");
            }
        }
    }

    /// The run ended: its waiting platform requests end too.
    fn drop_platform_requests(&self, run_id: Uuid, epoch: i64) {
        lock(&self.platform_requests).retain(|_, p| !(p.run_id == run_id && p.epoch == epoch));
    }

    /// Platform requests that wait for an ack (tests).
    pub fn waiting_platform_requests(&self) -> usize {
        lock(&self.platform_requests).len()
    }

    pub fn is_shutting_down(&self) -> bool {
        self.shutting_down.load(Ordering::Relaxed)
    }

    pub fn docker(&self) -> Option<&bollard::Docker> {
        match &self.opts.executor {
            Executor::Docker(d) => Some(d),
            Executor::Vm(_) | Executor::Local { .. } => None,
        }
    }

    /// The `hello` frame for a (re)connect: the ceilings and credentials as `run` read them
    /// when it started.
    pub async fn hello(&self) -> Hello {
        let (max_concurrent_runs, mut credentials) = {
            let capacity = lock(&self.capacity);
            (capacity.host_ceiling(), capacity.credentials().to_vec())
        };
        // A v1 server refuses a hello that names PI or OPENCODE (ADR-0041): v1 leaves them out,
        // also from the runtimes of the credentials.
        let v2 = self.speaks_v2();
        if !v2 {
            for credential in &mut credentials {
                credential.runtimes.retain(|runtime| runtime.in_v1());
            }
        }
        let mut runtimes = Vec::new();
        // A slow Docker must not hold a reconnect back while the leases of the runs expire:
        // an image it does not report in time is left out, like one it cannot inspect.
        let deadline = tokio::time::Instant::now() + HELLO_IMAGE_CHECK;
        for runtime in Runtime::ALL.into_iter().filter(|r| v2 || r.in_v1()) {
            let image = self.opts.image_for(runtime);
            match self.docker() {
                Some(d) => {
                    let inspected =
                        tokio::time::timeout_at(deadline, images::inspect(d, &image)).await;
                    if let Ok(Ok(Some(status))) = inspected {
                        runtimes.push(RuntimeInfo {
                            runtime,
                            image,
                            version: status.version,
                        });
                    }
                }
                // A VM's image holds the runner images; the driver's template names it.
                None => runtimes.push(RuntimeInfo {
                    runtime,
                    image,
                    version: Some(match &self.opts.executor {
                        Executor::Vm(vms) => format!("VM template {}", vms.options.template),
                        _ => "local test executor".into(),
                    }),
                }),
            }
        }
        // The level of isolation (ADR-0113), derived now: with the node driver, with the
        // hardening its nodes last reported. With protocol 2, the one value of
        // `isolationTiers`, with `hardened` and `isolationStated`. Protocol 1 reports
        // `container`, as before, and neither field. No host of this release sends `microvm`,
        // which a server of this release refuses; a server of an earlier release refuses
        // `machine` and `vm-jail` (ADR-0113, decision 2).
        let (isolation_tiers, hardened, isolation_stated) = if v2 {
            let isolation = self.isolation();
            (vec![isolation.tier], isolation.hardened, isolation.stated)
        } else {
            (vec![IsolationTier::Container], None, false)
        };
        // What the server learns of the nodes' hardening from this hello: [`tell_hardening`]
        // compares it with what the nodes report later.
        *lock(&self.hello_mark) = (v2
            && matches!(self.sandboxes, Some(crate::isolation::Sandboxes::Nodes(..))))
        .then(|| {
            if hardened == Some(tinfirm_protocol::Hardened::Yes) {
                crate::vm::Mark::Yes
            } else {
                crate::vm::Mark::No
            }
        });
        Hello {
            protocol_version: self.protocol_version(),
            runner_version: self.opts.runner_version.clone(),
            hostname: hostname::get()
                .ok()
                .map(|h| h.to_string_lossy().into_owned()),
            os: Some(std::env::consts::OS.to_string()),
            arch: Some(std::env::consts::ARCH.to_string()),
            executors: vec![ExecutorKind::Docker],
            isolation_tiers,
            max_concurrent_runs,
            credentials,
            runtimes,
            active_runs: Some(self.outbox.active_runs()),
            public_suffix_list: if v2 {
                self.public_suffix
                    .sha256()
                    .map(|sha256| PublicSuffixListVersion { sha256 })
            } else {
                None
            },
            features: {
                let mut features = features(v2, &self.opts.executor, self.opts.bridge.is_some());
                if v2 && self.ready_vms.is_some() {
                    features.push(feature::SANDBOX_POOL.to_string());
                }
                features
            },
            pool: (v2 && self.ready_vms.is_some()).then_some(tinfirm_protocol::HelloPool {
                max_ready: self.opts.ready_vms.max_ready,
            }),
            hardened,
            isolation_stated,
        }
    }

    /// `run.heartbeat` payload: runs that are still executing.
    pub fn heartbeat_runs(&self) -> Vec<RunRef> {
        lock(&self.runs)
            .iter()
            .map(|(id, e)| RunRef {
                run_id: *id,
                epoch: e.epoch,
            })
            .collect()
    }

    /// Whether no newer execution of the run has taken its entry on this host.
    pub(crate) fn no_newer_execution(&self, run_id: Uuid, epoch: i64) -> bool {
        lock(&self.runs)
            .get(&run_id)
            .is_none_or(|entry| entry.epoch <= epoch)
    }

    pub fn live_run_ids(&self) -> HashSet<Uuid> {
        lock(&self.runs).keys().copied().collect()
    }

    pub fn active_run_count(&self) -> usize {
        lock(&self.runs).len()
    }

    // ----- the VM pool (crate::pool) and its ready VMs (crate::ready_vms) ------------------

    /// The VM executor's pool; `None` for the other executors.
    pub fn pool(&self) -> Option<&Arc<crate::pool::Pool>> {
        self.pool.as_ref()
    }

    /// The pool's ready VMs; `None` without `--pool-max-ready` above 0, or for the other
    /// executors.
    pub fn ready_vms(&self) -> Option<&Arc<crate::ready_vms::ReadyVms>> {
        self.ready_vms.as_ref()
    }

    /// `host.pool.status` (v2, the feature `sandbox.pool`, ADR-0099), on the current
    /// connection.
    pub fn send_pool_status(&self, status: tinfirm_protocol::HostPoolStatus) {
        if self.speaks_v2() && self.is_connected() {
            self.outbox.push_control(Message::HostPoolStatus(status));
        }
    }

    /// What the ready VMs read of this host in a pass: its effective `maxConcurrentRuns` (0
    /// while it drains), its active runs, and its connection.
    pub fn ready_vms_view(&self) -> crate::ready_vms::HostView {
        let (pool_size, draining) = {
            let capacity = lock(&self.capacity);
            (capacity.max_concurrent_runs(), capacity.is_draining())
        };
        crate::ready_vms::HostView {
            pool_size,
            active_runs: self.active_run_count(),
            draining: draining || self.is_shutting_down(),
            connected: self.is_connected(),
            registered: self.runner_host_id().is_some(),
        }
    }

    // ----- credential state ------------------------------------------------------------

    pub fn set_cooldown(&self, label: &str, until: DateTime<Utc>, detail: String) {
        lock(&self.cooldowns).insert(label.to_string(), until);
        self.outbox
            .push_control(Message::CredentialStatus(CredentialStatus {
                label: label.to_string(),
                status: CredentialState::Cooldown,
                cooldown_until: Some(until),
                detail: Some(detail),
            }));
    }

    pub fn set_invalid(&self, label: &str, detail: String) {
        lock(&self.invalid).insert(label.to_string());
        self.outbox
            .push_control(Message::CredentialStatus(CredentialStatus {
                label: label.to_string(),
                status: CredentialState::Invalid,
                cooldown_until: None,
                detail: Some(detail),
            }));
    }

    /// Reports credentials whose cooldown has passed as AVAILABLE again.
    pub fn expire_cooldowns(&self) {
        let now = Utc::now();
        let expired: Vec<String> = {
            let mut cooldowns = lock(&self.cooldowns);
            let expired: Vec<String> = cooldowns
                .iter()
                .filter(|(_, until)| **until <= now)
                .map(|(label, _)| label.clone())
                .collect();
            for label in &expired {
                cooldowns.remove(label);
            }
            expired
        };
        for label in expired {
            self.outbox
                .push_control(Message::CredentialStatus(CredentialStatus {
                    label,
                    status: CredentialState::Available,
                    cooldown_until: None,
                    detail: Some("cooldown ended".into()),
                }));
        }
    }

    // ----- server messages -------------------------------------------------------------

    /// Handles one message from the server. It never waits: the connection calls it between
    /// two frames, and heartbeats must keep going meanwhile (a lease is lost after
    /// `leaseTtlSeconds` without one). What waits for Docker or for another task runs in a
    /// task of its own.
    pub fn handle_message(self: &Arc<Self>, message: Message) {
        match message {
            Message::RunAssign(assign) => self.handle_assign(assign),
            Message::RunCancel(cancel) => self.handle_cancel(cancel),
            Message::RunSteer(steer) => self.handle_steer(steer),
            Message::RunGitRevoke(revoke) => self.handle_git_revoke(revoke),
            Message::RunRepositoryRequestAck(ack) => self.handle_platform_ack(
                ack.run_id,
                ack.request_id,
                Message::RunRepositoryRequestAck(ack),
            ),
            Message::RunTaskCreateAck(ack) => {
                self.handle_platform_ack(ack.run_id, ack.request_id, Message::RunTaskCreateAck(ack))
            }
            Message::RunAskAck(ack) => {
                self.handle_platform_ack(ack.run_id, ack.request_id, Message::RunAskAck(ack))
            }
            Message::RunRequestAck(ack) => {
                self.handle_platform_ack(ack.run_id, ack.request_id, Message::RunRequestAck(ack))
            }
            Message::HostConfig(config) => self.handle_host_config(config),
            Message::PslUpdate(update) => self.handle_psl_update(update),
            Message::Error(error) => self.handle_error(error),
            Message::Welcome(_) => {}
            Message::WelcomeDone(_) => {
                // After the frames it promises: a revocation it names was applied already.
                tracing::info!("the server's answer to the hello is complete (welcome.done)");
                self.welcome_done.send_replace(HelloAnswer::Done);
            }
            other => self.outbox.push_control(Message::Error(ErrorMessage {
                code: "BAD_MESSAGE".into(),
                message: format!("unexpected message type `{}`", other.message_type()),
                run_id: None,
                epoch: None,
            })),
        }
    }

    // ----- the Public Suffix List ----------------------------------------------------------

    /// The Public Suffix List store: what `hello` reports, and what the checks of network
    /// wildcards read.
    pub fn public_suffix(&self) -> &Arc<PublicSuffixStore> {
        &self.public_suffix
    }

    /// The base of `rule` when it is a wildcard that would reach every site of a shared
    /// service (`*.github.io`, `*.com.sg`), and the host holds a Public Suffix List; `None` for
    /// any other rule, or without a list. The three places that check wildcards share it.
    pub fn public_suffix_wildcard_base<'a>(&self, rule: &'a Rule) -> Option<&'a str> {
        self.public_suffix.broad_wildcard_base(rule)
    }

    /// At `run` start: the operator's `--egress-allow` wildcards over a public suffix stay
    /// (the operator chose them), with a warning.
    pub fn warn_operator_public_suffix_wildcards(&self) {
        let Some(egress) = &self.opts.egress else {
            return;
        };
        for rule in &egress.allow {
            if let Some(base) = self.public_suffix_wildcard_base(rule) {
                tracing::warn!(rule = %rule, "an allowed egress wildcard covers a public suffix: `*.{base}` reaches every site on a shared service; name the exact hosts instead");
            }
        }
    }

    /// `psl.update` (v2): verifies, uses and stores the list; no answer. An update that is not
    /// valid is logged and the list the host holds stays. Decompression and the write to disk
    /// run off the connection's task.
    fn handle_psl_update(self: &Arc<Self>, update: PslUpdate) {
        let store = self.public_suffix.clone();
        let apply = move || {
            if let Err(why) = store.apply_update(&update) {
                tracing::warn!(%why, sha256 = %update.sha256.escape_debug(), "ignoring an invalid psl.update; keeping the Public Suffix List the host holds");
            }
        };
        match tokio::runtime::Handle::try_current() {
            Ok(runtime) => {
                runtime.spawn_blocking(apply);
            }
            Err(_) => apply(),
        }
    }

    // ----- capacity and drain (docs/archive/v1/CONVENTIONS.md §15.7, §15.4 step 13) --------------------------

    /// `host.config`: applies the desired values at once and answers with
    /// `host.config.applied`. Fewer slots never stop a run; they only keep new ones out.
    fn handle_host_config(&self, config: tinfirm_protocol::HostConfig) {
        let mut capacity = lock(&self.capacity);
        let unknown: Vec<String> = config
            .credentials
            .iter()
            .filter(|c| capacity.credential(&c.label).is_none())
            .map(|c| c.label.clone())
            .collect();
        match capacity.apply(&config) {
            ApplyOutcome::Applied(applied) => {
                tracing::info!(
                    version = applied.version,
                    max_concurrent_runs = applied.max_concurrent_runs,
                    ceiling = applied.max_concurrent_runs_ceiling,
                    draining = applied.draining,
                    "applied host.config"
                );
                if !unknown.is_empty() {
                    tracing::info!(labels = ?unknown, "host.config names credentials this host did not start with; ignored");
                }
                // Pushed under the lock, so an unprompted answer of a drain that starts
                // meanwhile cannot be overtaken by this older state.
                self.outbox
                    .push_control(Message::HostConfigApplied(applied));
                if let Some(ready_vms) = &self.ready_vms {
                    ready_vms.apply_config(config.pool.as_ref());
                }
            }
            ApplyOutcome::Outdated { last } => {
                tracing::info!(
                    version = config.version,
                    last,
                    "ignoring a host.config older than the one applied"
                );
            }
            ApplyOutcome::Invalid(message) => {
                tracing::warn!(%message, "ignoring an invalid host.config");
                self.outbox.push_control(Message::Error(ErrorMessage {
                    code: "BAD_MESSAGE".into(),
                    message,
                    run_id: None,
                    epoch: None,
                }));
            }
        }
    }

    /// With a VM per run: the room of the driver's machines that answer, while others do not
    /// ([`crate::vm::Vms::room_watch`]); `None` once every machine answers. The host has at
    /// most that many slots meanwhile, so that a run beyond them waits in the server's queue
    /// instead of failing when its VM is created. Tells the server with `host.config.applied`
    /// when its effective slots change, as a drain does, once a `host.config` was applied on
    /// the current connection; otherwise the answer to the first one carries them.
    pub fn set_vm_room(&self, room: Option<u32>) {
        let mut capacity = lock(&self.capacity);
        if !capacity.set_vm_room(room) {
            return;
        }
        tracing::info!(
            max_concurrent_runs = capacity.max_concurrent_runs(),
            ceiling = capacity.host_ceiling(),
            vm_room = ?room,
            "the runs this host takes at once changed with the room of its VM driver's machines that answer"
        );
        if let Some(applied) = capacity.applied() {
            self.outbox
                .push_control(Message::HostConfigApplied(applied));
        }
        drop(capacity);
        // The ready VMs count against the same slots.
        if let Some(ready_vms) = &self.ready_vms {
            ready_vms.poke();
        }
    }

    /// SIGHUP or `tinfirm-runner-host drain` (docs/archive/v1/CONVENTIONS.md §15.4 step 13): take no new run,
    /// let the active ones finish, deliver everything, then [`serve`] returns `Ok`. Tells the
    /// server with `host.config.applied` (`draining: true`, 0 slots) once a `host.config` was
    /// applied on the current connection; otherwise the answer to the first one says so.
    /// Returns `false` when the host already drains: a second request changes nothing.
    pub fn request_drain(&self) -> bool {
        let mut capacity = lock(&self.capacity);
        if !capacity.start_drain() {
            return false;
        }
        if let Some(applied) = capacity.applied() {
            self.outbox
                .push_control(Message::HostConfigApplied(applied));
        }
        drop(capacity);
        // A draining host keeps no ready VM.
        if let Some(ready_vms) = &self.ready_vms {
            ready_vms.poke();
        }
        // Before the log line: whatever happens to it, [`serve`] learns of the drain.
        self.drain_requested.cancel();
        tracing::info!(
            active_runs = self.active_run_count(),
            "draining: no new runs; the active runs finish, then the host exits"
        );
        true
    }

    /// `true` after [`Shared::request_drain`].
    pub fn is_draining(&self) -> bool {
        lock(&self.capacity).is_draining()
    }

    /// The capacity state (tests and diagnostics).
    pub fn capacity(&self) -> Capacity {
        lock(&self.capacity).clone()
    }

    // ----- admission (docs/archive/v1/CONVENTIONS.md §15.4 step 1) -------------------------------------------

    fn reject(&self, assign: &RunAssign, code: &str, reason: String) {
        tracing::info!(run_id = %assign.run_id, epoch = assign.epoch, code, %reason, "rejecting run");
        self.outbox.push_control(Message::RunRejected(RunRejected {
            run_id: assign.run_id,
            epoch: assign.epoch,
            reason,
            code: Some(code.to_string()),
        }));
    }

    /// `run.assign`: everything that is known at once is checked here, and the run takes its
    /// slot here, so two assignments cannot both take the last one. The rest runs in the
    /// run's own task ([`Shared::admit`], then [`run::execute`]).
    fn handle_assign(self: &Arc<Self>, assign: RunAssign) {
        if self.is_shutting_down() {
            return self.reject(
                &assign,
                reject_code::DRAINING,
                "the runner host is shutting down".into(),
            );
        }
        // Duplicates and re-assignments. A duplicate of a running execution is accepted
        // again even while draining: it is running here.
        let existing = lock(&self.runs).get(&assign.run_id).map(|e| e.epoch);
        if let Some(epoch) = existing {
            if epoch == assign.epoch {
                self.outbox.reaccept(assign.run_id, epoch);
                return;
            }
            if epoch > assign.epoch {
                return self.reject(
                    &assign,
                    reject_code::STALE_EPOCH,
                    format!("stale epoch {} (running epoch {epoch})", assign.epoch),
                );
            }
            // A newer epoch: our copy is stale. It stops at once; the new execution waits
            // for its end if it is admitted.
            self.request_stop(assign.run_id, StopRequest::Abort);
        }
        if self.is_draining() {
            return self.reject(&assign, reject_code::DRAINING, DRAINING_REASON.into());
        }
        let credential = match self.run_credential(&assign) {
            Ok(credential) => credential,
            Err((code, reason)) => return self.reject(&assign, code, reason),
        };
        if let Err(reason) =
            validate_workspaces(&assign.workspaces, self.opts.allow_insecure_repo_urls)
        {
            return self.reject(&assign, reject_code::INVALID_ASSIGNMENT, reason);
        }
        if let Err(reason) = crate::attachments::validate(&assign.attachments) {
            return self.reject(&assign, reject_code::INVALID_ASSIGNMENT, reason);
        }
        if let Err(reason) = crate::skills::validate(&assign.skills) {
            return self.reject(&assign, reject_code::INVALID_ASSIGNMENT, reason);
        }
        if !assign.skills.is_empty() && !self.takes_skills() {
            // The server sends skills only to a host that lists the feature; a run never goes
            // on without its skills (ADR-0064).
            return self.reject(
                &assign,
                reject_code::INVALID_ASSIGNMENT,
                format!(
                    "the run has skills, and this runner host does not take them (it does not list the feature {})",
                    feature::RUN_SKILLS
                ),
            );
        }
        let (stop_tx, stop_rx) = watch::channel(None);
        let steering = Steering::new(assign.run_id, assign.epoch, self.outbox.clone());
        let git_tokens = RunGitTokens::new(&assign);
        // Take the slot. The draining flag, the task, the host's slots and the pool are
        // checked under the lock of the runs, so two assignments cannot both take the last
        // slot, and no run starts after a drain found none active.
        let mut stale_copy = None;
        let refused = {
            let mut runs = lock(&self.runs);
            let capacity = lock(&self.capacity);
            // A re-assignment whose stale copy did not stop in time replaces its entry.
            let others = || runs.iter().filter(|(id, _)| **id != assign.run_id);
            let slots = capacity.max_concurrent_runs();
            // A run with model access needs a slot of the host only (ADR-0027).
            let label = credential.host_label();
            let pool = label.map(|l| capacity.pool_size(l).unwrap_or(0));
            let active = others().count();
            let in_pool = label.map_or(0, |l| {
                others()
                    .filter(|(_, e)| e.credential_label.as_deref() == Some(l))
                    .count()
            });
            let same_task = others()
                .find(|(_, e)| e.task_id == assign.work_item().id)
                .map(|(id, _)| *id);
            if capacity.is_draining() {
                Some((reject_code::DRAINING, DRAINING_REASON.to_string()))
            } else if let Some(other) = same_task {
                Some((
                    reject_code::TASK_RUN_ACTIVE,
                    format!(
                        "run {other} of {} {} is still active on this runner host",
                        if self.speaks_v2() {
                            "work item"
                        } else {
                            "task"
                        },
                        assign.work_item().key
                    ),
                ))
            } else if active >= slots as usize {
                let why = match capacity.bounding_vm_room() {
                    // The host's own slots would take the run: say why it has fewer now.
                    Some(room) => format!(
                        "no free slot ({active} of {slots} runs active; part of the VM driver's machines do not answer, and those that do have room for {room} of this runner host's runs)"
                    ),
                    None => format!("no free slot ({active} of {slots} runs active)"),
                };
                Some((reject_code::NO_FREE_SLOT, why))
            } else if let (Some(label), Some(pool)) = (label, pool)
                && in_pool >= pool as usize
            {
                Some((
                    reject_code::POOL_FULL,
                    format!("credential `{label}` has no free slot ({in_pool} of {pool} in use)"),
                ))
            } else {
                drop(capacity);
                // The stale copy of this run (an older epoch) gives its entry up; its task
                // is what the new execution waits for.
                stale_copy = runs
                    .insert(
                        assign.run_id,
                        RunEntry {
                            epoch: assign.epoch,
                            task_id: assign.work_item().id,
                            credential_label: label.map(str::to_string),
                            stop: stop_tx,
                            steering: steering.clone(),
                            git_tokens: git_tokens.clone(),
                            unqueued_revoked: Vec::new(),
                            handle: None,
                        },
                    )
                    .and_then(|stale| stale.handle);
                None
            }
        };
        if let Some((code, reason)) = refused {
            return self.reject(&assign, code, reason);
        }
        lock(&self.stale_hints).remove(&assign.run_id);
        let shared = self.clone();
        let run_id = assign.run_id;
        let epoch = assign.epoch;
        let handle = tokio::spawn(async move {
            let admitted = shared.admit(&assign).await;
            if let Some(stale_copy) = stale_copy {
                // The stale copy must be gone before this execution reuses the workspace
                // and the container name; an abort stops the sandbox within seconds.
                let _ = tokio::time::timeout(STALE_COPY_WAIT, stale_copy).await;
            }
            if admitted {
                let finished = run::execute(
                    &shared,
                    &assign,
                    &credential,
                    &git_tokens,
                    stop_rx,
                    &steering,
                )
                .await;
                // Before run.finished: a revocation that comes later is no event of the run.
                git_tokens.close_log();
                // Before run.finished: the steers the agent did not take wait for the next turn.
                steering.close("the run ended");
                match finished {
                    Some(finished) => {
                        tracing::info!(run_id = %run_id, outcome = ?finished.outcome, class = ?finished.failure_class, "run finished");
                        shared.outbox.push_finished(finished);
                    }
                    None => {
                        tracing::info!(run_id = %run_id, epoch, "run aborted (stale epoch)");
                        shared.outbox.drop_run(run_id, epoch);
                    }
                }
            }
            steering.close("the run ended");
            {
                // A newer epoch of the same run may already own the entry.
                let mut runs = lock(&shared.runs);
                if runs.get(&run_id).is_some_and(|e| e.epoch == epoch) {
                    runs.remove(&run_id);
                }
            }
            shared.drop_platform_requests(run_id, epoch);
            shared.remove_run_secrets(run_id, epoch);
        });
        if let Some(entry) = lock(&self.runs).get_mut(&run_id)
            && entry.epoch == epoch
        {
            entry.handle = Some(handle);
        }
    }

    /// What the run of `assign` uses to reach its model, as the host checks it at once
    /// (docs/archive/v1/CONVENTIONS.md §15.4 step 1): a credential of this host, as `run` read them when it
    /// started and as it is now, or the model access of a company model credential (ADR-0027),
    /// which needs no credential of the host. A refusal is a `run.rejected` code and reason.
    fn run_credential(
        &self,
        assign: &RunAssign,
    ) -> std::result::Result<RunCredential, (&'static str, String)> {
        let label = match assign.credential() {
            None => {
                return Err((
                    reject_code::INVALID_ASSIGNMENT,
                    "run.assign must carry exactly one of credentialLabel and modelAccess".into(),
                ));
            }
            Some(AssignedCredential::Model(access)) => {
                let local_server = crate::config::plain_http_local_origin(&self.config.server_url);
                return validate_model_access(access, assign.agent.runtime, local_server.as_ref())
                    .map(|()| RunCredential::Model(access.clone()))
                    .map_err(|reason| (reject_code::INVALID_ASSIGNMENT, reason));
            }
            Some(AssignedCredential::Host(label)) => label,
        };
        let credential = match self.credentials.get(label) {
            Ok(Some(c)) => c,
            Ok(None) => {
                return Err((
                    reject_code::UNKNOWN_CREDENTIAL,
                    format!("unknown credential `{label}`"),
                ));
            }
            Err(e) => {
                return Err((
                    reject_code::UNKNOWN_CREDENTIAL,
                    format!("cannot read credential `{label}`: {e:#}"),
                ));
            }
        };
        // Only the credentials of hello, as they were when `run` started (§15.7).
        let started_kind = lock(&self.capacity).credential(label).map(|c| c.kind);
        match started_kind {
            None => {
                return Err((
                    reject_code::UNKNOWN_CREDENTIAL,
                    format!(
                        "credential `{label}` was added after `run` started; drain or restart `run` to use it"
                    ),
                ));
            }
            Some(kind) if kind != credential.kind => {
                return Err((
                    reject_code::UNKNOWN_CREDENTIAL,
                    format!(
                        "credential `{label}` changed since `run` started; drain or restart `run` to use it"
                    ),
                ));
            }
            Some(_) => {}
        }
        if !credential.kind.runtimes().contains(&assign.agent.runtime) {
            return Err((
                reject_code::CREDENTIAL_UNAVAILABLE,
                format!(
                    "credential `{}` cannot serve runtime {}",
                    credential.label, assign.agent.runtime
                ),
            ));
        }
        if lock(&self.invalid).contains(&credential.label) {
            return Err((
                reject_code::CREDENTIAL_UNAVAILABLE,
                format!("credential `{}` is invalid", credential.label),
            ));
        }
        let cooldown = lock(&self.cooldowns)
            .get(&credential.label)
            .copied()
            .filter(|until| *until > Utc::now());
        if let Some(until) = cooldown {
            return Err((
                reject_code::CREDENTIAL_UNAVAILABLE,
                format!(
                    "credential `{}` is in cooldown until {until}",
                    credential.label
                ),
            ));
        }
        let problems = match credential.kind {
            CredentialKind::CodexChatgptAuth => crate::credentials::check_codex_home(
                &self.credentials.codex_home(&credential.label),
            ),
            CredentialKind::ClaudeLogin => crate::credentials::check_claude_login(
                &self.credentials.claude_login(&credential.label),
            ),
            CredentialKind::AnthropicApiKey | CredentialKind::OpenaiApiKey => Vec::new(),
        };
        if !problems.is_empty() {
            return Err((reject_code::CREDENTIAL_UNAVAILABLE, problems.join("; ")));
        }
        Ok(RunCredential::Host(credential))
    }

    /// The part of the admission that waits, in the run's task: Docker's image check. Then,
    /// under the lock of the runs, the run is accepted unless it was aborted or taken over by
    /// a newer epoch (nothing is reported), or the host began to shut down or drain
    /// meanwhile (rejected). Returns whether the run starts.
    async fn admit(&self, assign: &RunAssign) -> bool {
        let mut refusal = None;
        if let Some(d) = self.docker() {
            let image = self.opts.image_for(assign.agent.runtime);
            refusal = match images::inspect(d, &image).await {
                Ok(Some(_)) => None,
                Ok(None) => Some((
                    reject_code::IMAGE_MISSING,
                    format!("image {image} is missing; run `tinfirm-runner-host images build`"),
                )),
                Err(e) => Some((
                    reject_code::DOCKER_UNAVAILABLE,
                    format!("Docker is not available: {e:#}"),
                )),
            };
        }
        // A VM driver that can make no VM (crate::vm, `Vms::refusal`): the run waits in the
        // server's queue with the cause, instead of failing at its create. The protocol has
        // no code of its own for it; the host has no slot to give, and the reason says why.
        // A refusal found before keeps its own reason.
        if refusal.is_none()
            && let Executor::Vm(vms) = &self.opts.executor
            && let Some(reason) = vms.refusal().await
        {
            refusal = Some((reject_code::NO_FREE_SLOT, reason));
        }
        let mut runs = lock(&self.runs);
        let Some(entry) = runs
            .get_mut(&assign.run_id)
            .filter(|e| e.epoch == assign.epoch)
        else {
            // A newer epoch took the run over; that one answers the server.
            return false;
        };
        if *entry.stop.borrow() == Some(StopRequest::Abort) {
            tracing::info!(run_id = %assign.run_id, epoch = assign.epoch, "assignment aborted before it started");
            return false;
        }
        if refusal.is_none() {
            if self.is_shutting_down() {
                refusal = Some((
                    reject_code::DRAINING,
                    "the runner host is shutting down".into(),
                ));
            } else if lock(&self.capacity).is_draining() {
                refusal = Some((reject_code::DRAINING, DRAINING_REASON.into()));
            }
        }
        if let Some((code, reason)) = refusal {
            drop(runs);
            self.reject(assign, code, reason);
            return false;
        }
        tracing::info!(run_id = %assign.run_id, epoch = assign.epoch, work_item = %assign.work_item().key, "accepted run");
        // Under the lock of the runs: a newer epoch that takes the run over later opens its
        // queue after this one, never before.
        self.outbox.open_run(RunAccepted {
            run_id: assign.run_id,
            epoch: assign.epoch,
        });
        // A `run.git.revoke` that came during the admission is confirmed after the accept.
        for revoked in std::mem::take(&mut entry.unqueued_revoked) {
            self.outbox.push_revoked(revoked);
        }
        true
    }

    fn handle_cancel(&self, cancel: RunCancel) {
        let known = lock(&self.runs).get(&cancel.run_id).map(|e| e.epoch);
        match known {
            Some(epoch) if epoch == cancel.epoch => {
                let stale = lock(&self.stale_hints)
                    .remove(&cancel.run_id)
                    .is_some_and(|at| at.elapsed() < STALE_HINT_TTL);
                if stale {
                    // `error STALE_EPOCH` + `run.cancel(<our epoch>)`: this execution is
                    // stale. Stop it and report nothing.
                    tracing::info!(run_id = %cancel.run_id, epoch, "the server called this execution stale; aborting");
                    self.request_stop(cancel.run_id, StopRequest::Abort);
                } else {
                    // Duplicates (resent pending cancels) are harmless: stopping is idempotent.
                    tracing::info!(run_id = %cancel.run_id, epoch, "cancel requested by the server");
                    self.request_stop(cancel.run_id, StopRequest::Cancel);
                }
            }
            Some(epoch) if cancel.epoch > epoch => {
                // The server has moved on; our copy must not push or report.
                self.request_stop(cancel.run_id, StopRequest::Abort);
            }
            Some(epoch) => {
                // A cancel for an older execution of a run that now runs with a newer epoch
                // (it goes with a STALE_EPOCH error about that older execution).
                lock(&self.stale_hints).remove(&cancel.run_id);
                tracing::debug!(run_id = %cancel.run_id, cancel_epoch = cancel.epoch, epoch, "ignoring run.cancel for an older epoch");
            }
            None if self.outbox.knows_run(cancel.run_id) => {
                // Already finished (or aborted) here; a resent cancel needs no answer.
                tracing::debug!(run_id = %cancel.run_id, "run.cancel for a finished run ignored");
            }
            None => {
                if let Some(d) = self.docker() {
                    // In the background: Docker can be slow, and the connection does not wait.
                    let docker = d.clone();
                    let name = docker::container_name(cancel.run_id);
                    tokio::spawn(async move {
                        let _ = docker::remove_container(&docker, &name).await;
                    });
                }
                self.outbox.push_control(Message::Error(ErrorMessage {
                    code: "UNKNOWN_RUN".into(),
                    message: "this runner host has no such run (it may have restarted)".into(),
                    run_id: Some(cancel.run_id),
                    epoch: Some(cancel.epoch),
                }));
            }
        }
    }

    /// `run.git.revoke` (ADR-0116): the execution's holder drops the named repositories'
    /// tokens, and its take-back record is written again without them. Then the host confirms
    /// the frame with `run.git.revoked`, in the run's order through the outbox, naming every
    /// repository of the frame that the run has, those revoked already included: a frame that
    /// comes twice is confirmed twice. A frame for another epoch, or for a run this host does
    /// not hold or no longer holds, is ignored and confirmed by nothing, as is one that names
    /// none of the run's repositories. A record that could not be written is not confirmed: the
    /// server sends the frame again at the next hello, and the write is tried again then.
    fn handle_git_revoke(&self, revoke: RunGitRevoke) {
        let tokens = lock(&self.runs)
            .get(&revoke.run_id)
            .filter(|e| e.epoch == revoke.epoch)
            .map(|e| e.git_tokens.clone());
        let Some(tokens) = tokens else {
            tracing::debug!(run_id = %revoke.run_id, epoch = revoke.epoch, "run.git.revoke for an execution this host does not run; ignored");
            return;
        };
        tracing::info!(run_id = %revoke.run_id, epoch = revoke.epoch, reason = %revoke.reason, "the server revokes git tokens of a run");
        let applied = tokens.revoke(&revoke.component_ids, &revoke.reason, &revoke.message);
        if !applied.recorded {
            tracing::warn!(run_id = %revoke.run_id, epoch = revoke.epoch, "run.git.revoke not confirmed: the run's take-back record still holds the token");
            return;
        }
        if applied.named.is_empty() {
            tracing::debug!(run_id = %revoke.run_id, epoch = revoke.epoch, "run.git.revoke names none of the run's repositories; nothing to confirm");
            return;
        }
        let revoked = RunGitRevoked {
            run_id: revoke.run_id,
            epoch: revoke.epoch,
            component_ids: applied.named,
        };
        // Under the lock of the runs, as the admission opens the run's queue.
        let mut runs = lock(&self.runs);
        let Some(entry) = runs
            .get_mut(&revoke.run_id)
            .filter(|e| e.epoch == revoke.epoch)
        else {
            return;
        };
        if !self.outbox.push_revoked(revoked.clone()) {
            entry.unqueued_revoked.push(revoked);
        }
    }

    /// `run.steer`: for the execution's agent session, which answers it; an execution this host
    /// does not run refuses it at once.
    fn handle_steer(&self, steer: RunSteer) {
        let steering = lock(&self.runs)
            .get(&steer.run_id)
            .filter(|e| e.epoch == steer.epoch)
            .map(|e| e.steering.clone());
        match steering {
            Some(steering) => {
                tracing::info!(run_id = %steer.run_id, steer_id = %steer.steer_id, "steer for the running agent");
                steering.offer(steer);
            }
            None => {
                tracing::debug!(run_id = %steer.run_id, epoch = steer.epoch, "run.steer for an execution this host does not run");
                steer::refuse_unknown(&self.outbox, &steer);
            }
        }
    }

    fn handle_error(&self, error: ErrorMessage) {
        match error.code.as_str() {
            "STALE_EPOCH" => {
                tracing::warn!(run_id = ?error.run_id, epoch = ?error.epoch, message = %error.message, "the server reported a stale epoch");
                let Some(run_id) = error.run_id else { return };
                match error.epoch {
                    // The error names the execution: act on it directly.
                    Some(epoch) => self.abort_execution(run_id, epoch, "stale epoch"),
                    // Older servers: wait for the run.cancel that names the stale epoch.
                    None => {
                        lock(&self.stale_hints).insert(run_id, std::time::Instant::now());
                    }
                }
            }
            "UNKNOWN_RUN" => {
                tracing::warn!(run_id = ?error.run_id, epoch = ?error.epoch, message = %error.message, "the server does not know a run of this host");
                let Some(run_id) = error.run_id else { return };
                match error.epoch {
                    Some(epoch) => self.abort_execution(run_id, epoch, "unknown to the server"),
                    None => self.request_stop(run_id, StopRequest::Abort),
                }
            }
            "UNKNOWN_CREDENTIAL" => {
                tracing::warn!(message = %error.message, "the server does not know a credential of this host; drain or restart `run` after changing credentials");
            }
            "UNSUPPORTED_PROTOCOL" | "BAD_MESSAGE" | "UNKNOWN_TYPE" => {
                tracing::error!(code = %error.code, message = %error.message, "the server rejected a frame of this runner host");
            }
            _ => {
                tracing::warn!(code = %error.code, message = %error.message, run_id = ?error.run_id, "server error");
            }
        }
    }

    /// Aborts the execution `(run_id, epoch)` if it is the one running here; an error about
    /// another (older) epoch of the same run leaves the current execution alone.
    fn abort_execution(&self, run_id: Uuid, epoch: i64, why: &str) {
        let running = lock(&self.runs).get(&run_id).map(|e| e.epoch);
        match running {
            Some(current) if current == epoch => {
                tracing::info!(run_id = %run_id, epoch, why, "aborting the execution");
                lock(&self.stale_hints).remove(&run_id);
                self.request_stop(run_id, StopRequest::Abort);
            }
            Some(current) => {
                tracing::debug!(run_id = %run_id, epoch, current, "error about another epoch ignored");
            }
            None => {}
        }
    }

    fn request_stop(&self, run_id: Uuid, request: StopRequest) {
        if let Some(entry) = lock(&self.runs).get(&run_id) {
            let current = *entry.stop.borrow();
            // Abort wins over cancel; nothing downgrades an abort.
            if current != Some(StopRequest::Abort) {
                let _ = entry.stop.send(Some(request));
            }
        }
    }

    /// Stops every run without pushing or reporting (the connection is gone for good).
    pub async fn abort_all_runs(&self, timeout: Duration) {
        let handles: Vec<JoinHandle<()>> = {
            let mut runs = lock(&self.runs);
            runs.values_mut()
                .filter_map(|e| {
                    let _ = e.stop.send(Some(StopRequest::Abort));
                    e.handle.take()
                })
                .collect()
        };
        if !handles.is_empty() {
            tracing::warn!(runs = handles.len(), "aborting active runs");
            let _ = tokio::time::timeout(timeout, futures::future::join_all(handles)).await;
        }
    }

    /// Cancels every run and waits for them to report.
    pub async fn stop_all_runs(&self, timeout: Duration) {
        let handles: Vec<JoinHandle<()>> = {
            let mut runs = lock(&self.runs);
            runs.values_mut()
                .filter_map(|e| {
                    let _ = e.stop.send(Some(StopRequest::Cancel));
                    e.handle.take()
                })
                .collect()
        };
        if handles.is_empty() {
            return;
        }
        tracing::info!(runs = handles.len(), "cancelling active runs");
        let _ = tokio::time::timeout(timeout, futures::future::join_all(handles)).await;
    }

    /// Removes containers, or VMs, of this host that belong to no live run.
    pub async fn cleanup_orphans(&self) {
        let Some(host_id) = self.runner_host_id() else {
            return;
        };
        if let Executor::Vm(vms) = &self.opts.executor {
            let live = self.live_run_ids();
            match vms.cleanup_orphans(&host_id.to_string(), &live).await {
                Ok(0) => {}
                Ok(n) => tracing::info!(deleted = n, "deleted leftover sandbox VMs"),
                Err(e) => {
                    tracing::warn!(error = %format!("{e:#}"), "cannot list the leftover sandbox VMs")
                }
            }
            return;
        }
        let Some(d) = self.docker() else { return };
        let live = self.live_run_ids();
        match docker::cleanup_orphans(d, &host_id.to_string(), &live).await {
            Ok(0) => {}
            Ok(n) => tracing::info!(removed = n, "removed orphan run containers"),
            Err(e) => tracing::warn!(error = %e, "orphan cleanup failed"),
        }
    }
}

#[cfg(test)]
impl Shared {
    /// Registers an execution as if it were running (connection tests): heartbeats list it.
    pub(crate) fn register_test_run(&self, run_id: Uuid, epoch: i64, credential_label: &str) {
        let (stop, _) = watch::channel(None);
        lock(&self.runs).insert(
            run_id,
            RunEntry {
                epoch,
                task_id: Uuid::new_v4(),
                credential_label: Some(credential_label.to_string()),
                stop,
                steering: Steering::new(run_id, epoch, self.outbox.clone()),
                git_tokens: RunGitTokens::new_empty(run_id, epoch),
                unqueued_revoked: Vec::new(),
                handle: None,
            },
        );
    }
}

/// The platform requests through the server connection: the frame goes out as a control
/// message, and the tool waits for the ack with the same `requestId`, for as long as its
/// kind allows ([`REPOSITORY_REQUEST_TIMEOUT`], [`TOOL_REQUEST_TIMEOUT`]). A request that
/// still waits when the connection is lost is sent again after the next `welcome`
/// ([`Shared::on_welcome`]), and exactly one ack of its kind answers it
/// ([`Shared::handle_platform_ack`]). A server-executed tool call (TOOL) sent again so waits
/// once more, from that send: the replica that takes it answers within its tool timeout, and
/// the call journal never runs it twice (package R). File uploads go over HTTPS with the host token
/// (ADR-0021).
struct HostRequester {
    shared: Weak<Shared>,
}

/// Forgets a waiting request when its tool call ends, answered or not.
struct PendingCleanup {
    shared: Weak<Shared>,
    request_id: Uuid,
}

impl Drop for PendingCleanup {
    fn drop(&mut self) {
        if let Some(shared) = self.shared.upgrade() {
            lock(&shared.platform_requests).remove(&self.request_id);
        }
    }
}

impl HostRequester {
    /// Sends `frame` and waits up to `timeout` for the ack with `request_id`.
    fn send(
        &self,
        run_id: Uuid,
        epoch: i64,
        request_id: Uuid,
        frame: Message,
        timeout: fn(&HostOptions) -> Duration,
    ) -> BoxFuture<'static, RequestOutcome<Message>> {
        let weak = self.shared.clone();
        Box::pin(async move {
            let Some(shared) = weak.upgrade() else {
                return RequestOutcome::RunEnded;
            };
            if !shared.is_connected() {
                return RequestOutcome::NotConnected;
            }
            let (answer, mut answered) = oneshot::channel();
            let kind = frame.message_type();
            let renews = matches!(&frame, Message::RunRequest(request) if request.request.kind() == RequestKind::Tool);
            let resent = Arc::new(Notify::new());
            lock(&shared.platform_requests).insert(
                request_id,
                PendingRequest {
                    run_id,
                    epoch,
                    frame: frame.clone(),
                    answer,
                    resent: resent.clone(),
                },
            );
            let _cleanup = PendingCleanup {
                shared: weak,
                request_id,
            };
            shared.outbox.push_control(frame);
            let timeout = timeout(&shared.opts);
            drop(shared);
            let mut deadline = tokio::time::Instant::now() + timeout;
            let mut renewed = false;
            loop {
                tokio::select! {
                    ack = &mut answered => {
                        return match ack {
                            Ok(ack) => RequestOutcome::Answered(ack),
                            Err(_) => RequestOutcome::RunEnded,
                        };
                    }
                    _ = resent.notified(), if renews && !renewed => {
                        renewed = true;
                        deadline = tokio::time::Instant::now() + timeout;
                        tracing::info!(%request_id, frame = kind, "a tool call sent again after a reconnect waits once more");
                    }
                    _ = tokio::time::sleep_until(deadline) => {
                        tracing::warn!(%request_id, frame = kind, "the server did not answer a platform request in time");
                        return RequestOutcome::TimedOut;
                    }
                }
            }
        })
    }
}

impl PlatformRequester for HostRequester {
    fn redact(&self, text: &str) -> String {
        match self.shared.upgrade() {
            Some(shared) => shared.redactor().redact(text).into_owned(),
            None => crate::redact::REDACTED.to_string(),
        }
    }

    fn contains_secret(&self, content: &[u8]) -> bool {
        match self.shared.upgrade() {
            Some(shared) => shared.redactor().contains_any(content),
            None => true,
        }
    }

    fn upload_attachment(
        &self,
        upload: Upload,
    ) -> BoxFuture<'static, Result<UploadResponse, String>> {
        let weak = self.shared.clone();
        Box::pin(async move {
            let Some(shared) = weak.upgrade() else {
                return Err("The run is ending.".to_string());
            };
            let server_url = shared.config.server_url.clone();
            let token = shared.config.token.expose().to_string();
            drop(shared);
            let run_id = upload.run_id;
            attachments::upload(&server_url, &token, upload)
                .await
                .map_err(|e| {
                    tracing::warn!(%run_id, error = %format!("{e:#}"), "cannot upload an attachment");
                    "Tinfirm could not be reached, or did not answer.".to_string()
                })
        })
    }

    fn request(&self, frame: RunRepositoryRequest) -> BoxFuture<'static, RequestOutcome> {
        let sent = self.send(
            frame.run_id,
            frame.epoch,
            frame.request_id,
            Message::RunRepositoryRequest(frame),
            |opts| opts.repository_request_timeout,
        );
        Box::pin(async move {
            sent.await.map(|ack| match ack {
                Message::RunRepositoryRequestAck(ack) => Some(ack),
                _ => None,
            })
        })
    }

    fn create_task(
        &self,
        frame: RunTaskCreate,
    ) -> BoxFuture<'static, RequestOutcome<RunTaskCreateAck>> {
        let sent = self.send(
            frame.run_id,
            frame.epoch,
            frame.request_id,
            Message::RunTaskCreate(frame),
            |opts| opts.repository_request_timeout,
        );
        Box::pin(async move {
            sent.await.map(|ack| match ack {
                Message::RunTaskCreateAck(ack) => Some(ack),
                _ => None,
            })
        })
    }

    fn ask(&self, frame: RunAsk) -> BoxFuture<'static, RequestOutcome<RunAskAck>> {
        let sent = self.send(
            frame.run_id,
            frame.epoch,
            frame.request_id,
            Message::RunAsk(frame),
            |opts| opts.repository_request_timeout,
        );
        Box::pin(async move {
            sent.await.map(|ack| match ack {
                Message::RunAskAck(ack) => Some(ack),
                _ => None,
            })
        })
    }

    fn send_request(&self, frame: RunRequest) -> BoxFuture<'static, RequestOutcome<RunRequestAck>> {
        let timeout: fn(&HostOptions) -> Duration = match frame.request.kind() {
            RequestKind::Tool => |opts| opts.tool_request_timeout,
            RequestKind::Ask
            | RequestKind::GitToken
            | RequestKind::Access
            | RequestKind::Network
            | RequestKind::Artifact => |opts| opts.repository_request_timeout,
        };
        let sent = self.send(
            frame.run_id,
            frame.epoch,
            frame.request_id,
            Message::RunRequest(frame),
            timeout,
        );
        Box::pin(async move {
            sent.await.map(|ack| match ack {
                Message::RunRequestAck(ack) => Some(ack),
                _ => None,
            })
        })
    }
}

/// A git ref name we are willing to pass to git: no option-like or special syntax.
fn safe_ref(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 200
        && !name.starts_with('-')
        && !name.starts_with('/')
        && !name.ends_with('/')
        && !name.ends_with(".lock")
        && !name.contains("..")
        && !name.contains("//")
        && !name.contains("@{")
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'/'))
}

/// Checks the model access of a run (ADR-0027, ADR-0042) beyond the schema: its format serves the
/// agent's runtime; a base URL only for `anthropic` and `openai`, and https; a region for
/// `bedrock` and only there, and an access key ID only there; and no value holds whitespace or a
/// control character, since all of them become environment variables of the sandbox. The reason
/// never repeats the key.
///
/// `local_server` is the origin of this host's server when the host reaches it over plain http on
/// loopback (crate::config::plain_http_local_origin): a base URL with exactly that origin, the
/// relay of a server on this machine for local development, may be plain http too.
pub(crate) fn validate_model_access(
    access: &ModelAccess,
    runtime: Runtime,
    local_server: Option<&url::Origin>,
) -> Result<(), String> {
    use tinfirm_protocol::ModelFormat;
    if !access.format.serves(runtime) {
        return Err(format!(
            "the model access is for {}, which does not serve runtime {runtime}",
            access.format
        ));
    }
    // Fail closed (I8 of the security review of 2026-10-27): the server always sends its model
    // relay as the base URL, which the model proxy reaches with the run's token. Model access
    // without one would put its key into the sandbox's environment.
    let Some(url) = &access.base_url else {
        return Err(
            "model access without a base URL is refused: the server's model relay is the only model access a run takes"
                .into(),
        );
    };
    {
        if !matches!(access.format, ModelFormat::Anthropic | ModelFormat::Openai) {
            return Err(format!(
                "model access for {} takes no base URL; only an anthropic or openai gateway has one",
                access.format
            ));
        }
        let at_local_server = || {
            local_server.is_some_and(|origin| {
                url::Url::parse(url).is_ok_and(|parsed| parsed.origin() == *origin)
            })
        };
        let host = match url.strip_prefix("https://") {
            Some(host) => host,
            None if at_local_server() => url.strip_prefix("http://").unwrap_or(""),
            None => "",
        };
        if host.is_empty()
            || host.starts_with('/')
            || url.chars().any(|c| c.is_whitespace() || c.is_control())
        {
            return Err("the model access base URL must be an https URL".into());
        }
        let authority = host.split(['/', '?', '#']).next().unwrap_or("");
        if authority.contains('@') {
            return Err(
                "the model access base URL must not contain a user name or password".into(),
            );
        }
    }
    let plain = |value: &str| {
        !value.is_empty()
            && value
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-')
    };
    match (access.format, &access.region) {
        (ModelFormat::Bedrock, Some(region)) if plain(region) => {}
        (ModelFormat::Bedrock, _) => {
            return Err("model access for bedrock needs its AWS region, e.g. us-east-1".into());
        }
        (_, Some(_)) => {
            return Err(format!(
                "model access for {} takes no region",
                access.format
            ));
        }
        (_, None) => {}
    }
    if let Some(id) = &access.access_key_id {
        if access.format != ModelFormat::Bedrock {
            return Err(format!(
                "model access for {} takes no access key ID",
                access.format
            ));
        }
        if !plain(id) {
            return Err("the AWS access key ID must be letters and digits".into());
        }
    }
    let key = access.api_key.expose();
    if key.is_empty() || key.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err("the model access key is empty or contains whitespace".into());
    }
    Ok(())
}

/// The optional features of protocol v2 that a host with `executor` implements
/// (`hello.features`): `run.steer`, `run.skills` when the skills reach the sandbox (Docker
/// mounts them, a VM gets them over the bridge; the local test executor has no mounts,
/// ADR-0064), `session.bundle` (ADR-0097), which the server uses for company hosts only,
/// `run.git.revoke` and `run.git.refresh` (ADR-0116), `sandbox.bridge` with the sandbox bridge (ADR-0095), `sandbox.elastic` when the VM
/// driver is one of this binary's paid ones (ADR-0114), and `host.health` with a VM per run,
/// whose driver's state the host reports (ADR-0117). A v1 host lists none.
pub fn features(v2: bool, executor: &Executor, bridged: bool) -> Vec<String> {
    if !v2 {
        return Vec::new();
    }
    let mut features = vec![feature::RUN_STEER.to_string()];
    if matches!(executor, Executor::Docker(_) | Executor::Vm(_)) {
        features.push(feature::RUN_SKILLS.to_string());
    }
    features.push(feature::SESSION_BUNDLE.to_string());
    // Every executor: the holder of a run's git tokens is the host's (ADR-0116).
    features.push(feature::RUN_GIT_REVOKE.to_string());
    features.push(feature::RUN_GIT_REFRESH.to_string());
    if bridged {
        features.push(feature::SANDBOX_BRIDGE.to_string());
    }
    if let Executor::Vm(vms) = executor
        && vms.options.driver.elastic()
    {
        features.push(feature::SANDBOX_ELASTIC.to_string());
    }
    if crate::health::lists_feature(executor) {
        features.push(feature::HOST_HEALTH.to_string());
    }
    features
}

/// The longest git token the host takes, in an assignment or a renewal: the longest token the
/// server keeps (16 KiB, its bound for an OAuth access token; a token a person enters is at
/// most 4000 characters, and a fine-grained GitHub token has 93). It is also the bound of what
/// a take-back record's hashes of a token may be ([`crate::secret_hashes::MAX_FORM_LEN`]).
pub const MAX_GIT_TOKEN_BYTES: usize = 16384;

/// `run.assign.workspaces` (docs/archive/v1/CONVENTIONS.md §15.4 step 1): at most 20, each `dir` a valid and
/// unique directory name, and each repository valid ([`validate_workspace`]).
pub fn validate_workspaces(
    workspaces: &[WorkspaceSpec],
    allow_insecure: bool,
) -> Result<(), String> {
    if workspaces.len() > MAX_WORKSPACES {
        return Err(format!(
            "{} repositories; at most {MAX_WORKSPACES} are allowed",
            workspaces.len()
        ));
    }
    let mut dirs = HashSet::new();
    for ws in workspaces {
        if !is_valid_workspace_dir(&ws.dir) {
            return Err(format!("invalid workspace dir `{}`", ws.dir));
        }
        if !dirs.insert(ws.dir.as_str()) {
            return Err(format!("the workspace dir `{}` appears twice", ws.dir));
        }
        validate_workspace(ws, allow_insecure)
            .map_err(|reason| format!("repository `{}`: {reason}", ws.dir))?;
    }
    Ok(())
}

/// Defense in depth for one repository of `run.assign.workspaces`: HTTPS repositories, and a
/// task branch under `agent/` (the only kind of ref the host ever pushes).
pub fn validate_workspace(ws: &WorkspaceSpec, allow_insecure: bool) -> Result<(), String> {
    let url =
        url::Url::parse(&ws.repo_url).map_err(|_| format!("invalid repoUrl `{}`", ws.repo_url))?;
    match url.scheme() {
        "https" => {}
        "http" | "file" if allow_insecure => {}
        other => {
            return Err(format!(
                "repoUrl scheme `{other}` is not allowed (HTTPS only)"
            ));
        }
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err("repoUrl must not contain credentials".into());
    }
    if !ws.branch.starts_with("agent/") || !safe_ref(&ws.branch) {
        return Err(format!("branch `{}` is not an `agent/…` branch", ws.branch));
    }
    if !safe_ref(&ws.default_branch) {
        return Err(format!("invalid defaultBranch `{}`", ws.default_branch));
    }
    if let Some(git) = &ws.git
        && git.token.expose().len() > MAX_GIT_TOKEN_BYTES
    {
        return Err(format!("git.token is over {MAX_GIT_TOKEN_BYTES} bytes"));
    }
    // ADR-0116: a token that expires names its credential's mirror, and only together.
    if let Some(git) = &ws.git {
        match (&git.expires_at, &git.mirror_key) {
            (None, None) => {}
            (Some(_), Some(key)) if tinfirm_protocol::is_valid_mirror_key(key) => {}
            (Some(_), Some(_)) => {
                return Err("git.mirrorKey is not 64 lowercase hex characters".into());
            }
            _ => return Err("git.expiresAt and git.mirrorKey come together".into()),
        }
    }
    Ok(())
}

/// Why [`serve`] stops taking runs.
enum Stop {
    /// SIGINT or SIGTERM (docs/archive/v1/CONVENTIONS.md §15.4 step 12): cancel the runs, push WIP, report.
    Shutdown,
    /// A drain found no active run any more (step 13).
    Drained,
    /// The connection task ended on its own: a fatal close or error.
    Fatal(std::result::Result<Result<()>, tokio::task::JoinError>),
}

/// Waits for SIGINT/SIGTERM (`shutdown`), a fatal end of the connection, or the end of a
/// drain ([`Shared::request_drain`]).
async fn wait_for_stop(
    host: &Host,
    shutdown: &CancellationToken,
    connection: &mut JoinHandle<Result<()>>,
) -> Stop {
    let mut draining = false;
    loop {
        tokio::select! {
            biased;
            _ = shutdown.cancelled() => return Stop::Shutdown,
            result = &mut *connection => return Stop::Fatal(result),
            _ = host.drain_requested.cancelled(), if !draining => draining = true,
            _ = tokio::time::sleep(DRAIN_POLL), if draining => {
                if host.active_run_count() == 0 {
                    return Stop::Drained;
                }
            }
        }
    }
}

/// Gives the connection `limit` to deliver what is left, then stops it.
async fn deliver_within(connection: &mut JoinHandle<Result<()>>, limit: Duration) -> Result<()> {
    match tokio::time::timeout(limit, &mut *connection).await {
        Ok(joined) => {
            joined.unwrap_or_else(|e| Err(anyhow::anyhow!("connection task failed: {e}")))
        }
        Err(_) => {
            connection.abort();
            tracing::warn!("could not deliver every message before exit");
            Ok(())
        }
    }
}

/// Serves until `shutdown` fires (SIGINT/SIGTERM), a drain is complete, or the connection
/// fails fatally. After a drain it returns `Ok` once every message is delivered.
/// Whether a new hello is wanted: the hello of the connection carried `sent`, and the checks
/// of the nodes' hardening found `now`. A loss is told at once; a gain once it held for two
/// checks in a row, so that a node whose mark flaps does not move the server's placement
/// with it.
fn hello_is_stale(sent: crate::vm::Mark, now: crate::vm::Streak) -> bool {
    use crate::vm::Mark;
    match (sent, now.mark) {
        (Mark::Yes, Mark::No) => true,
        (Mark::No, Mark::Yes) => now.checks >= 2,
        _ => false,
    }
}

/// Passes the room of the VM driver's machines that answer on to the host's capacity, from its
/// value at the start and at each change, until `stop` ([`Host::set_vm_room`]). Nothing on a
/// host without a VM per run.
async fn follow_vm_room(host: Host, stop: CancellationToken) {
    let Executor::Vm(vms) = &host.opts.executor else {
        return;
    };
    let mut room = vms.room_watch();
    loop {
        let now = *room.borrow_and_update();
        host.set_vm_room(now);
        tokio::select! {
            () = stop.cancelled() => return,
            changed = room.changed() => {
                if changed.is_err() {
                    return;
                }
            }
        }
    }
}

/// Tells the server of a change of the node driver's hardening mark (ADR-0113, decision 5)
/// until `stop`: the protocol carries the mark in the hello alone, so the host asks the
/// connection for a new hello, which it makes by a reconnect (crate::connection). Runs go on
/// meanwhile, as through any reconnect: the server keeps their leases, and the new hello lists
/// them. Nothing is asked while the connection is down, since the next hello carries the
/// value of its time, and at most once per `hardening_every`. Nothing on a host that does not
/// report the nodes' mark.
async fn tell_hardening(host: Host, stop: CancellationToken) {
    let Executor::Vm(vms) = &host.opts.executor else {
        return;
    };
    if !vms.reads_hardening() || !host.speaks_v2() {
        return;
    }
    let every = vms.options.hardening_every;
    let mut streak = vms.streak_watch();
    let mut answer = host.hello_answer();
    let mut asked: Option<tokio::time::Instant> = None;
    loop {
        let now = *streak.borrow_and_update();
        answer.borrow_and_update();
        let sent = if host.is_connected() {
            *lock(&host.hello_mark)
        } else {
            None
        };
        if let Some(sent) = sent
            && hello_is_stale(sent, now)
        {
            if let Some(at) = asked
                && at.elapsed() < every
            {
                tokio::select! {
                    () = stop.cancelled() => return,
                    () = tokio::time::sleep_until(at + every) => continue,
                }
            }
            let hardening = vms.hardening();
            if now.mark == crate::vm::Mark::No {
                tracing::warn!(
                    not_hardened = %hardening.not_hardened.join("; "),
                    "the nodes' hardening mark is `no` now: this host reconnects, so that the server gets a hello with it"
                );
            } else {
                tracing::info!(
                    checks = now.checks,
                    "the nodes' hardening mark is `yes` now: this host reconnects, so that the server gets a hello with it"
                );
            }
            asked = Some(tokio::time::Instant::now());
            host.rehello.send_modify(|n| *n += 1);
        }
        tokio::select! {
            () = stop.cancelled() => return,
            changed = streak.changed() => if changed.is_err() { return },
            changed = answer.changed() => if changed.is_err() { return },
        }
    }
}

pub async fn serve(host: Host, shutdown: CancellationToken) -> Result<()> {
    // Before anything that cleans up what no run holds: these runs hold theirs.
    host.take_back_runs().await;
    run::recover_workspaces(&host);
    host.mirrors.remove_stale_temp_dirs();
    host.homes.remove_stale_temp_dirs();
    host.skills.remove_stale_temp_dirs(true);
    host.cleanup_orphans().await;
    // Cancelled when the connection should deliver what is left and close.
    let finish = CancellationToken::new();
    let mut connection = tokio::spawn(connection::run(host.clone(), finish.clone()));
    let maintenance_stop = CancellationToken::new();
    let maintenance = tokio::spawn(maintenance::run_loop(
        host.clone(),
        maintenance_stop.clone(),
    ));
    // The ready VMs' passes (crate::ready_vms), with `--pool-max-ready` above 0.
    let pool_stop = CancellationToken::new();
    let ready_vms = host
        .ready_vms()
        .map(|ready_vms| tokio::spawn(ready_vms.clone().run(host.clone(), pool_stop.clone())));
    // The host's report that it can take no run (ADR-0117); nothing on a host without a state
    // of its own.
    let health = tokio::spawn(crate::health::run(host.clone(), finish.clone()));
    // With the node driver: its `verify` on a schedule, and a new hello when the nodes'
    // hardening mark changes (ADR-0113, decision 5).
    let hardening = match &host.opts.executor {
        Executor::Vm(vms) => {
            let vms = vms.clone();
            let stop = finish.clone();
            Some(tokio::spawn(async move { vms.ask_hardening(stop).await }))
        }
        _ => None,
    };
    // With a VM per run: a health check when the driver says that a machine stopped answering
    // (`machinesChanged`), so that the room below follows it at once.
    let machines = match &host.opts.executor {
        Executor::Vm(vms) => {
            let vms = vms.clone();
            let stop = finish.clone();
            Some(tokio::spawn(async move { vms.follow_machines(stop).await }))
        }
        _ => None,
    };
    let tell = tokio::spawn(tell_hardening(host.clone(), finish.clone()));
    // With a VM per run: the slots this host has while part of its driver's machines do not
    // answer ([`Host::set_vm_room`]).
    let vm_room = tokio::spawn(follow_vm_room(host.clone(), finish.clone()));
    let ticker = {
        let host = host.clone();
        let stop = finish.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(5));
            loop {
                tokio::select! {
                    _ = stop.cancelled() => break,
                    _ = interval.tick() => {
                        host.expire_cooldowns();
                        host.refresh_redactor();
                        host.forget_delivered_reports();
                    }
                }
            }
        })
    };
    let stop = wait_for_stop(&host, &shutdown, &mut connection).await;
    match &stop {
        Stop::Fatal(_) => {
            host.shutting_down.store(true, Ordering::Relaxed);
            // 4401/4409/1002/1008: this process may not talk to the server any more. Do not
            // push work the server cannot hear about (another process may own the host now).
            host.abort_all_runs(Duration::from_secs(60)).await;
        }
        Stop::Shutdown => {
            host.shutting_down.store(true, Ordering::Relaxed);
            if host.is_draining() {
                tracing::info!(
                    "shutdown during a drain: cancelling the runs that are still active"
                );
            }
            host.stop_all_runs(host.opts.cancel_grace + Duration::from_secs(120))
                .await;
        }
        Stop::Drained => {
            tracing::info!("drain complete: no run is active; delivering the last messages");
        }
    }
    finish.cancel();
    ticker.abort();
    health.abort();
    tell.abort();
    vm_room.abort();
    if let Some(machines) = machines {
        machines.abort();
    }
    if let Some(hardening) = hardening {
        hardening.abort();
    }
    // A pass stops between two steps, but a gc that has started runs to its end: killing
    // `git gc` would leave its `git prune` running without the mirror's lock.
    maintenance_stop.cancel();
    if !maintenance.is_finished() {
        tracing::info!("waiting for the mirror maintenance to finish its current step");
    }
    let _ = maintenance.await;
    pool_stop.cancel();
    if let Some(ready_vms) = ready_vms {
        let _ = ready_vms.await;
    }
    // No more claims; the VMs that no run holds are deleted.
    if let Some(pool) = host.pool() {
        pool.stop().await;
    }
    host.stop_git_proxy().await;
    let ended = match stop {
        // The server closed the connection for good: the kept reports stay for the next start.
        Stop::Fatal(result) => {
            return result.unwrap_or_else(|e| Err(anyhow::anyhow!("connection task failed: {e}")));
        }
        // Let the connection deliver the final run.finished messages.
        Stop::Shutdown => deliver_within(&mut connection, Duration::from_secs(30)).await,
        // As long as it takes, reconnects included; SIGINT or SIGTERM bounds the wait.
        Stop::Drained => tokio::select! {
            joined = &mut connection => {
                joined.unwrap_or_else(|e| Err(anyhow::anyhow!("connection task failed: {e}")))
            }
            _ = shutdown.cancelled() => {
                deliver_within(&mut connection, Duration::from_secs(30)).await
            }
        },
    };
    // An orderly end: the ends that the server is known to have read, by the outbox's retention
    // or by its answer to the last connection's close, have their kept reports go; any other
    // stays for the next start. The ticker that forgets delivered reports has stopped already.
    if ended.is_ok() {
        host.forget_delivered_reports_at_the_end();
    }
    ended
}

#[cfg(test)]
mod tests {
    use super::*;
    use tinfirm_protocol::WorkspaceSpec;

    fn ws(url: &str, branch: &str, default: &str) -> WorkspaceSpec {
        WorkspaceSpec {
            component_id: Uuid::new_v4(),
            dir: "repo".into(),
            name: "Repo".into(),
            repo_url: url.into(),
            default_branch: default.into(),
            branch: branch.into(),
            git: None,
            author_name: None,
            author_email: None,
            read_only: false,
        }
    }

    fn access(format: tinfirm_protocol::ModelFormat, base_url: &str, key: &str) -> ModelAccess {
        ModelAccess {
            format,
            base_url: Some(base_url.into()),
            region: None,
            access_key_id: None,
            api_key: tinfirm_protocol::Secret::new(key),
        }
    }

    fn direct(format: tinfirm_protocol::ModelFormat, key: &str) -> ModelAccess {
        ModelAccess {
            format,
            base_url: None,
            region: None,
            access_key_id: None,
            api_key: tinfirm_protocol::Secret::new(key),
        }
    }

    #[test]
    fn validates_model_access() {
        use tinfirm_protocol::ModelFormat::{Anthropic, Openai};
        let key = "sk-litellm-TEST-ONLY-key";
        assert!(
            validate_model_access(
                &access(Anthropic, "https://litellm.example.test", key),
                Runtime::ClaudeCode,
                None
            )
            .is_ok()
        );
        assert!(
            validate_model_access(
                &access(Openai, "https://litellm.example.test:4000/v1", key),
                Runtime::Codex,
                None
            )
            .is_ok()
        );
        let refused = |access: ModelAccess, runtime: Runtime, why: &str| {
            let err = validate_model_access(&access, runtime, None).unwrap_err();
            assert!(err.contains(why), "{err}");
            let key = access.api_key.expose();
            assert!(key.is_empty() || !err.contains(key), "{err}");
        };
        refused(
            access(Openai, "https://litellm.example.test/v1", key),
            Runtime::ClaudeCode,
            "is for openai, which does not serve runtime CLAUDE_CODE",
        );
        refused(
            access(Anthropic, "https://litellm.example.test", key),
            Runtime::Codex,
            "is for anthropic",
        );
        refused(
            access(Anthropic, "http://litellm.example.test", key),
            Runtime::ClaudeCode,
            "must be an https URL",
        );
        refused(
            access(Anthropic, "https://", key),
            Runtime::ClaudeCode,
            "must be an https URL",
        );
        refused(
            access(Anthropic, "https://litellm.example.test/a b", key),
            Runtime::ClaudeCode,
            "must be an https URL",
        );
        refused(
            access(Anthropic, "https://user:pw@litellm.example.test", key),
            Runtime::ClaudeCode,
            "user name or password",
        );
        refused(
            access(Anthropic, "https://litellm.example.test", ""),
            Runtime::ClaudeCode,
            "key is empty",
        );
        refused(
            access(Anthropic, "https://litellm.example.test", "sk-a\nb"),
            Runtime::ClaudeCode,
            "contains whitespace",
        );
    }

    /// The relay of a server that this host reaches over plain http on loopback (`login --server
    /// http://localhost:8080`, as the Quick Start has it) may be plain http at exactly that
    /// origin; no other plain http base URL is taken. Before, every run of such a host was
    /// rejected (the release test of 1.0.0-rc.2, F-3).
    #[test]
    fn a_plain_http_relay_is_taken_only_at_the_hosts_own_local_server() {
        use tinfirm_protocol::ModelFormat::Anthropic;
        let key = "tfrun_TEST_ONLY_run_token";
        let local = crate::config::plain_http_local_origin("http://localhost:8080").unwrap();
        let relay = "http://localhost:8080/api/runner/v1/model/anthropic";
        let check = |base_url: &str, local: Option<&url::Origin>| {
            validate_model_access(
                &access(Anthropic, base_url, key),
                Runtime::ClaudeCode,
                local,
            )
        };
        assert!(check(relay, Some(&local)).is_ok());
        for (other, why) in [
            // Another port, or another name of the same machine, is another origin.
            (
                "http://localhost:8081/api/runner/v1/model/anthropic",
                "must be an https URL",
            ),
            (
                "http://127.0.0.1:8080/api/runner/v1/model/anthropic",
                "must be an https URL",
            ),
            ("http://litellm.example.test", "must be an https URL"),
            (
                "http://user:pw@localhost:8080/api/runner/v1/model/anthropic",
                "user name or password",
            ),
        ] {
            let err = check(other, Some(&local)).unwrap_err();
            assert!(err.contains(why), "{other}: {err}");
        }
        // A host whose server is https, or not on this machine, takes no plain http relay.
        assert!(
            check(relay, None)
                .unwrap_err()
                .contains("must be an https URL")
        );
    }

    /// I8 (security review of 2026-10-27): model access is the server's relay, with a base
    /// URL; access without one, which would put its key into the sandbox, is refused, whatever
    /// its format.
    #[test]
    fn validates_provider_keys() {
        use tinfirm_protocol::ModelFormat::{Anthropic, Bedrock, Google, Openai, Openrouter};
        let key = "sk-TEST-ONLY-key";
        let refused = |access: ModelAccess, runtime: Runtime, why: &str| {
            let err = validate_model_access(&access, runtime, None).unwrap_err();
            assert!(err.contains(why), "{err}");
            assert!(!err.contains(key), "{err}");
        };
        for (format, runtime) in [
            (Anthropic, Runtime::ClaudeCode),
            (Anthropic, Runtime::Pi),
            (Openai, Runtime::Opencode),
            (Openai, Runtime::Codex),
            (Openrouter, Runtime::Pi),
        ] {
            refused(
                direct(format, key),
                runtime,
                "without a base URL is refused",
            );
        }
        refused(
            ModelAccess {
                region: Some("eu-central-1".into()),
                ..direct(Bedrock, key)
            },
            Runtime::ClaudeCode,
            "without a base URL is refused",
        );
        assert!(
            validate_model_access(
                &access(Anthropic, "https://litellm.example.test", key),
                Runtime::Opencode,
                None
            )
            .is_ok()
        );
        refused(
            direct(Google, key),
            Runtime::ClaudeCode,
            "does not serve runtime CLAUDE_CODE",
        );
        refused(
            access(Google, "https://gw.example.test", key),
            Runtime::Pi,
            "takes no base URL",
        );
        refused(
            ModelAccess {
                region: Some("us-east-1".into()),
                ..access(Anthropic, "https://litellm.example.test", key)
            },
            Runtime::ClaudeCode,
            "takes no region",
        );
        refused(
            ModelAccess {
                access_key_id: Some("AKIAIOSFODNN7EXAMPLE".into()),
                ..access(Openai, "https://litellm.example.test/v1", key)
            },
            Runtime::Codex,
            "takes no access key ID",
        );
    }

    #[test]
    fn validates_the_workspace_list() {
        let ok = |dir: &str| {
            let mut w = ws("https://github.com/o/r.git", "agent/X-1", "main");
            w.dir = dir.into();
            w
        };
        assert!(validate_workspaces(&[], false).is_ok());
        assert!(
            validate_workspaces(&[ok("server"), ok("web-app"), ok("web-app-2")], false).is_ok()
        );
        let twenty: Vec<_> = (0..20).map(|i| ok(&format!("r{i}"))).collect();
        assert!(validate_workspaces(&twenty, false).is_ok());
        let too_many: Vec<_> = (0..21).map(|i| ok(&format!("r{i}"))).collect();
        assert!(
            validate_workspaces(&too_many, false)
                .unwrap_err()
                .contains("at most 20")
        );
        let twice = validate_workspaces(&[ok("server"), ok("server")], false).unwrap_err();
        assert!(twice.contains("appears twice"), "{twice}");
        for bad in ["", "..", ".", "a/b", "../x", "Server", "-a", "a b", "a--b"] {
            let err = validate_workspaces(&[ok(bad)], false).unwrap_err();
            assert!(err.contains("invalid workspace dir"), "{bad}: {err}");
        }
        let mut http = ok("server");
        http.repo_url = "http://github.com/o/r.git".into();
        let err = validate_workspaces(&[ok("web"), http], false).unwrap_err();
        assert!(
            err.contains("repository `server`") && err.contains("HTTPS only"),
            "{err}"
        );
        let mut branch = ok("server");
        branch.branch = "main".into();
        assert!(validate_workspaces(&[branch], false).is_err());
    }

    /// A v2 host, "connected" (after a welcome) but with no socket: the outbox is the wire. Its
    /// platform requests time out fast, so a test may wait for that.
    fn connected_v2_host(dir: &std::path::Path) -> Host {
        connected_v2_host_with_timeouts(dir, Duration::from_millis(300), Duration::from_millis(900))
    }

    /// [`connected_v2_host`] whose ASK, ACCESS and ARTIFACT requests wait `request` for their ack
    /// and whose TOOL requests wait `tool`.
    fn connected_v2_host_with_timeouts(
        dir: &std::path::Path,
        request: Duration,
        tool: Duration,
    ) -> Host {
        use crate::config::HostConfig;
        let paths = Paths::new(dir.join("config"), dir.join("data"));
        let mut config = HostConfig::new(
            "http://127.0.0.1:9",
            tinfirm_protocol::Secret::new("tfr_TESTONLYhosttokenfortherequestpairing0123"),
        );
        config.protocol_version = tinfirm_protocol::PROTOCOL_VERSION_V2;
        let mut opts = HostOptions::new(
            paths,
            Executor::Local {
                program: "false".into(),
                args: Vec::new(),
            },
        );
        opts.repository_request_timeout = request;
        opts.tool_request_timeout = tool;
        let host = Shared::new(opts, config);
        host.on_welcome(&tinfirm_protocol::Welcome {
            protocol_version: Some(2),
            runner_host_id: Uuid::new_v4(),
            company_id: Uuid::new_v4(),
            server_version: None,
            heartbeat_interval_seconds: 10,
            lease_ttl_seconds: 30,
            features: Vec::new(),
        });
        host
    }

    fn v2_request(run_id: Uuid, request: tinfirm_protocol::RequestPayload) -> RunRequest {
        RunRequest {
            run_id,
            epoch: 1,
            request_id: Uuid::new_v4(),
            request,
        }
    }

    fn ask_payload() -> tinfirm_protocol::RequestPayload {
        tinfirm_protocol::RequestPayload::Ask(tinfirm_protocol::AskRequest {
            question: "Why?".into(),
            options: vec![],
        })
    }

    fn tool_payload() -> tinfirm_protocol::RequestPayload {
        tinfirm_protocol::RequestPayload::Tool(tinfirm_protocol::ToolRequest {
            tool: "jira_get".into(),
            arguments: serde_json::json!({"key": "SHOP-1"}),
        })
    }

    fn v2_ack(run_id: Uuid, request_id: Uuid, status: tinfirm_protocol::RequestStatus) -> Message {
        Message::RunRequestAck(RunRequestAck {
            run_id,
            request_id,
            status,
            payload: Some(serde_json::json!({"decisionId": Uuid::new_v4()})),
            code: None,
            message: Some("ok".into()),
        })
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn v2_requests_are_answered_by_exactly_one_ack_of_their_kind() {
        use tinfirm_protocol::{RequestStatus, RunAskAck};
        let dir = tempfile::tempdir().unwrap();
        let host = connected_v2_host(dir.path());
        let requester = host.platform_requester();
        let run_id = Uuid::new_v4();
        let frame = v2_request(run_id, ask_payload());
        let request_id = frame.request_id;
        let waiting = tokio::spawn(requester.send_request(frame));
        // The frame goes out as a control message, with its request id.
        let sent = loop {
            if let Some(message) = host.outbox.take_next() {
                break message;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        };
        let Message::RunRequest(sent) = sent else {
            panic!("not a run.request: {sent:?}");
        };
        assert_eq!(sent.request_id, request_id);
        assert_eq!(sent.request.kind(), RequestKind::Ask);
        assert_eq!(host.waiting_platform_requests(), 1);
        // An ack of another kind with the same id is not the answer; nor is one of another run.
        host.handle_message(Message::RunAskAck(RunAskAck {
            run_id,
            request_id,
            status: tinfirm_protocol::AskStatus::Pending,
            ask_id: Some(Uuid::new_v4()),
            code: None,
            message: "wrong kind".into(),
        }));
        host.handle_message(v2_ack(Uuid::new_v4(), request_id, RequestStatus::Pending));
        assert_eq!(host.waiting_platform_requests(), 1);
        // The right one answers it; a second one is ignored (no error frame, nothing waits).
        host.handle_message(v2_ack(run_id, request_id, RequestStatus::Pending));
        let outcome = waiting.await.unwrap();
        let RequestOutcome::Answered(ack) = outcome else {
            panic!("not answered: {outcome:?}");
        };
        assert_eq!(ack.status, RequestStatus::Pending);
        assert_eq!(ack.message.as_deref(), Some("ok"));
        assert_eq!(host.waiting_platform_requests(), 0);
        host.handle_message(v2_ack(run_id, request_id, RequestStatus::Rejected));
        assert!(host.outbox.take_next().is_none(), "a late ack is ignored");
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn v2_requests_time_out_per_kind_and_are_sent_again_after_a_reconnect() {
        let dir = tempfile::tempdir().unwrap();
        let host = connected_v2_host(dir.path());
        let requester = host.platform_requester();
        let run_id = Uuid::new_v4();
        // ASK waits for the request timeout (300 ms here), TOOL for the tool timeout (900 ms).
        let started = std::time::Instant::now();
        let ask = requester.send_request(v2_request(run_id, ask_payload()));
        let tool = requester.send_request(v2_request(run_id, tool_payload()));
        let (ask, ask_took) = {
            let outcome = ask.await;
            (outcome, started.elapsed())
        };
        assert_eq!(ask, RequestOutcome::TimedOut);
        assert!(ask_took >= Duration::from_millis(250), "{ask_took:?}");
        assert!(ask_took < Duration::from_millis(800), "{ask_took:?}");
        let tool = tool.await;
        let tool_took = started.elapsed();
        assert_eq!(tool, RequestOutcome::TimedOut);
        assert!(tool_took >= Duration::from_millis(850), "{tool_took:?}");
        assert_eq!(host.waiting_platform_requests(), 0);
        // Everything went out once; nothing was answered.
        let mut sent = 0;
        while host.outbox.take_next().is_some() {
            sent += 1;
        }
        assert_eq!(sent, 2);

        // A request that waits when the connection is lost goes out again after the next
        // welcome, with the same request id. On a host whose requests wait long: the welcome
        // writes the host state to disk, which takes a loaded machine a while, and the request
        // must not run into its timeout meanwhile.
        let dir = tempfile::tempdir().unwrap();
        let host = connected_v2_host_with_timeouts(
            dir.path(),
            Duration::from_secs(30),
            Duration::from_secs(60),
        );
        let requester = host.platform_requester();
        let frame = v2_request(run_id, tool_payload());
        let request_id = frame.request_id;
        let waiting = tokio::spawn(requester.send_request(frame));
        loop {
            if host.outbox.take_next().is_some() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        host.on_disconnect();
        host.on_welcome(&tinfirm_protocol::Welcome {
            protocol_version: Some(2),
            runner_host_id: Uuid::new_v4(),
            company_id: Uuid::new_v4(),
            server_version: None,
            heartbeat_interval_seconds: 10,
            lease_ttl_seconds: 30,
            features: Vec::new(),
        });
        let again = host.outbox.take_next().expect("the request is sent again");
        let Message::RunRequest(again) = again else {
            panic!("not a run.request: {again:?}");
        };
        assert_eq!(again.request_id, request_id);
        host.handle_message(v2_ack(
            run_id,
            request_id,
            tinfirm_protocol::RequestStatus::Done,
        ));
        assert!(matches!(
            waiting.await.unwrap(),
            RequestOutcome::Answered(_)
        ));

        // Not connected: nothing is sent.
        host.on_disconnect();
        let outcome = requester
            .send_request(v2_request(run_id, ask_payload()))
            .await;
        assert_eq!(outcome, RequestOutcome::NotConnected);
        assert!(host.outbox.take_next().is_none());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_tool_request_sent_again_after_a_reconnect_waits_once_more_from_that_send() {
        let dir = tempfile::tempdir().unwrap();
        let host = connected_v2_host_with_timeouts(
            dir.path(),
            Duration::from_secs(2),
            Duration::from_secs(2),
        );
        let requester = host.platform_requester();
        let run_id = Uuid::new_v4();
        let welcome = tinfirm_protocol::Welcome {
            protocol_version: Some(2),
            runner_host_id: Uuid::new_v4(),
            company_id: Uuid::new_v4(),
            server_version: None,
            heartbeat_interval_seconds: 10,
            lease_ttl_seconds: 30,
            features: Vec::new(),
        };
        let started = std::time::Instant::now();
        let ask = tokio::spawn(requester.send_request(v2_request(run_id, ask_payload())));
        let tool = tokio::spawn(requester.send_request(v2_request(run_id, tool_payload())));
        let mut sent = 0;
        while sent < 2 {
            if host.outbox.take_next().is_some() {
                sent += 1;
            } else {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        }
        // A reconnect after 1 s: both go out again; the tool call waits 2 s from this send.
        tokio::time::sleep(Duration::from_secs(1)).await;
        host.on_disconnect();
        host.on_welcome(&welcome);
        // A second reconnect does not renew it again.
        tokio::time::sleep(Duration::from_millis(1500)).await;
        host.on_disconnect();
        host.on_welcome(&welcome);

        assert_eq!(ask.await.unwrap(), RequestOutcome::TimedOut);
        let tool = tool.await.unwrap();
        let tool_took = started.elapsed();
        assert_eq!(tool, RequestOutcome::TimedOut);
        // Not at 2 s from its first send, nor 2 s after the second reconnect (4.5 s): once.
        assert!(tool_took >= Duration::from_millis(2800), "{tool_took:?}");
        assert!(tool_took < Duration::from_millis(4300), "{tool_took:?}");
        assert_eq!(host.waiting_platform_requests(), 0);
    }

    /// A process killed while it wrote a record, a kept report or the bridge's identity leaves
    /// the temporary copy of that file, credentials and all: the next start removes it, and
    /// keeps what is no such copy.
    #[tokio::test]
    async fn the_start_removes_what_a_killed_write_of_a_record_left() {
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let runs = paths.data_dir.join("runs");
        crate::secure_fs::ensure_private_dir(&runs).unwrap();
        let (run, other) = (Uuid::new_v4(), Uuid::new_v4());
        let leftovers = [
            format!("{run}.json.tmp"),
            format!(".{run}.json.tmp.tmp-{}", Uuid::new_v4().simple()),
            format!("{other}.3.finished.json.tmp"),
            format!(
                ".{other}.3.finished.json.tmp.tmp-{}",
                Uuid::new_v4().simple()
            ),
            "bridge.json.tmp".to_string(),
            format!(".bridge.json.tmp.tmp-{}", Uuid::new_v4().simple()),
        ];
        for name in &leftovers {
            // TEST-ONLY value, no real credential.
            std::fs::write(runs.join(name), r#"{"token":"ghs_TEST_ONLY_leftover"}"#).unwrap();
        }
        // No copy of a write: it stays.
        std::fs::write(runs.join("notes.txt"), "kept").unwrap();
        let local = Executor::Local {
            program: "false".into(),
            args: Vec::new(),
        };
        let config = crate::config::HostConfig::new(
            "http://127.0.0.1:9",
            tinfirm_protocol::Secret::new("tfr_TEST_ONLY_host_token_of_the_leftover_test"),
        );
        let host = Shared::new(HostOptions::new(paths, local), config);
        host.take_back_runs().await;
        for name in &leftovers {
            assert!(!runs.join(name).exists(), "{name} is still there");
        }
        assert!(runs.join("notes.txt").exists());
    }

    #[tokio::test]
    async fn each_epoch_of_a_run_scans_with_its_own_hashes() {
        use crate::config::HostConfig;
        use crate::secret_hashes::{HashedMatcher, SecretHashes};
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let local = Executor::Local {
            program: "false".into(),
            args: Vec::new(),
        };
        let config = HostConfig::new(
            "http://127.0.0.1:9",
            tinfirm_protocol::Secret::new("tfr_TEST_ONLY_host_token_of_the_epoch_test_0123"),
        );
        let host = Shared::new(HostOptions::new(paths, local), config);
        let run = Uuid::new_v4();
        // Epoch 1, a copy this host still ends, and epoch 2, which replaced it: each held an
        // earlier token of its own.
        let (first, second) = (
            "ghs_TEST_ONLY_earlier_of_epoch_1_0123",
            "ghs_TEST_ONLY_earlier_of_epoch_2_4567",
        );
        for (epoch, token) in [(1, first), (2, second)] {
            let mut hashes = SecretHashes::new();
            hashes.add(token);
            host.add_run_hashed(run, epoch, Arc::new(HashedMatcher::new(&hashes).unwrap()));
        }
        let one = host.run_redactor(run, 1);
        let two = host.run_redactor(run, 2);
        assert!(one.contains_credential(first.as_bytes()));
        assert!(!one.contains_credential(second.as_bytes()));
        assert!(two.contains_credential(second.as_bytes()));
        assert!(!two.contains_credential(first.as_bytes()));
        // An execution with no hashes, or another run, gets none of these.
        assert!(
            !host
                .run_redactor(run, 3)
                .contains_credential(first.as_bytes())
        );
        assert!(
            !host
                .run_redactor(Uuid::new_v4(), 1)
                .contains_credential(first.as_bytes())
        );
        // The host-wide redactor of events and log lines has both.
        assert!(host.redactor().contains_credential(first.as_bytes()));
        assert!(host.redactor().contains_credential(second.as_bytes()));
        // Once epoch 1 ends, its hashes go, and epoch 2 keeps its own.
        host.remove_run_secrets(run, 1);
        assert!(!host.redactor().contains_credential(first.as_bytes()));
        assert!(
            host.run_redactor(run, 2)
                .contains_credential(second.as_bytes())
        );
    }

    #[tokio::test]
    async fn hello_names_the_version_and_reports_the_tier_under_v2_only() {
        use crate::config::HostConfig;
        use crate::sandbox::SandboxSettings;
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let local = Executor::Local {
            program: "false".into(),
            args: Vec::new(),
        };
        let mut config = HostConfig::new(
            "http://127.0.0.1:9",
            tinfirm_protocol::Secret::new("tfr_TESTONLYhosttokenforthehelloframe00000123"),
        );
        // An API key of this host also serves pi and OpenCode (ADR-0041).
        crate::credentials::CredentialStore::new(&paths)
            .save(&crate::credentials::Credential {
                label: "anthropic-key".into(),
                kind: CredentialKind::AnthropicApiKey,
                pool_size: 2,
                created_at: Utc::now(),
                secret: Some(tinfirm_protocol::Secret::new("sk-ant-TEST-ONLY")),
            })
            .unwrap();
        // The default is v2 (package 8); this test states the version of each half.
        config.protocol_version = 1;
        let kata = SandboxSettings::new(Some("kata".into()), false, Vec::new()).unwrap();
        let mut opts = HostOptions::new(paths.clone(), local.clone());
        opts.sandbox = kata.clone();
        let v1 = Shared::new(opts, config.clone());
        let hello = v1.hello().await;
        assert_eq!(hello.protocol_version, 1);
        assert_eq!(hello.isolation_tiers, vec![IsolationTier::Container]);
        assert!(!v1.speaks_v2());
        // A v1 server refuses PI and OPENCODE: v1 names neither, also not for a credential.
        let runtimes: Vec<Runtime> = hello.runtimes.iter().map(|r| r.runtime).collect();
        assert_eq!(runtimes, vec![Runtime::ClaudeCode, Runtime::Codex]);
        assert_eq!(hello.credentials[0].runtimes, vec![Runtime::ClaudeCode]);

        config.protocol_version = 2;
        let mut opts = HostOptions::new(paths.clone(), local.clone());
        opts.sandbox = kata;
        let v2 = Shared::new(opts, config.clone());
        let hello = v2.hello().await;
        assert_eq!(hello.protocol_version, 2);
        // The release's version, the workspace's: a semantic version alone, never the build
        // label that `--version` adds (ADR-0109).
        assert_eq!(hello.runner_version, crate::version::RUNNER_VERSION);
        assert_eq!(hello.runner_version, env!("CARGO_PKG_VERSION"));
        assert!(
            !hello.runner_version.contains(' '),
            "{}",
            hello.runner_version
        );
        // The local test executor reports `container`, whatever its sandbox settings.
        assert_eq!(hello.isolation_tiers, vec![IsolationTier::Container]);
        assert!(v2.speaks_v2());
        let runtimes: Vec<Runtime> = hello.runtimes.iter().map(|r| r.runtime).collect();
        assert_eq!(runtimes, Runtime::ALL.to_vec());
        assert_eq!(
            hello.credentials[0].runtimes,
            vec![Runtime::ClaudeCode, Runtime::Pi, Runtime::Opencode]
        );
        let plain = Shared::new(HostOptions::new(paths, local), config);
        assert_eq!(
            plain.hello().await.isolation_tiers,
            vec![IsolationTier::Container]
        );
    }

    #[tokio::test]
    async fn hello_reports_the_level_of_isolation_in_its_tiers() {
        use crate::config::HostConfig;
        use crate::isolation::Stated;
        use crate::sandbox::SandboxSettings;
        use tinfirm_protocol::Hardened;
        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let mut config = HostConfig::new(
            "http://127.0.0.1:9",
            tinfirm_protocol::Secret::new("tfr_TESTONLYhosttokenforthehelloframe00000123"),
        );
        let hello = |executor: Executor, sandbox: SandboxSettings, stated, config: &HostConfig| {
            let mut opts = HostOptions::new(paths.clone(), executor);
            opts.sandbox = sandbox;
            opts.isolation_stated = stated;
            let host = Shared::new(opts, config.clone());
            async move { host.hello().await }
        };
        // The tiers, `hardened` and `isolationStated` of a hello.
        let level = |h: &Hello| (h.isolation_tiers.clone(), h.hardened, h.isolation_stated);
        let seen = |tier| (vec![tier], None, false);
        // The feature of the decision that this one replaces is gone (ADR-0113).
        let no_old_feature = |h: &Hello| !h.features.iter().any(|f| f == "host.isolation");
        // Never contacted: the hello's image checks fail at once.
        let docker = || {
            Executor::Docker(
                bollard::Docker::connect_with_http(
                    "http://127.0.0.1:9",
                    1,
                    bollard::API_DEFAULT_VERSION,
                )
                .unwrap(),
            )
        };
        let vm = |driver: &str| {
            Executor::Vm(crate::vm::Vms::new(crate::vm::VmOptions::new(
                crate::vm::DriverCommand::parse(driver).unwrap(),
                "sandbox".into(),
            )))
        };
        let vms = || SandboxSettings::for_vms(None, false, Vec::new()).unwrap();
        let runtime =
            |name: &str| SandboxSettings::new(Some(name.into()), false, Vec::new()).unwrap();

        // Docker with runc or Kata: `container` (a Kata host's VMM is root on this machine).
        for sandbox in [SandboxSettings::default(), runtime("runc"), runtime("kata")] {
            let h = hello(docker(), sandbox, None, &config).await;
            assert_eq!(level(&h), seen(IsolationTier::Container));
            assert!(no_old_feature(&h));
        }
        // Docker with gVisor: `gvisor`.
        let h = hello(docker(), runtime("runsc"), None, &config).await;
        assert_eq!(level(&h), seen(IsolationTier::Gvisor));
        // A provider's VM per run: `machine`, and no machine of the host's own.
        let h = hello(vm("digitalocean"), vms(), None, &config).await;
        assert_eq!(
            level(&h),
            (vec![IsolationTier::Machine], Some(Hardened::None), false)
        );
        assert!(no_old_feature(&h));
        // The operator's own driver: `container` until the operator states a level.
        let h = hello(vm("/usr/local/bin/my-driver"), vms(), None, &config).await;
        assert_eq!(level(&h), seen(IsolationTier::Container));
        let stated = Stated {
            level: IsolationTier::VmJail,
            hardened: Hardened::Yes,
        };
        let h = hello(vm("/usr/local/bin/my-driver"), vms(), Some(stated), &config).await;
        assert_eq!(
            level(&h),
            (vec![IsolationTier::VmJail], Some(Hardened::Yes), true)
        );
        // The node driver (ADR-0112): what its configuration reaches, never a statement.
        let node = |config: &std::path::Path| {
            Executor::Vm(crate::vm::Vms::new(crate::vm::VmOptions::new(
                crate::vm::DriverCommand::node(config.to_path_buf()).unwrap(),
                "sandbox".into(),
            )))
        };
        let sockets = dir.path().join("sockets.json");
        std::fs::write(
            &sockets,
            r#"{"nodes": [{"name": "local", "socket": "/run/tinfirm-node-agent/agent.sock", "templates": ["sandbox"]}]}"#,
        )
        .unwrap();
        let h = hello(node(&sockets), vms(), None, &config).await;
        assert_eq!(level(&h), seen(IsolationTier::VmJail));
        assert!(no_old_feature(&h));
        assert!(!h.features.iter().any(|f| f == feature::SANDBOX_ELASTIC));
        // TLS nodes at addresses that are not this host's own (TEST-NET-1).
        let (cert, key) = (dir.path().join("client.crt"), dir.path().join("client.key"));
        std::fs::write(&cert, b"not parsed until the driver dials").unwrap();
        std::fs::write(&key, b"not parsed until the driver dials").unwrap();
        std::fs::set_permissions(&key, std::os::unix::fs::PermissionsExt::from_mode(0o600))
            .unwrap();
        let tls = dir.path().join("tls.json");
        std::fs::write(
            &tls,
            serde_json::json!({"nodes": [{
                "name": "node-1",
                "address": "192.0.2.10:7700",
                "nodeSha256": "ab".repeat(32),
                "certificate": cert,
                "key": key,
                "templates": ["sandbox"]
            }]})
            .to_string(),
        )
        .unwrap();
        let h = hello(node(&tls), vms(), None, &config).await;
        assert_eq!(level(&h), seen(IsolationTier::Machine));
        // The nodes' hardening, as the driver's `verify` at the start reported it (ADR-0113,
        // decision 5): `yes` is sent, `no` is left out.
        let mark = |mark| crate::vm::Verified {
            hardened: Some(mark),
            ..Default::default()
        };
        let checked = |driver: crate::vm::DriverCommand, mark| {
            let vms = crate::vm::Vms::new(crate::vm::VmOptions::new(driver, "sandbox".into()));
            vms.note_hardening(&mark, true);
            Executor::Vm(vms)
        };
        let node_driver = || crate::vm::DriverCommand::node(tls.clone()).unwrap();
        let h = hello(
            checked(node_driver(), mark(crate::vm::Mark::Yes)),
            vms(),
            None,
            &config,
        )
        .await;
        assert_eq!(
            level(&h),
            (vec![IsolationTier::Machine], Some(Hardened::Yes), false)
        );
        let h = hello(
            checked(node_driver(), mark(crate::vm::Mark::No)),
            vms(),
            None,
            &config,
        )
        .await;
        assert_eq!(level(&h), seen(IsolationTier::Machine));
        // A driver that could not tell at the start: `no`.
        let h = hello(
            checked(node_driver(), Default::default()),
            vms(),
            None,
            &config,
        )
        .await;
        assert_eq!(level(&h), seen(IsolationTier::Machine));
        // An operator's own driver whose `verify` says `yes`: its operator's statement stands.
        let own = || crate::vm::DriverCommand::parse("/usr/local/bin/my-driver").unwrap();
        let h = hello(
            checked(own(), mark(crate::vm::Mark::Yes)),
            vms(),
            None,
            &config,
        )
        .await;
        assert_eq!(level(&h), seen(IsolationTier::Container));
        let no = Stated {
            level: IsolationTier::VmJail,
            hardened: Hardened::No,
        };
        let h = hello(
            checked(own(), mark(crate::vm::Mark::Yes)),
            vms(),
            Some(no),
            &config,
        )
        .await;
        assert_eq!(
            level(&h),
            (vec![IsolationTier::VmJail], Some(Hardened::No), true)
        );
        // A statement with the node driver: the host reports `container` (`run` refuses it at
        // start).
        let h = hello(node(&tls), vms(), Some(stated), &config).await;
        assert_eq!(level(&h), seen(IsolationTier::Container));
        // The local test executor: `container`.
        let local = Executor::Local {
            program: "false".into(),
            args: Vec::new(),
        };
        let h = hello(local, SandboxSettings::default(), None, &config).await;
        assert_eq!(level(&h), seen(IsolationTier::Container));
        // Protocol 1: `container` as before, whatever the host, and neither field.
        config.protocol_version = 1;
        for (executor, sandbox) in [(docker(), runtime("runsc")), (vm("digitalocean"), vms())] {
            let h = hello(executor, sandbox, None, &config).await;
            assert_eq!(level(&h), seen(IsolationTier::Container));
            assert!(h.features.is_empty());
        }
        let h = hello(vm("/usr/local/bin/my-driver"), vms(), Some(stated), &config).await;
        assert_eq!(level(&h), seen(IsolationTier::Container));
    }

    /// A host of protocol `version` with the node driver, its nodes over a local socket, that
    /// asks for the hardening every `every`; connected.
    fn node_host(dir: &std::path::Path, version: u32, every: Duration) -> Host {
        use crate::config::HostConfig;
        let nodes = dir.join("node-driver.json");
        std::fs::write(
            &nodes,
            r#"{"nodes": [{"name": "local", "socket": "/run/tinfirm-node-agent/agent.sock", "templates": ["sandbox"]}]}"#,
        )
        .unwrap();
        let mut options = crate::vm::VmOptions::new(
            crate::vm::DriverCommand::node(nodes).unwrap(),
            "sandbox".into(),
        );
        options.hardening_every = every;
        let mut opts = HostOptions::new(
            Paths::new(dir.join("config"), dir.join("data")),
            Executor::Vm(crate::vm::Vms::new(options)),
        );
        opts.sandbox = crate::sandbox::SandboxSettings::for_vms(None, false, Vec::new()).unwrap();
        let mut config = HostConfig::new(
            "http://127.0.0.1:9",
            tinfirm_protocol::Secret::new("tfr_TESTONLYhosttokenforthehardeningmark00001"),
        );
        config.protocol_version = version;
        let host = Shared::new(opts, config);
        welcome(&host);
        host
    }

    fn welcome(host: &Host) {
        host.on_welcome(&tinfirm_protocol::Welcome {
            protocol_version: Some(host.protocol_version()),
            runner_host_id: Uuid::new_v4(),
            company_id: Uuid::new_v4(),
            server_version: None,
            heartbeat_interval_seconds: 10,
            lease_ttl_seconds: 30,
            features: Vec::new(),
        });
    }

    /// What a check of the node driver's `verify` says of the hardening: `None`, "not known".
    fn note(host: &Host, mark: Option<crate::vm::Mark>) {
        let Executor::Vm(vms) = &host.opts.executor else {
            unreachable!("a VM host");
        };
        vms.note_hardening(
            &crate::vm::Verified {
                hardened: mark,
                not_hardened: match mark {
                    Some(crate::vm::Mark::Yes) => Vec::new(),
                    _ => vec!["node local: io-uring: kernel.io_uring_disabled is 0, not 2".into()],
                },
                ..Default::default()
            },
            false,
        );
    }

    #[tokio::test]
    async fn each_hello_carries_the_nodes_hardening_of_its_time() {
        use crate::vm::Mark;
        use tinfirm_protocol::Hardened;
        let dir = tempfile::tempdir().unwrap();
        let host = node_host(dir.path(), 2, Duration::from_secs(300));
        let marks = |hello: &Hello| (hello.isolation_tiers.clone(), hello.hardened);
        // Not asked yet: `no`, left out.
        let hello = host.hello().await;
        assert_eq!(marks(&hello), (vec![IsolationTier::VmJail], None));
        assert_eq!(*lock(&host.hello_mark), Some(Mark::No));
        // The host was built before the nodes said `yes`: the hello reads it when it is built.
        note(&host, Some(Mark::Yes));
        let hello = host.hello().await;
        assert_eq!(
            marks(&hello),
            (vec![IsolationTier::VmJail], Some(Hardened::Yes))
        );
        assert_eq!(*lock(&host.hello_mark), Some(Mark::Yes));
        assert_eq!(host.isolation().hardened, Some(Hardened::Yes));
        // "Not known" keeps it.
        note(&host, None);
        assert_eq!(host.hello().await.hardened, Some(Hardened::Yes));
        note(&host, Some(Mark::No));
        assert_eq!(
            marks(&host.hello().await),
            (vec![IsolationTier::VmJail], None)
        );
        assert_eq!(*lock(&host.hello_mark), Some(Mark::No));
        // Protocol 1 reports `container` and carries no mark.
        let dir = tempfile::tempdir().unwrap();
        let v1 = node_host(dir.path(), 1, Duration::from_secs(300));
        note(&v1, Some(Mark::Yes));
        assert_eq!(
            marks(&v1.hello().await),
            (vec![IsolationTier::Container], None)
        );
        assert_eq!(*lock(&v1.hello_mark), None);
    }

    #[test]
    fn a_loss_makes_the_hello_stale_at_once_and_a_gain_after_two_checks() {
        use crate::vm::{Mark, Streak};
        let streak = |mark, checks| Streak { mark, checks };
        assert!(hello_is_stale(Mark::Yes, streak(Mark::No, 1)));
        assert!(!hello_is_stale(Mark::No, streak(Mark::Yes, 1)));
        assert!(hello_is_stale(Mark::No, streak(Mark::Yes, 2)));
        assert!(hello_is_stale(Mark::No, streak(Mark::Yes, 7)));
        for mark in [Mark::Yes, Mark::No] {
            for checks in [0, 1, 2] {
                assert!(!hello_is_stale(mark, streak(mark, checks)));
            }
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_change_of_the_nodes_mark_asks_for_a_new_hello_once_per_interval_at_most() {
        use crate::vm::Mark::{No, Yes};
        const EVERY: Duration = Duration::from_secs(1);
        // Lets the task see what happened: far less than the interval.
        async fn settle() {
            tokio::time::sleep(Duration::from_millis(150)).await;
        }
        // The connection's part: it ends, and the next one sends a new hello.
        async fn reconnect(host: &Host) {
            host.on_disconnect();
            host.hello().await;
            welcome(host);
            settle().await;
        }
        let dir = tempfile::tempdir().unwrap();
        let host = node_host(dir.path(), 2, EVERY);
        host.hello().await;
        let stop = CancellationToken::new();
        let task = tokio::spawn(tell_hardening(host.clone(), stop.clone()));
        settle().await;
        assert_eq!(host.rehellos(), 0);

        // A gain: not after one check, nor after a check that cannot tell, but after the
        // second `yes` in a row.
        note(&host, Some(Yes));
        settle().await;
        note(&host, None);
        settle().await;
        assert_eq!(host.rehellos(), 0);
        note(&host, Some(Yes));
        settle().await;
        assert_eq!(host.rehellos(), 1);
        reconnect(&host).await;
        assert_eq!(*lock(&host.hello_mark), Some(Yes));
        // More checks that say `yes`, or cannot tell: nothing.
        note(&host, Some(Yes));
        note(&host, None);
        settle().await;
        assert_eq!(host.rehellos(), 1);

        // A loss: at once once the interval since the last ask is over, here at its end.
        note(&host, Some(No));
        settle().await;
        assert_eq!(host.rehellos(), 1);
        tokio::time::sleep(EVERY).await;
        settle().await;
        assert_eq!(host.rehellos(), 2);
        reconnect(&host).await;
        assert_eq!(*lock(&host.hello_mark), Some(No));
        // A loss a whole interval after the last ask: at once.
        tokio::time::sleep(EVERY).await;
        note(&host, Some(Yes));
        note(&host, Some(Yes));
        settle().await;
        assert_eq!(host.rehellos(), 3);
        reconnect(&host).await;
        note(&host, Some(No));
        settle().await;
        assert_eq!(host.rehellos(), 3, "within the interval of the last ask");
        tokio::time::sleep(EVERY).await;
        settle().await;
        assert_eq!(host.rehellos(), 4);
        reconnect(&host).await;
        tokio::time::sleep(EVERY).await;

        // A mark that flaps faster than the interval, while no reconnect answers the asks: at
        // most one ask per interval, the first at once.
        let before = host.rehellos();
        for step in 0..39 {
            if step % 2 == 0 {
                note(&host, Some(Yes));
                note(&host, Some(Yes));
            } else {
                note(&host, Some(No));
            }
            tokio::time::sleep(EVERY / 10).await;
        }
        let asked = host.rehellos() - before;
        assert!((1..=4).contains(&asked), "{asked} asks in 3.9 intervals");
        reconnect(&host).await;
        note(&host, Some(No));
        reconnect(&host).await;
        tokio::time::sleep(EVERY).await;

        // While the connection is down: nothing; the next hello carries the value.
        let before = host.rehellos();
        host.on_disconnect();
        note(&host, Some(Yes));
        note(&host, Some(Yes));
        tokio::time::sleep(EVERY * 2).await;
        assert_eq!(host.rehellos(), before);
        host.hello().await;
        welcome(&host);
        settle().await;
        assert_eq!(host.rehellos(), before);
        assert_eq!(*lock(&host.hello_mark), Some(Yes));

        stop.cancel();
        task.await.unwrap();
    }

    #[tokio::test]
    async fn only_a_host_that_reports_the_nodes_mark_asks_for_a_new_hello() {
        let stop = CancellationToken::new();
        let ends = |host: Host| {
            let stop = stop.clone();
            async move {
                tokio::time::timeout(Duration::from_secs(5), tell_hardening(host, stop))
                    .await
                    .is_ok()
            }
        };
        // Protocol 1 carries no mark.
        let dir = tempfile::tempdir().unwrap();
        assert!(ends(node_host(dir.path(), 1, Duration::from_secs(1))).await);
        // An operator's own driver: its operator's statement stands.
        let dir = tempfile::tempdir().unwrap();
        let mut opts = HostOptions::new(
            Paths::new(dir.path().join("config"), dir.path().join("data")),
            Executor::Vm(crate::vm::Vms::new(crate::vm::VmOptions::new(
                crate::vm::DriverCommand::parse("/usr/local/bin/my-driver").unwrap(),
                "sandbox".into(),
            ))),
        );
        opts.sandbox = crate::sandbox::SandboxSettings::for_vms(None, false, Vec::new()).unwrap();
        let mut config = crate::config::HostConfig::new(
            "http://127.0.0.1:9",
            tinfirm_protocol::Secret::new("tfr_TESTONLYhosttokenforthehardeningmark00002"),
        );
        config.protocol_version = 2;
        assert!(ends(Shared::new(opts, config)).await);
    }

    #[test]
    fn lists_run_skills_only_with_docker_and_v2() {
        let local = Executor::Local {
            program: "false".into(),
            args: Vec::new(),
        };
        // Never contacted: the features depend on the kind of executor only.
        let docker = Executor::Docker(
            bollard::Docker::connect_with_http(
                "http://127.0.0.1:9",
                1,
                bollard::API_DEFAULT_VERSION,
            )
            .unwrap(),
        );
        assert_eq!(
            features(true, &docker, false),
            [
                "run.steer",
                "run.skills",
                "session.bundle",
                "run.git.revoke",
                "run.git.refresh"
            ]
        );
        // The local test executor has no mounts.
        assert_eq!(
            features(true, &local, false),
            [
                "run.steer",
                "session.bundle",
                "run.git.revoke",
                "run.git.refresh"
            ]
        );
        assert!(features(false, &docker, false).is_empty());
        assert!(features(false, &local, false).is_empty());
        // A bridged host says so (ADR-0095).
        assert_eq!(
            features(true, &docker, true),
            [
                "run.steer",
                "run.skills",
                "session.bundle",
                "run.git.revoke",
                "run.git.refresh",
                "sandbox.bridge"
            ]
        );
        // A VM driver of this binary's paid ones says so, for the server's licence (ADR-0114);
        // an operator's own driver does not.
        let vm = |value: &str| {
            let driver = crate::vm::DriverCommand::parse(value).unwrap();
            Executor::Vm(crate::vm::Vms::new(crate::vm::VmOptions::new(
                driver,
                "sandbox".into(),
            )))
        };
        assert_eq!(
            features(true, &vm("digitalocean"), true),
            [
                "run.steer",
                "run.skills",
                "session.bundle",
                "run.git.revoke",
                "run.git.refresh",
                "sandbox.bridge",
                "sandbox.elastic",
                "host.health"
            ]
        );
        // A VM per run reports its driver's state (ADR-0117); Docker reports none.
        assert_eq!(
            features(true, &vm("/usr/local/bin/my-driver --region x"), true),
            [
                "run.steer",
                "run.skills",
                "session.bundle",
                "run.git.revoke",
                "run.git.refresh",
                "sandbox.bridge",
                "host.health"
            ]
        );

        let dir = tempfile::tempdir().unwrap();
        let paths = Paths::new(dir.path().join("config"), dir.path().join("data"));
        let config = crate::config::HostConfig::new(
            "http://127.0.0.1:9",
            tinfirm_protocol::Secret::new("tfr_TESTONLYhosttokenforthefeaturetest0000123"),
        );
        assert_eq!(config.protocol_version, 2);
        let host = Shared::new(HostOptions::new(paths.clone(), docker), config.clone());
        assert!(host.takes_skills());
        assert_eq!(host.skills.dir(), paths.skills_dir());
        let host = Shared::new(HostOptions::new(paths, local), config);
        assert!(!host.takes_skills());
    }

    #[test]
    fn parses_the_inactivity_timeout() {
        assert_eq!(parse_inactivity_timeout(None), Duration::from_secs(1200));
        assert_eq!(
            parse_inactivity_timeout(Some("")),
            Duration::from_secs(1200)
        );
        assert_eq!(
            parse_inactivity_timeout(Some(" 90 ")),
            Duration::from_secs(90)
        );
        assert_eq!(
            parse_inactivity_timeout(Some("0")),
            Duration::from_secs(1200)
        );
        assert_eq!(
            parse_inactivity_timeout(Some("PT5M")),
            Duration::from_secs(1200)
        );
    }

    #[test]
    fn parses_the_finished_retention() {
        assert_eq!(parse_finished_retention(None), Duration::from_secs(180));
        assert_eq!(
            parse_finished_retention(Some(" 300 ")),
            Duration::from_secs(300)
        );
        assert_eq!(
            parse_finished_retention(Some("0")),
            Duration::from_secs(180)
        );
        assert_eq!(
            parse_finished_retention(Some("3m")),
            Duration::from_secs(180)
        );
    }

    #[test]
    fn a_token_that_expires_names_its_credentials_mirror() {
        let url = "https://github.com/acme/w.git";
        let with = |expires: bool, key: Option<&str>| {
            let mut spec = ws(url, "agent/X-1", "main");
            let mut git = tinfirm_protocol::GitCredentials::new(
                "x-access-token",
                tinfirm_protocol::Secret::new("ghs_x"),
            );
            git.expires_at = expires.then(Utc::now);
            git.mirror_key = key.map(str::to_string);
            spec.git = Some(git);
            validate_workspace(&spec, false)
        };
        let key = "3f".repeat(32);
        assert!(with(false, None).is_ok());
        assert!(with(true, Some(&key)).is_ok());
        assert!(with(true, None).is_err());
        assert!(with(false, Some(&key)).is_err());
        assert!(with(true, Some(&"3F".repeat(32))).is_err());
        assert!(with(true, Some("../x")).is_err());
        // A token longer than the host takes is refused.
        let mut spec = ws(url, "agent/X-1", "main");
        spec.git = Some(tinfirm_protocol::GitCredentials::new(
            "x-access-token",
            tinfirm_protocol::Secret::new("t".repeat(MAX_GIT_TOKEN_BYTES + 1)),
        ));
        let error = validate_workspace(&spec, false).unwrap_err();
        assert!(error.contains("git.token is over 16384 bytes"), "{error}");
    }

    #[test]
    fn validates_workspaces() {
        assert!(
            validate_workspace(
                &ws("https://github.com/o/r.git", "agent/MYCO-12", "main"),
                false
            )
            .is_ok()
        );
        assert!(validate_workspace(&ws("file:///tmp/r.git", "agent/X-1", "main"), false).is_err());
        assert!(validate_workspace(&ws("file:///tmp/r.git", "agent/X-1", "main"), true).is_ok());
        assert!(
            validate_workspace(
                &ws("ext::sh -c touch% /tmp/pwned", "agent/X-1", "main"),
                true
            )
            .is_err()
        );
        assert!(
            validate_workspace(
                &ws("https://u:p@github.com/o/r.git", "agent/X-1", "main"),
                false
            )
            .is_err()
        );
        assert!(
            validate_workspace(&ws("https://github.com/o/r.git", "main", "main"), false).is_err()
        );
        assert!(
            validate_workspace(
                &ws("https://github.com/o/r.git", "agent/../main", "main"),
                false
            )
            .is_err()
        );
        assert!(
            validate_workspace(
                &ws("https://github.com/o/r.git", "agent/X-1", "--upload-pack=x"),
                false
            )
            .is_err()
        );
        assert!(
            validate_workspace(
                &ws("https://github.com/o/r.git", "agent/X 1", "main"),
                false
            )
            .is_err()
        );
    }
}
