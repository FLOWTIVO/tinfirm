package com.flowtivo.tinfirm.principal.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;

/** {@link Principals} over the principal store, in the caller's tenant context. */
@Component
class PrincipalsImpl implements Principals {

    static final String DELETED_USER = "deleted user";

    private final PrincipalStore store;

    private final CompanyDirectory companies;

    private final GroupMembership groups;

    private final UserDisplayNames userNames;

    PrincipalsImpl(PrincipalStore store, CompanyDirectory companies, GroupMembership groups,
            UserDisplayNames userNames) {
        this.store = store;
        this.companies = companies;
        this.groups = groups;
        this.userNames = userNames;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrincipalInfo> find(UUID companyId, UUID principalId) {
        return store.find(companyId, principalId).map(PrincipalStore.PrincipalRow::info);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PrincipalInfo> lockShared(UUID companyId, UUID principalId) {
        return store.lockShared(companyId, principalId).map(PrincipalStore.PrincipalRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PrincipalInfo> findAll(UUID companyId, Collection<UUID> principalIds) {
        Map<UUID, PrincipalInfo> found = new HashMap<>();
        store.findAll(companyId, principalIds).forEach((id, row) -> found.put(id, row.info()));
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> currentIds(UUID companyId) {
        return store.currentIds(companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrincipalInfo> ofMember(UUID companyId, UUID userId) {
        return store.ofMember(companyId, userId, false).map(PrincipalStore.PrincipalRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean mayUse(UUID companyId, UUID userId, UUID principalId) {
        Optional<PrincipalStore.PrincipalRow> principal = store.find(companyId, principalId);
        if (principal.isEmpty()) {
            return false;
        }
        PrincipalInfo info = principal.get().info();
        if (info.member()) {
            return info.isMember(userId);
        }
        return usableServiceIdentities(companyId, userId).contains(principalId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> usableServiceIdentities(UUID companyId, UUID userId) {
        Optional<CompanyRole> role = companies.roleOf(companyId, userId);
        // A VIEWER uses no service account, through a group or a trigger (the security review of 2026-10-27, M7): they
        // hold no seat and author nothing.
        if (role.isEmpty() || role.get() == CompanyRole.VIEWER) {
            return Set.of();
        }
        if (role.get() == CompanyRole.OWNER) {
            return store.services(companyId);
        }
        return store.usableByGroups(companyId, groups.groupsOf(companyId, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> displayNames(UUID companyId, Collection<UUID> principalIds) {
        Map<UUID, PrincipalStore.PrincipalRow> rows = store.findAll(companyId, principalIds);
        Set<UUID> userIds = new HashSet<>();
        rows.values().forEach(row -> {
            if (row.userId() != null) {
                userIds.add(row.userId());
            }
        });
        Map<UUID, String> users = userIds.isEmpty() ? Map.of() : userNames.displayNames(List.copyOf(userIds));
        Map<UUID, String> names = new HashMap<>();
        rows.forEach((id, row) -> {
            UUID userId = row.userId();
            String name = userId != null ? users.getOrDefault(userId, DELETED_USER) : row.name();
            names.put(id, name != null ? name : DELETED_USER);
        });
        return names;
    }
}
