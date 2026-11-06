package com.flowtivo.tinfirm.conversation;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * Part 2 of package 3c end to end, through the API, a fake runner host and the Jira stub (the package 3 contract,
 * §5.4, §5.8, §5.9, §5.10; V2's conversation half): a turn lists the agent's tools in {@code run.assign} and the
 * prompt's {@code # Tools}; a tool call reaches Jira with the person's own account; a call of an {@code ASK} tool and
 * a question wait for the person, and the turn ends on them without a summary; the inbox shows them to the person
 * alone; a message that answers open questions answers the question, the approval is decided through its API and the
 * approved comment is written with the person's account and the agent's label; the {@code DECIDED} turn's prompt
 * tells every answer and the call's result; a {@code BLOCKED} result's open questions become questions, and ending the
 * conversation cancels them. TEST FIXTURE ONLY: tokens are test values.
 */
class ConversationDecisionsEndToEndTest extends ConversationTestBench {

    @Test
    void aTurnsToolsQuestionsAndApprovalsGoRoundTheirPersonAndBack() {
        Bench bench = bench(true);
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID jiraId = createSystem(bench.admin(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        String handle = handleOf(bench, jiraId);
        String agentName = unique("BA");
        ApiResponse created = bench.member().post(bench.path("/agents"), Map.of("name", agentName, "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "Review the requirements.", "systems",
                List.of(Map.of("systemId", jiraId.toString(), "tools", List.of(Map.of("name", "get", "mode", "ALLOW"),
                        Map.of("name", "comment", "mode", "ASK"))))));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID agentId = UUID.fromString(created.json().get("id").asString());
        TestSettings.nameModel(bench.id(), agentId, bench.memberPrincipal());
        connectToken(bench.member(), bench.path("/me/accounts/" + jiraId), jira, "test-e2e-alice", "alice");
        JiraStub.server().stubFor(get(urlPathEqualTo(jira.url("/rest/api/2/issue/SHOP-7")))
            .withHeader("Authorization", equalTo("Bearer test-e2e-alice"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10007", "SHOP-7", "Export to CSV", "As a user...", null,
                    List.of(), "Open", "2026-10-01T10:00:00.000+0200", List.of()))));
        String comment = jira.url("/rest/api/2/issue/SHOP-7/comment");
        JiraStub.server().stubFor(post(urlEqualTo(comment))
            .withHeader("Authorization", equalTo("Bearer test-e2e-alice"))
            .willReturn(JiraStub.json(201, Map.of("id", "10950"))));

        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "Review SHOP-7").get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();

            // run.assign lists the agent's tools, each saying whom it runs as; the prompt has one line per tool.
            List<String> names = new ArrayList<>();
            assign.get("tools").forEach(tool -> names.add(tool.get("name").asString()));
            assertThat(names).containsExactly(handle + "_comment", handle + "_get");
            assertThat(assign.get("tools").get(0).get("description").asString()).contains("Each call waits for");
            assertThat(assign.get("prompt").asString()).contains("# Tools", handle + "_get", handle + "_comment");

            runner.accepted(runId, epoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
            // A read runs at once, with the person's own account.
            JsonNode read = runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_get", "arguments",
                    Map.of("key", "SHOP-7")));
            assertThat(read.get("status").asString()).as("%s", read).isEqualTo("DONE");
            assertThat(read.get("payload").get("content").get(0).get("text").asString()).contains("Export to CSV");
            // The comment waits for an approval; a question for an answer.
            JsonNode asked = runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_comment",
                    "arguments", Map.of("key", "SHOP-7", "body", "The requirements are clear.")));
            assertThat(asked.get("status").asString()).as("%s", asked).isEqualTo("PENDING");
            UUID approval = UUID.fromString(asked.get("payload").get("decisionId").asString());
            JsonNode question = runner.requestAndAck(runId, epoch, "ASK", Map.of("question", "Which format?",
                    "options", List.of("CSV", "XLSX")));
            assertThat(question.get("status").asString()).as("%s", question).isEqualTo("PENDING");
            JiraStub.server().verify(0, postRequestedFor(urlEqualTo(comment)));

            // The turn ends on its own questions: no summary (ADR-0036); the person decides, nobody else.
            runner.send(Map.of("type", "run.finished", "runId", runId.toString(), "epoch", epoch, "outcome",
                    "SUCCEEDED", "lastSeq", 0, "summary", "I asked which format.", "sessionId", "session-e2e",
                    "git", List.of()));
            JsonNode waiting = awaitConversation(bench.member(), bench, id,
                    now -> "WAITING".equals(now.path("turnStatus").asString()));
            assertThat(waiting.get("waitingOn").asString()).isEqualTo("PERSON");
            assertThat(waiting.get("decisions")).hasSize(2).allSatisfy(decision -> {
                assertThat(decision.get("status").asString()).isEqualTo("OPEN");
                assertThat(decision.get("mayDecide").asBoolean()).isTrue();
            });
            // The agent's messages are the two cards, each of its decision; the summary was not posted.
            assertThat(waiting.get("messages")).filteredOn(message -> message.get("kind").asString().equals("AGENT"))
                .hasSize(2)
                .allSatisfy(message -> {
                    assertThat(message.get("decisionId").isNull()).isFalse();
                    assertThat(message.get("body").asString()).doesNotContain("I asked which format.");
                });
            // The inbox: the person's own decisions, longest waiting first; nothing of theirs for OWNER.
            JsonNode inbox = bench.member().get(bench.path("/inbox")).json();
            assertThat(inbox.get("items")).extracting(item -> item.get("kind").asString())
                .containsExactly("DECISION", "DECISION");
            assertThat(inbox.get("items").get(0).get("decision").get("id").asString())
                .isEqualTo(approval.toString());
            assertThat(inbox.get("counts").get("decisions").asInt()).isEqualTo(2);
            assertThat(bench.owner().get(bench.path("/inbox")).json().get("items")).isEmpty();

            // A message that answers the open questions answers the question; the approval still holds the turn.
            ApiResponse answered = bench.member().post(bench.path("/conversations/" + id + "/messages"),
                    Map.of("body", "CSV, please.", "answersOpenQuestions", true));
            assertThat(answered.status()).as("%s", answered).isEqualTo(201);
            runner.expectNone("run.assign", java.time.Duration.ofSeconds(1));
            // The approval: the comment is written with the person's account and the agent's label; then the turn.
            assertThat(bench.member().post(bench.path("/decisions/" + approval + "/decide"), Map.of("outcome",
                    "APPROVED")).status()).isEqualTo(200);
            JsonNode next = runner.expect("run.assign");
            JiraStub.server().verify(1, postRequestedFor(urlEqualTo(comment))
                .withRequestBody(equalToJson("{\"body\":\"*" + agentName + " (Tinfirm agent):* The requirements are "
                        + "clear.\"}")));
            String prompt = next.get("prompt").asString();
            assertThat(prompt).contains("# Answers", "**Q:** Which format?", "**A:** CSV, please.",
                    "Your call of `" + handle + "_comment` (comment on SHOP-7): approved by",
                    "> Commented on SHOP-7 (comment 10950)");
            assertThat(next.get("resumePrompt").asString()).contains("**A:** CSV, please.");

            // A BLOCKED result's open questions become questions; ending the conversation cancels them.
            UUID second = UUID.fromString(next.get("runId").asString());
            runner.accepted(second, next.get("epoch").asLong());
            runner.send(Map.of("type", "run.finished", "runId", second.toString(), "epoch", next.get("epoch").asLong(),
                    "outcome", "SUCCEEDED", "lastSeq", 0, "summary", "Blocked.", "sessionId", "session-e2e",
                    "git", List.of(), "result", Map.of("outcome", "BLOCKED", "summary", "Blocked on the region.",
                            "nextSteps", List.of(), "openQuestions", List.of("Which region?"))));
            JsonNode blocked = awaitConversation(bench.member(), bench, id,
                    now -> "QUESTION".equals(now.path("waitingReason").asString()));
            assertThat(blocked.get("decisions")).filteredOn(decision -> decision.get("status").asString()
                .equals("OPEN")).singleElement()
                .satisfies(decision -> assertThat(decision.get("question").asString()).isEqualTo("Which region?"));
            assertThat(bench.member().post(bench.path("/conversations/" + id + "/end"), null).status())
                .isEqualTo(200);
            assertThat(auditEvents(bench.owner(), bench.company(), "decision.cancelled")).singleElement()
                .satisfies(event -> assertThat(event.get("details").get("cause").asString()).isEqualTo("ENDED"));
            assertThat(bench.member().get(bench.path("/inbox")).json().get("items")).isEmpty();
            runner.assertFramesValid();
        }
    }

    @Test
    void aSavedLinkGoesThroughTheRunsRequestsToPartThreeAndIsAnOutput() {
        Bench bench = bench(true);
        ApiResponse created = bench.member().post(bench.path("/agents"), Map.of("name", unique("Scout"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "Find the report."));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID agentId = UUID.fromString(created.json().get("id").asString());
        TestSettings.nameModel(bench.id(), agentId, bench.memberPrincipal());
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "Find the Q3 report").get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);

            // save_artifact: run.request ARTIFACT, the conversation module's RunRequests, part 3's ArtifactRequests.
            UUID requestId = UUID.randomUUID();
            runner.request(runId, epoch, requestId, "ARTIFACT", Map.of("url", "https://example.com/q3-report",
                    "title", "Q3 report"));
            JsonNode saved = runner.expectAck(requestId);
            assertThat(saved.get("status").asString()).as("%s", saved).isEqualTo("DONE");
            String outputId = saved.get("payload").get("artifactId").asString();
            // The same request id again: the same output.
            runner.request(runId, epoch, requestId, "ARTIFACT", Map.of("url", "https://example.com/q3-report",
                    "title", "Q3 report"));
            assertThat(runner.expectAck(requestId).get("payload").get("artifactId").asString()).isEqualTo(outputId);
            // A link it refuses is part 3's answer, through the same path.
            JsonNode refused = runner.requestAndAck(runId, epoch, "ARTIFACT", Map.of("url", "ftp://example.com/x"));
            assertThat(refused.get("status").asString()).as("%s", refused).isEqualTo("REJECTED");

            JsonNode conversation = conversation(bench.member(), bench, id);
            assertThat(conversation.get("outputs")).singleElement().satisfies(output -> {
                assertThat(output.get("id").asString()).isEqualTo(outputId);
                assertThat(output.get("kind").asString()).isEqualTo("LINK");
                assertThat(output.get("url").asString()).isEqualTo("https://example.com/q3-report");
                assertThat(output.get("runId").asString()).isEqualTo(runId.toString());
            });
            runner.send(finished(runId.toString(), epoch, "SUCCEEDED", "Saved the report."));
            awaitConversation(bench.member(), bench, id, now -> "SUCCEEDED".equals(now.path("turnStatus").asString()));
            runner.assertFramesValid();
        }
    }

    private String handleOf(Bench bench, UUID systemId) {
        return bench.admin().get(bench.path("/systems/" + systemId)).json().get("handle").asString();
    }
}
