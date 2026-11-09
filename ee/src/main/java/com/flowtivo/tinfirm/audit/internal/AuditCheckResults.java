package com.flowtivo.tinfirm.audit.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.api.model.AuditCheck;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckPage;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * The daily checks' results (the package 6 contract, §5.8; ADR-0077, decision 4, {@code AUDIT_TOOLS}): the checks of a
 * chain, the acknowledgement of its latest failure by an OWNER (an instance admin for the instance chain), audited
 * {@code audit.check.acknowledged}, and the open failure the inbox shows. The check itself runs in every edition
 * ({@link AuditCheckService}). Each runs in the caller's transaction, after its access check.
 */
@Component
class AuditCheckResults {

    private final AuditCheckStore checks;

    private final AuditToolsStore acknowledgements;

    private final AuditLog auditLog;

    private final UserDisplayNames users;

    private final TimeSource time;

    private final Licensing licensing;

    AuditCheckResults(AuditCheckStore checks, AuditToolsStore acknowledgements, AuditLog auditLog,
            UserDisplayNames users, TimeSource time, Licensing licensing) {
        this.checks = checks;
        this.acknowledgements = acknowledgements;
        this.auditLog = auditLog;
        this.users = users;
        this.time = time;
        this.licensing = licensing;
    }

    /** The checks of the chain (the instance's when null), newest first. */
    AuditCheckPage checks(@Nullable UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        licensing.require(Feature.AUDIT_TOOLS);
        return page(companyId, PageRequest.of(limit, cursor));
    }

    Optional<AuditCheck> openFailure(UUID companyId) {
        if (!licensing.has(Feature.AUDIT_TOOLS)) {
            // The daily check runs in every edition; its results are shown with the audit's tools (decision 4).
            return Optional.empty();
        }
        return checks.latest(companyId).filter(row -> !row.anchors()).map(row -> dto(row, Map.of()));
    }

    /** Acknowledges the chain's latest failed check (the instance's when null), in the caller's transaction. */
    AuditCheck acknowledge(@Nullable UUID companyId, UUID checkId, String note) {
        licensing.require(Feature.AUDIT_TOOLS);
        String text = note.strip();
        if (text.isEmpty() || text.length() > 1000) {
            throw ApiException.validation("note must be 1 to 1000 characters");
        }
        AuditCheckStore.CheckRow row = checks.find(companyId, checkId)
            .orElseThrow(() -> ApiException.notFound("Check not found"));
        if (row.ok()) {
            throw ApiException.validation("Only a failed check is acknowledged");
        }
        if (row.acknowledgedAt() != null) {
            return dto(row, names(List.of(row)));
        }
        AuditCheckStore.CheckRow latest = checks.latest(companyId).orElseThrow();
        if (!latest.id().equals(row.id())) {
            throw ApiException.validation("Only the chain's latest check is acknowledged; a later check exists");
        }
        UUID userId = CurrentActor.requireUser().id();
        if (acknowledgements.acknowledge(checkId, time.now(), userId, text)) {
            AuditRecord.Builder record = companyId != null
                    ? AuditRecord.company(companyId, AuditAction.AUDIT_CHECK_ACKNOWLEDGED, Actor.user(userId))
                    : AuditRecord.instance(AuditAction.AUDIT_CHECK_ACKNOWLEDGED, Actor.user(userId));
            auditLog.record(record.target("audit_check", checkId)
                .detail("checkId", checkId.toString())
                .detail("failure", row.failure())
                .build());
        }
        AuditCheckStore.CheckRow acknowledged = checks.find(companyId, checkId).orElseThrow();
        return dto(acknowledged, names(List.of(acknowledged)));
    }

    private AuditCheckPage page(@Nullable UUID companyId, PageRequest request) {
        Instant beforeAt = request.firstPage() ? null : request.instantKey(0, 2);
        UUID beforeId = request.firstPage() ? null : request.uuidKey(1, 2);
        List<AuditCheckStore.CheckRow> rows = checks.newestFirst(companyId, beforeAt, beforeId, request.fetchSize());
        Page<AuditCheckStore.CheckRow> page = Page.of(rows, request,
                row -> List.of(row.checkedAt().toString(), row.id().toString()));
        Map<UUID, String> names = names(page.items());
        return new AuditCheckPage(page.items().stream().map(row -> dto(row, names)).toList())
            .nextCursor(page.nextCursor());
    }

    private Map<UUID, String> names(List<AuditCheckStore.CheckRow> rows) {
        Set<UUID> ids = rows.stream()
            .map(AuditCheckStore.CheckRow::acknowledgedBy)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : users.displayNames(ids);
    }

    private static AuditCheck dto(AuditCheckStore.CheckRow row, Map<UUID, String> names) {
        AuditCheck dto = new AuditCheck(row.id(), row.checkedAt().atOffset(ZoneOffset.UTC), row.ok(), row.fromSeq(),
                row.headSeq(), row.headHash(), row.eventsChecked())
            .failure(row.failure() != null ? AuditCheck.FailureEnum.fromValue(row.failure()) : null)
            .firstInvalidSeq(row.firstInvalidSeq())
            .anchorCheckpoint(row.anchorCheckpoint());
        if (row.acknowledgedAt() != null) {
            dto.acknowledgedAt(row.acknowledgedAt().atOffset(ZoneOffset.UTC))
                .acknowledgedByUserId(row.acknowledgedBy())
                .acknowledgedBy(row.acknowledgedBy() != null
                        ? names.getOrDefault(row.acknowledgedBy(), AuditEventMapper.DELETED_USER_DISPLAY) : null)
                .acknowledgedNote(row.acknowledgedNote());
        }
        return dto;
    }
}
