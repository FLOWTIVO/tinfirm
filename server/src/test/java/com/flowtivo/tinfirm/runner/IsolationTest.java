package com.flowtivo.tinfirm.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * ADR-0113, decision 4: the level a host is placed at, the order of hosts by level and hardening, and the level an
 * agent's runs go to.
 */
class IsolationTest {

    static RunnerHostInfo host(String name, @Nullable IsolationLevel level, @Nullable Hardening hardened) {
        return new RunnerHostInfo(UUID.randomUUID(), UUID.randomUUID(), name, HostScope.COMPANY, null, null, 2, 2,
                null, false, null, "1.0.0", Instant.now(), level, hardened, false);
    }

    static RunnerHostInfo personal(String name, @Nullable IsolationLevel level) {
        return new RunnerHostInfo(UUID.randomUUID(), UUID.randomUUID(), name, HostScope.PERSONAL, UUID.randomUUID(),
                UUID.randomUUID(), 2, 2, null, false, null, "1.0.0", Instant.now(), level,
                level != null ? Hardening.NO : null, false);
    }

    @Test
    void aHostIsPlacedAtTheLevelItReportsAndAtContainerWithoutOne() {
        assertThat(Isolation.placedAt(host("vm", IsolationLevel.MACHINE, Hardening.NONE)))
            .isEqualTo(IsolationLevel.MACHINE);
        assertThat(Isolation.placedAt(host("jail", IsolationLevel.VM_JAIL, Hardening.NO)))
            .isEqualTo(IsolationLevel.VM_JAIL);
        assertThat(Isolation.placedAt(host("kata", null, null))).as("no hello to this release yet")
            .isEqualTo(IsolationLevel.CONTAINER);
        // A gVisor host is stored at container (its gVisor is shown, and places nothing).
        assertThat(Isolation.placedAt(host("gvisor", IsolationLevel.CONTAINER, Hardening.NO)))
            .isEqualTo(IsolationLevel.CONTAINER);
    }

    @Test
    void theRankOrdersByLevelThenHardening() {
        assertThat(List.of(host("a", IsolationLevel.MACHINE, Hardening.NONE),
                host("b", IsolationLevel.MACHINE, Hardening.YES), host("c", IsolationLevel.MACHINE, Hardening.NO),
                host("d", IsolationLevel.VM_JAIL, Hardening.YES), host("e", IsolationLevel.VM_JAIL, Hardening.NO),
                host("f", IsolationLevel.VM_JAIL, Hardening.NONE), host("g", IsolationLevel.CONTAINER, Hardening.YES),
                host("h", null, null)))
            .extracting(Isolation::rank)
            .containsExactly(0, 0, 1, 2, 3, 3, 4, 4);
        assertThat(Isolation.stronger(IsolationLevel.MACHINE, IsolationLevel.VM_JAIL)).isTrue();
        assertThat(Isolation.stronger(IsolationLevel.CONTAINER, IsolationLevel.VM_JAIL)).isFalse();
        assertThat(Isolation.stronger(IsolationLevel.VM_JAIL, IsolationLevel.VM_JAIL)).isFalse();
    }

    @Test
    void theStrongestAndTheOfferedLevels() {
        List<RunnerHostInfo> hosts = List.of(host("kata", null, null), host("jail", IsolationLevel.VM_JAIL,
                Hardening.NO), host("docker", IsolationLevel.CONTAINER, Hardening.NO));
        assertThat(Isolation.strongest(hosts)).contains(IsolationLevel.VM_JAIL);
        assertThat(Isolation.offered(hosts)).containsExactly(IsolationLevel.VM_JAIL, IsolationLevel.CONTAINER);
        assertThat(Isolation.strongest(List.of())).isEmpty();
    }

