package com.flowtivo.tinfirm.agent.internal;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.MemorySettingsApi;
import com.flowtivo.tinfirm.shared.api.model.MemorySettings;
import com.flowtivo.tinfirm.shared.api.model.MemorySettingsRequest;

@RestController
class MemorySettingsController implements MemorySettingsApi {

    private final MemorySettingsService settings;

    MemorySettingsController(MemorySettingsService settings) {
        this.settings = settings;
    }

    @Override
    public ResponseEntity<MemorySettings> getMemorySettings(UUID companyId) {
        return ResponseEntity.ok(settings.get(companyId));
    }

    @Override
    public ResponseEntity<MemorySettings> putMemorySettings(UUID companyId, MemorySettingsRequest request) {
        return ResponseEntity.ok(settings.put(companyId, request));
    }
}
