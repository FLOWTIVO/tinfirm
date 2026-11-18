//! Ready sandbox VMs (ADR-0098, ADR-0099; the Kubernetes runner analysis §6.3): VMs that wait
//! for a run, connected and probed, so that a run that arrives takes one in seconds. Part of
//! elastic runners, Pro's and Enterprise's (ADR-0100, ADR-0114): the protocol v2 feature
//! `sandbox.pool`. This file is under the Tinfirm Enterprise licence (`ee/LICENSE`); the
//! runner host compiles it in (`tinfirm-runner-host/src/lib.rs`).
//!
//! The VMs themselves are the core's (crate::pool): it creates, boots, probes, hands out and
//! deletes every VM, with its guards. [`ReadyVms`] is the pool's [`Keeper`]: it holds the VMs
//! that came up for no run, decides how many wait, and asks the pool to start them
//! ([`Pool::plan`]). It is on with `run --pool-max-ready` above 0; without it, every run
//! starts cold.
//!
//! - **Shape.** The ready VMs are of one [`Shape`], their runtime's
//!   ([`ReadyVmOptions::runtime`]); a run of another runtime starts cold. With the
//!   multi-runtime image ([`ReadyVmOptions::multi_runtime`], ADR-0101) every runtime's runs
//!   have that one shape, and the run's adapter is named when it claims the VM.
//! - **Claim.** A run takes a ready VM of its shape, and its replacement starts at once. A run
//!   of another shape that starts a VM of its own makes the ready VMs make room, so that the
//!   host never has more VMs than its pool size.
//! - **Target.** The host keeps `min(max(scheduled, ⌈busy / headroom⌉), maxReady, pool size −
//!   busy)` VMs of its shape ready and starting, where the pool size is the host's effective
//!   `maxConcurrentRuns` and busy its active runs ([`ReadyVms::reconcile`]). Ready VMs above
//!   the target for `coolDown` go, and so does a ready VM older than `maxAge`, which the next
//!   pass replaces. While the host drains, after 15 minutes without the server, and with
//!   `maxReady` 0, it keeps none.
//!
//! The scheduled count is Tinfirm's ([`ReadyVms::set_scheduled`], `host.config.pool`); the
//! operator's ceilings and the tuning are the host's ([`ReadyVmOptions`]), so no setting can
//! raise the cost above them. The host reports its ready VMs to the server in
//! `host.pool.status`.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::Duration;

use tinfirm_protocol::Runtime;
use tokio::sync::Notify;
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

use crate::host::Shared;
use crate::pool::{Counts, IdleVm, Keeper, Pool, Served, Shape};

/// How often the host looks at its target when nothing else wakes it.
const RECONCILE_EVERY: Duration = Duration::from_secs(5);
/// Without a server connection for this long, no run can arrive: the ready VMs go.
pub const OFFLINE_AFTER: Duration = Duration::from_secs(15 * 60);
/// The cold starts the host remembers, for their 90th percentile.
const COLD_STARTS_KEPT: usize = 20;
/// The claims the host counts, served ready and served cold.
const CLAIMS_KEPT_FOR: Duration = Duration::from_secs(24 * 3600);
/// After a VM could not be created, the host starts no ready VM for this long, doubled with
/// every further failure up to [`RETRY_MAX`]; a run that waits for a VM still gets one.
const RETRY_FIRST: Duration = Duration::from_secs(30);
const RETRY_MAX: Duration = Duration::from_secs(600);
/// The lead time of a window before the host has measured a cold start.
const DEFAULT_LEAD: Duration = Duration::from_secs(300);
/// `host.pool.status` goes out at most this often, unless a `host.config.applied` asks for one.
const STATUS_EVERY: Duration = Duration::from_secs(10);

/// The operator's ceilings and the tuning (`run --pool-*`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReadyVmOptions {
    /// The most ready VMs that Tinfirm may ask for; 0, the default, turns them off.
    pub max_ready: u32,
    /// One more ready VM per this many busy ones; 0 is off.
    pub headroom: u32,
    /// A ready VM older than this is replaced.
    pub max_age: Duration,
    /// Ready VMs above the target for this long go.
    pub cool_down: Duration,
    /// The runtime whose runner image the ready VMs start: their shape.
    pub runtime: Runtime,
    /// Every runtime's runs start from the multi-runtime image (`--pool-runtime any`,
    /// ADR-0101), so the ready VMs serve them all; `runtime` then does not matter.
    pub multi_runtime: bool,
    /// How early a schedule's window may start filling: the host's measured cold start, at
    /// most this.
    pub lead_time_max: Duration,
}

