//! Ready sandbox VMs (ADR-0098; the Kubernetes runner analysis §6.3): the VM executor's pool.
//!
//! Every VM of the VM executor (crate::vm) comes from here, created for no run. Its user data
//! names none, its bridge's id is the VM's own, and its egress proxy listener refuses every
//! destination until a run binds it ([`crate::egress_proxy::EgressProxy::start_slot`]). So a
//! VM can wait for a run, connected and probed, and a run that arrives takes it in seconds.
//!
//! - **Shape.** A VM is created for one [`Shape`]: the runner image of a runtime, and the
//!   sandbox's own settings that its container starts with. A run takes only a VM of its own
//!   shape. The pool keeps ready VMs of one shape, its runtime's ([`PoolOptions::runtime`]);
//!   a run of another runtime starts cold. With the multi-runtime image
//!   ([`PoolOptions::multi_runtime`], ADR-0101) every runtime's runs have that one shape, and
//!   the run's adapter is named when it claims the VM.
//! - **Ready.** A VM is ready once its bridge has connected and its probe has passed. It holds
//!   no run data and no secret: those reach it over the bridge once a run claims it.
//! - **Claim.** A run takes a ready VM of its shape, or else waits for the next one that comes
//!   up, the one it causes or one already starting: a cold start ([`Pool::claim`]). A claimed
//!   VM never comes back; it is deleted after its run (ADR-0096).
//! - **Target.** The pool keeps `min(max(scheduled, ⌈busy / headroom⌉), maxReady, pool size −
//!   busy)` VMs of its shape ready and starting, where the pool size is the host's effective
//!   `maxConcurrentRuns` and busy its active runs ([`Pool::reconcile`]). Every claim starts a
//!   replacement; ready VMs above the target for `coolDown` go, and so does a ready VM older
//!   than `maxAge`, which the next pass replaces. While the host drains, after 15 minutes
//!   without the server, and with `maxReady` 0, it keeps none.
//!
//! The scheduled count is Tinfirm's ([`Pool::set_scheduled`]); the operator's ceilings and the
//! tuning are the host's ([`PoolOptions`]), so no setting can raise the cost above them.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use tinfirm_bridge::host::HostSession;
use tinfirm_protocol::Runtime;
use tokio::sync::{Notify, oneshot};
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::egress_proxy::EgressGuard;
use crate::host::{HostOptions, Shared};
use crate::vm::{VmGuard, Vms};

/// How often the pool looks at its target when nothing else wakes it.
const RECONCILE_EVERY: Duration = Duration::from_secs(5);
/// Without a server connection for this long, no run can arrive: the ready VMs go.
pub const OFFLINE_AFTER: Duration = Duration::from_secs(15 * 60);
/// The cold starts the pool remembers, for their 90th percentile.
const COLD_STARTS_KEPT: usize = 20;
/// The claims the pool counts, served ready and served cold.
const CLAIMS_KEPT_FOR: Duration = Duration::from_secs(24 * 3600);
/// After a VM could not be created, the pool starts none of its own for this long, doubled with
/// every further failure up to [`RETRY_MAX`]; a run that waits for a VM still gets one.
const RETRY_FIRST: Duration = Duration::from_secs(30);
const RETRY_MAX: Duration = Duration::from_secs(600);
/// The lead time of a window before the host has measured a cold start.
const DEFAULT_LEAD: Duration = Duration::from_secs(300);
/// `host.pool.status` goes out at most this often, unless a `host.config.applied` asks for one.
const STATUS_EVERY: Duration = Duration::from_secs(10);

