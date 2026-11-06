package com.flowtivo.tinfirm.account;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The accounts of chat systems, for the {@code chat} module (the package 5 contract, §3.2, §5.4, C4, C9; ADR-0072):
 * the bot, a {@code TOKEN} credential of the chat system that a service account borrows, set only through
 * {@code PUT /systems/{id}/bot}; and the links of chat users to members, {@code LINK} accounts that only
 * {@code ChatLinks} writes. Each change is audited and announced as every
 * account's is ({@code account.connected}, {@code account.reconnected}, {@code account.disconnected},
 * {@link AccountEvents}). Runs in the caller's transaction and tenant context; the caller authorised the change, and
 * checked the bot's token with its platform before it.
 */
public interface ChatAccounts {

    /** How a link was made (the audit's {@code how}). */
    enum How {
        /** Teams, by the Entra tenant and object id of the member's login (§5.4). */
        AUTOMATIC,
        /** A sign-in link the member confirmed. */
        SIGN_IN_LINK
    }

    /**
     * Stores the bot's token as the chat system's {@code TOKEN} credential, a managed company secret, lent to the
     * service account: the system's bot credential stored again, or a new one named {@code Bot}. {@code 409
     * PRINCIPAL_SUSPENDED} for a suspended service account, {@code 404} for none; I2 is the database's
     * ({@code IDENTITY_IN_USE}).
     *
     * @param botAccountId the system's bot credential now; null without a bot
     * @param externalId   the bot's identity at the platform: Slack's bot user id, Teams' app id
     */
    AccountInfo storeBot(UUID companyId, UUID systemId, @Nullable UUID botAccountId, UUID serviceIdentityId,
            String token, String externalId, @Nullable String login);

    /**
     * Removes a bot's credential with its secret and its lending ({@code account.disconnected} with the cause, e.g.
     * {@code system.bot_removed}). Nothing when it is gone.
     */
    void removeBot(UUID companyId, UUID accountId, String cause);

    /** The service account that borrows the bot's credential, if any. */
    Optional<UUID> botHolder(UUID companyId, UUID accountId);

    /**
     * Links a chat identity to a member: their {@code LINK} account on the chat system (C4). {@code 409
     * IDENTITY_IN_USE} when another member holds the identity (I2), {@code 409 ALREADY_LINKED} when the member holds
     * another account on the system (I1). Audited as {@code account.connected} with {@code how} and the chat user id.
     *
     * @param externalId the identity a link names: Slack's user id, Teams' Entra object id
     * @param login      the readable name, never proof
     */
    AccountInfo link(UUID companyId, UUID systemId, UUID memberPrincipalId, String externalId, @Nullable String login,
            How how, String chatUserId);

    /** The account of an identity on a system, whoever holds it. */
    Optional<AccountInfo> ofIdentity(UUID companyId, UUID systemId, String externalId);

    /**
     * An admin removes a member's account on a chat system, a {@code LINK} or the {@code OAUTH} account that serves as
     * one (open point 16): {@code account.disconnected} with {@code cause} {@code admin}.
     */
    void removeByAdmin(UUID companyId, UUID accountId);
}
