package com.flowtivo.tinfirm.audit;

import java.util.Optional;
import java.util.UUID;

import com.flowtivo.tinfirm.shared.api.model.AuditCheck;

/**
 * The daily checks of a company's chain for other modules (the package 6 contract, §5.8, §5.11). Runs in the caller's
 * transaction and tenant context.
 */
public interface AuditChecks {

    /**
     * The chain's latest check when it failed and no OWNER acknowledged it: an item of the inbox of OWNER and ADMIN
     * until a check passes or the failure is acknowledged.
     */
    Optional<AuditCheck> openFailure(UUID companyId);
}
