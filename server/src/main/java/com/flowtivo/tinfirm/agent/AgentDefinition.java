package com.flowtivo.tinfirm.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.ToolEffect;

/**
 * An agent's ceiling as of one version (the package 3 contract, §3.2 to §3.5 and §11): the systems it may use with
 * each tool's mode, its repositories with their access, and the network hosts its runs may reach. The two rules of
 * versions are functions of it: {@link #narrowedBy} (the agent a person's turns use: the current version narrowed by
 * the one they accepted) and {@link #wideningOver} (what a version adds over another: whether it widens, and what the
 * app shows before a person accepts it). A change of the instructions or of the skills ({@link AgentContent}) widens
 * too when the ceiling after it can act ({@link #canAct}: the security review of 2026-10-27, D1; the owner's decision
 * Q4).
 * <p>
 * The order of what widens, per entry: a system or a repository is there or not; a repository is {@code READ} below
 * {@code WRITE}; a tool is absent, then {@code ASK}, then {@code ALLOW} chosen for a read (which counts as {@code ASK}
 * once the tool writes, I19), then {@code ALLOW} chosen for a write; a host is covered by another host or not
 * ({@link NetworkHosts#covers}).
 *
 * @param systems      by system, each with its tools by name
 * @param repositories by repository
 * @param hosts        normalized ({@link NetworkHosts}), sorted
 */
public record AgentDefinition(List<SystemEntry> systems, List<RepositoryEntry> repositories, List<String> hosts) {

    public static final AgentDefinition EMPTY = new AgentDefinition(List.of(), List.of(), List.of());

    public AgentDefinition {
        systems = systems.stream().sorted(Comparator.comparing(SystemEntry::systemId)).toList();
        repositories = repositories.stream().sorted(Comparator.comparing(RepositoryEntry::repositoryId)).toList();
        hosts = hosts.stream().distinct().sorted().toList();
    }

    /**
     * A system of the agent and its tools.
     *
     * @param tools by name; a system without tools serves the agent its repositories only
     */
    public record SystemEntry(UUID systemId, List<ToolEntry> tools) {

        public SystemEntry {
            tools = tools.stream().sorted(Comparator.comparing(ToolEntry::name)).toList();
        }

        public Optional<ToolEntry> tool(String name) {
            return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
        }
    }

    /**
     * A tool of a system of the agent.
     *
     * @param chosenForEffect the tool's effect when the mode was chosen, {@code READ} or {@code WRITE}
     */
    public record ToolEntry(String name, ToolMode mode, ToolEffect chosenForEffect) {

        public ToolEntry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(mode, "mode");
            chosenForEffect = chosenForEffect.effective();
        }

        /**
         * The mode a call gets while the tool's effect is {@code effect} (I19): an {@code ALLOW} chosen for a read is
         * {@code ASK} for a tool that writes, an unconfirmed one included.
         */
        public ToolMode modeFor(ToolEffect effect) {
            if (mode == ToolMode.ALLOW && chosenForEffect == ToolEffect.READ && effect.effective() == ToolEffect.WRITE) {
                return ToolMode.ASK;
            }
            return mode;
        }

        /**
         * The effect a mode is chosen for (I19) when the editor sent the effect it showed: the narrower of that and the
         * tool's effect now, so that a choice made for a read never covers a write; the effect now when it sent none.
         *
         * @param shown the effect the editor showed when the mode was chosen; null when it sent none
         * @param now   the tool's effect now
         */
        public static ToolEffect chosenFor(@Nullable ToolEffect shown, ToolEffect now) {
            if (shown == null) {
                return now.effective();
            }
            return shown.effective() == ToolEffect.READ || now.effective() == ToolEffect.READ ? ToolEffect.READ
                    : ToolEffect.WRITE;
        }

        /** 0: {@code ASK}; 1: {@code ALLOW} chosen for a read; 2: {@code ALLOW} chosen for a write. */
        int rank() {
            return mode == ToolMode.ASK ? 0 : chosenForEffect == ToolEffect.READ ? 1 : 2;
        }

