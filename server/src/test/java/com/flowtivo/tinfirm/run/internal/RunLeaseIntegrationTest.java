package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.NewTurn;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TestTurns;

import tools.jackson.databind.JsonNode;

/**
 * Leases and epochs across disconnects and restarts: the reconnect grace, leases renewed by any frame of a host that
 * talks, a run lost by lease expiry and resumed when its host still runs it (its model token answering again), a
 * resumption that meets the person's next turn, a run's end sent again after the server's restart, a restarted host's
 * runs lost, stale epochs, frames of another host's run, and the tokens of lost runs revoked once they can no longer
 * come back. Time is moved by changing the stored timestamps.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunLeaseIntegrationTest extends RunTestBench {

    /** Comfortably below the default reconnect grace of 2 minutes, and above the lease of 30 s. */
    private static final Duration GRACE_SEEN = Duration.ofSeconds(90);

    @Autowired
    ReconnectGrace grace;

    @Autowired
    JdbcClient jdbc;

    private record Running(Bench bench, RunnerHostRef host, FakeRunner runner, Turn turn, String token) {
    }

    private Running running() {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        FakeRunner runner = connect(host, GATEWAY_VERSION, 4);
        Turn turn = start(bench);
        String token = expectAssign(runner, turn.runId()).get("modelAccess").get("apiKey").asString();
        runner.accepted(turn.runId(), 1);
        awaitRun(bench, turn.runId(), "RUNNING");
        return new Running(bench, host, runner, turn, token);
    }

    private Instant awaitLeaseBeyond(Bench bench, UUID runId, Duration ahead) {
        await().atMost(WAIT).until(() -> lease(bench.companyId(), runId).isAfter(Instant.now().plus(ahead)));
        return lease(bench.companyId(), runId);
    }

    private void setLease(Bench bench, UUID runId, Instant until) {
        update(bench.companyId(), "UPDATE run SET lease_expires_at = ? WHERE id = ?", ts(until), runId);
    }

    @Test
    void aLostRunIsResumedWhenItsHostComesBackWithItAndItsTokenAnswersAgain() {
        Running run = running();
        Bench bench = run.bench();
        assertThat(relay(run.token())).isEqualTo(200);

        // The host goes away for longer than the grace: the run is lost (LEASE_EXPIRED), the conversation told.
        run.runner().abort();
        awaitLeaseBeyond(bench, run.turn().runId(), GRACE_SEEN);
        setLease(bench, run.turn().runId(), Instant.now().minusSeconds(1));
        reaper.expireLeases();
        JsonNode lost = getRun(bench, run.turn().runId());
        assertThat(lost.get("status").asString()).isEqualTo("LOST");
        assertThat(lost.get("failureClass").asString()).isEqualTo("LOST");
        assertThat(query(bench.companyId(), "SELECT lost_cause FROM run WHERE id = ?", run.turn().runId()))
            .isEqualTo("LEASE_EXPIRED");
        assertThat(TestTurns.conversation(run.turn().conversationId()).ends).singleElement()
            .satisfies(end -> assertThat(end.status()).isEqualTo(RunStatus.LOST));
        // Its token is inactive while it is lost, but not revoked: the run may come back.
        assertThat(relay(run.token())).isEqualTo(401);
        assertThat(query(bench.companyId(), "SELECT count(*) FROM run_model_token WHERE run_id = ? "
                + "AND revoked_at IS NULL", run.turn().runId())).isEqualTo(1L);

        // The host reconnects within the resume window and still runs it: the conversation takes it back.
        try (FakeRunner again = FakeRunner.connect(port, run.host().token())) {
            again.helloV2AndWelcome(List.of(), List.of(Map.of("runId", run.turn().runId().toString(), "epoch", 1,
                    "lastSeq", 0)), GATEWAY_VERSION, 4);
            awaitRun(bench, run.turn().runId(), "RUNNING");
            again.expectNone("run.cancel", Duration.ofSeconds(1));
            assertThat(TestTurns.conversation(run.turn().conversationId()).resumed).hasSize(1);
            assertThat(relay(run.token())).isEqualTo(200);
            assertThat(auditEvents(bench.owner(), bench.company(), "run.resumed")).hasSize(1);

            again.send(finished(run.turn().runId(), 1, "SUCCEEDED", 0, "Done after all.", "session-r"));
            awaitRun(bench, run.turn().runId(), "SUCCEEDED");
            assertThat(relay(run.token())).isEqualTo(401);
            again.assertFramesValid();
        }
    }

    @Test
    void aLostRunTheConversationDoesNotTakeBackIsCancelledAndItsTokenRevokedAfterTheWindow() {
        Running run = running();
        Bench bench = run.bench();
        TestTurns.conversation(run.turn().conversationId()).takesBack = false;
        run.runner().abort();
        awaitLeaseBeyond(bench, run.turn().runId(), GRACE_SEEN);
        setLease(bench, run.turn().runId(), Instant.now().minusSeconds(1));
        reaper.expireLeases();
        awaitRun(bench, run.turn().runId(), "LOST");

        try (FakeRunner again = FakeRunner.connect(port, run.host().token())) {
            again.helloV2AndWelcome(List.of(), List.of(Map.of("runId", run.turn().runId().toString(), "epoch", 1,
                    "lastSeq", 0)), GATEWAY_VERSION, 4);
            assertThat(again.expect("run.cancel").get("runId").asString()).isEqualTo(run.turn().runId().toString());
            assertThat(getRun(bench, run.turn().runId()).get("status").asString()).isEqualTo("LOST");
        }

        // Once the resume window is over, the reaper revokes its token.
        update(bench.companyId(), "UPDATE run SET finished_at = ? WHERE id = ?",
                ts(Instant.now().minus(Duration.ofMinutes(11))), run.turn().runId());
        reaper.revokeLostTokens();
        assertThat(query(bench.companyId(), "SELECT count(*) FROM run_model_token WHERE run_id = ? "
                + "AND revoked_at IS NULL", run.turn().runId())).isEqualTo(0L);
    }

    @Test
    void aResumptionThatMeetsThePersonsNextTurnLeavesTheConversationToIt() throws Exception {
        Running run = running();
        Bench bench = run.bench();
        UUID lostId = run.turn().runId();
        run.runner().abort();
        awaitLeaseBeyond(bench, lostId, GRACE_SEEN);
        setLease(bench, lostId, Instant.now().minusSeconds(1));
        reaper.expireLeases();
        awaitRun(bench, lostId, "LOST");

        // The person's next turn starts and holds the conversation, its transaction still open ...
        ExecutorService threads = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger turnPid = new AtomicInteger();
        try {
            Future<Optional<UUID>> next = threads.submit(() -> transactions.inCompany(bench.companyId(), () -> {
                Optional<UUID> runId = turns.start(new NewTurn(bench.companyId(), run.turn().conversationId(),
                        run.turn().key(), bench.agentId(), RuntimeKind.CLAUDE_CODE, TurnReason.MESSAGE, 2,
                        bench.personPrincipalId(), bench.personPrincipalId(), ModelAccessChoice.BUDGET, null));
                turnPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                started.countDown();
                try {
                    commit.await(2 * WAIT.toSeconds(), TimeUnit.SECONDS);
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return runId;
            }));
            assertThat(started.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            // ... while the host comes back with the lost run: the hello's reconciliation waits for that turn.
            try (FakeRunner again = FakeRunner.connect(port, run.host().token())) {
                again.helloV2AndWelcome(List.of(), List.of(Map.of("runId", lostId.toString(), "epoch", 1, "lastSeq",
                        0)), GATEWAY_VERSION, 4);
                await().atMost(WAIT).until(() -> blockedBy(bench.companyId(), turnPid.get()) > 0);
                commit.countDown();
                UUID nextId = next.get(30, TimeUnit.SECONDS).orElseThrow();

                // The new turn is the conversation's latest: the lost run is not taken back, its host is told to stop
                // it, and the hello's transaction did not fail.
                assertThat(again.expect("run.cancel").get("runId").asString()).isEqualTo(lostId.toString());
                assertThat(getRun(bench, lostId).get("status").asString()).isEqualTo("LOST");
                assertThat(TestTurns.conversation(run.turn().conversationId()).resumed).isEmpty();
                assertThat(getRun(bench, nextId).get("status").asString()).isIn("QUEUED", "RUNNING");
                assertThat(again.received()).noneMatch(frame -> "error".equals(frame.path("type").asString())
                        && "INTERNAL_ERROR".equals(frame.path("code").asString()));
                again.assertFramesValid();
            }
        }
        finally {
            threads.shutdownNow();
        }
    }

    @Test
    void aRunsEndSentAgainAfterTheServersRestartEndsItWithTheHostsOutcome() {
        Running run = running();
        Bench bench = run.bench();
        UUID runId = run.turn().runId();
        // The host wrote run.finished just before the server went down, which never read it; the run keeps the
        // reconnect grace, which the server's start gives it again. Its lease never lapses here: the scheduled reaper
        // would lose the run meanwhile and the hello resume it, which the first test of this class covers.
        run.runner().abort();
        awaitLeaseBeyond(bench, runId, GRACE_SEEN);
        grace.extendAfterStart();

        // The host still holds the finished run: it lists it in hello and sends its end again.
        try (FakeRunner again = FakeRunner.connect(port, run.host().token())) {
            again.helloV2AndWelcome(List.of(), List.of(Map.of("runId", runId.toString(), "epoch", 1, "lastSeq", 0)),
                    GATEWAY_VERSION, 4);
            again.send(finished(runId, 1, "SUCCEEDED", 0, "Done before the restart.", "session-f"));
            awaitRun(bench, runId, "SUCCEEDED");
            assertThat(getRun(bench, runId).get("summary").asString()).isEqualTo("Done before the restart.");
            assertThat(TestTurns.conversation(run.turn().conversationId()).ends).singleElement()
                .satisfies(end -> assertThat(end.status()).isEqualTo(RunStatus.SUCCEEDED));
            again.expectNone("run.cancel", Duration.ofSeconds(1));
            again.assertFramesValid();
        }
    }

    @Test
    void aDisconnectedHostKeepsItsRunsForTheReconnectGraceAndARestartedHostLosesThem() {
        Running run = running();
        Bench bench = run.bench();
        run.runner().abort();
        Instant extended = awaitLeaseBeyond(bench, run.turn().runId(), GRACE_SEEN);

        // A minute later the lease of 30 s would be long gone; the grace still holds.
        setLease(bench, run.turn().runId(), extended.minusSeconds(60));
        reaper.expireLeases();
        assertThat(getRun(bench, run.turn().runId()).get("status").asString()).isEqualTo("RUNNING");

        // The host comes back without the run: it restarted, the run is lost and cancelled on it.
        try (FakeRunner again = FakeRunner.connect(port, run.host().token())) {
            again.helloV2AndWelcome(List.of(), List.of(), GATEWAY_VERSION, 4);
            awaitRun(bench, run.turn().runId(), "LOST");
            assertThat(query(bench.companyId(), "SELECT lost_cause FROM run WHERE id = ?", run.turn().runId()))
                .isEqualTo("HOST_RESTARTED");
            assertThat(again.expect("run.cancel").get("runId").asString()).isEqualTo(run.turn().runId().toString());
            assertThat(relay(run.token())).isEqualTo(401);
            again.assertFramesValid();
        }
    }

    @Test
    void aHostThatTalksKeepsItsRunsAliveAndStaleOrForeignFramesAreRefused() {
        Running run = running();
        Bench bench = run.bench();
        try (FakeRunner runner = run.runner()) {
            // Heartbeats are late, but the host talks: the reaper renews instead of losing it.
            setLease(bench, run.turn().runId(), Instant.now().minusSeconds(60));
            reaper.expireLeases();
            assertThat(getRun(bench, run.turn().runId()).get("status").asString()).isEqualTo("RUNNING");
            assertThat(lease(bench.companyId(), run.turn().runId())).isAfter(Instant.now().plusSeconds(20));

            // An event renews its lease; a heartbeat too.
            setLease(bench, run.turn().runId(), Instant.now().plusSeconds(3));
            runner.event(run.turn().runId(), 1, 1, "AGENT_MESSAGE", Map.of("text", "Working."));
            await().atMost(WAIT).until(() -> lease(bench.companyId(), run.turn().runId())
                .isAfter(Instant.now().plusSeconds(20)));
            setLease(bench, run.turn().runId(), Instant.now().plusSeconds(3));
            runner.heartbeat(run.turn().runId(), 1);
            await().atMost(WAIT).until(() -> lease(bench.companyId(), run.turn().runId())
                .isAfter(Instant.now().plusSeconds(20)));

            // Another epoch is stale, and a stale accept or heartbeat is told to stop.
            runner.heartbeat(run.turn().runId(), 7);
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("STALE_EPOCH");
            assertThat(runner.expect("run.cancel").get("epoch").asLong()).isEqualTo(7);
            // A run the server does not know, or another company's, is unknown.
            runner.event(UUID.randomUUID(), 1, 1, "AGENT_MESSAGE", Map.of("text", "Who?"));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("UNKNOWN_RUN");

            // Another host of the company may not speak for the run.
            try (FakeRunner other = companyHost(bench, GATEWAY_VERSION, 1)) {
                other.event(run.turn().runId(), 1, 2, "AGENT_MESSAGE", Map.of("text", "Not mine."));
                assertThat(other.expect("error").get("code").asString()).isEqualTo("UNKNOWN_RUN");
                other.assertFramesValid();
            }
            runner.assertFramesValid();
        }
    }

    @Test
    void theServersStartGivesTheRunsThatHoldASlotTheReconnectGrace() {
        Running run = running();
        Bench bench = run.bench();
        run.runner().abort();
        awaitLeaseBeyond(bench, run.turn().runId(), GRACE_SEEN);
        // The server was down: the lease lapsed meanwhile.
        setLease(bench, run.turn().runId(), Instant.now().minusSeconds(5));
        assertThat(grace.extendAfterStart()).isPositive();
        assertThat(lease(bench.companyId(), run.turn().runId())).isAfter(Instant.now().plus(GRACE_SEEN));
        reaper.expireLeases();
        assertThat(getRun(bench, run.turn().runId()).get("status").asString()).isEqualTo("RUNNING");
    }
}
