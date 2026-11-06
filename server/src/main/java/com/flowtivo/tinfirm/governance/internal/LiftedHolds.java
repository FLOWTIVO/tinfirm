package com.flowtivo.tinfirm.governance.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.retention.RetainedRecords;

/** A lifted legal hold is deleted 400 days after its lift (the package 6 contract, §3.7); the audit keeps its events. */
@Component
class LiftedHolds implements RetainedRecords {

    static final Duration KEPT = Duration.ofDays(400);

    private final RetentionStore store;

    LiftedHolds(RetentionStore store) {
        this.store = store;
    }

    @Override
    public String kind() {
        return "liftedHolds";
    }

    @Override
    public long sweep(UUID companyId, Instant now) {
        return store.deleteLifted(now.minus(KEPT));
    }
}
