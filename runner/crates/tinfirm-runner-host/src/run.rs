//! One run, end to end (docs/archive/v1/CONVENTIONS.md §15.4 steps 2–10).
//!
//! The run's workspace is `<data>/workspaces/<runId>`, mounted at `/workspace`, the agent's
//! working directory. Each repository of `run.assign.workspaces` is cloned into
//! `/workspace/<dir>` from the host's mirror of it ([`crate::mirror`]), gets its own git proxy
//! route, and is finalized on its own: WIP commit, credential scan, and a push of the job's
//! branch when it is ahead of the default branch. A credential in any repository (or in the
//! agent's output) means no repository is pushed; a failed push does not stop the others.
//!
//! Under protocol v2 the run also gets `/output` (`outputs/<runId>`, writable, empty at the
//! start, outside the workspace and anchored before the sandbox starts): after the agent's
//! turn and before the workspace is cleaned up, the files there are saved as artifacts of the
//! job ([`crate::output`]) and listed in `run.finished.artifacts`.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use chrono::Utc;
use tinfirm_protocol::{
    ArtifactResult, CredentialKind, ErrorPayload, EventPayload, FailureClass, GitAction,
    GitPayload, GitResult, LogLevel, LogPayload, ModelAccess, ModelFormat, Outcome, Phase,
    RateLimitPayload, RateLimitStatus, ResumeOutcome, RunAssign, RunEvent, RunFinished, RunResult,
    Runtime, StatusPayload, WORKSPACE_ROOT, WorkspaceSpec,
};
use tokio::io::AsyncWriteExt;
use tokio::sync::{Semaphore, mpsc, watch};
use uuid::Uuid;

use crate::acp::{
    self, AcpControl, AcpInbound, AcpLine, AcpParams, AcpResult, AcpTransport, NotResumed,
    ThoughtLevel,
};
use crate::attachments::{self, AttachmentsDir, Upload, UploadSource};
use crate::credentials::Credential;
use crate::docker;
use crate::egress_proxy::{EgressGuard, Rule};
use crate::git::{self, Git, branch_tip, ref_shas};
use crate::git_proxy::{GitProxy, McpGuard, RouteGuard};
use crate::host::{Shared, StopRequest};
use crate::limits::{self, AgentError, DEFAULT_COOLDOWN_MINUTES};
use crate::mapper::{EventMapper, Signal};
use crate::mcp::{FileRoot, RunTools, SandboxReader};
use crate::model_proxy::ModelProxyGuard;
use crate::output::{self, OutputFile, OutputRoot};
use crate::runtime_env::{Access, ProviderKey, effort_in_env, runtime_env};
use crate::sandbox::{Bind, SandboxIo, SandboxOutput, SandboxSettings, SandboxSpec};
use crate::sandbox_files::{self, Layout};
use crate::session_bundles;
use crate::skills::{self, ProvidedSkill};
use crate::steer::Steering;
use crate::takeback::{RecordHandle, RepoRecord, RunRecord};
use crate::usage::{UsageMode, prompt_usage};
use crate::util::{
    EVENT_PAYLOAD_MAX, LOG_MESSAGE_MAX, SUMMARY_MAX, shrink_json, truncate_with_marker,
    truncation_marker,
};

/// Container stderr lines forwarded as LOG events per run (the rest is dropped).
const MAX_STDERR_EVENTS: usize = 1000;
/// The last stderr lines kept for the failure message of an agent that died.
const STDERR_TAIL: usize = 20;
/// Longest line of the agent's stdout (one ACP message) the host takes. A longer one ends the
/// run as an agent that stopped speaking ACP: the host would otherwise hold it whole, and no
/// ACP message of a working agent comes near it (a tool result is capped far below). Complete
/// lines wait for the run's budget of lines in flight ([`acp::ACP_INBOUND_BYTES`], twice this).
pub(crate) const MAX_ACP_LINE: usize = 8 * 1024 * 1024;
/// Longest line of the agent's stderr the host keeps; the rest of a longer line is dropped,
/// and the line ends with a marker. A LOG event holds far less ([`LOG_MESSAGE_MAX`]).
pub(crate) const MAX_LOG_LINE: usize = 64 * 1024;

/// What a run uses to reach its model (docs/archive/v1/CONVENTIONS.md §15.4 step 1).
#[derive(Debug, Clone)]
pub enum RunCredential {
    /// A credential of this host (`run.assign.credentialLabel`).
    Host(Credential),
    /// The model access of a company model credential (`run.assign.modelAccess`, ADR-0027):
    /// no credential of this host. A gateway's key stays on the host, where the run's model proxy
    /// holds it (always on: crate::model_proxy); a provider's own key reaches the sandbox
    /// environment. The server, not the host, cools the credential down after a usage limit.
    Model(ModelAccess),
}

impl RunCredential {
    /// The label of the host credential; `None` for model access.
    pub fn host_label(&self) -> Option<&str> {
        match self {
            RunCredential::Host(credential) => Some(&credential.label),
            RunCredential::Model(_) => None,
        }
    }

    /// What the runtime reaches its model with (crate::runtime_env).
    pub fn access(&self) -> Access<'_> {
        match self {
            RunCredential::Host(credential) => {
                let secret = credential.secret.as_ref().map(|s| s.expose()).unwrap_or("");
                let provider = |format| {
                    Access::Provider(ProviderKey {
                        format,
                        base_url: None,
                        region: None,
                        access_key_id: None,
                        key: secret,
                        proxied: false,
                    })
                };
                match credential.kind {
                    CredentialKind::AnthropicApiKey => provider(ModelFormat::Anthropic),
                    CredentialKind::OpenaiApiKey => provider(ModelFormat::Openai),
                    kind @ (CredentialKind::ClaudeLogin | CredentialKind::CodexChatgptAuth) => {
                        Access::Subscription { kind, secret }
                    }
                }
            }
            RunCredential::Model(access) => Access::Provider(ProviderKey {
                format: access.format,
                base_url: access.base_url.as_deref(),
                region: access.region.as_deref(),
                access_key_id: access.access_key_id.as_deref(),
                key: access.api_key.expose(),
                proxied: false,
            }),
        }
    }

    /// Values only this run knows, which its redaction must cover from the start: the key of
    /// model access. Host credentials are in the redactor of every run already.
    fn run_secrets(&self) -> Vec<String> {
        match self {
            RunCredential::Host(_) => Vec::new(),
            RunCredential::Model(access) => vec![access.api_key.expose().to_string()],
        }
    }
}

/// Assigns `seq`, redacts every credential value, and queues events for the server.
pub struct EventSink {
    shared: Arc<Shared>,
    run_id: Uuid,
    epoch: i64,
    seq: u64,
    /// The run's record while its sandbox lives (ADR-0101): it reserves the event numbers.
    record: Option<Arc<RecordHandle>>,
}

impl EventSink {
    pub fn new(shared: Arc<Shared>, run_id: Uuid, epoch: i64) -> EventSink {
        EventSink::after(shared, run_id, epoch, 0)
    }

    /// A sink whose first event is `seq + 1`: a run that this host took back after a restart
    /// numbers its events after the earlier process's.
    pub fn after(shared: Arc<Shared>, run_id: Uuid, epoch: i64, seq: u64) -> EventSink {
        EventSink {
            shared,
            run_id,
            epoch,
            seq,
            record: None,
        }
    }

    /// Keeps the run's record up to date from now on.
    pub fn keep_record(&mut self, record: Arc<RecordHandle>) {
        self.record = Some(record);
    }

    /// The sandbox is gone: its record too.
    pub fn drop_record(&mut self) {
        if let Some(record) = self.record.take() {
            record.remove();
        }
    }

    /// The agent's session is open.
    pub fn session_opened(&self, session_id: &str) {
        if let Some(record) = &self.record {
            record.session_opened(session_id);
        }
    }

    /// Queues one event. Returns `true` when a credential value had to be redacted.
    pub fn emit(&mut self, payload: EventPayload) -> bool {
        // v1 has no `cacheWriteTokens`: its USAGE counts the cache writes as input.
        let payload = match payload {
            EventPayload::Usage(usage) if !self.shared.speaks_v2() => {
                EventPayload::Usage(usage.into_v1())
            }
            payload => payload,
        };
        let redactor = self.shared.redactor();
        let event_type = payload.event_type();
        let mut value = serde_json::to_value(&payload).unwrap_or(serde_json::Value::Null);
        let redacted = redactor.redact_json(&mut value);
        // Masked values (git proxy and MCP secrets) are replaced too, but are no leak.
        let hit = redacted.credential;
        let size = serde_json::to_string(&value).map(|s| s.len()).unwrap_or(0);
        let oversized = size > EVENT_PAYLOAD_MAX;
        let fits = !oversized || shrink_json(&mut value, EVENT_PAYLOAD_MAX);
        let payload = if !fits {
            EventPayload::Log(LogPayload {
                level: LogLevel::Warn,
                message: format!("a {event_type} event of {size} bytes was too large to send"),
            })
        } else if redacted.any() || oversized {
            serde_json::from_value::<EventPayload>(value).unwrap_or_else(|_| {
                EventPayload::Error(ErrorPayload {
                    message: "an event could not be sanitized and was dropped".into(),
                    failure_class: hit.then_some(FailureClass::CredentialLeak),
                })
            })
        } else {
            payload
        };
        self.seq += 1;
        if let Some(record) = &self.record {
            record.numbered(self.seq);
        }
        self.shared.outbox.push_event(RunEvent {
            run_id: self.run_id,
            epoch: self.epoch,
            seq: self.seq,
            at: Utc::now(),
            event: payload,
        });
        hit
    }

    pub fn status(&mut self, phase: Phase, message: Option<String>) {
        self.emit(EventPayload::Status(StatusPayload { phase, message }));
    }

    /// A LOG event: redacted first, then cut to [`LOG_MESSAGE_MAX`], so that the cut never
    /// leaves a part of a credential that redaction would no longer match (L26).
    pub fn log(&mut self, level: LogLevel, message: impl Into<String>) {
        let message = truncate_with_marker(&self.redact(&message.into()), LOG_MESSAGE_MAX);
        self.emit(EventPayload::Log(LogPayload { level, message }));
    }

    /// A GIT event of one repository.
    pub fn git(
        &mut self,
        ws: &WorkspaceSpec,
        action: GitAction,
        head_sha: Option<String>,
        commits: Option<u64>,
    ) {
        self.emit(EventPayload::Git(GitPayload {
            component_id: Some(ws.component_id),
            dir: Some(ws.dir.clone()),
            action,
            branch: ws.branch.clone(),
            head_sha,
            commits,
        }));
    }

    pub fn last_seq(&self) -> u64 {
        self.seq
    }

    fn redact(&self, text: &str) -> String {
        self.shared.redactor().redact(text).into_owned()
    }
}

/// Why the agent was stopped before it ended its turn.
#[derive(Debug, Clone, PartialEq)]
enum StopCause {
    Cancel,
    Abort,
    Guard(String),
    /// The wall-clock guard: `TIME_LIMIT` under protocol v2, which a retry goes on from;
    /// `GUARD_STOPPED` under v1.
    TimeLimit(String),
    Leak,
    UsageLimit {
        resets_at: Option<chrono::DateTime<Utc>>,
        window: Option<String>,
    },
}

/// The first failure of a run wins.
#[derive(Debug, Default)]
struct Failure(Option<(FailureClass, String)>);

impl Failure {
    fn set(&mut self, class: FailureClass, message: impl Into<String>) {
        if self.0.is_none() {
            self.0 = Some((class, message.into()));
        }
    }

    fn force(&mut self, class: FailureClass, message: impl Into<String>) {
        self.0 = Some((class, message.into()));
    }
}

struct AgentPhase {
    session_id: Option<String>,
    /// Set once the session is open: `None` inside when `session/load` succeeded.
    not_resumed: Option<Option<NotResumed>>,
    stop: Option<StopCause>,
    result: Option<AcpResult>,
    summary: Option<String>,
    usage: Option<tinfirm_protocol::UsagePayload>,
    stderr_tail: Vec<String>,
}

/// One cloned repository of the run.
struct Repo<'a> {
    ws: &'a WorkspaceSpec,
    git: Git,
    /// HEAD and the ahead count right after the clone.
    cloned_head: String,
    cloned_commits: Option<u64>,
    /// With the sandbox bridge (ADR-0095): how the repository came back from the sandbox, or
    /// why it did not.
    collected: Option<Result<sandbox_files::Collected, String>>,
}

impl Repo<'_> {
    /// `name` (`/workspace/<dir>`), for messages.
    fn label(&self) -> String {
        repo_label(self.ws)
    }

    /// The result of a repository that the host did not finalize.
    fn unfinalized_result(&self) -> GitResult {
        GitResult {
            component_id: self.ws.component_id,
            branch: self.ws.branch.clone(),
            pushed: false,
            head_sha: Some(self.cloned_head.clone()),
            commits: self.cloned_commits,
        }
    }
}

fn repo_label(ws: &WorkspaceSpec) -> String {
    format!("`{}` ({WORKSPACE_ROOT}/{})", ws.name, ws.dir)
}

