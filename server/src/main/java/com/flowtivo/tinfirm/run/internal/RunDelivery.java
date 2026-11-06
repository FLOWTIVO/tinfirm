package com.flowtivo.tinfirm.run.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.NetworkHosts;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.run.RunPlacementStale;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * Delivers an assignment the dispatcher made (the package 3 contract, §5.7, the {@code run.assign} table), outside any
 * transaction (CONVENTIONS §10):
 * <ol>
 * <li>the turn's content ({@link TurnSource#content}), whose git tokens are revealed outside any transaction; a turn
 * that cannot go on after all goes back to the queue with its reason, or ends; one that cannot go on right now for a
 * passing failure goes back with {@code OTHER} after the back-off; one whose host may no longer carry what it would
 * reveal ({@link RunPlacementStale}: a personal host and an account that is not the payer's, M2 of the security
 * review of 2026-10-27) goes back with {@code OTHER} at once, and is placed again under the rule. A failure to
 * prepare it that may pass (the database, a lock, a timeout) takes the run back to the queue after a back-off; any
 * other fails the run ({@code INFRA}), which its conversation shows, instead of failing again on every pass;</li>
 * <li>the model access: for a budget, a one-run token of the model relay ({@link ModelRelay#open}), bound to the run,
 * its epoch and its host, in the format of the runtime (Codex: {@code openai}; the others: {@code anthropic}); for a
 * subscription, its label;</li>
 * <li>{@code run.assign} (protocol v2): {@code workItem.id} and {@code job.id} the conversation's id (I12), the branch
 * {@code agent/{key}} in every repository, each repository's directory, {@code resumeSessionId} of the conversation's
 * previous run with a session, the agent's name with the settings' model and effort, the agent's network hosts with
 * the conversation's granted ones, the limits; no other secret than the workspace's git tokens and the relay's token
 * (I14);</li>
 * <li>in one short transaction, when the run is still this assignment: its workspace rows, the accept timeout counted
 * from now, {@code run.assigned} audited; then the frame is sent to the host. A frame that cannot be sent waits for
 * the accept timeout, which takes the run back. An assignment that went stale meanwhile (a {@code hello} that did not
 * list it, or the accept timeout, sent the run back to the queue, and it may run under a later epoch already; or it
 * ended) sends nothing, and revokes the token it opened, that epoch's only.</li>
 * </ol>
 * Taking the assignment back or ending the run writes its conversation too, so it locks the conversation's row before
 * the run's (CONVENTIONS §10); recording the assignment writes the run's rows only, and locks the run alone.
 */
@Component
class RunDelivery {

    private static final Logger log = LoggerFactory.getLogger(RunDelivery.class);

    private final RunStore runs;

    private final RunLocks locks;

    private final RunRepositoryStore repositories;

    private final RunSkillStore skills;

    private final RunPorts ports;

    private final ModelRelay relay;

    private final RunnerConnections connections;

    private final RunCompletion completion;

    private final SystemTransactions transactions;

    private final RunEventHub hub;

    private final ApplicationEventPublisher publisher;

    private final AuditLog auditLog;

    private final RunProperties properties;

    private final TimeSource time;

    /** Keeping session bundles, under {@code ee/} (ADR-0100): without it, no run gets one. */
    private final ObjectProvider<SessionBundles> bundles;

    RunDelivery(RunStore runs, RunLocks locks, RunRepositoryStore repositories, RunPorts ports, ModelRelay relay,
            RunnerConnections connections, RunCompletion completion, SystemTransactions transactions, RunEventHub hub,
            ApplicationEventPublisher publisher, AuditLog auditLog, RunProperties properties, TimeSource time,
            RunSkillStore skills, ObjectProvider<SessionBundles> bundles) {
        this.skills = skills;
        this.bundles = bundles;
        this.runs = runs;
        this.locks = locks;
        this.repositories = repositories;
        this.ports = ports;
        this.relay = relay;
        this.connections = connections;
        this.completion = completion;
        this.transactions = transactions;
        this.hub = hub;
        this.publisher = publisher;
        this.auditLog = auditLog;
        this.properties = properties;
        this.time = time;
    }

    /** The failure class of a run whose content could not be prepared ({@code RunFailureClass}). */
    static final String PREPARATION_FAILED = "INFRA";

    /**
     * The classes of SQL states that may pass: a connection exception, a transaction rolled back (a serialization
     * failure, a deadlock), insufficient resources, an operator's intervention (a cancelled statement).
     */
    private static final Set<String> TRANSIENT_SQL_STATES = Set.of("08", "40", "53", "57");

    /**
     * Whether a failure to prepare a run's content may pass when tried again: the database or its connections, a
     * lock, a timeout, a read. Anything else (a bug, data the code cannot handle) fails the same way every time, so
     * the run fails instead of going back to the queue for ever.
     */
    static boolean transientFailure(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException || cause instanceof IOException
                    || cause instanceof UncheckedIOException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().length() >= 2
                    && TRANSIENT_SQL_STATES.contains(sql.getSQLState().substring(0, 2))) {
                return true;
            }
        }
        return false;
    }

    /** The relay's format for the runtime: Codex speaks OpenAI's API, the others Anthropic's (LiteLLM translates). */
    static RunnerMessages.ModelAccessFormat format(RuntimeKind runtime) {
        return runtime == RuntimeKind.CODEX ? RunnerMessages.ModelAccessFormat.OPENAI
                : RunnerMessages.ModelAccessFormat.ANTHROPIC;
    }

    void deliver(RunDispatcher.Assignment assignment) {
        NoTransaction.require("Delivering a run's assignment");
        RunStore.RunRow run = assignment.run();
        UUID companyId = run.companyId();
        TurnSource source = ports.source().orElse(null);
        if (source == null) {
            takeBack(assignment, QueueReason.other("Nothing on this server supplies the turns of conversations."),
                    true);
            return;
        }
        TurnSource.Placement placement = new TurnSource.Placement(assignment.host().id(), assignment.host().scope(),
                assignment.epoch(), assignment.previous());
        TurnSource.TurnContent content;
        try {
            content = SystemContext.callInCompany(companyId, () -> source.content(assignment.turn(), placement,
                    assignment.settings()));
        }
        catch (RunPlacementStale stale) {
            // M2, D6: the host may no longer carry what the turn would reveal; nothing was sent. Back to the queue, as
            // a stale assignment goes, without a back-off: the next pass plans with the workspace the reveal read, and
            // places the run under the personal-host rule.
            log.info("Run {} no longer fits runner host {}: {}", run.id(), assignment.host().id(), stale.getMessage());
            takeBack(assignment, QueueReason.other(stale.getMessage()), false);
            return;
        }
        catch (RuntimeException ex) {
            if (transientFailure(ex)) {
                log.warn("Preparing the assignment of run {} failed; it goes back to the queue", run.id(), ex);
                takeBack(assignment, QueueReason.other("Preparing the run failed; it is tried again."), true);
            }
            else {
                // The same content fails the same way again: the run fails, and its conversation says so.
                log.error("Preparing the assignment of run {} failed, as it would again; the run fails", run.id(),
                        ex);
                end(assignment, RunStore.Outcome.of(RunStatus.FAILED, PREPARATION_FAILED, "Tinfirm could not "
                        + "prepare the turn: " + ex.getClass().getSimpleName() + " on the server, whose log has the "
                        + "details."));
            }
            return;
        }
        TurnSource.TurnContent.Content turn;
        switch (content) {
            case TurnSource.TurnContent.Content found -> turn = found;
            case TurnSource.TurnContent.NotReady notReady -> {
                notReady(assignment, notReady.outcome());
                return;
            }
            case TurnSource.TurnContent.Later later -> {
                // A passing failure: back to the queue after the back-off, so that it does not spin.
                takeBack(assignment, QueueReason.other(later.message()), true);
                return;
            }
        }

        RunnerMessages.ModelAccess modelAccess = null;
        if (assignment.access() instanceof ModelAccessChoice.Budget) {
            ModelRelay.RelayedRun relayed = new ModelRelay.RelayedRun(companyId, run.id(), assignment.epoch(),
                    assignment.host().id(), run.payerPrincipalId(), run.conversationId(), run.agentId());
            try {
                modelAccess = transactions.inCompany(companyId, () -> relay.open(relayed, format(run.runtime())));
            }
            catch (RuntimeException ex) {
                log.warn("Opening the model relay for run {} failed; it goes back to the queue", run.id(), ex);
                takeBack(assignment, QueueReason.other("Preparing the run's model access failed; it is tried "
                        + "again."), true);
                return;
            }
        }
        List<String> dirs = dirs(turn.workspaces());
        // The conversation's session bundle (ADR-0097), for a company host that takes bundles.
        String resumeSessionId = assignment.previous() != null ? assignment.previous().sessionId() : null;
        SessionBundles kept = bundles.getIfAvailable();
        RunnerMessages.SessionBundle sessionBundle = kept == null ? null
                : transactions.inCompany(companyId, () -> kept.forAssignment(run.conversationId(), run.agentId(),
                        run.runtime().name(), assignment.host().scope(),
                        connections.supports(assignment.host().id(), RunnerMessages.FEATURE_SESSION_BUNDLE),
                        resumeSessionId));
        RunnerMessages.RunAssign message = build(assignment, turn, dirs, modelAccess, sessionBundle);
        boolean opened = modelAccess != null;
        if (!Boolean.TRUE.equals(transactions.inCompany(companyId, () -> record(assignment, turn, dirs, opened)))) {
            log.info("Run {} is no longer assigned with epoch {}; its prepared assignment is not sent", run.id(),
                    assignment.epoch());
            return;
        }
        if (connections.protocolVersion(assignment.host().id()) != assignment.protocolVersion()) {
            // The host reconnected with another protocol version meanwhile: the accept timeout requeues it.
            log.info("Run {} was built for runner protocol {}, which runner host {} no longer speaks", run.id(),
                    assignment.protocolVersion(), assignment.host().id());
        }
        else if (!connections.send(assignment.host().id(), message)) {
            log.info("Run {} could not be sent to runner host {}; the accept timeout will requeue it", run.id(),
                    assignment.host().id());
        }
    }

    /** {@code run.assign} of protocol v2, without a session bundle. */
    RunnerMessages.RunAssign build(RunDispatcher.Assignment assignment, TurnSource.TurnContent.Content turn,
            List<String> dirs, RunnerMessages.@Nullable ModelAccess modelAccess) {
        return build(assignment, turn, dirs, modelAccess, null);
    }

    /** {@code run.assign} of protocol v2, with the conversation's session bundle (ADR-0097) when there is one. */
    RunnerMessages.RunAssign build(RunDispatcher.Assignment assignment, TurnSource.TurnContent.Content turn,
            List<String> dirs, RunnerMessages.@Nullable ModelAccess modelAccess,
            RunnerMessages.@Nullable SessionBundle sessionBundle) {
        RunStore.RunRow run = assignment.run();
        String branch = run.branch() != null ? run.branch() : Branches.of(run.conversationKey());
        List<RunnerMessages.Workspace> workspaces = new ArrayList<>();
        for (int position = 0; position < turn.workspaces().size(); position++) {
            TurnSource.Workspace workspace = turn.workspaces().get(position);
            workspaces.add(new RunnerMessages.Workspace(workspace.repositoryId(), dirs.get(position),
                    workspace.name(), workspace.url(), workspace.defaultBranch(), branch, workspace.git(),
                    workspace.authorName(), workspace.authorEmail(), workspace.readOnly()));
        }
        String resumeSessionId = assignment.previous() != null ? assignment.previous().sessionId() : null;
        EffectiveSettings settings = assignment.settings();
        return RunnerMessages.RunAssign.v2(run.id(), assignment.epoch(), assignment.credentialLabel(), modelAccess,
                new RunnerMessages.AssignedAgent(run.agentId(), settings.agent().agent().name(), run.runtime(),
                        settings.model(), settings.effort().orNullForDefault()),
                new RunnerMessages.AssignedWorkItem(run.conversationId(), run.conversationKey(), turn.title()),
                new RunnerMessages.AssignedJob(run.conversationId()), turn.prompt(), resumeSessionId, workspaces,
                turn.requestableRepositories(),
                new RunnerMessages.Limits(maxWallClockSeconds(settings.agent().agent(), properties.maxWallClock()),
                        properties.maxTurns()),
                resumeSessionId != null ? turn.resumePrompt() : null,
                turn.attachments().isEmpty() ? null : turn.attachments(), turn.tools(),
                allowedHosts(settings.hosts(), turn.grantedHosts()), turn.platformTools(), skills(settings),
                sessionBundle);
    }

    /**
     * {@code run.assign.skills} (ADR-0064): the skills of the agent the turn gets, each at the version it pins, by
     * name. The dispatcher sent a run with skills only to a host that lists {@code run.skills}.
     */
    static List<RunnerMessages.AssignedSkill> skills(EffectiveSettings settings) {
        return settings.skills()
            .stream()
            .map(skill -> new RunnerMessages.AssignedSkill(skill.skillId(), skill.version(), skill.name(),
                    skill.sha256(), skill.sizeBytes()))
            .toList();
    }

    /**
     * {@code run.assign.limits.maxWallClockSeconds}: the agent's time limit of a turn, or the deployment's
     * ({@code TINFIRM_RUN_MAX_WALL_CLOCK}) when it sets none.
     */
    static long maxWallClockSeconds(AgentInfo agent, Duration deploymentLimit) {
        Integer minutes = agent.turnTimeLimitMinutes();
        return minutes != null ? Duration.ofMinutes(minutes).toSeconds() : deploymentLimit.toSeconds();
    }

    /**
     * {@code run.assign.allowedHosts} (contract §5.7): the agent's hosts (as the person accepted it) and the
     * conversation's granted ones, sorted and distinct, without a wildcard over a public suffix or shared hosting
     * ({@link NetworkHosts#wildcardOverSuffix}), at most {@value TurnSource.TurnContent.Content#MAX_ALLOWED_HOSTS}.
     */
    static List<String> allowedHosts(List<String> agentHosts, List<String> grantedHosts) {
        List<String> hosts = Stream.concat(agentHosts.stream(), grantedHosts.stream())
            .filter(host -> !NetworkHosts.wildcardOverSuffix(host))
            .distinct()
            .sorted()
            .toList();
        if (hosts.size() > TurnSource.TurnContent.Content.MAX_ALLOWED_HOSTS) {
            log.warn("A run may reach {} network hosts; run.assign carries the first {}", hosts.size(),
                    TurnSource.TurnContent.Content.MAX_ALLOWED_HOSTS);
            return hosts.subList(0, TurnSource.TurnContent.Content.MAX_ALLOWED_HOSTS);
        }
        return hosts;
    }

    /** Each repository's directory under {@code /workspace}, unique within the run. */
    static List<String> dirs(List<TurnSource.Workspace> workspaces) {
        List<String> dirs = new ArrayList<>();
        for (TurnSource.Workspace workspace : workspaces) {
            dirs.add(Branches.uniqueDir(workspace.name(), dirs));
        }
        return dirs;
    }

    /**
     * Records the assignment when the run is still it: its workspace, the accept timeout from now, the audit. A stale
     * assignment revokes the model token it opened, and only that one: its epoch's. The run may be running under a
     * later assignment with that one's token (the relay's {@code open} of a stale assignment never revokes a later
     * epoch's); a run lost by lease expiry keeps its token, since its host may bring it back; and a run's end revoked
     * the tokens it had.
     *
     * @param opened whether this delivery opened a token of the model relay
     */
    private boolean record(RunDispatcher.Assignment assignment, TurnSource.TurnContent.Content turn,
            List<String> dirs, boolean opened) {
        RunStore.RunRow run = runs.lock(assignment.run().id()).orElse(null);
        if (run == null) {
            return false;
        }
        if (!current(run, assignment)) {
            if (opened) {
                relay.revoke(run.companyId(), run.id(), assignment.epoch());
            }
            return false;
        }
        repositories.deleteOfRun(run.id());
        for (int position = 0; position < turn.workspaces().size(); position++) {
            repositories.insert(run.companyId(), run.id(), position, turn.workspaces().get(position),
                    dirs.get(position));
        }
        // What its host may download: the skills of this assignment (the skills contract, §7.2).
        skills.replace(run.companyId(), run.id(), skills(assignment.settings()));
        runs.markSent(run.id(), assignment.epoch(), assignment.host().id(), time.now());
        runs.recordModel(run.id(), assignment.settings().model());
        auditLog.record(AuditRecord.company(run.companyId(), AuditAction.RUN_ASSIGNED, CurrentActor.actor())
            .target(RunCompletion.TARGET, run.id())
            .detail("conversationId", run.conversationId().toString())
            .detail("runnerHostId", assignment.host().id().toString())
            .detail("hostKind", assignment.host().scope().name())
            .detail("epoch", assignment.epoch())
            .detail("payerPrincipalId", run.payerPrincipalId().toString())
            .detail("modelAccess", TurnStarter.modelAccess(assignment.access()))
            .build());
        hub.changedAfterCommit(run.id());
        return true;
    }

    private static boolean current(RunStore.RunRow run, RunDispatcher.Assignment assignment) {
        return run.status() == RunStatus.QUEUED && run.epoch() == assignment.epoch() && run.dispatchedAt() != null
                && assignment.host().id().equals(run.runnerHostId());
    }

    /** The turn's source said the turn cannot go on after all. */
    private void notReady(RunDispatcher.Assignment assignment, TurnSource.TurnPlan outcome) {
        switch (outcome) {
            case TurnSource.TurnPlan.AccountRequired waits -> takeBack(assignment,
                    QueueReason.accountRequired(waits.message()), false);
            case TurnSource.TurnPlan.TriggerPaused paused -> takeBack(assignment,
                    QueueReason.triggerPaused(paused.message()), false);
            case TurnSource.TurnPlan.AgentNotUsable waits -> takeBack(assignment, QueueReason.other(waits.message()),
                    false);
            case TurnSource.TurnPlan.Cancel cancel -> end(assignment, RunStore.Outcome.of(RunStatus.CANCELLED, null,
                    cancel.message()));
            case TurnSource.TurnPlan.Ready ready -> throw new IllegalStateException("A ready turn has content");
        }
    }

    /**
     * Takes the assignment back, when the run is still it: back to the queue with a new epoch and the reason, after a
     * short back-off for a failure, so that a broken turn does not spin.
     */
    private void takeBack(RunDispatcher.Assignment assignment, QueueReason reason, boolean backOff) {
        transactions.runInCompany(assignment.run().companyId(), () -> locks.lock(assignment.run().id())
            .filter(run -> current(run, assignment))
            .ifPresent(run -> {
                Instant now = time.now();
                runs.requeue(run.id(), backOff ? now.plus(properties.rejectBackoff()) : null, reason, now);
                publisher.publishEvent(TurnStarter.queueReasonChanged(run, reason));
                hub.changedAfterCommit(run.id());
            }));
    }

    private void end(RunDispatcher.Assignment assignment, RunStore.Outcome outcome) {
        transactions.runInCompany(assignment.run().companyId(), () -> locks.lock(assignment.run().id())
            .filter(run -> current(run, assignment))
            .ifPresent(run -> completion.finish(run, outcome)));
    }
}
