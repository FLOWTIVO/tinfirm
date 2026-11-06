package com.flowtivo.tinfirm.run.internal;

/** Why the server marked a run LOST ({@code run.lost_cause}). */
enum LostCause {

    /** The reaper: the lease expired, and the host was not heard from. The only cause that can be resumed. */
    LEASE_EXPIRED,

    /** The host reconnected without the run in {@code hello.activeRuns}: it restarted. */
    HOST_RESTARTED,

    /**
     * The host reconnected with the run, but with another epoch, or so long after the lease expired that the run
     * could not have been resumed any more.
     */
    STALE_ON_RECONNECT
}
