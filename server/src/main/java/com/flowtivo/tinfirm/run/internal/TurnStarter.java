package com.flowtivo.tinfirm.run.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.NewTurn;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.Turns;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;

/**
 * {@link Turns} (the package 3 contract, §5.7): a turn becomes a QUEUED run of the conversation, in the conversation
 * module's transaction, with the branch {@code agent/{key}}. One active run per conversation is the database's
 * invariant (I11): the insert does nothing when the conversation has one, and the turn waits. Audited as
 * {@code run.started}; the dispatcher is nudged after the commit. Cancel ends a QUEUED run at once and asks the host
 * to stop a RUNNING one (a durable {@code run_command}, sent again on reconnect), which ends when the host reports it.
 * A person's message for a RUNNING run goes to its host as {@code run.steer} after the commit, when the host takes
 * steers; it is not sent again on reconnect, so one the host never answers waits for the next turn.
 * Both lock the conversation's row first (CONVENTIONS §10), at no cost for the caller that holds it already.
 */
@Service
class TurnStarter implements Turns {

    private static final Logger log = LoggerFactory.getLogger(TurnStarter.class);

    private final RunStore runs;

    private final RunPorts ports;

    private final RunCommandStore commands;

    private final RunCompletion completion;

    private final RunnerConnections connections;

    private final RunEventHub hub;

    private final ApplicationEventPublisher publisher;

    private final AuditLog auditLog;

    private final TimeSource time;