impl Default for ReadyVmOptions {
    fn default() -> ReadyVmOptions {
        ReadyVmOptions {
            max_ready: 0,
            headroom: 0,
            max_age: Duration::from_secs(6 * 3600),
            cool_down: Duration::from_secs(600),
            runtime: Runtime::ClaudeCode,
            multi_runtime: false,
            lead_time_max: Duration::from_secs(1800),
        }
    }
}

/// What the host has now (diagnostics, and its report to Tinfirm).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct PoolStatus {
    pub ready: u32,
    pub starting: u32,
    pub busy: u32,
    /// The ready VMs the host aims at now.
    pub target: u32,
    /// The 90th percentile of the last cold starts: from the create to a VM that is up.
    pub cold_start_p90: Option<Duration>,
    /// Claims of the last 24 hours that a ready VM served, and that waited for one.
    pub served_ready: u32,
    pub served_cold: u32,
}

/// What the ready VMs read of their host in a pass.
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

#[derive(Default)]
struct State {
    ready: Vec<IdleVm>,
    target: u32,
    /// The host's effective `maxConcurrentRuns` at the last pass.
    pool_size: u32,
    above_target_since: Option<Instant>,
    offline_since: Option<Instant>,
    cold_starts: VecDeque<Duration>,
    claims: VecDeque<(Instant, Served)>,
    /// Creations that failed in a row, and until when the host starts no ready VM.
    failures: u32,
    retry_after: Option<Instant>,
}

impl State {
    fn ready_of(&self, shape: &Shape) -> usize {
        self.ready.iter().filter(|r| r.shape() == shape).count()
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

/// The host's ready VMs: the keeper of its pool (crate::pool).
pub struct ReadyVms {
    options: ReadyVmOptions,
    /// The shape of the ready VMs.
    shape: Shape,
    /// The pool it keeps VMs of ([`ReadyVms::attach`]).
    pool: OnceLock<Weak<Pool>>,
    state: Mutex<State>,
    /// 0 until Tinfirm sends a count.
    scheduled: Mutex<Scheduled>,
    /// A `host.pool.status` is due at the next pass.
    report_due: AtomicBool,
    wake: Notify,
}

impl std::fmt::Debug for ReadyVms {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ReadyVms")
            .field("options", &self.options)
            .field("image", &self.shape.image)
            .finish_non_exhaustive()
    }
}

impl ReadyVms {
    /// Ready VMs of `shape`; [`ReadyVms::attach`] it to the pool that it is the keeper of.
    pub fn new(options: ReadyVmOptions, shape: Shape) -> Arc<ReadyVms> {
        Arc::new(ReadyVms {
            options,
            shape,
            pool: OnceLock::new(),
            state: Mutex::new(State::default()),
            scheduled: Mutex::new(Scheduled::default()),
            report_due: AtomicBool::new(false),
            wake: Notify::new(),
        })
    }

    /// The pool of `vms`, with these ready VMs as its keeper.
    pub fn pool(self: &Arc<ReadyVms>, vms: Arc<crate::vm::Vms>) -> Arc<Pool> {
        let pool = Pool::new(vms, Some(self.clone() as Arc<dyn Keeper>));
        self.attach(&pool);
        pool
    }

    /// The pool these ready VMs are the keeper of: they start their VMs through it.
    pub fn attach(&self, pool: &Arc<Pool>) {
        let _ = self.pool.set(Arc::downgrade(pool));
    }

    fn the_pool(&self) -> Option<Arc<Pool>> {
        self.pool.get().and_then(Weak::upgrade)
    }

    pub fn options(&self) -> &ReadyVmOptions {
        &self.options
    }

    pub fn shape(&self) -> &Shape {
        &self.shape
    }

    /// Tinfirm's count of ready VMs, with no next change; the operator's `maxReady` bounds it.
    pub fn set_scheduled(&self, ready: u32) {
        self.set_schedule(ready, None);
    }

    /// Tinfirm's count of ready VMs and its next change (`host.config.pool`): the host fills
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

    /// [`ReadyVms::set_schedule`] from a `host.config`: `None` is 0.
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

