package com.flowtivo.tinfirm.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.SystemCalls;

/**
 * Accounts for the other modules (the package 1 contract, §3.6, §3.7 and §5). Runs in the caller's tenant context:
 * row-level security limits every lookup to the company of that context. Every method but {@link #reveal} runs in the
 * caller's transaction; {@link #reveal} runs outside any, since it may call the system.
 */
public interface Accounts {

    /**
     * The binding rule (contract §5): which account a context acts with on the system, or why none. Stores nothing.
     * <ul>
     * <li>A personal conversation of member M: M's own account when it is {@code OK} ({@code Own}). Else, when M holds
     * none: the grants to M's groups whose credential is {@code OK} and enabled (ADR-0072), one ({@code Granted}) or
     * several ({@code Choose}). Else {@code ConnectRequired}: {@code CHOOSE} (M's own is broken
     * and a usable grant exists), {@code RECONNECT_YOURS} (M's own is broken), {@code RECONNECT_COMPANY} (a granted
     * account is broken), {@code NONE}. Nothing falls back from a broken account (I10).</li>
     * <li>A trigger of author A whose row names account X through H: {@code Fixed(X, H)} when X is A's own and H is A,
     * or X is the credential the service account H borrows on the system and A may use H, and X is {@code OK} (and
     * enabled); {@code ConnectRequired} when X is not; else {@code NotEntitled}.</li>
     * <li>A personal server binds only for its owner, and for nobody while the company switched personal servers off;
     * a member or holder who is not {@code ACTIVE} binds nothing (I7).</li>
     * <li>A chat link ({@code LINK}) is no account to act with (the package 5 contract, open point 13): a member's own
     * counts as none, and a trigger naming one is {@code NotEntitled} ({@code ACCOUNT_NOT_USABLE}); a chat system's
     * bot is never bound ({@code BOT_ACCOUNT}, C9).</li>
     * <li>An MCP server that needs no account (ADR-0071) binds a personal conversation {@code Open}, after the checks
     * above, whatever account the member holds there. A trigger naming an account on it is checked as above.</li>
     * </ul>
     */
    AccountBinding.Outcome bind(AccountBinding.Context context, UUID systemId);

    /**
     * Whether a conversation acting as the principal may use the system without an account now (ADR-0071): a binding
     * without one is made (a trigger's conversation's, at the system's first use) or used. {@code Open} when the
     * system exists, needs no account, is not paused, and the principal is an {@code ACTIVE} member (a personal server
     * its owner's, while the company allows them); else {@code Stopped}: {@code SYSTEM_GONE},
     * {@code PERSONAL_SYSTEMS_OFF}, {@code SYSTEM_PAUSED}, {@code PRINCIPAL_INACTIVE} (also another member's personal
     * server, which nothing binds for them) or {@code ACCOUNT_REQUIRED}.
     */
    AccountBinding.Check open(UUID companyId, UUID systemId, UUID actingPrincipalId);

    /**
     * The check of a stored binding at every use (contract §5): the system still there (and on, for a personal server),
     * the acting member and the holder {@code ACTIVE}; one's own account on the system still there with the bound
     * identity and {@code OK}, or the bound credential still there with it, {@code OK} and enabled; the grant still
     * there with the acting member in its group (its access read now); a trigger's author still allowed to use the
     * service account, which still borrows the bound credential (its access read now). A failed check stops the use; it
     * never re-binds.
     */
    AccountBinding.Check check(AccountBinding.Stored binding);

    Optional<AccountInfo> find(UUID companyId, UUID accountId);

    /** The member's own account on the system (I1: at most one). */
    Optional<AccountInfo> of(UUID companyId, UUID systemId, UUID principalId);

    /** The member's own accounts, by system. */
    List<AccountInfo> ofPrincipal(UUID companyId, UUID principalId);

    /** The credential the service account borrows on the system (ADR-0072: at most one), with its access. */
    Optional<Lent> lentTo(UUID companyId, UUID systemId, UUID servicePrincipalId);

