package com.flowtivo.tinfirm.conversation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.runner.RuntimeKind;

/**
 * A trigger's conversations, for the {@code trigger} module (the package 4 contract, §5.6 to §5.11 and §11): it starts
 * them for a firing, continues them with {@code EVENT} turns, stores the comments of their place, ends them, pauses
 * their turns, raises the review of a result and rebinds them after a reassignment. Every method runs in the caller's
 * transaction (a mandatory one) and tenant context, and follows the global lock order (CONVENTIONS §10): the caller
 * holds the trigger's row, if any, and the method locks the conversation, then its runs, bindings and decisions; the
 * caller audits after it.
 */
public interface TriggerConversations {

    /**
     * {@code TriggerConversations.start} (§5.7): a new conversation of the trigger, {@code OPEN}, with one
     * {@code FIXED}, {@code BOUND} binding per account of the table (T5), and its {@code FIRST} turn, which takes the
     * firings that wait for it ({@link TriggerTurns}). Audited as {@code conversation.started} (with the trigger, the
     * author, the firing and the depth) and {@code conversation.binding.bound} ({@code how FIXED}), announced as
     * {@code ConversationStarted}. A second open conversation of the trigger on the subject is refused by the database
     * (T11, {@code conversation_trigger_subject_open_uq}); the caller looks first ({@link #openOnSubject}).
     */
    Started start(NewTriggerConversation conversation);

    /**
     * A firing of the trigger on its open conversation (§5.6, {@code CONTINUED}): an {@code EVENT} turn when no run is
     * active and no decision is open; else the firing waits ({@link TriggerTurns#waiting}) and the next turn takes it,
     * as a waiting message is taken. Answers the run started, if any.
     */
    Optional<UUID> event(UUID companyId, UUID conversationId);

    /**
     * A comment on the conversation's issue (§3.6, §5.8): an {@code EXTERNAL} message, once per comment
     * ({@code message_external_uq}; a comment stored before answers what it did then). When {@code answers} and
     * questions are open, it answers every open question (ADR-0033), with {@code decided_by_name}, the member whose
     * account has the commenter's identity and the message, and the {@code DECIDED} turn follows once no decision is
     * open; approvals are never decided by a comment (T12). Otherwise the next turn reads it in
     * {@code # Conversation so far}, and it wakes nothing.
     */
    External external(UUID companyId, UUID conversationId, ExternalComment comment);

    /**
     * Ends the conversation (§5.7, §5.10, §5.11): {@code SUBJECT_CLOSED}, {@code SUPERSEDED}, {@code TRIGGER_DELETED}
     * or {@code USER}. Waiting messages stay unanswered, open decisions and parked calls are cancelled, a running turn
     * finishes and a queued one is cancelled. Audited as {@code conversation.ended}, announced as
     * {@code ConversationEnded}. An ended conversation is left as it is.
     *
     * @param endedBy the user who ended it, or null for the system
     */
    void end(UUID companyId, UUID conversationId, ConversationInfo.EndedReason reason, @Nullable UUID endedBy);

    /**
     * Cancels the conversation's active turn ({@code stopRunning} of a pause, an author who left, §5.11 and §11): a
     * queued run ends at once, a running one through its host; its open decisions and the outbox stay. Answers the run
     * cancelled, if any.
     */
    Optional<UUID> cancelTurn(UUID companyId, UUID conversationId);

    /** A {@code SYSTEM} message in the conversation, e.g. a chain that stopped or a write-back dropped. */
    UUID post(UUID companyId, UUID conversationId, String body);

    /** The trigger's open conversation on the subject ({@code TRACKER_ISSUE}, T11), by the system's stable id. */
    Optional<ConversationInfo> openOnSubject(UUID companyId, UUID triggerId, String subjectId);

    /**
     * The trigger's open conversations held in its webhook's place ({@code WEBHOOK}, the package 5 contract, §5.12) on
     * the mapped subject key (at most one, {@code conversation_trigger_webhook_open_uq}); with a null key, those held
     * without one. In the order of their ids.
     */
    List<ConversationInfo> openOnWebhook(UUID companyId, UUID triggerId, @Nullable String subjectKey);

    /** The trigger's open conversations, in the order of their ids (the lock order). */
    List<ConversationInfo> openOf(UUID companyId, UUID triggerId);

    /** The ids of all the trigger's conversations, open or ended, in the order of their ids (e.g. its outbox's). */
    List<UUID> allOf(UUID companyId, UUID triggerId);

    /**
     * Whether the conversation is busy (§5.10, a schedule's overlap {@code SKIP}): an active run, an open decision or a
     * waiting binding.
     */
    boolean busy(UUID companyId, UUID conversationId);

