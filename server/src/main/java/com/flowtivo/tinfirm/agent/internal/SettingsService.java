package com.flowtivo.tinfirm.agent.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentAccess;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentEvents;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.ModelAccessSetting;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.agent.ToolMode;
import com.flowtivo.tinfirm.agent.TriggerSettings;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.model.ModelAccess;
import com.flowtivo.tinfirm.model.RunModelAccess;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.HostCredentialInfo;
import com.flowtivo.tinfirm.runner.RunnerDirectory;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.AgentOwnServer;
import com.flowtivo.tinfirm.shared.api.model.AgentOwnServerTool;
import com.flowtivo.tinfirm.shared.api.model.AgentProject;
import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsToolMode;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolInfo;

/**
 * A person's settings of an agent (the package 3 contract, §3.7, §5.2 and §11): who may use the agent reads, saves and
 * resets their own, and accepts the agent's widening up to a version; only they see them. Settings narrow the agent,
 * never widen it (I4): on the agent's systems only {@code OFF} and {@code ASK}; {@code ALLOW} and added servers only
 * for the person's own MCP servers. A save checks the runtime among the agent's ({@code RUNTIME_NOT_LISTED}), the
 * model among those the person's model access allows ({@code MODEL_NOT_ALLOWED}), a subscription on the person's own
 * runner host that serves the runtime ({@code SUBSCRIPTION_NOT_SERVING}) and I4 ({@code SETTINGS_WIDEN}, also when the
 * database's check of I4 refuses a save that the agent's change meanwhile turned into a widening). Audited as
 * {@code settings.changed} (never the instructions) and {@code agent.accepted}.
 */
@Service
class SettingsService {

    static final int MAX_INSTRUCTIONS = 20_000;

    static final int MAX_PROJECTS = 20;

    static final int MAX_PROJECT_KEY = 200;

    static final int MAX_DEFAULT_REPOSITORIES = 100;

    static final int MAX_OWN_SERVERS = 20;

    static final String TARGET = "agent_settings";

    private final AgentStore agents;

    private final SettingsStore store;

    private final AgentVersions versions;

    private final AgentCallers callers;

    private final AgentViews views;

    private final CompanyAccessGuard companyAccess;

    private final Systems systems;

    private final ModelAccess modelAccess;

    private final RunnerDirectory runners;

    private final Principals principals;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final AgentSkills skills;

    private final AgentSkillStore skillStore;

