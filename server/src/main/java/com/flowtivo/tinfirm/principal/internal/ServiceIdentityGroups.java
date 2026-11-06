package com.flowtivo.tinfirm.principal.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.GroupDependents;
import com.flowtivo.tinfirm.company.GroupUses;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.GroupUsageItem;

/**
 * A deleted group may use no service identity any longer, each change audited and announced; and, for a group's
 * usage, the service identities it may use, by name, as {@code listServiceIdentities} shows their {@code userGroups} to
 * whoever manages them ({@code MANAGE_SERVICE_IDENTITIES}), in one query.
 */
@Component
class ServiceIdentityGroups implements GroupDependents, GroupUses {

    private final ServiceIdentityService service;

    private final PrincipalStore store;

    ServiceIdentityGroups(ServiceIdentityService service, PrincipalStore store) {
        this.service = service;
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void groupDeleting(UUID companyId, UUID groupId) {
        service.groupDeleting(companyId, groupId);
    }

    @Override
    public Uses of(CompanyAccess access, UUID groupId) {
        if (!access.covers(CompanyPermission.MANAGE_SERVICE_IDENTITIES)) {
            return Uses.NONE;
        }
        return Uses.serviceIdentities(store.serviceNamesUsableBy(access.companyId(), groupId)
            .stream()
            .map(identity -> new GroupUsageItem(identity.getKey(), identity.getValue()))
            .toList());
    }
}
