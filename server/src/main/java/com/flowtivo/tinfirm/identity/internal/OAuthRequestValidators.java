package com.flowtivo.tinfirm.identity.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.mcp.security.authorizationserver.config.LocalhostWildcardPortValidator;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationContext;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The checks of an authorization request and of a consent (ADR-0045), beyond Spring's grant type, PKCE and prompt
 * checks:
 * <ol>
 * <li>the redirect URI: exact, except that a loopback URI ({@code localhost}, {@code 127.0.0.1}, {@code [::1]}) of
 * a registered one matches with any port (MCP Security's {@link LocalhostWildcardPortValidator}, extended to the IP
 * literals of RFC 8252);</li>
 * <li>the scopes: at least one, all of them {@code tinfirm:*} (Spring's scope check against the client's scopes,
 * which are always the three). A request that names one of the three arrives here as a request for all three
 * ({@link ScopeWideningRequestConverter}); any other scope it names is still refused;</li>
 * <li>the {@code resource}: exactly the canonical URI of a company's MCP endpoint, else {@code invalid_target};</li>
 * <li>once the person is signed in, that they may act in that company (a member, or an instance admin with the
 * audited break-glass), else {@code access_denied}, which names no company.</li>
 * </ol>
 * The consent repeats the last check.
 */
final class OAuthRequestValidators {

    private OAuthRequestValidators() {
    }

    static Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> authorizationRequest(OAuthResources resources,
            OAuthCompanyAccess access) {
        Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> redirectUri = new LoopbackRedirectUri();
        return redirectUri.andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR)
            .andThen(context -> validateResource(context, resources, access));
    }

    private static void validateResource(OAuth2AuthorizationCodeRequestAuthenticationContext context,
            OAuthResources resources, OAuthCompanyAccess access) {
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        if (request.getScopes().isEmpty()) {
            throw error(OAuth2ErrorCodes.INVALID_SCOPE,
                    "Ask for one of the scopes tinfirm:read, tinfirm:write, tinfirm:decide", request);
        }
        Object resource = request.getAdditionalParameters().get(OAuthAccessTokenCustomizer.RESOURCE);
        Optional<UUID> company = resource instanceof String value ? resources.companyOfResource(value)
                : Optional.empty();
        if (company.isEmpty()) {
            throw error("invalid_target",
                    "The resource must be the MCP endpoint of a company, " + resources.issuer()
                            + "/api/companies/{companyId}/mcp",
                    request);
        }
        Authentication principal = (Authentication) request.getPrincipal();
        if (signedIn(principal) && !access.mayAuthorize(principal.getName(), company.get())) {
            throw error(OAuth2ErrorCodes.ACCESS_DENIED, "You cannot authorize access to this resource", request);
        }
    }

    /** The consent's own check: the person may still act in the company of the authorization. */
    static Consumer<OAuth2AuthorizationConsentAuthenticationContext> consent(OAuthResources resources,
            OAuthCompanyAccess access) {
        return context -> {
            OAuth2AuthorizationRequest request = context.getAuthorizationRequest();
            Object resource = request.getAdditionalParameters().get(OAuthAccessTokenCustomizer.RESOURCE);
            Optional<UUID> company = resource instanceof String value ? resources.companyOfResource(value)
                    : Optional.empty();
            String principal = context.getAuthorization().getPrincipalName();
            if (company.isEmpty() || !access.mayAuthorize(principal, company.get())) {
                OAuth2AuthorizationCodeRequestAuthenticationToken response =
                        new OAuth2AuthorizationCodeRequestAuthenticationToken(request.getAuthorizationUri(),
                                request.getClientId(), (Authentication) context.getAuthentication().getPrincipal(),
                                request.getRedirectUri(), request.getState(), request.getScopes(), null);
                throw error(OAuth2ErrorCodes.ACCESS_DENIED, "You cannot authorize access to this resource",
                        response);
            }
        };
    }

    private static boolean signedIn(@Nullable Authentication principal) {
        return principal != null && principal.isAuthenticated() && !(principal instanceof AnonymousAuthenticationToken);
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationException error(String code, String description,
            OAuth2AuthorizationCodeRequestAuthenticationToken request) {
        return new OAuth2AuthorizationCodeRequestAuthenticationException(new OAuth2Error(code, description, null),
                request);
    }

    /**
     * Loopback redirect URIs with any port (OAuth 2.1 §8.4.2, RFC 8252 §7.3): MCP Security's validator for
     * {@code localhost}, and the same rule here for {@code 127.0.0.1} and {@code [::1]}; everything else exactly.
     */
    static final class LoopbackRedirectUri implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

        private static final Set<String> LOOPBACK_IPS = Set.of("127.0.0.1", "[::1]", "::1");

        private final LocalhostWildcardPortValidator localhost = new LocalhostWildcardPortValidator();

        @Override
        public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
            OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
            UriComponents requested = loopbackIp(request.getRedirectUri());
            if (requested != null && context.getRegisteredClient()
                .getRedirectUris()
                .stream()
                .anyMatch(registered -> matchesExceptPort(requested, registered))) {
                return;
            }
            localhost.accept(context);
        }

        private static @Nullable UriComponents loopbackIp(@Nullable String redirectUri) {
            if (redirectUri == null) {
                return null;
            }
            try {
                UriComponents uri = UriComponentsBuilder.fromUriString(redirectUri).build();
                return "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                        && LOOPBACK_IPS.contains(uri.getHost()) ? uri : null;
            }
            catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private static boolean matchesExceptPort(UriComponents requested, String registered) {
            try {
                return UriComponentsBuilder.fromUriString(registered)
                    .port(requested.getPort())
                    .build()
                    .toUriString()
                    .equals(requested.toUriString());
            }
            catch (IllegalArgumentException ex) {
                return false;
            }
        }
    }
}
