package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;

import jakarta.annotation.PreDestroy;

/**
 * Work that calls a system once a transaction committed (CONVENTIONS §10: no call inside a transaction, and a
 * transaction's {@code afterCommit} still holds its connection): an approved tool call, a parked call once its binding
 * is bound, an approved change request's opening. Each runs on a virtual thread of its own, as actor SYSTEM in the
 * company's tenant context; a failure is logged, and what it should have recorded stays as it was (the decision's
 * result empty, the parked call parked).
 */
@Component
class ConversationWorkers {

    private static final Logger log = LoggerFactory.getLogger(ConversationWorkers.class);

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
        .name("conversation-work-", 0)
        .factory());

    private final AtomicInteger running = new AtomicInteger();

    /** Runs {@code work} once the current transaction committed (at once without one), on a thread of its own. */
    void afterCommit(UUID companyId, String what, Runnable work) {
        AfterCommit.run(() -> execute(companyId, what, work));
    }

    /** Runs {@code work} now, on a thread of its own. */
    void execute(UUID companyId, String what, Runnable work) {
        running.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    SystemContext.runInCompany(companyId, work);
                }
                catch (RuntimeException ex) {
                    log.warn("{} failed", what, ex);
                }
                finally {
                    running.decrementAndGet();
                }
            });
        }
        catch (RuntimeException ex) {
            running.decrementAndGet();
            log.warn("{} could not start", what, ex);
        }
    }

    /**
     * Runs {@code work} now on a thread of its own, as SYSTEM in the company, for a caller that waits for it within a
     * bound ({@link ToolCalls}: a run's tool call within the tool timeout); cancelling the answer interrupts the thread.
     */
    Future<?> submit(UUID companyId, Runnable work) {
        running.incrementAndGet();
        try {
            return executor.submit(() -> {
                try {
                    SystemContext.runInCompany(companyId, work);
                }
                finally {
                    running.decrementAndGet();
                }
            });
        }
        catch (RuntimeException ex) {
            running.decrementAndGet();
            throw ex;
        }
    }

    /** How many works are queued or running: for tests that wait for them. */
    int running() {
        return running.get();
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
