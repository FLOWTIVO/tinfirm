package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

/**
 * The principal stored in the session: only the user id. Everything else is loaded fresh on every request by
 * {@link ActiveUserFilter}. {@code getName()} of the authentication is the user id, which Spring Session indexes, so
 * all sessions of a user can be found and deleted.
 */
interface SessionUser {

    UUID userId();
}
