package com.flowtivo.tinfirm.principal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Principals for other modules (the package 1 contract, §3.1 and §3.2). Runs in the caller's transaction and tenant
 * context: row-level security limits every lookup to the company of that context, and a principal of another company
 * is absent.
 */
public interface Principals {

    Optional<PrincipalInfo> find(UUID companyId, UUID principalId);

    /**
     * The principal, with its row locked {@code FOR KEY SHARE} until the caller's transaction (a mandatory one) ends: no
     * deletion of the service identity meanwhile, the one change that locks the row {@code FOR UPDATE} (a change of its
     * status or its users and a member's leaving lock it {@code FOR NO KEY UPDATE}, which this lock lets go on). For a
     * module that then locks and writes rows of its own for the principal, which check the principal's row again
     * through their foreign keys (a row written twice in the transaction, a row inserted): the principal's row comes
     * before them, and they before the company's audit chain head (CONVENTIONS §10). Empty when the principal is gone.
     */
    Optional<PrincipalInfo> lockShared(UUID companyId, UUID principalId);

    /** The principals among {@code principalIds}; unknown ids are absent. */
    Map<UUID, PrincipalInfo> findAll(UUID companyId, Collection<UUID> principalIds);

    /**
     * The ids of the company's principals that have not left, members and service identities (bounded by the
     * company's members and service identities): for background work over every principal, such as the package 2
     * reconciliation of LiteLLM keys.
     */
    List<UUID> currentIds(UUID companyId);

    /** The member's current principal, the one that has not left; empty for a user who is not a member. */
    Optional<PrincipalInfo> ofMember(UUID companyId, UUID userId);

    /**
     * Whether the user may use the principal (contract §3.2): their own member's principal; a service identity when the
     * user is the company's OWNER, or a member of one of the groups that may use it, never a VIEWER (the security
     * review of 2026-10-27, M7). Nobody uses another member's principal, ADMIN included. The principal's status is not
     * looked at: whether it acts now is {@link PrincipalInfo#active()}.
     */
    boolean mayUse(UUID companyId, UUID userId, UUID principalId);

    /** The service identities the user may use, as {@link #mayUse} says: none for a VIEWER, who holds no seat. */
    Set<UUID> usableServiceIdentities(UUID companyId, UUID userId);

    /**
     * The names to show for the principals: a service identity's own, a member's user's display name ({@code deleted
     * user} for a user that no longer exists). Unknown ids are absent. Never part of an audit's details.
     */
    Map<UUID, String> displayNames(UUID companyId, Collection<UUID> principalIds);
}
