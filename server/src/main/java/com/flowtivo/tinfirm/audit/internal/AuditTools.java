package com.flowtivo.tinfirm.audit.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

import com.flowtivo.tinfirm.audit.AuditTargetNames;
import com.flowtivo.tinfirm.shared.api.model.AuditCheck;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckPage;
import com.flowtivo.tinfirm.shared.api.model.AuditEventPage;
import com.flowtivo.tinfirm.shared.api.model.AuditVerification;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;

/**
 * The audit's tools (ADR-0077, decision 4, {@code AUDIT_TOOLS}): its filters, the verification of a chain on demand,
 * the export, and the daily checks' results. Paid features' code, under {@code ee/} (decision 1), which checks the
 * licence in force. The core finds it when used ({@link AuditService}, {@link AuditCheckService}) and never names its
 * implementation: without {@code ee/} the server answers as Community, {@code 403 EDITION_REQUIRED}, and no failed check
 * reaches the inbox. The daily check itself runs in every edition ({@link AuditCheckService}).
 * <p>
 * Each method runs in the caller's transaction and tenant context, after the caller's access check.
 */
interface AuditTools {

    /** The company's events that match the query, which is not {@link AuditQuery#ALL}, newest first. */
    AuditEventPage companyEvents(UUID companyId, @Nullable Integer limit, @Nullable String cursor, AuditQuery query,
            AuditTargetNames.Reader reader);

    /** Recomputes the chain (the instance's when null) and reports the first position that does not verify. */
    AuditVerification verify(@Nullable UUID companyId);

    /** The export of a contiguous range of the company's chain, resolved by the caller's user. */
    Export export(UUID companyId, @Nullable Long fromSeq, @Nullable Long toSeq, @Nullable Instant from,
            @Nullable Instant to);

    /** The daily checks of the chain (the instance's when null), newest first. */
    AuditCheckPage checks(@Nullable UUID companyId, @Nullable Integer limit, @Nullable String cursor);

    /** Acknowledges the chain's latest failed check, so that the next one starts from the head it found. */
    AuditCheck acknowledge(@Nullable UUID companyId, UUID checkId, String note);

    /** The chain's latest check when it failed and nobody acknowledged it; empty while the tools are not in force. */
    Optional<AuditCheck> openFailure(UUID companyId);

    /** An export, resolved in the caller's transaction: its response streams it outside any. */
    interface Export {

        ResponseEntity<String> response();
    }
}
