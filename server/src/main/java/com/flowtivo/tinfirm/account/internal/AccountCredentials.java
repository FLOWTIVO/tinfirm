package com.flowtivo.tinfirm.account.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.diagnostics.RemoteText;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ClientCredentials;
import com.flowtivo.tinfirm.system.OAuthTokens;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.vault.RevealedSecret;

/**
 * An account's credential for one call (the package 1 contract, §3.6, I3, I7): revealed from the vault for the call
 * only, a member's for that member only; an OAuth access token that expires within a minute is refreshed first through
 * the system's app, and a refusal turns the account {@code RECONNECT}, audited as {@code account.refused} and announced.
 * A grant that expired and cannot be refreshed (no refresh token, or the system's app was removed) turns it
 * {@code RECONNECT} as well. A client's credentials ({@code CLIENT}) have no refresh token: the client mints a new
 * access token itself ({@code client_credentials}) when the one it minted last expires within the minute, and keeps it
 * in its secret beside its credentials; a refusal of the client turns the credential {@code RECONNECT} as a refused
 * refresh does.
 * <p>
 * Runs outside any transaction, in the caller's tenant context: the account is read and its secret revealed in a short
 * transaction of its own; a refresh is sent after it; the refreshed grant, or the refusal, is stored in another, on the
 * account as it is then. When the account changed in between (another call refreshed it, its member connected it
 * again), a refusal does not turn it {@code RECONNECT}: the credential is read again, once.
 * <p>
 * Refreshes of one account run one at a time in this process (a striped lock by the account's id, taken outside any
 * transaction): a system that rotates refresh tokens spends the one read on its first use, and a second refresh with it
 * would be refused and turn a working account {@code RECONNECT}. The call that waited reads the account's row again
 * under the lock and, when another call refreshed it meanwhile, takes the grant that one stored. Across replicas the
 * refresh that succeeds makes an account that turned {@code RECONNECT} meanwhile, with the same grant, {@code OK}
 * again: it has just proven the grant.
 */
@Component
class AccountCredentials {

    /** An access token that expires within this is refreshed before it is used. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(1);

    /** The stripes of the refresh locks: accounts that share one refresh one after the other. */
    private static final int REFRESH_STRIPES = 64;

    /** One refresh per account at a time in this process, by the account's id. */
    private final ReentrantLock[] refreshLocks = new ReentrantLock[REFRESH_STRIPES];

    private final AccountStore store;

    private final AccountSecrets secrets;

    private final Principals principals;

    private final Systems systems;

    private final SystemCalls calls;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactions;

    private final TimeSource time;

    AccountCredentials(AccountStore store, AccountSecrets secrets, Principals principals, Systems systems,
            SystemCalls calls, AuditLog auditLog, ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager, TimeSource time) {
        this.store = store;
        this.secrets = secrets;
        this.principals = principals;
        this.systems = systems;
        this.calls = calls;
        this.auditLog = auditLog;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.time = time;
        for (int i = 0; i < REFRESH_STRIPES; i++) {
            refreshLocks[i] = new ReentrantLock();
        }
    }

    /**
     * What the transaction before a refresh read: the system, before the account, so that its endpoint is the one the
     * account was still on (a move removes the account in its own transaction, ADR-0079); the account, its holder
     * (null for a credential the system holds) and its secret's value. Carries the credential: never log it.
     */
    private record Read(AccountStore.AccountRow account, @Nullable PrincipalInfo holder, String value,
            SystemInfo system) {

        @Override
        public String toString() {
            return "Read[account=" + account.id() + "]";
        }
    }

