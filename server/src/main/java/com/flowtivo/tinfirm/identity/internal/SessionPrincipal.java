package com.flowtivo.tinfirm.identity.internal;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

/** Session principal of a local login. */
record SessionPrincipal(UUID userId) implements SessionUser, AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return userId.toString();
    }
}
