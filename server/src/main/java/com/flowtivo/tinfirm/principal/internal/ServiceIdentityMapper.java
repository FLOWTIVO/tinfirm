package com.flowtivo.tinfirm.principal.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;

import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.shared.api.model.GroupRef;
import com.flowtivo.tinfirm.shared.api.model.ServiceIdentity;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;

/** The DTOs of the ServiceIdentities API (the package 1 contract, §6). */
@Mapper(config = ApiMapperConfig.class)
interface ServiceIdentityMapper {

    /**
     * A service identity as the caller may see it.
     *
     * @param userGroups       the groups that may use it, by name; null for a caller who does not manage service
     *                         identities
     * @param suspendedAt      when it was suspended, while it is; null otherwise
     * @param suspensionReason the reason typed, while it is suspended, for a caller who manages service identities;
     *                         null otherwise
     */
    record ServiceIdentityDetails(UUID id, String name, @Nullable String description, PrincipalStatus status,
            boolean usableByMe, @Nullable List<GroupDetails> userGroups, @Nullable Instant suspendedAt,
            @Nullable String suspensionReason, Instant createdAt, Instant updatedAt) {
    }

    record GroupDetails(UUID id, String name) {
    }

    ServiceIdentity toDto(ServiceIdentityDetails details);

    GroupRef toDto(GroupDetails group);
}