/// Executes a run. Returns `None` when the run was aborted (stale epoch) and must not
/// report anything.
pub async fn execute(
    shared: &Arc<Shared>,
    assign: &RunAssign,
    credential: &RunCredential,
    mut stop_rx: watch::Receiver<Option<StopRequest>>,
    steering: &Arc<Steering>,
) -> Option<RunFinished> {
    let started = Instant::now();
    let paths = shared.opts.paths.clone();
    let mut sink = EventSink::new(shared.clone(), assign.run_id, assign.epoch);
    let mut failure = Failure::default();
    let mut cancelled = false;
    sink.status(Phase::Preparing, None);

    let mut wall_clock = Duration::from_secs(assign.limits.max_wall_clock_seconds);
    if let Some(cap) = shared.opts.max_wall_clock_cap {
        wall_clock = wall_clock.min(cap);
    }
    let deadline = started + wall_clock;

    // --- directories ------------------------------------------------------------------
    let workspace_dir = paths.workspace(assign.run_id);
    // Held until the run ends, so maintenance never deletes the home under it.
    let home_lease = shared.homes.lease(assign.agent.id, assign.work_item().id);
    let home_dir = home_lease.path().to_path_buf();
    // A new home holds no earlier session: a failed session/load there is NO_SESSION.
    let mut new_home = !home_dir.exists();
    // Protocol v2: the documents folder, anchored here and saved as artifacts when the run
    // ends (crate::output).
    let output_root = match prepare_dirs(
        &workspace_dir,
        &home_dir,
        shared
            .speaks_v2()
            .then(|| paths.output(assign.run_id))
            .as_deref(),
        assign.agent.runtime,
    ) {
        Ok(output_root) => output_root,
        Err(e) => {
            failure.set(
                FailureClass::Infra,
                format!("cannot prepare the workspace: {e:#}"),
            );
            return Some(finish(&mut sink, assign, failure, false, Report::default()));
        }
    };
    let output_dir: Option<PathBuf> = output_root.as_ref().map(|root| root.path().to_path_buf());

    // --- git: mirror and clone, per repository ------------------------------------------
    // Every repository's token, and the key of model access, is redacted from the first git
    // command on.
    let mut secrets = credential.run_secrets();
    for ws in &assign.workspaces {
        if let Some(creds) = &ws.git {
            secrets.push(creds.token.expose().to_string());
        }
        if let Some(authorization) = git::authorization_for(ws) {
            secrets.push(authorization.expose().to_string());
        }
    }
    if !secrets.is_empty() {
        shared.add_run_secrets(assign.run_id, assign.epoch, secrets);
    }
    let mut repos: Vec<Repo> = Vec::new();
    if !assign.workspaces.is_empty() {
        sink.status(Phase::Cloning, None);
        let cloned = tokio::select! {
            result = clone_all(shared, &mut sink, assign, &workspace_dir, &mut repos) => Some(result),
            _ = wait_for_stop(&mut stop_rx) => None,
        };
        if let Some(Err(message)) = &cloned {
            failure.set(FailureClass::GitError, message.clone());
        }
        if !matches!(cloned, Some(Ok(()))) {
            if *stop_rx.borrow() == Some(StopRequest::Abort) {
                cleanup_workspace(shared, &workspace_dir, false);
                return None;
            }
            let was_cancelled = failure.0.is_none();
            // The repositories cloned so far, unchanged; none cloned is no git result.
            let report = Report {
                git: (!repos.is_empty())
                    .then(|| repos.iter().map(Repo::unfinalized_result).collect()),
                ..Report::default()
            };
            let finished = finish(&mut sink, assign, failure, was_cancelled, report);
            cleanup_workspace(shared, &workspace_dir, finished.outcome == Outcome::Failed);
            return Some(finished);
        }
    }

    // --- attachments (ADR-0014) ---------------------------------------------------------
    // Removed when this function returns, whichever way.
    let mut attachments_dir: Option<AttachmentsDir> = None;
    if !assign.attachments.is_empty() && stop_rx.borrow().is_none() && failure.0.is_none() {
        match AttachmentsDir::create(paths.attachments(assign.run_id)) {
            Ok(dir) => {
                let downloaded = tokio::select! {
                    result = attachments::download_all(
                        &shared.config.server_url,
                        shared.config.token.expose(),
                        assign,
                        dir.path(),
                    ) => Some(result),
                    _ = wait_for_stop(&mut stop_rx) => None,
                };
                match downloaded {
                    Some(Ok(downloaded)) => {
                        sink.log(
                            LogLevel::Info,
                            format!(
                                "{} attachment(s) in {}",
                                downloaded.stored.len(),
                                attachments::CONTAINER_DIR
                            ),
                        );
                        if !downloaded.skipped.is_empty() {
                            sink.log(
                                LogLevel::Warn,
                                format!(
                                    "attachments deleted before the run started: {}",
                                    downloaded.skipped.join(", ")
                                ),
                            );
                        }
                    }
                    Some(Err(e)) => {
                        let message = sink.redact(&format!("{e:#}"));
                        failure.set(FailureClass::Infra, message);
                    }
                    None => {}
                }
                attachments_dir = Some(dir);
            }
            Err(e) => failure.set(
                FailureClass::Infra,
                format!("cannot prepare the attachments: {e:#}"),
            ),
        }
    }

    // --- session bundle (ADR-0097) -------------------------------------------------------
    // The conversation's session from the server, when another host ran its last turn.
    if let Some((sha256, size)) = assign
        .session_bundle
        .as_ref()
        .and_then(|bundle| bundle.restore())
        && stop_rx.borrow().is_none()
        && failure.0.is_none()
        && restore_session_bundle(shared, &mut sink, assign, &home_dir, sha256, size).await
    {
        new_home = false;
    }

    // --- skills (ADR-0064) --------------------------------------------------------------
    // The empty mount points that the previous run of this home got go first: a skill the
    // agent no longer has leaves no empty folder for its runtime to list.
    skills::remove_earlier_mount_points(&home_dir);
    // Held until this function returns, so maintenance never deletes a skill a run mounts.
    let mut run_skills: Vec<ProvidedSkill> = Vec::new();
    if !assign.skills.is_empty() && stop_rx.borrow().is_none() && failure.0.is_none() {
        let provided = tokio::select! {
            result = skills::provide(
                &shared.skills,
                &shared.config.server_url,
                shared.config.token.expose(),
                assign,
            ) => Some(result),
            _ = wait_for_stop(&mut stop_rx) => None,
        };
        match provided {
            Some(Ok(provided)) => {
                let names: Vec<&str> = provided.iter().map(|s| s.name.as_str()).collect();
                match skills::create_mount_points(&home_dir, assign.agent.runtime, &names) {
                    Ok(()) => {
                        sink.log(
                            LogLevel::Info,
                            skills::summary(assign.agent.runtime, &provided),
                        );
                        run_skills = provided;
                    }
                    Err(message) => failure.set(FailureClass::Infra, sink.redact(&message)),
                }
            }
            Some(Err(message)) => failure.set(FailureClass::Infra, sink.redact(&message)),
            None => {}
        }
    }

    // --- a Claude login (ADR-0085) ------------------------------------------------------
    // Its credentials file is mounted at a plain file of the home, which the host makes.
    if let RunCredential::Host(host_credential) = credential
        && host_credential.kind == CredentialKind::ClaudeLogin
        && failure.0.is_none()
        && let Err(message) = crate::credentials::claude_login_mount_point(&home_dir)
    {
        failure.set(FailureClass::Infra, sink.redact(&message));
    }

    // --- a Codex login's file as the run starts (ADR-0085, decision 5) ------------------
    // Compared again when the run loses a renewal race: unchanged, nothing on this host
    // renewed the login, so it is stale (classify_agent_end).
    let codex_auth_at_start = match credential {
        RunCredential::Host(c) if c.kind == CredentialKind::CodexChatgptAuth => {
            crate::credentials::codex_auth_stamp(&shared.credentials.codex_home(&c.label))
        }
        _ => None,
    };

    // --- sandbox ------------------------------------------------------------------------
    // Where the sandbox's directories are: a container's; with the local test executor, which
    // has no mounts, the host's own; with the bridge (ADR-0095), whose sandbox gets no mounts,
    // a directory of its own that stands for the sandbox's (crate::sandbox_files).
    let bridged = shared.opts.bridge.is_some();
    let layout = match (&shared.opts.executor, bridged) {
        (crate::sandbox::Executor::Docker(_), _) => Layout::container(),
        (crate::sandbox::Executor::Vm(vms), _) if !vms.options.on_this_machine => {
            Layout::container()
        }
        (crate::sandbox::Executor::Vm(_), _) | (crate::sandbox::Executor::Local { .. }, true) => {
            Layout::local(&workspace_dir)
        }
        (crate::sandbox::Executor::Local { .. }, false) => Layout::host(
            &workspace_dir,
            &home_dir,
            &paths.attachments(assign.run_id),
            &paths.output(assign.run_id),
        ),
    };
    // Where `upload_attachment` reads files (ADR-0021); with the bridge, from the sandbox,
    // once it is there.
    let sandbox_reader = SandboxReader::default();
    let reader = bridged.then(|| sandbox_reader.clone());
    let roots = vec![
        FileRoot {
            container: layout.workspace.clone(),
            host: workspace_dir.clone(),
            sandbox: reader.clone(),
        },
        FileRoot {
            container: layout.home.clone(),
            host: home_dir.clone(),
            sandbox: reader,
        },
    ];
    // A VM per run (ADR-0096) comes from the VM pool (crate::pool, ADR-0098): a ready VM of
    // the run's shape, or the next one that comes up. The VM's tunnels and its egress proxy
    // listener are its own, so the run takes it before it sets up its listeners.
    let mut claimed: Option<crate::pool::Claimed> = None;
    let mut starting = false;
    if let Some(pool) = shared.pool()
        && stop_rx.borrow().is_none()
        && failure.0.is_none()
    {
        sink.status(Phase::Starting, None);
        starting = true;
        let shape = crate::pool::Shape::for_runtime(&shared.opts, assign.agent.runtime);
        let deadline = tokio::time::Instant::now() + pool.start_timeout();
        let got = tokio::select! {
            got = pool.claim(shared, shape, deadline) => Some(got),
            _ = wait_for_stop(&mut stop_rx) => None,
        };
        match got {
            Some(Ok(vm)) => {
                sink.log(
                    LogLevel::Info,
                    match vm.served {
                        crate::pool::Served::Ready => format!(
                            "the run took a ready sandbox VM ({}), which waited {} s for it",
                            vm.vm.id,
                            vm.ready_at.elapsed().as_secs()
                        ),
                        crate::pool::Served::Cold => format!(
                            "the run started cold: no sandbox VM was ready, and it waited {} s for {}",
                            vm.waited.as_secs(),
                            vm.vm.id
                        ),
                    },
                );
                claimed = Some(vm);
            }
            Some(Err(e)) => failure.set(
                FailureClass::Infra,
                sink.redact(&format!("cannot start the sandbox: {e:#}")),
            ),
            None => {}
        }
    }
    // A local test executor's sandbox over the bridge shares this machine's network: it
    // reaches the listeners on a loopback address of its own (crate::bridge); a VM of the VM
    // executor's test mode has one of its own.
    let sandbox_host = match &claimed {
        Some(vm) => vm.sandbox_host.clone(),
        None => (bridged && shared.opts.executor.on_this_machine())
            .then(|| crate::bridge::local_sandbox_loopback().to_string()),
    };
    // The sandbox's way to fetch and to the platform tools; dropping it (at the latest when
    // this function returns) removes the run's routes from the listener.
    let mut access = sandbox_access(shared, &mut sink, assign, &repos, roots, sandbox_host).await;
    let mut phase = None;
    if stop_rx.borrow().is_none() && failure.0.is_none() {
        if !starting {
            sink.status(Phase::Starting, None);
        }
        // A gateway's key stays on the host (the model proxy is always on): the runtime gets the
        // run's model proxy as its base URL and a dummy key.
        let model_proxy = start_model_proxy(
            shared,
            &mut sink,
            assign,
            credential,
            access.sandbox_host.as_deref(),
        )
        .await;
        let proxy_url = match &model_proxy {
            Ok(Some(guard)) => Some(guard.base_url().to_string()),
            _ => None,
        };
        let model = match &proxy_url {
            Some(url) => credential.access().through_model_proxy(url),
            None => credential.access(),
        };
        let mut spec = sandbox_spec(
            shared,
            assign,
            credential,
            model,
            &layout,
            &workspace_dir,
            &home_dir,
            attachments_dir.as_ref().map(AttachmentsDir::path),
            output_dir.as_deref(),
            &run_skills,
        );
        spec.extra_hosts = access.extra_hosts.clone();
        // With the bridge, the run's directories go into the sandbox in place of the mounts,
        // and the same paths of the home come back (crate::sandbox_files).
        let skill_dirs: Vec<String> = run_skills
            .iter()
            .map(|skill| format!("{}/{}", skills::mount_dir(assign.agent.runtime), skill.name))
            .collect();
        let home_exclude =
            sandbox_files::home_exclude(&skill_dirs, mounted_login(shared, credential));
        let puts = if bridged {
            sandbox_files::puts(
                &layout,
                &workspace_dir,
                &home_dir,
                home_exclude.clone(),
                run_skills
                    .iter()
                    .map(|skill| {
                        (
                            skill.path.clone(),
                            format!(
                                "{}/{}/{}",
                                layout.home,
                                skills::mount_dir(assign.agent.runtime),
                                skill.name
                            ),
                        )
                    })
                    .collect(),
                attachments_dir.as_ref().map(AttachmentsDir::path),
                output_dir.as_deref(),
            )
        } else {
            Vec::new()
        };
        // A proxy that cannot start is a run that cannot start. An egress proxy that is not there
        // leaves the sandbox on a network with no other way out, so it could not reach its model;
        // a model proxy that is not there would leave a gateway's key to the sandbox, which no
        // host may do (ADR-0059). The spec built above is then never started.
        let started = match model_proxy {
            Ok(model_proxy) => {
                access.model_proxy = model_proxy;
                let host = access.sandbox_host.clone();
                let slot = claimed.as_mut().and_then(|vm| vm.egress.take());
                match start_egress(
                    shared,
                    &mut sink,
                    assign,
                    model,
                    &mut spec,
                    host.as_deref(),
                    slot,
                )
                .await
                {
                    Ok(egress) => {
                        access.egress = egress;
                        start_sandbox(
                            shared,
                            &mut sink,
                            assign,
                            &mut spec,
                            &access,
                            puts,
                            claimed.take(),
                        )
                        .await
                    }
                    Err(error) => Err(anyhow::anyhow!(error)),
                }
            }
            Err(error) => Err(anyhow::anyhow!(error)),
        };
        match started {
            Ok((mut sandbox, io)) => {
                if let Some(files) = sandbox.files() {
                    sandbox_reader.set(files);
                }
                // While the sandbox lives, a host that restarts takes the run back (ADR-0101).
                if let Some((kind, bridge_id, session_key)) = sandbox.take_back_record() {
                    let record = RunRecord {
                        version: crate::takeback::RECORD_VERSION,
                        assign: assign.clone(),
                        sandbox: kind,
                        bridge_id,
                        session_key,
                        repos: repos
                            .iter()
                            .map(|repo| {
                                RepoRecord::new(
                                    &repo.ws.dir,
                                    &repo.cloned_head,
                                    repo.cloned_commits,
                                    repo.git.base_refs(),
                                    repo.git.config_snapshot(),
                                )
                            })
                            .collect(),
                        home_exclude: home_exclude.clone(),
                        session_id: None,
                        seq_floor: sink.last_seq() + crate::takeback::SEQ_BLOCK,
                    };
                    match RecordHandle::create(&shared.records, record) {
                        Ok(record) => sink.keep_record(Arc::new(record)),
                        Err(e) => sink.log(
                            LogLevel::Warn,
                            format!(
                                "cannot keep the run's record, so a restart of the host would lose the run: {e:#}"
                            ),
                        ),
                    }
                }
                sink.status(Phase::Running, None);
                let params = AcpParams {
                    cwd: shared.opts.executor.agent_cwd(&spec),
                    prompt: assign.prompt.clone(),
                    resume_session_id: assign.resume_session_id.clone(),
                    resume_prompt: assign.resume_prompt.clone(),
                    new_home,
                    client_version: shared.opts.runner_version.clone(),
                    mcp_url: access.mcp.as_ref().map(|m| m.url().to_string()),
                    session_meta: crate::runtime_env::session_meta(assign.agent.runtime),
                    steering: steering.clone(),
                    thought_level: if effort_in_env(assign.agent.runtime) {
                        ThoughtLevel::Untouched
                    } else {
                        assign
                            .agent
                            .effort
                            .map_or(ThoughtLevel::Default, ThoughtLevel::Effort)
                    },
                };
                let agent = run_agent(
                    shared,
                    assign,
                    &mut sink,
                    io,
                    params,
                    &mut stop_rx,
                    deadline,
                )
                .await;
                sandbox.stop_agent(Duration::from_secs(5)).await;
                // With the bridge, the agent's work comes back before the sandbox goes.
                if let Some(files) = sandbox.files()
                    && !matches!(agent.stop, Some(StopCause::Abort))
                {
                    collect_from_sandbox(
                        &mut sink,
                        assign,
                        &files,
                        &layout,
                        &mut repos,
                        &home_dir,
                        home_exclude,
                        output_dir.as_deref(),
                    )
                    .await;
                }
                sandbox.stop(Duration::from_secs(5)).await;
                sink.drop_record();
                phase = Some(agent);
            }
            Err(e) => {
                failure.set(
                    FailureClass::Infra,
                    sink.redact(&format!("cannot start the sandbox: {e:#}")),
                );
            }
        }
    }
    // The sandbox is gone, and with it the need to fetch or to call the platform tools. The
    // agent's last `finish_run` is the run's result (docs/archive/v1/CONVENTIONS.md §15.6).
    let result = access.tools.as_ref().and_then(|tools| tools.result());
    if let Some(egress) = &access.egress {
        let denied = egress.denied();
        if !denied.is_empty() {
            sink.log(
                LogLevel::Warn,
                format!(
                    "the egress proxy refused the sandbox's connections to: {}. If the run needs one of them, the operator adds it with `run --egress-allow HOST[:PORT]`",
                    denied.join(", ")
                ),
            );
        }
    }
    drop(access);

    // --- outcome of the agent phase -----------------------------------------------------
    let mut session_id = None;
    let mut not_resumed = None;
    let mut summary = None;
    let mut usage = None;
    let mut leak = false;
    match *stop_rx.borrow() {
        Some(StopRequest::Abort) => {
            cleanup_workspace(shared, &workspace_dir, false);
            return None;
        }
        Some(StopRequest::Cancel) => cancelled = true,
        None => {}
    }
    if let Some(agent) = phase {
        session_id = agent.session_id.clone();
        not_resumed = agent.not_resumed.clone();
        summary = agent.summary.clone();
        usage = agent.usage.clone();
        match agent.stop.clone() {
            Some(StopCause::Abort) => {
                cleanup_workspace(shared, &workspace_dir, false);
                return None;
            }
            Some(StopCause::Cancel) => cancelled = true,
            Some(StopCause::Leak) => {
                leak = true;
                failure.set(
                    FailureClass::CredentialLeak,
                    "the agent's output contained a credential value; the run was stopped and nothing was pushed",
                );
            }
            Some(StopCause::Guard(message)) => failure.set(FailureClass::GuardStopped, message),
            Some(StopCause::TimeLimit(message)) => failure.set(
                if shared.speaks_v2() {
                    FailureClass::TimeLimit
                } else {
                    FailureClass::GuardStopped
                },
                message,
            ),
            Some(StopCause::UsageLimit { resets_at, window }) => {
                usage_limit(
                    shared,
                    &mut sink,
                    &mut failure,
                    credential,
                    resets_at,
                    window,
                    None,
                );
            }
            None => {
                classify_agent_end(
                    shared,
                    &mut sink,
                    &mut failure,
                    credential,
                    &agent,
                    codex_auth_at_start,
                )
                .await
            }
        }
    }

    // --- /output (protocol v2) ----------------------------------------------------------
    // Before the git finalize and the cleanup: what the agent left in /output becomes the
    // artifacts of the job. After a leak nothing leaves the host, as with the push.
    let mut artifacts = Vec::new();
    if let Some(output_root) = &output_root {
        if leak {
            sink.log(
                LogLevel::Warn,
                "not saving the files of /output: the run leaked a credential value",
            );
        } else {
            artifacts = collect_output(shared, &mut sink, assign, output_root).await;
        }
    }

    // --- git finalize -------------------------------------------------------------------
    let mut results = Vec::new();
    if !repos.is_empty() {
        sink.status(Phase::Finalizing, None);
        let finalized = finalize_git(shared, &mut sink, &repos, assign.run_id, leak).await;
        if finalized.outgoing_leak {
            failure.force(
                FailureClass::CredentialLeak,
                "a commit contains a credential value; no repository was pushed",
            );
            cancelled = false;
        }
        if !finalized.errors.is_empty() {
            let message = git_failure_message(&finalized.errors, repos.len());
            if !cancelled {
                failure.set(FailureClass::GitError, message);
            } else {
                sink.log(LogLevel::Error, message);
            }
        }
        results = finalized.results;
    }

    // --- session bundle (ADR-0097) -------------------------------------------------------
    let session_bundle = match &assign.session_bundle {
        Some(_) => Some(
            store_session_bundle(
                shared,
                &mut sink,
                assign,
                &home_dir,
                session_id.as_deref(),
                leak,
            )
            .await,
        ),
        None => None,
    };

    let failed_git = results
        .iter()
        .any(|g| !g.pushed && g.commits.unwrap_or(0) > 0);
    let report = Report {
        summary,
        result,
        session_id,
        not_resumed,
        git: Some(results),
        usage,
        artifacts,
        session_bundle,
    };
    let finished = finish(&mut sink, assign, failure, cancelled, report);
    cleanup_workspace(
        shared,
        &workspace_dir,
        finished.outcome == Outcome::Failed || failed_git,
    );
    Some(finished)
}

/// How long a run taken back waits for its sandbox's bridge to come back.
const TAKE_BACK_WAIT: Duration = Duration::from_secs(120);
/// How long the agent of the earlier process has to stop.
const RECLAIM_TIMEOUT: Duration = Duration::from_secs(30);

