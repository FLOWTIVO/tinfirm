package com.flowtivo.tinfirm.company.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.identity.UserEvents;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.SystemContext;

/**
 * An instance admin's reset of a user's password is recorded in the chain of every company the user belongs to (the
 * security review of 2026-10-27, L35; the owner's decision Q7): signing in as the person gives their roles and their
 * own accounts there, so each company's owners see who reset it. {@code member.password_reset_by_instance_admin},
 * actor the instance admin, target the user, in the reset's transaction. The companies come from the narrow lookup
 * ({@link CompanyDirectory#companiesOf}); each event is recorded in its company's tenant context, which the audit log
 * applies when it appends it.
 */
@Component
class InstanceAdminPasswordResets {

    private final CompanyDirectory companies;

    private final AuditLog auditLog;

    InstanceAdminPasswordResets(CompanyDirectory companies, AuditLog auditLog) {
        this.companies = companies;
        this.auditLog = auditLog;
    }

    @EventListener
    void passwordSet(UserEvents.PasswordSetByInstanceAdmin event) {
        List<UUID> ofUser = companies.companiesOf(event.userId());
        for (UUID companyId : ofUser) {
            SystemContext.runInCompany(companyId, () -> auditLog.record(AuditRecord
                .company(companyId, AuditAction.MEMBER_PASSWORD_RESET_BY_INSTANCE_ADMIN,
                        Actor.user(event.instanceAdminId()))
                .target("user", event.userId())
                .detail("userId", event.userId().toString())
                .build()));
        }
    }
}
