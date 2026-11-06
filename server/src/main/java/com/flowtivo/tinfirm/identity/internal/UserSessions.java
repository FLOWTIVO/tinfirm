package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes a user's server-side sessions (found through Spring Session's principal-name index, which is the user id).
 * Deletion runs after the surrounding transaction commits, so a rolled-back change never logs anyone out.
 */
@Component
class UserSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    private final TransactionTemplate newTransaction;

    UserSessions(FindByIndexNameSessionRepository<? extends Session> sessions,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void invalidateAllAfterCommit(UUID userId) {
        invalidateAfterCommit(userId, null);
    }

    void invalidateOthersAfterCommit(UUID userId, @Nullable String keepSessionId) {
        invalidateAfterCommit(userId, keepSessionId);
    }

    private void invalidateAfterCommit(UUID userId, @Nullable String keepSessionId) {
        Runnable invalidate = () -> newTransaction.executeWithoutResult(status -> sessions
            .findByPrincipalName(userId.toString())
            .keySet()
            .stream()
            .filter(sessionId -> !sessionId.equals(keepSessionId))
            .forEach(sessions::deleteById));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        }
        else {
            invalidate.run();
        }
    }
}
