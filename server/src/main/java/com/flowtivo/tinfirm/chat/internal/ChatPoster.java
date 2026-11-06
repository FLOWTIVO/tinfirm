package com.flowtivo.tinfirm.chat.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;

/**
 * The bot's calls to its platform for the chat module (the package 5 contract, §5.9, C12): posts, updates, the
 * acknowledgement of a message, files, and its reads of a user. Each runs outside any transaction with the bot's
 * credential ({@link ChatBot}); a refused credential is revealed again once and then counts ({@code Accounts.refused}).
 * A post is split and formatted for its platform ({@link ChatMarkdown}, {@link ChatCards}); what the bot posted for a
 * conversation's card is recorded ({@code chat_post}), so that a press counts only on the bot's own card (C10).
 * <p>
 * Two ways to call: the outbox's writer ({@link ChatPlaceWriter}) makes {@link Attempts#ONCE} and answers what came of
 * it ({@link Sent}), the delivery then trying again as package 4's rows are; the bot's tells (refusals, hints, the
 * picker, the sign-in link) make {@link Attempts#RETRIED}: a {@code 429} waits for its {@code Retry-After} (at most 30
 * seconds), a {@code 5xx} or no answer is tried again after 1, 2 and 4 seconds.
 */
@Component
class ChatPoster {

    private static final Logger log = LoggerFactory.getLogger(ChatPoster.class);

    static final int ATTEMPTS = 4;

    static final Duration MAX_WAIT = Duration.ofSeconds(30);

    /** Where a post goes: Slack's channel and thread, or Teams' conversation at its {@code serviceUrl}. */
    record Target(String channelId, @Nullable String threadId, @Nullable String serviceUrl) {
    }

    /** How often a call is made. */
    enum Attempts {
        /** Once: the outbox tries again. */
        ONCE,
        /** Up to four times, waiting in between: a tell. */
        RETRIED
    }

    /**
     * What a call came to: its value; or that no bot can be used now ({@code noBot}: none, refused, suspended, its
     * system paused), the failure that ended it, and whether a part was posted before it failed ({@code partial}).
     */
    record Sent<T>(@Nullable T value, @Nullable String noBot, CallResult.@Nullable Failure failure, boolean partial) {

        static <T> Sent<T> ok(T value) {
            return new Sent<>(value, null, null, false);
        }

        static <T> Sent<T> noBot(String why) {
            return new Sent<>(null, why, null, false);
        }

        static <T> Sent<T> failed(CallResult.Failure failure) {
            return new Sent<>(null, null, failure, false);
        }

        boolean ok() {
            return noBot == null && failure == null;
        }

        Optional<T> optional() {
            return ok() ? Optional.ofNullable(value) : Optional.empty();
        }

        <R> Sent<R> as() {
            return new Sent<>(null, noBot, failure, partial);
        }
    }

    /** What a post is for, recorded with it. */
    record Recorded(String purpose, @Nullable UUID conversationId, @Nullable UUID decisionId,
            @Nullable UUID bindingId) {

        static Recorded of(String purpose, @Nullable UUID conversationId) {
            return new Recorded(purpose, conversationId, null, null);
        }
    }

    private final ChatBot bot;

    private final ChatCalls calls;

    private final ChatStore store;

    private final SystemTransactions transactions;

    private final TimeSource time;

    ChatPoster(ChatBot bot, ChatCalls calls, ChatStore store, SystemTransactions transactions, TimeSource time) {
        this.bot = bot;
        this.calls = calls;
        this.store = store;
        this.transactions = transactions;
        this.time = time;
    }

    /**
     * Posts the message, split in parts; more than {@link ChatMarkdown#MAX_PARTS} post the first and then the whole
     * answer as {@code answer.md} (Slack) or a link to it in Tinfirm (Teams). Answers the first part's id.
     */
    Optional<String> post(UUID companyId, UUID systemId, Target target, ChatCards.Message message,
            @Nullable Recorded recorded) {
        return post(companyId, systemId, target, message, recorded, Attempts.RETRIED).optional();
    }

