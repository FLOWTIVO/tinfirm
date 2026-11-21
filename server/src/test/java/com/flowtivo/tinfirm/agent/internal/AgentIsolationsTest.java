package com.flowtivo.tinfirm.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.flowtivo.tinfirm.agent.AgentStarts;
import com.flowtivo.tinfirm.agent.AgentStatus;
import com.flowtivo.tinfirm.runner.Hardening;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.IsolationLevel;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.api.model.AgentIsolation;
import com.flowtivo.tinfirm.shared.api.model.AgentIsolationSetting;
import com.flowtivo.tinfirm.shared.api.model.RunnerIsolationLevel;

/**
 * ADR-0113, decision 4: the levels an agent's editor may choose come from the company's hosts that serve its runtimes,
 * never a member's personal host; where its runs go now; the warning where runs that start from outside the company
 * would run in a container; a chosen level no host reports any more.
 */
class AgentIsolationsTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static RunnerHostInfo host(HostScope scope, @Nullable IsolationLevel level, RuntimeKind runtime) {
        return new RunnerHostInfo(UUID.randomUUID(), COMPANY, "h", scope, null, null, 2, 2, null, false,
                java.util.Set.of(runtime), "1.0.0", Instant.now(), level, level != null ? Hardening.NO : null, false);
    }

    private static AgentStore.AgentRow agent(@Nullable IsolationLevel chosen) {
        Instant now = Instant.now();
        return new AgentStore.AgentRow(UUID.randomUUID(), COMPANY, "Analyst", "analyst", null, "", List.of(
                RuntimeKind.CLAUDE_CODE), null, true, UUID.randomUUID(), AgentStatus.ACTIVE, null, null, 1, null, now,
                now, null, chosen);
    }

    @SuppressWarnings("unchecked")
    private static AgentIsolations isolations(List<RunnerHostInfo> hosts, boolean outside) {
        RunnerDirectory runners = mock(RunnerDirectory.class);
        when(runners.hosts(COMPANY)).thenReturn(hosts);
        ObjectProvider<AgentStarts> starts = mock(ObjectProvider.class);
        when(starts.getIfAvailable()).thenReturn((company, agent) -> outside);
        return new AgentIsolations(runners, starts);
    }

    @Test
    void theCompanysHostsOfItsRuntimesAreOfferedNeverAPersonalHost() {
        AgentIsolations isolations = isolations(List.of(host(HostScope.COMPANY, null, RuntimeKind.CLAUDE_CODE),
                host(HostScope.COMPANY, IsolationLevel.MACHINE, RuntimeKind.CODEX),
                host(HostScope.PERSONAL, IsolationLevel.VM_JAIL, RuntimeKind.CLAUDE_CODE)), false);
        AgentIsolation view = isolations.view(agent(null));
        assertThat(view.getOffered()).containsExactly(RunnerIsolationLevel.CONTAINER);
        assertThat(view.getSetting()).isEqualTo(AgentIsolationSetting.AUTOMATIC);
        assertThat(view.getLevel()).isEqualTo(RunnerIsolationLevel.CONTAINER);
        assertThat(view.getContainerWarning()).isFalse();
    }

    @Test
    void outsideStartsOnAContainerAreWarnedOf() {
        List<RunnerHostInfo> containers = List.of(host(HostScope.COMPANY, null, RuntimeKind.CLAUDE_CODE));
        assertThat(isolations(containers, true).view(agent(null)).getContainerWarning()).isTrue();
        assertThat(isolations(containers, false).view(agent(null)).getContainerWarning()).isFalse();
        List<RunnerHostInfo> mixed = List.of(host(HostScope.COMPANY, null, RuntimeKind.CLAUDE_CODE),
                host(HostScope.COMPANY, IsolationLevel.VM_JAIL, RuntimeKind.CLAUDE_CODE));
        AgentIsolation strong = isolations(mixed, true).view(agent(null));
        assertThat(strong.getOutsideStarts()).isTrue();
        assertThat(strong.getContainerWarning()).isFalse();
        // A level chosen on purpose is still warned of.
        assertThat(isolations(mixed, true).view(agent(IsolationLevel.CONTAINER)).getContainerWarning()).isTrue();
    }

    @Test
    void aChosenLevelNoHostReportsIsKeptAndSaidSo() {
        AgentIsolation view = isolations(List.of(host(HostScope.COMPANY, null, RuntimeKind.CLAUDE_CODE)), false)
            .view(agent(IsolationLevel.MACHINE));
        assertThat(view.getSetting()).isEqualTo(AgentIsolationSetting.MACHINE);
        assertThat(view.getChosenUnavailable()).isTrue();
        assertThat(view.getLevel()).isNull();
    }
}
