package com.flowtivo.tinfirm.agent.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentAccess;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.ModelAccessSetting;
import com.flowtivo.tinfirm.agent.ToolMode;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.api.model.Agent;
import com.flowtivo.tinfirm.shared.api.model.AgentEffort;
import com.flowtivo.tinfirm.shared.api.model.AgentHost;
import com.flowtivo.tinfirm.shared.api.model.AgentMemberShare;
import com.flowtivo.tinfirm.shared.api.model.AgentModelAccess;
import com.flowtivo.tinfirm.shared.api.model.AgentOwnServer;
import com.flowtivo.tinfirm.shared.api.model.AgentOwnServerTool;
import com.flowtivo.tinfirm.shared.api.model.AgentProject;
import com.flowtivo.tinfirm.shared.api.model.AgentRepository;
import com.flowtivo.tinfirm.shared.api.model.AgentRepositoryAccess;
import com.flowtivo.tinfirm.shared.api.model.AgentRuntime;
import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsToolMode;
import com.flowtivo.tinfirm.shared.api.model.AgentStatus;
import com.flowtivo.tinfirm.shared.api.model.AgentSubscription;
import com.flowtivo.tinfirm.shared.api.model.AgentSummary;
import com.flowtivo.tinfirm.shared.api.model.AgentSystem;
import com.flowtivo.tinfirm.shared.api.model.AgentTool;
import com.flowtivo.tinfirm.shared.api.model.AgentToolMode;
import com.flowtivo.tinfirm.shared.api.model.AgentWidenedInstructions;
import com.flowtivo.tinfirm.shared.api.model.AgentWidenedRepository;
import com.flowtivo.tinfirm.shared.api.model.AgentWidenedSkill;
import com.flowtivo.tinfirm.shared.api.model.AgentWidenedTool;
import com.flowtivo.tinfirm.shared.api.model.AgentWidening;
import com.flowtivo.tinfirm.shared.api.model.GroupRef;
import com.flowtivo.tinfirm.shared.api.model.SystemKind;
import com.flowtivo.tinfirm.shared.api.model.ToolEffect;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.ToolInfo;

/**
 * The DTOs of the Agents and Settings APIs (the package 3 contract, §6), as the caller may see them: the definition
 * with the names of its systems, tools and repositories as they are now, and, for a caller who may use the agent, the
 * version they accepted and what the current version adds over it.
 */
@Component
class AgentViews {

    private final AgentStore store;

    private final SettingsStore settings;

    private final AgentVersions versions;

    private final Systems systems;

    private final Principals principals;

    private final GroupMembership groups;

    private final AgentSkills skills;

    /** The deployment's time limit of a turn, in whole minutes, for an agent that sets none. */
    private final int defaultTurnTimeLimitMinutes;

    AgentViews(AgentStore store, SettingsStore settings, AgentVersions versions, Systems systems,
            Principals principals, GroupMembership groups, AgentSkills skills,
            @Value("${tinfirm.run.max-wall-clock:PT60M}") Duration deploymentTurnTimeLimit) {
        this.skills = skills;
        this.store = store;
        this.settings = settings;
        this.versions = versions;
        this.systems = systems;
        this.principals = principals;
        this.groups = groups;
        this.defaultTurnTimeLimitMinutes = (int) Math.max(1, (deploymentTurnTimeLimit.toSeconds() + 59) / 60);
    }

