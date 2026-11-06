package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.flowtivo.tinfirm.agent.AgentEvents;
import com.flowtivo.tinfirm.company.CompanyEvents;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.model.Hosts;
import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.ModelEvents;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.model.RunModelContext;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.HostCredentialInfo;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerVersions;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The dispatcher (the package 3 contract, §5.7), on replicas with the {@code worker} role: every 2 s, and right after a
 * run is queued, a slot is freed, a host confirms its capacity with {@code host.config.applied}, a principal's key
 * becomes usable again ({@code BudgetAvailable}), or a paused agent is resumed.
 * <p>
 * Each pass first returns assigned runs without {@code run.accepted} in time to the queue (with a new epoch), then
 * walks the queue oldest first. The queue comes from the narrow {@code run_dispatch_queue} lookup (ids only); each run
 * is then locked {@code FOR NO KEY UPDATE SKIP LOCKED} in its own company's tenant context, after its conversation's
 * row (CONVENTIONS §10: the plan, the run's end and its queue reason write the conversation), and checked in the
 * contract's order, the first check that fails being its queue reason:
 * <ol>
 * <li>step 0 (the package 6 contract, §5.4): the company's runs stopped ({@code COMPANY_STOPPED}); a repository of a
 * paused system in the turn's workspace ({@code SYSTEM_PAUSED}, {@link TurnSource#pausedWorkspaceSystem});</li>
 * <li>the agent ({@link Agents#find}): paused ({@code AGENT_PAUSED}, I18); deleted (the run is cancelled: its
 * conversation ends); no longer listing the conversation's runtime (the run fails, {@code RUNTIME_REMOVED}). Then the
 * settings in effect for the turn ({@link Settings#effective} of the conversation's person), which the conversation's
 * port and the assignment use;</li>
 * <li>the conversation ({@link TurnSource#plan}): a workspace binding waits ({@code ACCOUNT_REQUIRED});</li>
 * <li>the payer's model access ({@link ModelAccess#forRun}, nobody else ever pays, I10): a spent budget
 * ({@code BUDGET_SPENT} until its reset), a key LiteLLM refused ({@code KEY_ERROR}), a subscription's host offline
 * ({@code HOST_OFFLINE}), anything package 2 refuses ({@code MODEL_ACCESS_UNAVAILABLE}); a budget's settings that
 * name no model ({@code MODEL_REQUIRED}) or one the key does not allow ({@code MODEL_NOT_ALLOWED}) fail the run;</li>
 * <li>the hosts that may carry the run's credentials ({@link Hosts#eligible}, I13): none while the company has hosts
 * for the runtime ({@code NO_HOST_FOR_CREDENTIALS}), none at all ({@code NO_RUNNER_HOST});</li>
 * <li>a subscription's label still reported by its host; then, with the eligible hosts locked in the order of their
 * ids so that concurrent dispatchers count one at a time, on every replica, a subscription's pool on its host
 * ({@code CREDENTIAL_POOL_FULL}) and the host ({@link HostSelection}): connected and configured, of protocol v2, not
 * draining, with a free slot, the conversation's previous host first (open point 13); a budget's run only on a host
 * of the minimum runner version ({@link RunnerVersions});</li>
 * <li>assign: a new epoch, the host and its kind, {@code ASSIGNED}; the assignment is delivered after the commit
 * ({@link RunDelivery}), outside any transaction.</li>
 * </ol>
 * A queue reason is written only when it changes, and every write signals the run's live streams.
 */
@Component
class RunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);

    private static final int BATCH = 100;

    private final RunStore runs;

    private final RunLocks locks;

    private final RunPorts ports;

    private final Agents agents;

    private final Settings settings;

    private final ModelAccess modelAccess;

    private final Hosts hosts;

    private final RunnerDirectory runners;

    private final RunnerConnections connections;

    private final RunDelivery delivery;

    private final RunCompletion completion;

    private final SystemTransactions transactions;

    private final RunWorkers workers;

    private final RunProperties properties;

    private final boolean worker;

    private final TimeSource time;

    private final RunEventHub hub;

    private final ApplicationEventPublisher publisher;

    private final CompanySwitches companySwitches;

    private final ReentrantLock pass = new ReentrantLock();

    private final AtomicBoolean again = new AtomicBoolean();

    RunDispatcher(RunStore runs, RunLocks locks, RunPorts ports, Agents agents, Settings settings,
            ModelAccess modelAccess, Hosts hosts, RunnerDirectory runners, RunnerConnections connections,
            RunDelivery delivery, RunCompletion completion, SystemTransactions transactions, RunWorkers workers,
            RunProperties properties, TinfirmProperties tinfirm, TimeSource time, RunEventHub hub,
            ApplicationEventPublisher publisher, CompanySwitches companySwitches) {
        this.companySwitches = companySwitches;
        this.runs = runs;
        this.locks = locks;
        this.ports = ports;
        this.agents = agents;
        this.settings = settings;
        this.modelAccess = modelAccess;
        this.hosts = hosts;
        this.runners = runners;
        this.connections = connections;
        this.delivery = delivery;
        this.completion = completion;
        this.transactions = transactions;
        this.workers = workers;
        this.properties = properties;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        this.time = time;
        this.hub = hub;
        this.publisher = publisher;
    }

    /** The server's failure class of a turn whose agent no longer lists the conversation's runtime (open point 3). */
    static final String RUNTIME_REMOVED = "RUNTIME_REMOVED";

    /**
     * A run assigned to its host in the dispatching transaction, to deliver once that transaction has committed.
     *
     * @param credentialLabel the subscription's label for a subscription's run; null for a budget's
     * @param settings        the settings in effect for the turn, as the dispatcher read them
     */
    record Assignment(RunStore.RunRow run, long epoch, RunnerHostInfo host, int protocolVersion,
            ModelAccessChoice access, @Nullable String credentialLabel, TurnSource.TurnRef turn,
            TurnSource.@Nullable PreviousRun previous, EffectiveSettings settings) {
    }

    @Scheduled(fixedDelayString = "${tinfirm.run.dispatch-interval:PT2S}")
    void scheduledPass() {
        if (worker) {
            dispatch();
        }
    }

    /** A run was queued or a slot was freed: dispatch now instead of waiting for the next tick. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onDispatchNeeded(DispatchNeeded event) {
        nudge();
    }

    /** A principal's key is usable again (a reset, a raise, a repair): its waiting runs go on (V7). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onModelKeyStatusChanged(ModelEvents.ModelKeyStatusChanged event) {
        if ("OK".equals(event.status())) {
            nudge();
        }
    }

    /** A paying principal was resumed: its waiting runs go on. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onPrincipalResumed(PrincipalEvents.PrincipalResumed event) {
        nudge();
    }

    /** The company's stop was lifted, or a paused system resumed (package 6): the waiting turns go on. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onRunsResumed(CompanyEvents.RunsResumed event) {
        nudge();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onSystemResumed(SystemEvents.SystemResumed event) {
        nudge();
    }

    /** A paused agent was resumed: its conversations' waiting turns go on. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onAgentResumed(AgentEvents.AgentResumed event) {
        nudge();
    }

    /** Dispatches on a background thread now, instead of waiting for the next tick. */
    void nudge() {
        if (worker) {
            workers.execute("dispatch", this::dispatch);
        }
    }

    /** One pass; a request that arrives during a pass makes that pass run once more instead of waiting. */
    void dispatch() {
        again.set(true);
        while (again.get() && pass.tryLock()) {
            try {
                again.set(false);
                requeueOverdue();
                dispatchQueued();
            }
            catch (RuntimeException ex) {
                log.warn("Dispatch pass failed", ex);
            }
            finally {
                pass.unlock();
            }
        }
    }

    /**
     * Assigned runs without {@code run.accepted} in time go back to the queue, unless they are being prepared or in a
     * reconnect grace (their {@code lease_expires_at}): the host may have accepted them while the server or the
     * connection was down, and reports them in {@code hello}.
     */
    private void requeueOverdue() {
        Instant now = time.now();
        Instant deadline = now.minus(properties.acceptTimeout());
        for (RunStore.RunRef ref : transactions.unscoped(() -> runs.acceptOverdue(deadline, now, BATCH))) {
            transactions.runInCompany(ref.companyId(), () -> locks.lock(ref.runId())
                .filter(run -> run.status() == RunStatus.QUEUED && run.dispatchedAt() != null
                        && run.dispatchedAt().isBefore(deadline)
                        && (run.leaseExpiresAt() == null || !run.leaseExpiresAt().isAfter(now)))
                .ifPresent(run -> {
                    log.info("Run {} was not accepted by runner host {} in time; back to the queue", run.id(),
                            run.runnerHostId());
                    // The reason is cleared; the dispatch attempt of this pass sets it again.
                    runs.requeue(run.id(), null, null, time.now());
                    publisher.publishEvent(TurnStarter.queueReasonChanged(run, null));
                    hub.changedAfterCommit(run.id());
                }));
        }
    }

    /** Walks the whole queue in pages, so runs that wait for an offline host never starve younger ones. */
    private void dispatchQueued() {
        Instant now = time.now();
        RunStore.QueuedRef position = null;
        List<RunStore.QueuedRef> page;
        do {
            RunStore.QueuedRef after = position;
            page = transactions.unscoped(() -> runs.dispatchQueue(now, after, BATCH));
            for (RunStore.QueuedRef ref : page) {
                dispatchOne(ref);
                position = ref;
            }
        }
        while (page.size() == BATCH);
    }

    private void dispatchOne(RunStore.QueuedRef ref) {
        try {
            Optional<Assignment> assignment = transactions.inCompany(ref.companyId(), () -> assign(ref.runId()));
            // The turn's content (its git tokens) is revealed outside any transaction, and may take a moment: one
            // conversation's assignment never holds up another's.
            assignment.ifPresent(assigned -> workers.execute("delivery of run " + ref.runId(),
                    () -> delivery.deliver(assigned)));
        }
        catch (RuntimeException ex) {
            log.warn("Dispatching run {} failed", ref.runId(), ex);
        }
    }

    /**
     * The checks of one queued run under its row lock, taken after its conversation's, in its company's tenant context;
     * the assignment, if any.
     */
    Optional<Assignment> assign(UUID runId) {
        RunStore.RunRow run = locks.lockDispatchable(runId).orElse(null);
        if (run == null) {
            return Optional.empty();
        }
        Instant now = time.now();

        // 0. The company's stop, and a paused system's repository in the workspace (package 6, §5.4).
        if (companySwitches.runsStopped(run.companyId()).isPresent()) {
            return waiting(run, QueueReason.companyStopped(), now);
        }
        TurnSource pausedSource = ports.source().orElse(null);
        if (pausedSource != null && run.choice() != null) {
            Optional<String> paused = pausedSource.pausedWorkspaceSystem(turnRef(run, run.choice()));
            if (paused.isPresent()) {
                return waiting(run, QueueReason.systemPaused(paused.get()), now);
            }
        }

        // 1. The agent: deleted (its conversations end), paused (I18), or without the conversation's runtime.
        AgentInfo agent = agents.find(run.companyId(), run.agentId()).filter(AgentInfo::live).orElse(null);
        if (agent == null) {
            completion.finish(run, RunStore.Outcome.of(RunStatus.CANCELLED, null, "The agent was deleted."));
            return Optional.empty();
        }
        if (agent.paused()) {
            return waiting(run, QueueReason.agentPaused(agent.name()), now);
        }
        if (!agent.runtimes().contains(run.runtime())) {
            completion.finish(run, RunStore.Outcome.of(RunStatus.FAILED, RUNTIME_REMOVED, "The agent " + agent.name()
                    + " no longer runs on " + run.runtime() + ", which this conversation was started with."));
            return Optional.empty();
        }
        TurnSource source = ports.source().orElse(null);
        if (source == null) {
            return waiting(run, QueueReason.other("Nothing on this server supplies the turns of conversations."),
                    now);
        }
        ModelAccessChoice choice = run.choice();
        if (choice == null) {
            return waiting(run, QueueReason.modelAccessUnavailable("SUBSCRIPTION_GONE",
                    "The personal runner host of the subscription that pays for this conversation was removed."), now);
        }
        TurnSource.TurnRef turn = turnRef(run, choice);
        // The settings in effect for this turn: the conversation's person's, or its trigger's (the package 4
        // contract, §5.7).
        EffectiveSettings effective = settings.effective(run.companyId(), run.agentId(),
                run.triggerId() != null ? new SettingsOwner.Trigger(run.triggerId())
                        : new SettingsOwner.Person(run.actingPrincipalId()));

        // 2. The conversation: a workspace binding that waits.
        TurnSource.TurnPlan.Ready ready;
        switch (source.plan(turn, effective)) {
            case TurnSource.TurnPlan.Ready found -> ready = found;
            case TurnSource.TurnPlan.AccountRequired waits -> {
                return waiting(run, QueueReason.accountRequired(waits.message()), now);
            }
            case TurnSource.TurnPlan.TriggerPaused paused -> {
                return waiting(run, QueueReason.triggerPaused(paused.message()), now);
            }
            case TurnSource.TurnPlan.AgentNotUsable waits -> {
                return waiting(run, QueueReason.other(waits.message()), now);
            }
            case TurnSource.TurnPlan.Cancel cancel -> {
                completion.finish(run, RunStore.Outcome.of(RunStatus.CANCELLED, null, cancel.message()));
                return Optional.empty();
            }
        }

        // 3. The payer's model access.
        RunModelContext context = new RunModelContext(run.companyId(), run.payerPrincipalId(), choice, run.runtime(),
                ready.workspaceAccountHolders());
        String label = null;
        switch (modelAccess.forRun(context)) {
            case RunModelAccess.Wait wait -> {
                return waiting(run, switch (wait.reason()) {
                    case BUDGET_SPENT -> QueueReason.budgetSpent(wait.until(), wait.detail());
                    case KEY_ERROR -> QueueReason.keyError(wait.detail());
                    case HOST_OFFLINE -> QueueReason.subscriptionHostOffline(subscriptionHost(run),
                            run.subscriptionLabel(), wait.detail());
                }, now);
            }
            case RunModelAccess.Refuse refuse -> {
                return waiting(run, QueueReason.modelAccessUnavailable(refuse.reason().name(), refuse.detail()), now);
            }
            case RunModelAccess.Budget budget -> {
                RunModelAccess.Refuse refuse = budget.refusal(effective.model());
                if (refuse != null) {
                    // MODEL_REQUIRED or MODEL_NOT_ALLOWED: the person's settings, not the queue, change that.
                    completion.finish(run, RunStore.Outcome.of(RunStatus.FAILED, refuse.reason().name(),
                            refuse.detail() + "."));
                    return Optional.empty();
                }
            }
            case RunModelAccess.Subscription subscription -> label = subscription.label();
        }

        // 4. The hosts that may carry the run's credentials.
        List<RunnerHostInfo> eligible;
        switch (hosts.eligible(context)) {
            case Hosts.Eligibility.Nowhere nowhere -> {
                return waiting(run, QueueReason.noHostForCredentials(run.runtime(), nowhere.refusal().detail()), now);
            }
            case Hosts.Eligibility.Eligible found -> {
                if (found.hosts().isEmpty()) {
                    return waiting(run, noHost(run, context, found.whyNone()), now);
                }
                eligible = found.hosts();
            }
        }

        // 5. A subscription's label still reported; then, under the eligible hosts' locks, its pool and the hosts'
        // slots.
        HostCredentialInfo credential = null;
        if (label != null) {
            String subscriptionLabel = label;
            credential = runners.credentials(run.companyId(), List.of(eligible.getFirst().id()))
                .stream()
                .filter(candidate -> candidate.label().equals(subscriptionLabel) && candidate.reported())
                .findFirst()
                .orElse(null);
            if (credential == null) {
                return waiting(run, QueueReason.modelAccessUnavailable("SUBSCRIPTION_GONE",
                        "The host no longer reports the subscription " + label), now);
            }
        }
        boolean budget = choice instanceof ModelAccessChoice.Budget;
        // Concurrent dispatchers, on any replica, lock the hosts in the order of their ids and count only under these
        // locks: one that waited here sees the run the other one assigned, in the subscription's pool as on the host.
        List<RunnerHostInfo> locked = runners.lockHostsForDispatch(eligible.stream().map(RunnerHostInfo::id).toList())
            .stream()
            // The minimum runner version again, as the host's latest hello says now (package 2, §7).
            .filter(host -> !budget || host.gatewayRuns())
            .toList();
        if (credential != null) {
            RunnerHostInfo subscriptionHost = eligible.getFirst();
            // A machine's pool is one across its presences in every company (ADR-0092).
            int used = subscriptionHost.personalHostId() != null
                    ? runs.occupyingBySubscriptionOfMachine(subscriptionHost.personalHostId(), label)
                    : runs.occupyingBySubscription(subscriptionHost.id(), label);
            if (used >= credential.poolSize()) {
                return waiting(run, QueueReason.poolFull(subscriptionHost, label, used, credential.poolSize()), now);
            }
        }
        if (locked.isEmpty()) {
            return waiting(run, QueueReason.noRunnerHost(run.runtime(), "No runner host that may run this "
                    + "conversation has runner version " + RunnerVersions.MODEL_PROXY_ALWAYS_ON + " or later."), now);
        }
        Map<UUID, Integer> used = runs.occupyingByHosts(locked.stream().map(RunnerHostInfo::id).toList());
        // A machine's slots are one across its presences in every company (ADR-0092): its runs elsewhere count.
        Map<UUID, Integer> usedOfMachines = runs.occupyingByMachines(locked.stream()
            .map(RunnerHostInfo::personalHostId)
            .filter(Objects::nonNull)
            .toList());
        List<HostSelection.Host> candidates = locked.stream()
            .map(host -> new HostSelection.Host(host, connections.isConfigured(host.id()),
                    connections.protocolVersion(host.id()),
                    host.personalHostId() != null ? usedOfMachines.getOrDefault(host.personalHostId(), 0)
                            : used.getOrDefault(host.id(), 0),
                    connections.supports(host.id(), RunnerMessages.FEATURE_RUN_SKILLS)))
            .toList();
        UUID previousHost = runs.previousHost(run.conversationId(), run.id()).orElse(null);
        HostSelection.Host chosen;
        switch (HostSelection.select(candidates, previousHost, !effective.skills().isEmpty())) {
            case HostSelection.Waits waits -> {
                return waiting(run, waits.reason(), now);
            }
            case HostSelection.Chosen found -> chosen = found.host();
        }

        // 6. Assign.
        RunnerHostInfo host = chosen.info();
        long epoch = run.epoch() + 1;
        QueueReason assigned = QueueReason.assigned(host, label);
        runs.markDispatched(run.id(), epoch, host.id(), host.name(), host.scope(), assigned, now,
                now.plus(properties.prepareTimeout()));
        publisher.publishEvent(TurnStarter.queueReasonChanged(run, assigned));
        hub.changedAfterCommit(run.id());
        TurnSource.PreviousRun previous = runs.previousWithSession(run.conversationId(), run.id(), run.createdAt())
            .map(prior -> new TurnSource.PreviousRun(prior.id(), prior.sessionId(), prior.createdAt(),
                    prior.runnerHostId()))
            .orElse(null);
        log.info("Run {} of conversation {} assigned to runner host {} (epoch {})", run.id(), run.conversationKey(),
                host.id(), epoch);
        return Optional.of(new Assignment(run, epoch, host, chosen.protocol(), choice, label, turn, previous,
                effective));
    }

    static TurnSource.TurnRef turnRef(RunStore.RunRow run, ModelAccessChoice choice) {
        return new TurnSource.TurnRef(run.companyId(), run.id(), run.conversationId(), run.conversationKey(),
                run.agentId(), run.runtime(), run.turnReason(), run.turnNumber(), run.actingPrincipalId(),
                run.payerPrincipalId(), choice);
    }

    private @Nullable RunnerHostInfo subscriptionHost(RunStore.RunRow run) {
        return run.subscriptionHostId() != null ? runners.host(run.companyId(), run.subscriptionHostId()).orElse(null)
                : null;
    }

    /**
     * No eligible host: {@code NO_HOST_FOR_CREDENTIALS} when the company has hosts that can run the runtime but none
     * may carry the run's credentials (M4: a personal host and another principal's credentials), else
     * {@code NO_RUNNER_HOST} with package 2's reason (no host for the runtime, or none of the minimum version).
     */
    private QueueReason noHost(RunStore.RunRow run, RunModelContext context, @Nullable String whyNone) {
        List<RunnerHostInfo> all = runners.hosts(run.companyId());
        boolean forRuntime = all.stream().anyMatch(host -> host.serves(run.runtime()));
        boolean mayCarry = all.stream().anyMatch(host -> host.serves(run.runtime()) && mayCarry(host, context));
        if (forRuntime && !mayCarry) {
            return QueueReason.noHostForCredentials(run.runtime(), "No runner host may run this conversation's "
                    + "credentials: a personal host runs only its owner's work, and only with its owner's own "
                    + "accounts.");
        }
        return QueueReason.noRunnerHost(run.runtime(), whyNone);
    }

    /** Package 2's host scope rule (M4), for telling the two reasons apart. */
    private static boolean mayCarry(RunnerHostInfo host, RunModelContext context) {
        UUID payer = context.payingPrincipalId();
        return switch (context.access()) {
            case ModelAccessChoice.Budget budget -> host.company()
                    || host.personalOf(payer) && context.workspaceAllOf(payer);
            case ModelAccessChoice.Subscription subscription -> host.id().equals(subscription.runnerHostId())
                    && host.personalOf(payer) && context.workspaceAllOf(payer);
        };
    }

    /**
     * The run stays queued for this reason. It is written only when the code or a field changed; {@code since} moves
     * only with the code.
     */
    private Optional<Assignment> waiting(RunStore.RunRow run, QueueReason reason, Instant now) {
        QueueReason current = run.queueReason();
        if (!reason.equals(current)) {
            boolean sameCode = current != null && current.code() == reason.code();
            Instant since = sameCode && run.queueReasonSince() != null ? run.queueReasonSince() : now;
            runs.setQueueReason(run.id(), reason, since);
            if (!sameCode) {
                publisher.publishEvent(TurnStarter.queueReasonChanged(run, reason));
            }
            hub.changedAfterCommit(run.id());
        }
        return Optional.empty();
    }
}
