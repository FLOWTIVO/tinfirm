package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.flowtivo.tinfirm.support.FakeRunner;

import tools.jackson.databind.JsonNode;

/**
 * A runner host's report that it can take no run (ADR-0117): the feature {@code host.health} in {@code hello} and
 * {@code welcome}; no run for a host with a problem, which waits {@code RUNNER_HOST_UNHEALTHY} without an assignment,
 * a token or a new epoch; the runs it rejected placed again at once when it reports healthy, with their count of
 * rejections (decision 1); the problems on the host's page while it is connected, gone with its connection; one audit
 * event at each change to and from a problem; the flood control and the frame's bounds.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunnerHostHealthIntegrationTest extends RunTestBench {

    private static final String CAUSE = "node node-1: the firewall table (inet tinfirm_node) is not loaded";

    @Test
    void aHostThatReportsAProblemGetsNoRunAndTheRunsItRejectedGoAtOnceWhenItIsHealthy() {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        try (FakeRunner runner = healthHost(host)) {
            Turn turn = start(bench);
            long epoch = expectAssign(runner, turn.runId()).get("epoch").asLong();
            // Rejected three times, before the host says why: 10, 20, then 40 s (decision 1).
            for (long seconds : new long[] {10, 20, 40}) {
                assertThat(reject(bench, runner, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(seconds));
                if (seconds < 40) {
                    epoch = again(bench, runner, turn.runId());
                }
            }
            long waitingEpoch = epoch + 1;

            // The host says that it takes no run: the run's back-off ends, and it waits for the host with its cause.
            runner.send(problem(CAUSE));
            JsonNode reason = awaitQueueReason(bench, turn.runId(), "RUNNER_HOST_UNHEALTHY");
            assertThat(reason.get("runnerHostId").asString()).isEqualTo(host.id().toString());
            assertThat(reason.get("reason").asString()).isEqualTo("VM_DRIVER_NO_VM");
            assertThat(reason.get("message").asString()).isEqualTo(CAUSE);
            assertThat(reason.get("text").asString()).endsWith("can take no run: " + CAUSE + ".");
            assertThat(reason.has("retryAt")).isFalse();
            assertThat(storedCount(bench, turn.runId())).isEqualTo(3);
            // No assignment while it waits: no frame, no audit, no new epoch.
            int assigned = auditEvents(bench.owner(), bench.company(), "run.assigned").size();
            dispatcher.dispatch();
            dispatcher.dispatch();
            runner.expectNone("run.assign", Duration.ofSeconds(1));
            assertThat(getRun(bench, turn.runId()).get("queueReason").get("code").asString())
                .isEqualTo("RUNNER_HOST_UNHEALTHY");
            assertThat(epochOf(bench, turn.runId())).isEqualTo(waitingEpoch);
            assertThat(auditEvents(bench.owner(), bench.company(), "run.assigned")).hasSize(assigned);

            // The host's page shows the problem while the host is connected.
            JsonNode page = hostOnPage(bench, host.id());
            assertThat(page.get("online").asBoolean()).isTrue();
            assertThat(page.get("problems")).hasSize(1);
            assertThat(page.get("problems").get(0).get("code").asString()).isEqualTo("VM_DRIVER_NO_VM");
            assertThat(page.get("problems").get(0).get("message").asString()).isEqualTo(CAUSE);
            assertThat(Instant.parse(page.get("problemsSince").asString())).isEqualTo("2026-11-20T09:15:00Z");

            // The same problem again, then another cause: the page follows, the audit has one event.
            runner.send(problem(CAUSE));
            runner.send(problem("node node-1: the disk is full"));
            await().atMost(WAIT).untilAsserted(() -> assertThat(hostOnPage(bench, host.id()).get("problems").get(0)
                .get("message").asString()).isEqualTo("node node-1: the disk is full"));
            List<JsonNode> failed = auditEvents(bench.owner(), bench.company(), "runner_host.health_failed");
            assertThat(failed).hasSize(1);
            assertThat(failed.getFirst().get("actor").get("type").asString()).isEqualTo("SYSTEM");
            assertThat(failed.getFirst().get("targetId").asString()).isEqualTo(host.id().toString());
            assertThat(failed.getFirst().get("details").get("codes").get(0).asString()).isEqualTo("VM_DRIVER_NO_VM");
            assertThat(failed.getFirst().get("details").get("message").asString()).isEqualTo(CAUSE);

            // Healthy again: the run goes at once, with its count, and the page and the audit say so.
            runner.send(healthy());
            JsonNode assign = expectAssign(runner, turn.runId());
            assertThat(assign.get("epoch").asLong()).isEqualTo(waitingEpoch + 1);
            assertThat(storedCount(bench, turn.runId())).isEqualTo(3);
            assertThat(hostOnPage(bench, host.id()).has("problems")).isFalse();
            assertThat(auditEvents(bench.owner(), bench.company(), "runner_host.health_restored")).hasSize(1);
            // A fourth rejection by the same host doubles again (decision 1).
            assertThat(reject(bench, runner, turn.runId(), waitingEpoch + 1)).isEqualTo(Duration.ofSeconds(80));
            runner.assertFramesValid();
        }
    }

    @Test
    void theProblemsGoWithTheConnectionAndTheFrameIsBoundedAndCounted() {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        try (FakeRunner runner = healthHost(host)) {
            // Outside the schema: refused, and nothing changes.
            runner.send(Map.of("type", "host.health", "problems", List.of(Map.of("code", "DISK_FULL", "message",
                    "full"))));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
            runner.send(Map.of("type", "host.health", "problems", List.of(Map.of("code", "VM_DRIVER_NO_VM",
                    "message", "two\nlines"))));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
            List<Map<String, Object>> nine = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                nine.add(Map.of("code", "VM_DRIVER_NO_VM", "message", "problem " + i));
            }
            runner.send(Map.of("type", "host.health", "problems", nine));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("BAD_MESSAGE");
            assertThat(connections.health(host.id()).unhealthy()).isFalse();

            // Twelve frames at once are taken; the thirteenth is refused, and the twelfth's state stays.
            for (int i = 1; i <= 12; i++) {
                runner.send(problem("cause " + i));
            }
            runner.send(healthy());
            JsonNode refused = runner.expect("error");
            assertThat(refused.get("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
            assertThat(refused.get("message").asString()).contains("12 a minute");
            await().atMost(WAIT).untilAsserted(() -> assertThat(connections.health(host.id()).problems())
                .extracting(problem -> problem.message())
                .containsExactly("cause 12"));
            assertThat(hostOnPage(bench, host.id()).get("problems").get(0).get("message").asString())
                .isEqualTo("cause 12");
            runner.assertFramesValid();
        }
        // The connection is gone, and its problems with it: offline, and none on the page.
        await().atMost(WAIT).until(() -> !connections.isConnected(host.id()));
        JsonNode offline = hostOnPage(bench, host.id());
        assertThat(offline.get("online").asBoolean()).isFalse();
        assertThat(offline.has("problems")).isFalse();
        // A new connection starts healthy, and takes runs.
        try (FakeRunner again = healthHost(host)) {
            assertThat(connections.health(host.id()).unhealthy()).isFalse();
            assertThat(hostOnPage(bench, host.id()).has("problems")).isFalse();
            Turn turn = start(bench);
            expectAssign(again, turn.runId());
            again.assertFramesValid();
        }
    }

    @Test
    void aHostWithoutTheFeatureMayNotSendTheFrame() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            runner.send(problem(CAUSE));
            JsonNode error = runner.expect("error");
            assertThat(error.get("code").asString()).isEqualTo("BAD_MESSAGE");
            assertThat(error.get("message").asString()).contains("host.health");
            Turn turn = start(bench);
            expectAssign(runner, turn.runId());
            runner.assertFramesValid();
        }
    }

    /** A company host of protocol 2 that lists {@code host.health}; the server's welcome lists it too. */
    private FakeRunner healthHost(RunnerHostRef host) {
        FakeRunner runner = FakeRunner.connect(port, host.token());
        Map<String, Object> hello = FakeRunner.helloV2(List.of(), List.of(), RUNNER_VERSION, 4);
        hello.put("features", List.of("host.health"));
        runner.send(hello);
        JsonNode welcome = runner.expect("welcome");
        assertThat(welcome.get("features").toString()).contains("\"host.health\"");
        awaitConfigured(host.id());
        return runner;
    }

    private static Map<String, Object> problem(String message) {
        return Map.of("type", "host.health", "problems", List.of(Map.of("code", "VM_DRIVER_NO_VM", "message",
                message)), "since", "2026-11-20T09:15:00.000Z");
    }

    private static Map<String, Object> healthy() {
        return Map.of("type", "host.health", "problems", List.of());
    }

    private JsonNode hostOnPage(Bench bench, UUID hostId) {
        for (JsonNode host : bench.owner().get(bench.path("/runner-hosts")).json().get("items")) {
            if (host.get("id").asString().equals(hostId.toString())) {
                return host;
            }
        }
        throw new AssertionError("No runner host " + hostId);
    }

    /** The host rejects the run as a host whose driver can make no VM does; how long the run waits then. */
    private Duration reject(Bench bench, FakeRunner runner, UUID runId, long epoch) {
        runner.send(Map.of("type", "run.rejected", "runId", runId.toString(), "epoch", epoch, "reason",
                "the VM driver can make no VM now (" + CAUSE + ")", "code", "NO_FREE_SLOT"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(getRun(bench, runId).get("queueReason").get("code")
            .asString()).isEqualTo("RUNNER_HOST_REJECTED"));
        JsonNode reason = getRun(bench, runId).get("queueReason");
        Duration waits = Duration.between(Instant.parse(reason.get("since").asString()),
                Instant.parse(reason.get("retryAt").asString()));
        return Duration.ofSeconds(Math.round(waits.toMillis() / 1000.0));
    }

    /** Ends the back-off now, dispatches, and expects the run on this host: its new epoch. */
    private long again(Bench bench, FakeRunner runner, UUID runId) {
        update(bench.companyId(), "UPDATE run SET dispatch_after = ? WHERE id = ?", ts(Instant.now().minusSeconds(1)),
                runId);
        dispatcher.dispatch();
        return expectAssign(runner, runId).get("epoch").asLong();
    }

    private static Integer storedCount(Bench bench, UUID runId) {
        return (Integer) query(bench.companyId(),
                "SELECT (queue_reason_details -> 'rejections' ->> 'count')::int FROM run WHERE id = ?", runId);
    }

    private static long epochOf(Bench bench, UUID runId) {
        return ((Number) query(bench.companyId(), "SELECT epoch FROM run WHERE id = ?", runId)).longValue();
    }
}
