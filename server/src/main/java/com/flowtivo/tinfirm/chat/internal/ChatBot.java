package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.ChatAccounts;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;

/**
 * The bot of a chat system (the package 5 contract, §3.2, §5.9, §5.14, C9, C14): whether it can be used, and its
 * credential for one use. The credential is revealed with {@code Accounts.reveal}, the bot's own service identity as
 * the actor, purpose {@code chat-bot:{systemId}}, and kept in memory per replica for 10 minutes; a platform that
 * refuses it gets it revealed again once (a token set through another replica is used at once), and only a second
 * refusal counts: {@code Accounts.refused} turns the bot {@code RECONNECT} and the system {@code ERROR}. Only
 * {@code ChatPoster} and the platforms' reads use it; it is never a binding, a tool's credential or sent to a runner
 * host (C9).
 * <p>
 * A paused system's bot is used for nothing (the package 6 contract, §5.4, G5): {@link #state} answers
 * {@code PAUSED} before anything else, and {@link #credential} reads the pause at every use, a kept credential's too, so
 * that a pause made through another replica holds at once.
 * <p>
 * Seam for package 5b (open point 21, decided (b)): {@link #state} is what a channel trigger waits on
 * ({@code BOT_MISSING}, {@code BOT_RECONNECT}, {@code BOT_SUSPENDED}), and it goes on by itself once the bot is usable.
 */
@Component
class ChatBot {

    private static final Logger log = LoggerFactory.getLogger(ChatBot.class);

    static final Duration KEPT = Duration.ofMinutes(10);

    /** Whether the bot can be used, as a channel trigger waits on it (package 5b). */
    enum State {
        USABLE, MISSING, RECONNECT, SUSPENDED,

        /** An admin paused the system (G5): its triggers pause {@code SYSTEM_PAUSED} at the gate, and do not wait. */
        PAUSED;

        /**
         * A channel trigger's {@code waiting_reason} (open point 21); null for a usable bot and for a paused system,
         * whose triggers package 6's gate pauses instead.
         */
        @Nullable String waitingReason() {
            return this == USABLE || this == PAUSED ? null : "BOT_" + name();
        }
    }

    /** The bot's credential for one use: Slack's token, or Teams' app with its password. Never logged. */
    record Credential(ChatSystemInfo system, UUID accountId, UUID principalId, String secret) {

        ChatCalls.TeamsBot teams() {
            return new ChatCalls.TeamsBot(java.util.Objects.requireNonNull(system.tenantId()),
                    java.util.Objects.requireNonNull(system.appId()), secret);
        }

        @Override
        public String toString() {
            return "Credential[system=" + system.id() + ", account=" + accountId + ", secret=***]";
        }
    }

    private record Kept(Credential credential, Instant at) {
    }

    private final Map<UUID, Kept> kept = new ConcurrentHashMap<>();

    private final ChatSystems chatSystems;

    private final Accounts accounts;

    private final ChatAccounts chatAccounts;

    private final Principals principals;

    private final SystemTransactions transactions;

    ChatBot(ChatSystems chatSystems, Accounts accounts, ChatAccounts chatAccounts, Principals principals,
            SystemTransactions transactions) {
        this.chatSystems = chatSystems;
        this.accounts = accounts;
        this.chatAccounts = chatAccounts;
        this.principals = principals;
        this.transactions = transactions;
    }

    /** In the caller's transaction and tenant context. */
    State state(ChatSystemInfo system) {
        if (system.system().paused()) {
            return State.PAUSED;
        }
        if (system.botAccountId() == null) {
            return State.MISSING;
        }
        AccountInfo account = accounts.find(system.companyId(), system.botAccountId()).orElse(null);
        if (account == null) {
            return State.MISSING;
        }
        // The service account that borrows the bot's credential (ADR-0072): suspended, it carries nothing.
        PrincipalInfo holder = chatAccounts.botHolder(system.companyId(), account.id())
            .flatMap(principalId -> principals.find(system.companyId(), principalId))
            .orElse(null);
        if (holder == null || !holder.active()) {
            return State.SUSPENDED;
        }
        return account.ok() ? State.USABLE : State.RECONNECT;
    }

    /**
     * The bot's credential, outside any transaction: the kept one, or revealed now. Empty when the system has no
     * usable bot, or is paused (G5, read at every use).
     */
    Optional<Credential> credential(UUID companyId, UUID systemId) {
        Kept current = kept.get(systemId);
        if (current != null && current.at().plus(KEPT).isAfter(Instant.now())) {
            boolean paused = transactions.inCompany(companyId, () -> chatSystems.find(companyId, systemId)
                .map(system -> system.system().paused())
                .orElse(true));
            return paused ? Optional.empty() : Optional.of(current.credential());
        }
        return reveal(companyId, systemId);
    }

    private Optional<Credential> reveal(UUID companyId, UUID systemId) {
        Usable usable = transactions.inCompany(companyId, () -> chatSystems.find(companyId, systemId)
            .filter(system -> state(system) == State.USABLE)
            .map(system -> new Usable(system, java.util.Objects.requireNonNull(system.botAccountId()),
                    chatAccounts.botHolder(companyId, system.botAccountId()).orElseThrow()))
            .orElse(null));
        if (usable == null) {
            kept.remove(systemId);
            return Optional.empty();
        }
        Optional<Credential> revealed = com.flowtivo.tinfirm.shared.security.SystemContext.callInCompany(companyId,
                () -> accounts.reveal(companyId, usable.accountId(), usable.principalId(), "chat-bot:" + systemId))
            .map(secret -> new Credential(usable.system(), usable.accountId(), usable.principalId(),
                    secret.credential().token()));
        revealed.ifPresentOrElse(credential -> kept.put(systemId, new Kept(credential, Instant.now())),
                () -> kept.remove(systemId));
        return revealed;
    }

    private record Usable(ChatSystemInfo system, UUID accountId, UUID principalId) {
    }

    /**
     * The platform refused the credential once: it is revealed again (another replica may have set a new one), and the
     * caller tries the fresh one once. Empty when the system has no usable bot any more.
     */
    Optional<Credential> revealAgain(Credential refused) {
        kept.remove(refused.system().id());
        return reveal(refused.system().companyId(), refused.system().id());
    }

    /**
     * The platform refused the credential a second time: the bot turns {@code RECONNECT} ({@code Accounts.refused},
     * audited {@code account.refused}) and the system {@code ERROR}, until an admin sets a token again.
     */
    void refused(Credential refused, String reason) {
        UUID companyId = refused.system().companyId();
        UUID systemId = refused.system().id();
        kept.remove(systemId);
        log.info("The bot of the chat system {} was refused twice: it waits for a new token", systemId);
        transactions.runInCompany(companyId, () -> {
            accounts.refused(companyId, refused.accountId(), reason);
            chatSystems.status(companyId, systemId, "ERROR", "The platform refused the bot: " + reason);
        });
    }

    /** Forgets the kept credential: the bot was set, replaced or removed here. */
    void forget(UUID systemId) {
        kept.remove(systemId);
    }
}
