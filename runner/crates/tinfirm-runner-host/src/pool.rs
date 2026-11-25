//! The VM executor's VMs (ADR-0096, ADR-0114): where a run gets its VM. This is the one path
//! that creates, boots, probes, claims and deletes a VM; its guards are here.
//!
//! - **Shape.** A VM is created for one [`Shape`]: the runner image of a runtime, and the
//!   sandbox's own settings that its container starts with. A run takes only a VM of its own
//!   shape.
//! - **Create.** A run that asks for a VM ([`Pool::claim`]) starts one, unless enough of its
//!   shape are starting already, and waits for the next one of its shape that comes up. The
//!   VM's user data names no run, its bridge's id is the VM's own, and its egress proxy
//!   listener refuses every destination until the run binds it
//!   ([`crate::egress_proxy::EgressProxy::start_slot`]).
//! - **Up.** A VM is up once the provider has it running, its bridge has connected and its
//!   probe has passed ([`IdleVm`]). It holds no run data and no secret: those reach it over the
//!   bridge once a run claims it.
//! - **Front.** Each VM has its own front process from its create (ADR-0112, path C, part 2),
//!   with a TLS identity of its own, whose pin is in the VM's user data: `run` dials the VM's
//!   bridge and passes each connection to the front, which holds the bridge's session while the
//!   VM waits for a run and while the run lasts, and serves the VM's egress proxy through its
//!   tunnel. A VM whose front ends before a run claims it has no session any more
//!   ([`IdleVm::has_ended`]), and no run takes it.
//! - **Claim.** The first run that waits for the VM's shape takes it. A claimed VM never comes
//!   back; it is deleted after its run (ADR-0096). A VM that came up and that no run waits
//!   for any more is deleted at once.
//! - **Stop.** When the host stops, the runs that wait fail, and VMs still being created are
//!   deleted when they come up.
//!
//! So every run starts cold here. A [`Keeper`] may be plugged in ([`Pool::new`]): it is told
//! what happens to the VMs, may keep a VM that came up for no run and hand it to a later
//! claim, and may ask the pool to start VMs of its own ([`Pool::plan`]). The binary's one
//! keeper is the paid ready VMs (crate::ready_vms, ADR-0098, ADR-0114); without it, the pool
//! keeps no VM.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use tinfirm_protocol::Runtime;
use tokio::sync::oneshot;
use tokio::time::Instant;
use uuid::Uuid;

use crate::egress_proxy::EgressGuard;
use crate::front::session::FrontSession;
use crate::host::{HostOptions, Shared};
use crate::vm::{VmGuard, Vms};

/// A VM that came up at least this long before a run claims it is probed again then: a cheap
/// check that the provider's firewall has not changed since. No flag sets it
/// (`HostOptions::vm_reprobe_after`).
pub const REPROBE_AFTER: Duration = Duration::from_secs(60);

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

/// A VM that is up, its bridge connected and its probe passed, and that no run holds. Dropped,
/// it is deleted.
pub struct IdleVm {
    vm: VmGuard,
    session: FrontSession,
    /// The bridge's identity and where it is dialed.
    keep: crate::sandbox::BridgeKeep,
    egress: Option<EgressGuard>,
    loopback: std::net::IpAddr,
    ticket: String,
    boot_settings: Vec<(String, String)>,
    shape: Shape,
    up_at: Instant,
}

impl IdleVm {
    /// The VM's id at the provider.
    pub fn id(&self) -> &str {
        &self.vm.id
    }

    pub fn shape(&self) -> &Shape {
        &self.shape
    }

    /// When the VM came up.
    pub fn up_at(&self) -> Instant {
        self.up_at
    }

    /// Whether the VM's bridge is gone, so that no run can use it.
    pub fn has_ended(&self) -> bool {
        self.session.has_ended()
    }

    /// Deletes the VM now, and returns once the driver has answered.
    pub async fn delete(mut self) {
        self.vm.delete().await;
    }
}

