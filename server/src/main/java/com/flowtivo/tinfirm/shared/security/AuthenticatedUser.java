package com.flowtivo.tinfirm.shared.security;

import java.util.UUID;

/**
 * The logged-in user of the current request, loaded fresh from the database on every request
 * (so a demotion or deactivation takes effect immediately).
 */
public record AuthenticatedUser(UUID id, String email, String displayName, boolean instanceAdmin) {
}
