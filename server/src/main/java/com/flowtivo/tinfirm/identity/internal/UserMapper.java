package com.flowtivo.tinfirm.identity.internal;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.flowtivo.tinfirm.identity.UserMembership;
import com.flowtivo.tinfirm.shared.api.model.CurrentUser;
import com.flowtivo.tinfirm.shared.api.model.Membership;
import com.flowtivo.tinfirm.shared.api.model.User;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;

@Mapper(config = ApiMapperConfig.class)
interface UserMapper {

    User toDto(UserEntity user);

    @Mapping(target = "memberships", source = "memberships")
    CurrentUser toCurrentUser(UserEntity user, List<UserMembership> memberships);

    Membership toDto(UserMembership membership);
}
