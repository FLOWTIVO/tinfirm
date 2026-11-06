package com.flowtivo.tinfirm.model;

import java.util.UUID;

/**
 * The model module's domain events, ids only, published in the transaction that made the change (listen after the
 * commit with {@code @TransactionalEventListener}), but {@link RelayRequestCounted}, which the relay publishes
 * outside any transaction.
 */
public final class ModelEvents {

    private ModelEvents() {
    }

    /**
     * A principal's key changed status ({@code OK}, {@code BUDGET_SPENT}, {@code BLOCKED}, {@code ERROR}): package 3
     * resumes the principal's waiting turns when it becomes {@code OK}.
     */
    public record ModelKeyStatusChanged(UUID companyId, UUID principalId, String status) {
    }

    /** The company's model settings changed: their gateway, its test, or the defaults. */
    public record ModelSettingsChanged(UUID companyId, boolean relayEnabled) {
    }

    /**
     * The relay counted a request of the run ({@code ModelRelay.usage}), whatever its answer was. Published outside
     * any transaction after the relay counted it, once per request, so that a run whose last answer was still under
     * way when the run ended (its end saw no request yet) is still settled (CONVENTIONS §15.12). Listen with
     * {@code @EventListener}, in a transaction of your own.
     */
    public record RelayRequestCounted(UUID companyId, UUID runId) {
    }
}
