package com.flowtivo.tinfirm.diagnostics.internal;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.diagnostics.internal.ErrorReportStore.SettingsRow;
import com.flowtivo.tinfirm.shared.api.model.ErrorReporting.ModeEnum;
import com.flowtivo.tinfirm.shared.api.model.ErrorReporting.SetByEnum;
import com.flowtivo.tinfirm.shared.config.TelemetryStatus;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * The mode of error reporting and its destination (ADR-0070, §4, §5): the operator's {@code TINFIRM_ERROR_REPORTS},
 * else the instance admin's choice ({@code LOCAL} until they make one). A wrong DSN, label or proxy stops the server
 * at startup with the reason. The admin's choice is read again at most every 30 seconds, so that a change on another
 * replica takes effect there too.
 */
@Service
class ErrorReportingSettings implements TelemetryStatus {

    private static final Logger log = LoggerFactory.getLogger(ErrorReportingSettings.class);

    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Duration CHOICE_TTL = Duration.ofSeconds(30);

    private final ErrorReportProperties properties;

    private final ErrorReportStore store;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final @Nullable Dsn dsn;

    private final @Nullable InetSocketAddress proxy;

    private volatile @Nullable Choice choice;

    private record Choice(boolean automatic, Instant readAt) {
    }

    ErrorReportingSettings(ErrorReportProperties properties, ErrorReportStore store, SystemTransactions transactions,
            AuditLog auditLog, TimeSource time) {
        this.properties = properties;
        this.store = store;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.time = time;
        this.dsn = properties.dsn() == null || properties.dsn().isBlank() ? null : Dsn.parse(properties.dsn());
        String label = properties.labelOrNull();
        if (label != null && !LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException(
                    "TINFIRM_ERROR_REPORTS_LABEL must be 1 to 64 letters, digits, dots, dashes or underscores");
        }
        this.proxy = proxy(properties.proxy());
        if (properties.mode() == ErrorReportingMode.AUTOMATIC && dsn == null) {
            log.warn("TINFIRM_ERROR_REPORTS is automatic, but TINFIRM_ERROR_REPORTS_DSN is unset: no error report is"
                    + " delivered until it is set");
        }
    }

    /** The destination, null without a DSN. */
    @Nullable
    Dsn destination() {
        return dsn;
    }

    /** The HTTP proxy of the delivery, null to connect directly. */
    @Nullable
    InetSocketAddress proxy() {
        return proxy;
    }

    @Nullable
    String label() {
        return properties.labelOrNull();
    }

    /** Whether the operator set the mode, which the page then does not change. */
    boolean setByOperator() {
        return properties.mode() != null;
    }

    /** The mode in effect now. */
    ErrorReportingMode mode() {
        ErrorReportingMode set = properties.mode();
        if (set != null) {
            return set;
        }
        Choice current = choice;
        if (current == null || current.readAt().plus(CHOICE_TTL).isBefore(time.now())) {
            current = new Choice(transactions.instanceWide(() -> {
                store.ensureSettings();
                return store.settings().automatic();
            }), time.now());
            choice = current;
        }
        return current.automatic() ? ErrorReportingMode.AUTOMATIC : ErrorReportingMode.LOCAL;
    }

    /** Whether reports are delivered: the mode is {@code AUTOMATIC} and there is a destination. */
    boolean delivering() {
        return dsn != null && mode() == ErrorReportingMode.AUTOMATIC;
    }

    @Override
    public boolean enabled() {
        return delivering();
    }

    /** The settings as the page shows them; the row is made at the first read. */
    @Transactional
    com.flowtivo.tinfirm.shared.api.model.ErrorReporting read() {
        CurrentActor.requireInstanceAdmin();
        store.ensureSettings();
        SettingsRow row = store.settings();
        choice = new Choice(row.automatic(), time.now());
        return view(row, store.undeliveredCount());
    }

    /**
     * The instance admin's choice: {@code AUTOMATIC} or {@code LOCAL}; audited {@code error_reporting.changed} in the
     * instance chain when it changes.
     */
    @Transactional
    com.flowtivo.tinfirm.shared.api.model.ErrorReporting update(boolean automatic) {
        AuthenticatedUser admin = CurrentActor.requireInstanceAdmin();
        if (setByOperator()) {
            throw ApiException.conflict(ProblemCode.ERROR_REPORTING_SET_BY_OPERATOR,
                    "TINFIRM_ERROR_REPORTS sets the mode of error reporting; its operator changes it");
        }
        if (automatic && dsn == null) {
            throw ApiException.conflict(ProblemCode.ERROR_REPORTING_NO_DESTINATION,
                    "Automatic delivery needs a destination, TINFIRM_ERROR_REPORTS_DSN, which this installation lacks");
        }
        store.ensureSettings();
        if (store.lockAutomatic() != automatic) {
            store.setAutomatic(automatic, time.now(), admin.id());
            auditLog.record(AuditRecord.instance(AuditAction.ERROR_REPORTING_CHANGED, CurrentActor.actor())
                .target("error_reporting", "instance")
                .detail("mode", automatic ? ErrorReportingMode.AUTOMATIC.name() : ErrorReportingMode.LOCAL.name())
                .build());
        }
        SettingsRow row = store.settings();
        choice = new Choice(row.automatic(), time.now());
        return view(row, store.undeliveredCount());
    }

    private com.flowtivo.tinfirm.shared.api.model.ErrorReporting view(SettingsRow row, long pending) {
        ErrorReportingMode mode = properties.mode() != null ? properties.mode()
                : row.automatic() ? ErrorReportingMode.AUTOMATIC : ErrorReportingMode.LOCAL;
        return new com.flowtivo.tinfirm.shared.api.model.ErrorReporting(ModeEnum.fromValue(mode.name()),
                setByOperator() ? SetByEnum.OPERATOR : SetByEnum.ADMIN, row.instanceId(), pending)
            .destination(dsn != null ? dsn.host() : null)
            .label(label())
            .changedAt(ErrorReportViews.offset(row.changedAt()))
            .changedByUserId(row.changedBy())
            .lastAttemptAt(ErrorReportViews.offset(row.lastAttemptAt()))
            .lastDeliveryAt(ErrorReportViews.offset(row.lastDeliveryAt()))
            .lastDeliveryError(row.lastDeliveryError());
    }

    private static @Nullable InetSocketAddress proxy(@Nullable String proxy) {
        if (proxy == null || proxy.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(proxy.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("TINFIRM_ERROR_REPORTS_PROXY is not a URL");
        }
        if (uri.getScheme() == null || !uri.getScheme().toLowerCase(Locale.ROOT).equals("http") || uri.getHost() == null
                || uri.getPort() < 1 || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("TINFIRM_ERROR_REPORTS_PROXY must be http://<host>:<port>");
        }
        return InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort());
    }
}
