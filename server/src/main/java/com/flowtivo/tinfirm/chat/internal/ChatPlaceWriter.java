package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.PlaceConversations;
import com.flowtivo.tinfirm.conversation.PlaceDecisions;
import com.flowtivo.tinfirm.conversation.PlaceFiles;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.trigger.PlaceWriter;

/**
 * The chat's writer of package 4's outbox (the package 5 contract, §5.9, C12, open point 14): the rows of a
 * {@code CHAT_THREAD} conversation, written by its system's bot ({@link PlaceWriter.Writer#BOT}) through
 * {@link ChatPoster}, one attempt per delivery. A post carries the agent's name "(Tinfirm agent)" and its buttons, added
 * now from the row's cause (End on a direct message's answer, a question's options and an approval's Approve and Deny
 * while it is open, a Connect card's link); a card's post is recorded ({@code chat_post}) so that a press counts only
 * on it (C10). What came of an attempt:
 * <ul>
 * <li>no usable bot (none, refused twice, its service identity suspended): the row waits, without an attempt, and is
 * tried again every minute, at most 7 days ({@link PlaceWriter.Outcome.Waiting}); package 6's pause of the system and
 * the company's stop put it off before (the delivery's claim);</li>
 * <li>{@code 429}: tried again after its {@code Retry-After}; a {@code 5xx} or no answer: again later, as it may have
 * posted (neither platform takes an idempotency key: it may post twice, accepted);</li>
 * <li>the channel or the message gone ({@code channel_not_found}, {@code not_in_channel}, {@code is_archived}, a
 * {@code 404}): dropped, and a trigger's channel turns {@code UNAVAILABLE} ({@link ChatChannels#gone});</li>
 * <li>anything else: dropped with the reason, posted in the app.</li>
 * </ul>
 * A delivered row records the message's {@code ts} or activity id, and a {@code system_write} {@code CHAT_MESSAGE}.
 */
@Component
class ChatPlaceWriter implements PlaceWriter {

    /** How long a row waits for a usable bot before it is tried again. */
    static final Duration BOT_RETRY = Duration.ofMinutes(1);

    private final ChatPoster poster;

    private final ChatSystems chatSystems;

    private final PlaceConversations places;

    private final PlaceDecisions decisions;

    private final PlaceFiles files;

    private final ChatChannels channels;

    private final SystemTransactions transactions;

    private final TinfirmProperties tinfirm;

    ChatPlaceWriter(ChatPoster poster, ChatSystems chatSystems, PlaceConversations places, PlaceDecisions decisions,
            PlaceFiles files, ChatChannels channels, SystemTransactions transactions, TinfirmProperties tinfirm) {
        this.poster = poster;
        this.chatSystems = chatSystems;
        this.places = places;
        this.decisions = decisions;
        this.files = files;
        this.channels = channels;
        this.transactions = transactions;
        this.tinfirm = tinfirm;
    }

    @Override
    public ConversationInfo.Place place() {
        return ConversationInfo.Place.CHAT_THREAD;
    }

    @Override
    public Writer writer() {
        return Writer.BOT;
    }

    /** Where the row goes: the thread of its conversation's place. */
    private record Where(ChatSystemInfo system, ChatPoster.Target target, boolean channel, String channelId) {
    }

    @Override
    public Outcome write(Write write) {
        Row row = write.row();
        UUID companyId = row.companyId();
        Where where = transactions.inCompany(companyId, () -> where(row));
        if (where == null) {
            return new Outcome.Dropped("its chat thread or system is gone");
        }
        String page = ChatOutbox.page(tinfirm, companyId, row.conversationId());
        ChatCards.Message message = new ChatCards.Message(Objects.requireNonNullElse(row.body(), ""),
                write.agentName(), write.conversation().key(), page, List.of());
        return switch (row.kind()) {
            case CHAT_POST -> posted(row, where, message.withButtons(buttons(row, where, write.conversation())));
            case CHAT_UPDATE -> {
                if (row.targetExternalId() == null) {
                    yield new Outcome.Dropped("the card it updates was never posted");
                }
                ChatPoster.Sent<Boolean> sent = poster.update(companyId, where.system().id(), where.target(),
                        row.targetExternalId(), message, ChatPoster.Attempts.ONCE);
                yield outcome(row, where, sent, row.targetExternalId(), false);
            }
            case CHAT_REACTION -> {
                ChatPoster.Sent<Boolean> sent = poster.received(companyId, where.system().id(), where.target(),
                        Objects.requireNonNull(row.value()), ChatPoster.Attempts.ONCE);
                yield outcome(row, where, sent, row.value(), false);
            }
            case CHAT_FILE -> file(row, where);
            default -> new Outcome.Dropped("the chat's bot writes no " + row.kind());
        };
    }

    private Outcome posted(Row row, Where where, ChatCards.Message message) {
        boolean card = row.decisionId() != null && (row.cause() == com.flowtivo.tinfirm.trigger.OutboxCause.QUESTION
                || row.cause() == com.flowtivo.tinfirm.trigger.OutboxCause.APPROVAL);
        ChatPoster.Recorded recorded = card ? new ChatPoster.Recorded(row.cause().name(), row.conversationId(),
                row.decisionId(), null) : null;
        ChatPoster.Sent<String> sent = poster.post(row.companyId(), where.system().id(), where.target(), message,
                recorded, ChatPoster.Attempts.ONCE);
        return outcome(row, where, sent, sent.value(), true);
    }