/// The operator's ceilings and the tuning (`run --pool-*`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PoolOptions {
    /// The most ready VMs that Tinfirm may ask for; 0 turns the pool off.
    pub max_ready: u32,
    /// One more ready VM per this many busy ones; 0 is off.
    pub headroom: u32,
    /// A ready VM older than this is replaced.
    pub max_age: Duration,
    /// Ready VMs above the target for this long go.
    pub cool_down: Duration,
    /// The runtime whose runner image the ready VMs start: the pool's shape.
    pub runtime: Runtime,
    /// Every runtime's runs start from the multi-runtime image (`--pool-runtime any`,
    /// ADR-0101), so the ready VMs serve them all; `runtime` then does not matter.
    pub multi_runtime: bool,
    /// A VM that has waited longer than this is probed again when a run claims it: a cheap
    /// check that the provider's firewall has not changed since. 60 s; no flag sets it.
    pub reprobe_after: Duration,
    /// How early a schedule's window may start filling: the host's measured cold start, at
    /// most this.
    pub lead_time_max: Duration,
}

impl Default for PoolOptions {
    fn default() -> PoolOptions {
        PoolOptions {
            max_ready: 4,
            headroom: 0,
            max_age: Duration::from_secs(6 * 3600),
            cool_down: Duration::from_secs(600),
            runtime: Runtime::ClaudeCode,
            multi_runtime: false,
            reprobe_after: Duration::from_secs(60),
            lead_time_max: Duration::from_secs(1800),
        }
    }
}

/// What a VM is created as: the runner image its container runs, and the sandbox's own
/// settings that the container starts with (crate::bridge::stays_in_sandbox_settings), without
/// the egress proxy's, which are each VM's own. Sorted, so that two shapes compare.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Shape {
    pub image: String,
    pub settings: Vec<(String, String)>,
}

impl Shape {
    pub fn new(image: String, mut settings: Vec<(String, String)>) -> Shape {
        settings.sort();
        Shape { image, settings }
    }

    /// The shape of a run of `runtime` on this host: the same image and settings that the
    /// run's sandbox spec gets (crate::run::sandbox_spec).
    pub fn for_runtime(opts: &HostOptions, runtime: Runtime) -> Shape {
        let mut env = Vec::new();
        crate::run::add_sandbox_env(&mut env, &opts.sandbox);
        let (settings, _) = crate::bridge::split_env(&env);
        Shape::new(opts.image_for(runtime), settings)
    }
}

/// A VM that is up, its bridge connected and its probe passed, and that no run holds.
pub struct ReadyVm {
    vm: VmGuard,
    session: HostSession,
    egress: Option<EgressGuard>,
    sandbox_host: Option<String>,
    ticket: String,
    boot_settings: Vec<(String, String)>,
    shape: Shape,
    ready_at: Instant,
}

/// How a run got its VM.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Served {
    /// A VM was ready when the run asked.
    Ready,
    /// The run waited for one to come up.
    Cold,
}

/// A VM that a run claimed: the run's until it ends, then deleted.
pub struct Claimed {
    pub vm: VmGuard,
    /// The VM's bridge, connected; the adapter is not started yet.
    pub session: HostSession,
    /// The VM's egress proxy listener, which the run binds to its rules.
    pub egress: Option<EgressGuard>,
    /// The VM's own loopback in the VM executor's test mode (`VmOptions::on_this_machine`),
    /// where its tunnels listen; `None` for a real VM.
    pub sandbox_host: Option<String>,
    /// The VM's one-time ticket, used up when its bridge connected; it is in the VM's user
    /// data, so the run's log masks it.
    pub ticket: String,
    /// The settings that the VM's container started with: the shape's and the egress
    /// proxy's.
    pub boot_settings: Vec<(String, String)>,
    pub shape: Shape,
    /// When the VM became ready.
    pub ready_at: Instant,
    pub served: Served,
    /// From the claim to the VM.
    pub waited: Duration,
    /// Counts the VM busy until it is dropped.
    pub busy: Busy,
}

/// Counts a claimed VM busy in its pool while it lives.
pub struct Busy(Option<Arc<Pool>>);

impl Busy {
    /// Counted under the pool's lock, by the caller that holds it.
    fn counted(pool: &Arc<Pool>) -> Busy {
        Busy(Some(pool.clone()))
    }