    /** {@link #post}, made as {@code attempts} says, answering what came of it. */
    Sent<String> post(UUID companyId, UUID systemId, Target target, ChatCards.Message message,
            @Nullable Recorded recorded, Attempts attempts) {
        NoTransaction.require("A post to a chat");
        ChatBot.Credential credential = bot.credential(companyId, systemId).orElse(null);
        if (credential == null) {
            log.info("Nothing posted to the chat system {}: it has no usable bot", systemId);
            return Sent.noBot("the chat system has no usable bot");
        }
        boolean slack = credential.system().slack();
        String text = slack ? ChatMarkdown.toSlack(message.markdown()) : ChatMarkdown.toTeams(message.markdown());
        List<String> parts = ChatMarkdown.split(text, slack ? ChatMarkdown.SLACK_PART : ChatMarkdown.TEAMS_PART);
        boolean tooLong = parts.size() > ChatMarkdown.MAX_PARTS;
        if (tooLong) {
            parts = List.of(parts.getFirst());
        }
        String first = null;
        for (int i = 0; i < parts.size(); i++) {
            boolean last = i == parts.size() - 1;
            String part = parts.get(i);
            ChatCards.Message shown = tooLong && !slack && message.pageUrl() != null
                    ? message.withMarkdown(part + "\n\nRead the full answer in Tinfirm: " + message.pageUrl()) : message;
            Sent<String> posted = call(companyId, systemId, current -> {
                if (current.system().slack()) {
                    return calls.slackPost(current.system().system(), current.secret(), new ChatCalls.SlackMessage(
                            target.channelId(), target.threadId(), ChatCards.slackFallback(part),
                            ChatCards.slackBlocks(shown, part, last), ChatCards.username(shown)));
                }
                return calls.teamsPost(current.system().system(), current.teams(),
                        java.util.Objects.requireNonNull(target.serviceUrl()), target.channelId(),
                        ChatCards.teamsActivity(shown, tooLong ? shown.markdown() : part, last));
            }, attempts);
            if (!posted.ok()) {
                return new Sent<>(null, posted.noBot(), posted.failure(), first != null);
            }
            if (first == null) {
                first = Objects.requireNonNullElse(posted.value(), "");
                if (recorded != null) {
                    String id = first;
                    transactions.runInCompany(companyId, () -> store.post(companyId, new ChatStore.Post(Ids.newId(),
                            systemId, recorded.conversationId(), recorded.decisionId(), recorded.bindingId(),
                            recorded.purpose(), target.channelId(), target.threadId(), id), time.now()));
                }
            }
        }
        if (tooLong && slack) {
            upload(companyId, systemId, target, "answer.md", message.markdown().getBytes(StandardCharsets.UTF_8));
        }
        return Sent.ok(Objects.requireNonNullElse(first, ""));
    }

    /** Replaces a message the bot posted. */
    boolean update(UUID companyId, UUID systemId, Target target, String externalId, ChatCards.Message message) {
        return update(companyId, systemId, target, externalId, message, Attempts.RETRIED).ok();
    }

    /** {@link #update}, made as {@code attempts} says, answering what came of it. */
    Sent<Boolean> update(UUID companyId, UUID systemId, Target target, String externalId, ChatCards.Message message,
            Attempts attempts) {
        NoTransaction.require("A post to a chat");
        return call(companyId, systemId, current -> {
            if (current.system().slack()) {
                String text = ChatMarkdown.toSlack(message.markdown());
                return calls.slackUpdate(current.system().system(), current.secret(), target.channelId(), externalId,
                        ChatCards.slackFallback(text), ChatCards.slackBlocks(message, ChatStore.cut(text, 11_000),
                                true));
            }
            return calls.teamsUpdate(current.system().system(), current.teams(),
                    java.util.Objects.requireNonNull(target.serviceUrl()), target.channelId(), externalId,
                    ChatCards.teamsActivity(message, ChatMarkdown.toTeams(message.markdown()), true));
        }, attempts);
    }

    /**
     * Tells one chat user only: Slack's ephemeral message in the channel; on Teams, which has none, a message in the
     * conversation (a direct message's own).
     */
    void tell(UUID companyId, UUID systemId, Target target, String userId, String text) {
        call(companyId, systemId, current -> current.system().slack()
                ? calls.slackEphemeral(current.system().system(), current.secret(), target.channelId(), userId,
                        ChatMarkdown.toSlack(text))
                : calls.teamsPost(current.system().system(), current.teams(),
                        java.util.Objects.requireNonNull(target.serviceUrl()), target.channelId(),
                        ChatCards.teamsActivity(ChatCards.Message.of(text), ChatMarkdown.toTeams(text), true))
                    .map(id -> true));
    }

