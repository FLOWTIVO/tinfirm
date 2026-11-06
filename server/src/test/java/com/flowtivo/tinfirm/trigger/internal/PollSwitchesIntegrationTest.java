package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.trigger.FiringOutcome;

/**
 * Package 6's kill switches on polls, firings and schedules, with real triggers (the package 6 contract, §5.4, open
 * points 6 and 7): while the company's runs are stopped polls go on, keep their snapshots and store the comments, and
 * the gate fires nothing and records nothing, run now included; the lift fires nothing of the stop. A paused system's
 * poll calls nothing and waits without a failure, its window held, and run now and the editor's search read nothing;
 * its resume moves the window to the resume and wakes the poll. A schedule due during the stop records nothing and
 * moves on. TEST FIXTURE ONLY: tokens are test values.
 */
class PollSwitchesIntegrationTest extends PollingTestSupport {

    record Jira(JiraStub.Instance stub, UUID systemId, UUID aliceAccount, String aliceToken) {

        String search() {
            return stub.url("/rest/api/2/search");
        }

        String issue(String key) {
            return stub.url("/rest/api/2/issue/" + key);
        }
    }

    Jira jira(Bench bench) {
        JiraStub.Instance stub = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", stub.baseUrl(), false);
        String token = unique("alice-token");
        UUID account = connectToken(bench.alice(), bench.path("/me/accounts/" + system), stub, token, "alice");
        return new Jira(stub, system, account, token);
    }

    static Map<String, Object> issue(String id, String key, String assignee, Duration createdAgo,
            Duration updatedAgo, List<Map<String, Object>> comments) {
        return JiraStub.issue(id, key, "Checkout fails " + key, "The total is wrong.", assignee, List.of(), "To Do",
                jiraAgo(createdAgo), jiraAgo(updatedAgo), comments);
    }

