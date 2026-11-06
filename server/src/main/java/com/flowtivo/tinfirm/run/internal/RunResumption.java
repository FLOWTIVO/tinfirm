package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

/**
 * Resumes a run that the server marked LOST because its lease expired, when its runner host shows that it still runs
 * it: the host lists it in {@code hello.activeRuns}, or sends {@code run.heartbeat} or {@code run.finished} for it.
 * All of these must hold, else nothing changes and the caller tells the host to cancel:
 * <ol>
 * <li>the run is LOST with the cause {@link LostCause#LEASE_EXPIRED};</li>
 * <li>the host is the run's host and reports the run's epoch;</li>
 * <li>it was marked LOST less than {@code TINFIRM_RUNNER_LOST_RESUME_WINDOW} ago;</li>
 * <li>it is the conversation's latest run, and the conversation takes the turn back
 * ({@link com.flowtivo.tinfirm.run.TurnEnded#turnResumed}), both decided under the conversation's lock (CONVENTIONS
 * §10): a turn the person starts at that moment ({@code Turns.start} locks the conversation too) is either the latest
 * already, or starts after the resumption and waits for it, and never meets the resumed run in
 * {@code run_one_active_per_conversation_uq}.</li>
 * </ol>
 * Resuming undoes what marking the run LOST did: the row is RUNNING again with a fresh lease; its repositories have
 * no git result; the commands that its end completed are pending again; its model token, which a loss by lease expiry
 * does not revoke, answers again; {@code run.resumed} follows {@code run.finished} in the audit log; live streams get a
 * signal.
 */
@Component
class RunResumption {

    private static final Logger log = LoggerFactory.getLogger(RunResumption.class);

    private final RunStore runs;

    private final RunLocks locks;

    private final RunRepositoryStore repositories;

    private final RunCommandStore commands;

    private final RunPorts ports;

    private final RunEventHub hub;

    private final ApplicationEventPublisher publisher;

    private final AuditLog auditLog;

    private final RunnerProperties properties;

    RunResumption(RunStore runs, RunLocks locks, RunRepositoryStore repositories, RunCommandStore commands,
            RunPorts ports, RunEventHub hub, ApplicationEventPublisher publisher, AuditLog auditLog,
            RunnerProperties properties) {
        this.runs = runs;
        this.locks = locks;
        this.repositories = repositories;
        this.commands = commands;
        this.ports = ports;
        this.hub = hub;
        this.publisher = publisher;
        this.auditLog = auditLog;
        this.properties = properties;
    }

    /**
     * Resumes the run if the rule allows it. The caller holds the run's row lock, taken after its conversation's
     * ({@link RunLocks}, CONVENTIONS §10): the conversation takes the turn back in this transaction. The conversation
     * is locked again before the decision, at no cost, so that the decision never rests on a caller's lock alone.
     *
     * @param reason the frame that showed that the host still runs it: {@code hello}, {@code run.heartbeat} or
     *               {@code run.finished}
     * @return whether the run is RUNNING again
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resume(ConnectedHost host, RunStore.RunRow run, long epoch, Instant now, String reason) {
        if (run.status() != RunStatus.LOST || run.lostCause() != LostCause.LEASE_EXPIRED
                || !host.runnerHostId().equals(run.runnerHostId()) || run.epoch() != epoch
                || run.finishedAt() == null || !now.isBefore(run.finishedAt().plus(properties.lostResumeWindow()))) {
            return false;
        }
        // Under the conversation's lock no turn starts in it: the latest run read here stays the latest until the
        // commit, and a turn that started just before has committed and is read.
        locks.lockConversation(run);
        Optional<UUID> latest = runs.latestOfConversation(run.conversationId());
        if (latest.isEmpty() || !latest.get().equals(run.id())) {
            return false;
        }
        if (!ports.ended().turnResumed(run.active())) {
            return false;
        }
        if (!runs.resume(run.id(), run.epoch(), now.plus(properties.leaseTtl()))) {
            throw new IllegalStateException("Run " + run.id() + " could not be resumed although it is LOST");
        }
        repositories.reopen(run.id());
        commands.reopen(run.id(), run.finishedAt());
        auditLog.record(AuditRecord.company(run.companyId(), AuditAction.RUN_RESUMED, CurrentActor.actor())
            .target(RunCompletion.TARGET, run.id())
            .detail("conversationId", run.conversationId().toString())
            .detail("runnerHostId", host.runnerHostId().toString())
            .detail("epoch", run.epoch())
            .detail("reason", reason)
            .detail("lostAt", run.finishedAt().toString())
            .build());
        publisher.publishEvent(new RunEvents.RunResumed(run.companyId(), run.id(), run.conversationId()));
        hub.changedAfterCommit(run.id());
        log.info("Run {} of conversation {} resumed: runner host {} still runs it ({}); it was lost at {}", run.id(),
                run.conversationKey(), host.runnerHostId(), reason, run.finishedAt());
        return true;
    }
}
