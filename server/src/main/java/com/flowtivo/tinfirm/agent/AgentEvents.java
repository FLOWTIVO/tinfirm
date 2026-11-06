package com.flowtivo.tinfirm.agent;

import java.util.Set;
import java.util.UUID;

/**
 * The agent module's domain events (the package 3 contract, §5.1 and §11), for the modules that react to an agent's
 * change: conversations end when their agent is deleted, a paused agent's turns wait. Ids only. Each is published with
 * Spring's {@code ApplicationEventPublisher} in the transaction that made the change, after it records its audit event,
 * which the audit log appends when the transaction commits (CONVENTIONS §8): a listener reacts after the commit with
 * {@code @TransactionalEventListener}, or inside the transaction with {@code @EventListener}.
 */
public final class AgentEvents {

    private AgentEvents() {
    }

    /** An agent was created, as version 1. */
    public record AgentCreated(UUID companyId, UUID agentId) {
    }

    /**
     * The agent's definition changed and made a new version; {@code widens} when it adds a system, tool, repository or
     * host, or raises an access or a mode, or changes the instructions or skills within a ceiling that can act (then
     * {@link AgentWidened} follows). Narrowing applies to everyone's next
     * turn; widening only once each person accepts it.
     */
    public record AgentChanged(UUID companyId, UUID agentId, int version, boolean widens) {
    }

    /** The new version widens the one before: people see what widened and may accept it. */
    public record AgentWidened(UUID companyId, UUID agentId, int version) {
    }

    /** The groups whose members may use the agent, or the members who may use it on their own, changed. */
    public record AgentShared(UUID companyId, UUID agentId, Set<UUID> addedGroupIds, Set<UUID> removedGroupIds,
            Set<UUID> addedPrincipalIds, Set<UUID> removedPrincipalIds) {

        public AgentShared {
            addedGroupIds = Set.copyOf(addedGroupIds);
            removedGroupIds = Set.copyOf(removedGroupIds);
            addedPrincipalIds = Set.copyOf(addedPrincipalIds);
            removedPrincipalIds = Set.copyOf(removedPrincipalIds);
        }

        /** Whether someone lost the agent's use right: a group or a member was taken from it. */
        public boolean narrows() {
            return !removedGroupIds.isEmpty() || !removedPrincipalIds.isEmpty();
        }
    }

    /**
     * The agent was paused (the kill switch): its conversations start no turn and run no approved call; with
     * {@code stopRunning} its running turns were cancelled ({@link AgentRuns#cancelRunning}).
     */
    public record AgentPaused(UUID companyId, UUID agentId, boolean stopRunning) {
    }

    /** A paused agent was resumed: its conversations' waiting turns may start. */
    public record AgentResumed(UUID companyId, UUID agentId) {
    }

    /**
     * The agent was deleted (soft): its shares and settings went; its conversations end ({@code AGENT_DELETED}) with
     * their decisions and waiting bindings, which their module does in this transaction.
     */
    public record AgentDeleted(UUID companyId, UUID agentId) {
    }

    /** The agent's owner left the company: admins edit it now (contract §5.7). */
    public record AgentPassedToAdmins(UUID companyId, UUID agentId, UUID formerOwnerPrincipalId) {
    }

    /** A person accepted the agent's widening up to a version (contract §11). */
    public record AgentAccepted(UUID companyId, UUID agentId, UUID principalId, int version) {
    }

    /** A person's settings of the agent changed, or returned to the defaults. */
    public record SettingsChanged(UUID companyId, UUID agentId, UUID principalId) {
    }
}
