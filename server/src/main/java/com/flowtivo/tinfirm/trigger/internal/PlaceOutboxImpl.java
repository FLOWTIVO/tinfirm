package com.flowtivo.tinfirm.trigger.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.trigger.PlaceOutbox;
import com.flowtivo.tinfirm.trigger.PlaceWriter;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

/**
 * {@link PlaceOutbox}: a place's module queues the rows its bot writes (the package 5 contract, §5.9), into package 4's
 * outbox, in the order of its conversation, held while the conversation's trigger is paused; a result its trigger
 * reviews waits in {@code outbox_review} until the review is approved (T14).
 */
@Component
class PlaceOutboxImpl implements PlaceOutbox {

    private final OutboxStore store;

    private final TriggerStore triggers;

    private final Conversations conversations;

    private final TriggerConversations triggerConversations;

    private final ConversationLock locks;

    private final OutboxDelivery delivery;

    private final TimeSource time;

    PlaceOutboxImpl(OutboxStore store, TriggerStore triggers, Conversations conversations,
            TriggerConversations triggerConversations, ConversationLock locks, OutboxDelivery delivery,
            TimeSource time) {
        this.store = store;
        this.triggers = triggers;
        this.conversations = conversations;
        this.triggerConversations = triggerConversations;
        this.locks = locks;
        this.delivery = delivery;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> queue(UUID companyId, UUID conversationId, Entry entry) {
        Target target = target(companyId, conversationId).orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        locks.lock(companyId, conversationId);
        Optional<UUID> queued = store.insert(companyId, conversationId, row(entry), target.held(), time.now());
        queued.ifPresent(id -> delivery.requestPassAfterCommit());
        return queued;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int queued(UUID companyId, UUID conversationId, String prefix) {
        return store.queuedWithPrefix(companyId, conversationId, prefix);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reviewsResults(UUID companyId, UUID conversationId) {
        return conversations.find(companyId, conversationId)
            .map(ConversationInfo::triggerId)
            .flatMap(triggerId -> triggers.find(companyId, triggerId))
            .filter(TriggerInfo::live)
            .map(trigger -> trigger.results().review())
            .orElse(false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID review(UUID companyId, UUID conversationId, UUID runId, String summary,
            List<TriggerConversations.ReviewWrite> writes, List<Entry> entries) {
        UUID decisionId = triggerConversations.raiseResultReview(companyId, conversationId, runId, summary, writes);
        store.insertReview(companyId, decisionId, conversationId, runId, entries.stream().map(PlaceOutboxImpl::row)
            .toList(), time.now());
        return decisionId;
    }

    /** A review's rows, approved: queued as {@link #queue} queues one (the review's own transaction). */
    void queueReviewed(UUID companyId, UUID conversationId, List<OutboxStore.NewRow> rows) {
        Target target = target(companyId, conversationId).orElse(null);
        if (target == null) {
            return;
        }
        locks.lock(companyId, conversationId);
        boolean queued = false;
        for (OutboxStore.NewRow row : rows) {
            queued |= store.insert(companyId, conversationId, row, target.held(), time.now()).isPresent();
        }
        if (queued) {
            delivery.requestPassAfterCommit();
        }
    }

    /** Whether the conversation's place has a writer, and whether its trigger holds its rows (paused). */
    private record Target(boolean held) {
    }

    private Optional<Target> target(UUID companyId, UUID conversationId) {
        ConversationInfo conversation = conversations.find(companyId, conversationId).orElse(null);
        if (conversation == null || !delivery.writes(conversation.place())) {
            return Optional.empty();
        }
        if (conversation.triggerId() == null) {
            return Optional.of(new Target(false));
        }
        return triggers.find(companyId, conversation.triggerId())
            .filter(TriggerInfo::live)
            .map(trigger -> new Target(trigger.paused()));
    }

    static OutboxStore.NewRow row(Entry entry) {
        return new OutboxStore.NewRow(entry.systemId(), null, entry.subjectKey(), entry.kind(), entry.cause(),
                entry.causeKey(), entry.runId(), entry.decisionId(), entry.outputId(), entry.attachmentId(),
                entry.messageId(), entry.body(), entry.value(), entry.url(), PlaceWriter.Writer.BOT,
                entry.targetCauseKey());
    }
}
