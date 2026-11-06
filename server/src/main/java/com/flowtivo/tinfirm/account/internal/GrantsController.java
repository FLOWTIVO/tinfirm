package com.flowtivo.tinfirm.account.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.GrantsApi;
import com.flowtivo.tinfirm.shared.api.model.AccountGrant;
import com.flowtivo.tinfirm.shared.api.model.AccountGrantCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.AccountGrantPage;
import com.flowtivo.tinfirm.shared.api.model.AccountGrantUpdateRequest;

@RestController
class GrantsController implements GrantsApi {

    private final Grants grants;

    GrantsController(Grants grants) {
        this.grants = grants;
    }

    @Override
    public ResponseEntity<AccountGrantPage> listGrants(UUID companyId, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(grants.list(companyId, limit, cursor));
    }

    @Override
    public ResponseEntity<AccountGrant> createGrant(UUID companyId, AccountGrantCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(grants.create(companyId, request));
    }

    @Override
    public ResponseEntity<AccountGrant> updateGrant(UUID companyId, UUID grantId, AccountGrantUpdateRequest request) {
        return ResponseEntity.ok(grants.update(companyId, grantId, request));
    }

    @Override
    public ResponseEntity<Void> deleteGrant(UUID companyId, UUID grantId) {
        grants.delete(companyId, grantId);
        return ResponseEntity.noContent().build();
    }
}