    /**
     * The review of a turn's result (§5.8, T14): an approval {@code {type: RESULT, runId, summary, writes}} for the
     * trigger's team, or its author without one, raised by SYSTEM with its card. {@code APPROVED} publishes
     * {@code ResultReviewed} with {@code approved}; {@code DENIED} needs a note and starts a {@code REVIEW_RETURNED}
     * turn with it. Answers the decision.
     *
     * @param writes exactly what would be written to the place, in order: the comment's Markdown, then each action, each
     *               with its kind and target
     */
    UUID raiseResultReview(UUID companyId, UUID conversationId, UUID runId, String summary, List<ReviewWrite> writes);

    /**
     * One write a result's review shows: what the outbox would write, as its row's kind names it, where, and the same
     * in words.
     *
     * @param kind   the outbox row's kind: {@code COMMENT}, {@code TRANSITION}, {@code LABEL_ADD}, {@code LABEL_REMOVE},
     *               {@code CLOSE}, {@code ATTACHMENT}, {@code REMOTE_LINK}, {@code CHAT_POST}, {@code CHAT_FILE}; null
     *               for a review stored with its words only
     * @param target the subject it is written to: the issue's or change request's key, the chat thread's reference
     * @param value  what it names: a transition's or a label's name, a document's title, a link's address; null for a
     *               comment, a post and a close
     * @param text   the write in words, a comment's or a post's Markdown in full
     */
    record ReviewWrite(@Nullable String kind, @Nullable String target, @Nullable String value, String text) {

        public ReviewWrite {
            Objects.requireNonNull(text, "text");
        }
    }

    /** The questions a turn raised that are still open, oldest first (the {@code QUESTION} comment of §5.8). */
    List<Question> openQuestionsOf(UUID companyId, UUID runId);

    /**
     * What a turn produced (§5.9, the {@code RESULT} comment and {@code ATTACH_DOCUMENTS}): its change requests with
     * their links, its documents and its links, oldest first.
     */
    List<RunOutput> outputsOf(UUID companyId, UUID runId);

    /**
     * An answered question (§5.8, the {@code ANSWER} comment of an answer given in Tinfirm): the answer, who gave it,
     * and every question of the conversation the same message answered (only this one when it was answered on its
     * own). Empty for a decision that is not an answered question.
     */
    Optional<Answer> answerOf(UUID companyId, UUID decisionId);

    /** A document of the conversation with its content, for the write-back's {@code ATTACHMENT} (§5.9). */
    Optional<Document> document(UUID companyId, UUID conversationId, UUID attachmentId);

    /**
     * A trigger's team changed (§3.8, §5.11): the open approvals of its conversations move to the new team, or to its
     * author without one, each audited as {@code decision.approver_changed}.
     */
    void approversChanged(UUID companyId, UUID triggerId, @Nullable UUID teamGroupId, UUID authorPrincipalId);

    /**
     * After a reassigned trigger was resumed (§11, open point 6): the conversation takes the trigger's new account table,
     * payer and model access through {@code conversation_rebind}, the one exception to T7 and to package 3's I6, I7 and
     * I10; audited as {@code conversation.rebound}, posted as a {@code SYSTEM} message ("From now on, {new author}'s
     * choices act and pay: …") and announced as {@code ConversationRebound}. A conversation with an active run, or that
     * already acts for the trigger's author with its payer, is left as it is (empty).
     */
    Optional<Rebound> rebind(UUID companyId, UUID conversationId);

    /**
     * What a trigger's new conversation is started with (§5.7). The trigger module builds it from the trigger at this
     * moment: its agent, author and payer, its settings' runtime and model access, its table and the workspace.
     *
     * @param place      {@code TRACKER_ISSUE} with {@code subject}, {@code SCHEDULE} with {@code schedule}, or a
     *                   place package 5 adds with {@code held}: a channel's chat thread ({@code CHAT_THREAD}) or a
     *                   webhook's subject ({@code WEBHOOK})
     * @param accounts   one per row of the table with an account (T5); a system without a row is unavailable
     * @param workspace  the settings' default repositories, else every repository the agent lists, leaving out those of
     *                   systems without a row
     * @param chainDepth 0, or one more than the creating conversation's (§5.6)
     * @param firingId   the firing that starts it, for the audit
     * @param startedBy  the user of a run now, or null for the system
     */
    record NewTriggerConversation(UUID companyId, UUID triggerId, String triggerName, UUID agentId,
            UUID authorPrincipalId, UUID payerPrincipalId, ModelAccessChoice modelAccess, RuntimeKind runtime,
            ConversationInfo.Place place, @Nullable TrackerSubject subject, @Nullable ScheduleFiring schedule,
            List<FixedAccount> accounts, List<ConversationInfo.WorkspaceEntry> workspace, int chainDepth,
            @Nullable UUID firingId, @Nullable UUID startedBy, @Nullable HeldPlace held) {

        public NewTriggerConversation {
            Objects.requireNonNull(companyId, "companyId");
            Objects.requireNonNull(triggerId, "triggerId");
            Objects.requireNonNull(modelAccess, "modelAccess");
            boolean valid = switch (place) {
                case TRACKER_ISSUE -> subject != null && schedule == null && held == null;
                case SCHEDULE -> schedule != null && subject == null && held == null;
                case CHAT_THREAD, WEBHOOK -> held != null && subject == null && schedule == null;
                default -> false;
            };
            if (!valid) {
                throw new IllegalArgumentException("A trigger's conversation is on a TRACKER_ISSUE with its subject, "
                        + "a SCHEDULE's firing, or a chat thread's or a webhook's place");
            }
            if (chainDepth < 0 || chainDepth > 3) {
                throw new IllegalArgumentException("A chain is at most 3 deep");
            }
            accounts = List.copyOf(accounts);
            workspace = List.copyOf(workspace);
        }
    }

