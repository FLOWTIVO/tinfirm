package com.flowtivo.tinfirm.trigger;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.system.CallCredential;

/**
 * Writes the write-back outbox's rows to one kind of place (the package 4 contract, §5.9; the package 5 contract, open
 * point 14): the delivery ({@code OutboxDelivery}) dispatches each row by its conversation's place to the writer a
 * module registers for it, a Spring bean of this type. Package 4 registers the tracker's ({@code TRACKER_ISSUE}: a
 * comment, a transition, labels, the close, an attachment, a remote link); package 5 adds the chat's
 * ({@code CHAT_THREAD}), whose rows its bot writes ({@link Writer#BOT}).
 * <p>
 * A writer runs <b>outside any database transaction</b> (CONVENTIONS §10), once per attempt of a row; it may read what
 * it needs in short transactions of its own. It answers an {@link Outcome}, never an exception: the delivery records
 * it (delivered, tried again later, waiting on its binding, dropped) in a transaction of its own.
 */
public interface PlaceWriter {

    /** The place whose rows it writes. */
    ConversationInfo.Place place();

    /**
     * Who writes its rows: {@link Writer#BINDING}, the conversation's binding on the row's system, whose token the
     * delivery hands it ({@code ConversationBindings.use}, purpose {@code write-back:{outboxId}}); or {@link Writer#BOT},
     * a writer that finds its own credential (package 5's chat bot), which gets none.
     */
    default Writer writer() {
        return Writer.BINDING;
    }

    /** Writes the row once. */
    Outcome write(Write write);

    /**
     * Before a repeated attempt of a row whose last attempt may have reached the system ({@link Row#ambiguous()}):
     * whether that attempt wrote it after all, as the system shows it (a comment with the row's marker or text, an
     * attachment of its name and size, an issue in the status already). Empty: it did not, or the writer cannot tell,
     * and the row is written again.
     */
    default java.util.Optional<Outcome.Delivered> written(Write write) {
        return java.util.Optional.empty();
    }

    /** {@code outbox.writer} (package 5): the conversation's binding, or the place's bot. */
    enum Writer {
        BINDING, BOT
    }

    /**
     * A row of the outbox, as the delivery claimed it.
     *
     * @param bindingId    the binding that writes it; null for a bot's row (package 5)
     * @param subjectKey   the place's subject, e.g. {@code SHOP-241}, {@code acme/shop#57}
     * @param body         a comment's Markdown
     * @param value        a transition's or a label's name, a link's title
     * @param url          a remote link's target
     * @param ambiguous    whether its last attempt may have reached the system
     * @param targetExternalId a {@code CHAT_UPDATE}'s: what the system answered for the post it changes (its
     *                     {@code external_id}); null while that row is undelivered, and for every other kind
     */
    record Row(UUID id, UUID companyId, UUID conversationId, UUID systemId, @Nullable UUID bindingId,
            String subjectKey, int seq, OutboxKind kind, OutboxCause cause, @Nullable UUID runId,
            @Nullable UUID decisionId, @Nullable UUID outputId, @Nullable UUID attachmentId, @Nullable UUID messageId,
            @Nullable String body, @Nullable String value, @Nullable String url, int attempts, boolean ambiguous,
            Instant createdAt, @Nullable String targetExternalId) {

        public Row {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /**
     * What one attempt writes, and with what.
     *
     * @param agentName  the conversation's agent, for the label {@code {agent} (Tinfirm agent):}
     * @param credential the binding's token for this attempt ({@link Writer#BINDING}); null for a bot's writer
     */
    record Write(Row row, ConversationInfo conversation, String agentName, @Nullable CallCredential credential) {

        @Override
        public String toString() {
            return "Write[row=" + row.id() + ", kind=" + row.kind() + ", credential=***]";
        }
    }

    /** How one attempt ended. */
    sealed interface Outcome {

        /**
         * Written.
         *
         * @param externalId  the system's id of what was written: a comment's, an attachment's, a link's
         * @param writtenKind {@code system_write}'s kind: {@code COMMENT}, {@code TRANSITION}, {@code LABEL},
         *                    {@code CLOSE}, {@code ATTACHMENT}, {@code REMOTE_LINK}
         * @param values      what the poller needs of it, as {@code SystemWritten} carries it: the status entered,
         *                    the labels added and removed
         */
        record Delivered(@Nullable String externalId, String writtenKind, ConversationEvents.WrittenValues values)
                implements Outcome {

            /** A write that set no status and no label: a comment, a close, an attachment, a link. */
            public Delivered(@Nullable String externalId, String writtenKind) {
                this(externalId, writtenKind, ConversationEvents.WrittenValues.NONE);
            }
        }

        /**
         * Not written now.
         *
         * @param retryable  whether trying again later may help: no answer, a {@code 5xx}, the rate limit
         * @param ambiguous  whether the request may have reached the system (a timeout, a connection lost after
         *                   sending), so that the next attempt looks first ({@link PlaceWriter#written})
         * @param retryAfter the wait the system asked for, if any
         */
        record Failed(String message, boolean retryable, boolean ambiguous, @Nullable Duration retryAfter)
                implements Outcome {
        }

        /** The system refused the credential ({@code 401}): the account must be connected again, and the row waits. */
        record Reconnect(String message) implements Outcome {
        }

        /**
         * Not written now, and no attempt: the writer waits for something outside the row (package 5: its chat
         * system's bot, missing, refused or suspended). Tried again after {@code retryIn}; dropped once it waited
         * {@code tinfirm.trigger.outbox.binding-wait} (7 days) since it was queued.
         */
        record Waiting(String message, Duration retryIn) implements Outcome {
        }

        /** It will never be written, for this reason. */
        record Dropped(String reason) implements Outcome {
        }
    }
}
