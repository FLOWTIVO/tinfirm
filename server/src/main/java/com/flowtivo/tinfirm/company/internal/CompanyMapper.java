package com.flowtivo.tinfirm.company.internal;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.api.model.Company;
import com.flowtivo.tinfirm.shared.api.model.Member;
import com.flowtivo.tinfirm.shared.api.model.Role;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;

@Mapper(config = ApiMapperConfig.class)
interface CompanyMapper {

    Company toDto(CompanyQueries.CompanyRow company);

    @Mapping(target = "userId", source = "member.userId")
    @Mapping(target = "role", source = "member.role")
    @Mapping(target = "addedAt", source = "member.createdAt")
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "roleManagedBy", ignore = true)
    Member toDto(CompanyQueries.MemberRow member, UserSummary user);

    Role toApi(CompanyRole role);

    CompanyRole toDomain(Role role);
}