    /**
     * The issue or change request of a {@code TRACKER_ISSUE} conversation ({@code place_ref}).
     *
     * @param subjectId the system's stable id (Jira's issue id, GitHub's and GitLab's global id): a moved issue stays one
     *                  place
     * @param kind      {@code ISSUE} or {@code CHANGE_REQUEST}
     */
    record TrackerSubject(UUID systemId, String subjectId, String key, String kind, @Nullable String url,
            @Nullable String title) {
    }

    /** A schedule's firing ({@code place_ref}): its due time, and its date in the schedule's zone for the title. */
    record ScheduleFiring(@Nullable UUID firingId, Instant dueAt, LocalDate localDate) {
    }

    /**
     * Where a package 5 place holds a trigger's conversation: its {@code place_ref} (a channel's chat thread
     * {@code {systemId, kind CHANNEL, teamId, channelId, threadId, serviceUrl}}; a webhook's
     * {@code {systemId, deliveryId, subjectKey}}) and the title.
     */
    record HeldPlace(Map<String, String> placeRef, String title) {

        public HeldPlace {
            placeRef = Map.copyOf(placeRef);
            Objects.requireNonNull(title, "title");
        }
    }

    /** A row of the trigger's account table with its account, as the conversation binds it ({@code FIXED}). */
    record FixedAccount(UUID systemId, UUID accountId, UUID holderPrincipalId, @Nullable String externalId) {
    }

    /** What {@link #start} answers: the conversation and its first turn's run (empty when it could not start one). */
    record Started(ConversationInfo conversation, Optional<UUID> runId) {
    }

    /**
     * A comment on the issue (§3.6).
     *
     * @param externalId        the comment's id at the system
     * @param author            {@code id}, {@code login}, {@code name} at the system
     * @param authorPrincipalId the member whose account on the system has that identity (package 1's I2), else null
     * @param body              the comment in Markdown ({@code JiraMarkup}, {@code Adf}), cut to 100 000 characters
     * @param answers           whether it answers the open questions: the next new comment while questions are open
     */
    record ExternalComment(String externalId, Map<String, String> author, @Nullable UUID authorPrincipalId,
            String body, Instant at, boolean answers) {

        public ExternalComment {
            Objects.requireNonNull(externalId, "externalId");
            author = Map.copyOf(author);
        }
    }

    /**
     * What {@link #external} did.
     *
     * @param created  false when the comment was stored before
     * @param answered the questions it answered
     * @param runId    the {@code DECIDED} turn it started, if any
     */
    record External(UUID messageId, boolean created, int answered, Optional<UUID> runId) {
    }

    /** An open question of a turn. */
    record Question(UUID decisionId, String question, List<String> options) {

        public Question {
            options = List.copyOf(options);
        }
    }

    /**
     * An output of a turn.
     *
     * @param kind         {@code CHANGE_REQUEST}, {@code DOCUMENT} or {@code LINK}
     * @param url          a change request's or a link's
     * @param attachmentId a document's file
     */
    record RunOutput(UUID id, String kind, String title, @Nullable String url, @Nullable UUID attachmentId) {
    }

    /**
     * An answered question.
     *
     * @param questions         the questions the answer's message answered, oldest first; this one alone without one
     * @param answer            the answer's text, or the option chosen
     * @param byPrincipalId     the member who answered; null for an answer from the place by someone who is not one
     * @param messageId         the message that answered; null for an answer given to this question alone
     */
    record Answer(UUID decisionId, List<String> questions, String answer, @Nullable UUID byPrincipalId,
            @Nullable String byName, @Nullable UUID messageId) {

        public Answer {
            questions = List.copyOf(questions);
        }
    }

    /** A file of the conversation with its content. Never logged with it. */
    record Document(UUID attachmentId, String fileName, String contentType, long sizeBytes, byte[] content) {

        @Override
        public String toString() {
            return "Document[" + attachmentId + ", " + fileName + ", " + sizeBytes + " bytes]";
        }
    }

    /**
     * What {@link #rebind} changed: per system the former and the new holder, account and grant, and the former and the
     * new author, payer and model access, as {@code conversation.rebound} records them.
     */
    record Rebound(UUID conversationId, UUID fromAuthor, UUID toAuthor, UUID fromPayer, UUID toPayer,
            String fromModelAccess, String toModelAccess, List<Map<String, @Nullable Object>> systems) {

        public Rebound {
            systems = List.copyOf(systems);
        }
    }
}