    /** The message was taken (§5.9 {@code RECEIVED}): Slack's {@code eyes} on it, Teams' typing. */
    Sent<Boolean> received(UUID companyId, UUID systemId, Target target, String messageTs, Attempts attempts) {
        return call(companyId, systemId, current -> current.system().slack()
                ? calls.slackReact(current.system().system(), current.secret(), target.channelId(), messageTs,
                        "eyes")
                : calls.teamsPost(current.system().system(), current.teams(),
                        java.util.Objects.requireNonNull(target.serviceUrl()), target.channelId(),
                        Map.of("type", "typing")).map(id -> true), attempts);
    }

    /** A file into a Slack thread; Teams takes none (open point 12). */
    Optional<String> upload(UUID companyId, UUID systemId, Target target, String fileName, byte[] content) {
        return upload(companyId, systemId, target, fileName, content, Attempts.RETRIED).optional();
    }

    Sent<String> upload(UUID companyId, UUID systemId, Target target, String fileName, byte[] content,
            Attempts attempts) {
        return call(companyId, systemId, current -> current.system().slack()
                ? calls.slackUpload(current.system().system(), current.secret(), target.channelId(),
                        target.threadId(), fileName, content)
                : CallResult.<String>failed(CallResult.Kind.INVALID, "Teams takes no files"), attempts);
    }

    /** A Slack file of a message, with the bot's token. */
    Optional<byte[]> download(UUID companyId, UUID systemId, String url, long maxBytes) {
        return call(companyId, systemId, current -> calls.slackDownload(current.system().system(), current.secret(),
                url, maxBytes));
    }

    /** Opens the bot's direct message with a user: Slack's channel, Teams' conversation id. */
    Optional<String> openDirect(UUID companyId, UUID systemId, String userId, @Nullable String serviceUrl) {
        return call(companyId, systemId, current -> current.system().slack()
                ? calls.slackOpenDirect(current.system().system(), current.secret(), userId)
                : calls.teamsOpenDirect(current.system().system(), current.teams(),
                        java.util.Objects.requireNonNull(serviceUrl), userId));
    }

    /** A call with the bot's credential, tried again as the class says; empty when it did not succeed. */
    <T> Optional<T> call(UUID companyId, UUID systemId, Function<ChatBot.Credential, CallResult<T>> work) {
        return call(companyId, systemId, work, Attempts.RETRIED).optional();
    }

    /** A call with the bot's credential, made as {@code attempts} says; answers what came of it. */
    <T> Sent<T> call(UUID companyId, UUID systemId, Function<ChatBot.Credential, CallResult<T>> work,
            Attempts attempts) {
        ChatBot.Credential credential = bot.credential(companyId, systemId).orElse(null);
        if (credential == null) {
            return Sent.noBot("the chat system has no usable bot");
        }
        int most = attempts == Attempts.ONCE ? 1 : ATTEMPTS;
        boolean revealedAgain = false;
        CallResult.Failure last = null;
        for (int attempt = 1; attempt <= most; attempt++) {
            CallResult<T> result = work.apply(credential);
            if (result.failure() == null) {
                return Sent.ok(java.util.Objects.requireNonNull(result.value()));
            }
            CallResult.Failure failure = result.failure();
            last = failure;
            if (failure.kind() == CallResult.Kind.UNAUTHORIZED) {
                if (revealedAgain) {
                    bot.refused(credential, failure.message());
                    return Sent.noBot("the platform refused the bot's token: " + failure.message());
                }
                revealedAgain = true;
                credential = bot.revealAgain(credential).orElse(null);
                if (credential == null) {
                    return Sent.noBot("the chat system has no usable bot");
                }
                // The credential revealed again is tried once more, whatever the attempts.
                attempt--;
                continue;
            }
            if (!failure.retryable() || attempt == most) {
                log.info("A call to the chat system {} failed: {}", systemId, failure.message());
                return Sent.failed(failure);
            }
            Duration wait = failure.retryAfter() != null ? failure.retryAfter()
                    : Duration.ofSeconds(1L << (attempt - 1));
            if (wait.compareTo(MAX_WAIT) > 0) {
                log.info("A call to the chat system {} is rate limited for {}: given up", systemId, wait);
                return Sent.failed(failure);
            }
            try {
                Thread.sleep(wait.toMillis());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Sent.failed(failure);
            }
        }
        return Sent.failed(java.util.Objects.requireNonNull(last));
    }
}
