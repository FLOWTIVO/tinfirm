package com.flowtivo.tinfirm.run;

import java.util.UUID;

/**
 * Port: the row lock of a turn's conversation, implemented by the conversation module (package 3c). It serves the
 * global lock order of CONVENTIONS §10: a conversation's row before the rows of its runs, several conversations in the
 * order of their ids, and both before the company's audit chain head, which the transaction's audit records lock when
 * it commits. The run module never reads the {@code conversation} table, so every transaction of it that may lock or
 * write a conversation (a run's end through {@link TurnEnded}, the listeners of {@link RunEvents},
 * {@link TurnSource#plan}, {@link RunRequests#request}, {@link TurnEnded#turnResumed}) takes this lock first, then the
 * run's.
 * <p>
 * Without the conversation module nothing is locked: no conversation is written either.
 */
public interface ConversationLock {

    /**
     * Locks the conversation's row ({@code FOR NO KEY UPDATE}, so that the foreign keys' checks of the rows that name
     * it never wait on it) in the caller's transaction, a mandatory one, and tenant context, until the transaction
     * ends. A conversation the context does not see is not locked; one the transaction holds already is locked again at
     * no cost.
     */
    void lock(UUID companyId, UUID conversationId);
}
