package com.flowtivo.tinfirm.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.skill.SkillVersionInfo;
import com.flowtivo.tinfirm.system.ToolEffect;

/**
 * A person's settings of an agent in effect (the package 3 contract, §5.2 and §11, {@code Settings.effective}): the
 * stored row, or the defaults (the agent's first runtime, no model, {@code DEFAULT} effort, the budget, nothing added
 * or switched off), over the agent their turns use ({@link EffectiveAgent}: the current version narrowed by the one
 * they accepted). Steps 3 and 4 of the tools of a run (contract §5.4) are {@link #modeOf}: the agent's mode, or the
 * settings' rows on the person's own MCP server, then what the settings switch off or turn to {@code ASK}.
 *
 * @param stored               whether a row is stored; false: these are the defaults
 * @param runtime              the runtime in effect: the chosen one while the agent lists it, else the agent's first
 * @param chosenRuntime        the runtime the person chose; null for the agent's first
 * @param model                null: the runtime's default
 * @param instructions         added after the agent's; null for none
 * @param defaultRepositoryIds the repositories a new conversation's workspace starts with, among those the agent's turns
 *                             use; empty means all of them
 * @param defaultProjects      tracker projects the prompt names as the person's usual scope
 * @param systemsOff           systems of the agent switched off for the person
 * @param toolOverrides        tools of the agent's systems switched off or turned to {@code ASK}
 * @param ownServers           the person's own MCP servers, each with the modes of its tools
 * @param memoryOn             whether the owner turned the agent's memory on for themselves (ADR-0063, opt-in)
 * @param skillsOff            skills of the agent switched off (ADR-0064)
 * @param skills               the skills the turns get: the agent's, each at the version it pins, less those switched
 *                             off, by name
 * @param autoRetry            whether the owner chose that a turn failed for a reason that may pass is retried by
 *                             itself (ADR-0073, opt-in)
 */
