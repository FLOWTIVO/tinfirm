package com.flowtivo.tinfirm.audit.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.BearerGrant;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;

import tools.jackson.databind.JsonNode;

/**
 * Appends to a chain under the lock of its head row: {@code SELECT ... FOR UPDATE} serialises concurrent appends,
 * so {@code seq} is gapless and every event links to its predecessor's hash.
 * <p>
 * <b>The head is locked last</b> (CONVENTIONS §8, §10): the events a transaction records are kept, in their order, and
 * appended when it commits, by a {@link TransactionExecutionListener} of the transaction manager whose
 * {@code beforeCommit} runs after every synchronization's {@code beforeCommit} and {@code beforeCompletion} (an
 * {@code @TransactionalEventListener} of the phase {@code BEFORE_COMMIT} among them), right before the commit. It
 * flushes the transaction first ({@link TransactionStatus#flush}), so that JPA's entities, which the commit would write
 * only after it, lock their rows before the head as well. So a transaction never holds a chain's head while it waits
 * for another row, whatever the order of its calls, and no work in a synchronization comes after its events either.
 * Each event keeps the time and the details of its call, and its tenant context ({@link TenantContextHolder}), under
 * which it is appended: a transaction that entered several companies appends each company's events in that company's
 * context, then sets its own back ({@link TenantScope#eachIn}). A transaction that rolls back appends none. An event
 * recorded once the transaction's events were appended (an {@code afterCommit} or {@code afterCompletion} callback on
 * the same thread, whose transaction-local tenant context is gone) is appended in a transaction of its own, and so is
 * one kept past a commit through a transaction manager without the listener. Without a transaction synchronization, an
 * event is appended at once. A nested transaction's savepoint is not followed (the application takes none): its events
 * stay with its transaction.
 * <p>
 * An event recorded while the thread runs under a bearer credential of the MCP endpoint ({@link BearerGrantHolder})
 * carries the credential in its {@code details}, {@code tokenId} for a personal access token and {@code grantId} for
 * an OAuth grant (ADR-0045), added here, when it is recorded, before the canonical form is hashed, rather than at every
 * call site: the chain shows which actions came from a person's client. A key the caller already set is kept.
 */
