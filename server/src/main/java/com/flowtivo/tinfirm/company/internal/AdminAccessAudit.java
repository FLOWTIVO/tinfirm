package com.flowtivo.tinfirm.company.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.security.Actor;

/**
 * Break-glass audit: an instance admin used admin rights inside a company. Recorded in its own transaction,
 * so the access is on record even if the operation itself fails or is read-only. A mandatory reason is planned and not
 * built (threat model 1.15 and Q5); no milestone is set for it.
 */
@Component
class AdminAccessAudit {

    private final AuditLog auditLog;

    AdminAccessAudit(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID companyId, UUID adminUserId, CompanyPermission permission) {
        auditLog.record(AuditRecord.company(companyId, AuditAction.ADMIN_COMPANY_ACCESSED, Actor.user(adminUserId))
            .target("company", companyId)
            .detail("permission", permission.name())
            .build());
    }
}
