package com.flowtivo.tinfirm.principal.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.company.MemberPrincipals;
import com.flowtivo.tinfirm.principal.PrincipalDependents;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * A member's principal lives with the membership (the package 1 contract, §3.1): created with it, audited as
 * {@code principal.created} and announced as {@link PrincipalEvents.PrincipalCreated}; {@code LEFT} when it ends,
 * after the member's accounts, owned secrets and personal systems went (I8, {@link PrincipalDependents#memberLeaving}),
 * audited as {@code principal.left} and announced as {@link PrincipalEvents.PrincipalLeft}, and then the member's
 * personal runner hosts go ({@link PrincipalDependents#memberLeft}). A member who rejoins gets a new principal (§10).
 */
@Component
class MemberPrincipalsImpl implements MemberPrincipals {

    static final String TARGET = "principal";

    private final PrincipalStore store;

    private final List<PrincipalDependents> dependents;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final GroupMembership groups;

    MemberPrincipalsImpl(PrincipalStore store, List<PrincipalDependents> dependents, AuditLog auditLog,
            ApplicationEventPublisher events, TimeSource time, GroupMembership groups) {
        this.groups = groups;
        this.store = store;
        this.dependents = List.copyOf(dependents);
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void joined(UUID companyId, UUID userId) {
        Instant now = time.now();
        Actor actor = CurrentActor.actor();
        UUID createdBy = actor.type() == ActorType.USER ? actor.id() : null;
        UUID id = Ids.newId();
        store.insert(new PrincipalStore.PrincipalRow(id, companyId, PrincipalKind.MEMBER, userId, null, null,
                PrincipalStatus.ACTIVE, now, now, createdBy));
        auditLog.record(AuditRecord.company(companyId, AuditAction.PRINCIPAL_CREATED, actor)
            .target(TARGET, id)
            .detail("kind", PrincipalKind.MEMBER.name())
            .detail("userId", userId.toString())
            .build());
        events.publishEvent(new PrincipalEvents.PrincipalCreated(companyId, id, PrincipalKind.MEMBER));
        for (PrincipalDependents dependent : dependents) {
            dependent.memberJoined(companyId, id, userId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void leaving(UUID companyId, UUID userId) {
        Optional<PrincipalStore.PrincipalRow> found = store.ofMember(companyId, userId, true);
        if (found.isEmpty()) {
            return;
        }
        UUID principalId = found.get().id();
        // The membership's cascade takes the member out of every group: the leaver's checklist keeps which
        // (the package 6 contract, §5.5).
        store.recordDepartureGroups(companyId, principalId, groups.groupsOf(companyId, userId));
        for (PrincipalDependents dependent : dependents) {
            dependent.memberLeaving(companyId, principalId, userId);
        }
        store.updateStatus(companyId, principalId, PrincipalStatus.LEFT, time.now());
        auditLog.record(AuditRecord.company(companyId, AuditAction.PRINCIPAL_LEFT, CurrentActor.actor())
            .target(TARGET, principalId)
            .detail("userId", userId.toString())
            .build());
        events.publishEvent(new PrincipalEvents.PrincipalLeft(companyId, principalId, userId));
        // After the person's conversations, which PrincipalLeft locked (CONVENTIONS §10): the personal runner hosts.
        for (PrincipalDependents dependent : dependents) {
            dependent.memberLeft(companyId, principalId, userId);
        }
    }
}
