package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.model.internal.KeyReconciliation;
import com.flowtivo.tinfirm.model.internal.KeyRotation;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rotation of principals' LiteLLM keys against the fake LiteLLM (security review row 2 of the platform redesign;
 * CONVENTIONS §15.4): a key as old as the rotation interval is replaced by a new one with the same team, budget,
 * period, models, metadata and spend, the relay switches to it, and the old one serves in-flight requests for the
 * grace, is then blocked, and is deleted a minute later with its late spend moved over; an admin rotates one key, or
 * every key of the company, at once, the old key blocked before the answer; a crash between any two steps leaves the
 * principal a working key and is finished by the reconciliation, with no second key left live. The audit never holds a
 * key. Time passes by moving the row's times back, as the clock would.
 */
@ExtendWith(EnterpriseLicence.class)
class ModelKeyRotationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String MESSAGES = "/api/runner/v1/model/anthropic/v1/messages";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    private KeyReconciliation reconciliation;

    @Autowired
    private KeyRotation rotation;

    @Autowired
    private ModelRelay relay;

    @Autowired
    private SystemTransactions transactions;

    @AfterEach
    void resetModelHandler() {
        litellm.resetModelHandler();
    }

    /** A company whose bundled gateway passed its test, with a service identity whose key is OK. */
    private record Setup(TestCompany company, ApiClient owner, UUID bot, FakeLiteLlm.Key key) {
    }

    /**
     * The service identity exists before the gateway, so that the reconciliation after the gateway's test makes its
     * key. That reconciliation runs after the commit, off the test's thread, and ends with its sweep, which lists the
     * team's keys: once the fake saw that, no key work of the company runs in the background any more, and every step
     * that follows is the test's own. (A rotation's wakes do not fire during a test either: the test profile's settling
     * time is an hour, the grace ten minutes, and the tests move the row's times back instead.)
     */
    private Setup setUp() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        ModelGateways.configureBundled(owner, company);
        await().atMost(ModelGateways.WAIT)
            .until(() -> !litellm.requests("/key/list?team_id=tinfirm-" + company.id()).isEmpty());
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, bot);
        ModelGateways.awaitStatus(owner, company, bot, "OK");
        return new Setup(company, owner, bot, key);
    }

    @Test
    void aKeyRotatesOnScheduleWithItsBudgetAndSpendAndTheOldOneRetiresAfterTheGrace() throws SQLException {
        Setup setup = setUp();
        TestCompany company = setup.company();
        FakeLiteLlm.Key first = setup.key();
        first.spend = 7.5;
        reconciliation.reconcile(company.id());

        // The key's age and its next rotation; the settings name the interval and the grace.
        JsonNode fresh = access(setup);
        assertThat(fresh.get("spentUsd").asDouble()).isEqualTo(7.5);
        Instant created = instant(fresh, "keyCreatedAt");
        assertThat(created).isCloseTo(Instant.now(), within(Duration.ofMinutes(1)));
        assertThat(fresh.get("keyAgeDays").asInt()).isZero();
        assertThat(instant(fresh, "nextKeyRotationAt")).isEqualTo(created.plus(Duration.ofDays(30)));
        assertThat(isNull(fresh, "keyRotatedAt")).isTrue();
        assertThat(isNull(fresh, "previousKeyValidUntil")).isTrue();
        JsonNode settings = setup.owner().get(company.path("/model-settings")).json();
        assertThat(settings.get("keyRotationInterval").asString()).isEqualTo("P30D");
        assertThat(settings.get("keyRotationGrace").asString()).isEqualTo("PT10M");

        // Thirty-one days on, the reconciliation rotates the key.
        sql(company, "UPDATE principal_model_key SET key_created_at = now() - interval '31 days' WHERE principal_id = ?",
                setup.bot());
        assertThat(access(setup).get("keyAgeDays").asInt()).isEqualTo(31);
        reconciliation.reconcile(company.id());
        FakeLiteLlm.Key second = litellm.keyOfAlias(ModelGateways.alias(company, setup.bot(), 1)).orElseThrow();
        assertThat(second.token).isNotEqualTo(first.token);
        assertThat(second.teamId).isEqualTo(first.teamId);
        assertThat(second.maxBudget).isEqualTo(25.0);
        assertThat(second.budgetDuration).isEqualTo("30d");
        assertThat(second.models).containsExactlyElementsOf(ModelGateways.DEFAULT_MODELS);
        assertThat(second.metadata).isEqualTo(first.metadata);
        assertThat(second.blocked).isFalse();
        // The spend goes on, and so does the period: LiteLLM resets a 30-day budget on the 1st of the month.
        assertThat(second.spend).isEqualTo(7.5);
        assertThat(second.resetAt).isEqualTo(first.resetAt);
        // The old key serves the requests that hold it for the grace.
        assertThat(first.blocked).isFalse();
        assertThat(callWith(first.key)).isEqualTo(200);
        assertThat(callWith(second.key)).isEqualTo(200);
        Map<String, Object> row = row(company, setup.bot());
        assertThat(row.get("litellm_key_id")).isEqualTo(second.token);
        assertThat(row.get("retiring_key_id")).isEqualTo(first.token);
        assertThat(row.get("key_generation")).isEqualTo(1);
        assertThat(secrets(company, setup.bot())).isEqualTo(1);

        JsonNode rotated = access(setup);
        assertThat(rotated.get("keyAgeDays").asInt()).isZero();
        assertThat(instant(rotated, "keyRotatedAt")).isEqualTo(instant(rotated, "keyCreatedAt"));
        assertThat(instant(rotated, "previousKeyValidUntil"))
            .isCloseTo(Instant.now().plus(Duration.ofMinutes(10)), within(Duration.ofMinutes(1)));
        assertThat(rotated.get("spentUsd").asDouble()).isEqualTo(7.5);
        assertThat(rotated.get("keyStatus").asString()).isEqualTo("OK");
        List<JsonNode> rotations = auditEvents(setup.owner(), company, "model_key.rotated");
        assertThat(rotations).hasSize(1);
        assertThat(rotations.getFirst().get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(rotations.getFirst().get("details").get("cause").asString()).isEqualTo("schedule");
        assertThat(rotations.getFirst().get("details").get("graceSeconds").asInt()).isEqualTo(600);
        // Before the grace ends, nothing more happens.
        reconciliation.reconcile(company.id());
        assertThat(first.blocked).isFalse();

        // A stream that held the old key finishes in the grace and spends; then the grace ends, and the old key is
        // refused.
        first.spend = 8.25;
        passTime(company, setup.bot());
        reconciliation.reconcile(company.id());
        assertThat(first.blocked).isTrue();
        assertThat(callWith(first.key)).isEqualTo(401);
        assertThat(callWith(second.key)).isEqualTo(200);
        assertThat(auditEvents(setup.owner(), company, "model_key.retired")).hasSize(1);
        assertThat(litellm.key(first.token)).isPresent();

        // A minute later, once LiteLLM wrote its spend, the old key's late spend goes over and the old key goes.
        passTime(company, setup.bot());
        reconciliation.reconcile(company.id());
        assertThat(litellm.key(first.token)).isEmpty();
        assertThat(second.spend).isEqualTo(8.25);
        assertThat(ModelGateways.keysOf(litellm, company, setup.bot())).containsExactly(second);
        assertThat(row(company, setup.bot()).get("retiring_key_id")).isNull();
        JsonNode retired = access(setup);
        assertThat(isNull(retired, "previousKeyValidUntil")).isTrue();
        assertThat(retired.get("spentUsd").asDouble()).isEqualTo(8.25);
        assertThat(retired.get("keyStatus").asString()).isEqualTo("OK");
        // Again, nothing changes.
        reconciliation.reconcile(company.id());
        assertThat(second.spend).isEqualTo(8.25);
        assertThat(ModelGateways.keysOf(litellm, company, setup.bot())).containsExactly(second);
        assertThat(auditEvents(setup.owner(), company, "model_key.rotated")).hasSize(1);

        assertNoKeyInAudit(setup.owner(), company, first, second);
        assertThat(setup.owner().get(company.path("/audit-events/verification")).json().get("valid").asBoolean())
            .isTrue();
    }

    /**
     * Rotation now, on suspicion: the old key is blocked before the answer, a relayed stream that already holds it
     * finishes, and the next relayed request goes with the new key.
     */
    @Test
    void anAdminRotatesAKeyAtOnceAndARelayedRequestDuringTheRotationSucceeds() throws Exception {
        Setup setup = setUp();
        TestCompany company = setup.company();
        ApiClient admin = loginAs(company.email("ADMIN"));
        FakeLiteLlm.Key first = setup.key();
        first.spend = 2.0;
        UUID hostId = registerRunnerHost(setup.owner(), company.id(), unique("host"), "COMPANY").id();
        UUID runId = UUID.randomUUID();
        TestRuns.running(company.id(), runId, 1, hostId, setup.bot());
        String token = transactions.inCompany(company.id(), () -> relay.open(new ModelRelay.RelayedRun(company.id(),
                runId, 1, hostId, setup.bot(), UUID.randomUUID(), UUID.randomUUID()),
                RunnerMessages.ModelAccessFormat.ANTHROPIC)).apiKey();

        CountDownLatch streaming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        litellm.onModel((exchange, request, key) -> {
            if (!request.json().path("stream").asBoolean(false)) {
                FakeLiteLlm.json(exchange, 200, JSON.createObjectNode().put("alias", key.alias));
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("data: {\"part\":1,\"alias\":\"" + key.alias + "\"}\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                streaming.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IOException("The test never released the stream");
                }
                out.write("data: {\"part\":2}\n\n".getBytes(StandardCharsets.UTF_8));
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        CompletableFuture<HttpResponse<String>> inFlight = http.sendAsync(relayed(token,
                message("claude-sonnet-4-5").replace("{", "{\"stream\":true,")), HttpResponse.BodyHandlers.ofString());
        assertThat(streaming.await(20, TimeUnit.SECONDS)).isTrue();

        // A member may not; an unknown principal is 404.
        ApiClient member = loginAs(company.email("MEMBER"));
        assertThat(member.post(rotatePath(company, setup.bot()), null).status()).isEqualTo(403);
        assertThat(admin.post(rotatePath(company, UUID.randomUUID()), null).status()).isEqualTo(404);

        ApiResponse rotated = admin.post(rotatePath(company, setup.bot()), null);
        assertThat(rotated.status()).as("%s", rotated).isEqualTo(200);
        JsonNode view = rotated.json();
        assertThat(isNull(view, "keyRotatedAt")).isFalse();
        assertThat(isNull(view, "keyRotationRequestedAt")).isTrue();
        assertThat(instant(view, "previousKeyValidUntil")).isBeforeOrEqualTo(Instant.now());
        assertThat(view.get("keyStatus").asString()).isEqualTo("OK");
        // The old key is refused at once; the new one has its budget and spend.
        assertThat(first.blocked).isTrue();
        assertThat(callWith(first.key)).isEqualTo(401);
        FakeLiteLlm.Key second = litellm.keyOfAlias(ModelGateways.alias(company, setup.bot(), 1)).orElseThrow();
        assertThat(second.blocked).isFalse();
        assertThat(second.spend).isEqualTo(2.0);
        assertThat(second.maxBudget).isEqualTo(25.0);

        // The stream that held the old key finishes; the next request goes with the new key.
        release.countDown();
        HttpResponse<String> streamed = inFlight.get(20, TimeUnit.SECONDS);
        assertThat(streamed.statusCode()).isEqualTo(200);
        assertThat(streamed.body()).contains("\"alias\":\"" + first.alias + "\"").contains("\"part\":2");
        HttpResponse<String> next = http.send(relayed(token, message("claude-sonnet-4-5")),
                HttpResponse.BodyHandlers.ofString());
        assertThat(next.statusCode()).as(next.body()).isEqualTo(200);
        assertThat(JSON.readTree(next.body()).get("alias").asString()).isEqualTo(second.alias);
        assertThat(litellm.requests("/v1/messages").getLast().header("authorization"))
            .isEqualTo("Bearer " + second.key);
        ModelGateways.awaitStatus(setup.owner(), company, setup.bot(), "OK");
        assertThat(auditEvents(setup.owner(), company, "model_key.refused")).isEmpty();

        // A minute later the old key goes.
        first.spend = 2.5;
        passTime(company, setup.bot());
        reconciliation.reconcile(company.id());
        assertThat(litellm.key(first.token)).isEmpty();
        assertThat(second.spend).isEqualTo(2.5);

        List<JsonNode> requested = auditEvents(setup.owner(), company, "model_key.rotation_requested");
        assertThat(requested).hasSize(1);
        assertThat(requested.getFirst().get("actor").get("id").asString())
            .isEqualTo(company.userId("ADMIN").toString());
        assertThat(requested.getFirst().get("details").get("scope").asString()).isEqualTo("principal");
        assertThat(requested.getFirst().get("details").get("principalId").asString())
            .isEqualTo(setup.bot().toString());
        JsonNode cause = auditEvents(setup.owner(), company, "model_key.rotated").getFirst().get("details");
        assertThat(cause.get("cause").asString()).isEqualTo("request");
        assertThat(cause.get("graceSeconds").asInt()).isZero();
        assertThat(auditEvents(setup.owner(), company, "model_key.retired")).hasSize(1);
        assertNoKeyInAudit(setup.owner(), company, first, second);
    }

    /**
     * After a suspected leak of the vault or of LiteLLM: every key of the company is rotated, a suspended principal's
     * new key blocked as its old one was, and every old key refused at once, the previous key of an earlier rotation
     * still in its grace included.
     */
    @Test
    void rotatingEveryKeyOfTheCompanyRefusesEveryOldKeyAtOnce() throws SQLException {
        Setup setup = setUp();
        TestCompany company = setup.company();
        ApiClient owner = setup.owner();
        UUID suspended = createServiceIdentity(owner, company, unique("suspended"));
        ModelGateways.awaitStatus(owner, company, suspended, "OK");
        assertThat(owner.post(company.path("/service-identities/" + suspended + "/suspend"), null).status())
            .isEqualTo(200);
        ModelGateways.awaitStatus(owner, company, suspended, "BLOCKED");
        // The bot's key rotated on schedule a moment ago: its previous key is in its grace.
        FakeLiteLlm.Key previous = setup.key();
        sql(company, "UPDATE principal_model_key SET key_created_at = now() - interval '31 days' WHERE principal_id = ?",
                setup.bot());
        reconciliation.reconcile(company.id());
        FakeLiteLlm.Key current = litellm.keyOfAlias(ModelGateways.alias(company, setup.bot(), 1)).orElseThrow();
        assertThat(previous.blocked).isFalse();
        List<UUID> principals = List.of(memberPrincipal(company, company.userId("OWNER")),
                memberPrincipal(company, company.userId("ADMIN")), memberPrincipal(company, company.userId("MEMBER")),
                setup.bot(), suspended);
        Map<UUID, FakeLiteLlm.Key> before = new HashMap<>();
        for (UUID principal : principals) {
            ModelGateways.awaitStatus(owner, company, principal, principal.equals(suspended) ? "BLOCKED" : "OK");
            before.put(principal, litellm.key((String) row(company, principal).get("litellm_key_id")).orElseThrow());
        }

        ApiClient member = loginAs(company.email("MEMBER"));
        assertThat(member.post(company.path("/model-settings/rotate-keys"), null).status()).isEqualTo(403);
        ApiResponse requested = owner.post(company.path("/model-settings/rotate-keys"), null);
        assertThat(requested.status()).as("%s", requested).isEqualTo(202);
        // Every principal's key, and the company's own (ADR-0065).
        assertThat(requested.json().get("requested").asInt()).isEqualTo(principals.size() + 1);

        for (UUID principal : principals) {
            await().atMost(ModelGateways.WAIT).untilAsserted(() -> {
                JsonNode access = setup.owner().get(company.path("/principals/" + principal + "/model-access"))
                    .json();
                assertThat(isNull(access, "keyRotationRequestedAt")).as("%s", access).isTrue();
                assertThat(before.get(principal).blocked).isTrue();
            });
            FakeLiteLlm.Key old = before.get(principal);
            assertThat(callWith(old.key)).isEqualTo(401);
            FakeLiteLlm.Key now = litellm.key((String) row(company, principal).get("litellm_key_id")).orElseThrow();
            assertThat(now.token).isNotEqualTo(old.token);
            assertThat(now.blocked).isEqualTo(principal.equals(suspended));
        }
        // The bot's earlier previous key did not wait for its grace: it is gone, and its successor is blocked.
        assertThat(litellm.key(previous.token)).isEmpty();
        assertThat(current.blocked).isTrue();
        assertThat(litellm.keyOfAlias(ModelGateways.alias(company, setup.bot(), 2)).orElseThrow().blocked).isFalse();
        ModelGateways.awaitStatus(owner, company, suspended, "BLOCKED");
        ModelGateways.awaitStatus(owner, company, setup.bot(), "OK");

        List<JsonNode> audits = auditEvents(owner, company, "model_key.rotation_requested");
        assertThat(audits).hasSize(principals.size() + 1);
        assertThat(audits).allSatisfy(event -> assertThat(event.get("details").get("scope").asString())
            .isEqualTo("company"));
        assertThat(auditEvents(owner, company, "model_key.rotated")).hasSize(principals.size() + 2);
        ModelGateways.awaitCompanyKey(owner, company, "OK");
        assertThat(litellm.keyOfAlias(ModelGateways.companyKeyAlias(company, 1))).isPresent();
        List<FakeLiteLlm.Key> all = new ArrayList<>(litellm.keysOfTeam("tinfirm-" + company.id()));
        all.add(previous);
        assertNoKeyInAudit(owner, company, all.toArray(FakeLiteLlm.Key[]::new));
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void aGatewayThatPassedNoTestRotatesNothing() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        assertThat(owner.post(company.path("/model-settings/rotate-keys"), null).problemCode())
            .isEqualTo("GATEWAY_NOT_CONFIGURED");
        assertThat(owner.post(rotatePath(company, member), null).problemCode()).isEqualTo("GATEWAY_NOT_CONFIGURED");
    }

    @Test
    void aRotationLiteLlmFailsStaysRequestedAndTheReconciliationCarriesItOut() throws SQLException {
        Setup setup = setUp();
        TestCompany company = setup.company();
        litellm.failNext("/key/generate", 500, 1);
        ApiResponse failed = setup.owner().post(rotatePath(company, setup.bot()), null);
        assertThat(failed.status()).as("%s", failed).isEqualTo(502);
        assertThat(failed.problemCode()).isEqualTo("GATEWAY_UNREACHABLE");
        // The current key keeps working, and the request stays, with LiteLLM's reason.
        assertThat(setup.key().blocked).isFalse();
        assertThat(callWith(setup.key().key)).isEqualTo(200);
        JsonNode pending = access(setup);
        assertThat(isNull(pending, "keyRotationRequestedAt")).isFalse();
        assertThat(pending.get("keyRotationError").asString()).contains("500");
        assertThat(pending.get("keyStatus").asString()).isEqualTo("OK");

        reconciliation.reconcile(company.id());
        JsonNode done = access(setup);
        assertThat(isNull(done, "keyRotationRequestedAt")).isTrue();
        assertThat(isNull(done, "keyRotationError")).isTrue();
        assertThat(setup.key().blocked).isTrue();
        assertThat(litellm.keyOfAlias(ModelGateways.alias(company, setup.bot(), 1))).isPresent();
    }

    /**
     * A crash after any step of a rotation (simulated: nothing after the step runs, and the row's lease stays held
     * until it expires) leaves the principal a working key, and the reconciliation finishes the rotation: one live key
     * in the end, the spend carried over (the old key's late spend counted once, or twice should the crash fall
     * between moving it and deleting the old key, never not at all), one secret, each step audited once.
     */
    @ParameterizedTest
    @EnumSource(KeyRotation.Step.class)
    void aCrashBetweenTwoStepsIsFinishedByTheReconciliation(KeyRotation.Step step) throws SQLException {
        Setup setup = setUp();
        TestCompany company = setup.company();
        UUID bot = setup.bot();
        FakeLiteLlm.Key first = setup.key();
        first.spend = 3.0;
        reconciliation.reconcile(company.id());
        sql(company, "UPDATE principal_model_key SET key_created_at = now() - interval '31 days' WHERE principal_id = ?",
                bot);
        boolean beforeTheSwitch = step == KeyRotation.Step.GENERATED || step == KeyRotation.Step.SWITCHED;
        if (!beforeTheSwitch) {
            // The rotation itself went through, and a stream that held the old key spent in its grace; the crash comes
            // while the old key retires.
            reconciliation.reconcile(company.id());
            first.spend = 3.5;
            passTime(company, bot);
            if (step != KeyRotation.Step.BLOCKED) {
                reconciliation.reconcile(company.id());
                assertThat(first.blocked).isTrue();
                passTime(company, bot);
            }
        }

        rotation.crashAfter(bot, step);
        assertThatThrownBy(() -> reconciliation.reconcile(company.id()))
            .isInstanceOf(KeyRotation.SimulatedCrash.class);

        // Whatever the step, the principal has a working key: the row's current one.
        FakeLiteLlm.Key current = litellm.key((String) row(company, bot).get("litellm_key_id")).orElseThrow();
        assertThat(current.blocked).isFalse();
        assertThat(callWith(current.key)).isEqualTo(200);
        if (step == KeyRotation.Step.GENERATED) {
            // The next key LiteLLM made is nobody's yet.
            assertThat(current.token).isEqualTo(first.token);
            assertThat(litellm.keyOfAlias(ModelGateways.alias(company, bot, 1))).isPresent();
        }
        // While the lease holds, nothing moves.
        reconciliation.reconcile(company.id());
        assertThat(row(company, bot).get("litellm_key_id")).isEqualTo(current.token);

        // The lease expires; the reconciliation finishes what the crash left, as time passes.
        for (int round = 0; round < 4 && row(company, bot).get("retiring_key_id") != null
                || round == 0; round++) {
            passTime(company, bot);
            reconciliation.reconcile(company.id());
        }
        Map<String, Object> row = row(company, bot);
        assertThat(row.get("retiring_key_id")).isNull();
        assertThat(row.get("key_generation")).isEqualTo(1);
        // No run keeps the lease: the crashed run's expired, and every run since let go of its own. (Awaited, not read
        // once: a sync of the key may hold it for a moment, which is no leak; a leaked one would hold it two minutes.)
        await().atMost(ModelGateways.WAIT).until(() -> row(company, bot).get("sync_lease_until") == null);
        FakeLiteLlm.Key last = litellm.key((String) row.get("litellm_key_id")).orElseThrow();
        assertThat(last.alias).isEqualTo(ModelGateways.alias(company, bot, 1));
        assertThat(ModelGateways.keysOf(litellm, company, bot)).containsExactly(last);
        assertThat(last.blocked).isFalse();
        assertThat(callWith(last.key)).isEqualTo(200);
        assertThat(callWith(first.key)).isEqualTo(401);
        double expected = beforeTheSwitch ? 3.0 : 3.5;
        if (step == KeyRotation.Step.TRANSFERRED) {
            assertThat(last.spend).isBetween(expected, expected + 0.5);
        }
        else {
            assertThat(last.spend).isEqualTo(expected);
        }
        assertThat(secrets(company, bot)).isEqualTo(1);
        assertThat(auditEvents(setup.owner(), company, "model_key.rotated")).hasSize(1);
        assertThat(auditEvents(setup.owner(), company, "model_key.retired")).hasSize(1);
        ModelGateways.awaitStatus(setup.owner(), company, bot, "OK");
        assertNoKeyInAudit(setup.owner(), company, first, last);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private JsonNode access(Setup setup) {
        ApiResponse response = setup.owner().get(setup.company().path("/principals/" + setup.bot() + "/model-access"));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }

    private static String rotatePath(TestCompany company, UUID principal) {
        return company.path("/principals/" + principal + "/model-access/rotate-key");
    }

    private static boolean isNull(JsonNode node, String field) {
        return !node.has(field) || node.get(field).isNull();
    }

    private static Instant instant(JsonNode node, String field) {
        return OffsetDateTime.parse(node.get(field).asString()).toInstant();
    }

    private static String message(String model) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
    }

    private HttpRequest relayed(String token, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + MESSAGES))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    /** A model request straight to the fake LiteLLM with the key, as a runner host never sends one: its status. */
    private int callWith(String key) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(litellm.url() + "/v1/messages"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(message("claude-sonnet-4-5")))
            .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> row(TestCompany company, UUID principal) throws SQLException {
        try (TenantSql sql = TenantSql.in(company.id())) {
            return sql.rows("SELECT * FROM principal_model_key WHERE principal_id = ?", principal).getFirst();
        }
    }

    /** The internal secrets of the principal's key in the vault. */
    private static long secrets(TestCompany company, UUID principal) throws SQLException {
        try (TenantSql sql = TenantSql.in(company.id())) {
            return sql.count("SELECT count(*) FROM secret WHERE internal AND name LIKE ?",
                    "MODEL_KEY_" + principal.toString().replace("-", "") + "%");
        }
    }

    private static void sql(TestCompany company, String statement, Object... params) throws SQLException {
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update(statement, params);
            sql.commit();
        }
    }

    /**
     * Time passes for the principal's rotation, as the clock would move it, and nothing else: the old key's grace and
     * settling time (an hour in the test profile) are over, and a lease a crashed run left has expired. The test then
     * reconciles; no wake of the server's runs the step on its own.
     */
    private static void passTime(TestCompany company, UUID principal) throws SQLException {
        sql(company, """
                UPDATE principal_model_key
                   SET sync_lease_until = NULL,
                       retiring_until = CASE WHEN retiring_until IS NULL THEN NULL
                                             ELSE now() - interval '1 second' END,
                       retiring_blocked_at = CASE WHEN retiring_blocked_at IS NULL THEN NULL
                                                  ELSE now() - interval '1 day' END
                 WHERE principal_id = ?""", principal);
    }

    /** No audit event of the company holds a key, or LiteLLM's id of one. */
    private void assertNoKeyInAudit(ApiClient reader, TestCompany company, FakeLiteLlm.Key... keys) {
        String audit = auditEvents(reader, company).toString();
        for (FakeLiteLlm.Key key : keys) {
            assertThat(audit).doesNotContain(key.key).doesNotContain(key.token);
        }
        assertThat(audit).doesNotContainPattern("sk-[A-Za-z0-9_-]{16,}");
    }
}