    /// The count was taken back under the pool's lock: dropping it changes nothing.
    fn disarm(mut self) {
        self.0 = None;
    }
}

impl Drop for Busy {
    fn drop(&mut self) {
        if let Some(pool) = self.0.take() {
            {
                let mut state = lock(&pool.state);
                state.busy = state.busy.saturating_sub(1);
            }
            pool.wake.notify_one();
        }
    }
}

/// What the pool has now (diagnostics, and the host's report to Tinfirm).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct PoolStatus {
    pub ready: u32,
    pub starting: u32,
    pub busy: u32,
    /// The ready VMs the pool aims at now.
    pub target: u32,
    /// The 90th percentile of the last cold starts: from the create to a ready VM.
    pub cold_start_p90: Option<Duration>,
    /// Claims of the last 24 hours that a ready VM served, and that waited for one.
    pub served_ready: u32,
    pub served_cold: u32,
}

/// What the pool reads of its host in a pass.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HostView {
    /// The host's effective `maxConcurrentRuns`.
    pub pool_size: u32,
    pub active_runs: usize,
    pub draining: bool,
    pub connected: bool,
    /// The host has its id at the server, which every VM's labels name.
    pub registered: bool,
}

/// A run that waits for a VM of its shape.
struct Waiter {
    shape: Shape,
    answer: oneshot::Sender<std::result::Result<(ReadyVm, Busy), String>>,
}

#[derive(Default)]
struct State {
    ready: Vec<ReadyVm>,
    /// The shapes of the VMs being created, one entry each.
    starting: Vec<Shape>,
    waiters: VecDeque<Waiter>,
    busy: usize,
    target: u32,
    /// The host's effective `maxConcurrentRuns` at the last pass.
    pool_size: u32,
    above_target_since: Option<Instant>,
    offline_since: Option<Instant>,
    cold_starts: VecDeque<Duration>,
    claims: VecDeque<(Instant, Served)>,
    /// Creations that failed in a row, and until when the pool starts none of its own.
    failures: u32,
    retry_after: Option<Instant>,
    stopped: bool,
}

impl State {
    fn ready_of(&self, shape: &Shape) -> usize {
        self.ready.iter().filter(|r| &r.shape == shape).count()
    }

    fn starting_of(&self, shape: &Shape) -> usize {
        self.starting.iter().filter(|s| *s == shape).count()
    }

    fn waiting_for(&self, shape: &Shape) -> usize {
        self.waiters
            .iter()
            .filter(|w| &w.shape == shape && !w.answer.is_closed())
            .count()
    }

    fn started(&mut self, shape: &Shape) {
        if let Some(i) = self.starting.iter().position(|s| s == shape) {
            self.starting.swap_remove(i);
        }
    }

    fn claimed(&mut self, served: Served) {
        let now = Instant::now();
        self.claims.push_back((now, served));
        while self
            .claims
            .front()
            .is_some_and(|(at, _)| now.duration_since(*at) > CLAIMS_KEPT_FOR)
        {
            self.claims.pop_front();
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

/// Tinfirm's count of ready VMs (`host.config.pool`, ADR-0099), and its next change.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct Scheduled {
    target: u32,
    next: Option<(u32, Instant)>,
}

/// The VM executor's pool.
pub struct Pool {
    options: PoolOptions,
    vms: Arc<Vms>,
    /// The shape of the ready VMs.
    shape: Shape,
    state: Mutex<State>,
    /// 0 until Tinfirm sends a count.
    scheduled: Mutex<Scheduled>,
    /// A `host.pool.status` is due at the next pass.
    report_due: AtomicBool,
    wake: Notify,
}

impl std::fmt::Debug for Pool {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Pool")
            .field("options", &self.options)
            .field("image", &self.shape.image)
            .finish_non_exhaustive()
    }
}

impl Pool {
    pub fn new(vms: Arc<Vms>, options: PoolOptions, shape: Shape) -> Arc<Pool> {
        Arc::new(Pool {
            options,
            vms,
            shape,
            state: Mutex::new(State::default()),
            scheduled: Mutex::new(Scheduled::default()),
            report_due: AtomicBool::new(false),
            wake: Notify::new(),
        })
    }

