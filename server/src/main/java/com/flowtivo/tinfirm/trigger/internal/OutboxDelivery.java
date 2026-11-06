package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.trigger.PlaceWriter;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

/**
 * Delivers the write-back outbox, keyed by conversation (the package 4 contract, §5.9, T13): every
 * {@code tinfirm.trigger.outbox.tick} on {@code worker} replicas, and after the commit of a transaction that queued
 * rows or made them due. A pass runs rounds while they make progress; a round takes the due rows of every company
 * ({@code outbox_due}: per conversation its oldest undelivered row, later rows waiting behind it), and for each, in its
 * company as SYSTEM:
 * <ol>
 * <li><b>claims</b> it ({@code claimed_until}, 5 minutes) in a short transaction, where a paused trigger holds it
 * ({@code HELD}, §5.11) and a deleted trigger or an ended conversation drops it (but a {@code SUBJECT_CLOSED} one,
 * whose last turn's result is still written); while the company's runs are stopped
 * ({@code CompanySwitches.runsStopped}) or the row's system is paused ({@code SystemInfo.paused}, package 6's
 * switches) it is put off, without an attempt, by {@code first-retry}, and so delivered once the stop is lifted or the
 * system resumed;</li>
 * <li>outside any transaction, takes the token of the conversation's binding on the row's system
 * ({@link ConversationBindings#use}, purpose {@code write-back:{outboxId}}): a waiting binding keeps the row waiting,
 * without an attempt, until it is bound again ({@code BindingBound}), at most 7 days, then drops it; a gone binding drops
 * it; a binding that holds while its system is paused ({@code SYSTEM_PAUSED}, which changes no binding) puts it off as
 * the claim does, and so does one the reveal holds ({@code BindingUse.Held}: the company's runs stopped or the agent
 * paused since the claim; a trigger paused since holds it); a token that could not be revealed for a passing failure
 * is a failed attempt, tried again. After a
 * reassignment's rebinding (§11) the binding holds the new author's account, which writes. A token whose access is
 * {@code READ} now (the credential lent read-only to the trigger's service account, as it is at this attempt, rows
 * queued before a narrowing included) writes nothing (I9: no write through a trigger's {@code READ} lending): the row
 * is dropped with a posted notice that names the credential and the service account, as a gone binding's is;</li>
 * <li>when its last attempt may have reached the system, asks the place's writer whether it wrote after all
 * ({@link PlaceWriter#written}); else writes it ({@link PlaceWriter#write}), the writer the place's
 * ({@link PlaceWriter}, a registry by place for package 5's writers);</li>
 * <li><b>records</b> the outcome in a transaction of its own, in the lock order (CONVENTIONS §10, {@link TriggerLocks}):
 * a delivered row's every poll's snapshot of its subject first, then the conversation, then the row, then the audit
 * ({@code source.written}) and {@code SystemWritten} with its {@code outboxId}, whose recorder inserts Tinfirm's
 * record ({@code system_write}, {@code SystemWriteRecorder}). A retryable failure is
 * tried again after 1, 2, 4… minutes up to an hour, or the system's {@code Retry-After} when longer, at most 10
 * attempts; any other failure, and the last attempt, drop the row with the reason, posted as a {@code SYSTEM} message
 * (the inbox counts it). A {@code 401} runs {@code Accounts.refused}: the binding waits, the row with it, and the
 * trigger pauses {@code ACCOUNT_BROKEN} through the gate (§11, point 9). A writer that waits for something outside
 * the row ({@link PlaceWriter.Outcome.Waiting}: package 5's bot, missing or refused) puts it off without an attempt,
 * and drops it once it waited {@code binding-wait} since it was queued.</li>
 * </ol>
 * A row its place's bot writes ({@link PlaceWriter.Writer#BOT}, package 5's chats) takes no binding: its writer finds
 * its own credential.
 */
