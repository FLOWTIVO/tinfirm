package com.flowtivo.tinfirm.conversation;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The start of a personal conversation (the package 5 contract, §2.2 and §5.6), shared by the app's
 * {@code POST /conversations} and a direct message to the bot: exactly the app's start (CONVENTIONS §15.7), with
 * {@code Settings.firstUse}, the runtime and the model access fixed, and the refusals of what can never run
 * ({@code MODEL_ACCESS_UNAVAILABLE}, {@code NO_HOST_FOR_CONVERSATION}). Runs in the caller's transaction and tenant
 * context; the caller authorised the person (a direct message's sender is linked to them, C5). A refusal is thrown as
 * {@link StartRefused} before anything is stored but the person's first settings of the agent: the caller's
 * transaction rolls back.
 */
public interface PersonalConversations {

    /**
     * The first message.
     *
     * @param externalId     the chat's id of the message (Slack's {@code ts}, a Teams activity's id); null in the app
     * @param externalAuthor the chat user who wrote it ({@code {id, name, via, verified}}); null in the app
     */
    record FirstMessage(String body, @Nullable String externalId, @Nullable Map<String, Object> externalAuthor) {
    }

    /** A started conversation and its first turn's run, when one started. */
    record Started(UUID conversationId, String key, @Nullable UUID runId) {
    }

    /**
     * Starts the conversation of the person with the agent, in a place.
     *
     * @param placeRef the place's reference: for {@code CHAT_THREAD}
     *                 {@code {systemId, kind DM, teamId?, channelId, threadId, serviceUrl?}}; null for a Tinfirm thread
     * @throws StartRefused when the agent cannot be used by the person, or the conversation could never run
     */
    Started start(UUID companyId, UUID personPrincipalId, UUID agentId, FirstMessage firstMessage,
            ConversationInfo.Place place, ConversationInfo.Origin origin, @Nullable Map<String, String> placeRef);

    /**
     * Why a conversation was not started: the code of the app's problem ({@code AGENT_NOT_USABLE},
     * {@code MODEL_ACCESS_UNAVAILABLE}, {@code NO_HOST_FOR_CONVERSATION}, {@code NOT_FOUND}, {@code VALIDATION_FAILED}),
     * its message for the person, and the refusal's reason when it has one.
     */
    final class StartRefused extends RuntimeException {

        private final String code;

        private final @Nullable String reason;

        public StartRefused(String code, String message, @Nullable String reason) {
            super(message);
            this.code = code;
            this.reason = reason;
        }

        public String code() {
            return code;
        }

        public @Nullable String reason() {
            return reason;
        }
    }
}
