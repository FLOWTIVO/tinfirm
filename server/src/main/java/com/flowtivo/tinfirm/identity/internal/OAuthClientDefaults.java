package com.flowtivo.tinfirm.identity.internal;

import java.time.Duration;
import java.util.Collection;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * What every client of Tinfirm's authorization server gets, whatever its metadata document asks for (ADR-0045): a
 * public client ({@code none}) with PKCE, the authorization code and refresh token grants only, the three
 * {@code tinfirm:*} scopes, consent on every authorization, JWT access tokens of 1 hour and refresh tokens of 30 days
 * that rotate on every use.
 */
final class OAuthClientDefaults {

    static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);

    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    static final Duration AUTHORIZATION_CODE_TTL = Duration.ofMinutes(5);

    private OAuthClientDefaults() {
    }

    static RegisteredClient.Builder publicClient(String id, String clientId, String name,
            Collection<String> redirectUris) {
        return RegisteredClient.withId(id)
            .clientId(clientId)
            .clientName(name)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUris(uris -> uris.addAll(redirectUris))
            .scopes(scopes -> scopes.addAll(OAuthScopes.ALL))
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
            .tokenSettings(TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                .refreshTokenTimeToLive(REFRESH_TOKEN_TTL)
                .reuseRefreshTokens(false)
                .authorizationCodeTimeToLive(AUTHORIZATION_CODE_TTL)
                .build());
    }
}