@Component
class OutboxDelivery implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxDelivery.class);

    /** The audit's target. */
    static final String TARGET = "outbox";

    /** Why a row is put off while its system is paused. */
    private static final String SYSTEM_PAUSED = "its system is paused";

    private final OutboxStore store;

    private final TriggerStore triggers;

    private final Conversations conversations;

    private final TriggerConversations triggerConversations;

    private final ConversationBindings bindings;

    private final ConversationLock locks;

    private final Accounts accounts;

    private final Principals principals;

    private final Agents agents;

    private final CompanySwitches companySwitches;

    private final Systems systems;

    private final Map<ConversationInfo.Place, PlaceWriter> writers = new EnumMap<>(ConversationInfo.Place.class);

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final OutboxProperties properties;

    private final TimeSource time;

    private final boolean worker;

    private final ExecutorService executor = Executors
        .newThreadPerTaskExecutor(Thread.ofVirtual().name("trigger-outbox-", 0).factory());

    /**
     * Whether a writer writes to the place ({@link PlaceWriter#place}): nothing is queued for a conversation of a place
     * without one (a Tinfirm thread, a schedule, a webhook: their results stay in Tinfirm).
     */
    boolean writes(ConversationInfo.Place place) {
        return writers.containsKey(place);
    }

    /** A pass runs; another was asked for meanwhile. */
    private final AtomicBoolean running = new AtomicBoolean();

    private final AtomicBoolean again = new AtomicBoolean();

    OutboxDelivery(OutboxStore store, TriggerStore triggers, Conversations conversations,
            TriggerConversations triggerConversations, ConversationBindings bindings, ConversationLock locks,
            Accounts accounts, Principals principals, Agents agents, CompanySwitches companySwitches,
            Systems systems, List<PlaceWriter> writers, SystemTransactions transactions, AuditLog auditLog,
            ApplicationEventPublisher events, OutboxProperties properties, TinfirmProperties tinfirm,
            TimeSource time) {
        this.store = store;
        this.triggers = triggers;
        this.conversations = conversations;
        this.triggerConversations = triggerConversations;
        this.bindings = bindings;
        this.locks = locks;
        this.accounts = accounts;
        this.principals = principals;
        this.agents = agents;
        this.companySwitches = companySwitches;
        this.systems = systems;
        for (PlaceWriter writer : writers) {
            if (this.writers.put(writer.place(), writer) != null) {
                throw new IllegalStateException("Two writers of the outbox for the place " + writer.place());
            }
        }
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.events = events;
        this.properties = properties;
        this.time = time;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${tinfirm.trigger.outbox.tick:PT15S}",
            initialDelayString = "${tinfirm.trigger.outbox.tick:PT15S}")
    void scheduled() {
        if (worker) {
            requestPass();
        }
    }

    /**
     * A pass on a thread of its own, soon: one at a time per replica; one asked for while one runs follows it (rows
     * another replica claimed meanwhile are skipped there).
     */
    void requestPass() {
        again.set(true);
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                while (again.getAndSet(false)) {
                    try {
                        deliverDue();
                    }
                    catch (RuntimeException ex) {
                        log.warn("A pass of the write-back failed", ex);
                    }
                }
            }
            finally {
                running.set(false);
                if (again.get()) {
                    requestPass();
                }
            }
        });
    }

    /** Marks a transaction that asked for a pass after its commit. */
    private static final String AFTER_COMMIT = OutboxDelivery.class.getName() + ".afterCommit";

    /**
     * A pass after the commit of the caller's transaction, which queued rows or made them due (once per transaction);
     * without {@code deliverAfterCommit}, the tick delivers them.
     */
    void requestPassAfterCommit() {
        if (!properties.deliverAfterCommit()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            if (TransactionSynchronizationManager.hasResource(AFTER_COMMIT)) {
                return;
            }
            TransactionSynchronizationManager.bindResource(AFTER_COMMIT, Boolean.TRUE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(AFTER_COMMIT);
                }
            });
        }
        AfterCommit.run(this::requestPass);
    }

    /**
     * One pass: rounds of the due rows while they make progress. Safe on several replicas at once.
     *
     * @return the rows delivered
     */
    int deliverDue() {
        NoTransaction.require("The write-back's delivery");
        int delivered = 0;
        for (int round = 0; round < properties.rounds(); round++) {
            List<OutboxStore.Due> due = transactions.unscoped(() -> store.due(time.now(), properties.batch()));
            int progress = 0;
            for (OutboxStore.Due row : due) {
                try {
                    Result result = SystemContext.callInCompany(row.companyId(), () -> deliver(row.companyId(),
                            row.id()));
                    if (result == Result.DELIVERED) {
                        delivered++;
                    }
                    if (result != Result.SKIPPED && result != Result.FAILED) {
                        progress++;
                    }
                }
                catch (RuntimeException ex) {
                    log.warn("Delivering outbox row {} failed", row.id(), ex);
                }
            }
            if (progress == 0) {
                break;
            }
        }
        return delivered;
    }

    /** What one attempt of a row did. */
    enum Result {
        DELIVERED, FAILED, WAITING, HELD, DROPPED, SKIPPED
    }

    /** A claimed row and what writes it. */
    private record Claimed(PlaceWriter.Row row, ConversationInfo conversation, PlaceWriter writer, String agentName) {
    }

    /** What the claim found: a row to write, or what became of it. */
    private sealed interface Claim {

        record Write(Claimed claimed) implements Claim {
        }

        record Drop(PlaceWriter.Row row, String reason, boolean post) implements Claim {
        }

        record Done(Result result) implements Claim {
        }
    }

    /** One attempt of the row, in its company as SYSTEM, outside any transaction. */
    Result deliver(UUID companyId, UUID outboxId) {
        Claim claim = transactions.inCompany(companyId, () -> claim(companyId, outboxId));
        if (claim instanceof Claim.Done done) {
            return done.result();
        }
        if (claim instanceof Claim.Drop drop) {
            return transactions.inCompany(companyId, () -> dropped(drop.row(), drop.row().attempts(), drop.reason(),
                    null, drop.post()));
        }
        Claimed claimed = ((Claim.Write) claim).claimed();
        PlaceWriter.Row row = claimed.row();
        CallCredential credential = null;
        AccountInfo account = null;
        BindingInfo binding = null;
        if (claimed.writer().writer() == PlaceWriter.Writer.BINDING) {
            if (row.bindingId() == null) {
                return transactions.inCompany(companyId, () -> dropped(row, row.attempts(), "the row names no "
                        + "binding to write it with", null, true));
            }
            ConversationBindings.BindingUse use = bindings.use(companyId, row.bindingId(), "write-back:" + row.id());
            binding = use.binding();
            if (use instanceof ConversationBindings.BindingUse.Held held) {
                // The reveal is the gate (D5): a kill switch went on after the claim. The binding holds; the row is
                // held or put off as the claim does, without an attempt.
                return transactions.inCompany(companyId, () -> held(row, held));
            }
            if (use instanceof ConversationBindings.BindingUse.Failed) {
                // The binding holds, and its token could not be revealed for a passing failure: an attempt, again.
                return recorded(row, new PlaceWriter.Outcome.Failed("the account's token could not be revealed",
                        true, false, null), null, binding);
            }
            if (!(use instanceof ConversationBindings.BindingUse.Token token)) {
                BindingInfo unavailable = use.binding();
                return transactions.inCompany(companyId, () -> unavailable(row, unavailable));
            }
            if (token.access() == GrantInfo.Access.READ) {
                // I9: no write through a trigger's READ lending, the access now; before any writer, so that neither
                // the tracker's nor a chat's writes with it. The token was revealed and is let go unused.
                BindingInfo readOnly = token.binding();
                AccountInfo lent = token.account();
                return transactions.inCompany(companyId, () -> dropped(row, row.attempts(), readOnly(readOnly, lent),
                        readOnly, true));
            }
            credential = token.credential().callCredential();
            account = token.account();
        }
        PlaceWriter.Write write = new PlaceWriter.Write(row, claimed.conversation(), claimed.agentName(), credential);
        PlaceWriter.Outcome outcome;
        try {
            Optional<PlaceWriter.Outcome.Delivered> before = row.ambiguous() ? claimed.writer().written(write)
                    : Optional.empty();
            outcome = before.isPresent() ? before.get() : claimed.writer().write(write);
        }
        catch (RuntimeException ex) {
            // The exception's class only: a message could quote a header, a header could hold the token.
            log.warn("Writing outbox row {} failed inside Tinfirm: {}", row.id(), ex.getClass().getName());
            outcome = new PlaceWriter.Outcome.Failed("the write failed inside Tinfirm (" + ex.getClass()
                .getSimpleName() + ")", true, true, null);
        }
        return recorded(row, outcome, account, binding);
    }

    /** The attempts a record makes, should a concurrent transaction make it fail (a deadlock's loser). */
    private static final int RECORD_TRIES = 3;

    /**
     * Records the attempt, tried again when its transaction fails; should it never succeed, the row is marked
     * {@code ambiguous}, so that its next attempt, once its claim expired, looks for what this one may have written.
     */
    private Result recorded(PlaceWriter.Row row, PlaceWriter.Outcome outcome, @Nullable AccountInfo account,
            @Nullable BindingInfo binding) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < RECORD_TRIES; attempt++) {
            try {
                return transactions.inCompany(row.companyId(), () -> record(row, outcome, account, binding));
            }
            catch (org.springframework.dao.TransientDataAccessException ex) {
                last = ex;
                log.info("Recording outbox row {} failed, trying again: {}", row.id(), ex.getClass().getSimpleName());
            }
        }
        try {
            transactions.runInCompany(row.companyId(), () -> store.markAmbiguous(row.companyId(), row.id(),
                    time.now()));
        }
        catch (RuntimeException ex) {
            log.warn("Outbox row {} could not be marked ambiguous", row.id(), ex);
        }
        throw Objects.requireNonNull(last);
    }

    // ---- the claim -------------------------------------------------------------------------------------------------

    private Claim claim(UUID companyId, UUID outboxId) {
        Instant now = time.now();
        OutboxStore.Stored stored = store.claim(companyId, outboxId, now, now.plus(properties.claim())).orElse(null);
        if (stored == null) {
            return new Claim.Done(Result.SKIPPED);
        }
        PlaceWriter.Row row = stored.row();
        ConversationInfo conversation = conversations.find(companyId, row.conversationId()).orElse(null);
        if (conversation == null) {
            return new Claim.Drop(row, "the conversation does not exist", false);
        }
        if (conversation.triggerId() != null) {
            TriggerInfo trigger = triggers.find(companyId, conversation.triggerId()).orElse(null);
            if (trigger == null || !trigger.live()) {
                return new Claim.Drop(row, "TRIGGER_DELETED", false);
            }
            if (trigger.paused()) {
                // Paused after the row was queued, or while it was claimed: held until it is resumed (§5.11).
                store.hold(companyId, row.id(), now);
                return new Claim.Done(Result.HELD);
            }
        }
        if (!conversation.open() && conversation.endedReason() != ConversationInfo.EndedReason.SUBJECT_CLOSED) {
            return new Claim.Drop(row, "the conversation ended (" + conversation.endedReason() + ")", false);
        }
        // Package 6's switches: a row queued during the company's stop, or on a paused system, is put off, not tried.
        if (companySwitches.runsStopped(companyId).isPresent()) {
            store.waiting(companyId, row.id(), now.plus(properties.firstRetry()), "the company's runs are stopped",
                    now);
            return new Claim.Done(Result.SKIPPED);
        }
        if (systems.find(companyId, row.systemId()).map(SystemInfo::paused).orElse(false)) {
            store.waiting(companyId, row.id(), now.plus(properties.firstRetry()), SYSTEM_PAUSED, now);
            return new Claim.Done(Result.SKIPPED);
        }
        PlaceWriter writer = writers.get(conversation.place());
        if (writer == null) {
            return new Claim.Drop(row, "nothing writes to a " + conversation.place() + " place", true);
        }
        String agentName = agents.find(companyId, conversation.agentId()).map(AgentInfo::name).orElse("An agent");
        return new Claim.Write(new Claimed(row, conversation, writer, agentName));
    }

    // ---- the record ------------------------------------------------------------------------------------------------

    /** The attempt's outcome, recorded in the lock order. */
    private Result record(PlaceWriter.Row row, PlaceWriter.Outcome outcome, @Nullable AccountInfo account,
            @Nullable BindingInfo binding) {
        return switch (outcome) {
            case PlaceWriter.Outcome.Delivered delivered -> delivered(row, delivered, account, binding);
            case PlaceWriter.Outcome.Failed failed -> failed(row, failed, account, binding);
            case PlaceWriter.Outcome.Reconnect reconnect -> reconnect(row, reconnect, account);
            case PlaceWriter.Outcome.Waiting waiting -> waiting(row, waiting);
            case PlaceWriter.Outcome.Dropped dropped -> dropped(row, row.attempts() + 1, dropped.reason(), binding,
                    true);
        };
    }

    /**
     * Written: every poll's snapshot of the subject first (the poll's rows lead the lock order), then the conversation
     * and the row, then the audit and {@code SystemWritten} with the row's id, whose recorder
     * ({@code SystemWriteRecorder}) inserts Tinfirm's record ({@code system_write}).
     */
    private Result delivered(PlaceWriter.Row row, PlaceWriter.Outcome.Delivered delivered,
            @Nullable AccountInfo account, @Nullable BindingInfo binding) {
        Instant now = time.now();
        ConversationEvents.WrittenValues values = delivered.values();
        store.updateSnapshots(row.companyId(), row.systemId(), row.subjectKey(), values.status(),
                values.labelsAdded(), values.labelsRemoved(), "CLOSE".equals(delivered.writtenKind()));
        locks.lock(row.companyId(), row.conversationId());
        OutboxStore.Stored stored = store.lock(row.companyId(), row.id()).orElse(null);
        int attempts = row.attempts() + 1;
        if (stored == null || !store.delivered(row.companyId(), row.id(), delivered.externalId(), attempts, now)) {
            // Dropped meanwhile (its conversation ended, its trigger was deleted): written all the same, recorded.
            log.info("Outbox row {} was written, and {} meanwhile", row.id(),
                    stored != null ? stored.status() : "removed");
        }
        audit(row, "DELIVERED", null, attempts, account, binding);
        events.publishEvent(new ConversationEvents.SystemWritten(row.companyId(), row.conversationId(),
                row.systemId(), delivered.writtenKind() + " " + row.subjectKey(), delivered.externalId(),
                delivered.writtenKind(), row.subjectKey(), values, row.id()));
        return Result.DELIVERED;
    }

    private Result failed(PlaceWriter.Row row, PlaceWriter.Outcome.Failed failed, @Nullable AccountInfo account,
            @Nullable BindingInfo binding) {
        int attempts = row.attempts() + 1;
        if (!failed.retryable() || attempts >= properties.maxAttempts()) {
            return dropped(row, attempts, failed.retryable() ? "gave up after " + attempts + " attempts: "
                    + failed.message() : failed.message(), binding, true);
        }
        Duration delay = properties.retryDelay(attempts);
        if (failed.retryAfter() != null && failed.retryAfter().compareTo(delay) > 0) {
            delay = failed.retryAfter().compareTo(properties.lastRetry()) > 0 ? properties.lastRetry()
                    : failed.retryAfter();
        }
        Instant now = time.now();
        locks.lock(row.companyId(), row.conversationId());
        store.retry(row.companyId(), row.id(), attempts, now.plus(delay), failed.message(), failed.ambiguous(), now);
        log.info("Writing outbox row {} ({} of {}) failed, trying again in {}: {}", row.id(), row.kind(),
                row.subjectKey(), delay, failed.message());
        return Result.FAILED;
    }

    /**
     * The system refused the credential: the account turns {@code RECONNECT} ({@code Accounts.refused}, which pauses
     * the trigger through the gate, §11 point 9), the binding waits, and the row with it, without an attempt. In the
     * lock order (CONVENTIONS §10): the conversation, the row, then the binding ({@code ConversationBindings.check},
     * which finds it usable yet and changes nothing), then the account, then the binding's wait; the audit chain's
     * head when the transaction commits.
     */
    private Result reconnect(PlaceWriter.Row row, PlaceWriter.Outcome.Reconnect reconnect,
            @Nullable AccountInfo account) {
        Instant now = time.now();
        locks.lock(row.companyId(), row.conversationId());
        store.waiting(row.companyId(), row.id(), now.plus(properties.bindingWait()), "the account must be connected "
                + "again: " + reconnect.message(), now);
        if (row.bindingId() != null) {
            bindings.check(row.companyId(), row.bindingId());
        }
        if (account != null) {
            accounts.refused(row.companyId(), account.id(), reconnect.message());
        }
        if (row.bindingId() != null) {
            bindings.check(row.companyId(), row.bindingId());
        }
        return Result.WAITING;
    }

    /**
     * The writer waits for something outside the row (package 5's bot): put off without an attempt, at most
     * {@code bindingWait} since the row was queued, then dropped.
     */
    private Result waiting(PlaceWriter.Row row, PlaceWriter.Outcome.Waiting waiting) {
        Instant now = time.now();
        Instant until = row.createdAt().plus(properties.bindingWait());
        if (!until.isAfter(now)) {
            return dropped(row, row.attempts(), "it waited " + properties.bindingWait().toDays() + " days: "
                    + waiting.message(), null, true);
        }
        Instant next = now.plus(waiting.retryIn());
        locks.lock(row.companyId(), row.conversationId());
        store.waiting(row.companyId(), row.id(), next.isAfter(until) ? until : next, waiting.message(), now);
        return Result.WAITING;
    }

    /**
     * The binding holds, and a kill switch held the reveal ({@code BindingUse.Held}): a paused trigger holds the row
     * until its resume ({@code HELD}, §5.11); the company's stop and a paused agent put it off by {@code first-retry},
     * without an attempt, as the claim puts it off during the stop.
     */
    private Result held(PlaceWriter.Row row, ConversationBindings.BindingUse.Held held) {
        Instant now = time.now();
        locks.lock(row.companyId(), row.conversationId());
        if (held.hold() == ConversationBindings.Hold.TRIGGER_PAUSED) {
            store.hold(row.companyId(), row.id(), now);
            return Result.HELD;
        }
        store.waiting(row.companyId(), row.id(), now.plus(properties.firstRetry()), held.why(), now);
        return Result.WAITING;
    }

    /**
     * No token now: a waiting binding keeps the row waiting, at most {@code bindingWait}; a gone one drops it; one that
     * holds, its system paused ({@code SYSTEM_PAUSED} changes no binding), puts it off as the claim does.
     */
    private Result unavailable(PlaceWriter.Row row, BindingInfo binding) {
        if (binding.gone()) {
            return dropped(row, row.attempts(), "its account on the system is gone (" + binding.reason() + ")",
                    binding, true);
        }
        Instant now = time.now();
        if (!binding.waiting()) {
            locks.lock(row.companyId(), row.conversationId());
            store.waiting(row.companyId(), row.id(), now.plus(properties.firstRetry()), SYSTEM_PAUSED, now);
            return Result.WAITING;
        }
        Instant since = binding.waitingSince() != null ? binding.waitingSince() : now;
        Instant until = since.plus(properties.bindingWait());
        if (!until.isAfter(now)) {
            return dropped(row, row.attempts(), "it waited " + properties.bindingWait().toDays() + " days for its "
                    + "account on the system (" + binding.reason() + ")", binding, true);
        }
        locks.lock(row.companyId(), row.conversationId());
        store.waiting(row.companyId(), row.id(), until, "waits for its account on the system (" + binding.reason()
                + ")", now);
        return Result.WAITING;
    }

    /**
     * Dropped for good: the conversation first, its {@code SYSTEM} message (the inbox counts the row), the row, the
     * audit.
     *
     * @param post whether to say so in the conversation; not for one that ended or whose trigger was deleted
     */
    private Result dropped(PlaceWriter.Row row, int attempts, String reason, @Nullable BindingInfo binding,
            boolean post) {
        locks.lock(row.companyId(), row.conversationId());
        if (post) {
            triggerConversations.post(row.companyId(), row.conversationId(), row.kind().chat()
                    ? "Tinfirm did not post " + what(row) + " to the chat: " + reason + "."
                    : "Tinfirm did not write " + what(row) + " to " + row.subjectKey() + ": " + reason + ".");
        }
        if (store.drop(row.companyId(), row.id(), reason, attempts, time.now())) {
            log.warn("Outbox row {} ({} of {}) dropped: {}", row.id(), row.kind(), row.subjectKey(), reason);
            audit(row, "DROPPED", reason, attempts, null, binding);
        }
        return Result.DROPPED;
    }

    /** {@code source.written} (§8): never the body. */
    private void audit(PlaceWriter.Row row, String outcome, @Nullable String reason, int attempts,
            @Nullable AccountInfo account, @Nullable BindingInfo binding) {
        UUID accountId = account != null ? account.id() : binding != null ? binding.accountId() : null;
        // Whom it acted through: the binding's holder (a credential has none of its own), else the account's member.
        UUID holder = binding != null && binding.holderPrincipalId() != null ? binding.holderPrincipalId()
                : account != null ? account.principalId() : null;
        Map<String, @Nullable Object> details = new HashMap<>();
        details.put("conversationId", row.conversationId().toString());
        details.put("systemId", row.systemId().toString());
        details.put("subjectKey", row.subjectKey());
        details.put("kind", row.kind().name());
        details.put("cause", row.cause().name());
        details.put("accountId", accountId != null ? accountId.toString() : null);
        details.put("holderPrincipalId", holder != null ? holder.toString() : null);
        details.put("attempts", Math.min(attempts, 10));
        details.put("outcome", outcome);
        details.put("reason", reason != null ? OutboxStore.cut(reason, 500) : null);
        auditLog.record(AuditRecord.company(row.companyId(), AuditAction.SOURCE_WRITTEN, CurrentActor.actor())
            .target(TARGET, row.id())
            .details(details)
            .build());
    }

    /**
     * Why a row is dropped under a {@code READ} lending: the credential by its name, which every member it is lent to
     * may see, and the service account it is lent to, as the tools' refusal says it ({@code ToolRules}).
     */
    private String readOnly(BindingInfo binding, AccountInfo account) {
        String holder = binding.holderPrincipalId() != null
                ? principals.find(binding.companyId(), binding.holderPrincipalId()).map(PrincipalInfo::name)
                    .orElse(null)
                : null;
        return "its credential" + (account.name() != null ? " " + account.name() : "") + " is lent read-only to "
                + Objects.requireNonNullElse(holder, "the service account it acts through");
    }

    /** What the row writes, in words, for its drop's message. */
    static String what(PlaceWriter.Row row) {
        return switch (row.kind()) {
            case COMMENT -> switch (row.cause()) {
                case RESULT -> "the result's comment";
                case FAILURE -> "the comment on the failure";
                case QUESTION -> "the questions' comment";
                case ANSWER -> "the answer given in Tinfirm";
                default -> "a comment";
            };
            case TRANSITION -> "the transition “" + row.value() + "”";
            case LABEL_ADD -> "the label “" + row.value() + "”";
            case LABEL_REMOVE -> "the removal of the label “" + row.value() + "”";
            case CLOSE -> "the close";
            case ATTACHMENT -> "the document " + (row.value() != null ? row.value() : "");
            case REMOTE_LINK -> "the link " + (row.url() != null ? row.url() : "");
            case CHAT_POST -> switch (row.cause()) {
                case RESULT -> "the answer";
                case FAILURE -> "the note on the failure";
                case QUESTION -> "the question's card";
                case APPROVAL -> "the approval's card";
                case ANSWER -> "the answer given in Tinfirm";
                case CONNECT -> "the Connect card";
                default -> "a message";
            };
            case CHAT_UPDATE -> "the update of a card";
            case CHAT_FILE -> "a document";
            case CHAT_REACTION -> "the reaction to a message taken";
        };
    }
}
