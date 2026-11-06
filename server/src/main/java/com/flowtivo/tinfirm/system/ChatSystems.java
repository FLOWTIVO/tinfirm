package com.flowtivo.tinfirm.system;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.ExternalSystem;

/**
 * Chat systems for the {@code chat} module (the package 5 contract, §3.1, §3.2, §5.1): their settings, their bot's
 * account, their own secrets and their status. Runs in the caller's transaction and tenant context, but
 * {@link #revealSecret}, which reveals in a short transaction of its own; row-level security limits it to the
 * company of that context. Whether the caller may change the system is the caller's rule (admins, §6).
 */
public interface ChatSystems {

    /** A {@code SLACK} or {@code TEAMS} system; empty for another kind or an unknown id. */
    Optional<ChatSystemInfo> find(UUID companyId, UUID systemId);

    /** As {@link #find}, its row locked for the change that follows. */
    Optional<ChatSystemInfo> lock(UUID companyId, UUID systemId);

    /**
     * Sets the bot's account, a service identity's on the system (I9), and on Slack the workspace and bot user its
     * token named (§3.1): {@code teamId} is fixed once set, and {@code botUserId} is kept while the system has a bot.
     * The caller locked the system and checked both ({@code BOT_IDENTITY_MISMATCH}).
     */
    void setBot(UUID companyId, UUID systemId, UUID accountId, @Nullable String teamId, @Nullable String teamName,
            @Nullable String botUserId);

    /** Removes the bot: {@code bot_account_id} and {@code botUserId} cleared; {@code teamId} stays. */
    void clearBot(UUID companyId, UUID systemId);

    /** Which of a Slack system's own secrets. */
    enum ChatSecret {
        /** The signing secret of the Events API. */
        SIGNING_SECRET,
        /** The app-level token of Socket Mode. */
        APP_TOKEN
    }

    /**
     * Stores a Slack system's own secret as a managed company secret ({@code SLACK_SIGNING_SECRET},
     * {@code SLACK_APP_TOKEN}), a new version of the one it keeps, audited by the vault. The caller locked the system
     * and audits the change ({@code system.changed}).
     *
     * @return whether a secret was kept before (replaced) or not (set)
     */
    boolean storeSecret(UUID companyId, UUID systemId, ChatSecret which, String value);

    /**
     * Reveals a chat system's own secret for one use, audited as {@code secret.accessed} with the purpose
     * ({@code chat-signing:{systemId}}, {@code chat-socket:{systemId}}). Outside any transaction: it reads and reveals
     * in a short transaction of its own, in the caller's tenant context. Empty when none is kept. Never log it.
     */
    Optional<String> revealSecret(UUID companyId, UUID systemId, ChatSecret which, String purpose);

    /** The system's status: {@code OK}, or {@code ERROR} with the reason (a refused bot, a silent subscription). */
    void status(UUID companyId, UUID systemId, String status, @Nullable String lastError);

    /**
     * The system as {@code GET /systems/{id}} answers it, for the {@code chat} module's own routes that answer the
     * system ({@code PUT …/chat/secrets}); in a transaction of its own, as the caller, who may view systems.
     */
    ExternalSystem view(UUID companyId, UUID systemId);

    /** The chat system whose bot the account is (C9: never granted, bound or removed but through the bot's routes). */
    Optional<UUID> botOf(UUID companyId, UUID accountId);
}
