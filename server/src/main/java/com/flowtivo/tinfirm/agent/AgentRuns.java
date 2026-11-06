package com.flowtivo.tinfirm.agent;

import java.util.List;
import java.util.UUID;

/**
 * Port: the runs of an agent's conversations, implemented by the module that runs them (package 3's run module); the
 * agent module does not depend on it. Called in the transaction that deletes or pauses the agent, in the company's
 * tenant context, after the agent's row is locked. Without an implementation, an agent has no runs.
 */
public interface AgentRuns {

    /** Whether a run of the agent is queued or running: deleting the agent waits (contract §5.1). */
    boolean hasActiveRuns(UUID companyId, UUID agentId);

    /**
     * Cancels the running turns of the agent's conversations (pausing it with {@code stopRunning}, open point 12): a
     * queued one at once, a running one through its host. Answers the runs cancelled, for the audit.
     */
    List<UUID> cancelRunning(UUID companyId, UUID agentId);
}