@Component
class JdbcAuditLog implements AuditLog {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditLog.class);

    private final AuditEventStore store;

    private final TimeSource timeSource;

    private final TransactionTemplate afterCommit;

    private final TenantScope tenants;

    JdbcAuditLog(AuditEventStore store, TimeSource timeSource, PlatformTransactionManager transactionManager,
            TenantScope tenants) {
        this.store = store;
        this.timeSource = timeSource;
        this.tenants = tenants;
        this.afterCommit = new TransactionTemplate(transactionManager);
        this.afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        if (!(transactionManager instanceof ConfigurableTransactionManager configurable)) {
            throw new IllegalStateException("The audit log appends a transaction's events when it commits, through a "
                    + "listener of the transaction manager, which " + transactionManager.getClass().getName()
                    + " does not take");
        }
        configurable.addListener(new AtCommitListener());
    }

    /**
     * An event as its call recorded it: its time, its details with the bearer credential, and the tenant context it
     * was recorded in, which its chain's row-level security sees when it is appended; ready to append.
     */
    private record Pending(AuditRecord record, JsonNode details, Instant occurredAt, TenantContext tenant) {
    }

    @Override
    @Transactional
    public void record(AuditRecord record) {
        Pending event = new Pending(record, AuditCanonicalForm.detailsTree(withGrant(record.details(),
                BearerGrantHolder.current())), timeSource.now(), TenantContextHolder.current());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            append(event);
            return;
        }
        AtCommit atCommit = atCommit();
        if (atCommit == null) {
            atCommit = new AtCommit(this);
            TransactionSynchronizationManager.registerSynchronization(atCommit);
        }
        if (atCommit.appended) {
            // After the transaction's events were appended (an afterCommit or afterCompletion callback on this thread):
            // in a transaction of its own.
            appendAfterCommit(List.of(event));
        }
        else {
            atCommit.events.add(event);
        }
    }

    /** The synchronization this log registered with the transaction for its events, if any. */
    private @Nullable AtCommit atCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof AtCommit atCommit && atCommit.owner == this) {
                return atCommit;
            }
        }
        return null;
    }

    /**
     * Appends the transaction's events right before it commits: after every synchronization's {@code beforeCommit} and
     * {@code beforeCompletion}, once the transaction is flushed, in it. Only a transaction of its own (not a nested
     * one's savepoint, nor a transaction that joins another): the synchronizations are its own then.
     */
    private final class AtCommitListener implements TransactionExecutionListener {

        @Override
        public void beforeCommit(TransactionExecution transaction) {
            if (!transaction.isNewTransaction() || transaction.isNested()
                    || !TransactionSynchronizationManager.isSynchronizationActive()) {
                return;
            }
            AtCommit atCommit = atCommit();
            if (atCommit == null) {
                return;
            }
            if (!atCommit.events.isEmpty() && transaction instanceof TransactionStatus status) {
                // The commit would write JPA's entities after this: flushed now, they lock their rows before the head.
                status.flush();
            }
            atCommit.appended = true;
            List<Pending> pending = List.copyOf(atCommit.events);
            atCommit.events.clear();
            appendAll(pending);
        }
    }

    /**
     * The events of one transaction, kept until it commits ({@link AtCommitListener}); registered with its first event.
     * A rollback drops them. Events still kept after a commit were recorded after it, or the commit came through a
     * transaction manager without the listener: they are appended in a transaction of their own.
     */
    private static final class AtCommit implements TransactionSynchronization {

        private final JdbcAuditLog owner;

        private final List<Pending> events = new ArrayList<>();

        /** The transaction's events were appended: it commits, or has. */
        private boolean appended;

        AtCommit(JdbcAuditLog owner) {
            this.owner = owner;
        }

        @Override
        public void afterCompletion(int status) {
            if (events.isEmpty()) {
                return;
            }
            List<Pending> pending = List.copyOf(events);
            events.clear();
            if (status == STATUS_COMMITTED) {
                owner.appendAfterCommit(pending);
            }
        }
    }

    private void appendAfterCommit(List<Pending> pending) {
        try {
            afterCommit.executeWithoutResult(status -> appendAll(pending));
        }
        catch (RuntimeException ex) {
            log.error("{} audit events recorded after their transaction's commit could not be appended",
                    pending.size(), ex);
        }
    }

    /**
     * Appends the events in the current transaction, each under the tenant context it was recorded in: a transaction
     * that entered several companies (a user's deactivation suspends them in each) records each company's events in
     * that company's context, and its chain's row-level security sees that one at the append, not the last one
     * entered. The transaction's own context is set back after.
     */
    private void appendAll(List<Pending> events) {
        tenants.eachIn(events, Pending::tenant, this::append);
    }

    /** Appends one event to its chain under the lock of the chain's head, in the current transaction. */
    private void append(Pending event) {
        AuditRecord record = event.record();
        ChainHead head = store.lockHead(record.companyId()).orElseGet(() -> {
            store.createHeadIfAbsent(record.companyId());
            return store.lockHead(record.companyId())
                .orElseThrow(() -> new IllegalStateException("Audit chain head is not accessible"));
        });

        long seq = head.lastSeq() + 1;
        Instant occurredAt = event.occurredAt();
        AuditCanonicalForm.Content content = new AuditCanonicalForm.Content(record.companyId(), seq, occurredAt,
                record.actor().type().name(), record.actor().id(), record.action().value(), record.targetType(),
                record.targetId(), event.details());
        String hash = AuditCanonicalForm.hash(head.lastHash(), AuditCanonicalForm.canonical(content));

        store.insert(new StoredAuditEvent(Ids.newId(), record.companyId(), seq, occurredAt, content.actorType(),
                content.actorId(), content.action(), content.targetType(), content.targetId(),
                AuditCanonicalForm.canonicalJson(event.details()), head.lastHash(), hash));
        store.advanceHead(record.companyId(), seq, hash);
    }

    /** The details with the bearer credential's id under its key, when one is held and the key is not set. */
    static Map<String, @Nullable Object> withGrant(Map<String, @Nullable Object> details,
            Optional<BearerGrant> grant) {
        if (grant.isEmpty() || details.containsKey(grant.get().auditDetailKey())) {
            return details;
        }
        Map<String, @Nullable Object> enriched = new LinkedHashMap<>(details);
        enriched.put(grant.get().auditDetailKey(), grant.get().id().toString());
        return enriched;
    }
}