    @Test
    void theTargetIsTheChosenLevelElseTheStrongest() {
        List<RunnerHostInfo> hosts = List.of(host("vm", IsolationLevel.MACHINE, Hardening.NONE),
                host("docker", null, null));
        assertThat(Isolation.target(hosts, null))
            .isEqualTo(new Isolation.Level(IsolationLevel.MACHINE, false));
        assertThat(Isolation.target(hosts, IsolationLevel.CONTAINER))
            .isEqualTo(new Isolation.Level(IsolationLevel.CONTAINER, true));
        assertThat(Isolation.target(hosts, IsolationLevel.VM_JAIL)).isEqualTo(new Isolation.NoHostOfLevel(
                IsolationLevel.VM_JAIL, List.of(IsolationLevel.MACHINE, IsolationLevel.CONTAINER)));
        assertThatThrownBy(() -> Isolation.target(List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the owner's rule of 2026-11-19: the company's hosts set the level -----------------------------------------

    @Test
    void theCompanysHostsSetTheAutomaticLevelAndAStrongerPersonalHostDoesNot() {
        RunnerHostInfo docker = host("docker", IsolationLevel.CONTAINER, Hardening.NO);
        RunnerHostInfo laptop = personal("laptop", IsolationLevel.VM_JAIL);
        assertThat(Isolation.target(List.of(docker, laptop), null))
            .isEqualTo(new Isolation.Level(IsolationLevel.CONTAINER, false));
        assertThat(Isolation.automatic(List.of(docker, laptop))).contains(IsolationLevel.CONTAINER);
    }

    @Test
    void whereNoCompanyHostCountsThePersonalHostsSetTheLevel() {
        // A subscription's run, which only its owner's personal host may run; or a company without a host for it.
        assertThat(Isolation.target(List.of(personal("laptop", IsolationLevel.VM_JAIL),
                personal("old", null)), null)).isEqualTo(new Isolation.Level(IsolationLevel.VM_JAIL, false));
    }

    @Test
    void theCompanyHostsOfTheLevelAndThePersonalHostsOfItOrStrongerTakeIt() {
        RunnerHostInfo machine = host("vm", IsolationLevel.MACHINE, Hardening.NONE);
        RunnerHostInfo jail = host("jail", IsolationLevel.VM_JAIL, Hardening.NO);
        RunnerHostInfo docker = host("docker", null, null);
        RunnerHostInfo strongLaptop = personal("strong", IsolationLevel.MACHINE);
        RunnerHostInfo sameLaptop = personal("same", IsolationLevel.VM_JAIL);
        RunnerHostInfo weakLaptop = personal("weak", IsolationLevel.CONTAINER);
        List<RunnerHostInfo> all = List.of(machine, jail, docker, strongLaptop, sameLaptop, weakLaptop);
        assertThat(Isolation.taking(all, IsolationLevel.VM_JAIL)).containsExactly(jail, strongLaptop, sameLaptop);
        assertThat(Isolation.taking(all, IsolationLevel.MACHINE)).containsExactly(machine, strongLaptop);
        assertThat(Isolation.taking(all, IsolationLevel.CONTAINER))
            .containsExactly(docker, strongLaptop, sameLaptop, weakLaptop);
        assertThat(Isolation.takes(machine, IsolationLevel.CONTAINER)).as("a stronger company host").isFalse();
    }

    @Test
    void aChosenLevelIsTakenByACompanyHostOfItOrAPersonalHostOfItOrStronger() {
        RunnerHostInfo docker = host("docker", IsolationLevel.CONTAINER, Hardening.NO);
        assertThat(Isolation.target(List.of(docker, personal("laptop", IsolationLevel.MACHINE)),
                IsolationLevel.VM_JAIL)).isEqualTo(new Isolation.Level(IsolationLevel.VM_JAIL, true));
        assertThat(Isolation.target(List.of(docker, personal("laptop", IsolationLevel.CONTAINER)),
                IsolationLevel.VM_JAIL)).isEqualTo(new Isolation.NoHostOfLevel(IsolationLevel.VM_JAIL,
                        List.of(IsolationLevel.CONTAINER)));
        // A stronger company host does not take a weaker chosen level.
        assertThat(Isolation.target(List.of(host("vm", IsolationLevel.MACHINE, Hardening.NONE)),
                IsolationLevel.VM_JAIL)).isInstanceOf(Isolation.NoHostOfLevel.class);
    }
}
