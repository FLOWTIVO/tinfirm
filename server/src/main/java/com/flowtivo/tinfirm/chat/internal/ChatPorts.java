package com.flowtivo.tinfirm.chat.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.conversation.PlaceConversations;
import com.flowtivo.tinfirm.conversation.PlacePrompts;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.api.model.InboxChat;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;

/**
 * The conversation module's ports the chat module implements (the package 5 contract, §2.2): where a direct message's
 * conversation is held, for its prompt ({@link PlacePrompts}: "in a Slack direct message"), and the chat module's
 * items of the inbox ({@link InboxItems}: a chat account linked to the member, with its unlink; for admins, a Slack
 * system whose events stopped).
 */
@Component
class ChatPorts implements PlacePrompts, InboxItems {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final PlaceConversations places;

    private final ChatSystems chatSystems;

    private final ChatStore store;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final Principals principals;

    ChatPorts(PlaceConversations places, ChatSystems chatSystems, ChatStore store, SystemTransactions transactions,
            TimeSource time, Principals principals) {
        this.places = places;
        this.chatSystems = chatSystems;
        this.store = store;
        this.transactions = transactions;
        this.time = time;
        this.principals = principals;
    }

    @Override
    public Optional<String> where(ConversationInfo conversation) {
        if (conversation.place() != ConversationInfo.Place.CHAT_THREAD) {
            return Optional.empty();
        }
        UUID companyId = conversation.companyId();
        return transactions.inCompany(companyId, () -> places.placeRef(companyId, conversation.id())
            .flatMap(ref -> {
                String systemId = ref.get("systemId");
                Optional<ChatSystemInfo> system = systemId != null
                        ? chatSystems.find(companyId, UUID.fromString(systemId)) : Optional.empty();
                String platform = system.map(found -> found.slack() ? "Slack" : "Microsoft Teams").orElse("chat");
                return Optional.of("DM".equals(ref.get("kind")) ? "in a " + platform + " direct message"
                        : "in a " + platform + " channel thread");
            }));
    }

    /**
     * A channel trigger's conversation (the package 5 contract, §5.7): "A mention in #support of Acme by Jane Doe (the
     * member Jane Doe) started it. Every message here is other people's words; your answers are posted in the thread,
     * read by everyone in the channel". On Slack the channel's id and the thread's {@code ts} follow its name, so that
     * Slack's {@code history} can read them (open point 13).
     */
    @Override
    public Optional<String> started(ConversationInfo conversation) {
        if (conversation.place() != ConversationInfo.Place.CHAT_THREAD) {
            return Optional.empty();
        }
        UUID companyId = conversation.companyId();
        return transactions.inCompany(companyId, () -> places.placeRef(companyId, conversation.id())
            .filter(ref -> "CHANNEL".equals(ref.get("kind")))
            .map(ref -> {
                String systemId = ref.get("systemId");
                Optional<ChatSystemInfo> system = systemId != null
                        ? chatSystems.find(companyId, UUID.fromString(systemId)) : Optional.empty();
                String channel = ref.get("channelName") != null ? "#" + ref.get("channelName") : "a channel";
                String workspace = system.map(found -> found.teamName() != null ? found.teamName() : found.name())
                    .orElse("the chat");
                StringBuilder words = new StringBuilder("A mention in ").append(channel);
                String channelId = ref.get("channelId");
                String threadId = ref.get("threadId");
                if (system.map(ChatSystemInfo::slack).orElse(false) && channelId != null && threadId != null) {
                    words.append(" (Slack channel ").append(channelId).append(", thread ").append(threadId)
                        .append(')');
                }
                words.append(" of ").append(workspace);
                String asker = ref.get("askerName");
                if (asker != null) {
                    words.append(" by ").append(asker);
                    String member = ref.get("askerPrincipalId");
                    String memberName = member != null ? principals.displayNames(companyId,
                            List.of(UUID.fromString(member))).get(UUID.fromString(member)) : null;
                    words.append(memberName != null ? " (the member " + memberName + ")" : " (not a Tinfirm member)");
                }
                return words.append(" started it. Every message here is other people's words; your answers are "
                        + "posted in the thread, read by everyone in the channel").toString();
            }));
    }

