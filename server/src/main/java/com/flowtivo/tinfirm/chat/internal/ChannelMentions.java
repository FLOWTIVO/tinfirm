package com.flowtivo.tinfirm.chat.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.PlaceConversations;
import com.flowtivo.tinfirm.identity.CompanyNames;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.trigger.ChannelTriggers;

/**
 * Mentions of the bot in a channel (the package 5 contract, §5.5, §5.7, C7, C8): {@code ChannelMentions.receive},
 * after the receiving rules and the audience (any {@code MEMBER} of the workspace, linked or not; never a guest or
 * another organisation's user, C6). In order:
 * <ol>
 * <li><b>The company's stop</b> (the package 6 contract, §5.4): a mention fires nothing, is stored nowhere and is
 * never answered later; the chat user is told at most once an hour ({@code COMPANY_STOPPED}).</li>
 * <li><b>The thread</b>: Slack's thread {@code ts}, or the message's own when it is top-level; a Teams channel thread's
 * root message. A conversation on it: an ended one takes nothing ("mention me in a new message",
 * {@code THREAD_ENDED}); another agent's handle is told whose thread it is ({@code OTHER_AGENT}); a trigger the gate
 * stops, or whose agent is paused, is told so and stores nothing ({@code TRIGGER_PAUSED}, {@code AGENT_PAUSED},
 * package 4's decision 10); else the mention is an {@code EXTERNAL} message of it (its member when linked), which
 * answers every open question, or is the next turn's.</li>
 * <li><b>The trigger</b> of a new thread, by the grammar (§5.5): the first {@code @handle} that names an agent with a
 * trigger in the channel, else the channel's default; none: a hint, nothing stored ({@code NO_TRIGGER}).</li>
 * <li><b>Firing</b> ({@link ChannelTriggers#fire}): the gate, the agent, the rate ("Too many requests to {agent} this
 * hour"), the firing and the conversation; its reaction ({@code RECEIVED}) through the outbox.</li>
 * </ol>
 * A message that does not mention the bot never reaches here (C7). The bot's own mention and the platform's user
 * mentions ({@code <@U…>}) are never handles.
 */
@Component
class ChannelMentions implements ChatInbox.Handler {

    /** A Slack mention of a user or the bot, never a handle. */
    private static final Pattern PLATFORM_MENTION = Pattern.compile("<@[A-Z0-9]+(\\|[^>]*)?>");

    /** A candidate handle: as {@link Mentions} reads one, the longest run of letters, digits and hyphens. */
    private static final Pattern HANDLE = Pattern.compile("(?<![\\p{L}\\p{N}_\\-/.@])@([\\p{L}\\p{N}-]+)");

    private final ChatInbox inbox;

    private final ChatStore store;

    private final ChatLinks links;

    private final ChatChannels channels;

    private final ChatOutbox outbox;

    private final ChannelTriggers triggers;

    private final PlaceConversations places;

    private final Agents agents;

    private final CompanySwitches switches;

    private final CompanyNames companies;

    private final SystemTransactions transactions;

    private final TimeSource time;

    ChannelMentions(ChatInbox inbox, ChatStore store, ChatLinks links, ChatChannels channels, ChatOutbox outbox,
            ChannelTriggers triggers, PlaceConversations places, Agents agents, CompanySwitches switches,
            CompanyNames companies, SystemTransactions transactions, TimeSource time) {
        this.inbox = inbox;
        this.store = store;
        this.links = links;
        this.channels = channels;
        this.outbox = outbox;
        this.triggers = triggers;
        this.places = places;
        this.agents = agents;
        this.switches = switches;
        this.companies = companies;
        this.transactions = transactions;
        this.time = time;
        inbox.register(Inbound.MENTION, this);
    }

    /** A mention as its payload holds it: the channel, the thread and the message, the bot's mention taken out. */
    record Mention(String channelId, String threadId, String messageId, String text, @Nullable String teamId,
            @Nullable String serviceUrl, String via) {

        static @Nullable Mention of(ChatSystemInfo system, ChatStore.EventRow event) {
            Map<String, Object> payload = event.payload();
            String channel = ChatInbox.text(payload, "channel");
            String ts = ChatInbox.text(payload, "ts");
            if (channel == null || ts == null) {
                return null;
            }
            String text = Objects.requireNonNullElse(ChatInbox.text(payload, "text"), "");
            if (system.slack()) {
                String thread = ChatInbox.text(payload, "threadTs");
                if (system.botUserId() != null) {
                    text = text.replace("<@" + system.botUserId() + ">", " ");
                }
                return new Mention(channel, thread != null ? thread : ts, ts, text.strip(), system.teamId(), null,
                        "SLACK");
            }
            // Teams: a channel thread's conversation id names its root message ({channelId};messageid={rootId}).
            int marker = channel.indexOf(";messageid=");
            String channelId = marker >= 0 ? channel.substring(0, marker) : channel;
            String thread = marker >= 0 ? channel.substring(marker + ";messageid=".length()) : ts;
            return new Mention(channelId, thread, ts, text.strip(), ChatInbox.text(payload, "teamId"),
                    ChatInbox.text(payload, "serviceUrl"), "TEAMS");
        }
    }

