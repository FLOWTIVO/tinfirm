package com.flowtivo.tinfirm.system;

import java.util.Set;
import java.util.UUID;

/**
 * The system module's domain events, for the packages that react to a change of a system (package 3: bindings and tools
 * of conversations). Ids and names only. Each is published with Spring's {@code ApplicationEventPublisher} in the
 * transaction that made the change, after it records its audit event, which the audit log appends when the transaction
 * commits (CONVENTIONS §8): a listener reacts after the commit with {@code @TransactionalEventListener}, or inside the
 * transaction with {@code @EventListener}.
 */
public final class SystemEvents {

    private SystemEvents() {
    }

    /** The system was deleted, after its accounts, grants, repositories and tools. */
    public record SystemDeleted(UUID companyId, UUID systemId) {
    }

    /**
     * Tools of an MCP server's catalogue changed: added, removed, changed (and so unconfirmed), confirmed, switched on
     * or off.
     */
    public record SystemToolsChanged(UUID companyId, UUID systemId, Set<String> tools) {

        public SystemToolsChanged {
            tools = Set.copyOf(tools);
        }
    }

    /** A repository was removed from its code host. */
    public record RepositoryRemoved(UUID companyId, UUID systemId, UUID repositoryId) {
    }

    /** An admin paused the system (the package 6 contract, §5.4): its accounts are used for nothing. */
    public record SystemPaused(UUID companyId, UUID systemId) {
    }

    /** A paused system was resumed: what waited on it goes on. */
    public record SystemResumed(UUID companyId, UUID systemId) {
    }

    /**
     * Whether an MCP server needs accounts changed (ADR-0071): conversations' bindings on it are looked at again, a
     * waiting one binding without an account, an account-less one stopping once accounts are needed again.
     */
    public record AccountsRequiredChanged(UUID companyId, UUID systemId, boolean accountsRequired) {
    }

    /**
     * An MCP server moved to a new endpoint (ADR-0079), after its accounts and credentials went, each announced as its
     * account's disconnection.
     */
    public record SystemMoved(UUID companyId, UUID systemId) {
    }

    /** The company switched personal MCP servers on or off (contract §10). */
    public record PersonalSystemsSwitched(UUID companyId, boolean on) {
    }

    /** A Slack system's transport changed (the package 5 contract, §5.1): its Socket Mode connections follow. */
    public record ChatTransportChanged(UUID companyId, UUID systemId) {
    }
}
