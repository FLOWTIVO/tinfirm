package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * V2's trigger half and the questions of a trigger's conversation answered on the issue (the package 4 contract, §5.8,
 * §9's V2 and V12 rows, §11 point 4): the BA agent fires from a Jira {@code ASSIGNED} trigger whose table is
 * {@code svc-ba}'s; the poll reaches Jira with {@code svc-ba}'s token; the first turn's prompt carries the agent's
 * instructions and the trigger's instruction, the subject and the firing; the run is paid by {@code svc-ba}; the team
 * sees the conversation and Alice, who is not in it, gets {@code 404}. The agent asks; the next poll reads two new
 * comments: Tinfirm's own answers nothing, a Jira user who is no member answers the question with
 * {@code decided_by_name}, and the {@code DECIDED} turn follows once the turn ended. TEST FIXTURE ONLY.
 */
class TriggerQuestionsIntegrationTest extends PollingTestSupport {

    @Autowired
    RunnerConnections connections;

    @Autowired
    SystemTransactions transactions;

    @Autowired
    SystemWriteRecorder writes;

    @Test
    void v2TheBaAgentFiresWithSvcBasAccountAndACommentOnTheIssueAnswersItsQuestion() throws Exception {
        Bench bench = bench();
        UUID svcBa = createServiceIdentity(bench.owner(), bench.company(), unique("svc-ba"));
        ApiResponse users = bench.owner().put(bench.path("/service-identities/" + svcBa + "/users"),
                Map.of("groupIds", List.of(bench.team().toString())));
        assertThat(users.status()).as("%s", users).isEqualTo(200);
        ModelGateways.awaitKey(litellm, bench.company(), svcBa);
        ModelGateways.awaitStatus(bench.owner(), bench.company(), svcBa, "OK");
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        String svcToken = unique("svc-ba-token");
        UUID account = connectServiceToken(bench.owner(), bench.company(), svcBa, system, jira, svcToken, "svc-ba");
        // Bob, in the team that may use svc-ba, makes the trigger with svc-ba's account and budget.
        UUID trigger = create(bench.bob(), bench, tracker(bench, unique("BA"), system, account, svcBa,
                List.of(event("ASSIGNED")), "ISSUE", "SHOP", bench.team()));
        UUID poll = pollOf(bench, trigger);
        armedAgo(bench, trigger, Duration.ofHours(1));
        jira.searchFor(svcToken, List.of(JiraStub.issue("10001", "SHOP-7", "Refunds", "Refund in *EUR*.", "svc-ba",
                List.of(), "To Do", jiraAgo(Duration.ofMinutes(30)), jiraAgo(Duration.ofMinutes(30)), List.of())));

        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            runner.helloV2AndWelcome(List.of(), List.of(), "0.2.0", 4);
            await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));

            pass(bench, poll);
            assertThat(firings(bench, trigger)).containsExactly("ASSIGNED:STARTED:SHOP-7");
            JiraStub.server().verify(getRequestedFor(urlPathEqualTo(jira.url("/rest/api/2/search")))
                .withHeader("Authorization", equalTo("Bearer " + svcToken)));
            JsonNode assign = runner.expect("run.assign");
            String prompt = assign.get("prompt").asString();
            assertThat(prompt).contains("You analyse issues.", "# Instruction\nPrepare the issue for the team.",
                    "# Subject", "Refund in **EUR**.", "# Request", "SHOP-7", "was assigned to svc-ba");
            assertThat(prompt.indexOf("You analyse issues.")).isLessThan(prompt.indexOf("# Instruction"));
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            try (TenantSql sql = TenantSql.in(bench.id())) {
                assertThat(sql.column("SELECT payer_principal_id FROM run WHERE id = ?", runId))
                    .containsExactly(svcBa);
            }
            UUID conversation = (UUID) conversations(bench, trigger).getFirst().get("id");
            assertThat(bench.bob().get(bench.path("/conversations/" + conversation)).status()).isEqualTo(200);
            assertThat(bench.alice().get(bench.path("/conversations/" + conversation)).status()).isEqualTo(404);

            // The agent asks; the next comment on the issue by anyone but Tinfirm answers it.
            runner.accepted(runId, epoch);
            JsonNode asked = runner.requestAndAck(runId, epoch, "ASK", Map.of("question", "Which currency?"));
            String questionId = asked.get("payload").get("decisionId").asString();
            transactions.runInCompany(bench.id(), () -> writes.record(bench.id(), system, "SHOP-7", "COMMENT",
                    "30001", null, List.of(), List.of(), conversation, null));
            readAgo(bench, poll, Duration.ofMinutes(20));
            jira.searchFor(svcToken, List.of(JiraStub.issue("10001", "SHOP-7", "Refunds", "Refund in *EUR*.",
                    "svc-ba", List.of(), "To Do", jiraAgo(Duration.ofMinutes(30)), jiraAgo(Duration.ofMinutes(5)),
                    List.of(JiraStub.comment("30001", "svc-ba", "BA (Tinfirm agent): Which currency? Reply in a "
                            + "comment on this issue to answer.", jiraAgo(Duration.ofMinutes(6))),
                            JiraStub.comment("30002", "carol", "EUR, please.", jiraAgo(Duration.ofMinutes(5)))))));
            pass(bench, poll);
            JsonNode answered = json(bench.bob().get(bench.path("/decisions/" + questionId)));
            assertThat(answered.get("status").asString()).isEqualTo("DECIDED");
            assertThat(answered.get("decidedByName").asString()).isEqualTo("Display carol");
            // §8: the hashed audit holds ids only, never the commenter's name, which erasure could not remove.
            assertThat(auditEvents(bench.owner(), bench.company(), "decision.decided")).singleElement()
                .satisfies(event -> {
                    assertThat(event.get("details").has("decidedByName")).isFalse();
                    assertThat(event.get("details").get("messageId").asString()).isNotBlank();
                    assertThat(event.toString()).doesNotContain("carol");
                });
            assertThat(messages(bench, conversation)).contains("EXTERNAL:EUR, please.")
                .noneMatch(message -> message.contains("Which currency? Reply"));
            // No trigger fired on the comments (the trigger fires on ASSIGNED only).
            assertThat(firings(bench, trigger)).hasSize(1);

            runner.send(Map.of("type", "run.finished", "runId", runId.toString(), "epoch", epoch, "outcome",
                    "SUCCEEDED", "lastSeq", 0, "summary", "Asked about the currency.", "sessionId", "session-1",
                    "git", List.of()));
            JsonNode decided = runner.expect("run.assign");
            assertThat(decided.get("prompt").asString()).contains("# Answers", "EUR, please.");
            runner.assertFramesValid();
        }
    }
}
