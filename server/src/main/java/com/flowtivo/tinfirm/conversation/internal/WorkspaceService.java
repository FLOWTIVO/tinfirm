package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.NetworkHosts;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * {@code Workspace.of(conversation)} (the package 3 contract, §5.6) and what approved requests add to it. The
 * workspace is the start's repositories, then the approved requests; each repository's system is bound at the turn
 * that first needs it (§5.3). A repository is read-only when the agent's access is {@code READ} ({@code AGENT}), the
 * approval gave {@code READ} ({@code REQUEST}), or its binding's access is {@code READ} now ({@code GRANT}). In the
 * caller's transaction; resolves nothing and reveals nothing.
 */
@Service
class WorkspaceService implements ConversationWorkspace {

    /** The most repositories {@code run.assign.requestableRepositories} names (§5.5). */
    static final int MAX_REQUESTABLE = 200;

    /** The branch a repository without a default branch of its own starts from. */
    static final String FALLBACK_BRANCH = "main";

    private final ConversationStore conversations;

    private final BindingStore bindings;

    private final Systems systems;

    private final Accounts accounts;

    private final TimeSource time;

    WorkspaceService(ConversationStore conversations, BindingStore bindings, Systems systems, Accounts accounts,
            TimeSource time) {
        this.conversations = conversations;
        this.bindings = bindings;
        this.systems = systems;
        this.accounts = accounts;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Repository> of(ConversationInfo conversation, EffectiveSettings settings) {
        Map<UUID, RepositoryInfo> known = repositoriesOfAgent(conversation.companyId(), settings);
        Map<UUID, AgentDefinition.RepositoryEntry> agents = new HashMap<>();
        settings.repositories().forEach(entry -> agents.put(entry.repositoryId(), entry));
        Map<UUID, BindingInfo> bound = new HashMap<>();
        bindings.ofConversation(conversation.companyId(), conversation.id())
            .forEach(row -> bound.put(row.info().systemId(), row.info()));
        List<Repository> workspace = new ArrayList<>();
        for (ConversationInfo.WorkspaceEntry entry : conversation.workspace()) {
            RepositoryInfo repository = known.get(entry.repositoryId());
            if (repository == null) {
                // Removed, or of a system the agent no longer lists.
                continue;
            }
            AgentDefinition.RepositoryEntry onAgent = agents.get(entry.repositoryId());
            if (entry.source() == ConversationInfo.WorkspaceEntry.Source.AGENT && onAgent == null) {
                // The agent no longer lists it or its system, or the settings switched the system off.
                continue;
            }
            BindingInfo binding = bound.get(repository.systemId());
            if (binding != null && binding.gone() || binding == null && conversation.triggered()) {
                // Gone; or a trigger's conversation's system without a row in its trigger's table (the package 4
                // contract, §5.7).
                continue;
            }
            TurnSource.ReadOnlyCause cause = null;
            if (entry.source() == ConversationInfo.WorkspaceEntry.Source.AGENT
                    && (entry.readOnly() || onAgent.access() == RepositoryAccess.READ)) {
                cause = TurnSource.ReadOnlyCause.AGENT;
            }
            else if (entry.source() == ConversationInfo.WorkspaceEntry.Source.REQUESTED && entry.readOnly()) {
                cause = TurnSource.ReadOnlyCause.REQUEST;
            }
            else if (binding != null && accessOf(binding) == GrantInfo.Access.READ) {
                cause = TurnSource.ReadOnlyCause.GRANT;
            }
            workspace.add(new Repository(repository.id(), repository.systemId(), repository.name(), repository.url(),
                    repository.defaultBranch() != null ? repository.defaultBranch() : FALLBACK_BRANCH,
                    entry.source(), cause != null, cause, binding));
        }
        return workspace;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Requestable> requestable(ConversationInfo conversation, EffectiveSettings settings) {
        Set<UUID> inWorkspace = new HashSet<>();
        conversation.workspace().forEach(entry -> inWorkspace.add(entry.repositoryId()));
        Map<UUID, RepositoryInfo> known = repositoriesOfAgent(conversation.companyId(), settings);
        Set<UUID> onAgent = new HashSet<>();
        settings.repositories().forEach(entry -> onAgent.add(entry.repositoryId()));
        // A trigger's conversation requests only on the systems its trigger's table names (the package 4 contract,
        // §5.7).
        Set<UUID> rows = new HashSet<>();
        if (conversation.triggered()) {
            bindings.ofConversation(conversation.companyId(), conversation.id()).stream()
                .filter(row -> !row.info().gone())
                .forEach(row -> rows.add(row.info().systemId()));
        }
        List<Requestable> first = new ArrayList<>();
        List<Requestable> others = new ArrayList<>();
        for (RepositoryInfo repository : known.values()) {
            if (inWorkspace.contains(repository.id()) || settings.systemsOff().contains(repository.systemId())
                    || conversation.triggered() && !rows.contains(repository.systemId())) {
                continue;
            }
            boolean agents = onAgent.contains(repository.id());
            (agents ? first : others).add(new Requestable(repository.id(), repository.systemId(), repository.name(),
                    repository.url(), agents));
        }
        List<Requestable> all = new ArrayList<>(first);
        all.addAll(others);
        return all.size() > MAX_REQUESTABLE ? List.copyOf(all.subList(0, MAX_REQUESTABLE)) : all;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean addRequested(UUID companyId, UUID conversationId, UUID repositoryId, RepositoryAccess access) {
        ConversationInfo conversation = conversations.lock(companyId, conversationId).orElseThrow().info();
        if (conversation.workspace().stream().anyMatch(entry -> entry.repositoryId().equals(repositoryId))
                || conversation.workspace().size() >= ConversationInfo.MAX_WORKSPACE) {
            return false;
        }
        List<ConversationInfo.WorkspaceEntry> workspace = new ArrayList<>(conversation.workspace());
        workspace.add(new ConversationInfo.WorkspaceEntry(repositoryId, access == RepositoryAccess.READ,
                ConversationInfo.WorkspaceEntry.Source.REQUESTED));
        conversations.updateWorkspace(companyId, conversationId, workspace, time.now());
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public HostGrant grantHost(UUID companyId, UUID conversationId, String host) {
        NetworkHosts.Parsed parsed = NetworkHosts.parse(host);
        if (!parsed.valid()) {
            throw new IllegalArgumentException(parsed.problem());
        }
        String normalized = parsed.host();
        ConversationInfo conversation = conversations.lock(companyId, conversationId).orElseThrow().info();
        if (conversation.grantedHosts().stream().anyMatch(granted -> NetworkHosts.covers(granted, normalized))) {
            return HostGrant.COVERED;
        }
        if (conversation.grantedHosts().size() >= ConversationInfo.MAX_GRANTED_HOSTS) {
            return HostGrant.FULL;
        }
        List<String> hosts = new ArrayList<>(conversation.grantedHosts());
        hosts.add(normalized);
        conversations.updateGrantedHosts(companyId, conversationId, hosts, time.now());
        return HostGrant.ADDED;
    }

    /**
     * The access of a binding now: {@code WRITE} for the person's own account, the grant's access otherwise (open
     * point 9); null while it was never bound.
     */
    GrantInfo.@Nullable Access accessOf(BindingInfo binding) {
        if (binding.accountId() == null) {
            return null;
        }
        if (binding.grantId() == null) {
            // One's own account; a trigger's row, its author's own or a service account's credential as it is lent.
            return binding.how() == BindingInfo.How.FIXED && binding.holderPrincipalId() != null
                    ? accounts.fixedAccess(binding.companyId(), binding.systemId(), binding.holderPrincipalId(),
                            binding.accountId())
                    : GrantInfo.Access.WRITE;
        }
        return accounts.grant(binding.companyId(), binding.grantId()).map(GrantInfo::access)
            .orElse(GrantInfo.Access.READ);
    }

    /** The repositories of the code hosts the agent of the person's turns lists, by id, in the systems' order. */
    Map<UUID, RepositoryInfo> repositoriesOfAgent(UUID companyId, EffectiveSettings settings) {
        Map<UUID, RepositoryInfo> repositories = new LinkedHashMap<>();
        List<UUID> systemIds = settings.agent().definition().systems().stream()
            .map(AgentDefinition.SystemEntry::systemId)
            .toList();
        Map<UUID, SystemInfo> infos = systems.findAll(companyId, systemIds);
        for (UUID systemId : systemIds) {
            SystemInfo info = infos.get(systemId);
            if (info == null || !info.kind().hasRepositories()) {
                continue;
            }
            for (RepositoryInfo repository : systems.repositories(companyId, systemId)) {
                repositories.put(repository.id(), repository);
            }
        }
        return repositories;
    }

    /** One repository of the company's code hosts the agent lists, by id. */
    Optional<RepositoryInfo> repository(UUID companyId, EffectiveSettings settings, UUID repositoryId) {
        return Optional.ofNullable(repositoriesOfAgent(companyId, settings).get(repositoryId));
    }
}
