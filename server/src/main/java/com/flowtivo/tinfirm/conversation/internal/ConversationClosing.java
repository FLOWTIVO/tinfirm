package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OpenDecisions}: what a conversation leaves open when it is ended or cancelled, its agent deleted or its person
 * gone (the package 3 contract, §5.7): its open decisions are cancelled, each audited as {@code decision.cancelled}
 * with the cause, and the calls parked on its bindings are cancelled, which the next turn tells (a cancelled
 * conversation has one).
 */
@Component
class ConversationClosing implements OpenDecisions {

    private final DecisionStore store;

    private final DecisionOps decisions;

    private final ParkedCalls parked;

    ConversationClosing(DecisionStore store, DecisionOps decisions, ParkedCalls parked) {
        this.store = store;
        this.decisions = decisions;
        this.parked = parked;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelAll(UUID companyId, UUID conversationId, String cause) {
        store.lockConversation(conversationId);
        for (DecisionStore.DecisionRow open : store.lockOpen(conversationId)) {
            decisions.cancel(open, cause);
        }
        parked.cancel(conversationId, cause);
    }
}
