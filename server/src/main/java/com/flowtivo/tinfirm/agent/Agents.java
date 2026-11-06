package com.flowtivo.tinfirm.agent;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Agents for other modules (the package 3 contract, §3.1 to §3.6, §5.1 and §11). Runs in the caller's transaction and
 * tenant context: row-level security limits every lookup to the company of that context, and an agent of another
 * company is absent.
 */
public interface Agents {

    /** The agent, live or deleted. */
    Optional<AgentInfo> find(UUID companyId, UUID agentId);

    /**
     * The live agents the member uses (their own, those shared with their groups and those shared with them;
     * {@code MANAGE_AGENTS} adds none), by name, at most {@code limit} (package 5: the agent picker of a direct
     * message).
     */
    java.util.List<AgentInfo> usedBy(UUID companyId, UUID userId, int limit);

    /** The live agent with this handle, ignoring case (package 5: a direct message's leading {@code @handle}). */
    Optional<AgentInfo> byHandle(UUID companyId, String handle);

    /**
     * {@code Agents.access(caller, agent)} (contract §5.1): what the user may do with the agent. Its owner sees, uses
     * and edits it; a member of a group it is shared with, and a member it is shared with on their own, see and use
     * it; OWNER and ADMIN see and edit every agent and use one only as its owner or through a share.
     * {@link AgentAccess#NONE} for a deleted or unknown agent.
     */
    AgentAccess access(UUID companyId, UUID agentId, UUID userId);

    /** The agent's current ceiling, as its tables hold it now. Empty for a deleted or unknown agent. */
    Optional<AgentDefinition> definition(UUID companyId, UUID agentId);

    /**
     * {@code Agents.effective(agentId, acceptedVersion)} (contract §11): the current version narrowed by the accepted
     * one, with what the current version adds over it. An accepted version above the current one (never stored) counts
     * as the current. Empty for a deleted or unknown agent.
     */
    Optional<EffectiveAgent> effective(UUID companyId, UUID agentId, int acceptedVersion);

    /**
     * How the instructions and skills of version {@code to} of the agent differ from those of version {@code from}
     * (the security review of 2026-10-27, D1): for a conversation whose turn uses other instructions or skills than its
     * turn before ({@link EffectiveAgent#contentVersion}), which it then announces. Empty when they are the same, or
     * for a deleted or unknown agent.
     */
    Optional<ContentChange> contentChange(UUID companyId, UUID agentId, int from, int to);

    /**
     * A change of an agent's instructions or skills between two versions.
     *
     * @param from         the version the turn before used
     * @param to           the version the turn uses now; below {@code from} when a newer one waits for acceptance
     * @param version      the newest version between them that changed the instructions or skills
     * @param at           when that version was made
     * @param byUserId     who made it; null for a system change
     * @param instructions whether the instructions differ
     * @param skills       whether the skills differ
     */
    record ContentChange(int from, int to, int version, java.time.Instant at, @Nullable UUID byUserId,
            boolean instructions, boolean skills) {
    }

    /**
     * An approved {@code request} of a repository for good (contract §5.8, scope {@code AGENT}): the repository joins
     * the agent with the access, and its code host without tools when the agent does not list it. The caller has
     * authorised the approver to edit the agent. Makes a new version, which widens (audited as
     * {@code agent.repositories_changed} with {@code decisionId}); the approver's own settings accept it when they were
     * up to date. A repository the agent lists with that access or more changes nothing. Answers the current version.
     */
    int addRepository(UUID companyId, UUID agentId, UUID repositoryId, RepositoryAccess access, UUID decisionId);

    /**
     * An approved {@code request} of a network host for good (contract §5.8, scope {@code AGENT}): an
     * {@code agent_host} row naming the decision, as {@link #addRepository} does for a repository. A host the agent
     * covers already changes nothing. Answers the current version.
     *
     * @throws IllegalArgumentException a host that is not one ({@link NetworkHosts#parse})
     */
    int addHost(UUID companyId, UUID agentId, String host, @Nullable String reason, UUID decisionId);

    /**
     * The live agents that passed to the admins from the member who left and that no admin gave a new owner since (the
     * package 6 contract, §5.5), by id with their names, for the leaver's checklist.
     */
    java.util.Map<UUID, String> passedFrom(UUID companyId, UUID formerOwnerPrincipalId);

    /** The company's paused live agents, newest pause first: kill switches that are on (package 6, §5.4). */
    java.util.List<PausedAgent> paused(UUID companyId);

    /** A paused agent: since when and by which user. */
    record PausedAgent(UUID id, String name, java.time.Instant pausedAt, @Nullable UUID pausedBy) {
    }

    /** How many live agents the principal owns: what passes to the admins when they leave. */
    int ownedBy(UUID companyId, UUID ownerPrincipalId);
}
