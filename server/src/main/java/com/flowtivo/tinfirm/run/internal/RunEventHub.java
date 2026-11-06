package com.flowtivo.tinfirm.run.internal;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.tx.AfterCommit;

/**
 * In-process fan-out of "run changed" signals to live SSE streams (one replica: an accepted limit). A signal carries
 * no data: the stream reads events and status from the database, which stays the only source of truth, so a missed or
 * duplicated signal can never reorder or lose events.
 */
@Component
class RunEventHub {

    private final ConcurrentMap<UUID, Set<Runnable>> listeners = new ConcurrentHashMap<>();

    /** Registers a listener; close the returned subscription to remove it. */
    AutoCloseable subscribe(UUID runId, Runnable listener) {
        listeners.computeIfAbsent(runId, id -> ConcurrentHashMap.newKeySet()).add(listener);
        return () -> listeners.computeIfPresent(runId, (id, set) -> {
            set.remove(listener);
            return set.isEmpty() ? null : set;
        });
    }

    /** Signals the run's listeners once the current transaction has committed. */
    void changedAfterCommit(UUID runId) {
        AfterCommit.run(() -> changed(runId));
    }

    void changed(UUID runId) {
        Set<Runnable> set = listeners.get(runId);
        if (set != null) {
            set.forEach(Runnable::run);
        }
    }
}