    pub fn options(&self) -> &PoolOptions {
        &self.options
    }

    pub fn shape(&self) -> &Shape {
        &self.shape
    }

    /// From a VM's create to its bridge's first connection.
    pub fn start_timeout(&self) -> Duration {
        self.vms.options.start_timeout
    }

    /// Tinfirm's count of ready VMs, with no next change; the operator's `maxReady` bounds it.
    pub fn set_scheduled(&self, ready: u32) {
        self.set_schedule(ready, None);
    }

    /// Tinfirm's count of ready VMs and its next change (`host.config.pool`): the pool fills
    /// the next window early by its measured cold start, and follows the change by itself
    /// when its time comes before the server's next word. A `host.pool.status` goes out at
    /// the next pass.
    pub fn set_schedule(&self, ready: u32, next: Option<(u32, Instant)>) {
        *lock(&self.scheduled) = Scheduled {
            target: ready,
            next,
        };
        self.report_due.store(true, Ordering::Relaxed);
        self.wake.notify_one();
    }

    /// [`Pool::set_schedule`] from a `host.config`: `None` is 0.
    pub fn apply_config(&self, pool: Option<&tinfirm_protocol::PoolConfig>) {
        let Some(pool) = pool else {
            self.set_schedule(0, None);
            return;
        };
        let next = pool.next.as_ref().map(|next| {
            let ahead = (next.at - chrono::Utc::now())
                .to_std()
                .unwrap_or(Duration::ZERO);
            (next.ready_target, Instant::now() + ahead)
        });
        self.set_schedule(pool.ready_target, next);
    }

    /// The count the pool follows now: Tinfirm's, or already its next one, once that is due,
    /// or within the lead time of a higher one.
    pub fn scheduled(&self) -> u32 {
        let lead = self.lead_time(&lock(&self.state));
        self.scheduled_with(lead)
    }

    fn scheduled_with(&self, lead: Duration) -> u32 {
        let scheduled = *lock(&self.scheduled);
        let now = Instant::now();
        match scheduled.next {
            Some((count, at)) if now >= at => count,
            Some((count, at)) if count > scheduled.target && at.duration_since(now) <= lead => {
                count
            }
            _ => scheduled.target,
        }
    }

    /// How early a window starts filling: the 90th percentile of the cold starts, or five
    /// minutes before the first, at most `leadTimeMax`.
    fn lead_time(&self, state: &State) -> Duration {
        p90(&state.cold_starts)
            .unwrap_or(DEFAULT_LEAD)
            .min(self.options.lead_time_max)
    }

    /// Something that changes the target happened (the host's settings, a drain).
    pub fn poke(&self) {
        self.wake.notify_one();
    }

    pub fn status(&self) -> PoolStatus {
        let state = lock(&self.state);
        let cold_start_p90 = p90(&state.cold_starts);
        let now = Instant::now();
        let recent = |served: Served| {
            state
                .claims
                .iter()
                .filter(|(at, s)| *s == served && now.duration_since(*at) <= CLAIMS_KEPT_FOR)
                .count() as u32
        };
        PoolStatus {
            ready: state.ready.len() as u32,
            starting: state.starting.len() as u32,
            busy: state.busy as u32,
            target: state.target,
            cold_start_p90,
            served_ready: recent(Served::Ready),
            served_cold: recent(Served::Cold),
        }
    }

