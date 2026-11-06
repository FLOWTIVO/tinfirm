package com.flowtivo.tinfirm.conversation.internal;

import java.util.Objects;

import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.TurnSource;

/**
 * One turn of a conversation as its parts see it while the turn's {@code run.assign} is built
 * ({@code TurnSource.content}): the conversation, the turn's run, and the settings in effect for the turn (one reading
 * for the whole turn).
 */
public record TurnContext(ConversationInfo conversation, TurnSource.TurnRef turn, EffectiveSettings settings) {

    public TurnContext {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(settings, "settings");
    }
}
