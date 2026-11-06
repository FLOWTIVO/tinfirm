package com.flowtivo.tinfirm.conversation;

import java.util.Optional;

/**
 * Port: the place's lines of a turn's prompt (the package 5 contract, §2.2), implemented by the module that owns the
 * place (the {@code chat} module for {@code CHAT_THREAD}), looked up when used, so that the conversation module does
 * not depend on it. Called outside any transaction, in the conversation's tenant context.
 */
public interface PlacePrompts {

    /**
     * Where the conversation is held, in words, after "A conversation with {person}": e.g. "in a Slack direct
     * message". Empty for a place this module does not own.
     */
    Optional<String> where(ConversationInfo conversation);

    /**
     * A trigger's conversation held in the place (package 5: a channel's thread, a webhook's subject): the lines under
     * the conversation's heading after "Started by the trigger {name} of {author}", where it started, where results go
     * and whose words its messages are. Empty for a place this module does not own.
     */
    default Optional<String> started(ConversationInfo conversation) {
        return Optional.empty();
    }

    /** How the prompt names the author of a message of the place ({@code EXTERNAL}): "Jane Doe (in Slack)". */
    default Optional<String> author(ConversationInfo conversation, String name) {
        return Optional.empty();
    }
}
