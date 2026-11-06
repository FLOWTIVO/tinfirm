package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.model.RunModelContext;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;

/**
 * Ends runs, in one transaction with everything that follows from the end:
 * <ul>
 * <li>the run's row is final (status, result, failure, usage, counts, what the model relay passed for it), and each
 * repository of its workspace gets its git result, matched by repository; a run that failed with the runner's
 * catch-all after the gateway answered that it does not serve the run's model fails {@code MODEL_NOT_SERVED};</li>
 * <li>its pending commands are done; a LOST run records its cause;</li>
 * <li>its one-run model tokens are revoked (M7), but for a run lost by lease expiry, which its host may still bring
 * back: its token is inactive anyway while the run is not RUNNING, and the reaper revokes it once the resume window
 * is over;</li>
 * <li>the payer's spend is read again ({@link ModelAccess#refresh}, after the commit); a {@code USAGE_LIMIT} failure
 * carries the reset time of the payer's spent budget, or else the runtime's (package 3's open point 8);</li>
 * <li>the conversation is told ({@link TurnEnded}), and may start its next waiting turn in this transaction; then
 * {@link RunEvents.RunEnded} is published, on which the governance module settles the run's spend;</li>
 * <li>{@code run.finished} is audited; a run lost for another cause than its lease is cancelled on its host after the
 * commit (fencing); the freed slot nudges the dispatcher.</li>
 * </ul>
 * The caller holds the run's row lock, taken after its conversation's ({@link RunLocks}, CONVENTIONS §10), since the
 * conversation is told in this transaction.
 */
@Component
class RunCompletion {

    private static final Logger log = LoggerFactory.getLogger(RunCompletion.class);

    static final String TARGET = "run";

    /** The server's failure class of a run whose model the company's gateway answered it does not serve. */
    static final String MODEL_NOT_SERVED = "MODEL_NOT_SERVED";

    private final RunStore runs;

    private final RunRepositoryStore repositories;

    private final RunCommandStore commands;

    private final RunEventStore events;

    private final RunPorts ports;

    private final ModelRelay relay;

    private final ModelAccess modelAccess;

    private final RunnerConnections connections;

    private final RunEventHub hub;

    private final ApplicationEventPublisher publisher;

    private final AuditLog auditLog;

    private final TimeSource time;

    RunCompletion(RunStore runs, RunRepositoryStore repositories, RunCommandStore commands, RunEventStore events,
            RunPorts ports, ModelRelay relay, ModelAccess modelAccess, RunnerConnections connections, RunEventHub hub,
            ApplicationEventPublisher publisher, AuditLog auditLog, TimeSource time) {
        this.runs = runs;
        this.repositories = repositories;
        this.commands = commands;
        this.events = events;
        this.ports = ports;
        this.relay = relay;
        this.modelAccess = modelAccess;
        this.connections = connections;
        this.hub = hub;
        this.publisher = publisher;
        this.auditLog = auditLog;
        this.time = time;
    }

