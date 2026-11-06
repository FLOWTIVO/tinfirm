package com.flowtivo.tinfirm.audit;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Port: the company's principals as the reads of its audit chain need them (CONVENTIONS §8, §15.11), implemented by
 * the principal module: whose user a member's principal is, for the events about a principal ({@code principalId})
 * and the names of principal targets; and which principals a user had in the company, for a person the company erased.
 * Runs in the reader's transaction and tenant context.
 */
public interface AuditPrincipals {

    /** These principals of the company; unknown ids, and other companies', are absent. */
    Map<UUID, Principal> find(UUID companyId, Collection<UUID> principalIds);

    /** Every member principal these users had in the company, those who left included. */
    List<Principal> ofUsers(UUID companyId, Collection<UUID> userIds);

    /**
     * A principal of the company.
     *
     * @param userId a member's user; null for a service identity
     * @param name   a service identity's name; null for a member, whose name is their user's
     * @param left   whether the member left the company
     */
    record Principal(UUID id, @Nullable UUID userId, @Nullable String name, boolean left) {
    }
}
