package com.flowtivo.tinfirm.company.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.identity.UserMembership;

@Component
class MembershipDirectoryImpl implements MembershipDirectory {

    private final CompanyQueries queries;

    MembershipDirectoryImpl(CompanyQueries queries) {
        this.queries = queries;
    }

    /** Readable through the "own memberships" policy: the tenant context must carry {@code userId}. */
    @Override
    @Transactional(readOnly = true)
    public List<UserMembership> membershipsOf(UUID userId) {
        return queries.membershipsOf(userId);
    }
}
