package com.flowtivo.tinfirm.trigger;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.chat.ChatTestBench;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generic webhook (the package 5 contract, §3.12, §5.12, V18): a signed delivery fires its trigger and the result
 * stays in Tinfirm; the same delivery again does nothing; one 6 minutes old and one with a wrong signature store
 * nothing; the mapped actor is no member; the trigger's team writes in the conversation and starts a turn. The weaker
 * secret header, the rotation, the rates, the company's stop and the system's pause; echoes that end a chain.
 */
class WebhookTriggersTest extends ChatTestBench {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final Duration NONE_WITHIN = Duration.ofSeconds(2);

    @Test
    void aSignedDeliveryFiresItsTriggerWhoseResultStaysInTinfirm() throws SQLException {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            // The admin registers the sender: Tinfirm generates its secret, answered once.
            ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Desk"), "kind",
                    "WEBHOOK", "webhook", Map.of("scheme", "STANDARD", "mapping", Map.of("event", "/type",
                            "subjectKey", "/issue/key", "subjectTitle", "/issue/title", "subjectUrl", "/issue/url",
                            "actorId", "/user/id", "actorName", "/user/name", "text", "/comment"))));
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            UUID systemId = UUID.fromString(created.json().get("id").asString());
            String secret = created.json().get("webhook").get("secret").asString();
            assertThat(secret).startsWith("whsec_");
            assertThat(created.json().get("webhook").get("url").asString()).endsWith("/api/hooks/" + systemId);
            assertThat(created.json().get("webhook").get("perMinute").asInt()).isEqualTo(60);
            JsonNode read = bench.admin().get(bench.path("/systems/" + systemId)).json();
            assertThat(read.get("webhook").toString()).doesNotContain(secret);
            assertThat(bench.admin().post(bench.path("/systems"), Map.of("name", unique("Bad"), "kind", "WEBHOOK",
                    "webhook", Map.of("scheme", "STANDARD", "mapping", Map.of("event", "type")))).problemCode())
                .isEqualTo("MAPPING_INVALID");

            // The author's trigger: two mapped events, a condition on the body, the admin's team.
            UUID team = createGroup(bench.owner(), bench.company(), unique("Team"),
                    List.of(bench.company().userId("ADMIN")));
            JsonNode agent = agent(bench.member(), bench, "Desk agent");
            ApiResponse trigger = trigger(bench.member(), bench, systemId, agent, team,
                    List.of(Map.of("event", "DELIVERED", "value", "issue.created"),
                            Map.of("event", "DELIVERED", "value", "issue.commented")),
                    List.of(Map.of("pointer", "/issue/priority", "op", "IN", "values", List.of("high", "urgent"))));
            assertThat(trigger.status()).as("%s", trigger).isEqualTo(201);
            UUID triggerId = UUID.fromString(trigger.json().get("id").asString());
            assertThat(trigger.json().get("webhookConditions").get(0).get("op").asString()).isEqualTo("IN");

            // A signed delivery: a conversation of its own, held in the webhook's place.
            String first = body("issue.created", "DESK-1", "high", "The printer is on fire");
            Delivered delivered = standard(systemId, secret, "msg-1", Instant.now(), first);
            assertThat(delivered.status()).as("%s", delivered).isEqualTo(202);
            assertThat(delivered.json().get("firings").asInt()).isEqualTo(1);
            JsonNode assign = runner.expect("run.assign");
            String prompt = assign.get("prompt").asString();
            assertThat(prompt).contains("# Event", "DESK-1", "Printer", "A delivery of",
                    "Alice Sender (as the sender says, unverified)", "The printer is on fire",
                    "Answer the desk's deliveries.");
            Map<String, Object> conversation = rows(bench.id(), "SELECT id, place, origin, place_ref::text AS ref"
                    + " FROM conversation WHERE trigger_id = ?", triggerId).getFirst();
            assertThat(conversation).containsEntry("place", "WEBHOOK").containsEntry("origin", "TRIGGER");
            assertThat((String) conversation.get("ref")).contains("DESK-1", "msg-1");
            UUID conversationId = (UUID) conversation.get("id");
            // Its subject, from its place: the mapped key, title and link, on the webhook system. Who may write in it:
            // the team's ADMIN, as the POST lets them; OWNER, who sees it but is not one of its people, not.
            JsonNode page = bench.admin().get(bench.path("/conversations/" + conversationId)).json();
            assertThat(page.get("subject").get("key").asString()).isEqualTo("DESK-1");
            assertThat(page.get("subject").get("title").asString()).isEqualTo("Printer");
            assertThat(page.get("subject").get("url").asString()).isEqualTo("https://desk.example/issues/DESK-1");
            assertThat(page.get("subject").get("systemId").asString()).isEqualTo(systemId.toString());
            assertThat(page.get("mayPost").asBoolean()).isTrue();
            assertThat(page.get("mayAnswer").asBoolean()).isTrue();
            assertThat(bench.owner().get(bench.path("/conversations/" + conversationId)).json().get("mayPost")
                .asBoolean()).isFalse();
            assertThat(bench.admin().get(bench.path("/conversations?triggerId=" + triggerId)).json().get("items")
                .get(0).get("subject").get("key").asString()).isEqualTo("DESK-1");
            assertThat(rows(bench.id(), "SELECT kind, author_principal_id FROM message WHERE conversation_id = ?"
                    + " AND kind = 'EXTERNAL'", conversationId).getFirst()).containsEntry("author_principal_id", null);
            assertThat(rows(bench.id(), "SELECT event, value, outcome, actor_principal_id FROM trigger_firing"
                    + " WHERE trigger_id = ?", triggerId).getFirst()).containsEntry("event", "DELIVERED")
                .containsEntry("value", "issue.created").containsEntry("outcome", "STARTED")
                .containsEntry("actor_principal_id", null);
            // C13: a webhook's words are never a member's.
            try (TenantSql sql = TenantSql.in(bench.id())) {
                assertThat(sql.refused("INSERT INTO message (id, company_id, conversation_id, kind,"
                        + " author_principal_id, body, created_at, external_id, external_author) VALUES (?, ?, ?,"
                        + " 'EXTERNAL', ?, 'x', now(), 'forged', '{}'::jsonb)", UUID.randomUUID(), bench.id(),
                        conversationId, bench.memberPrincipal())).isEqualTo("message_place_ck");
            }

            // The same delivery again does nothing; a wrong signature and a stale one store nothing.
            Delivered again = standard(systemId, secret, "msg-1", Instant.now(), first);
            assertThat(again.status()).isEqualTo(200);
            assertThat(again.json().get("duplicate").asBoolean()).isTrue();
            Delivered forged = standard(systemId, "whsec_" + Base64.getEncoder().encodeToString(new byte[32]),
                    "msg-2", Instant.now(), first);
            assertThat(forged.status()).isEqualTo(401);
            assertThat(forged.body()).isEmpty();
            assertThat(standard(systemId, secret, "msg-3", Instant.now().minus(Duration.ofMinutes(6)), first)
                .status()).isEqualTo(401);
            assertThat(rows(bench.id(), "SELECT delivery_id FROM webhook_delivery WHERE system_id = ?", systemId))
                .hasSize(1);

            // A delivery the condition leaves out fires nothing.
            Delivered low = standard(systemId, secret, "msg-4", Instant.now(),
                    body("issue.created", "DESK-2", "low", "Not urgent"));
            assertThat(low.status()).isEqualTo(202);
            assertThat(low.json().get("firings").asInt()).isZero();

            // A comment on the subject during the run: the same conversation, its next turn.
            runner.accepted(UUID.fromString(assign.get("runId").asString()), assign.get("epoch").asLong());
            Delivered comment = standard(systemId, secret, "msg-5", Instant.now(),
                    body("issue.commented", "DESK-1", "high", "Still burning"));
            assertThat(comment.json().get("firings").asInt()).isEqualTo(1);
            runner.expectNone("run.assign", NONE_WITHIN);
            runner.send(finished(assign.get("runId").asString(), assign.get("epoch").asLong(), "Called the vendor."));
            JsonNode next = runner.expect("run.assign");
            assertThat(next.get("prompt").asString()).contains("Still burning");
            assertThat(rows(bench.id(), "SELECT id FROM conversation WHERE trigger_id = ?", triggerId)).hasSize(1);
            runner.accepted(UUID.fromString(next.get("runId").asString()), next.get("epoch").asLong());
            runner.send(finished(next.get("runId").asString(), next.get("epoch").asLong(), "Done."));

            // The result stays in Tinfirm, seen by the team, which writes in the conversation: a turn of its own.
            ApiResponse written = bench.admin().post(bench.path("/conversations/" + conversationId + "/messages"),
                    Map.of("body", "Also check the toner"));
            assertThat(written.status()).as("%s", written).isBetween(200, 201);
            JsonNode third = runner.expect("run.assign");
            assertThat(third.get("prompt").asString()).contains("Also check the toner");

            // The admins' list and the mapping's try.
            JsonNode deliveries = bench.admin().get(bench.path("/systems/" + systemId + "/webhook/deliveries")).json();
            assertThat(deliveries.get("items")).hasSize(3);
            assertThat(deliveries.get("items").get(0).get("deliveryId").asString()).isEqualTo("msg-5");
            assertThat(deliveries.get("items").get(0).get("status").asString()).isEqualTo("FIRED");
            assertThat(deliveries.get("items").get(1).get("status").asString()).isEqualTo("NO_TRIGGER");
            assertThat(deliveries.get("items").get(2).get("body").toString()).contains("The printer is on fire");
            assertThat(bench.member().get(bench.path("/systems/" + systemId + "/webhook/deliveries")).status())
                .isEqualTo(403);
            JsonNode mapped = bench.admin().post(bench.path("/systems/" + systemId + "/webhook/map"),
                    JSON.readValue(body("issue.created", "DESK-9", "urgent", "Try"), Map.class)).json();
            assertThat(mapped.get("subjectKey").asString()).isEqualTo("DESK-9");
            assertThat(mapped.get("triggers").get(0).get("matches").asBoolean()).isTrue();
            assertThat(rows(bench.id(), "SELECT delivery_id FROM webhook_delivery WHERE system_id = ?", systemId))
                .hasSize(3);
            runner.assertFramesValid();
        }
    }

    @Test
    void theSecretHeaderTheRotationTheRatesTheStopAndThePause() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            // The company's rates: a system above the maximum is refused.
            int version = bench.owner().get(bench.path("/settings")).json().get("version").asInt();
            ApiResponse settings = bench.owner().put(bench.path("/settings"), Map.of("triggerFiringsPerHour",
                    Map.of("default", 30, "max", 300), "webhookDeliveriesPerMinute", Map.of("default", 5, "max", 10),
                    "version", version));
            assertThat(settings.status()).as("%s", settings).isEqualTo(200);
            assertThat(bench.admin().post(bench.path("/systems"), Map.of("name", unique("Hook"), "kind", "WEBHOOK",
                    "webhook", Map.of("scheme", "SECRET_HEADER", "mapping", Map.of(), "maxPerMinute", 20)))
                .problemCode()).isEqualTo("WEBHOOK_LIMIT_TOO_HIGH");
            ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Hook"), "kind",
                    "WEBHOOK", "webhook", Map.of("scheme", "SECRET_HEADER", "secretHeader", "X-Hook-Secret",
                            "mapping", Map.of("deliveryId", "/id", "subjectKey", "/key"), "maxPerMinute", 3)));
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            UUID systemId = UUID.fromString(created.json().get("id").asString());
            String secret = created.json().get("webhook").get("secret").asString();
            JsonNode agent = agent(bench.member(), bench, "Hook agent");
            assertThat(trigger(bench.member(), bench, systemId, agent, null, List.of(), List.of()).status())
                .isEqualTo(201);

            // The header is the secret; the delivery id the mapped one.
            Delivered one = header(systemId, "X-Hook-Secret", secret, "{\"id\":\"d-1\",\"key\":\"K-1\"}");
            assertThat(one.status()).as("%s", one).isEqualTo(202);
            assertThat(one.json().get("deliveryId").asString()).isEqualTo("d-1");
            runner.expect("run.assign");
            // An id no random UUID or hash in the audit can contain, so that its absence proves something.
            assertThat(header(systemId, "X-Hook-Secret", secret + "x", "{\"id\":\"forged-delivery-q7x\"}").status())
                .isEqualTo(401);

            // A rotation: the previous secret is accepted for 24 hours, the new one at once.
            JsonNode rotated = bench.admin().post(bench.path("/systems/" + systemId + "/webhook/rotate"), null).json();
            String newSecret = rotated.get("secret").asString();
            assertThat(newSecret).isNotEqualTo(secret);
            assertThat(rotated.get("previousUntil").isNull()).isFalse();
            assertThat(header(systemId, "X-Hook-Secret", secret, "{\"id\":\"d-3\",\"key\":\"K-1\"}").status())
                .isEqualTo(202);
            // Past the system's 3 deliveries a minute: 429 with Retry-After, nothing stored.
            Delivered limited = header(systemId, "X-Hook-Secret", newSecret, "{\"id\":\"d-4\",\"key\":\"K-1\"}");
            assertThat(limited.status()).isEqualTo(202);
            Delivered over = header(systemId, "X-Hook-Secret", newSecret, "{\"id\":\"d-5\",\"key\":\"K-1\"}");
            assertThat(over.status()).isEqualTo(429);
            assertThat(over.retryAfter()).isNotNull();
            assertThat(rows(bench.id(), "SELECT delivery_id FROM webhook_delivery WHERE system_id = ?", systemId))
                .hasSize(3);
            assertThat(auditEvents(bench.owner(), bench.company(), "system.webhook_secret_rotated")).hasSize(1);
            assertThat(auditEvents(bench.owner(), bench.company(), "webhook.refused"))
                .anySatisfy(event -> assertThat(event.toString()).contains("SIGNATURE"));
            assertThat(auditEvents(bench.owner(), bench.company(), "webhook.refused").toString())
                .doesNotContain("forged-delivery-q7x");

            // Another sender's system: the company's stop answers 503, the system's pause 200, neither stored.
            ApiResponse other = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Hook"), "kind",
                    "WEBHOOK", "webhook", Map.of("scheme", "SECRET_HEADER", "mapping", Map.of("deliveryId", "/id"))));
            UUID otherId = UUID.fromString(other.json().get("id").asString());
            String otherSecret = other.json().get("webhook").get("secret").asString();
            assertThat(bench.owner().post(bench.path("/runs-stop"), Map.of("reason", "Incident")).status())
                .isEqualTo(200);
            Delivered stopped = header(otherId, WebhookHeader.DEFAULT, otherSecret, "{\"id\":\"s-1\"}");
            assertThat(stopped.status()).isEqualTo(503);
            assertThat(stopped.retryAfter()).isEqualTo("300");
            assertThat(bench.owner().delete(bench.path("/runs-stop")).status()).isEqualTo(200);
            assertThat(bench.owner().post(bench.path("/systems/" + otherId + "/pause"), Map.of("reason", "Noise",
                    "stopRunning", false)).status()).isEqualTo(200);
            Delivered paused = header(otherId, WebhookHeader.DEFAULT, otherSecret, "{\"id\":\"p-1\"}");
            assertThat(paused.status()).isEqualTo(200);
            assertThat(paused.json().get("paused").asBoolean()).isTrue();
            assertThat(rows(bench.id(), "SELECT delivery_id FROM webhook_delivery WHERE system_id = ?", otherId))
                .isEmpty();
            assertThat(bench.owner().post(bench.path("/systems/" + otherId + "/resume"), Map.of()).status())
                .isEqualTo(200);

            // A stranger's flood: past 60 refusals a minute, even the right secret waits.
            for (int i = 0; i < 61; i++) {
                assertThat(header(otherId, WebhookHeader.DEFAULT, "wrong", "{\"id\":\"f-" + i + "\"}").status())
                    .isEqualTo(401);
            }
            assertThat(header(otherId, WebhookHeader.DEFAULT, otherSecret, "{\"id\":\"f-ok\"}").status())
                .isEqualTo(429);
            // The wrong content type and a body too deep are refused before anything is stored.
            assertThat(send(systemId, "text/plain", Map.of(), "{}").status()).isEqualTo(415);
        }
    }

    @Test
    void anEchoBeyondTheChainStartsNothingAndTheWritingConversationIsTold() throws SQLException {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Ops"), "kind",
                    "WEBHOOK", "webhook", Map.of("scheme", "STANDARD", "mapping", Map.of("subjectKey", "/key",
                            "text", "/text"))));
            UUID systemId = UUID.fromString(created.json().get("id").asString());
            String secret = created.json().get("webhook").get("secret").asString();
            JsonNode agent = agent(bench.member(), bench, "Ops agent");
            UUID triggerId = UUID.fromString(trigger(bench.member(), bench, systemId, agent, null, List.of(),
                    List.of()).json().get("id").asString());
            // The editor warns of echoes, and a webhook trigger is open to anyone who can make the sender call.
            Map<String, Object> draft = new HashMap<>();
            draft.put("name", unique("Preview"));
            draft.put("source", "WEBHOOK");
            draft.put("systemId", systemId.toString());
            draft.put("agentId", agent.get("id").asString());
            draft.put("agentVersion", agent.get("version").asInt());
            draft.put("instruction", "Look.");
            draft.put("accounts", List.of());
            draft.put("payerPrincipalId", bench.memberPrincipal().toString());
            JsonNode preview = bench.member().post(bench.path("/triggers/preview"), Map.of("draft", withModel(draft)))
                .json();
            assertThat(preview.get("warnings").toString()).contains("echo");
            assertThat(preview.get("openToAnyone").get("who").asString()).contains("call its webhook");
            assertThat(bench.member().post(bench.path("/triggers/" + triggerId + "/run"), Map.of()).status())
                .isEqualTo(400);

            assertThat(standard(systemId, secret, "e-1", Instant.now(), "{\"key\":\"OPS-1\",\"text\":\"Down\"}")
                .status()).isEqualTo(202);
            JsonNode assign = runner.expect("run.assign");
            UUID conversationId = (UUID) rows(bench.id(), "SELECT id FROM conversation WHERE trigger_id = ?",
                    triggerId).getFirst().get("id");
            // The conversation wrote to OPS-1 now, at the end of a chain: its write comes back as a delivery.
            inCompany(bench.id(), "INSERT INTO system_write (id, company_id, system_id, subject_key, kind,"
                    + " conversation_id, written_at) VALUES (?, ?, ?, 'OPS-1', 'COMMENT', ?, now())", UUID.randomUUID(),
                    bench.id(), systemId, conversationId);
            inCompany(bench.id(), "UPDATE trigger_firing SET chain_depth = 3 WHERE trigger_id = ?", triggerId);
            Delivered echo = standard(systemId, secret, "e-2", Instant.now(), "{\"key\":\"OPS-1\",\"text\":\"Hi\"}");
            assertThat(echo.status()).isEqualTo(202);
            assertThat(echo.json().get("firings").asInt()).isZero();
            assertThat(rows(bench.id(), "SELECT outcome FROM trigger_firing WHERE trigger_id = ? ORDER BY created_at",
                    triggerId)).extracting(row -> row.get("outcome")).containsExactly("STARTED", "SKIPPED_CHAIN");
            assertThat(rows(bench.id(), "SELECT body FROM message WHERE conversation_id = ? AND kind = 'SYSTEM'",
                    conversationId)).anySatisfy(row -> assertThat((String) row.get("body")).contains("started nothing"));
            runner.accepted(UUID.fromString(assign.get("runId").asString()), assign.get("epoch").asLong());
            runner.expectNone("run.assign", NONE_WITHIN);
        }
    }

    /**
     * Review finding (package 5b): a verified, well-formed body that holds the JSON escape {@code \\u0000} passes the
     * parser (Jackson accepts it) but PostgreSQL's {@code jsonb} refuses it ({@code CAST(? AS jsonb)} in
     * {@code WebhookStore.insert}: "unsupported Unicode escape sequence"), so the inbox answers {@code 503} as if the
     * database failed, and a Standard Webhooks sender retries it for days. It should be refused as a bad body
     * ({@code 400}), stored nowhere, with the next delivery unaffected.
     */
    @Test
    void aVerifiedBodyWithANulEscapeIsRefusedAsABadBodyNotAsADatabaseFailure() {
        Bench bench = bench(false);
        ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Nul"), "kind",
                "WEBHOOK", "webhook", Map.of("scheme", "STANDARD", "mapping", Map.of("event", "/type",
                        "subjectKey", "/issue/key", "text", "/comment"))));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID systemId = UUID.fromString(created.json().get("id").asString());
        String secret = created.json().get("webhook").get("secret").asString();
        // The escape stays literal in the raw body: six characters, which Jackson reads as U+0000.
        String nul = "{\"type\":\"issue.created\",\"issue\":{\"key\":\"NUL-1\",\"title\":\"a\\u0000b\"},"
                + "\"comment\":\"fine\"}";
        Delivered delivered = standard(systemId, secret, "nul-1", Instant.now(), nul);
        assertThat(delivered.status()).as("%s", delivered).isNotEqualTo(503);
        assertThat(delivered.status()).as("%s", delivered).isEqualTo(400);
        assertThat(rows(bench.id(), "SELECT delivery_id FROM webhook_delivery WHERE system_id = ?", systemId))
            .isEmpty();
        Delivered next = standard(systemId, secret, "nul-2", Instant.now(), body("issue.created", "NUL-2", "high",
                "clean"));
        assertThat(next.status()).as("%s", next).isEqualTo(202);
    }

    /**
     * Review finding (package 5b, nit): a kept previous secret is refused from its own {@code previousUntil} on, not up
     * to 10 minutes later when the kept secrets are revealed again.
     */
    @Test
    void aKeptPreviousSecretIsRefusedFromItsOwnPreviousUntil() {
        Bench bench = bench(false);
        ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Kept"), "kind",
                "WEBHOOK", "webhook", Map.of("scheme", "SECRET_HEADER", "mapping", Map.of("deliveryId", "/id"))));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID systemId = UUID.fromString(created.json().get("id").asString());
        String secret = created.json().get("webhook").get("secret").asString();
        String newSecret = bench.admin().post(bench.path("/systems/" + systemId + "/webhook/rotate"), null).json()
            .get("secret").asString();
        // The new secret reveals both and keeps them; the previous one counts.
        assertThat(header(systemId, WebhookHeader.DEFAULT, newSecret, "{\"id\":\"k-1\"}").status()).isEqualTo(202);
        assertThat(header(systemId, WebhookHeader.DEFAULT, secret, "{\"id\":\"k-2\"}").status()).isEqualTo(202);
        // Its 24 hours are over: refused at once, though the kept secrets still hold it.
        inCompany(bench.id(), "UPDATE external_system SET webhook_previous_until = now() - interval '1 second'"
                + " WHERE id = ?", systemId);
        assertThat(header(systemId, WebhookHeader.DEFAULT, secret, "{\"id\":\"k-3\"}").status()).isEqualTo(401);
        assertThat(header(systemId, WebhookHeader.DEFAULT, newSecret, "{\"id\":\"k-4\"}").status()).isEqualTo(202);
    }

    /**
     * The system page's count of deliveries: the deliveries' first page counts the last 7 days, all the time deliveries
     * are kept: those received, those the mapping could not read, and those refused, from the audit's
     * {@code webhook.refused}; the next pages do not.
     */
    @Test
    void theFirstPageOfDeliveriesCountsTheLastSevenDays() {
        Bench bench = bench(false);
        ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Counted"), "kind",
                "WEBHOOK", "webhook", Map.of("scheme", "SECRET_HEADER", "mapping", Map.of("deliveryId", "/id"))));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID systemId = UUID.fromString(created.json().get("id").asString());
        String secret = created.json().get("webhook").get("secret").asString();
        String deliveries = bench.path("/systems/" + systemId + "/webhook/deliveries");
        JsonNode none = bench.admin().get(deliveries).json().get("lastSevenDays");
        assertThat(none.get("deliveries").asInt()).isZero();
        assertThat(none.get("failed").asInt()).isZero();
        assertThat(none.get("refused").asInt()).isZero();

        for (String id : List.of("c-1", "c-2", "c-3")) {
            assertThat(header(systemId, WebhookHeader.DEFAULT, secret, "{\"id\":\"" + id + "\"}").status())
                .isEqualTo(202);
        }
        // The same delivery again is one delivery.
        assertThat(header(systemId, WebhookHeader.DEFAULT, secret, "{\"id\":\"c-1\"}").status()).isEqualTo(200);
        // Two refused: the first recorded at once, the second with the next record a minute on.
        assertThat(header(systemId, WebhookHeader.DEFAULT, "whsec_wrong", "{\"id\":\"r-1\"}").status())
            .isEqualTo(401);
        assertThat(header(systemId, WebhookHeader.DEFAULT, "whsec_wrong", "{\"id\":\"r-2\"}").status())
            .isEqualTo(401);
        // One the mapping could not read, and one past the 7 days that the daily sweep has not deleted yet.
        inCompany(bench.id(), "INSERT INTO webhook_delivery (id, company_id, system_id, delivery_id, received_at,"
                + " status, failure, body_bytes) VALUES (?, ?, ?, 'c-failed', now() - interval '6 days',"
                + " 'MAPPING_FAILED', 'The pointer /id is not text', 2)", UUID.randomUUID(), bench.id(), systemId);
        inCompany(bench.id(), "INSERT INTO webhook_delivery (id, company_id, system_id, delivery_id, received_at,"
                + " status, body_bytes) VALUES (?, ?, ?, 'c-old', now() - interval '7 days 1 hour', 'NO_TRIGGER',"
                + " 2)", UUID.randomUUID(), bench.id(), systemId);

        JsonNode first = bench.admin().get(deliveries + "?limit=2").json();
        JsonNode week = first.get("lastSevenDays");
        assertThat(week.get("deliveries").asInt()).as("%s", week).isEqualTo(4);
        assertThat(week.get("failed").asInt()).isEqualTo(1);
        assertThat(week.get("refused").asInt()).isEqualTo(1);
        assertThat(Instant.parse(week.get("since").asString())).isBetween(Instant.now().minus(Duration.ofDays(7))
            .minusSeconds(60), Instant.now().minus(Duration.ofDays(7)));
        // The next page counts nothing again; a member reads no deliveries at all.
        JsonNode next = bench.admin().get(deliveries + "?limit=2&cursor=" + first.get("nextCursor").asString())
            .json();
        assertThat(next.get("items")).isNotEmpty();
        assertThat(next.has("lastSevenDays")).isFalse();
        assertThat(bench.member().get(deliveries).status()).isEqualTo(403);
    }

    /**
     * Review finding (package 5b): a client address's flood is answered {@code 429} before the system is looked up
     * (the contract's step 2), keyed by the path's system id: a flooded address learns nothing of the system, not
     * even that it was deleted since.
     */
    @Test
    void aFloodedAddressIsRefusedBeforeTheSystemIsLookedUp() {
        Bench bench = bench(false);
        ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Flood"), "kind",
                "WEBHOOK", "webhook", Map.of("scheme", "SECRET_HEADER", "mapping", Map.of())));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID systemId = UUID.fromString(created.json().get("id").asString());
        Delivered last = null;
        for (int i = 0; i <= 61 && (last == null || last.status() == 401); i++) {
            last = header(systemId, WebhookHeader.DEFAULT, "whsec_forged", "{}");
        }
        assertThat(last).isNotNull();
        assertThat(last.status()).as("%s", last).isEqualTo(429);
        assertThat(bench.owner().delete(bench.path("/systems/" + systemId)).status()).isIn(200, 204);
        Delivered gone = header(systemId, WebhookHeader.DEFAULT, "whsec_forged", "{}");
        assertThat(gone.status()).as("%s", gone).isEqualTo(429);
        assertThat(gone.retryAfter()).isEqualTo("60");
    }

    /**
     * Review finding (package 5b, nit c): each webhook system's secret is named after its handle
     * ({@code WEBHOOK_SECRET_{HANDLE}}, and {@code _2} while a rotated one is kept), so that a company with many webhook
     * systems rotates them all; one shared {@code WEBHOOK_SECRET} ran out of its 99 names past about 50 systems.
     */
    @Test
    void aCompanyWithManyWebhookSystemsRotatesThemAll() {
        Bench bench = bench(false);
        List<UUID> systems = new java.util.ArrayList<>();
        List<String> handles = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Many " + i), "kind",
                    "WEBHOOK", "webhook", Map.of("scheme", "SECRET_HEADER", "mapping", Map.of())));
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            systems.add(UUID.fromString(created.json().get("id").asString()));
            handles.add(created.json().get("handle").asString());
        }
        for (int round = 0; round < 2; round++) {
            for (UUID systemId : systems) {
                ApiResponse rotated = bench.admin().post(bench.path("/systems/" + systemId + "/webhook/rotate"), null);
                assertThat(rotated.status()).as("%s", rotated).isEqualTo(200);
            }
        }
        // Each system holds its current and its previous secret, under its own name and that name's _2.
        List<Object> names = rows(bench.id(), "SELECT name FROM secret WHERE name LIKE 'WEBHOOK_SECRET%'").stream()
            .map(row -> row.get("name"))
            .toList();
        assertThat(names).hasSize(120);
        for (String handle : handles) {
            String name = "WEBHOOK_SECRET_" + handle.toUpperCase(java.util.Locale.ROOT);
            assertThat(names).contains(name, name + "_2");
        }
        // The last rotation's secret is the one that counts.
        UUID last = systems.getLast();
        String secret = bench.admin().post(bench.path("/systems/" + last + "/webhook/rotate"), null).json()
            .get("secret").asString();
        assertThat(header(last, WebhookHeader.DEFAULT, secret, "{}").status()).isEqualTo(202);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** The default header of {@code SECRET_HEADER}. */
    private static final class WebhookHeader {

        static final String DEFAULT = "X-Tinfirm-Secret";
    }

    private record Delivered(int status, String body, @Nullable String retryAfter) {

        JsonNode json() {
            return JSON.readTree(body);
        }
    }

    private static String body(String type, String key, String priority, String comment) {
        return JSON.writeValueAsString(Map.of("type", type, "issue", Map.of("key", key, "title", "Printer",
                "priority", priority, "url", "https://desk.example/issues/" + key), "user", Map.of("id", "u-1", "name",
                        "Alice Sender"), "comment", comment));
    }

    /** A Standard Webhooks delivery, signed with the secret. */
    private Delivered standard(UUID systemId, String secret, String id, Instant at, String body) {
        String timestamp = Long.toString(at.getEpochSecond());
        return send(systemId, "application/json", Map.of("webhook-id", id, "webhook-timestamp", timestamp,
                "webhook-signature", "v1," + sign(secret, id, timestamp, body)), body);
    }

    private Delivered header(UUID systemId, String header, String secret, String body) {
        return send(systemId, "application/json", Map.of(header, secret), body);
    }

    private Delivered send(UUID systemId, String contentType, Map<String, String> headers, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/hooks/"
                + systemId))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Delivered(response.statusCode(), response.body(),
                    response.headers().firstValue("Retry-After").orElse(null));
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** Standard Webhooks' signature: the base64 HMAC-SHA256 of {@code {id}.{timestamp}.{body}}, keyed with the secret. */
    static String sign(String secret, String id, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring("whsec_".length())),
                    "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((id + "." + timestamp + "." + body)
                .getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode agent(ApiClient creator, Bench bench, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique(name));
        body.put("runtimes", List.of("CLAUDE_CODE", "CODEX"));
        body.put("instructions", "Answer briefly.");
        body.put("systems", List.of());
        body.put("repositories", List.of());
        body.put("shareGroupIds", List.of());
        ApiResponse agent = creator.post(bench.path("/agents"), body);
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return agent.json();
    }

    private ApiResponse trigger(ApiClient author, Bench bench, UUID systemId, JsonNode agent, @Nullable UUID team,
            List<Map<String, Object>> events, List<Map<String, Object>> conditions) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Desk trigger"));
        body.put("source", "WEBHOOK");
        body.put("systemId", systemId.toString());
        body.put("events", events);
        body.put("webhookConditions", conditions);
        body.put("agentId", agent.get("id").asString());
        body.put("agentVersion", agent.get("version").asInt());
        body.put("instruction", "Answer the desk's deliveries.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.memberPrincipal().toString());
        body.put("acknowledgeOpenEvents", true);
        if (team != null) {
            body.put("teamGroupId", team.toString());
        }
        return author.post(bench.path("/triggers"), withModel(body));
    }

    private static Map<String, Object> finished(String runId, long epoch, String summary) {
        return Map.of("type", "run.finished", "runId", runId, "epoch", epoch, "outcome", "SUCCEEDED", "lastSeq", 0,
                "summary", summary, "sessionId", "session-" + runId, "git", List.of(), "result", Map.of("outcome",
                        "COMPLETED", "summary", summary, "nextSteps", List.of(), "openQuestions", List.of()));
    }
}
