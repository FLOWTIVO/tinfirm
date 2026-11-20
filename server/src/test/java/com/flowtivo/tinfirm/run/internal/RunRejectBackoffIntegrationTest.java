package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.flowtivo.tinfirm.support.FakeRunner;

import tools.jackson.databind.JsonNode;

/**
 * The back-off of a run that its host rejects again (CONVENTIONS §15.6): {@code TINFIRM_RUN_REJECT_BACKOFF} (10 s)
 * after the first rejection, doubled for each further one in a row by the same host, at most
 * {@code TINFIRM_RUN_REJECT_BACKOFF_MAX} (5 minutes); kept while the run waits for another reason, started again from
 * one by another host, and gone once the run starts. The reason's {@code retryAt} is when the run is tried again.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunRejectBackoffIntegrationTest extends RunTestBench {

    @Test
    void aRunThatTheSameHostRejectsAgainWaitsTwiceAsLongEachTimeUpToTheCap() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench);
            long epoch = expectAssign(runner, turn.runId()).get("epoch").asLong();
            long[] seconds = {10, 20, 40, 80, 160, 300, 300};
            for (int rejection = 1; rejection <= seconds.length; rejection++) {
                assertThat(reject(bench, runner, turn.runId(), epoch)).as("rejection %d", rejection)
                    .isEqualTo(Duration.ofSeconds(seconds[rejection - 1]));
                assertThat(storedCount(bench, turn.runId())).isEqualTo(rejection);
                epoch = again(bench, runner, turn.runId());
            }
            // The run starts: its count goes with its queue reason.
            runner.accepted(turn.runId(), epoch);
            awaitRun(bench, turn.runId(), "RUNNING");
            assertThat(query(bench.companyId(), "SELECT queue_reason_details FROM run WHERE id = ?", turn.runId()))
                .isNull();
            runner.assertFramesValid();
        }
    }

    @Test
    void theCountStaysWhileTheRunWaitsForItsOfflineHost() {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        Turn turn;
        long epoch;
        try (FakeRunner runner = connect(host, RUNNER_VERSION, 4)) {
            turn = start(bench);
            epoch = expectAssign(runner, turn.runId()).get("epoch").asLong();
            assertThat(reject(bench, runner, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(10));
            epoch = again(bench, runner, turn.runId());
            assertThat(reject(bench, runner, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(20));
        }
        await().atMost(WAIT).until(() -> !connections.isConnected(host.id()));
        // The back-off ends while the host is offline: the run waits for it, with its count.
        update(bench.companyId(), "UPDATE run SET dispatch_after = ? WHERE id = ?", ts(Instant.now().minusSeconds(1)),
                turn.runId());
        awaitQueueReason(bench, turn.runId(), "RUNNER_HOST_OFFLINE");
        assertThat(storedCount(bench, turn.runId())).isEqualTo(2);

        try (FakeRunner back = connect(host, RUNNER_VERSION, 4)) {
            dispatcher.dispatch();
            epoch = expectAssign(back, turn.runId()).get("epoch").asLong();
            assertThat(reject(bench, back, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(40));
            back.assertFramesValid();
        }
    }

    @Test
    void anotherHostStartsTheCountAgain() {
        Bench bench = bench(true);
        try (FakeRunner small = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench);
            long epoch = expectAssign(small, turn.runId()).get("epoch").asLong();
            assertThat(reject(bench, small, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(10));
            epoch = again(bench, small, turn.runId());
            assertThat(reject(bench, small, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(20));

            // A host with more free slots takes the run next: its first rejection waits the shortest time.
            RunnerHostRef largeHost = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
            try (FakeRunner large = connect(largeHost, RUNNER_VERSION, 8)) {
                epoch = again(bench, large, turn.runId());
                assertThat(reject(bench, large, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(10));
                large.assertFramesValid();
            }
            await().atMost(WAIT).until(() -> !connections.isConnected(largeHost.id()));

            // Back on the first host, whose count the other host's assignment dropped.
            epoch = again(bench, small, turn.runId());
            assertThat(reject(bench, small, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(10));
            small.assertFramesValid();
        }
    }

    @Test
    void aPreviousTurnThatStillEndsKeepsTheShortWaitAndTheCount() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench);
            long epoch = expectAssign(runner, turn.runId()).get("epoch").asLong();
            assertThat(reject(bench, runner, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(10));
            epoch = again(bench, runner, turn.runId());
            assertThat(reject(bench, runner, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(20));
            epoch = again(bench, runner, turn.runId());

            runner.send(Map.of("type", "run.rejected", "runId", turn.runId().toString(), "epoch", epoch, "reason",
                    "The previous turn still ends.", "code", "TASK_RUN_ACTIVE"));
            JsonNode reason = awaitReason(bench, turn.runId(), "PREVIOUS_RUN_ACTIVE");
            assertThat(waits(reason)).isEqualTo(Duration.ofSeconds(10));
            assertThat(storedCount(bench, turn.runId())).isEqualTo(2);

            epoch = again(bench, runner, turn.runId());
            assertThat(reject(bench, runner, turn.runId(), epoch)).isEqualTo(Duration.ofSeconds(40));
            runner.assertFramesValid();
        }
    }

    /** The host rejects the run as a host whose driver can make no VM does; how long the run waits then. */
    private Duration reject(Bench bench, FakeRunner runner, UUID runId, long epoch) {
        runner.send(Map.of("type", "run.rejected", "runId", runId.toString(), "epoch", epoch, "reason",
                "The VM driver can make no VM.", "code", "NO_FREE_SLOT"));
        JsonNode reason = awaitReason(bench, runId, "RUNNER_HOST_REJECTED");
        assertThat(reason.get("rejectCode").asString()).isEqualTo("NO_FREE_SLOT");
        // The API shows none of the count.
        assertThat(reason.has("rejections")).isFalse();
        return waits(reason);
    }

    /** Ends the back-off now, dispatches, and expects the run on this host: its new epoch. */
    private long again(Bench bench, FakeRunner runner, UUID runId) {
        update(bench.companyId(), "UPDATE run SET dispatch_after = ? WHERE id = ?", ts(Instant.now().minusSeconds(1)), runId);
        dispatcher.dispatch();
        return expectAssign(runner, runId).get("epoch").asLong();
    }

    private JsonNode awaitReason(Bench bench, UUID runId, String code) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(getRun(bench, runId).get("queueReason").get("code")
            .asString()).isEqualTo(code));
        return getRun(bench, runId).get("queueReason");
    }

    /**
     * From the reason's start to its retryAt, both set by the rejection, to the second: the stored times may be cut to
     * different precisions.
     */
    private static Duration waits(JsonNode reason) {
        Duration waits = Duration.between(Instant.parse(reason.get("since").asString()),
                Instant.parse(reason.get("retryAt").asString()));
        return Duration.ofSeconds(Math.round(waits.toMillis() / 1000.0));
    }

    private static Integer storedCount(Bench bench, UUID runId) {
        Object count = query(bench.companyId(),
                "SELECT (queue_reason_details -> 'rejections' ->> 'count')::int FROM run WHERE id = ?", runId);
        return (Integer) count;
    }
}
