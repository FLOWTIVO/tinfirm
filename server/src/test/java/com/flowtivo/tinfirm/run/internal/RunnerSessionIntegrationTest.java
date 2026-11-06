package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TestTurns;

import tools.jackson.databind.JsonNode;

/**
 * The server's flood controls on what a runner host sends about its runs (the security review of 2026-10-27, M4):
 * a run's tool calls beyond those in flight, a host's requests and a run's events beyond their rates, an event's
 * payload and text chunk beyond the schema's sizes, and a run's events beyond its byte budget are refused, each with
 * its code, and never stored or handed to the conversation. With low limits, so that a test reaches them: a request
 * and an event per second with ten seconds' worth at once, and a budget of 1 MiB.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunnerSessionIntegrationTest extends RunTestBench {

    private static final Map<String, Object> TOOL = Map.of("tool", "jira_search", "arguments",
            Map.of("query", "bugs"));

    @DynamicPropertySource
    static void lowLimits(DynamicPropertyRegistry registry) {
        registry.add("tinfirm.runner.requests-per-second", () -> "1");
        registry.add("tinfirm.runner.events-per-second", () -> "1");
        registry.add("tinfirm.runner.events-max-bytes", () -> "1MB");
    }

    @Test
    void aRunsToolCallsBeyondThoseInFlightAreRefused() throws Exception {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = running(bench, runner);
            TestTurns.Script script = TestTurns.conversation(turn.conversationId());
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger started = new AtomicInteger();
            script.tools = (run, kind, requestId, payload) -> {
                started.incrementAndGet();
                try {
                    release.await(3, TimeUnit.SECONDS);
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return RunnerMessages.RunRequestAck.done(run.runId(), requestId,
                        RunnerMessages.ToolResult.text("Found bugs", null, false), null);
            };

            List<UUID> waiting = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                UUID requestId = UUID.randomUUID();
                waiting.add(requestId);
                runner.request(turn.runId(), 1, requestId, "TOOL", TOOL);
            }
            await().atMost(WAIT).until(() -> started.get() == 4);
            // A fifth while four wait: refused at once, never handed to the conversation.
            JsonNode fifth = runner.requestAndAck(turn.runId(), 1, "TOOL", TOOL);
            assertThat(fifth.get("status").asString()).isEqualTo("REJECTED");
            assertThat(fifth.get("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
            assertThat(fifth.get("message").asString()).contains("At most 4 tool calls");
            assertThat(started.get()).isEqualTo(4);

            release.countDown();
            for (UUID requestId : waiting) {
                assertThat(runner.expectAck(requestId).get("status").asString()).isEqualTo("DONE");
            }
            // Answered: the run may call again.
            assertThat(runner.requestAndAck(turn.runId(), 1, "TOOL", TOOL).get("status").asString())
                .isEqualTo("DONE");
            assertThat(started.get()).isEqualTo(5);
            runner.assertFramesValid();
        }
    }

    @Test
    void aHostsRequestsBeyondItsRateAreRefused() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = running(bench, runner);
            TestTurns.Script script = TestTurns.conversation(turn.conversationId());
            AtomicInteger asked = new AtomicInteger();
            script.requests = (run, kind, requestId, payload) -> {
                asked.incrementAndGet();
                return RunnerMessages.RunRequestAck.pending(run.runId(), requestId, UUID.randomUUID(), "Asked.");
            };

            long start = System.nanoTime();
            List<UUID> sent = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                UUID requestId = UUID.randomUUID();
                sent.add(requestId);
                runner.request(turn.runId(), 1, requestId, "ASK", Map.of("question", "Which branch " + i + "?"));
            }
            int refused = 0;
            for (UUID requestId : sent) {
                JsonNode ack = runner.expectAck(requestId);
                if ("TOO_MANY_REQUESTS".equals(ack.path("code").asString(null))) {
                    assertThat(ack.get("status").asString()).isEqualTo("REJECTED");
                    refused++;
                }
                else {
                    assertThat(ack.get("status").asString()).isEqualTo("PENDING");
                }
            }
            // Ten at once, and one more per second since.
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) + 1;
            assertThat(asked.get()).isBetween(10, (int) (10 + seconds));
            assertThat(refused).isEqualTo(30 - asked.get());
            runner.assertFramesValid();
        }
    }

    @Test
    void aRunsEventsBeyondItsRateAreRefusedAndItsLogEventsDropped() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = running(bench, runner);
            long start = System.nanoTime();
            for (int seq = 1; seq <= 25; seq++) {
                runner.event(turn.runId(), 1, seq, "AGENT_MESSAGE", Map.of("text", "chunk " + seq));
            }
            barrier(runner, turn);
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) + 1;
            List<JsonNode> refused = errors(runner, "TOO_MANY_REQUESTS");
            int stored = events(bench, turn.runId()).size();
            assertThat(stored).isBetween(10, (int) (10 + seconds));
            assertThat(refused).hasSize(25 - stored)
                .allSatisfy(error -> assertThat(error.get("runId").asString()).isEqualTo(turn.runId().toString()));

            // LOG events over the rate are dropped without an answer.
            for (int seq = 26; seq <= 40; seq++) {
                runner.event(turn.runId(), 1, seq, "LOG", Map.of("level", "info", "message", "line " + seq));
            }
            barrier(runner, turn);
            assertThat(errors(runner, "TOO_MANY_REQUESTS")).hasSameSizeAs(refused);
            assertThat(events(bench, turn.runId()).size()).isLessThan(stored + 15);
            runner.assertFramesValid();
        }
    }

    @Test
    void anEventOverTheSchemasSizesIsRefused() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = running(bench, runner);
            runner.event(turn.runId(), 1, 1, "AGENT_MESSAGE", Map.of("text", "a".repeat(32 * 1024)));
            runner.event(turn.runId(), 1, 2, "AGENT_MESSAGE", Map.of("text", "a".repeat(32 * 1024 + 1)));
            runner.event(turn.runId(), 1, 3, "AGENT_THOUGHT", Map.of("text", "一".repeat(11_000)));
            runner.event(turn.runId(), 1, 4, "LOG", Map.of("level", "info", "message", "b".repeat(300 * 1024)));
            runner.event(turn.runId(), 1, 5, "LOG", Map.of("level", "info", "message", "within"));
            barrier(runner, turn);
            assertThat(events(bench, turn.runId())).extracting(event -> event.get("seq").asLong())
                .containsExactly(1L, 5L);
            List<JsonNode> refused = errors(runner, "PAYLOAD_TOO_LARGE");
            assertThat(refused).hasSize(3);
            assertThat(refused.get(0).get("message").asString()).contains("Event 2", "text chunk");
            assertThat(refused.get(1).get("message").asString()).contains("Event 3", "33000");
            assertThat(refused.get(2).get("message").asString()).contains("Event 4", "262144");
            runner.assertFramesValid();
        }
    }

    @Test
    void pastItsByteBudgetARunsEventsAreDroppedOrRefusedAfterOneWarning() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = running(bench, runner);
            // Five LOG events of 200 KiB fit 1 MiB; the sixth does not, and its place holds the warning.
            for (int seq = 1; seq <= 6; seq++) {
                runner.event(turn.runId(), 1, seq, "LOG", Map.of("level", "info", "message",
                        Character.toString('a' + seq).repeat(200 * 1024)));
            }
            runner.event(turn.runId(), 1, 7, "AGENT_MESSAGE", Map.of("text", "Done soon."));
            runner.event(turn.runId(), 1, 8, "LOG", Map.of("level", "info", "message", "small"));
            runner.event(turn.runId(), 1, 9, "AGENT_MESSAGE", Map.of("text", "Done."));
            barrier(runner, turn);

            List<JsonNode> stored = events(bench, turn.runId());
            assertThat(stored).extracting(event -> event.get("seq").asLong())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
            JsonNode warning = stored.get(5);
            assertThat(warning.get("type").asString()).isEqualTo("LOG");
            assertThat(warning.get("payload").get("level").asString()).isEqualTo("warn");
            assertThat(warning.get("payload").get("message").asString()).contains("limit of 1 MiB");
            assertThat(errors(runner, "RUN_EVENTS_FULL")).extracting(error -> error.get("message").asString())
                .hasSize(2)
                .allSatisfy(message -> assertThat(message).contains("limit of 1048576 bytes"))
                .anySatisfy(message -> assertThat(message).startsWith("Event 7"))
                .anySatisfy(message -> assertThat(message).startsWith("Event 9"));
            assertThat(((Number) query(bench.companyId(), "SELECT event_bytes FROM run WHERE id = ?", turn.runId()))
                .longValue()).isEqualTo(1024 * 1024);
            // The run still ends as its host says.
            runner.send(finished(turn.runId(), 1, "SUCCEEDED", 9, "Done.", "session-budget"));
            awaitRun(bench, turn.runId(), "SUCCEEDED");
            runner.assertFramesValid();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private Turn running(Bench bench, FakeRunner runner) {
        Turn turn = start(bench);
        expectAssign(runner, turn.runId());
        runner.accepted(turn.runId(), 1);
        awaitRun(bench, turn.runId(), "RUNNING");
        return turn;
    }

    /**
     * Waits until the server handled every frame sent before: a connection's frames are handled in order, and a
     * request is answered, refused as it may be.
     */
    private static void barrier(FakeRunner runner, Turn turn) {
        runner.requestAndAck(turn.runId(), 99, "ASK", Map.of("question", "Barrier?"));
    }

    private static List<JsonNode> errors(FakeRunner runner, String code) {
        return runner.received()
            .stream()
            .filter(frame -> "error".equals(frame.get("type").asString())
                    && code.equals(frame.get("code").asString()))
            .toList();
    }

    private List<JsonNode> events(Bench bench, UUID runId) {
        ApiResponse response = bench.person().get(bench.path("/runs/" + runId + "/events?afterSeq=0&limit=100"));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        response.json().get("items").forEach(items::add);
        return items;
    }
}