/// A VM's bridge, connected, and what came with it, before its build and its network are
/// checked.
struct Up {
    session: FrontSession,
    keep: crate::sandbox::BridgeKeep,
    egress: Option<EgressGuard>,
    loopback: std::net::IpAddr,
    ticket: String,
    boot_settings: Vec<(String, String)>,
}

/// How a run got its VM.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Served {
    /// A [`Keeper`] held a VM of the run's shape when the run asked.
    Ready,
    /// The run waited for a VM to come up.
    Cold,
}

/// A VM that a run claimed: the run's until it ends, then deleted.
pub struct Claimed {
    pub vm: VmGuard,
    /// The VM's bridge, connected in the VM's front; the adapter is not started yet.
    pub session: FrontSession,
    /// The bridge's identity and where it is dialed: what a new front, or a host that
    /// restarted, takes the session back with.
    pub keep: crate::sandbox::BridgeKeep,
    /// The VM's egress proxy listener, which the run binds to its rules.
    pub egress: Option<EgressGuard>,
    /// Where the VM's tunnels to its front listen: its loopback, which in the VM executor's test
    /// mode (`VmOptions::on_this_machine`) is a loopback address of its own on this machine.
    pub loopback: std::net::IpAddr,
    /// The VM's one-time ticket, used up when its bridge connected; it is in the VM's user
    /// data, so the run's log masks it.
    pub ticket: String,
    /// The settings that the VM's container started with: the shape's and the egress
    /// proxy's.
    pub boot_settings: Vec<(String, String)>,
    pub shape: Shape,
    /// When the VM came up.
    pub up_at: Instant,
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
            pool.changed();
        }
    }
}

/// Holds VMs that came up for no run, for later claims: the seam where ready VMs plug into the
/// pool (crate::ready_vms, ADR-0098, ADR-0114). The pool creates, boots, probes, hands out and
/// deletes every VM; a keeper only decides which VMs it holds and how many the pool starts
/// for it ([`Pool::plan`]).
///
/// The pool calls every method but [`Keeper::changed`] under its own lock, so none of them
/// may call the pool back.
pub trait Keeper: Send + Sync {
    /// A run asks for a VM of `shape`: one that this keeper holds, which the run then takes.
    fn take(&self, shape: &Shape) -> Option<IdleVm>;
    /// A claim starts a VM, which `counts` holds already: the VMs that this keeper holds and
    /// that must go first, so that the host has no more VMs than it may. The pool deletes them
    /// before it creates the run's.
    fn make_room(&self, counts: &Counts<'_>) -> Vec<IdleVm>;
    /// A VM came up that no run waits for: `None` once this keeper holds it, else the VM
    /// back, which the pool deletes.
    fn offer(&self, vm: IdleVm, counts: &Counts<'_>) -> Option<IdleVm>;
    /// A VM came up, `took` after its create.
    fn came_up(&self, took: Duration);
    /// A VM could not be created.
    fn create_failed(&self);
    /// A run claimed a VM.
    fn claimed(&self, served: Served);
    /// A VM came up, could not be created or was claimed, or a run let its VM go. Called
    /// without the pool's lock.
    fn changed(&self);
    /// The host stops: every VM that this keeper holds, which the pool deletes.
    fn release_all(&self) -> Vec<IdleVm>;
}

/// What the pool's VMs are now, as a [`Keeper`] sees them under the pool's lock.
pub struct Counts<'a> {
    state: &'a State,
}

impl Counts<'_> {
    /// The VMs being created.
    pub fn starting(&self) -> usize {
        self.state.starting.len()
    }

    /// The VMs of `shape` being created.
    pub fn starting_of(&self, shape: &Shape) -> usize {
        self.state.starting_of(shape)
    }

    /// The runs that wait for a VM of `shape`.
    pub fn waiting_for(&self, shape: &Shape) -> usize {
        self.state.waiting_for(shape)
    }

