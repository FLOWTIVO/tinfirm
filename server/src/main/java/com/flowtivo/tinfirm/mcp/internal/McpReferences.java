package com.flowtivo.tinfirm.mcp.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentReads;
import com.flowtivo.tinfirm.conversation.ConversationReads;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * What a tool's argument names, as the person names it: a conversation by its id or its key, an agent by its id or its
 * handle ({@code dev}, {@code @dev}). Each is resolved through the public API as the caller, so what the caller does
 * not see is {@code NOT_FOUND}, as for an unknown one.
 */
@Component
class McpReferences {

    private final ConversationReads conversations;

    private final AgentReads agents;

    McpReferences(ConversationReads conversations, AgentReads agents) {
        this.conversations = conversations;
        this.agents = agents;
    }

    /** The conversation named by its id or its key, among those the caller sees through a client. */
    UUID conversation(UUID companyId, @Nullable String reference) {
        String value = required(reference, "conversation");
        UUID id = uuid(value);
        return id != null ? id : conversations.getByKey(companyId, ConversationView.CLIENT, value).getId();
    }

    /** The agent named by its id or its handle, among the live agents the caller sees. */
    UUID agent(UUID companyId, @Nullable String reference) {
        String value = required(reference, "agent");
        UUID id = uuid(value);
        return id != null ? id : agents.idOfHandle(companyId, value)
            .orElseThrow(() -> ApiException.notFound("Agent not found"));
    }

    private static String required(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation(name + " is required: its id, or its " + (name.equals("agent") ? "@handle"
                    : "key"));
        }
        return value.strip();
    }

    private static @Nullable UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