    /// A VM of `shape` for a run: a ready one, or else the next one of its shape that comes up
    /// before `deadline`, which this claim starts unless enough are starting already. Fails
    /// when the VM that the run waits for cannot be created.
    pub async fn claim(
        self: &Arc<Pool>,
        host: &Arc<Shared>,
        shape: Shape,
        deadline: Instant,
    ) -> Result<Claimed> {
        let asked = Instant::now();
        let mut evicted = Vec::new();
        let (answer, start) = {
            let mut state = lock(&self.state);
            if state.stopped {
                bail!("the runner host is stopping");
            }
            state
                .ready
                .retain(|r| !(r.shape == shape && r.session.has_ended()));
            if let Some(i) = state.ready.iter().position(|r| r.shape == shape) {
                let ready = state.ready.swap_remove(i);
                state.busy += 1;
                state.claimed(Served::Ready);
                drop(state);
                // Its replacement starts now.
                self.wake.notify_one();
                return Ok(claimed(ready, Busy::counted(self), Served::Ready, asked));
            }
            let (tx, rx) = oneshot::channel();
            state.waiters.push_back(Waiter {
                shape: shape.clone(),
                answer: tx,
            });
            let start = state.waiting_for(&shape) > state.starting_of(&shape);
            if start {
                state.starting.push(shape.clone());
                // A VM of another shape than the pool's: the ready ones make room, so that the
                // host never has more VMs than its pool size.
                state.ready.sort_by_key(|r| r.ready_at);
                while !state.ready.is_empty()
                    && state.ready.len() + state.starting.len() + state.busy
                        > state.pool_size.max(1) as usize
                {
                    evicted.push(state.ready.remove(0));
                }
            }
            (rx, start)
        };
        if start {
            let (pool, host) = (self.clone(), host.clone());
            tokio::spawn(async move {
                // The room first, so that the host never has more VMs than its pool size.
                for mut vm in evicted {
                    tracing::info!(vm = %vm.vm.id, "deleting a ready sandbox VM to make room for a run of another shape");
                    vm.vm.delete().await;
                }
                pool.create(host, shape).await;
            });
        }
        match tokio::time::timeout_at(deadline, answer).await {
            Ok(Ok(Ok((ready, busy)))) => Ok(claimed(ready, busy, Served::Cold, asked)),
            Ok(Ok(Err(message))) => Err(anyhow!(message)),
            Ok(Err(_)) => bail!("the runner host is stopping"),
            Err(_) => bail!(
                "no sandbox VM came up within {} s",
                deadline
                    .saturating_duration_since(asked)
                    .as_millis()
                    .div_ceil(1000)
            ),
        }
    }

    /// Creates a VM of `shape` and hands it to the first run that waits for its shape, or
    /// keeps it ready.
    async fn create(self: Arc<Pool>, host: Arc<Shared>, shape: Shape) {
        let started = Instant::now();
        match self.boot(&host, &shape).await {
            Ok(ready) => {
                {
                    let mut state = lock(&self.state);
                    state.cold_starts.push_back(started.elapsed());
                    while state.cold_starts.len() > COLD_STARTS_KEPT {
                        state.cold_starts.pop_front();
                    }
                }
                tracing::info!(
                    vm = %ready.vm.id,
                    seconds = started.elapsed().as_secs(),
                    "sandbox VM ready"
                );
                self.arrived(ready);
            }
            Err(e) => self.failed(&shape, format!("{e:#}")),
        }
    }

