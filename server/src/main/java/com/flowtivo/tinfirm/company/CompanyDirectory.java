package com.flowtivo.tinfirm.company;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * Company data for other modules. Runs in the caller's transaction and tenant context, so the caller
 * must already be authorised for the company (or work for it as a trusted system task).
 */
public interface CompanyDirectory {

    Optional<CompanySummary> find(UUID companyId);

    /**
     * The user's current role in the company, or empty when the user is not a member. Readable in the company's
     * tenant context, e.g. to judge what an agent may do with its owner's rights (ADR-0035).
     */
    Optional<CompanyRole> roleOf(UUID companyId, UUID userId);

    /** The members of the company with the role (bounded by the company's members), e.g. its OWNERs. */
    Set<UUID> usersWithRole(UUID companyId, CompanyRole role);

    /**
     * Whether the company's SCIM client suspended the member (ADR-0087, decision 7), which only it lifts; false for a
     * user who is not a member.
     */
    boolean scimSuspended(UUID companyId, UUID userId);

    /**
     * Serialises the changes of the company's memberships and roles (the last-owner checks) until the transaction
     * ends, as adding, removing and changing a member does; a member's suspension takes it too.
     */
    void lockMemberships(UUID companyId);

    /**
     * The companies the user is a member of, across companies (the narrow lookup {@code member_companies}), for an
     * instance admin's deactivation of the user (the package 6 contract, §5.4); the caller enters each company's
     * tenant context before it reads or writes there.
     */
    java.util.List<UUID> companiesOf(UUID userId);

    record CompanySummary(UUID id, String name) {
    }
}