/// Takes back a run of the process this host restarted from (ADR-0101): its sandbox
/// (`sandbox`, held since the host started) comes back over `registration` with the session
/// key. The agent stops, and the run ends as a run that the host stopped does: the work comes
/// back from the sandbox and is pushed, the session's bundle is stored, and the run is reported
/// failed with `INFRA`, or cancelled when the server cancels it meanwhile. Returns `None` when
/// the run was aborted (stale epoch) and must not report anything.
pub async fn take_back(
    shared: &Arc<Shared>,
    record: RunRecord,
    sandbox: crate::sandbox::TakenBack,
    registration: tinfirm_bridge::host::Registration,
    mut stop_rx: watch::Receiver<Option<StopRequest>>,
) -> Option<RunFinished> {
    let assign = &record.assign;
    let paths = shared.opts.paths.clone();
    let mut sink = EventSink::after(
        shared.clone(),
        assign.run_id,
        assign.epoch,
        record.seq_floor,
    );
    sink.keep_record(Arc::new(RecordHandle::kept(
        &shared.records,
        record.clone(),
    )));
    let mut secrets = Vec::new();
    if let Some(tinfirm_protocol::AssignedCredential::Model(access)) = assign.credential() {
        secrets.push(access.api_key.expose().to_string());
    }
    for ws in &assign.workspaces {
        if let Some(creds) = &ws.git {
            secrets.push(creds.token.expose().to_string());
        }
        if let Some(authorization) = git::authorization_for(ws) {
            secrets.push(authorization.expose().to_string());
        }
    }
    if !secrets.is_empty() {
        shared.add_run_secrets(assign.run_id, assign.epoch, secrets);
    }
    sink.log(
        LogLevel::Warn,
        "the runner host restarted during the run and takes it back: the agent stops, and its work and its session are saved",
    );
    sink.status(Phase::Finalizing, None);

    let workspace_dir = paths.workspace(assign.run_id);
    let home_lease = shared.homes.lease(assign.agent.id, assign.work_item().id);
    let home_dir = home_lease.path().to_path_buf();
    let output_path = paths.output(assign.run_id);
    let output_root = if output_path.is_dir() {
        match OutputRoot::create(&output_path) {
            Ok(root) => Some(root),
            Err(e) => {
                sink.log(
                    LogLevel::Warn,
                    format!("cannot prepare the run's {}: {e:#}", crate::sandbox::OUTPUT),
                );
                None
            }
        }
    } else {
        None
    };
    let layout = match &shared.opts.executor {
        crate::sandbox::Executor::Docker(_) => Layout::container(),
        crate::sandbox::Executor::Vm(vms) if !vms.options.on_this_machine => Layout::container(),
        _ => Layout::local(&workspace_dir),
    };
    let mut repos: Vec<Repo> = record
        .repos
        .iter()
        .filter_map(|kept| {
            let ws = assign.workspaces.iter().find(|ws| ws.dir == kept.dir)?;
            Some(Repo {
                ws,
                git: Git::restored(
                    workspace_dir.join(&ws.dir),
                    ws,
                    git_identity(assign, Some(ws)),
                    kept.base_refs(),
                    kept.config_snapshot(),
                ),
                cloned_head: kept.cloned_head.clone(),
                cloned_commits: kept.cloned_commits,
                collected: None,
            })
        })
        .collect();

    // --- the sandbox's bridge comes back --------------------------------------------------
    // A cancel meanwhile still saves the work, as a run's cancel does; only an abort (a stale
    // epoch) stops the wait.
    let mut failure = Failure::default();
    let mut came_back = false;
    let connected = tokio::select! {
        session = registration.connected(TAKE_BACK_WAIT) => Some(session),
        _ = wait_for_abort(&mut stop_rx) => None,
    };
    match connected {
        Some(Ok(session)) => {
            came_back = true;
            let mut sandbox = sandbox.connected(session);
            match sandbox.reclaim(RECLAIM_TIMEOUT).await {
                Ok(true) => sink.log(
                    LogLevel::Info,
                    "the agent of the host before its restart was stopped",
                ),
                Ok(false) => sink.log(
                    LogLevel::Info,
                    "the agent had stopped already: its host was gone for too long",
                ),
                Err(e) => sink.log(
                    LogLevel::Warn,
                    format!(
                        "the agent did not stop as asked: {}",
                        sink.redact(&format!("{e:#}"))
                    ),
                ),
            }
            if let Some(files) = sandbox.files()
                && *stop_rx.borrow() != Some(StopRequest::Abort)
            {
                collect_from_sandbox(
                    &mut sink,
                    assign,
                    &files,
                    &layout,
                    &mut repos,
                    &home_dir,
                    record.home_exclude.clone(),
                    output_root.as_ref().map(OutputRoot::path),
                )
                .await;
            }
            sandbox.stop(Duration::from_secs(5)).await;
        }
        Some(Err(e)) => {
            failure.set(
                FailureClass::Infra,
                format!(
                    "The runner host restarted during the run, and the run's sandbox did not come back to it ({}); the work of the run is lost.",
                    sink.redact(&format!("{e:#}"))
                ),
            );
            sandbox.discard(Duration::from_secs(5)).await;
        }
        None => sandbox.discard(Duration::from_secs(5)).await,
    }
    sink.drop_record();

    let cancelled = match *stop_rx.borrow() {
        Some(StopRequest::Abort) => {
            let _ = std::fs::remove_dir_all(paths.attachments(assign.run_id));
            cleanup_workspace(shared, &workspace_dir, false);
            return None;
        }
        Some(StopRequest::Cancel) => true,
        None => false,
    };

    // --- what the run leaves ---------------------------------------------------------------
    let mut artifacts = Vec::new();
    if came_back && let Some(output_root) = &output_root {
        artifacts = collect_output(shared, &mut sink, assign, output_root).await;
    }
    let mut leak = false;
    let results = if came_back && !repos.is_empty() {
        sink.status(Phase::Finalizing, None);
        let finalized = finalize_git(shared, &mut sink, &repos, assign.run_id, false).await;
        if finalized.outgoing_leak {
            leak = true;
            failure.force(
                FailureClass::CredentialLeak,
                "a commit contains a credential value; no repository was pushed",
            );
        }
        if !finalized.errors.is_empty() {
            sink.log(
                LogLevel::Error,
                git_failure_message(&finalized.errors, repos.len()),
            );
        }
        finalized.results
    } else {
        repos.iter().map(Repo::unfinalized_result).collect()
    };
    let session_bundle = match &assign.session_bundle {
        Some(_) if came_back => Some(
            store_session_bundle(
                shared,
                &mut sink,
                assign,
                &home_dir,
                record.session_id.as_deref(),
                leak,
            )
            .await,
        ),
        _ => None,
    };
    let failed_git = results
        .iter()
        .any(|g| !g.pushed && g.commits.unwrap_or(0) > 0);
    if !cancelled {
        let mut saved = vec!["the agent was stopped"];
        if results.iter().any(|g| g.pushed) {
            saved.push(if failed_git {
                "some of its work was pushed (the log names what was not)"
            } else {
                "its work so far was pushed"
            });
        }
        if matches!(
            session_bundle,
            Some(tinfirm_protocol::SessionBundleOutcome::Stored)
        ) || (assign.session_bundle.is_none() && record.session_id.is_some())
        {
            saved.push("its session was kept, so the next message goes on from it");
        }
        failure.set(
            FailureClass::Infra,
            format!(
                "The runner host restarted during the run and took the run back: {}.",
                saved.join(", ")
            ),
        );
    }
    let report = Report {
        session_id: record.session_id.clone(),
        git: Some(results),
        artifacts,
        session_bundle,
        ..Report::default()
    };
    let finished = finish(&mut sink, assign, failure, cancelled, report);
    let _ = std::fs::remove_dir_all(paths.attachments(assign.run_id));
    cleanup_workspace(
        shared,
        &workspace_dir,
        finished.outcome == Outcome::Failed || failed_git,
    );
    Some(finished)
}

/// Restores the conversation's session bundle into the home, unless the home holds it already.
/// Whether the home now holds the bundle's session; a bundle that cannot be restored is a log
/// line, and the turn starts a new session.
async fn restore_session_bundle(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    assign: &RunAssign,
    home_dir: &Path,
    sha256: &str,
    size: u64,
) -> bool {
    if session_bundles::recorded(home_dir).as_deref() == Some(sha256) {
        sink.log(
            LogLevel::Debug,
            "the session home holds the conversation's session bundle already",
        );
        return true;
    }
    let bundle = match session_bundles::download(
        &shared.config.server_url,
        shared.config.token.expose(),
        assign.run_id,
        assign.epoch,
        sha256,
        size,
    )
    .await
    {
        Ok(bundle) => bundle,
        Err(e) => {
            let message = sink.redact(&format!(
                "cannot download the conversation's session bundle, so the turn starts a new session: {e:#}"
            ));
            sink.log(LogLevel::Warn, message);
            return false;
        }
    };
    let (home, runtime) = (home_dir.to_path_buf(), assign.agent.runtime);
    let restored =
        tokio::task::spawn_blocking(move || session_bundles::restore(&home, runtime, &bundle))
            .await
            .map_err(|e| anyhow::anyhow!("the restore stopped: {e}"))
            .and_then(|restored| restored);
    match restored {
        Ok(restored) => {
            if let Err(e) = session_bundles::record(home_dir, sha256) {
                tracing::warn!(error = %e, "cannot record the session home's bundle");
            }
            sink.log(
                LogLevel::Info,
                format!(
                    "restored the session from the server's bundle: {} file(s) in {}",
                    restored.files,
                    restored.dirs.join(", ")
                ),
            );
            if !restored.left_out.is_empty() {
                sink.log(
                    LogLevel::Warn,
                    format!(
                        "left out of the session bundle (not the runtime's session folders, or not plain files): {}",
                        restored.left_out.join(", ")
                    ),
                );
            }
            true
        }
        Err(e) => {
            session_bundles::forget(home_dir);
            sink.log(
                LogLevel::Warn,
                format!(
                    "cannot restore the conversation's session bundle, so the turn starts a new session: {e:#}"
                ),
            );
            false
        }
    }
}

/// Packs the runtime's session folders of the home and uploads them to the server, which keeps
/// them for the conversation's next turn on any host (ADR-0097). Never fails the run.
async fn store_session_bundle(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    assign: &RunAssign,
    home_dir: &Path,
    session_id: Option<&str>,
    leak: bool,
) -> tinfirm_protocol::SessionBundleOutcome {
    use tinfirm_protocol::SessionBundleOutcome as Outcome;
    // Its folders change with every run; a record names them only once they are stored again.
    session_bundles::forget(home_dir);
    let Some(session_id) = session_id else {
        return Outcome::None;
    };
    if leak {
        sink.log(
            LogLevel::Warn,
            "not storing the session bundle: the run leaked a credential value",
        );
        return Outcome::Refused;
    }
    let (home, runtime) = (home_dir.to_path_buf(), assign.agent.runtime);
    let redactor = shared.redactor();
    let packed = tokio::task::spawn_blocking(move || {
        let packed = session_bundles::pack(&home, runtime)?;
        // The run's own credentials, which the server's scan does not know. Its URL secrets
        // (the platform tools', the git proxy's) open nothing once the run ends.
        let leaks = match &packed {
            session_bundles::Packed::Bundle(bundle) => {
                redactor.contains_credential(&session_bundles::gunzip(bundle)?)
            }
            _ => false,
        };
        Ok::<_, std::io::Error>((packed, leaks))
    })
    .await;
    let bundle = match packed {
        Ok(Ok((session_bundles::Packed::Bundle(_), true))) => {
            sink.log(
                LogLevel::Warn,
                "not storing the session bundle: it holds a credential value of the run",
            );
            return Outcome::Refused;
        }
        Ok(Ok((session_bundles::Packed::Bundle(bundle), false))) => bundle,
        Ok(Ok((session_bundles::Packed::Empty, _))) => return Outcome::None,
        Ok(Ok((session_bundles::Packed::TooLarge, _))) => {
            sink.log(
                LogLevel::Warn,
                format!(
                    "not storing the session bundle: the session folders hold more than {} MiB, so the next turn on another host starts a new session",
                    tinfirm_protocol::SESSION_BUNDLE_MAX_UNPACKED_BYTES >> 20
                ),
            );
            return Outcome::TooLarge;
        }
        Ok(Err(e)) => {
            sink.log(
                LogLevel::Warn,
                format!("cannot pack the session bundle: {e}"),
            );
            return Outcome::Failed;
        }
        Err(e) => {
            sink.log(
                LogLevel::Warn,
                format!("cannot pack the session bundle: {e}"),
            );
            return Outcome::Failed;
        }
    };
    let sha256 = session_bundles::sha256_hex(&bundle);
    match session_bundles::upload(
        &shared.config.server_url,
        shared.config.token.expose(),
        assign.run_id,
        assign.epoch,
        session_id,
        bundle,
    )
    .await
    {
        Ok(answer) if answer.stored() => {
            if let Err(e) = session_bundles::record(home_dir, &sha256) {
                tracing::warn!(error = %e, "cannot record the session home's bundle");
            }
            sink.log(LogLevel::Debug, "stored the session bundle on the server");
            Outcome::Stored
        }
        Ok(answer) => {
            let message = sink.redact(&answer.message);
            sink.log(
                LogLevel::Warn,
                format!("the server did not store the session bundle: {message}"),
            );
            Outcome::Refused
        }
        Err(e) => {
            let message = sink.redact(&format!("{e:#}"));
            sink.log(
                LogLevel::Warn,
                format!("cannot store the session bundle on the server: {message}"),
            );
            Outcome::Failed
        }
    }
}

async fn wait_for_abort(stop_rx: &mut watch::Receiver<Option<StopRequest>>) {
    if stop_rx
        .wait_for(|stop| *stop == Some(StopRequest::Abort))
        .await
        .is_err()
    {
        std::future::pending::<()>().await;
    }
}

async fn wait_for_stop(stop_rx: &mut watch::Receiver<Option<StopRequest>>) {
    loop {
        if stop_rx.borrow().is_some() {
            return;
        }
        if stop_rx.changed().await.is_err() {
            std::future::pending::<()>().await;
        }
    }
}

/// Makes the run's directories: the workspace (empty), the home, and with `output` the
/// `/output` folder, empty and anchored before the sandbox starts ([`OutputRoot`]).
fn prepare_dirs(
    workspace: &Path,
    home: &Path,
    output: Option<&Path>,
    runtime: Runtime,
) -> anyhow::Result<Option<OutputRoot>> {
    if workspace.exists() {
        std::fs::remove_dir_all(workspace)?;
    }
    if let Some(parent) = workspace.parent() {
        crate::secure_fs::ensure_private_dir(parent)?;
    }
    std::fs::create_dir(workspace)?;
    // Empty at the start of every run, writable like the workspace, but never inside it: the
    // host reads it back when the run ends, and nothing the agent writes may decide where.
    let output_root = output.map(OutputRoot::create).transpose()?;
    if let Some(parent) = home.parent() {
        std::fs::create_dir_all(parent)?;
    }
    if !home.exists() {
        std::fs::create_dir_all(home)?;
    }
    if runtime == Runtime::Codex {
        // codex refuses to start when CODEX_HOME does not exist.
        std::fs::create_dir_all(home.join(".codex"))?;
    }
    Ok(output_root)
}

/// Mirror fetch and clone of every repository, in `workspaces` order (docs/archive/v1/CONVENTIONS.md §15.4
/// step 2). The first repository that cannot be cloned fails the run; `Err` is its message.
async fn clone_all<'a>(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    assign: &'a RunAssign,
    workspace_dir: &Path,
    repos: &mut Vec<Repo<'a>>,
) -> Result<(), String> {
    for ws in &assign.workspaces {
        let mut git = Git::new(
            workspace_dir.join(&ws.dir),
            ws,
            git_identity(assign, Some(ws)),
        );
        let cloned = async {
            // The mirror is fetched with this repository's credentials first, so a token
            // that no longer works fails the run instead of serving stale content.
            let mirror = shared
                .mirrors
                .update(&ws.repo_url, git.authorization())
                .await
                .map_err(|e| e.context("cannot update the host's mirror"))?;
            git.clone_from_mirror(&mirror.path, &mirror.refs, ws).await
        }
        .await;
        match cloned {
            Ok(head) => {
                let commits = match branch_tip(git.base_refs(), &ws.default_branch) {
                    Some(base) => git.count_ahead(base).await.ok(),
                    None => None,
                };
                sink.git(ws, GitAction::Cloned, Some(head.clone()), commits);
                repos.push(Repo {
                    ws,
                    git,
                    collected: None,
                    cloned_head: head,
                    cloned_commits: commits,
                });
            }
            Err(e) => {
                return Err(sink.redact(&format!("cannot clone {}: {e:#}", repo_label(ws))));
            }
        }
    }
    Ok(())
}

/// What the sandbox reaches on the host: one git proxy route per HTTP(S) repository and the
/// platform MCP endpoint. Dropping it removes them.
#[derive(Default)]
struct SandboxAccess {
    routes: Vec<RouteGuard>,
    mcp: Option<McpGuard>,
    /// The run's platform tools, which hold the result of `finish_run`.
    tools: Option<Arc<RunTools>>,
    /// Docker `extra_hosts` for the listener's host name.
    extra_hosts: Vec<String>,
    /// The run's egress proxy listener, with `run --egress-proxy`.
    egress: Option<EgressGuard>,
    /// The run's model proxy listener, when its model access is a gateway.
    model_proxy: Option<ModelProxyGuard>,
    /// Where the sandbox reaches the listeners when it is not the endpoint's host: a local
    /// test executor's sandbox over the bridge, on a loopback address of its own.
    sandbox_host: Option<String>,
}

/// An address of the sandbox network's reserved test range (RFC 5737) on which nothing answers.
const UNREACHABLE_DNS: &str = "192.0.2.1";

/// Starts the run's model proxy when its model access is a gateway (crate::model_proxy, always
/// on), so that the gateway's key stays on this host. `Ok(None)` for any other model access and
/// for this host's credentials. A proxy that cannot start is an error: the run fails closed
/// rather than give the sandbox the key, and the log says why.
async fn start_model_proxy(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    assign: &RunAssign,
    credential: &RunCredential,
    sandbox_host: Option<&str>,
) -> Result<Option<ModelProxyGuard>, String> {
    let RunCredential::Model(model) = credential else {
        return Ok(None);
    };
    if !crate::model_proxy::applies(model) {
        return Ok(None);
    }
    let unavailable = |sink: &EventSink, e: anyhow::Error| {
        let error = sink.redact(&format!("{e:#}"));
        tracing::warn!(run_id = %assign.run_id, %error, "the model proxy is not available for this run");
        format!("the model proxy cannot start: {error}")
    };
    let proxy = match shared.model_proxy().await {
        Ok(proxy) => proxy,
        Err(e) => return Err(unavailable(sink, e)),
    };
    let guard = match proxy.start_run_at(assign.run_id, model, sandbox_host).await {
        Ok(guard) => guard,
        Err(e) => return Err(unavailable(sink, e)),
    };
    // The base URL carries the run's secret: masked before it can appear anywhere (the agent
    // may print its settings). The gateway's key is in the run's redactor already.
    shared.add_run_masked(
        assign.run_id,
        assign.epoch,
        vec![guard.secret().to_string()],
    );
    sink.log(
        LogLevel::Debug,
        "the sandbox reaches its model through the runner host's model proxy; the gateway's key stays on the host",
    );
    // The run's model requests go without the host's token; the server will refuse them.
    if guard.host_token_withheld() {
        sink.log(LogLevel::Warn, crate::model_proxy::HOST_TOKEN_WITHHELD);
    }
    Ok(Some(guard))
}

