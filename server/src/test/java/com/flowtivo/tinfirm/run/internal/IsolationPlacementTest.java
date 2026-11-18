package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.run.PlacementNote;
import com.flowtivo.tinfirm.run.QueueReasonCode;
import com.flowtivo.tinfirm.runner.Hardening;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.IsolationLevel;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;

/**
 * ADR-0113, decision 4, the dispatcher's step for the level, row by row of CONVENTIONS §15.6's table: C the hosts that
 * count, T the target level, S the session's host, B a bundle of the session kept.
 */
class IsolationPlacementTest {

    private static final RunnerHostInfo VM = host("vm", IsolationLevel.MACHINE, Hardening.NONE);

    private static final RunnerHostInfo VM_2 = host("vm-2", IsolationLevel.MACHINE, Hardening.NO);

    private static final RunnerHostInfo JAIL = host("jail", IsolationLevel.VM_JAIL, Hardening.YES);

    private static final RunnerHostInfo DOCKER = host("docker", null, null);

    private static RunnerHostInfo host(String name, @Nullable IsolationLevel level, @Nullable Hardening hardened) {
        return new RunnerHostInfo(UUID.randomUUID(), UUID.randomUUID(), name, HostScope.COMPANY, null, null, 2, 2,
                null, false, null, "1.0.0", Instant.now(), level, hardened, false);
    }

    private static IsolationPlacement.Plan plan(List<RunnerHostInfo> counting, @Nullable IsolationLevel chosen,
            IsolationPlacement.@Nullable Session session) {
        return (IsolationPlacement.Plan) IsolationPlacement.plan(counting, chosen, session);
    }

    @Test
    void row2AChosenLevelNoHostReportsWaits() {
        IsolationPlacement.Outcome outcome = IsolationPlacement.plan(List.of(DOCKER), IsolationLevel.MACHINE, null);
        assertThat(outcome).isInstanceOf(IsolationPlacement.Waits.class);
        QueueReason reason = ((IsolationPlacement.Waits) outcome).reason();
        assertThat(reason.code()).isEqualTo(QueueReasonCode.NO_HOST_OF_LEVEL);
        assertThat(reason.isolationLevel()).isEqualTo(IsolationLevel.MACHINE);
        assertThat(reason.text()).isEqualTo("This agent's runs go to hosts that report machine, and no runner host "
                + "that may run this conversation reports it.");
    }

    @Test
    void row3AutomaticWithoutASessionGoesToTheStrongestLevelOnly() {
        IsolationPlacement.Plan plan = plan(List.of(DOCKER, VM, JAIL, VM_2), null, null);
        assertThat(plan.candidates()).containsExactly(VM, VM_2);
        assertThat(plan.level()).isEqualTo(IsolationLevel.MACHINE);
        assertThat(plan.chosen()).isFalse();
        assertThat(plan.preferred()).isNull();
        assertThat(plan.weakerLeftOut()).isTrue();
        // One kind of host: nothing is left out, and nothing is said.
        assertThat(plan(List.of(DOCKER), null, null).weakerLeftOut()).isFalse();
    }

    @Test
    void row4TheSessionsHostOfTheTargetLevelComesFirst() {
        IsolationPlacement.Session session = new IsolationPlacement.Session(VM_2, false);
        IsolationPlacement.Plan plan = plan(List.of(VM, VM_2, DOCKER), null, session);
        assertThat(plan.candidates()).containsExactly(VM, VM_2);
        assertThat(plan.preferred()).isEqualTo(VM_2.id());
        assertThat(IsolationPlacement.note(plan, VM_2, session, false)).isNull();
        // Another host of the same level: no note, as before ADR-0113.
        assertThat(IsolationPlacement.note(plan, VM, session, false)).isNull();
    }

    @Test
    void row5AnAutomaticConversationStaysOnTheWeakerHostOfItsSession() {
        IsolationPlacement.Session session = new IsolationPlacement.Session(DOCKER, false);
        IsolationPlacement.Plan plan = plan(List.of(VM, DOCKER), null, session);
        assertThat(plan.candidates()).containsExactly(VM, DOCKER);
        assertThat(plan.preferred()).isEqualTo(DOCKER.id());
        assertThat(plan.level()).isEqualTo(IsolationLevel.MACHINE);
        assertThat(IsolationPlacement.note(plan, DOCKER, session, false))
            .isEqualTo(PlacementNote.STAYED_ON_WEAKER_HOST);
        // The session's host could not take it: the stronger level, with a fresh session.
        assertThat(IsolationPlacement.note(plan, VM, session, false))
            .isEqualTo(PlacementNote.FRESH_SESSION_STRONGER_LEVEL);
    }

