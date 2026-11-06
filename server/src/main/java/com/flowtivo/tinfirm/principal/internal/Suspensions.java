package com.flowtivo.tinfirm.principal.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.MemberStatuses;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserEvents;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.PrincipalSwitches;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Suspending and resuming principals, both kinds (the package 6 contract, §5.4; open points 5 and 24), and the state
 * of a member's departure (§5.5). A suspension stores who, when, why and its cause on the principal's row (V021),
 * audits {@code principal.suspended} with ids only (a member's {@code userId}, never a person's name: ADR-0010) and
 * publishes {@link PrincipalEvents.PrincipalSuspended}. Deactivating a user at the instance suspends their principal in
 * every company they are a member of ({@code USER_DEACTIVATED}), each audited in its company's chain; reactivating
 * lifts those suspensions and only those. The company's SCIM client suspends a member with the cause {@code SCIM}
 * (ADR-0087), which only it lifts, and an admin's suspension or the user's deactivation never lifts it. It also answers the company module's {@link MemberStatuses}.
 */
@Component
class Suspensions implements PrincipalSwitches, MemberStatuses {

    private final PrincipalStore store;

    private final CompanyAccessGuard companyAccess;

    private final CompanyDirectory companies;

    private final TenantScope tenantScope;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final UserDirectory users;

