package com.flowtivo.tinfirm.governance.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.RetentionApi;
import com.flowtivo.tinfirm.shared.api.model.Erasure;
import com.flowtivo.tinfirm.shared.api.model.ErasurePreview;
import com.flowtivo.tinfirm.shared.api.model.HoldConversationRef;
import com.flowtivo.tinfirm.shared.api.model.LegalHold;
import com.flowtivo.tinfirm.shared.api.model.LegalHoldPage;
import com.flowtivo.tinfirm.shared.api.model.LegalHoldRequest;
import com.flowtivo.tinfirm.shared.api.model.PrincipalErasureRequest;
import com.flowtivo.tinfirm.shared.api.model.RetentionOpenConversationPage;
import com.flowtivo.tinfirm.shared.api.model.RetentionPeriods;
import com.flowtivo.tinfirm.shared.api.model.RetentionPreview;
import com.flowtivo.tinfirm.shared.api.model.RetentionSettings;

@RestController
class GovernanceController implements RetentionApi {

    private final RetentionService retention;

    private final HoldService holds;

    private final ErasureService erasure;

    GovernanceController(RetentionService retention, HoldService holds, ErasureService erasure) {
        this.retention = retention;
        this.holds = holds;
        this.erasure = erasure;
    }

    @Override
    public ResponseEntity<RetentionSettings> getRetention(UUID companyId) {
        return ResponseEntity.ok(retention.get(companyId));
    }

    @Override
    public ResponseEntity<RetentionSettings> updateRetention(UUID companyId, RetentionPeriods request) {
        return ResponseEntity.ok(retention.update(companyId, request));
    }

    @Override
    public ResponseEntity<RetentionPreview> previewRetention(UUID companyId, RetentionPeriods request) {
        return ResponseEntity.ok(retention.preview(companyId, request));
    }

    @Override
    public ResponseEntity<RetentionOpenConversationPage> listRetentionOpenConversations(UUID companyId,
            Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(retention.openConversations(companyId, limit, cursor));
    }

    @Override
    public ResponseEntity<LegalHoldPage> listHolds(UUID companyId, Integer limit, @Nullable String cursor,
            Boolean lifted) {
        return ResponseEntity.ok(holds.list(companyId, Boolean.TRUE.equals(lifted), limit, cursor));
    }

    @Override
    public ResponseEntity<HoldConversationRef> findHoldConversation(UUID companyId, String key) {
        return ResponseEntity.ok(holds.conversation(companyId, key));
    }

    @Override
    public ResponseEntity<LegalHold> createHold(UUID companyId, LegalHoldRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(holds.set(companyId, request));
    }

    @Override
    public ResponseEntity<Void> liftHold(UUID companyId, UUID holdId) {
        holds.lift(companyId, holdId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Erasure> erasePrincipal(UUID companyId, UUID principalId,
            @Nullable PrincipalErasureRequest request) {
        return ResponseEntity.ok(erasure.erasePrincipal(companyId, principalId,
                request != null ? request.getConversationIds() : null));
    }

    @Override
    public ResponseEntity<ErasurePreview> previewPrincipalErasure(UUID companyId, UUID principalId,
            @Nullable PrincipalErasureRequest request) {
        return ResponseEntity.ok(erasure.previewPrincipal(companyId, principalId,
                request != null ? request.getConversationIds() : null));
    }

    @Override
    public ResponseEntity<Erasure> eraseExternalIdentity(UUID companyId, UUID systemId, String externalId) {
        return ResponseEntity.ok(erasure.eraseIdentity(companyId, systemId, externalId));
    }

    @Override
    public ResponseEntity<ErasurePreview> previewExternalIdentityErasure(UUID companyId, UUID systemId,
            String externalId) {
        return ResponseEntity.ok(erasure.previewIdentity(companyId, systemId, externalId));
    }
}
