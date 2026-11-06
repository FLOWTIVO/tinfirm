package com.flowtivo.tinfirm.conversation.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.Conversations;

/**
 * Part 2's way to part 1's beans (the conversation core: {@link Conversations}, {@link ConversationBindings},
 * {@link ConversationTurns}, {@link ConversationMessages}, {@link ConversationWorkspace}), looked up when used. Part 1
 * calls part 2's hooks ({@link BindingHook}, {@link TurnEndHook}, {@link OpenDecisions}, {@link QuestionAnswers}) from
 * inside its own beans, so a constructor dependency both ways would be a cycle; resolving them lazily keeps the two
 * parts' beans independent of each other's construction order.
 */
@Component
class ConversationCore {

    private final ObjectProvider<Conversations> conversations;

    private final ObjectProvider<ConversationBindings> bindings;

    private final ObjectProvider<ConversationTurns> turns;

    private final ObjectProvider<ConversationMessages> messages;

    private final ObjectProvider<ConversationWorkspace> workspace;

    ConversationCore(ObjectProvider<Conversations> conversations, ObjectProvider<ConversationBindings> bindings,
            ObjectProvider<ConversationTurns> turns, ObjectProvider<ConversationMessages> messages,
            ObjectProvider<ConversationWorkspace> workspace) {
        this.conversations = conversations;
        this.bindings = bindings;
        this.turns = turns;
        this.messages = messages;
        this.workspace = workspace;
    }

    Conversations conversations() {
        return conversations.getObject();
    }

    ConversationBindings bindings() {
        return bindings.getObject();
    }

    ConversationTurns turns() {
        return turns.getObject();
    }

    ConversationMessages messages() {
        return messages.getObject();
    }

    ConversationWorkspace workspace() {
        return workspace.getObject();
    }
}