    /**
     * The account's credential for a call that acts as {@code actingPrincipalId}; empty when the account is gone, is
     * not {@code OK}, has no credential, is a disabled credential, or its member is not {@code ACTIVE}.
     *
     * @param actingPrincipalId whom the call acts as; null for a credential revealed at an admin's request (a test, a
     *                          catalogue's refresh), never for a member's own account
     * @throws IllegalArgumentException for a member's account and a call that does not act as that member (I3)
     * @throws IllegalStateException    inside a transaction
     */
    Optional<Accounts.RevealedCredential> reveal(UUID companyId, UUID accountId, @Nullable UUID actingPrincipalId,
            String purpose) {
        NoTransaction.require("Revealing an account's credential");
        Read read = readInTransaction(companyId, accountId, actingPrincipalId, purpose);
        if (read == null) {
            return Optional.empty();
        }
        Accounts.RevealedCredential usable = usableAsRead(read);
        if (usable != null) {
            return Optional.of(usable);
        }
        // A refresh: one at a time per account, on the account as it is once this call's turn comes.
        ReentrantLock lock = refreshLocks[Math.floorMod(accountId.hashCode(), REFRESH_STRIPES)];
        lock.lock();
        try {
            return refreshed(current(read, companyId, accountId, actingPrincipalId, purpose), companyId, accountId,
                    actingPrincipalId, purpose, true);
        }
        finally {
            lock.unlock();
        }
    }

    /**
     * The credential as it was read, when it needs no refresh: a token, or an OAuth access token that does not expire
     * within the margin; null otherwise.
     */
    private Accounts.@Nullable RevealedCredential usableAsRead(Read read) {
        AccountStore.AccountRow account = read.account();
        if (account.kind() == AccountInfo.Kind.TOKEN) {
            return new Accounts.RevealedCredential(new SystemCalls.Credential(read.value(), false),
                    account.apiBaseUrl(), CallCredential.Endpoint.of(read.system()));
        }
        if (account.kind() == AccountInfo.Kind.CLIENT) {
            ClientCredentials client = ClientCredentials.fromJson(read.value());
            OAuthTokens minted = client != null ? client.tokens() : null;
            return minted != null && !minted.expiresWithin(time.now(), REFRESH_MARGIN) ? oauth(minted, read) : null;
        }
        OAuthTokens tokens = OAuthTokens.fromJson(read.value());
        return tokens != null && !tokens.expiresWithin(time.now(), REFRESH_MARGIN) ? oauth(tokens, read) : null;
    }

    /**
     * Under the account's refresh lock: what was read when the account's row is unchanged since (nobody refreshed it,
     * connected it again or turned it {@code RECONNECT} meanwhile), else the account read again; null when nothing is
     * usable any more.
     */
    private @Nullable Read current(Read read, UUID companyId, UUID accountId, @Nullable UUID actingPrincipalId,
            String purpose) {
        AccountStore.AccountRow before = read.account();
        AccountStore.AccountRow now = transactions.execute(status -> store.find(companyId, accountId).orElse(null));
        boolean unchanged = now != null && now.status() == before.status()
                && now.connectedAt().equals(before.connectedAt())
                && Objects.equals(now.secretId(), before.secretId())
                && Objects.equals(now.accessExpiresAt(), before.accessExpiresAt());
        return unchanged ? read : readInTransaction(companyId, accountId, actingPrincipalId, purpose);
    }