/// With `run --egress-proxy` (ADR-0044): starts the run's egress proxy listener, allowed to
/// reach the host-wide hosts and the run's model (`model`: what the runtime reaches it with; a
/// gateway behind the model proxy needs no host), and points the sandbox's tools at it. The
/// sandbox is on an internal network, so a run whose proxy cannot start has no network: it
/// fails closed, and the log says why. A VM from the pool brings its own listener (`slot`),
/// which its container's settings name already: the run binds its rules to that one.
#[allow(clippy::too_many_arguments)]
async fn start_egress(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    assign: &RunAssign,
    model: Access<'_>,
    spec: &mut SandboxSpec,
    sandbox_host: Option<&str>,
    slot: Option<EgressGuard>,
) -> Result<Option<EgressGuard>, String> {
    if shared.opts.egress.is_none() {
        return Ok(None);
    }
    let unavailable = |sink: &EventSink, e: anyhow::Error| {
        let error = sink.redact(&format!("{e:#}"));
        tracing::warn!(run_id = %assign.run_id, %error, "the egress proxy is not available for this run");
        format!("the egress proxy cannot start: {error}")
    };
    let proxy = match slot {
        Some(_) => None,
        None => match shared.egress_proxy().await {
            Ok(Some(proxy)) => Some(proxy),
            Ok(None) => return Ok(None),
            Err(e) => return Err(unavailable(sink, e)),
        },
    };
    let mut rules = crate::runtime_env::model_hosts(assign.agent.runtime, model);
    let (approved, skipped) = allowed_host_rules(&assign.allowed_hosts);
    for (entry, why) in skipped {
        sink.log(
            LogLevel::Warn,
            format!(
                "ignoring the allowed host `{}` of this run: {why}",
                sink.redact(&entry.escape_debug().to_string())
            ),
        );
    }
    let (approved, too_broad) = drop_public_suffix_wildcards(approved, shared.public_suffix());
    for rule in too_broad {
        sink.log(
            LogLevel::Warn,
            format!(
                "ignoring the allowed host `{rule}` of this run: it would reach every site on a shared service (a public suffix); the exact host is allowed instead"
            ),
        );
    }
    rules.extend(approved);
    let guard = match (slot, proxy) {
        (Some(slot), _) => {
            slot.bind(assign.run_id, rules);
            slot
        }
        (None, Some(proxy)) => match proxy.start_run_at(assign.run_id, rules, sandbox_host).await {
            Ok(guard) => guard,
            Err(e) => return Err(unavailable(sink, e)),
        },
        (None, None) => return Ok(None),
    };
    // The proxy URL carries the run's credential: masked before it can appear anywhere.
    shared.add_run_masked(
        assign.run_id,
        assign.epoch,
        vec![guard.secret().to_string()],
    );
    // Nothing in the sandbox can resolve a name (the proxy does), and Docker's own resolver
    // must not forward a lookup of a name to the outside: its upstream is an address on
    // which nothing answers.
    spec.dns = vec![UNREACHABLE_DNS.to_string()];
    // Whatever the operator set in `--sandbox-env` stays (`run` refuses the proxy variables).
    let mut env = guard.env();
    if assign.agent.runtime == Runtime::ClaudeCode {
        env.push((
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC".into(),
            "1".into(),
        ));
    }
    for (key, value) in env {
        match spec.env.iter_mut().find(|(k, _)| *k == key) {
            // The operator's JVM options stay, with the proxy's added.
            Some((_, existing)) if key == "JAVA_TOOL_OPTIONS" => {
                *existing = format!("{existing} {value}");
            }
            Some(_) => {}
            None => spec.env.push((key, value)),
        }
    }
    sink.log(
        LogLevel::Debug,
        "the sandbox reaches the network only through the runner host's egress proxy",
    );
    Ok(Some(guard))
}

/// The egress rules of `run.assign.allowedHosts`: the entries that parse and, for the others,
/// the entry with the reason. A bad entry never fails the run.
fn allowed_host_rules(hosts: &[String]) -> (Vec<Rule>, Vec<(String, String)>) {
    let mut rules = Vec::new();
    let mut skipped = Vec::new();
    for entry in hosts {
        match Rule::parse(entry) {
            Ok(rule) => rules.push(rule),
            Err(why) => skipped.push((entry.clone(), why)),
        }
    }
    (rules, skipped)
}

/// Splits off the wildcards over a public suffix (`*.github.io`), which would reach every
/// site of a shared service: they are dropped, never fail the run. Without a Public Suffix List
/// nothing is dropped.
fn drop_public_suffix_wildcards(
    rules: Vec<Rule>,
    store: &crate::public_suffix::PublicSuffixStore,
) -> (Vec<Rule>, Vec<Rule>) {
    rules
        .into_iter()
        .partition(|rule| store.broad_wildcard_base(rule).is_none())
}

/// Registers the run with the listener of the git proxy and the platform MCP endpoint
/// (docs/archive/v1/CONVENTIONS.md §15.4 step 2, §15.6). Without the listener the run goes on without fetch
/// and without the platform tools.
async fn sandbox_access(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    assign: &RunAssign,
    repos: &[Repo<'_>],
    file_roots: Vec<FileRoot>,
    sandbox_host: Option<String>,
) -> SandboxAccess {
    let mut access = SandboxAccess {
        sandbox_host,
        ..SandboxAccess::default()
    };
    let proxy = match shared.git_proxy().await {
        Ok(Some(proxy)) => proxy,
        Ok(None) => {
            sink.log(
                LogLevel::Warn,
                "the sandbox has no network, so the Tinfirm platform tools and git fetch are not available in this run",
            );
            return access;
        }
        Err(e) => {
            let error = sink.redact(&format!("{:#}", e.context("cannot start the git proxy")));
            tracing::warn!(run_id = %assign.run_id, %error, "the sandbox cannot reach the host's listener");
            sink.log(
                LogLevel::Warn,
                format!(
                    "the Tinfirm platform tools and git fetch are not available in this run: {error}"
                ),
            );
            return access;
        }
    };
    access.extra_hosts = proxy.endpoint().extra_hosts.clone();
    for repo in repos {
        let host = access.sandbox_host.as_deref();
        if let Some(route) = route_git_proxy(shared, sink, &proxy, repo, assign, host).await {
            access.routes.push(route);
        }
    }
    if !access.routes.is_empty() {
        sink.log(
            LogLevel::Debug,
            "the sandbox fetches through the runner host's git proxy",
        );
    }
    let tools = Arc::new(
        RunTools::new(
            assign.run_id,
            assign.epoch,
            &assign.workspaces,
            &assign.requestable_repositories,
            shared.platform_requester(),
            shared.opts.runner_version.clone(),
        )
        .with_file_roots(file_roots)
        .with_protocol(shared.protocol_version(), assign.tools.clone())
        .with_network_requests(shared.opts.egress.is_some())
        .with_platform_tools(assign.platform_tools.clone())
        .with_public_suffix(shared.public_suffix().clone())
        .with_upload_max_bytes(shared.opts.upload_max_bytes),
    );
    match proxy.register_mcp_at(tools.clone(), access.sandbox_host.as_deref()) {
        Ok(mcp) => {
            // Before the URL can appear anywhere: the agent may print its MCP settings.
            shared.add_run_masked(
                assign.run_id,
                assign.epoch,
                vec![mcp.secret().expose().to_string()],
            );
            access.mcp = Some(mcp);
            access.tools = Some(tools);
        }
        Err(e) => {
            let error = sink.redact(&format!("{e:#}"));
            sink.log(
                LogLevel::Warn,
                format!("the Tinfirm platform tools are not available in this run: {error}"),
            );
        }
    }
    access
}

/// Registers one repository with the git proxy and points its `origin` at it, so the agent
/// can fetch. Without a route the run goes on; only fetching that repository does not work.
async fn route_git_proxy(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    proxy: &GitProxy,
    repo: &Repo<'_>,
    assign: &RunAssign,
    sandbox_host: Option<&str>,
) -> Option<RouteGuard> {
    let ws = repo.ws;
    // Only HTTP(S) repositories; `file://` ones exist in tests only.
    let scheme = url::Url::parse(&ws.repo_url)
        .map(|u| u.scheme().to_string())
        .unwrap_or_default();
    if scheme != "https" && scheme != "http" {
        return None;
    }
    let unavailable = |sink: &mut EventSink, e: anyhow::Error| {
        let error = sink.redact(&format!("{e:#}"));
        tracing::warn!(run_id = %assign.run_id, dir = %ws.dir, %error, "the sandbox cannot fetch through the git proxy");
        sink.log(
            LogLevel::Warn,
            format!(
                "git fetch will not work in {WORKSPACE_ROOT}/{}: {error}",
                ws.dir
            ),
        );
    };
    let route = match proxy.register_at(
        assign.run_id,
        &ws.repo_url,
        repo.git.authorization().cloned(),
        &ws.branch,
        sandbox_host,
    ) {
        Ok(route) => route,
        Err(e) => {
            unavailable(sink, e);
            return None;
        }
    };
    // Before the URL can appear anywhere: `git fetch` prints it.
    shared.add_run_masked(
        assign.run_id,
        assign.epoch,
        vec![route.secret().expose().to_string()],
    );
    if let Err(e) = repo.git.set_origin_url(route.sandbox_url()).await {
        unavailable(sink, e);
        return None;
    }
    Some(route)
}

/// Git identity for a repository (or the sandbox, with `None`: the first repository's):
/// `authorName`/`authorEmail` from the server, else `Tinfirm agent <name>` /
/// `agent+<agentId>@tinfirm.invalid` (the server's convention).
fn git_identity(assign: &RunAssign, ws: Option<&WorkspaceSpec>) -> (String, String) {
    let ws = ws.or(assign.workspaces.first());
    let name = ws
        .and_then(|w| w.author_name.clone())
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| format!("Tinfirm agent {}", assign.agent.name));
    let email = ws
        .and_then(|w| w.author_email.clone())
        .filter(|e| !e.trim().is_empty())
        .unwrap_or_else(|| format!("agent+{}@tinfirm.invalid", assign.agent.id));
    (name, email)
}

/// Starts the run's sandbox: attached to its stdio, or with the bridge (ADR-0095,
/// `run --sandbox-bridge`), where the run's credentials reach the adapter in the bridge's
/// `start` frame instead of the sandbox's settings, and the sandbox reaches the listeners of
/// `access` through tunnels of the bridge.
#[allow(clippy::too_many_arguments)]
async fn start_sandbox(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    assign: &RunAssign,
    spec: &mut SandboxSpec,
    access: &SandboxAccess,
    puts: Vec<crate::sandbox::Put>,
    claimed: Option<crate::pool::Claimed>,
) -> anyhow::Result<(crate::sandbox::Sandbox, SandboxIo)> {
    use anyhow::Context as _;
    let Some(options) = shared.opts.bridge.clone() else {
        return shared.opts.executor.start(spec).await;
    };
    // The listeners the run uses, on this host's loopback: the sandbox reaches each through a
    // tunnel on its own loopback, at the same port.
    let mut listeners = Vec::new();
    if (access.mcp.is_some() || !access.routes.is_empty())
        && let Some(proxy) = shared.git_proxy().await?
    {
        listeners.push(proxy.local_addr());
    }
    listeners.extend(access.model_proxy.as_ref().map(|m| m.local_addr()));
    listeners.extend(access.egress.as_ref().map(|e| e.local_addr()));
    let loopback = match access.sandbox_host.as_deref().map(str::parse) {
        Some(Ok(ip)) => ip,
        _ => std::net::IpAddr::V4(crate::bridge::SANDBOX_LOOPBACK),
    };
    let tunnels = crate::bridge::tunnels(&listeners, loopback);
    let bridge_note =
        "the agent runs over the sandbox bridge; its credentials are not in the sandbox's settings";
    if let Some(claimed) = claimed {
        // The VM's ticket is in its user data, used up when its bridge connected; it is no
        // credential, but it never leaves the host.
        shared.add_run_masked(assign.run_id, assign.epoch, vec![claimed.ticket.clone()]);
        sink.log(LogLevel::Debug, bridge_note);
        return shared
            .opts
            .executor
            .start_claimed(spec, claimed, shared.opts.pool.reprobe_after, tunnels, puts)
            .await;
    }
    let listener = shared.bridge().await?.context(
        "the sandbox has no network, so its bridge cannot reach this host (--sandbox-bridge)",
    )?;
    if spec.extra_hosts.is_empty() {
        spec.extra_hosts = listener.extra_hosts.clone();
    }
    // A run assigned again with a newer epoch gets a new sandbox and a new registration.
    let registration = listener
        .server
        .register(&format!("{}.{}", assign.run_id, assign.epoch))?;
    // The ticket is in the sandbox's settings; it is no credential, but it never leaves the host.
    shared.add_run_masked(
        assign.run_id,
        assign.epoch,
        vec![registration.ticket().to_string()],
    );
    sink.log(LogLevel::Debug, bridge_note);
    let bridged = crate::sandbox::Bridged {
        registration,
        addr: listener.sandbox_addr(),
        binary: options.binary,
        connect_timeout: options.connect_timeout,
        tunnels,
        puts,
    };
    shared.opts.executor.start_bridged(spec, bridged).await
}

/// Container settings of docs/archive/v1/CONVENTIONS.md §15.3 (and, with `output`, the `/output` bind of
/// protocol v2; with `skills`, the read-only binds of the run's skills, ADR-0064). `model` is
/// what the runtime reaches its model with: the credential's access, or the same through the
/// run's model proxy.
#[allow(clippy::too_many_arguments)]
fn sandbox_spec(
    shared: &Arc<Shared>,
    assign: &RunAssign,
    credential: &RunCredential,
    model: Access<'_>,
    layout: &Layout,
    workspace: &Path,
    home: &Path,
    attachments: Option<&Path>,
    output: Option<&Path>,
    skills: &[ProvidedSkill],
) -> SandboxSpec {
    let mut env: Vec<(String, String)> = vec![
        ("DISABLE_AUTOUPDATER".into(), "1".into()),
        ("GIT_TERMINAL_PROMPT".into(), "0".into()),
        ("TINFIRM_RUN_ID".into(), assign.run_id.to_string()),
        ("TINFIRM_TASK_KEY".into(), assign.work_item().key.clone()),
    ];
    if shared.speaks_v2() {
        env.push((
            "TINFIRM_WORK_ITEM_KEY".into(),
            assign.work_item().key.clone(),
        ));
        if let Some(job) = &assign.job {
            env.push(("TINFIRM_JOB_ID".into(), job.id.to_string()));
        }
    }
    // Docker Desktop and rootless setups map ownership: git in the sandbox must accept each
    // repository as safe.
    if !assign.workspaces.is_empty() {
        env.push((
            "GIT_CONFIG_COUNT".into(),
            assign.workspaces.len().to_string(),
        ));
        for (i, ws) in assign.workspaces.iter().enumerate() {
            env.push((format!("GIT_CONFIG_KEY_{i}"), "safe.directory".into()));
            env.push((
                format!("GIT_CONFIG_VALUE_{i}"),
                format!("{}/{}", crate::sandbox::WORKSPACE, ws.dir),
            ));
        }
    }
    let (author_name, author_email) = git_identity(assign, None);
    for key in ["GIT_AUTHOR_NAME", "GIT_COMMITTER_NAME"] {
        env.push((key.into(), author_name.clone()));
    }
    for key in ["GIT_AUTHOR_EMAIL", "GIT_COMMITTER_EMAIL"] {
        env.push((key.into(), author_email.clone()));
    }
    env.extend(runtime_env(
        assign.agent.runtime,
        model,
        agent_model(assign),
        assign.agent.effort,
    ));
    if assign.agent.runtime == Runtime::Codex {
        env.push(("CODEX_HOME".into(), format!("{}/.codex", layout.home)));
    }
    // With the bridge (ADR-0095) the directories go into the sandbox instead of these mounts
    // (crate::sandbox_files); only a Codex ChatGPT login stays one. The local test executor
    // has no mounts: the layout points the agent at the host's directories.
    let mounts = shared.opts.bridge.is_none();
    let mut binds = Vec::new();
    if mounts {
        binds.push(Bind {
            host: workspace.to_path_buf(),
            container: layout.workspace.clone(),
            read_only: false,
        });
        binds.push(Bind {
            host: home.to_path_buf(),
            container: layout.home.clone(),
            read_only: false,
        });
        // Inside the home, at mount points the host created there (crate::skills).
        binds.extend(skills::binds(assign.agent.runtime, skills));
    }
    if let Some(dir) = attachments {
        if mounts {
            binds.push(Bind {
                host: dir.to_path_buf(),
                container: layout.attachments.clone(),
                read_only: true,
            });
        }
        env.push(("TINFIRM_ATTACHMENTS_DIR".into(), layout.attachments.clone()));
    }
    if let Some(dir) = output {
        if mounts {
            binds.push(Bind {
                host: dir.to_path_buf(),
                container: layout.output.clone(),
                read_only: false,
            });
        }
        env.push(("TINFIRM_OUTPUT_DIR".into(), layout.output.clone()));
    }
    if let RunCredential::Host(credential) = credential {
        match credential.kind {
            // The local test executor has no mounts: it points the agent at the login's
            // `CODEX_HOME` on the host.
            CredentialKind::CodexChatgptAuth => match shared.opts.executor {
                crate::sandbox::Executor::Docker(_) => binds.push(Bind {
                    host: shared.credentials.codex_home(&credential.label),
                    container: format!("{}/.codex", crate::sandbox::HOME),
                    read_only: false,
                }),
                // A VM per run mounts nothing of this machine; a login stays here, and the
                // company host a VM runs on holds none (ADR-0096).
                crate::sandbox::Executor::Vm(_) => {}
                crate::sandbox::Executor::Local { .. } => {
                    env.retain(|(key, _)| key != "CODEX_HOME");
                    env.push((
                        "CODEX_HOME".into(),
                        shared
                            .credentials
                            .codex_home(&credential.label)
                            .to_string_lossy()
                            .into_owned(),
                    ));
                }
            },
            // Claude Code's own credentials file, read-write so that Claude Code renews the
            // login in place for every run that shares it (ADR-0085). The mount point in the
            // home is a plain file the host made (crate::credentials::claude_login_mount_point).
            // The local test executor has no mounts: it points the agent at the login's
            // directory on the host, as Claude Code's own `CLAUDE_CONFIG_DIR`.
            CredentialKind::ClaudeLogin => match shared.opts.executor {
                crate::sandbox::Executor::Docker(_) => binds.push(Bind {
                    host: shared
                        .credentials
                        .claude_credentials_file(&credential.label),
                    container: crate::credentials::CLAUDE_CREDENTIALS_IN_SANDBOX.into(),
                    read_only: false,
                }),
                crate::sandbox::Executor::Vm(_) => {}
                crate::sandbox::Executor::Local { .. } => env.push((
                    "CLAUDE_CONFIG_DIR".into(),
                    shared
                        .credentials
                        .claude_login(&credential.label)
                        .to_string_lossy()
                        .into_owned(),
                )),
            },
            CredentialKind::AnthropicApiKey | CredentialKind::OpenaiApiKey => {}
        }
    }
    let sandbox = &shared.opts.sandbox;
    add_sandbox_env(&mut env, sandbox);
    let mut labels = std::collections::HashMap::new();
    labels.insert(docker::RUN_ID_LABEL.to_string(), assign.run_id.to_string());
    labels.insert(docker::RUNNER_HOST_LABEL.to_string(), shared.host_label());
    SandboxSpec {
        name: docker::container_name(assign.run_id),
        image: shared.opts.image_for(assign.agent.runtime),
        env,
        binds,
        labels,
        resources: shared.opts.resources,
        network_mode: shared.opts.sandbox_network.clone(),
        extra_hosts: Vec::new(),
        dns: Vec::new(),
        runtime: sandbox.runtime.clone(),
        docker: sandbox.docker,
        bridge_binary: None,
        command: shared.opts.adapter_for(assign.agent.runtime),
        local_cwd: PathBuf::from(&layout.workspace),
        local_home: PathBuf::from(&layout.home),
    }
}