    /// Creates the VM, waits until the provider has it up, dials its bridge and probes it.
    async fn boot(&self, host: &Arc<Shared>, shape: &Shape) -> Result<ReadyVm> {
        let options = &self.vms.options;
        let listener = host.bridge().await?.context(
            "the sandboxes have no network, so the VMs' bridges cannot be reached from this host",
        )?;
        let id = Uuid::new_v4().to_string();
        let registration = listener.server.register(&format!("vm-{id}"))?;
        // Where the VM's tunnels listen in the test mode, whose "VMs" share this machine's
        // network: a loopback address of their own.
        let sandbox_host = options
            .on_this_machine
            .then(|| crate::bridge::local_sandbox_loopback().to_string());
        let egress = match host.egress_proxy().await? {
            Some(proxy) => Some(proxy.start_slot(sandbox_host.as_deref()).await?),
            None => None,
        };
        let mut boot_settings = shape.settings.clone();
        if let Some(egress) = &egress {
            boot_settings.extend(
                egress
                    .env()
                    .into_iter()
                    .filter(|(key, _)| crate::bridge::stays_in_sandbox_settings(key)),
            );
        }
        boot_settings.sort();
        let bootstrap = crate::bridge::bootstrap_env(&registration, &listener.sandbox_addr());
        let ticket = registration.ticket().to_string();
        let data = crate::vm::user_data(&shape.image, &boot_settings, &bootstrap, options.port);
        let labels = Vms::labels(&host.host_label(), &id);
        let name = format!("tinfirm-vm-{}", &id[..8]);
        let deadline = Instant::now() + options.start_timeout;
        let mut vm = self.vms.create(&name, &data, &labels).await?;
        let address = match self.vms.address(&vm.id, deadline).await {
            Ok(address) => address,
            Err(e) => {
                vm.delete().await;
                return Err(e);
            }
        };
        vm.set_address(&address);
        tracing::debug!(vm = %vm.id, %address, "dialing the sandbox VM's bridge");
        registration.dial(crate::git_proxy::host_port(&address, options.port));
        // `--vm-start-timeout` bounds the time from the create to the bridge's connection.
        let wait = deadline.saturating_duration_since(Instant::now());
        let connected = tokio::select! {
            session = registration.connected(wait) => session,
            gone = self.vms.ended(&vm.id) => Err(anyhow!(
                "the VM ended before its bridge connected ({gone})"
            )),
        };
        let session = match connected {
            Ok(session) => session,
            Err(e) => {
                vm.delete().await;
                return Err(e);
            }
        };
        if let Err(e) = self.vms.check_image(&vm.id, session.bridge_build()) {
            vm.delete().await;
            return Err(e);
        }
        if let Err(e) = crate::sandbox::probe(&session, &options.probe).await {
            vm.delete().await;
            return Err(e);
        }
        Ok(ReadyVm {
            vm,
            session,
            egress,
            sandbox_host,
            ticket,
            boot_settings,
            shape: shape.clone(),
            ready_at: Instant::now(),
        })
    }

    /// A VM came up: the first run that waits for its shape takes it; else it stays ready
    /// while the pool is below its target, and goes otherwise.
    fn arrived(self: &Arc<Pool>, ready: ReadyVm) {
        let mut ready = ready;
        let doomed = {
            let mut state = lock(&self.state);
            state.started(&ready.shape);
            state.failures = 0;
            state.retry_after = None;
            loop {
                if state.stopped {
                    break Some(ready);
                }
                let Some(i) = state
                    .waiters
                    .iter()
                    .position(|w| w.shape == ready.shape && !w.answer.is_closed())
                else {
                    let keep = ready.shape == self.shape
                        && (state.ready_of(&self.shape) as u32) < state.target;
                    if keep {
                        state.ready.push(ready);
                        break None;
                    }
                    break Some(ready);
                };
                let waiter = state.waiters.remove(i).expect("found");
                state.busy += 1;
                match waiter.answer.send(Ok((ready, Busy::counted(self)))) {
                    Ok(()) => {
                        state.claimed(Served::Cold);
                        break None;
                    }
                    // The run gave up just now: the next one, if any, takes it.
                    Err(Ok((back, busy))) => {
                        state.busy -= 1;
                        busy.disarm();
                        ready = back;
                    }
                    Err(Err(_)) => unreachable!("the pool sends only VMs"),
                }
            }
        };
        if let Some(doomed) = doomed {
            tracing::info!(vm = %doomed.vm.id, "sandbox VM not needed; deleting it");
        }
        self.wake.notify_one();
    }

