package com.flowtivo.tinfirm.principal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A principal's kill switch, its suspension, for both kinds, and the state of a member's departure (the package 6
 * contract, §5.4 and §5.5; open points 5, 21 and 24). The suspension's state is the principal's {@code status} with
 * who, when, why and its cause beside; a suspension is announced as {@link PrincipalEvents.PrincipalSuspended}, after
 * whose commit the model module blocks the key and the run module cancels the running turns it acts for or pays.
 * Runs in the caller's transaction; the member's suspension and the departure's closing authorise the caller.
 */
public interface PrincipalSwitches {

    /**
     * Suspends a member ({@code MANAGE_MEMBERS}; an OWNER's needs {@code MANAGE_OWNERS}): nobody themselves
     * ({@code 409 CANNOT_SUSPEND_SELF}), never the last active OWNER ({@code 409 LAST_OWNER}). Audited as
     * {@code principal.suspended} with the member's {@code userId}; a suspended member stays as they are.
     */
    Suspension suspendMember(UUID companyId, UUID userId, String reason);

    /**
     * Resumes a suspended member ({@code MANAGE_MEMBERS}; an OWNER's needs {@code MANAGE_OWNERS}). A suspension the
     * user's deactivation made is lifted only by reactivating the user ({@code 409 PRINCIPAL_SUSPENDED}).
     */
    Suspension resumeMember(UUID companyId, UUID userId);

    /**
     * SCIM's suspension of the member was just set on their membership (ADR-0087, decision 7): an active principal is
     * suspended with the cause {@code SCIM}, audited {@code principal.suspended}; one already suspended, by an admin or
     * the user's deactivation, stays as it is. The caller is the company's SCIM, which checked the last active OWNER.
     */
    void scimSuspended(UUID companyId, UUID userId);

    /**
     * SCIM's suspension of the member was just lifted from their membership: a principal suspended with the cause
     * {@code SCIM} is resumed; one an admin suspended, or the user's deactivation, stays suspended (decision 7).
     */
    void scimLifted(UUID companyId, UUID userId);

    /** The company's suspended principals, members and service identities, newest suspension first. */
    List<Suspension> suspended(UUID companyId);

    /** The member's principal that has not left, with its suspension if any; empty for a user who is not a member. */
    Optional<Suspension> ofMember(UUID companyId, UUID userId);

    /** A member who left: who, since when, the groups they were in then, and whether the admins closed the checklist. */
    Optional<Departure> departure(UUID companyId, UUID principalId);

    /** The members who left whose checklist is open, newest first, at most {@code limit}. */
    List<Departure> openDepartures(UUID companyId, int limit);

    /**
     * Closes the checklist of a member who left; the caller authorised it and judged its items. Audited as
     * {@code principal.departure_closed} with {@code force} and {@code openItems} (counts by item); a closed checklist
     * stays as it is.
     */
    Departure closeDeparture(UUID companyId, UUID principalId, boolean force, java.util.Map<String, Integer> openItems);

    /**
     * Why a principal is suspended: an admin's switch, the user's deactivation at the instance, or the company's SCIM
     * client (ADR-0087), which lifts only its own.
     */
    enum Cause {
        ADMIN, USER_DEACTIVATED, SCIM
    }

    /**
     * A principal's suspension, or an active principal ({@code since} null).
     *
     * @param userId a member's user; null for a service identity
     * @param name   a service identity's name; null for a member (their name is the user's)
     */
    record Suspension(UUID principalId, PrincipalKind kind, @Nullable UUID userId, @Nullable String name,
            PrincipalStatus status, @Nullable Instant since, @Nullable UUID byUserId, @Nullable String reason,
            @Nullable Cause cause) {
    }

    /** A member who left. */
    record Departure(UUID principalId, UUID userId, Instant leftAt, Set<UUID> groupIds, @Nullable Instant closedAt,
            @Nullable UUID closedByUserId) {

        public Departure {
            groupIds = Set.copyOf(groupIds);
        }

        public boolean closed() {
            return closedAt != null;
        }
    }
}