    /**
     * Under the account's refresh lock: the credential, refreshed first when its access token expires within the
     * margin. A refusal turns the account {@code RECONNECT} unless it changed since it was read, when it is read again
     * once ({@code mayReadAgain}).
     */
    private Optional<Accounts.RevealedCredential> refreshed(@Nullable Read read, UUID companyId, UUID accountId,
            @Nullable UUID actingPrincipalId, String purpose, boolean mayReadAgain) {
        if (read == null) {
            return Optional.empty();
        }
        Accounts.RevealedCredential usable = usableAsRead(read);
        if (usable != null) {
            return Optional.of(usable);
        }
        AccountStore.AccountRow account = read.account();
        if (account.kind() == AccountInfo.Kind.CLIENT) {
            return minted(read, companyId, accountId, actingPrincipalId, purpose, mayReadAgain);
        }
        OAuthTokens tokens = OAuthTokens.fromJson(read.value());
        if (tokens == null) {
            inTransaction(() -> needsReconnectUnlessChanged(account, "The stored grant is not readable"));
            return Optional.empty();
        }
        boolean expired = tokens.expiresWithin(time.now(), Duration.ZERO);
        String refreshToken = tokens.refreshToken();
        SystemInfo system = read.system();
        if (refreshToken == null || !system.oauthApp()) {
            if (!expired) {
                return Optional.of(oauth(tokens, read));
            }
            String reason = refreshToken == null ? "The grant expired and has no refresh token"
                    : "The grant expired and the system has no app to refresh it with";
            inTransaction(() -> needsReconnectUnlessChanged(account, reason));
            return Optional.empty();
        }
        // Outside any transaction: the system may take its time.
        CallResult<OAuthTokens> fresh = calls.refresh(system, refreshToken);
        if (fresh.failure() != null) {
            if (fresh.failure().kind() != CallResult.Kind.UNAUTHORIZED) {
                return expired ? Optional.empty() : Optional.of(oauth(tokens, read));
            }
            boolean marked = inTransaction(() -> needsReconnectUnlessChanged(account,
                    "The system refused to refresh the grant: " + fresh.failureMessage()));
            return !marked && mayReadAgain
                    ? refreshed(readInTransaction(companyId, accountId, actingPrincipalId, purpose), companyId,
                            accountId, actingPrincipalId, purpose, false)
                    : Optional.empty();
        }
        OAuthTokens next = tokens.refreshedBy(Objects.requireNonNull(fresh.value()));
        inTransaction(() -> storeRefreshed(read, system, next.json(), next));
        return Optional.of(oauth(next, read));
    }

    /**
     * Under the account's refresh lock: a client's new access token, minted with its credentials. A refusal of the
     * client turns the credential {@code RECONNECT} unless it changed since it was read, when it is read again once
     * ({@code mayReadAgain}); any other failure leaves it as it is, and the token minted last is used while it has
     * not expired and the credential is still the one it was minted for. A token minted for a credential that was
     * connected again or removed meanwhile is not used: the credential is read again once.
     */
    private Optional<Accounts.RevealedCredential> minted(Read read, UUID companyId, UUID accountId,
            @Nullable UUID actingPrincipalId, String purpose, boolean mayReadAgain) {
        AccountStore.AccountRow account = read.account();
        ClientCredentials client = ClientCredentials.fromJson(read.value());
        if (client == null) {
            inTransaction(() -> needsReconnectUnlessChanged(account, "The stored client credentials are not readable"));
            return Optional.empty();
        }
        SystemInfo system = read.system();
        // Outside any transaction: the system may take its time.
        CallResult<OAuthTokens> fresh = calls.clientToken(system, client);
        if (fresh.failure() != null) {
            if (fresh.failure().kind() != CallResult.Kind.UNAUTHORIZED) {
                OAuthTokens last = client.tokens();
                if (last == null || last.expiresWithin(time.now(), Duration.ZERO)) {
                    return Optional.empty();
                }
                // The token minted last only while the client is the one it was minted for.
                if (!sameCredential(account)) {
                    return mayReadAgain
                            ? refreshed(readInTransaction(companyId, accountId, actingPrincipalId, purpose), companyId,
                                    accountId, actingPrincipalId, purpose, false)
                            : Optional.empty();
                }
                return Optional.of(oauth(last, read));
            }
            boolean marked = inTransaction(() -> needsReconnectUnlessChanged(account,
                    "The system refused the client credentials: " + fresh.failureMessage()));
            return !marked && mayReadAgain
                    ? refreshed(readInTransaction(companyId, accountId, actingPrincipalId, purpose), companyId,
                            accountId, actingPrincipalId, purpose, false)
                    : Optional.empty();
        }
        OAuthTokens next = Objects.requireNonNull(fresh.value());
        String stored = client.minted(next).json();
        if (!inTransaction(() -> storeRefreshed(read, system, stored, next))) {
            // Connected again or removed meanwhile: never a token of the client it held before.
            return mayReadAgain
                    ? refreshed(readInTransaction(companyId, accountId, actingPrincipalId, purpose), companyId,
                            accountId, actingPrincipalId, purpose, false)
                    : Optional.empty();
        }
        return Optional.of(oauth(next, read));
    }

