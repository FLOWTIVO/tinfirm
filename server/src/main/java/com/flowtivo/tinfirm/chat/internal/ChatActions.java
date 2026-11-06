package com.flowtivo.tinfirm.chat.internal;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.conversation.PlaceConversations;
import com.flowtivo.tinfirm.conversation.PlaceDecisions;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.ChatSystemInfo;

/**
 * The bot's buttons, pressed (the package 5 contract, §5.6, §5.8, C10): a Slack {@code block_actions}, or a Teams
 * {@code Action.Submit} that arrives as a message carrying the card's data. A press is verified (C1), stored once (C3)
 * and handled in its thread's order like every other event; it counts only from a member of the workspace (C6).
 * <ul>
 * <li>{@code decide}: a question's option, an approval's Approve or Deny, on the card the bot posted for that decision
 * (else {@code IGNORED}), by the chat user linked to the member who may decide it ({@code PlaceDecisions.decide});
 * anyone else is told so, alone, and the decision stays open (C10). Text never decides.</li>
 * <li>{@code pick}: the agent picker, by the held message's own sender only.</li>
 * <li>{@code end}: the End button of an answer; {@code new}: Teams' "New conversation".</li>
 * </ul>
 * An unlinked member's press in a direct message waits for a sign-in link, as a direct message does; in a channel's
 * thread (package 5b) a question's option counts from any member of the workspace, linked or not, and an approval only
 * from a linked member of the trigger's team (or its author), anyone else told so alone (§5.8).
 */
@Component
class ChatActions implements ChatInbox.Handler {

    private static final Logger log = LoggerFactory.getLogger(ChatActions.class);

    private final ChatInbox inbox;

    private final ChatStore store;

    private final ChatLinks links;

    private final DirectMessages directMessages;

    private final PlaceDecisions decisions;

    private final PlaceConversations places;

    private final Conversations conversations;

    private final Agents agents;

    private final ChatPoster poster;

    private final SystemTransactions transactions;

    private final TimeSource time;

    ChatActions(ChatInbox inbox, ChatStore store, ChatLinks links, DirectMessages directMessages,
            PlaceDecisions decisions, PlaceConversations places, Conversations conversations, Agents agents,
            ChatPoster poster, SystemTransactions transactions, TimeSource time) {
        this.inbox = inbox;
        this.store = store;
        this.links = links;
        this.directMessages = directMessages;
        this.decisions = decisions;
        this.places = places;
        this.conversations = conversations;
        this.agents = agents;
        this.poster = poster;
        this.transactions = transactions;
        this.time = time;
        inbox.register(Inbound.ACTION, this);
    }

    @Override
    public ChatInbox.Outcome handle(ChatSystemInfo system, ChatStore.EventRow event, ChatAudience.Who who) {
        Map<String, Object> value = value(event.payload());
        String action = ChatInbox.text(value, "a");
        if (action == null) {
            return ChatInbox.Outcome.ignored("NOT_ADDRESSED");
        }
        String sender = Objects.requireNonNull(event.sender());
        UUID companyId = system.companyId();
        ChatLinks.Member member = transactions.inCompany(companyId, () -> links.memberOf(system, sender,
                who.aadObjectId() != null ? who.aadObjectId() : ChatInbox.text(event.payload(), "aadObjectId"))
            .orElse(null));
        ChatPoster.Target here = new ChatPoster.Target(Objects.requireNonNull(ChatInbox.text(event.payload(),
                "channel")), ChatInbox.threadOf(event.payload()), ChatInbox.text(event.payload(), "serviceUrl"));
        if (member == null && system.teams()) {
            member = links.automatic(system, sender, who, here).orElse(null);
        }
        if (member == null && "decide".equals(action) && inChannel(companyId, value)) {
            // A channel's card: the decision's own rule says who decides (§5.8), never a sign-in link here.
            return decide(system, event, who, null, value);
        }
        if (member == null) {
            return links.offer(system, event, who, here);
        }
        return switch (action) {
            case "decide" -> decide(system, event, who, member, value);
            case "pick" -> pick(system, event, who, member, value);
            case "end" -> end(system, event, member, value, ConversationInfo.EndedReason.USER,
                    "This conversation ended. Send a new message to start another.");
            case "new" -> end(system, event, member, value, ConversationInfo.EndedReason.SUPERSEDED,
                    "Send your next message to start a new conversation.");
            default -> ChatInbox.Outcome.ignored("NOT_ADDRESSED");
        };
    }

