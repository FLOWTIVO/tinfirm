package com.flowtivo.tinfirm.principal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A principal as other modules see it.
 *
 * @param userId the member's user; null for a service identity
 * @param name   a service identity's name; null for a member, whose name is the user's
 *               ({@link Principals#displayNames})
 */
public record PrincipalInfo(UUID id, UUID companyId, PrincipalKind kind, @Nullable UUID userId, @Nullable String name,
        PrincipalStatus status) {

    /** Whether the principal acts at all: neither suspended nor gone (I7). */
    public boolean active() {
        return status == PrincipalStatus.ACTIVE;
    }

    public boolean member() {
        return kind == PrincipalKind.MEMBER;
    }

    public boolean service() {
        return kind == PrincipalKind.SERVICE;
    }

    /** Whether this is the principal of the user (a member's, of that user). */
    public boolean isMember(UUID user) {
        return member() && user.equals(userId);
    }
}
