package com.flowtivo.tinfirm.runner;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port: runs occupying runner hosts, implemented by the module that runs work (the run module, package 3 of the
 * platform redesign).
 */
public interface RunnerActivity {

    /** Runs assigned to each host that are running or awaiting {@code run.accepted}; absent means zero. */
    Map<UUID, Integer> activeRunsByHost(Collection<UUID> runnerHostIds);

    /**
     * Whether a QUEUED or RUNNING run is assigned to the host, or waits for it as its subscription's host; either
     * keeps the host from being removed.
     */
    boolean hasActiveRuns(UUID runnerHostId);

    /**
     * Whether a QUEUED or RUNNING run is assigned to any presence of the machine, in any company, or waits for a
     * subscription of its (ADR-0092): either keeps the machine from being removed.
     */
    boolean hasActiveRunsOnMachine(UUID personalHostId);
}
