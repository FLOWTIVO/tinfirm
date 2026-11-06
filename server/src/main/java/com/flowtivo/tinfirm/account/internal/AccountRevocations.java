package com.flowtivo.tinfirm.account.internal;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.OAuthTokens;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemInfo;

/**
 * Revoking an OAuth account's grant at its system (RFC 7009), outside any transaction: at once for a disconnect asked
 * for, between the transaction that read the grant and the one that removes the account; in the background after the
 * commit for an account that goes with its principal (a deleted service identity, a member who left), whose removal
 * runs in the transaction of that change. Best effort: a failure is logged and never stops a removal.
 */
@Component
class AccountRevocations implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AccountRevocations.class);

    /** How long a shutdown waits for the revocations under way. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final SystemCalls calls;

    private final ExecutorService background = Executors
        .newThreadPerTaskExecutor(Thread.ofVirtual().name("account-revocation-", 0).factory());

    AccountRevocations(SystemCalls calls) {
        this.calls = calls;
    }

    /**
     * A grant to revoke, read before its account went: the account, its system and its tokens. Never log the tokens.
     */
    record Revocation(UUID companyId, UUID accountId, SystemInfo system, OAuthTokens tokens) {

        @Override
        public String toString() {
            return "Revocation[account=" + accountId + ", system=" + system.id() + "]";
        }
    }

    /**
     * Revokes the grant now, outside any transaction, in the caller's tenant context; whether the system confirmed it
     * ({@code false} also when it offers no revocation).
     */
    boolean now(Revocation revocation) {
        CallResult<Boolean> result = calls.revoke(revocation.system(), revocation.tokens());
        if (result.failure() != null) {
            log.info("Revoking the grant of the account {} failed: {}", revocation.accountId(),
                    result.failureMessage());
            return false;
        }
        return Boolean.TRUE.equals(result.value());
    }

    /**
     * Revokes the grant once the current transaction has committed, in the background, as the system in the account's
     * company: never when it rolled back, and never on the committing thread, whose transaction still holds its
     * connection while its synchronisations run.
     */
    void afterCommit(Revocation revocation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            inBackground(revocation);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    inBackground(revocation);
                }
            }
        });
    }

    private void inBackground(Revocation revocation) {
        background.execute(() -> {
            try {
                boolean revoked = SystemContext.callInCompany(revocation.companyId(), () -> now(revocation));
                log.info("The grant of the removed account {} was {}revoked at its system", revocation.accountId(),
                        revoked ? "" : "not ");
            }
            catch (RuntimeException ex) {
                log.warn("Revoking the grant of the removed account {} failed", revocation.accountId(), ex);
            }
        });
    }

    @Override
    public void destroy() throws InterruptedException {
        background.shutdown();
        background.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }
}
