package com.flowtivo.tinfirm.company.scim;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * The company module's own API for the memberships the company's SCIM client manages (ADR-0087, decision 12): what
 * the SCIM directory under {@code ee/} reads and changes, with the module's checks. The caller is the company's SCIM
 * token, which authorises the request for its company; each method runs in the caller's transaction and the company's
 * tenant context, audited with the token as actor. The membership's changes take the company's lock
 * ({@link #lock}) and a role that holds a seat takes the seat check's ({@code licence:seats}).
 */
public interface ScimMembers {

    /** The source of a seat fallback SCIM caused, for the admins' {@code SEAT_FALLBACK} item (decision 8). */
    String SOURCE = "scim";

    /** Serialises the company's membership changes until the transaction ends (the last-owner checks). */
    void lock(UUID companyId);

    Optional<Member> find(UUID companyId, UUID userId);

    Optional<Member> findByExternalId(UUID companyId, String externalId);

    /** The member whose user has this email, case-insensitive. */
    Optional<Member> findByEmail(UUID companyId, String email);

    /** The company's members. */
    int count(UUID companyId);

    /**
     * A page of the company's members by the user's id, from a 0-based offset: SCIM's lists are index-based (RFC 7644
     * §3.4.2.4), the one read of the server with an offset, which the caller bounds (CONVENTIONS §7), over the index
     * {@code (company_id, user_id)} inside one company.
     */
    List<Member> page(UUID companyId, int offset, int limit);

    /**
     * Makes the user a member with the role asked, or VIEWER when it holds a seat and none is free (decision 8), and
     * the {@code externalId}; audited {@code member.added} with {@code provider} {@code scim}, and, after a fallback,
     * {@code seatFallback} and {@code providerRole}, which the admins' {@code SEAT_FALLBACK} item shows.
     *
     * @param role        never OWNER
     * @param managesRole whether SCIM decides the role from now on (ADR-0088 decision 8, {@code IDP}): the
     *                    membership's role source is {@code SCIM}, else the app's
     * @throws org.springframework.dao.DataIntegrityViolationException when another member holds the {@code externalId}
     */
    Given add(UUID companyId, UUID userId, CompanyRole role, String externalId, boolean managesRole);

    /**
     * Gives the member the role asked, or VIEWER when it holds a seat the member does not and none is free; an
     * OWNER's role never changes (decision 7). The membership's role source becomes {@code SCIM}, which the app's
     * members' API then answers {@code 409 ROLE_MANAGED} and a sign-in's claim leaves (ADR-0088 decision 8). Audited
     * {@code member.role.changed} when it changes, with {@code seatFallback} and {@code providerRole} after a
     * fallback.
     *
     * @param role never OWNER
     * @return the member's role after
     */
    Given changeRole(UUID companyId, UUID userId, CompanyRole role);

    /**
     * Every membership of the company whose role SCIM decides becomes the app's, keeping its role (ADR-0088 decision
     * 7): when the company's {@code _SCIM_ROLE_SOURCE} is {@code APP}, its SCIM is no longer allowed, or the licence
     * stops serving SCIM. Audited once, {@code member.role_source.changed} with the count.
     *
     * @return how many were released
     */
    int releaseRoles(UUID companyId);

    /**
     * Sets the member's {@code externalId}; the caller never changes an OWNER's (decision 7).
     *
     * @throws org.springframework.dao.DataIntegrityViolationException when another member holds it
     */
    void setExternalId(UUID companyId, UUID userId, String externalId);

    /**
     * Sets or clears SCIM's own suspension of the member on the membership (decision 7); the principal's suspension is
     * the principal module's, which the caller tells after.
     */
    void setScimSuspended(UUID companyId, UUID userId, boolean suspended);

    /**
     * The member leaves the company as a member an admin removes does (decision 7): their principal leaves, their
     * content stays, the departure's checklist goes to the Owners and Admins. The caller checked the last OWNER.
     */
    void remove(UUID companyId, UUID userId);

    /**
     * Whether the member is an OWNER and the company's last active one: a suspended owner counts for nothing, so that
     * the company keeps an owner who can act.
     */
    boolean lastActiveOwner(UUID companyId, UUID userId);

    /**
     * SCIM tried to deactivate or delete this OWNER, the company's last active one, and was refused: the OWNER's inbox
     * says so ({@code SCIM_LAST_OWNER}, decision 7).
     */
    void lastOwnerRefused(UUID companyId, UUID userId);

    /** When SCIM was last refused the deactivation or deletion of this OWNER, if since {@code since}. */
    Optional<Instant> lastOwnerRefusedSince(UUID companyId, UUID userId, Instant since);

    /**
     * The member, who becomes active again after SCIM's deactivation of the user, takes VIEWER when the role they hold
     * needs a seat and none is free (decision 8): audited {@code member.role.changed} with {@code seatFallback}.
     */
    Given fallBackToViewer(UUID companyId, UUID userId);

    /** Whether the user is an OWNER of any company: no sign-in binds to an owner's account (decision 6). */
    boolean ownsACompany(UUID userId);

    /**
     * The user's memberships of other companies, and how many of them are not suspended: SCIM deactivates the user
     * only when no other company holds them, and renames them only while this company is their only one.
     */
    Elsewhere elsewhere(UUID companyId, UUID userId);

    /**
     * A membership as SCIM sees it.
     *
     * @param externalId    the IdP's id of the person; null for a member SCIM did not create or bind
     * @param scimSuspended whether SCIM's own suspension holds
     * @param suspended     whether the member is suspended, by SCIM, an admin or the user's deactivation
     */
    record Member(UUID companyId, UUID userId, CompanyRole role, @Nullable String externalId,
            boolean scimSuspended, boolean suspended, Instant createdAt, Instant updatedAt) {
    }

    /** A role given: the role asked, or VIEWER when no seat was free for it (decision 8). */
    record Given(CompanyRole role, boolean seatFallback) {
    }

    /** A user's memberships of other companies. */
    record Elsewhere(int memberships, int activeMemberships) {
    }
}
