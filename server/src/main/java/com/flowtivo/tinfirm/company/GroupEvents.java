package com.flowtivo.tinfirm.company;

import java.util.Set;
import java.util.UUID;

/**
 * The company module's domain events about groups, for the packages that react to them (package 3: a person who left
 * the group a grant was made to). Ids only. Published with Spring's {@code ApplicationEventPublisher} in the
 * transaction that made the change, after it records its audit event, which the audit log appends when the transaction
 * commits (CONVENTIONS §8): a listener reacts after the commit with {@code @TransactionalEventListener}, or inside the
 * transaction with {@code @EventListener}.
 */
public final class GroupEvents {

    private GroupEvents() {
    }

    /**
     * Some joined or left a group: its members were replaced, or one was added or taken out; also for each group a
     * member who leaves the company drops out of with the membership (the package 6 contract, §5.5), so that teams and
     * grants are looked at again.
     */
    public record GroupMembersChanged(UUID companyId, UUID groupId, Set<UUID> addedUserIds, Set<UUID> removedUserIds) {

        public GroupMembersChanged {
            addedUserIds = Set.copyOf(addedUserIds);
            removedUserIds = Set.copyOf(removedUserIds);
        }
    }
}
