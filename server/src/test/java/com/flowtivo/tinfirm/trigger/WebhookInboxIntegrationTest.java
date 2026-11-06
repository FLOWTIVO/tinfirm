package com.flowtivo.tinfirm.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.chat.ChatTestBench;
import com.flowtivo.tinfirm.support.ApiResponse;

import tools.jackson.databind.JsonNode;

/**
 * The webhook inbox's address limiter and the rotation's "revoke now" (the security review of 2026-10-27, L6 and L8):
 * the path's system id is parsed first and the limiter keyed by its canonical form, so a refused address that spells
 * the id in upper case is flooded as under the canonical one; anything that is not a UUID is {@code 404} before
 * anything is counted; a rotation with {@code revokePrevious} refuses the secret it replaced at once. TEST FIXTURES
 * ONLY: every secret here is a test value.
 */
class WebhookInboxIntegrationTest extends ChatTestBench {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private int deliver(String systemId, String secret) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/hooks/" + systemId))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-Hook-Secret", secret)
            .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + UUID.randomUUID() + "\"}"))
            .build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private record Hook(UUID id, String secret) {
    }

    private Hook hook(Bench bench) {
        ApiResponse created = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Hook"), "kind",
                "WEBHOOK", "webhook", Map.of("scheme", "SECRET_HEADER", "secretHeader", "X-Hook-Secret", "mapping",
                        Map.of("deliveryId", "/id"))));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        return new Hook(UUID.fromString(created.json().get("id").asString()),
                created.json().get("webhook").get("secret").asString());
    }

    @Test
    void anUpperCaseIdIsFloodedAsTheCanonicalOne() {
        Bench bench = bench(false);
        Hook hook = hook(bench);
        String upper = hook.id().toString().toUpperCase(Locale.ROOT);

        // Refused deliveries under the upper-case spelling count for the system, and flood it under every spelling.
        int status = 0;
        for (int i = 0; i < 70 && status != 429; i++) {
            status = deliver(upper, "test-wrong-secret");
        }
        assertThat(status).isEqualTo(429);
        assertThat(deliver(hook.id().toString(), hook.secret())).isEqualTo(429);
        assertThat(deliver(upper, hook.secret())).isEqualTo(429);
        assertThat(auditEvents(bench.owner(), bench.company(), "webhook.refused"))
            .anySatisfy(event -> assertThat(event.toString()).contains("SOURCE_RATE"));

        // Not a UUID, or a short form of one: no system, nothing counted.
        assertThat(deliver("not-a-uuid", "x")).isEqualTo(404);
        assertThat(deliver("1-2-3-4-5", "x")).isEqualTo(404);
    }

    @Test
    void aRotationThatRevokesThePreviousSecretRefusesItAtOnce() {
        Bench bench = bench(false);
        Hook hook = hook(bench);
        assertThat(deliver(hook.id().toString(), hook.secret())).isEqualTo(202);

        JsonNode rotated = bench.admin().post(bench.path("/systems/" + hook.id() + "/webhook/rotate"),
                Map.of("revokePrevious", true)).json();
        String newSecret = rotated.get("secret").asString();
        // Null, which the API leaves out.
        assertThat(rotated.path("previousUntil").isMissingNode() || rotated.path("previousUntil").isNull()).isTrue();

        assertThat(deliver(hook.id().toString(), hook.secret())).isEqualTo(401);
        assertThat(deliver(hook.id().toString(), newSecret)).isEqualTo(202);
        assertThat(auditEvents(bench.owner(), bench.company(), "system.webhook_secret_rotated")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("revokedPrevious").asBoolean()).isTrue());

        // Without the option the rotation keeps the replaced secret for 24 hours.
        JsonNode kept = bench.admin().post(bench.path("/systems/" + hook.id() + "/webhook/rotate"), null).json();
        assertThat(kept.get("previousUntil").isNull()).isFalse();
        assertThat(deliver(hook.id().toString(), newSecret)).isEqualTo(202);
    }
}
