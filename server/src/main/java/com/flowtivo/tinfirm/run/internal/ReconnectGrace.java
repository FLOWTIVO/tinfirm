package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The reconnect grace at startup: while the server was down, no host could renew a lease, so every run that holds a
 * slot gets a lease of at least now + {@code TINFIRM_RUNNER_RECONNECT_GRACE}. For an assigned run that awaits
 * {@code run.accepted}, that is how long the accept timeout waits. Its host then has the grace to reconnect and report
 * it in {@code hello.activeRuns}.
 * <p>
 * It runs once all beans exist and before the context starts its lifecycle: the web server's connector (so a runner
 * host can reconnect), the {@code @Scheduled} reaper and the dispatcher begin only after that, so neither can see a
 * lease that lapsed while the server was down before it is extended. If it fails, the server does not start. It runs
 * on every replica, whatever its roles.
 */
@Component
class ReconnectGrace implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ReconnectGrace.class);

    private final RunStore runs;

    private final SystemTransactions transactions;

    private final RunnerProperties properties;

    private final TimeSource time;

    ReconnectGrace(RunStore runs, SystemTransactions transactions, RunnerProperties properties, TimeSource time) {
        this.runs = runs;
        this.transactions = transactions;
        this.properties = properties;
        this.time = time;
    }

    @Override
    public void afterSingletonsInstantiated() {
        extendAfterStart();
    }

    /** Extends the leases of every company's runs that hold a slot; returns how many runs it extended. */
    int extendAfterStart() {
        Instant until = time.now().plus(properties.reconnectGrace());
        int extended = 0;
        for (UUID companyId : transactions.unscoped(runs::occupyingCompanies)) {
            extended += transactions.inCompany(companyId, () -> runs.extendLeases(until));
        }
        if (extended > 0) {
            log.info("Server start: {} active runs keep their lease until at least {} for their runner hosts to "
                    + "reconnect", extended, until);
        }
        return extended;
    }
}
