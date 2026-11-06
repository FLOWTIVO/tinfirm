package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerEvents;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * The states of a trigger and what they do to its conversations (the package 4 contract, §5.3, §5.11 and §11): pause,
 * wait, resume, delete. Each runs in the caller's transaction, which locked the trigger's row first; the trigger's
 * conversations are locked after it, by id ({@code TriggerConversations}). Each change is audited and announced
 * ({@link TriggerEvents}).
 */
@Component
class TriggerStates {

    static final String TARGET = "trigger";

    private final TriggerStore store;

    private final TriggerConversations conversations;

    private final ConversationLock locks;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    TriggerStates(TriggerStore store, TriggerConversations conversations, ConversationLock locks, AuditLog auditLog,
            ApplicationEventPublisher events, TimeSource time) {
        this.store = store;
        this.conversations = conversations;
        this.locks = locks;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    /**
     * Pauses the locked trigger (§5.11): it fires nothing; its conversations' queued turns wait
     * ({@code TRIGGER_PAUSED}) and their undelivered write-back is held. With {@code stopRunning} (a person's, or an
     * author who left, §11) their active turns are cancelled; their open decisions and the outbox stay. A paused trigger
     * keeps its first reason, but {@code AUTHOR_LEFT}, which replaces any, and {@code TO_ACCEPT}, a reassignment's,
     * which replaces the loss it follows (H2).
     *
     * @param by the user who paused it; null for the system
     * @return the runs cancelled
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<UUID> pause(TriggerInfo trigger, PausedReason reason, @Nullable UUID by, boolean stopRunning) {
        return pause(trigger, reason, by, stopRunning, null);
    }

    /**
     * As {@link #pause(TriggerInfo, PausedReason, UUID, boolean)}, the switch that paused it on record
     * ({@code cause} {@code system.paused}, package 6).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<UUID> pause(TriggerInfo trigger, PausedReason reason, @Nullable UUID by, boolean stopRunning,
            @Nullable String cause) {
        if (trigger.paused() && trigger.pausedReason() == reason && !stopRunning) {
            return List.of();
        }
        Instant now = time.now();
        boolean changes = !trigger.paused() || (reason == PausedReason.AUTHOR_LEFT || reason == PausedReason.TO_ACCEPT)
                && trigger.pausedReason() != reason;
        if (changes) {
            store.pause(trigger.companyId(), trigger.id(), reason, by, now);
        }
        // With stopRunning its conversations are locked anyway (their turns cancelled): before their outbox rows.
        List<UUID> open = conversationIds(trigger, stopRunning);
        store.holdOutbox(trigger.companyId(), open, now);
        List<UUID> cancelled = new ArrayList<>();
        if (stopRunning) {
            for (UUID conversationId : open) {
                conversations.cancelTurn(trigger.companyId(), conversationId).ifPresent(cancelled::add);
            }
        }
        if (changes || !cancelled.isEmpty()) {
            AuditRecord.Builder record = AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_PAUSED,
                    CurrentActor.actor())
                .target(TARGET, trigger.id())
                .detail("reason", reason.name())
                .detail("stopRunning", stopRunning)
                .detail("runsCancelled", cancelled.stream().map(UUID::toString).toList());
            if (cause != null) {
                record.detail("cause", cause);
            }
            auditLog.record(record.build());
        }
        if (changes) {
            events.publishEvent(new TriggerEvents.TriggerPaused(trigger.companyId(), trigger.id(), reason));
        }
        return cancelled;
    }

    /** The trigger waits (§5.3): it fires nothing until the event that repairs it; audited once per wait. */
    @Transactional(propagation = Propagation.MANDATORY)
    void waiting(TriggerInfo trigger, WaitingReason reason) {
        if (trigger.waitingReason() == reason) {
            return;
        }
        store.waiting(trigger.companyId(), trigger.id(), reason, time.now());
        auditLog.record(AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_WAITING, CurrentActor.actor())
            .target(TARGET, trigger.id())
            .detail("reason", reason.name())
            .build());
        events.publishEvent(new TriggerEvents.TriggerWaiting(trigger.companyId(), trigger.id(), reason));
    }

    /** The wait was repaired: the trigger goes on by itself (§5.3). */
    @Transactional(propagation = Propagation.MANDATORY)
    void repaired(TriggerInfo trigger) {
        if (trigger.waitingReason() == null) {
            return;
        }
        store.waiting(trigger.companyId(), trigger.id(), null, time.now());
        events.publishEvent(new TriggerEvents.TriggerResumed(trigger.companyId(), trigger.id()));
    }

