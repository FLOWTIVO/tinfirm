package com.flowtivo.tinfirm.diagnostics.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import tools.jackson.databind.json.JsonMapper;

import com.flowtivo.tinfirm.diagnostics.internal.ErrorReportStore.ReportRow;
import com.flowtivo.tinfirm.diagnostics.internal.SentryEvents.Installation;
import com.flowtivo.tinfirm.diagnostics.internal.SentryTransport.Failed;
import com.flowtivo.tinfirm.diagnostics.internal.SentryTransport.RateLimited;
import com.flowtivo.tinfirm.diagnostics.internal.SentryTransport.Result;
import com.flowtivo.tinfirm.diagnostics.internal.SentryTransport.Sent;
import com.flowtivo.tinfirm.shared.config.BuildInfo;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * Delivers the reports with occurrences not yet sent (ADR-0070, §5): on {@code worker} replicas every 15 minutes, while
 * the mode is {@code AUTOMATIC} with a destination. A pass holds the ShedLock lock {@value #LOCK}, so that one pass
 * runs at a time across replicas: for {@code deliveryLease} from its take, extended at each report sent, so that the
 * lock of a pass whose server crashed expires, and a pass that lost its lock stops. It takes at most
 * {@code deliveryBatch} reports, the longest waiting first, and sends each as one event, outside any transaction, on a
 * thread of its own (CONVENTIONS §10). A failure ends the pass and is kept on the row for the page; a {@code 429} keeps
 * every pass back until its {@code Retry-After}.
 */
@Component
class ErrorDelivery {

    private static final Logger log = LoggerFactory.getLogger(ErrorDelivery.class);

    /** The delivery's ShedLock lock, in {@code shedlock}. */
    static final String LOCK = "error-reports.delivery";

    /** What a pass did: the reports it sent, and why it stopped early, if it did. */
    record Outcome(boolean ran, int sent, @Nullable String error) {

        static final Outcome SKIPPED = new Outcome(false, 0, null);
    }

    private final ErrorReportingSettings reporting;

    private final ErrorReportStore store;

    private final SystemTransactions transactions;

    private final BuildInfo build;

    private final TimeSource time;

    private final ErrorReportProperties properties;

    private final JsonMapper json;

    private final LockProvider locks;

    private final SentryTransport transport;

    private final boolean worker;

    private final AtomicBoolean running = new AtomicBoolean();

    ErrorDelivery(ErrorReportingSettings reporting, ErrorReportStore store, SystemTransactions transactions,
            BuildInfo build, TimeSource time, ErrorReportProperties properties, JsonMapper json,
            LockProvider locks, TinfirmProperties tinfirm) {
        this.reporting = reporting;
        this.store = store;
        this.transactions = transactions;
        this.build = build;
        this.time = time;
        this.properties = properties;
        this.json = json;
        this.locks = locks;
        this.transport = new SentryTransport(reporting.proxy(), properties.timeout());
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
    }

    @Scheduled(fixedDelayString = "${tinfirm.error-reports.delivery-tick:PT15M}",
            initialDelayString = "${tinfirm.error-reports.delivery-initial-delay:PT2M}")
    void scheduledDelivery() {
        try {
            if (!worker || !reporting.delivering() || !running.compareAndSet(false, true)) {
                return;
            }
        }
        catch (RuntimeException ex) {
            log.warn("The error reports' mode could not be read; no delivery this time", ex);
            return;
        }
        Thread.ofVirtual().name("error-report-delivery").start(() -> {
            try {
                Outcome outcome = deliver();
                if (outcome.error() != null) {
                    log.warn("The delivery of the error reports stopped after {}: {}", outcome.sent(), outcome.error());
                }
            }
            catch (RuntimeException ex) {
                log.warn("The delivery of the error reports failed", ex);
            }
            finally {
                running.set(false);
            }
        });
    }

    /** One pass on the caller's thread, outside any transaction; skipped while not delivering or another pass runs. */
    Outcome deliver() {
        Dsn dsn = reporting.destination();
        if (dsn == null || !reporting.delivering()) {
            return Outcome.SKIPPED;
        }
        Optional<SimpleLock> taken = locks.lock(
                new LockConfiguration(time.now(), LOCK, properties.deliveryLease(), Duration.ZERO));
        if (taken.isEmpty()) {
            return Outcome.SKIPPED;
        }
        HeldLock lock = new HeldLock(taken.get());
        try {
            return pass(dsn, lock);
        }
        finally {
            try {
                lock.release();
            }
            catch (RuntimeException ex) {
                log.warn("The delivery's lock could not be released; it expires by itself", ex);
            }
        }
    }

    /** A pass that holds the lock: skipped while a {@code 429} asks to wait. */
    private Outcome pass(Dsn dsn, HeldLock lock) {
        Instant start = time.now();
        boolean started = transactions.instanceWide(() -> {
            store.ensureSettings();
            return store.startDelivery(start);
        });
        if (!started) {
            return Outcome.SKIPPED;
        }
        int sent = 0;
        String error = null;
        Instant retryAfter = null;
        try {
            Installation installation = new Installation(
                    transactions.instanceWide(() -> store.settings().instanceId()), reporting.label(), build.version());
            List<ReportRow> rows = transactions.instanceWide(() -> store.undelivered(properties.deliveryBatch()));
            for (ReportRow row : rows) {
                // The mode is read again before each send: turning delivery off stops a pass that is running, at
                // the latest once the replica's view of the admin's choice is fresh (30 seconds).
                if (!reporting.delivering()) {
                    break;
                }
                byte[] event = json.writeValueAsString(SentryEvents.event(row, installation))
                    .getBytes(StandardCharsets.UTF_8);
                Result result = transport.send(dsn, SentryEvents.envelope(SentryEvents.eventId(row), event, time.now()),
                        build.version(), time.now());
                if (result instanceof Sent) {
                    transactions.instanceWide(() -> {
                        store.delivered(row.id(), row.occurrences(), time.now());
                        return null;
                    });
                    sent++;
                    // A pass may take longer than its lock (a slow destination): it extends it as it goes, and
                    // stops once the lock expired or another replica holds it.
                    if (!lock.extend(properties.deliveryLease())) {
                        error = "The pass lost its lock: it expired, or another replica holds it";
                        break;
                    }
                }
                else if (result instanceof RateLimited limited) {
                    error = "The destination " + dsn.host() + " asked to wait until " + limited.until();
                    retryAfter = limited.until();
                    break;
                }
                else if (result instanceof Failed failed) {
                    error = failed.reason();
                    break;
                }
            }
        }
        catch (RuntimeException ex) {
            error = "The delivery failed: " + ex.getClass().getSimpleName();
            throw ex;
        }
        finally {
            String reason = error == null ? null : Scrubber.cut(error, 300);
            Instant wait = retryAfter;
            transactions.instanceWide(() -> {
                store.finishDelivery(time.now(), reason, wait);
                return null;
            });
        }
        return new Outcome(true, sent, error);
    }

    /** The pass's lock, which each extension replaces; none once it was lost, so that it is not released either. */
    private static final class HeldLock {

        private @Nullable SimpleLock lock;

        HeldLock(SimpleLock lock) {
            this.lock = lock;
        }

        /** Holds it for {@code lease} from now; false once it expired or another replica holds it. */
        boolean extend(Duration lease) {
            if (lock != null) {
                lock = lock.extend(lease, Duration.ZERO).orElse(null);
            }
            return lock != null;
        }

        void release() {
            if (lock != null) {
                lock.unlock();
                lock = null;
            }
        }
    }
}
