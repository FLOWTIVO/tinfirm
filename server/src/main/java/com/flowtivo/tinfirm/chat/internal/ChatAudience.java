package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.ChatSystemInfo;

/**
 * {@code ChatAudience.of(system, chatUser)} (the package 5 contract, §5.3, C6): who a chat user is to the workspace,
 * from {@code chat_user}, read again from the platform when unknown or older than an hour, outside any transaction.
 * <ul>
 * <li>Slack ({@code users.info}): {@code MEMBER} with the system's {@code team_id}, neither restricted,
 * ultra-restricted, a stranger, a bot nor deleted; {@code GUEST} restricted or ultra-restricted; {@code EXTERNAL}
 * another workspace, a stranger, or {@code users.info} refused (the event's {@code user_team} is never the rule);
 * {@code BOT}; {@code DELETED}.</li>
 * <li>Teams (the conversation's roster): {@code MEMBER} with {@code userRole} {@code user}, the system's tenant and an
 * {@code aadObjectId}; {@code GUEST} for {@code guest}; {@code EXTERNAL} for another tenant, {@code anonymous}, or no
 * {@code aadObjectId} and unknown to the roster, whatever the activity's own role says.</li>
 * </ul>
 * Only {@code MEMBER} is served.
 */
@Component
class ChatAudience {

    static final Duration FRESH = Duration.ofHours(1);

    enum Audience {
        MEMBER, GUEST, EXTERNAL, BOT, DELETED
    }

    /** Who the chat user is, and their name in the chat (never proof). */
    record Who(Audience audience, @Nullable String name, @Nullable String aadObjectId) {

        boolean member() {
            return audience == Audience.MEMBER;
        }
    }

    private final ChatStore store;

    private final ChatPoster poster;

    private final ChatCalls calls;

    private final ChatBot bot;

    private final SystemTransactions transactions;

    private final TimeSource time;

    ChatAudience(ChatStore store, ChatPoster poster, ChatCalls calls, ChatBot bot, SystemTransactions transactions,
            TimeSource time) {
        this.store = store;
        this.poster = poster;
        this.calls = calls;
        this.bot = bot;
        this.transactions = transactions;
        this.time = time;
    }

    /**
     * Who the chat user is, outside any transaction.
     *
     * @param conversationId Teams: the conversation whose roster names the user; null on Slack
     * @param serviceUrl     Teams: its {@code serviceUrl}
     */
    Who of(ChatSystemInfo system, String userId, @Nullable String conversationId, @Nullable String serviceUrl) {
        UUID companyId = system.companyId();
        ChatStore.ChatUser known = transactions.inCompany(companyId, () -> store.user(system.id(), userId)
            .orElse(null));
        Instant now = time.now();
        if (known != null && known.audience() != null && known.checkedAt() != null
                && known.checkedAt().plus(FRESH).isAfter(now)) {
            return new Who(Audience.valueOf(known.audience()), known.displayName(), known.aadObjectId());
        }
        Who who = system.slack() ? slack(system, userId) : teams(system, userId, conversationId, serviceUrl);
        if (who == null) {
            // The platform could not be asked: what was known before, else the event is tried again later.
            if (known != null && known.audience() != null) {
                return new Who(Audience.valueOf(known.audience()), known.displayName(), known.aadObjectId());
            }
            throw new IllegalStateException("The platform could not be asked who the chat user is");
        }
        transactions.runInCompany(companyId, () -> {
            store.seen(companyId, system.id(), userId, who.aadObjectId(), who.name(), null, now);
            store.checked(system.id(), userId, who.audience().name(), system.workspace(), who.name(),
                    who.aadObjectId(), now);
        });
        return who;
    }

    private @Nullable Who slack(ChatSystemInfo system, String userId) {
        ChatBot.Credential credential = bot.credential(system.companyId(), system.id()).orElse(null);
        if (credential == null) {
            return null;
        }
        CallResult<ChatCalls.SlackUser> found = calls.slackUser(system.system(), credential.secret(), userId);
        if (found.failure() != null) {
            if (found.failure().kind() == CallResult.Kind.NOT_FOUND
                    || found.failure().kind() == CallResult.Kind.INVALID) {
                return new Who(Audience.EXTERNAL, null, null);
            }
            if (found.failure().kind() == CallResult.Kind.UNAUTHORIZED) {
                bot.revealAgain(credential);
            }
            return null;
        }
        ChatCalls.SlackUser user = Objects.requireNonNull(found.value());
        return new Who(slackAudience(user, system.teamId()), user.name(), null);
    }

    static Audience slackAudience(ChatCalls.SlackUser user, @Nullable String teamId) {
        if (user.deleted()) {
            return Audience.DELETED;
        }
        if (user.bot()) {
            return Audience.BOT;
        }
        if (user.stranger() || teamId == null || !teamId.equals(user.teamId())) {
            return Audience.EXTERNAL;
        }
        if (user.restricted() || user.ultraRestricted()) {
            return Audience.GUEST;
        }
        return Audience.MEMBER;
    }

    private @Nullable Who teams(ChatSystemInfo system, String userId, @Nullable String conversationId,
            @Nullable String serviceUrl) {
        if (conversationId == null || serviceUrl == null) {
            return null;
        }
        ChatBot.Credential credential = bot.credential(system.companyId(), system.id()).orElse(null);
        if (credential == null) {
            return null;
        }
        CallResult<ChatCalls.TeamsMember> found = calls.teamsMember(system.system(), credential.teams(), serviceUrl,
                conversationId, userId);
        if (found.failure() != null) {
            return found.failure().kind() == CallResult.Kind.NOT_FOUND ? new Who(Audience.EXTERNAL, null, null)
                    : null;
        }
        ChatCalls.TeamsMember member = Objects.requireNonNull(found.value());
        return new Who(teamsAudience(member, system.tenantId()), member.name(), member.aadObjectId());
    }

    static Audience teamsAudience(ChatCalls.TeamsMember member, @Nullable String tenantId) {
        String role = member.userRole();
        if ("guest".equalsIgnoreCase(role)) {
            return Audience.GUEST;
        }
        if ("anonymous".equalsIgnoreCase(role) || member.aadObjectId() == null || member.aadObjectId().isBlank()) {
            return Audience.EXTERNAL;
        }
        if (member.tenantId() != null && tenantId != null && !member.tenantId().equalsIgnoreCase(tenantId)) {
            return Audience.EXTERNAL;
        }
        return Audience.MEMBER;
    }

    /** The poster, for the handlers that tell a refusal. */
    ChatPoster poster() {
        return poster;
    }
}
