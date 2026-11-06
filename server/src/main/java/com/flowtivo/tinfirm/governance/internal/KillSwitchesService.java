package com.flowtivo.tinfirm.governance.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.CompanyRunsDependents;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.governance.TriggerSwitchReads;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.PrincipalSwitches;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.api.model.KillSwitch;
import com.flowtivo.tinfirm.shared.api.model.KillSwitchCount;
import com.flowtivo.tinfirm.shared.api.model.KillSwitchKind;
import com.flowtivo.tinfirm.shared.api.model.KillSwitches;
import com.flowtivo.tinfirm.shared.api.model.MemberSuspension;
import com.flowtivo.tinfirm.shared.api.model.PausedTrigger;
import com.flowtivo.tinfirm.shared.api.model.RunsLiftResult;
import com.flowtivo.tinfirm.shared.api.model.RunsStop;
import com.flowtivo.tinfirm.shared.api.model.RunsStopResult;
import com.flowtivo.tinfirm.shared.api.model.SystemPauseResult;
import com.flowtivo.tinfirm.shared.api.model.SystemResumeResult;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.system.SystemSwitches;
import com.flowtivo.tinfirm.system.Systems;

/**
 * The kill switches on one page (the package 6 contract, §5.4; {@code GET /kill-switches}) and the switches pulled
 * through it, each by its owner's public API. Admins (OWNER, ADMIN, an instance admin under break-glass) see every
 * switch that is on, with who, when and why; a member sees the company's stop and their own suspension, which a
 * suspended member may still read (open point 24).
 */
@Service
class KillSwitchesService {

    private final CompanyAccessGuard companyAccess;

    private final CompanySwitches companySwitches;

    private final CompanyDirectory companies;

    private final SystemSwitches systemSwitches;

    private final Systems systems;

    private final PrincipalSwitches principalSwitches;

    private final Principals principals;

    private final Agents agents;

    private final UserDirectory users;

    private final ObjectProvider<TriggerSwitchReads> triggers;

    KillSwitchesService(CompanyAccessGuard companyAccess, CompanySwitches companySwitches, CompanyDirectory companies,
            SystemSwitches systemSwitches, Systems systems, PrincipalSwitches principalSwitches, Principals principals,
            Agents agents, UserDirectory users, ObjectProvider<TriggerSwitchReads> triggers) {
        this.companyAccess = companyAccess;
        this.companySwitches = companySwitches;
        this.companies = companies;
        this.systemSwitches = systemSwitches;
        this.systems = systems;
        this.principalSwitches = principalSwitches;
        this.principals = principals;
        this.agents = agents;
        this.users = users;
        this.triggers = triggers;
    }

    static boolean admin(CompanyAccess access) {
        return access.viaInstanceAdmin() || access.role() == CompanyRole.OWNER || access.role() == CompanyRole.ADMIN;
    }

    @Transactional(readOnly = true)
    public KillSwitches page(UUID companyId) {
        return page(companyAccess.requireAllowingSuspended(companyId, CompanyPermission.VIEW_COMPANY));
    }

