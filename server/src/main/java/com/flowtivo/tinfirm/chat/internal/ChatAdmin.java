package com.flowtivo.tinfirm.chat.internal;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.ChatAccounts;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ChatBotRequest;
import com.flowtivo.tinfirm.shared.api.model.ChatSecretsRequest;
import com.flowtivo.tinfirm.shared.api.model.ChatSettings;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.system.Systems;

/**
 * The admins' side of a chat system (the package 5 contract, §3.2, §5.1, §6, C9): its bot, set, replaced and removed
 * only here; a Slack system's own secrets; the company's chat rates. OWNER and ADMIN ({@code MANAGE_SYSTEMS}). A bot's
 * token is checked with its platform between the transaction that authorises the caller and the one that stores it
 * (CONVENTIONS §10).
 */
@Component
class ChatAdmin {

    private final ChatSystems chatSystems;

    private final Systems systems;

    private final ChatAccounts chatAccounts;

    private final Accounts accounts;

    private final Principals principals;

    private final ChatCalls calls;

    private final ChatBot bot;

    private final SlackVerifier slackVerifier;

    private final InboundLimits limits;

    private final ChatStore store;

    private final CompanyAccessGuard guard;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final TimeSource time;

    ChatAdmin(ChatSystems chatSystems, Systems systems, ChatAccounts chatAccounts, Accounts accounts, Principals principals,
            ChatCalls calls, ChatBot bot, SlackVerifier slackVerifier, InboundLimits limits, ChatStore store,
            CompanyAccessGuard guard, AuditLog auditLog, ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager, TimeSource time) {
        this.chatSystems = chatSystems;
        this.systems = systems;
        this.chatAccounts = chatAccounts;
        this.accounts = accounts;
        this.principals = principals;
        this.calls = calls;
        this.bot = bot;
        this.slackVerifier = slackVerifier;
        this.limits = limits;
        this.store = store;
        this.guard = guard;
        this.auditLog = auditLog;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.time = time;
    }

    // ---- the bot ---------------------------------------------------------------------------------------------------

    /** What the platform said of a token: the bot's identity, and on Slack its workspace and bot user. */
    private record Checked(String identity, @Nullable String teamId, @Nullable String teamName,
            @Nullable String botUserId) {
    }

