package com.flowtivo.tinfirm.run;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Port: the runner host's answer to a person's message sent to a running turn ({@link Turns#steer}), implemented by
 * the conversation module, which then shows the message in that turn or lets it wait for the next one. Without that
 * module, nobody is told.
 */
public interface TurnSteered {

    /**
     * The host answered {@code run.steer} for the message {@code steerId} of the run's conversation, in a transaction
     * that locked the conversation's row before the run's ({@link ConversationLock}, CONVENTIONS §10). The run is
     * still active.
     *
     * @param delivered the agent has the message in the turn it runs; false: it does not, and the message waits for
     *                  the next turn
     * @param code      why it was refused ({@code NOT_SUPPORTED}, {@code NOT_RUNNING}, {@code FAILED}); null when
     *                  delivered
     */
    void steered(ActiveRun run, UUID steerId, boolean delivered, @Nullable String code);
}