    /** The press's own data: Slack's button value (JSON), Teams' {@code Action.Submit} data. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> value(Map<String, Object> payload) {
        Object value = payload.get("value");
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value instanceof String json && json.startsWith("{")) {
            try {
                return ChatStore.map(json);
            }
            catch (RuntimeException ex) {
                return Map.of();
            }
        }
        return Map.of();
    }

    /** The id of the message whose button was pressed: Slack's {@code ts}, Teams' {@code replyToId}. */
    private static @Nullable String pressedMessage(Map<String, Object> payload) {
        String slack = ChatInbox.text(payload, "messageTs");
        return slack != null ? slack : ChatInbox.text(payload, "replyToId");
    }

    /** Whether the decision a press names is of a channel trigger's conversation. */
    private boolean inChannel(UUID companyId, Map<String, Object> value) {
        UUID decisionId = uuid(value.get("d"));
        if (decisionId == null) {
            return false;
        }
        return transactions.inCompany(companyId, () -> decisions.find(companyId, decisionId)
            .flatMap(card -> conversations.find(companyId, card.conversationId()))
            .map(conversation -> conversation.triggerId() != null)
            .orElse(false));
    }

    private ChatInbox.Outcome decide(ChatSystemInfo system, ChatStore.EventRow event, ChatAudience.Who who,
            ChatLinks.@Nullable Member member, Map<String, Object> value) {
        UUID companyId = system.companyId();
        UUID decisionId = uuid(value.get("d"));
        String pressed = pressedMessage(event.payload());
        String channel = ChatInbox.text(event.payload(), "channel");
        if (decisionId == null || pressed == null) {
            return ChatInbox.Outcome.ignored("NOT_ADDRESSED");
        }
        // The press names the card the bot posted for this decision, in this channel (C10).
        boolean card = transactions.inCompany(companyId, () -> store.postsOfDecision(decisionId)
            .stream()
            .anyMatch(post -> post.systemId().equals(system.id()) && pressed.equals(post.externalId())
                    && post.channelId().equals(channel)));
        if (!card) {
            return ChatInbox.Outcome.ignored("NOT_ADDRESSED");
        }
        String outcome = ChatInbox.text(value, "r");
        String option = ChatInbox.text(value, "o");
        PlaceDecisions.Choice choice = "APPROVED".equals(outcome) ? new PlaceDecisions.Approve()
                : "DENIED".equals(outcome) ? new PlaceDecisions.Deny() : new PlaceDecisions.Answer(option, null);
        String name = who.name() != null ? who.name() : member != null ? member.name()
                : Objects.requireNonNull(event.sender());
        PlaceDecisions.PlaceDecider decider = new PlaceDecisions.PlaceDecider(member != null ? member.principalId()
                : null, name, system.slack() ? "SLACK" : "TEAMS", Objects.requireNonNull(event.sender()), null);
        PlaceDecisions.Outcome decided;
        try {
            decided = transactions.inCompany(companyId, () -> {
                PlaceDecisions.Outcome result = decisions.decide(companyId, decisionId, decider, choice);
                switch (result) {
                    case PlaceDecisions.Decided ok -> store.finish(event.id(), "HANDLED", null, time.now());
                    case PlaceDecisions.NotAllowed no -> store.finish(event.id(), "REFUSED", "NOT_YOURS", time.now());
                    case PlaceDecisions.AlreadyDecided already -> store.finish(event.id(), "IGNORED", "THREAD_ENDED",
                            time.now());
                    case PlaceDecisions.Refused refused -> store.finish(event.id(), "REFUSED", "NOT_USABLE",
                            time.now());
                }
                return result;
            });
        }
        catch (ApiException ex) {
            log.info("A press on a decision of the chat system {} was refused: {}", system.id(), ex.getMessage());
            if (ex.code() == ProblemCode.COMPANY_STOPPED) {
                // G4: approving waits for the lift, in the app as here; the card stays open.
                inbox.tell(system, event, ex.getMessage() + ".");
                return ChatInbox.Outcome.refused("COMPANY_STOPPED");
            }
            inbox.tell(system, event, "This cannot be decided here: " + ex.getMessage() + ". Decide it in Tinfirm.");
            return ChatInbox.Outcome.refused("NOT_USABLE");
        }
        switch (decided) {
            case PlaceDecisions.NotAllowed no -> inbox.tell(system, event, no.why() + ".");
            case PlaceDecisions.AlreadyDecided already -> inbox.tell(system, event, "This was "
                    + ("CANCELLED".equals(already.status()) ? "cancelled" : "decided") + " already"
                    + (already.name() != null ? " by " + already.name() : "")
                    + (already.via() != null ? " in the chat" : "") + ".");
            case PlaceDecisions.Refused refused -> inbox.tell(system, event, refused.message() + ".");
            case PlaceDecisions.Decided ok -> {
                // The card is updated from the decision's event, through the outbox (ChatOutbox).
            }
        }
        return ChatInbox.Outcome.RECORDED;
    }

