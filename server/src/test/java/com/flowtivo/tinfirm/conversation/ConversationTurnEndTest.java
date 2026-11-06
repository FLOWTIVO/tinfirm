package com.flowtivo.tinfirm.conversation;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * What a turn's end starts (the package 3 contract, §5.7, §5.3, §5.8): a decision decided, or a call that waited for
 * a binding settled, while the conversation's run is still active cannot start its {@code DECIDED} or
 * {@code CONNECTED} turn then (one run at a time, I11), so the run's end starts it, once, and its {@code # Answers}
 * tells what was decided or ran. A decision decided after the run ended starts its turn at once. Driven
 * through the API, a fake runner host and the Jira stub. TEST FIXTURE ONLY: tokens are test values.
 */
class ConversationTurnEndTest extends ConversationTestBench {

    private static final Duration NONE_WITHIN = Duration.ofSeconds(2);

    @Test
    void aQuestionAnsweredWhileItsRunIsActiveStartsOneDecidedTurnAtTheRunsEnd() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Asker", "", List.<UUID[]>of(), "READ", List.of());
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "Export the report").get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));

            // The agent asks and is told to end its turn; the person answers before the run's end arrives.
            JsonNode asked = runner.requestAndAck(runId, epoch, "ASK", Map.of("question", "Which format?",
                    "options", List.of("CSV", "XLSX")));
            assertThat(asked.get("status").asString()).as("%s", asked).isEqualTo("PENDING");
            String question = asked.get("payload").get("decisionId").asString();
            ApiResponse answered = bench.member().post(bench.path("/decisions/" + question + "/decide"),
                    Map.of("option", "CSV", "answer", "With a header row."));
            assertThat(answered.status()).as("%s", answered).isEqualTo(200);
            // The run is active: nothing starts yet.
            runner.expectNone("run.assign", NONE_WITHIN);

            // The run ends: the DECIDED turn starts, once, and tells the answer.
            runner.send(Map.of("type", "run.finished", "runId", runId.toString(), "epoch", epoch, "outcome",
                    "SUCCEEDED", "lastSeq", 0, "summary", "I asked which format.", "sessionId", "session-asker",
                    "git", List.of()));
            JsonNode decided = runner.expect("run.assign");
            assertThat(decided.get("prompt").asString()).contains("# Answers", "**Q:** Which format?",
                    "**A:** **CSV**\n\nWith a header row.");
            JsonNode second = awaitConversation(bench.member(), bench, id, now -> now.get("turnCount").asInt() == 2);
            assertThat(lastTurn(second).get("turnReason").asString()).isEqualTo("DECIDED");
            assertThat(lastTurn(second).get("id").asString()).isEqualTo(decided.get("runId").asString());
            runner.expectNone("run.assign", NONE_WITHIN);

            // A question answered after its run ended starts its DECIDED turn at once, and only once.
            UUID secondRun = UUID.fromString(decided.get("runId").asString());
            long secondEpoch = decided.get("epoch").asLong();
            runner.accepted(secondRun, secondEpoch);
            JsonNode again = runner.requestAndAck(secondRun, secondEpoch, "ASK", Map.of("question", "Which region?"));
            String region = again.get("payload").get("decisionId").asString();
            runner.send(Map.of("type", "run.finished", "runId", secondRun.toString(), "epoch", secondEpoch,
                    "outcome", "SUCCEEDED", "lastSeq", 0, "summary", "I asked which region.", "sessionId",
                    "session-asker", "git", List.of()));
            awaitConversation(bench.member(), bench, id, now -> "WAITING".equals(now.path("turnStatus").asString()));
            runner.expectNone("run.assign", NONE_WITHIN);
            assertThat(bench.member().post(bench.path("/decisions/" + region + "/decide"), Map.of("answer", "EU"))
                .status()).isEqualTo(200);
            JsonNode third = runner.expect("run.assign");
            // # Answers tells each answer once: the first one was told by the second turn.
            assertThat(third.get("prompt").asString()).contains("**Q:** Which region?", "**A:** EU")
                .doesNotContain("**Q:** Which format?");
            assertThat(lastTurn(conversation(bench.member(), bench, id)).get("turnReason").asString())
                .isEqualTo("DECIDED");
            runner.expectNone("run.assign", NONE_WITHIN);
            runner.assertFramesValid();
        }
    }

    @Test
    void anApprovalDecidedWhileItsRunIsActiveStartsOneDecidedTurnAtTheRunsEnd() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(), "READ", List.of());
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "Build the shop").get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));

            // An approval posts no message of the person's that would wait for a turn: only the run's end starts it.
            JsonNode requested = runner.requestAndAck(runId, epoch, "NETWORK", Map.of("host",
                    "registry.example.test", "reason", "To fetch the packages."));
            assertThat(requested.get("status").asString()).as("%s", requested).isEqualTo("PENDING");
            String approval = requested.get("payload").get("decisionId").asString();
            assertThat(bench.member().post(bench.path("/decisions/" + approval + "/decide"), Map.of("outcome",
                    "APPROVED")).status()).isEqualTo(200);
            runner.expectNone("run.assign", NONE_WITHIN);

            runner.send(finished(runId.toString(), epoch, "SUCCEEDED", "I asked for the registry."));
            JsonNode decided = runner.expect("run.assign");
            assertThat(decided.get("prompt").asString()).contains("# Answers",
                    "Your request of the network host `registry.example.test`");
            assertThat(decided.get("allowedHosts").toString()).contains("registry.example.test");
            JsonNode second = awaitConversation(bench.member(), bench, id, now -> now.get("turnCount").asInt() == 2);
            assertThat(lastTurn(second).get("turnReason").asString()).isEqualTo("DECIDED");
            runner.expectNone("run.assign", NONE_WITHIN);
            runner.assertFramesValid();
        }
    }

    @Test
    void aCallThatWaitedForItsBindingAndRanWhileItsRunIsActiveStartsOneConnectedTurnAtTheRunsEnd() throws Exception {
        Bench bench = bench(true);
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID jiraId = createSystem(bench.admin(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        String handle = bench.admin().get(bench.path("/systems/" + jiraId)).json().get("handle").asString();
        ApiResponse created = bench.member().post(bench.path("/agents"), Map.of("name", unique("Reader"),
                "runtimes", List.of("CLAUDE_CODE"), "systems", List.of(Map.of("systemId", jiraId.toString(),
                        "tools", List.of(Map.of("name", "get", "mode", "ALLOW"))))));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID agentId = UUID.fromString(created.json().get("id").asString());
        TestSettings.nameModel(bench.id(), agentId, bench.memberPrincipal());
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "Read SHOP-1").get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));

            // Jira is not connected for the person: the call waits on the binding.
            JsonNode parked = runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_get",
                    "arguments", Map.of("key", "SHOP-1")));
            assertThat(parked.get("status").asString()).as("%s", parked).isEqualTo("PENDING");
            // The person connects Jira while the run is still active: the call runs after the commit, and settles.
            JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-1")))
                .withHeader("Authorization", equalTo("Bearer test-turn-end-token"))
                .willReturn(JiraStub.json(200, JiraStub.issue("10001", "SHOP-1", "Checkout fails", "Steps", null,
                        List.of(), "Open", "2026-10-01T10:00:00.000+0200", List.of()))));
            connectToken(bench.member(), bench.path("/me/accounts/" + jiraId), jira, "test-turn-end-token", "pia");
            await().atMost(WAIT).until(() -> settledCalls(bench, id) == 1);
            runner.expectNone("run.assign", NONE_WITHIN);

            // The run ends: the CONNECTED turn starts, once, and tells the call's result.
            runner.send(finished(runId.toString(), epoch, "SUCCEEDED", "I asked for SHOP-1."));
            JsonNode connected = runner.expect("run.assign");
            assertThat(connected.get("prompt").asString()).contains("# Answers",
                    "**Calls that waited for an account**", "`" + handle + "_get`", "SHOP-1: Checkout fails");
            JsonNode second = awaitConversation(bench.member(), bench, id, now -> now.get("turnCount").asInt() == 2);
            assertThat(lastTurn(second).get("turnReason").asString()).isEqualTo("CONNECTED");
            runner.expectNone("run.assign", NONE_WITHIN);
            runner.assertFramesValid();
        }
    }

    /** The calls of the conversation's bindings that ran or were refused. */
    private static long settledCalls(Bench bench, String conversationId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            return sql.count("""
                    SELECT count(*) FROM conversation_binding b, jsonb_array_elements(b.waiting_calls) AS call
                     WHERE b.conversation_id = ? AND call ->> 'status' IN ('DONE', 'REFUSED')""",
                    UUID.fromString(conversationId));
        }
    }
}
