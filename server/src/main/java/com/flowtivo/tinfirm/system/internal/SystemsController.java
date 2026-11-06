package com.flowtivo.tinfirm.system.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.SystemsApi;
import com.flowtivo.tinfirm.shared.api.model.ExternalSystem;
import com.flowtivo.tinfirm.shared.api.model.ExternalSystemPage;
import com.flowtivo.tinfirm.shared.api.model.SystemAppRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemRepository;
import com.flowtivo.tinfirm.shared.api.model.SystemRepositoryCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemRepositoryPage;
import com.flowtivo.tinfirm.shared.api.model.SystemSettings;
import com.flowtivo.tinfirm.shared.api.model.SystemTestResult;
import com.flowtivo.tinfirm.shared.api.model.SystemTool;
import com.flowtivo.tinfirm.shared.api.model.SystemToolList;
import com.flowtivo.tinfirm.shared.api.model.SystemToolRefresh;
import com.flowtivo.tinfirm.shared.api.model.SystemToolUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemUpdateRequest;

@RestController
class SystemsController implements SystemsApi {

    private final SystemService systems;

    private final CatalogueService catalogue;

    SystemsController(SystemService systems, CatalogueService catalogue) {
        this.systems = systems;
        this.catalogue = catalogue;
    }

    @Override
    public ResponseEntity<ExternalSystemPage> listSystems(UUID companyId, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(systems.list(companyId, limit, cursor));
    }

    @Override
    public ResponseEntity<ExternalSystem> createSystem(UUID companyId, SystemCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(systems.create(companyId, request));
    }

    @Override
    public ResponseEntity<ExternalSystem> getSystem(UUID companyId, UUID systemId) {
        return ResponseEntity.ok(systems.get(companyId, systemId));
    }

    @Override
    public ResponseEntity<ExternalSystem> updateSystem(UUID companyId, UUID systemId, SystemUpdateRequest request) {
        return ResponseEntity.ok(systems.update(companyId, systemId, request));
    }

    @Override
    public ResponseEntity<Void> deleteSystem(UUID companyId, UUID systemId) {
        systems.delete(companyId, systemId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ExternalSystem> setSystemApp(UUID companyId, UUID systemId, SystemAppRequest request) {
        return ResponseEntity.ok(systems.setApp(companyId, systemId, request));
    }

    @Override
    public ResponseEntity<Void> removeSystemApp(UUID companyId, UUID systemId) {
        systems.removeApp(companyId, systemId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<SystemTestResult> testSystem(UUID companyId, UUID systemId, @Nullable UUID credentialId) {
        return ResponseEntity.ok(systems.test(companyId, systemId, credentialId));
    }

    @Override
    public ResponseEntity<SystemRepositoryPage> listSystemRepositories(UUID companyId, UUID systemId, Integer limit,
            @Nullable String cursor) {
        return ResponseEntity.ok(catalogue.repositories(companyId, systemId, limit, cursor));
    }

    @Override
    public ResponseEntity<SystemRepository> addSystemRepository(UUID companyId, UUID systemId,
            SystemRepositoryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogue.addRepository(companyId, systemId, request));
    }

    @Override
    public ResponseEntity<Void> removeSystemRepository(UUID companyId, UUID systemId, UUID repositoryId) {
        catalogue.removeRepository(companyId, systemId, repositoryId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<SystemToolList> listSystemTools(UUID companyId, UUID systemId) {
        return ResponseEntity.ok(catalogue.tools(companyId, systemId));
    }

    @Override
    public ResponseEntity<SystemToolRefresh> refreshSystemTools(UUID companyId, UUID systemId,
            @Nullable UUID credentialId) {
        return ResponseEntity.ok(catalogue.refresh(companyId, systemId, credentialId));
    }

    @Override
    public ResponseEntity<SystemTool> updateSystemTool(UUID companyId, UUID systemId, String toolName,
            SystemToolUpdateRequest request) {
        return ResponseEntity.ok(catalogue.updateTool(companyId, systemId, toolName, request));
    }

    @Override
    public ResponseEntity<SystemSettings> getSystemSettings(UUID companyId) {
        return ResponseEntity.ok(systems.settings(companyId));
    }

    @Override
    public ResponseEntity<SystemSettings> replaceSystemSettings(UUID companyId, SystemSettings request) {
        return ResponseEntity.ok(systems.replaceSettings(companyId, request));
    }
}
