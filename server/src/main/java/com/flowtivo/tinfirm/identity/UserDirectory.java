package com.flowtivo.tinfirm.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read access to users. */
public interface UserDirectory {

    Optional<UserSummary> findById(UUID id);

    /** The user with this email (case-insensitive). */
    Optional<UserSummary> findByEmail(String email);

    /** The local user, with a password, that has this email (case-insensitive): one an instance admin made. */
    Optional<UserSummary> findLocalByEmail(String email);

    Map<UUID, UserSummary> findAllById(Collection<UUID> ids);

    /**
     * The user whose last login through an Entra ID v2.0 issuer named this tenant and object id in its ID token (the
     * package 5 contract, §3.8, §5.4): the one key Teams' automatic link matches, never an email or a name.
     */
    Optional<UserSummary> findByEntraObject(String tenantId, String objectId);
}
