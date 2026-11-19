package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.run.RunGitRevocations;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;

/**
 * {@link RunGitRevocations} (ADR-0116, decisions 1 and 2): finds the runs whose current assignment holds a token the
 * cause concerns, locks their conversations in the order of their ids and then each run (CONVENTIONS §10), and for each
 * run whose repositories no earlier revocation of its epoch named writes a {@code REVOKE_GIT} command, audits
 * {@code run.git_revoked}, and sends {@code run.git.revoke} after the commit when its host is connected and lists the
 * feature. A LOST run's command is completed with the run's end, as its other commands are, so that a resumption opens
 * it again and the {@code hello} that resumes the run sends it ({@link RunnerSessionHandler}).
 */
@Service
class GitRevocationService implements RunGitRevocations {

    private static final Logger log = LoggerFactory.getLogger(GitRevocationService.class);

    private final RunStore runs;

    private final RunLocks locks;

    private final RunRepositoryStore repositories;

    private final RunCommandStore commands;

    private final RunnerConnections connections;

    private final RunnerProperties runnerProperties;

    private final AuditLog auditLog;

    private final RunEventHub hub;

    private final TimeSource time;

    GitRevocationService(RunStore runs, RunLocks locks, RunRepositoryStore repositories, RunCommandStore commands,
            RunnerConnections connections, RunnerProperties runnerProperties, AuditLog auditLog, RunEventHub hub,
            TimeSource time) {
        this.runs = runs;
        this.locks = locks;
        this.repositories = repositories;
        this.commands = commands;
        this.connections = connections;
        this.runnerProperties = runnerProperties;
        this.auditLog = auditLog;
        this.hub = hub;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Revoked> ofBinding(UUID companyId, UUID bindingId, String reason, String why) {
        return revoke(repositories.targetsOfBinding(bindingId, lostSince()), null, reason, why,
                Map.of("bindingId", bindingId.toString()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> conversationsOfSystem(UUID companyId, UUID systemId) {
        Set<UUID> conversations = new HashSet<>();
        repositories.targetsOfSystem(systemId, lostSince())
            .forEach(target -> conversations.add(target.conversationId()));
        return conversations;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Revoked> ofSystem(UUID companyId, UUID systemId, String why) {
        return revoke(repositories.targetsOfSystem(systemId, lostSince()), null, SYSTEM_PAUSED, why,
                Map.of("systemId", systemId.toString()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Revoked> ofRun(UUID companyId, UUID runId, Collection<UUID> componentIds, String reason, String why) {
        return revoke(repositories.targetsOfRun(runId, lostSince()), Set.copyOf(componentIds), reason, why, Map.of());
    }

    private Instant lostSince() {
        return time.now().minus(runnerProperties.lostResumeWindow());
    }

    /**
     * Revokes the targets' repositories, run by run.
     *
     * @param only    the repositories the cause names; null for every target
     * @param details the cause's own audit details ({@code bindingId} or {@code systemId})
     */
    private List<Revoked> revoke(List<RunRepositoryStore.RevocationTarget> targets, @Nullable Set<UUID> only,
            String reason, String why, Map<String, Object> details) {
        Map<UUID, Set<UUID>> components = new TreeMap<>();
        for (RunRepositoryStore.RevocationTarget target : targets) {
            if (only == null || only.contains(target.componentId())) {
                components.computeIfAbsent(target.runId(), id -> new HashSet<>()).add(target.componentId());
            }
        }
        if (components.isEmpty()) {
            return List.of();
        }
        List<RunStore.RunRow> found = new ArrayList<>();
        components.keySet().forEach(runId -> runs.find(runId).ifPresent(found::add));
        locks.lockConversations(found);
        List<Revoked> revoked = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> entry : components.entrySet()) {
            RunStore.RunRow run = runs.lock(entry.getKey()).orElse(null);
            if (run == null) {
                continue;
            }
            Revoked one = revokeRun(run, entry.getValue(), reason, why, details);
            if (one != null) {
                revoked.add(one);
            }
        }
        return revoked;
    }

    /** One run under its row lock: the repositories of its current assignment that no revocation named yet. */
    private @Nullable Revoked revokeRun(RunStore.RunRow run, Set<UUID> wanted, String reason, String why,
            Map<String, Object> details) {
        Instant now = time.now();
        // Read again under the lock: the run may have ended, gone back to the queue or been assigned anew.
        Map<UUID, String> names = new LinkedHashMap<>();
        for (RunRepositoryStore.RevocationTarget target : repositories.targetsOfRun(run.id(), lostSince())) {
            if (wanted.contains(target.componentId())) {
                names.put(target.componentId(), target.name());
            }
        }
        names.keySet().removeAll(commands.revokedComponents(run.id(), run.epoch()));
        if (names.isEmpty()) {
            return null;
        }
        List<UUID> componentIds = List.copyOf(names.keySet());
        List<String> repositoryNames = List.copyOf(names.values());
        String message = message(repositoryNames, why);
        UUID hostId = run.runnerHostId();
        boolean lost = run.status() == RunStatus.LOST;
        Delivery delivery = lost || hostId == null || !connections.isConnected(hostId) ? Delivery.ON_RECONNECT
                : connections.supports(hostId, RunnerMessages.FEATURE_RUN_GIT_REVOKE) ? Delivery.SENT
                        : Delivery.UNSUPPORTED;
        UUID commandId = commands.insertRevocation(run.companyId(), run.id(), run.epoch(), componentIds, reason,
                message, currentUser(), now, lost ? run.finishedAt() : null);
        if (delivery == Delivery.SENT) {
            commands.markRevocationSent(commandId, now);
            RunnerMessages.RunGitRevoke frame = new RunnerMessages.RunGitRevoke(run.id(), run.epoch(), componentIds,
                    reason, message);
            AfterCommit.run(() -> connections.send(hostId, frame));
        }
        auditLog.record(AuditRecord.company(run.companyId(), AuditAction.RUN_GIT_REVOKED, CurrentActor.actor())
            .target(RunCompletion.TARGET, run.id())
            .detail("conversationId", run.conversationId().toString())
            .details(details)
            .detail("componentIds", componentIds.stream().map(UUID::toString).toList())
            .detail("reason", reason)
            .detail("epoch", run.epoch())
            .detail("sent", delivery == Delivery.SENT)
            .detail("delivery", delivery.name())
            .build());
        hub.changedAfterCommit(run.id());
        log.info("Run {} of conversation {}: git token of {} revoked ({}, {})", run.id(), run.conversationKey(),
                componentIds, reason, delivery);
        return new Revoked(run.id(), run.conversationId(), hostId, componentIds, repositoryNames, reason, message,
                delivery);
    }

    /** "Tinfirm revoked this run's access to acme/shop and acme/web: {why}.", cut to the protocol's length. */
    static String message(List<String> repositories, String why) {
        String names = repositories.size() == 1 ? repositories.getFirst()
                : String.join(", ", repositories.subList(0, repositories.size() - 1)) + " and "
                        + repositories.getLast();
        String cause = why.strip();
        if (cause.endsWith(".")) {
            cause = cause.substring(0, cause.length() - 1);
        }
        String message = "Tinfirm revoked this run's access to " + names + ": " + cause + ". Fetching from and pushing "
                + "to " + (repositories.size() == 1 ? "it" : "them") + " no longer works in this run.";
        return message.length() <= RunnerMessages.MAX_GIT_REVOKE_MESSAGE ? message
                : message.substring(0, RunnerMessages.MAX_GIT_REVOKE_MESSAGE - 1) + "…";
    }

    private static @Nullable UUID currentUser() {
        return CurrentActor.user().map(AuthenticatedUser::id).orElse(null);
    }
}
