package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.retention.RetainedRecords;

/**
 * A webhook's deliveries are kept 7 days, their bodies 24 hours (the package 5 contract, §3.12): the daily retention
 * sweep (package 6) deletes the rows past their time and clears the bodies past theirs, and counts them as
 * {@code webhookDeliveries}. Meanwhile the admins' list shows no body older than 24 hours.
 */
@Component
class WebhookRetention implements RetainedRecords {

    static final Duration ROWS_KEPT = Duration.ofDays(7);

    static final Duration BODIES_KEPT = Duration.ofHours(24);

    private final WebhookStore store;

    WebhookRetention(WebhookStore store) {
        this.store = store;
    }

    @Override
    public String kind() {
        return "webhookDeliveries";
    }

    @Override
    public long sweep(UUID companyId, Instant now) {
        return store.sweep(companyId, now.minus(BODIES_KEPT), now.minus(ROWS_KEPT));
    }
}
