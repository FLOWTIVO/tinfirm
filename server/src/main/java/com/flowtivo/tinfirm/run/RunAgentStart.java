package com.flowtivo.tinfirm.run;

import java.util.UUID;

/**
 * Whether a run's agent may have had its prompt (ADR-0073, amended 2026-11-23): a run lost because its runner host
 * restarted is started again by itself only when its agent never had one, since only then no work exists and no model
 * budget was spent. Runs in the caller's transaction and tenant context.
 */
public interface RunAgentStart {

    /**
     * False only when the server holds proof that the run's agent got no prompt: the run is paid from a budget, so
     * that every model request of its agent passes the model relay, and the relay admitted none; and every event
     * stored of the run is the host's preparation ({@code STATUS} {@code preparing}, {@code cloning} or
     * {@code starting}, {@code LOG}, {@code GIT} {@code cloned}), so no {@code STATUS} {@code running}, which a host
     * sends before the first prompt, and no event of the agent. True in every other case, an unknown run included,
     * and always for a run on a subscription: its agent's requests go from the host to the provider, and the host
     * keeps the run's events in memory while its connection is down, so the stored events alone prove nothing.
     */
    boolean mayHaveStarted(UUID companyId, UUID runId);
}