    /** {@code PUT /systems/{id}/bot}. */
    com.flowtivo.tinfirm.shared.api.model.ChatBot setBot(UUID companyId, UUID systemId, ChatBotRequest request) {
        String token = request.getToken() == null ? "" : request.getToken().strip();
        ChatSystemInfo system = inTransaction(() -> {
            guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
            if (token.isEmpty() || token.length() > 4000) {
                throw ApiException.validation("token must be 1 to 4000 characters");
            }
            ChatSystemInfo found = chatSystem(companyId, systemId);
            requireNotPaused(found);
            serviceIdentity(companyId, request.getPrincipalId());
            requireSameHolder(found, request.getPrincipalId());
            return found;
        });
        if (system.slack() && !token.startsWith("xoxb-")) {
            throw ApiException.validation("A Slack bot token starts with xoxb-");
        }
        Checked checked = check(system, token);
        return inTransaction(() -> {
            guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
            ChatSystemInfo current = chatSystems.lock(companyId, systemId).orElseThrow(ChatAdmin::systemNotFound);
            requireNotPaused(current);
            serviceIdentity(companyId, request.getPrincipalId());
            requireSameHolder(current, request.getPrincipalId());
            if (current.slack()) {
                if (current.teamId() != null && !current.teamId().equals(checked.teamId())) {
                    throw ApiException.badRequest(ProblemCode.BOT_IDENTITY_MISMATCH, "The token is of another "
                            + "workspace than the system's");
                }
                if (current.hasBot() && current.botUserId() != null
                        && !current.botUserId().equals(checked.botUserId())) {
                    throw ApiException.badRequest(ProblemCode.BOT_IDENTITY_MISMATCH, "The token is of another bot "
                            + "user than the system's: remove the bot first");
                }
            }
            boolean replaced = current.hasBot();
            AccountInfo account = chatAccounts.storeBot(companyId, systemId, current.botAccountId(),
                    request.getPrincipalId(), token, checked.identity(),
                    checked.botUserId() != null ? checked.botUserId() : checked.identity());
            chatSystems.setBot(companyId, systemId, account.id(), checked.teamId(), checked.teamName(),
                    checked.botUserId());
            chatSystems.status(companyId, systemId, "OK", null);
            AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.SYSTEM_BOT_SET,
                    CurrentActor.actor())
                .target("system", systemId)
                .detail("principalId", request.getPrincipalId().toString())
                .detail("accountId", account.id().toString())
                .detail("identity", checked.identity())
                .detail("replaced", Boolean.toString(replaced));
            if (checked.teamId() != null) {
                audit.detail("teamId", checked.teamId());
            }
            auditLog.record(audit.build());
            bot.forget(systemId);
            return new com.flowtivo.tinfirm.shared.api.model.ChatBot(systemId, request.getPrincipalId(), account.id(), checked.identity())
                .teamId(checked.teamId() != null ? checked.teamId() : current.tenantId())
                .teamName(checked.teamName());
        });
    }

    /** The token, checked with the platform, outside any transaction; {@code 400 ACCOUNT_REFUSED} when refused. */
    private Checked check(ChatSystemInfo system, String token) {
        if (system.slack()) {
            CallResult<ChatCalls.SlackBot> tested = calls.slackAuthTest(system.system(), token);
            if (tested.failure() != null) {
                throw refused(tested, token);
            }
            ChatCalls.SlackBot slack = Objects.requireNonNull(tested.value());
            return new Checked(slack.botUserId(), slack.teamId(), slack.teamName(), slack.botUserId());
        }
        String tenant = Objects.requireNonNull(system.tenantId());
        String appId = Objects.requireNonNull(system.appId());
        CallResult<java.time.Instant> tested = calls.teamsCheck(system.system(),
                new ChatCalls.TeamsBot(tenant, appId, token));
        if (tested.failure() != null) {
            throw refused(tested, token);
        }
        return new Checked(appId.toLowerCase(Locale.ROOT), null, null, null);
    }

    private static ApiException refused(CallResult<?> result, String token) {
        String reason = Objects.requireNonNull(result.failureMessage()).replace(token, "***");
        return ApiException.badRequest(ProblemCode.ACCOUNT_REFUSED, "The platform refused the token: " + reason);
    }

    /** {@code DELETE /systems/{id}/bot}: the account with its secret, then the system without a bot. */
    void removeBot(UUID companyId, UUID systemId) {
        inTransaction(() -> {
            guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
            chatSystem(companyId, systemId);
            ChatSystemInfo system = chatSystems.lock(companyId, systemId).orElseThrow(ChatAdmin::systemNotFound);
            UUID accountId = system.botAccountId();
            if (accountId == null) {
                throw ApiException.notFound("The system has no bot");
            }
            AccountInfo account = accounts.find(companyId, accountId).orElse(null);
            java.util.Optional<UUID> holder = chatAccounts.botHolder(companyId, accountId);
            chatSystems.clearBot(companyId, systemId);
            chatAccounts.removeBot(companyId, accountId, AuditAction.SYSTEM_BOT_REMOVED.value());
            AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.SYSTEM_BOT_REMOVED,
                    CurrentActor.actor())
                .target("system", systemId)
                .detail("accountId", accountId.toString());
            holder.ifPresent(principalId -> audit.detail("principalId", principalId.toString()));
            if (account != null && account.externalId() != null) {
                audit.detail("identity", account.externalId());
            }
            auditLog.record(audit.build());
            return Boolean.TRUE;
        });
        bot.forget(systemId);
    }

    /** I9 and C9: a service account borrows the bot's credential, and a bot another one borrows is removed first. */
    private void requireSameHolder(ChatSystemInfo system, UUID principalId) {
        if (system.botAccountId() == null) {
            return;
        }
        chatAccounts.botHolder(system.companyId(), system.botAccountId())
            .filter(holder -> !holder.equals(principalId))
            .ifPresent(holder -> {
                throw ApiException.conflict(ProblemCode.BOT_ACCOUNT_IN_USE, "Another service account borrows the "
                        + "system's bot: remove it first");
            });
    }

    private PrincipalInfo serviceIdentity(UUID companyId, @Nullable UUID principalId) {
        if (principalId == null) {
            throw ApiException.validation("principalId is required");
        }
        PrincipalInfo principal = principals.find(companyId, principalId)
            .filter(PrincipalInfo::service)
            .orElseThrow(() -> ApiException.notFound("Service identity not found"));
        if (!principal.active()) {
            throw ApiException.conflict(ProblemCode.PRINCIPAL_SUSPENDED, "The service identity is suspended");
        }
        return principal;
    }

    // ---- a Slack system's own secrets ------------------------------------------------------------------------------

    /** {@code PUT /systems/{id}/chat/secrets}: stored as managed company secrets, never returned. */
    void setSecrets(UUID companyId, UUID systemId, ChatSecretsRequest request) {
        String signing = blankToNull(request.getSigningSecret());
        String appToken = blankToNull(request.getAppToken());
        inTransaction(() -> {
            guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
            chatSystem(companyId, systemId);
            if (signing == null && appToken == null) {
                throw ApiException.validation("signingSecret or appToken is required");
            }
            if (appToken != null && !appToken.startsWith("xapp-")) {
                throw ApiException.validation("An app-level token starts with xapp-");
            }
            ChatSystemInfo system = chatSystems.lock(companyId, systemId).orElseThrow(ChatAdmin::systemNotFound);
            if (!system.slack()) {
                throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "Only a Slack system keeps a signing "
                        + "secret or an app-level token");
            }
            AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.SYSTEM_CHANGED,
                    CurrentActor.actor())
                .target("system", systemId);
            java.util.List<String> changed = new java.util.ArrayList<>();
            if (signing != null) {
                chatSystems.storeSecret(companyId, systemId, ChatSystems.ChatSecret.SIGNING_SECRET, signing);
                changed.add("signingSecret");
            }
            if (appToken != null) {
                chatSystems.storeSecret(companyId, systemId, ChatSystems.ChatSecret.APP_TOKEN, appToken);
                changed.add("appToken");
            }
            auditLog.record(audit.detail("changed", String.join(",", changed)).build());
            if (appToken != null) {
                // The Socket Mode connections are opened again with the new token.
                events.publishEvent(new SystemEvents.ChatTransportChanged(companyId, systemId));
            }
            return Boolean.TRUE;
        });
        if (signing != null) {
            slackVerifier.forget(systemId);
        }
    }

    // ---- the company's rates ---------------------------------------------------------------------------------------

    ChatSettings settings(UUID companyId) {
        return inTransaction(() -> {
            guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
            ChatStore.Rates rates = store.rates(companyId).orElse(limits.defaults());
            return new ChatSettings(rates.messagesPerUserMinute(), rates.messagesPerUserDay(),
                    rates.eventsPerSystemMinute(), rates.version());
        });
    }

    /**
     * Sets the rates under optimistic locking (V060): the change names the version it read, and one made meanwhile is
     * {@code 409 SETTINGS_CHANGED} with nothing written.
     */
    ChatSettings setSettings(UUID companyId, ChatSettings settings) {
        ChatSettings answer = inTransaction(() -> {
            guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
            Integer version = settings.getVersion();
            if (version == null || version < 0) {
                throw ApiException.validation("version: the version of the rates the change read");
            }
            ChatStore.Rates rates = new ChatStore.Rates(within(settings.getMessagesPerUserPerMinute(), 600,
                    "messagesPerUserPerMinute"), within(settings.getMessagesPerUserPerDay(), 10_000,
                            "messagesPerUserPerDay"),
                    within(settings.getEventsPerSystemPerMinute(), 6000, "eventsPerSystemPerMinute"), version + 1);
            if (!store.rates(companyId, rates, version, CurrentActor.requireUser().id(), time.now())) {
                throw ApiException.conflict(ProblemCode.SETTINGS_CHANGED, "The company's chat rates changed since "
                        + "version " + version + " was read: read them again and make the change on them");
            }
            auditLog.record(AuditRecord.company(companyId, AuditAction.CHAT_SETTINGS_CHANGED, CurrentActor.actor())
                .target("company", companyId)
                .detail("messagesPerUserPerMinute", Integer.toString(rates.messagesPerUserMinute()))
                .detail("messagesPerUserPerDay", Integer.toString(rates.messagesPerUserDay()))
                .detail("eventsPerSystemPerMinute", Integer.toString(rates.eventsPerSystemMinute()))
                .build());
            return new ChatSettings(rates.messagesPerUserMinute(), rates.messagesPerUserDay(),
                    rates.eventsPerSystemMinute(), rates.version());
        });
        limits.forget(companyId);
        return answer;
    }

    private static int within(@Nullable Integer value, int max, String field) {
        if (value == null || value < 1 || value > max) {
            throw ApiException.validation(field + " must be 1 to " + max);
        }
        return value;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** A chat system of the company, in the caller's transaction; {@code 400 SYSTEM_KIND_INVALID} for another kind. */
    ChatSystemInfo chatSystem(UUID companyId, UUID systemId) {
        if (systems.find(companyId, systemId).isEmpty()) {
            throw systemNotFound();
        }
        return chatSystems.find(companyId, systemId).orElseThrow(() -> ApiException.badRequest(
                ProblemCode.SYSTEM_KIND_INVALID, "Not a chat system"));
    }

    /**
     * A paused system's accounts are connected to nothing (the package 6 contract, §5.4): setting its bot, which checks
     * the token with the platform, waits for the resume ({@code 409 SYSTEM_PAUSED}). Removing it does not.
     */
    private static void requireNotPaused(ChatSystemInfo system) {
        if (system.system().paused()) {
            throw ApiException.conflict(ProblemCode.SYSTEM_PAUSED, system.name() + " is paused by an admin");
        }
    }

    private static ApiException systemNotFound() {
        return ApiException.notFound("System not found");
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }
}
