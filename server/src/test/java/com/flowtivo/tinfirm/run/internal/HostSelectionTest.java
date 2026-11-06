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
}