    /// The VMs that runs hold.
    pub fn busy(&self) -> usize {
        self.state.busy
    }
}

/// A run that waits for a VM of its shape.
struct Waiter {
    shape: Shape,
    answer: oneshot::Sender<std::result::Result<(IdleVm, Busy), String>>,
}

#[derive(Default)]
struct State {
    /// The shapes of the VMs being created, one entry each.
    starting: Vec<Shape>,
    waiters: VecDeque<Waiter>,
    busy: usize,
    stopped: bool,
}

impl State {
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
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

/// The VM executor's VMs: every VM of its runs comes from here.
pub struct Pool {
    vms: Arc<Vms>,
    keeper: Option<Arc<dyn Keeper>>,
    state: Mutex<State>,
}

impl std::fmt::Debug for Pool {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Pool")
            .field("vms", &self.vms)
            .field("keeper", &self.keeper.is_some())
            .finish_non_exhaustive()
    }
}

impl Pool {
    /// The pool of `vms`; `keeper` holds VMs that came up for no run, if any.
    pub fn new(vms: Arc<Vms>, keeper: Option<Arc<dyn Keeper>>) -> Arc<Pool> {
        Arc::new(Pool {
            vms,
            keeper,
            state: Mutex::new(State::default()),
        })
    }

    /// From a VM's create to its bridge's first connection.
    pub fn start_timeout(&self) -> Duration {
        self.vms.options.start_timeout
    }

    fn changed(&self) {
        if let Some(keeper) = &self.keeper {
            keeper.changed();
        }
    }

    /// A VM of `shape` for a run: one that the keeper holds, or else the next one of its shape
    /// that comes up before `deadline`, which this claim starts unless enough are starting
    /// already. Fails when the VM that the run waits for cannot be created.
    ///
    /// A claim that started a VM waits for that VM's own outcome before its timer decides, so
    /// that the run fails with what happened to its VM, and only once the VM is deleted:
    ///
    /// - The VM's create keeps its own deadline, `--vm-start-timeout` from its create, which
    ///   comes later than the claim's (after the room's deletes, the bridge's and the egress
    ///   proxy's set-up); the two are not shared. When the claim's `deadline` passes first,
    ///   the claim waits on: its wait is then bounded by the VM's create, that is the deletes
    ///   of the keeper's VMs that make room, the driver's `create`, the VM's own start
    ///   timeout, the probe's timeout, and the delete of a VM that failed, each of
    ///   which has a timeout of its own. An answer that comes meanwhile (the host stops, or a
    ///   VM of its shape comes up) ends the wait at once.
    /// - The VM that this claim started may still come up just after `deadline`: the first
    ///   run that waits for its shape takes it, this claim if no earlier one waits, instead of
    ///   the VM being created for nobody.
    /// - The VM may also have gone to an earlier waiter of its shape, or its failure to that
    ///   waiter: with no answer of its own when the VM's create ends, the claim fails with
    ///   "no sandbox VM came up within N s".
    ///
    /// A claim that started no VM (it waits for one that another claim or the keeper started)
    /// fails at `deadline`. Dropped (its run was cancelled), a claim leaves its VM's create
    /// running on its own, and the VM goes to the next waiter of its shape, or to the keeper,
    /// or is deleted.
    pub async fn claim(
        self: &Arc<Pool>,
        host: &Arc<Shared>,
        shape: Shape,
        deadline: Instant,
    ) -> Result<Claimed> {
        let asked = Instant::now();
        let mut room = Vec::new();
        let (answer, start) = {
            let mut state = lock(&self.state);
            if state.stopped {
                bail!("the runner host is stopping");
            }
            if let Some(keeper) = &self.keeper
                && let Some(vm) = keeper.take(&shape)
            {
                state.busy += 1;
                keeper.claimed(Served::Ready);
                drop(state);
                self.changed();
                return Ok(claimed(vm, Busy::counted(self), Served::Ready, asked));
            }
            let (tx, rx) = oneshot::channel();
            state.waiters.push_back(Waiter {
                shape: shape.clone(),
                answer: tx,
            });
            let start = state.waiting_for(&shape) > state.starting_of(&shape);
            if start {
                state.starting.push(shape.clone());
                if let Some(keeper) = &self.keeper {
                    room = keeper.make_room(&Counts { state: &state });
                }
            }
            (rx, start)
        };
        // The VM this claim started: dropped with the claim, the task goes on detached.
        let started = start.then(|| {
            let (pool, host) = (self.clone(), host.clone());
            tokio::spawn(async move {
                // The room first, so that the host never has more VMs than it may.
                for vm in room {
                    tracing::info!(vm = %vm.id(), "deleting a sandbox VM that no run holds, to make room for a run of another shape");
                    vm.delete().await;
                }
                pool.create(host, shape).await;
            })
        });
        let mut answer = answer;
        if let Ok(got) = tokio::time::timeout_at(deadline, &mut answer).await {
            return answered(got.ok(), asked);
        }
        if let Some(started) = started {
            // Bounded by the VM's own create (see above); an answer ends the wait at once.
            tokio::select! {
                got = &mut answer => return answered(got.ok(), asked),
                _ = started => {}
            }
            match answer.try_recv() {
                Ok(got) => return answered(Some(got), asked),
                Err(oneshot::error::TryRecvError::Closed) => return answered(None, asked),
                Err(oneshot::error::TryRecvError::Empty) => {}
            }
        }
        bail!(
            "no sandbox VM came up within {} s",
            deadline
                .saturating_duration_since(asked)
                .as_millis()
                .div_ceil(1000)
        )
    }

