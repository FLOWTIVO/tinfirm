package com.flowtivo.tinfirm.diagnostics.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import tools.jackson.databind.JsonNode;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedException;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.Source;
import com.flowtivo.tinfirm.diagnostics.internal.ErrorCapture.Pending;
import com.flowtivo.tinfirm.shared.config.BuildInfo;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.SentryStub;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * ADR-0070: errors of the server's process and of the web app become scrubbed reports, which instance admins list,
 * download and delete, and which a worker delivers to the destination while delivery is automatic.
 */
class ErrorReportsIntegrationTest extends IntegrationTest {

    private static final String CHROME = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/141.0.0.0 Safari/537.36";

    @Autowired
    ErrorFlush flush;

    @Autowired
    ErrorDelivery delivery;

    @Autowired
    ErrorLogCapture logCapture;

    @Autowired
    ErrorReportStore store;

    @Autowired
    SystemTransactions transactions;

    @Autowired
    LockProvider locks;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BuildInfo build;

    @BeforeEach
    void attached() {
        // Another test context's start resets Logback, which drops the appender until the next flush.
        logCapture.ensureAttached();
    }

    @AfterEach
    void localAgain() {
        // Every other test sees the default: nothing is delivered, and SystemInfo says so.
        assertThat(admin().put("/api/admin/error-reporting", Map.of("automatic", false)).status()).isEqualTo(200);
        SentryStub.server().resetAll();
        // And no test's lock outlives it, a crashed or another replica's one included.
        jdbc.sql("UPDATE shedlock SET lock_until = timezone('utc', CURRENT_TIMESTAMP) WHERE name = ?")
            .param(ErrorDelivery.LOCK)
            .update();
    }

    @Test
    void aServerErrorBecomesAScrubbedReportWithItsFrames() {
        flush.flush();
        String loggerName = unique("errorreports.test");
        Logger logger = LoggerFactory.getLogger(loggerName);
        for (int i = 0; i < 3; i++) {
            logger.error("Sync of {} failed", "Payroll of alice@acme.example",
                    new IllegalStateException("Issue 'Bob's salary' at https://jira.acme.example/browse/PAY-1"));
        }
        logger.warn("Not an error {}", "x");
        flush.flush();

        ApiClient admin = admin();
        JsonNode report = findByLogger(admin, loggerName);
        assertThat(report.get("source").asString()).isEqualTo("SERVER");
        assertThat(report.get("title").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(report.get("message").asString()).isEqualTo("Issue <str> at <url>");
        assertThat(report.get("occurrences").asLong()).isEqualTo(3);
        assertThat(report.get("unreported").asLong()).isEqualTo(3);
        assertThat(report.get("lastVersion").asString()).isEqualTo(build.version());

        ApiResponse detail = admin.get("/api/admin/error-reports/" + report.get("id").asString());
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.json().get("logMessage").asString()).isEqualTo("Sync of {} failed");
        JsonNode frame = detail.json().get("exceptions").get(0).get("frames").get(0);
        assertThat(frame.get("module").asString()).isEqualTo(getClass().getName());
        assertThat(frame.get("inApp").asBoolean()).isTrue();
        assertThat(detail.body()).doesNotContain("alice", "Payroll", "jira.acme", "Bob");
    }

