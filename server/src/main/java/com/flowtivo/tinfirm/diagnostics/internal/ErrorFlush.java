package com.flowtivo.tinfirm.diagnostics.internal;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.diagnostics.internal.ErrorCapture.Pending;
import com.flowtivo.tinfirm.shared.config.BuildInfo;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * Adds what each replica captured to the reports every 30 seconds (ADR-0070, §3), in one short transaction; what it
 * could not store waits in memory for the next flush. A {@code worker} replica then deletes the reports not seen for
 * 90 days and the least recently seen beyond 1000. Its own failures are logged at {@code WARN}, which the capture does
 * not take.
 */
@Component
class ErrorFlush {

    private static final Logger log = LoggerFactory.getLogger(ErrorFlush.class);

    private final ErrorCapture capture;

    private final ErrorLogCapture logCapture;

    private final ErrorReportStore store;

    private final SystemTransactions transactions;

    private final BuildInfo build;

    private final TimeSource time;

    private final ErrorReportProperties properties;

    private final boolean worker;

    ErrorFlush(ErrorCapture capture, ErrorLogCapture logCapture, ErrorReportStore store,
            SystemTransactions transactions, BuildInfo build, TimeSource time, ErrorReportProperties properties,
            TinfirmProperties tinfirm) {
        this.capture = capture;
        this.logCapture = logCapture;
        this.store = store;
        this.transactions = transactions;
        this.build = build;
        this.time = time;
        this.properties = properties;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
    }

    @Scheduled(fixedDelayString = "${tinfirm.error-reports.flush-tick:PT30S}",
            initialDelayString = "${tinfirm.error-reports.flush-tick:PT30S}")
    void scheduledFlush() {
        try {
            logCapture.ensureAttached();
            flush();
            if (worker) {
                prune();
            }
        }
        catch (RuntimeException ex) {
            log.warn("The error reports could not be stored; they wait for the next flush", ex);
        }
    }

    /**
     * Stores what this replica holds.
     *
     * @return the kinds of error stored
     */
    int flush() {
        List<Pending> pending = capture.drain();
        if (pending.isEmpty()) {
            return 0;
        }
        try {
            transactions.instanceWide(() -> {
                store.add(pending, build.version(), build.commit());
                return null;
            });
        }
        catch (RuntimeException ex) {
            capture.putBack(pending);
            throw ex;
        }
        return pending.size();
    }

    /** Deletes the reports past their time and beyond the limit. */
    int prune() {
        return transactions.instanceWide(
                () -> store.prune(time.now().minus(properties.keep()), properties.maxReports(),
                        properties.maxWebReports()));
    }
}
