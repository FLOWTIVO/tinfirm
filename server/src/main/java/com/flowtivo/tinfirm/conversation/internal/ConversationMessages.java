package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * For parts 2 and 3, implemented by part 1: posts a message in the conversation's thread (the contract's §3.10), in the
 * caller's transaction. A question's or an approval's card is a message with its {@code decisionId}; an answer through
 * a card is a {@code PERSON} message quoting the question's first line.
 */
public interface ConversationMessages {

    /** What is said: by the person, by the agent, or by Tinfirm. */
    enum Kind {
        PERSON, AGENT, SYSTEM
    }

    /**
     * @param authorPrincipalId the person, for a {@code PERSON} message; null otherwise
     * @param runId             the turn that produced an {@code AGENT} or {@code SYSTEM} message (a {@code PERSON}
     *                          message without one waits for the next turn)
     * @return the message's id
     */
    UUID post(UUID companyId, UUID conversationId, Kind kind, @Nullable UUID authorPrincipalId, String body,
            @Nullable UUID runId, @Nullable UUID decisionId);
}
