package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.WebhookSystems;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSource;

import tools.jackson.databind.JsonNode;

/**
 * A verified webhook delivery, in one transaction of its company (the package 5 contract, §5.12): stored once
 * ({@code webhook_delivery}, {@code ON CONFLICT (system_id, delivery_id) DO NOTHING}: a known id does nothing more),
 * then mapped and fired: each live {@code WEBHOOK} trigger of the system, in the order of their ids (the lock order),
 * whose events and conditions match it ({@link WebhookFirings}). The delivery records what it did: {@code FIRED} with
 * the firings that started or continued a conversation, {@code NO_TRIGGER} when none did (none matched, or those that
 * matched were paused, waited, or skipped), {@code MAPPING_FAILED} with the reason.
 */
@Component
class WebhookReceiver {

    private final WebhookStore store;

    private final TriggerStore triggers;

    private final WebhookFirings firings;

    private final SystemTransactions transactions;

    WebhookReceiver(WebhookStore store, TriggerStore triggers, WebhookFirings firings,
            SystemTransactions transactions) {
        this.store = store;
        this.triggers = triggers;
        this.firings = firings;
        this.transactions = transactions;
    }

    /** What became of a delivery. */
    sealed interface Received {

        /** Its id is known for the system already: nothing more was done (C3). */
        record Duplicate() implements Received {
        }

        /** Stored, mapped and fired: how many triggers took it. */
        record Accepted(UUID id, int firings) implements Received {
        }
    }

    /**
     * Stores, maps and fires the delivery.
     *
     * @param mapping    what the mapping read from the body, or why it could not
     * @param deliveryId the delivery's id: {@code webhook-id}, else the mapped one, else the SHA-256 of the body
     * @param signedAt   a {@code STANDARD} delivery's {@code webhook-timestamp}
     */
    Received receive(WebhookSystems.WebhookSystem system, WebhookMapping.Result mapping, String deliveryId,
            @Nullable Instant signedAt, Instant receivedAt, String raw, int bytes, JsonNode body) {
        UUID companyId = system.companyId();
        return transactions.inCompany(companyId, () -> {
            UUID id = store.insert(Ids.newId(), companyId, system.id(), deliveryId, signedAt, receivedAt, raw, bytes)
                .orElse(null);
            if (id == null) {
                return new Received.Duplicate();
            }
            if (mapping instanceof WebhookMapping.Result.Failed failed) {
                store.finish(companyId, id, "MAPPING_FAILED", null, null, 0, failed.reason());
                return new Received.Accepted(id, 0);
            }
            WebhookMapping.Mapped mapped = ((WebhookMapping.Result.Read) mapping).mapped();
            WebhookFirings.Delivery delivery = new WebhookFirings.Delivery(id, deliveryId, system, receivedAt, mapped,
                    raw);
            int took = 0;
            for (TriggerInfo trigger : webhookTriggers(companyId, system.id())) {
                List<WebhookCondition> conditions = triggers.webhookConditions(companyId, trigger.id());
                if (WebhookMapping.matches(trigger, conditions, mapped, body)
                        && firings.fire(trigger, delivery).took()) {
                    took++;
                }
            }
            store.finish(companyId, id, took > 0 ? "FIRED" : "NO_TRIGGER", mapped.event(), mapped.subjectKey(), took,
                    null);
            return new Received.Accepted(id, took);
        });
    }

    /** The system's live webhook triggers, in the order of their ids. */
    List<TriggerInfo> webhookTriggers(UUID companyId, UUID systemId) {
        return triggers.liveOnSystem(companyId, systemId).stream()
            .filter(trigger -> trigger.source() == TriggerSource.WEBHOOK && systemId.equals(trigger.systemId()))
            .toList();
    }
}
