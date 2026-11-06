package com.flowtivo.tinfirm.governance.internal;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.api.model.RetentionPeriods;
import com.flowtivo.tinfirm.shared.api.model.RetentionPreview;

/**
 * The OWNER's retention periods, Pro's and Enterprise's (ADR-0077, decisions 4, 7 and 8, {@code RETENTION_SETTINGS}):
 * their change and its preview. A paid feature's code, under {@code ee/} (decision 1), which checks the licence in
 * force. The core finds it when used ({@link RetentionService}) and never names its implementation: without
 * {@code ee/} the server answers as Community, {@code 403 EDITION_REQUIRED}. The periods in force
 * ({@link RetentionPolicy}) are the core's in every edition.
 * <p>
 * Each method runs in the caller's transaction and tenant context, after its access check ({@code MANAGE_RETENTION}).
 */
interface RetentionChanges {

    /** Stores the periods, checked, and audits a change. */
    void update(UUID companyId, RetentionPeriods request);

    /**
     * What the next sweep would purge if it ran now, under the proposed periods and under the stored ones, and what
     * the open holds keep from it under the proposed ones.
     */
    RetentionPreview preview(UUID companyId, RetentionPeriods request);
}
