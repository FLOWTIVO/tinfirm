package com.flowtivo.tinfirm.chat.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;

/**
 * The platforms' lifecycle events (the package 5 contract, §5.2, §5.14): they keep {@code chat_channel} and
 * {@code chat_user} current. Slack's public and private channels alike (§5.1): the bot joining or leaving, a
 * channel archived, unarchived, renamed, deleted, shared or unshared; a user changed (read again before the next use).
 * Teams' {@code conversationUpdate} (a channel created, renamed or deleted, the bot added or removed). Slack's
 * {@code app_uninstalled} and {@code tokens_revoked} turn the bot {@code RECONNECT} ({@code Accounts.refused}) and the
 * system {@code ERROR}.
 * <p>
 * A channel that becomes unavailable here makes its {@code trigger_channel} rows {@code UNAVAILABLE}, and available
 * again when it comes back ({@link #channelChanged}, through {@link ChatChannels#changed}); a revoked bot makes the
 * system's channel triggers wait ({@code ChannelTriggers.botChanged}, open point 21).
 */
@Component
class ChatLifecycle implements ChatInbox.Handler {

    private final ChatStore store;

    private final ChatSystems chatSystems;

    private final Accounts accounts;

    private final ChatBot bot;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final ChatChannels channels;

    private final com.flowtivo.tinfirm.trigger.ChannelTriggers channelTriggers;

    ChatLifecycle(ChatInbox inbox, ChatStore store, ChatSystems chatSystems, Accounts accounts, ChatBot bot,
            SystemTransactions transactions, TimeSource time, ChatChannels channels,
            com.flowtivo.tinfirm.trigger.ChannelTriggers channelTriggers) {
        this.channels = channels;
        this.channelTriggers = channelTriggers;
        this.store = store;
        this.chatSystems = chatSystems;
        this.accounts = accounts;
        this.bot = bot;
        this.transactions = transactions;
        this.time = time;
        inbox.register(Inbound.LIFECYCLE, this);
    }

    @Override
    public ChatInbox.Outcome handle(ChatSystemInfo system, ChatStore.EventRow event, ChatAudience.Who who) {
        Map<String, Object> payload = event.payload();
        String type = String.valueOf(payload.get("type"));
        UUID companyId = system.companyId();
        Instant now = time.now();
        String channel = ChatInbox.text(payload, "channel");
        String user = ChatInbox.text(payload, "user");
        boolean bots = user != null && user.equals(system.botUserId());
        transactions.runInCompany(companyId, () -> {
            if ("TEAMS".equals(payload.get("platform"))) {
                teams(system, payload, now);
                return;
            }
            boolean group = Boolean.TRUE.equals(payload.get("private"));
            String kind = group ? "PRIVATE" : null;
            switch (type) {
                case "member_joined_channel" -> {
                    if (bots && channel != null) {
                        store.channel(companyId, system.id(), channel, null, null, channelKind(payload), true, null,
                                false, now);
                    }
                }
                case "member_left_channel", "channel_left", "group_left" -> {
                    if (channel != null && (bots || type.endsWith("_left"))) {
                        store.channel(companyId, system.id(), channel, null, null, kind, false, null, null, now);
                    }
                }
                case "channel_archive", "group_archive" -> store.channel(companyId, system.id(), channel, null, null,
                        kind, null, true, null, now);
                case "channel_unarchive", "group_unarchive" -> store.channel(companyId, system.id(), channel, null,
                        null, kind, null, false, null, now);
                case "channel_rename", "group_rename" -> store.channel(companyId, system.id(), channel, null,
                        ChatInbox.text(payload, "channelName"), kind, null, null, null, now);
                case "channel_deleted", "group_deleted" -> store.channel(companyId, system.id(), channel, null, null,
                        kind, null, null, true, now);
                case "channel_shared" -> store.channel(companyId, system.id(), channel, null, null, "SHARED", null,
                        null, null, now);
                case "channel_unshared" -> store.channel(companyId, system.id(), channel, null, null,
                        group ? "PRIVATE" : "PUBLIC", null, null, null, now);
                case "user_change" -> {
                    if (user != null) {
                        store.stale(system.id(), user);
                    }
                }
                case "app_uninstalled", "tokens_revoked" -> revoked(system, type);
                default -> {
                }
            }
            if (channel != null) {
                channelChanged(system, channel);
            }
        });
        return ChatInbox.Outcome.handled();
    }

    private static String channelKind(Map<String, Object> payload) {
        Object type = payload.get("channelType");
        return "G".equals(type) || "group".equals(type) ? "PRIVATE" : "PUBLIC";
    }

    private void teams(ChatSystemInfo system, Map<String, Object> payload, Instant now) {
        String channel = ChatInbox.text(payload, "channel");
        String eventType = ChatInbox.text(payload, "eventType");
        if (channel == null || eventType == null) {
            return;
        }
        UUID companyId = system.companyId();
        String team = ChatInbox.text(payload, "teamId");
        switch (eventType) {
            case "channelCreated", "teamMemberAdded" -> store.channel(companyId, system.id(), channel, team,
                    ChatInbox.text(payload, "channelName"), null, true, null, false, now);
            case "channelRenamed" -> store.channel(companyId, system.id(), channel, team,
                    ChatInbox.text(payload, "channelName"), null, null, null, null, now);
            case "channelDeleted", "teamDeleted" -> store.channel(companyId, system.id(), channel, team, null, null,
                    null, null, true, now);
            case "teamMemberRemoved" -> store.channel(companyId, system.id(), channel, team, null, null, false, null,
                    null, now);
            default -> {
            }
        }
        String serviceUrl = ChatInbox.text(payload, "serviceUrl");
        if (serviceUrl != null) {
            store.channelServiceUrl(system.id(), channel, serviceUrl);
        }
        channelChanged(system, channel);
    }

    /** The app was uninstalled or its tokens revoked: the bot waits for a new token ({@code RECONNECT}). */
    private void revoked(ChatSystemInfo system, String why) {
        if (system.botAccountId() != null) {
            accounts.refused(system.companyId(), system.botAccountId(), "Slack: " + why);
        }
        chatSystems.status(system.companyId(), system.id(), "ERROR", "Slack: " + why);
        bot.forget(system.id());
        channelTriggers.botChanged(system.companyId(), system.id());
    }

    /** A channel's state changed: its trigger channels follow (§3.10, §5.14). */
    void channelChanged(ChatSystemInfo system, String channelId) {
        channels.changed(system, channelId);
    }
}
