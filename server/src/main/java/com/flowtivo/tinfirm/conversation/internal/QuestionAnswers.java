package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import com.flowtivo.tinfirm.conversation.ConversationInfo;

/**
 * Seam for part 2: a person's message with {@code answersOpenQuestions} answers every open question of the conversation
 * (ADR-0033, the contract's §5.7), in the transaction that stored the message, before part 1 decides whether the
 * message starts a turn: a turn starts ({@code DECIDED} when it answered any, else {@code MESSAGE}) only when no
 * decision of the conversation is open afterwards; a plain message while decisions are open waits for the next turn.
 * Part 2 implements it as a bean; without one, {@link #NONE} answers nothing.
 */
public interface QuestionAnswers {

    QuestionAnswers NONE = (conversation, messageId, body, personPrincipalId) -> 0;

    /**
     * @param messageId the person's message, which is the answer
     * @return how many questions it answered
     */
    int answerAll(ConversationInfo conversation, UUID messageId, String body, UUID personPrincipalId);

    /**
     * A message of the place that answers every open question (package 5: a mention in a channel's thread), by the
     * member linked to its author if any, with the author's name at the place.
     */
    default int answerAll(ConversationInfo conversation, UUID messageId, String body,
            @org.jspecify.annotations.Nullable UUID principalId,
            @org.jspecify.annotations.Nullable String decidedByName) {
        return answerAll(conversation, messageId, body, principalId);
    }
}