    /// A VM of `shape` could not be created: a run that waits for that shape and that no other
    /// VM being created will serve fails with `message`.
    fn failed(&self, shape: &Shape, message: String) {
        tracing::warn!(error = %message, "cannot create a sandbox VM");
        let mut state = lock(&self.state);
        state.started(shape);
        state.failures = state.failures.saturating_add(1);
        let pause = RETRY_FIRST
            .saturating_mul(1 << (state.failures - 1).min(5))
            .min(RETRY_MAX);
        state.retry_after = Some(Instant::now() + pause);
        if state.waiting_for(shape) > state.starting_of(shape)
            && let Some(i) = state
                .waiters
                .iter()
                .position(|w| &w.shape == shape && !w.answer.is_closed())
        {
            let waiter = state.waiters.remove(i).expect("found");
            let _ = waiter.answer.send(Err(message));
        }
        drop(state);
        self.wake.notify_one();
    }

    /// One pass: the VMs that went or are too old leave, the target follows the host, VMs
    /// start until the ready and starting ones meet it, and those above it for `coolDown` go.
    pub fn reconcile(self: &Arc<Pool>, host: &Arc<Shared>, view: HostView) {
        let now = Instant::now();
        let mut doomed: Vec<(ReadyVm, &'static str)> = Vec::new();
        let start = {
            let mut state = lock(&self.state);
            if state.stopped {
                return;
            }
            if view.connected {
                state.offline_since = None;
            } else if state.offline_since.is_none() {
                state.offline_since = Some(now);
            }
            let offline = state
                .offline_since
                .is_some_and(|since| now.duration_since(since) >= OFFLINE_AFTER);
            let mut kept = Vec::with_capacity(state.ready.len());
            for ready in std::mem::take(&mut state.ready) {
                if ready.session.has_ended() {
                    doomed.push((ready, "its bridge is gone"));
                } else if now.duration_since(ready.ready_at) >= self.options.max_age {
                    doomed.push((ready, "it is older than the pool's maxAge"));
                } else {
                    kept.push(ready);
                }
            }
            state.ready = kept;
            state.waiters.retain(|w| !w.answer.is_closed());

            let off = self.options.max_ready == 0 || view.draining || offline;
            let busy = state.busy.max(view.active_runs) as u32;
            let target = if off || !view.registered {
                0
            } else {
                let headroom = match self.options.headroom {
                    0 => 0,
                    per => busy.div_ceil(per),
                };
                self.scheduled_with(self.lead_time(&state))
                    .max(headroom)
                    .min(self.options.max_ready)
                    .min(view.pool_size.saturating_sub(busy))
            };
            state.target = target;
            state.pool_size = view.pool_size;

            let ready = state.ready_of(&self.shape) as u32;
            let starting = state.starting_of(&self.shape) as u32;
            let waiting = state.waiting_for(&self.shape) as u32;
            let have = ready + starting.saturating_sub(waiting);
            let paused = state.retry_after.is_some_and(|until| now < until);
            let start = if have < target && view.connected && view.registered && !paused {
                target - have
            } else {
                0
            };
            for _ in 0..start {
                state.starting.push(self.shape.clone());
            }

            if ready > target {
                let due = off
                    || state
                        .above_target_since
                        .is_some_and(|since| now.duration_since(since) >= self.options.cool_down);
                if due {
                    // The oldest go first.
                    let mut extra = ready - target;
                    state.ready.sort_by_key(|r| r.ready_at);
                    let mut kept = Vec::with_capacity(state.ready.len());
                    for vm in std::mem::take(&mut state.ready) {
                        if extra > 0 && vm.shape == self.shape {
                            extra -= 1;
                            doomed.push((vm, "the pool is above its target"));
                        } else {
                            kept.push(vm);
                        }
                    }
                    state.ready = kept;
                    state.above_target_since = None;
                } else if state.above_target_since.is_none() {
                    state.above_target_since = Some(now);
                }
            } else {
                state.above_target_since = None;
            }
            start
        };
        for (vm, why) in doomed {
            tracing::info!(vm = %vm.vm.id, why, "deleting a ready sandbox VM");
        }
        for _ in 0..start {
            tokio::spawn(self.clone().create(host.clone(), self.shape.clone()));
        }
    }

    /// Runs passes until `stop`, then deletes the ready VMs ([`Pool::stop`]). After each pass
    /// the host reports its sandboxes to the server when they changed, at most every 10
    /// seconds, and when a `host.config` asked for it ([`Pool::set_schedule`]).
    pub async fn run(self: Arc<Pool>, host: Arc<Shared>, stop: CancellationToken) {
        let mut sent: Option<(tinfirm_protocol::HostPoolStatus, Instant)> = None;
        loop {
            self.reconcile(&host, host.pool_view());
            if host.is_connected() {
                let report = self.report();
                let due = self.report_due.swap(false, Ordering::Relaxed);
                let changed = sent.as_ref().is_none_or(|(last, _)| *last != report);
                let spaced = sent
                    .as_ref()
                    .is_none_or(|(_, at)| at.elapsed() >= STATUS_EVERY);
                if due || (changed && spaced) {
                    host.send_pool_status(report.clone());
                    sent = Some((report, Instant::now()));
                }
            } else {
                // The next connection gets the status afresh.
                sent = None;
            }
            tokio::select! {
                _ = stop.cancelled() => break,
                _ = self.wake.notified() => {}
                _ = tokio::time::sleep(RECONCILE_EVERY) => {}
            }
        }
        self.stop().await;
    }

    /// `host.pool.status` of the pool now.
    pub fn report(&self) -> tinfirm_protocol::HostPoolStatus {
        let status = self.status();
        tinfirm_protocol::HostPoolStatus {
            ready: status.ready,
            starting: status.starting,
            busy: status.busy,
            cold_start_p90_seconds: status.cold_start_p90.map(|d| d.as_secs()),
            served_ready: status.served_ready,
            served_cold: status.served_cold,
        }
    }

    /// The host stops: no more claims, the waiting runs fail, and the ready VMs are deleted.
    /// VMs still being created are deleted when they come up.
    pub async fn stop(&self) {
        let ready = {
            let mut state = lock(&self.state);
            state.stopped = true;
            for waiter in state.waiters.drain(..) {
                let _ = waiter
                    .answer
                    .send(Err("the runner host is stopping".to_string()));
            }
            std::mem::take(&mut state.ready)
        };
        let deletes = ready.into_iter().map(|mut ready| async move {
            ready.vm.delete().await;
        });
        let _ =
            tokio::time::timeout(Duration::from_secs(60), futures::future::join_all(deletes)).await;
    }
}

/// The 90th percentile of the cold starts; `None` before the first.
fn p90(starts: &VecDeque<Duration>) -> Option<Duration> {
    let mut starts: Vec<Duration> = starts.iter().copied().collect();
    starts.sort();
    (!starts.is_empty()).then(|| {
        let rank = (starts.len() * 9).div_ceil(10).max(1);
        starts[rank - 1]
    })
}

fn claimed(ready: ReadyVm, busy: Busy, served: Served, asked: Instant) -> Claimed {
    Claimed {
        vm: ready.vm,
        session: ready.session,
        egress: ready.egress,
        sandbox_host: ready.sandbox_host,
        ticket: ready.ticket,
        boot_settings: ready.boot_settings,
        shape: ready.shape,
        ready_at: ready.ready_at,
        served,
        waited: asked.elapsed(),
        busy,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shapes_compare_whatever_the_order_of_their_settings() {
        let a = Shape::new(
            "tinfirm-runner-claude:local".into(),
            vec![
                ("TINFIRM_SANDBOX_DOCKER".into(), "1".into()),
                ("TINFIRM_SANDBOX_AGENT_USER".into(), "1000:1000".into()),
            ],
        );
        let b = Shape::new(
            "tinfirm-runner-claude:local".into(),
            vec![
                ("TINFIRM_SANDBOX_AGENT_USER".into(), "1000:1000".into()),
                ("TINFIRM_SANDBOX_DOCKER".into(), "1".into()),
            ],
        );
        assert_eq!(a, b);
        assert_ne!(
            a,
            Shape::new("tinfirm-runner-codex:local".into(), b.settings.clone())
        );
    }
}