    /**
     * Who lent the service account the credential it borrows on the system (the lending's {@code created_by}); empty
     * when nothing is lent there or it was not recorded. The trigger module tells them when a trigger open to anyone
     * acts with it (the security review of 2026-10-27, D2).
     */
    Optional<UUID> lentBy(UUID companyId, UUID systemId, UUID servicePrincipalId);

    /** The credentials the service account borrows, by system. */
    List<Lent> borrowedBy(UUID companyId, UUID servicePrincipalId);

    /** The service accounts that borrow the credential, oldest lending first. */
    List<UUID> borrowers(UUID companyId, UUID credentialId);

    /**
     * The access a trigger's row acts with (ADR-0072): {@code WRITE} for its author's own account; for a credential,
     * the access it is lent to the service account with, {@code READ} when the service account no longer borrows it
     * (its next check stops it).
     */
    GrantInfo.Access fixedAccess(UUID companyId, UUID systemId, UUID holderPrincipalId, UUID accountId);

    /** Whether the account is a chat system's bot (C9): a credential changed only with the bot. */
    boolean bot(UUID companyId, UUID accountId);

    /** A credential a service account borrows, and with which access. */
    record Lent(AccountInfo credential, UUID servicePrincipalId, GrantInfo.Access access) {
    }

    /**
     * The credentials the user connected, which nobody connected again since: a member who left may have made their
     * token (the package 6 contract, §5.5).
     */
    List<AccountInfo> credentialsConnectedBy(UUID companyId, UUID userId);

    Optional<GrantInfo> grant(UUID companyId, UUID grantId);

    /**
     * The account's credential for one call, clone or push that acts as {@code actingPrincipalId}, revealed from the
     * vault and audited as {@code secret.accessed} with the purpose; an OAuth access token about to expire is refreshed
     * first (a refusal turns the account {@code RECONNECT}). A member's account is revealed only for a call that acts
     * as that member (I3); a system's credential for anyone the caller checked ({@link #check}), through a service
     * account only while it is active and borrows it, and with no acting principal for a poll the credential's
     * triggers share (ADR-0072). Empty when the account is gone, not {@code OK}, a disabled credential, or a
     * {@code LINK}. Never log the result.
     * <p>
     * Runs outside any transaction (CONVENTIONS §10), and throws {@link IllegalStateException} inside one: the account
     * is read in a short transaction of its own, a refresh is sent after it, and the refreshed grant or the refusal is
     * stored in another. A caller checks the binding in its transaction, ends it, reveals, calls the system, and
     * records the outcome in a new one.
     */
    Optional<RevealedCredential> reveal(UUID companyId, UUID accountId, @Nullable UUID actingPrincipalId, String purpose);

    /**
     * The system refused the account's credential on a call made with it (a {@code 401}, the package 3 contract,
     * §5.3): the account turns {@code RECONNECT}, audited as {@code account.refused} with the reason (at most 500
     * characters, never a token) and announced ({@code AccountNeedsReconnect}), as a refused refresh does. Whoever
     * manages the account signs it in again. Nothing happens to an account that is gone or not {@code OK} any more. In
     * the caller's transaction, after the call: the caller revealed the credential, called outside any transaction,
     * and records the refusal here.
     *
     * @return whether the account turned {@code RECONNECT} now
     */
    boolean refused(UUID companyId, UUID accountId, String reason);

    /**
     * A credential for one call. Never log it.
     *
     * @param apiBaseUrl where to call the system with it when that is not its base URL (Jira Cloud's gateway); null
     *                   otherwise
     * @param endpoint   the system's kind and base URL when the credential was revealed, read before the account in
     *                   the reveal's transaction: the call sends the token only to a system that still has them
     *                   (ADR-0079, {@code CallCredential.endpoint})
     */
    record RevealedCredential(SystemCalls.Credential credential, @Nullable String apiBaseUrl,
            CallCredential.Endpoint endpoint) {

        /** The credential as a tool call, a poll or a write takes it: pinned to the endpoint it was revealed for. */
        public CallCredential callCredential() {
            return CallCredential.of(credential, apiBaseUrl, endpoint);
        }

        @Override
        public String toString() {
            return "RevealedCredential[apiBaseUrl=" + apiBaseUrl + ", endpoint=" + endpoint + "]";
        }
    }
}
