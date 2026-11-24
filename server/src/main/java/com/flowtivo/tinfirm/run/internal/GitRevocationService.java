package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunGitRevocations;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;
import com.flowtivo.tinfirm.shared.tx.AfterTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * {@link RunGitRevocations} (ADR-0116, decisions 1 and 2): finds the runs whose current assignment holds a token the
 * cause concerns, locks their conversations in the order of their ids and then each run (CONVENTIONS §10), and for each
 * run whose repositories no earlier revocation of its epoch named writes a {@code REVOKE_GIT} command, audits
 * {@code run.git_revoked}, and sends {@code run.git.revoke} after the commit when its host is connected over protocol 2
 * and lists the feature, a LOST run's host too (it may still run it, and resume it with a heartbeat). The command
 * counts as sent ({@code delivery SENT}, {@code last_sent_at}) once the frame was written to the host's connection. A
 * LOST run's command is completed with the run's end, as its other commands are, so that a resumption opens it again.
 * <p>
 * For the {@link RunnerSessionHandler}: the pending revocations a {@code hello} or a resumption sends again
 * ({@link #pending}), and those that go before a {@code run.cancel} of a run its host reports ({@link #beforeCancels}),
 * since a cancelled run pushes its work.
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

    private final RunPorts ports;

    /** The transaction that records a frame written after the change's commit: a new one, in the run's company. */
    private final TransactionTemplate separate;

    private final SystemTransactions transactions;

    private final TenantScope tenants;

    private final ApplicationEventPublisher publisher;

    GitRevocationService(RunStore runs, RunLocks locks, RunRepositoryStore repositories, RunCommandStore commands,
            RunnerConnections connections, RunnerProperties runnerProperties, AuditLog auditLog, RunEventHub hub,
            TimeSource time, RunPorts ports, PlatformTransactionManager transactionManager,
            SystemTransactions transactions, ApplicationEventPublisher publisher, TenantScope tenants) {
        this.tenants = tenants;
        this.publisher = publisher;
        this.transactions = transactions;
        this.ports = ports;
        this.separate = new TransactionTemplate(transactionManager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    public Set<UUID> conversationsOfBinding(UUID companyId, UUID bindingId) {
        Set<UUID> conversations = new HashSet<>();
        repositories.targetsOfBinding(bindingId, lostSince())
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
        // A LOST run's host too, when it is connected: it may still run the run, and resume it with a heartbeat.
        Delivery delivery = hostId == null || !connections.isConnected(hostId) ? Delivery.ON_RECONNECT
                : takesRevocations(hostId) ? Delivery.SENT : Delivery.UNSUPPORTED;
        if (hostId != null) {
            // Not while a hello's answer of the host ends: it waits for this commit, or this for its end (decision 1).
            commands.lockRevocationsOf(hostId);
        }
        // SENT is what goes after the commit; the row says so once the frame was written to the host's connection.
        UUID commandId = commands.insertRevocation(run.companyId(), run.id(), run.epoch(), componentIds, reason,
                message, currentUser(), now, lost ? run.finishedAt() : null,
                delivery == Delivery.UNSUPPORTED ? Delivery.UNSUPPORTED.name() : Delivery.ON_RECONNECT.name());
        if (delivery == Delivery.SENT) {
            RunnerMessages.RunGitRevoke frame = new RunnerMessages.RunGitRevoke(run.id(), run.epoch(), componentIds,
                    reason, message);
            UUID companyId = run.companyId();
            AfterCommit.run(() -> {
                if (connections.send(hostId, frame)) {
                    // Once the transaction has released its connection, which its afterCommit still holds. A mark
                    // that fails is only logged, as it was: the row stays ON_RECONNECT and goes again at the hello.
                    AfterTransaction.run(() -> {
                        try {
                            SystemContext.runInCompany(companyId, () -> separate
                                .executeWithoutResult(status -> commands.markRevocationSent(commandId, time.now())));
                        }
                        catch (RuntimeException ex) {
                            log.warn("Run {}: the revocation sent to runner host {} could not be marked sent; it goes "
                                    + "again at its hello", run.id(), hostId, ex);
                        }
                    });
                }
                else {
                    log.info("Run {}: the revocation could not be sent to runner host {}; it goes at its hello",
                            run.id(), hostId);
                }
            });
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
        if (delivery == Delivery.UNSUPPORTED) {
            // Whatever the cause, the suspension's and a leaving member's included: the conversation says so.
            publisher.publishEvent(new RunEvents.GitTokensKept(run.companyId(), run.id(), run.conversationId(),
                    repositoryNames));
        }
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
        if (message.length() <= RunnerMessages.MAX_GIT_REVOKE_MESSAGE) {
            return message;
        }
        // Cut on a code point: half a surrogate pair is no text, and its frame could not be encoded.
        int end = RunnerMessages.MAX_GIT_REVOKE_MESSAGE - 1;
        if (Character.isHighSurrogate(message.charAt(end - 1))) {
            end--;
        }
        return message.substring(0, end) + "…";
    }

    /** Whether the host's connection takes {@code run.git.revoke}: protocol 2, and the feature in its hello. */
    private boolean takesRevocations(UUID hostId) {
        return connections.protocolVersion(hostId) == RunnerMessages.PROTOCOL_V2
                && connections.supports(hostId, RunnerMessages.FEATURE_RUN_GIT_REVOKE);
    }

    /**
     * {@code run.git.revoke} for the pending revocations of the runs' current assignments, for a {@code hello} that
     * keeps or resumes them, or a heartbeat that resumes one, in its transaction: to a host that takes them, each
     * counted as sent once the answer was written ({@link #written}); a host that does not gets none, and the
     * revocations it was never sent are {@code UNSUPPORTED}.
     */
    List<RunnerMessages.Outbound> pending(ConnectedHost host, Collection<UUID> runIds, Instant now) {
        boolean takes = takesRevocations(host.runnerHostId());
        List<RunnerMessages.Outbound> frames = new ArrayList<>();
        for (RunCommandStore.GitRevocation revocation : commands.pendingRevocations(runIds)) {
            RunStore.RunRow run = runs.find(revocation.runId()).orElse(null);
            if (run == null || run.epoch() != revocation.epoch()) {
                continue;
            }
            if (!takes) {
                commands.markRevocationUnsupported(revocation.id());
                continue;
            }
            // Counted as sent once the answer was written ({@link #written}).
            frames.add(new RunnerMessages.RunGitRevoke(run.id(), run.epoch(), revocation.componentIds(),
                    revocation.reason(), revocation.message()));
        }
        return frames;
    }

    /**
     * The {@code run.git.revoke} frames of an answer that were written to the host's connection: their revocations
     * are {@code SENT} from now, in a transaction of their own. Those of an answer that was not written stay
     * {@code ON_RECONNECT}, which the run's view shows as pending, and go again at the host's next {@code hello}.
     */
    void written(List<RunnerMessages.RunGitRevoke> frames) {
        Instant now = time.now();
        separate.executeWithoutResult(status -> frames.forEach(frame -> commands.markRevocationWritten(frame.runId(),
                frame.epoch(), frame.componentIds(), now)));
    }

    /**
     * Inserts before each {@code run.cancel} of the answer the revocations of that run's assignment (the cancel's
     * epoch) that its host must hear first (ADR-0116, decision 1): a cancelled run pushes its work. Those are every
     * revocation recorded for that assignment, pending or done, and, for a run the caller holds locked (its
     * conversation first), the repositories whose token the server would not let it use now
     * ({@link TurnSource#gitAccessGone}), which are recorded, audited and sent here: a revocation whose cause came
     * after the server stopped tracking the run (it ended, or was lost longer ago than the resume window). A
     * repository a frame of the answer names already for that assignment is not named again. A host that does not
     * take revocations gets the answer as it is.
     *
     * @param locked the runs the caller holds locked, for which a revocation may be recorded
     */
    List<RunnerMessages.Outbound> beforeCancels(ConnectedHost host, List<RunnerMessages.Outbound> answer,
            Set<UUID> locked) {
        if (answer.stream().noneMatch(RunnerMessages.RunCancel.class::isInstance)) {
            return answer;
        }
        if (!takesRevocations(host.runnerHostId())) {
            // The host that reports these runs will never be told: their revocations say so, not "pending".
            for (RunnerMessages.Outbound frame : answer) {
                if (frame instanceof RunnerMessages.RunCancel cancel) {
                    commands.markRevocationsUnsupported(cancel.runId(), cancel.epoch());
                }
            }
            return answer;
        }
        Instant now = time.now();
        Map<String, Set<UUID>> named = new HashMap<>();
        for (RunnerMessages.Outbound frame : answer) {
            if (frame instanceof RunnerMessages.RunGitRevoke revoke) {
                named.computeIfAbsent(revoke.runId() + "/" + revoke.epoch(), key -> new HashSet<>())
                    .addAll(revoke.componentIds());
            }
        }
        List<RunnerMessages.Outbound> frames = new ArrayList<>();
        for (RunnerMessages.Outbound frame : answer) {
            if (frame instanceof RunnerMessages.RunCancel cancel) {
                Set<UUID> sent = named.computeIfAbsent(cancel.runId() + "/" + cancel.epoch(), key -> new HashSet<>());
                frames.addAll(beforeCancel(cancel.runId(), cancel.epoch(), locked.contains(cancel.runId()), sent,
                        now));
            }
            frames.add(frame);
        }
        return frames;
    }

    private List<RunnerMessages.Outbound> beforeCancel(UUID runId, long epoch, boolean locked, Set<UUID> sent,
            Instant now) {
        // The run is of the host's company (the tenant context); the host reports it with that epoch.
        RunStore.RunRow run = runs.find(runId).orElse(null);
        if (run == null) {
            return List.of();
        }
        List<RunnerMessages.Outbound> frames = new ArrayList<>();
        Set<UUID> recorded = new HashSet<>();
        for (RunCommandStore.GitRevocation revocation : commands.revocationsOf(runId, epoch)) {
            recorded.addAll(revocation.componentIds());
            if (!sent.containsAll(revocation.componentIds())) {
                frames.add(new RunnerMessages.RunGitRevoke(runId, epoch, revocation.componentIds(),
                        revocation.reason(), revocation.message()));
                sent.addAll(revocation.componentIds());
            }
        }
        TurnSource source = ports.source().orElse(null);
        // A run the revocations still track (its current assignment, active or lost within the resume window) has
        // every revocation of its causes recorded: the check is for the others only.
        boolean tracked = run.epoch() == epoch && (run.status().active() || run.status() == RunStatus.LOST
                && run.finishedAt() != null && run.finishedAt().isAfter(lostSince()));
        if (!locked || tracked || source == null) {
            return frames;
        }
        // What that assignment cloned, kept by epoch whatever later assignments did (V087).
        Map<UUID, RunRepositoryStore.AssignedRepository> cloned = new LinkedHashMap<>();
        for (RunRepositoryStore.AssignedRepository row : repositories.ofAssignment(runId, epoch)) {
            if (!recorded.contains(row.componentId()) && !sent.contains(row.componentId())) {
                cloned.put(row.componentId(), row);
            }
        }
        if (cloned.isEmpty()) {
            return frames;
        }
        List<TurnSource.GitAccessGone> gone = source.gitAccessGone(run.companyId(), cloned.values().stream()
            .map(row -> new TurnSource.ClonedRepository(row.componentId(), row.systemId(), row.bindingId(),
                    row.bindingVersion()))
            .toList());
        // One revocation per cause, as the listeners write them.
        Map<List<Object>, List<TurnSource.GitAccessGone>> causes = new LinkedHashMap<>();
        gone.forEach(one -> causes.computeIfAbsent(java.util.Arrays.asList(one.reason(), one.why(), one.bindingId(),
                one.systemId()), key -> new ArrayList<>()).add(one));
        for (List<TurnSource.GitAccessGone> cause : causes.values()) {
            TurnSource.GitAccessGone first = cause.getFirst();
            List<UUID> componentIds = cause.stream().map(TurnSource.GitAccessGone::componentId).toList();
            List<String> names = componentIds.stream().map(id -> cloned.get(id).name()).toList();
            String message = message(names, first.why());
            commands.insertRevocation(run.companyId(), runId, epoch, componentIds, first.reason(), message,
                    currentUser(), now, now, Delivery.ON_RECONNECT.name());
            auditLog.record(AuditRecord.company(run.companyId(), AuditAction.RUN_GIT_REVOKED, CurrentActor.actor())
                .target(RunCompletion.TARGET, runId)
                .detail("conversationId", run.conversationId().toString())
                .details(first.bindingId() != null ? Map.of("bindingId", first.bindingId().toString())
                        : first.systemId() != null ? Map.of("systemId", first.systemId().toString()) : Map.of())
                .detail("componentIds", componentIds.stream().map(UUID::toString).toList())
                .detail("reason", first.reason())
                .detail("epoch", epoch)
                .detail("sent", true)
                .detail("delivery", Delivery.SENT.name())
                .build());
            frames.add(new RunnerMessages.RunGitRevoke(runId, epoch, componentIds, first.reason(), message));
            sent.addAll(componentIds);
            log.info("Run {} of conversation {}: git token of {} revoked before its cancel ({})", runId,
                    run.conversationKey(), componentIds, first.reason());
        }
        if (!causes.isEmpty()) {
            hub.changedAfterCommit(runId);
        }
        return frames;
    }

    /**
     * The host before which the run of a company it is no longer in is cancelled (a machine whose owner left it,
     * ADR-0116): the revocations of that run's assignment of {@code epoch}, as {@link #beforeCancels} gives them, in
     * the run's company's transaction, with the run locked (its conversation first). Only when that assignment went to
     * this machine ({@code run_assignment_repository.personal_host_id}): a host learns nothing of a run it does not
     * hold, and an assignment with no repository of a binding's token has nothing to revoke. A host that does not take
     * revocations gets none, and those of that assignment it was never sent are {@code UNSUPPORTED}.
     */
    List<RunnerMessages.Outbound> beforeForeignCancel(UUID personalHostId, UUID runId, long epoch,
            boolean takesRevocations) {
        RunStore.RunRow found = runs.find(runId).orElse(null);
        if (found == null || repositories.ofAssignment(runId, epoch)
            .stream()
            .noneMatch(row -> personalHostId.equals(row.personalHostId()))) {
            return List.of();
        }
        locks.lockConversations(List.of(found));
        if (runs.lock(runId).isEmpty()) {
            return List.of();
        }
        if (!takesRevocations) {
            commands.markRevocationsUnsupported(runId, epoch);
            return List.of();
        }
        return beforeCancel(runId, epoch, true, new HashSet<>(), time.now());
    }

    /**
     * The end of the answer to a {@code hello} that lists {@code run.git.revoke} (ADR-0116, decision 1): it holds back
     * the commit of every revocation of a run on the host's presences ({@link RunCommandStore#holdRevocationsOf}, which
     * also waits for those that took their lock to commit), reads in each company, under its tenant context, the
     * revocations of the runs the hello listed there, for the epoch it listed and of an assignment that went to that
     * presence, that were never written to a connection, writes them, writes {@code welcome.done}, and only then lets
     * them commit. So every revocation of those runs that committed before {@code welcome.done} was written went
     * before it: one that committed before the hold is read here, unless it was written already, and one that did not
     * cannot commit until {@code welcome.done} was written. The after-commit send of a revocation read here may write
     * it again: a host applies a frame once. The frames written count as sent afterwards ({@link #written}).
     */
    void endHelloAnswer(Map<ConnectedHost, List<RunnerMessages.ActiveRun>> listed,
            Predicate<RunnerMessages.Outbound> write) {
        Map<UUID, List<RunnerMessages.RunGitRevoke>> written = new LinkedHashMap<>();
        transactions.unscoped(() -> {
            commands.holdRevocationsOf(listed.keySet().stream().map(ConnectedHost::runnerHostId).toList());
            // Each company's revocations are read in this transaction, under that company's tenant context in turn:
            // the hold stays, and the thread never asks for a second connection while it holds this one.
            List<Map.Entry<ConnectedHost, List<RunnerMessages.ActiveRun>>> presences = listed.entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .toList();
            Map<ConnectedHost, List<RunnerMessages.RunGitRevoke>> unwritten = new LinkedHashMap<>();
            tenants.eachIn(presences, entry -> TenantContext.forCompany(entry.getKey().company()),
                    entry -> unwritten.put(entry.getKey(), unwritten(entry.getKey(), entry.getValue())));
            unwritten.forEach((placed, frames) -> {
                for (RunnerMessages.RunGitRevoke frame : frames) {
                    if (write.test(frame)) {
                        written.computeIfAbsent(placed.company(), id -> new ArrayList<>()).add(frame);
                    }
                }
            });
            write.test(new RunnerMessages.WelcomeDone());
            return null;
        });
        written.forEach((company, frames) -> SystemContext.runInCompany(company, () -> written(frames)));
    }

    /** The revocations of the listed runs' assignments that went to the presence and were never written. */
    private List<RunnerMessages.RunGitRevoke> unwritten(ConnectedHost placed, List<RunnerMessages.ActiveRun> listed) {
        List<RunnerMessages.RunGitRevoke> frames = new ArrayList<>();
        for (RunnerMessages.ActiveRun run : listed) {
            List<RunCommandStore.GitRevocation> found = commands.unwrittenRevocations(run.runId(), run.epoch());
            if (found.isEmpty() || repositories.ofAssignment(run.runId(), run.epoch())
                .stream()
                .noneMatch(row -> placed.runnerHostId().equals(row.runnerHostId()))) {
                continue;
            }
            found.forEach(revocation -> frames.add(new RunnerMessages.RunGitRevoke(run.runId(), run.epoch(),
                    revocation.componentIds(), revocation.reason(), revocation.message())));
        }
        return frames;
    }

    /**
     * {@code run.git.revoked} (ADR-0116): the host applied the revocations of the run's assignment of its epoch that
     * name these repositories. Taken only from the host the run is assigned to, for the run's epoch, and ignored
     * otherwise, without an {@code error}. A revocation confirmed for the first time is {@code CONFIRMED} with the
     * time and audited {@code run.git_revoke_confirmed}; a confirmation that comes again changes nothing.
     */
    List<RunnerMessages.Outbound> confirmed(ConnectedHost host, RunnerMessages.RunGitRevoked frame) {
        RunStore.RunRow run = runs.find(frame.runId()).orElse(null);
        if (run == null || run.epoch() != frame.epoch() || !host.runnerHostId().equals(run.runnerHostId())) {
            return List.of();
        }
        List<RunCommandStore.Confirmed> confirmed = commands.confirmRevocations(run.id(), run.epoch(),
                frame.componentIds(), time.now());
        for (RunCommandStore.Confirmed one : confirmed) {
            auditLog.record(AuditRecord.company(run.companyId(), AuditAction.RUN_GIT_REVOKE_CONFIRMED,
                    CurrentActor.actor())
                .target(RunCompletion.TARGET, run.id())
                .detail("conversationId", run.conversationId().toString())
                .detail("componentIds", one.componentIds().stream().map(UUID::toString).toList())
                .detail("reason", one.reason())
                .detail("epoch", run.epoch())
                .detail("runnerHostId", host.runnerHostId().toString())
                .build());
            log.info("Run {} of conversation {}: runner host {} confirmed the revocation of {}", run.id(),
                    run.conversationKey(), host.runnerHostId(), one.componentIds());
        }
        if (!confirmed.isEmpty()) {
            hub.changedAfterCommit(run.id());
        }
        return List.of();
    }

    private static @Nullable UUID currentUser() {
        return CurrentActor.user().map(AuthenticatedUser::id).orElse(null);
    }
}
