package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The reaper, every 5 s on replicas with the {@code worker} role:
 * <ul>
 * <li>a RUNNING run whose lease expired becomes LOST (cause {@link LostCause#LEASE_EXPIRED}), its turn ended with it;
 * unless its host's connection received a frame within the lease time: a host that talks is alive, and the run gets a
 * fresh lease instead;</li>
 * <li>a run lost by lease expiry whose resume window is over gets its one-run model tokens revoked (M7): until then
 * its host might still bring it back.</li>
 * </ul>
 * Candidates come from the narrow cross-company lookups; each is re-checked under its row lock in its own company's
 * tenant context. A lease that expires ends the run, which tells its conversation: its conversation's row is locked
 * first (CONVENTIONS §10). A token's revocation writes no conversation, and locks the run alone.
 */
@Component
class RunReaper {

    private static final Logger log = LoggerFactory.getLogger(RunReaper.class);

    private static final int BATCH = 100;

    private final RunStore runs;

    private final RunLocks locks;

    private final RunCompletion completion;

    private final ModelRelay relay;

    private final SystemTransactions transactions;

    private final RunnerConnections connections;

    private final RunnerProperties runnerProperties;

    private final boolean worker;

    private final TimeSource time;

    RunReaper(RunStore runs, RunLocks locks, RunCompletion completion, ModelRelay relay,
            SystemTransactions transactions, RunnerConnections connections, RunnerProperties runnerProperties,
            TinfirmProperties tinfirm, TimeSource time) {
        this.runs = runs;
        this.locks = locks;
        this.completion = completion;
        this.relay = relay;
        this.transactions = transactions;
        this.connections = connections;
        this.runnerProperties = runnerProperties;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        this.time = time;
    }

    @Scheduled(fixedDelayString = "${tinfirm.run.reaper-interval:PT5S}")
    void scheduledReap() {
        if (worker) {
            reap();
        }
    }

    void reap() {
        try {
            expireLeases();
            revokeLostTokens();
        }
        catch (RuntimeException ex) {
            log.warn("Reaper pass failed", ex);
        }
    }

    void expireLeases() {
        Instant now = time.now();
        for (RunStore.RunRef ref : transactions.unscoped(() -> runs.leaseExpired(now, BATCH))) {
            transactions.runInCompany(ref.companyId(), () -> locks.lock(ref.runId())
                .filter(run -> run.status() == RunStatus.RUNNING && run.leaseExpiresAt() != null
                        && run.leaseExpiresAt().isBefore(time.now()))
                .ifPresent(this::expire));
        }
    }

    /** The lease of a locked RUNNING run has expired. */
    private void expire(RunStore.RunRow run) {
        Instant now = time.now();
        UUID hostId = run.runnerHostId();
        Instant heard = hostId != null ? connections.lastInbound(hostId) : null;
        if (hostId != null && heard != null && heard.isAfter(now.minus(runnerProperties.leaseTtl()))) {
            // Its heartbeats are late, but the host talks on its connection: it is alive.
            runs.renewLease(run.id(), run.epoch(), hostId, now.plus(runnerProperties.leaseTtl()));
            log.info("Run {} of conversation {}: its lease expired, but runner host {} was heard from at {}; renewed",
                    run.id(), run.conversationKey(), hostId, heard);
            return;
        }
        log.info("Run {} of conversation {} lost: its lease expired", run.id(), run.conversationKey());
        completion.lose(run, LostCause.LEASE_EXPIRED, "The runner host stopped renewing the run's lease.");
    }

    /** Revokes the model tokens of runs lost by lease expiry that can no longer be resumed. */
    void revokeLostTokens() {
        Instant before = time.now().minus(runnerProperties.lostResumeWindow());
        for (RunStore.RunRef ref : transactions.unscoped(() -> runs.lostUnrevoked(before, BATCH))) {
            transactions.runInCompany(ref.companyId(), () -> runs.lock(ref.runId())
                .filter(run -> run.status() == RunStatus.LOST)
                .ifPresent(run -> relay.revoke(run.companyId(), run.id())));
        }
    }
}
