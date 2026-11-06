package com.flowtivo.tinfirm.shared.web;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.shared.api.SystemApi;
import com.flowtivo.tinfirm.shared.api.model.SystemInfo;
import com.flowtivo.tinfirm.shared.config.BuildInfo;
import com.flowtivo.tinfirm.shared.config.TelemetryStatus;

@RestController
class SystemInfoController implements SystemApi {

    private final BuildInfo build;

    private final ObjectProvider<TelemetryStatus> telemetry;

    SystemInfoController(BuildInfo build, ObjectProvider<TelemetryStatus> telemetry) {
        this.build = build;
        this.telemetry = telemetry;
    }

    /** {@link BuildInfo#commitOf}, which this controller's tests pin. */
    static @Nullable String commitOf(@Nullable String revision, @Nullable GitProperties git) {
        return BuildInfo.commitOf(revision, git);
    }

    @Override
    public ResponseEntity<SystemInfo> getSystemInfo() {
        // Tinfirm sends nothing but error reports, and those only while their delivery is automatic (ADR-0070).
        TelemetryStatus status = telemetry.getIfAvailable();
        return ResponseEntity.ok(new SystemInfo(build.version(), status != null && status.enabled())
            .commit(build.commit()));
    }
}