/// The run's login that is mounted on its own (Docker only), relative to the home: a Codex
/// ChatGPT login's `.codex`, a Claude login's `.claude/.credentials.json` (ADR-0085). It stays a
/// mount with the bridge too, since its token is renewed in place, and the home's copy leaves it
/// out.
fn mounted_login(shared: &Shared, credential: &RunCredential) -> Option<&'static str> {
    if !matches!(shared.opts.executor, crate::sandbox::Executor::Docker(_)) {
        return None;
    }
    match credential {
        RunCredential::Host(c) => match c.kind {
            CredentialKind::CodexChatgptAuth => Some(".codex"),
            CredentialKind::ClaudeLogin => Some(".claude/.credentials.json"),
            CredentialKind::AnthropicApiKey | CredentialKind::OpenaiApiKey => None,
        },
        _ => None,
    }
}

/// With the bridge (ADR-0095), after the agent and before the sandbox goes: each repository's
/// commits, the session home and `/output` come back (crate::sandbox_files). A repository that
/// does not come back fails in the finalize; a home or an `/output` that does not is logged.
#[allow(clippy::too_many_arguments)]
async fn collect_from_sandbox(
    sink: &mut EventSink,
    assign: &RunAssign,
    files: &tinfirm_bridge::host::SandboxFiles,
    layout: &Layout,
    repos: &mut [Repo<'_>],
    home_dir: &Path,
    home_exclude: Vec<String>,
    output_dir: Option<&Path>,
) {
    let tag = format!("{}.{}", assign.run_id, assign.epoch);
    if !repos.is_empty() {
        let temp = home_dir
            .parent()
            .map(|p| p.join(format!("{}bundles-{tag}", crate::homes::DELETE_PREFIX)))
            .unwrap_or_else(|| PathBuf::from(format!("{}.bundles", assign.run_id)));
        let message = format!("WIP: uncommitted changes from run {}", assign.run_id);
        for (index, repo) in repos.iter_mut().enumerate() {
            // Commits this clone has, which the bundle leaves out.
            let mut candidates = vec![repo.cloned_head.clone()];
            for branch in [&repo.ws.default_branch, &repo.ws.branch] {
                if let Some(sha) = branch_tip(repo.git.base_refs(), branch) {
                    candidates.push(sha.to_string());
                }
            }
            candidates.sort();
            candidates.dedup();
            let present = repo.git.present(&candidates).await.unwrap_or_default();
            candidates.retain(|sha| present.contains(sha));
            let sandbox_repo = sandbox_files::SandboxRepo {
                files,
                dir: format!("{}/{}", layout.workspace, repo.ws.dir),
                scratch: &layout.scratch,
                index,
            };
            let (name, email) = git_identity(assign, Some(repo.ws));
            let collected = sandbox_files::collect_repository(
                &sandbox_repo,
                &repo.git,
                &message,
                (&name, &email),
                &candidates,
                &temp,
            )
            .await;
            if let Err(e) = &collected {
                tracing::warn!(run_id = %assign.run_id, dir = %repo.ws.dir, error = %sink.redact(&format!("{e:#}")), "a repository did not come back from the sandbox");
            }
            repo.collected = Some(collected.map_err(|e| sink.redact(&format!("{e:#}"))));
        }
        let _ = std::fs::remove_dir_all(&temp);
    }
    match sandbox_files::collect_home(files, layout, home_exclude, home_dir, &tag).await {
        Ok(home) if !home.left_out.is_empty() => sink.log(
            LogLevel::Debug,
            format!(
                "left out of the session home, as links or devices: {}",
                sink.redact(&home.left_out.join(", "))
            ),
        ),
        Ok(_) => {}
        Err(e) => sink.log(
            LogLevel::Warn,
            format!(
                "the session home did not come back from the sandbox, so the next turn may start a new session: {}",
                sink.redact(&format!("{e:#}"))
            ),
        ),
    }
    if let Some(output_dir) = output_dir {
        match sandbox_files::collect_output(files, layout, output_dir).await {
            Ok(output) if !output.left_out.is_empty() => sink.log(
                LogLevel::Warn,
                format!(
                    "not saved from {}: {} (a link, a device, or a file larger than {} MiB)",
                    crate::sandbox::OUTPUT,
                    sink.redact(&output.left_out.join(", ")),
                    crate::output::MAX_FILE_BYTES / (1024 * 1024)
                ),
            ),
            Ok(_) => {}
            Err(e) => sink.log(
                LogLevel::Warn,
                format!(
                    "the files of {} did not come back from the sandbox: {}",
                    crate::sandbox::OUTPUT,
                    sink.redact(&format!("{e:#}"))
                ),
            ),
        }
    }
}

/// The agent's model, when it has one.
fn agent_model(assign: &RunAssign) -> Option<&str> {
    assign
        .agent
        .model
        .as_deref()
        .map(str::trim)
        .filter(|model| !model.is_empty())
}

/// The environment that the host's sandbox settings add (ADR-0025): Docker in the sandbox,
/// then `--sandbox-env`.
pub(crate) fn add_sandbox_env(env: &mut Vec<(String, String)>, sandbox: &SandboxSettings) {
    if sandbox.docker {
        // The image's entrypoint starts dockerd as root, then runs the agent as this user.
        env.push((crate::sandbox::DOCKER_ENV.into(), "1".into()));
        env.push((
            crate::sandbox::AGENT_USER_ENV.into(),
            crate::sandbox::USER.into(),
        ));
    }
    // `run` refuses a `--sandbox-env` that names a variable of the host when it starts;
    // should one slip through, the host's value wins.
    for (key, value) in &sandbox.env {
        if !env.iter().any(|(k, _)| k == key) {
            env.push((key.clone(), value.clone()));
        }
    }
}

/// Splits sandbox output into ACP lines (stdout) and log lines (stderr, stray stdout).
/// Last time an ACP message (any JSON line on the agent's stdout: notification, request or
/// response) arrived, for the inactivity guard.
type Activity = Arc<std::sync::Mutex<tokio::time::Instant>>;

/// What the splitter hands the agent's ACP lines to: the transport's queue and the run's
/// budget for lines in flight, which a line takes before it is queued and holds until the run
/// loop has taken it (or the SDK dispatched it elsewhere).
struct AcpLines {
    tx: mpsc::Sender<std::io::Result<AcpLine>>,
    budget: Arc<Semaphore>,
}

/// One line of the agent's stdout: an ACP message when it is JSON, else a log line. An ACP
/// message counts as the agent's activity as soon as it is complete, then waits for its share
/// of the budget: the sandbox's output stays in its queue meanwhile, and the agent's writes in
/// the sandbox. A closed budget means the transport is gone, and the line goes nowhere.
async fn emit_stdout_line(
    line: &[u8],
    lines: &AcpLines,
    logs: &mpsc::Sender<String>,
    activity: &Activity,
) {
    let text = String::from_utf8_lossy(line)
        .trim_end_matches('\r')
        .to_string();
    if text.trim_start().starts_with('{') {
        *activity.lock().unwrap_or_else(|e| e.into_inner()) = tokio::time::Instant::now();
        let Ok(permit) = lines
            .budget
            .clone()
            .acquire_many_owned(acp::line_cost(text.len()))
            .await
        else {
            return;
        };
        let _ = lines.tx.send(Ok(AcpLine { text, permit })).await;
    } else if !text.trim().is_empty() {
        let _ = logs.send(format!("stdout: {text}")).await;
    }
}

/// A line of stderr, with a marker when `dropped` bytes of it were not kept.
async fn emit_stderr_line(line: &[u8], dropped: usize, logs: &mpsc::Sender<String>) {
    let mut text = String::from_utf8_lossy(line).trim_end().to_string();
    if dropped > 0 {
        text.push_str(&truncation_marker(dropped));
    }
    if !text.is_empty() {
        let _ = logs.send(text).await;
    }
}

/// The agent's stderr between two newlines: at most [`MAX_LOG_LINE`] bytes are kept, the
/// rest is counted and dropped.
#[derive(Default)]
struct StderrLine {
    kept: Vec<u8>,
    dropped: usize,
}

impl StderrLine {
    fn extend(&mut self, bytes: &[u8]) {
        let room = MAX_LOG_LINE
            .saturating_sub(self.kept.len())
            .min(bytes.len());
        self.kept.extend_from_slice(&bytes[..room]);
        self.dropped += bytes.len() - room;
    }

    fn take(&mut self) -> (Vec<u8>, usize) {
        (
            std::mem::take(&mut self.kept),
            std::mem::take(&mut self.dropped),
        )
    }
}

/// Reads the sandbox's output chunks and hands on its lines: ACP messages on `lines`, the
/// rest on `logs`. Each chunk is scanned once for its newlines. A stdout line over
/// [`MAX_ACP_LINE`] ends the transport with an error, as a dead agent would; a stderr line
/// over [`MAX_LOG_LINE`] is cut there and the run goes on. Both senders take the run loop's
/// pace, the ACP lines through the run's budget of lines in flight too: dropping `lines`
/// ends the ACP transport.
fn spawn_output_splitter(
    mut output: mpsc::Receiver<SandboxOutput>,
    lines: mpsc::Sender<std::io::Result<AcpLine>>,
    budget: Arc<Semaphore>,
    logs: mpsc::Sender<String>,
    activity: Activity,
) {
    let lines = AcpLines { tx: lines, budget };
    tokio::spawn(async move {
        let mut out_buf: Vec<u8> = Vec::new();
        let mut err_line = StderrLine::default();
        while let Some(item) = output.recv().await {
            match item {
                SandboxOutput::Stdout(bytes) => {
                    let mut rest = &bytes[..];
                    while let Some(pos) = rest.iter().position(|b| *b == b'\n') {
                        if out_buf.len() + pos > MAX_ACP_LINE {
                            break;
                        }
                        out_buf.extend_from_slice(&rest[..pos]);
                        emit_stdout_line(&out_buf, &lines, &logs, &activity).await;
                        out_buf.clear();
                        rest = &rest[pos + 1..];
                    }
                    if out_buf.len() + rest.len() > MAX_ACP_LINE {
                        tracing::warn!(
                            cap = MAX_ACP_LINE,
                            "a line of the agent's stdout is over the cap; ending the ACP transport"
                        );
                        let mib = MAX_ACP_LINE / (1024 * 1024);
                        let _ = logs
                            .send(format!(
                                "the agent's stdout had a line over {mib} MiB, which the runner host does not take; the ACP transport was ended"
                            ))
                            .await;
                        let _ = lines
                            .tx
                            .send(Err(std::io::Error::new(
                                std::io::ErrorKind::InvalidData,
                                format!("an ACP line over {mib} MiB on the agent's stdout"),
                            )))
                            .await;
                        return;
                    }
                    out_buf.extend_from_slice(rest);
                }
                SandboxOutput::Stderr(bytes) => {
                    let mut rest = &bytes[..];
                    while let Some(pos) = rest.iter().position(|b| *b == b'\n') {
                        err_line.extend(&rest[..pos]);
                        let (line, dropped) = err_line.take();
                        emit_stderr_line(&line, dropped, &logs).await;
                        rest = &rest[pos + 1..];
                    }
                    err_line.extend(rest);
                }
            }
        }
        if !out_buf.is_empty() {
            emit_stdout_line(&out_buf, &lines, &logs, &activity).await;
        }
        let (line, dropped) = err_line.take();
        emit_stderr_line(&line, dropped, &logs).await;
        // Dropping `lines` ends the ACP transport: the agent process is gone.
    });
}

async fn run_agent(
    shared: &Arc<Shared>,
    assign: &RunAssign,
    sink: &mut EventSink,
    io: SandboxIo,
    params: AcpParams,
    stop_rx: &mut watch::Receiver<Option<StopRequest>>,
    deadline: Instant,
) -> AgentPhase {
    // Bounded like the sandbox's output, the ACP lines by the run's budget of lines in flight
    // as well: a run loop that falls behind holds the agent's output back in the sandbox,
    // never in this process without bound.
    let (line_tx, line_rx) = mpsc::channel::<std::io::Result<AcpLine>>(acp::ACP_LINE_QUEUE);
    let budget = acp::inbound_budget();
    let (log_tx, mut log_rx) = mpsc::channel::<String>(crate::sandbox::OUTPUT_QUEUE);
    let activity: Activity = Arc::new(std::sync::Mutex::new(tokio::time::Instant::now()));
    spawn_output_splitter(io.output, line_tx, budget.clone(), log_tx, activity.clone());
    let outgoing = futures::sink::unfold(io.stdin, async |mut stdin, line: String| {
        stdin.write_all(line.as_bytes()).await?;
        stdin.write_all(b"\n").await?;
        stdin.flush().await?;
        Ok::<_, std::io::Error>(stdin)
    });
    let transport = AcpTransport {
        outgoing: Box::pin(outgoing),
        lines: line_rx,
        budget,
    };

    let (inbound_tx, mut inbound_rx) = mpsc::unbounded_channel();
    let (control_tx, control_rx) = mpsc::unbounded_channel();
    let acp_future = acp::run_session(transport, params, inbound_tx, control_rx);
    tokio::pin!(acp_future);

    let mut mapper = EventMapper::new(
        assign.limits.max_turns,
        UsageMode::for_runtime(assign.agent.runtime),
    )
    .with_redactor({
        let shared = shared.clone();
        Arc::new(move || shared.redactor())
    });
    let mut session_id: Option<String> = None;
    let mut not_resumed: Option<Option<NotResumed>> = None;
    let mut stop: Option<StopCause> = None;
    let mut cancel_deadline: Option<tokio::time::Instant> = None;
    let mut stderr_events = 0usize;
    let mut stderr_tail: Vec<String> = Vec::new();
    let mut tick = tokio::time::interval(Duration::from_millis(50));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let wall_clock = tokio::time::Instant::from_std(deadline);
    let inactivity = shared.opts.inactivity_timeout;
    let grace = shared.opts.cancel_grace;
    let mut out: Vec<EventPayload> = Vec::new();

    let request_stop = |cause: StopCause,
                        stop: &mut Option<StopCause>,
                        cancel_deadline: &mut Option<tokio::time::Instant>,
                        sink: &mut EventSink| {
        if stop.is_some() {
            return;
        }
        let message = match &cause {
            StopCause::Cancel => "cancel requested".to_string(),
            StopCause::Abort => "the run was reassigned (stale epoch)".to_string(),
            StopCause::Guard(m) => format!("guard: {m}"),
            StopCause::TimeLimit(m) => format!("time limit: {m}"),
            StopCause::Leak => "credential value in the agent's output".to_string(),
            StopCause::UsageLimit { .. } => "usage limit reached".to_string(),
        };
        if cause != StopCause::Abort {
            sink.status(Phase::Cancelling, Some(message));
        }
        *stop = Some(cause);
        let _ = control_tx.send(AcpControl::Cancel);
        *cancel_deadline = Some(tokio::time::Instant::now() + grace);
    };

    // Emits mapped events; a redaction hit in agent output is a credential leak, whether the
    // mapper (which redacts before it truncates) or the sink found it.
    fn emit_all(
        sink: &mut EventSink,
        mapper: &mut EventMapper,
        out: &mut Vec<EventPayload>,
    ) -> bool {
        let mut leak = mapper.take_leak();
        for event in out.drain(..) {
            leak |= sink.emit(event);
        }
        leak
    }

    // An initial stop (cancel before the agent started) is honoured right away.
    if let Some(request) = *stop_rx.borrow() {
        let cause = if request == StopRequest::Abort {
            StopCause::Abort
        } else {
            StopCause::Cancel
        };
        request_stop(cause, &mut stop, &mut cancel_deadline, sink);
    }

    let result = loop {
        tokio::select! {
            biased;
            result = &mut acp_future => break Some(result),
            Some(item) = inbound_rx.recv() => {
                let now = Instant::now();
                match item {
                    AcpInbound::Update { params, permit } => {
                        let signals = mapper.handle_update(&params, now, &mut out);
                        // Mapped: the line's share of the budget goes back to the splitter.
                        drop((params, permit));
                        if emit_all(sink, &mut mapper, &mut out) {
                            request_stop(StopCause::Leak, &mut stop, &mut cancel_deadline, sink);
                        }
                        for signal in signals {
                            match signal {
                                Signal::Guard(message) => request_stop(StopCause::Guard(message), &mut stop, &mut cancel_deadline, sink),
                                Signal::UsageLimit { resets_at, window } => request_stop(StopCause::UsageLimit { resets_at, window }, &mut stop, &mut cancel_deadline, sink),
                            }
                        }
                    }
                    AcpInbound::Prompting { session_id: id, not_resumed: why, agent_version } => {
                        mapper.set_usage_mode(UsageMode::for_agent(assign.agent.runtime, agent_version.as_deref()));
                        open_session(&mut mapper, sink, &id, why.as_ref());
                        sink.session_opened(&id);
                        session_id = Some(id);
                        not_resumed = Some(why);
                    }
                    AcpInbound::PermissionGranted { title, option } => {
                        sink.log(LogLevel::Debug, format!("auto-approved permission ({option}): {title}"));
                    }
                    AcpInbound::Warning(message) => {
                        tracing::warn!(run_id = %assign.run_id, %message, "agent session");
                        sink.log(LogLevel::Warn, message);
                    }
                    AcpInbound::Info(message) => {
                        sink.log(LogLevel::Info, message);
                    }
                }
            }
            Some(line) = log_rx.recv() => {
                if stderr_tail.len() >= STDERR_TAIL {
                    stderr_tail.remove(0);
                }
                stderr_tail.push(line.clone());
                if stderr_events < MAX_STDERR_EVENTS {
                    stderr_events += 1;
                    sink.log(LogLevel::Debug, line);
                }
            }
            _ = tick.tick() => {
                mapper.flush_due(Instant::now(), &mut out);
                if emit_all(sink, &mut mapper, &mut out) {
                    request_stop(StopCause::Leak, &mut stop, &mut cancel_deadline, sink);
                }
            }
            _ = async {
                // Re-arms whenever a message arrived in the meantime.
                loop {
                    let last = *activity.lock().unwrap_or_else(|e| e.into_inner());
                    let due = last + inactivity;
                    if tokio::time::Instant::now() >= due {
                        return;
                    }
                    tokio::time::sleep_until(due).await;
                }
            }, if stop.is_none() => {
                request_stop(
                    StopCause::Guard(format!(
                        "the agent sent no ACP message for {} s (inactivity timeout) and seems hung",
                        inactivity.as_secs()
                    )),
                    &mut stop,
                    &mut cancel_deadline,
                    sink,
                );
            }
            _ = tokio::time::sleep_until(wall_clock), if stop.is_none() => {
                let limit = shared
                    .opts
                    .max_wall_clock_cap
                    .map(|cap| cap.as_secs().min(assign.limits.max_wall_clock_seconds))
                    .unwrap_or(assign.limits.max_wall_clock_seconds);
                request_stop(
                    StopCause::TimeLimit(format!(
                        "the run reached its time limit ({})",
                        duration_words(limit)
                    )),
                    &mut stop,
                    &mut cancel_deadline,
                    sink,
                );
            }
            changed = stop_rx.changed() => {
                if changed.is_ok() {
                    let request = *stop_rx.borrow();
                    match request {
                        Some(StopRequest::Abort) => {
                            // Abort overrides any earlier cause.
                            stop = None;
                            request_stop(StopCause::Abort, &mut stop, &mut cancel_deadline, sink);
                            break None;
                        }
                        Some(StopRequest::Cancel) => request_stop(StopCause::Cancel, &mut stop, &mut cancel_deadline, sink),
                        None => {}
                    }
                }
            }
            _ = async {
                match cancel_deadline {
                    Some(t) => tokio::time::sleep_until(t).await,
                    None => std::future::pending().await,
                }
            } => {
                sink.log(LogLevel::Warn, "the agent did not end its turn after session/cancel; stopping the sandbox");
                break None;
            }
        }
    };

    // Drain what arrived before the prompt response.
    while let Ok(item) = inbound_rx.try_recv() {
        if let AcpInbound::Prompting {
            session_id: id,
            not_resumed: why,
            agent_version,
        } = item
        {
            mapper.set_usage_mode(UsageMode::for_agent(
                assign.agent.runtime,
                agent_version.as_deref(),
            ));
            open_session(&mut mapper, sink, &id, why.as_ref());
            session_id = Some(id);
            not_resumed = Some(why);
        } else if let AcpInbound::Update { params, .. } = item {
            let signals = mapper.handle_update(&params, Instant::now(), &mut out);
            if emit_all(sink, &mut mapper, &mut out) && stop.is_none() {
                stop = Some(StopCause::Leak);
            }
            for signal in signals {
                if stop.is_none()
                    && let Signal::UsageLimit { resets_at, window } = signal
                {
                    stop = Some(StopCause::UsageLimit { resets_at, window });
                }
            }
        }
    }
    while let Ok(line) = log_rx.try_recv() {
        if stderr_events < MAX_STDERR_EVENTS {
            stderr_events += 1;
            sink.log(LogLevel::Debug, line.clone());
        }
        if stderr_tail.len() >= STDERR_TAIL {
            stderr_tail.remove(0);
        }
        stderr_tail.push(line);
    }
    let usage_value = result
        .as_ref()
        .and_then(|r| r.outcome.as_ref().ok())
        .and_then(|o| prompt_usage(o.usage.as_ref(), o.meta.as_ref()).cloned());
    mapper.finish_prompt(usage_value.as_ref(), &mut out);
    if emit_all(sink, &mut mapper, &mut out) && stop.is_none() {
        stop = Some(StopCause::Leak);
    }
    let summary = mapper.summary().map(|s| sink.redact(&s));
    let (known_reset, known_window) = mapper.last_rate_limit_reset();
    if let Some(StopCause::UsageLimit { resets_at, window }) = &mut stop {
        if resets_at.is_none() {
            *resets_at = known_reset;
        }
        if window.is_none() {
            *window = known_window;
        }
    }
    AgentPhase {
        session_id: session_id.or_else(|| result.as_ref().and_then(|r| r.session_id.clone())),
        not_resumed,
        stop,
        result,
        summary,
        usage: mapper.usage(),
        stderr_tail,
    }
}

/// The session is open and the prompt about to be sent: logs the session the agent got, and
/// why it is not the resumed one.
fn open_session(
    mapper: &mut EventMapper,
    sink: &mut EventSink,
    id: &str,
    why: Option<&NotResumed>,
) {
    mapper.start_prompt();
    match why {
        None => mapper.session_resumed(),
        Some(NotResumed {
            outcome,
            error: Some(error),
        }) => {
            let level = if *outcome == ResumeOutcome::LoadFailed {
                LogLevel::Warn
            } else {
                LogLevel::Info
            };
            sink.log(
                level,
                format!(
                    "session/load failed ({}): {error}; starting a new session with the full prompt",
                    outcome.as_str()
                ),
            );
        }
        Some(NotResumed {
            outcome: ResumeOutcome::NotSupported,
            ..
        }) => sink.log(
            LogLevel::Info,
            "the agent does not support session/load; starting a new session with the full prompt",
        ),
        Some(_) => {}
    }
    sink.log(
        LogLevel::Info,
        match why {
            None => format!("resumed session {id} (session/load)"),
            Some(_) => format!("started session {id}"),
        },
    );
}

/// How long a run that lost a renewal race waits for the winner's renewed `auth.json` before it
/// takes an unchanged file as proof that the refresh token was used elsewhere: the provider may
/// refuse the loser while the winner still handles its answer (ADR-0085, decision 5).
const REFRESH_RACE_GRACE: Duration = Duration::from_secs(2);

/// Decides the failure (if any) of an agent turn that ended on its own.
async fn classify_agent_end(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    failure: &mut Failure,
    credential: &RunCredential,
    agent: &AgentPhase,
    codex_auth_at_start: crate::credentials::FileStamp,
) {
    let Some(result) = &agent.result else {
        failure.set(FailureClass::ProtocolError, "the agent did not answer");
        return;
    };
    match &result.outcome {
        Ok(outcome) => match outcome.stop_reason.as_str() {
            "end_turn" | "max_tokens" => {
                if let Some(summary) = &agent.summary
                    && summary.len() < 500
                    && limits::is_usage_limit_text(summary)
                {
                    usage_limit(
                        shared,
                        sink,
                        failure,
                        credential,
                        None,
                        None,
                        Some(summary.clone()),
                    );
                }
            }
            "max_turn_requests" => failure.set(
                FailureClass::GuardStopped,
                "the agent reached its own turn limit (max_turn_requests)",
            ),
            // Not a credential problem: no credential.status for it.
            "refusal" => failure.set(
                FailureClass::ModelRefused,
                "the model refused to continue (stop reason: refusal)",
            ),
            "cancelled" => failure.set(
                FailureClass::ProtocolError,
                "the agent cancelled the turn without being asked to",
            ),
            other => failure.set(
                FailureClass::ProtocolError,
                format!("unexpected stop reason `{other}`"),
            ),
        },
        Err((stage, error)) => {
            let mut error: AgentError = error.clone();
            if error.transport_closed && !agent.stderr_tail.is_empty() {
                error.message = format!(
                    "the agent process exited: {}",
                    agent
                        .stderr_tail
                        .iter()
                        .rev()
                        .take(5)
                        .rev()
                        .cloned()
                        .collect::<Vec<_>>()
                        .join(" | ")
                );
            }
            let mut class = limits::classify_error(&error);
            let mut message = sink.redact(&format!("{stage:?}: {}", error.text()));
            // A lost renewal race is a retry only when a run of this host renewed the login
            // since this run started (ADR-0085, decision 5). Otherwise the refresh token was
            // used elsewhere (a copy of the login on another machine), the file stays stale,
            // and every run would lose the same way: the login is expired.
            if class == FailureClass::ServerLimited
                && limits::is_refresh_race(&error)
                && let RunCredential::Host(c) = credential
                && c.kind == CredentialKind::CodexChatgptAuth
            {
                let home = shared.credentials.codex_home(&c.label);
                let mut now = crate::credentials::codex_auth_stamp(&home);
                if now == codex_auth_at_start {
                    // The winner may still be writing its renewed file: look once more.
                    tokio::time::sleep(REFRESH_RACE_GRACE).await;
                    now = crate::credentials::codex_auth_stamp(&home);
                }
                if now == codex_auth_at_start {
                    class = FailureClass::LoginExpired;
                    message.push_str(
                        "; no run of this host renewed the login since this run started, so the refresh token was used elsewhere: sign in again on the host",
                    );
                } else {
                    sink.log(
                        LogLevel::Info,
                        "another run of this host renewed the login while this run held the old refresh token; the turn can go again",
                    );
                }
            }
            match class {
                FailureClass::UsageLimit => {
                    usage_limit(
                        shared,
                        sink,
                        failure,
                        credential,
                        None,
                        None,
                        Some(error.text()),
                    );
                }
                FailureClass::LoginExpired => {
                    // A host credential is marked invalid on the host. For model access the gateway
                    // refused the company's key: the server marks the model credential INVALID when
                    // the run ends LOGIN_EXPIRED (docs/archive/v1/CONVENTIONS.md §14.8).
                    if let Some(label) = credential.host_label() {
                        shared.set_invalid(label, message.clone());
                    }
                    failure.set(class, message);
                }
                _ => failure.set(class, message),
            }
        }
    }
}

/// Usage limit: RATE_LIMIT event, credential cooldown, FAILED/USAGE_LIMIT.
///
/// For model access (ADR-0027) the host sends no `credential.status`: the server cools the
/// company's credential down, until the event's `resetsAt` when the runtime reported one.
fn usage_limit(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    failure: &mut Failure,
    credential: &RunCredential,
    resets_at: Option<chrono::DateTime<Utc>>,
    window: Option<String>,
    text: Option<String>,
) {
    let now = Utc::now();
    let parsed = text
        .as_deref()
        .and_then(|t| limits::parse_retry_after(t, now));
    let known = resets_at.or(parsed);
    let RunCredential::Host(credential) = credential else {
        let reported = known.filter(|t| *t > now);
        sink.emit(EventPayload::RateLimit(RateLimitPayload {
            status: RateLimitStatus::Rejected,
            utilization: Some(1.0),
            resets_at: reported,
            window: window.clone(),
        }));
        let detail = match (&window, reported) {
            (Some(w), Some(t)) => format!("usage limit ({w}) reached; resets at {t}"),
            (None, Some(t)) => format!("usage limit reached; resets at {t}"),
            (_, None) => "usage limit reached; reset time unknown".to_string(),
        };
        failure.set(FailureClass::UsageLimit, detail);
        return;
    };
    let until = known
        .filter(|t| *t > now)
        .unwrap_or_else(|| now + chrono::Duration::minutes(DEFAULT_COOLDOWN_MINUTES));
    sink.emit(EventPayload::RateLimit(RateLimitPayload {
        status: RateLimitStatus::Rejected,
        utilization: Some(1.0),
        resets_at: Some(until),
        window: window.clone(),
    }));
    let detail = match (&window, known) {
        (Some(w), Some(_)) => format!("usage limit ({w}) reached; resets at {until}"),
        (None, Some(_)) => format!("usage limit reached; resets at {until}"),
        (_, None) => format!("usage limit reached; reset time unknown, cooling down until {until}"),
    };
    shared.set_cooldown(&credential.label, until, detail.clone());
    failure.set(FailureClass::UsageLimit, detail);
}

/// The end of the git part of a run.
#[derive(Debug, Default)]
struct Finalized {
    /// One result per cloned repository, in `workspaces` order.
    results: Vec<GitResult>,
    /// An outgoing commit contains a credential value; nothing was pushed.
    outgoing_leak: bool,
    /// Repositories that failed, each with the reason (`name (/workspace/dir): reason`).
    errors: Vec<String>,
}

/// A run's time limit in words for its `failureMessage`: whole minutes as `60 min`, else seconds.
fn duration_words(seconds: u64) -> String {
    if seconds >= 60 && seconds.is_multiple_of(60) {
        format!("{} min", seconds / 60)
    } else {
        format!("{seconds} s")
    }
}

/// Why `git push --porcelain` failed, from its output (status lines on stdout, errors on
/// stderr). A lease that no longer holds ([`Git::push`]) shows as `[rejected] (stale info)`,
/// or, when the branch changed between git's check and the update, as a `[remote rejected]`
/// ref update with the wrong old value.
fn push_failure(output: &str) -> &'static str {
    if output.contains("(stale info)")
        || output.contains("(incorrect old value provided)")
        || (output.contains("cannot lock ref") && output.contains("but expected"))
        || (output.contains("[remote rejected]") && output.contains("reference already exists"))
    {
        "the remote branch changed during the run (someone else pushed to it or deleted it); nothing was pushed and nothing was overwritten"
    } else if output.contains("[rejected]")
        || output.contains("non-fast-forward")
        || output.contains("fetch first")
    {
        "the push was rejected (the remote branch has diverged)"
    } else if output.contains("[remote rejected]") {
        "the repository refused the push"
    } else {
        "git push failed"
    }
}