    /**
     * Whether the account is still there with the credential it was read with (connected at the same time, the same
     * secret), read in a short transaction of its own.
     */
    private boolean sameCredential(AccountStore.AccountRow before) {
        AccountStore.AccountRow now = transactions.execute(status -> store.find(before.companyId(), before.id())
            .orElse(null));
        return now != null && now.connectedAt().equals(before.connectedAt())
                && Objects.equals(now.secretId(), before.secretId());
    }

    /**
     * Reads the account and reveals its secret in a transaction of its own, then writes the account's last use in
     * another. The reveal's transaction takes the audit chain head ({@code secret.accessed}) and no account row; the
     * last use takes the account's row and audits nothing. So a reveal never holds the chain head while it waits for
     * the row that a transaction which changes the account locks before its audit (a refusal, a refreshed grant;
     * CONVENTIONS §10: the audit chain head last).
     */
    private @Nullable Read readInTransaction(UUID companyId, UUID accountId, @Nullable UUID actingPrincipalId,
            String purpose) {
        Read read = transactions.execute(status -> read(companyId, accountId, actingPrincipalId, purpose));
        if (read != null) {
            transactions.executeWithoutResult(status -> store.markUsed(companyId, accountId, time.now()));
        }
        return read;
    }

    /**
     * Reads the account and reveals its secret, in the transaction before a refresh; null when nothing is usable. It
     * locks no account row: see {@link #readInTransaction}.
     */
    private @Nullable Read read(UUID companyId, UUID accountId, @Nullable UUID actingPrincipalId, String purpose) {
        // The system's endpoint before the account it is read for (ADR-0079): a move that commits between the two
        // statements takes the account with it, so an account found here is one on the endpoint read before it, and
        // never a token given for the old endpoint pinned to the new one.
        SystemInfo system = store.find(companyId, accountId)
            .flatMap(first -> systems.find(companyId, first.systemId()))
            .orElse(null);
        AccountStore.AccountRow account = system != null ? store.find(companyId, accountId)
            .filter(now -> now.systemId().equals(system.id()))
            .orElse(null) : null;
        if (account == null || account.status() != AccountInfo.Status.OK || account.secretId() == null
                || !account.enabled()) {
            return null;
        }
        PrincipalInfo holder = null;
        if (!account.credential()) {
            holder = principals.find(companyId, Objects.requireNonNull(account.principalId())).orElse(null);
            if (holder == null || !holder.active()) {
                return null;
            }
            if (!holder.id().equals(actingPrincipalId)) {
                throw new IllegalArgumentException("A member's account is revealed only for a call that acts as them");
            }
        }
        else if (actingPrincipalId != null && !mayActThrough(companyId, account, actingPrincipalId)) {
            return null;
        }
        String value;
        try (RevealedSecret secret = secrets.reveal(companyId, holder, Objects.requireNonNull(account.secretId()),
                purpose)) {
            value = secret.asString();
        }
        return new Read(account, holder, value, system);
    }

    /**
     * Whether a credential is revealed for a call that acts through the principal, while it is active: a service
     * account that borrows the credential (I7, ADR-0072); a member through a grant, which the caller checked just
     * before.
     */
    private boolean mayActThrough(UUID companyId, AccountStore.AccountRow credential, UUID actingPrincipalId) {
        PrincipalInfo acting = principals.find(companyId, actingPrincipalId).orElse(null);
        if (acting == null || !acting.active()) {
            return false;
        }
        return !acting.service() || store.lending(companyId, acting.id(), credential.systemId(), false)
            .map(lending -> lending.accountId().equals(credential.id()))
            .orElse(false);
    }

