package com.flowtivo.tinfirm.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Refresh tokens for public clients (ADR-0045). Spring's {@code OAuth2RefreshTokenGenerator} issues none to a public
 * client; an MCP client is always one, and OAuth 2.1 allows it refresh tokens that rotate on every use, which every
 * Tinfirm client has ({@code reuseRefreshTokens(false)}). 96 random bytes, base64url, with the client's refresh
 * token lifetime.
 */
final class PublicClientRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private final StringKeyGenerator keys = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

    private final Clock clock;

    PublicClientRefreshTokenGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public @Nullable OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())
                || !context.getRegisteredClient().getAuthorizationGrantTypes()
                    .contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            return null;
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        return new OAuth2RefreshToken(keys.generateKey(), issuedAt, expiresAt);
    }
}
