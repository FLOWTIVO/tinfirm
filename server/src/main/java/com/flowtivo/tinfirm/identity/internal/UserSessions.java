package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.tx.AfterTransaction;

/**
 * Deletes a user's server-side sessions (found through Spring Session's principal-name index, which is the user id).
 * Deletion runs after the surrounding transaction commits, so a rolled-back change never logs anyone out, and once
 * that transaction has released its connection ({@link AfterTransaction}), before the request is answered. Spring
 * Session's repository reads and deletes each in a short transaction of its own, one connection at a time.
 */
@Component
class UserSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    UserSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    void invalidateAllAfterCommit(UUID userId) {
        invalidateAfterCommit(userId, null);
    }

    void invalidateOthersAfterCommit(UUID userId, @Nullable String keepSessionId) {
        invalidateAfterCommit(userId, keepSessionId);
    }

    private void invalidateAfterCommit(UUID userId, @Nullable String keepSessionId) {
        AfterTransaction.afterCommit(() -> sessions.findByPrincipalName(userId.toString())
            .keySet()
            .stream()
            .filter(sessionId -> !sessionId.equals(keepSessionId))
            .forEach(sessions::deleteById));
    }
}
