package com.flowtivo.tinfirm.shared.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Side effects that must only happen once the current transaction has committed: messages to runner hosts,
 * live-stream notifications, dispatcher nudges. Without an active transaction the action runs at once.
 * A failing action is logged and never undoes the committed work.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safely(action);
            }
        });
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        }
        catch (RuntimeException ex) {
            log.warn("After-commit action failed", ex);
        }
    }
}
