package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.run.ConversationLock;

/**
 * {@link ConversationLock}: the run module locks a turn's conversation's row through here before it locks the run's
 * row, in every transaction that may then lock or write the conversation (CONVENTIONS §10). It is the conversation's
 * own lock ({@link ConversationStore#lock}), the one every transaction of this module takes first.
 */
@Component
class ConversationLockImpl implements ConversationLock {

    private final ConversationStore store;

    ConversationLockImpl(ConversationStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID companyId, UUID conversationId) {
        store.lock(companyId, conversationId);
    }
}
