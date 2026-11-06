package com.flowtivo.tinfirm.agent.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentAccess;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentEvents;
import com.flowtivo.tinfirm.agent.AgentRuns;
import com.flowtivo.tinfirm.agent.AgentStatus;
import com.flowtivo.tinfirm.agent.Handles;
import com.flowtivo.tinfirm.agent.NetworkHosts;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.agent.ToolMode;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.Agent;
import com.flowtivo.tinfirm.shared.api.model.AgentCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.AgentHostInput;
import com.flowtivo.tinfirm.shared.api.model.AgentPage;
import com.flowtivo.tinfirm.shared.api.model.AgentRepositoryInput;
import com.flowtivo.tinfirm.shared.api.model.AgentRuntime;
import com.flowtivo.tinfirm.shared.api.model.AgentSkillInput;
import com.flowtivo.tinfirm.shared.api.model.AgentSystemInput;
import com.flowtivo.tinfirm.shared.api.model.AgentToolInput;
import com.flowtivo.tinfirm.shared.api.model.AgentUpdateRequest;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.skill.SkillInfo;
import com.flowtivo.tinfirm.skill.Skills;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolInfo;

/**
 * Agents (the package 3 contract, §3.1 to §3.6, §5.1, §6 and §11): every member creates agents and becomes their
 * owner; an agent is seen by its owner, the members of the groups it is shared with, the members it is shared with on
 * their own and admins, and edited, shared, paused and deleted by its owner and admins ({@link AgentCallers#access});
 * anything else is 404. Every change of the definition makes a version ({@link AgentVersions}), audited by ids and
 * names (never the description or the instructions); a request that changes nothing is neither versioned nor audited.
 * Each change is announced ({@link AgentEvents}) in its transaction.
 */
@Service
class AgentService {

    static final int MAX_NAME = 100;

    static final int MAX_DESCRIPTION = 1000;

    static final int MAX_INSTRUCTIONS = 100_000;

    static final int MAX_SYSTEMS = 100;

    static final int MAX_TOOLS_PER_SYSTEM = 200;

    static final int MAX_REPOSITORIES = 100;

    static final int MAX_HOSTS = 100;

    static final int MAX_REASON = 500;

    static final int MAX_SHARES = 50;

    static final int MAX_MEMBER_SHARES = 100;

    /** The bounds of an agent's time limit of a turn, in minutes ({@code agent_turn_time_limit_ck}). */
    static final int MIN_TURN_TIME_LIMIT = 5;

    static final int MAX_TURN_TIME_LIMIT = 1440;

    /**
     * The kinds an agent lists (contract §3.2): those with tools or repositories, and Slack for its {@code history}
     * (the package 5 contract, open point 13); not Teams, which has no tools, nor the webhook.
     */
    static final Set<SystemKind> KINDS = Set.of(SystemKind.JIRA_DC, SystemKind.JIRA_CLOUD, SystemKind.GITHUB,
            SystemKind.GITLAB, SystemKind.GIT, SystemKind.MCP, SystemKind.SLACK);

    private static final String NAME_INDEX = "agent_name_uq";

    private static final String HANDLE_INDEX = "agent_handle_uq";

    private final AgentStore store;

    private final SettingsStore settings;

    private final AgentVersions versions;

    private final AgentCallers callers;

    private final AgentViews views;

    private final CompanyAccessGuard companyAccess;

    private final Systems systems;

    private final GroupMembership groups;

    private final ObjectProvider<AgentRuns> runs;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final Principals principals;

    private final AgentSkills skills;

    private final Skills library;

    AgentService(AgentStore store, SettingsStore settings, AgentVersions versions, AgentCallers callers,
            AgentViews views, CompanyAccessGuard companyAccess, Systems systems, GroupMembership groups,
            ObjectProvider<AgentRuns> runs, AuditLog auditLog, ApplicationEventPublisher events, TimeSource time,
            Principals principals, AgentSkills skills, Skills library) {
        this.skills = skills;
        this.library = library;
        this.principals = principals;
        this.store = store;
        this.settings = settings;
        this.versions = versions;
        this.callers = callers;
        this.views = views;
        this.companyAccess = companyAccess;
        this.systems = systems;
        this.groups = groups;
        this.runs = runs;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    /** The agents the caller sees, by name; with {@code usable}, only those they may use. */
    @Transactional(readOnly = true)
    public AgentPage list(UUID companyId, @Nullable Boolean usable, @Nullable Integer limit, @Nullable String cursor) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        AgentCallers.Caller caller = callers.current(access);
        PageRequest request = PageRequest.of(limit, cursor);
        AgentStore.Scope scope = caller.admin() && !Boolean.TRUE.equals(usable) ? AgentStore.Scope.ALL
                : AgentStore.Scope.USABLE;
        if (scope == AgentStore.Scope.USABLE && caller.principalId() == null) {
            return new AgentPage(new ArrayList<>());
        }
        List<AgentStore.AgentRow> rows = request.firstPage()
                ? store.page(companyId, scope, caller.principalId(), caller.groupIds(), null, null,
                        request.fetchSize())
                : store.page(companyId, scope, caller.principalId(), caller.groupIds(), request.key(0, 2),
                        request.uuidKey(1, 2), request.fetchSize());
        Page<AgentStore.AgentRow> page = Page.of(rows, request, row -> List.of(row.name(), row.id().toString()));
        return new AgentPage(new ArrayList<>(views.summaries(caller, page.items()))).nextCursor(page.nextCursor());
    }

    /** One agent the caller sees. */
    @Transactional(readOnly = true)
    public Agent get(UUID companyId, UUID agentId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        AgentCallers.Caller caller = callers.current(access);
        AgentStore.AgentRow row = store.findLive(companyId, agentId).orElseThrow(AgentService::notFound);
        if (!AgentCallers.access(caller, row.ownerPrincipalId(), store.shares(companyId, agentId)).sees()) {
            throw notFound();
        }
        return views.agent(caller, row);
    }