    SettingsService(AgentStore agents, SettingsStore store, AgentVersions versions, AgentCallers callers,
            AgentViews views, CompanyAccessGuard companyAccess, Systems systems, ModelAccess modelAccess,
            RunnerDirectory runners, Principals principals, AuditLog auditLog, ApplicationEventPublisher events,
            TimeSource time, AgentSkills skills, AgentSkillStore skillStore) {
        this.skills = skills;
        this.skillStore = skillStore;
        this.agents = agents;
        this.store = store;
        this.versions = versions;
        this.callers = callers;
        this.views = views;
        this.companyAccess = companyAccess;
        this.systems = systems;
        this.modelAccess = modelAccess;
        this.runners = runners;
        this.principals = principals;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    // ---- the API ---------------------------------------------------------------------------------------------------

    /** The caller's settings of the agent, or the defaults when none are stored. */
    @Transactional(readOnly = true)
    public AgentSettings get(UUID companyId, UUID agentId) {
        Use use = requireUsable(companyId, agentId);
        return view(use.row(), use.principalId());
    }

    /** Saves the caller's settings of the agent as a whole; absent fields take their defaults. */
    @Transactional
    public AgentSettings put(UUID companyId, UUID agentId, AgentSettingsRequest input) {
        Use use = requireUsable(companyId, agentId);
        AgentStore.AgentRow agent = use.row();
        UUID principalId = use.principalId();
        // The checks first, then the row's lock: a save that meets a change of the agent meanwhile is refused by the
        // database's check of I4 (SETTINGS_WIDEN).
        UUID stored = store.find(agent.companyId(), agent.id(), principalId).map(SettingsStore.SettingsRow::id)
            .orElse(null);
        Wanted wanted = wanted(agent, Checks.person(principalId, stored), input);
        SettingsStore.SettingsRow row = ensure(agent, principalId);
        save(row, wanted);
        return view(agent, principalId);
    }

    /** Returns the caller's settings to the defaults; the version they accepted stays. */
    @Transactional
    public AgentSettings reset(UUID companyId, UUID agentId) {
        Use use = requireUsable(companyId, agentId);
        Optional<SettingsStore.SettingsRow> row = store.lock(companyId, agentId, use.principalId());
        row.ifPresent(stored -> save(stored, Wanted.DEFAULTS));
        return view(use.row(), use.principalId());
    }

    /** The caller accepts the agent's widening up to {@code version}, for their own conversations (contract §11). */
    @Transactional
    public AgentSettings accept(UUID companyId, UUID agentId, int version) {
        Use use = requireUsable(companyId, agentId);
        AgentStore.AgentRow agent = use.row();
        if (version < 1 || version > agent.version()) {
            throw ApiException.validation("The agent has versions 1 to " + agent.version());
        }
        SettingsStore.SettingsRow row = ensure(agent, use.principalId());
        if (version > row.acceptedVersion()) {
            store.setAcceptedVersion(companyId, row.id(), version, time.now());
            auditLog.record(AuditRecord.company(companyId, AuditAction.AGENT_ACCEPTED, CurrentActor.actor())
                .target(AgentVersions.TARGET, agentId)
                .detail("principalId", use.principalId().toString())
                .detail("version", version)
                .detail("previousVersion", row.acceptedVersion())
                .build());
            events.publishEvent(new AgentEvents.AgentAccepted(companyId, agentId, use.principalId(), version));
        }
        return view(agent, use.principalId());
    }

    // ---- Settings (for conversations) ------------------------------------------------------------------------------

    /** The settings in effect, as {@link com.flowtivo.tinfirm.agent.Settings#effective} answers them. */
    EffectiveSettings effective(UUID companyId, UUID agentId, SettingsOwner owner) {
        AgentStore.AgentRow agent = agents.findLive(companyId, agentId)
            .orElseThrow(() -> new IllegalArgumentException("No live agent " + agentId));
        return switch (owner) {
            case SettingsOwner.Person person -> effective(agent, person.principalId());
            case SettingsOwner.Trigger trigger -> effective(agent, store.findOfTrigger(companyId, trigger.triggerId())
                .filter(row -> row.agentId().equals(agentId))
                .orElseThrow(() -> new IllegalArgumentException("No settings of the trigger " + trigger.triggerId()
                        + " for the agent " + agentId)), owner);
        };
    }

    /** The person's first use: their row, unless they have one; then the settings in effect. */
    EffectiveSettings firstUse(UUID companyId, UUID agentId, UUID principalId) {
        AgentStore.AgentRow agent = agents.findLive(companyId, agentId)
            .orElseThrow(() -> new IllegalArgumentException("No live agent " + agentId));
        principals.find(companyId, principalId)
            .filter(PrincipalInfo::member)
            .filter(principal -> principal.status() != com.flowtivo.tinfirm.principal.PrincipalStatus.LEFT)
            .orElseThrow(() -> new IllegalArgumentException("Not a current member's principal: " + principalId));
        ensure(agent, principalId);
        return effective(agent, principalId);
    }

    // ---- a trigger's settings (the package 4 contract, §3.3) ---------------------------------------------------------

    private static Checks checks(TriggerSettings.Owner owner, @Nullable UUID settingsId) {
        return new Checks(owner.authorPrincipalId(), owner.payerPrincipalId(), owner.ownServersAllowed(),
                owner.subscriptionAllowed(), false, true, settingsId);
    }

    private AgentStore.AgentRow live(UUID companyId, UUID agentId) {
        return agents.findLive(companyId, agentId)
            .orElseThrow(() -> new IllegalArgumentException("No live agent " + agentId));
    }

    void checkTrigger(UUID companyId, UUID agentId, TriggerSettings.Owner owner,
            @Nullable AgentSettingsRequest input) {
        AgentStore.AgentRow agent = live(companyId, agentId);
        UUID settingsId = store.findOfTrigger(companyId, owner.triggerId()).map(SettingsStore.SettingsRow::id)
            .orElse(null);
        wanted(agent, checks(owner, settingsId), input != null ? input : new AgentSettingsRequest());
    }

    Map<String, Object> createTrigger(UUID companyId, UUID agentId, int acceptedVersion, TriggerSettings.Owner owner,
            @Nullable AgentSettingsRequest input) {
        AgentStore.AgentRow agent = live(companyId, agentId);
        if (acceptedVersion < 1 || acceptedVersion > agent.version()) {
            throw new IllegalArgumentException("The agent has versions 1 to " + agent.version());
        }
        Wanted wanted = wanted(agent, checks(owner, null), input != null ? input : new AgentSettingsRequest());
        store.insertOfTrigger(SettingsStore.SettingsRow.triggerDefaults(Ids.newId(), companyId, agentId,
                owner.triggerId(), acceptedVersion, time.now()));
        return save(store.lockOfTrigger(companyId, owner.triggerId()).orElseThrow(), wanted);
    }

    Map<String, Object> saveTrigger(UUID companyId, UUID agentId, TriggerSettings.Owner owner,
            AgentSettingsRequest input) {
        AgentStore.AgentRow agent = live(companyId, agentId);
        SettingsStore.SettingsRow row = store.lockOfTrigger(companyId, owner.triggerId())
            .orElseThrow(() -> new IllegalArgumentException("No settings of the trigger " + owner.triggerId()));
        return save(row, wanted(agent, checks(owner, row.id()), input));
    }

    int acceptTrigger(UUID companyId, UUID agentId, UUID triggerId, int version) {
        AgentStore.AgentRow agent = live(companyId, agentId);
        if (version < 1 || version > agent.version()) {
            throw ApiException.validation("The agent has versions 1 to " + agent.version());
        }
        SettingsStore.SettingsRow row = store.lockOfTrigger(companyId, triggerId)
            .orElseThrow(() -> new IllegalArgumentException("No settings of the trigger " + triggerId));
        if (version > row.acceptedVersion()) {
            store.setAcceptedVersion(companyId, row.id(), version, time.now());
        }
        return row.acceptedVersion();
    }

    int triggerAcceptedVersion(UUID companyId, UUID triggerId) {
        return store.findOfTrigger(companyId, triggerId).map(SettingsStore.SettingsRow::acceptedVersion)
            .orElseThrow(() -> new IllegalArgumentException("No settings of the trigger " + triggerId));
    }

    AgentSettings viewTrigger(UUID companyId, UUID agentId, UUID triggerId) {
        AgentStore.AgentRow agent = agents.find(companyId, agentId)
            .orElseThrow(() -> new IllegalArgumentException("No agent " + agentId));
        SettingsStore.SettingsRow row = store.findOfTrigger(companyId, triggerId)
            .orElseThrow(() -> new IllegalArgumentException("No settings of the trigger " + triggerId));
        return views.settings(effective(agent, row, new SettingsOwner.Trigger(triggerId)), row.updatedAt());
    }

    boolean triggerAddsOwnServers(UUID companyId, UUID triggerId) {
        return store.findOfTrigger(companyId, triggerId)
            .map(row -> store.systems(companyId, row.id())
                .stream()
                .anyMatch(system -> system.state().equals(SettingsStore.SystemRow.ADDED)))
            .orElse(false);
    }

    // ---- rules -----------------------------------------------------------------------------------------------------

    /** A caller who may use the agent, their principal, and the agent's row. */
    record Use(AgentStore.AgentRow row, UUID principalId) {
    }

    /** The caller, who must use the agent: 404 when they do not see it, 403 when they see it only. */
    Use requireUsable(UUID companyId, UUID agentId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        AgentCallers.Caller caller = callers.current(access);
        AgentStore.AgentRow row = agents.findLive(companyId, agentId).orElseThrow(AgentService::notFound);
        AgentAccess rights = AgentCallers.access(caller, row.ownerPrincipalId(), agents.shares(companyId, agentId));
        if (!rights.sees()) {
            throw AgentService.notFound();
        }
        if (!rights.uses()) {
            throw ApiException.forbidden(ProblemCode.AGENT_NOT_USABLE,
                    "Only the agent's owner and the people it is shared with use it");
        }
        return new Use(row, Objects.requireNonNull(caller.principalId()));
    }

    /** The person's row, made with the defaults and the current version accepted when they have none; locked. */
    private SettingsStore.SettingsRow ensure(AgentStore.AgentRow agent, UUID principalId) {
        store.insertIfAbsent(SettingsStore.SettingsRow.defaults(Ids.newId(), agent.companyId(), agent.id(),
                principalId, agent.version(), time.now()));
        return store.lock(agent.companyId(), agent.id(), principalId).orElseThrow();
    }

    /** The person's settings in effect over the agent's row. */
    EffectiveSettings effective(AgentStore.AgentRow agent, UUID principalId) {
        Optional<SettingsStore.SettingsRow> stored = store.find(agent.companyId(), agent.id(), principalId);
        SettingsOwner owner = new SettingsOwner.Person(principalId);
        if (stored.isEmpty()) {
            EffectiveAgent effectiveAgent = versions.effective(agent, agent.version());
            return new EffectiveSettings(agent.id(), owner, false, effectiveAgent, agent.runtimes().getFirst(), null,
                    null, EffortLevel.DEFAULT, new ModelAccessSetting.Budget(), null, List.of(), List.of(), Set.of(),
                    List.of(), List.of(), false, Set.of(),
                    skills.turnSkills(agent.companyId(), effectiveAgent.content().skills(), Set.of()),
                    false);
        }
        return effective(agent, stored.get(), owner);
    }

    /** The stored settings in effect over the agent's row: a person's, or a trigger's (package 4, §3.3). */
    EffectiveSettings effective(AgentStore.AgentRow agent, SettingsStore.SettingsRow row, SettingsOwner owner) {
        UUID companyId = agent.companyId();
        EffectiveAgent effectiveAgent = versions.effective(agent, row.acceptedVersion());
        AgentDefinition definition = effectiveAgent.definition();
        List<SettingsStore.SystemRow> systemRows = store.systems(companyId, row.id());
        Set<UUID> added = systemRows.stream()
            .filter(system -> system.state().equals(SettingsStore.SystemRow.ADDED))
            .map(SettingsStore.SystemRow::systemId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> off = systemRows.stream()
            .filter(system -> system.state().equals(SettingsStore.SystemRow.OFF))
            .map(SettingsStore.SystemRow::systemId)
            .filter(systemId -> definition.system(systemId).isPresent())
            .collect(Collectors.toSet());
        List<EffectiveSettings.ToolOverride> overrides = new ArrayList<>();
        Map<UUID, List<AgentDefinition.ToolEntry>> ownTools = new LinkedHashMap<>();
        added.forEach(systemId -> ownTools.put(systemId, new ArrayList<>()));
        for (SettingsStore.ToolRow tool : store.tools(companyId, row.id())) {
            if (added.contains(tool.systemId())) {
                ownTools.get(tool.systemId())
                    .add(new AgentDefinition.ToolEntry(tool.tool(), ToolMode.valueOf(tool.mode()),
                            tool.chosenForEffect()));
            }
            else if (definition.tool(tool.systemId(), tool.tool()).isPresent()) {
                overrides.add(new EffectiveSettings.ToolOverride(tool.systemId(), tool.tool(),
                        EffectiveSettings.OverrideMode.valueOf(tool.mode())));
            }
        }
        List<EffectiveSettings.OwnServer> ownServers = ownTools.entrySet()
            .stream()
            .map(entry -> new EffectiveSettings.OwnServer(entry.getKey(), entry.getValue()))
            .toList();
        RuntimeKind runtime = row.runtime() != null && agent.runtimes().contains(row.runtime()) ? row.runtime()
                : agent.runtimes().getFirst();
        ModelAccessSetting access = row.modelAccess() == SettingsStore.ModelAccessKind.BUDGET
                ? new ModelAccessSetting.Budget()
                : row.subscriptionHostId() != null
                        ? new ModelAccessSetting.Subscription(row.subscriptionHostId(),
                                Objects.requireNonNull(row.subscriptionLabel()))
                        : new ModelAccessSetting.SubscriptionGone(Objects.requireNonNullElse(row.subscriptionLabel(),
                                ""));
        List<UUID> defaultRepositories = row.defaultRepositoryIds()
            .stream()
            .filter(id -> definition.repository(id).isPresent())
            .toList();
        List<EffectiveSettings.Project> projects = AgentJson.projects(row.defaultProjects())
            .stream()
            .filter(project -> definition.system(project.systemId()).isPresent())
            .toList();
        // The agent's skills are those of the version the turns use, like its instructions (ADR-0064; ADR-0103): a
        // switch of one it no longer has went with it.
        Set<UUID> skillsOff = skillStore.skillsOff(companyId, row.id());
        return new EffectiveSettings(agent.id(), owner, true, effectiveAgent, runtime, row.runtime(), row.model(),
                row.effort(), access, row.instructions(), defaultRepositories, projects, off, overrides, ownServers,
                row.memoryOn(), skillsOff, skills.turnSkills(companyId, effectiveAgent.content().skills(), skillsOff),
                row.autoRetry());
    }

    /** The person's settings in effect, as the API shows them. */
    AgentSettings view(AgentStore.AgentRow agent, UUID principalId) {
        EffectiveSettings effective = effective(agent, principalId);
        Instant updatedAt = store.find(agent.companyId(), agent.id(), principalId)
            .map(SettingsStore.SettingsRow::updatedAt)
            .orElse(null);
        return views.settings(effective, updatedAt);
    }

    // ---- a save ----------------------------------------------------------------------------------------------------

    /** What a save asks for, checked. */
    private record Wanted(@Nullable RuntimeKind runtime, @Nullable String model, EffortLevel effort,
            SettingsStore.ModelAccessKind modelAccess, @Nullable UUID subscriptionHostId,
            @Nullable String subscriptionLabel, @Nullable String instructions, List<UUID> defaultRepositoryIds,
            List<EffectiveSettings.Project> defaultProjects, List<SettingsStore.SystemRow> systems,
            List<SettingsStore.ToolRow> tools, boolean memoryOn, List<UUID> skillsOff, boolean autoRetry) {

        static final Wanted DEFAULTS = new Wanted(null, null, EffortLevel.DEFAULT,
                SettingsStore.ModelAccessKind.BUDGET, null, null, null, List.of(), List.of(), List.of(), List.of(),
                false, List.of(), false);
    }

    /**
     * Whose settings a save checks (the package 3 contract, §5.2; the package 4 contract, §3.3 for a trigger's).
     *
     * @param ownerPrincipalId  whose own MCP servers may be added, and whose personal host a subscription is on: the
     *                          person, or the trigger's author
     * @param payerPrincipalId  whose model key's models the model must be among: the person, or the trigger's payer
     * @param ownServers        whether the owner's own MCP servers may be added (a trigger: only while every row of its
     *                          table is the author's own account, T3)
     * @param subscriptions     whether a subscription may pay (a trigger: only when the company allows one for a
     *                          trigger and its payer is its author, ADR-0085 decision 7)
     * @param instructions      whether instructions may be added (a trigger: never, its instruction is in every turn)
     * @param model             whether settings a budget pays for must name a model at the save: a trigger's, whose
     *                          runs start with nobody there to read why one fails; a person's readiness and start say
     *                          so instead ({@code RunModelAccess.Budget#refusal}), and the rest of their settings save
     * @param settingsId        the stored row, whose tools keep the effect they were chosen for; null for none yet
     */
    record Checks(UUID ownerPrincipalId, UUID payerPrincipalId, boolean ownServers, boolean subscriptions,
            boolean instructions, boolean model, @Nullable UUID settingsId) {

        static Checks person(UUID principalId, @Nullable UUID settingsId) {
            return new Checks(principalId, principalId, true, true, true, false, settingsId);
        }
    }

    private Wanted wanted(AgentStore.AgentRow agent, Checks checks, AgentSettingsRequest input) {
        UUID companyId = agent.companyId();
        UUID principalId = checks.ownerPrincipalId();
        AgentDefinition definition = agents.definition(companyId, agent.id());
        RuntimeKind runtime = input.getRuntime() != null ? RuntimeKind.valueOf(input.getRuntime().getValue()) : null;
        if (runtime != null && !agent.runtimes().contains(runtime)) {
            throw ApiException.badRequest(ProblemCode.RUNTIME_NOT_LISTED,
                    "The agent is tested on " + agent.runtimes() + " only");
        }
        RuntimeKind effectiveRuntime = runtime != null ? runtime : agent.runtimes().getFirst();
        String model = input.getModel() != null && !input.getModel().isBlank() ? input.getModel().strip() : null;
        if (model != null) {
            List<String> allowed = modelAccess.models(companyId, checks.payerPrincipalId());
            if (!RunModelAccess.allows(allowed, model)) {
                throw ApiException.badRequest(ProblemCode.MODEL_NOT_ALLOWED,
                        "Your model access allows " + String.join(", ", allowed));
            }
        }
        EffortLevel effort = input.getEffort() != null ? EffortLevel.valueOf(input.getEffort().getValue())
                : EffortLevel.DEFAULT;
        SettingsStore.ModelAccessKind access = input.getModelAccess() != null
                ? SettingsStore.ModelAccessKind.valueOf(input.getModelAccess().getValue())
                : SettingsStore.ModelAccessKind.BUDGET;
        UUID hostId = null;
        String label = null;
        if (access == SettingsStore.ModelAccessKind.SUBSCRIPTION) {
            if (!checks.subscriptions()) {
                throw ApiException.badRequest(ProblemCode.SUBSCRIPTION_NOT_SERVING, "A subscription pays for a "
                        + "trigger only when the company allows it and the trigger's payer is its author; otherwise "
                        + "a trigger pays with its payer's budget");
            }
            if (input.getSubscription() == null || input.getSubscription().getRunnerHostId() == null) {
                throw ApiException.validation("Name the subscription: its runner host and label");
            }
            hostId = input.getSubscription().getRunnerHostId();
            label = input.getSubscription().getLabel();
            requireSubscription(companyId, principalId, hostId, label, effectiveRuntime);
        }
        else if (input.getSubscription() != null) {
            throw ApiException.validation("A subscription is named only with the model access SUBSCRIPTION");
        }
        else if (model == null && checks.model()) {
            // A budget's run goes through the gateway, which serves only the models it lists (RunModelAccess.Budget).
            throw ApiException.badRequest(ProblemCode.MODEL_REQUIRED, "Choose a model: a run paid by a budget goes "
                    + "through the company's model gateway, which serves only the models it lists");
        }
        String instructions = input.getInstructions() != null && !input.getInstructions().isBlank()
                ? input.getInstructions() : null;
        if (instructions != null && !checks.instructions()) {
            throw ApiException.validation("A trigger's settings add no instructions: write them in its instruction");
        }
        if (instructions != null && instructions.length() > MAX_INSTRUCTIONS) {
            throw ApiException.validation("instructions must be at most " + MAX_INSTRUCTIONS + " characters");
        }
        List<UUID> repositories = new ArrayList<>(new LinkedHashSet<>(
                input.getDefaultRepositoryIds() != null ? input.getDefaultRepositoryIds() : List.of()));
        if (repositories.size() > MAX_DEFAULT_REPOSITORIES) {
            throw ApiException.validation("At most " + MAX_DEFAULT_REPOSITORIES + " default repositories");
        }
        for (UUID repositoryId : repositories) {
            if (definition.repository(repositoryId).isEmpty()) {
                throw ApiException.badRequest(ProblemCode.SETTINGS_WIDEN,
                        "The repository " + repositoryId + " is not one of the agent's");
            }
        }
        List<EffectiveSettings.Project> projects = projects(definition,
                input.getDefaultProjects() != null ? input.getDefaultProjects() : List.of());
        List<SettingsStore.SystemRow> systemRows = new ArrayList<>();
        List<SettingsStore.ToolRow> toolRows = new ArrayList<>();
        Set<UUID> off = new LinkedHashSet<>(input.getSystemsOff() != null ? input.getSystemsOff() : List.of());
        for (UUID systemId : off) {
            if (definition.system(systemId).isEmpty()) {
                throw notOnAgent(systemId);
            }
            systemRows.add(new SettingsStore.SystemRow(systemId, SettingsStore.SystemRow.OFF));
        }
        Set<String> toolKeys = new HashSet<>();
        for (AgentSettingsToolMode tool : input.getToolModes() != null ? input.getToolModes()
                : List.<AgentSettingsToolMode>of()) {
            if (definition.system(tool.getSystemId()).isEmpty()) {
                throw notOnAgent(tool.getSystemId());
            }
            AgentDefinition.ToolEntry entry = definition.tool(tool.getSystemId(), tool.getTool())
                .orElseThrow(() -> AgentService.unknownTool(tool.getSystemId(), tool.getTool(),
                        "The tool " + tool.getTool() + " is not one of the agent's"));
            if (!toolKeys.add(tool.getSystemId() + "/" + tool.getTool())) {
                throw ApiException.validation("The tool " + tool.getTool() + " is named twice");
            }
            toolRows.add(new SettingsStore.ToolRow(tool.getSystemId(), tool.getTool(), tool.getMode().getValue(),
                    entry.chosenForEffect()));
        }
        List<AgentOwnServer> own = input.getOwnServers() != null ? input.getOwnServers() : List.of();
        if (!own.isEmpty() && !checks.ownServers()) {
            throw ApiException.badRequest(ProblemCode.SETTINGS_WIDEN, "The author's own MCP servers act only while "
                    + "every account of the trigger is the author's own");
        }
        ownServers(agent, principalId, checks.settingsId(), own, systemRows, toolRows);
        return new Wanted(runtime, model, effort, access, hostId, label, instructions, repositories, projects,
                systemRows, toolRows, Boolean.TRUE.equals(input.getMemory()),
                skillsOff(agent, input.getSkillsOff()), Boolean.TRUE.equals(input.getAutoRetry()));
    }

    /** The skills of the agent a save switches off (ADR-0064): each one the agent has, at most 20. */
    private List<UUID> skillsOff(AgentStore.AgentRow agent, @Nullable List<UUID> input) {
        List<UUID> off = new ArrayList<>(new LinkedHashSet<>(input != null ? input : List.of()));
        if (off.size() > AgentSkills.MAX_SKILLS) {
            throw ApiException.validation("An agent has at most " + AgentSkills.MAX_SKILLS + " skills to switch off");
        }
        Set<UUID> ofAgent = skills.skillIds(agent.companyId(), agent.id());
        for (UUID skillId : off) {
            if (!ofAgent.contains(skillId)) {
                throw ApiException.badRequest(ProblemCode.SKILL_NOT_ON_AGENT, "The skill " + skillId
                        + " is not one of the agent's", Map.of("details", Map.of("skillId", skillId.toString())));
            }
        }
        off.sort(Comparator.naturalOrder());
        return off;
    }

    /** A subscription on the person's own personal runner host, still reported, that serves the runtime. */
    private void requireSubscription(UUID companyId, UUID principalId, UUID hostId, String label,
            RuntimeKind runtime) {
        Optional<RunnerHostInfo> host = runners.host(companyId, hostId).filter(info -> info.personalOf(principalId));
        Optional<HostCredentialInfo> credential = host.isEmpty() ? Optional.empty()
                : runners.credentials(companyId, List.of(hostId))
                    .stream()
                    .filter(candidate -> candidate.label().equals(label) && candidate.reported())
                    .findFirst();
        if (credential.isEmpty() || !credential.get().runtimes().contains(runtime)) {
            throw ApiException.badRequest(ProblemCode.SUBSCRIPTION_NOT_SERVING, "The subscription " + label
                    + " is not one on your own runner host that serves " + runtime);
        }
    }

    private static List<EffectiveSettings.Project> projects(AgentDefinition definition, List<AgentProject> input) {
        if (input.size() > MAX_PROJECTS) {
            throw ApiException.validation("At most " + MAX_PROJECTS + " default projects");
        }
        List<EffectiveSettings.Project> projects = new ArrayList<>();
        for (AgentProject project : input) {
            if (definition.system(project.getSystemId()).isEmpty()) {
                throw notOnAgent(project.getSystemId());
            }
            String key = project.getKey().strip();
            if (key.isEmpty() || key.length() > MAX_PROJECT_KEY) {
                throw ApiException.validation("A project's key has 1 to " + MAX_PROJECT_KEY + " characters");
            }
            EffectiveSettings.Project entry = new EffectiveSettings.Project(project.getSystemId(), key);
            if (!projects.contains(entry)) {
                projects.add(entry);
            }
        }
        return projects;
    }

    /**
     * The person's own MCP servers (open point 6): each an {@code MCP} server they own; its tools as named (each one
     * it serves and has switched on), or every one it serves, reads {@code ALLOW} and writes and unconfirmed tools
     * {@code ASK}. A tool whose mode stays keeps the effect it was chosen for.
     */
    private void ownServers(AgentStore.AgentRow agent, UUID principalId, @Nullable UUID settingsId,
            List<AgentOwnServer> input, List<SettingsStore.SystemRow> systemRows, List<SettingsStore.ToolRow> toolRows) {
        if (input.size() > MAX_OWN_SERVERS) {
            throw ApiException.validation("At most " + MAX_OWN_SERVERS + " own servers");
        }
        UUID companyId = agent.companyId();
        Map<String, SettingsStore.ToolRow> previous = (settingsId != null ? store.tools(companyId, settingsId)
                : List.<SettingsStore.ToolRow>of())
            .stream()
            .collect(Collectors.toMap(tool -> tool.systemId() + "/" + tool.tool(), Function.identity()));
        Set<UUID> seen = new HashSet<>();
        for (AgentOwnServer server : input) {
            UUID systemId = server.getSystemId();
            if (!seen.add(systemId)) {
                throw ApiException.validation("The server " + systemId + " is named twice");
            }
            SystemInfo system = systems.find(companyId, systemId)
                .filter(info -> info.kind() == SystemKind.MCP && info.ownedBy(principalId))
                .orElseThrow(() -> ApiException.badRequest(ProblemCode.SETTINGS_WIDEN,
                        "Settings add only your own MCP servers"));
            systemRows.add(new SettingsStore.SystemRow(system.id(), SettingsStore.SystemRow.ADDED));
            Map<String, ToolInfo> served = systems.tools(companyId, systemId)
                .stream()
                .filter(ToolInfo::enabled)
                .collect(Collectors.toMap(ToolInfo::name, Function.identity(), (a, b) -> a));
            if (server.getTools() == null) {
                served.values().forEach(tool -> toolRows.add(new SettingsStore.ToolRow(systemId, tool.name(),
                        tool.effect() == ToolEffect.READ ? "ALLOW" : "ASK", tool.effect().effective())));
                continue;
            }
            Set<String> names = new HashSet<>();
            for (AgentOwnServerTool tool : server.getTools()) {
                if (!names.add(tool.getName())) {
                    throw ApiException.validation("The tool " + tool.getName() + " is named twice");
                }
                ToolInfo info = served.get(tool.getName());
                if (info == null) {
                    throw AgentService.unknownTool(systemId, tool.getName(), "Your server " + system.name()
                            + " does not serve the tool " + tool.getName() + ", or it is switched off");
                }
                String mode = tool.getMode().getValue();
                ToolEffect shown = tool.getChosenForEffect() != null
                        ? ToolEffect.valueOf(tool.getChosenForEffect().getValue()) : null;
                SettingsStore.ToolRow was = previous.get(systemId + "/" + tool.getName());
                // As the agent's tools (I19): only an effect sent chooses an unchanged mode anew.
                ToolEffect chosen = was != null && was.mode().equals(mode) && shown == null ? was.chosenForEffect()
                        : AgentDefinition.ToolEntry.chosenFor(shown, info.effect());
                toolRows.add(new SettingsStore.ToolRow(systemId, tool.getName(), mode, chosen));
            }
        }
    }

    /**
     * Writes the wanted settings; a person's are audited and announced when anything changed (a trigger's by the
     * trigger module, as {@code trigger.settings_changed}). Answers what changed, as {@code settings.changed} names
     * it; empty when nothing did.
     */
    private Map<String, Object> save(SettingsStore.SettingsRow row, Wanted wanted) {
        UUID companyId = row.companyId();
        List<SettingsStore.SystemRow> previousSystems = store.systems(companyId, row.id());
        List<SettingsStore.ToolRow> previousTools = store.tools(companyId, row.id());
        Set<UUID> previousSkillsOff = skillStore.skillsOff(companyId, row.id());
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(row.runtime(), wanted.runtime())) {
            changed.add("runtime");
        }
        if (!Objects.equals(row.model(), wanted.model())) {
            changed.add("model");
        }
        if (row.effort() != wanted.effort()) {
            changed.add("effort");
        }
        if (row.modelAccess() != wanted.modelAccess()
                || !Objects.equals(row.subscriptionHostId(), wanted.subscriptionHostId())
                || !Objects.equals(row.subscriptionLabel(), wanted.subscriptionLabel())) {
            changed.add("modelAccess");
        }
        if (!Objects.equals(row.instructions(), wanted.instructions())) {
            changed.add("instructions");
        }
        if (!row.defaultRepositoryIds().equals(wanted.defaultRepositoryIds())) {
            changed.add("defaultRepositoryIds");
        }
        if (!AgentJson.projects(row.defaultProjects()).equals(wanted.defaultProjects())) {
            changed.add("defaultProjects");
        }
        if (!Set.copyOf(previousSystems).equals(Set.copyOf(wanted.systems()))) {
            changed.add("systems");
        }
        if (!Set.copyOf(previousTools).equals(Set.copyOf(wanted.tools()))) {
            changed.add("tools");
        }
        if (!previousSkillsOff.equals(Set.copyOf(wanted.skillsOff()))) {
            changed.add("skillsOff");
        }
        if (row.memoryOn() != wanted.memoryOn()) {
            changed.add("memory");
        }
        if (row.autoRetry() != wanted.autoRetry()) {
            changed.add("autoRetry");
        }
        if (changed.isEmpty()) {
            return Map.of();
        }
        Instant now = time.now();
        store.update(new SettingsStore.SettingsRow(row.id(), companyId, row.agentId(), row.principalId(),
                row.triggerId(), wanted.runtime(), wanted.model(), wanted.effort(), wanted.modelAccess(), wanted.subscriptionHostId(),
                wanted.subscriptionLabel(), wanted.instructions(), wanted.defaultRepositoryIds(),
                AgentJson.projects(wanted.defaultProjects()), row.acceptedVersion(), wanted.memoryOn(),
                wanted.autoRetry(), row.createdAt(), now));
        try {
            store.replaceSystemsAndTools(companyId, row.id(), wanted.systems(), wanted.tools());
            skillStore.replaceSkillsOff(companyId, row.id(), row.agentId(), wanted.skillsOff());
        }
        catch (DataIntegrityViolationException ex) {
            // The database's I4: the agent or a server changed after the checks above (a system or a skill left the
            // agent).
            if (SettingsStore.widens(ex) || AgentSkillStore.widens(ex)) {
                throw ApiException.badRequest(ProblemCode.SETTINGS_WIDEN,
                        "The settings would widen the agent as it is now; reload them and save again");
            }
            throw ex;
        }
        Set<UUID> added = wanted.systems()
            .stream()
            .filter(system -> system.state().equals(SettingsStore.SystemRow.ADDED))
            .map(SettingsStore.SystemRow::systemId)
            .collect(Collectors.toSet());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("changed", changed);
        details.put("systemsOff", wanted.systems()
            .stream()
            .filter(system -> system.state().equals(SettingsStore.SystemRow.OFF))
            .map(system -> system.systemId().toString())
            .toList());
        details.put("toolsAsk", toolDetails(wanted.tools(), added, "ASK"));
        details.put("toolsOff", toolDetails(wanted.tools(), added, "OFF"));
        details.put("ownServers", added.stream().sorted().map(UUID::toString).toList());
        details.put("skillsOff", wanted.skillsOff().stream().map(UUID::toString).toList());
        if (row.principalId() == null) {
            return details;
        }
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.SETTINGS_CHANGED, CurrentActor.actor())
            .target(TARGET, row.id())
            .detail("agentId", row.agentId().toString())
            .detail("principalId", row.principalId().toString());
        details.forEach(audit::detail);
        auditLog.record(audit.build());
        events.publishEvent(new AgentEvents.SettingsChanged(companyId, row.agentId(), row.principalId()));
        return details;
    }

    private static List<Map<String, String>> toolDetails(List<SettingsStore.ToolRow> tools, Set<UUID> added,
            String mode) {
        return tools.stream()
            .filter(tool -> !added.contains(tool.systemId()) && tool.mode().equals(mode))
            .map(tool -> Map.of("systemId", tool.systemId().toString(), "tool", tool.tool()))
            .toList();
    }

    private static ApiException notOnAgent(UUID systemId) {
        return ApiException.badRequest(ProblemCode.SYSTEM_NOT_ON_AGENT,
                "The system " + systemId + " is not one the agent lists",
                Map.of("details", Map.of("systemId", systemId.toString())));
    }
}