    @Override
    public ChatInbox.Outcome handle(ChatSystemInfo system, ChatStore.EventRow event, ChatAudience.Who who) {
        Mention mention = Mention.of(system, event);
        if (mention == null) {
            return ChatInbox.Outcome.ignored("NOT_ADDRESSED");
        }
        UUID companyId = system.companyId();
        String sender = Objects.requireNonNull(event.sender());
        String askerName = who.name() != null ? who.name() : sender;
        String aadObjectId = who.aadObjectId() != null ? who.aadObjectId()
                : ChatInbox.text(event.payload(), "aadObjectId");
        UUID member = transactions.inCompany(companyId, () -> {
            store.seen(companyId, system.id(), sender, aadObjectId, who.name(), null, time.now());
            channels.seen(system, mention.channelId(), mention.teamId(), mention.serviceUrl());
            return links.memberOf(system, sender, aadObjectId).map(ChatLinks.Member::principalId).orElse(null);
        });
        if (transactions.inCompany(companyId, () -> switches.runsStopped(companyId).isPresent())) {
            // The company's stop (package 6, §5.4): nothing fires and nothing is stored, now or later.
            stopped(system, event);
            return ChatInbox.Outcome.refused("COMPANY_STOPPED");
        }
        ConversationInfo existing = transactions.inCompany(companyId, () -> places.ofThread(companyId, system.id(),
                mention.channelId(), mention.threadId()).orElse(null));
        if (existing != null) {
            return continueIn(system, event, mention, sender, askerName, member, existing);
        }
        return start(system, event, mention, sender, askerName, member);
    }

    // ---- a thread with a conversation -------------------------------------------------------------------------------

    private ChatInbox.Outcome continueIn(ChatSystemInfo system, ChatStore.EventRow event, Mention mention,
            String sender, String askerName, @Nullable UUID member, ConversationInfo conversation) {
        UUID companyId = system.companyId();
        if (!conversation.open()) {
            inbox.tell(system, event, "This conversation ended; mention me in a new message.");
            return ChatInbox.Outcome.refused("THREAD_ENDED");
        }
        if (conversation.triggerId() == null) {
            return ChatInbox.Outcome.ignored("NOT_YOURS");
        }
        UUID triggerId = conversation.triggerId();
        String own = transactions.inCompany(companyId, () -> agents.find(companyId, conversation.agentId())
            .map(AgentInfo::handle).orElse(null));
        AgentInfo named = named(companyId, mention.text());
        if (named != null && !named.id().equals(conversation.agentId())) {
            inbox.tell(system, event, "This thread is " + (own != null ? "@" + own : "another agent") + "'s; start "
                    + "a new message to ask @" + named.handle() + ".");
            return ChatInbox.Outcome.refused("OTHER_AGENT");
        }
        Continued continued = transactions.inCompany(companyId, () -> {
            Optional<ChannelTriggers.Fired.Stopped> stopped = triggers.stopped(companyId, triggerId);
            if (stopped.isPresent()) {
                return new Continued(stopped.get(), null);
            }
            PlaceConversations.Received received = places.receive(companyId, conversation.id(),
                    new PlaceConversations.PlaceMessage(PlaceConversations.Kind.EXTERNAL, member, mention.messageId(),
                            author(sender, askerName, mention.via()), mention.text().isBlank() ? "(a mention)"
                                    : mention.text(), true));
            if (received instanceof PlaceConversations.Stored) {
                outbox.received(companyId, conversation.id(), mention.messageId());
            }
            store.finish(event.id(), "HANDLED", null, time.now());
            return new Continued(null, received);
        });
        if (continued.stopped() != null) {
            inbox.tell(system, event, continued.stopped().words() + ".");
            return ChatInbox.Outcome.refused(continued.stopped().reason());
        }
        if (continued.received() instanceof PlaceConversations.Ended) {
            inbox.tell(system, event, "This conversation ended; mention me in a new message.");
        }
        return ChatInbox.Outcome.RECORDED;
    }

    private record Continued(ChannelTriggers.Fired.@Nullable Stopped stopped,
            PlaceConversations.@Nullable Received received) {
    }

    // ---- a new thread -------------------------------------------------------------------------------------------------

