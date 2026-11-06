package com.flowtivo.tinfirm.governance.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.LicenceStatus;
import com.flowtivo.tinfirm.shared.licence.Licensing;

/**
 * The retention periods in force (ADR-0077, decisions 7 and 8): what the sweep purges, what it forecasts and what the
 * inbox counts, in the company's tenant context.
 * <ul>
 * <li>Pro and Enterprise ({@code RETENTION_SETTINGS}): the OWNER's periods, or today's defaults until they set
 * theirs, the starting values once a paid key first comes into force.</li>
 * <li>A licence that expired: the periods it left, frozen, so that no data is kept past the agreed periods; they
 * cannot be changed.</li>
 * <li>Community, never licensed: everything kept, but the memory notes' own period; the operational purges of
 * {@code RetainedRecords} stay in every edition.</li>
 * </ul>
 */
@Component
class RetentionPolicy {

    /** How the periods in force came about. */
    enum Mode {
        PERIODS, FROZEN, KEEP_EVERYTHING
    }

    private final RetentionStore store;

    private final Licensing licensing;

    RetentionPolicy(RetentionStore store, Licensing licensing) {
        this.store = store;
        this.licensing = licensing;
    }

    Mode mode() {
        LicenceStatus status = licensing.status();
        if (status.has(Feature.RETENTION_SETTINGS)) {
            return Mode.PERIODS;
        }
        return status.expired() && status.lapsedEdition() != null ? Mode.FROZEN : Mode.KEEP_EVERYTHING;
    }

    Periods inForce(UUID companyId) {
        Periods stored = store.periods(companyId);
        return mode() == Mode.KEEP_EVERYTHING ? Periods.keepEverything(stored.memoryDays()) : stored;
    }
}