    /**
     * The page for an access the caller holds already: the guard is never asked again once the transaction recorded an
     * audit event, since an instance admin's break-glass is audited in a transaction of its own, which would wait on
     * the chain head this one holds.
     */
    private KillSwitches page(CompanyAccess access) {
        UUID companyId = access.companyId();
        UUID userId = CurrentActor.requireUser().id();
        boolean admin = admin(access);
        Names names = new Names();
        List<Pending> switches = new ArrayList<>();

        Optional<CompanySwitches.RunsStop> stop = companySwitches.runsStopped(companyId);
        RunsStop runsStop = stop.map(found -> runsStop(found, names)).orElse(null);
        stop.ifPresent(found -> switches.add(new Pending(KillSwitchKind.RUNS_STOPPED, companyId,
                companies.find(companyId).map(CompanyDirectory.CompanySummary::name).orElse("The company"),
                found.stoppedAt(), found.byUserId(), found.reason(), null, null, null)));

        Optional<PrincipalSwitches.Suspension> mine = principalSwitches.ofMember(companyId, userId)
            .filter(suspension -> suspension.status() == PrincipalStatus.SUSPENDED);
        Pending mySuspension = mine.map(suspension -> suspended(suspension, names, userId)).orElse(null);

        if (admin) {
            TriggerSwitchReads reads = triggers.getIfAvailable();
            for (SystemSwitches.Pause pause : systemSwitches.paused(companyId)) {
                List<PausedTrigger> paused = reads == null ? List.of() : reads
                    .pausedBySystem(companyId, pause.systemId())
                    .stream()
                    .map(trigger -> new PausedTrigger(trigger.id(), trigger.name())
                        .authorPrincipalId(trigger.authorPrincipalId())
                        .authorName(trigger.authorPrincipalId() != null
                                ? principals.displayNames(companyId, List.of(trigger.authorPrincipalId()))
                                    .get(trigger.authorPrincipalId())
                                : null))
                    .toList();
                switches.add(new Pending(KillSwitchKind.SYSTEM_PAUSED, pause.systemId(), pause.name(),
                        pause.pausedAt(), pause.byUserId(), pause.reason(), null, paused, null));
            }
            List<PrincipalSwitches.Suspension> suspended = principalSwitches.suspended(companyId);
            Map<UUID, String> principalNames = principals.displayNames(companyId,
                    suspended.stream().map(PrincipalSwitches.Suspension::principalId).toList());
            for (PrincipalSwitches.Suspension suspension : suspended) {
                switches.add(new Pending(suspension.kind() == PrincipalKind.SERVICE
                        ? KillSwitchKind.SERVICE_IDENTITY_SUSPENDED : KillSwitchKind.MEMBER_SUSPENDED,
                        suspension.principalId(), principalNames.getOrDefault(suspension.principalId(), "deleted user"),
                        suspension.since(), suspension.byUserId(), suspension.reason(),
                        suspension.cause() != null ? suspension.cause().name() : null, null,
                        suspension.kind() == PrincipalKind.MEMBER ? suspension.userId() : null));
            }
            for (Agents.PausedAgent agent : agents.paused(companyId)) {
                switches.add(new Pending(KillSwitchKind.AGENT_PAUSED, agent.id(), agent.name(), agent.pausedAt(),
                        agent.pausedBy(), null, null, null, null));
            }
            if (reads != null) {
                for (TriggerSwitchReads.PausedTrigger trigger : reads.paused(companyId)) {
                    switches.add(new Pending(KillSwitchKind.TRIGGER_PAUSED, trigger.id(), trigger.name(),
                            trigger.pausedAt(), trigger.pausedBy(), null, trigger.reason(), null, null));
                }
            }
            if (!systems.personalSystemsOn(companyId)) {
                switches.add(new Pending(KillSwitchKind.PERSONAL_MCP_SERVERS_OFF, companyId,
                        "Personal MCP servers", null, null, null, null, null, null));
            }
        }
        else if (mySuspension != null) {
            switches.add(mySuspension);
        }
        switches.sort(Comparator.comparing((Pending pending) -> pending.since() != null ? pending.since()
                : Instant.EPOCH).reversed());
        names.load(switches);
        List<KillSwitch> views = switches.stream().map(names::view).toList();
        return new KillSwitches(counts(switches), views)
            .runsStop(runsStop)
            .mySuspension(mySuspension != null ? names.view(mySuspension) : null);
    }

    /** {@code POST /runs-stop}. */
    public RunsStopResult stop(UUID companyId, String reason) {
        CompanySwitches.Stopped stopped = companySwitches.stopRuns(companyId, reason);
        return new RunsStopResult(runsStop(stopped.stop(), new Names()), stopped.runsCancelled().size());
    }