    /** The live agent with the handle ({@code @} and case ignored) that the caller sees. */
    @Transactional(readOnly = true)
    public Optional<UUID> idOfHandle(UUID companyId, String handle) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        AgentCallers.Caller caller = callers.current(access);
        String wanted = handle.strip();
        wanted = wanted.startsWith("@") ? wanted.substring(1) : wanted;
        return store.findLiveByHandle(companyId, wanted.toLowerCase(Locale.ROOT))
            .filter(row -> AgentCallers.access(caller, row.ownerPrincipalId(), store.shares(companyId, row.id()))
                .sees())
            .map(AgentStore.AgentRow::id);
    }

    // ---- create and change -----------------------------------------------------------------------------------------

    /** Creates an agent owned by the caller, as version 1, with what the request names of its ceiling and shares. */
    @Transactional
    public Agent create(UUID companyId, AgentCreateRequest input) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        AgentCallers.Caller caller = callers.current(access);
        UUID owner = caller.requirePrincipal();
        String name = requireName(input.getName());
        String description = optionalText(input.getDescription(), MAX_DESCRIPTION, "description");
        String instructions = instructions(input.getInstructions());
        List<RuntimeKind> runtimes = runtimes(input.getRuntimes());
        Integer turnTimeLimit = turnTimeLimit(input.getTurnTimeLimitMinutes());
        boolean memoryAllowed = !Boolean.FALSE.equals(input.getMemoryAllowed());
        if (store.nameTaken(companyId, name, null)) {
            throw duplicateName();
        }
        String handle = handle(companyId, input.getHandle(), name, null);
        Instant now = time.now();
        AgentStore.AgentRow row = new AgentStore.AgentRow(Ids.newId(), companyId, name, handle, description,
                instructions, runtimes, turnTimeLimit, memoryAllowed, owner, AgentStatus.ACTIVE, null, null, 1, null, now,
                now, caller.userId());
        try {
            store.insert(row);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicate(ex);
        }
        Actor actor = CurrentActor.actor();
        if (input.getSystems() != null) {
            writeSystems(row, AgentDefinition.EMPTY,
                    systemsInput(companyId, AgentDefinition.EMPTY, input.getSystems(), owner));
        }
        if (input.getRepositories() != null) {
            writeRepositories(row, repositoriesInput(companyId, store.definition(companyId, row.id()),
                    input.getRepositories()));
        }
        if (input.getHosts() != null) {
            Map<String, String> hosts = hostsInput(input.getHosts());
            for (Map.Entry<String, String> host : hosts.entrySet()) {
                store.insertHost(companyId, row.id(), new AgentStore.HostRow(Ids.newId(), host.getKey(),
                        host.getValue(), caller.userId(), null, now));
            }
        }
        AgentStore.Shares shares = new AgentStore.Shares(
                input.getShareGroupIds() != null ? groupsInput(companyId, input.getShareGroupIds()) : Set.of(),
                input.getSharePrincipalIds() != null ? membersInput(companyId, input.getSharePrincipalIds())
                        : Set.of());
        store.addShares(companyId, row.id(), shares.groupIds(), caller.userId(), now);
        store.addMemberShares(companyId, row.id(), shares.principalIds(), caller.userId(), now);
        AgentDefinition definition = store.definition(companyId, row.id());
        versions.first(companyId, row.id(), name, handle, runtimes, definition, now);
        auditLog.record(agentAudit(AuditAction.AGENT_CREATED, actor, row).build());
        if (input.getSystems() != null) {
            auditLog.record(toolsAudit(companyId, row.id(), actor, definition, 1, false).build());
        }
        if (!definition.repositories().isEmpty()) {
            auditLog.record(repositoriesAudit(companyId, row.id(), actor, definition, 1, false, null).build());
        }
        if (!definition.hosts().isEmpty()) {
            auditLog.record(hostsAudit(companyId, row.id(), actor, definition, 1, false, null).build());
        }
        events.publishEvent(new AgentEvents.AgentCreated(companyId, row.id()));
        if (!shares.isEmpty()) {
            sharesChanged(companyId, row.id(), actor, AgentStore.Shares.NONE, shares, null);
        }
        return views.agent(caller, row);
    }

    /**
     * Renames the agent, or changes its handle, description, instructions, runtimes, time limit of a turn or whether
     * memory is allowed; absent fields stay. A change of the instructions widens where the ceiling can act (ADR-0103,
     * {@link AgentVersions}).
     */
    @Transactional
    public Agent update(UUID companyId, UUID agentId, AgentUpdateRequest input) {
        Edit edit = requireEditable(companyId, agentId);
        AgentStore.AgentRow row = edit.row();
        String name = input.getName() != null ? requireName(input.getName()) : row.name();
        String description = input.getDescription() != null
                ? optionalText(input.getDescription(), MAX_DESCRIPTION, "description") : row.description();
        String instructions = input.getInstructions() != null ? instructions(input.getInstructions())
                : row.instructions();
        List<RuntimeKind> runtimes = input.getRuntimes() != null ? runtimes(input.getRuntimes()) : row.runtimes();
        Integer turnTimeLimit = input.getTurnTimeLimitMinutes() != null
                ? turnTimeLimit(input.getTurnTimeLimitMinutes()) : row.turnTimeLimitMinutes();
        boolean memoryAllowed = input.getMemoryAllowed() != null ? input.getMemoryAllowed() : row.memoryAllowed();
        String handle = row.handle();
        if (input.getHandle() != null) {
            handle = input.getHandle().isBlank() ? handle(companyId, null, name, agentId)
                    : handle(companyId, input.getHandle(), name, agentId);
        }
        List<String> changed = new ArrayList<>();
        if (!name.equals(row.name())) {
            changed.add("name");
        }
        if (!handle.equals(row.handle())) {
            changed.add("handle");
        }
        if (!Objects.equals(description, row.description())) {
            changed.add("description");
        }
        if (!instructions.equals(row.instructions())) {
            changed.add("instructions");
        }
        if (!runtimes.equals(row.runtimes())) {
            changed.add("runtimes");
        }
        if (!Objects.equals(turnTimeLimit, row.turnTimeLimitMinutes())) {
            changed.add("turnTimeLimitMinutes");
        }
        // Memory is not a widening (ADR-0063): it reaches nothing the conversation could not reach already.
        if (memoryAllowed != row.memoryAllowed()) {
            changed.add("memoryAllowed");
        }
        if (changed.isEmpty()) {
            return views.agent(edit.caller(), row);
        }
        if (changed.contains("name") && store.nameTaken(companyId, name, agentId)) {
            throw duplicateName();
        }
        AgentDefinition before = store.definition(companyId, agentId);
        Instant now = time.now();
        try {
            store.update(companyId, agentId, name, handle, description, instructions, runtimes, turnTimeLimit,
                    memoryAllowed, now);
        }
        catch (DataIntegrityViolationException ex) {
            throw duplicate(ex);
        }
        AgentVersions.Versioned versioned = versions.next(row, before, name, handle, runtimes, null, now);
        AgentStore.AgentRow after = store.findLive(companyId, agentId).orElseThrow();
        auditLog.record(agentAudit(AuditAction.AGENT_CHANGED, CurrentActor.actor(), after)
            .detail("changed", changed)
            .build());
        versions.announce(companyId, agentId, row.version(), versioned, edit.caller().principalId());
        return views.agent(edit.caller(), after);
    }

    /** Replaces the agent's systems and their tools as a whole. */
    @Transactional
    public Agent replaceSystems(UUID companyId, UUID agentId, List<AgentSystemInput> input) {
        Edit edit = requireEditable(companyId, agentId);
        AgentDefinition before = store.definition(companyId, agentId);
        Map<UUID, List<AgentDefinition.ToolEntry>> requested = systemsInput(companyId, before, input,
                edit.caller().principalId());
        writeSystems(edit.row(), before, requested);
        return changed(edit, before, AuditAction.AGENT_TOOLS_CHANGED, null);
    }

    /** Replaces the agent's repositories as a whole. */
    @Transactional
    public Agent replaceRepositories(UUID companyId, UUID agentId, List<AgentRepositoryInput> input) {
        Edit edit = requireEditable(companyId, agentId);
        AgentDefinition before = store.definition(companyId, agentId);
        writeRepositories(edit.row(), repositoriesInput(companyId, before, input));
        return changed(edit, before, AuditAction.AGENT_REPOSITORIES_CHANGED, null);
    }

    /**
     * Replaces the agent's network hosts as a whole. A changed reason alone is audited but makes no version: the
     * hosts themselves are the definition.
     */
    @Transactional
    public Agent replaceHosts(UUID companyId, UUID agentId, List<AgentHostInput> input) {
        Edit edit = requireEditable(companyId, agentId);
        AgentDefinition before = store.definition(companyId, agentId);
        Map<String, String> requested = hostsInput(input);
        Map<String, AgentStore.HostRow> existing = store.hosts(companyId, agentId)
            .stream()
            .collect(Collectors.toMap(AgentStore.HostRow::host, Function.identity()));
        Instant now = time.now();
        List<UUID> removed = existing.values()
            .stream()
            .filter(host -> !requested.containsKey(host.host()))
            .map(AgentStore.HostRow::id)
            .toList();
        boolean reasons = false;
        store.deleteHosts(companyId, removed);
        for (Map.Entry<String, String> host : requested.entrySet()) {
            AgentStore.HostRow kept = existing.get(host.getKey());
            if (kept == null) {
                store.insertHost(companyId, agentId, new AgentStore.HostRow(Ids.newId(), host.getKey(),
                        host.getValue(), edit.caller().userId(), null, now));
            }
            else if (!Objects.equals(kept.reason(), host.getValue())) {
                store.updateHostReason(companyId, kept.id(), host.getValue());
                reasons = true;
            }
        }
        if (store.definition(companyId, agentId).equals(before) && reasons) {
            auditLog.record(hostsAudit(companyId, agentId, CurrentActor.actor(), before, edit.row().version(), false,
                    null).build());
            return views.agent(edit.caller(), edit.row());
        }
        return changed(edit, before, AuditAction.AGENT_HOSTS_CHANGED, null);
    }

    /**
     * Replaces the groups whose members may use the agent and, unless {@code principalIds} is null, the members who may
     * use it on their own; not a change of the definition, so no version.
     */
    @Transactional
    public Agent replaceShares(UUID companyId, UUID agentId, List<UUID> groupIds, @Nullable List<UUID> principalIds) {
        Edit edit = requireEditable(companyId, agentId);
        AgentStore.Shares previous = store.shares(companyId, agentId);
        AgentStore.Shares requested = new AgentStore.Shares(groupsInput(companyId, groupIds),
                principalIds != null ? membersInput(companyId, principalIds) : previous.principalIds());
        if (requested.equals(previous)) {
            return views.agent(edit.caller(), edit.row());
        }
        Instant now = time.now();
        store.removeShares(companyId, agentId, minus(previous.groupIds(), requested.groupIds()));
        store.addShares(companyId, agentId, minus(requested.groupIds(), previous.groupIds()), edit.caller().userId(),
                now);
        store.removeMemberShares(companyId, agentId, minus(previous.principalIds(), requested.principalIds()));
        store.addMemberShares(companyId, agentId, minus(requested.principalIds(), previous.principalIds()),
                edit.caller().userId(), now);
        sharesChanged(companyId, agentId, CurrentActor.actor(), previous, requested, null);
        return views.agent(edit.caller(), edit.row());
    }

    /**
     * Replaces the agent's skills, each pinned to a version (the skills contract, §5.1). A change makes a version: a
     * skill added or a pin moved widens where the ceiling can act, as a change of the instructions does (ADR-0103,
     * {@link AgentVersions}); otherwise, and for a skill removed, it reaches everyone's next turn, announced.
     */
    @Transactional
    public Agent replaceSkills(UUID companyId, UUID agentId, List<AgentSkillInput> input) {
        // The skills' rows before the agent's (CONVENTIONS §10), as a skill's delete takes them.
        Edit edit = requireEditable(companyId, agentId, () -> skills.lockForPins(companyId, input));
        AgentStore.AgentRow row = edit.row();
        AgentDefinition before = store.definition(companyId, agentId);
        Instant now = time.now();
        AgentSkills.Change change = skills.replace(companyId, agentId, input, edit.caller().userId(), now);
        if (change.none()) {
            return views.agent(edit.caller(), row);
        }
        AgentVersions.Versioned versioned = versions.next(row, before, row.name(), row.handle(), row.runtimes(), null,
                now);
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.AGENT_SKILLS_CHANGED,
                CurrentActor.actor())
            .target(AgentVersions.TARGET, agentId)
            .detail("version", versioned.version());
        change.details().forEach(audit::detail);
        auditLog.record(audit.build());
        versions.announce(companyId, agentId, row.version(), versioned, edit.caller().principalId());
        return views.agent(edit.caller(), store.findLive(companyId, agentId).orElseThrow());
    }

    /**
     * The canonical zip of the version of a skill the agent pins, for whoever sees the agent (§5.4): how a person who
     * uses an agent reads what its skills tell it, without seeing the skill in the library.
     */
    @Transactional(readOnly = true)
    public SkillContent skillContent(UUID companyId, UUID agentId, UUID skillId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        AgentCallers.Caller caller = callers.current(access);
        AgentStore.AgentRow row = store.findLive(companyId, agentId).orElseThrow(AgentService::notFound);
        if (!AgentCallers.access(caller, row.ownerPrincipalId(), store.shares(companyId, agentId)).sees()) {
            throw notFound();
        }
        Integer version = skills.pinnedVersion(companyId, agentId, skillId);
        if (version == null) {
            throw ApiException.notFound("The agent has no such skill");
        }
        String name = library.find(companyId, skillId).map(SkillInfo::name).orElseThrow(AgentService::notFound);
        byte[] content = library.content(companyId, skillId, version).orElseThrow(AgentService::notFound);
        return new SkillContent(name + "-v" + version + ".zip", content);
    }

    /** A skill's canonical zip and the name its download takes. */
    record SkillContent(String fileName, byte[] content) {
    }

    /** Pauses the agent (the kill switch); with {@code stopRunning} its running turns are cancelled too. */
    @Transactional
    public Agent pause(UUID companyId, UUID agentId, boolean stopRunning) {
        Edit edit = requireEditable(companyId, agentId);
        if (edit.row().status() == AgentStatus.PAUSED) {
            return views.agent(edit.caller(), edit.row());
        }
        Instant now = time.now();
        store.pause(companyId, agentId, edit.caller().userId(), now);
        List<UUID> cancelled = new ArrayList<>();
        AgentRuns agentRuns = runs.getIfAvailable();
        if (stopRunning && agentRuns != null) {
            cancelled.addAll(agentRuns.cancelRunning(companyId, agentId));
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.AGENT_PAUSED, CurrentActor.actor())
            .target(AgentVersions.TARGET, agentId)
            .detail("name", edit.row().name())
            .detail("stopRunning", stopRunning)
            .detail("runsCancelled", cancelled.stream().map(UUID::toString).toList())
            .build());
        events.publishEvent(new AgentEvents.AgentPaused(companyId, agentId, stopRunning));
        return views.agent(edit.caller(), store.findLive(companyId, agentId).orElseThrow());
    }

    /**
     * Gives the agent a new owner, an active member of the company, who then edits it as its creator did (the package
     * 6 contract, open point 13; {@code MANAGE_AGENTS}). Audited as {@code agent.owner_changed}; its owner again
     * changes nothing.
     */
    @Transactional
    public Agent setOwner(UUID companyId, UUID agentId, UUID ownerPrincipalId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.MANAGE_AGENTS);
        AgentCallers.Caller caller = callers.current(access);
        AgentStore.AgentRow row = store.lockLive(companyId, agentId).orElseThrow(AgentService::notFound);
        PrincipalInfo owner = principals.find(companyId, ownerPrincipalId)
            .filter(PrincipalInfo::member)
            .filter(PrincipalInfo::active)
            .orElseThrow(() -> ApiException.validation("principalId must be an active member's principal"));
        if (owner.id().equals(row.ownerPrincipalId())) {
            return views.agent(caller, row);
        }
        store.setOwner(companyId, agentId, owner.id(), time.now());
        auditLog.record(AuditRecord.company(companyId, AuditAction.AGENT_OWNER_CHANGED, CurrentActor.actor())
            .target(AgentVersions.TARGET, agentId)
            .detail("formerOwnerPrincipalId", row.ownerPrincipalId() != null ? row.ownerPrincipalId().toString()
                    : null)
            .detail("ownerPrincipalId", owner.id().toString())
            .build());
        return views.agent(caller, store.findLive(companyId, agentId).orElseThrow());
    }

    /** Resumes a paused agent. */
    @Transactional
    public Agent resume(UUID companyId, UUID agentId) {
        Edit edit = requireEditable(companyId, agentId);
        if (edit.row().status() == AgentStatus.ACTIVE) {
            return views.agent(edit.caller(), edit.row());
        }
        store.resume(companyId, agentId, time.now());
        auditLog.record(AuditRecord.company(companyId, AuditAction.AGENT_RESUMED, CurrentActor.actor())
            .target(AgentVersions.TARGET, agentId)
            .detail("name", edit.row().name())
            .build());
        events.publishEvent(new AgentEvents.AgentResumed(companyId, agentId));
        return views.agent(edit.caller(), store.findLive(companyId, agentId).orElseThrow());
    }

    /**
     * Deletes the agent (soft), unless a run of it is queued or running: its shares and every person's settings go,
     * and its conversations end in this transaction ({@code AgentDeleted}).
     */
    @Transactional
    public void delete(UUID companyId, UUID agentId) {
        Edit edit = requireEditable(companyId, agentId);
        AgentRuns agentRuns = runs.getIfAvailable();
        if (agentRuns != null && agentRuns.hasActiveRuns(companyId, agentId)) {
            throw ApiException.conflict(ProblemCode.AGENT_HAS_ACTIVE_RUNS,
                    "A run of this agent is queued or running: cancel it, or wait until it ends");
        }
        store.softDelete(companyId, agentId, edit.caller().userId(), time.now());
        store.deleteShares(companyId, agentId);
        settings.deleteOfAgent(companyId, agentId);
        auditLog.record(AuditRecord.company(companyId, AuditAction.AGENT_DELETED, CurrentActor.actor())
            .target(AgentVersions.TARGET, agentId)
            .detail("name", edit.row().name())
            .detail("handle", edit.row().handle())
            .build());
        events.publishEvent(new AgentEvents.AgentDeleted(companyId, agentId));
    }

    // ---- additions for good, from approvals (Agents.addRepository, Agents.addHost) ------------------------------------

    /** A repository approved for good (contract §5.8); the caller authorised the approver. Answers the version. */
    int addRepository(UUID companyId, UUID agentId, UUID repositoryId, RepositoryAccess access, UUID decisionId,
            @Nullable UUID editorPrincipalId) {
        AgentStore.AgentRow row = store.lockLive(companyId, agentId)
            .orElseThrow(() -> new IllegalArgumentException("No live agent " + agentId));
        AgentDefinition before = store.definition(companyId, agentId);
        Optional<AgentDefinition.RepositoryEntry> listed = before.repository(repositoryId);
        if (listed.isPresent() && (listed.get().access() == RepositoryAccess.WRITE || access == RepositoryAccess.READ)) {
            return row.version();
        }
        RepositoryInfo repository = companyRepository(companyId, repositoryId)
            .orElseThrow(() -> new IllegalArgumentException("No repository " + repositoryId));
        if (before.system(repository.systemId()).isEmpty()) {
            store.insertSystem(companyId, agentId, repository.systemId());
        }
        List<AgentDefinition.RepositoryEntry> repositories = new ArrayList<>(before.repositories()
            .stream()
            .filter(entry -> !entry.repositoryId().equals(repositoryId))
            .toList());
        repositories.add(new AgentDefinition.RepositoryEntry(repositoryId, repository.systemId(), access));
        if (repositories.size() > MAX_REPOSITORIES) {
            throw ApiException.validation("An agent has at most " + MAX_REPOSITORIES + " repositories");
        }
        writeRepositories(row, repositories);
        return changedForGood(row, before, AuditAction.AGENT_REPOSITORIES_CHANGED, decisionId, editorPrincipalId);
    }

    /** A network host approved for good (contract §5.8); the caller authorised the approver. Answers the version. */
    int addHost(UUID companyId, UUID agentId, String host, @Nullable String reason, UUID decisionId,
            @Nullable UUID editorPrincipalId) {
        NetworkHosts.Parsed parsed = NetworkHosts.parse(host);
        if (!parsed.valid()) {
            throw new IllegalArgumentException(parsed.problem());
        }
        AgentStore.AgentRow row = store.lockLive(companyId, agentId)
            .orElseThrow(() -> new IllegalArgumentException("No live agent " + agentId));
        AgentDefinition before = store.definition(companyId, agentId);
        String normalized = Objects.requireNonNull(parsed.host());
        if (before.coversHost(normalized)) {
            return row.version();
        }
        if (before.hosts().size() >= MAX_HOSTS) {
            throw hostLimit();
        }
        Actor actor = CurrentActor.actor();
        store.insertHost(companyId, agentId, new AgentStore.HostRow(Ids.newId(), normalized,
                reason != null ? cut(reason.strip(), MAX_REASON) : null,
                actor.type() == ActorType.USER ? actor.id() : null, decisionId, time.now()));
        return changedForGood(row, before, AuditAction.AGENT_HOSTS_CHANGED, decisionId, editorPrincipalId);
    }

    // ---- rules -----------------------------------------------------------------------------------------------------

    /** A caller who may edit the agent, and its row, locked. */
    private record Edit(AgentCallers.Caller caller, AgentStore.AgentRow row) {
    }

    private Edit requireEditable(UUID companyId, UUID agentId) {
        return requireEditable(companyId, agentId, () -> {
        });
    }

    /** As {@link #requireEditable(UUID, UUID)}, with {@code first} locking rows that come before the agent's. */
    private Edit requireEditable(UUID companyId, UUID agentId, Runnable first) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AGENTS);
        AgentCallers.Caller caller = callers.current(access);
        first.run();
        AgentStore.AgentRow row = store.lockLive(companyId, agentId).orElseThrow(AgentService::notFound);
        AgentAccess rights = AgentCallers.access(caller, row.ownerPrincipalId(), store.shares(companyId, agentId));
        if (!rights.sees()) {
            throw notFound();
        }
        if (!rights.edits()) {
            throw ApiException.forbidden(ProblemCode.AGENT_NOT_EDITABLE,
                    "Only the agent's owner and the company's admins edit it");
        }
        return new Edit(caller, row);
    }

    /** After a change of the ceiling: a version when it changed, audited with the whole list, and announced. */
    private Agent changed(Edit edit, AgentDefinition before, AuditAction action, @Nullable UUID decisionId) {
        AgentStore.AgentRow row = edit.row();
        if (store.definition(row.companyId(), row.id()).equals(before)) {
            return views.agent(edit.caller(), row);
        }
        changedForGood(row, before, action, decisionId, edit.caller().principalId());
        return views.agent(edit.caller(), store.findLive(row.companyId(), row.id()).orElseThrow());
    }

    private int changedForGood(AgentStore.AgentRow row, AgentDefinition before, AuditAction action,
            @Nullable UUID decisionId, @Nullable UUID editorPrincipalId) {
        UUID companyId = row.companyId();
        AgentVersions.Versioned versioned = versions.next(row, before, row.name(), row.handle(), row.runtimes(),
                decisionId, time.now());
        AgentDefinition after = store.definition(companyId, row.id());
        Actor actor = CurrentActor.actor();
        AuditRecord.Builder audit = switch (action) {
            case AGENT_TOOLS_CHANGED -> toolsAudit(companyId, row.id(), actor, after, versioned.version(),
                    versioned.widens());
            case AGENT_REPOSITORIES_CHANGED -> repositoriesAudit(companyId, row.id(), actor, after,
                    versioned.version(), versioned.widens(), decisionId);
            case AGENT_HOSTS_CHANGED -> hostsAudit(companyId, row.id(), actor, after, versioned.version(),
                    versioned.widens(), decisionId);
            default -> throw new IllegalArgumentException("Not a change of the ceiling: " + action);
        };
        if (action == AuditAction.AGENT_TOOLS_CHANGED && decisionId != null) {
            audit.detail("decisionId", decisionId.toString());
        }
        auditLog.record(audit.build());
        versions.announce(companyId, row.id(), row.version(), versioned, editorPrincipalId);
        return versioned.version();
    }

    /**
     * The systems a request names, with their tools: each a company system of a kind with tools or repositories; its
     * tools as named (each one its system serves and has switched on, or a row the agent keeps unchanged), or, when
     * none are named, the agent's rows of it, or for a system new to the agent every tool its system serves: reads
     * {@code ALLOW}, writes and unconfirmed tools {@code ASK}. A kept row keeps the effect its mode was chosen for.
     */
    private Map<UUID, List<AgentDefinition.ToolEntry>> systemsInput(UUID companyId, AgentDefinition before,
            List<AgentSystemInput> input, @Nullable UUID callerPrincipalId) {
        if (input.size() > MAX_SYSTEMS) {
            throw ApiException.validation("An agent lists at most " + MAX_SYSTEMS + " systems");
        }
        Map<UUID, List<AgentDefinition.ToolEntry>> requested = new LinkedHashMap<>();
        for (AgentSystemInput entry : input) {
            UUID systemId = Objects.requireNonNull(entry.getSystemId());
            if (requested.containsKey(systemId)) {
                throw ApiException.validation("The system " + systemId + " is named twice");
            }
            // Another member's personal server is unknown to the caller, as everywhere (the package 1 contract, I6).
            SystemInfo system = systems.find(companyId, systemId)
                .filter(info -> !info.personal() || callerPrincipalId != null && info.ownedBy(callerPrincipalId))
                .orElseThrow(() -> ApiException.validation("The system " + systemId
                        + " is not a system of this company"));
            if (system.personal()) {
                throw ApiException.badRequest(ProblemCode.PERSONAL_SYSTEM,
                        "An agent lists only company systems: add your own MCP server in your settings of the agent");
            }
            if (!KINDS.contains(system.kind())) {
                throw ApiException.badRequest(ProblemCode.SYSTEM_KIND_INVALID,
                        "An agent lists only systems with tools or repositories");
            }
            Map<String, ToolInfo> served = systems.tools(companyId, systemId)
                .stream()
                .collect(Collectors.toMap(ToolInfo::name, Function.identity(), (a, b) -> a));
            Optional<AgentDefinition.SystemEntry> existing = before.system(systemId);
            List<AgentDefinition.ToolEntry> tools = new ArrayList<>();
            if (entry.getTools() == null) {
                if (existing.isPresent()) {
                    tools.addAll(existing.get().tools());
                }
                else {
                    served.values()
                        .stream()
                        .filter(ToolInfo::enabled)
                        .forEach(tool -> tools.add(new AgentDefinition.ToolEntry(tool.name(),
                                tool.effect() == ToolEffect.READ ? ToolMode.ALLOW : ToolMode.ASK, tool.effect())));
                }
            }
            else {
                if (entry.getTools().size() > MAX_TOOLS_PER_SYSTEM) {
                    throw ApiException.validation("A system lists at most " + MAX_TOOLS_PER_SYSTEM + " tools");
                }
                Set<String> names = new HashSet<>();
                for (AgentToolInput tool : entry.getTools()) {
                    if (!names.add(tool.getName())) {
                        throw ApiException.validation("The tool " + tool.getName() + " is named twice");
                    }
                    ToolMode mode = ToolMode.valueOf(tool.getMode().getValue());
                    ToolEffect sent = tool.getChosenForEffect() != null
                            ? ToolEffect.valueOf(tool.getChosenForEffect().getValue()) : null;
                    AgentDefinition.ToolEntry was = existing.flatMap(e -> e.tool(tool.getName())).orElse(null);
                    ToolInfo info = served.get(tool.getName());
                    boolean offered = info != null && info.enabled();
                    if (was != null && was.mode() == mode && (sent == null || !offered)) {
                        // Kept as it was chosen: saving another part of the agent never confirms it anew (I19).
                        tools.add(was);
                    }
                    else if (offered) {
                        tools.add(new AgentDefinition.ToolEntry(tool.getName(), mode,
                                AgentDefinition.ToolEntry.chosenFor(sent, info.effect())));
                    }
                    else {
                        throw unknownTool(system.id(), tool.getName(), "The system " + system.name()
                                + " does not serve the tool " + tool.getName() + ", or it is switched off");
                    }
                }
            }
            requested.put(systemId, tools);
        }
        return requested;
    }

    /** Writes the systems and their tools; a removed system takes its tools and repositories, and people's switches. */
    private void writeSystems(AgentStore.AgentRow row, AgentDefinition before,
            Map<UUID, List<AgentDefinition.ToolEntry>> requested) {
        UUID companyId = row.companyId();
        List<UUID> removed = before.systems()
            .stream()
            .map(AgentDefinition.SystemEntry::systemId)
            .filter(id -> !requested.containsKey(id))
            .toList();
        store.deleteSystems(companyId, row.id(), removed);
        settings.forgetSystems(companyId, row.id(), removed);
        store.deleteTools(companyId, row.id());
        for (Map.Entry<UUID, List<AgentDefinition.ToolEntry>> system : requested.entrySet()) {
            if (before.system(system.getKey()).isEmpty()) {
                store.insertSystem(companyId, row.id(), system.getKey());
            }
            for (AgentDefinition.ToolEntry tool : system.getValue()) {
                store.insertTool(companyId, row.id(), system.getKey(), tool);
            }
        }
    }

    /** The repositories a request names: each a repository of a code host the agent lists, at most 100. */
    private List<AgentDefinition.RepositoryEntry> repositoriesInput(UUID companyId, AgentDefinition definition,
            List<AgentRepositoryInput> input) {
        if (input.size() > MAX_REPOSITORIES) {
            throw ApiException.validation("An agent has at most " + MAX_REPOSITORIES + " repositories");
        }
        Map<UUID, RepositoryInfo> known = new HashMap<>();
        Map<UUID, SystemInfo> listed = systems.findAll(companyId,
                definition.systems().stream().map(AgentDefinition.SystemEntry::systemId).toList());
        for (SystemInfo system : listed.values()) {
            if (system.kind().hasRepositories()) {
                systems.repositories(companyId, system.id()).forEach(repository -> known.put(repository.id(), repository));
            }
        }
        List<AgentDefinition.RepositoryEntry> repositories = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (AgentRepositoryInput entry : input) {
            UUID repositoryId = Objects.requireNonNull(entry.getRepositoryId());
            if (!seen.add(repositoryId)) {
                throw ApiException.validation("The repository " + repositoryId + " is named twice");
            }
            RepositoryInfo repository = known.get(repositoryId);
            if (repository == null) {
                throw ApiException.badRequest(ProblemCode.SYSTEM_NOT_ON_AGENT, "The repository " + repositoryId
                        + " is not a repository of a code host the agent lists: add its system first",
                        Map.of("details", Map.of("repositoryId", repositoryId.toString())));
            }
            repositories.add(new AgentDefinition.RepositoryEntry(repositoryId, repository.systemId(),
                    RepositoryAccess.valueOf(entry.getAccess().getValue())));
        }
        return repositories;
    }

    private void writeRepositories(AgentStore.AgentRow row, List<AgentDefinition.RepositoryEntry> repositories) {
        store.deleteRepositories(row.companyId(), row.id());
        repositories.forEach(repository -> store.insertRepository(row.companyId(), row.id(), repository));
    }

    /** The hosts a request names, normalized, each once with its reason (the first one named), at most 100. */
    private static Map<String, String> hostsInput(List<AgentHostInput> input) {
        Map<String, String> hosts = new LinkedHashMap<>();
        for (AgentHostInput entry : input) {
            NetworkHosts.Parsed parsed = NetworkHosts.parse(entry.getHost());
            if (!parsed.valid()) {
                throw ApiException.validation(entry.getHost() + ": " + parsed.problem());
            }
            String reason = entry.getReason() != null && !entry.getReason().isBlank() ? entry.getReason().strip()
                    : null;
            if (reason != null && reason.length() > MAX_REASON) {
                throw ApiException.validation("A host's reason has at most " + MAX_REASON + " characters");
            }
            hosts.putIfAbsent(Objects.requireNonNull(parsed.host()), reason);
        }
        if (hosts.size() > MAX_HOSTS) {
            throw hostLimit();
        }
        return hosts;
    }

    private Set<UUID> groupsInput(UUID companyId, List<UUID> groupIds) {
        Set<UUID> requested = new LinkedHashSet<>(groupIds);
        if (requested.size() > MAX_SHARES) {
            throw ApiException.validation("An agent is shared with at most " + MAX_SHARES + " groups");
        }
        Map<UUID, String> known = groups.groupNames(companyId, requested);
        for (UUID groupId : requested) {
            if (!known.containsKey(groupId)) {
                throw ApiException.validation("The group " + groupId + " is not a group of this company");
            }
        }
        return requested;
    }

    /**
     * The members a request names by principal: each a member of the company who has not left (a suspended one
     * included: suspension stops what they do, not what they were given). A service identity uses no agent.
     */
    private Set<UUID> membersInput(UUID companyId, List<UUID> principalIds) {
        Set<UUID> requested = new LinkedHashSet<>(principalIds);
        if (requested.size() > MAX_MEMBER_SHARES) {
            throw ApiException.validation("An agent is shared with at most " + MAX_MEMBER_SHARES + " members");
        }
        Map<UUID, PrincipalInfo> known = principals.findAll(companyId, requested);
        for (UUID principalId : requested) {
            PrincipalInfo principal = known.get(principalId);
            if (principal == null || !principal.member() || principal.status() == PrincipalStatus.LEFT) {
                throw ApiException.validation("The principal " + principalId + " is not a member of this company");
            }
        }
        return requested;
    }

    /**
     * Audits and announces a change of whom the agent is shared with: the groups ({@code groupIds}, {@code added},
     * {@code removed}) and the members ({@code principalIds}, {@code principalsAdded}, {@code principalsRemoved}).
     */
    void sharesChanged(UUID companyId, UUID agentId, Actor actor, AgentStore.Shares before, AgentStore.Shares after,
            @Nullable String cause) {
        Set<UUID> added = minus(after.groupIds(), before.groupIds());
        Set<UUID> removed = minus(before.groupIds(), after.groupIds());
        Set<UUID> principalsAdded = minus(after.principalIds(), before.principalIds());
        Set<UUID> principalsRemoved = minus(before.principalIds(), after.principalIds());
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.AGENT_SHARES_CHANGED, actor)
            .target(AgentVersions.TARGET, agentId)
            .detail("groupIds", sorted(after.groupIds()))
            .detail("added", sorted(added))
            .detail("removed", sorted(removed))
            .detail("principalIds", sorted(after.principalIds()))
            .detail("principalsAdded", sorted(principalsAdded))
            .detail("principalsRemoved", sorted(principalsRemoved));
        if (cause != null) {
            audit.detail("cause", cause);
        }
        auditLog.record(audit.build());
        events.publishEvent(new AgentEvents.AgentShared(companyId, agentId, added, removed, principalsAdded,
                principalsRemoved));
    }

    private static Set<UUID> minus(Set<UUID> from, Set<UUID> taken) {
        return from.stream().filter(id -> !taken.contains(id)).collect(Collectors.toSet());
    }

    private Optional<RepositoryInfo> companyRepository(UUID companyId, UUID repositoryId) {
        for (SystemInfo system : systems.visibleTo(companyId, null)) {
            if (system.kind().hasRepositories()) {
                for (RepositoryInfo repository : systems.repositories(companyId, system.id())) {
                    if (repository.id().equals(repositoryId)) {
                        return Optional.of(repository);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String handle(UUID companyId, @Nullable String requested, String name, @Nullable UUID exceptId) {
        if (requested != null && !requested.isBlank()) {
            String handle = Handles.requested(requested);
            if (store.handlesLike(companyId, handle, exceptId).contains(handle)) {
                throw duplicateHandle();
            }
            return handle;
        }
        String base = Handles.fromName(name);
        return Handles.unique(base, store.handlesLike(companyId, base, exceptId));
    }

    // ---- audit -----------------------------------------------------------------------------------------------------

    private static AuditRecord.Builder agentAudit(AuditAction action, Actor actor, AgentStore.AgentRow row) {
        return AuditRecord.company(row.companyId(), action, actor)
            .target(AgentVersions.TARGET, row.id())
            .detail("name", row.name())
            .detail("handle", row.handle())
            .detail("runtimes", row.runtimes().stream().map(Enum::name).toList())
            .detail("turnTimeLimitMinutes", row.turnTimeLimitMinutes())
            .detail("memoryAllowed", row.memoryAllowed())
            .detail("ownerPrincipalId", row.ownerPrincipalId() != null ? row.ownerPrincipalId().toString() : null)
            .detail("version", row.version());
    }

    private static AuditRecord.Builder toolsAudit(UUID companyId, UUID agentId, Actor actor,
            AgentDefinition definition, int version, boolean widens) {
        return AuditRecord.company(companyId, AuditAction.AGENT_TOOLS_CHANGED, actor)
            .target(AgentVersions.TARGET, agentId)
            .detail("systems", definition.systems()
                .stream()
                .map(system -> Map.of("systemId", system.systemId().toString(), "tools", system.tools()
                    .stream()
                    .map(tool -> Map.of("tool", tool.name(), "mode", tool.mode().name()))
                    .toList()))
                .toList())
            .detail("version", version)
            .detail("widens", widens);
    }

    private static AuditRecord.Builder repositoriesAudit(UUID companyId, UUID agentId, Actor actor,
            AgentDefinition definition, int version, boolean widens, @Nullable UUID decisionId) {
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.AGENT_REPOSITORIES_CHANGED, actor)
            .target(AgentVersions.TARGET, agentId)
            .detail("repositories", definition.repositories()
                .stream()
                .map(repository -> Map.of("repositoryId", repository.repositoryId().toString(), "access",
                        repository.access().name()))
                .toList())
            .detail("version", version)
            .detail("widens", widens);
        if (decisionId != null) {
            audit.detail("decisionId", decisionId.toString());
        }
        return audit;
    }

    private static AuditRecord.Builder hostsAudit(UUID companyId, UUID agentId, Actor actor,
            AgentDefinition definition, int version, boolean widens, @Nullable UUID decisionId) {
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.AGENT_HOSTS_CHANGED, actor)
            .target(AgentVersions.TARGET, agentId)
            .detail("hosts", definition.hosts())
            .detail("version", version)
            .detail("widens", widens);
        if (decisionId != null) {
            audit.detail("decisionId", decisionId.toString());
        }
        return audit;
    }

    // ---- input -----------------------------------------------------------------------------------------------------

    private static String requireName(String requested) {
        String name = requested.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw ApiException.validation("name must be 1 to " + MAX_NAME + " characters");
        }
        return name;
    }

    private static @Nullable String optionalText(@Nullable String requested, int max, String field) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String text = requested.strip();
        if (text.length() > max) {
            throw ApiException.validation(field + " must be at most " + max + " characters");
        }
        return text;
    }

    private static String instructions(@Nullable String requested) {
        if (requested == null) {
            return "";
        }
        if (requested.length() > MAX_INSTRUCTIONS) {
            throw ApiException.validation("instructions must be at most " + MAX_INSTRUCTIONS + " characters");
        }
        return requested;
    }

    /** The time limit of a turn a request names: 0 (or none) for the deployment's, else 5 to 1440 minutes. */
    private static @Nullable Integer turnTimeLimit(@Nullable Integer requested) {
        if (requested == null || requested == 0) {
            return null;
        }
        if (requested < MIN_TURN_TIME_LIMIT || requested > MAX_TURN_TIME_LIMIT) {
            throw ApiException.invalid("turnTimeLimitMinutes must be 0 (the deployment's default) or "
                    + MIN_TURN_TIME_LIMIT + " to " + MAX_TURN_TIME_LIMIT + " minutes", "turnTimeLimitMinutes");
        }
        return requested;
    }

    private static List<RuntimeKind> runtimes(List<AgentRuntime> requested) {
        if (requested.isEmpty()) {
            throw ApiException.validation("An agent is tested on at least one runtime");
        }
        List<RuntimeKind> runtimes = new ArrayList<>();
        for (AgentRuntime runtime : requested) {
            RuntimeKind kind = RuntimeKind.valueOf(runtime.getValue());
            if (runtimes.contains(kind)) {
                throw ApiException.validation("The runtime " + kind + " is named twice");
            }
            runtimes.add(kind);
        }
        return runtimes;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static List<String> sorted(Set<UUID> ids) {
        return ids.stream().sorted().map(UUID::toString).toList();
    }

    static ApiException notFound() {
        return ApiException.notFound("Agent not found");
    }

    /** {@code UNKNOWN_TOOL}, naming the system and the tool in its details for the editor to point at. */
    static ApiException unknownTool(UUID systemId, String tool, String detail) {
        return ApiException.badRequest(ProblemCode.UNKNOWN_TOOL, detail, Map.of("details",
                Map.of("systemId", systemId.toString(), "tool", tool)));
    }

    private static ApiException hostLimit() {
        return ApiException.conflict(ProblemCode.NETWORK_HOST_LIMIT, "An agent may reach at most " + MAX_HOSTS
                + " network hosts");
    }

    private static ApiException duplicateName() {
        return ApiException.conflict(ProblemCode.DUPLICATE_AGENT_NAME, "An agent with this name already exists");
    }

    private static ApiException duplicateHandle() {
        return ApiException.conflict(ProblemCode.DUPLICATE_AGENT_HANDLE, "An agent with this handle already exists");
    }

    /** A unique index refused the row: another agent took the name or handle at the same moment. */
    private static RuntimeException duplicate(DataIntegrityViolationException ex) {
        if (Constraints.violated(ex, NAME_INDEX)) {
            return duplicateName();
        }
        if (Constraints.violated(ex, HANDLE_INDEX)) {
            return duplicateHandle();
        }
        return ex;
    }
}
