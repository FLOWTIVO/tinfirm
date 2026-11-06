package com.flowtivo.tinfirm.model.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * Where the work on keys runs, principals' and the company's own ({@link KeyHolder}): off the request's thread, after
 * the commit of the change that asked for it, one sync of a key at a time in this process (and, through the key row's
 * lease, across replicas). A sync asked for while another of the same key waits to start is not queued twice: the
 * waiting one reads the newest state when it starts. A sync that finds the key's row held by another replica tries
 * again a little later, for up to the lease's length; the reconciliation repairs what is left. Each sync is followed by
 * what is due of the key's rotation ({@link KeyRotation}), and a previous key's grace or settling time that ends later
 * wakes a sync then, in this process (the reconciliation, should it restart meanwhile).
 */
@Component
class KeyWork implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KeyWork.class);

    private static final int STRIPES = 64;

    private static final long RETRY_SECONDS = 3;

    private static final int MAX_RETRIES = (int) (KeySync.LEASE.toSeconds() / RETRY_SECONDS) + 1;

    /** A wake comes this long after its time, so that the step it waits for is due when the sync reads it. */
    private static final long WAKE_MARGIN_MILLIS = 500;

    private final KeySync sync;

    private final KeyRotation rotation;

    private final GatewayAdapter adapter;

    private final SystemTransactions transactions;

    private final ModelSettingsStore settings;

    private final Gateways gateways;

    private final TimeSource time;

    private final ExecutorService executor = Executors
        .newThreadPerTaskExecutor(Thread.ofVirtual().name("model-keys-", 0).factory());

    private final ScheduledExecutorService delays = Executors
        .newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("model-keys-retry").factory());

    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Instant> wakes = new ConcurrentHashMap<>();

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    KeyWork(KeySync sync, KeyRotation rotation, GatewayAdapter adapter, SystemTransactions transactions,
            ModelSettingsStore settings, Gateways gateways, TimeSource time) {
        this.sync = sync;
        this.rotation = rotation;
        this.adapter = adapter;
        this.transactions = transactions;
        this.settings = settings;
        this.gateways = gateways;
        this.time = time;
        for (int index = 0; index < STRIPES; index++) {
            stripes[index] = new ReentrantLock();
        }
    }

    /** Syncs the key after the current transaction commits (at once without one), on a thread of its own. */
    void syncAfterCommit(KeyHolder holder) {
        AfterCommit.run(() -> submit(holder, 0));
    }

    /** Syncs the principal's key after the current transaction commits (at once without one). */
    void syncAfterCommit(UUID companyId, UUID principalId) {
        syncAfterCommit(KeyHolder.principal(companyId, principalId));
    }

    /** Runs the work after the current transaction commits (at once without one), on a thread of its own. */
    void runAfterCommit(String what, Runnable work) {
        AfterCommit.run(() -> executor.execute(() -> {
            try {
                work.run();
            }
            catch (RuntimeException ex) {
                log.warn("{} failed", what, ex);
            }
        }));
    }

    /**
     * Deletes a key in the company's LiteLLM after the commit, by its id, or by its alias when its id is unknown; a
     * key LiteLLM does not know is gone already. A failure is logged: the reconciliation deletes the key later.
     */
    void deleteAfterCommit(UUID companyId, @Nullable String keyId, String alias) {
        runAfterCommit("Deleting a LiteLLM key", () -> {
            // A gateway whose keys its team provides deletes them itself (ADR-0094).
            Optional<Gateway> gateway = transactions.inCompany(companyId,
                    () -> settings.find(companyId).flatMap(gateways::of).filter(Gateway::managed));
            if (gateway.isPresent()) {
                delete(gateway.get(), keyId, alias);
            }
        });
    }

    /** Deletes a key at the gateway now; a failure is logged. Never inside a transaction. */
    void delete(Gateway gateway, @Nullable String keyId, String alias) {
        try {
            boolean deleted = keyId != null ? adapter.delete(gateway, keyId) : adapter.deleteByAlias(gateway, alias);
            log.debug("LiteLLM key {} {}", alias, deleted ? "deleted" : "was gone already");
        }
        catch (LiteLlmException ex) {
            log.warn("A LiteLLM key was not deleted ({}); the reconciliation deletes it", ex.getMessage());
        }
    }

    /** What a sync and the rotation after it did. */
    record Synced(KeySync.Result sync, KeyRotation.Outcome rotation) {

        /** Whether another replica held the key's row, so that the work is to be tried again. */
        boolean busy() {
            return sync == KeySync.Result.BUSY || rotation.result() == KeyRotation.Result.BUSY;
        }
    }

    /**
     * Syncs the key now and runs what is due of its rotation, on the caller's thread, one at a time per key in this
     * process.
     */
    Synced syncNow(KeyHolder holder) {
        ReentrantLock lock = stripes[Math.floorMod(holder.workId().hashCode(), STRIPES)];
        lock.lock();
        Synced synced;
        try {
            KeySync.Result result = sync.sync(holder);
            synced = new Synced(result, rotation.run(holder));
        }
        finally {
            lock.unlock();
        }
        Instant wakeAt = synced.rotation().wakeAt();
        if (wakeAt != null) {
            wakeAt(holder, wakeAt);
        }
        return synced;
    }

    /** Syncs the key soon, off the caller's thread, trying again while another replica holds it. */
    void syncSoon(KeyHolder holder) {
        submit(holder, 0);
    }

    /**
     * Syncs the key now, on the caller's thread outside any transaction, for an answer that shows the key's status:
     * soon instead while another replica holds it. A failure is logged; the reconciliation repairs the key.
     */
    void syncForAnswer(KeyHolder holder) {
        try {
            if (syncNow(holder).busy()) {
                syncSoon(holder);
            }
        }
        catch (RuntimeException ex) {
            log.warn("Syncing the LiteLLM key of {} failed; the reconciliation syncs it", holder, ex);
        }
    }

    /** Syncs the key at the time (a previous key's grace or settling ends), unless one is due earlier. */
    private void wakeAt(KeyHolder holder, Instant at) {
        UUID id = holder.workId();
        Instant[] scheduled = {null};
        wakes.compute(id, (ignored, existing) -> {
            if (existing != null && !existing.isAfter(at)) {
                return existing;
            }
            scheduled[0] = at;
            return at;
        });
        if (scheduled[0] == null) {
            return;
        }
        long delay = Math.max(0, Duration.between(time.now(), at).toMillis()) + WAKE_MARGIN_MILLIS;
        try {
            delays.schedule(() -> {
                wakes.remove(id, at);
                submit(holder, 0);
            }, delay, TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException ex) {
            wakes.remove(id, at);
        }
    }

    private void submit(KeyHolder holder, int attempt) {
        UUID id = holder.workId();
        if (!queued.add(id)) {
            return;
        }
        executor.execute(() -> {
            queued.remove(id);
            try {
                Synced result = syncNow(holder);
                if (result.busy() && attempt < MAX_RETRIES) {
                    delays.schedule(() -> submit(holder, attempt + 1), RETRY_SECONDS, TimeUnit.SECONDS);
                }
            }
            catch (RuntimeException ex) {
                log.warn("Syncing the LiteLLM key of {} failed", holder, ex);
            }
        });
    }

    @Override
    public void destroy() {
        delays.shutdownNow();
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
