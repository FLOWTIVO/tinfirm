package com.flowtivo.tinfirm.trigger.internal;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.WebhooksApi;
import com.flowtivo.tinfirm.shared.api.model.WebhookDeliveryPage;
import com.flowtivo.tinfirm.shared.api.model.WebhookMapped;
import com.flowtivo.tinfirm.shared.api.model.WebhookRotateRequest;
import com.flowtivo.tinfirm.shared.api.model.WebhookSecret;
import com.flowtivo.tinfirm.system.WebhookSystems;

/**
 * A webhook system's own routes (the package 5 contract, §5.12, §6), for admins ({@code MANAGE_SYSTEMS}): its secret
 * rotated, a body tried against its mapping and triggers without storing or firing, and its deliveries, newest first
 * ({@link WebhookService}).
 */
@RestController
class WebhooksController implements WebhooksApi {

    private final WebhookService webhooks;

    private final WebhookSystems webhookSystems;

    private final WebhookInbox inbox;

    WebhooksController(WebhookService webhooks, WebhookSystems webhookSystems, WebhookInbox inbox) {
        this.webhooks = webhooks;
        this.webhookSystems = webhookSystems;
        this.inbox = inbox;
    }

    @Override
    @Transactional
    public ResponseEntity<WebhookSecret> rotateWebhookSecret(UUID companyId, UUID systemId,
            @Nullable WebhookRotateRequest request) {
        webhooks.webhook(companyId, systemId);
        String secret = webhookSystems.rotate(companyId, systemId,
                request != null && Boolean.TRUE.equals(request.getRevokePrevious()));
        // This replica reads the new secret at the next delivery; another one when the kept secrets refuse it.
        com.flowtivo.tinfirm.shared.tx.AfterCommit.run(() -> inbox.forget(systemId));
        WebhookSystems.WebhookSystem rotated = webhookSystems.find(companyId, systemId).orElseThrow();
        return ResponseEntity.ok(new WebhookSecret(secret).previousUntil(rotated.previousUntil() != null
                ? rotated.previousUntil().atOffset(ZoneOffset.UTC) : null));
    }

    @Override
    public ResponseEntity<WebhookMapped> tryWebhookMapping(UUID companyId, UUID systemId,
            Map<String, Object> requestBody) {
        return ResponseEntity.ok(webhooks.tryMapping(companyId, systemId, requestBody));
    }

    @Override
    public ResponseEntity<WebhookDeliveryPage> listWebhookDeliveries(UUID companyId, UUID systemId,
            @Nullable Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(webhooks.deliveries(companyId, systemId, limit, cursor));
    }
}
