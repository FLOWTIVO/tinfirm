package com.flowtivo.tinfirm.conversation;

import java.util.Optional;
import java.util.UUID;

/**
 * Port: the firings that a trigger's conversation's turns take (the package 4 contract, §5.6, §5.12), implemented by
 * the {@code trigger} module, whose {@code trigger_firing} rows they are. Looked up when used; without a bean a trigger's
 * conversation has no firings waiting. In the caller's transaction and tenant context, under the conversation's lock.
 * <p>
 * A firing of a trigger on its open conversation waits, as a person's message does, until a turn takes it: an
 * {@code EVENT} turn when no run is active and no decision open, else the next turn of the conversation, whatever its
 * reason ({@code trigger_firing.run_id} is set then). So an event that arrives during a run is never lost: the end of
 * that run starts an {@code EVENT} turn for it, after a {@code DECIDED} or {@code CONNECTED} turn, as messages that
 * waited start a {@code MESSAGE} turn.
 */
public interface TriggerTurns {

    /** Whether firings of the conversation wait for a turn. */
    boolean waiting(UUID companyId, UUID conversationId);

    /** The turn's run takes the firings that wait: they are its request. In the transaction that started it. */
    void taken(UUID companyId, UUID conversationId, UUID runId);

    /**
     * The turn's {@code # Request} of the firings it took (§5.12): "assigned to svc-ba by Bob at 10:02", a comment
     * quoted as other people's words; empty when it took none.
     */
    Optional<String> request(UUID companyId, UUID runId);

    /**
     * The first turn's {@code # Subject} (§5.12, open point 12): the subject's type, status, labels, assignees and
     * description, cut to 16 KiB, from the poll's read, as other people's words; empty for a schedule and for a later
     * turn.
     */
    Optional<String> subject(UUID companyId, UUID conversationId, UUID runId);
}