    /// Creates a VM of `shape` and hands it to the first run that waits for its shape, or to
    /// the keeper.
    async fn create(self: Arc<Pool>, host: Arc<Shared>, shape: Shape) {
        let started = Instant::now();
        match self.boot(&host, &shape).await {
            Ok(vm) => {
                tracing::info!(
                    vm = %vm.id(),
                    seconds = started.elapsed().as_secs(),
                    "sandbox VM up"
                );
                self.arrived(vm, started.elapsed());
            }
            Err(e) => self.failed(&shape, format!("{e:#}")),
        }
    }

    /// Creates the VM, waits until the provider has it up, dials its bridge and probes it.
    async fn boot(&self, host: &Arc<Shared>, shape: &Shape) -> Result<IdleVm> {
        let options = &self.vms.options;
        let bridge = host
            .opts
            .bridge
            .as_ref()
            .context("the VM executor runs its agents over the sandbox bridge")?;
        let id = Uuid::new_v4().to_string();
        let bridge_id = format!("vm-{id}");
        // Where the VM's tunnels listen in the test mode, whose "VMs" share this machine's
        // network: a loopback address of their own.
        let loopback = std::net::IpAddr::V4(if options.on_this_machine {
            crate::bridge::local_sandbox_loopback()
        } else {
            crate::bridge::SANDBOX_LOOPBACK
        });
        // The VM's front holds its bridge and serves its proxies' HTTP: its egress proxy's
        // now, through the tunnel, and the rest once a run claims the VM.
        let front = host.sandbox_front().await?.context("the VM has no front")?;
        let serving = crate::front::sandbox::Serving::Tunnels {
            front: front.clone(),
            loopback,
        };
        let egress = match host.egress_proxy().await? {
            Some(proxy) => Some(proxy.start_slot(&serving).await?),
            None => None,
        };
        let boot_settings = boot_settings(shape, egress.as_ref());
        let identity = tinfirm_bridge::tls::Identity::generate()?;
        let expected = crate::front::session::Expected::new_ticket()?;
        let crate::front::session::Expected::Ticket(ticket) = &expected else {
            unreachable!("a new ticket");
        };
        let ticket = ticket.expose().to_string();
        // The VM listens: it dials nothing, and its user data names no address of this host.
        let bootstrap = crate::bridge::bootstrap_env(&bridge_id, &ticket, &identity.pin_hex(), "");
        let data = crate::vm::user_data(&shape.image, &boot_settings, &bootstrap, options.port);
        let labels = Vms::labels(&host.host_label(), &id);
        let name = format!("tinfirm-vm-{}", &id[..8]);
        let deadline = Instant::now() + options.start_timeout;
        let mut vm = self.vms.create(&name, &data, &labels).await?;
        let reach = match self.vms.reach(&vm.id, deadline).await {
            Ok(reach) => reach,
            Err(e) => {
                vm.delete().await;
                return Err(e);
            }
        };
        // The driver's port, if it names one (a node's published port, ADR-0112); else
        // `--vm-port`, where the bridge listens in the VM.
        let target = reach.dial(options.port);
        vm.set_reach(reach);
        tracing::debug!(vm = %vm.id, %target, "dialing the sandbox VM's bridge for its front");
        let registration = match front
            .bridge(
                &identity,
                &bridge.timing,
                crate::front::session::Peer::Dial(target.clone()),
                &bridge_id,
                expected,
            )
            .await
            .context("the VM's front cannot take its bridge")
        {
            Ok(registration) => registration,
            Err(e) => {
                vm.delete().await;
                return Err(e);
            }
        };
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
        let (cert, key) = identity.to_der();
        let keep = crate::sandbox::BridgeKeep {
            cert: cert.to_vec(),
            key: key.to_vec(),
            reach: crate::sandbox::BridgeReach::Dial { target },
        };
        let up = Up {
            session,
            keep,
            egress,
            loopback,
            ticket,
            boot_settings,
        };
        self.up(vm, up, shape).await
    }

