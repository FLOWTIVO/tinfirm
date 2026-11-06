package com.flowtivo.tinfirm.run;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The run module's domain events, ids only, published in the transaction that made the change (CONVENTIONS §10):
 * the conversation module keeps its turn's status and what it waits on ({@code turn_status}, {@code waiting_on}) with
 * an {@code @EventListener} in that transaction, which locked the conversation's row before the run's
 * ({@link ConversationLock}). A run's end is told to the conversation through {@link TurnEnded}, then published as
 * {@link RunEnded}.
 */
public final class RunEvents {

    private RunEvents() {
    }

    /** The host accepted the run (or reported it running after a reconnect): RUNNING. */
    public record RunAccepted(UUID companyId, UUID runId, UUID conversationId) {
    }

    /** A QUEUED run waits for another reason now; {@code code} is null when it no longer has one. */
    public record RunQueueReasonChanged(UUID companyId, UUID runId, UUID conversationId,
            @Nullable QueueReasonCode code) {
    }

    /**
     * A run ended (SUCCEEDED, FAILED, CANCELLED or LOST), published in the transaction that ended it, after
     * {@link TurnEnded} was told (the package 6 contract, §2.2): the governance module settles its spend from it.
     * Every end publishes it, a loss by lease expiry included, whose run may be resumed and end again.
     */
    public record RunEnded(UUID companyId, UUID runId, UUID conversationId, RunStatus status) {
    }

    /** A run lost by lease expiry runs again: its host still ran it ({@link TurnEnded#turnResumed}). */
    public record RunResumed(UUID companyId, UUID runId, UUID conversationId) {
    }
}