    /**
     * Resumes the locked, paused trigger whose gate passed (§5.11): {@code ACTIVE}, armed now (T16: what happened
     * while it was paused fires nothing), a schedule's next firing after now; its held write-back released; each open
     * conversation takes its new author's table and payer when the trigger was reassigned (§11,
     * {@code conversation_rebind}), then the turn that what waits asks for.
     *
     * @param nextRunAt a schedule's next firing after now; null for a tracker trigger
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<TriggerConversations.Rebound> resume(TriggerInfo trigger, @Nullable Instant nextRunAt, @Nullable UUID by) {
        return resume(trigger, nextRunAt, by, null);
    }

    /** As {@link #resume(TriggerInfo, Instant, UUID)}, the switch that resumed it on record ({@code system.resumed}). */
    @Transactional(propagation = Propagation.MANDATORY)
    List<TriggerConversations.Rebound> resume(TriggerInfo trigger, @Nullable Instant nextRunAt, @Nullable UUID by,
            @Nullable String cause) {
        Instant now = time.now();
        store.resume(trigger.companyId(), trigger.id(), trigger.source() == TriggerSource.SCHEDULE ? nextRunAt : null,
                now);
        List<UUID> open = conversationIds(trigger, true);
        store.releaseOutbox(trigger.companyId(), open, now);
        List<TriggerConversations.Rebound> rebound = new ArrayList<>();
        for (ConversationInfo conversation : conversations.openOf(trigger.companyId(), trigger.id())) {
            if (!Objects.equals(conversation.authorPrincipalId(), trigger.authorPrincipalId())
                    || !Objects.equals(conversation.payerPrincipalId(), trigger.payerPrincipalId())) {
                conversations.rebind(trigger.companyId(), conversation.id()).ifPresent(rebound::add);
            }
            conversations.event(trigger.companyId(), conversation.id());
        }
        AuditRecord.Builder record = AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_RESUMED,
                CurrentActor.actor())
            .target(TARGET, trigger.id())
            .detail("from", trigger.pausedReason() != null ? trigger.pausedReason().name() : null)
            .detail("by", by != null ? by.toString() : null)
            .detail("conversationsRebound", rebound.size());
        if (cause != null) {
            record.detail("cause", cause);
        }
        auditLog.record(record.build());
        events.publishEvent(new TriggerEvents.TriggerResumed(trigger.companyId(), trigger.id()));
        return rebound;
    }

    /**
     * Deletes the locked trigger (soft, §5.11): its open conversations end {@code TRIGGER_DELETED} (a running turn
     * finishes, a queued one is cancelled), its undelivered write-back is dropped; the name is free again.
     *
     * @param cause {@code USER}, {@code agent.deleted}, {@code system.deleted}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void delete(TriggerInfo trigger, String cause, @Nullable UUID by) {
        delete(trigger, cause, by, ConversationInfo.EndedReason.TRIGGER_DELETED);
    }

    /** As {@link #delete(TriggerInfo, String, UUID)}, its conversations ending for the reason. */
    @Transactional(propagation = Propagation.MANDATORY)
    void delete(TriggerInfo trigger, String cause, @Nullable UUID by, ConversationInfo.EndedReason ended) {
        if (trigger.deletedAt() != null) {
            return;
        }
        Instant now = time.now();
        store.delete(trigger.companyId(), trigger.id(), cause, by, now);
        List<UUID> open = conversationIds(trigger, true);
        store.dropOutbox(trigger.companyId(), open, "TRIGGER_DELETED", now);
        UUID endedBy = CurrentActor.user().map(user -> user.id()).orElse(null);
        for (UUID conversationId : open) {
            conversations.end(trigger.companyId(), conversationId, ended, endedBy);
        }
        auditLog.record(AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_DELETED, CurrentActor.actor())
            .target(TARGET, trigger.id())
            .detail("cause", cause)
            .detail("source", trigger.source().name())
            .detail("systemId", trigger.systemId() != null ? trigger.systemId().toString() : null)
            .detail("agentId", trigger.agentId().toString())
            .detail("conversationsEnded", open.size())
            .build());
        events.publishEvent(new TriggerEvents.TriggerDeleted(trigger.companyId(), trigger.id(), cause));
    }

    /**
     * The trigger's open conversations' ids, in the lock order. With {@code lock}, each is locked now, before their
     * outbox rows change: a transaction that locks them later anyway (a turn cancelled, a conversation rebound or ended)
     * takes them in the order of the global lock order (the trigger, its conversations, their outbox rows), the order
     * in which the write-back records a delivery.
     */
    private List<UUID> conversationIds(TriggerInfo trigger, boolean lock) {
        List<UUID> ids = conversations.openOf(trigger.companyId(), trigger.id()).stream().map(ConversationInfo::id)
            .toList();
        if (lock) {
            ids.forEach(id -> locks.lock(trigger.companyId(), id));
        }
        return ids;
    }

    /** The trigger, locked again after a change, as it is now. */
    Optional<TriggerInfo> reread(TriggerInfo trigger) {
        return store.find(trigger.companyId(), trigger.id());
    }
}
