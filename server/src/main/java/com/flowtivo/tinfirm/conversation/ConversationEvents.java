package com.flowtivo.tinfirm.conversation;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.RunnerMessages;

/**
 * The conversation module's domain events (the package 3 contract, §5.3, §5.7), ids only, published with Spring's
 * {@code ApplicationEventPublisher} in the transaction that made the change, after it records its audit event, which
 * the audit log appends when the transaction commits (CONVENTIONS §8): a listener reacts after the commit
 * ({@code @TransactionalEventListener}) or inside the transaction ({@code @EventListener}).
 */
public final class ConversationEvents {

    private ConversationEvents() {
    }

    /** A person started a conversation; its first turn is queued. */
    public record ConversationStarted(UUID companyId, UUID conversationId, UUID agentId, UUID personPrincipalId) {
    }

    /** The conversation ended, for good. */
    public record ConversationEnded(UUID companyId, UUID conversationId, ConversationInfo.EndedReason reason) {
    }

    /** A binding of the conversation became {@code BOUND}: its parked calls may run (part 2). */
    public record BindingBound(UUID companyId, UUID conversationId, UUID bindingId, UUID systemId) {
    }

    /** A binding of the conversation waits for the person or an admin, with its reason. */
    public record BindingWaiting(UUID companyId, UUID conversationId, UUID bindingId, UUID systemId,
            BindingInfo.Reason reason) {
    }

    /** A binding of the conversation is gone for good, with its reason. */
    public record BindingGone(UUID companyId, UUID conversationId, UUID bindingId, UUID systemId,
            BindingInfo.Reason reason) {
    }

    /**
     * The conversation wrote to a system (the package 3 contract, §3.14; the package 4 contract, §2.2 and open point
     * 13): one event per write, so that package 4's poller raises no event for Tinfirm's own writes, which the trigger
     * module records in {@code system_write} inside the transaction ({@code @EventListener}). Published in the
     * transaction that records the write: by a tool call's record, after its {@code tool.called}; by the opening of a
     * change request ({@code CHANGE_REQUEST}); by the end of a turn for each branch it pushed ({@code PUSH}); by the
     * trigger module's write-back for each row it delivered (with its {@code outboxId}). The trigger module's recorder
     * is the one writer of {@code system_write}.
     *
     * @param what       the kind of the write and its subject's key, e.g. {@code COMMENT SHOP-241}
     * @param externalId the system's id of what was written: a comment's id, a change request's number, a pushed head
     *                   commit; null where there is none
     * @param kind       {@code COMMENT}, {@code TRANSITION}, {@code LABEL}, {@code CLOSE}, {@code ATTACHMENT},
     *                   {@code REMOTE_LINK}, {@code CHANGE_REQUEST}, {@code PUSH} or {@code ISSUE}, as
     *                   {@code system_write.kind}
     * @param subjectKey the subject's key at the system: {@code SHOP-241}, {@code owner/repo#57}, {@code group/path!12};
     *                   a push's {@code {repository}@{branch}}
     * @param values     what the write set: the status entered, the labels added and removed; a change request's
     *                   link, title and output, which part c's write-back links from a Jira issue
     * @param outboxId   the trigger module's outbox row that wrote it (part c's write-back); null for a tool's call,
     *                   a change request and a push
     */
    public record SystemWritten(UUID companyId, UUID conversationId, UUID systemId, String what,
            @Nullable String externalId, String kind, String subjectKey, WrittenValues values,
            @Nullable UUID outboxId) {

        /** A write of a conversation's own: a tool's call, a change request, a push. */
        public SystemWritten(UUID companyId, UUID conversationId, UUID systemId, String what,
                @Nullable String externalId, String kind, String subjectKey, WrittenValues values) {
            this(companyId, conversationId, systemId, what, externalId, kind, subjectKey, values, null);
        }

        /** A write without values, of the kind and key {@code what} names ({@code COMMENT SHOP-241}). */
        public SystemWritten(UUID companyId, UUID conversationId, UUID systemId, String what,
                @Nullable String externalId) {
            this(companyId, conversationId, systemId, what, externalId, kindOf(what), keyOf(what),
                    WrittenValues.NONE, null);
        }

        private static String kindOf(String what) {
            String kind = what.contains(" ") ? what.substring(0, what.indexOf(' ')) : what;
            return "LABELS".equals(kind) ? "LABEL" : kind;
        }

        private static String keyOf(String what) {
            return what.contains(" ") ? what.substring(what.indexOf(' ') + 1) : what;
        }
    }

