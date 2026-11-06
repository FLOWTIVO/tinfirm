package com.flowtivo.tinfirm.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Decide from the place (the package 5 contract, §2.2 and §5.8): a question's answer or an approval's press in a
 * chat, with what a card shows. Runs in the caller's transaction and tenant context, under the conversation's lock.
 * An approval is decided only by who may decide it now (in a personal conversation its person; package 5b adds a
 * trigger's team), with scope {@code CONVERSATION} and the agent's access; a question by whoever the caller vouches
 * for, here its person too (C10). The decision records {@code decided_via} {@code CHAT}, the chat user and their
 * name; text never approves.
 */
public interface PlaceDecisions {

    /**
     * What a card shows.
     *
     * @param kind    {@code QUESTION} or {@code APPROVAL}
     * @param status  {@code OPEN}, {@code DECIDED}, {@code CANCELLED}
     * @param what    an approval's call in words ({@code comment on SHOP-241}), its repository or host
     * @param outcome {@code ANSWERED}, {@code APPROVED}, {@code DENIED}; null while open
     * @param subject an approval's subject: {@code TOOL_CALL}, {@code REPOSITORY}, {@code HOST},
     *                {@code CHANGE_REQUEST}, or {@code RESULT} (the review of a trigger's result, decided in the app
     *                only); null for a question
     * @param decidedByPrincipalId the member who decided; null while open, and for a chat user who is not linked
     * @param approverGroupId      a trigger's approval: the team that decides it; null for its author or a person
     */
    record Card(UUID decisionId, UUID conversationId, String kind, String status, @Nullable String question,
            List<String> options, @Nullable String what, @Nullable String reason, @Nullable String outcome,
            @Nullable String answer, @Nullable String decidedByName, @Nullable String decidedVia,
            @Nullable String subject, @Nullable UUID decidedByPrincipalId, @Nullable UUID approverGroupId) {

        /** The review of a result: the app's only (the package 5 contract, §5.8). */
        public boolean review() {
            return "RESULT".equals(subject);
        }
    }

    Optional<Card> find(UUID companyId, UUID decisionId);

    /**
     * Who decides from the place.
     *
     * @param principalId the member linked to the chat user; null for one who is not linked
     * @param name        their name in the chat
     * @param via         {@code SLACK}, {@code TEAMS}
     * @param externalId  the chat user's id
     * @param messageId   the message that answered, for an answer by a message
     */
    record PlaceDecider(@Nullable UUID principalId, String name, String via, String externalId,
            @Nullable UUID messageId) {
    }

    /** A press or an answer. */
    sealed interface Choice permits Answer, Approve, Deny {
    }

    /** A question's option (one of its own, as pressed) or a text. */
    record Answer(@Nullable String option, @Nullable String text) implements Choice {
    }

    record Approve() implements Choice {
    }

    record Deny() implements Choice {
    }

    /** What came of it. */
    sealed interface Outcome permits Decided, NotAllowed, AlreadyDecided, Refused {
    }

    record Decided(UUID decisionId, String outcome) implements Outcome {
    }

    /** The presser may not decide it now; it stays open (C10). */
    record NotAllowed(String why) implements Outcome {
    }

    /** It was decided or cancelled already, by whom and where. */
    record AlreadyDecided(@Nullable String name, @Nullable String via, String status) implements Outcome {
    }

    /** It cannot be decided so now (a paused agent's approval, an option not the question's, an ended conversation). */
    record Refused(String code, String message) implements Outcome {
    }

    Outcome decide(UUID companyId, UUID decisionId, PlaceDecider decider, Choice choice);
}
