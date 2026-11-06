package com.flowtivo.tinfirm.shared.tx;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Work that waits on another system runs outside a database transaction (CONVENTIONS §10): a transaction would hold
 * its connection, and the locks it took, for as long as the other system takes to answer. Such work reads what it
 * needs in a short transaction of its own before the call and writes the outcome in another after it.
 */
public final class NoTransaction {

    private NoTransaction() {
    }

    /**
     * Refuses to go on inside a transaction, which is a programming error: the transaction's synchronisation, its
     * {@code afterCommit} and {@code afterCompletion} callbacks included, still holds the connection.
     *
     * @param what the work, for the message
     * @throws IllegalStateException inside a transaction
     */
    public static void require(String what) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(what + " must run outside a database transaction (CONVENTIONS §10)");
        }
    }
}
