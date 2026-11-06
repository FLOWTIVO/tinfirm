package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * {@link ConversationMessages} (the package 3 contract, §3.10): what is said in a Tinfirm thread, in the caller's
 * transaction. A body is cut to 100 000 characters; the conversation's activity moves with it.
 */
@Component
class ConversationMessagesImpl implements ConversationMessages {

    static final int MAX_BODY = 100_000;

    private final ConversationStore store;

    private final TimeSource time;

    ConversationMessagesImpl(ConversationStore store, TimeSource time) {
        this.store = store;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(UUID companyId, UUID conversationId, Kind kind, @Nullable UUID authorPrincipalId, String body,
            @Nullable UUID runId, @Nullable UUID decisionId) {
        return insert(companyId, conversationId, kind, authorPrincipalId, body, false, runId, decisionId);
    }

    /** A person's message, which waits for the next turn. */
    UUID person(UUID companyId, UUID conversationId, UUID authorPrincipalId, String body, boolean answersOpenQuestions) {
        return insert(companyId, conversationId, Kind.PERSON, authorPrincipalId, body, answersOpenQuestions, null,
                null);
    }

    /** Tinfirm's note in the thread. */
    UUID system(UUID companyId, UUID conversationId, String body, @Nullable UUID runId) {
        return insert(companyId, conversationId, Kind.SYSTEM, null, body, false, runId, null);
    }

    /** The agent's message: a turn's summary. */
    UUID agent(UUID companyId, UUID conversationId, String body, UUID runId) {
        return insert(companyId, conversationId, Kind.AGENT, null, body, false, runId, null);
    }

    private UUID insert(UUID companyId, UUID conversationId, Kind kind, @Nullable UUID authorPrincipalId, String body,
            boolean answersOpenQuestions, @Nullable UUID runId, @Nullable UUID decisionId) {
        String text = body.isBlank() ? "(empty)" : ConversationStore.cut(body, MAX_BODY);
        UUID id = Ids.newId();
        store.insertMessage(new ConversationStore.MessageRow(id, companyId, conversationId, kind.name(),
                kind == Kind.PERSON ? authorPrincipalId : null, text, kind == Kind.PERSON && answersOpenQuestions,
                runId, decisionId, false, time.now()));
        store.touch(companyId, conversationId, time.now());
        return id;
    }
}
