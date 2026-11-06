package com.flowtivo.tinfirm.principal.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.ServiceIdentitiesApi;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentity;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentitySuspendRequest;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentityCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentityPage;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentityUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentityUsersRequest;

@RestController
class ServiceIdentitiesController implements ServiceIdentitiesApi {

    private final ServiceIdentityService service;

    ServiceIdentitiesController(ServiceIdentityService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<ServiceIdentityPage> listServiceIdentities(UUID companyId, Integer limit,
            @Nullable String cursor) {
        return ResponseEntity.ok(service.list(companyId, limit, cursor));
    }

    @Override
    public ResponseEntity<ServiceIdentity> createServiceIdentity(UUID companyId, ServiceIdentityCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(companyId, request));
    }

    @Override
    public ResponseEntity<ServiceIdentity> getServiceIdentity(UUID companyId, UUID serviceIdentityId) {
        return ResponseEntity.ok(service.get(companyId, serviceIdentityId));
    }

    @Override
    public ResponseEntity<ServiceIdentity> updateServiceIdentity(UUID companyId, UUID serviceIdentityId,
            ServiceIdentityUpdateRequest request) {
        return ResponseEntity.ok(service.update(companyId, serviceIdentityId, request));
    }

    @Override
    public ResponseEntity<Void> deleteServiceIdentity(UUID companyId, UUID serviceIdentityId) {
        service.delete(companyId, serviceIdentityId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ServiceIdentity> replaceServiceIdentityUsers(UUID companyId, UUID serviceIdentityId,
            ServiceIdentityUsersRequest request) {
        return ResponseEntity.ok(service.replaceUsers(companyId, serviceIdentityId, request));
    }

    @Override
    public ResponseEntity<ServiceIdentity> suspendServiceIdentity(UUID companyId, UUID serviceIdentityId,
            @Nullable ServiceIdentitySuspendRequest request) {
        return ResponseEntity.ok(service.suspend(companyId, serviceIdentityId,
                request != null ? request.getReason() : null));
    }

    @Override
    public ResponseEntity<ServiceIdentity> resumeServiceIdentity(UUID companyId, UUID serviceIdentityId) {
        return ResponseEntity.ok(service.resume(companyId, serviceIdentityId));
    }
}
