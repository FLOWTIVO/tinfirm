package com.flowtivo.tinfirm.trigger;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Channel triggers for the {@code chat} module (the package 5 contract, §5.7, §5.14, open point 21): the triggers of a
 * channel, the firing of a mention, whether a thread's trigger answers now, and what the chat says of its channels and
 * its bot. Every method runs in the caller's transaction (a mandatory one) and tenant context, and keeps the lock
 * order: the trigger's row, then its conversations.
 */
public interface ChannelTriggers {

    /**
     * A live channel trigger's row of a channel.
     *
     * @param available the channel's row is {@code ACTIVE} (the bot is in it, it is kept and not shared)
     */
    record ChannelTrigger(UUID triggerId, String triggerName, UUID agentId, boolean isDefault, boolean available) {
    }

    /** The live channel triggers of the channel, the default first. */
    List<ChannelTrigger> ofChannel(UUID companyId, UUID systemId, String channelId);

    /**
     * A live channel trigger on a channel, for the channel list (§6).
     *
     * @param seen the viewer sees it: its author, in its team, or an admin
     */
    record OnChannel(String channelId, UUID triggerId, String triggerName, UUID agentId, boolean isDefault,
            boolean seen) {
    }

    /**
     * The live channel triggers on these channels of the system, the default first, each marked whether the viewer
     * sees it.
     *
     * @param viewerPrincipalId the member whose view it is (the caller, or a trigger's author for an admin editing on
     *                          their behalf); null for none, who sees none unless an admin
     * @param admin             the viewer sees every trigger (OWNER, ADMIN)
     */
    List<OnChannel> onChannels(UUID companyId, UUID systemId, Collection<String> channelIds,
            @Nullable UUID viewerPrincipalId, boolean admin);

    /**
     * A mention of the bot that starts a conversation in a new thread (§5.7 step 3).
     *
     * @param teamId           a Teams team, or Slack's workspace
     * @param threadId         Slack's thread {@code ts} (the message's own when it is top-level), a Teams channel
     *                         thread's root message id
     * @param messageId        the message's id at the platform: stored once as the conversation's {@code EXTERNAL}
     *                         message
     * @param text             the message, the bot's mention taken out
     * @param askerId          the chat user's id
     * @param askerName        their name in the chat
     * @param askerPrincipalId the member linked to the chat user, else null
     * @param channelName      the channel's name, for the title and the prompt
     * @param serviceUrl       Teams' {@code serviceUrl}; null on Slack
     */
    record Mention(UUID triggerId, UUID systemId, @Nullable String teamId, String channelId, String threadId,
            String messageId, String text, String askerId, String askerName, @Nullable UUID askerPrincipalId,
            @Nullable String channelName, @Nullable String serviceUrl, String via) {

        public Mention {
            Objects.requireNonNull(triggerId, "triggerId");
            Objects.requireNonNull(channelId, "channelId");
            Objects.requireNonNull(threadId, "threadId");
        }
    }

    /** What a mention's firing did. */
    sealed interface Fired {

        /** A conversation started in the thread, its first turn's request the mention. */
        record Started(UUID firingId, UUID conversationId) implements Fired {
        }

        /**
         * Nothing fired, and nothing is recorded or fired later (package 4, decision 10).
         *
         * @param reason {@code TRIGGER_PAUSED} (paused, or waiting so that it fires nothing), {@code AGENT_PAUSED},
         *               {@code RATE_LIMITED} (past its firings an hour), {@code NO_TRIGGER} (deleted meanwhile)
         * @param words  why, for the asker
         */
        record Stopped(String reason, String words) implements Fired {
        }
    }

    /** Fires the trigger for the mention: the gate, the agent, the rate, the firing and the conversation (§5.7). */
    Fired fire(UUID companyId, Mention mention);

    /**
     * Whether the trigger's conversation takes a mention in its thread now (§5.7 step 4, §5.14): empty when it does;
     * else why not ({@code TRIGGER_PAUSED}, {@code AGENT_PAUSED}), the gate run as at a firing.
     */
    Optional<Fired.Stopped> stopped(UUID companyId, UUID triggerId);

    /**
     * The chat says the channel is available to the bot again, or not (the bot left, it was archived, deleted or
     * shared): its rows turn {@code ACTIVE} or {@code UNAVAILABLE}, and a trigger all of whose channels are unavailable
     * waits {@code CHANNEL_UNAVAILABLE} until one comes back.
     */
    void channelState(UUID companyId, UUID systemId, String channelId, boolean available);

    /**
     * The system's bot changed (set, removed, refused, its service identity suspended or resumed): its channel triggers
     * go through the gate again, so that they wait on the bot, or go on by themselves (open point 21).
     */
    void botChanged(UUID companyId, UUID systemId);
}
