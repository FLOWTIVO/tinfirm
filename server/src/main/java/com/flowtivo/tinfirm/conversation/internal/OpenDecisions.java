package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

/**
 * Seam for part 2: cancels what a conversation leaves open when it is ended or cancelled, or its agent deleted, or its
 * person left (§5.7): its open decisions (audited {@code decision.cancelled} with the cause) and the calls parked on
 * its bindings. In the caller's transaction. Without a bean that implements it, part 1 cancels the open decisions
 * itself ({@code status CANCELLED}, {@code cancel_cause} the cause), unaudited.
 */
public interface OpenDecisions {

    /**
     * @param cause {@code ENDED}, {@code CANCELLED}, {@code AGENT_DELETED} or {@code PERSON_LEFT}
     */
    void cancelAll(UUID companyId, UUID conversationId, String cause);
}