public record EffectiveSettings(UUID agentId, SettingsOwner owner, boolean stored, EffectiveAgent agent,
        RuntimeKind runtime, @Nullable RuntimeKind chosenRuntime, @Nullable String model, EffortLevel effort,
        ModelAccessSetting modelAccess, @Nullable String instructions, List<UUID> defaultRepositoryIds,
        List<Project> defaultProjects, Set<UUID> systemsOff, List<ToolOverride> toolOverrides,
        List<OwnServer> ownServers, boolean memoryOn, Set<UUID> skillsOff, List<SkillVersionInfo> skills,
        boolean autoRetry) {

    public EffectiveSettings {
        defaultRepositoryIds = List.copyOf(defaultRepositoryIds);
        defaultProjects = List.copyOf(defaultProjects);
        systemsOff = Set.copyOf(systemsOff);
        toolOverrides = List.copyOf(toolOverrides);
        ownServers = List.copyOf(ownServers);
        skillsOff = Set.copyOf(skillsOff);
        skills = List.copyOf(skills);
    }

    /** Settings without memory, skills or automatic retries. */
    public EffectiveSettings(UUID agentId, SettingsOwner owner, boolean stored, EffectiveAgent agent,
            RuntimeKind runtime, @Nullable RuntimeKind chosenRuntime, @Nullable String model, EffortLevel effort,
            ModelAccessSetting modelAccess, @Nullable String instructions, List<UUID> defaultRepositoryIds,
            List<Project> defaultProjects, Set<UUID> systemsOff, List<ToolOverride> toolOverrides,
            List<OwnServer> ownServers) {
        this(agentId, owner, stored, agent, runtime, chosenRuntime, model, effort, modelAccess, instructions,
                defaultRepositoryIds, defaultProjects, systemsOff, toolOverrides, ownServers, false, Set.of(),
                List.of(), false);
    }

    /**
     * Whether the agent keeps notes for these settings' owner (ADR-0063): the agent allows memory and the owner, a
     * person or a trigger's author, turned it on.
     */
    public boolean memory() {
        return agent.agent().memoryAllowed() && memoryOn;
    }

    /** A tracker project of a system the agent lists, by its key there. */
    public record Project(UUID systemId, String key) {
    }

    /** What settings may do to a tool of the agent: switch it off, or turn its {@code ALLOW} into {@code ASK}. */
    public enum OverrideMode {
        ASK, OFF
    }

    /** A tool of one of the agent's systems, switched off or turned to {@code ASK}. */
    public record ToolOverride(UUID systemId, String tool, OverrideMode mode) {
    }

    /** One of the person's own MCP servers, added to the agent for them, with each tool's mode (open point 6). */
    public record OwnServer(UUID systemId, List<AgentDefinition.ToolEntry> tools) {

        public OwnServer {
            tools = List.copyOf(tools);
        }

        public Optional<AgentDefinition.ToolEntry> tool(String name) {
            return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
        }
    }

    /** What steps 3 and 4 of the tools of a run (contract §5.4) answer for one tool. */
    public enum ToolDecision {
        /** The call runs. */
        ALLOW,
        /** Each call is an approval. */
        ASK,
        /** Not one of the agent's tools (nor of the person's own server): {@code TOOL_DENIED}. */
        NOT_ON_AGENT,
        /** Switched off in the person's settings, the tool or its system: {@code TOOL_DENIED}. */
        SWITCHED_OFF
    }

    /**
     * The mode of a tool for this person (contract §5.4, steps 3 and 4) while the tool's effect is {@code effect} (step
     * 2: an MCP tool's confirmed effect, {@code UNKNOWN} counting as {@code WRITE}): on the person's own MCP server
     * their settings' row; else the agent's row in the version they accepted, then the settings' {@code OFF} or
     * {@code ASK}. An {@code ALLOW} chosen for a read is {@code ASK} while the tool writes (I19).
     */
    public ToolDecision modeOf(UUID systemId, String tool, ToolEffect effect) {
        Optional<OwnServer> own = ownServer(systemId);
        if (own.isPresent()) {
            return own.get().tool(tool).map(entry -> decision(entry.modeFor(effect))).orElse(ToolDecision.NOT_ON_AGENT);
        }
        Optional<AgentDefinition.ToolEntry> entry = agent.definition().tool(systemId, tool);
        if (entry.isEmpty()) {
            return ToolDecision.NOT_ON_AGENT;
        }
        if (systemsOff.contains(systemId)) {
            return ToolDecision.SWITCHED_OFF;
        }
        Optional<ToolOverride> override = override(systemId, tool);
        if (override.isPresent()) {
            return override.get().mode() == OverrideMode.OFF ? ToolDecision.SWITCHED_OFF : ToolDecision.ASK;
        }
        return decision(entry.get().modeFor(effect));
    }

    /** The systems this person's turns may use: the agent's (as accepted) that are not switched off, then their own. */
    public List<UUID> systems() {
        List<UUID> systems = new ArrayList<>();
        for (AgentDefinition.SystemEntry system : agent.definition().systems()) {
            if (!systemsOff.contains(system.systemId())) {
                systems.add(system.systemId());
            }
        }
        ownServers.forEach(server -> systems.add(server.systemId()));
        return systems;
    }

    /**
     * The tools of a system this person's turns may list, with the mode settings leave them (I19 is applied per call by
     * {@link #modeOf} with the tool's effect then): the agent's, minus those switched off, {@code ASK} where the
     * settings say so; the person's own server's as they set them. Empty for a system switched off or not theirs.
     */
    public List<AgentDefinition.ToolEntry> tools(UUID systemId) {
        Optional<OwnServer> own = ownServer(systemId);
        if (own.isPresent()) {
            return own.get().tools();
        }
        if (systemsOff.contains(systemId)) {
            return List.of();
        }
        List<AgentDefinition.ToolEntry> tools = new ArrayList<>();
        for (AgentDefinition.ToolEntry tool : agent.definition().system(systemId)
            .map(AgentDefinition.SystemEntry::tools)
            .orElse(List.of())) {
            Optional<ToolOverride> override = override(systemId, tool.name());
            if (override.isEmpty()) {
                tools.add(tool);
            }
            else if (override.get().mode() == OverrideMode.ASK) {
                tools.add(new AgentDefinition.ToolEntry(tool.name(), ToolMode.ASK, tool.chosenForEffect()));
            }
        }
        return tools;
    }

    /** The agent's repositories (as accepted) of the systems not switched off. */
    public List<AgentDefinition.RepositoryEntry> repositories() {
        return agent.definition().repositories().stream()
            .filter(repository -> !systemsOff.contains(repository.systemId()))
            .toList();
    }

    /**
     * The repositories a new conversation's workspace starts with when it names none (contract §5.6): the defaults
     * among {@link #repositories()}, or all of them when the person chose none (or none of theirs is left).
     */
    public List<AgentDefinition.RepositoryEntry> startingRepositories() {
        List<AgentDefinition.RepositoryEntry> all = repositories();
        List<AgentDefinition.RepositoryEntry> chosen = all.stream()
            .filter(repository -> defaultRepositoryIds.contains(repository.repositoryId()))
            .toList();
        return chosen.isEmpty() ? all : chosen;
    }

    /** The agent's network hosts (as accepted). */
    public List<String> hosts() {
        return agent.definition().hosts();
    }

    public Optional<OwnServer> ownServer(UUID systemId) {
        return ownServers.stream().filter(server -> server.systemId().equals(systemId)).findFirst();
    }

    private Optional<ToolOverride> override(UUID systemId, String tool) {
        return toolOverrides.stream()
            .filter(override -> override.systemId().equals(systemId) && override.tool().equals(tool))
            .findFirst();
    }

    private static ToolDecision decision(ToolMode mode) {
        return Objects.requireNonNull(mode) == ToolMode.ALLOW ? ToolDecision.ALLOW : ToolDecision.ASK;
    }
}
