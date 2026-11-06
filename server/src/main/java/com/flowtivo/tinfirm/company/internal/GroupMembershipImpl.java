package com.flowtivo.tinfirm.company.internal;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.GroupMembership;

/** {@link GroupMembership} over the group store, in the caller's tenant context. */
@Component
class GroupMembershipImpl implements GroupMembership {

    private final GroupStore store;

    GroupMembershipImpl(GroupStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> groupsOf(UUID companyId, UUID userId) {
        return store.groupsOf(companyId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> groupNames(UUID companyId, Collection<UUID> groupIds) {
        return store.names(companyId, groupIds);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<UUID> membersOf(UUID companyId, UUID groupId) {
        return store.members(groupId);
    }
}
