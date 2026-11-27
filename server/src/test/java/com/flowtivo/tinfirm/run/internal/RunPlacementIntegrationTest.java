package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.support.FakeRunner;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/**
 * ADR-0113, decision 4: a run goes to the hosts of the strongest level among the company's hosts that count, or of the
 * level chosen for its agent, and never to a weaker host by itself; the payer's personal host takes it too at that level
 * or a stronger one, and no run waits for it; a gVisor host, and a host without a level, are placed as
 * {@code container}; an
 * automatic agent's conversation stays on the weaker host of its session, and a chosen level moves it with a fresh
 * session; the run records the level and the note, and the metric counts it.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunPlacementIntegrationTest extends RunTestBench {

    @Autowired
    MeterRegistry meters;

    /** A company host that reports a level ({@code hello.isolationTiers}), with these slots, configured. */
    FakeRunner leveledHost(Bench bench, String level, String hardened, int slots) {
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique(level), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, host.token());
        Map<String, Object> hello = FakeRunner.helloWithIsolation(level, hardened);
        hello.put("maxConcurrentRuns", slots);
        runner.send(hello);
        runner.expect("welcome");
        awaitConfigured(host.id());
        return runner;
    }

    private void chooseLevel(Bench bench, String level) {
        update(bench.companyId(), "UPDATE agent SET isolation_level = ? WHERE id = ?", level, bench.agentId());
    }

    private void finish(FakeRunner runner, Bench bench, UUID runId, String session) {
        runner.accepted(runId, 1);
        awaitRun(bench, runId, "RUNNING");
        runner.send(finished(runId, 1, "SUCCEEDED", 0, "Done.", session));
        awaitRun(bench, runId, "SUCCEEDED");
    }

    private double assigned(String level, String isolation, String note) {
        var counter = meters.find(RunMetrics.RUNS_ASSIGNED)
            .tag("isolation_level", level)
            .tag("isolation", isolation)
            .tag("placement_note", note)
            .counter();
        return counter != null ? counter.count() : 0;
    }

    @Test
    void aRunGoesToTheStrongestLevelAndWaitsForItRatherThanAWeakerFreeHost() {
        Bench bench = bench(true);
        double before = assigned("MACHINE", "automatic", "none");
        try (FakeRunner container = companyHost(bench, RUNNER_VERSION, 4);
                FakeRunner machine = leveledHost(bench, "machine", "none", 1)) {
            Turn first = start(bench);
            expectAssign(machine, first.runId());
            JsonNode run = getRun(bench, first.runId());
            assertThat(run.get("isolationLevel").asString()).isEqualTo("MACHINE");
            assertThat(run.has("placementNote")).isFalse();
            await().atMost(WAIT).until(() -> assigned("MACHINE", "automatic", "none") > before);

            // The machine host's one slot is taken: the next conversation waits for it, the container host idle.
            Turn second = start(bench);
            JsonNode reason = awaitQueueReason(bench, second.runId(), "RUNNER_HOST_FULL");
            assertThat(reason.get("isolationLevel").asString()).isEqualTo("MACHINE");
            assertThat(reason.get("text").asString()).endsWith("This agent's runs go to hosts that report machine "
                    + "and do not move to a weaker one.");
            container.expectNone("run.assign", Duration.ofMillis(500));

            // It goes on as soon as the machine host has a free slot.
            finish(machine, bench, first.runId(), "session-1");
            expectAssign(machine, second.runId());
            container.assertFramesValid();
            machine.assertFramesValid();
        }
    }

    @Test
    void aChosenLevelNoHostReportsWaitsAndGoesOnOnceOneIsRegistered() {
        Bench bench = bench(true);
        chooseLevel(bench, "VM_JAIL");
        try (FakeRunner container = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench);
            JsonNode reason = awaitQueueReason(bench, turn.runId(), "NO_HOST_OF_LEVEL");
            assertThat(reason.get("isolationLevel").asString()).isEqualTo("VM_JAIL");
            assertThat(reason.get("text").asString()).isEqualTo("This agent's runs go to hosts that report vm-jail, "
                    + "and no runner host that may run this conversation reports it.");
            container.expectNone("run.assign", Duration.ofMillis(500));
            try (FakeRunner jail = leveledHost(bench, "vm-jail", "no", 2)) {
                expectAssign(jail, turn.runId());
                assertThat(getRun(bench, turn.runId()).get("isolationLevel").asString()).isEqualTo("VM_JAIL");
                jail.assertFramesValid();
            }
        }
    }

    @Test
    void aChosenContainerLevelRunsOnAHostWithoutALevel() {
        Bench bench = bench(true);
        chooseLevel(bench, "CONTAINER");
        try (FakeRunner machine = leveledHost(bench, "machine", "none", 4);
                FakeRunner container = companyHost(bench, RUNNER_VERSION, 1)) {
            Turn turn = start(bench);
            expectAssign(container, turn.runId());
            assertThat(getRun(bench, turn.runId()).get("isolationLevel").asString()).isEqualTo("CONTAINER");
            machine.expectNone("run.assign", Duration.ofMillis(500));
        }
    }

    @Test
    void anAutomaticConversationStaysOnTheWeakerHostOfItsSessionAndAChosenLevelMovesIt() {
        Bench bench = bench(true);
        try (FakeRunner container = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn first = start(bench);
            expectAssign(container, first.runId());
            finish(container, bench, first.runId(), "session-first");

            try (FakeRunner machine = leveledHost(bench, "machine", "none", 4)) {
                // Automatic: the conversation stays where its session is, and says so.
                UUID second = startTurn(bench, first.conversationId(), first.key(), TurnReason.MESSAGE, 2,
                        ModelAccessChoice.BUDGET).orElseThrow();
                JsonNode assign = expectAssign(container, second);
                assertThat(assign.get("resumeSessionId").asString()).isEqualTo("session-first");
                JsonNode stayed = getRun(bench, second);
                assertThat(stayed.get("isolationLevel").asString()).isEqualTo("CONTAINER");
                assertThat(stayed.get("placementNote").asString()).isEqualTo("STAYED_ON_WEAKER_HOST");
                finish(container, bench, second, "session-first");

                // A new conversation goes to the strongest level.
                Turn other = start(bench);
                expectAssign(machine, other.runId());

                // Chosen machine: the next turn starts a fresh session there, and says so.
                chooseLevel(bench, "MACHINE");
                UUID third = startTurn(bench, first.conversationId(), first.key(), TurnReason.MESSAGE, 3,
                        ModelAccessChoice.BUDGET).orElseThrow();
                expectAssign(machine, third);
                JsonNode moved = getRun(bench, third);
                assertThat(moved.get("isolationLevel").asString()).isEqualTo("MACHINE");
                assertThat(moved.get("placementNote").asString()).isEqualTo("FRESH_SESSION_CHOSEN_LEVEL");
                assertThat(query(bench.companyId(), "SELECT placement_note FROM run WHERE id = ?", third))
                    .isEqualTo("FRESH_SESSION_CHOSEN_LEVEL");
                container.expectNone("run.assign", Duration.ofMillis(300));
                machine.assertFramesValid();
            }
            container.assertFramesValid();
        }
    }

    @Test
    void aQueuedRunGoesToTheLevelAHostReportsAtItsNextHello() {
        Bench bench = bench(true);
        chooseLevel(bench, "MACHINE");
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("upgraded"), "COMPANY");
        Turn turn;
        try (FakeRunner before = connect(host, RUNNER_VERSION, 4)) {
            turn = start(bench);
            awaitQueueReason(bench, turn.runId(), "NO_HOST_OF_LEVEL");
        }
        // The same host, upgraded: it reports machine now, and the queued run goes to it.
        try (FakeRunner after = FakeRunner.connect(port, host.token())) {
            after.send(FakeRunner.helloWithIsolation("machine", "none"));
            after.expect("welcome");
            expectAssign(after, turn.runId());
        }
    }

    /**
     * The review of 2026-11-18: a run waits for its level's hosts while they drain or are offline, not only while they
     * are full; and a host that drops its level at its next hello is placed again with the run still queued.
     */
    @Test
    void aRunWaitsForItsLevelsHostsWhileTheyDrainOrAreOfflineAndFollowsAHostThatDropsItsLevel() {
        Bench bench = bench(true);
        RunnerHostRef vm = registerRunnerHost(bench.owner(), bench.companyId(), unique("vm"), "COMPANY");
        try (FakeRunner container = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn;
            try (FakeRunner machine = FakeRunner.connect(port, vm.token())) {
                machine.send(FakeRunner.helloWithIsolation("machine", "none"));
                machine.expect("welcome");
                awaitConfigured(vm.id());
                machine.startDraining();
                await().atMost(WAIT).until(() -> "true".equals(String.valueOf(query(bench.companyId(),
                        "SELECT draining FROM runner_host WHERE id = ?", vm.id()))));
                turn = start(bench);
                JsonNode draining = awaitQueueReason(bench, turn.runId(), "RUNNER_HOST_DRAINING");
                assertThat(draining.get("isolationLevel").asString()).isEqualTo("MACHINE");
                assertThat(draining.get("text").asString()).endsWith("do not move to a weaker one.");
                container.expectNone("run.assign", Duration.ofMillis(500));
            }
            JsonNode offline = awaitQueueReason(bench, turn.runId(), "RUNNER_HOST_OFFLINE");
            assertThat(offline.get("isolationLevel").asString()).isEqualTo("MACHINE");
            container.expectNone("run.assign", Duration.ofMillis(300));

            // The host comes back with container: the queued run is placed again.
            try (FakeRunner downgraded = FakeRunner.connect(port, vm.token())) {
                downgraded.send(FakeRunner.helloV2(List.of(), List.of()));
                downgraded.expect("welcome");
                await().atMost(WAIT).until(() -> "ASSIGNED".equals(getRun(bench, turn.runId()).path("queueReason")
                    .path("code").asString()));
                assertThat(getRun(bench, turn.runId()).get("isolationLevel").asString()).isEqualTo("CONTAINER");
            }
        }
    }

    @Test
    void theRunsColumnsHoldOnlyALevelAndANoteWithIt() throws Exception {
        Bench bench = bench(false);
        try (com.flowtivo.tinfirm.support.TenantSql sql = com.flowtivo.tinfirm.support.TenantSql
            .in(bench.companyId())) {
            assertThat(sql.refused("UPDATE agent SET isolation_level = 'CLOUD' WHERE id = ?", bench.agentId()))
                .isEqualTo("agent_isolation_level_ck");
            sql.accepted("UPDATE agent SET isolation_level = 'VM_JAIL' WHERE id = ?", bench.agentId());
        }
        Turn turn = start(bench);
        try (com.flowtivo.tinfirm.support.TenantSql sql = com.flowtivo.tinfirm.support.TenantSql
            .in(bench.companyId())) {
            assertThat(sql.refused("UPDATE run SET isolation_level = 'KATA' WHERE id = ?", turn.runId()))
                .isEqualTo("run_isolation_level_ck");
            assertThat(sql.refused("UPDATE run SET placement_note = 'STAYED_ON_WEAKER_HOST' WHERE id = ?",
                    turn.runId())).isEqualTo("run_placement_note_ck");
            assertThat(sql.refused("UPDATE run SET isolation_level = 'MACHINE', placement_note = 'MOVED' WHERE id = ?",
                    turn.runId())).isEqualTo("run_placement_note_ck");
            assertThat(sql.refused("UPDATE run SET queue_reason = 'NO_SUCH_REASON' WHERE id = ?", turn.runId()))
                .isEqualTo("run_queue_reason_ck");
            sql.accepted("UPDATE run SET isolation_level = 'MACHINE', placement_note = 'FRESH_SESSION_CHOSEN_LEVEL' "
                    + "WHERE id = ?", turn.runId());
        }
    }

    /** A gVisor host is a container host: its level is {@code container}, beside a host with runc. */
    @Test
    void aGvisorHostIsPlacedAsContainer() {
        Bench bench = bench(true);
        chooseLevel(bench, "CONTAINER");
        try (FakeRunner gvisor = leveledHost(bench, "gvisor", null, 2)) {
            Turn turn = start(bench);
            expectAssign(gvisor, turn.runId());
            assertThat(getRun(bench, turn.runId()).get("isolationLevel").asString()).isEqualTo("CONTAINER");
            gvisor.assertFramesValid();
        }
    }

    /** The member's own host at this level, said hello once and left connected; its id in the result. */
    private FakeRunner personalHost(Bench bench, RunnerHostRef host, String level) {
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.send(FakeRunner.helloWithIsolation(level, "no"));
        runner.expect("welcome");
        awaitConfigured(host.id());
        return runner;
    }

    /**
     * The owner's rule of 2026-11-19: the company's hosts set the level. A member's vm-jail laptop does not hold their
     * runs of an automatic agent back from the company's container hosts while it is offline, and takes them, as the
     * stronger host, when it is free.
     */
    @Test
    void aStrongerPersonalHostTakesTheMembersRunsWhenFreeAndNoRunWaitsForIt() {
        Bench bench = bench(true);
        RunnerHostRef laptop = registerRunnerHost(bench.person(), bench.companyId(), unique("laptop"));
        try (FakeRunner container = companyHost(bench, RUNNER_VERSION, 4)) {
            // The laptop reported vm-jail once, and is offline now.
            try (FakeRunner once = personalHost(bench, laptop, "vm-jail")) {
                once.assertFramesValid();
            }
            await().atMost(WAIT).until(() -> !connections.isConnected(laptop.id()));
            Turn offline = start(bench);
            expectAssign(container, offline.runId());
            JsonNode run = getRun(bench, offline.runId());
            assertThat(run.get("isolationLevel").asString()).isEqualTo("CONTAINER");
            assertThat(run.has("placementNote")).isFalse();

            // The laptop is back and free: a new conversation goes to it, the stronger host.
            try (FakeRunner back = personalHost(bench, laptop, "vm-jail")) {
                Turn free = start(bench);
                expectAssign(back, free.runId());
                JsonNode placed = getRun(bench, free.runId());
                assertThat(placed.get("isolationLevel").asString()).isEqualTo("VM_JAIL");
                assertThat(placed.get("runnerHostKind").asString()).isEqualTo("PERSONAL");
                container.expectNone("run.assign", Duration.ofMillis(300));
                back.assertFramesValid();
            }
            container.assertFramesValid();
        }
    }

    /**
     * The owner's rule of 2026-11-19: a personal host weaker than the company's level never takes the member's runs,
     * even while the company's host of the level is full.
     */
    @Test
    void aWeakerPersonalHostNeverTakesTheMembersRuns() {
        Bench bench = bench(true);
        RunnerHostRef laptop = registerRunnerHost(bench.person(), bench.companyId(), unique("laptop"));
        try (FakeRunner machine = leveledHost(bench, "machine", "none", 1);
                FakeRunner docker = personalHost(bench, laptop, "container")) {
            Turn first = start(bench);
            expectAssign(machine, first.runId());
            Turn second = start(bench);
            JsonNode reason = awaitQueueReason(bench, second.runId(), "RUNNER_HOST_FULL");
            assertThat(reason.get("isolationLevel").asString()).isEqualTo("MACHINE");
            docker.expectNone("run.assign", Duration.ofMillis(500));
            finish(machine, bench, first.runId(), "session-1");
            expectAssign(machine, second.runId());
            docker.expectNone("run.assign", Duration.ofMillis(300));
            docker.assertFramesValid();
        }
    }
}
