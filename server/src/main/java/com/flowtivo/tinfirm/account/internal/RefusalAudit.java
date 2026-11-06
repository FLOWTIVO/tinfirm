package com.flowtivo.tinfirm.account.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.diagnostics.RemoteText;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

/**
 * {@code account.refused} for a credential the system refused while it was being connected: written in a transaction
 * of its own, which the refusal's 400 does not roll back. In the tenant context the request entered.
 */
@Component
class RefusalAudit {

    private final AuditLog auditLog;

    RefusalAudit(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /**
     * The system's reason, scrubbed as an error report's message ({@link RemoteText}): masked, without control
     * characters, at most 300 characters; never a token.
     *
     * @param principalId the member whose own account it was to be; null for a credential
     * @param credential  the credential's name; null for a member's own account
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refused(UUID companyId, UUID systemId, @Nullable UUID principalId, @Nullable String credential,
            String reason) {
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.ACCOUNT_REFUSED, CurrentActor.actor())
            .target("system", systemId)
            .detail("systemId", systemId.toString());
        if (principalId != null) {
            audit.detail("principalId", principalId.toString());
        }
        if (credential != null) {
            audit.detail("credential", credential);
        }
        // The remote system's text enters the immutable chain scrubbed (the security review of 2026-10-27, I2): no
        // token it echoed, no NUL that would fail the commit, no control characters.
        auditLog.record(audit.detail("reason", RemoteText.scrubbed(reason)).build());
    }
}
