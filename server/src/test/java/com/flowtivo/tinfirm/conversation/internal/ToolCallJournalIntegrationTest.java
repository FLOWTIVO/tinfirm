package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.system.SystemTools;

/**
 * The call journal (package R, §3.2): a run's tool call is claimed in the database before it runs, so that a request
 * sent again, on this replica or another and with nothing of it in memory, gets the stored result and never a second
 * call; a request sent again while its call runs waits for it; other arguments under the same request id are
 * {@code INVALID_REQUEST}; and the result is cleared when the run ends, its row deleted after the retention. A call
 * whose system is paused after its check never runs and its row ends {@code DONE} with the refusal; a record that
 * finds its row settled meanwhile answers the row's answer; a call claimed after its run ended is
 * {@code RUN_NOT_ACTIVE}, and one whose run ends while it runs keeps no answer; a trigger's run's calls are journaled as
 * a person's. Arguments larger than 64 KiB as JSON are {@code INVALID_REQUEST} before the journal, never a call (the
 * security review of 2026-10-27, D3). A second replica is a second {@link ToolCalls} over the same database
 * ({@link #replica}). TEST FIXTURE ONLY: tokens are test values.
 */
class ToolCallJournalIntegrationTest extends ToolJournalBench {

    @Test
    void aRequestSentAgainGetsTheStoredResultOnAnyReplicaWithoutASecondCall() {
        Bench bench = bench();
        connectOwnJira(bench, "test-journal-again", "joan");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        stubIssue(bench, "test-journal-again", "SHOP-21", "Journal", 0);
        UUID requestId = UUID.randomUUID();

        RunnerMessages.RunRequestAck first = call(conversation, requestId, handle + "_get", Map.of("key", "SHOP-21"));

        assertThat(outcome(first)).isEqualTo("DONE");
        assertThat(resultText(first)).contains("SHOP-21");
        Map<String, Object> row = journalRow(conversation, requestId);
        assertThat(row).containsEntry("state", "DONE").containsEntry("attempt", 1).containsEntry("replay", "SAFE")
            .containsEntry("effect", "READ").containsEntry("tool", "get").containsEntry("name", handle + "_get");
        assertThat(String.valueOf(row.get("result"))).contains("SHOP-21");

        // Sent again on this replica (nothing of it is kept in memory), and on another replica: the stored result.
        assertThat(call(conversation, requestId, handle + "_get", Map.of("key", "SHOP-21"))).isEqualTo(first);
        ToolCalls other = replica(Clock.systemUTC(), journal.toolTimeout());
        assertThat(callOn(other, conversation, requestId, handle + "_get", Map.of("key", "SHOP-21")))
            .isEqualTo(first);
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-21"))));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).hasSize(1);

        // Other arguments under the same request id: never a call.
        RunnerMessages.RunRequestAck other2 = callOn(other, conversation, requestId, handle + "_get",
                Map.of("key", "SHOP-22"));
        assertThat(outcome(other2)).isEqualTo("INVALID_REQUEST");
        assertThat(other2.message()).contains("other arguments");
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-22"))));
        // The canonical arguments: the same object with its keys in another order is the same call.
        assertThat(ToolJournal.sha256(JSON.valueToTree(Map.of("a", 1, "b", List.of(Map.of("y", 2, "x", 1))))))
            .isEqualTo(ToolJournal.sha256(JSON.readTree("{\"b\":[{\"x\":1,\"y\":2}],\"a\":1}")));
    }

    @Test
    void aRequestSentAgainWhileItsCallRunsWaitsForItsResult() throws Exception {
        Bench bench = bench();
        connectOwnJira(bench, "test-journal-wait", "walt");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        // Jira takes its time: the request is sent again, to another replica, while the call runs.
        stubIssue(bench, "test-journal-wait", "SHOP-23", "Slow", 1500);
        UUID requestId = UUID.randomUUID();
        ToolCalls other = replica(Clock.systemUTC(), journal.toolTimeout());

        ExecutorService hosts = Executors.newFixedThreadPool(2);
        try {
            Future<RunnerMessages.RunRequestAck> first = hosts.submit(() -> call(conversation, requestId,
                    handle + "_get", Map.of("key", "SHOP-23")));
            await().atMost(WAIT).until(() -> "RUNNING".equals(journalRow(conversation, requestId).get("state")));
            Future<RunnerMessages.RunRequestAck> again = hosts.submit(() -> callOn(other, conversation, requestId,
                    handle + "_get", Map.of("key", "SHOP-23")));

            RunnerMessages.RunRequestAck answered = first.get(30, TimeUnit.SECONDS);
            assertThat(outcome(answered)).isEqualTo("DONE");
            assertThat(again.get(30, TimeUnit.SECONDS)).isEqualTo(answered);
        }
        finally {
            hosts.shutdownNow();
        }
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-23"))));
        assertThat(journalRow(conversation, requestId)).containsEntry("state", "DONE").containsEntry("attempt", 1);
    }

