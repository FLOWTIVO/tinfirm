package com.flowtivo.tinfirm.runner.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * The pool settings of a company runner host (ADR-0099): an admin sets the schedule over HTTP, the server evaluates it
 * and sends the host numbers in {@code host.config.pool}, the host confirms them capped by its ceiling and reports its
 * sandboxes in {@code host.pool.status}, and the minute's tick sends a host whose count changed its new configuration.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
@ExtendWith(EnterpriseLicence.class)
class RunnerPoolIntegrationTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static final List<String> EVERY_DAY = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired
    private RunnerPoolsImpl pools;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void anAdminSetsTheScheduleAndTheHostGetsTheCountCappedByItsCeiling() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("vm-host"), "COMPANY");
        String path = company.path("/runner-hosts/" + host.id());

        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            runner.send(FakeRunner.helloWithPool(8, 4));
            runner.expect("welcome");
            // A new pool host keeps no ready sandboxes until an admin opts in (ADR-0099).
            JsonNode first = runner.expect("host.config");
            assertThat(first.get("pool").get("readyTarget").asInt()).isZero();
            assertThat(first.get("pool").has("next")).isFalse();
            await().atMost(WAIT).until(() -> !host(owner, company, host.id()).get("configPending").asBoolean());
            JsonNode fresh = host(owner, company, host.id()).get("pool");
            assertThat(fresh.get("maxReadyCeiling").asInt()).isEqualTo(4);
            assertThat(fresh.get("readyTarget").asInt()).isZero();
            assertThat(nullable(fresh, "settings")).isNull();
            assertThat(fresh.get("week")).hasSize(1);

            // A window that holds now, every day, in UTC: six ready, above the host's ceiling of four.
            Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            Map<String, Object> settings = settings("UTC", 1,
                    window(EVERY_DAY, utc(now.minus(1, ChronoUnit.HOURS)), utc(now.plus(2, ChronoUnit.HOURS)), 6));
            ApiResponse set = admin.patch(path, Map.of("pool", settings));
            assertThat(set.status()).as("%s", set).isEqualTo(200);
            JsonNode pool = set.json().get("pool");
            assertThat(pool.get("settings").get("zone").asString()).isEqualTo("UTC");
            assertThat(pool.get("settings").get("windows").get(0).get("days")).hasSize(7);
            assertThat(pool.get("readyTarget").asInt()).isEqualTo(6);
            assertThat(Instant.parse(pool.get("next").get("at").asString())).isEqualTo(now.plus(2, ChronoUnit.HOURS));
            assertThat(pool.get("next").get("readyTarget").asInt()).isEqualTo(1);
            assertThat(pool.get("week").get(0).get("readyTarget").asInt()).isEqualTo(6);
            assertThat(pool.get("week").get(1)).isEqualTo(pool.get("next"));

            // The host gets the numbers, its next change included, and confirms them up to its ceiling.
            JsonNode config = runner.expect("host.config", frame -> frame.get("pool").get("readyTarget").asInt() == 6);
            assertThat(config.get("pool").get("next").get("readyTarget").asInt()).isEqualTo(1);
            assertThat(Instant.parse(config.get("pool").get("next").get("at").asString()))
                .isEqualTo(now.plus(2, ChronoUnit.HOURS));
            await().atMost(WAIT).until(() -> !host(owner, company, host.id()).get("configPending").asBoolean());

            // Its report of the sandboxes shows on the page.
            runner.send(Map.of("type", "host.pool.status", "ready", 2, "starting", 1, "busy", 3,
                    "coldStartP90Seconds", 95, "servedReady", 7, "servedCold", 2));
            await().atMost(WAIT).until(() -> host(owner, company, host.id()).get("pool").hasNonNull("status"));
            JsonNode status = host(owner, company, host.id()).get("pool").get("status");
            assertThat(status.get("ready").asInt()).isEqualTo(2);
            assertThat(status.get("starting").asInt()).isEqualTo(1);
            assertThat(status.get("busy").asInt()).isEqualTo(3);
            assertThat(status.get("coldStartP90Seconds").asLong()).isEqualTo(95);
            assertThat(status.get("servedReady").asInt()).isEqualTo(7);
            assertThat(status.get("servedCold").asInt()).isEqualTo(2);
            assertThat(status.hasNonNull("reportedAt")).isTrue();

            // The same settings again change nothing: no audit, no new version.
            List<JsonNode> before = updates(owner, company, host.id());
            long version = config.get("version").asLong();
            assertThat(admin.patch(path, Map.of("pool", settings)).status()).isEqualTo(200);
            assertThat(updates(owner, company, host.id())).hasSameSizeAs(before);

            // Cleared: no sandbox waits ready, and the host hears so.
            ApiResponse cleared = owner.patch(path, Map.of("clearPool", true));
            assertThat(cleared.status()).as("%s", cleared).isEqualTo(200);
            assertThat(nullable(cleared.json().get("pool"), "settings")).isNull();
            assertThat(cleared.json().get("pool").get("readyTarget").asInt()).isZero();
            JsonNode none = runner.expect("host.config", frame -> frame.get("version").asLong() > version);
            assertThat(none.get("pool").get("readyTarget").asInt()).isZero();
            assertThat(none.get("pool").has("next")).isFalse();

            // runner_host.updated: what changed, the old and new settings, and the version.
            List<JsonNode> audited = updates(owner, company, host.id());
            assertThat(audited).hasSize(2);
            JsonNode added = audited.getLast().get("details");
            assertThat(added.get("changed").asString()).isEqualTo("pool");
            assertThat(nullable(added, "oldPool")).isNull();
            assertThat(added.get("newPool").get("zone").asString()).isEqualTo("UTC");
            assertThat(added.get("newPool").get("readyDefault").asInt()).isEqualTo(1);
            assertThat(added.get("newPool").get("windows").get(0).get("ready").asInt()).isEqualTo(6);
            assertThat(added.has("oldDesiredMaxConcurrentRuns")).isFalse();
            assertThat(added.get("configVersion").asLong()).isEqualTo(version);
            JsonNode removed = audited.getFirst().get("details");
            assertThat(removed.get("oldPool").get("windows")).hasSize(1);
            assertThat(nullable(removed, "newPool")).isNull();
            assertThat(removed.get("configVersion").asLong()).isEqualTo(none.get("version").asLong());
            assertThat(audited.getFirst().get("actor").get("id").asString())
                .isEqualTo(company.userId("OWNER").toString());
            assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
            runner.assertFramesValid();
        }
    }

    @Test
    void onlyAnAdminSetsAPoolAndOnlyOnACompanyHostThatKeepsReadySandboxes() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        RunnerHostRef poolHost = registerRunnerHost(owner, company.id(), unique("vm-host"), "COMPANY");
        RunnerHostRef dockerHost = registerRunnerHost(owner, company.id(), unique("docker-host"), "COMPANY");
        RunnerHostRef personal = registerRunnerHost(member, company.id(), unique("member-host"));
        Map<String, Object> body = Map.of("pool", settings(null, 1, List.of()));

        try (FakeRunner runner = FakeRunner.connect(port, poolHost.token());
                FakeRunner docker = FakeRunner.connect(port, dockerHost.token())) {
            runner.send(FakeRunner.helloWithPool(8, 4));
            runner.expect("welcome");
            docker.helloV2AndWelcome(List.of());
            String path = company.path("/runner-hosts/" + poolHost.id());

            assertThat(member.patch(path, body).status()).isEqualTo(403);
            for (String other : List.of(company.path("/runner-hosts/" + dockerHost.id()),
                    company.path("/runner-hosts/" + personal.id()))) {
                ApiResponse refused = (other.contains(personal.id().toString()) ? member : owner).patch(other, body);
                assertThat(refused.status()).as("%s", refused).isEqualTo(409);
                assertThat(refused.problemCode()).isEqualTo("RUNNER_HOST_POOL_UNSUPPORTED");
            }
            // A host without sandbox.pool shows no pool, even when it confirms one; its status is refused.
            docker.send(Map.of("type", "host.config.applied", "version", 0, "draining", false, "maxConcurrentRuns", 4,
                    "maxConcurrentRunsCeiling", 4, "credentials", List.of(), "pool",
                    Map.of("readyTarget", 0, "maxReadyCeiling", 4)));
            docker.send(Map.of("type", "host.pool.status", "ready", 1, "starting", 0, "busy", 0, "servedReady", 0,
                    "servedCold", 0));
            assertThat(docker.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
            assertThat(nullable(host(owner, company, dockerHost.id()), "pool")).isNull();

            List<Object> bad = new ArrayList<>(List.of(
                    Map.of("pool", settings(null, 1, List.of()), "clearPool", true),
                    Map.of("pool", settings("Mars/Olympus", 1, List.of())),
                    Map.of("pool", settings(null, 1, List.of(window(EVERY_DAY, "08:00", "18:00", 2)))),
                    Map.of("pool", settings("UTC", 9, List.of())),
                    Map.of("pool", settings("UTC", 0, List.of(window(List.of(), "08:00", "18:00", 2)))),
                    Map.of("pool", settings("UTC", 0, List.of(window(List.of("MO"), "08:00", "18:00", 2)))),
                    Map.of("pool", settings("UTC", 0, List.of(window(EVERY_DAY, "08:00", "24:00", 2)))),
                    Map.of("pool", settings("UTC", 0, List.of(window(EVERY_DAY, "08:00", "08:00", 2))))));
            // The pool size is the desired slots where set: three ready do not fit in two.
            bad.add(Map.of("desiredMaxConcurrentRuns", 2, "pool", settings("UTC", 3, List.of())));
            for (Object each : bad) {
                ApiResponse refused = owner.patch(path, each);
                assertThat(refused.status()).as("%s -> %s", each, refused).isEqualTo(400);
            }
            assertThat(updates(owner, company, poolHost.id())).isEmpty();
            assertThat(owner.patch(path, Map.of("desiredMaxConcurrentRuns", 2, "pool", settings("UTC", 2, List.of())))
                .status()).isEqualTo(200);
            // One change of both: one audit event that names both.
            List<JsonNode> audited = updates(owner, company, poolHost.id());
            assertThat(audited).hasSize(1);
            assertThat(audited.getFirst().get("details").get("newDesiredMaxConcurrentRuns").asInt()).isEqualTo(2);
            assertThat(audited.getFirst().get("details").get("changed").asString()).isEqualTo("pool");
            runner.assertFramesValid();
            docker.assertFramesValid();
        }
    }

    @Test
    void theTickSendsAHostWhoseCountChangedItsNewConfig() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("vm-host"), "COMPANY");

        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            runner.send(FakeRunner.helloWithPool(8, 4));
            runner.expect("welcome");
            ApiResponse set = owner.patch(company.path("/runner-hosts/" + host.id()),
                    Map.of("pool", settings("Europe/Prague", 2, List.of())));
            assertThat(set.status()).as("%s", set).isEqualTo(200);
            JsonNode config = runner.expect("host.config", frame -> frame.get("pool").get("readyTarget").asInt() == 2);

            // As if the count had changed since: the stored count is the one before.
            transactions.runInCompany(company.id(), () -> jdbc
                .sql("UPDATE runner_host SET pool_ready_target = 0 WHERE id = ?")
                .param(host.id())
                .update());
            pools.tick();
            JsonNode next = runner.expect("host.config",
                    frame -> frame.get("version").asLong() > config.get("version").asLong());
            assertThat(next.get("pool").get("readyTarget").asInt()).isEqualTo(2);
            // Nothing changed since: the next tick sends nothing.
            int frames = runner.received().size();
            pools.tick();
            assertThat(runner.received()).hasSize(frames);
            runner.assertFramesValid();
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static Map<String, Object> settings(String zone, int readyDefault, List<Map<String, Object>> windows) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (zone != null) {
            settings.put("zone", zone);
        }
        settings.put("readyDefault", readyDefault);
        settings.put("windows", windows);
        return settings;
    }

    private static Map<String, Object> settings(String zone, int readyDefault, Map<String, Object> window) {
        return settings(zone, readyDefault, List.of(window));
    }

    private static Map<String, Object> window(List<String> days, String from, String to, int ready) {
        return Map.of("days", days, "from", from, "to", to, "ready", ready);
    }

    private static String utc(Instant at) {
        return LocalTime.ofInstant(at, ZoneOffset.UTC).format(HH_MM);
    }

    private static JsonNode host(ApiClient actor, TestCompany company, UUID hostId) {
        for (JsonNode host : actor.get(company.path("/runner-hosts")).json().get("items")) {
            if (host.get("id").asString().equals(hostId.toString())) {
                return host;
            }
        }
        throw new AssertionError("No runner host " + hostId);
    }

    /** The host's {@code runner_host.updated} events, newest first. */
    private List<JsonNode> updates(ApiClient actor, TestCompany company, UUID hostId) {
        return auditEvents(actor, company, "runner_host.updated").stream()
            .filter(event -> event.get("targetId").asString().equals(hostId.toString()))
            .toList();
    }

    private static JsonNode nullable(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }
}