    TurnStarter(RunStore runs, RunPorts ports, RunCommandStore commands, RunCompletion completion,
            RunnerConnections connections, RunEventHub hub, ApplicationEventPublisher publisher, AuditLog auditLog,
            TimeSource time) {
        this.runs = runs;
        this.ports = ports;
        this.commands = commands;
        this.completion = completion;
        this.connections = connections;
        this.hub = hub;
        this.publisher = publisher;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> start(NewTurn turn) {
        ports.conversationLock().lock(turn.companyId(), turn.conversationId());
        // An automatic retry waits until its time (ADR-0073): the dispatcher does not look at it before, so the
        // reason stays, as a rejected run's does.
        NewTurn.Scheduled scheduled = turn.scheduled();
        RunStore.NewRun run = new RunStore.NewRun(Ids.newId(), turn.companyId(), turn.conversationId(),
                turn.conversationKey(), turn.agentId(), turn.runtime(), turn.reason(), turn.number(),
                turn.actingPrincipalId(), turn.payerPrincipalId(), turn.modelAccess(),
                Branches.of(turn.conversationKey()), turn.createdBy(), time.now(), turn.triggerId(),
                scheduled != null ? scheduled.at() : null, scheduled != null
                        ? QueueReason.retryScheduled(scheduled.at(), scheduled.attempt(), scheduled.attempts())
                        : null);
        if (!runs.insert(run)) {
            log.info("Turn {} of conversation {} waits: the conversation has an active run", turn.number(),
                    turn.conversationId());
            return Optional.empty();
        }
        auditLog.record(AuditRecord.company(turn.companyId(), AuditAction.RUN_STARTED, CurrentActor.actor())
            .target(RunCompletion.TARGET, run.id())
            .detail("conversationId", turn.conversationId().toString())
            .detail("agentId", turn.agentId().toString())
            .detail("turn", turn.number())
            .detail("turnReason", turn.reason().name())
            .detail("payerPrincipalId", turn.payerPrincipalId().toString())
            .detail("modelAccess", modelAccess(turn.modelAccess()))
            .details(scheduled != null ? Map.of("automatic", true, "attempt", scheduled.attempt(), "retryAt",
                    scheduled.at().toString()) : Map.of())
            .build());
        log.info("Run {} created for turn {} of conversation {} ({}{})", run.id(), turn.number(),
                turn.conversationKey(), turn.reason(), scheduled != null ? ", automatic " + scheduled.attempt()
                        + " of " + scheduled.attempts() + " at " + scheduled.at() : "");
        publisher.publishEvent(new DispatchNeeded(run.id()));
        return Optional.of(run.id());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> steer(UUID companyId, UUID conversationId, UUID steerId, String text) {
        if (text.isEmpty() || text.length() > RunnerMessages.MAX_STEER_TEXT) {
            throw new IllegalArgumentException("A steer's text has 1 to " + RunnerMessages.MAX_STEER_TEXT
                    + " characters");
        }
        ports.conversationLock().lock(companyId, conversationId);
        RunStore.RunRow run = runs.activeOfConversation(conversationId).orElse(null);
        if (run == null || run.status() != RunStatus.RUNNING || run.runnerHostId() == null
                || !connections.supports(run.runnerHostId(), RunnerMessages.FEATURE_RUN_STEER)) {
            return Optional.empty();
        }
        UUID hostId = run.runnerHostId();
        RunnerMessages.RunSteer steer = new RunnerMessages.RunSteer(run.id(), run.epoch(), steerId, text);
        AfterCommit.run(() -> connections.send(hostId, steer));
        log.info("Message {} goes to run {} of conversation {} (steering)", steerId, run.id(),
                run.conversationKey());
        return Optional.of(run.id());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> cancelActive(UUID companyId, UUID conversationId) {
        ports.conversationLock().lock(companyId, conversationId);
        Optional<RunStore.RunRow> active = runs.activeOfConversation(conversationId)
            .flatMap(found -> runs.lock(found.id()));
        active.ifPresent(this::cancel);
        return active.map(RunStore.RunRow::id);
    }

    /**
     * Cancels an active run under its row lock, which the caller took after the run's conversation's (a QUEUED run's
     * end tells {@code TurnEnded}): a QUEUED run ends at once, a RUNNING run gets a durable cancel command and
     * {@code run.cancel}. Idempotent; false when the run was no longer active.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean cancel(RunStore.RunRow run) {
        return cancel(run, null);
    }

    /**
     * As {@link #cancel(RunStore.RunRow)}, with the kill switch that cancelled it as {@code run.cancelled}'s
     * {@code cause} ({@code principal.suspended}, {@code company.runs_stopped}, {@code system.paused}; package 6).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean cancel(RunStore.RunRow run, @Nullable String cause) {
        switch (run.status()) {
            case QUEUED -> {
                audit(run, "QUEUED", cause);
                completion.cancelQueued(run);
                return true;
            }
            case RUNNING -> {
                if (commands.requestCancel(run.companyId(), run.id(), currentUser(), time.now())) {
                    audit(run, "RUNNING", cause);
                }
                UUID hostId = run.runnerHostId();
                if (hostId != null) {
                    RunnerMessages.RunCancel cancel = new RunnerMessages.RunCancel(run.id(), run.epoch());
                    AfterCommit.run(() -> connections.send(hostId, cancel));
                }
                hub.changedAfterCommit(run.id());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void audit(RunStore.RunRow run, String previousStatus, @Nullable String cause) {
        AuditRecord.Builder record = AuditRecord.company(run.companyId(), AuditAction.RUN_CANCELLED,
                CurrentActor.actor())
            .target(RunCompletion.TARGET, run.id())
            .detail("conversationId", run.conversationId().toString())
            .detail("agentId", run.agentId().toString())
            .detail("turn", run.turnNumber())
            .detail("previousStatus", previousStatus);
        if (cause != null) {
            record.detail("cause", cause);
        }
        auditLog.record(record.build());
    }

    static String modelAccess(ModelAccessChoice choice) {
        return choice instanceof ModelAccessChoice.Subscription ? RunStore.SUBSCRIPTION : RunStore.BUDGET;
    }

    private static @Nullable UUID currentUser() {
        return CurrentActor.user().map(AuthenticatedUser::id).orElse(null);
    }

    /** Publishes that a run's queue reason changed, for the conversation's {@code waiting_on}. */
    static RunEvents.RunQueueReasonChanged queueReasonChanged(RunStore.RunRow run, @Nullable QueueReason reason) {
        return new RunEvents.RunQueueReasonChanged(run.companyId(), run.id(), run.conversationId(),
                reason != null ? reason.code() : null);
    }
}
