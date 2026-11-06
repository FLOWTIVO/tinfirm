package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TestTurns;

import tools.jackson.databind.JsonNode;

/**
 * The platform requests of a run ({@code run.request}, runner protocol v2): exactly one {@code run.request.ack} per
 * request; the run module's own checks ({@code INVALID_REQUEST}, {@code RUN_NOT_ACTIVE}) before the conversation
 * module's port; {@code ASK}, {@code ACCESS}, {@code ARTIFACT} and {@code NETWORK} answered in the run's transaction,
 * {@code TOOL} outside any transaction, every request handed to the port, whose journal keeps the calls (package R),
 * and {@code TOOL_INTERRUPTED} when the port answers nothing, fails or takes too long.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunRequestIntegrationTest extends RunTestBench {

    @Test
    void requestsReachTheConversationOnlyForTheActiveRunAndEachIsAnsweredOnce() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = start(bench);
            expectAssign(runner, turn.runId());
            TestTurns.Script script = TestTurns.conversation(turn.conversationId());
            UUID decision = UUID.randomUUID();
            script.requests = (run, kind, requestId, payload) -> RunnerMessages.RunRequestAck.pending(run.runId(),
                    requestId, decision, kind + " " + payload.get("question").asString());

            // Assigned, not accepted: not active yet.
            JsonNode early = runner.requestAndAck(turn.runId(), 1, "ASK", Map.of("question", "Which branch?"));
            assertThat(early.get("status").asString()).isEqualTo("REJECTED");
            assertThat(early.get("code").asString()).isEqualTo("RUN_NOT_ACTIVE");

            runner.accepted(turn.runId(), 1);
            awaitRun(bench, turn.runId(), "RUNNING");
            JsonNode asked = runner.requestAndAck(turn.runId(), 1, "ASK", Map.of("question", "Which branch?"));
            assertThat(asked.get("status").asString()).isEqualTo("PENDING");
            assertThat(asked.get("payload").get("decisionId").asString()).isEqualTo(decision.toString());
            assertThat(asked.get("message").asString()).isEqualTo("ASK Which branch?");
            assertThat(script.requestsInTransaction).containsOnly(true);

            // The run module's own refusals, before the conversation is asked.
            JsonNode unknown = runner.requestAndAck(turn.runId(), 1, "TELEPORT", Map.of());
            assertThat(unknown.get("code").asString()).isEqualTo("INVALID_REQUEST");
            JsonNode stale = runner.requestAndAck(turn.runId(), 2, "ASK", Map.of("question", "Again?"));
            assertThat(stale.get("code").asString()).isEqualTo("RUN_NOT_ACTIVE");
            UUID raw = UUID.randomUUID();
            runner.send(Map.of("type", "run.request", "runId", turn.runId().toString(), "epoch", 1, "requestId",
                    raw.toString(), "kind", "ARTIFACT", "payload", "not an object"));
            assertThat(runner.expectAck(raw).get("code").asString()).isEqualTo("INVALID_REQUEST");
            assertThat(script.requestsInTransaction).hasSize(1);

            // A request the scripted conversation does not take.
            script.requests = null;
            JsonNode network = runner.requestAndAck(turn.runId(), 1, "NETWORK", Map.of("host", "example.com",
                    "reason", "Docs."));
            assertThat(network.get("status").asString()).isEqualTo("REJECTED");
            assertThat(network.get("code").asString()).isEqualTo("REQUEST_UNAVAILABLE");
            runner.assertFramesValid();
        }
    }

    @Test
    void aToolCallRunsOutsideAnyTransactionAndARequestSentAgainReachesThePortAgain() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = start(bench);
            expectAssign(runner, turn.runId());
            runner.accepted(turn.runId(), 1);
            awaitRun(bench, turn.runId(), "RUNNING");
            TestTurns.Script script = TestTurns.conversation(turn.conversationId());
            AtomicInteger calls = new AtomicInteger();
            script.tools = (run, kind, requestId, payload) -> {
                calls.incrementAndGet();
                sleep(Duration.ofMillis(300));
                return RunnerMessages.RunRequestAck.done(run.runId(), requestId, RunnerMessages.ToolResult
                    .text("Found " + payload.get("arguments").get("query").asString(), null, false), null);
            };

            UUID requestId = UUID.randomUUID();
            Map<String, Object> payload = Map.of("tool", "jira_search", "arguments", Map.of("query", "bugs"));
            runner.request(turn.runId(), 1, requestId, "TOOL", payload);
            JsonNode first = runner.expectAck(requestId);
            assertThat(first.get("status").asString()).isEqualTo("DONE");
            assertThat(first.get("payload").get("content").get(0).get("text").asString()).isEqualTo("Found bugs");
            // Sent again (a reconnect): the run module keeps nothing of it and hands it to the port again, whose call
            // journal answers it (package R; the conversation module's ToolCallJournalIntegrationTest).
            runner.request(turn.runId(), 1, requestId, "TOOL", payload);
            assertThat(runner.expectAck(requestId)).isEqualTo(first);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(script.toolsInTransaction).containsOnly(false);

            // The run module never answers nothing (the host sends a request again only after a reconnect), and never
            // TOOL_FAILED for a call it does not know the outcome of: a port that answers nothing, fails or takes too
            // long is TOOL_INTERRUPTED (package R), a tool error that says the call may or may not have taken effect.
            UUID unanswered = UUID.randomUUID();
            script.tools = (run, kind, id, arguments) -> null;
            runner.request(turn.runId(), 1, unanswered, "TOOL", payload);
            assertInterrupted(runner.expectAck(unanswered), "jira_search");
            script.tools = (run, kind, id, arguments) -> {
                throw new IllegalStateException("The system is down");
            };
            assertInterrupted(runner.requestAndAck(turn.runId(), 1, "TOOL", payload), "jira_search");
            // The port answers within the tool timeout itself (4 s in the tests); one that does not is cut off 2 s
            // later, and its thread interrupted.
            AtomicInteger interrupted = new AtomicInteger();
            script.tools = (run, kind, id, arguments) -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                }
                catch (InterruptedException ex) {
                    interrupted.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
                return RunnerMessages.RunRequestAck.rejected(run.runId(), id, "TOO_LATE", "Too late.");
            };
            JsonNode slow = runner.requestAndAck(turn.runId(), 1, "TOOL", payload);
            assertInterrupted(slow, "jira_search");
            assertThat(slow.get("payload").get("content").get(0).get("text").asString()).contains("6 seconds");
            await().atMost(WAIT).until(() -> interrupted.get() == 1);
            script.tools = null;
            assertThat(runner.requestAndAck(turn.runId(), 1, "TOOL", payload).get("code").asString())
                .isEqualTo("UNKNOWN_TOOL");
            assertThat(runner.requestAndAck(turn.runId(), 9, "TOOL", payload).get("code").asString())
                .isEqualTo("RUN_NOT_ACTIVE");
            await().atMost(WAIT).until(() -> calls.get() == 2);
            runner.assertFramesValid();
        }
    }

    /** {@code TOOL_INTERRUPTED}: a DONE tool error with its code, naming the tool and asking for a check. */
    private static void assertInterrupted(JsonNode ack, String tool) {
        assertThat(ack.get("status").asString()).as("%s", ack).isEqualTo("DONE");
        JsonNode result = ack.get("payload");
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.get("structuredContent").get("code").asString()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(result.get("content").get(0).get("text").asString()).contains("`" + tool + "`",
                "may or may not have taken effect", "Check");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
