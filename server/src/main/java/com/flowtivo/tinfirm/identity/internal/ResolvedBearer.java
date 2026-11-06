package com.flowtivo.tinfirm.identity.internal;

import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.BearerGrant;

/**
 * A bearer credential that a {@link BearerCredentialResolver} accepted: the active user it speaks for, loaded fresh,
 * and the grant (which credential, which scope).
 */
record ResolvedBearer(AuthenticatedUser user, BearerGrant grant) {
}
