package com.flowtivo.tinfirm.principal.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditPrincipals;
import com.flowtivo.tinfirm.principal.PrincipalStatus;

/**
 * The audit's port on principals (CONVENTIONS §8, §15.11): whose user a member's principal is, and which principals a
 * user had in the company, those who left included. In the reader's transaction and tenant context.
 */
@Component
class AuditPrincipalsImpl implements AuditPrincipals {

    private final PrincipalStore store;

    AuditPrincipalsImpl(PrincipalStore store) {
        this.store = store;
    }

    @Override
    public Map<UUID, Principal> find(UUID companyId, Collection<UUID> principalIds) {
        Map<UUID, Principal> principals = new HashMap<>();
        store.findAll(companyId, principalIds).forEach((id, row) -> principals.put(id, principal(row)));
        return principals;
    }

    @Override
    public List<Principal> ofUsers(UUID companyId, Collection<UUID> userIds) {
        return store.ofUsers(companyId, userIds).stream().map(AuditPrincipalsImpl::principal).toList();
    }

    private static Principal principal(PrincipalStore.PrincipalRow row) {
        return new Principal(row.id(), row.userId(), row.name(), row.status() == PrincipalStatus.LEFT);
    }
}
