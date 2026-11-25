package com.flowtivo.tinfirm.agent.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentStarts;
import com.flowtivo.tinfirm.runner.Isolation;
import com.flowtivo.tinfirm.runner.IsolationLevel;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.api.model.AgentIsolation;
import com.flowtivo.tinfirm.shared.api.model.AgentIsolationSetting;
import com.flowtivo.tinfirm.shared.api.model.RunnerIsolationLevel;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * An agent's level of isolation (ADR-0113, decision 4) as its editor sees and sets it: the levels the company's runner
 * hosts that serve one of its runtimes report, which an editor may choose; where its new conversations' runs go now; and
 * the warning where runs that start from outside the company would run in a container. A member's personal hosts are
 * not offered: they are hidden from everyone but their owner, and count only for their owner's own runs.
 */
@Component
class AgentIsolations {

    private final RunnerDirectory runners;

    /** The trigger module's answer; without it, no agent starts from outside. */
    private final ObjectProvider<AgentStarts> starts;

    AgentIsolations(RunnerDirectory runners, ObjectProvider<AgentStarts> starts) {
        this.runners = runners;
        this.starts = starts;
    }

    /** The levels the company's hosts that serve one of the runtimes report, strongest first. */
    List<IsolationLevel> offered(UUID companyId, Collection<RuntimeKind> runtimes) {
        return Isolation.offered(companyHosts(companyId, runtimes));
    }

    /** The chosen level of a setting; null for {@code AUTOMATIC}. */
    static @Nullable IsolationLevel chosen(AgentIsolationSetting setting) {
        return setting == AgentIsolationSetting.AUTOMATIC ? null : IsolationLevel.valueOf(setting.getValue());
    }

    /**
     * The level a request sets: {@code AUTOMATIC} always, a level only when a company host that serves one of the
     * runtimes reports it ({@code 400 VALIDATION_FAILED} on {@code isolation} otherwise). A level the agent has
     * already is kept without the check, even when no host reports it any more.
     */
    @Nullable IsolationLevel requireOffered(UUID companyId, Collection<RuntimeKind> runtimes,
            AgentIsolationSetting setting, @Nullable IsolationLevel current) {
        IsolationLevel chosen = chosen(setting);
        if (chosen == null || chosen == current || offered(companyId, runtimes).contains(chosen)) {
            return chosen;
        }
        throw ApiException.invalid("No runner host of the company that serves the agent's runtimes reports "
                + chosen.wire() + ": choose a level the hosts report, or AUTOMATIC", "isolation");
    }

    /** The agent's isolation for its page. */
    AgentIsolation view(AgentStore.AgentRow row) {
        List<IsolationLevel> offered = offered(row.companyId(), row.runtimes());
        IsolationLevel chosen = row.isolationLevel();
        boolean unavailable = chosen != null && !offered.contains(chosen);
        IsolationLevel level = chosen != null ? (unavailable ? null : chosen)
                : offered.isEmpty() ? null : offered.getFirst();
        AgentStarts found = starts.getIfAvailable();
        boolean outside = found != null && found.startsFromOutside(row.companyId(), row.id());
        List<RunnerIsolationLevel> offeredView = new ArrayList<>();
        offered.forEach(each -> offeredView.add(RunnerIsolationLevel.fromValue(each.name())));
        return new AgentIsolation(
                chosen != null ? AgentIsolationSetting.fromValue(chosen.name()) : AgentIsolationSetting.AUTOMATIC,
                offeredView, unavailable, outside, outside && level == IsolationLevel.CONTAINER)
            .level(level != null ? RunnerIsolationLevel.fromValue(level.name()) : null);
    }

    /** The setting's name for the audit: {@code AUTOMATIC}, or the level. */
    static String audited(@Nullable IsolationLevel level) {
        return level != null ? level.name() : AgentIsolationSetting.AUTOMATIC.getValue();
    }

    private List<RunnerHostInfo> companyHosts(UUID companyId, Collection<RuntimeKind> runtimes) {
        return runners.hosts(companyId)
            .stream()
            .filter(RunnerHostInfo::company)
            .filter(host -> runtimes.stream().anyMatch(host::serves))
            .toList();
    }
}
