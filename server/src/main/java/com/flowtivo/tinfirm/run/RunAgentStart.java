package com.flowtivo.tinfirm.run;

import java.util.UUID;

/**
 * Whether a run's agent may have had its prompt (ADR-0073, amended 2026-11-23): a run lost because its runner host
 * restarted is started again by itself only when its agent never had one, since only then no work exists and no model
 * budget was spent. Runs in the caller's transaction and tenant context.
 */
public interface RunAgentStart {

    /**
     * False only when the server holds proof that the run's agent got no prompt: every event stored of the run is the
     * host's preparation ({@code STATUS} {@code preparing}, {@code cloning} or {@code starting}, {@code LOG},
     * {@code GIT} {@code cloned}), so no {@code STATUS} {@code running}, which a host sends before the first prompt,
     * and no event of the agent; and the model relay admitted no request of the run. True in every other case, an
     * unknown run included.
     */
    boolean mayHaveStarted(UUID companyId, UUID runId);
}
