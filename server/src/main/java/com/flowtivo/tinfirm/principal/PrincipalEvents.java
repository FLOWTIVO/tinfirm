package com.flowtivo.tinfirm.principal;

import java.util.Set;
import java.util.UUID;

/**
 * The principal module's domain events, for the packages that react to a principal's change (package 3: waiting
 * bindings, a leaving member's conversations). Each carries ids only. Each is published with Spring's
 * {@code ApplicationEventPublisher} in the transaction that made the change, after it records its audit event, which
 * the audit log appends when the transaction commits (CONVENTIONS §8): a listener reacts after the commit with
 * {@code @TransactionalEventListener} (and never sees a change that rolled back), or inside the transaction with
 * {@code @EventListener}.
 */
public final class PrincipalEvents {

    private PrincipalEvents() {
    }

    /**
     * A principal was created: a member's with the membership, or a service identity by an admin. The package 2 model
     * module creates its LiteLLM key after the commit.
     */
    public record PrincipalCreated(UUID companyId, UUID principalId, PrincipalKind kind) {
    }

    /** A service identity was suspended (I7): its accounts act for nothing until it is resumed. */
    public record PrincipalSuspended(UUID companyId, UUID principalId) {
    }

    /** A suspended service identity was resumed. */
    public record PrincipalResumed(UUID companyId, UUID principalId) {
    }

    /**
     * A member left the company (I8): the principal is {@code LEFT}, and their accounts, owned secrets and personal
     * systems are gone (each with its own event).
     */
    public record PrincipalLeft(UUID companyId, UUID principalId, UUID userId) {
    }

    /**
     * The groups that may use a service identity changed (the package 1 contract, §3.2), by a replace or because a
     * group was deleted.
     *
     * @param added   the groups that may use it now and could not before
     * @param removed the groups that may no longer use it
     */
    public record ServiceIdentityUsersChanged(UUID companyId, UUID principalId, Set<UUID> added, Set<UUID> removed) {

        public ServiceIdentityUsersChanged {
            added = Set.copyOf(added);
            removed = Set.copyOf(removed);
        }
    }
}
