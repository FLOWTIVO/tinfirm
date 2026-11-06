package com.flowtivo.tinfirm.run;

import java.util.Optional;
import java.util.UUID;

/**
 * The runs of turns, for the conversation module (the package 3 contract, §5.7). Every method runs in the caller's
 * transaction (a mandatory one) and tenant context.
 * <p>
 * Lock order (CONVENTIONS §10): a conversation's row before its runs' rows, and both before the transaction's first
 * audit record. Each method locks the conversation's row first through {@link ConversationLock}, which costs nothing
 * for a caller that holds it already; a caller locks it before it audits or locks anything else of the conversation.
 */
public interface Turns {

    /**
     * Creates the turn's run, QUEUED, unless the conversation has a QUEUED or RUNNING run: one run at a time per
     * conversation (I11, {@code run_one_active_per_conversation_uq}), which the insert checks without aborting the
     * caller's transaction. Audited as {@code run.started}; the dispatcher looks at the run once the transaction
     * commits.
     *
     * @return the new run's id; empty when the conversation has an active run, and the turn waits for it
     */
    Optional<UUID> start(NewTurn turn);

    /**
     * Cancels the conversation's active run, if any (the person's or OWNER's cancel, the end of a conversation whose
     * person left): a QUEUED run ends CANCELLED at once, its {@link TurnEnded} with it; a RUNNING run gets a durable
     * cancel command and {@code run.cancel}, and ends when its host reports {@code run.finished}. Audited as
     * {@code run.cancelled}.
     *
     * @return the run that was active, if any
     */
    Optional<UUID> cancelActive(UUID companyId, UUID conversationId);

    /**
     * Gives a person's message to the conversation's running turn (steering, the runner protocol 2 feature
     * {@code run.steer}): when its run is RUNNING on a connected host that lists the feature, the host gets
     * {@code run.steer} once the transaction commits, hands the text to the agent, which takes it after its current
     * step, and answers through {@link TurnSteered}. The message itself is the caller's to store.
     *
     * @param steerId the message's id: the key of the host's answer
     * @param text    what the agent reads, 1 to {@code RunnerMessages.MAX_STEER_TEXT} characters
     * @return the run it goes to; empty when the conversation has no running run, or its host does not take steers
     */
    Optional<UUID> steer(UUID companyId, UUID conversationId, UUID steerId, String text);
}
