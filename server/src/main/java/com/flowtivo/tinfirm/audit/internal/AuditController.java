package com.flowtivo.tinfirm.audit.internal;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.AuditApi;
import com.flowtivo.tinfirm.shared.api.model.AuditCheck;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckAcknowledgement;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckPage;
import com.flowtivo.tinfirm.shared.api.model.AuditEventPage;
import com.flowtivo.tinfirm.shared.api.model.AuditVerification;

@RestController
class AuditController implements AuditApi {

    private final AuditService auditService;

    AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public ResponseEntity<AuditEventPage> listCompanyAuditEvents(UUID companyId, Integer limit,
            @Nullable String cursor, @Nullable String action, @Nullable String actorType, @Nullable UUID actorId,
            @Nullable String targetType, @Nullable String targetId, @Nullable OffsetDateTime from,
            @Nullable OffsetDateTime to, @Nullable UUID conversationId, @Nullable String conversationKey,
            @Nullable UUID principalId) {
        return ResponseEntity.ok(auditService.companyEvents(companyId, limit, cursor,
                new AuditQuery(action, actorType, actorId, targetType, targetId,
                        from != null ? from.toInstant() : null, to != null ? to.toInstant() : null, conversationId,
                        conversationKey, principalId)));
    }

    @Override
    public ResponseEntity<AuditVerification> verifyCompanyAuditChain(UUID companyId) {
        return ResponseEntity.ok(auditService.verifyCompanyChain(companyId));
    }

    /** Resolved in a transaction, then streamed outside any ({@link AuditTools.Export}). */
    @Override
    public ResponseEntity<String> exportCompanyAuditEvents(UUID companyId, @Nullable Long fromSeq,
            @Nullable Long toSeq, @Nullable OffsetDateTime from, @Nullable OffsetDateTime to) {
        return auditService.export(companyId, fromSeq, toSeq, from != null ? from.toInstant() : null,
                to != null ? to.toInstant() : null).response();
    }

    @Override
    public ResponseEntity<AuditCheckPage> listCompanyAuditChecks(UUID companyId, Integer limit,
            @Nullable String cursor) {
        return ResponseEntity.ok(auditService.companyChecks(companyId, limit, cursor));
    }

    @Override
    public ResponseEntity<AuditCheck> acknowledgeCompanyAuditCheck(UUID companyId, UUID checkId,
            AuditCheckAcknowledgement request) {
        return ResponseEntity.ok(auditService.acknowledgeCompanyCheck(companyId, checkId, request.getNote()));
    }
}
