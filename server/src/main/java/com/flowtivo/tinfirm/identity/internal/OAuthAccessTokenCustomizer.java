package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * The claims of Tinfirm's access tokens (ADR-0045), after MCP Security's {@code ResourceIdentifierAudienceTokenCustomizer}
 * (which copies the token request's {@code resource} into {@code aud}):
 * <ul>
 * <li>{@code aud} is the {@code resource} of the authorization the person consented to, on the code exchange and on
 * every refresh; a token request that names another resource is refused with {@code invalid_target}, so a client
 * cannot turn a grant for company A into a token for company B;</li>
 * <li>a deactivated user gets no token: the code exchange and the refresh answer {@code invalid_grant};</li>
 * <li>{@code client_id} (RFC 9068) and {@code authorization_id}, by which the MCP endpoint finds the authorization and
 * refuses a token whose grant was revoked.</li>
 * </ul>
 * {@code sub} (the user id), {@code scope}, {@code iss}, {@code iat}, {@code exp} and {@code jti} are Spring's.
 */
final class OAuthAccessTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    static final String RESOURCE = "resource";

    static final String AUTHORIZATION_ID_CLAIM = "authorization_id";

    static final String CLIENT_ID_CLAIM = "client_id";

    private final OAuthResources resources;

    private final UserRepository users;

    OAuthAccessTokenCustomizer(OAuthResources resources, UserRepository users) {
        this.resources = resources;
        this.users = users;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        OAuth2Authorization authorization = context.getAuthorization();
        OAuth2AuthorizationRequest request = authorization != null
                ? authorization.getAttribute(OAuth2AuthorizationRequest.class.getName()) : null;
        Object bound = request != null ? request.getAdditionalParameters().get(RESOURCE) : null;
        if (authorization == null || !(bound instanceof String resource)
                || resources.companyOfResource(resource).isEmpty()) {
            throw invalidTarget();
        }
        if (context.getAuthorizationGrant() instanceof OAuth2AuthorizationGrantAuthenticationToken grant) {
            Object asked = grant.getAdditionalParameters().get(RESOURCE);
            if (asked != null && !resource.equals(asked)) {
                throw invalidTarget();
            }
        }
        if (!activeUser(context.getPrincipal().getName())) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                    "The user of this grant cannot sign in", null));
        }
        context.getClaims()
            // A string, as MCP Security writes it: Spring stores the claims as JSON that reads back only allow-listed
            // types, and an immutable List is none of them.
            .claim(JwtClaimNames.AUD, resource)
            .claim(CLIENT_ID_CLAIM, context.getRegisteredClient().getClientId())
            .claim(AUTHORIZATION_ID_CLAIM, authorization.getId());
    }

    private boolean activeUser(String principalName) {
        try {
            return users.findById(UUID.fromString(principalName)).filter(UserEntity::isActive).isPresent();
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static OAuth2AuthenticationException invalidTarget() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_target",
                "The resource must be the MCP endpoint of the company this grant was given for", null));
    }
}
