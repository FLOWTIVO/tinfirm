package com.flowtivo.tinfirm.trigger.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.system.WebhookLimits;

/**
 * {@link WebhookLimits}: the company's webhook deliveries a minute ({@code trigger_setting}, V019), its admins', else
 * the instance's ({@link WebhookProperties}). In the caller's transaction and tenant context.
 */
@Component
class WebhookLimitsImpl implements WebhookLimits {

    private final TriggerStore store;

    private final WebhookProperties properties;

    WebhookLimitsImpl(TriggerStore store, WebhookProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /** {@code [default, max]}. */
    int[] limits(UUID companyId) {
        return store.webhookLimits(companyId).orElse(instance());
    }

    /** The instance's {@code [default, max]}, for a company that set none. */
    int[] instance() {
        return new int[] {properties.deliveriesPerMinuteDefault(), properties.deliveriesPerMinuteMax()};
    }

    @Override
    public int defaultPerMinute(UUID companyId) {
        return limits(companyId)[0];
    }

    @Override
    public int maxPerMinute(UUID companyId) {
        return limits(companyId)[1];
    }
}
