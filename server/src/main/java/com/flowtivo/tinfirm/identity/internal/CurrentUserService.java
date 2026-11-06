package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.shared.api.model.CurrentUser;
import com.flowtivo.tinfirm.shared.web.ApiException;

@Service
class CurrentUserService {

    private final UserRepository users;

    private final MembershipDirectory memberships;

    private final UserMapper mapper;

    CurrentUserService(UserRepository users, MembershipDirectory memberships, UserMapper mapper) {
        this.users = users;
        this.memberships = memberships;
        this.mapper = mapper;
    }

    /** The user and their memberships. The tenant context must carry {@code userId}. */
    @Transactional(readOnly = true)
    public CurrentUser describe(UUID userId) {
        UserEntity user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Authentication required"));
        return mapper.toCurrentUser(user, memberships.membershipsOf(userId));
    }
}
