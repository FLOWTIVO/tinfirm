package com.flowtivo.tinfirm.run;

/**
 * Thrown by {@link TurnSource#content} when the host the run was placed on may no longer carry what the turn would
 * reveal (the security review of 2026-10-27, M2 and D6): a run placed on a personal host whose workspace, read again at
 * the reveal, holds an account that is not the payer's own (a repository approved between the dispatcher's plan and
 * the reveal). Nothing was sent; the run module takes the assignment back to the queue, as it takes back any stale
 * assignment, and the dispatcher places it again under the rule.
 */
public class RunPlacementStale extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RunPlacementStale(String message) {
        super(message);
    }
}