    /// The VM, its bridge connected: its build checked and its network probed.
    async fn up(&self, mut vm: VmGuard, up: Up, shape: &Shape) -> Result<IdleVm> {
        if let Err(e) = self.vms.check_image(&vm.id, up.session.bridge_build()) {
            vm.delete().await;
            return Err(e);
        }
        if let Err(e) = crate::sandbox::probe(&up.session.files(), &self.vms.options.probe).await {
            vm.delete().await;
            return Err(e);
        }
        Ok(IdleVm {
            vm,
            session: up.session,
            keep: up.keep,
            egress: up.egress,
            loopback: up.loopback,
            ticket: up.ticket,
            boot_settings: up.boot_settings,
            shape: shape.clone(),
            up_at: Instant::now(),
        })
    }

    /// A VM came up, `took` after its create: the first run that waits for its shape takes
    /// it; else the keeper may hold it, and otherwise it goes.
    fn arrived(self: &Arc<Pool>, vm: IdleVm, took: Duration) {
        let mut vm = vm;
        let doomed = {
            let mut state = lock(&self.state);
            state.started(&vm.shape);
            if let Some(keeper) = &self.keeper {
                keeper.came_up(took);
            }
            loop {
                if state.stopped {
                    break Some(vm);
                }
                let Some(i) = state
                    .waiters
                    .iter()
                    .position(|w| w.shape == vm.shape && !w.answer.is_closed())
                else {
                    break match &self.keeper {
                        Some(keeper) => keeper.offer(vm, &Counts { state: &state }),
                        None => Some(vm),
                    };
                };
                let waiter = state.waiters.remove(i).expect("found");
                state.busy += 1;
                match waiter.answer.send(Ok((vm, Busy::counted(self)))) {
                    Ok(()) => {
                        if let Some(keeper) = &self.keeper {
                            keeper.claimed(Served::Cold);
                        }
                        break None;
                    }
                    // The run gave up just now: the next one, if any, takes it.
                    Err(Ok((back, busy))) => {
                        state.busy -= 1;
                        busy.disarm();
                        vm = back;
                    }
                    Err(Err(_)) => unreachable!("the pool sends only VMs"),
                }
            }
        };
        if let Some(doomed) = doomed {
            tracing::info!(vm = %doomed.id(), "sandbox VM not needed; deleting it");
        }
        self.changed();
    }