    @Test
    void aWebErrorKeepsTheRoutePatternAndThePathsOfItsOwnScriptsOnly() {
        flush.flush();
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        String route = "/c/:companyId/" + unique("page");
        Map<String, Object> error = Map.of("kind", "TypeError",
                "message", "Cannot read 'title' of undefined for carol@acme.example",
                "route", route,
                "frames", List.of(Map.of("function", "ConversationPage.send", "file",
                        "http://localhost:8080/chunk-w4QG-jIO.js", "line", 1, "column", 4211),
                        Map.of("function", "inject", "file", "chrome-extension://abc/content.js", "line", 3)));
        for (int i = 0; i < 25; i++) {
            ApiResponse posted = member.send("POST", "/api/client-errors", error, Map.of("User-Agent", CHROME));
            assertThat(posted.status()).as("post %d: %s", i, posted).isEqualTo(204);
        }
        flush.flush();

        ApiClient admin = admin();
        JsonNode report = find(admin, r -> route.equals(text(r, "route")));
        assertThat(report.get("source").asString()).isEqualTo("WEB");
        assertThat(report.get("message").asString()).isEqualTo("Cannot read <str> of undefined for <email>");
        // At most 20 a minute from one user; the rest are dropped, each post still 204.
        assertThat(report.get("occurrences").asLong()).isEqualTo(20);
        JsonNode detail = admin.get("/api/admin/error-reports/" + report.get("id").asString()).json();
        assertThat(detail.get("runtime").asString()).isEqualTo("Chrome 141");
        JsonNode frames = detail.get("exceptions").get(0).get("frames");
        assertThat(frames.get(0).get("file").asString()).isEqualTo("/chunk-w4QG-jIO.js");
        assertThat(text(frames.get(0), "function")).as("a minified name is not kept").isNull();
        assertThat(frames.get(0).get("inApp").asBoolean()).isTrue();
        assertThat(frames.get(1).get("file").asString()).isEqualTo("<external>");
        assertThat(frames.get(1).get("inApp").asBoolean()).isFalse();
    }

    @Test
    void aLateFlushOfOlderOccurrencesKeepsTheNewestSampleAndVersion() {
        // Two replicas: one flushed this hour's occurrences, the other flushes yesterday's late (its database was
        // away, or it still runs the previous version during a rollout).
        String route = "/" + unique("late");
        String fingerprint = CapturedError.webFingerprint("TypeError", null, route);
        Instant now = Instant.now();
        Pending newer = pending(fingerprint, route, "newer", 2, now.minusSeconds(3600), now);
        Pending older = pending(fingerprint, route, "older", 3, now.minusSeconds(86_400), now.minusSeconds(82_800));
        transactions.instanceWide(() -> {
            store.add(List.of(newer), "0.7.0", "b".repeat(40));
            return null;
        });
        transactions.instanceWide(() -> {
            store.add(List.of(older), "0.6.0", "a".repeat(40));
            return null;
        });

        JsonNode report = find(admin(), r -> route.equals(text(r, "route")));
        assertThat(report.get("message").asString()).isEqualTo("newer");
        assertThat(report.get("lastVersion").asString()).isEqualTo("0.7.0");
        assertThat(report.get("firstVersion").asString()).isEqualTo("0.6.0");
        assertThat(report.get("occurrences").asLong()).isEqualTo(5);
        assertThat(Instant.parse(report.get("firstSeenAt").asString())).isBefore(now.minusSeconds(82_800));
    }

    @Test
    void theLockOfAPassThatCrashedExpires() {
        ApiClient admin = admin();
        assertThat(admin.put("/api/admin/error-reporting", Map.of("automatic", true)).status()).isEqualTo(200);
        // A pass on another replica takes the lock and its server crashes: it never extends nor releases it.
        assertThat(locks.lock(new LockConfiguration(Instant.now(), ErrorDelivery.LOCK, Duration.ofSeconds(2),
                Duration.ZERO))).isPresent();

        assertThat(delivery.deliver().ran()).as("held until it expires").isFalse();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
            .until(() -> delivery.deliver().ran());
    }

