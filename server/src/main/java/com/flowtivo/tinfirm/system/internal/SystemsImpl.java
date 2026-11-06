package com.flowtivo.tinfirm.system.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.ToolInfo;
import com.flowtivo.tinfirm.system.Systems;

/** {@link Systems} over the system stores, in the caller's tenant context. */
@Component
class SystemsImpl implements Systems {

    private final SystemStore store;

    private final CatalogueStore catalogue;

    SystemsImpl(SystemStore store, CatalogueStore catalogue) {
        this.store = store;
        this.catalogue = catalogue;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SystemInfo> find(UUID companyId, UUID systemId) {
        return store.find(companyId, systemId).map(SystemStore.SystemRow::info);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SystemInfo> lockShared(UUID companyId, UUID systemId) {
        return store.lockShared(companyId, systemId).map(SystemStore.SystemRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, SystemInfo> findAll(UUID companyId, Collection<UUID> systemIds) {
        Map<UUID, SystemInfo> found = new HashMap<>();
        store.findAll(companyId, systemIds).forEach((id, row) -> found.put(id, row.info()));
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SystemInfo> visibleTo(UUID companyId, @Nullable UUID memberPrincipalId) {
        return store.page(companyId, memberPrincipalId, null, null, Integer.MAX_VALUE)
            .stream()
            .map(SystemStore.SystemRow::info)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean personalSystemsOn(UUID companyId) {
        return store.personalSystemsOn(companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ToolInfo> tools(UUID companyId, UUID systemId) {
        return store.find(companyId, systemId).map(system -> {
            if (system.kind().hasFixedTools()) {
                return FixedTools.tools(system.kind());
            }
            return catalogue.tools(companyId, systemId)
                .stream()
                .map(tool -> new ToolInfo(tool.name(), tool.title(), tool.description(), tool.inputSchema(),
                        tool.effect(), tool.enabled(), false))
                .toList();
        }).orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RepositoryInfo> repositories(UUID companyId, UUID systemId) {
        return catalogue.repositories(companyId, systemId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepositoryInfo> repository(UUID companyId, UUID repositoryId) {
        return catalogue.findRepository(companyId, repositoryId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int forgetTester(UUID companyId, UUID principalId) {
        return store.forgetTester(companyId, principalId);
    }

    @Override
    @Transactional(readOnly = true)
    public int countTester(UUID companyId, UUID principalId) {
        return store.countTester(companyId, principalId);
    }
}
