package com.flowtivo.tinfirm.shared.security;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Per-request authentication of a logged-in user, built from the session principal and fresh user data.
 * It is never stored in the session.
 */
public final class ActorAuthentication extends AbstractAuthenticationToken {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_INSTANCE_ADMIN = "ROLE_INSTANCE_ADMIN";

    @Serial
    private static final long serialVersionUID = 1L;

    private final AuthenticatedUser user;

    public ActorAuthentication(AuthenticatedUser user) {
        super(authorities(user));
        this.user = user;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(AuthenticatedUser user) {
        List<GrantedAuthority> authorities = new ArrayList<>(2);
        authorities.add(new SimpleGrantedAuthority(ROLE_USER));
        if (user.instanceAdmin()) {
            authorities.add(new SimpleGrantedAuthority(ROLE_INSTANCE_ADMIN));
        }
        return authorities;
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }

    @Override
    public @Nullable Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return user.id().toString();
    }
}
