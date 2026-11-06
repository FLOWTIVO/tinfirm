package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ChatChannel;
import com.flowtivo.tinfirm.shared.api.model.ChatChannelList;
import com.flowtivo.tinfirm.shared.api.model.ChatChannelTrigger;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.trigger.ChannelSources;
import com.flowtivo.tinfirm.trigger.ChannelTriggers;

/**
 * The channels the bot is in, as the chat module keeps them ({@code chat_channel}, the package 5 contract, §3.4), and
 * what channel triggers follow of them (§3.10, §5.14): a channel the bot left, or that was archived, deleted or shared
 * with another organisation, makes its {@code trigger_channel} rows {@code UNAVAILABLE}, and {@code ACTIVE} again when
 * it comes back ({@link #changed}). Kept from the lifecycle events, from a mention (which proves the bot is in the
 * channel), from a post the platform refused for the channel ({@link #gone}), and on Slack from {@link #refresh}
 * ({@code users.conversations}) when a channel trigger's editor lists them, at most once a minute per system and
 * replica, so that members listing them do not spend the bot's rate on Slack: the kept list in between.
 */
@Component
class ChatChannels {

    private static final Logger log = LoggerFactory.getLogger(ChatChannels.class);

    /** A system's channels are read again from Slack at most this often (per replica). */
    static final Duration REFRESH_EVERY = Duration.ofMinutes(1);

    /** When each system's channels were last read from Slack in this replica. */
    private final Map<UUID, Instant> refreshed = new ConcurrentHashMap<>();

    private final ChatStore store;

    private final ChannelTriggers triggers;

    private final ChatPoster poster;

    private final ChatCalls calls;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final CompanyAccessGuard guard;

    private final Systems systems;

    private final ChatSystems chatSystems;

    private final Principals principals;

    private final ChannelSources sources;

    private final TransactionTemplate callerTransactions;

    ChatChannels(ChatStore store, ChannelTriggers triggers, ChatPoster poster, ChatCalls calls,
            SystemTransactions transactions, TimeSource time, CompanyAccessGuard guard, Systems systems,
            ChatSystems chatSystems, Principals principals, ChannelSources sources,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.triggers = triggers;
        this.poster = poster;
        this.calls = calls;
        this.transactions = transactions;
        this.time = time;
        this.guard = guard;
        this.systems = systems;
        this.chatSystems = chatSystems;
        this.principals = principals;
        this.sources = sources;
        this.callerTransactions = new TransactionTemplate(transactionManager);
    }

    // ---- the channel list (§6) -------------------------------------------------------------------------------------

    /** Whose view the list is, and of what. */
    private record Viewer(ChatSystemInfo system, @Nullable UUID principalId, boolean admin) {
    }

    /** The kept channels and the triggers on them, read in one transaction. */
    private record Read(List<ChatStore.Channel> channels, List<ChannelTriggers.OnChannel> triggers) {
    }