    /** An agent's summaries for a list, in the order of the rows. */
    List<AgentSummary> summaries(AgentCallers.Caller caller, List<AgentStore.AgentRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        UUID companyId = rows.getFirst().companyId();
        List<UUID> ids = rows.stream().map(AgentStore.AgentRow::id).toList();
        Map<UUID, AgentStore.Shares> shares = store.shares(companyId, ids);
        Map<UUID, Integer> accepted = caller.principalId() != null
                ? settings.acceptedVersions(companyId, caller.principalId(), ids) : Map.of();
        Map<UUID, String> owners = principals.displayNames(companyId, rows.stream()
            .map(AgentStore.AgentRow::ownerPrincipalId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet()));
        // Updates of skills are the editors' to take (ADR-0064): counted for the agents the caller edits.
        List<UUID> edited = rows.stream()
            .filter(row -> AgentCallers.access(caller, row.ownerPrincipalId(),
                    shares.getOrDefault(row.id(), AgentStore.Shares.NONE)).edits())
            .map(AgentStore.AgentRow::id)
            .toList();
        Map<UUID, Integer> updates = skills.updates(companyId, edited);
        List<AgentSummary> summaries = new ArrayList<>();
        for (AgentStore.AgentRow row : rows) {
            AgentStore.Shares shared = shares.getOrDefault(row.id(), AgentStore.Shares.NONE);
            AgentAccess access = AgentCallers.access(caller, row.ownerPrincipalId(), shared);
            int acceptedVersion = accepted.getOrDefault(row.id(), row.version());
            boolean pending = access.uses() && acceptedVersion < row.version()
                    && versions.effective(row, acceptedVersion).wideningPending();
            summaries.add(new AgentSummary(row.id(), row.name(), row.handle(), runtimes(row), status(row),
                    row.version(), access.uses(), access.edits(), pending, updates.getOrDefault(row.id(), 0),
                    ts(row.createdAt()), ts(row.updatedAt()))
                .description(row.description())
                .ownerPrincipalId(row.ownerPrincipalId())
                .ownerName(row.ownerPrincipalId() != null ? owners.get(row.ownerPrincipalId()) : null)
                // Who sees the agent sees whom it is shared with, as its page shows it.
                .shareGroupIds(shared.groupIds().stream().sorted().collect(Collectors.toCollection(ArrayList::new)))
                .sharePrincipalIds(shared.principalIds()
                    .stream()
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new))));
        }
        return summaries;
    }

    /** An agent as the caller sees it. */
    Agent agent(AgentCallers.Caller caller, AgentStore.AgentRow row) {
        UUID companyId = row.companyId();
        AgentStore.Shares shared = store.shares(companyId, row.id());
        AgentAccess access = AgentCallers.access(caller, row.ownerPrincipalId(), shared);
        AgentDefinition definition = store.definition(companyId, row.id());
        Map<UUID, SystemInfo> systemInfos = systems.findAll(companyId,
                definition.systems().stream().map(AgentDefinition.SystemEntry::systemId).toList());
        List<AgentSystem> systemViews = new ArrayList<>();
        Map<UUID, RepositoryInfo> repositoryInfos = new HashMap<>();
        for (AgentDefinition.SystemEntry system : definition.systems()) {
            SystemInfo info = systemInfos.get(system.systemId());
            if (info == null) {
                continue;
            }
            Map<String, ToolInfo> served = systems.tools(companyId, info.id())
                .stream()
                .collect(Collectors.toMap(ToolInfo::name, Function.identity(), (a, b) -> a));
            List<AgentTool> tools = new ArrayList<>();
            for (AgentDefinition.ToolEntry tool : system.tools()) {
                ToolInfo toolInfo = served.get(tool.name());
                boolean offered = toolInfo != null && toolInfo.enabled();
                tools.add(new AgentTool(tool.name(), toolMode(tool.mode()),
                        AgentTool.ChosenForEffectEnum.fromValue(tool.chosenForEffect().name()), offered)
                    .title(toolInfo != null ? toolInfo.title() : null)
                    .effect(toolInfo != null ? ToolEffect.fromValue(toolInfo.effect().name()) : null));
            }
            systemViews.add(new AgentSystem(info.id(), info.name(), info.handle(),
                    SystemKind.fromValue(info.kind().name()), tools));
            if (info.kind().hasRepositories()) {
                systems.repositories(companyId, info.id()).forEach(repo -> repositoryInfos.put(repo.id(), repo));
            }
        }
        systemViews.sort(Comparator.comparing(AgentSystem::getName, String.CASE_INSENSITIVE_ORDER));
        List<AgentRepository> repositories = new ArrayList<>();
        for (AgentDefinition.RepositoryEntry repository : definition.repositories()) {
            RepositoryInfo info = repositoryInfos.get(repository.repositoryId());
            if (info != null) {
                repositories.add(new AgentRepository(info.id(), info.systemId(), info.name(), info.url(),
                        AgentRepositoryAccess.fromValue(repository.access().name())));
            }
        }
        repositories.sort(Comparator.comparing(AgentRepository::getName, String.CASE_INSENSITIVE_ORDER));
        List<AgentHost> hosts = store.hosts(companyId, row.id())
            .stream()
            .map(host -> new AgentHost(host.host(), ts(host.addedAt())).reason(host.reason())
                .addedBy(host.addedBy())
                .decisionId(host.decisionId()))
            .toList();
        Map<UUID, String> groupNames = groups.groupNames(companyId, shared.groupIds());
        List<GroupRef> shares = groupNames.entrySet()
            .stream()
            .map(entry -> new GroupRef(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(GroupRef::getName, String.CASE_INSENSITIVE_ORDER))
            .toList();
        Map<UUID, PrincipalInfo> members = principals.findAll(companyId, shared.principalIds());
        Map<UUID, String> memberNames = principals.displayNames(companyId, members.keySet());
        List<AgentMemberShare> memberShares = members.values()
            .stream()
            .map(member -> new AgentMemberShare(member.id(), memberNames.getOrDefault(member.id(), ""))
                .userId(member.userId()))
            .sorted(Comparator.comparing(AgentMemberShare::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AgentMemberShare::getPrincipalId))
            .toList();
        Agent agent = new Agent(row.id(), row.name(), row.handle(), row.instructions(),
                skills.views(companyId, row.id()), runtimes(row), status(row),
                row.version(), access.uses(), access.edits(), false, systemViews, repositories, new ArrayList<>(hosts),
                new ArrayList<>(shares), new ArrayList<>(memberShares), ts(row.createdAt()), ts(row.updatedAt()))
            .description(row.description())
            .turnTimeLimitMinutes(row.turnTimeLimitMinutes())
            .defaultTurnTimeLimitMinutes(defaultTurnTimeLimitMinutes)
            .memoryAllowed(row.memoryAllowed())
            .pausedAt(nullableTs(row.pausedAt()))
            .ownerPrincipalId(row.ownerPrincipalId())
            .ownerName(row.ownerPrincipalId() != null
                    ? principals.displayNames(companyId, List.of(row.ownerPrincipalId())).get(row.ownerPrincipalId())
                    : null)
            .createdBy(row.createdBy());
        if (access.uses() && caller.principalId() != null) {
            int accepted = settings.find(companyId, row.id(), caller.principalId())
                .map(SettingsStore.SettingsRow::acceptedVersion)
                .orElse(row.version());
            EffectiveAgent effective = versions.effective(row, accepted);
            agent.acceptedVersion(effective.acceptedVersion())
                .wideningPending(effective.wideningPending())
                .widening(widening(effective));
        }
        return agent;
    }

    /** A person's settings in effect. */
    AgentSettings settings(EffectiveSettings effective, @Nullable Instant updatedAt) {
        EffectiveAgent agent = effective.agent();
        Map<UUID, SystemInfo> own = systems.findAll(agent.agent().companyId(),
                effective.ownServers().stream().map(EffectiveSettings.OwnServer::systemId).toList());
        AgentSettings view = new AgentSettings(effective.agentId(), effective.stored(), runtime(effective.runtime()),
                AgentEffort.fromValue(effective.effort().name()), modelAccess(effective.modelAccess()),
                new ArrayList<>(effective.defaultRepositoryIds()),
                effective.defaultProjects()
                    .stream()
                    .map(project -> new AgentProject(project.systemId(), project.key()))
                    .collect(Collectors.toCollection(ArrayList::new)),
                effective.systemsOff().stream().sorted().collect(Collectors.toCollection(ArrayList::new)),
                effective.toolOverrides()
                    .stream()
                    .map(override -> new AgentSettingsToolMode(override.systemId(), override.tool(),
                            AgentSettingsToolMode.ModeEnum.fromValue(override.mode().name())))
                    .collect(Collectors.toCollection(ArrayList::new)),
                effective.ownServers()
                    .stream()
                    .map(server -> new AgentOwnServer(server.systemId())
                        .name(Optional.ofNullable(own.get(server.systemId())).map(SystemInfo::name).orElse(null))
                        .tools(server.tools()
                            .stream()
                            .map(tool -> new AgentOwnServerTool(tool.name(), toolMode(tool.mode()))
                                .chosenForEffect(AgentOwnServerTool.ChosenForEffectEnum
                                    .fromValue(tool.chosenForEffect().name())))
                            .collect(Collectors.toCollection(ArrayList::new))))
                    .collect(Collectors.toCollection(ArrayList::new)),
                effective.skillsOff().stream().sorted().collect(Collectors.toCollection(ArrayList::new)),
                agent.acceptedVersion(), agent.agent().version())
            .chosenRuntime(effective.chosenRuntime() != null ? runtime(effective.chosenRuntime()) : null)
            .model(effective.model())
            .instructions(effective.instructions())
            .memory(effective.memoryOn())
            .autoRetry(effective.autoRetry())
            .widening(widening(agent))
            .updatedAt(nullableTs(updatedAt));
        switch (effective.modelAccess()) {
            case ModelAccessSetting.Subscription subscription -> view.subscription(
                    new AgentSubscription(subscription.label()).runnerHostId(subscription.runnerHostId()));
            case ModelAccessSetting.SubscriptionGone gone -> view.subscription(new AgentSubscription(gone.label()));
            case ModelAccessSetting.Budget budget -> {
            }
        }
        return view;
    }

    /**
     * What the current version adds over the accepted one; null when nothing. With the instructions of both versions
     * when they changed, and the skills added or moved with their names, for the diff the acceptance shows (ADR-0103).
     */
    @Nullable AgentWidening widening(EffectiveAgent agent) {
        AgentDefinition.Widening widening = agent.pending();
        if (widening.none()) {
            return null;
        }
        Map<UUID, String> skillNames = skills.names(agent.agent().companyId(),
                widening.skills().stream().map(AgentDefinition.Widening.Skill::skillId).toList());
        return new AgentWidening(agent.acceptedVersion(), agent.agent().version(), new ArrayList<>(widening.systems()),
                widening.tools()
                    .stream()
                    .map(tool -> new AgentWidenedTool(tool.systemId(), tool.tool().name(), toolMode(tool.tool().mode()))
                        .was(tool.was() == null ? null
                                : tool.was().mode() == ToolMode.ASK ? AgentWidenedTool.WasEnum.ASK
                                        : AgentWidenedTool.WasEnum.ALLOW_FOR_READS))
                    .collect(Collectors.toCollection(ArrayList::new)),
                widening.repositories()
                    .stream()
                    .map(entry -> new AgentWidenedRepository(entry.repository().repositoryId(),
                            AgentRepositoryAccess.fromValue(entry.repository().access().name()))
                        .was(entry.was() != null ? AgentRepositoryAccess.fromValue(entry.was().name()) : null))
                    .collect(Collectors.toCollection(ArrayList::new)),
                new ArrayList<>(widening.hosts()))
            // While the instructions wait, the turns use the accepted version's (EffectiveAgent#content).
            .instructions(widening.instructions()
                    ? new AgentWidenedInstructions(agent.instructions(), agent.agent().instructions())
                    : null)
            .skills(widening.skills()
                .stream()
                .map(skill -> new AgentWidenedSkill(skill.skillId(), skillNames.getOrDefault(skill.skillId(), ""),
                        skill.version()).was(skill.was()))
                .collect(Collectors.toCollection(ArrayList::new)));
    }

    private static List<AgentRuntime> runtimes(AgentStore.AgentRow row) {
        return row.runtimes().stream().map(AgentViews::runtime).collect(Collectors.toCollection(ArrayList::new));
    }

    private static AgentRuntime runtime(com.flowtivo.tinfirm.runner.RuntimeKind runtime) {
        return AgentRuntime.fromValue(runtime.name());
    }

    private static AgentStatus status(AgentStore.AgentRow row) {
        return AgentStatus.fromValue(row.status().name());
    }

    private static AgentToolMode toolMode(ToolMode mode) {
        return AgentToolMode.fromValue(mode.name());
    }

    private static AgentModelAccess modelAccess(ModelAccessSetting setting) {
        return setting instanceof ModelAccessSetting.Budget ? AgentModelAccess.BUDGET : AgentModelAccess.SUBSCRIPTION;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable OffsetDateTime nullableTs(@Nullable Instant instant) {
        return instant != null ? ts(instant) : null;
    }
}
