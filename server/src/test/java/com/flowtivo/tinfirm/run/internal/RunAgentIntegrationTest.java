package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TestTurns;

import tools.jackson.databind.JsonNode;

/**
 * An agent's runs (the package 3 contract, §5.1, open point 12; I18), the agent module's {@code AgentRuns} as the run
 * module implements it: pausing an agent holds its queued turns and lets its running ones finish; pausing it with
 * {@code stopRunning} cancels them, a queued one at once and a running one through its host, audited with the runs
 * cancelled; deleting it waits while a run of it is active ({@code AGENT_HAS_ACTIVE_RUNS}); a turn of a deleted agent
 * never runs.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunAgentIntegrationTest extends RunTestBench {

    @Test
    void pausingHoldsTheAgentsTurnsAndStopRunningCancelsThem() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn running = start(bench);
            expectAssign(runner, running.runId());
            runner.accepted(running.runId(), 1);
            awaitRun(bench, running.runId(), "RUNNING");

            // Paused: the running turn goes on, a new one waits (I18), and the agent is not deleted meanwhile.
            assertThat(bench.person().post(bench.agentPath("/pause"), Map.of("stopRunning", false)).status())
                .isEqualTo(200);
            Turn waiting = start(bench);
            awaitQueueReason(bench, waiting.runId(), "AGENT_PAUSED");
            runner.expectNone("run.cancel", Duration.ofMillis(300));
            assertThat(getRun(bench, running.runId()).get("status").asString()).isEqualTo("RUNNING");
            ApiResponse refused = bench.person().delete(bench.agentPath(""));
            assertThat(refused.status()).as("%s", refused).isEqualTo(409);
            assertThat(refused.problemCode()).isEqualTo("AGENT_HAS_ACTIVE_RUNS");

            // Resumed, the waiting turn is assigned; paused with stopRunning, both are cancelled.
            assertThat(bench.person().post(bench.agentPath("/resume"), null).status()).isEqualTo(200);
            expectAssign(runner, waiting.runId());
            assertThat(bench.person().post(bench.agentPath("/pause"), Map.of("stopRunning", true)).status())
                .isEqualTo(200);
            List<String> cancels = List.of(runner.expect("run.cancel").get("runId").asString(),
                    runner.expect("run.cancel").get("runId").asString());
            assertThat(cancels).containsExactlyInAnyOrder(running.runId().toString(), waiting.runId().toString());
            awaitRun(bench, waiting.runId(), "CANCELLED");
            assertThat(getRun(bench, running.runId()).get("status").asString()).isEqualTo("RUNNING");
            runner.send(Map.of("type", "run.finished", "runId", running.runId().toString(), "epoch", 1, "outcome",
                    "CANCELLED", "lastSeq", 0));
            awaitRun(bench, running.runId(), "CANCELLED");
            assertThat(TestTurns.conversation(running.conversationId()).ends).singleElement()
                .satisfies(end -> assertThat(end.status()).isEqualTo(RunStatus.CANCELLED));
            assertThat(TestTurns.conversation(waiting.conversationId()).ends).singleElement()
                .satisfies(end -> assertThat(end.status()).isEqualTo(RunStatus.CANCELLED));

            List<JsonNode> pauses = auditEvents(bench.owner(), bench.company(), "agent.paused");
            assertThat(pauses).hasSize(2);
            assertThat(pauses.getFirst().get("details").get("stopRunning").asBoolean()).isTrue();
            assertThat(pauses.getFirst().get("details").get("runsCancelled")).extracting(JsonNode::asString)
                .containsExactlyInAnyOrder(running.runId().toString(), waiting.runId().toString());
            assertThat(auditEvents(bench.owner(), bench.company(), "run.cancelled")).hasSize(2);

            // Nothing is active any more: the agent may be deleted.
            assertThat(bench.person().delete(bench.agentPath("")).status()).isEqualTo(204);
            runner.assertFramesValid();
        }
    }

    @Test
    void aTurnOfADeletedAgentNeverRuns() {
        Bench bench = bench(true);
        assertThat(bench.person().delete(bench.agentPath("")).status()).isEqualTo(204);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            UUID conversationId = UUID.randomUUID();
            UUID runId = startTurn(bench, conversationId, "late-1", TurnReason.FIRST, 1, ModelAccessChoice.BUDGET)
                .orElseThrow();
            dispatcher.dispatch();
            awaitRun(bench, runId, "CANCELLED");
            assertThat(getRun(bench, runId).get("failureMessage").asString()).contains("deleted");
            runner.expectNone("run.assign", Duration.ofMillis(300));
        }
    }
}
