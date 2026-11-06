package com.flowtivo.tinfirm.conversation.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.NewTurn;
import com.flowtivo.tinfirm.run.QueueReasonCode;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.run.TurnSteered;
import com.flowtivo.tinfirm.run.Turns;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The turns of conversations (the package 3 contract, §5.7): {@code Turns.request(conversation, reason)}, the end of a
 * turn's run ({@link TurnEnded}), and what a conversation waits on (the resolver of ADR-0028).
 * <ul>
 * <li>A turn is requested for an open conversation without an active run; it takes every waiting message, oldest first,
 * and its workspace's systems are resolved before its run is created. A runtime the agent dropped, or a subscription
 * whose host was removed, fails the turn at once without a run.</li>
 * <li>At a run's end the turn's result or failure is recorded, the agent's summary posted (none when the turn ended on
 * its own questions, ADR-0036), Tinfirm's note of a failure or a cancel posted, parts 2 and 3 told
 * ({@link TurnEndHook}), and the next turn started, unless decisions are open: after a succeeded turn the
 * {@code DECIDED} or {@code CONNECTED} turn of what was decided or ran while the run was active, else one for the
 * messages that waited.</li>
 * <li>A person's message written while a turn runs goes to that turn (steering, {@link Turns#steer}) when its host takes
 * it: {@code SENDING} until the host answers ({@link TurnSteered}), then the run's and {@code DELIVERED}, or waiting
 * again for the next turn. The end of a turn returns what is still on its way, which the next turn takes.</li>
 * <li>A turn that failed for a reason that may pass is retried by itself when its settings' owner chose so
 * (ADR-0073): a {@code RETRY} turn that nobody started, whose run waits {@code RETRY_SCHEDULED} until its time, at most
 * {@value #AUTO_RETRIES} in a row, after 1, 2 and 4 minutes; the failure reaches its place only once they are
 * spent.</li>
 * <li>What the conversation waits on, the first that applies: ended, nothing; an open decision, the person (its kind);
 * a waiting binding that holds the turn up (parked calls, or the active run waits {@code ACCOUNT_REQUIRED}), the person
 * ({@code ACCOUNT}); a running run, the agent; a queued run, the runner (its queue reason); a failed turn, the person
 * ({@code FAILED}); else nothing.</li>
 * </ul>
 */
@Service
class TurnService implements ConversationTurns, TurnEnded, TurnSteered {

    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

    static final String RUNTIME_REMOVED = "RUNTIME_REMOVED";

    static final String SUBSCRIPTION_GONE = "SUBSCRIPTION_GONE";

    /** Automatic retries in a row at most (ADR-0073). */
    static final int AUTO_RETRIES = 3;

    /** The wait before the first automatic retry; it doubles for each one after it. */
    static final Duration AUTO_RETRY_WAIT = Duration.ofMinutes(1);

    private final ConversationStore store;

    private final BindingStore bindingStore;

    private final BindingService bindings;

    private final WorkspaceService workspace;

    private final DecisionReads decisions;

    private final ConversationMessagesImpl messages;

    private final Turns turns;

    private final RunReads runs;

    private final Agents agents;

    private final Settings settings;

    private final List<TurnEndHook> hooks;

    private final TimeSource time;

    private final TriggerPorts triggers;

    private final ApplicationEventPublisher events;

    private final ToolCallStore toolCalls;

    private final Principals principals;

    TurnService(ConversationStore store, BindingStore bindingStore, BindingService bindings,
            WorkspaceService workspace, DecisionReads decisions, ConversationMessagesImpl messages, Turns turns,
            RunReads runs, Agents agents, Settings settings, List<TurnEndHook> hooks, TimeSource time,
            TriggerPorts triggers, ApplicationEventPublisher events, ToolCallStore toolCalls, Principals principals) {
        this.principals = principals;
        this.triggers = triggers;
        this.events = events;
        this.store = store;
        this.toolCalls = toolCalls;
        this.bindingStore = bindingStore;
        this.bindings = bindings;
        this.workspace = workspace;
        this.decisions = decisions;
        this.messages = messages;
        this.turns = turns;
        this.runs = runs;
        this.agents = agents;
        this.settings = settings;
        this.hooks = hooks;
        this.time = time;
    }

    // ---- requesting a turn -----------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> request(UUID companyId, UUID conversationId, TurnReason reason) {
        return start(companyId, conversationId, reason, null);
    }

    /**
     * Starts a turn, as {@link #request} asks; with {@code scheduled}, the system's automatic retry of a failed turn
     * (ADR-0073), whose run waits until its time. Any other turn counts the automatic retries from nothing again.
     */
    private Optional<UUID> start(UUID companyId, UUID conversationId, TurnReason reason,
            NewTurn.@Nullable Scheduled scheduled) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.open() || runs.activeOf(companyId, conversationId).isPresent()) {
            return Optional.empty();
        }
        if ((reason == TurnReason.DECIDED || reason == TurnReason.CONNECTED) && holdsUp(conversation)) {
            return Optional.empty();
        }
        if (triggers.pausedTrigger(conversation).isPresent()) {
            // A paused trigger's conversation takes no new turn: what asked for one (messages, firings, decisions)
            // waits, and the trigger's resume asks again (the package 4 contract, §5.11, §11).
            refreshWaiting(companyId, conversationId);
            return Optional.empty();
        }
        AgentInfo agent = agents.find(companyId, conversation.agentId()).filter(AgentInfo::live).orElse(null);
        if (agent == null) {
            return Optional.empty();
        }
        if (!agent.runtimes().contains(conversation.runtime())) {
            failWithoutRun(conversation, RUNTIME_REMOVED, agent.name() + " no longer runs on "
                    + conversation.runtime() + ", which this conversation was started with. Start a new conversation "
                    + "with " + agent.name() + ".");
            return Optional.empty();
        }
        if (conversation.modelAccess() == null) {
            failWithoutRun(conversation, SUBSCRIPTION_GONE, "The personal runner host of the subscription that pays "
                    + "for this conversation was removed. Start a new conversation.");
            return Optional.empty();
        }
        EffectiveSettings effective = settings.effective(companyId, conversation.agentId(),
                conversation.settingsOwner());
        Set<UUID> asked = resolveWorkspace(conversation, effective);

        int number = conversation.turnCount() + 1;
        UUID createdBy = scheduled != null ? null : CurrentActor.user().map(AuthenticatedUser::id).orElse(null);
        Optional<UUID> runId = turns.start(new NewTurn(companyId, conversationId, conversation.key(),
                conversation.agentId(), conversation.runtime(), reason, number, conversation.actingPrincipalId(),
                conversation.payerPrincipalId(), conversation.modelAccess(), createdBy, conversation.triggerId(),
                scheduled));
        if (runId.isEmpty()) {
            return Optional.empty();
        }
        // The bindings its workspace made just now were asked for by this turn's run.
        bindingStore.askedBy(companyId, conversationId, asked, runId.get());
        announceAgentContent(conversation, effective);
        if (reason == TurnReason.RETRY && conversation.lastRunId() != null) {
            store.retake(companyId, conversation.lastRunId(), runId.get());
        }
        store.takeWaiting(companyId, conversationId, runId.get());
        // A trigger's conversation's turn takes the firings that waited too: they are its request (§5.6).
        triggers.taken(conversation, runId.get());
        store.turnStarted(companyId, conversationId, runId.get(), number, scheduled != null ? scheduled.attempt() : 0,
                time.now());
        refreshWaiting(companyId, conversationId);
        return runId;
    }

    /**
     * A turn that uses other instructions or skills of its agent than the conversation's turn before (the security
     * review of 2026-10-27, D1: a change that widens nothing reaches everyone's next turn) says so in the conversation,
     * with when and by whom, and records the version it uses. A conversation's first turn records it only.
     */
    private void announceAgentContent(ConversationInfo conversation, EffectiveSettings effective) {
        UUID companyId = conversation.companyId();
        int using = effective.agent().contentVersion();
        Integer before = store.agentContentVersion(companyId, conversation.id());
        if (before != null && before == using) {
            return;
        }
        if (before != null) {
            agents.contentChange(companyId, conversation.agentId(), before, using)
                .ifPresent(change -> messages.system(companyId, conversation.id(), contentNotice(companyId, change),
                        null));
        }
        store.setAgentContentVersion(companyId, conversation.id(), using);
    }

    /** "The agent's instructions changed on 2026-10-27 by Jane Doe." */
    private String contentNotice(UUID companyId, Agents.ContentChange change) {
        String what = change.instructions() && change.skills() ? "instructions and skills"
                : change.instructions() ? "instructions" : "skills";
        String by;
        if (change.byUserId() == null) {
            by = "Tinfirm";
        }
        else {
            by = principals.ofMember(companyId, change.byUserId())
                .map(principal -> principals.displayNames(companyId, List.of(principal.id())).get(principal.id()))
                .orElse("someone who is no longer a member");
        }
        String on = DAY.format(change.at());
        if (change.to() < change.from()) {
            return "This conversation's turns use the agent's " + what + " of version " + change.to() + " again: "
                    + by + " changed them on " + on + " in version " + change.version()
                    + ", which waits for acceptance.";
        }
        return "The agent's " + what + " changed on " + on + " by " + by + ".";
    }

    /**
     * The workspace's systems are bound (§5.3) before the turn's run is created; answers the systems that had no
     * binding, whose bindings the run then names.
     */
    Set<UUID> resolveWorkspace(ConversationInfo conversation, EffectiveSettings effective) {
        Set<UUID> systems = new LinkedHashSet<>();
        for (ConversationWorkspace.Repository repository : workspace.of(conversation, effective)) {
            if (repository.binding() == null) {
                systems.add(repository.systemId());
            }
        }
        for (UUID systemId : systems) {
            bindings.resolve(conversation.companyId(), conversation.id(), systemId);
        }
        return systems;
    }

    /** Whether a decision or a binding with parked calls of the conversation is open: no DECIDED turn yet. */
    private boolean holdsUp(ConversationInfo conversation) {
        return !decisions.open(conversation.companyId(), conversation.id()).isEmpty()
                || bindingStore.ofConversation(conversation.companyId(), conversation.id())
                    .stream()
                    .anyMatch(row -> row.info().waiting() && row.parkedCalls() > 0);
    }

    private void failWithoutRun(ConversationInfo conversation, String failureClass, String cause) {
        store.turnFailedWithoutRun(conversation.companyId(), conversation.id(), failureClass, cause, time.now());
        messages.system(conversation.companyId(), conversation.id(), cause, null);
        refreshWaiting(conversation.companyId(), conversation.id());
        log.info("A turn of conversation {} failed before its run: {}", conversation.key(), failureClass);
    }

    // ---- what the conversation waits on ----------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshWaiting(UUID companyId, UUID conversationId) {
        ConversationInfo conversation = store.find(companyId, conversationId).map(ConversationStore.ConversationRow::info)
            .orElse(null);
        if (conversation == null) {
            return;
        }
        RunInfo active = conversation.activeRunId() == null ? null
                : runs.find(companyId, conversation.activeRunId()).filter(run -> run.status().active()).orElse(null);
        List<DecisionReads.Open> open = decisions.open(companyId, conversationId);
        List<BindingStore.BindingRow> waitingBindings = bindingStore.ofConversation(companyId, conversationId)
            .stream()
            .filter(row -> row.info().waiting())
            .toList();
        boolean parked = waitingBindings.stream().anyMatch(row -> row.parkedCalls() > 0);

        ConversationInfo.TurnStatus status = conversation.turnStatus();
        if (active != null) {
            status = active.status() == RunStatus.RUNNING ? ConversationInfo.TurnStatus.RUNNING
                    : ConversationInfo.TurnStatus.QUEUED;
        }
        else if (status == ConversationInfo.TurnStatus.SUCCEEDED || status == ConversationInfo.TurnStatus.WAITING) {
            status = !open.isEmpty() || parked ? ConversationInfo.TurnStatus.WAITING
                    : ConversationInfo.TurnStatus.SUCCEEDED;
        }

        ConversationInfo.WaitingOn on;
        String reason;
        if (!conversation.open()) {
            on = ConversationInfo.WaitingOn.NONE;
            reason = null;
        }
        else if (!open.isEmpty()) {
            on = ConversationInfo.WaitingOn.PERSON;
            // A result's review is REVIEW, so that the page says "Waits for Analysts · Review" (with its group).
            reason = open.getFirst().waitingReason();
        }
        else if (!waitingBindings.isEmpty() && (parked
                || active != null && active.queueReason() == QueueReasonCode.ACCOUNT_REQUIRED)) {
            on = ConversationInfo.WaitingOn.PERSON;
            reason = "ACCOUNT";
        }
        else if (active != null && active.status() == RunStatus.RUNNING) {
            on = ConversationInfo.WaitingOn.AGENT;
            reason = "RUNNING";
        }
        else if (active != null) {
            on = ConversationInfo.WaitingOn.RUNNER;
            reason = active.queueReason() != null ? active.queueReason().name() : "QUEUED";
        }
        else if (status == ConversationInfo.TurnStatus.FAILED) {
            on = ConversationInfo.WaitingOn.PERSON;
            reason = "FAILED";
        }
        else if (conversation.triggered() && triggers.pausedTrigger(conversation).isPresent()
                && (triggers.firingsWaiting(conversation) || !store.waitingMessages(companyId, conversationId)
                    .isEmpty())) {
            // What asked for a turn waits for the paused trigger's resume.
            on = ConversationInfo.WaitingOn.RUNNER;
            reason = QueueReasonCode.TRIGGER_PAUSED.name();
        }
        else {
            on = ConversationInfo.WaitingOn.NONE;
            reason = null;
        }
        store.updateWaiting(companyId, conversationId, status, on, reason, time.now());
    }

    /**
     * The run module's events of a conversation's run, in their transaction: its turn's status and wait. That
     * transaction locked the conversation's row before the run's (ConversationLock, CONVENTIONS §10).
     */
    @EventListener
    void runAccepted(RunEvents.RunAccepted event) {
        // The messages written while the turn was queued go to it now that it runs (steering): its host appends them
        // to the prompt, or gives them to the agent after its current step. Answers wait for # Answers.
        ConversationInfo conversation = store.find(event.companyId(), event.conversationId())
            .map(ConversationStore.ConversationRow::info)
            .orElse(null);
        if (conversation != null && conversation.open() && event.runId().equals(conversation.activeRunId())) {
            for (ConversationStore.MessageRow waiting : store.waitingMessages(event.companyId(),
                    event.conversationId())) {
                if (waiting.steering() == null && !waiting.answersOpenQuestions()
                        && waiting.authorPrincipalId() != null) {
                    steer(conversation, waiting.id(), waiting.authorPrincipalId(), waiting.body());
                }
            }
        }
        refreshWaiting(event.companyId(), event.conversationId());
    }

    @EventListener
    void runQueueReasonChanged(RunEvents.RunQueueReasonChanged event) {
        refreshWaiting(event.companyId(), event.conversationId());
    }

    @EventListener
    void runResumed(RunEvents.RunResumed event) {
        refreshWaiting(event.companyId(), event.conversationId());
    }

    // ---- steering: a message for the running turn (Turns.steer, TurnSteered) ---------------------------------------

    /**
     * A person's message that waits while a turn runs goes to that turn's agent, which takes it after its current
     * step, when the run is RUNNING on a host that takes steers: {@code SENDING} until the host answers. Under the
     * conversation's lock, which the caller holds.
     *
     * @return whether it went
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean steer(ConversationInfo conversation, UUID messageId, UUID authorPrincipalId, String body) {
        String author = principals.displayNames(conversation.companyId(), List.of(authorPrincipalId))
            .getOrDefault(authorPrincipalId, "The person");
        Optional<UUID> run = turns.steer(conversation.companyId(), conversation.id(), messageId,
                PromptRenderer.steerText(author, body));
        run.ifPresent(runId -> store.steerSending(conversation.companyId(), conversation.id(), messageId));
        return run.isPresent();
    }

    /**
     * The host's answer to a steer, in the run module's transaction, which locked the conversation's row before the
     * run's: the message is the run's when the run is still the conversation's active one and the message still waits;
     * otherwise it waits for the next turn.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void steered(ActiveRun run, UUID steerId, boolean delivered, @Nullable String code) {
        ConversationStore.ConversationRow row = store.lock(run.companyId(), run.conversationId()).orElse(null);
        if (row == null) {
            return;
        }
        if (delivered && run.runId().equals(row.info().activeRunId())
                && store.steerDelivered(run.companyId(), run.conversationId(), steerId, run.runId())) {
            log.info("Run {} took message {} while it worked", run.runId(), steerId);
            return;
        }
        store.steerReturned(run.companyId(), run.conversationId(), steerId);
    }

    // ---- the end of a turn's run (TurnEnded) -----------------------------------------------------------------------

    /**
     * In the run module's transaction, which locked the conversation's row before the run's (ConversationLock,
     * CONVENTIONS §10): the lock below is the one it holds already.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void turnEnded(TurnEnd end) {
        ConversationStore.ConversationRow row = store.lock(end.companyId(), end.conversationId()).orElse(null);
        if (row == null) {
            return;
        }
        // The run's tool calls keep no answer past its end (package R, §3.2); their rows go after the retention.
        toolCalls.runEnded(end.runId(), time.now());
        ConversationInfo conversation = row.info();
        if (!end.runId().equals(conversation.activeRunId()) && !end.runId().equals(conversation.lastRunId())) {
            refreshWaiting(end.companyId(), end.conversationId());
            return;
        }
        ConversationInfo.TurnStatus status = switch (end.status()) {
            case SUCCEEDED -> ConversationInfo.TurnStatus.SUCCEEDED;
            case CANCELLED -> ConversationInfo.TurnStatus.CANCELLED;
            default -> ConversationInfo.TurnStatus.FAILED;
        };
        RunInfo.Result result = end.result();
        String failureClass = status == ConversationInfo.TurnStatus.FAILED
                ? (end.failureClass() != null ? end.failureClass() : end.status() == RunStatus.LOST ? "LOST" : null)
                : null;
        String summary = result != null ? result.summary() : end.summary();
        // What was on its way to this turn and got no answer waits for the next one.
        store.steerReturned(end.companyId(), end.conversationId(), null);
        store.turnEnded(end.companyId(), end.conversationId(), end.runId(), status,
                result != null ? result.outcome().name() : null, summary,
                result != null ? result.nextSteps() : List.of(), result != null ? result.openQuestions() : List.of(),
                failureClass, status == ConversationInfo.TurnStatus.FAILED ? end.failureMessage() : null, time.now());

        boolean retrying = false;
        switch (status) {
            case SUCCEEDED -> {
                // A turn that ended on its own questions posts no summary (ADR-0036).
                boolean ownQuestions = result == null && decisions.openQuestionsOfRun(end.companyId(), end.runId());
                String body = result != null ? resultMessage(result) : summary;
                if (body != null && !body.isBlank() && !ownQuestions) {
                    messages.agent(end.companyId(), end.conversationId(), body, end.runId());
                }
            }
            case FAILED -> {
                // A failure that may pass is retried by itself when the settings' owner chose so (ADR-0073); its
                // note says so only once the retry's turn is there.
                AutoRetry retry = autoRetry(conversation, end, failureClass);
                if (retry.scheduled() != null && start(end.companyId(), end.conversationId(), TurnReason.RETRY,
                        retry.scheduled()).isPresent()) {
                    retrying = true;
                }
                messages.system(end.companyId(), end.conversationId(), failureMessage(end, failureClass,
                        retrying ? retry.scheduled() : null, retry.spent()), end.runId());
            }
            case CANCELLED -> {
                messages.system(end.companyId(), end.conversationId(), "The turn was cancelled.", end.runId());
                // What waited for the cancelled turn stays unanswered (§5.7).
                store.dropWaiting(end.companyId(), end.conversationId());
            }
            default -> {
            }
        }
        ConversationInfo after = store.find(end.companyId(), end.conversationId()).orElseThrow().info();
        for (TurnEndHook hook : hooks) {
            hook.turnEnded(after, end);
        }
        // In this transaction, after the hooks (the package 4 contract, open point 14): the trigger module listens.
        events.publishEvent(new ConversationEvents.TurnRecorded(end.companyId(), end.conversationId(), end.runId(),
                after.place(), after.triggerId(), end.status(), result != null ? result.outcome() : null, retrying));
        after = store.find(end.companyId(), end.conversationId()).orElseThrow().info();
        if (after.open() && !retrying) {
            next(after, status);
        }
        refreshWaiting(end.companyId(), end.conversationId());
    }

    /**
     * Requests the turn that what waits asks for, as the end of a turn does, outside one: after a paused trigger's
     * resume, a trigger conversation's rebinding (the package 4 contract, §5.11, §11), or a firing on an open
     * conversation ({@code EVENT}, §5.6). Nothing while a run is active or a decision open; under the conversation's
     * lock, which the caller holds.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> requestNext(UUID companyId, UUID conversationId) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.open() || conversation.activeRunId() != null
                || !decisions.open(companyId, conversationId).isEmpty()) {
            refreshWaiting(companyId, conversationId);
            return Optional.empty();
        }
        TurnReason reason = decisions.decidedUntold(companyId, conversationId) ? TurnReason.DECIDED
                : bindingStore.settledCallsUntold(companyId, conversationId) ? TurnReason.CONNECTED
                        : triggers.firingsWaiting(conversation) ? TurnReason.EVENT
                                : !store.waitingMessages(companyId, conversationId).isEmpty() ? TurnReason.MESSAGE
                                        : null;
        Optional<UUID> runId = reason != null ? request(companyId, conversationId, reason) : Optional.empty();
        refreshWaiting(companyId, conversationId);
        return runId;
    }

    /**
     * The turn that follows a turn's end (§5.7), in its transaction, under the conversation's lock. Nothing while a
     * decision is open. After a SUCCEEDED turn: a {@code DECIDED} turn when a decision was decided and no turn told it,
     * else a {@code CONNECTED} turn when a call that waited for a binding ran or was refused and no turn told it: both
     * happened while the run was active, so their own requests started nothing (I11), and the agent ended its turn to
     * wait for them. Else a {@code MESSAGE} turn for messages that waited. A cancelled turn starts nothing (its
     * waiting messages are unanswered already); a failed or lost turn only the {@code MESSAGE} turn: what was
     * decided waits for the person's retry, which tells it, and a turn that failed before its run was told never
     * starts itself again. Never two: the conversation's lock orders this end and a decision's or a call's commit,
     * {@link #request} starts nothing while a run is active, and {@code Turns.start} answers empty then (I11).
     */
    private void next(ConversationInfo conversation, ConversationInfo.TurnStatus status) {
        UUID companyId = conversation.companyId();
        UUID conversationId = conversation.id();
        if (!decisions.open(companyId, conversationId).isEmpty()) {
            return;
        }
        if (status == ConversationInfo.TurnStatus.SUCCEEDED) {
            TurnReason reason = decisions.decidedUntold(companyId, conversationId) ? TurnReason.DECIDED
                    : bindingStore.settledCallsUntold(companyId, conversationId) ? TurnReason.CONNECTED : null;
            if (reason != null && request(companyId, conversationId, reason).isPresent()) {
                return;
            }
        }
        // A trigger's firings that arrived during the run wait as messages do: an EVENT turn takes them (and any
        // message that waited), so that no event is lost (the package 4 contract, §5.6).
        if (status != ConversationInfo.TurnStatus.CANCELLED && triggers.firingsWaiting(conversation)
                && request(companyId, conversationId, TurnReason.EVENT).isPresent()) {
            return;
        }
        if (!store.waitingMessages(companyId, conversationId).isEmpty()) {
            request(companyId, conversationId, TurnReason.MESSAGE);
        }
    }

    /**
     * What follows a failed turn by itself (ADR-0073): {@code scheduled}, an automatic retry, when the failure may pass
     * ({@link #mayPass}), the conversation is open, nothing else waits (a decision, a firing, a message: the turn that
     * takes it comes first, as after any failure), its trigger is not paused, the settings' owner chose automatic
     * retries, and fewer than {@value #AUTO_RETRIES} came in a row; {@code spent} when only the last is not so.
     */
    private AutoRetry autoRetry(ConversationInfo conversation, TurnEnd end, @Nullable String failureClass) {
        UUID companyId = conversation.companyId();
        UUID conversationId = conversation.id();
        if (!conversation.open() || !mayPass(end, failureClass) || !decisions.open(companyId, conversationId).isEmpty()
                || triggers.firingsWaiting(conversation) || !store.waitingMessages(companyId, conversationId).isEmpty()
                || triggers.pausedTrigger(conversation).isPresent()) {
            return AutoRetry.NONE;
        }
        if (!settings.effective(companyId, conversation.agentId(), conversation.settingsOwner()).autoRetry()) {
            return AutoRetry.NONE;
        }
        int done = store.automaticRetries(companyId, conversationId);
        if (done >= AUTO_RETRIES) {
            return new AutoRetry(null, true);
        }
        int attempt = done + 1;
        return new AutoRetry(new NewTurn.Scheduled(time.now().plus(waitBefore(attempt)), attempt, AUTO_RETRIES),
                false);
    }

    /**
     * Whether a failed turn's cause may pass by itself (ADR-0073): the model's provider or gateway was overloaded,
     * limiting or unavailable ({@code SERVER_LIMITED}), or its runner host lost the run for good: it restarted, or no
     * longer knew the run when it reconnected. A run lost because its lease expired may still be taken back by its host
     * within the resume window, which a retry's active run would refuse; a person retries it.
     */
    static boolean mayPass(TurnEnd end, @Nullable String failureClass) {
        if (RunnerMessages.FailureClass.SERVER_LIMITED.name().equals(failureClass)) {
            return true;
        }
        return end.status() == RunStatus.LOST
                && ("HOST_RESTARTED".equals(end.lostCause()) || "STALE_ON_RECONNECT".equals(end.lostCause()));
    }

    /** The wait before the {@code attempt}th automatic retry in a row: 1, 2, 4 minutes. */
    static Duration waitBefore(int attempt) {
        return AUTO_RETRY_WAIT.multipliedBy(1L << Math.max(0, attempt - 1));
    }

    /** A failed turn's automatic retry, if any, and whether the retries in a row are spent. */
    private record AutoRetry(NewTurn.@Nullable Scheduled scheduled, boolean spent) {

        static final AutoRetry NONE = new AutoRetry(null, false);
    }

    /** In the run module's transaction, which locked the conversation's row before the run's, as for an end. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean turnResumed(ActiveRun run) {
        ConversationStore.ConversationRow row = store.lock(run.companyId(), run.conversationId()).orElse(null);
        if (row == null) {
            return false;
        }
        ConversationInfo conversation = row.info();
        if (!conversation.open() || conversation.activeRunId() != null
                || !run.runId().equals(conversation.lastRunId())) {
            return false;
        }
        store.turnResumed(run.companyId(), run.conversationId(), run.runId(), time.now());
        toolCalls.runResumed(run.runId());
        messages.system(run.companyId(), run.conversationId(), "The turn goes on: its runner host still runs it.",
                run.runId());
        return true;
    }

    /** The agent's message at the end of a turn: the summary, then the next steps and the open questions. */
    static String resultMessage(RunInfo.Result result) {
        StringBuilder body = new StringBuilder(result.summary().strip());
        if (body.isEmpty()) {
            body.append(result.outcome() == RunnerMessages.ResultOutcome.BLOCKED ? "Blocked." : "Done.");
        }
        if (!result.nextSteps().isEmpty()) {
            body.append("\n\n**Next steps**\n");
            result.nextSteps().forEach(step -> body.append("\n- ").append(step));
        }
        if (!result.openQuestions().isEmpty()) {
            body.append("\n\n**Open questions**\n");
            result.openQuestions().forEach(question -> body.append("\n- ").append(question));
        }
        return body.toString();
    }

    /** A reset's time in a failed turn's note: "12 Nov at 00:00 UTC", never an instant. */
    /** The day of a change of an agent's instructions, in its notice. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
        .withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter RESETS = DateTimeFormatter.ofPattern("d MMM 'at' HH:mm 'UTC'", Locale.ENGLISH)
        .withZone(ZoneOffset.UTC);

    /**
     * Tinfirm's note of a failed turn, with the reset time of a spent budget (open point 8). A turn stopped at its time
     * limit is not told as a failure: its session and its work are kept, and a retry goes on from where it stopped.
     */
    static String failureMessage(TurnEnd end, @Nullable String failureClass) {
        return failureMessage(end, failureClass, null, false);
    }

    /**
     * Tinfirm's note of a failed turn, saying when it is retried by itself ({@code retry}, ADR-0073) or that the
     * automatic retries in a row are spent ({@code spent}).
     */
    static String failureMessage(TurnEnd end, @Nullable String failureClass, NewTurn.@Nullable Scheduled retry,
            boolean spent) {
        if (RunnerMessages.FailureClass.TIME_LIMIT.name().equals(failureClass)) {
            // The runner's message, which names the limit, is on the turn; the note says what to do.
            return "The turn reached its time limit and stopped. Its work so far is kept: retry the turn to go on from "
                    + "where it stopped, or say what to do next.";
        }
        StringBuilder body = new StringBuilder();
        if (end.status() == RunStatus.LOST) {
            body.append("The turn was lost: its runner host stopped reporting it.");
        }
        else {
            body.append("The turn failed");
            if (failureClass != null) {
                body.append(" (").append(failureClass).append(')');
            }
            body.append('.');
        }
        if (end.failureMessage() != null && !end.failureMessage().isBlank()) {
            body.append(' ').append(end.failureMessage().strip());
        }
        Instant retryAt = end.retryAt();
        if ("USAGE_LIMIT".equals(failureClass) && retryAt != null) {
            // Stored text, read in any locale and zone: one fixed human form, in UTC ("on 12 Nov at 00:00 UTC").
            body.append(" The budget resets on ").append(RESETS.format(retryAt)).append("; retry the turn then.");
        }
        else if (retry != null) {
            long minutes = waitBefore(retry.attempt()).toMinutes();
            body.append(" Tinfirm retries it by itself in ").append(minutes)
                .append(minutes == 1 ? " minute" : " minutes")
                .append(" (automatic retry ").append(retry.attempt()).append(" of ").append(retry.attempts())
                .append("); cancel the turn to stop it.");
        }
        else if (spent) {
            body.append(" Tinfirm retried it by itself ").append(AUTO_RETRIES)
                .append(" times in a row; retry the turn, or say what to do next.");
        }
        else {
            body.append(" Retry the turn, or say what to do next.");
        }
        return body.toString();
    }
}
