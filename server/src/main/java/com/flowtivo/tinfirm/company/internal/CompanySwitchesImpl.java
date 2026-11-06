package com.flowtivo.tinfirm.company.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyEvents;
import com.flowtivo.tinfirm.company.CompanyRunsDependents;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;

/**
 * The company's stop of its runs (the package 6 contract, §5.4, open point 7), on the company's row. The stop and the
 * lift lock the row ({@code FOR NO KEY UPDATE}: the foreign keys' key-share locks of the company's other rows never
 * wait on it), mark it, let {@link CompanyRunsDependents} act in the modules above (each locks what it changes, the
 * conversations before their runs), and audit last, before the event. Only a person undoes the switch (G7).
 */
@Component
class CompanySwitchesImpl implements CompanySwitches {

    static final int MAX_REASON = 500;

    private final JdbcClient jdbc;

    private final CompanyAccessGuard companyAccess;

    private final ObjectProvider<CompanyRunsDependents> dependents;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    CompanySwitchesImpl(JdbcClient jdbc, CompanyAccessGuard companyAccess,
            ObjectProvider<CompanyRunsDependents> dependents, AuditLog auditLog, ApplicationEventPublisher events,
            TimeSource time) {
        this.jdbc = jdbc;
        this.companyAccess = companyAccess;
        this.dependents = dependents;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunsStop> runsStopped(UUID companyId) {
        return jdbc.sql("""
                SELECT runs_stopped_at, runs_stopped_by, runs_stopped_reason FROM company
                 WHERE id = ? AND runs_stopped_at IS NOT NULL""")
            .param(companyId)
            .query((rs, row) -> new RunsStop(rs.getObject("runs_stopped_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("runs_stopped_by", UUID.class), rs.getString("runs_stopped_reason")))
            .optional();
    }

    @Override
    @Transactional
    public Stopped stopRuns(UUID companyId, String reason) {
        companyAccess.require(companyId, CompanyPermission.STOP_RUNS);
        String why = requireReason(reason);
        lock(companyId);
        Optional<RunsStop> current = runsStopped(companyId);
        if (current.isPresent()) {
            return new Stopped(current.get(), List.of());
        }
        Instant now = time.now();
        UUID by = CurrentActor.user().map(AuthenticatedUser::id).orElse(null);
        jdbc.sql("""
                UPDATE company SET runs_stopped_at = ?, runs_stopped_by = ?, runs_stopped_reason = ?, updated_at = ?
                 WHERE id = ?""")
            .params(now.atOffset(ZoneOffset.UTC), by, why, now.atOffset(ZoneOffset.UTC), companyId)
            .update();
        List<UUID> cancelled = new ArrayList<>();
        dependents.orderedStream().forEach(dependent -> cancelled.addAll(dependent.runsStopping(companyId)));
        auditLog.record(AuditRecord.company(companyId, AuditAction.COMPANY_RUNS_STOPPED, CurrentActor.actor())
            .target("company", companyId)
            .detail("runsCancelled", cancelled.stream().map(UUID::toString).toList())
            .build());
        events.publishEvent(new CompanyEvents.RunsStopped(companyId));
        return new Stopped(new RunsStop(now, by, why), cancelled);
    }

    @Override
    @Transactional
    public CompanyRunsDependents.Lifted liftRuns(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.STOP_RUNS);
        lock(companyId);
        if (runsStopped(companyId).isEmpty()) {
            return CompanyRunsDependents.Lifted.NONE;
        }
        Instant now = time.now();
        jdbc.sql("""
                UPDATE company SET runs_stopped_at = NULL, runs_stopped_by = NULL, runs_stopped_reason = NULL,
                       runs_stopped_access_id = NULL, updated_at = ?
                 WHERE id = ?""")
            .params(now.atOffset(ZoneOffset.UTC), companyId)
            .update();
        CompanyRunsDependents.Lifted lifted = dependents.orderedStream()
            .map(dependent -> dependent.runsLifted(companyId))
            .reduce(CompanyRunsDependents.Lifted.NONE, CompanyRunsDependents.Lifted::plus);
        auditLog.record(AuditRecord.company(companyId, AuditAction.COMPANY_RUNS_RESUMED, CurrentActor.actor())
            .target("company", companyId)
            .detail("triggersArmed", lifted.triggersArmed())
            .detail("heldRows", lifted.heldRowsReleased())
            .build());
        events.publishEvent(new CompanyEvents.RunsResumed(companyId));
        return lifted;
    }

    private void lock(UUID companyId) {
        jdbc.sql("SELECT id FROM company WHERE id = ? FOR NO KEY UPDATE").param(companyId).query(UUID.class).optional();
    }

    static String requireReason(@Nullable String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > MAX_REASON) {
            throw ApiException.validation("reason must be 1 to " + MAX_REASON + " characters");
        }
        return why;
    }
}
