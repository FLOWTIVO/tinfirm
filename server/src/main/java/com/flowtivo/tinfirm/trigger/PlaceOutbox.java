package com.flowtivo.tinfirm.trigger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.conversation.TriggerConversations;

/**
 * The write-back outbox for a module that owns a place and its writer (the package 5 contract, §5.9, open point 14):
 * the {@code chat} module queues the rows of every {@code CHAT_THREAD} conversation, personal ones and a channel
 * trigger's, in the transaction that commits their cause, and {@code OutboxDelivery} delivers them in order per
 * conversation with the place's writer ({@link PlaceWriter.Writer#BOT}: its bot). Every method runs in the caller's
 * transaction (a mandatory one) and tenant context, and locks the conversation before its rows (CONVENTIONS §10).
 */
public interface PlaceOutbox {

    /**
     * A row to queue, which the place's bot writes.
     *
     * @param subjectKey     the place's name for the row's audit and record, e.g. a chat thread's
     *                       {@code {channelId}:{threadId}}
     * @param causeKey       a cause queued twice queues once (T13), e.g. {@code RESULT:{runId}}
     * @param value          a {@code CHAT_REACTION}'s message id at the place
     * @param url            a card's link into Tinfirm
     * @param targetCauseKey a {@code CHAT_UPDATE}'s: the cause key of the conversation's earlier post it changes
     */
    record Entry(UUID systemId, String subjectKey, OutboxKind kind, OutboxCause cause, String causeKey,
            @Nullable UUID runId, @Nullable UUID decisionId, @Nullable UUID outputId, @Nullable UUID attachmentId,
            @Nullable UUID messageId, @Nullable String body, @Nullable String value, @Nullable String url,
            @Nullable String targetCauseKey) {

        public Entry {
            Objects.requireNonNull(systemId, "systemId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(causeKey, "causeKey");
            if (!kind.chat()) {
                throw new IllegalArgumentException("A place's bot writes the chat's kinds only, not " + kind);
            }
        }

        /** A post. */
        public static Entry post(UUID systemId, String subjectKey, OutboxCause cause, String causeKey, String body) {
            return new Entry(systemId, subjectKey, OutboxKind.CHAT_POST, cause, causeKey, null, null, null, null,
                    null, body, null, null, null);
        }

        public Entry withRun(@Nullable UUID run) {
            return new Entry(systemId, subjectKey, kind, cause, causeKey, run, decisionId, outputId, attachmentId,
                    messageId, body, value, url, targetCauseKey);
        }

        public Entry withDecision(@Nullable UUID decision) {
            return new Entry(systemId, subjectKey, kind, cause, causeKey, runId, decision, outputId, attachmentId,
                    messageId, body, value, url, targetCauseKey);
        }

        public Entry withUrl(@Nullable String link) {
            return new Entry(systemId, subjectKey, kind, cause, causeKey, runId, decisionId, outputId, attachmentId,
                    messageId, body, value, link, targetCauseKey);
        }
    }

    /**
     * Queues the row after the conversation's last, held while its trigger is paused, and delivers it after the
     * commit; nothing when its cause was queued before (T13), or when no writer writes to the conversation's place.
     * Answers the row, if queued.
     */
    Optional<UUID> queue(UUID companyId, UUID conversationId, Entry entry);

    /**
     * How many rows of the conversation have a cause key that starts with {@code prefix}: whether a post was queued,
     * which an update would name, and how many of its kind.
     */
    int queued(UUID companyId, UUID conversationId, String prefix);

    /**
     * Whether a turn's result waits for the review of the conversation's trigger before it is written (package 4,
     * {@code results.review}, T14); false for a personal conversation.
     */
    boolean reviewsResults(UUID companyId, UUID conversationId);

    /**
     * The review of a turn's result instead of its rows (T14): raised now for the trigger's team, or its author without
     * one, with exactly what would be written; the rows are queued once it is approved, and go when it is returned or
     * the conversation ends. Answers the review's decision.
     *
     * @param writes what the review shows would be written: each entry's kind, target and words, in order
     */
    UUID review(UUID companyId, UUID conversationId, UUID runId, String summary,
            List<TriggerConversations.ReviewWrite> writes, List<Entry> entries);
}