        ToolEntry withRank(int rank) {
            return switch (rank) {
                case 0 -> new ToolEntry(name, ToolMode.ASK, chosenForEffect);
                case 1 -> new ToolEntry(name, ToolMode.ALLOW, ToolEffect.READ);
                default -> new ToolEntry(name, ToolMode.ALLOW, ToolEffect.WRITE);
            };
        }
    }

    /** A repository of a code host the agent lists, with its access. */
    public record RepositoryEntry(UUID repositoryId, UUID systemId, RepositoryAccess access) {
    }

    public Optional<SystemEntry> system(UUID systemId) {
        return systems.stream().filter(system -> system.systemId().equals(systemId)).findFirst();
    }

    public Optional<ToolEntry> tool(UUID systemId, String name) {
        return system(systemId).flatMap(system -> system.tool(name));
    }

    public Optional<RepositoryEntry> repository(UUID repositoryId) {
        return repositories.stream().filter(entry -> entry.repositoryId().equals(repositoryId)).findFirst();
    }

    /** Whether one of the hosts covers {@code host} ({@link NetworkHosts#covers}). */
    public boolean coversHost(String host) {
        return hosts.stream().anyMatch(allowed -> NetworkHosts.covers(allowed, host));
    }

    /**
     * This definition narrowed by {@code accepted} (contract §11): what both allow. A system, tool or repository only
     * when both list it; a tool's mode and a repository's access the narrower of the two; a host of either that a host
     * of the other covers. Narrowing by an older version that allowed less keeps a widening out until it is accepted;
     * what this definition removed stays removed.
     */
    public AgentDefinition narrowedBy(AgentDefinition accepted) {
        List<SystemEntry> narrowedSystems = new ArrayList<>();
        for (SystemEntry system : systems) {
            Optional<SystemEntry> other = accepted.system(system.systemId());
            if (other.isEmpty()) {
                continue;
            }
            List<ToolEntry> tools = new ArrayList<>();
            for (ToolEntry tool : system.tools()) {
                other.get().tool(tool.name())
                    .ifPresent(was -> tools.add(tool.rank() <= was.rank() ? tool : tool.withRank(was.rank())));
            }
            narrowedSystems.add(new SystemEntry(system.systemId(), tools));
        }
        List<RepositoryEntry> narrowedRepositories = new ArrayList<>();
        for (RepositoryEntry repository : repositories) {
            accepted.repository(repository.repositoryId())
                .filter(was -> was.systemId().equals(repository.systemId()))
                .ifPresent(was -> narrowedRepositories.add(new RepositoryEntry(repository.repositoryId(),
                        repository.systemId(), repository.access().min(was.access()))));
        }
        // The names both allow: a host of either that the other covers (a wildcard of one and an exact host of the
        // other under it give the exact host; two nested wildcards the inner one).
        List<String> narrowedHosts = new ArrayList<>();
        for (String host : hosts) {
            if (accepted.coversHost(host)) {
                narrowedHosts.add(host);
            }
        }
        for (String host : accepted.hosts()) {
            if (coversHost(host)) {
                narrowedHosts.add(host);
            }
        }
        return new AgentDefinition(narrowedSystems, narrowedRepositories, narrowedHosts);
    }

    /**
     * What this definition allows that {@code accepted} does not (contract §11): the systems it adds, the tools it
     * adds or raises, the repositories it adds or raises to {@code WRITE}, the hosts none of {@code accepted} covers.
     * Empty when this definition only narrows {@code accepted}, or equals it.
     */
    public Widening wideningOver(AgentDefinition accepted) {
        List<UUID> addedSystems = new ArrayList<>();
        List<Widening.Tool> raisedTools = new ArrayList<>();
        for (SystemEntry system : systems) {
            Optional<SystemEntry> other = accepted.system(system.systemId());
            if (other.isEmpty()) {
                addedSystems.add(system.systemId());
            }
            for (ToolEntry tool : system.tools()) {
                ToolEntry was = other.flatMap(entry -> entry.tool(tool.name())).orElse(null);
                if (was == null || tool.rank() > was.rank()) {
                    raisedTools.add(new Widening.Tool(system.systemId(), tool, was));
                }
            }
        }
        List<Widening.Repository> raisedRepositories = new ArrayList<>();
        for (RepositoryEntry repository : repositories) {
            RepositoryEntry was = accepted.repository(repository.repositoryId()).orElse(null);
            if (was == null || was.access() == RepositoryAccess.READ && repository.access() == RepositoryAccess.WRITE) {
                raisedRepositories.add(new Widening.Repository(repository, was != null ? was.access() : null));
            }
        }
        List<String> addedHosts = hosts.stream().filter(host -> !accepted.coversHost(host)).toList();
        return new Widening(addedSystems, raisedTools, raisedRepositories, addedHosts);
    }

    /**
     * Whether this ceiling lets the agent act on its own (the owner's decision Q4): a tool {@code ALLOW} chosen for a
     * write, a repository at {@code WRITE}, or a network host. Within such a ceiling a change of the instructions or
     * skills widens; within one that cannot act, it only reads and asks, and the change is announced.
     */
    public boolean canAct() {
        return !hosts.isEmpty()
                || repositories.stream().anyMatch(entry -> entry.access() == RepositoryAccess.WRITE)
                || systems.stream().flatMap(system -> system.tools().stream()).anyMatch(tool -> tool.rank() == 2);
    }

    /**
     * {@link #wideningOver(AgentDefinition)}, and the instructions and skills of {@code content} over those of
     * {@code acceptedContent} when this ceiling can act ({@link #canAct}): a change of the instructions, a skill added
     * or a pin moved. A skill removed narrows.
     */
    public Widening wideningOver(AgentDefinition accepted, AgentContent content, AgentContent acceptedContent) {
        Widening ceiling = wideningOver(accepted);
        if (!canAct()) {
            return ceiling;
        }
        return new Widening(ceiling.systems(), ceiling.tools(), ceiling.repositories(), ceiling.hosts(),
                !content.instructions().equals(acceptedContent.instructions()), content.skillsOver(acceptedContent));
    }

    /**
     * What one definition allows beyond another ({@link #wideningOver}).
     *
     * @param systems      the systems added
     * @param tools        the tools added, or raised towards {@code ALLOW}
     * @param repositories the repositories added, or raised to {@code WRITE}
     * @param hosts        the hosts no host of the other covers
     * @param instructions whether the instructions changed within a ceiling that can act
     * @param skills       the skills added, or pinned at another version, within a ceiling that can act
     */
    public record Widening(List<UUID> systems, List<Tool> tools, List<Repository> repositories, List<String> hosts,
            boolean instructions, List<Skill> skills) {

        public static final Widening NONE = new Widening(List.of(), List.of(), List.of(), List.of());

        public Widening {
            systems = List.copyOf(systems);
            tools = List.copyOf(tools);
            repositories = List.copyOf(repositories);
            hosts = List.copyOf(hosts);
            skills = List.copyOf(skills);
        }

        /** A widening of the ceiling alone. */
        public Widening(List<UUID> systems, List<Tool> tools, List<Repository> repositories, List<String> hosts) {
            this(systems, tools, repositories, hosts, false, List.of());
        }

        /** Whether nothing widened. */
        public boolean none() {
            return systems.isEmpty() && tools.isEmpty() && repositories.isEmpty() && hosts.isEmpty()
                    && !contentChanged();
        }

        /** Whether the instructions or the skills widened: the turns keep the accepted version's until accepted. */
        public boolean contentChanged() {
            return instructions || !skills.isEmpty();
        }

        /** A skill added ({@code was} null) or pinned at another version. */
        public record Skill(UUID skillId, int version, @Nullable Integer was) {
        }

        /** A tool added ({@code was} null) or raised. */
        public record Tool(UUID systemId, ToolEntry tool, @Nullable ToolEntry was) {
        }

        /** A repository added ({@code was} null) or raised to {@code WRITE}. */
        public record Repository(RepositoryEntry repository, @Nullable RepositoryAccess was) {
        }

        /** For the audit: ids and names only. */
        public Map<String, Object> details() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("systems", systems.stream().map(UUID::toString).toList());
            details.put("tools", tools.stream()
                .map(tool -> Map.of("systemId", tool.systemId().toString(), "tool", tool.tool().name(), "mode",
                        tool.tool().mode().name()))
                .toList());
            details.put("repositories", repositories.stream()
                .map(entry -> Map.of("repositoryId", entry.repository().repositoryId().toString(), "access",
                        entry.repository().access().name()))
                .toList());
            details.put("hosts", hosts);
            if (instructions) {
                details.put("instructions", "changed");
            }
            if (!skills.isEmpty()) {
                details.put("skills", "changed");
            }
            return details;
        }
    }
}