    /// A VM of `shape` could not be created: a run that waits for that shape and that no other
    /// VM being created will serve fails with `message`.
    fn failed(&self, shape: &Shape, message: String) {
        tracing::warn!(error = %message, "cannot create a sandbox VM");
        let mut state = lock(&self.state);
        state.started(shape);
        if let Some(keeper) = &self.keeper {
            keeper.create_failed();
        }
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
        self.changed();
    }

    /// For the keeper: under the pool's lock, `decide` sees what the pool's VMs are and names
    /// the shapes of the VMs to start for no run, one entry each; the pool creates them and
    /// offers each to the keeper once it is up, if no run waits for it. Does nothing once the
    /// host stops.
    pub fn plan(
        self: &Arc<Pool>,
        host: &Arc<Shared>,
        decide: impl FnOnce(&Counts<'_>) -> Vec<Shape>,
    ) {
        let start = {
            let mut state = lock(&self.state);
            if state.stopped {
                return;
            }
            state.waiters.retain(|w| !w.answer.is_closed());
            let start = decide(&Counts { state: &state });
            state.starting.extend(start.iter().cloned());
            start
        };
        for shape in start {
            tokio::spawn(self.clone().create(host.clone(), shape));
        }
    }

    /// `f` of what the pool's VMs are now, under the pool's lock.
    pub fn inspect<T>(&self, f: impl FnOnce(&Counts<'_>) -> T) -> T {
        f(&Counts {
            state: &lock(&self.state),
        })
    }

    /// The host stops: no more claims, the waiting runs fail, and the VMs that the keeper
    /// holds are deleted. VMs still being created are deleted when they come up.
    pub async fn stop(&self) {
        let held = {
            let mut state = lock(&self.state);
            state.stopped = true;
            for waiter in state.waiters.drain(..) {
                let _ = waiter
                    .answer
                    .send(Err("the runner host is stopping".to_string()));
            }
            self.keeper
                .as_ref()
                .map(|keeper| keeper.release_all())
                .unwrap_or_default()
        };
        let deletes = held.into_iter().map(IdleVm::delete);
        let _ =
            tokio::time::timeout(Duration::from_secs(60), futures::future::join_all(deletes)).await;
    }
}

/// What a claim's waiter got from the pool: a VM, a failure, or (`None`) its sender dropped
/// unanswered, which happens only as the host stops.
fn answered(
    got: Option<std::result::Result<(IdleVm, Busy), String>>,
    asked: Instant,
) -> Result<Claimed> {
    match got {
        Some(Ok((vm, busy))) => Ok(claimed(vm, busy, Served::Cold, asked)),
        Some(Err(message)) => Err(anyhow!(message)),
        None => bail!("the runner host is stopping"),
    }
}

/// The settings that a VM's container starts with: the shape's, and its egress proxy
/// listener's that stay in the sandbox's settings. Sorted, as a shape's are.
fn boot_settings(shape: &Shape, egress: Option<&EgressGuard>) -> Vec<(String, String)> {
    let mut settings = shape.settings.clone();
    if let Some(egress) = egress {
        settings.extend(
            egress
                .env()
                .into_iter()
                .filter(|(key, _)| crate::bridge::stays_in_sandbox_settings(key)),
        );
    }
    settings.sort();
    settings
}

fn claimed(vm: IdleVm, busy: Busy, served: Served, asked: Instant) -> Claimed {
    Claimed {
        vm: vm.vm,
        session: vm.session,
        keep: vm.keep,
        egress: vm.egress,
        loopback: vm.loopback,
        ticket: vm.ticket,
        boot_settings: vm.boot_settings,
        shape: vm.shape,
        up_at: vm.up_at,
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
