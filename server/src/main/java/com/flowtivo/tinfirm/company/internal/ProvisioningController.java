package com.flowtivo.tinfirm.company.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.ProvisioningApi;
import com.flowtivo.tinfirm.shared.api.model.ScimStatus;
import com.flowtivo.tinfirm.shared.api.model.ScimTokenCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.ScimTokenCreated;
import com.flowtivo.tinfirm.shared.api.model.ScimTokenList;

/** A company's SCIM in the app: its status and its tokens (ADR-0087, decision 11). */
@RestController
class ProvisioningController implements ProvisioningApi {

    private final ScimTokenService tokens;

    ProvisioningController(ScimTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public ResponseEntity<ScimStatus> getScimStatus(UUID companyId) {
        return ResponseEntity.ok(tokens.status(companyId));
    }

    @Override
    public ResponseEntity<ScimTokenList> listScimTokens(UUID companyId) {
        return ResponseEntity.ok(tokens.list(companyId));
    }

    @Override
    public ResponseEntity<ScimTokenCreated> createScimToken(UUID companyId, ScimTokenCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tokens.issue(companyId, request));
    }

    @Override
    public ResponseEntity<Void> revokeScimToken(UUID companyId, UUID tokenId) {
        tokens.revoke(companyId, tokenId);
        return ResponseEntity.noContent().build();
    }
}
