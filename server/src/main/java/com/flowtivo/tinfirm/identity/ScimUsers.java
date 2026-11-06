package com.flowtivo.tinfirm.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Port: the users a company's SCIM client creates, renames and deactivates (ADR-0087, decisions 6 and 7), for the
 * company module's SCIM directory. The caller authorises the request (the company's SCIM token) and decides which user
 * it may touch; each method runs in the caller's transaction and is audited in the instance chain with the current
 * actor, the token.
 */
public interface ScimUsers {

    Optional<ScimUser> find(UUID userId);

    /** The user with this email, case-insensitive. */
    Optional<ScimUser> findByEmail(String email);

    /**
     * Creates a user with no password and no identity, which their first sign-in through a provider of the company
     * binds ({@code authSource} {@code SCIM}), audited {@code user.created}.
     *
     * @throws com.flowtivo.tinfirm.shared.web.ApiException {@code 409 DUPLICATE_EMAIL} when the email is taken
     */
    ScimUser create(UUID companyId, String email, String displayName);

    /**
     * Changes the user's email and name, where given and different, audited {@code user.updated} with the fields'
     * names. The caller decides that the company's IdP may (decision 7).
     *
     * @throws com.flowtivo.tinfirm.shared.web.ApiException {@code 409 DUPLICATE_EMAIL} when another user has the email
     */
    ScimUser update(UUID userId, @Nullable String email, @Nullable String displayName);

    /**
     * Sets or lifts SCIM's deactivation of the user, beside an instance admin's (decision 7): a user is inactive while
     * either holds. A user who becomes inactive loses their sessions everywhere and is suspended in every company; one
     * who becomes active again is resumed where their deactivation suspended them. Audited {@code user.updated} with
     * {@code scimActive} when SCIM's own changes.
     *
     * @return the user as they stand after
     */
    ScimUser setScimActive(UUID userId, boolean active);

    /**
     * A user as SCIM sees them.
     *
     * @param authSource      {@code LOCAL}, {@code OIDC} or {@code SCIM}
     * @param active          the user's standing as a whole
     * @param scimDeactivated whether SCIM's own deactivation holds
     * @param scimCompanyId   the company whose SCIM created or bound the user; null for none
     */
    record ScimUser(UUID id, String email, String displayName, String authSource, boolean active,
            boolean scimDeactivated, @Nullable UUID scimCompanyId, Instant createdAt, Instant updatedAt) {

        /** A user SCIM created who has not signed in yet. */
        public boolean awaitingSignIn() {
            return "SCIM".equals(authSource);
        }
    }
}
