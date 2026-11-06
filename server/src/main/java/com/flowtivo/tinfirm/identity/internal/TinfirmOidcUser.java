package com.flowtivo.tinfirm.identity.internal;

import java.io.Serial;
import java.util.Collection;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/** Session principal of an OIDC login, linked to a Tinfirm user by (issuer, subject). */
final class TinfirmOidcUser extends DefaultOidcUser implements SessionUser {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;

    TinfirmOidcUser(UUID userId, Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken,
            @Nullable OidcUserInfo userInfo) {
        super(authorities, idToken, userInfo, IdTokenClaimNames.SUB);
        this.userId = userId;
    }

    @Override
    public UUID userId() {
        return userId;
    }

    @Override
    public String getName() {
        return userId.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TinfirmOidcUser that && userId.equals(that.userId) && super.equals(other);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + userId.hashCode();
    }
}
