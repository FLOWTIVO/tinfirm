package com.flowtivo.tinfirm.audit;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.AuditAnchorStatus;
import com.flowtivo.tinfirm.shared.api.model.AuditCheck;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckPage;
import com.flowtivo.tinfirm.shared.api.model.AuditEventPage;
import com.flowtivo.tinfirm.shared.api.model.AuditVerification;

/** Read access to the instance chain, its verification and its daily checks (instance admins only). */
public interface AuditTrail {

    AuditEventPage instanceEvents(@Nullable Integer limit, @Nullable String cursor);

    AuditVerification verifyInstanceChain();

    /** The daily checks of the instance chain, newest first (the package 6 contract, §5.8). */
    AuditCheckPage instanceChecks(@Nullable Integer limit, @Nullable String cursor);

    /** Acknowledges the instance chain's latest failed check, so that the next one starts from the head it found. */
    AuditCheck acknowledgeInstanceCheck(UUID checkId, String note);

    /** The anchor of the chains outside the database (ADR-0111), {@code 403 EDITION_REQUIRED} without it. */
    AuditAnchorStatus auditAnchorStatus();
}
