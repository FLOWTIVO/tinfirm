package com.flowtivo.tinfirm.company;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port: which members of a company are suspended (the package 6 contract, §5.4, open points 5 and 24), implemented by
 * the principal module, which keeps a member's principal and its status; the company module does not depend on it.
 * The access guard asks it on every company path, and the last-owner checks count only active owners. Runs in the
 * caller's transaction, in the company's tenant context.
 */
public interface MemberStatuses {

    /** Whether the member's principal in the company is {@code SUSPENDED}; false for a user who is not a member. */
    boolean suspended(UUID companyId, UUID userId);

    /** The users among the company's members whose principal is {@code SUSPENDED}. */
    Set<UUID> suspendedUsers(UUID companyId);

    /**
     * The principal of each of these members in the company (one that has not left), by user: the Members API shows
     * it, for the paths that take a principal (a member's own budget). A user who is not a member is absent.
     */
    Map<UUID, UUID> principalIds(UUID companyId, Collection<UUID> userIds);
}