    /** The trigger armed {@code ago} before now, its poll's window left as it is: time went on after a switch. */
    static void armedOnly(Bench bench, UUID triggerId, Duration ago) throws Exception {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger SET armed_at = ? WHERE id = ?", OffsetDateTime.now(ZoneOffset.UTC).minus(ago),
                    triggerId);
            sql.commit();
        }
    }

    static Instant instant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((OffsetDateTime) value).toInstant();
    }

    @Test
    void whileTheCompanysRunsAreStoppedPollsGoOnAndFireNothingAndTheLiftFiresNothingOfTheStop() throws Exception {
        Bench bench = bench();
        Jira jira = jira(bench);
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Mine"), jira.systemId(),
                jira.aliceAccount(), bench.alicePrincipal(), List.of(event("ASSIGNED"), event("COMMENTED")), "ISSUE",
                "SHOP", null));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        jira.stub().searchFor(jira.aliceToken(), List.of(
                issue("10001", "SHOP-1", "alice", Duration.ofMinutes(20), Duration.ofMinutes(20), List.of())));
        pass(bench, poll);
        assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-1");
        UUID conversation = (UUID) conversations(bench, trigger).getFirst().get("id");

        ApiResponse stopped = bench.owner().post(bench.path("/runs-stop"), Map.of("reason", "Incident"));
        assertThat(stopped.status()).as("%s", stopped).isEqualTo(200);
        assertThat(triggerRow(bench, trigger).get("waiting_reason")).isEqualTo("COMPANY_STOPPED");

        // During the stop: a comment on SHOP-1 and SHOP-2 assigned to Alice. The poll reads both and keeps their
        // snapshots, stores the comment in the open conversation (its turn waits), and fires and records nothing.
        readAgo(bench, poll, Duration.ofMinutes(15));
        jira.stub().searchFor(jira.aliceToken(), List.of(
                issue("10001", "SHOP-1", "alice", Duration.ofMinutes(20), Duration.ofMinutes(5), List.of(
                        JiraStub.comment("20002", "bob", "Please also check the tax.",
                                jiraAgo(Duration.ofMinutes(5))))),
                issue("10002", "SHOP-2", "alice", Duration.ofMinutes(4), Duration.ofMinutes(4), List.of())));
        TriggerPolls.Outcome during = pass(bench, poll);
        assertThat(during.status()).isEqualTo("ACTIVE");
        assertThat(during.error()).isNull();
        assertThat(during.handled()).isEqualTo(2);
        assertThat(during.fired()).isZero();
        assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-1");
        assertThat(messages(bench, conversation)).filteredOn(message -> message.startsWith("EXTERNAL:"))
            .containsExactly("EXTERNAL:Please also check the tax.");
        assertThat(triggerRow(bench, trigger).get("waiting_reason")).isEqualTo("COMPANY_STOPPED");
        Map<String, Object> polled = pollRow(bench, poll);
        assertThat(polled.get("status")).isEqualTo("ACTIVE");
        assertThat(polled.get("failures")).isEqualTo(0);
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.fired")).hasSize(1);

        // Run now during the stop: the gate answers 409 TRIGGER_PAUSED before anything is read.
        ApiResponse run = bench.alice().post(bench.path("/triggers/" + trigger + "/run"), Map.of("subjectKey",
                "SHOP-2"));
        assertThat(run.problemCode()).as("%s", run).isEqualTo("TRIGGER_PAUSED");
        assertThat(run.body()).contains("COMPANY_STOPPED");
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(jira.issue("SHOP-2")))
            .withHeader("Authorization", equalTo("Bearer " + jira.aliceToken())));
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.run")).isEmpty();

        // The lift arms the trigger at the lift and clears its wait; what the poll read during the stop is no edge.
        Instant beforeLift = Instant.now().minusSeconds(1);
        ApiResponse lifted = bench.owner().delete(bench.path("/runs-stop"));
        assertThat(lifted.status()).as("%s", lifted).isEqualTo(200);
        Map<String, Object> armed = triggerRow(bench, trigger);
        assertThat(armed.get("waiting_reason")).isNull();
        assertThat(instant(armed.get("armed_at"))).isAfter(beforeLift);
        assertThat(pass(bench, poll).handled()).isZero();
        assertThat(firings(bench, trigger)).hasSize(1);

        // Later (its arming moved back), a new assignment fires; SHOP-2, read during the stop, still does not.
        armedOnly(bench, trigger, Duration.ofHours(1));
        jira.stub().searchFor(jira.aliceToken(), List.of(
                issue("10001", "SHOP-1", "alice", Duration.ofMinutes(20), Duration.ofMinutes(5), List.of(
                        JiraStub.comment("20002", "bob", "Please also check the tax.",
                                jiraAgo(Duration.ofMinutes(5))))),
                issue("10002", "SHOP-2", "alice", Duration.ofMinutes(4), Duration.ofMinutes(4), List.of()),
                issue("10003", "SHOP-3", "alice", Duration.ofMinutes(3), Duration.ofMinutes(3), List.of())));
        pass(bench, poll);
        assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-1", "ASSIGNED:STARTED:SHOP-3");
    }

    @Test
    void aPausedSystemsPollCallsNothingAndItsResumeMovesTheWindowAndWakesIt() throws Exception {
        Bench bench = bench();
        Jira jira = jira(bench);
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Mine"), jira.systemId(),
                jira.aliceAccount(), bench.alicePrincipal(), List.of(event("ASSIGNED")), "ISSUE", "SHOP", null));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        Object since = pollRow(bench, poll).get("since");
        Map<String, Object> during = issue("10001", "SHOP-1", "alice", Duration.ofMinutes(20), Duration.ofMinutes(20),
                List.of());
        jira.stub().searchFor(jira.aliceToken(), List.of(during));
        jira.stub().issueFor(jira.aliceToken(), during);

        ApiResponse paused = bench.owner().post(bench.path("/systems/" + jira.systemId() + "/pause"),
                Map.of("reason", "The token leaked"));
        assertThat(paused.status()).as("%s", paused).isEqualTo(200);
        assertThat(triggerRow(bench, trigger).get("paused_reason")).isEqualTo("SYSTEM_PAUSED");

        // Its poll calls nothing and waits: no failure, no backoff, its window held.
        TriggerPolls.Outcome outcome = pass(bench, poll);
        assertThat(outcome.status()).isEqualTo("WAITING");
        assertThat(outcome.error()).contains("paused");
        Map<String, Object> waiting = pollRow(bench, poll);
        assertThat(waiting.get("status")).isEqualTo("WAITING");
        assertThat(waiting.get("failures")).isEqualTo(0);
        assertThat(waiting.get("backoff_until")).isNull();
        assertThat(waiting.get("failing_since")).isNull();
        assertThat(waiting.get("since")).isEqualTo(since);
        // Run now and the editor's search read nothing either.
        ApiResponse run = bench.alice().post(bench.path("/triggers/" + trigger + "/run"), Map.of("subjectKey",
                "SHOP-1"));
        assertThat(run.problemCode()).as("%s", run).isEqualTo("TRIGGER_PAUSED");
        assertThat(run.body()).contains("SYSTEM_PAUSED");
        assertThat(json(bench.alice().get(bench.path("/triggers/" + trigger + "/subjects?query=checkout")))
            .get("items")).isEmpty();
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(jira.search()))
            .withHeader("Authorization", equalTo("Bearer " + jira.aliceToken())));
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(jira.issue("SHOP-1")))
            .withHeader("Authorization", equalTo("Bearer " + jira.aliceToken())));

        // Resumed without its triggers: the poll goes on after the commit and, with no active trigger, idles.
        ApiResponse alone = bench.owner().post(bench.path("/systems/" + jira.systemId() + "/resume"),
                Map.of("resumeTriggers", false));
        assertThat(alone.status()).as("%s", alone).isEqualTo(200);
        await().atMost(WAIT).untilAsserted(() -> assertThat(pollRow(bench, poll).get("status")).isEqualTo("ACTIVE"));
        assertThat(pass(bench, poll).status()).isEqualTo("IDLE");
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(jira.search()))
            .withHeader("Authorization", equalTo("Bearer " + jira.aliceToken())));

        // Paused (its window an hour back) and resumed with its triggers: the window moves to the resume (less the
        // overlap), the poll goes on.
        json(bench.owner().post(bench.path("/systems/" + jira.systemId() + "/pause"), Map.of("reason", "Again")));
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger_poll SET since = ? WHERE id = ?",
                    OffsetDateTime.now(ZoneOffset.UTC).minusHours(1), poll);
            sql.commit();
        }
        Instant beforeResume = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ApiResponse resumed = bench.owner().post(bench.path("/systems/" + jira.systemId() + "/resume"), Map.of());
        assertThat(resumed.json().get("triggersResumed").asInt()).as("%s", resumed).isEqualTo(1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(pollRow(bench, poll).get("status")).isEqualTo("ACTIVE"));
        assertThat(instant(pollRow(bench, poll).get("since")))
            .isAfterOrEqualTo(beforeResume.minus(Duration.ofMinutes(2)))
            .isBefore(Instant.now().minus(Duration.ofMinutes(1)));

        // SHOP-1, assigned during the pause, is history; a later assignment fires (its time after the arming).
        armedOnly(bench, trigger, Duration.ofHours(1));
        jira.stub().searchFor(jira.aliceToken(), List.of(during,
                issue("10002", "SHOP-2", "alice", Duration.ofSeconds(100), Duration.ofSeconds(100), List.of())));
        TriggerPolls.Outcome after = pass(bench, poll);
        assertThat(after.error()).isNull();
        assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-2");
    }

    @Test
    void aScheduleDueWhileTheCompanysRunsAreStoppedRecordsNothingAndMovesOn() throws Exception {
        Bench bench = bench();
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Monthly"));
        body.put("source", "SCHEDULE");
        body.put("schedule", Map.of("cron", "0 9 1 * *", "zone", "Europe/Prague", "overlap", "SKIP", "catchUp",
                "ONE"));
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", bench.agentVersion());
        body.put("instruction", "Write the monthly report.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.alicePrincipal().toString());
        UUID trigger = create(bench.alice(), bench, body);

        json(bench.owner().post(bench.path("/runs-stop"), Map.of("reason", "Incident")));
        dueAgo(bench, trigger, Duration.ofMinutes(5));
        assertThat(fire(bench, trigger)).isNull();
        assertThat(firings(bench, trigger)).isEmpty();
        assertThat(instant(triggerRow(bench, trigger).get("next_run_at"))).isAfter(Instant.now());
        // Run now waits for the lift too.
        assertThat(bench.alice().post(bench.path("/triggers/" + trigger + "/run"), Map.of()).problemCode())
            .isEqualTo("TRIGGER_PAUSED");

        // After the lift its catch-up ONE does not bring the stop's firing back; its next due firing fires.
        json(bench.owner().delete(bench.path("/runs-stop")));
        assertThat(fire(bench, trigger)).isNull();
        dueAgo(bench, trigger, Duration.ofMinutes(1));
        assertThat(fire(bench, trigger)).isEqualTo(FiringOutcome.STARTED);
        assertThat(firings(bench, trigger)).containsExactly("SCHEDULE:STARTED");
    }

    static void dueAgo(Bench bench, UUID triggerId, Duration ago) throws Exception {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger SET next_run_at = ? WHERE id = ?",
                    OffsetDateTime.now(ZoneOffset.UTC).minus(ago), triggerId);
            sql.commit();
        }
    }

    FiringOutcome fire(Bench bench, UUID triggerId) {
        return SystemContext.callInCompany(bench.id(), () -> schedules.fireOne(bench.id(), triggerId));
    }
}
