package com.flowtivo.tinfirm.account.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.company.GroupUses;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;

/**
 * The accounts lent to a group, for its usage: its grants as {@code listGrants} shows them to whoever manages grants
 * ({@code MANAGE_GRANTS}), by system and service identity name.
 */
@Component
class GrantGroupUses implements GroupUses {

    private final Grants grants;

    GrantGroupUses(Grants grants) {
        this.grants = grants;
    }

    @Override
    public Uses of(CompanyAccess access, UUID groupId) {
        if (!access.covers(CompanyPermission.MANAGE_GRANTS)) {
            return Uses.NONE;
        }
        return Uses.grants(grants.ofGroup(access.companyId(), groupId));
    }
}