    /**
     * Stores the refreshed grant (a client's credentials with the token it minted), in the transaction after the
     * refresh, unless the account was connected again meanwhile (its new grant stays) or is gone. An account that
     * turned {@code RECONNECT} meanwhile with the same grant (another replica's refresh with the refresh token this one
     * spent was refused, a call with the old access token met a 401) is {@code OK} again: the refresh has just proven
     * the grant.
     */
    private boolean storeRefreshed(Read read, SystemInfo system, String secretValue, OAuthTokens next) {
        AccountStore.AccountRow before = read.account();
        AccountStore.AccountRow now = store.lock(before.companyId(), before.id()).orElse(null);
        if (now == null || !now.connectedAt().equals(before.connectedAt())
                || !Objects.equals(now.secretId(), before.secretId())) {
            return false;
        }
        secrets.store(now.companyId(), read.holder(), now.secretId(), system.handle(), secretValue);
        store.refreshed(now.companyId(), now.id(), next.expiresAt(), next.scope(), time.now());
        if (now.status() == AccountInfo.Status.RECONNECT) {
            usableAgain(now, read.holder());
        }
        return true;
    }

    /**
     * A refresh proved the grant of an account that had turned {@code RECONNECT}: {@code OK} again, audited as
     * {@code account.reconnected} with {@code refreshed} and announced as a reconnected account.
     */
    private void usableAgain(AccountStore.AccountRow account, @Nullable PrincipalInfo holder) {
        store.markStatus(account.companyId(), account.id(), AccountInfo.Status.OK, null, time.now());
        AuditRecord.Builder audit = AccountService.holderDetails(AuditRecord
            .company(account.companyId(), AuditAction.ACCOUNT_RECONNECTED, CurrentActor.actor())
            .target(AccountService.TARGET, account.id())
            .detail("systemId", account.systemId().toString()), account)
            .detail("kind", account.kind().name())
            .detail("refreshed", true);
        if (holder != null && holder.userId() != null) {
            audit.detail("userId", Objects.requireNonNull(holder.userId()).toString());
        }
        auditLog.record(audit.build());
        events.publishEvent(new AccountEvents.AccountReconnected(account.companyId(), account.id(),
                account.systemId(), account.principalId()));
    }

    /**
     * Turns the account {@code RECONNECT}, in the transaction after the refresh, unless it changed since it was read
     * (refreshed by another call, connected again, already not {@code OK}, gone); whether it did.
     */
    private boolean needsReconnectUnlessChanged(AccountStore.AccountRow before, String reason) {
        AccountStore.AccountRow now = store.lock(before.companyId(), before.id()).orElse(null);
        if (now == null || now.status() != AccountInfo.Status.OK || !now.connectedAt().equals(before.connectedAt())
                || !Objects.equals(now.accessExpiresAt(), before.accessExpiresAt())) {
            return false;
        }
        needsReconnect(now, reason);
        return true;
    }

    /**
     * The system refused the credential on a call ({@link Accounts#refused}): {@code RECONNECT} under the row's lock,
     * unless the account is gone or not {@code OK} any more. In the caller's transaction.
     */
    boolean refused(UUID companyId, UUID accountId, String reason) {
        AccountStore.AccountRow now = store.lock(companyId, accountId).orElse(null);
        if (now == null || now.status() != AccountInfo.Status.OK) {
            return false;
        }
        needsReconnect(now, reason);
        return true;
    }

    /** The account stopped working: {@code RECONNECT}, audited with the reason and announced. */
    private void needsReconnect(AccountStore.AccountRow account, String reason) {
        // The remote system's text, scrubbed before it is kept on the row and in the immutable chain (the security
        // review of 2026-10-27, I2).
        String cut = RemoteText.scrubbed(reason);
        store.markStatus(account.companyId(), account.id(), AccountInfo.Status.RECONNECT, cut, time.now());
        auditLog.record(AccountService.holderDetails(AuditRecord
            .company(account.companyId(), AuditAction.ACCOUNT_REFUSED, CurrentActor.actor())
            .target(AccountService.TARGET, account.id())
            .detail("systemId", account.systemId().toString()), account)
            .detail("reason", cut)
            .build());
        events.publishEvent(new AccountEvents.AccountNeedsReconnect(account.companyId(), account.id(),
                account.systemId(), account.principalId()));
    }

    private static Accounts.RevealedCredential oauth(OAuthTokens tokens, Read read) {
        return new Accounts.RevealedCredential(new SystemCalls.Credential(tokens.accessToken(), true),
                read.account().apiBaseUrl(), CallCredential.Endpoint.of(read.system()));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }
}