    /**
     * {@code GET /systems/{systemId}/chat/channels}: the channels the bot is in, public ones, and private ones to a
     * linked member the platform says is in them, each with the triggers on it the caller sees: a member those they
     * author or are in the team of, an OWNER or ADMIN every one, as the trigger pages show them (package 4, §5.1). On
     * Slack the bot's channels are read again first, and the member's private channels asked, outside any transaction.
     *
     * @param authorPrincipalId an admin's view of a trigger's author (package 4, decision 7): the author's channels,
     *                          private ones included, each with every trigger on it; 404 for anyone else
     */
    ChatChannelList list(UUID companyId, UUID systemId, @Nullable String query, @Nullable UUID authorPrincipalId) {
        NoTransaction.require("Listing the bot's channels");
        Viewer viewer = inCaller(() -> {
            CompanyAccess access = guard.require(companyId, CompanyPermission.VIEW_COMPANY);
            boolean admin = access.viaInstanceAdmin() || access.role() == CompanyRole.OWNER
                    || access.role() == CompanyRole.ADMIN;
            if (systems.find(companyId, systemId).isEmpty()) {
                throw ApiException.notFound("System not found");
            }
            ChatSystemInfo system = chatSystems.find(companyId, systemId).orElseThrow(() -> ApiException.badRequest(
                    ProblemCode.SYSTEM_KIND_INVALID, "Not a chat system"));
            if (authorPrincipalId != null) {
                PrincipalInfo author = principals.find(companyId, authorPrincipalId)
                    .filter(found -> admin && found.member())
                    .orElseThrow(() -> ApiException.notFound("Member not found"));
                // The author's channels, the admin's sight of the triggers on them.
                return new Viewer(system, author.id(), true);
            }
            UUID principalId = principals.ofMember(companyId, CurrentActor.requireUser().id())
                .map(PrincipalInfo::id)
                .orElse(null);
            return new Viewer(system, principalId, admin);
        });
        refresh(viewer.system());
        String wanted = query == null || query.isBlank() ? null : query.strip().toLowerCase(Locale.ROOT);
        Read read = inCaller(() -> {
            guard.require(companyId, CompanyPermission.VIEW_COMPANY);
            List<ChatStore.Channel> kept = store.channels(systemId, null).stream()
                .filter(ChatStore.Channel::botMember)
                .filter(channel -> wanted == null || channel.name() != null
                        && channel.name().toLowerCase(Locale.ROOT).contains(wanted))
                .toList();
            return new Read(kept, triggers.onChannels(companyId, systemId,
                    kept.stream().map(ChatStore.Channel::channelId).toList(), viewer.principalId(), viewer.admin()));
        });
        Set<String> privateIds = new LinkedHashSet<>();
        read.channels().stream()
            .filter(channel -> "PRIVATE".equals(channel.kind()))
            .forEach(channel -> privateIds.add(channel.channelId()));
        Set<String> joined = Set.of();
        if (!privateIds.isEmpty() && viewer.principalId() != null) {
            try {
                joined = sources.joined(companyId, systemId, viewer.principalId(), privateIds).joined();
            }
            catch (ApiException ex) {
                log.info("The private channels of the chat system {} are left out: the platform could not be asked",
                        systemId);
            }
        }
        Map<String, List<ChannelTriggers.OnChannel>> on = new HashMap<>();
        read.triggers().forEach(trigger -> on.computeIfAbsent(trigger.channelId(), id -> new ArrayList<>())
            .add(trigger));
        List<ChatChannel> items = new ArrayList<>();
        for (ChatStore.Channel channel : read.channels()) {
            if ("PRIVATE".equals(channel.kind()) && !joined.contains(channel.channelId())) {
                continue;
            }
            List<ChannelTriggers.OnChannel> here = on.getOrDefault(channel.channelId(), List.of());
            items.add(new ChatChannel(channel.channelId(), ChatChannel.KindEnum.fromValue(channel.kind()),
                    channel.archived(), channel.available(), here.stream().anyMatch(ChannelTriggers.OnChannel::isDefault),
                    here.stream()
                        .filter(ChannelTriggers.OnChannel::seen)
                        .map(trigger -> new ChatChannelTrigger(trigger.triggerId(), trigger.triggerName(),
                                trigger.agentId(), trigger.isDefault()))
                        .toList())
                .name(channel.name())
                .teamId(channel.teamId()));
        }
        return new ChatChannelList(items);
    }

    private <T> T inCaller(Supplier<T> work) {
        return Objects.requireNonNull(callerTransactions.execute(status -> work.get()));
    }

    /**
     * The channel's state changed here: its trigger channels follow it (available: the bot is in it, it is kept and
     * not shared). In the caller's transaction and tenant context.
     */
    void changed(ChatSystemInfo system, String channelId) {
        boolean available = store.channel(system.id(), channelId).map(ChatStore.Channel::available).orElse(false);
        triggers.channelState(system.companyId(), system.id(), channelId, available);
    }