    @Test
    void theResultIsClearedWhenTheRunEndsAndTheRowGoesAfterTheRetention() {
        Bench bench = bench();
        connectOwnJira(bench, "test-journal-end", "erin");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        stubIssue(bench, "test-journal-end", "SHOP-25", "Ending", 0);
        UUID requestId = UUID.randomUUID();
        assertThat(outcome(call(conversation, requestId, handle + "_get", Map.of("key", "SHOP-25"))))
            .isEqualTo("DONE");
        assertThat(journalRow(conversation, requestId).get("result")).isNotNull();

        runEnds(conversation);

        Map<String, Object> ended = journalRow(conversation, requestId);
        assertThat(ended.get("result")).isNull();
        assertThat(ended.get("run_ended_at")).isNotNull();
        assertThat(ended).containsEntry("state", "DONE");
        // Kept for seven days after the run's end, then deleted by the sweep.
        sweep.sweep(Instant.now().plus(Duration.ofDays(6)));
        assertThat(journalRow(conversation, requestId)).isNotEmpty();
        sweep.sweep(Instant.now().plus(Duration.ofDays(8)));
        assertThat(journalRow(conversation, requestId)).isEmpty();
    }

    @Test
    void aCallWhoseSystemIsPausedAfterItsCheckNeverRunsAndItsRowEndsDoneWithTheRefusal() {
        Bench bench = bench();
        connectOwnJira(bench, "test-journal-paused", "paula");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        stubIssue(bench, "test-journal-paused", "SHOP-27", "Paused", 0);
        UUID requestId = UUID.randomUUID();
        // An admin pauses Jira between the call's check and its use of the binding (package 6, §5.4).
        ToolCalls pausing = replica(Clock.systemUTC(), journal.toolTimeout(), answerWithin, runnerThat(new Around() {

            @Override
            public void before() {
                pauseJira(bench);
            }
        }));

        RunnerMessages.RunRequestAck refused = callOn(pausing, conversation, requestId, handle + "_get",
                Map.of("key", "SHOP-27"));

        assertThat(outcome(refused)).isEqualTo("TOOL_DENIED");
        assertThat(refused.message()).contains("is paused by an admin");
        Map<String, Object> row = journalRow(conversation, requestId);
        assertThat(row).containsEntry("state", "DONE").containsEntry("attempt", 1);
        assertThat(String.valueOf(row.get("result"))).contains("TOOL_DENIED");
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-27"))));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).isEmpty();
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.denied")).hasSize(1);
        // Sent again: the stored refusal; the binding kept its state.
        assertThat(call(conversation, requestId, handle + "_get", Map.of("key", "SHOP-27"))).isEqualTo(refused);
        assertThat(bindingRow(conversation, bench.jiraId())).containsEntry("state", "BOUND");
    }