    @Test
    void row6AKeptBundleMovesTheAutomaticConversationToTheStrongerLevel() {
        IsolationPlacement.Session session = new IsolationPlacement.Session(DOCKER, true);
        IsolationPlacement.Plan plan = plan(List.of(VM, DOCKER), null, session);
        assertThat(plan.candidates()).containsExactly(VM);
        assertThat(plan.preferRestorers()).isTrue();
        assertThat(plan.preferred()).isNull();
        assertThat(IsolationPlacement.note(plan, VM, session, true)).isNull();
        assertThat(IsolationPlacement.note(plan, VM, session, false))
            .isEqualTo(PlacementNote.FRESH_SESSION_STRONGER_LEVEL);
    }

    @Test
    void row7AChosenLevelOfTheSessionsHost() {
        IsolationPlacement.Session session = new IsolationPlacement.Session(DOCKER, false);
        IsolationPlacement.Plan plan = plan(List.of(VM, DOCKER), IsolationLevel.CONTAINER, session);
        assertThat(plan.candidates()).containsExactly(DOCKER);
        assertThat(plan.chosen()).isTrue();
        assertThat(plan.preferred()).isEqualTo(DOCKER.id());
        assertThat(plan.weakerLeftOut()).isFalse();
        assertThat(IsolationPlacement.note(plan, DOCKER, session, false)).isNull();
    }

    @Test
    void rows8And9AChosenLevelHoldsForEveryTurn() {
        IsolationPlacement.Session session = new IsolationPlacement.Session(DOCKER, false);
        IsolationPlacement.Plan plan = plan(List.of(VM, DOCKER), IsolationLevel.MACHINE, session);
        assertThat(plan.candidates()).containsExactly(VM);
        assertThat(plan.preferred()).isNull();
        assertThat(IsolationPlacement.note(plan, VM, session, false))
            .isEqualTo(PlacementNote.FRESH_SESSION_CHOSEN_LEVEL);

        IsolationPlacement.Session carried = new IsolationPlacement.Session(DOCKER, true);
        IsolationPlacement.Plan moving = plan(List.of(VM, DOCKER), IsolationLevel.MACHINE, carried);
        assertThat(moving.candidates()).containsExactly(VM);
        assertThat(moving.preferRestorers()).isTrue();
        assertThat(IsolationPlacement.note(moving, VM, carried, true)).isNull();
        assertThat(IsolationPlacement.note(moving, VM, carried, false))
            .isEqualTo(PlacementNote.FRESH_SESSION_CHOSEN_LEVEL);

        // A stronger session than the chosen level moves down to it, also with a note.
        IsolationPlacement.Session strong = new IsolationPlacement.Session(VM, false);
        IsolationPlacement.Plan down = plan(List.of(VM, DOCKER), IsolationLevel.CONTAINER, strong);
        assertThat(down.candidates()).containsExactly(DOCKER);
        assertThat(IsolationPlacement.note(down, DOCKER, strong, false))
            .isEqualTo(PlacementNote.FRESH_SESSION_CHOSEN_LEVEL);
    }

    @Test
    void aReasonAboutTheHostsOfTheLevelNamesItAndKeepsItWhenStored() {
        QueueReason full = QueueReason.hostsFull(VM, 2, 2);
        assertThat(full.text()).isEqualTo("Waiting for a free slot on vm (2 of 2 in use).");
        QueueReason held = full.withIsolationLevel(IsolationLevel.MACHINE);
        assertThat(held.text()).isEqualTo("Waiting for a free slot on vm (2 of 2 in use). This agent's runs go to "
                + "hosts that report machine and do not move to a weaker one.");
        assertThat(QueueReason.fromColumns(held.code().name(), held.detailsJson())).isEqualTo(held);
        assertThat(QueueReason.fromColumns(full.code().name(), full.detailsJson())).isEqualTo(full);
    }
}
