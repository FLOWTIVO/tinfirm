package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.JiraCloudStub;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * Polling Jira Data Center for triggers (the package 4 contract, §5.4 to §5.6, §9's polling, firing and V4 rows): a
 * poll reads with its source account alone and assigned means that account (T8, V4); a second pass finds no edge
 * (T10); Alice's own comment fires and Tinfirm's does not (T9); a {@code 401} waits the poll and pauses the trigger
 * (§11 point 9); the rate skips and says so once an hour (§11 point 15); a paused trigger fires nothing for what
 * happened before its resume (T16); a rate limit and failures back off with the window held; another company's poll is
 * not the caller's; run now and the editor's search read with the source account. TEST FIXTURE ONLY: tokens are test
 * values.
 */
class JiraPollingIntegrationTest extends PollingTestSupport {

    @Autowired
    SystemTransactions transactions;

    @Autowired
    SystemWriteRecorder writes;

    record Jira(JiraStub.Instance stub, UUID systemId, UUID aliceAccount, String aliceToken) {
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
        return JiraStub.issue(id, key, "Checkout fails " + key, "The *EUR* total is wrong.", assignee, List.of(),
                "To Do", jiraAgo(createdAgo), jiraAgo(updatedAgo), comments);
    }

    @Test
    void v4AlicesAssignedToMeTriggerFiresWithHerAccountOnHerAssignmentsAndHerOwnComments() throws Exception {
        Bench bench = bench();
        Jira jira = jira(bench);
        // V4: Alice's own trigger, without a team: "when an issue is assigned to me, prepare it".
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Mine"), jira.systemId(),
                jira.aliceAccount(), bench.alicePrincipal(), List.of(event("ASSIGNED"), event("COMMENTED")), "ISSUE",
                "SHOP", null));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        jira.stub().searchFor(jira.aliceToken(), List.of(
                issue("10001", "SHOP-1", "alice", Duration.ofMinutes(20), Duration.ofMinutes(20), List.of()),
                issue("10002", "SHOP-2", "bob", Duration.ofMinutes(19), Duration.ofMinutes(19), List.of())));

        // The system's poll interval, as its admins set it, is the time to the next pass.
        assertThat(bench.owner().patch(bench.path("/systems/" + jira.systemId()), Map.of("pollIntervalSeconds", 600))
            .status()).isEqualTo(200);
        Instant before = Instant.now();
        TriggerPolls.Outcome first = pass(bench, poll);
        assertThat(first.error()).isNull();
        assertThat(first.handled()).isEqualTo(2);
        assertThat(((java.sql.Timestamp) pollRow(bench, poll).get("next_poll_at")).toInstant())
            .isAfter(before.plusSeconds(590))
            .isBefore(Instant.now().plusSeconds(610));
        // Only the issue assigned to Alice fires; it started a conversation of the trigger, paid by Alice.
        assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-1");
        JiraStub.server().verify(getRequestedFor(urlPathEqualTo(jira.stub().url("/rest/api/2/search")))
            .withHeader("Authorization", equalTo("Bearer " + jira.aliceToken())));
        Map<String, Object> firing = firingRows(bench, trigger).getFirst();
        assertThat((String) firing.get("request")).contains("SHOP-1", "was assigned to alice");
        assertThat((String) firing.get("subject_summary")).contains("Status: To Do", "Type: Story",
                "Assignees: Display alice", "The **EUR** total is wrong.");
        List<Map<String, Object>> started = conversations(bench, trigger);
        assertThat(started).hasSize(1);
        UUID conversation = (UUID) started.getFirst().get("id");
        JsonNode view = json(bench.alice().get(bench.path("/conversations/" + conversation)));
        assertThat(view.get("place").asString()).isEqualTo("TRACKER_ISSUE");
        assertThat(view.get("payer").get("principalId").asString()).isEqualTo(bench.alicePrincipal().toString());
        // Without a team, Bob gets 404 (T15).
        assertThat(bench.bob().get(bench.path("/conversations/" + conversation)).status()).isEqualTo(404);

        // A second pass reads the same: no edge, no firing (T10).
        TriggerPolls.Outcome again = pass(bench, poll);
        assertThat(again.handled()).isZero();
        assertThat(firings(bench, trigger)).hasSize(1);

        // Two comments through Alice's account: one Tinfirm wrote (recorded), one Alice wrote herself, after the
        // last read.
        readAgo(bench, poll, Duration.ofMinutes(15));
        transactions.runInCompany(bench.id(), () -> writes.record(bench.id(), jira.systemId(), "SHOP-1", "COMMENT",
                "20001", null, List.of(), List.of(), conversation, null));
        jira.stub().searchFor(jira.aliceToken(), List.of(
                issue("10001", "SHOP-1", "alice", Duration.ofMinutes(20), Duration.ofMinutes(5), List.of(
                        JiraStub.comment("20001", "alice", "Analyst (Tinfirm agent): looked at it",
                                jiraAgo(Duration.ofMinutes(6))),
                        JiraStub.comment("20002", "alice", "Please also check the tax.",
                                jiraAgo(Duration.ofMinutes(5))))),
                issue("10002", "SHOP-2", "bob", Duration.ofMinutes(19), Duration.ofMinutes(19), List.of())));
        pass(bench, poll);
        assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-1", "COMMENTED:CONTINUED:SHOP-1");
        assertThat(messages(bench, conversation)).containsExactly("EXTERNAL:Please also check the tax.");
        assertThat(conversations(bench, trigger)).hasSize(1);
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.fired")).hasSize(2);
        assertThat(auditEvents(bench.owner(), bench.company(), "secret.accessed")).extracting(event -> event
            .get("details").get("purpose").asString()).contains("trigger-poll:" + poll);
    }

    @Test
    void aRefusedTokenWaitsThePollAndPausesTheTrigger() throws Exception {
        Bench bench = bench();
        Jira jira = jira(bench);
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Refused"), jira.systemId(),
                jira.aliceAccount(), bench.alicePrincipal(), List.of(event("ASSIGNED")), "ISSUE", "SHOP", null));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        jira.stub().searchAnswers(jira.aliceToken(), JiraStub.json(401, Map.of("errorMessages",
                List.of("Unauthorized"))));

        TriggerPolls.Outcome outcome = pass(bench, poll);
        assertThat(outcome.status()).isEqualTo("WAITING");
        assertThat(pollRow(bench, poll).get("status")).isEqualTo("WAITING");
        // Accounts.refused: the account needs a reconnect, and the gate pauses the whole trigger (§11, point 9).
        await().atMost(WAIT).untilAsserted(() -> {
            JsonNode view = json(bench.alice().get(bench.path("/triggers/" + trigger)));
            assertThat(view.get("status").asString()).isEqualTo("PAUSED");
            assertThat(view.get("pausedReason").asString()).isEqualTo("ACCOUNT_BROKEN");
        });
        assertThat(firings(bench, trigger)).isEmpty();
        assertThat(auditEvents(bench.owner(), bench.company(), "account.refused")).isNotEmpty();
    }

    @Test
    void theRateSkipsAndSaysSoOnceAnHourAndAPausedTriggerFiresNothingFromBeforeItsResume() throws Exception {
        Bench bench = bench();
        Jira jira = jira(bench);
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Busy"), jira.systemId(),
                jira.aliceAccount(), bench.alicePrincipal(), List.of(event("ASSIGNED")), "ISSUE", "SHOP", null));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger SET max_firings_per_hour = 1 WHERE id = ?", trigger);
            sql.commit();
        }
        jira.stub().searchFor(jira.aliceToken(), List.of(
                issue("10001", "SHOP-1", "alice", Duration.ofMinutes(30), Duration.ofMinutes(30), List.of()),
                issue("10002", "SHOP-2", "alice", Duration.ofMinutes(29), Duration.ofMinutes(29), List.of()),
                issue("10003", "SHOP-3", "alice", Duration.ofMinutes(28), Duration.ofMinutes(28), List.of())));

        pass(bench, poll);
        // One firing an hour; the rest skipped, recorded once (one inbox item an hour), all counted.
        assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-1",
                "ASSIGNED:SKIPPED_RATE:SHOP-2");
        assertThat(((Number) triggerRow(bench, trigger).get("rate_skipped_count")).intValue()).isEqualTo(2);
        JsonNode inbox = json(bench.alice().get(bench.path("/inbox")));
        assertThat(inbox.toString()).contains(trigger.toString());

        // Paused, then a change, then resumed: the change before the resume fires nothing (T16).
        json(bench.alice().post(bench.path("/triggers/" + trigger + "/pause"), Map.of("stopRunning", false)));
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger SET max_firings_per_hour = 30 WHERE id = ?", trigger);
            sql.commit();
        }
        jira.stub().searchFor(jira.aliceToken(), List.of(
                issue("10004", "SHOP-4", "alice", Duration.ofMinutes(10), Duration.ofMinutes(10), List.of())));
        json(bench.alice().post(bench.path("/triggers/" + trigger + "/resume"), Map.of()));
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger_poll SET since = ? WHERE id = ?",
                    OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(1), poll);
            sql.commit();
        }
        pass(bench, poll);
        assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-1",
                "ASSIGNED:SKIPPED_RATE:SHOP-2");
    }

    @Test
    void aRateLimitAndFailuresBackOffWithTheWindowHeld() throws Exception {
        Bench bench = bench();
        Jira jira = jira(bench);
        UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Limited"), jira.systemId(),
                jira.aliceAccount(), bench.alicePrincipal(), List.of(event("ASSIGNED")), "ISSUE", "SHOP", null));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        Object since = pollRow(bench, poll).get("since");
        jira.stub().searchAnswers(jira.aliceToken(), JiraStub.json(429, Map.of("errorMessages", List.of("Slow down")))
            .withHeader("Retry-After", "120"));

        Instant before = Instant.now();
        TriggerPolls.Outcome limited = pass(bench, poll);
        assertThat(limited.error()).contains("rate limit");
        Map<String, Object> row = pollRow(bench, poll);
        assertThat(((java.sql.Timestamp) row.get("backoff_until")).toInstant()).isAfter(before.plusSeconds(100))
            .isBefore(before.plusSeconds(200));
        assertThat(row.get("since")).isEqualTo(since);
        assertThat(row.get("lease_until")).isNull();

        jira.stub().searchAnswers(jira.aliceToken(), JiraStub.json(503, Map.of("errorMessages", List.of("Down"))));
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger_poll SET backoff_until = NULL, failing_since = ? WHERE id = ?",
                    OffsetDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), poll);
            sql.commit();
        }
        pass(bench, poll);
        Map<String, Object> failing = pollRow(bench, poll);
        assertThat(failing.get("failures")).isEqualTo(1);
        assertThat(failing.get("since")).isEqualTo(since);
        // An hour of failures: its triggers wait SYSTEM_UNREACHABLE; a pass that reads again repairs it.
        assertThat(triggerRow(bench, trigger).get("waiting_reason")).isEqualTo("SYSTEM_UNREACHABLE");
        jira.stub().searchFor(jira.aliceToken(), List.of());
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger_poll SET backoff_until = NULL WHERE id = ?", poll);
            sql.commit();
        }
        assertThat(pass(bench, poll).error()).isNull();
        assertThat(triggerRow(bench, trigger).get("waiting_reason")).isNull();
        assertThat(pollRow(bench, poll).get("failures")).isEqualTo(0);
    }

    @Test
    void aPollIsItsCompanysOwn() throws Exception {
        Bench a = bench();
        Bench b = bench();
        Jira jiraA = jira(a);
        Jira jiraB = jira(b);
        UUID triggerA = create(a.alice(), a, tracker(a, unique("A"), jiraA.systemId(), jiraA.aliceAccount(),
                a.alicePrincipal(), List.of(event("ASSIGNED")), "ISSUE", "SHOP", null));
        UUID triggerB = create(b.alice(), b, tracker(b, unique("B"), jiraB.systemId(), jiraB.aliceAccount(),
                b.alicePrincipal(), List.of(event("ASSIGNED")), "ISSUE", "SHOP", null));
        UUID pollA = pollOf(a, triggerA);
        UUID pollB = pollOf(b, triggerB);
        armedAgo(a, triggerA, Duration.ofHours(1));
        armedAgo(b, triggerB, Duration.ofHours(1));
        jiraA.stub().searchFor(jiraA.aliceToken(), List.of(issue("10001", "SHOP-1", "alice", Duration.ofMinutes(10),
                Duration.ofMinutes(10), List.of())));
        jiraB.stub().searchFor(jiraB.aliceToken(), List.of(issue("10001", "SHOP-1", "alice", Duration.ofMinutes(10),
                Duration.ofMinutes(10), List.of())));

        // Company A's context neither claims nor sees company B's poll.
        assertThat(com.flowtivo.tinfirm.shared.security.SystemContext.callInCompany(a.id(), () -> polls.poll(a.id(),
                pollB, true))).isNull();
        pass(a, pollA);
        assertThat(firings(a, triggerA)).containsExactly("ASSIGNED:STARTED:SHOP-1");
        assertThat(firings(b, triggerB)).isEmpty();
        try (TenantSql sql = TenantSql.in(a.id())) {
            assertThat(sql.count("SELECT count(*) FROM trigger_subject WHERE poll_id = ?", pollB)).isZero();
            assertThat(sql.count("SELECT count(*) FROM trigger_firing WHERE trigger_id = ?", triggerB)).isZero();
        }
        pass(b, pollB);
        assertThat(firings(b, triggerB)).containsExactly("ASSIGNED:STARTED:SHOP-1");
        // The firings API of one company answers the other's trigger 404.
        assertThat(a.owner().get(a.path("/triggers/" + triggerB + "/firings")).status()).isEqualTo(404);
    }

    @Test
    void runNowReadsTheSubjectWithTheSourceAccountAndChecksTheConditions() throws Exception {
        Bench bench = bench();
        Jira jira = jira(bench);
        Map<String, Object> body = tracker(bench, unique("Labelled"), jira.systemId(), jira.aliceAccount(),
                bench.alicePrincipal(), List.of(event("LABEL_ADDED", "ai")), "ISSUE", "SHOP", bench.team());
        body.put("conditions", List.of(Map.of("notStatuses", List.of("Done"))));
        UUID trigger = create(bench.alice(), bench, body);
        Map<String, Object> open = JiraStub.issue("10001", "SHOP-1", "Checkout", "Broken", "alice", List.of("ai"),
                "To Do", jiraAgo(Duration.ofDays(3)), jiraAgo(Duration.ofDays(1)), List.of());
        Map<String, Object> done = JiraStub.issue("10002", "SHOP-2", "Old", "Fixed", null, List.of(), "Done",
                jiraAgo(Duration.ofDays(3)), jiraAgo(Duration.ofDays(1)), List.of());
        jira.stub().issueFor(jira.aliceToken(), open);
        jira.stub().issueFor(jira.aliceToken(), done);

        // A subject the account cannot read; one whose conditions fail; one outside the scope.
        assertThat(bench.bob().post(bench.path("/triggers/" + trigger + "/run"), Map.of("subjectKey", "SHOP-404"))
            .problemCode()).isEqualTo("SUBJECT_NOT_VISIBLE");
        ApiResponse failing = bench.bob().post(bench.path("/triggers/" + trigger + "/run"),
                Map.of("subjectKey", "SHOP-2"));
        assertThat(failing.problemCode()).as("%s", failing).isEqualTo("TRIGGER_CONDITIONS_FAIL");
        assertThat(failing.body()).contains("the status is Done");
        // The team runs it now: its first event, manual, never once, depth 0.
        ApiResponse run = bench.bob().post(bench.path("/triggers/" + trigger + "/run"), Map.of("subjectKey",
                "SHOP-1"));
        assertThat(run.status()).as("%s", run).isEqualTo(200);
        assertThat(run.json().get("outcome").asString()).isEqualTo("STARTED");
        assertThat(run.json().get("event").asString()).isEqualTo("LABEL_ADDED");
        assertThat(run.json().get("manualBy").asString()).isEqualTo(bench.bobUser().toString());
        assertThat(firingRows(bench, trigger).getFirst().get("once")).isEqualTo(false);
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.run")).hasSize(1);
        assertThat(auditEvents(bench.owner(), bench.company(), "secret.accessed")).extracting(event -> event
            .get("details").get("purpose").asString()).contains("trigger-run:" + trigger);
        // A second run now on the subject continues its open conversation.
        ApiResponse second = bench.bob().post(bench.path("/triggers/" + trigger + "/run"), Map.of("subjectKey",
                "SHOP-1"));
        assertThat(second.json().get("outcome").asString()).isEqualTo("CONTINUED");
        assertThat(second.json().get("conversationId").asString())
            .isEqualTo(run.json().get("conversationId").asString());

        // The editor's search reads with the source account in the trigger's scope.
        Map<String, Object> page = new java.util.LinkedHashMap<>();
        page.put("startAt", 0);
        page.put("maxResults", 20);
        page.put("total", 1);
        page.put("issues", List.of(open));
        JiraStub.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(jira.stub()
            .url("/rest/api/2/search")))
            .withHeader("Authorization", equalTo("Bearer " + jira.aliceToken()))
            .withQueryParam("jql", equalTo("project = \"SHOP\" AND text ~ \"checkout\" ORDER BY updated DESC"))
            .willReturn(JiraStub.json(200, page)));
        JsonNode subjects = json(bench.bob().get(bench.path("/triggers/" + trigger + "/subjects?query=checkout")));
        assertThat(subjects.get("items")).hasSize(1);
        assertThat(subjects.get("items").get(0).get("key").asString()).isEqualTo("SHOP-1");
        assertThat(subjects.get("items").get(0).get("status").asString()).isEqualTo("To Do");
        assertThat(bench.carol().get(bench.path("/triggers/" + trigger + "/subjects?query=checkout")).status())
            .isEqualTo(404);
    }

    @Test
    void jiraCloudPagesByItsCursorAndAMentionNodeOfTheAccountFires() throws Exception {
        com.github.tomakehurst.wiremock.WireMockServer cloud = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        cloud.start();
        try {
            Bench bench = bench();
            String credential = "alice@example.test:" + unique("api-token");
            String accountId = "5b10ac8d82e05b22cc7d4ef5";
            cloud.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/rest/api/3/myself"))
                .withHeader("Authorization", equalTo(JiraCloudStub.basic(credential)))
                .willReturn(JiraStub.json(200, Map.of("accountId", accountId, "displayName", "Alice", "timeZone",
                        "Europe/Prague", "active", true))));
            UUID system = createSystem(bench.owner(), bench.company(), unique("Cloud"), "JIRA_CLOUD",
                    "http://localhost:" + cloud.port(), false);
            ApiResponse connected = bench.alice().put(bench.path("/me/accounts/" + system + "/token"),
                    Map.of("token", credential));
            assertThat(connected.status()).as("%s", connected).isEqualTo(200);
            UUID account = UUID.fromString(connected.json().get("id").asString());
            UUID trigger = create(bench.alice(), bench, tracker(bench, unique("Cloud"), system, account,
                    bench.alicePrincipal(), List.of(event("ASSIGNED"), event("MENTIONED")), "ISSUE", "SHOP", null));
            UUID poll = pollOf(bench, trigger);
            armedAgo(bench, trigger, Duration.ofHours(1));
            Map<String, Object> first = new java.util.LinkedHashMap<>();
            first.put("issues", List.of(JiraCloudStub.issue("20001", "SHOP-1", "New", null, accountId,
                    "557058:bob", List.of(), "To Do", "Task", jiraAgo(Duration.ofMinutes(20)),
                    jiraAgo(Duration.ofMinutes(20)), null, List.of())));
            first.put("nextPageToken", "tok-2");
            first.put("isLast", false);
            Map<String, Object> second = new java.util.LinkedHashMap<>();
            second.put("issues", List.of(JiraCloudStub.issue("20002", "SHOP-2", "Old", null, null, "557058:bob",
                    List.of(), "To Do", "Task", jiraAgo(Duration.ofDays(3)), jiraAgo(Duration.ofMinutes(10)), null,
                    List.of(JiraCloudStub.comment("30001", "557058:bob", JiraCloudStub.doc(List.of(JiraCloudStub
                        .paragraph(List.of(JiraCloudStub.mention(accountId, "Alice"),
                                JiraCloudStub.textNode(" please look"))))), jiraAgo(Duration.ofMinutes(10)))))));
            second.put("isLast", true);
            String search = "/rest/api/3/search/jql";
            cloud.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(search))
                .withQueryParam("nextPageToken", com.github.tomakehurst.wiremock.client.WireMock.absent())
                .willReturn(JiraStub.json(200, first)));
            cloud.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(search))
                .withQueryParam("nextPageToken", equalTo("tok-2"))
                .willReturn(JiraStub.json(200, second)));

            TriggerPolls.Outcome outcome = pass(bench, poll);
            assertThat(outcome.error()).isNull();
            assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-1",
                    "MENTIONED:STARTED:SHOP-2");
            cloud.verify(getRequestedFor(urlPathEqualTo(search))
                .withQueryParam("nextPageToken", equalTo("tok-2"))
                .withHeader("Authorization", equalTo(JiraCloudStub.basic(credential))));
            assertThat((String) firingRows(bench, trigger).getLast().get("request")).contains("please look")
                .contains("mentioned Alice");
        }
        finally {
            cloud.stop();
        }
    }

    @Autowired
    SubjectHandling handling;

    @Autowired
    com.flowtivo.tinfirm.trigger.Triggers triggerReads;

    @Test
    void twoHandlingsOfOneChangeFireItOnceEachTriggerHasItsConversationAndAClosedSubjectEndsThem()
            throws Exception {
        Bench bench = bench();
        Jira jira = jira(bench);
        UUID assigned = create(bench.alice(), bench, tracker(bench, unique("Assigned"), jira.systemId(),
                jira.aliceAccount(), bench.alicePrincipal(), List.of(event("ASSIGNED")), "ISSUE", "SHOP", null));
        UUID labelled = create(bench.alice(), bench, tracker(bench, unique("Labelled"), jira.systemId(),
                jira.aliceAccount(), bench.alicePrincipal(), List.of(event("LABEL_ADDED", "ai")), "ISSUE", "SHOP",
                null));
        UUID poll = pollOf(bench, assigned);
        armedAgo(bench, assigned, Duration.ofHours(1));
        armedAgo(bench, labelled, Duration.ofHours(1));

        // Two passes handle the same read at once (a lease that ran out): the snapshot's lock lets one fire (T10).
        Instant created = Instant.now().minus(Duration.ofMinutes(20));
        com.flowtivo.tinfirm.system.SystemPolls.Subject subject = new com.flowtivo.tinfirm.system.SystemPolls.Subject(
                "10001", "SHOP-1", com.flowtivo.tinfirm.system.SystemPolls.SubjectKind.ISSUE, "SHOP", null,
                "Checkout", "Broken", "To Do", "Story", true, List.of(new com.flowtivo.tinfirm.system.SystemPolls
                    .User(null, "alice", "Alice")), List.of("ai"), created, created, List.of(), 0, null, null);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<SubjectHandling.Handled>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return com.flowtivo.tinfirm.shared.security.SystemContext.callInCompany(bench.id(), () -> {
                        List<com.flowtivo.tinfirm.trigger.TriggerInfo> serving = transactions.inCompany(bench.id(),
                                () -> triggerReads.onSourceAccount(bench.id(), jira.systemId(), jira.aliceAccount()));
                        SubjectHandling.Pass pass = new SubjectHandling.Pass(bench.id(), poll, jira.systemId(),
                                serving, new EventDetector.Identity("key-alice", "alice"), "alice",
                                Instant.now().minus(Duration.ofHours(1)), Instant.now(), Instant.now(),
                                Duration.ofHours(24), Duration.ofMinutes(2));
                        return transactions.inCompany(bench.id(), () -> handling.handle(pass, subject));
                    });
                }));
            }
            start.countDown();
            int fired = 0;
            for (java.util.concurrent.Future<SubjectHandling.Handled> result : results) {
                fired += result.get(60, java.util.concurrent.TimeUnit.SECONDS).fired();
            }
            assertThat(fired).isEqualTo(2);
        }
        finally {
            pool.shutdownNow();
        }
        // Each trigger fired once, with a conversation of its own on the issue.
        assertThat(firings(bench, assigned)).containsExactly("ASSIGNED:STARTED:SHOP-1");
        assertThat(firings(bench, labelled)).containsExactly("LABEL_ADDED:STARTED:SHOP-1");
        UUID first = (UUID) conversations(bench, assigned).getFirst().get("id");
        UUID second = (UUID) conversations(bench, labelled).getFirst().get("id");
        assertThat(first).isNotEqualTo(second);

        // The issue is resolved: the conversations the close did not fire end SUBJECT_CLOSED.
        Map<String, Object> resolved = JiraStub.issue("10001", "SHOP-1", "Checkout", "Broken", "alice", List.of("ai"),
                "Done", jiraAgo(Duration.ofMinutes(20)), jiraAgo(Duration.ofMinutes(5)), List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) resolved.get("fields");
        fields.put("resolutiondate", jiraAgo(Duration.ofMinutes(5)));
        jira.stub().searchFor(jira.aliceToken(), List.of(resolved));
        pass(bench, poll);
        assertThat(conversations(bench, assigned).getFirst().get("ended_reason")).isEqualTo("SUBJECT_CLOSED");
        assertThat(conversations(bench, labelled).getFirst().get("ended_reason")).isEqualTo("SUBJECT_CLOSED");
    }
}
