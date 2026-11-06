package com.flowtivo.tinfirm.trigger;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What the {@code trigger} module asks of the module that holds the chats (the package 5 contract, §5.7, §5.14, open
 * point 21): a port the {@code chat} module implements, looked up when used, as {@code TriggerAudience} is. A channel
 * trigger's editor checks its channels and its author against it, and its gate waits on the system's bot.
 */
public interface ChannelSources {

    /**
     * What a chat system's bot makes a channel trigger wait on: {@code BOT_MISSING}, {@code BOT_RECONNECT},
     * {@code BOT_SUSPENDED}; empty while it can be used (open point 21). In the caller's transaction.
     */
    Optional<WaitingReason> botWait(UUID companyId, UUID systemId);

    /**
     * A channel of a chat system as its bot knows it ({@code chat_channel}).
     *
     * @param kind {@code PUBLIC}, {@code PRIVATE}, {@code SHARED} (Slack Connect, a Teams shared channel)
     */
    record Channel(String channelId, @Nullable String name, String kind, boolean botMember, boolean archived,
            boolean deleted) {

        /** Whether a trigger may hold it, and its mentions reach the bot: the bot is in it, kept and not shared. */
        public boolean available() {
            return botMember && !archived && !deleted && !"SHARED".equals(kind);
        }
    }

    /** The channels of a chat system the bot knows of, by id; in the caller's transaction. */
    List<Channel> channels(UUID companyId, UUID systemId, Set<String> channelIds);

    /**
     * Which of the channels the author is a member of, as the platform says now (§5.7, {@code CHANNEL_NOT_JOINED}):
     * <b>outside any transaction</b>, before the save that checks it. {@code linked} is false when the author has no
     * link on the system, and {@code joined} then empty.
     */
    Joined joined(UUID companyId, UUID systemId, UUID authorPrincipalId, Set<String> channelIds);

    /** What {@link #joined} found. */
    record Joined(boolean linked, Set<String> joined) {

        public static final Joined NONE = new Joined(false, Set.of());

        public Joined {
            joined = Set.copyOf(joined);
        }
    }

    /** The workspace's or tenant's name for words: "any member of Acme in #support". */
    Optional<String> workspaceName(UUID companyId, UUID systemId);
}
