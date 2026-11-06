package com.flowtivo.tinfirm.model.internal;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.ModelAccessApi;
import com.flowtivo.tinfirm.shared.api.model.ModelAccessUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.ModelAccessView;
import com.flowtivo.tinfirm.shared.api.model.ModelKeyRequest;

@RestController
class ModelAccessController implements ModelAccessApi {

    private final ModelAccessService access;

    private final ModelKeyRotationService rotation;

    private final ProvidedKeyService provided;

    ModelAccessController(ModelAccessService access, ModelKeyRotationService rotation, ProvidedKeyService provided) {
        this.access = access;
        this.rotation = rotation;
        this.provided = provided;
    }

    @Override
    public ResponseEntity<ModelAccessView> getMyModelAccess(UUID companyId) {
        return ResponseEntity.ok(access.mine(companyId));
    }

    @Override
    public ResponseEntity<ModelAccessView> getPrincipalModelAccess(UUID companyId, UUID principalId) {
        return ResponseEntity.ok(access.get(companyId, principalId));
    }

    @Override
    public ResponseEntity<ModelAccessView> updatePrincipalModelAccess(UUID companyId, UUID principalId,
            ModelAccessUpdateRequest request) {
        return ResponseEntity.ok(access.update(companyId, principalId, request));
    }

    @Override
    public ResponseEntity<ModelAccessView> rotatePrincipalModelKey(UUID companyId, UUID principalId) {
        return rotation.rotate(companyId, principalId);
    }

    @Override
    public ResponseEntity<ModelAccessView> getCompanyKeyModelAccess(UUID companyId) {
        return ResponseEntity.ok(access.company(companyId));
    }

    @Override
    public ResponseEntity<ModelAccessView> updateCompanyKeyModelAccess(UUID companyId,
            ModelAccessUpdateRequest request) {
        return ResponseEntity.ok(access.updateCompany(companyId, request));
    }

    @Override
    public ResponseEntity<ModelAccessView> rotateCompanyKey(UUID companyId) {
        return rotation.rotateCompany(companyId);
    }

    @Override
    public ResponseEntity<ModelAccessView> putPrincipalModelKey(UUID companyId, UUID principalId,
            ModelKeyRequest request) {
        return ResponseEntity.ok(provided.putPrincipal(companyId, principalId, request.getKey()));
    }

    @Override
    public ResponseEntity<ModelAccessView> deletePrincipalModelKey(UUID companyId, UUID principalId) {
        return ResponseEntity.ok(provided.deletePrincipal(companyId, principalId));
    }

    @Override
    public ResponseEntity<ModelAccessView> putCompanyModelKey(UUID companyId, ModelKeyRequest request) {
        return ResponseEntity.ok(provided.putCompany(companyId, request.getKey()));
    }

    @Override
    public ResponseEntity<ModelAccessView> deleteCompanyModelKey(UUID companyId) {
        return ResponseEntity.ok(provided.deleteCompany(companyId));
    }

    @Override
    public ResponseEntity<ModelAccessView> putMyModelKey(UUID companyId, ModelKeyRequest request) {
        return ResponseEntity.ok(provided.putMine(companyId, request.getKey()));
    }

    @Override
    public ResponseEntity<ModelAccessView> deleteMyModelKey(UUID companyId) {
        return ResponseEntity.ok(provided.deleteMine(companyId));
    }
}
