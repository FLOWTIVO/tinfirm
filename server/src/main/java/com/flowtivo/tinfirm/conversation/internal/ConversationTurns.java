package com.flowtivo.tinfirm.conversation.internal;

import java.util.Optional;
import java.util.UUID;

import com.flowtivo.tinfirm.run.TurnReason;

/**
 * For parts 2 and 3, implemented by part 1: {@code Turns.request(conversation, reason)} (the contract's §5.7) and what
 * the conversation waits on. Every method runs in the caller's transaction (a mandatory one) and tenant context.
 */
public interface ConversationTurns {

    /**
     * Requests the conversation's next turn: {@code DECIDED} (the last open decision decided), {@code CONNECTED} (a
     * binding resolved with parked calls), or part 1's own {@code FIRST}, {@code MESSAGE} and {@code RETRY}. Nothing
     * starts for an ended conversation, while a run is active (the next turn waits for it), nor, for {@code DECIDED} and
     * {@code CONNECTED}, while a decision or a binding of the conversation is still open. The turn takes every waiting
     * message, oldest first. Updates what the conversation waits on.
     *
     * @return the new turn's run, if one started
     */
    Optional<UUID> request(UUID companyId, UUID conversationId, TurnReason reason);

    /**
     * Works out again what the conversation waits on ({@code turn_status}, {@code waiting_on}, its reason and since
     * when) from its active run, its open decisions, its waiting bindings and its latest turn: after a decision was
     * raised, decided or cancelled.
     */
    void refreshWaiting(UUID companyId, UUID conversationId);
}
