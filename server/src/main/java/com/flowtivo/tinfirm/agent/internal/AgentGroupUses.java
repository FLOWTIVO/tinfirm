package com.flowtivo.tinfirm.agent.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.company.GroupUses;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.GroupUsageItem;

/**
 * The agents a group's members may use through it, for its usage: the live agents shared with the group, by name, as
 * {@code listAgents} and their {@code shares} show them to whoever sees every agent ({@code MANAGE_AGENTS}); one query.
 */
@Component
class AgentGroupUses implements GroupUses {

    private final AgentStore store;

    AgentGroupUses(AgentStore store) {
        this.store = store;
    }

    @Override
    public Uses of(CompanyAccess access, UUID groupId) {
        if (!access.covers(CompanyPermission.MANAGE_AGENTS)) {
            return Uses.NONE;
        }
        return Uses.agents(store.namesSharedWith(access.companyId(), groupId)
            .stream()
            .map(agent -> new GroupUsageItem(agent.getKey(), agent.getValue()))
            .toList());
    }
}
