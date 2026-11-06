package com.flowtivo.tinfirm.identity;

import java.util.List;
import java.util.UUID;

/** Port: a user's memberships, for {@code GET /api/me}. Implemented by the company module. */
public interface MembershipDirectory {

    /** The memberships of {@code userId}; the tenant context must carry that user. */
    List<UserMembership> membershipsOf(UUID userId);
}