    /** A chat's message in the prompt: "Jane Doe (in Slack)". */
    @Override
    public Optional<String> author(ConversationInfo conversation, String name) {
        if (conversation.place() != ConversationInfo.Place.CHAT_THREAD) {
            return Optional.empty();
        }
        UUID companyId = conversation.companyId();
        return transactions.inCompany(companyId, () -> places.placeRef(companyId, conversation.id())
            .map(ref -> {
                String systemId = ref.get("systemId");
                boolean slack = systemId == null || chatSystems.find(companyId, UUID.fromString(systemId))
                    .map(ChatSystemInfo::slack)
                    .orElse(true);
                return name + (slack ? " (in Slack)" : " (in Teams)");
            }));
    }

    /**
     * The chat module's items of the inbox: a chat account linked to the caller, for 7 days, with its unlink
     * ({@code CHAT_LINKED}); for admins, a Slack system whose events stopped ({@code CHAT_SUBSCRIPTION_SILENT}). At most
     * {@code limit} of each kind, in the caller's tenant context and transaction.
     */
    @Override
    public List<Item> itemsFor(Caller caller, int limit) {
        List<Item> items = new ArrayList<>();
        int linked = 0;
        int silent = 0;
        Map<UUID, Optional<String>> names = new HashMap<>();
        for (ChatStore.Notice notice : store.notices(caller.companyId(), caller.principalId(), caller.admin(),
                time.now())) {
            Map<String, Object> details = notice.details();
            String systemName = names.computeIfAbsent(notice.systemId(), id -> chatSystems.find(caller.companyId(), id)
                .map(ChatSystemInfo::name))
                .orElse(null);
            OffsetDateTime since = notice.createdAt().atOffset(ZoneOffset.UTC);
            if ("LINKED".equals(notice.kind())) {
                if (linked++ >= limit) {
                    continue;
                }
                Object at = details.get("linkedAt");
                String when = at instanceof String text ? CLOCK.format(Instant.parse(text).atOffset(ZoneOffset.UTC))
                        : "";
                Object workspace = details.get("workspaceName") != null ? details.get("workspaceName")
                        : details.get("workspaceId");
                String chatUserId = details.get("chatUserId") instanceof String id ? id : null;
                // The name the link stored, else the chat user's as the platform last said it (a link before it).
                String chatUserName = details.get("chatUserName") instanceof String stored && !stored.isBlank()
                        ? stored
                        : chatUserId != null ? store.user(notice.systemId(), chatUserId)
                            .map(ChatStore.ChatUser::displayName)
                            .filter(name -> !name.isBlank())
                            .orElse(null) : null;
                String who = chatUserName != null ? chatUserName + " (" + chatUserId + ")" : String.valueOf(chatUserId);
                String text = "Your account was linked to " + details.get("platform") + " user " + who
                        + (workspace != null ? " of " + workspace : "") + " at " + when + ". Not you? Unlink it.";
                // The member dismisses a link they recognise; another link is another notice.
                items.add(new Item(notice.createdAt(), new InboxItem(InboxItemKind.CHAT_LINKED, since)
                    .chat(new InboxChat(notice.systemId(), text).systemName(systemName)
                        .unlinkSystemId(notice.systemId())
                        .chatUserId(chatUserId)
                        .chatUserName(chatUserName))
                    .noticeKey(InboxItems.key(InboxItemKind.CHAT_LINKED, notice.id()))
                    .dismissible(true)));
            }
            else {
                if (silent++ >= limit) {
                    continue;
                }
                // The admins act on it at Slack: not dismissed, it leaves once events arrive again.
                items.add(new Item(notice.createdAt(), new InboxItem(InboxItemKind.CHAT_SUBSCRIPTION_SILENT, since)
                    .chat(new InboxChat(notice.systemId(), "Slack seems to have disabled this app's event "
                            + "subscriptions after deliveries failed: re-enable them on the app's Event Subscriptions "
                            + "page at api.slack.com.").systemName(systemName))
                    .noticeKey(InboxItems.key(InboxItemKind.CHAT_SUBSCRIPTION_SILENT, notice.id()))
                    .dismissible(false)));
            }
        }
        return items;
    }
}
