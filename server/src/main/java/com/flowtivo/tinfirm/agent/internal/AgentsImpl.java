package com.flowtivo.tinfirm.agent.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentAccess;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

/**
 * {@link Agents} and {@link Settings} for other modules: in the caller's transaction and tenant context. The additions
 * for good from approvals are the approver's edits: their own settings follow when they were up to date.
 */
@Component
class AgentsImpl implements Agents, Settings {

    private final AgentStore store;

    private final AgentVersions versions;

    private final AgentCallers callers;

    private final AgentService agents;

    private final SettingsService settings;

    private final Principals principals;

    AgentsImpl(AgentStore store, AgentVersions versions, AgentCallers callers, AgentService agents,
            SettingsService settings, Principals principals) {
        this.store = store;
        this.versions = versions;
        this.callers = callers;
        this.agents = agents;
        this.settings = settings;
        this.principals = principals;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, String> passedFrom(UUID companyId, UUID formerOwnerPrincipalId) {
        return store.passedFrom(companyId, formerOwnerPrincipalId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<PausedAgent> paused(UUID companyId) {
        return store.paused(companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public int ownedBy(UUID companyId, UUID ownerPrincipalId) {
        return store.countOwnedBy(companyId, ownerPrincipalId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentInfo> find(UUID companyId, UUID agentId) {
        return store.find(companyId, agentId).map(AgentStore.AgentRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public AgentAccess access(UUID companyId, UUID agentId, UUID userId) {
        Optional<AgentStore.AgentRow> row = store.findLive(companyId, agentId);
        Optional<AgentCallers.Caller> caller = callers.member(companyId, userId);
        if (row.isEmpty() || caller.isEmpty()) {
            return AgentAccess.NONE;
        }
        return AgentCallers.access(caller.get(), row.get().ownerPrincipalId(), store.shares(companyId, agentId));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<AgentInfo> usedBy(UUID companyId, UUID userId, int limit) {
        Optional<AgentCallers.Caller> caller = callers.member(companyId, userId);
        if (caller.isEmpty() || caller.get().principalId() == null) {
            return java.util.List.of();
        }
        return store.page(companyId, AgentStore.Scope.USABLE, caller.get().principalId(), caller.get().groupIds(),
                null, null, Math.max(1, limit))
            .stream()
            .map(AgentStore.AgentRow::info)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentInfo> byHandle(UUID companyId, String handle) {
        return store.findLiveByHandle(companyId, handle.toLowerCase(java.util.Locale.ROOT))
            .map(AgentStore.AgentRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentDefinition> definition(UUID companyId, UUID agentId) {
        return store.findLive(companyId, agentId).map(row -> store.definition(companyId, agentId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EffectiveAgent> effective(UUID companyId, UUID agentId, int acceptedVersion) {
        return store.findLive(companyId, agentId).map(row -> versions.effective(row, acceptedVersion));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContentChange> contentChange(UUID companyId, UUID agentId, int from, int to) {
        return store.find(companyId, agentId).flatMap(row -> versions.contentChange(companyId, agentId, from, to));
    }

    @Override
    @Transactional
    public int addRepository(UUID companyId, UUID agentId, UUID repositoryId, RepositoryAccess access,
            UUID decisionId) {
        return agents.addRepository(companyId, agentId, repositoryId, access, decisionId, editor(companyId));
    }

    @Override
    @Transactional
    public int addHost(UUID companyId, UUID agentId, String host, @Nullable String reason, UUID decisionId) {
        return agents.addHost(companyId, agentId, host, reason, decisionId, editor(companyId));
    }

    @Override
    @Transactional(readOnly = true)
    public EffectiveSettings effective(UUID companyId, UUID agentId, SettingsOwner owner) {
        return settings.effective(companyId, agentId, owner);
    }

    @Override
    @Transactional
    public EffectiveSettings firstUse(UUID companyId, UUID agentId, UUID principalId) {
        return settings.firstUse(companyId, agentId, principalId);
    }

    /** The member whose request this is, by their principal; none for a system task. */
    private @Nullable UUID editor(UUID companyId) {
        return CurrentActor.user()
            .filter(user -> CurrentActor.actor().type() == ActorType.USER)
            .flatMap(user -> principals.ofMember(companyId, user.id()))
            .map(PrincipalInfo::id)
            .orElse(null);
    }
}
