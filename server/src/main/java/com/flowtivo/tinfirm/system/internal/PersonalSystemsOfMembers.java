package com.flowtivo.tinfirm.system.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.principal.PrincipalDependents;

/**
 * A member who leaves takes their personal MCP servers with them (I8): each is removed as its owner would remove it,
 * accounts first, audited with the cause {@code principal.left}. A service identity owns no system.
 */
@Component
class PersonalSystemsOfMembers implements PrincipalDependents {

    private final SystemStore store;

    private final SystemService systems;

    PersonalSystemsOfMembers(SystemStore store, SystemService systems) {
        this.store = store;
        this.systems = systems;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void principalDeleting(UUID companyId, UUID principalId) {
        // A service identity owns no system (external_system_owner_fk admits members' principals only).
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberLeaving(UUID companyId, UUID principalId, UUID userId) {
        for (SystemStore.SystemRow system : store.lockPersonal(companyId, principalId)) {
            systems.remove(system, "principal.left");
        }
    }
}
