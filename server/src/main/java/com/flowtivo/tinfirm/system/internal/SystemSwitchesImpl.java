package com.flowtivo.tinfirm.system.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.system.SystemSwitchDependents;
import com.flowtivo.tinfirm.system.SystemSwitches;

/**
 * A company system's pause and resume (the package 6 contract, §5.4, open point 6). The system's row is locked and
 * marked first, then {@link SystemSwitchDependents} act above the module (each locking what it changes, conversations
 * before their runs), then the switch is audited, the reason never in the chain, and announced.
 */
@Component
class SystemSwitchesImpl implements SystemSwitches {

    private final SystemAccess access;

    private final SystemStore store;

    private final ObjectProvider<SystemSwitchDependents> dependents;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    SystemSwitchesImpl(SystemAccess access, SystemStore store, ObjectProvider<SystemSwitchDependents> dependents,
            AuditLog auditLog, ApplicationEventPublisher events, TimeSource time) {
        this.access = access;
        this.store = store;
        this.dependents = dependents;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    @Override
    @Transactional
    public Paused pause(UUID companyId, UUID systemId, String reason, boolean stopRunning) {
        SystemStore.SystemRow system = companySystem(companyId, systemId);
        String why = requireReason(reason);
        if (system.paused()) {
            return new Paused(pauseOf(companyId, systemId), List.of(), 0);
        }
        store.pause(companyId, systemId, time.now(), currentUser(), why);
        List<UUID> cancelled = new ArrayList<>();
        int triggers = 0;
        for (SystemSwitchDependents dependent : dependents.orderedStream().toList()) {
            SystemSwitchDependents.Pausing pausing = dependent.systemPausing(companyId, systemId, stopRunning);
            cancelled.addAll(pausing.runsCancelled());
            triggers += pausing.triggersPaused();
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_PAUSED, CurrentActor.actor())
            .target(SystemService.TARGET, systemId)
            .detail("stopRunning", stopRunning)
            .detail("runsCancelled", cancelled.stream().map(UUID::toString).toList())
            .detail("triggersPaused", triggers)
            .build());
        events.publishEvent(new SystemEvents.SystemPaused(companyId, systemId));
        return new Paused(pauseOf(companyId, systemId), cancelled, triggers);
    }

    @Override
    @Transactional
    public Resumed resume(UUID companyId, UUID systemId, boolean resumeTriggers) {
        SystemStore.SystemRow system = companySystem(companyId, systemId);
        if (!system.paused()) {
            return new Resumed(systemId, 0, 0);
        }
        store.resume(companyId, systemId, time.now());
        int resumed = 0;
        int still = 0;
        for (SystemSwitchDependents dependent : dependents.orderedStream().toList()) {
            SystemSwitchDependents.Resuming resuming = dependent.systemResuming(companyId, systemId, resumeTriggers);
            resumed += resuming.triggersResumed();
            still += resuming.triggersStillPaused();
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.SYSTEM_RESUMED, CurrentActor.actor())
            .target(SystemService.TARGET, systemId)
            .detail("resumeTriggers", resumeTriggers)
            .detail("triggersResumed", resumed)
            .build());
        events.publishEvent(new SystemEvents.SystemResumed(companyId, systemId));
        return new Resumed(systemId, resumed, still);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Pause> paused(UUID companyId) {
        return store.paused(companyId).stream().map(SystemSwitchesImpl::pause).toList();
    }

    /** The company system, locked, for an admin; a personal server is no system's pause. */
    private SystemStore.SystemRow companySystem(UUID companyId, UUID systemId) {
        SystemAccess.Caller caller = access.caller(companyId);
        SystemStore.SystemRow system = access.managed(caller, systemId);
        if (system.personal()) {
            throw ApiException.badRequest(ProblemCode.PERSONAL_SYSTEM, "A personal MCP server is not paused on its "
                    + "own: the company's switch of personal servers stops them");
        }
        return system;
    }

    private Pause pauseOf(UUID companyId, UUID systemId) {
        return pause(store.pauseOf(companyId, systemId).orElseThrow());
    }

    private static Pause pause(SystemStore.PauseRow row) {
        return new Pause(row.systemId(), row.name(), row.pausedAt(), row.pausedBy(), row.reason());
    }

    private static @Nullable UUID currentUser() {
        return CurrentActor.user().map(AuthenticatedUser::id).orElse(null);
    }

    private static String requireReason(@Nullable String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 500) {
            throw ApiException.validation("reason must be 1 to 500 characters");
        }
        return why;
    }
}
