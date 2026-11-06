package com.flowtivo.tinfirm.company.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;

import com.flowtivo.tinfirm.shared.api.model.Group;
import com.flowtivo.tinfirm.shared.api.model.GroupMember;
import com.flowtivo.tinfirm.shared.api.model.GroupSummary;
import com.flowtivo.tinfirm.shared.mapping.ApiMapperConfig;

/** The DTOs of the Groups API (ADR-0049 §2). */
@Mapper(config = ApiMapperConfig.class)
interface GroupMapper {

    /** A group with its members, by display name. */
    record GroupDetails(UUID id, String name, @Nullable String description, List<MemberDetails> members,
            Instant createdAt, Instant updatedAt) {
    }

    /** A member of a group, as {@code Members} shows people. */
    record MemberDetails(UUID userId, String email, String displayName) {
    }

    /** A group as {@code listGroups} shows it. */
    record SummaryDetails(UUID id, String name, @Nullable String description, int memberCount) {
    }

    Group toDto(GroupDetails group);

    GroupMember toDto(MemberDetails member);

    GroupSummary toDto(SummaryDetails group);
}