    private Outcome file(Row row, Where where) {
        if (!where.system().slack() || row.attachmentId() == null) {
            return new Outcome.Dropped("a document is uploaded to Slack only");
        }
        PlaceFiles.Content content = transactions.inCompany(row.companyId(), () -> files.content(row.companyId(),
                row.conversationId(), Objects.requireNonNull(row.attachmentId())).orElse(null));
        if (content == null) {
            return new Outcome.Dropped("the document is gone");
        }
        ChatPoster.Sent<String> sent = poster.upload(row.companyId(), where.system().id(), where.target(),
                fileName(content.fileName()), content.bytes(), ChatPoster.Attempts.ONCE);
        return outcome(row, where, sent, sent.value(), true);
    }

    /** What an attempt came to, as the delivery records it. */
    private Outcome outcome(Row row, Where where, ChatPoster.Sent<?> sent, @Nullable String externalId,
            boolean post) {
        if (sent.ok()) {
            return new Outcome.Delivered(externalId, "CHAT_MESSAGE");
        }
        if (sent.noBot() != null) {
            return new Outcome.Waiting(sent.noBot(), BOT_RETRY);
        }
        CallResult.Failure failure = Objects.requireNonNull(sent.failure());
        return switch (failure.kind()) {
            case RATE_LIMITED -> new Outcome.Failed(failure.message(), true, sent.partial(), failure.retryAfter());
            case UNAVAILABLE, OTHER -> new Outcome.Failed(failure.message(), true, true, failure.retryAfter());
            case NOT_FOUND -> {
                if (post) {
                    // The channel went: the bot left it, it was archived or deleted (§5.9).
                    channels.gone(row.companyId(), where.system(), where.channelId());
                }
                yield new Outcome.Dropped(failure.message());
            }
            default -> new Outcome.Failed(failure.message(), false, sent.partial(), null);
        };
    }

    /** The conversation's thread and its system, in a short transaction; null when either went. */
    private @Nullable Where where(Row row) {
        Map<String, String> ref = places.placeRef(row.companyId(), row.conversationId()).orElse(null);
        ChatSystemInfo system = chatSystems.find(row.companyId(), row.systemId()).orElse(null);
        if (ref == null || system == null || ref.get("channelId") == null) {
            return null;
        }
        boolean channel = "CHANNEL".equals(ref.get("kind"));
        String channelId = ref.get("channelId");
        String threadId = ref.get("threadId");
        ChatPoster.Target target;
        if (system.slack()) {
            target = new ChatPoster.Target(channelId, threadId, null);
        }
        else if (channel && threadId != null) {
            // A Teams channel's thread: its root message's conversation (§5.9).
            target = new ChatPoster.Target(channelId + ";messageid=" + threadId, null, ref.get("serviceUrl"));
        }
        else {
            target = new ChatPoster.Target(channelId, null, ref.get("serviceUrl"));
        }
        return new Where(system, target, channel, channelId);
    }

    /** The buttons of a post, from its cause and the decision as it is now. */
    private List<ChatCards.Button> buttons(Row row, Where where, ConversationInfo conversation) {
        List<ChatCards.Button> buttons = new ArrayList<>();
        switch (row.cause()) {
            case RESULT -> {
                if (!where.channel()) {
                    buttons.add(ChatCards.Button.press("End", Map.of("a", "end", "c", conversation.id().toString())));
                    if (!where.system().slack()) {
                        buttons.add(ChatCards.Button.press("New conversation", Map.of("a", "new", "c",
                                conversation.id().toString())));
                    }
                }
            }
            case QUESTION, APPROVAL -> {
                PlaceDecisions.Card card = row.decisionId() == null ? null : transactions.inCompany(row.companyId(),
                        () -> decisions.find(row.companyId(), Objects.requireNonNull(row.decisionId())).orElse(null));
                if (card == null || !"OPEN".equals(card.status())) {
                    break;
                }
                String decision = card.decisionId().toString();
                if ("QUESTION".equals(card.kind())) {
                    List<String> options = card.options();
                    for (int i = 0; i < Math.min(options.size(), ChatOutbox.OPTION_BUTTONS); i++) {
                        buttons.add(ChatCards.Button.press(options.get(i), Map.of("a", "decide", "d", decision, "o",
                                options.get(i))));
                    }
                }
                else {
                    buttons.add(ChatCards.Button.primary("Approve", Map.of("a", "decide", "d", decision, "r",
                            "APPROVED")));
                    buttons.add(ChatCards.Button.danger("Deny", Map.of("a", "decide", "d", decision, "r",
                            "DENIED")));
                }
            }
            case CONNECT -> {
                if (row.url() != null) {
                    buttons.add(ChatCards.Button.link("Connect in Tinfirm", row.url()));
                }
            }
            default -> {
            }
        }
        return buttons;
    }

    /** A document's name in a chat: its last segment. */
    private static String fileName(String name) {
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
