package com.flowtivo.tinfirm.system.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Who may see and manage a system (the package 1 contract, §6): every member sees the company's systems and their own
 * personal MCP servers, never another member's (404, admins included); OWNER and ADMIN manage company systems
 * ({@code MANAGE_SYSTEMS}, 403 for a MEMBER); a personal server is managed by its owner only.
 */
@Component
class SystemAccess {

    private final CompanyAccessGuard guard;

    private final Principals principals;

    private final SystemStore store;

    SystemAccess(CompanyAccessGuard guard, Principals principals, SystemStore store) {
        this.guard = guard;
        this.principals = principals;
        this.store = store;
    }

    /**
     * The caller in the company.
     *
     * @param principalId the caller's member principal; null for an instance admin who is not a member
     */
    record Caller(CompanyAccess access, UUID userId, @Nullable UUID principalId) {

        UUID companyId() {
            return access.companyId();
        }

        boolean admin() {
            return access.covers(CompanyPermission.MANAGE_SYSTEMS);
        }

        boolean owns(SystemStore.SystemRow system) {
            return principalId != null && principalId.equals(system.ownerPrincipalId());
        }
    }

    /** Authorises the caller for the company's systems (any member) and names their principal. */
    Caller caller(UUID companyId) {
        CompanyAccess access = guard.require(companyId, CompanyPermission.VIEW_SYSTEMS);
        UUID userId = CurrentActor.requireUser().id();
        UUID principalId = principals.ofMember(companyId, userId).map(PrincipalInfo::id).orElse(null);
        return new Caller(access, userId, principalId);
    }

    /** Requires the caller to manage company systems; 403 otherwise (an instance admin passes, audited). */
    void requireAdmin(Caller caller) {
        if (!caller.admin()) {
            guard.require(caller.companyId(), CompanyPermission.MANAGE_SYSTEMS);
        }
    }

    /** The system when the caller sees it: a company system, or their own personal server; 404 otherwise. */
    SystemStore.SystemRow visible(Caller caller, UUID systemId) {
        return seen(caller, store.find(caller.companyId(), systemId));
    }

    /**
     * The system, locked, when the caller manages it: a company system for OWNER and ADMIN (403 for others), their
     * own personal server; 404 for a system they do not see.
     */
    SystemStore.SystemRow managed(Caller caller, UUID systemId) {
        SystemStore.SystemRow system = seen(caller, store.lock(caller.companyId(), systemId));
        if (!system.personal()) {
            requireAdmin(caller);
        }
        return system;
    }

    /**
     * The system again, locked, in the transaction that stores what a call to it found: {@link #managed} authorised
     * the caller before the call, so only whether the system is still there, and still theirs to see, is checked; 404
     * otherwise.
     */
    SystemStore.SystemRow relocked(Caller caller, UUID systemId) {
        return seen(caller, store.lock(caller.companyId(), systemId));
    }

    private static SystemStore.SystemRow seen(Caller caller, Optional<SystemStore.SystemRow> found) {
        SystemStore.SystemRow system = found.orElseThrow(SystemAccess::notFound);
        if (system.personal() && !caller.owns(system)) {
            throw notFound();
        }
        return system;
    }

    static ApiException notFound() {
        return ApiException.notFound("System not found");
    }
}