    Suspensions(PrincipalStore store, CompanyAccessGuard companyAccess, CompanyDirectory companies,
            TenantScope tenantScope, AuditLog auditLog,
            ApplicationEventPublisher events, TimeSource time, UserDirectory users) {
        this.users = users;
        this.store = store;
        this.companyAccess = companyAccess;
        this.companies = companies;
        this.tenantScope = tenantScope;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    // ---- MemberStatuses ------------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean suspended(UUID companyId, UUID userId) {
        return store.memberSuspended(companyId, userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> suspendedUsers(UUID companyId) {
        return store.suspendedUsers(companyId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, UUID> principalIds(UUID companyId, Collection<UUID> userIds) {
        return store.memberPrincipalIds(companyId, userIds);
    }

    // ---- A member's suspension -----------------------------------------------------------------------------------

    @Override
    @Transactional
    public Suspension suspendMember(UUID companyId, UUID userId, String reason) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        String why = requireReason(reason);
        if (CurrentActor.user().map(AuthenticatedUser::id).filter(userId::equals).isPresent()) {
            throw ApiException.conflict(ProblemCode.CANNOT_SUSPEND_SELF, "Nobody suspends themselves");
        }
        // Serialises with the membership's changes, so that two suspensions never take the last two active owners.
        companies.lockMemberships(companyId);
        PrincipalStore.PrincipalRow member = store.ofMember(companyId, userId, true)
            .orElseThrow(Suspensions::memberNotFound);
        if (companies.roleOf(companyId, userId).orElse(null) == CompanyRole.OWNER) {
            requireOwnerManagement(access);
            if (member.status() == PrincipalStatus.ACTIVE && lastActiveOwner(companyId, userId)) {
                throw ApiException.conflict(ProblemCode.LAST_OWNER, "The last active owner cannot be suspended");
            }
        }
        if (member.status() == PrincipalStatus.ACTIVE) {
            suspend(companyId, member.id(), why, Cause.ADMIN);
        }
        else if (member.status() == PrincipalStatus.SUSPENDED && causeOf(companyId, member.id()) == Cause.SCIM) {
            // ADR-0087, decision 7: the admin's block is kept apart from SCIM's, so that SCIM's reactivation never
            // lifts it; the principal stays suspended, now for the admin.
            store.suspend(companyId, member.id(), time.now(),
                    CurrentActor.user().map(AuthenticatedUser::id).orElse(null), why, Cause.ADMIN.name());
            audit(companyId, member.id(), AuditAction.PRINCIPAL_SUSPENDED, Cause.ADMIN);
        }
        return view(companyId, member.id());
    }

    @Override
    @Transactional
    public Suspension resumeMember(UUID companyId, UUID userId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        PrincipalStore.PrincipalRow member = store.ofMember(companyId, userId, true)
            .orElseThrow(Suspensions::memberNotFound);
        if (companies.roleOf(companyId, userId).orElse(null) == CompanyRole.OWNER) {
            requireOwnerManagement(access);
        }
        if (member.status() == PrincipalStatus.SUSPENDED) {
            PrincipalStore.SuspensionRow current = store.suspension(companyId, member.id()).orElseThrow();
            if (Cause.USER_DEACTIVATED.name().equals(current.cause())) {
                throw ApiException.conflict(ProblemCode.PRINCIPAL_SUSPENDED, "The user is deactivated at the instance: "
                        + "an instance admin reactivates them, which resumes them here");
            }
            if (Cause.SCIM.name().equals(current.cause())) {
                throw ApiException.conflict(ProblemCode.PRINCIPAL_SUSPENDED, "The company's identity provider "
                        + "deactivated them through SCIM: it reactivates them, which resumes them here");
            }
            resumeOrKeepScim(companyId, member.id(), userId);
        }
        return view(companyId, member.id());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void scimSuspended(UUID companyId, UUID userId) {
        store.ofMember(companyId, userId, true)
            .filter(row -> row.status() == PrincipalStatus.ACTIVE)
            .ifPresent(row -> suspend(companyId, row.id(), null, Cause.SCIM));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void scimLifted(UUID companyId, UUID userId) {
        store.ofMember(companyId, userId, true)
            .filter(row -> row.status() == PrincipalStatus.SUSPENDED)
            .filter(row -> causeOf(companyId, row.id()) == Cause.SCIM)
            .ifPresent(row -> {
                if (users.findById(userId).map(UserSummary::active).orElse(false)) {
                    resume(companyId, row.id());
                }
                else {
                    // The user is deactivated at the instance: they stay suspended for it, which their
                    // reactivation lifts.
                    store.suspend(companyId, row.id(), time.now(), null, null, Cause.USER_DEACTIVATED.name());
                    audit(companyId, row.id(), AuditAction.PRINCIPAL_SUSPENDED, Cause.USER_DEACTIVATED);
                }
            });
    }

    /**
     * Lifts an admin's suspension or the user's deactivation's, unless SCIM's suspension of the member holds too
     * (ADR-0087, decision 7): then the principal stays suspended, for SCIM, which lifts it.
     */
    private void resumeOrKeepScim(UUID companyId, UUID principalId, UUID userId) {
        if (companies.scimSuspended(companyId, userId)) {
            store.suspend(companyId, principalId, time.now(), null, null, Cause.SCIM.name());
            audit(companyId, principalId, AuditAction.PRINCIPAL_SUSPENDED, Cause.SCIM);
            return;
        }
        resume(companyId, principalId);
    }

    private @Nullable Cause causeOf(UUID companyId, UUID principalId) {
        return store.suspension(companyId, principalId)
            .map(PrincipalStore.SuspensionRow::cause)
            .map(Cause::valueOf)
            .orElse(null);
    }

    /** Suspends a locked, active principal of either kind; the caller authorised it. */
    void suspend(UUID companyId, UUID principalId, @Nullable String reason, Cause cause) {
        Instant now = time.now();
        UUID by = CurrentActor.user().map(AuthenticatedUser::id).orElse(null);
        store.suspend(companyId, principalId, now, by, reason, cause.name());
        audit(companyId, principalId, AuditAction.PRINCIPAL_SUSPENDED, cause);
        events.publishEvent(new PrincipalEvents.PrincipalSuspended(companyId, principalId));
    }

    /** Resumes a locked, suspended principal of either kind; the caller authorised it. */
    void resume(UUID companyId, UUID principalId) {
        Cause cause = store.suspension(companyId, principalId)
            .map(PrincipalStore.SuspensionRow::cause)
            .map(Cause::valueOf)
            .orElse(Cause.ADMIN);
        store.updateStatus(companyId, principalId, PrincipalStatus.ACTIVE, time.now());
        audit(companyId, principalId, AuditAction.PRINCIPAL_RESUMED, cause);
        events.publishEvent(new PrincipalEvents.PrincipalResumed(companyId, principalId));
    }

    /** {@code principal.suspended} and {@code .resumed}: a service identity's name, a member's user id (ADR-0010). */
    private void audit(UUID companyId, UUID principalId, AuditAction action, Cause cause) {
        PrincipalStore.PrincipalRow row = store.find(companyId, principalId).orElseThrow();
        AuditRecord.Builder record = AuditRecord.company(companyId, action, CurrentActor.actor())
            .target(MemberPrincipalsImpl.TARGET, principalId)
            .detail("kind", row.kind().name())
            .detail("cause", cause.name());
        if (row.kind() == PrincipalKind.SERVICE) {
            record.detail("name", Objects.requireNonNull(row.name()));
        }
        else {
            record.detail("userId", Objects.requireNonNull(row.userId()).toString());
        }
        auditLog.record(record.build());
    }

    private boolean lastActiveOwner(UUID companyId, UUID userId) {
        Set<UUID> suspended = store.suspendedUsers(companyId);
        return companies.usersWithRole(companyId, CompanyRole.OWNER)
            .stream()
            .noneMatch(owner -> !owner.equals(userId) && !suspended.contains(owner));
    }

    private void requireOwnerManagement(CompanyAccess access) {
        if (!access.covers(CompanyPermission.MANAGE_OWNERS)) {
            companyAccess.require(access.companyId(), CompanyPermission.MANAGE_OWNERS);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Suspension> suspended(UUID companyId) {
        return store.suspended(companyId).stream().map(Suspensions::suspension).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Suspension> ofMember(UUID companyId, UUID userId) {
        return store.ofMember(companyId, userId, false)
            .flatMap(row -> store.suspension(companyId, row.id()))
            .map(Suspensions::suspension);
    }

    private Suspension view(UUID companyId, UUID principalId) {
        return suspension(store.suspension(companyId, principalId).orElseThrow());
    }

    private static Suspension suspension(PrincipalStore.SuspensionRow row) {
        return new Suspension(row.id(), row.kind(), row.userId(), row.name(), row.status(), row.since(), row.by(),
                row.reason(), row.cause() != null ? Cause.valueOf(row.cause()) : null);
    }

    // ---- A user's deactivation at the instance -------------------------------------------------------------------

    /**
     * An instance admin deactivated the user: their principal is suspended in every company they are a member of, in
     * the deactivating transaction, each audited in its company's chain. The instance admin's right is the
     * authorisation: the companies come from a narrow lookup ({@code CompanyDirectory.companiesOf}), then each
     * company's tenant context is entered for its principal and chain.
     */
    @EventListener
    void userDeactivated(UserEvents.UserDeactivated event) {
        for (UUID companyId : companies.companiesOf(event.userId())) {
            tenantScope.enterCompany(companyId);
            store.ofMember(companyId, event.userId(), true)
                .filter(row -> row.status() == PrincipalStatus.ACTIVE)
                .ifPresent(row -> suspend(companyId, row.id(), null, Cause.USER_DEACTIVATED));
        }
    }

    /** The user was reactivated: the suspensions their deactivation made are lifted, and only those. */
    @EventListener
    void userReactivated(UserEvents.UserReactivated event) {
        for (UUID companyId : companies.companiesOf(event.userId())) {
            tenantScope.enterCompany(companyId);
            store.ofMember(companyId, event.userId(), true)
                .flatMap(row -> store.suspension(companyId, row.id()))
                .filter(row -> Cause.USER_DEACTIVATED.name().equals(row.cause()))
                .ifPresent(row -> resumeOrKeepScim(companyId, row.id(), event.userId()));
        }
    }

    // ---- A member's departure -------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<Departure> departure(UUID companyId, UUID principalId) {
        return store.departure(companyId, principalId, false).map(row -> departure(companyId, row));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Departure> openDepartures(UUID companyId, int limit) {
        return store.openDepartures(companyId, limit).stream().map(row -> departure(companyId, row)).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Departure closeDeparture(UUID companyId, UUID principalId, boolean force, Map<String, Integer> openItems) {
        PrincipalStore.DepartureRow row = store.departure(companyId, principalId, true)
            .orElseThrow(() -> ApiException.notFound("No member who left with this id"));
        if (row.closedAt() == null) {
            store.closeDeparture(companyId, principalId, time.now(),
                    CurrentActor.user().map(AuthenticatedUser::id).orElse(null));
            auditLog.record(AuditRecord.company(companyId, AuditAction.PRINCIPAL_DEPARTURE_CLOSED, CurrentActor.actor())
                .target(MemberPrincipalsImpl.TARGET, principalId)
                .detail("userId", row.userId().toString())
                .detail("force", force)
                .detail("openItems", new TreeMap<>(openItems))
                .build());
        }
        return departure(companyId, store.departure(companyId, principalId, false).orElseThrow());
    }

    private Departure departure(UUID companyId, PrincipalStore.DepartureRow row) {
        return new Departure(row.id(), row.userId(), row.leftAt(), store.departureGroups(companyId, row.id()),
                row.closedAt(), row.closedBy());
    }

    static String requireReason(@Nullable String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 500) {
            throw ApiException.validation("reason must be 1 to 500 characters");
        }
        return why;
    }

    private static ApiException memberNotFound() {
        return ApiException.notFound("Member not found");
    }
}
