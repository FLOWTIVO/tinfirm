package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditReads;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.TriggerPausedReason;
import com.flowtivo.tinfirm.shared.api.model.TriggerWaitingReason;
import com.flowtivo.tinfirm.shared.api.model.WebhookDelivery;
import com.flowtivo.tinfirm.shared.api.model.WebhookDeliveryCounts;
import com.flowtivo.tinfirm.shared.api.model.WebhookDeliveryPage;
import com.flowtivo.tinfirm.shared.api.model.WebhookMapped;
import com.flowtivo.tinfirm.shared.api.model.WebhookMappedTrigger;
import com.flowtivo.tinfirm.shared.api.model.WebhookMismatch;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.WebhookSystems;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.WebhookReads;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A webhook system's reads (the package 5 contract, §5.12, §6), for admins ({@code MANAGE_SYSTEMS}): a body tried
 * against its mapping and triggers without storing or firing, each trigger with the first of its tests the body fails
 * ({@link WebhookMapping#mismatch}, the receiver's own rule); and its deliveries, newest first. The Webhooks API and
 * the MCP endpoint call it ({@link WebhookReads}).
 */
@Service
class WebhookService implements WebhookReads {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CompanyAccessGuard guard;

    private final Systems systems;

    private final WebhookSystems webhookSystems;

    private final WebhookStore store;

    private final WebhookReceiver receiver;

    private final TriggerStore triggers;

    private final TimeSource time;

    private final AuditReads auditReads;

    WebhookService(CompanyAccessGuard guard, Systems systems, WebhookSystems webhookSystems, WebhookStore store,
            WebhookReceiver receiver, TriggerStore triggers, TimeSource time, AuditReads auditReads) {
        this.guard = guard;
        this.systems = systems;
        this.webhookSystems = webhookSystems;
        this.store = store;
        this.receiver = receiver;
        this.triggers = triggers;
        this.time = time;
        this.auditReads = auditReads;
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookMapped tryMapping(UUID companyId, UUID systemId, Map<String, Object> requestBody) {
        WebhookSystems.WebhookSystem system = webhook(companyId, systemId);
        JsonNode body = JSON.valueToTree(requestBody);
        WebhookMapped answer = new WebhookMapped(new ArrayList<>());
        WebhookMapping.Result result = WebhookMapping.map(system, body);
        if (result instanceof WebhookMapping.Result.Failed failed) {
            return answer.failure(failed.reason());
        }
        WebhookMapping.Mapped mapped = ((WebhookMapping.Result.Read) result).mapped();
        answer.event(mapped.event())
            .subjectKey(mapped.subjectKey())
            .subjectTitle(mapped.subjectTitle())
            .subjectUrl(mapped.subjectUrl())
            .actorId(mapped.actorId())
            .actorName(mapped.actorName())
            .text(mapped.text())
            .deliveryId(mapped.deliveryId());
        List<WebhookMappedTrigger> matched = new ArrayList<>();
        for (TriggerInfo trigger : receiver.webhookTriggers(companyId, systemId)) {
            WebhookMapping.Mismatch mismatch = WebhookMapping.mismatch(trigger,
                    triggers.webhookConditions(companyId, trigger.id()), mapped, body);
            matched.add(new WebhookMappedTrigger(trigger.id(), trigger.name(), mismatch == null)
                .status(com.flowtivo.tinfirm.shared.api.model.TriggerStatus.fromValue(trigger.status().name()))
                .pausedReason(trigger.pausedReason() != null
                        ? TriggerPausedReason.fromValue(trigger.pausedReason().name()) : null)
                .waitingReason(trigger.waitingReason() != null
                        ? TriggerWaitingReason.fromValue(trigger.waitingReason().name()) : null)
                .mismatch(mismatch != null ? view(mismatch) : null));
        }
        return answer.triggers(matched);
    }

    /** A mismatch as the API answers it: Tinfirm's sentence, and what the body holds apart from it. */
    static WebhookMismatch view(WebhookMapping.Mismatch mismatch) {
        return switch (mismatch) {
            case WebhookMapping.Mismatch.Event event -> new WebhookMismatch(WebhookMismatch.KindEnum.EVENT,
                    event.event() == null
                            ? "The mapping read no event, and the trigger takes only the events it names"
                            : "The mapped event is none of the events the trigger takes")
                .events(new ArrayList<>(event.events()))
                .event(event.event());
            case WebhookMapping.Mismatch.Condition condition -> new WebhookMismatch(
                    WebhookMismatch.KindEnum.CONDITION, "Condition " + (condition.index() + 1) + " does not hold: "
                            + switch (condition.condition().op()) {
                                case PRESENT -> "its pointer reads no value";
                                case ABSENT -> "its pointer reads a value";
                                case EQUALS -> condition.actual() == null ? "its pointer reads no text"
                                        : "the text its pointer reads is not its value";
                                case IN -> condition.actual() == null ? "its pointer reads no text"
                                        : "the text its pointer reads is none of its values";
                            })
                .condition(condition.index())
                .pointer(condition.condition().pointer())
                .op(WebhookMismatch.OpEnum.fromValue(condition.condition().op().name()))
                .values(new ArrayList<>(condition.condition().values()))
                .actual(condition.actual());
        };
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookDeliveryPage deliveries(UUID companyId, UUID systemId, @Nullable Integer limit,
            @Nullable String cursor) {
        webhook(companyId, systemId);
        PageRequest request = PageRequest.of(limit, cursor);
        List<WebhookStore.Delivery> rows = request.firstPage()
                ? store.page(companyId, systemId, null, null, request.fetchSize())
                : store.page(companyId, systemId, request.instantKey(0, 2), request.uuidKey(1, 2),
                        request.fetchSize());
        Page<WebhookStore.Delivery> page = Page.of(rows, request,
                row -> List.of(row.receivedAt().toString(), row.id().toString()));
        Instant now = time.now();
        Instant bodiesAfter = now.minus(WebhookRetention.BODIES_KEPT);
        WebhookDeliveryPage answer = new WebhookDeliveryPage(new ArrayList<>(page.items().stream()
            .map(row -> delivery(row, row.receivedAt().isAfter(bodiesAfter)))
            .toList())).nextCursor(page.nextCursor());
        if (request.firstPage()) {
            answer.lastSevenDays(lastSevenDays(companyId, systemId, now));
        }
        return answer;
    }

    /**
     * The system's deliveries over the last 7 days, the first page's only: rows are kept 7 days
     * ({@link WebhookRetention#ROWS_KEPT}), so the count is whole; the refusals, which store no row, from the audit.
     */
    private WebhookDeliveryCounts lastSevenDays(UUID companyId, UUID systemId, Instant now) {
        Instant since = now.minus(WebhookRetention.ROWS_KEPT);
        WebhookStore.Counts counts = store.counts(companyId, systemId, since);
        long refused = auditReads.webhookRefusals(companyId, systemId, since);
        return new WebhookDeliveryCounts(since.atOffset(ZoneOffset.UTC), counts.deliveries(), counts.failed(),
                (int) Math.min(Integer.MAX_VALUE, refused));
    }

    /** The company's webhook system, for an admin; 404 for none, {@code 400 SYSTEM_KIND_INVALID} for another kind. */
    WebhookSystems.WebhookSystem webhook(UUID companyId, UUID systemId) {
        guard.require(companyId, CompanyPermission.MANAGE_SYSTEMS);
        SystemInfo system = systems.find(companyId, systemId)
            .filter(found -> !found.personal())
            .orElseThrow(() -> ApiException.notFound("System not found"));
        if (system.kind() != SystemKind.WEBHOOK) {
            throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID, "Not a webhook system");
        }
        return webhookSystems.find(companyId, systemId).orElseThrow(() -> ApiException.notFound("System not found"));
    }

    @SuppressWarnings("unchecked")
    private static WebhookDelivery delivery(WebhookStore.Delivery row, boolean withBody) {
        WebhookDelivery delivery = new WebhookDelivery(row.id(), row.deliveryId(),
                row.receivedAt().atOffset(ZoneOffset.UTC), WebhookDelivery.StatusEnum.fromValue(row.status()),
                row.firings(), row.bodyBytes())
            .signedAt(row.signedAt() != null ? row.signedAt().atOffset(ZoneOffset.UTC) : null)
            .event(row.event())
            .subjectKey(row.subjectKey())
            .failure(row.failure());
        if (withBody && row.body() != null) {
            delivery.body(JSON.readValue(row.body(), Map.class));
        }
        return delivery;
    }
}