    private ChatInbox.Outcome pick(ChatSystemInfo system, ChatStore.EventRow event, ChatAudience.Who who,
            ChatLinks.Member member, Map<String, Object> value) {
        UUID companyId = system.companyId();
        UUID heldId = uuid(value.get("e"));
        UUID agentId = uuid(value.get("g"));
        if (heldId == null || agentId == null) {
            return ChatInbox.Outcome.ignored("NOT_ADDRESSED");
        }
        ChatStore.EventRow held = transactions.inCompany(companyId, () -> store.find(heldId).orElse(null));
        // Only the held message's own sender chooses its agent (open point 5).
        if (held == null || !"AWAITING_AGENT".equals(held.status()) || !held.systemId().equals(system.id())
                || !Objects.equals(held.sender(), event.sender())) {
            return ChatInbox.Outcome.ignored("NOT_YOURS");
        }
        AgentInfo agent = transactions.inCompany(companyId, () -> agents.find(companyId, agentId)
            .filter(AgentInfo::live)
            .orElse(null));
        if (agent == null) {
            inbox.tell(system, event, "That agent is gone. Send your message again.");
            return ChatInbox.Outcome.refused("NO_AGENT_CHOSEN");
        }
        if (Boolean.TRUE.equals(value.get("d"))) {
            boolean uses = transactions.inCompany(companyId, () -> agents.access(companyId, agentId,
                    member.userId()).uses());
            if (uses) {
                transactions.runInCompany(companyId, () -> store.defaultAgent(companyId, member.principalId(),
                        agentId, time.now()));
            }
        }
        ChatInbox.Outcome started = directMessages.startHeld(system, held, who, member, agent);
        if (started.status() != null) {
            // A start refused (the company's stop, a refusal of the conversation's): the held message is done too.
            inbox.finish(companyId, held.id(), started.status(), started.reason());
        }
        return ChatInbox.Outcome.handled();
    }

    private ChatInbox.Outcome end(ChatSystemInfo system, ChatStore.EventRow event, ChatLinks.Member member,
            Map<String, Object> value, ConversationInfo.EndedReason reason, String told) {
        UUID companyId = system.companyId();
        UUID conversationId = uuid(value.get("c"));
        if (conversationId == null) {
            return ChatInbox.Outcome.ignored("NOT_ADDRESSED");
        }
        Boolean ended = transactions.inCompany(companyId, () -> conversations.find(companyId, conversationId)
            .filter(conversation -> member.principalId().equals(conversation.personPrincipalId()))
            .map(conversation -> places.end(companyId, conversation.id(), reason, member.userId()))
            .orElse(null));
        if (ended == null) {
            inbox.tell(system, event, "This conversation is another member's.");
            return ChatInbox.Outcome.refused("NOT_YOURS");
        }
        inbox.tell(system, event, told);
        return ChatInbox.Outcome.handled();
    }

    private static @Nullable UUID uuid(@Nullable Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return UUID.fromString(text);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
