package com.flowtivo.tinfirm.run;

/**
 * The status of a run. {@code QUEUED} covers a run waiting for a host and one assigned and awaiting
 * {@code run.accepted}; {@code QUEUED} and {@code RUNNING} are active, and at most one run per conversation is (I11).
 */
public enum RunStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, LOST;

    public boolean active() {
        return this == QUEUED || this == RUNNING;
    }
}
