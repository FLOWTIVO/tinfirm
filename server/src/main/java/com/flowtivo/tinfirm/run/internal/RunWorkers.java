package com.flowtivo.tinfirm.run.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Virtual threads for work that must not run on the caller's thread: dispatcher nudges after a commit, the delivery
 * of an assignment (the turn's content, revealed outside any transaction), the server-executed tool calls of runs,
 * and the pumps of SSE streams. Deliberately not an {@code Executor} bean, so Spring Boot keeps its own task executor
 * for MVC async requests.
 */
@Component
class RunWorkers {

    private static final Logger log = LoggerFactory.getLogger(RunWorkers.class);

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("tinfirm-run-", 0).factory());

    /**
     * Starts the work on a thread of its own.
     *
     * @return false when it could not be started (shutting down): it will never run
     */
    boolean execute(String what, Runnable work) {
        try {
            executor.execute(() -> {
                try {
                    work.run();
                }
                catch (RuntimeException ex) {
                    log.warn("Background work failed: {}", what, ex);
                }
            });
            return true;
        }
        catch (RuntimeException ex) {
            log.debug("Background work rejected: {}", what, ex);
            return false;
        }
    }

    /**
     * Starts work whose caller may cancel it (interrupting its thread), such as a tool call past its timeout.
     *
     * @return the running work; a cancelled future when the work could not be started (shutting down)
     */
    Future<?> submit(String what, Runnable work) {
        try {
            return executor.submit(() -> {
                try {
                    work.run();
                }
                catch (RuntimeException ex) {
                    log.warn("Background work failed: {}", what, ex);
                }
            });
        }
        catch (RuntimeException ex) {
            log.debug("Background work rejected: {}", what, ex);
            CompletableFuture<Void> rejected = new CompletableFuture<>();
            rejected.cancel(false);
            return rejected;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
