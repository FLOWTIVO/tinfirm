package com.flowtivo.tinfirm.trigger;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.WebhookDeliveryPage;
import com.flowtivo.tinfirm.shared.api.model.WebhookMapped;

/**
 * A webhook system's deliveries and the try of its mapping (the package 5 contract, §5.12), for the Webhooks API and
 * the caller's AI client (the MCP endpoint, ADR-0045), with the API's rules: OWNER and ADMIN ({@code MANAGE_SYSTEMS},
 * else 403); a system they do not see is 404, one of another kind {@code 400 SYSTEM_KIND_INVALID}. Neither stores nor
 * fires anything.
 */
public interface WebhookReads {

    /** {@code listWebhookDeliveries}: the system's deliveries, newest first, a page; a body for its first 24 hours. */
    WebhookDeliveryPage deliveries(UUID companyId, UUID systemId, @Nullable Integer limit, @Nullable String cursor);

    /**
     * {@code tryWebhookMapping}: the body mapped as a delivery's, and each live webhook trigger of the system: whether
     * it matches, its state, and the first of its events or conditions the body fails.
     */
    WebhookMapped tryMapping(UUID companyId, UUID systemId, Map<String, Object> body);
}
