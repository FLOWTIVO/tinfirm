package com.flowtivo.tinfirm.identity;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;

/**
 * Port: the companies a user of an identity provider joins, those memberships (ADR-0075, ADR-0089), and the role the
 * provider decides at every sign-in (ADR-0088). The companies are the deployment's declarations
 * ({@code tinfirm.companies}); implemented by the company module. Called by the sign-in through a provider only,
 * with company ids the server resolved from the deployment's settings, never from the request: each call enters that
 * company's tenant context in the sign-in's transaction.
 */
public interface ProvisionedMemberships {

    /** Whether the deployment declares a company with this key. */
    boolean declares(String companyKey);

    /**
     * The company a provider fills: the declared company with this key, or, with no key, the only declared company
     * when exactly one is declared. Empty when there is none, or when it does not exist yet.
     */
    Optional<UUID> companyFor(@Nullable String companyKey);

    /**
     * The key of the declared company a provider's new users join: {@code companyKey} when it is declared, or, with no
     * key, the only declared company's. Known at the start, before the companies exist.
     */
    Optional<String> companyKeyFor(@Nullable String companyKey);

    /**
     * Makes the user a member of the company with the role, audited as {@code member.added} in the company's chain
     * with the registration id as {@code provider}. Called in the sign-in's transaction, as actor SYSTEM. A role that
     * holds a seat becomes VIEWER when the licence has no seat free, and the company's Owners and Admins see it in
     * their inbox (ADR-0077, decision 5): signing in never fails for want of a seat.
     *
     * @param role   never OWNER
     * @param source who decides the role from now on: {@link RoleSource#provider} when the provider does (ADR-0088),
     *               else {@link RoleSource#APP}
     * @return the role given
     */
    CompanyRole grant(UUID companyId, UUID userId, CompanyRole role, String provider, RoleSource source);

    /**
     * Applies the role a provider that decides roles maps at a sign-in to the user's membership of one of its
     * companies (ADR-0088 decision 2), in the sign-in's transaction, as actor SYSTEM: with the seat fallback, audited
     * as {@code member.role.changed} when it changes. Never grants a membership, never touches an OWNER, and never a
     * membership that SCIM manages. It takes the company's lock, which the sign-in keeps until it commits, so that a
     * {@link #grant} after {@link ProviderRole#NOT_A_MEMBER} in the same transaction cannot race another sign-in's.
     * A sign-in calls it, and {@link #leave}, for its companies in their id order, so that two never deadlock.
     *
     * @param role the mapped role, never OWNER; null when the claim maps to none
     */
    ProviderRole applyProviderRole(UUID companyId, UUID userId, @Nullable CompanyRole role, String provider);

    /**
     * Ends the user's membership of a company the provider decides, because its claim names no role there any more
     * (ADR-0089): as a member who leaves, in the sign-in's transaction, as actor SYSTEM, audited {@code member.removed}
     * with the {@code provider} and {@code roleSource: IDP}. Never an OWNER, and never a membership that SCIM manages.
     */
    Departure leave(UUID companyId, UUID userId, String provider);

    /**
     * At the start: every membership whose role source is a provider that no longer decides the roles of that
     * membership's company becomes the app's, keeping its role, audited once per company and provider as
     * {@code member.role_source.changed} (ADR-0088 decision 7).
     *
     * @param deciding the registration ids of the providers that decide roles, each with the keys of the companies it
     *                 decides
     */
    void releaseProviderRoles(Map<String, Set<String>> deciding);

    /**
     * The member of the company whose SCIM {@code externalId} is this one (ADR-0087, decision 6), for the binding of a
     * first sign-in; in the sign-in's transaction and the company's tenant context.
     */
    Optional<UUID> scimMemberByExternalId(UUID companyId, String externalId);

    /** The member of the company whose user has this email, for a provider's {@code _SCIM_MATCH=EXTERNAL_ID_OR_EMAIL}. */
    Optional<UUID> scimMemberByEmail(UUID companyId, String email);

    /** Whether the user is an OWNER of any company: no sign-in is bound to an owner's account (decision 6). */
    boolean ownsACompany(UUID userId);

    /** What {@link #applyProviderRole} did. */
    enum ProviderRole {

        /** The membership has the provider's role, or VIEWER for want of a seat. */
        APPLIED,

        /** An OWNER, or a membership that SCIM manages: not applied, and never refused. */
        KEPT,

        /** No member of the company: the provider grants the membership when it decides (ADR-0089). */
        NOT_A_MEMBER,

        /** The claim maps to no role: the sign-in is refused ({@code ROLE_NOT_MAPPED}), the membership kept. */
        NOT_MAPPED
    }

    /** What {@link #leave} did. */
    enum Departure {

        /** The member left the company. */
        LEFT,

        /** An OWNER, or a membership that SCIM manages: kept. */
        KEPT,

        /** No member of the company: nothing to end. */
        NOT_A_MEMBER
    }
}