    @Test
    void aRecordThatFindsItsRowSettledMeanwhileAnswersTheRowsAnswer() {
        Bench bench = bench(List.of(Map.of("name", "comment", "mode", "ALLOW")));
        connectOwnJira(bench, "test-journal-settled", "seth");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        String comment = bench.jira().url("/rest/api/2/issue/SHOP-28/comment");
        JiraStub.server().stubFor(post(urlEqualTo(comment)).willReturn(JiraStub.json(201, Map.of("id", "1028"))));
        UUID requestId = UUID.randomUUID();
        // While the call runs, its row is settled elsewhere (the sweep found it lost: a replica's clock ran ahead).
        ToolCalls lagging = replica(Clock.systemUTC(), journal.toolTimeout(), answerWithin, runnerThat(new Around() {

            @Override
            public void before() {
                assertThat(SystemContext.callInCompany(bench.companyId(), () -> journal.settleLost(bench.companyId(),
                        conversation.runId(), requestId, Instant.now().plus(Duration.ofHours(1))))).isTrue();
            }
        }));

        RunnerMessages.RunRequestAck answered = callOn(lagging, conversation, requestId, handle + "_comment",
                Map.of("key", "SHOP-28", "body", "Twice?"));

        // The host hears what the journal holds, as every replica answers it; the call is on record all the same.
        assertThat(structured(answered).get("code").asString()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(journalRow(conversation, requestId)).containsEntry("state", "INTERRUPTED");
        assertThat(call(conversation, requestId, handle + "_comment", Map.of("key", "SHOP-28", "body", "Twice?")))
            .isEqualTo(answered);
        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(comment)));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).hasSize(1);
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted")).hasSize(1);
    }

    @Test
    void aCallClaimedAfterItsRunEndedIsRefusedAndOneWhoseRunEndsWhileItRunsKeepsNoAnswer() {
        Bench bench = bench();
        connectOwnJira(bench, "test-journal-ended", "enid");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        stubIssue(bench, "test-journal-ended", "SHOP-29", "Ending", 0);

        // The run ends while its call runs: the end clears the rows it finds, and the call's record stores no answer.
        UUID during = UUID.randomUUID();
        ToolCalls ending = replica(Clock.systemUTC(), journal.toolTimeout(), answerWithin, runnerThat(new Around() {

            @Override
            public void before() {
                runEnds(conversation);
            }
        }));
        assertThat(outcome(callOn(ending, conversation, during, handle + "_get", Map.of("key", "SHOP-29"))))
            .isEqualTo("DONE");
        Map<String, Object> row = journalRow(conversation, during);
        assertThat(row).containsEntry("state", "DONE");
        assertThat(row.get("result")).isNull();
        assertThat(row.get("run_ended_at")).isNotNull();

        // A request whose check passed as its run ended (it waited for the conversation the end held): the claim reads
        // the run again, and claims and calls nothing.
        UUID after = UUID.randomUUID();
        RunnerMessages.RunRequestAck refused = call(conversation, after, handle + "_get", Map.of("key", "SHOP-29"));
        assertThat(outcome(refused)).isEqualTo("RUN_NOT_ACTIVE");
        assertThat(journalRow(conversation, after)).isEmpty();
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-29"))));
    }

    @Test
    void theSweepDeletesRowsWhoseRunsEndNeverReachedThemAndDeletesInBatches() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        // A claim that raced its run's end: no run_ended_at, deleted the retention after its claim.
        UUID raced = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        update(bench.companyId(), """
                INSERT INTO tool_call (company_id, run_id, request_id, conversation_id, system_id, tool, name, effect,
                                       replay, arguments_sha256, state, attempt, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, 'get', 'jira_get', 'READ', 'SAFE', repeat('a', 64), 'DONE', 1,
                        now() - interval '8 days', now() - interval '8 days'),
                       (?, ?, ?, ?, ?, 'get', 'jira_get', 'READ', 'SAFE', repeat('b', 64), 'DONE', 1,
                        now() - interval '6 days', now() - interval '6 days')""", bench.companyId(),
                conversation.runId(), raced, conversation.id(), bench.jiraId(), bench.companyId(),
                conversation.runId(), recent, conversation.id(), bench.jiraId());
        // An ended run with more rows than one transaction deletes.
        Conversation many = conversation(bench);
        TestRuns.ended(bench.companyId(), many.runId());
        int rows = ToolJournalSweep.DELETE_BATCH * 2 + 1;
        update(bench.companyId(), """
                INSERT INTO tool_call (company_id, run_id, request_id, conversation_id, system_id, tool, name, effect,
                                       replay, arguments_sha256, state, attempt, started_at, finished_at, run_ended_at)
                SELECT ?, ?, gen_random_uuid(), ?, ?, 'get', 'jira_get', 'READ', 'SAFE', repeat('c', 64), 'DONE', 1,
                       now() - interval '9 days', now() - interval '9 days', now() - interval '8 days'
                  FROM generate_series(1, ?)""", bench.companyId(), many.runId(), many.id(), bench.jiraId(), rows);

        ToolJournalSweep.Swept swept = sweep.sweep(Instant.now());

        assertThat(swept.deleted()).isGreaterThanOrEqualTo(rows + 1);
        assertThat(journalRow(conversation, raced)).isEmpty();
        assertThat(journalRow(conversation, recent)).isNotEmpty();
        assertThat(row(bench.companyId(), "SELECT count(*) AS n FROM tool_call WHERE run_id = ?", many.runId()))
            .containsEntry("n", 0L);
    }

    @Test
    void aTriggersRunsCallsAreJournaledAsAPersonsAre() {
        Bench bench = bench();
        UUID accountId = connectOwnJira(bench, "test-journal-trigger", "trudy");
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("bot"));
        // A trigger's conversation has no person, and its runs are paid by a service identity here.
        Conversation conversation = triggerConversation(bench, accountId, bot);
        String handle = handle(bench);
        stubIssue(bench, "test-journal-trigger", "SHOP-30", "Scheduled", 0);
        UUID requestId = UUID.randomUUID();

        RunnerMessages.RunRequestAck first = call(conversation, requestId, handle + "_get", Map.of("key", "SHOP-30"));

        assertThat(outcome(first)).as("%s", first).isEqualTo("DONE");
        assertThat(resultText(first)).contains("SHOP-30");
        assertThat(journalRow(conversation, requestId)).containsEntry("state", "DONE").containsEntry("replay", "SAFE");
        ToolCalls other = replica(Clock.systemUTC(), journal.toolTimeout());
        assertThat(callOn(other, conversation, requestId, handle + "_get", Map.of("key", "SHOP-30")))
            .isEqualTo(first);
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-30"))));

        // An UNSAFE call a stopped replica left running is settled TOOL_INTERRUPTED, audited and noted, as a person's.
        UUID lost = UUID.randomUUID();
        Map<String, Object> arguments = Map.of("key", "SHOP-30", "body", "Nightly.");
        leftRunning(conversation, lost, handle, "comment", arguments, "WRITE", "UNSAFE");
        MovableClock clock = new MovableClock();
        clock.advance(journal.toolTimeout().plus(ToolJournal.RUNNING_MARGIN).plusSeconds(1));
        RunnerMessages.RunRequestAck interrupted = callOn(replica(clock, journal.toolTimeout()), conversation, lost,
                handle + "_comment", arguments);
        assertThat(structured(interrupted).get("code").asString()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(journalRow(conversation, lost)).containsEntry("state", "INTERRUPTED");
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted")).singleElement()
            .satisfies(event -> assertThat(event.get("targetId").asString()).isEqualTo(conversation.id().toString()));
        assertThat(rows(bench.companyId(), "SELECT body FROM message WHERE conversation_id = ? AND kind = 'SYSTEM'",
                conversation.id())).extracting(message -> String.valueOf(message.get("body")))
            .anySatisfy(body -> assertThat(body).contains(handle + "_comment", "was interrupted"));
    }

    @Test
    void argumentsLargerThan64KiBAreRefusedBeforeTheJournalAndNeverCalled() {
        Bench bench = bench();
        connectOwnJira(bench, "test-journal-large", "lara");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        stubIssue(bench, "test-journal-large", "SHOP-31", "Large", 0);
        UUID requestId = UUID.randomUUID();
        // {"key":"SHOP-31","note":"…"}, one byte more than 64 KiB as JSON in UTF-8.
        int around = "{\"key\":\"SHOP-31\",\"note\":\"\"}".length();
        Map<String, Object> arguments = Map.of("key", "SHOP-31", "note",
                "x".repeat(SystemTools.MAX_ARGUMENT_BYTES + 1 - around));

        RunnerMessages.RunRequestAck refused = call(conversation, requestId, handle + "_get", arguments);

        assertThat(outcome(refused)).isEqualTo("INVALID_REQUEST");
        assertThat(refused.status()).isEqualTo(RunnerMessages.RequestStatus.REJECTED);
        assertThat(refused.message()).contains("larger than 64 KiB");
        assertThat(journalRow(conversation, requestId)).isEmpty();
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-31"))));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).isEmpty();
    }

    private static void stubIssue(Bench bench, String token, String key, String summary, int delayMillis) {
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/" + key)))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(JiraStub.json(200, JiraStub.issue("1" + key.substring(key.indexOf('-') + 1), key, summary, "",
                    null, List.of(), "Open", "2026-10-01T10:00:00.000+0200", List.of())).withFixedDelay(delayMillis)));
    }
}
