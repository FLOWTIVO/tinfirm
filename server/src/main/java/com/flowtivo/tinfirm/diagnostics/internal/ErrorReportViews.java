package com.flowtivo.tinfirm.diagnostics.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedException;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedFrame;
import com.flowtivo.tinfirm.diagnostics.internal.ErrorReportStore.ReportRow;
import com.flowtivo.tinfirm.shared.api.model.ErrorReport;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportDetail;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportException;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportFrame;

/** The API's views of the reports. */
final class ErrorReportViews {

    private ErrorReportViews() {
    }

    static ErrorReport summary(ReportRow row) {
        return new ErrorReport(row.id(), ErrorReport.SourceEnum.fromValue(row.source().name()), row.title(),
                row.occurrences(), row.unreported(), offset(row.firstSeenAt()), offset(row.lastSeenAt()),
                row.firstVersion(), row.lastVersion())
            .message(row.message())
            .logger(row.logger())
            .route(row.route())
            .reportedAt(offset(row.reportedAt()));
    }

    static ErrorReportDetail detail(ReportRow row) {
        return new ErrorReportDetail(row.id(), ErrorReportDetail.SourceEnum.fromValue(row.source().name()),
                row.title(), row.occurrences(), row.unreported(), offset(row.firstSeenAt()), offset(row.lastSeenAt()),
                row.firstVersion(), row.lastVersion(), row.exceptions().stream().map(ErrorReportViews::exception)
                    .toList())
            .message(row.message())
            .logger(row.logger())
            .route(row.route())
            .reportedAt(offset(row.reportedAt()))
            .logMessage(row.logMessage())
            .commit(row.lastCommit())
            .runtime(row.runtime());
    }

    private static ErrorReportException exception(CapturedException exception) {
        return new ErrorReportException(exception.type(), exception.frames().stream().map(ErrorReportViews::frame)
            .toList()).message(exception.message());
    }

    private static ErrorReportFrame frame(CapturedFrame frame) {
        return new ErrorReportFrame(frame.inApp())
            .module(frame.module())
            .function(frame.function())
            .file(frame.file())
            .line(frame.line())
            .column(frame.column());
    }

    /** RFC 3339 in UTC; null stays null. */
    static @Nullable OffsetDateTime offset(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
