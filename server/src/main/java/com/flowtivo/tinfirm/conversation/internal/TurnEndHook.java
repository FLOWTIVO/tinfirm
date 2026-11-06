package com.flowtivo.tinfirm.conversation.internal;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.TurnEnded;

/**
 * Seam for parts 2 and 3: what else follows the end of a turn's run, in the transaction that ended it
 * ({@code TurnEnded.turnEnded}), after part 1 recorded the result or failure and posted its messages, and before it
 * starts the next waiting turn and works out what the conversation waits on. Part 2 raises a {@code BLOCKED} result's
 * open questions; part 3 records the {@code /output} files as documents and opens change requests after a push
 * (§5.4, open point 5). Every bean is called, in their order.
 */
public interface TurnEndHook {

    /**
     * @param conversation the conversation as part 1 left it after recording the turn
     */
    void turnEnded(ConversationInfo conversation, TurnEnded.TurnEnd end);
}
