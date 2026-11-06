package com.flowtivo.tinfirm.shared.security;

import java.io.Serial;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Authentication of trusted server-side work (bootstrap, maintenance). Audited with actor {@code SYSTEM}. */
public final class SystemAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    static final SystemAuthentication INSTANCE = new SystemAuthentication();

    private SystemAuthentication() {
        super(List.of());
        setAuthenticated(true);
    }

    @Override
    public String getPrincipal() {
        return "system";
    }

    @Override
    public @Nullable Object getCredentials() {
        return null;
    }
}
