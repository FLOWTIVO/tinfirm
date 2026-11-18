package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.flowtivo.tinfirm.run.QueueReasonCode;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;

/**
 * The host of a run among the eligible ones (the package 3 contract, §5.7 steps 4 and 5; open point 13), as a table:
 * connected and configured, of protocol v2, not draining, with a free slot; the conversation's previous host first,
 * else the most free slots, then the name; and the queue reason when none takes the run.
 */
class HostSelectionTest {

    private static final UUID A = UUID.fromString("00000000-0000-7000-8000-00000000000a");

    private static final UUID B = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    private static HostSelection.Host host(UUID id, String name, boolean configured, int protocol, boolean draining,
            int slots, int used) {
        RunnerHostInfo info = new RunnerHostInfo(id, UUID.randomUUID(), name, HostScope.COMPANY, null,
                draining ? 0 : slots, slots, null, draining, null, "0.2.0", Instant.now());
        return new HostSelection.Host(info, configured, protocol, used);
    }

    /** One row: the eligible hosts, the previous host, and the chosen host's name or the queue reason. */
    record Row(String name, List<HostSelection.Host> hosts, @Nullable UUID previous, @Nullable String chosen,
            @Nullable QueueReasonCode waits, @Nullable Integer used, @Nullable Integer limit) {

        @Override
        public String toString() {
            return name;
        }
    }

    static List<Row> rows() {
        return List.of(
                new Row("one free host", List.of(host(A, "alpha", true, 2, false, 2, 0)), null, "alpha", null, null,
                        null),
                new Row("the most free slots", List.of(host(A, "alpha", true, 2, false, 4, 3),
                        host(B, "beta", true, 2, false, 4, 1)), null, "beta", null, null, null),
                new Row("equal slots: the name", List.of(host(B, "beta", true, 2, false, 2, 0),
                        host(A, "alpha", true, 2, false, 2, 0)), null, "alpha", null, null, null),
                new Row("the previous host though another has more room", List.of(
                        host(A, "alpha", true, 2, false, 4, 3), host(B, "beta", true, 2, false, 4, 0)), A, "alpha",
                        null, null, null),
                new Row("the previous host is full: another", List.of(host(A, "alpha", true, 2, false, 1, 1),
                        host(B, "beta", true, 2, false, 4, 0)), A, "beta", null, null, null),
                new Row("the one host is offline", List.of(host(A, "alpha", false, 2, false, 2, 0)), null, null,
                        QueueReasonCode.RUNNER_HOST_OFFLINE, null, null),
                new Row("no eligible host is connected", List.of(host(A, "alpha", false, 2, false, 2, 0),
                        host(B, "beta", false, 2, false, 2, 0)), null, null, QueueReasonCode.NO_RUNNER_HOST, null,
                        null),
                new Row("a host of protocol v1 takes no run", List.of(host(A, "alpha", true, 1, false, 2, 0)), null,
                        null, QueueReasonCode.OTHER, null, null),
                new Row("the one host drains", List.of(host(A, "alpha", true, 2, true, 2, 0)), null, null,
                        QueueReasonCode.RUNNER_HOST_DRAINING, null, null),
                new Row("the one host is full", List.of(host(A, "alpha", true, 2, false, 2, 2)), null, null,
                        QueueReasonCode.RUNNER_HOST_FULL, 2, 2),
                new Row("every host is full: the sums", List.of(host(A, "alpha", true, 2, false, 2, 2),
                        host(B, "beta", true, 2, false, 3, 3), host(UUID.randomUUID(), "gamma", false, 2, false, 4,
                                0)), null, null, QueueReasonCode.RUNNER_HOST_FULL, 5, 5));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void selectsAsTheTableSays(Row row) {
        HostSelection.Outcome outcome = HostSelection.select(row.hosts(), row.previous());
        if (row.chosen() != null) {
            assertThat(outcome).isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo(row.chosen()));
            return;
        }
        assertThat(outcome).isInstanceOfSatisfying(HostSelection.Waits.class, waits -> {
            assertThat(waits.reason().code()).isEqualTo(row.waits());
            assertThat(waits.reason().used()).isEqualTo(row.used());
            assertThat(waits.reason().limit()).isEqualTo(row.limit());
            assertThat(waits.reason().text()).isNotBlank().doesNotContain("null");
        });
    }

