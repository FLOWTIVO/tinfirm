package com.flowtivo.tinfirm.run.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * What goes of the session bundles (ADR-0097) in every edition, whoever stored them ({@link SessionBundles}, under
 * {@code ee/}): a conversation's when its content is purged, an older one when a run reports its session without it,
 * and in the sweep the bundles of ended conversations and of those without a turn for
 * {@code tinfirm.session-bundles.retention}, and the store's objects that no bundle names. An object goes after the
 * commit of its row; a failure leaves it to the sweeper.
 */
@Component
class SessionBundleCleanup {

    private static final Logger log = LoggerFactory.getLogger(SessionBundleCleanup.class);

    private static final int SWEEP_BATCH = 200;

    private final SessionBundleProperties properties;

    private final BundleObjects objects;

    private final SessionBundleStore store;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final RunPorts ports;

    private final boolean worker;

    SessionBundleCleanup(SessionBundleProperties properties, BundleObjects objects, SessionBundleStore store,
            SystemTransactions transactions, TimeSource time, TinfirmProperties tinfirm, RunPorts ports) {
        this.properties = properties;
        this.objects = objects;
        this.store = store;
        this.transactions = transactions;
        this.time = time;
        this.ports = ports;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
    }

    /** Whether a bundle store is configured ({@code tinfirm.session-bundles.store}). */
    boolean enabled() {
        return properties.enabled();
    }

    // ---- the end of a run -----------------------------------------------------------------------------------------

    /**
     * The host reported the run's end: a run that reports a session drops the conversation's bundle unless the run
     * stored it itself, whatever the host says of it, since an earlier bundle holds an older state of the session (or
     * another session). In the company's transaction, with the run locked.
     */
    void finished(UUID conversationId, UUID agentId, UUID runId, @Nullable String sessionId,
            RunnerMessages.@Nullable SessionBundleOutcome outcome) {
        if (!enabled() || sessionId == null) {
            return;
        }
        store.deleteUnlessOf(conversationId, agentId, runId).ifPresent(stale -> {
            log.info("Run {} reported session {} without its own bundle stored ({}); the conversation's earlier "
                    + "bundle goes", runId, sessionId, outcome != null ? outcome : "no bundle");
            deleteAfterCommit(stale);
        });
    }

    // ---- retention and erasure ------------------------------------------------------------------------------------

    /**
     * The conversation's content is purged (its run texts or events, by retention or an erasure): its bundles go
     * too, of every agent, the objects after the commit. In the caller's transaction and tenant context. Answers how
     * many went.
     */
    int purged(UUID conversationId) {
        List<UUID> objectNames = store.deleteOf(conversationId);
        objectNames.forEach(this::deleteAfterCommit);
        return objectNames.size();
    }

    // ---- the sweeper ----------------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${tinfirm.session-bundles.sweep:PT1H}", initialDelayString = "PT5M")
    void scheduledSweep() {
        if (worker && enabled()) {
            sweep();
        }
    }

    /**
     * One pass: the due bundles, then the objects no bundle names. Answers how many objects went.
     * <p>
     * A due bundle's row is deleted in its company's transaction with its conversation's row locked first (the lock
     * order of CONVENTIONS §10: the conversation before its other rows, which a turn's start and a run's end take
     * too), and only while the row still names the object the lookup found and the conversation is still due
     * ({@link SessionBundleStore#deleteDue}): a conversation that resumed since the lookup took a turn, which set its
     * {@code last_turn_at}, and its run may have replaced the bundle with a new object, which stays. The lookup's
     * cutoff is the delete's. The object goes after its row, outside the transaction. A batch that removed no row
     * ends the pass, so that it never spins on rows it cannot delete.
     */
    int sweep() {
        int deleted = 0;
        try {
            Instant cutoff = time.now().minus(properties.retention());
            List<SessionBundleStore.Due> due;
            int removed;
            do {
                due = transactions.unscoped(() -> store.due(cutoff, SWEEP_BATCH));
                removed = 0;
                for (SessionBundleStore.Due bundle : due) {
                    Optional<UUID> object = transactions.inCompany(bundle.companyId(), () -> {
                        ports.conversationLock().lock(bundle.companyId(), bundle.conversationId());
                        return store.deleteDue(bundle.conversationId(), bundle.agentId(), bundle.objectName(),
                                cutoff);
                    });
                    if (object.isPresent()) {
                        removed++;
                        if (deleteQuietly(object.get())) {
                            deleted++;
                        }
                    }
                }
            }
            while (due.size() == SWEEP_BATCH && removed > 0);
            Instant orphanBefore = time.now().minus(properties.orphanAge());
            UUID after = null;
            List<BundleObjects.StoredObject> page;
            do {
                page = objects.list(after, SWEEP_BATCH);
                List<UUID> names = page.stream().map(BundleObjects.StoredObject::name).toList();
                Set<UUID> known = transactions.unscoped(() -> store.known(names));
                for (BundleObjects.StoredObject object : page) {
                    if (!known.contains(object.name()) && object.modifiedAt().isBefore(orphanBefore)
                            && deleteQuietly(object.name())) {
                        deleted++;
                    }
                }
                after = page.isEmpty() ? null : page.getLast().name();
            }
            while (page.size() == SWEEP_BATCH);
            // What uploads that never committed left behind: a server that stopped during one (ADR-0101).
            deleted += objects.abandoned(orphanBefore);
        }
        catch (IOException | RuntimeException ex) {
            log.warn("The session bundle sweep failed; the next one tries again", ex);
        }
        if (deleted > 0) {
            log.info("The session bundle sweep deleted {} object(s)", deleted);
        }
        return deleted;
    }

    // ---- objects --------------------------------------------------------------------------------------------------

    /** Deletes the object once the transaction commits, or now outside one. */
    void deleteAfterCommit(UUID objectName) {
        AfterCommit.run(() -> deleteQuietly(objectName));
    }

    /** Deletes an object; a failure leaves it to the sweeper. */
    boolean deleteQuietly(UUID objectName) {
        try {
            objects.delete(objectName);
            return true;
        }
        catch (IOException | RuntimeException ex) {
            log.warn("Cannot delete the session bundle object {}; the sweeper tries again", objectName, ex);
            return false;
        }
    }

    /** Ids of objects, for the tests. */
    List<UUID> objectNames() throws IOException {
        List<UUID> names = new ArrayList<>();
        for (BundleObjects.StoredObject object : objects.list(null, 10_000)) {
            names.add(object.name());
        }
        return names;
    }
}
