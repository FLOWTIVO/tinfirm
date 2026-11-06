package com.flowtivo.tinfirm.system.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.api.model.ExternalSystem;
import com.flowtivo.tinfirm.shared.api.model.ExternalSystemPage;
import com.flowtivo.tinfirm.shared.api.model.SystemTestResult;
import com.flowtivo.tinfirm.shared.api.model.SystemToolList;
import com.flowtivo.tinfirm.system.SystemReads;

/**
 * The Systems API's reads and its test for the caller's AI client ({@link SystemReads}): the calls of
 * {@link SystemsController}, to the same services, with the same inputs.
 */
@Component
class SystemClientReads implements SystemReads {

    private final SystemService systems;

    private final CatalogueService catalogue;

    SystemClientReads(SystemService systems, CatalogueService catalogue) {
        this.systems = systems;
        this.catalogue = catalogue;
    }

    @Override
    public ExternalSystemPage list(UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        return systems.list(companyId, limit, cursor);
    }

    @Override
    public ExternalSystem get(UUID companyId, UUID systemId) {
        return systems.get(companyId, systemId);
    }

    @Override
    public SystemToolList tools(UUID companyId, UUID systemId) {
        return catalogue.tools(companyId, systemId);
    }

    @Override
    public SystemTestResult test(UUID companyId, UUID systemId) {
        return systems.test(companyId, systemId, null);
    }
}
