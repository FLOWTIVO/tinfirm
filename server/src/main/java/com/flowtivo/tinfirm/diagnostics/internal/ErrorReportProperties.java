package com.flowtivo.tinfirm.diagnostics.internal;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The settings of error reporting (ADR-0070; CONVENTIONS §4).
 *
 * @param mode            {@code TINFIRM_ERROR_REPORTS}: {@code off}, {@code local} or {@code automatic}, which the page
 *                        then shows and does not change; unset, the instance admin chooses ({@code local} until then)
 * @param dsn             {@code TINFIRM_ERROR_REPORTS_DSN}: the destination's Sentry DSN; unset, nothing is delivered
 * @param label           {@code TINFIRM_ERROR_REPORTS_LABEL}: the installation's name in its reports, if the operator
 *                        gives it one
 * @param proxy           {@code TINFIRM_ERROR_REPORTS_PROXY}: {@code http://host:port}, an HTTP proxy for the delivery
 * @param maxReports      the server's reports kept; beyond, the least recently seen goes
 * @param maxWebReports   the web app's reports kept, apart from the server's, so that a member's flood of them never
 *                        evicts the server's errors; beyond, the least recently seen goes
 * @param keep            a report not seen this long is deleted
 * @param maxPending      the kinds of error a replica holds in memory between two flushes; more are dropped
 * @param perUserMinute   the web app's errors taken from one user a minute; more are dropped
 * @param webFingerprintsPerUserHour the distinct kinds of web error (fingerprints) taken from one user an hour; an
 *                        error of a kind not yet taken from them beyond this is dropped
 * @param deliveryBatch   the reports one delivery pass sends at most
 * @param deliveryLease   how long a pass holds its ShedLock lock against the other workers, from its take or from
 *                        the last report it sent
 * @param timeout         the connect timeout of one delivery, and its whole budget from the send to the end of the
 *                        destination's answer, its body included (a destination that stalls after its headers fails
 *                        the send then)
 */
@ConfigurationProperties("tinfirm.error-reports")
record ErrorReportProperties(@Nullable ErrorReportingMode mode, @Nullable String dsn, @Nullable String label,
        @Nullable String proxy, @DefaultValue("1000") int maxReports, @DefaultValue("250") int maxWebReports,
        @DefaultValue("P90D") Duration keep, @DefaultValue("200") int maxPending, @DefaultValue("20") int perUserMinute,
        @DefaultValue("50") int webFingerprintsPerUserHour,
        @DefaultValue("100") int deliveryBatch, @DefaultValue("PT10M") Duration deliveryLease,
        @DefaultValue("PT20S") Duration timeout) {

    /** The label, blank as none. */
    @Nullable
    String labelOrNull() {
        return label == null || label.isBlank() ? null : label.strip();
    }
}