    /**
     * A mention or a press arrived from the channel: the bot is in it. Teams' {@code serviceUrl} is kept for its
     * roster. In the caller's transaction and tenant context.
     */
    void seen(ChatSystemInfo system, String channelId, @Nullable String teamId, @Nullable String serviceUrl) {
        boolean known = store.channel(system.id(), channelId).map(ChatStore.Channel::botMember).orElse(false);
        if (!known) {
            store.channel(system.companyId(), system.id(), channelId, teamId, null, null, true, null, null,
                    time.now());
            changed(system, channelId);
        }
        if (serviceUrl != null) {
            store.channelServiceUrl(system.id(), channelId, serviceUrl);
        }
    }

    /**
     * The platform said the channel is gone for the bot ({@code channel_not_found}, {@code not_in_channel},
     * {@code is_archived}) while the outbox posted to it: the bot is no longer in it, and its trigger channels turn
     * {@code UNAVAILABLE}.
     */
    void gone(UUID companyId, ChatSystemInfo system, String channelId) {
        log.info("The chat system {} lost the channel {}", system.id(), channelId);
        transactions.runInCompany(companyId, () -> {
            store.channel(companyId, system.id(), channelId, null, null, null, false, null, null, time.now());
            changed(system, channelId);
        });
    }

    /**
     * Slack: the bot's channels as {@code users.conversations} lists them now (at most once a minute per system and
     * replica, {@link #REFRESH_EVERY}), kept, outside any transaction; Teams
     * lists none (its channels arrive with the bot's installation in a team). The channels the bot left meanwhile stay
     * as they were kept.
     */
    void refresh(ChatSystemInfo system) {
        NoTransaction.require("Listing the bot's channels");
        // Without a bot there is nothing to ask: the minute stays unclaimed, so that the list right after the bot
        // is set asks Slack.
        if (!system.slack() || !system.hasBot()) {
            return;
        }
        Instant claimed = claimRefresh(system.id());
        if (claimed == null) {
            return;
        }
        ChatPoster.Sent<List<ChatCalls.SlackChannel>> listed = poster.call(system.companyId(), system.id(),
                credential -> calls.slackChannels(credential.system().system(), credential.secret(), null),
                ChatPoster.Attempts.RETRIED);
        if (!listed.ok() || listed.value() == null) {
            // A bot that could not be used (refused, suspended, not answering) gives the minute back.
            refreshed.remove(system.id(), claimed);
            return;
        }
        transactions.runInCompany(system.companyId(), () -> {
            for (ChatCalls.SlackChannel channel : listed.value()) {
                boolean was = store.channel(system.id(), channel.id())
                    .map(found -> found.botMember() && !found.archived() && !"SHARED".equals(found.kind()))
                    .orElse(false);
                store.channel(system.companyId(), system.id(), channel.id(), null, channel.name(),
                        channel.shared() ? "SHARED" : channel.privateChannel() ? "PRIVATE" : "PUBLIC", true,
                        channel.archived(), false, time.now());
                if (!was) {
                    changed(system, channel.id());
                }
            }
        });
    }

    /** Claims the minute to read the system's channels from Slack: the claim's instant, or null when it is taken. */
    private @Nullable Instant claimRefresh(UUID systemId) {
        Instant now = time.now();
        boolean[] claimed = {false};
        refreshed.compute(systemId, (id, last) -> {
            if (last != null && last.plus(REFRESH_EVERY).isAfter(now)) {
                return last;
            }
            claimed[0] = true;
            return now;
        });
        return claimed[0] ? now : null;
    }

    /**
     * Forgets when the system's channels were last read from Slack: its bot was set, replaced or removed, or the system
     * resumed, so that the next list asks Slack at once.
     */
    void forgetRefresh(UUID systemId) {
        refreshed.remove(systemId);
    }

    /** The channel's name, for words; null when the bot does not know it. */
    @Nullable String name(UUID systemId, String channelId) {
        return store.channel(systemId, channelId).map(ChatStore.Channel::name).orElse(null);
    }

    /** The channels of the system the bot knows, by id. */
    Map<String, ChatStore.Channel> byId(UUID systemId, List<String> channelIds) {
        Map<String, ChatStore.Channel> found = new java.util.LinkedHashMap<>();
        store.channels(systemId, channelIds).forEach(channel -> found.put(channel.channelId(), channel));
        return found;
    }
}