    /**
     * What a write set at the system ({@link SystemWritten}).
     *
     * @param status        the status a transition entered, when known
     * @param labelsAdded   the labels added
     * @param labelsRemoved the labels removed
     * @param url           a change request's link ({@code CHANGE_REQUEST}); else null
     * @param title         a change request's title ({@code CHANGE_REQUEST}); else null
     * @param outputId      the conversation's output a change request is ({@code CHANGE_REQUEST}); else null
     */
    public record WrittenValues(@Nullable String status, java.util.List<String> labelsAdded,
            java.util.List<String> labelsRemoved, @Nullable String url, @Nullable String title,
            @Nullable UUID outputId) {

        public static final WrittenValues NONE = new WrittenValues(null, java.util.List.of(), java.util.List.of());

        public WrittenValues {
            labelsAdded = java.util.List.copyOf(labelsAdded);
            labelsRemoved = java.util.List.copyOf(labelsRemoved);
        }

        /** What a tracker's write set: a status, labels. */
        public WrittenValues(@Nullable String status, java.util.List<String> labelsAdded,
                java.util.List<String> labelsRemoved) {
            this(status, labelsAdded, labelsRemoved, null, null, null);
        }

        /** A change request the conversation opened: its link, title and output. */
        public static WrittenValues changeRequest(String url, @Nullable String title, @Nullable UUID outputId) {
            return new WrittenValues(null, java.util.List.of(), java.util.List.of(), url, title, outputId);
        }
    }

    /**
     * A turn of the conversation was recorded (the package 4 contract, §2.2 and open point 14): published by
     * {@code TurnEnded.turnEnded} after the hooks of parts 2 and 3, in the transaction that ended the run, for every
     * conversation. The {@code trigger} module fills its outbox from it inside the transaction ({@code @EventListener}); a
     * chat's direct message gets its answers and failures from it after the commit (the package 5 contract, §5.9).
     *
     * @param place     where the conversation is held
     * @param triggerId the conversation's trigger; null for a personal conversation
     * @param status    the run's end: {@code SUCCEEDED}, {@code FAILED}, {@code CANCELLED} or {@code LOST}
     * @param outcome   the agent's result, {@code COMPLETED} or {@code BLOCKED}; null without one
     * @param retrying  a failed turn that is retried by itself (ADR-0073): its place hears nothing of the failure until
     *                  the automatic retries are spent
     */
    public record TurnRecorded(UUID companyId, UUID conversationId, UUID runId, ConversationInfo.Place place,
            @Nullable UUID triggerId, RunStatus status, RunnerMessages.@Nullable ResultOutcome outcome,
            boolean retrying) {
    }

    /**
     * A decision of the conversation was decided (package 4: an answer in Tinfirm is written to the issue, §5.8), in
     * the deciding transaction, after its {@code decision.decided}.
     *
     * @param kind                 {@code QUESTION} or {@code APPROVAL}
     * @param outcome              {@code ANSWERED}, {@code APPROVED} or {@code DENIED}
     * @param decidedByPrincipalId the member who decided; null for an answer from the place by someone who is not one
     * @param decidedByName        an answer from the place: the commenter's name at the system; null in Tinfirm
     * @param messageId            the message that answered, a {@code PERSON} one in Tinfirm or an {@code EXTERNAL}
     *                             one from the place; null for an approval
     */
    public record DecisionDecided(UUID companyId, UUID conversationId, UUID decisionId, String kind, String outcome,
            @Nullable UUID decidedByPrincipalId, @Nullable String decidedByName, @Nullable UUID messageId) {
    }

    /**
     * The team decided the review of a turn's result (package 4, §5.8, a decision of subject {@code RESULT}), in the
     * deciding transaction: {@code approved} queues its writes (T14); a denial with its note started a
     * {@code REVIEW_RETURNED} turn.
     */
    public record ResultReviewed(UUID companyId, UUID conversationId, UUID decisionId, UUID runId, boolean approved) {
    }

    /**
     * A reassigned trigger's waiting conversation took the trigger's new account table and payer
     * ({@code conversation_rebind}, the package 4 contract §11), audited as {@code conversation.rebound} and posted.
     *
     * @param note the {@code SYSTEM} message posted ("From now on, {new author}'s choices act and pay: …"), which a
     *             chat's thread gets too (the package 5 contract, §5.14)
     */
    public record ConversationRebound(UUID companyId, UUID conversationId, UUID triggerId, String note) {
    }

    /**
     * A question or an approval was raised in the conversation (the package 5 contract, §2.2, §5.8), in the raising
     * transaction after its {@code decision.created}: a chat posts its card.
     *
     * @param kind {@code QUESTION} or {@code APPROVAL}
     */
    public record DecisionRaised(UUID companyId, UUID conversationId, UUID decisionId, String kind) {
    }

    /**
     * A decision was decided or cancelled (the package 5 contract, §2.2, §5.8), in that transaction after it records
     * its audit event: a chat updates its card, and posts an answer given in the app.
     *
     * @param outcome         {@code ANSWERED}, {@code APPROVED}, {@code DENIED}, or {@code CANCELLED}
     * @param via             {@code APP} or {@code CHAT}
     * @param answerMessageId the message that answered a question; null otherwise
     */
    public record DecisionClosed(UUID companyId, UUID conversationId, UUID decisionId, String outcome, String via,
            @Nullable UUID answerMessageId) {
    }
}