    /** {@code DELETE /runs-stop}: what the lift released, and every other switch that stays on. */
    @Transactional
    public RunsLiftResult lift(UUID companyId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.STOP_RUNS);
        CompanyRunsDependents.Lifted lifted = companySwitches.liftRuns(companyId);
        List<KillSwitchCount> stillOn = page(access).getCounts();
        return new RunsLiftResult(lifted.triggersArmed(), lifted.heldRowsReleased(), stillOn);
    }

    public SystemPauseResult pause(UUID companyId, UUID systemId, String reason, boolean stopRunning) {
        SystemSwitches.Paused paused = systemSwitches.pause(companyId, systemId, reason, stopRunning);
        return new SystemPauseResult(systemId, utc(paused.pause().pausedAt()), paused.pause().reason(),
                paused.runsCancelled().size(), paused.triggersPaused());
    }

    public SystemResumeResult resume(UUID companyId, UUID systemId, boolean resumeTriggers) {
        SystemSwitches.Resumed resumed = systemSwitches.resume(companyId, systemId, resumeTriggers);
        return new SystemResumeResult(systemId, resumed.triggersResumed(), resumed.triggersStillPaused());
    }

    public MemberSuspension suspend(UUID companyId, UUID userId, String reason) {
        return suspension(principalSwitches.suspendMember(companyId, userId, reason));
    }

    public MemberSuspension resumeMember(UUID companyId, UUID userId) {
        return suspension(principalSwitches.resumeMember(companyId, userId));
    }

    private static MemberSuspension suspension(PrincipalSwitches.Suspension suspension) {
        return new MemberSuspension(Objects.requireNonNull(suspension.userId()), suspension.principalId(),
                com.flowtivo.tinfirm.shared.api.model.PrincipalStatus.fromValue(suspension.status().name()))
            .suspendedAt(suspension.since() != null ? utc(suspension.since()) : null)
            .reason(suspension.reason())
            .cause(suspension.cause() != null ? MemberSuspension.CauseEnum.fromValue(suspension.cause().name())
                    : null);
    }

    private Pending suspended(PrincipalSwitches.Suspension suspension, Names names, UUID userId) {
        return new Pending(KillSwitchKind.MEMBER_SUSPENDED, suspension.principalId(), "You", suspension.since(),
                suspension.byUserId(), suspension.reason(),
                suspension.cause() != null ? suspension.cause().name() : null, null, suspension.userId());
    }

    private RunsStop runsStop(CompanySwitches.RunsStop stop, Names names) {
        String by = stop.byUserId() != null ? names.user(stop.byUserId()) : null;
        return new RunsStop(utc(stop.stoppedAt()), stop.reason()).byUserId(stop.byUserId()).byName(by);
    }

    private static List<KillSwitchCount> counts(List<Pending> switches) {
        Map<KillSwitchKind, Integer> counts = new EnumMap<>(KillSwitchKind.class);
        switches.forEach(pending -> counts.merge(pending.kind(), 1, Integer::sum));
        return counts.entrySet().stream().map(entry -> new KillSwitchCount(entry.getKey(), entry.getValue())).toList();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    /** A switch before the names of who pulled them are read, all at once. */
    /** @param userId a suspended member's user, which resuming them takes; null for the other kinds */
    private record Pending(KillSwitchKind kind, UUID targetId, String name, @Nullable Instant since,
            @Nullable UUID by, @Nullable String reason, @Nullable String cause,
            @Nullable List<PausedTrigger> pausedTriggers, @Nullable UUID userId) {
    }

    /** The users' display names a page shows, read once. */
    private final class Names {

        private final Map<UUID, UserSummary> users = new java.util.HashMap<>();

        void load(List<Pending> switches) {
            Set<UUID> ids = new HashSet<>();
            switches.stream().map(Pending::by).filter(Objects::nonNull).forEach(ids::add);
            ids.removeAll(users.keySet());
            if (!ids.isEmpty()) {
                users.putAll(KillSwitchesService.this.users.findAllById(ids));
            }
        }

        @Nullable String user(UUID userId) {
            if (!users.containsKey(userId)) {
                users.putAll(KillSwitchesService.this.users.findAllById(List.of(userId)));
            }
            UserSummary user = users.get(userId);
            return user != null ? user.displayName() : null;
        }

        KillSwitch view(Pending pending) {
            return new KillSwitch(pending.kind(), pending.targetId(), pending.name())
                .since(utc(pending.since()))
                .byUserId(pending.by())
                .byName(pending.by() != null ? user(pending.by()) : null)
                .reason(pending.reason())
                .cause(pending.cause())
                .pausedTriggers(pending.pausedTriggers())
                .userId(pending.userId());
        }
    }
}
