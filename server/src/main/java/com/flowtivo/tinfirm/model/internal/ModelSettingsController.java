package com.flowtivo.tinfirm.model.internal;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.ModelSettingsApi;
import com.flowtivo.tinfirm.shared.api.model.GatewayModelList;
import com.flowtivo.tinfirm.shared.api.model.ModelKeyRotationRequest;
import com.flowtivo.tinfirm.shared.api.model.ModelSettings;
import com.flowtivo.tinfirm.shared.api.model.ModelSettingsRequest;
import com.flowtivo.tinfirm.shared.api.model.ModelSettingsTestResult;

@RestController
class ModelSettingsController implements ModelSettingsApi {

    private final ModelSettingsService settings;

    private final ModelKeyRotationService rotation;

    ModelSettingsController(ModelSettingsService settings, ModelKeyRotationService rotation) {
        this.settings = settings;
        this.rotation = rotation;
    }

    @Override
    public ResponseEntity<ModelSettings> getModelSettings(UUID companyId) {
        return ResponseEntity.ok(settings.get(companyId));
    }

    @Override
    public ResponseEntity<ModelSettings> putModelSettings(UUID companyId, ModelSettingsRequest request) {
        return ResponseEntity.ok(settings.put(companyId, request));
    }

    @Override
    public ResponseEntity<ModelSettingsTestResult> testModelSettings(UUID companyId) {
        return ResponseEntity.ok(settings.test(companyId));
    }

    @Override
    public ResponseEntity<GatewayModelList> listGatewayModels(UUID companyId) {
        return ResponseEntity.ok(settings.models(companyId));
    }

    @Override
    public ResponseEntity<ModelKeyRotationRequest> rotateCompanyModelKeys(UUID companyId) {
        return ResponseEntity.accepted().body(rotation.rotateAll(companyId));
    }
}
