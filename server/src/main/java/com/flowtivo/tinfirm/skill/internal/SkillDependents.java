package com.flowtivo.tinfirm.skill.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupDependents;
import com.flowtivo.tinfirm.principal.PrincipalDependents;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.skill.SkillEvents;

/**
 * What goes with skills when something they hang from goes, each in that change's transaction and audited with its
 * cause (the skills contract, §4.4): a leaving member's skills pass to the admins ({@code skill.passed_to_admins}) and
 * the skills shared with them on their own are no longer ({@code skill.shares_changed} with {@code cause}
 * {@code principal.left}); a deleted group no longer sees any skill ({@code cause} {@code group.deleted}).
 * <p>
 * First among the dependents, so that a leaving and a group's deletion lock the skills before the agents
 * ({@code AgentDependents}), as a skill's delete with {@code removeFromAgents} does (CONVENTIONS §10): in the other
 * order the two waited on each other ({@code 40P01}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SkillDependents implements PrincipalDependents, GroupDependents {

    private final SkillStore store;

    private final SkillService skills;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    SkillDependents(SkillStore store, SkillService skills, AuditLog auditLog, ApplicationEventPublisher events,
            TimeSource time) {
        this.store = store;
        this.skills = skills;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void principalDeleting(UUID companyId, UUID principalId) {
        // A service identity owns no skill (the owner's foreign key admits members' principals only).
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberLeaving(UUID companyId, UUID principalId, UUID userId) {
        List<SkillStore.SkillRow> owned = store.lockOwnedBy(companyId, principalId);
        store.clearOwner(companyId, principalId, time.now());
        for (SkillStore.SkillRow skill : owned) {
            auditLog.record(AuditRecord.company(companyId, AuditAction.SKILL_PASSED_TO_ADMINS, CurrentActor.actor())
                .target(SkillService.TARGET, skill.id())
                .detail("name", skill.name())
                .detail("formerOwnerPrincipalId", principalId.toString())
                .detail("cause", "principal.left")
                .build());
            events.publishEvent(new SkillEvents.SkillPassedToAdmins(companyId, skill.id(), principalId));
        }
        for (SkillStore.SkillRow skill : store.lockSharedWithMember(companyId, principalId)) {
            SkillStore.Shares before = store.shares(companyId, skill.id());
            store.removeMemberShares(companyId, skill.id(), List.of(principalId));
            skills.sharesChanged(companyId, skill.id(), CurrentActor.actor(), before,
                    store.shares(companyId, skill.id()), "principal.left");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void groupDeleting(UUID companyId, UUID groupId) {
        for (SkillStore.SkillRow skill : store.lockSharedWith(companyId, groupId)) {
            SkillStore.Shares before = store.shares(companyId, skill.id());
            store.removeShares(companyId, skill.id(), List.of(groupId));
            skills.sharesChanged(companyId, skill.id(), CurrentActor.actor(), before,
                    store.shares(companyId, skill.id()), "group.deleted");
        }
    }
}
