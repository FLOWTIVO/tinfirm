package com.flowtivo.tinfirm.system;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Port: the accounts on a system, implemented by the account module, which keeps them and depends on this one; the
 * system module does not depend on it. Runs in the caller's tenant context: {@link #systemDeleting} in the caller's
 * transaction, {@link #ownCredential} outside any.
 */
public interface SystemAccounts {

    /**
     * The system is about to be deleted, in the transaction that deletes it: the accounts and credentials on it go,
     * with their secrets, the grants and lendings of them and their pending sign-ins, each audited.
     */
    void systemDeleting(UUID companyId, UUID systemId);

    /**
     * An MCP server's endpoint is changing (ADR-0079), in the transaction that changes it: every account and credential
     * on it goes as for {@link #systemDeleting}, with the cause {@code system.moved}, so that no token reaches the new
     * address. Answers how many went.
     */
    int systemMoving(UUID companyId, UUID systemId);

    /**
     * The OAuth accounts and credentials on the system, in the caller's transaction: what a change of its app's token
     * or revocation endpoint takes with it ({@link #appEndpointsChanging}).
     */
    OAuthOnSystem oauthOn(UUID companyId, UUID systemId);

    /**
     * The OAuth accounts and credentials on a system: how many members' own accounts (their names are not the
     * caller's to read), and the system's credentials by id and name.
     */
    record OAuthOnSystem(int accounts, List<NamedCredential> credentials) {

        public OAuthOnSystem {
            credentials = List.copyOf(credentials);
        }

        public boolean none() {
            return accounts == 0 && credentials.isEmpty();
        }
    }

    /** A credential of the system, by its id and name. */
    record NamedCredential(UUID id, @Nullable String name) {
    }

    /**
     * An MCP server's app is about to take another token or revocation endpoint (the security review of 2026-10-27,
     * M8: ADR-0079's rule extended from the base URL to the app's endpoints), in the transaction that stores it: every
     * OAuth account and credential on it goes as for {@link #systemDeleting}, with the cause
     * {@code system.app_endpoints_changed}, with its pending sign-ins, so that no refresh token or code reaches the new
     * endpoint. Answers how many went.
     */
    int appEndpointsChanging(UUID companyId, UUID systemId);

    /**
     * The credential of the member's own account on the system, when they hold one that is {@code OK}, for a call
     * that acts as them (I3): testing the system or reading its catalogue at their request. Revealing it is audited
     * as {@code secret.accessed} with the purpose; an expiring OAuth access token is refreshed first. Runs outside
     * any transaction, in short ones of its own around the refresh ({@link IllegalStateException} inside one).
     */
    Optional<OwnCredential> ownCredential(UUID companyId, UUID systemId, UUID userId, String purpose);

    /**
     * The member's own account that {@link #ownCredential} revealed, or the system's credential that
     * {@link #revealCredential} did, with its credential: the account a test used, which the system's last test names,
     * even when the account is replaced while the test runs.
     *
     * @param apiBaseUrl where the credential is valid when that is not the system's base URL (Jira Cloud's gateway);
     *                   null otherwise
     * @param endpoint   the system's kind and base URL when the credential was revealed (ADR-0079): the test or the
     *                   refresh sends it only to a row that still has them
     */
    record OwnCredential(UUID accountId, SystemCalls.Credential credential, @Nullable String apiBaseUrl,
            CallCredential.Endpoint endpoint) {

        @Override
        public String toString() {
            return "OwnCredential[accountId=" + accountId + ", apiBaseUrl=" + apiBaseUrl + ", endpoint=" + endpoint
                    + "]";
        }
    }

    /** Whether the credential is one of the system's (ADR-0072), in the caller's transaction. */
    default boolean credentialOf(UUID companyId, UUID systemId, UUID credentialId) {
        return credentialName(companyId, systemId, credentialId).isPresent();
    }

    /** The name of the system's credential (ADR-0072); empty for another account, in the caller's transaction. */
    Optional<String> credentialName(UUID companyId, UUID systemId, UUID credentialId);

    /**
     * The system's only credential that is enabled and {@code OK}, when it has exactly one, in the caller's
     * transaction: what an admin's test or refresh reaches the system with when they name none and hold no account
     * there.
     */
    Optional<UUID> onlyUsableCredential(UUID companyId, UUID systemId);

    /**
     * A system's credential for testing the system or reading its catalogue at an admin's request (ADR-0072), revealed
     * and audited as {@code secret.accessed} with the purpose; empty when it is gone, disabled or not {@code OK}. Runs
     * outside any transaction, as {@link #ownCredential} does.
     */
    Optional<OwnCredential> revealCredential(UUID companyId, UUID credentialId, String purpose);

    /**
     * Who holds the account: the member whose own it is, or the service account that borrows a credential (a chat
     * system's bot's, §3.1 of the package 5 contract), in the caller's transaction and tenant context; empty for an
     * account that is gone or a credential nobody borrows.
     */
    Optional<UUID> holderOf(UUID companyId, UUID accountId);
}