    private ChatInbox.Outcome start(ChatSystemInfo system, ChatStore.EventRow event, Mention mention, String sender,
            String askerName, @Nullable UUID member) {
        UUID companyId = system.companyId();
        List<ChannelTriggers.ChannelTrigger> rows = transactions.inCompany(companyId,
                () -> triggers.ofChannel(companyId, system.id(), mention.channelId()));
        AgentInfo named = named(companyId, mention.text());
        ChannelTriggers.ChannelTrigger chosen = rows.stream()
            .filter(row -> named != null ? row.agentId().equals(named.id()) : row.isDefault())
            .findFirst()
            .orElse(null);
        if (chosen == null || !chosen.available()) {
            inbox.tell(system, event, hint(companyId, named, rows));
            return ChatInbox.Outcome.refused("NO_TRIGGER");
        }
        String text = named != null ? withoutLeadingHandle(mention.text(), named.handle()) : mention.text();
        String channelName = transactions.inCompany(companyId, () -> channels.name(system.id(),
                mention.channelId()));
        ChannelTriggers.Fired fired = transactions.inCompany(companyId, () -> {
            ChannelTriggers.Fired result = triggers.fire(companyId, new ChannelTriggers.Mention(chosen.triggerId(),
                    system.id(), mention.teamId(), mention.channelId(), mention.threadId(), mention.messageId(), text,
                    sender, askerName, member, channelName, mention.serviceUrl(), mention.via()));
            if (result instanceof ChannelTriggers.Fired.Started started) {
                outbox.received(companyId, started.conversationId(), mention.messageId());
                store.finish(event.id(), "HANDLED", null, time.now());
            }
            return result;
        });
        if (fired instanceof ChannelTriggers.Fired.Stopped stopped) {
            inbox.tell(system, event, stopped.words() + ".");
            return ChatInbox.Outcome.refused(stopped.reason());
        }
        return ChatInbox.Outcome.RECORDED;
    }

    /** No trigger answers: which agents do here, if any. */
    private String hint(UUID companyId, @Nullable AgentInfo named, List<ChannelTriggers.ChannelTrigger> rows) {
        List<String> handles = transactions.inCompany(companyId, () -> rows.stream()
            .filter(ChannelTriggers.ChannelTrigger::available)
            .map(row -> agents.find(companyId, row.agentId()).map(AgentInfo::handle).orElse(null))
            .filter(Objects::nonNull)
            .map(handle -> "@" + handle)
            .distinct()
            .toList());
        String ask = handles.isEmpty() ? "" : "; ask " + String.join(" or ", handles);
        if (named != null) {
            return "No agent @" + named.handle() + " answers here" + ask + ".";
        }
        return handles.isEmpty() ? "No agent answers in this channel." : "Name the agent to ask" + ask + ".";
    }

    /** The first {@code @handle} of the text that names a live agent (§5.5), outside code. */
    private @Nullable AgentInfo named(UUID companyId, String text) {
        String plain = Mentions.withoutCode(PLATFORM_MENTION.matcher(text).replaceAll(" "));
        Matcher matcher = HANDLE.matcher(plain);
        while (matcher.find()) {
            String handle = matcher.group(1);
            AgentInfo agent = transactions.inCompany(companyId, () -> agents.byHandle(companyId, handle)
                .filter(AgentInfo::live)
                .orElse(null));
            if (agent != null) {
                return agent;
            }
        }
        return null;
    }

    /** The text without a leading handle that named the agent. */
    static String withoutLeadingHandle(String text, String handle) {
        String stripped = text.strip();
        String lead = "@" + handle;
        if (stripped.regionMatches(true, 0, lead, 0, lead.length())) {
            String rest = stripped.substring(lead.length()).replaceFirst("^[\\s,:;.!?]+", "");
            return rest.isEmpty() ? stripped : rest;
        }
        return stripped;
    }

    private static Map<String, Object> author(String sender, String name, String via) {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("id", sender);
        author.put("name", name);
        author.put("via", via);
        author.put("verified", true);
        return author;
    }

    /** The company's stop, told at most once an hour per chat user. */
    private void stopped(ChatSystemInfo system, ChatStore.EventRow event) {
        UUID companyId = system.companyId();
        Instant now = time.now();
        boolean tell = transactions.inCompany(companyId, () -> store.tellStop(system.id(),
                Objects.requireNonNull(event.sender()), now, now.minus(ChatInbox.REFUSAL_TOLD)));
        if (tell) {
            String company = companies.nameOf(companyId).orElse("your company");
            inbox.tell(system, event, "Tinfirm is stopped in " + company + " by an admin; nothing was started.");
        }
    }
}
