package com.flowtivo.tinfirm.agent.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentEvents;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupDependents;
import com.flowtivo.tinfirm.principal.PrincipalDependents;
import com.flowtivo.tinfirm.shared.directory.AgentDisplayNames;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * What goes with agents when something they hang from goes, each in that change's transaction and audited with its
 * cause (the package 3 contract, §5.7): a leaving member's settings go, their agents pass to the admins
 * ({@code agent.passed_to_admins}) and the agents shared with them on their own are no longer
 * ({@code agent.shares_changed} with {@code cause} {@code principal.left}); a deleted group no longer uses any agent
 * ({@code agent.shares_changed} with {@code cause} {@code group.deleted}). Also the names of agents for the audit log's
 * actors.
 */
@Component
class AgentDependents implements PrincipalDependents, GroupDependents, AgentDisplayNames {

    private final AgentStore store;

    private final SettingsStore settings;

    private final AgentService agents;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    AgentDependents(AgentStore store, SettingsStore settings, AgentService agents, AuditLog auditLog,
            ApplicationEventPublisher events, TimeSource time) {
        this.store = store;
        this.settings = settings;
        this.agents = agents;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void principalDeleting(UUID companyId, UUID principalId) {
        // A service identity owns no agent and has no settings (both foreign keys admit members' principals only).
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberLeaving(UUID companyId, UUID principalId, UUID userId) {
        settings.deleteOfPrincipal(companyId, principalId);
        List<AgentStore.AgentRow> owned = store.lockOwnedBy(companyId, principalId);
        store.clearOwner(companyId, principalId, time.now());
        for (AgentStore.AgentRow agent : owned) {
            auditLog.record(AuditRecord.company(companyId, AuditAction.AGENT_PASSED_TO_ADMINS, CurrentActor.actor())
                .target(AgentVersions.TARGET, agent.id())
                .detail("name", agent.name())
                .detail("formerOwnerPrincipalId", principalId.toString())
                .detail("cause", "principal.left")
                .build());
            events.publishEvent(new AgentEvents.AgentPassedToAdmins(companyId, agent.id(), principalId));
        }
        for (AgentStore.AgentRow agent : store.lockSharedWithMember(companyId, principalId)) {
            AgentStore.Shares before = store.shares(companyId, agent.id());
            store.removeMemberShares(companyId, agent.id(), List.of(principalId));
            agents.sharesChanged(companyId, agent.id(), CurrentActor.actor(), before,
                    store.shares(companyId, agent.id()), "principal.left");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void groupDeleting(UUID companyId, UUID groupId) {
        for (AgentStore.AgentRow agent : store.lockSharedWith(companyId, groupId)) {
            AgentStore.Shares before = store.shares(companyId, agent.id());
            store.removeShares(companyId, agent.id(), List.of(groupId));
            agents.sharesChanged(companyId, agent.id(), CurrentActor.actor(), before,
                    store.shares(companyId, agent.id()), "group.deleted");
        }
    }

    @Override
    public Map<UUID, String> names(Collection<UUID> agentIds) {
        return store.names(agentIds);
    }
}
