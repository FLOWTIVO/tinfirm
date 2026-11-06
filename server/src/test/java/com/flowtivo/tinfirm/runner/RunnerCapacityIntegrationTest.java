package com.flowtivo.tinfirm.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * Runner capacity settings (ADR-0016, docs/archive/v1/CONVENTIONS.md §14.8, §15.7): the desired value of a host over HTTP,
 * its audit, and how it reaches the runner host and comes back confirmed. The pools of the credentials a host reports
 * are not set from Tinfirm: a personal host's credentials keep the pool of its hello.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@ExtendWith(EnterpriseLicence.class)
class RunnerCapacityIntegrationTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Test
    void theHostOwnerOrAnAdminSetsTheDesiredSlotsAndBadValuesAreRefused() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient operator = loginAs(company.email("MEMBER"));
        // A company host, and the member's own personal host (the package 2 contract, §8).
        RunnerHostRef ownersHost = registerRunnerHost(owner, company.id(), unique("owner-host"), "COMPANY");
        RunnerHostRef operatorsHost = registerRunnerHost(operator, company.id(), unique("member-host"));
        String ownersPath = company.path("/runner-hosts/" + ownersHost.id());
        String operatorsPath = company.path("/runner-hosts/" + operatorsHost.id());

        // A new host: ceiling 1 until it connects, no desired value, nothing pending.
        JsonNode fresh = host(operator, company, operatorsHost.id());
        assertThat(fresh.get("maxConcurrentRuns").asInt()).isEqualTo(1);
        assertThat(fresh.get("maxConcurrentRunsCeiling").asInt()).isEqualTo(1);
        assertThat(fresh.has("desiredMaxConcurrentRuns")).isFalse();
        assertThat(fresh.get("configPending").asBoolean()).isFalse();
        assertThat(fresh.get("draining").asBoolean()).isFalse();

        assertThat(operator.patch(ownersPath, Map.of("desiredMaxConcurrentRuns", 2)).status()).isEqualTo(403);
        assertThat(operator.patch(company.path("/runner-hosts/" + UUID.randomUUID()),
                Map.of("desiredMaxConcurrentRuns", 2)).status()).isEqualTo(404);

        ApiResponse own = operator.patch(operatorsPath, Map.of("desiredMaxConcurrentRuns", 3));
        assertThat(own.status()).as("%s", own).isEqualTo(200);
        assertThat(own.json().get("desiredMaxConcurrentRuns").asInt()).isEqualTo(3);
        // The offline host confirms the change when it connects: until then the last confirmed value stays.
        assertThat(own.json().get("configPending").asBoolean()).isTrue();
        assertThat(own.json().get("maxConcurrentRuns").asInt()).isEqualTo(1);
        // Another member's personal host is not there for an admin either; a company host is the admins'.
        assertThat(admin.patch(operatorsPath, Map.of("desiredMaxConcurrentRuns", 16)).status()).isEqualTo(404);
        assertThat(operator.patch(operatorsPath, Map.of("desiredMaxConcurrentRuns", 16)).status()).isEqualTo(200);
        assertThat(admin.patch(ownersPath, Map.of("desiredMaxConcurrentRuns", 2)).status()).isEqualTo(200);
        assertThat(owner.patch(ownersPath, Map.of("desiredMaxConcurrentRuns", 1)).status()).isEqualTo(200);

        // Out of range, or a value together with its clear flag.
        for (Object body : List.of(Map.of("desiredMaxConcurrentRuns", 0), Map.of("desiredMaxConcurrentRuns", 1001),
                Map.of("desiredMaxConcurrentRuns", 2, "clearDesiredMaxConcurrentRuns", true))) {
            ApiResponse bad = operator.patch(operatorsPath, body);
            assertThat(bad.status()).as("%s -> %s", body, bad).isEqualTo(400);
            assertThat(bad.problemCode()).isEqualTo("VALIDATION_FAILED");
        }

        // A request that changes nothing is not audited and does not move the version.
        List<JsonNode> before = audits(owner, company, "runner_host.updated");
        assertThat(operator.patch(operatorsPath, Map.of("desiredMaxConcurrentRuns", 16)).status()).isEqualTo(200);
        assertThat(operator.patch(operatorsPath, Map.of()).status()).isEqualTo(200);
        assertThat(operator.patch(operatorsPath, Map.of("clearDesiredMaxConcurrentRuns", false)).status())
            .isEqualTo(200);
        assertThat(audits(owner, company, "runner_host.updated")).hasSameSizeAs(before);

        ApiResponse cleared = operator.patch(operatorsPath, Map.of("clearDesiredMaxConcurrentRuns", true));
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.json().has("desiredMaxConcurrentRuns")).isFalse();
        assertThat(operator.patch(operatorsPath, Map.of("clearDesiredMaxConcurrentRuns", true)).status())
            .isEqualTo(200);

        // runner_host.updated: the old and new value and the host's new version, per change.
        List<JsonNode> updates = audits(owner, company, "runner_host.updated").stream()
            .filter(event -> event.get("targetId").asString().equals(operatorsHost.id().toString()))
            .toList();
        assertThat(updates).hasSize(3);
        JsonNode first = updates.getLast().get("details");
        assertThat(nullable(first, "oldDesiredMaxConcurrentRuns")).isNull();
        assertThat(first.get("newDesiredMaxConcurrentRuns").asInt()).isEqualTo(3);
        assertThat(first.get("configVersion").asLong()).isEqualTo(1);
        assertThat(first.get("name").asString()).startsWith("member-host");
        JsonNode last = updates.getFirst();
        assertThat(last.get("targetType").asString()).isEqualTo("runner_host");
        assertThat(last.get("actor").get("id").asString()).isEqualTo(company.userId("MEMBER").toString());
        assertThat(last.get("details").get("oldDesiredMaxConcurrentRuns").asInt()).isEqualTo(16);
        assertThat(nullable(last.get("details"), "newDesiredMaxConcurrentRuns")).isNull();
        assertThat(last.get("details").get("configVersion").asLong()).isEqualTo(3);
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void anOfflineHostGetsItsSettingsAfterWelcomeAndTheServerExpectsThemFromHello() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("host"));
        assertThat(owner.patch(company.path("/runner-hosts/" + host.id()), Map.of("desiredMaxConcurrentRuns", 2))
            .status()).isEqualTo(200);

        try (FakeRunner runner = FakeRunner.connect(port, host.token()).manualConfig()) {
            runner.helloAndWelcome(List.of(FakeRunner.claudeCredential("claude-max")), 6);
            // welcome, then host.config with the stored state, before any other frame.
            List<JsonNode> frames = runner.received();
            assertThat(frames).extracting(frame -> frame.get("type").asString()).startsWith("welcome");
            JsonNode config = runner.expect("host.config");
            assertThat(runner.received().get(1)).isEqualTo(config);
            assertThat(config.get("version").asLong()).isEqualTo(1);
            assertThat(config.get("desiredMaxConcurrentRuns").asInt()).isEqualTo(2);

            // From hello the server expects min(desired, ceiling), but it is not confirmed yet.
            JsonNode expected = host(owner, company, host.id());
            assertThat(expected.get("online").asBoolean()).isTrue();
            assertThat(expected.get("maxConcurrentRunsCeiling").asInt()).isEqualTo(6);
            assertThat(expected.get("maxConcurrentRuns").asInt()).isEqualTo(2);
            assertThat(expected.get("configPending").asBoolean()).isTrue();

            runner.applyConfig(config);
            await().atMost(WAIT).until(() -> !host(owner, company, host.id()).get("configPending").asBoolean());
            runner.assertFramesValid();
        }
    }

    @Test
    void theConfirmedVersionOnlyGrowsAndBadAnswersAreIgnored() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("host"));
        String path = company.path("/runner-hosts/" + host.id());
        try (FakeRunner runner = FakeRunner.connect(port, host.token()).manualConfig()) {
            runner.helloAndWelcome(List.of(FakeRunner.claudeCredential("claude-max")), 4);
            JsonNode initial = runner.expect("host.config");
            assertThat(initial.get("version").asLong()).isZero();
            assertThat(initial.has("desiredMaxConcurrentRuns")).isFalse();
            assertThat(initial.get("credentials")).isEmpty();
            runner.applyConfig(initial);
            await().atMost(WAIT).until(() -> host(owner, company, host.id()).get("maxConcurrentRuns").asInt() == 4);

            assertThat(owner.patch(path, Map.of("desiredMaxConcurrentRuns", 3)).status()).isEqualTo(200);
            JsonNode v1 = nextConfig(runner, 1);
            assertThat(owner.patch(path, Map.of("desiredMaxConcurrentRuns", 2)).status()).isEqualTo(200);
            JsonNode v2 = nextConfig(runner, 2);
            runner.applyConfig(v2);
            await().atMost(WAIT).until(() -> !host(owner, company, host.id()).get("configPending").asBoolean());
            assertThat(host(owner, company, host.id()).get("maxConcurrentRuns").asInt()).isEqualTo(2);
            // The host ignores the older version.
            assertThat(runner.applyConfig(v1)).isNull();

            // A late answer to an older version stores its values, but the confirmed version stays.
            runner.send(applied(1, false, 3, 4, List.of(pool("claude-max", 2, 2))));
            await().atMost(WAIT).until(() -> host(owner, company, host.id()).get("maxConcurrentRuns").asInt() == 3);
            assertThat(host(owner, company, host.id()).get("configPending").asBoolean()).isFalse();

            // A version the server never issued does not count beyond the current one.
            runner.send(applied(99, false, 2, 4, List.of(pool("claude-max", 2, 2))));
            await().atMost(WAIT).until(() -> host(owner, company, host.id()).get("maxConcurrentRuns").asInt() == 2);
            assertThat(owner.patch(path, Map.of("desiredMaxConcurrentRuns", 1)).status()).isEqualTo(200);
            JsonNode v3 = nextConfig(runner, 3);
            assertThat(host(owner, company, host.id()).get("configPending").asBoolean()).isTrue();
            runner.send(applied(2, false, 2, 4, List.of(pool("claude-max", 2, 2))));
            runner.applyConfig(v3);
            await().atMost(WAIT).until(() -> !host(owner, company, host.id()).get("configPending").asBoolean()
                    && host(owner, company, host.id()).get("maxConcurrentRuns").asInt() == 1);

            // An effective value above its ceiling, or a label twice: BAD_MESSAGE, and nothing is stored.
            runner.send(applied(3, false, 5, 4, List.of()));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
            assertThat(host(owner, company, host.id()).get("maxConcurrentRuns").asInt()).isEqualTo(1);
            runner.send(applied(3, false, 3, 4, List.of(pool("claude-max", 3, 2))));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
            runner.send(applied(3, false, 3, 4, List.of(pool("claude-max", 1, 2), pool("claude-max", 2, 2))));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
            assertThat(host(owner, company, host.id()).get("maxConcurrentRuns").asInt()).isEqualTo(1);
            // The schema: 0 slots exactly while draining.
            runner.send(applied(3, true, 1, 4, List.of()));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
            // The pools it lists, of known labels or not, are accepted and not stored: the personal host's credential
            // keeps the pool of its hello.
            runner.send(applied(3, false, 1, 4, List.of(pool("claude-max", 1, 2), pool("no-such-label", 1, 1))));
            runner.expectNone("error", Duration.ofMillis(500));
            JsonNode after = host(owner, company, host.id());
            assertThat(after.get("maxConcurrentRuns").asInt()).isEqualTo(1);
            assertThat(after.get("maxConcurrentRunsCeiling").asInt()).isEqualTo(4);
            assertThat(after.get("credentials")).hasSize(1);
            assertThat(after.get("credentials").get(0).get("label").asString()).isEqualTo("claude-max");
            runner.assertFramesValid();
        }
    }

    @Test
    void aDrainingHostShowsWithoutSlotsWhileConnectedAndAHelloEndsTheDrain() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("host"));
        try (FakeRunner runner = connectRunner(host, List.of(FakeRunner.claudeCredential("claude-max")))) {
            await().atMost(WAIT).until(() -> host(owner, company, host.id()).get("maxConcurrentRuns").asInt() == 4
                    && !host(owner, company, host.id()).get("configPending").asBoolean());
            runner.startDraining();
            await().atMost(WAIT).until(() -> host(owner, company, host.id()).get("draining").asBoolean());
            JsonNode draining = host(owner, company, host.id());
            assertThat(draining.get("maxConcurrentRuns").asInt()).isZero();
            assertThat(draining.get("maxConcurrentRunsCeiling").asInt()).isEqualTo(4);
            runner.assertFramesValid();
        }
        await().atMost(WAIT).until(() -> !host(owner, company, host.id()).get("online").asBoolean());
        assertThat(host(owner, company, host.id()).get("draining").asBoolean()).isFalse();

        try (FakeRunner again = connectRunner(host, List.of(FakeRunner.claudeCredential("claude-max")))) {
            await().atMost(WAIT).until(() -> host(owner, company, host.id()).get("maxConcurrentRuns").asInt() == 4);
            assertThat(host(owner, company, host.id()).get("draining").asBoolean()).isFalse();
            again.assertFramesValid();
        }
    }

    @Test
    void aV1HelloWithMoreThanSixteenSlotsIsRefusedAndAV2HostHasUpTo1000() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("host"));
        FakeRunner old = FakeRunner.connect(port, host.token());
        old.send(FakeRunner.hello(List.of(FakeRunner.claudeCredential("claude-max")), List.of(), 17));
        assertThat(old.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
        assertThat(old.awaitClose()).isEqualTo(1008);
        assertThat(host(owner, company, host.id()).get("online").asBoolean()).isFalse();

        // A host with a sandbox per run has no fixed maximum of its own (D12): protocol 2 bounds it at 1000.
        try (FakeRunner vm = FakeRunner.connect(port, host.token())) {
            vm.helloV2AndWelcome(List.of(), List.of(), "0.3.0", 200);
            await().atMost(WAIT).until(() -> host(owner, company, host.id()).get("maxConcurrentRuns").asInt() == 200);
            assertThat(host(owner, company, host.id()).get("maxConcurrentRunsCeiling").asInt()).isEqualTo(200);
            vm.assertFramesValid();
        }
        FakeRunner tooMany = FakeRunner.connect(port, host.token());
        tooMany.send(FakeRunner.helloV2(List.of(), List.of(), "0.3.0", 1001));
        assertThat(tooMany.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
        assertThat(tooMany.awaitClose()).isEqualTo(1008);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static Map<String, Object> applied(long version, boolean draining, int slots, int ceiling,
            List<Map<String, Object>> credentials) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "host.config.applied");
        frame.put("version", version);
        frame.put("draining", draining);
        frame.put("maxConcurrentRuns", slots);
        frame.put("maxConcurrentRunsCeiling", ceiling);
        frame.put("credentials", credentials);
        return frame;
    }

    private static Map<String, Object> pool(String label, int poolSize, int ceiling) {
        return Map.of("label", label, "poolSize", poolSize, "poolSizeCeiling", ceiling);
    }

    /** The next host.config frame, which must carry this version. */
    private static JsonNode nextConfig(FakeRunner runner, long version) {
        JsonNode config = runner.expect("host.config");
        assertThat(config.get("version").asLong()).as("%s", config).isEqualTo(version);
        return config;
    }

    private static JsonNode host(ApiClient actor, TestCompany company, UUID hostId) {
        for (JsonNode host : actor.get(company.path("/runner-hosts")).json().get("items")) {
            if (host.get("id").asString().equals(hostId.toString())) {
                return host;
            }
        }
        throw new AssertionError("No runner host " + hostId);
    }

    /** Audit events of one action, newest first. */
    private static List<JsonNode> audits(ApiClient actor, TestCompany company, String action) {
        List<JsonNode> events = new ArrayList<>();
        actor.get(company.path("/audit-events?limit=200")).json().get("items").forEach(event -> {
            if (event.get("action").asString().equals(action)) {
                events.add(event);
            }
        });
        return events;
    }

    private static JsonNode nullable(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }
}