    /** Ends an active run with a final status other than LOST; the caller holds its conversation's and its lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(RunStore.RunRow run, RunStore.Outcome outcome) {
        finish(run, outcome, List.of());
    }

    /** Ends an active run with a final status other than LOST, with the files of {@code /output} its host listed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(RunStore.RunRow run, RunStore.Outcome outcome, List<TurnEnded.OutputFile> outputFiles) {
        if (outcome.status() == RunStatus.LOST) {
            throw new IllegalStateException("A run is lost with lose(), which records the cause");
        }
        end(run, outcome, null, outputFiles);
    }

    /** Marks an active run LOST for the given cause; the caller holds its conversation's and its row lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lose(RunStore.RunRow run, LostCause cause, String message) {
        end(run, RunStore.Outcome.of(RunStatus.LOST, "LOST", message), cause, List.of());
    }

    /** A person cancels a QUEUED run: it ends at once; an assigned one is cancelled on its host too. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelQueued(RunStore.RunRow run) {
        if (run.status() != RunStatus.QUEUED) {
            throw new IllegalStateException("Only a queued run can be cancelled at once");
        }
        finish(run, RunStore.Outcome.of(RunStatus.CANCELLED, null, null));
        if (run.dispatchedAt() != null && run.runnerHostId() != null) {
            // It was assigned: the host may have started it already.
            UUID hostId = run.runnerHostId();
            AfterCommit.run(() -> connections.send(hostId, new RunnerMessages.RunCancel(run.id(), run.epoch())));
        }
    }

    private void end(RunStore.RunRow run, RunStore.Outcome reported, @Nullable LostCause lostCause,
            List<TurnEnded.OutputFile> outputFiles) {
        if (!run.status().active() || reported.status().active()) {
            throw new IllegalStateException("Only an active run can end, with a final status");
        }
        RunStore.Outcome outcome = notServed(run, reported);
        Instant now = time.now();
        runs.finish(run.id(), outcome, relay.usage(run.companyId(), run.id()), now);
        List<TurnEnded.RepositoryResult> workspace = finishRepositories(run, outcome);
        commands.completeAll(run.id(), now);
        if (lostCause != null) {
            runs.recordLoss(run.id(), lostCause);
        }
        if (lostCause != LostCause.LEASE_EXPIRED) {
            relay.revoke(run.companyId(), run.id());
        }
        modelAccess.refresh(run.companyId(), run.payerPrincipalId());

        Instant retryAt = "USAGE_LIMIT".equals(outcome.failureClass()) ? resetOf(run) : null;
        RunStore.RunRow ended = runs.find(run.id()).orElseThrow();
        ports.ended().turnEnded(new TurnEnded.TurnEnd(run.companyId(), run.id(), run.conversationId(), run.agentId(),
                run.turnReason(), run.turnNumber(), run.actingPrincipalId(), run.payerPrincipalId(), run.choice(),
                outcome.status(), outcome.failureClass(), outcome.failureMessage(), ended.summary(),
                ended.publicResult(), retryAt, workspace, outputFiles,
                lostCause != null ? lostCause.name() : null));
        // Package 6 (§2.2): the run's end for other modules, after the conversation was told.
        publisher.publishEvent(new RunEvents.RunEnded(run.companyId(), run.id(), run.conversationId(),
                outcome.status()));

        List<String> pushed = workspace.stream()
            .filter(repository -> Boolean.TRUE.equals(repository.pushed()) && repository.repositoryId() != null)
            .map(repository -> repository.repositoryId().toString())
            .toList();
        int commits = workspace.stream().mapToInt(repository -> repository.commits() != null ? repository.commits()
                : 0).sum();
        auditLog.record(AuditRecord.company(run.companyId(), AuditAction.RUN_FINISHED, CurrentActor.actor())
            .target(TARGET, run.id())
            .detail("conversationId", run.conversationId().toString())
            .detail("agentId", run.agentId().toString())
            .detail("turn", run.turnNumber())
            .detail("status", outcome.status().name())
            .detail("failureClass", outcome.failureClass())
            .detail("lostCause", lostCause != null ? lostCause.name() : null)
            .detail("pushedRepositoryIds", pushed)
            .detail("commits", commits)
            .detail("resultOutcome", outcome.result() != null ? outcome.result().outcome().name() : null)
            .build());
        log.info("Run {} of conversation {} ended {} (turn {})", run.id(), run.conversationKey(), outcome.status(),
                run.turnNumber());

        hub.changedAfterCommit(run.id());
        if (lostCause != null && lostCause != LostCause.LEASE_EXPIRED && run.runnerHostId() != null) {
            // Fencing: tell a host that may still run it to stop and discard it. A run lost by lease expiry may be
            // resumed instead; its host is told when it next reports the run and the run cannot be resumed.
            UUID hostId = run.runnerHostId();
            AfterCommit.run(() -> connections.send(hostId, new RunnerMessages.RunCancel(run.id(), run.epoch())));
        }
        publisher.publishEvent(new DispatchNeeded(run.id()));
    }

    /**
     * The outcome as the run's end records it: a run that failed with the runner's catch-all ({@code PROTOCOL_ERROR},
     * or no class) after the gateway answered that it does not serve the model the run asked for fails
     * {@code MODEL_NOT_SERVED}, in words that say what to change; only this epoch's answer counts. The runner sees only
     * the runtime's error; the relay saw the gateway's answer.
     */
    private RunStore.Outcome notServed(RunStore.RunRow run, RunStore.Outcome outcome) {
        if (outcome.status() != RunStatus.FAILED
                || outcome.failureClass() != null && !"PROTOCOL_ERROR".equals(outcome.failureClass())) {
            return outcome;
        }
        return relay.modelNotServed(run.companyId(), run.id(), run.epoch())
            .map(model -> outcome.failedAs(MODEL_NOT_SERVED, "The company's model gateway does not serve the model "
                    + model + ": choose a model it serves in the settings."))
            .orElse(outcome);
    }

    /**
     * When a run that hit its usage limit may go on: the reset of the payer's spent budget, else the latest reset a
     * RATE_LIMIT event of the runtime named (a subscription's limit); null when neither is known.
     */
    private @Nullable Instant resetOf(RunStore.RunRow run) {
        ModelAccessChoice choice = run.choice();
        if (choice instanceof ModelAccessChoice.Budget) {
            RunModelAccess access = modelAccess.forRun(new RunModelContext(run.companyId(), run.payerPrincipalId(),
                    choice, run.runtime(), Set.of()));
            if (access instanceof RunModelAccess.Wait wait && wait.reason() == RunModelAccess.WaitReason.BUDGET_SPENT
                    && wait.until() != null) {
                return wait.until();
            }
        }
        return events.rateLimitResets(run.id()).stream().max(Comparator.naturalOrder()).orElse(null);
    }

    /** Records the git result of each repository, matched by repository, and answers the workspace at the end. */
    private List<TurnEnded.RepositoryResult> finishRepositories(RunStore.RunRow run, RunStore.Outcome outcome) {
        List<RunRepositoryStore.RepositoryRow> rows = repositories.ofRun(run.id());
        Map<UUID, RunStore.GitResult> results = new HashMap<>();
        if (outcome.git() != null) {
            for (RunStore.GitResult result : outcome.git()) {
                if (rows.stream().noneMatch(row -> result.repositoryId().equals(row.repositoryId()))) {
                    log.info("Run {} reported a git result for repository {}, which is not in its workspace",
                            run.id(), result.repositoryId());
                }
                results.putIfAbsent(result.repositoryId(), result);
            }
        }
        List<TurnEnded.RepositoryResult> workspace = new ArrayList<>();
        for (RunRepositoryStore.RepositoryRow row : rows) {
            RunStore.GitResult result = row.repositoryId() != null ? results.get(row.repositoryId()) : null;
            // A read-only repository is never pushed (I9), whatever the host reported.
            Boolean pushed = result != null ? result.pushed() && !row.readOnly() : null;
            repositories.finish(run.id(), row.position(), pushed, result != null ? result.headSha() : null,
                    result != null ? result.commits() : null);
            workspace.add(new TurnEnded.RepositoryResult(row.repositoryId(), row.systemId(), row.bindingId(),
                    row.name(), run.branch() != null ? run.branch() : Branches.of(run.conversationKey()),
                    row.readOnly(), pushed, result != null ? result.headSha() : null,
                    result != null ? result.commits() : null));
        }
        return workspace;
    }
}
