package com.flowtivo.tinfirm.shared.tx;

import java.io.Serial;

/**
 * A step after a transaction failed once the transaction had committed (work kept for after it,
 * {@link AfterTransaction}, or a callback after the commit), in a piece of work in which a transaction that was neither
 * read-only nor bookkeeping committed ({@link AfterTransaction#savedInThisWork}): a change may already be saved, and
 * stays; only the step after it failed. Its cause is the step's failure. What a request that ends with it is told
 * follows the request's method ({@code shared.web.ServerBusy}).
 */
public final class FailedAfterCommit extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    FailedAfterCommit(Throwable cause) {
        super("The transaction committed, and a step after it failed: " + cause, cause);
    }

    private FailedAfterCommit(String message) {
        super(message, null, false, false);
    }

    /**
     * Attached as suppressed to an {@link Error} that a step after a commit threw, which is thrown as it is: so that a
     * request is still answered as one whose change may already be saved.
     */
    static FailedAfterCommit marker() {
        return new FailedAfterCommit("An Error was thrown by a step after a committed transaction");
    }
}
