package com.flowtivo.tinfirm.governance.internal;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.shared.time.TimeSource;

import jakarta.annotation.PreDestroy;

/**
 * The compaction of agents' memories after the daily retention sweep (ADR-0063), off the scheduler's thread: the sweep
 * queues each company it purged, and one virtual thread compacts them one after another, in the order queued, each as
 * of its own start. Spring runs every fixed-delay job on one scheduler thread, so a compaction there (up to 15 minutes
 * of model requests a company) would hold up the next retention pass and every other job with it: the run dispatcher,
 * the runners' heartbeats, the polls. A company waiting or being compacted is not queued again, so the queue holds each
 * company at most once and a slow one delays the others by its own budget only. The queue lives in memory: after a
 * restart, a company's memories stay due and its next sweep queues it again.
 */
@Component
class MemoryCompactions {

    private static final Logger log = LoggerFactory.getLogger(MemoryCompactions.class);

    private final AgentMemory memory;

    private final TimeSource time;

    /** The companies queued whose compaction has not ended. */
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    private final ExecutorService executor = Executors
        .newSingleThreadExecutor(Thread.ofVirtual().name("memory-compaction-", 0).factory());

    MemoryCompactions(AgentMemory memory, TimeSource time) {
        this.memory = memory;
        this.time = time;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Queues the company's compaction and returns at once; answers false when it is queued or being compacted already,
     * or the server is stopping.
     */
    boolean queue(UUID companyId) {
        if (!queued.add(companyId)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    compact(companyId, time.now());
                }
                finally {
                    queued.remove(companyId);
                }
            });
            return true;
        }
        catch (RejectedExecutionException ex) {
            queued.remove(companyId);
            return false;
        }
    }

    /** Compacts the company's due memories as of {@code now} on the caller's thread; a failure is logged. */
    void compact(UUID companyId, Instant now) {
        try {
            memory.compact(companyId, now);
        }
        catch (RuntimeException ex) {
            log.warn("The compaction of the agents' memories of company {} failed", companyId, ex);
        }
    }
}
