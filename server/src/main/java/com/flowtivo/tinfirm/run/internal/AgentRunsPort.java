package com.flowtivo.tinfirm.run.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentRuns;

/**
 * The agent module's {@link AgentRuns} (the package 3 contract, §5.1, open point 12): deleting an agent waits while a
 * run of it is queued or running ({@code AGENT_HAS_ACTIVE_RUNS}), and pausing it with {@code stopRunning} cancels its
 * active runs as a person's cancel does ({@link TurnStarter#cancel}): a queued one at once, a running one through its
 * host. Runs in the agent's transaction and tenant context, after the agent's row is locked; the cancel frames go to
 * the hosts after the commit. The runs' conversations are locked before the runs, in the order of their ids
 * (CONVENTIONS §10), since a queued run's end tells its conversation.
 */
@Component
class AgentRunsPort implements AgentRuns {

    private static final Logger log = LoggerFactory.getLogger(AgentRunsPort.class);

    private final RunStore runs;

    private final RunLocks locks;

    private final TurnStarter starter;

    AgentRunsPort(RunStore runs, RunLocks locks, TurnStarter starter) {
        this.runs = runs;
        this.locks = locks;
        this.starter = starter;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasActiveRuns(UUID companyId, UUID agentId) {
        return runs.hasActiveForAgent(agentId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> cancelRunning(UUID companyId, UUID agentId) {
        List<UUID> cancelled = new ArrayList<>();
        // A run that ended meanwhile is no longer cancelled. One that starts meanwhile is not cancelled either, as if
        // it had started right after the pause: the dispatcher keeps it AGENT_PAUSED. (The agent's row is locked FOR
        // NO KEY UPDATE, so a new run's foreign key to it does not wait for the pause, CONVENTIONS §10.)
        List<RunStore.RunRow> active = runs.activeOfAgent(agentId);
        locks.lockConversations(active);
        for (RunStore.RunRow found : active) {
            RunStore.RunRow run = runs.lock(found.id()).orElse(null);
            if (run != null && starter.cancel(run)) {
                cancelled.add(run.id());
            }
        }
        if (!cancelled.isEmpty()) {
            log.info("Agent {} was paused with stopRunning: {} runs cancelled", agentId, cancelled.size());
        }
        return cancelled;
    }
}
