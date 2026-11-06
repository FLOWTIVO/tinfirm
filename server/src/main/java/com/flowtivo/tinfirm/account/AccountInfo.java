package com.flowtivo.tinfirm.account;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.principal.PrincipalKind;

/**
 * An account as other modules see it (the package 1 contract, §3.6; ADR-0072): a member's own credential on a system,
 * or a credential the system holds for the company, without the credential.
 *
 * @param principalId   the member whose own account it is; null for a credential the system holds
 * @param principalKind {@code MEMBER} for a member's own account; null for a credential
 * @param name          a credential's name, unique on its system; null for a member's own account
 * @param externalId    the identity on the system, a stable id; null where the system has none
 * @param externalLogin a readable login there; null where the system has none
 * @param enabled       whether a credential may be used; always true for a member's own account
 */
public record AccountInfo(UUID id, UUID companyId, UUID systemId, @Nullable UUID principalId,
        @Nullable PrincipalKind principalKind, @Nullable String name, Kind kind, @Nullable String externalId,
        @Nullable String externalLogin, Status status, boolean enabled) {

    /** How the account holds its credential. */
    public enum Kind {
        /** A token stored by hand, checked with the system's "who am I". */
        TOKEN,
        /** An OAuth grant, signed in through the system's app. */
        OAUTH,
        /**
         * An OAuth client's own credentials, which mint its access tokens ({@code client_credentials}): an Atlassian
         * service account's on Jira Cloud. A credential of the system only, never a member's own account.
         */
        CLIENT,
        /** An identity without a credential: a chat user linked through the identity provider (package 5). */
        LINK
    }

    /**
     * Whether the account works: {@code OK}; {@code RECONNECT}, the system refused it or the grant expired, whoever
     * manages it signs in again; {@code ERROR}. Nothing falls back from one that is not {@code OK} (I10).
     */
    public enum Status {
        OK, RECONNECT, ERROR
    }

    /** A credential the system holds for the company (ADR-0072), rather than a member's own account. */
    public boolean credential() {
        return principalId == null;
    }

    /** Whether it may be used now: {@code OK}, and enabled when it is a credential. */
    public boolean ok() {
        return status == Status.OK && enabled;
    }
}
