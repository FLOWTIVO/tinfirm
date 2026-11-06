package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.UserEvents;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Password changes. Only local users have a password; other sessions of the user are logged out afterwards, and the
 * user's personal access tokens and OAuth grants are revoked (CONVENTIONS §5; the owner's decision Q6 of 2026-10-27).
 * An instance admin's reset is also recorded in the chain of every company the user belongs to ({@link
 * UserEvents.PasswordSetByInstanceAdmin}; the owner's decision Q7 of 2026-10-27).
 */
@Service
class PasswordService {

    private final UserRepository users;

    private final PasswordHasher hasher;

    private final AuditLog auditLog;

    private final UserSessions sessions;

    private final TimeSource time;

    private final BearerRevocations bearers;

    private final ApplicationEventPublisher events;

    PasswordService(UserRepository users, PasswordHasher hasher, AuditLog auditLog, UserSessions sessions,
            TimeSource time, BearerRevocations bearers, ApplicationEventPublisher events) {
        this.users = users;
        this.hasher = hasher;
        this.auditLog = auditLog;
        this.sessions = sessions;
        this.time = time;
        this.bearers = bearers;
        this.events = events;
    }

    @Transactional
    public void changeOwn(String currentPassword, String newPassword, @Nullable String currentSessionId) {
        AuthenticatedUser actor = CurrentActor.requireUser();
        UserEntity user = users.findById(actor.id())
            .orElseThrow(() -> ApiException.unauthorized("Authentication required"));
        requireLocal(user);
        if (!hasher.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest(ProblemCode.WRONG_PASSWORD, "The current password is wrong");
        }
        user.changePassword(hasher.hash(newPassword), time.now());
        auditLog.record(AuditRecord.instance(AuditAction.USER_PASSWORD_CHANGED, CurrentActor.actor())
            .target("user", user.getId())
            .build());
        bearers.passwordChanged(user.getId(), false);
        sessions.invalidateOthersAfterCommit(user.getId(), currentSessionId);
    }

    /**
     * Admin reset. The target's sessions are logged out (except the admin's own current session), their tokens and
     * grants revoked, and the reset recorded in each of their companies' chains.
     */
    @Transactional
    public void set(UUID userId, String newPassword, @Nullable String currentSessionId) {
        AuthenticatedUser admin = CurrentActor.requireInstanceAdmin();
        UserEntity user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
        requireLocal(user);
        user.changePassword(hasher.hash(newPassword), time.now());
        auditLog.record(AuditRecord.instance(AuditAction.USER_PASSWORD_SET, CurrentActor.actor())
            .target("user", user.getId())
            .build());
        bearers.passwordChanged(user.getId(), true);
        events.publishEvent(new UserEvents.PasswordSetByInstanceAdmin(user.getId(), admin.id()));
        sessions.invalidateOthersAfterCommit(user.getId(), admin.id().equals(userId) ? currentSessionId : null);
    }

    private static void requireLocal(UserEntity user) {
        if (!user.isLocal()) {
            throw ApiException.conflict(ProblemCode.NOT_LOCAL_USER,
                    "This user signs in with an identity provider and has no password");
        }
    }
}
