package com.flowtivo.tinfirm.trigger.internal;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.chat.ChatTestBench;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.system.WebhookSystems;

import tools.jackson.databind.JsonNode;

/**
 * Review finding (package 5b): the inbox checks the company's stop and the system's pause in short transactions of
 * their own; a stop or a pause committed after those checks reaches the trigger's gate in the delivery's transaction
 * ({@code COMPANY_STOPPED}, {@code SYSTEM_PAUSED}). The delivery must not be stored as {@code NO_TRIGGER} and answered
 * {@code 202}, which loses it: {@link WebhookFirings.Held} rolls the delivery back, and the inbox answers as its own
 * checks would have ({@code 503} with {@code Retry-After}, or {@code 200 {"paused": true}}). The receiver is called
 * here as the inbox calls it after its checks passed.
 */
class WebhookStopRaceTest extends ChatTestBench {

    @Autowired
    private WebhookReceiver receiver;

    @Autowired
    private WebhookSystems webhookSystems;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void aStopOrAPauseCommittedAfterTheInboxsChecksStoresNothingAndIsAnsweredAsTheChecksWould() {
        Bench bench = bench(true);
        ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Race"), "kind",
                "WEBHOOK", "webhook", Map.of("scheme", "SECRET_HEADER", "mapping", Map.of("deliveryId", "/id",
                        "subjectKey", "/key"))));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID systemId = UUID.fromString(created.json().get("id").asString());
        String[] agent = agent(bench.member(), bench, "Race agent", List.of());
        JsonNode agentJson = bench.member().get(bench.path("/agents/" + agent[0])).json();
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Race trigger"));
        body.put("source", "WEBHOOK");
        body.put("systemId", systemId.toString());
        body.put("events", List.of());
        body.put("webhookConditions", List.of());
        body.put("agentId", agent[0]);
        body.put("agentVersion", agentJson.get("version").asInt());
        body.put("instruction", "Answer the deliveries.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.memberPrincipal().toString());
        body.put("acknowledgeOpenEvents", true);
        ApiResponse trigger = bench.member().post(bench.path("/triggers"), withModel(body));
        assertThat(trigger.status()).as("%s", trigger).isEqualTo(201);

        // The company's stop, committed after the inbox's check: held as stopped, nothing stored.
        assertThat(bench.owner().post(bench.path("/runs-stop"), Map.of("reason", "Incident")).status())
            .isEqualTo(200);
        WebhookFirings.Held stopped = catchThrowableOfType(WebhookFirings.Held.class,
                () -> receive(bench.id(), systemId, "{\"id\":\"r-1\",\"key\":\"R-1\"}"));
        assertThat(stopped).isNotNull();
        assertThat(stopped.stopped()).isTrue();
        assertThat(deliveries(bench.id(), systemId)).isEmpty();
        assertThat(bench.owner().delete(bench.path("/runs-stop")).status()).isEqualTo(200);

        // The system's pause, committed after the inbox read the system: held as paused, nothing stored.
        assertThat(bench.owner().post(bench.path("/systems/" + systemId + "/pause"), Map.of("reason", "Noise",
                "stopRunning", false)).status()).isEqualTo(200);
        WebhookFirings.Held paused = catchThrowableOfType(WebhookFirings.Held.class,
                () -> receive(bench.id(), systemId, "{\"id\":\"r-2\",\"key\":\"R-1\"}"));
        assertThat(paused).isNotNull();
        assertThat(paused.stopped()).isFalse();
        assertThat(deliveries(bench.id(), systemId)).isEmpty();
        assertThat(bench.owner().post(bench.path("/systems/" + systemId + "/resume"), Map.of()).status())
            .isEqualTo(200);

        // Resumed: the same delivery is stored and fired.
        WebhookReceiver.Received received = receive(bench.id(), systemId, "{\"id\":\"r-3\",\"key\":\"R-1\"}");
        assertThat(received).isInstanceOf(WebhookReceiver.Received.Accepted.class);
        assertThat(((WebhookReceiver.Received.Accepted) received).firings()).isEqualTo(1);
        assertThat(deliveries(bench.id(), systemId)).containsExactly("r-3");
    }

    /** The receiver, as the inbox calls it once its checks passed. */
    private WebhookReceiver.Received receive(UUID companyId, UUID systemId, String raw) {
        // The system as the inbox read it before the stop or the pause.
        WebhookSystems.WebhookSystem read = transactions.inCompany(companyId,
                () -> webhookSystems.find(companyId, systemId).orElseThrow());
        WebhookSystems.WebhookSystem system = new WebhookSystems.WebhookSystem(read.id(), read.companyId(),
                read.name(), read.handle(), false, read.scheme(), read.secretHeader(), read.mapping(),
                read.maxPerMinute(), read.secretSetAt(), read.previousUntil());
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        JsonNode body = WebhookInbox.parse(bytes);
        assertThat(body).isNotNull();
        WebhookMapping.Result mapping = WebhookMapping.map(system, body);
        String deliveryId = WebhookInbox.deliveryId(system, null, null, mapping, bytes);
        return receiver.receive(system, mapping, deliveryId, null, Instant.now(), raw, bytes.length, body);
    }

    private static List<Object> deliveries(UUID companyId, UUID systemId) {
        return rows(companyId, "SELECT delivery_id FROM webhook_delivery WHERE system_id = ?", systemId).stream()
            .map(row -> row.get("delivery_id"))
            .toList();
    }
}
