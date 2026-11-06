package com.flowtivo.tinfirm.chat.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.trigger.ChannelSources;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * {@link ChannelSources}, the trigger module's port (the package 5 contract, §5.7, open point 21): what a channel
 * trigger's editor and gate ask of the chats. The bot's state for the gate's waits; the channels as the bot keeps them
 * ({@code chat_channel}); and the author's membership, asked of the platform outside any transaction: on Slack, the
 * author's linked user's channels the bot is in too ({@code users.conversations} with {@code user}); on Teams, the
 * channel's roster with the author's Entra object id, at the channel's last {@code serviceUrl}.
 */
@Component
class ChatChannelSources implements ChannelSources {

    private final ChatSystems chatSystems;

    private final ChatBot bot;

    private final ChatStore store;

    private final ChatPoster poster;

    private final com.flowtivo.tinfirm.system.ChatCalls calls;

    private final Accounts accounts;

    private final SystemTransactions transactions;

    ChatChannelSources(ChatSystems chatSystems, ChatBot bot, ChatStore store, ChatPoster poster,
            com.flowtivo.tinfirm.system.ChatCalls calls, Accounts accounts, SystemTransactions transactions) {
        this.chatSystems = chatSystems;
        this.bot = bot;
        this.store = store;
        this.poster = poster;
        this.calls = calls;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Override
    public Optional<WaitingReason> botWait(UUID companyId, UUID systemId) {
        return chatSystems.find(companyId, systemId).flatMap(system -> switch (bot.state(system)) {
            case MISSING -> Optional.of(WaitingReason.BOT_MISSING);
            case RECONNECT -> Optional.of(WaitingReason.BOT_RECONNECT);
            case SUSPENDED -> Optional.of(WaitingReason.BOT_SUSPENDED);
            case USABLE, PAUSED -> Optional.<WaitingReason>empty();
        });
    }

    @Override
    public List<Channel> channels(UUID companyId, UUID systemId, Set<String> channelIds) {
        return store.channels(systemId, channelIds).stream()
            .map(channel -> new Channel(channel.channelId(), channel.name(), channel.kind(), channel.botMember(),
                    channel.archived(), channel.deleted()))
            .toList();
    }

    @Override
    public Joined joined(UUID companyId, UUID systemId, UUID authorPrincipalId, Set<String> channelIds) {
        NoTransaction.require("Asking the chat platform for the author's channels");
        record Asked(ChatSystemInfo system, String identity) {
        }
        Asked asked = transactions.inCompany(companyId, () -> chatSystems.find(companyId, systemId)
            .flatMap(system -> accounts.of(companyId, systemId, authorPrincipalId)
                .map(AccountInfo::externalId)
                .map(identity -> new Asked(system, identity)))
            .orElse(null));
        if (asked == null) {
            return Joined.NONE;
        }
        String platform = asked.system().slack() ? "Slack" : "Teams";
        Set<String> joined = new LinkedHashSet<>();
        if (asked.system().slack()) {
            ChatPoster.Sent<List<com.flowtivo.tinfirm.system.ChatCalls.SlackChannel>> listed = poster.call(companyId,
                    systemId, credential -> calls.slackChannels(credential.system().system(), credential.secret(),
                            asked.identity()), ChatPoster.Attempts.RETRIED);
            if (!listed.ok()) {
                throw unasked(platform);
            }
            for (com.flowtivo.tinfirm.system.ChatCalls.SlackChannel channel : java.util.Objects.requireNonNull(
                    listed.value())) {
                if (channelIds.contains(channel.id())) {
                    joined.add(channel.id());
                }
            }
            return new Joined(true, joined);
        }
        for (String channelId : channelIds) {
            String serviceUrl = transactions.inCompany(companyId, () -> store.channelServiceUrl(systemId, channelId)
                .orElse(null));
            if (serviceUrl == null) {
                continue;
            }
            ChatPoster.Sent<com.flowtivo.tinfirm.system.ChatCalls.TeamsMember> member = poster.call(companyId,
                    systemId, credential -> calls.teamsMember(credential.system().system(), credential.teams(),
                            serviceUrl, channelId, asked.identity()), ChatPoster.Attempts.RETRIED);
            if (member.ok()) {
                joined.add(channelId);
            }
            else if (member.failure() == null || member.failure().kind()
                    != com.flowtivo.tinfirm.system.CallResult.Kind.NOT_FOUND) {
                throw unasked(platform);
            }
        }
        return new Joined(true, joined);
    }

    private static ApiException unasked(String platform) {
        return ApiException.badRequest(ProblemCode.CHANNEL_NOT_JOINED, platform + " could not be asked whether the "
                + "trigger's author is in its channels now: try again");
    }

    @Override
    public Optional<String> workspaceName(UUID companyId, UUID systemId) {
        return chatSystems.find(companyId, systemId)
            .map(system -> system.teamName() != null ? system.teamName() : system.name());
    }
}
