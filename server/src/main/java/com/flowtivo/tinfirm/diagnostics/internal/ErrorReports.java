package com.flowtivo.tinfirm.diagnostics.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.diagnostics.internal.ErrorReportStore.ReportRow;
import com.flowtivo.tinfirm.diagnostics.internal.SentryEvents.Installation;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportDetail;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportExport;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportPage;
import com.flowtivo.tinfirm.shared.config.BuildInfo;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;

/** The reports as instance admins read, download and delete them (ADR-0070, §6). */
@Service
class ErrorReports {

    private final ErrorReportStore store;

    private final ErrorReportingSettings reporting;

    private final AuditLog auditLog;

    private final BuildInfo build;

    private final TimeSource time;

    private final ErrorReportProperties properties;

    ErrorReports(ErrorReportStore store, ErrorReportingSettings reporting, AuditLog auditLog, BuildInfo build,
            TimeSource time, ErrorReportProperties properties) {
        this.store = store;
        this.reporting = reporting;
        this.auditLog = auditLog;
        this.build = build;
        this.time = time;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    ErrorReportPage list(@Nullable Integer limit, @Nullable String cursor) {
        CurrentActor.requireInstanceAdmin();
        Page<ReportRow> page = store.page(PageRequest.of(limit, cursor));
        return new ErrorReportPage(page.items().stream().map(ErrorReportViews::summary).toList())
            .nextCursor(page.nextCursor());
    }

    @Transactional(readOnly = true)
    ErrorReportDetail get(UUID reportId) {
        CurrentActor.requireInstanceAdmin();
        return ErrorReportViews.detail(store.find(reportId).orElseThrow(() -> ApiException.notFound("No such report")));
    }

    /** Deletes a report; audited {@code error_report.deleted} in the instance chain. */
    @Transactional
    void delete(UUID reportId) {
        CurrentActor.requireInstanceAdmin();
        ReportRow row = store.delete(reportId).orElseThrow(() -> ApiException.notFound("No such report"));
        auditLog.record(AuditRecord.instance(AuditAction.ERROR_REPORT_DELETED, CurrentActor.actor())
            .target("error_report", row.id())
            .detail("source", row.source().name())
            .build());
    }

    /** Every report as the event delivery sends for it, in every mode. */
    @Transactional
    ErrorReportExport export() {
        CurrentActor.requireInstanceAdmin();
        store.ensureSettings();
        Installation installation = new Installation(store.settings().instanceId(), reporting.label(),
                build.version());
        return new ErrorReportExport(ErrorReportExport.FormatEnum.TINFIRM_ERROR_REPORTS_1, installation.instanceId(),
                build.version(), ErrorReportViews.offset(time.now()),
                store.all(properties.maxReports() + properties.maxWebReports()).stream().map(row -> SentryEvents.event(row, installation)).toList())
            .label(installation.label())
            .commit(build.commit());
    }
}
