package com.flowtivo.tinfirm.vault.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.SecretsApi;
import com.flowtivo.tinfirm.shared.api.model.SecretCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SecretMeta;
import com.flowtivo.tinfirm.shared.api.model.SecretPage;
import com.flowtivo.tinfirm.shared.api.model.SecretUpdateRequest;

@RestController
class SecretsController implements SecretsApi {

    private final SecretService secretService;

    SecretsController(SecretService secretService) {
        this.secretService = secretService;
    }

    @Override
    public ResponseEntity<SecretPage> listSecrets(UUID companyId, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(secretService.list(companyId, limit, cursor));
    }

    @Override
    public ResponseEntity<SecretMeta> createSecret(UUID companyId, SecretCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(secretService.create(companyId, request));
    }

    @Override
    public ResponseEntity<SecretMeta> updateSecret(UUID companyId, UUID secretId, SecretUpdateRequest request) {
        return ResponseEntity.ok(secretService.update(companyId, secretId, request));
    }

    @Override
    public ResponseEntity<Void> deleteSecret(UUID companyId, UUID secretId) {
        secretService.delete(companyId, secretId);
        return ResponseEntity.noContent().build();
    }
}
