package com.flowtivo.tinfirm.account;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The account module's domain events, for the packages that react to accounts and grants (package 3: resolving and
 * ending bindings). Ids only, never a credential. Each is published with Spring's {@code ApplicationEventPublisher} in
 * the transaction that made the change, after it records its audit event, which the audit log appends when the
 * transaction commits (CONVENTIONS §8): a listener reacts after the commit with {@code @TransactionalEventListener}
 * (and never sees a change that rolled back), or inside the transaction with {@code @EventListener}.
 */
public final class AccountEvents {

    private AccountEvents() {
    }

    /**
     * A member connected an account on a system they held none on, or an admin added a credential to a system.
     *
     * @param principalId the member; null for a credential (ADR-0072)
     */
    public record AccountConnected(UUID companyId, UUID accountId, UUID systemId, @Nullable UUID principalId) {
    }

    /**
     * A member's account or a credential was signed in or stored again; it is {@code OK}.
     *
     * @param principalId the member; null for a credential
     */
    public record AccountReconnected(UUID companyId, UUID accountId, UUID systemId, @Nullable UUID principalId) {
    }

    /**
     * An account or a credential went: disconnected, removed, or with its system or its leaving member, as its audit's
     * {@code cause} says. Its grants went first, each with {@link GrantDeleted}; its lendings to service accounts with
     * it.
     *
     * @param principalId the member; null for a credential
     */
    public record AccountDisconnected(UUID companyId, UUID accountId, UUID systemId, @Nullable UUID principalId) {
    }

    /**
     * An account or a credential stopped working: the system refused it, or its grant could not be refreshed.
     *
     * @param principalId the member; null for a credential
     */
    public record AccountNeedsReconnect(UUID companyId, UUID accountId, UUID systemId, @Nullable UUID principalId) {
    }

    /**
     * A credential changed how it may be used (ADR-0072): disabled or enabled, or lent to a service account, its
     * lending's access changed, or withdrawn from it. What uses it is checked again.
     *
     * @param servicePrincipalId the service account a lending changed for; null for a credential disabled or enabled
     */
    public record CredentialChanged(UUID companyId, UUID accountId, UUID systemId,
            @Nullable UUID servicePrincipalId) {
    }

    /**
     * A service account borrows another credential on a system in place of the one it borrowed (ADR-0072), published
     * in the lending's transaction: what acts through the service account follows it.
     *
     * @param replacedAccountId the credential it borrowed until now
     * @param accountId         the credential it borrows now
     */
    public record LendingReplaced(UUID companyId, UUID systemId, UUID servicePrincipalId, UUID replacedAccountId,
            UUID accountId) {
    }

    /** An admin lent a credential to a group. */
    public record GrantCreated(UUID companyId, UUID grantId, UUID accountId, UUID systemId, UUID groupId,
            GrantInfo.Access access) {
    }

    /** A grant's access changed. */
    public record GrantChanged(UUID companyId, UUID grantId, UUID accountId, UUID systemId, UUID groupId,
            GrantInfo.Access access) {
    }

    /** A grant was withdrawn: by an admin, or with its account or its group. */
    public record GrantDeleted(UUID companyId, UUID grantId, UUID accountId, UUID systemId, UUID groupId) {
    }
}
