package com.flowtivo.tinfirm.identity.internal;

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.UserEvents;
import com.flowtivo.tinfirm.shared.security.Actor;

/**
 * Login events in the instance chain. Details never contain the email: a failed login for an unknown email records
 * only {@code {"reason":"unknown_user"}}. A successful login is also published as {@link UserEvents.UserSignedIn}.
 */
@Component
class LoginAudit {

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    LoginAudit(AuditLog auditLog, ApplicationEventPublisher events) {
        this.auditLog = auditLog;
        this.events = events;
    }

    void succeeded(UUID userId, String method, @Nullable String provider) {
        succeeded(userId, method, provider, Set.of());
    }

    /**
     * @param providerCompanyIds the companies the login's provider fills (ADR-0075, ADR-0089) when the login asserted
     *                           the user's email, for the declared owner; empty otherwise
     */
    void succeeded(UUID userId, String method, @Nullable String provider, Set<UUID> providerCompanyIds) {
        AuditRecord.Builder event = AuditRecord.instance(AuditAction.AUTH_LOGIN_SUCCEEDED, Actor.user(userId))
            .target("user", userId)
            .detail("method", method);
        if (provider != null) {
            event.detail("provider", provider);
        }
        auditLog.record(event.build());
        events.publishEvent(new UserEvents.UserSignedIn(userId, "local".equals(method), providerCompanyIds));
    }

    void failed(@Nullable UUID userId, String reason, @Nullable String provider) {
        failed(userId, reason, provider, null);
    }

    /** @param method the sign-in's method when it names one: {@code saml} (ADR-0086, decision 13) */
    void failed(@Nullable UUID userId, String reason, @Nullable String provider, @Nullable String method) {
        AuditRecord.Builder event = AuditRecord.instance(AuditAction.AUTH_LOGIN_FAILED, Actor.ANONYMOUS)
            .detail("reason", reason);
        if (method != null) {
            event.detail("method", method);
        }
        if (userId != null) {
            event.target("user", userId);
        }
        if (provider != null) {
            event.detail("provider", provider);
        }
        auditLog.record(event.build());
    }
}