/// `git failed in …` for `failureMessage`, naming the repositories.
fn git_failure_message(errors: &[String], total: usize) -> String {
    match errors {
        [one] => format!("git failed in {one}"),
        _ => format!(
            "git failed in {} of {total} repositories: {}",
            errors.len(),
            errors.join("; ")
        ),
    }
}

/// Finish, for each repository (docs/archive/v1/CONVENTIONS.md §15.4 step 8): restore `.git/config`, WIP commit,
/// ahead count and credential scan for all of them first; then, only when no repository
/// (and not the agent's output) contained a credential, push each repository that is ahead
/// of its default branch, with a lease on the task branch's clone-time tip ([`Git::push`]).
/// A read-only repository (ADR-0017) is never pushed, so its commits are not scanned either.
/// A failed repository does not stop the others.
async fn finalize_git(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    repos: &[Repo<'_>],
    run_id: Uuid,
    leak: bool,
) -> Finalized {
    let mut finalized = Finalized::default();
    // Per repository: the ahead count, when it is ready to push.
    let mut ready: Vec<Option<u64>> = Vec::with_capacity(repos.len());
    let mut leaking = Vec::new();
    for repo in repos {
        let mut result = GitResult {
            component_id: repo.ws.component_id,
            branch: repo.ws.branch.clone(),
            pushed: false,
            head_sha: None,
            commits: None,
        };
        let scan = !leak && !repo.ws.read_only;
        match prepare_repository(shared, sink, repo, run_id, scan, &mut result).await {
            Ok(outgoing_leak) => {
                if outgoing_leak {
                    leaking.push(repo.label());
                }
                ready.push(result.commits);
            }
            Err(e) => {
                let reason = sink.redact(&format!("{e:#}"));
                finalized.errors.push(format!("{}: {reason}", repo.label()));
                ready.push(None);
            }
        }
        finalized.results.push(result);
    }
    if leak {
        sink.log(
            LogLevel::Warn,
            "not pushing: the run leaked a credential value",
        );
        return finalized;
    }
    if !leaking.is_empty() {
        sink.log(
            LogLevel::Error,
            format!(
                "not pushing any repository: a commit in {} contains a credential value",
                leaking.join(", ")
            ),
        );
        finalized.outgoing_leak = true;
        return finalized;
    }
    if repos
        .iter()
        .zip(&ready)
        .any(|(repo, c)| !repo.ws.read_only && c.is_some_and(|n| n > 0))
    {
        sink.status(Phase::Pushing, None);
    }
    for (i, repo) in repos.iter().enumerate() {
        let Some(commits) = ready[i] else { continue };
        let head = finalized.results[i].head_sha.clone();
        if commits == 0 {
            sink.git(repo.ws, GitAction::NothingToPush, head, Some(0));
            continue;
        }
        if repo.ws.read_only {
            sink.log(
                LogLevel::Info,
                format!(
                    "not pushing {}: the agent may only read it ({commits} commits are dropped)",
                    repo.label()
                ),
            );
            continue;
        }
        match repo.git.push(&repo.ws.branch).await {
            Ok(out) if out.status.success() => {
                finalized.results[i].pushed = true;
                sink.git(repo.ws, GitAction::Pushed, head, Some(commits));
            }
            Ok(out) => {
                let detail = sink.redact(out.stderr.trim());
                let reason = push_failure(&format!("{}\n{}", out.stdout, out.stderr));
                finalized
                    .errors
                    .push(format!("{}: {reason}: {detail}", repo.label()));
            }
            Err(e) => {
                let reason = sink.redact(&format!("{e:#}"));
                finalized.errors.push(format!("{}: {reason}", repo.label()));
            }
        }
    }
    finalized
}

/// Sanitize, WIP commit, ahead count and (with `scan`) the credential scan of one repository.
/// Fills `result`; `Ok(true)` when an outgoing commit contains a credential value.
async fn prepare_repository(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    repo: &Repo<'_>,
    run_id: Uuid,
    scan: bool,
    result: &mut GitResult,
) -> anyhow::Result<bool> {
    let git = &repo.git;
    let ws = repo.ws;
    git.sanitize()?;
    // With the bridge (ADR-0095), the sandbox made the WIP commit, and this clone, which the
    // agent never touched, has its commits already (collect_from_sandbox).
    let committed = match &repo.collected {
        Some(Err(message)) => {
            anyhow::bail!("the work did not come back from the sandbox: {message}")
        }
        Some(Ok(collected)) => collected.wip,
        None => {
            git.commit_all(&format!("WIP: uncommitted changes from run {run_id}"))
                .await?
        }
    };
    if committed {
        let head = git.head().await.ok();
        sink.git(ws, GitAction::Committed, head, None);
    }
    // What the host knows the repository has: the mirror's refs at clone time and, fetched
    // again now, its current refs. Never the workspace's own refs, which the agent controls.
    let current = if scan {
        match shared
            .mirrors
            .refresh(git.repo_url(), git.authorization())
            .await
        {
            Ok(refs) => refs,
            Err(e) => {
                let error = sink.redact(&format!("{e:#}"));
                sink.log(
                    LogLevel::Warn,
                    format!(
                        "cannot fetch {} again before the push; comparing with the repository as it was at clone time: {error}",
                        repo.label()
                    ),
                );
                Vec::new()
            }
        }
    } else {
        Vec::new()
    };
    let mut known = current.clone();
    known.extend_from_slice(git.base_refs());
    let shas = ref_shas(&known);
    let present = git.present(&shas).await?;
    let exclude: Vec<String> = shas.into_iter().filter(|s| present.contains(s)).collect();
    let base = [&current[..], git.base_refs()]
        .into_iter()
        .filter_map(|refs| branch_tip(refs, &ws.default_branch))
        .find(|sha| present.contains(*sha))
        .ok_or_else(|| {
            anyhow::anyhow!(
                "the default branch `{}` is not in the workspace",
                ws.default_branch
            )
        })?
        .to_string();
    let commits = git.count_ahead(&base).await?;
    result.commits = Some(commits);
    result.head_sha = git.head().await.ok();
    if !scan {
        return Ok(false);
    }
    git.outgoing_contains_secret(&shared.redactor(), &exclude)
        .await
}

/// What a run reports besides its outcome.
#[derive(Debug, Default)]
struct Report {
    summary: Option<String>,
    /// The agent's last `finish_run` (v1) or `finish` (v2).
    result: Option<RunResult>,
    session_id: Option<String>,
    /// Set when the agent opened a session: `None` inside when it resumed.
    not_resumed: Option<Option<NotResumed>>,
    git: Option<Vec<GitResult>>,
    usage: Option<tinfirm_protocol::UsagePayload>,
    /// v2: the files of `/output` saved as artifacts.
    artifacts: Vec<ArtifactResult>,
    /// v2, the feature `session.bundle`: what became of the run's session bundle.
    session_bundle: Option<tinfirm_protocol::SessionBundleOutcome>,
}

/// Saves the files of `/output` as artifacts of the job (protocol v2, [`crate::output`]):
/// each one scanned with the run's redactor and posted with the host token and
/// `artifact=true&source=output`. The log lines go out as LOG events; the run goes on
/// whatever happens.
async fn collect_output(
    shared: &Arc<Shared>,
    sink: &mut EventSink,
    assign: &RunAssign,
    output_root: &OutputRoot,
) -> Vec<ArtifactResult> {
    let redactor = shared.redactor();
    let server_url = shared.config.server_url.clone();
    let token = shared.config.token.expose().to_string();
    let (run_id, epoch) = (assign.run_id, assign.epoch);
    let collected = output::collect(
        output_root,
        shared.opts.upload_max_bytes,
        &|content| redactor.contains_any(content),
        |file: OutputFile| {
            let (server_url, token) = (server_url.clone(), token.clone());
            async move {
                let upload = Upload {
                    run_id,
                    epoch,
                    file_name: file.name,
                    content_type: file.content_type,
                    note: None,
                    content: file.content,
                    source: UploadSource::Output,
                };
                attachments::upload(&server_url, &token, upload)
                    .await
                    .map_err(|e| {
                        tracing::warn!(%run_id, error = %format!("{e:#}"), "cannot upload a file of /output");
                        "Tinfirm could not be reached, or did not answer.".to_string()
                    })
            }
        },
    )
    .await;
    for (level, message) in collected.log {
        sink.log(level, message);
    }
    if !collected.artifacts.is_empty() {
        sink.log(
            LogLevel::Info,
            format!(
                "{} file(s) of /output saved as artifacts of the job",
                collected.artifacts.len()
            ),
        );
    }
    collected.artifacts
}

/// `run.finished.resumed` and `resumeOutcome` (v2). A run without `resumeSessionId` is
/// FIRST_RUN even when it ended before opening a session; one with it that ended before
/// `session/load` has no outcome.
fn resume_fields(
    assign: &RunAssign,
    not_resumed: Option<Option<NotResumed>>,
) -> (bool, Option<ResumeOutcome>) {
    match (assign.resume_session_id.as_ref(), not_resumed) {
        (None, _) => (false, Some(ResumeOutcome::FirstRun)),
        (Some(_), Some(None)) => (true, None),
        (Some(_), Some(Some(why))) => (false, Some(why.outcome)),
        (Some(_), None) => (false, None),
    }
}

fn finish(
    sink: &mut EventSink,
    assign: &RunAssign,
    failure: Failure,
    cancelled: bool,
    report: Report,
) -> RunFinished {
    let Report {
        summary,
        result,
        session_id,
        not_resumed,
        git,
        usage,
        artifacts,
        session_bundle,
    } = report;
    let (resumed, resume_outcome, usage) = if sink.shared.speaks_v2() {
        let (resumed, outcome) = resume_fields(assign, not_resumed);
        (Some(resumed), outcome, usage)
    } else {
        // v1 has no `cacheWriteTokens`: its usage counts the cache writes as input.
        let usage = usage.map(tinfirm_protocol::UsagePayload::into_v1);
        (None, None, usage)
    };
    let (outcome, failure_class, failure_message) = match failure.0 {
        Some((class, message)) => (Outcome::Failed, Some(class), Some(message)),
        None if cancelled => (Outcome::Cancelled, None, None),
        None => (Outcome::Succeeded, None, None),
    };
    if let (Some(class), Some(message)) = (failure_class, &failure_message) {
        sink.emit(EventPayload::Error(ErrorPayload {
            message: message.clone(),
            failure_class: Some(class),
        }));
    }
    RunFinished {
        run_id: assign.run_id,
        epoch: assign.epoch,
        outcome,
        last_seq: sink.last_seq(),
        failure_class,
        failure_message: failure_message.map(|m| truncate_with_marker(&sink.redact(&m), 8 * 1024)),
        summary: summary.map(|s| truncate_with_marker(&s, SUMMARY_MAX)),
        // Redacted when the tool stored it; again here, as for everything that leaves the host.
        result: result.map(|r| RunResult {
            outcome: r.outcome,
            summary: sink.redact(&r.summary),
            next_steps: r.next_steps.iter().map(|s| sink.redact(s)).collect(),
            open_questions: r.open_questions.iter().map(|s| sink.redact(s)).collect(),
        }),
        session_id,
        git,
        usage,
        artifacts,
        resumed,
        resume_outcome,
        session_bundle,
    }
}

fn failed_dir(shared: &Shared) -> PathBuf {
    shared.opts.paths.data_dir.join("failed-workspaces")
}

/// Deletes a workspace and the run's `/output` folder (`outputs/<runId>`), or keeps the
/// workspace (the last N failed ones) for inspection, with the `/output` moved into it as
/// `output`.
fn cleanup_workspace(shared: &Arc<Shared>, workspace: &Path, keep: bool) {
    // The local test executor's stand-in for a sandbox with the bridge (crate::sandbox_files)
    // goes with the run.
    if let Some(root) = Layout::local(workspace).local_root
        && root.exists()
    {
        let _ = std::fs::remove_dir_all(&root);
    }
    let output = workspace
        .file_name()
        .map(|name| shared.opts.paths.outputs_dir().join(name))
        .filter(|output| output.exists());
    let remove_output = |output: &Path| {
        if let Err(e) = std::fs::remove_dir_all(output) {
            tracing::warn!(path = %output.display(), error = %e, "cannot delete the output folder");
        }
    };
    if !workspace.exists() {
        if let Some(output) = &output {
            remove_output(output);
        }
        return;
    }
    if keep && shared.opts.keep_failed_workspaces > 0 {
        let target_dir = failed_dir(shared);
        if std::fs::create_dir_all(&target_dir).is_ok()
            && let Some(name) = workspace.file_name()
        {
            let target = target_dir.join(name);
            let _ = std::fs::remove_dir_all(&target);
            if std::fs::rename(workspace, &target).is_ok() {
                if let Some(output) = &output {
                    // Into the kept workspace as `output`; a rename never follows a link the
                    // agent may have left at that name, and fails on anything else there.
                    let kept = target.join("output");
                    if std::fs::symlink_metadata(&kept).is_ok()
                        || std::fs::rename(output, &kept).is_err()
                    {
                        remove_output(output);
                    }
                }
                prune_failed(shared);
                return;
            }
        }
    }
    if let Err(e) = std::fs::remove_dir_all(workspace) {
        tracing::warn!(path = %workspace.display(), error = %e, "cannot delete the workspace");
    }
    if let Some(output) = &output {
        remove_output(output);
    }
}

fn prune_failed(shared: &Shared) {
    let Ok(entries) = std::fs::read_dir(failed_dir(shared)) else {
        return;
    };
    let mut dirs: Vec<(std::time::SystemTime, PathBuf)> = entries
        .filter_map(|e| e.ok())
        .filter_map(|e| {
            let modified = e.metadata().ok()?.modified().ok()?;
            Some((modified, e.path()))
        })
        .collect();
    dirs.sort_by_key(|(modified, _)| std::cmp::Reverse(*modified));
    for (_, path) in dirs.into_iter().skip(shared.opts.keep_failed_workspaces) {
        let _ = std::fs::remove_dir_all(path);
    }
}

/// Startup: workspaces, `/output` folders and attachments left by a previous process belong
/// to no run any more.
pub fn recover_workspaces(shared: &Arc<Shared>) {
    let live = shared.live_run_ids();
    // A run's workspace, and the local test executor's stand-in for its sandbox beside it
    // (`<runId>.sandbox`), which a run taken back after a restart still has (ADR-0101).
    let is_live = |entry: &std::fs::DirEntry| {
        entry
            .file_name()
            .to_str()
            .map(|n| n.strip_suffix(".sandbox").unwrap_or(n))
            .and_then(|n| Uuid::parse_str(n).ok())
            .is_some_and(|id| live.contains(&id))
    };
    if let Ok(entries) = std::fs::read_dir(shared.opts.paths.attachments_dir()) {
        for entry in entries.filter_map(|e| e.ok()) {
            if !is_live(&entry) {
                let _ = std::fs::remove_dir_all(entry.path());
            }
        }
    }
    if let Ok(entries) = std::fs::read_dir(shared.opts.paths.workspaces_dir()) {
        for entry in entries.filter_map(|e| e.ok()) {
            let path = entry.path();
            if !is_live(&entry) && path.is_dir() {
                cleanup_workspace(shared, &path, true);
            }
        }
    }
    // The `/output` of a workspace that is gone already.
    if let Ok(entries) = std::fs::read_dir(shared.opts.paths.outputs_dir()) {
        for entry in entries.filter_map(|e| e.ok()) {
            if !is_live(&entry) {
                let _ = std::fs::remove_dir_all(entry.path());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    type Split = (
        mpsc::Sender<SandboxOutput>,
        mpsc::Receiver<std::io::Result<AcpLine>>,
        mpsc::Receiver<String>,
    );

    fn splitter() -> Split {
        splitter_with_budget(acp::inbound_budget())
    }

    fn splitter_with_budget(budget: Arc<Semaphore>) -> Split {
        let (tx, rx) = mpsc::channel(8);
        let (line_tx, line_rx) = mpsc::channel(acp::ACP_LINE_QUEUE);
        let (log_tx, log_rx) = mpsc::channel(64);
        let activity: Activity = Arc::new(std::sync::Mutex::new(tokio::time::Instant::now()));
        spawn_output_splitter(rx, line_tx, budget, log_tx, activity);
        (tx, line_rx, log_rx)
    }

    /// The splitter stops reading the sandbox's output when the budget for lines in flight
    /// is taken, so the sandbox's writes wait; what the consumer takes lets it go on, and no
    /// line is lost or reordered.
    #[tokio::test]
    async fn the_splitter_waits_for_the_budget_of_lines_in_flight() {
        let total = 4096;
        let budget = Arc::new(Semaphore::new(total));
        let (tx, mut lines, _logs) = splitter_with_budget(budget.clone());
        // Each line holds 1000 bytes plus the overhead: two fit, the third waits.
        let line = |i: usize| format!("{{\"i\":{i},\"pad\":\"{}\"}}", "p".repeat(980));
        let cost = acp::line_cost(line(0).len()) as usize;
        assert!(2 * cost <= total && 3 * cost > total, "{cost}");
        let sent = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let producer = tokio::spawn({
            let sent = sent.clone();
            async move {
                for i in 0..40 {
                    tx.send(SandboxOutput::Stdout(format!("{}\n", line(i)).into_bytes()))
                        .await
                        .unwrap();
                    sent.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                }
            }
        });
        // Two lines hold their budget; the third waits for it in the splitter (reserving what
        // is left as it comes), with the chunk queue (8) full behind it: the producer blocks
        // on its 12th chunk.
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert!(budget.available_permits() <= total - 2 * cost);
        assert_eq!(sent.load(std::sync::atomic::Ordering::SeqCst), 11);
        assert!(
            !producer.is_finished(),
            "the producer blocked on the chunk queue"
        );
        // Taking a line and dropping it gives its budget back; the splitter goes on.
        let first = lines.recv().await.unwrap().unwrap();
        assert!(first.text.starts_with("{\"i\":0,"));
        drop(first);
        for i in 1..40 {
            let line = lines.recv().await.unwrap().unwrap();
            assert!(
                line.text.starts_with(&format!("{{\"i\":{i},")),
                "{}",
                line.text
            );
        }
        producer.await.unwrap();
        assert_eq!(budget.available_permits(), total, "every permit came back");
        assert!(
            lines.recv().await.is_none(),
            "the splitter ended with its input"
        );
    }

    /// A closed budget (the transport is gone) does not hold the splitter: the sandbox's
    /// stderr still reaches the run's log.
    #[tokio::test]
    async fn the_splitter_goes_on_without_the_transport() {
        let budget = Arc::new(Semaphore::new(4096));
        let (tx, lines, mut logs) = splitter_with_budget(budget.clone());
        drop(lines);
        budget.close();
        tx.send(SandboxOutput::Stdout(b"{\"a\":1}\n".to_vec()))
            .await
            .unwrap();
        tx.send(SandboxOutput::Stderr(b"still logged\n".to_vec()))
            .await
            .unwrap();
        assert_eq!(logs.recv().await.unwrap(), "still logged");
    }

    #[tokio::test]
    async fn the_splitter_ends_the_transport_on_an_acp_line_over_the_cap() {
        let (tx, mut lines, mut logs) = splitter();
        // A message split across chunks, then one byte more than the cap without a newline.
        tx.send(SandboxOutput::Stdout(b"{\"a\"".to_vec()))
            .await
            .unwrap();
        tx.send(SandboxOutput::Stdout(b":1}\r\n".to_vec()))
            .await
            .unwrap();
        let mut sent = 0;
        while sent < MAX_ACP_LINE + 1 {
            let n = (MAX_ACP_LINE + 1 - sent).min(64 * 1024);
            tx.send(SandboxOutput::Stdout(vec![b'x'; n])).await.unwrap();
            sent += n;
        }
        assert_eq!(lines.recv().await.unwrap().unwrap().text, "{\"a\":1}");
        let error = lines.recv().await.unwrap().unwrap_err();
        assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
        assert_eq!(
            error.to_string(),
            "an ACP line over 8 MiB on the agent's stdout"
        );
        assert!(lines.recv().await.is_none(), "the splitter ended");
        // Its input is not read any more: the sandbox's output stays where it is.
        assert!(
            tx.send(SandboxOutput::Stdout(b"more\n".to_vec()))
                .await
                .is_err()
        );
        let log = logs.recv().await.unwrap();
        assert!(log.contains("a line over 8 MiB"), "{log}");
        assert!(logs.recv().await.is_none());
    }

    #[tokio::test]
    async fn the_splitter_cuts_a_stderr_line_over_the_cap_and_goes_on() {
        let (tx, mut lines, mut logs) = splitter();
        tx.send(SandboxOutput::Stderr(vec![b'e'; MAX_LOG_LINE + 1]))
            .await
            .unwrap();
        tx.send(SandboxOutput::Stderr(b"tail\nnext\n".to_vec()))
            .await
            .unwrap();
        let cut = logs.recv().await.unwrap();
        assert_eq!(cut.len(), MAX_LOG_LINE + truncation_marker(5).len());
        assert!(cut.starts_with("eeee"));
        assert!(
            cut.ends_with("…[truncated 5 bytes]"),
            "{}",
            &cut[MAX_LOG_LINE..]
        );
        assert_eq!(logs.recv().await.unwrap(), "next");
        // The run goes on: ACP messages and plain stdout still come through.
        tx.send(SandboxOutput::Stdout(b"{\"b\":2}\nplain\n".to_vec()))
            .await
            .unwrap();
        assert_eq!(lines.recv().await.unwrap().unwrap().text, "{\"b\":2}");
        assert_eq!(logs.recv().await.unwrap(), "stdout: plain");
        // What is left without a newline comes out when the output ends.
        tx.send(SandboxOutput::Stderr(b"last".to_vec()))
            .await
            .unwrap();
        drop(tx);
        assert_eq!(logs.recv().await.unwrap(), "last");
        assert!(logs.recv().await.is_none());
        assert!(lines.recv().await.is_none());
    }

    #[test]
    fn names_a_time_limit_in_minutes_when_it_is_whole_minutes() {
        assert_eq!(duration_words(3600), "60 min");
        assert_eq!(duration_words(10_800), "180 min");
        assert_eq!(duration_words(90), "90 s");
        assert_eq!(duration_words(3), "3 s");
    }

    #[test]
    fn classifies_push_failures() {
        let changed = "the remote branch changed during the run (someone else pushed to it or deleted it); nothing was pushed and nothing was overwritten";
        // The lease no longer holds, as git sees it before the push.
        assert_eq!(
            push_failure(
                "!\tHEAD:refs/heads/agent/X-1\t[rejected] (stale info)\nDone\nerror: failed to push some refs to 'https://x/'"
            ),
            changed
        );
        // The branch changed between git's check and the update of the ref.
        assert_eq!(
            push_failure(
                "!\tHEAD:refs/heads/agent/X-1\t[remote rejected] (incorrect old value provided)\nremote: error: cannot lock ref 'refs/heads/agent/X-1': is at 1111111 but expected 2222222"
            ),
            changed
        );
        assert_eq!(
            push_failure(
                "!\tHEAD:refs/heads/agent/X-1\t[remote rejected] (cannot lock ref 'refs/heads/agent/X-1': is at 1111111 but expected 2222222)"
            ),
            changed
        );
        assert_eq!(
            push_failure(
                "!\tHEAD:refs/heads/agent/X-1\t[remote rejected] (reference already exists)"
            ),
            changed
        );
        assert_eq!(
            push_failure("!\tHEAD:refs/heads/agent/X-1\t[rejected] (non-fast-forward)\n"),
            "the push was rejected (the remote branch has diverged)"
        );
        assert_eq!(
            push_failure("!\tHEAD:refs/heads/agent/X-1\t[rejected] (fetch first)"),
            "the push was rejected (the remote branch has diverged)"
        );
        assert_eq!(
            push_failure(
                "!\tHEAD:refs/heads/agent/X-1\t[remote rejected] (pre-receive hook declined)"
            ),
            "the repository refused the push"
        );
        assert_eq!(
            push_failure(
                "fatal: unable to access 'https://x/': The requested URL returned error: 403"
            ),
            "git push failed"
        );
    }

    #[test]
    fn sandbox_settings_add_their_environment() {
        let host_env = || {
            vec![
                ("TINFIRM_RUN_ID".to_string(), "r1".to_string()),
                ("ANTHROPIC_MODEL".to_string(), "m".to_string()),
            ]
        };
        let mut env = host_env();
        add_sandbox_env(&mut env, &SandboxSettings::default());
        assert_eq!(env, host_env());

        let settings = SandboxSettings::new(
            Some("kata".into()),
            true,
            vec![
                crate::sandbox::parse_env_entry("TINFIRM_SANDBOX_NPM_REGISTRY=https://n.example/")
                    .unwrap(),
            ],
        )
        .unwrap();
        let mut env = host_env();
        add_sandbox_env(&mut env, &settings);
        assert_eq!(
            env[2..],
            [
                ("TINFIRM_SANDBOX_DOCKER".to_string(), "1".to_string()),
                (
                    "TINFIRM_SANDBOX_AGENT_USER".to_string(),
                    "1000:1000".to_string()
                ),
                (
                    "TINFIRM_SANDBOX_NPM_REGISTRY".to_string(),
                    "https://n.example/".to_string()
                ),
            ]
        );

        // A variable of the host keeps the host's value.
        let settings = SandboxSettings {
            env: vec![("ANTHROPIC_MODEL".into(), "other".into())],
            ..SandboxSettings::default()
        };
        let mut env = host_env();
        add_sandbox_env(&mut env, &settings);
        assert_eq!(env, host_env());
    }

    fn model_access(format: ModelFormat) -> ModelAccess {
        ModelAccess {
            format,
            base_url: Some("https://litellm.example.test".into()),
            region: None,
            access_key_id: None,
            api_key: tinfirm_protocol::Secret::new("sk-litellm-TEST-ONLY-key"),
        }
    }

    fn pairs(expected: &[(&str, &str)]) -> Vec<(String, String)> {
        expected
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    fn host_credential(kind: CredentialKind) -> RunCredential {
        RunCredential::Host(Credential {
            label: "c".into(),
            kind,
            pool_size: 1,
            created_at: Utc::now(),
            secret: Some(tinfirm_protocol::Secret::new("s3cret-value")),
        })
    }

    #[test]
    fn model_access_sets_the_variables_of_its_format() {
        let key = "sk-litellm-TEST-ONLY-key";
        let anthropic = RunCredential::Model(model_access(ModelFormat::Anthropic));
        assert_eq!(
            runtime_env(Runtime::ClaudeCode, anthropic.access(), None, None),
            pairs(&[
                ("ANTHROPIC_BASE_URL", "https://litellm.example.test"),
                ("ANTHROPIC_AUTH_TOKEN", key),
                ("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"),
            ])
        );
        assert_eq!(anthropic.host_label(), None);
        assert_eq!(anthropic.run_secrets(), vec![key.to_string()]);

        let openai = RunCredential::Model(model_access(ModelFormat::Openai));
        let codex = runtime_env(Runtime::Codex, openai.access(), Some("m1"), None);
        assert_eq!(
            codex[..3],
            pairs(&[
                ("OPENAI_BASE_URL", "https://litellm.example.test"),
                ("OPENAI_API_KEY", key),
                (
                    "DEFAULT_AUTH_REQUEST",
                    crate::runtime_env::OPENAI_API_KEY_AUTH
                ),
            ])
        );
        // The gateway as Codex's own provider, without WebSockets (runtime_env.rs).
        assert_eq!(codex.len(), 4);
        assert_eq!(codex[3].0, "CODEX_CONFIG");
        let config: serde_json::Value = serde_json::from_str(&codex[3].1).unwrap();
        assert_eq!(config["model"], "m1");
        assert_eq!(config["model_provider"], "tinfirm");
        assert_eq!(
            config["model_providers"]["tinfirm"]["base_url"],
            "https://litellm.example.test"
        );
        assert_eq!(
            config["model_providers"]["tinfirm"]["supports_websockets"],
            false
        );
        assert!(config.get("openai_base_url").is_none());

        // A provider's key without a gateway, for pi.
        let bedrock = RunCredential::Model(ModelAccess {
            base_url: None,
            region: Some("eu-west-1".into()),
            access_key_id: Some("AKIAIOSFODNN7EXAMPLE".into()),
            ..model_access(ModelFormat::Bedrock)
        });
        assert_eq!(
            runtime_env(Runtime::Pi, bedrock.access(), None, None),
            pairs(&[
                ("AWS_REGION", "eu-west-1"),
                ("AWS_DEFAULT_REGION", "eu-west-1"),
                ("AWS_ACCESS_KEY_ID", "AKIAIOSFODNN7EXAMPLE"),
                ("AWS_SECRET_ACCESS_KEY", key),
                ("TINFIRM_MODEL_PROVIDER", "amazon-bedrock"),
            ])
        );
    }

    #[test]
    fn the_agents_model_is_trimmed_and_optional() {
        let mut assign = test_assign();
        assert_eq!(agent_model(&assign), None);
        assign.agent.model = Some("  ".into());
        assert_eq!(agent_model(&assign), None);
        assign.agent.model = Some(" m1 ".into());
        assert_eq!(agent_model(&assign), Some("m1"));
    }

    fn test_assign() -> RunAssign {
        serde_json::from_value(serde_json::json!({
            "runId": uuid::Uuid::new_v4(),
            "epoch": 1,
            "modelAccess": {
                "format": "openai",
                "baseUrl": "https://litellm.example.test",
                "apiKey": "sk-litellm-TEST-ONLY-key"
            },
            "agent": {"id": uuid::Uuid::new_v4(), "name": "Tester", "runtime": "CODEX", "model": null},
            "task": {"id": uuid::Uuid::new_v4(), "key": "TEST-1", "title": "Test task"},
            "prompt": "Do the test task.",
            "workspaces": [],
            "requestableRepositories": [],
            "limits": {"maxWallClockSeconds": 600, "maxTurns": 10}
        }))
        .unwrap()
    }

    #[test]
    fn resume_fields_follow_the_assignment_and_the_session() {
        let first = test_assign();
        let why = |outcome| {
            Some(Some(NotResumed {
                outcome,
                error: None,
            }))
        };
        // Without resumeSessionId it is FIRST_RUN, whether a session was opened or not.
        assert_eq!(
            resume_fields(&first, None),
            (false, Some(ResumeOutcome::FirstRun))
        );
        assert_eq!(
            resume_fields(&first, why(ResumeOutcome::FirstRun)),
            (false, Some(ResumeOutcome::FirstRun))
        );
        let mut follow_up = test_assign();
        follow_up.resume_session_id = Some("s1".into());
        assert_eq!(resume_fields(&follow_up, Some(None)), (true, None));
        assert_eq!(
            resume_fields(&follow_up, why(ResumeOutcome::NoSession)),
            (false, Some(ResumeOutcome::NoSession))
        );
        // Ended before session/load (e.g. the clone failed): not resumed, not tried.
        assert_eq!(resume_fields(&follow_up, None), (false, None));
    }

    #[test]
    fn allowed_hosts_become_egress_rules_and_a_bad_entry_is_skipped() {
        let hosts: Vec<String> = [
            "registry.example.com",
            "*.cdn.example.com:8443",
            "https://example.com",
            "*",
            "example.com:0",
            "[2001:db8::1]:8080",
        ]
        .map(String::from)
        .into();
        let (rules, skipped) = allowed_host_rules(&hosts);
        assert_eq!(
            rules.iter().map(ToString::to_string).collect::<Vec<_>>(),
            [
                "registry.example.com:443",
                "*.cdn.example.com:8443",
                "[2001:db8::1]:8080"
            ]
        );
        assert_eq!(
            skipped.iter().map(|(e, _)| e.as_str()).collect::<Vec<_>>(),
            ["https://example.com", "*", "example.com:0"]
        );
        assert!(skipped.iter().all(|(_, why)| !why.is_empty()));
        assert_eq!(allowed_host_rules(&[]), (Vec::new(), Vec::new()));
    }

    #[test]
    fn wildcards_over_a_public_suffix_are_dropped_from_the_allowed_hosts() {
        let rules = |hosts: &[&str]| -> Vec<Rule> {
            hosts.iter().map(|h| Rule::parse(h).unwrap()).collect()
        };
        let hosts = [
            "*.github.io",
            "github.io",
            "*.tenant.github.io",
            "*.com.sg:8443",
            "registry.example.com",
            "*.example.com",
        ];
        let store = crate::public_suffix::PublicSuffixStore::in_memory();
        // Without a list nothing is dropped.
        let (kept, dropped) = drop_public_suffix_wildcards(rules(&hosts), &store);
        assert_eq!((kept.len(), dropped.len()), (6, 0));
        store
            .apply_update(&crate::public_suffix::tests::update_of(
                crate::public_suffix::tests::sample_list().as_bytes(),
            ))
            .unwrap();
        let (kept, dropped) = drop_public_suffix_wildcards(rules(&hosts), &store);
        assert_eq!(
            kept.iter().map(ToString::to_string).collect::<Vec<_>>(),
            [
                "github.io:443",
                "*.tenant.github.io:443",
                "registry.example.com:443",
                "*.example.com:443"
            ]
        );
        assert_eq!(
            dropped.iter().map(ToString::to_string).collect::<Vec<_>>(),
            ["*.github.io:443", "*.com.sg:8443"]
        );
    }

    #[test]
    fn allowed_hosts_default_to_none_and_are_read_from_the_frame() {
        assert!(test_assign().allowed_hosts.is_empty());
        let mut frame = serde_json::to_value(test_assign()).unwrap();
        frame["allowedHosts"] = serde_json::json!(["a.example.com"]);
        let assign: RunAssign = serde_json::from_value(frame).unwrap();
        assert_eq!(assign.allowed_hosts, ["a.example.com"]);
    }

    #[test]
    fn host_credentials_set_the_variables_of_their_kind() {
        let env = |kind, runtime| runtime_env(runtime, host_credential(kind).access(), None, None);
        // A Claude login signs the runtime in through the mounted credentials file.
        assert!(env(CredentialKind::ClaudeLogin, Runtime::ClaudeCode).is_empty());
        assert_eq!(
            env(CredentialKind::AnthropicApiKey, Runtime::ClaudeCode),
            pairs(&[("ANTHROPIC_API_KEY", "s3cret-value")])
        );
        assert_eq!(
            env(CredentialKind::OpenaiApiKey, Runtime::Codex),
            pairs(&[
                ("OPENAI_API_KEY", "s3cret-value"),
                ("DEFAULT_AUTH_REQUEST", r#"{"methodId":"api-key"}"#),
                ("CODEX_CONFIG", r#"{"web_search":"disabled"}"#),
            ])
        );
        // A ChatGPT login needs no variable to sign in, but Codex's web search is off in every
        // run (ADR-0108).
        assert_eq!(
            env(CredentialKind::CodexChatgptAuth, Runtime::Codex),
            pairs(&[("CODEX_CONFIG", r#"{"web_search":"disabled"}"#)])
        );
        // An API key of this host also serves pi and OpenCode.
        assert_eq!(
            env(CredentialKind::AnthropicApiKey, Runtime::Pi),
            pairs(&[
                ("ANTHROPIC_API_KEY", "s3cret-value"),
                ("TINFIRM_MODEL_PROVIDER", "anthropic"),
            ])
        );
        let opencode = env(CredentialKind::OpenaiApiKey, Runtime::Opencode);
        assert_eq!(
            opencode[0],
            ("OPENAI_API_KEY".into(), "s3cret-value".into())
        );
        assert_eq!(opencode[1].0, "OPENCODE_CONFIG_CONTENT");
        let host = host_credential(CredentialKind::ClaudeLogin);
        assert_eq!(host.host_label(), Some("c"));
        // The redactor of every run knows host credentials already.
        assert!(host.run_secrets().is_empty());
    }

    #[test]
    fn skills_are_bound_read_only_at_the_runtimes_directory_in_the_home() {
        let dir = tempfile::tempdir().unwrap();
        let paths = crate::paths::Paths::new(dir.path().join("config"), dir.path().join("data"));
        // Never contacted: the spec is only built.
        let docker = crate::sandbox::Executor::Docker(
            bollard::Docker::connect_with_http(
                "http://127.0.0.1:9",
                1,
                bollard::API_DEFAULT_VERSION,
            )
            .unwrap(),
        );
        let shared = Shared::new(
            crate::host::HostOptions::new(paths.clone(), docker),
            crate::config::HostConfig::new(
                "http://127.0.0.1:9",
                tinfirm_protocol::Secret::new("tfr_TESTONLYhosttokenforthesandboxspec0000123"),
            ),
        );
        let sha = "0".repeat(64);
        let pdf = ProvidedSkill::leased(&shared.skills, &sha, "pdf", 2);
        let workspace = paths.workspace(Uuid::new_v4());
        let home = dir.path().join("data/homes/a/t");
        for (runtime, format, skill_dir) in [
            (
                Runtime::ClaudeCode,
                ModelFormat::Anthropic,
                ".claude/skills",
            ),
            (Runtime::Codex, ModelFormat::Openai, ".agents/skills"),
            (Runtime::Opencode, ModelFormat::Openai, ".agents/skills"),
            (Runtime::Pi, ModelFormat::Anthropic, ".agents/skills"),
        ] {
            let mut assign = test_assign();
            assign.agent.runtime = runtime;
            let credential = RunCredential::Model(model_access(format));
            let spec = sandbox_spec(
                &shared,
                &assign,
                &credential,
                credential.access(),
                &Layout::container(),
                &workspace,
                &home,
                None,
                None,
                std::slice::from_ref(&pdf),
            );
            let bind = spec
                .binds
                .iter()
                .find(|b| b.host == paths.skill(&sha, "pdf"))
                .expect("the skill is bound");
            assert_eq!(bind.container, format!("/home/agent/{skill_dir}/pdf"));
            assert!(bind.read_only, "{runtime}");
            assert!(
                spec.binds
                    .iter()
                    .any(|b| b.container == crate::sandbox::HOME && !b.read_only)
            );
            let binds = crate::sandbox::container_body(&spec)
                .host_config
                .unwrap()
                .binds
                .unwrap();
            assert!(
                binds.contains(&format!(
                    "{}:/home/agent/{skill_dir}/pdf:ro",
                    paths.skill(&sha, "pdf").display()
                )),
                "{binds:?}"
            );
            let switch = spec
                .env
                .iter()
                .any(|(k, v)| k == crate::runtime_env::OPENCODE_SKILLS_SWITCH && v == "1");
            assert_eq!(switch, runtime == Runtime::Opencode, "{runtime}");
        }
        // Without skills, nothing is bound inside the home.
        let assign = test_assign();
        let credential = RunCredential::Model(model_access(ModelFormat::Openai));
        let spec = sandbox_spec(
            &shared,
            &assign,
            &credential,
            credential.access(),
            &Layout::container(),
            &workspace,
            &home,
            None,
            None,
            &[],
        );
        assert!(
            !spec
                .binds
                .iter()
                .any(|b| b.container.starts_with("/home/agent/")),
            "{:?}",
            spec.binds
        );
    }

    #[test]
    fn git_failures_name_the_repositories() {
        let one = vec!["`Server` (/workspace/server): git push failed: boom".to_string()];
        assert_eq!(
            git_failure_message(&one, 2),
            "git failed in `Server` (/workspace/server): git push failed: boom"
        );
        let two = vec![
            "`Server` (/workspace/server): a".to_string(),
            "`Web` (/workspace/web): b".to_string(),
        ];
        assert_eq!(
            git_failure_message(&two, 3),
            "git failed in 2 of 3 repositories: `Server` (/workspace/server): a; `Web` (/workspace/web): b"
        );
    }
}
