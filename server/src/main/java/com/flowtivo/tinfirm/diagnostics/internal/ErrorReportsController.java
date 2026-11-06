package com.flowtivo.tinfirm.diagnostics.internal;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.ErrorReportsApi;
import com.flowtivo.tinfirm.shared.api.model.ClientError;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportDetail;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportExport;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportPage;
import com.flowtivo.tinfirm.shared.api.model.ErrorReporting;
import com.flowtivo.tinfirm.shared.api.model.ErrorReportingUpdate;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

/**
 * {@code POST /api/client-errors} for any member's session, {@code /api/admin/error-reports/**} and
 * {@code /api/admin/error-reporting} for instance admins (the security chain checks {@code /api/admin/**}, the services
 * again).
 */
@RestController
class ErrorReportsController implements ErrorReportsApi {

    private final ErrorCapture capture;

    private final ClientErrors clientErrors;

    private final ErrorReports reports;

    private final ErrorReportingSettings reporting;

    private final HttpServletRequest request;

    ErrorReportsController(ErrorCapture capture, ClientErrors clientErrors, ErrorReports reports,
            ErrorReportingSettings reporting, HttpServletRequest request) {
        this.capture = capture;
        this.clientErrors = clientErrors;
        this.reports = reports;
        this.reporting = reporting;
        this.request = request;
    }

    @Override
    public ResponseEntity<Void> reportClientError(ClientError error) {
        AuthenticatedUser user = CurrentActor.requireUser();
        if (capture.on() && clientErrors.allow(user.id())) {
            CapturedError captured = clientErrors.of(error, request.getHeader(HttpHeaders.USER_AGENT));
            if (clientErrors.allowKind(user.id(), captured.fingerprint())) {
                capture.add(captured);
            }
        }
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ErrorReportPage> listErrorReports(Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(reports.list(limit, cursor));
    }

    @Override
    public ResponseEntity<ErrorReportExport> exportErrorReports() {
        ErrorReportExport export = reports.export();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("tinfirm-error-reports-" + export.getInstanceId() + ".json")
                .build()
                .toString())
            .body(export);
    }

    @Override
    public ResponseEntity<ErrorReportDetail> getErrorReport(UUID reportId) {
        return ResponseEntity.ok(reports.get(reportId));
    }

    @Override
    public ResponseEntity<Void> deleteErrorReport(UUID reportId) {
        reports.delete(reportId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ErrorReporting> getErrorReporting() {
        return ResponseEntity.ok(reporting.read());
    }

    @Override
    public ResponseEntity<ErrorReporting> updateErrorReporting(ErrorReportingUpdate update) {
        return ResponseEntity.ok(reporting.update(update.getAutomatic()));
    }
}