    /** A run whose agent has skills goes only to a host that lists {@code run.skills} (ADR-0064). */
    @org.junit.jupiter.api.Test
    void aRunWithSkillsGoesOnlyToAHostThatDeliversThem() {
        RunnerHostInfo alpha = host(A, "alpha", true, 2, false, 4, 0).info();
        RunnerHostInfo beta = host(B, "beta", true, 2, false, 4, 3).info();
        HostSelection.Host plain = new HostSelection.Host(alpha, true, 2, 0, false);
        HostSelection.Host delivering = new HostSelection.Host(beta, true, 2, 3, true);

        assertThat(HostSelection.select(List.of(plain, delivering), null, true))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("beta"));
        assertThat(HostSelection.select(List.of(plain, delivering), null, false))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("alpha"));
        assertThat(HostSelection.select(List.of(plain), A, true))
            .isInstanceOfSatisfying(HostSelection.Waits.class,
                    waits -> assertThat(waits.reason().code()).isEqualTo(QueueReasonCode.NO_HOST_FOR_SKILLS));
    }

    private static HostSelection.Host leveled(String name, com.flowtivo.tinfirm.runner.@Nullable IsolationLevel level,
            com.flowtivo.tinfirm.runner.@Nullable Hardening hardened, int used, boolean restores) {
        RunnerHostInfo info = new RunnerHostInfo(UUID.randomUUID(), UUID.randomUUID(), name, HostScope.COMPANY, null,
                null, 4, 4, null, false, null, "1.0.0", Instant.now(), level, hardened, false);
        return new HostSelection.Host(info, true, 2, used, false, restores);
    }

    /**
     * ADR-0113, decision 4: among the free hosts the dispatcher hands it, the stronger by level and hardening comes
     * before the one with more room; the session's host comes before both; a host that restores a moving session's
     * bundle before all others.
     */
    @org.junit.jupiter.api.Test
    void theStrongerHostByLevelAndHardeningComesBeforeTheFreerOne() {
        HostSelection.Host hardened = leveled("hardened", com.flowtivo.tinfirm.runner.IsolationLevel.MACHINE,
                com.flowtivo.tinfirm.runner.Hardening.YES, 3, false);
        HostSelection.Host plain = leveled("plain", com.flowtivo.tinfirm.runner.IsolationLevel.MACHINE,
                com.flowtivo.tinfirm.runner.Hardening.NO, 0, false);
        HostSelection.Host restoring = leveled("restoring", com.flowtivo.tinfirm.runner.IsolationLevel.MACHINE,
                com.flowtivo.tinfirm.runner.Hardening.NO, 2, true);
        assertThat(HostSelection.select(List.of(plain, hardened), null, false))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("hardened"));
        assertThat(HostSelection.select(List.of(plain, hardened), plain.info().id(), false))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("plain"));
        assertThat(HostSelection.select(List.of(plain, hardened, restoring), null, false,
                com.flowtivo.tinfirm.runner.IsolationLevel.MACHINE))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("restoring"));
        assertThat(HostSelection.select(List.of(plain, hardened, restoring), null, false, null))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("hardened"));
    }

    /**
     * ADR-0113, decision 4 (the review of 2026-11-18, finding 1): a moving session goes first to a free host of the
     * level that restores it, before the session's or the previous run's host; a restorer of another level (the
     * weaker session host itself) is not one; the session's host then comes before the level's other hosts.
     */
    @org.junit.jupiter.api.Test
    void aFreeRestorerOfTheLevelComesFirstThenTheSessionsHostThenTheRest() {
        com.flowtivo.tinfirm.runner.IsolationLevel machine = com.flowtivo.tinfirm.runner.IsolationLevel.MACHINE;
        HostSelection.Host session = leveled("session", null, null, 0, true);
        HostSelection.Host other = leveled("other", machine, com.flowtivo.tinfirm.runner.Hardening.NONE, 0, false);
        HostSelection.Host restorer = leveled("restorer", machine, com.flowtivo.tinfirm.runner.Hardening.NO, 3,
                true);
        assertThat(HostSelection.select(List.of(session, other, restorer), session.info().id(), false, machine))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("restorer"));
        // No free restorer of the level: the session's host, though another host of the level is free.
        HostSelection.Host busy = leveled("restorer", machine, com.flowtivo.tinfirm.runner.Hardening.NO, 4, true);
        assertThat(HostSelection.select(List.of(session, other, busy), session.info().id(), false, machine))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("session"));
        // A chosen level: no session host among the candidates, and the restorer before the previous run's host.
        assertThat(HostSelection.select(List.of(other, restorer), other.info().id(), false, machine))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("restorer"));
        assertThat(HostSelection.select(List.of(other, busy), other.info().id(), false, machine))
            .isInstanceOfSatisfying(HostSelection.Chosen.class,
                    chosen -> assertThat(chosen.host().info().name()).isEqualTo("other"));
    }
}
