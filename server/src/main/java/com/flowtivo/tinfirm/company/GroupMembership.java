package com.flowtivo.tinfirm.company;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The groups of members (ADR-0049 §2) for the higher modules, which give groups rights of their own (who may use a
 * service identity, who acts with a granted account). Runs in the caller's transaction and tenant context: row-level
 * security limits every lookup to the company of that context.
 */
public interface GroupMembership {

    /** The groups the user is a member of in the company; empty for a user who is in none, or not a member. */
    Set<UUID> groupsOf(UUID companyId, UUID userId);

    /** The groups of the company among {@code groupIds}, by id with their names; other ids are absent. */
    Map<UUID, String> groupNames(UUID companyId, Collection<UUID> groupIds);

    /** The users in the group; empty for a group without members, or not the company's. */
    List<UUID> membersOf(UUID companyId, UUID groupId);
}