    /// The count the host follows now: Tinfirm's, or already its next one, once that is due,
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
        let counted = |starting: usize, busy: usize| {
            let state = lock(&self.state);
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
                starting: starting as u32,
                busy: busy as u32,
                target: state.target,
                cold_start_p90: p90(&state.cold_starts),
                served_ready: recent(Served::Ready),
                served_cold: recent(Served::Cold),
            }
        };
        match self.the_pool() {
            Some(pool) => pool.inspect(|counts| counted(counts.starting(), counts.busy())),
            None => counted(0, 0),
        }
    }

    /// One pass: the VMs that went or are too old leave, the target follows the host, VMs
    /// start until the ready and starting ones meet it, and those above it for `coolDown` go.
    pub fn reconcile(self: &Arc<ReadyVms>, host: &Arc<Shared>, view: HostView) {
        let Some(pool) = self.the_pool() else {
            return;
        };
        let now = Instant::now();
        let mut doomed: Vec<(IdleVm, &'static str)> = Vec::new();
        pool.plan(host, |counts| {
            let mut state = lock(&self.state);
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
                if ready.has_ended() {
                    doomed.push((ready, "its bridge is gone"));
                } else if now.duration_since(ready.up_at()) >= self.options.max_age {
                    doomed.push((ready, "it is older than the pool's maxAge"));
                } else {
                    kept.push(ready);
                }
            }
            state.ready = kept;

            let off = self.options.max_ready == 0 || view.draining || offline;
            let busy = counts.busy().max(view.active_runs) as u32;
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
            let starting = counts.starting_of(&self.shape) as u32;
            let waiting = counts.waiting_for(&self.shape) as u32;
            let have = ready + starting.saturating_sub(waiting);
            let paused = state.retry_after.is_some_and(|until| now < until);
            let start = if have < target && view.connected && view.registered && !paused {
                target - have
            } else {
                0
            };

            if ready > target {
                let due = off
                    || state
                        .above_target_since
                        .is_some_and(|since| now.duration_since(since) >= self.options.cool_down);
                if due {
                    // The oldest go first.
                    let mut extra = ready - target;
                    state.ready.sort_by_key(|r| r.up_at());
                    let mut kept = Vec::with_capacity(state.ready.len());
                    for vm in std::mem::take(&mut state.ready) {
                        if extra > 0 && vm.shape() == &self.shape {
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
            vec![self.shape.clone(); start as usize]
        });
        // Dropped, each is deleted.
        for (vm, why) in doomed {
            tracing::info!(vm = %vm.id(), why, "deleting a ready sandbox VM");
        }
    }

    /// Runs passes until `stop`. After each pass the host reports its sandboxes to the server
    /// when they changed, at most every 10 seconds, and when a `host.config` asked for it
    /// ([`ReadyVms::set_schedule`]). The host deletes the ready VMs when it stops
    /// ([`Pool::stop`]).
    pub async fn run(self: Arc<ReadyVms>, host: Arc<Shared>, stop: CancellationToken) {
        let mut sent: Option<(tinfirm_protocol::HostPoolStatus, Instant)> = None;
        loop {
            self.reconcile(&host, host.ready_vms_view());
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
    }

    /// `host.pool.status` of the host now.
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
}

impl Keeper for ReadyVms {
    fn take(&self, shape: &Shape) -> Option<IdleVm> {
        let mut state = lock(&self.state);
        state
            .ready
            .retain(|r| !(r.shape() == shape && r.has_ended()));
        let i = state.ready.iter().position(|r| r.shape() == shape)?;
        Some(state.ready.swap_remove(i))
    }

    fn make_room(&self, counts: &Counts<'_>) -> Vec<IdleVm> {
        // A VM of another shape than the ready ones: they make room, so that the host never
        // has more VMs than its pool size.
        let mut state = lock(&self.state);
        state.ready.sort_by_key(|r| r.up_at());
        let mut room = Vec::new();
        while !state.ready.is_empty()
            && state.ready.len() + counts.starting() + counts.busy()
                > state.pool_size.max(1) as usize
        {
            room.push(state.ready.remove(0));
        }
        room
    }

    fn offer(&self, vm: IdleVm, _counts: &Counts<'_>) -> Option<IdleVm> {
        let mut state = lock(&self.state);
        if vm.shape() == &self.shape && (state.ready_of(&self.shape) as u32) < state.target {
            state.ready.push(vm);
            return None;
        }
        Some(vm)
    }

    fn came_up(&self, took: Duration) {
        let mut state = lock(&self.state);
        state.cold_starts.push_back(took);
        while state.cold_starts.len() > COLD_STARTS_KEPT {
            state.cold_starts.pop_front();
        }
        state.failures = 0;
        state.retry_after = None;
    }

    fn create_failed(&self) {
        let mut state = lock(&self.state);
        state.failures = state.failures.saturating_add(1);
        let pause = RETRY_FIRST
            .saturating_mul(1 << (state.failures - 1).min(5))
            .min(RETRY_MAX);
        state.retry_after = Some(Instant::now() + pause);
    }

    fn claimed(&self, served: Served) {
        let mut state = lock(&self.state);
        let now = Instant::now();
        state.claims.push_back((now, served));
        while state
            .claims
            .front()
            .is_some_and(|(at, _)| now.duration_since(*at) > CLAIMS_KEPT_FOR)
        {
            state.claims.pop_front();
        }
    }

    fn changed(&self) {
        self.wake.notify_one();
    }

    fn release_all(&self) -> Vec<IdleVm> {
        std::mem::take(&mut lock(&self.state).ready)
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