    @Test
    void aPassThatLostItsLockStops() throws Exception {
        // Each answer takes a second: the pass is still sending when another replica holds its lock.
        SentryStub.server().stubFor(post(urlEqualTo(SentryStub.ENVELOPE)).willReturn(aResponse().withStatus(200)
            .withFixedDelay(1000)));
        ApiClient admin = admin();
        for (int i = 0; i < 3; i++) {
            LoggerFactory.getLogger(unique("errorreports.lost")).error("Lost test", new IllegalStateException("x"));
        }
        flush.flush();
        assertThat(admin.put("/api/admin/error-reporting", Map.of("automatic", true)).status()).isEqualTo(200);

        CompletableFuture<ErrorDelivery.Outcome> pass = CompletableFuture.supplyAsync(delivery::deliver);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20))
            .until(() -> !SentryStub.server().findAll(postRequestedFor(urlEqualTo(SentryStub.ENVELOPE))).isEmpty());
        // Its lock expired meanwhile, and another replica took it.
        jdbc.sql("UPDATE shedlock SET locked_by = 'another-replica' WHERE name = ?").param(ErrorDelivery.LOCK)
            .update();
        ErrorDelivery.Outcome outcome = pass.get(30, TimeUnit.SECONDS);

        assertThat(outcome.sent()).as("the send in flight completes, nothing after it").isEqualTo(1);
        assertThat(outcome.error()).isEqualTo("The pass lost its lock: it expired, or another replica holds it");
        assertThat(SentryStub.server().findAll(postRequestedFor(urlEqualTo(SentryStub.ENVELOPE)))).hasSize(1);
        assertThat(jdbc.sql("SELECT locked_by FROM shedlock WHERE name = ?").param(ErrorDelivery.LOCK)
            .query(String.class).single()).as("the other replica's lock is not released").isEqualTo("another-replica");
    }

    @Test
    void onlyInstanceAdminsReadTheReportsAndASessionIsNeededToPostOne() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        assertThat(owner.get("/api/admin/error-reports").status()).isEqualTo(403);
        assertThat(owner.get("/api/admin/error-reporting").status()).isEqualTo(403);
        assertThat(owner.get("/api/admin/error-reports/export").status()).isEqualTo(403);
        assertThat(owner.put("/api/admin/error-reporting", Map.of("automatic", true)).status()).isEqualTo(403);
        ApiClient anonymous = client();
        anonymous.fetchCsrfToken();
        assertThat(anonymous.post("/api/client-errors", Map.of("kind", "TypeError")).status()).isEqualTo(401);
        assertThat(owner.post("/api/client-errors", Map.of("kind", "")).status()).isEqualTo(400);
    }

    @Test
    void automaticDeliverySendsEachUndeliveredReportOnceAsASentryEvent() {
        SentryStub.server().stubFor(post(urlEqualTo(SentryStub.ENVELOPE)).willReturn(aResponse().withStatus(200)
            .withBody("{\"id\":\"x\"}")));
        ApiClient admin = admin();
        ApiResponse local = admin.get("/api/admin/error-reporting");
        assertThat(local.json().get("mode").asString()).isEqualTo("LOCAL");
        assertThat(local.json().get("setBy").asString()).isEqualTo("ADMIN");
        assertThat(local.json().get("destination").asString()).isEqualTo("localhost");
        assertThat(delivery.deliver().ran()).as("nothing is sent while LOCAL").isFalse();

        String loggerName = unique("errorreports.delivery");
        LoggerFactory.getLogger(loggerName).error("Delivery test", new IllegalArgumentException("bad 'x'"));
        flush.flush();

        ApiResponse automatic = admin.put("/api/admin/error-reporting", Map.of("automatic", true));
        assertThat(automatic.status()).as("%s", automatic).isEqualTo(200);
        assertThat(automatic.json().get("mode").asString()).isEqualTo("AUTOMATIC");
        assertThat(client().get("/api/system/info").json().get("telemetryEnabled").asBoolean()).isTrue();
        assertThat(instanceAudit(admin)).anySatisfy(event -> {
            assertThat(event.get("action").asString()).isEqualTo("error_reporting.changed");
            assertThat(event.get("details").get("mode").asString()).isEqualTo("AUTOMATIC");
        });

        // Every undelivered report goes, the longest waiting first, in passes of at most 100.
        while (delivery.deliver().sent() > 0) {
            // next pass
        }
        JsonNode report = findByLogger(admin, loggerName);
        assertThat(report.get("unreported").asLong()).isZero();
        assertThat(text(report, "reportedAt")).isNotNull();
        List<LoggedRequest> sent = SentryStub.server().findAll(postRequestedFor(urlEqualTo(SentryStub.ENVELOPE))
            .withHeader("X-Sentry-Auth", containing("sentry_key=" + SentryStub.KEY))
            .withHeader("Content-Type", equalTo("application/x-sentry-envelope")));
        List<String> ours = new ArrayList<>();
        for (LoggedRequest request : sent) {
            if (request.getBodyAsString().contains(loggerName)) {
                ours.add(request.getBodyAsString());
            }
        }
        assertThat(ours).hasSize(1);
        String[] lines = ours.getFirst().split("\n");
        assertThat(lines[1]).contains("\"type\":\"event\"");
        assertThat(lines[2]).contains("\"fingerprint\":[", "\"platform\":\"java\"", "\"value\":\"bad <str>\"",
                "\"instance\":\"" + local.json().get("instanceId").asString() + "\"");

        int before = SentryStub.server().findAll(postRequestedFor(urlEqualTo(SentryStub.ENVELOPE))).size();
        assertThat(delivery.deliver().sent()).as("a delivered report is not sent again").isZero();
        assertThat(SentryStub.server().findAll(postRequestedFor(urlEqualTo(SentryStub.ENVELOPE)))).hasSize(before);
        JsonNode settings = admin.get("/api/admin/error-reporting").json();
        assertThat(text(settings, "lastDeliveryAt")).isNotNull();
        assertThat(text(settings, "lastDeliveryError")).isNull();
    }

    @Test
    void turningDeliveryOffStopsAPassThatIsRunning() throws Exception {
        // Each answer takes a second: the pass is still sending when an admin turns delivery off.
        SentryStub.server().stubFor(post(urlEqualTo(SentryStub.ENVELOPE)).willReturn(aResponse().withStatus(200)
            .withFixedDelay(1000)));
        ApiClient admin = admin();
        for (int i = 0; i < 3; i++) {
            LoggerFactory.getLogger(unique("errorreports.stop")).error("Stop test", new IllegalStateException("x"));
        }
        flush.flush();
        assertThat(admin.put("/api/admin/error-reporting", Map.of("automatic", true)).status()).isEqualTo(200);

        CompletableFuture<ErrorDelivery.Outcome> pass = CompletableFuture.supplyAsync(delivery::deliver);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (SentryStub.server().findAll(postRequestedFor(urlEqualTo(SentryStub.ENVELOPE))).isEmpty()
                && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        assertThat(admin.put("/api/admin/error-reporting", Map.of("automatic", false)).status()).isEqualTo(200);
        ErrorDelivery.Outcome outcome = pass.get(30, TimeUnit.SECONDS);

        assertThat(outcome.ran()).isTrue();
        assertThat(outcome.sent()).as("the send in flight completes, nothing after it").isEqualTo(1);
        assertThat(SentryStub.server().findAll(postRequestedFor(urlEqualTo(SentryStub.ENVELOPE)))).hasSize(1);
        assertThat(admin.get("/api/admin/error-reporting").json().get("pending").asLong()).isPositive();
    }

    @Test
    void aFailedDeliveryIsShownAndTriedAgain() {
        SentryStub.server().stubFor(post(urlEqualTo(SentryStub.ENVELOPE)).willReturn(aResponse().withStatus(503)));
        ApiClient admin = admin();
        String loggerName = unique("errorreports.failure");
        LoggerFactory.getLogger(loggerName).error("Failure test", new IllegalStateException("x"));
        flush.flush();
        assertThat(admin.put("/api/admin/error-reporting", Map.of("automatic", true)).status()).isEqualTo(200);

        ErrorDelivery.Outcome outcome = delivery.deliver();
        assertThat(outcome.ran()).isTrue();
        assertThat(outcome.error()).isEqualTo("The destination localhost answered 503");
        JsonNode settings = admin.get("/api/admin/error-reporting").json();
        assertThat(settings.get("lastDeliveryError").asString()).isEqualTo("The destination localhost answered 503");
        assertThat(settings.get("pending").asLong()).isPositive();
        assertThat(findByLogger(admin, loggerName).get("unreported").asLong()).isEqualTo(1);
    }

    @Test
    void theDownloadHoldsTheEventsAndADeletedReportIsGone() {
        String loggerName = unique("errorreports.export");
        LoggerFactory.getLogger(loggerName).error("Export test for {}", "dave@acme.example",
                new UnsupportedOperationException("no"));
        flush.flush();
        ApiClient admin = admin();

        ApiResponse export = admin.get("/api/admin/error-reports/export");
        assertThat(export.status()).isEqualTo(200);
        assertThat(export.header("Content-Disposition")).startsWith("attachment;")
            .contains("tinfirm-error-reports-");
        assertThat(export.json().get("format").asString()).isEqualTo("tinfirm-error-reports/1");
        assertThat(export.body()).contains(loggerName).doesNotContain("dave@acme.example");

        String id = findByLogger(admin, loggerName).get("id").asString();
        assertThat(admin.delete("/api/admin/error-reports/" + id).status()).isEqualTo(204);
        assertThat(admin.get("/api/admin/error-reports/" + id).status()).isEqualTo(404);
        assertThat(admin.delete("/api/admin/error-reports/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(instanceAudit(admin)).anySatisfy(event -> {
            assertThat(event.get("action").asString()).isEqualTo("error_report.deleted");
            assertThat(event.get("targetId").asString()).isEqualTo(id);
        });
    }

    @Test
    void theWebAppsReportsHaveABudgetOfTheirOwnAndNeverEvictTheServers() {
        // L18: a member's flood of web reports, newer than every other report, evicts only web reports.
        Instant now = Instant.now();
        String server = "server-" + unique("budget");
        CapturedError serverError = new CapturedError(Source.SERVER, CapturedError.webFingerprint("S", server, null),
                "IllegalStateException", null, "com.flowtivo.tinfirm.Budget", server, null, "Java 25",
                List.of(new CapturedException("IllegalStateException", null, List.of())));
        List<Pending> web = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String route = "/" + unique("flood");
            web.add(pending(CapturedError.webFingerprint("TypeError", null, route), route, "flood " + i, 1,
                    now.plusSeconds(60 + i), now.plusSeconds(60 + i)));
        }
        transactions.instanceWide(() -> {
            store.add(List.of(new Pending(serverError, 1, now.minusSeconds(3600), now.minusSeconds(3600))), "0.7.0",
                    null);
            store.add(web, "0.7.0", null);
            return null;
        });

        transactions.instanceWide(() -> store.prune(now.minusSeconds(86_400), 1_000_000, 1));

        List<String> webLeft = jdbc.sql("SELECT fingerprint FROM error_report WHERE source = 'WEB'")
            .query(String.class)
            .list();
        assertThat(webLeft).containsExactly(web.get(2).error().fingerprint());
        assertThat(jdbc.sql("SELECT count(*) FROM error_report WHERE fingerprint = ?")
            .params(serverError.fingerprint())
            .query(Long.class)
            .single()).isEqualTo(1);
    }

    private static Pending pending(String fingerprint, String route, String message, long count, Instant first,
            Instant last) {
        CapturedError error = new CapturedError(Source.WEB, fingerprint, "TypeError", message, null, null, route,
                "Chrome 141", List.of(new CapturedException("TypeError", message, List.of())));
        return new Pending(error, count, first, last);
    }

    private JsonNode findByLogger(ApiClient admin, String logger) {
        return find(admin, report -> logger.equals(text(report, "logger")));
    }

    private static JsonNode find(ApiClient admin, java.util.function.Predicate<JsonNode> match) {
        String cursor = null;
        do {
            ApiResponse page = admin.get("/api/admin/error-reports?limit=200" + (cursor == null ? "" : "&cursor="
                    + cursor));
            assertThat(page.status()).as("%s", page).isEqualTo(200);
            for (JsonNode report : page.json().get("items")) {
                if (match.test(report)) {
                    return report;
                }
            }
            JsonNode next = page.json().get("nextCursor");
            cursor = next == null || next.isNull() ? null : next.asString();
        }
        while (cursor != null);
        throw new AssertionError("no such report");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<JsonNode> instanceAudit(ApiClient admin) {
        List<JsonNode> events = new ArrayList<>();
        admin.get("/api/admin/audit-events?limit=200").json().get("items").forEach(events::add);
        return events;
    }
}
