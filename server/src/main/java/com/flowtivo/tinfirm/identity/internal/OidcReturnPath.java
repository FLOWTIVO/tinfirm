package com.flowtivo.tinfirm.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Carries an OIDC login's return path ({@link ReturnPaths}) through the round trip, server-side: the start's checked
 * {@code returnTo} becomes an attribute of the authorization request, which Spring Security keeps in the session and
 * finds again only by the callback's {@code state}; the callback's removal of that request hands the path to the
 * success handler as a request attribute ({@link ReturnPaths#REQUEST_ATTRIBUTE}). Nothing the IdP sends is read for
 * it, a failed login never reads it, and the request is removed on the first callback either way.
 */
final class OidcReturnPath {

    /** The authorization request's attribute. */
    static final String ATTRIBUTE = "tinfirm_return_path";

    private OidcReturnPath() {
    }

    /** Adds the start's checked return path, if any, to the authorization requests the delegate resolves. */
    static OAuth2AuthorizationRequestResolver resolver(OAuth2AuthorizationRequestResolver delegate) {
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return withReturnPath(request, delegate.resolve(request));
            }

            @Override
            public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request,
                    String clientRegistrationId) {
                return withReturnPath(request, delegate.resolve(request, clientRegistrationId));
            }
        };
    }

    private static @Nullable OAuth2AuthorizationRequest withReturnPath(HttpServletRequest request,
            @Nullable OAuth2AuthorizationRequest authorizationRequest) {
        if (authorizationRequest == null) {
            return null;
        }
        String path = ReturnPaths.check(request.getParameter(ReturnPaths.PARAMETER));
        return OAuth2AuthorizationRequest.from(authorizationRequest).attributes(attributes -> {
            attributes.remove(ATTRIBUTE);
            if (path != null) {
                attributes.put(ATTRIBUTE, path);
            }
        }).build();
    }

    /** The session's repository, which hands the removed request's return path to the callback's request. */
    static AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository() {
        HttpSessionOAuth2AuthorizationRequestRepository delegate = new HttpSessionOAuth2AuthorizationRequestRepository();
        return new AuthorizationRequestRepository<>() {
            @Override
            public @Nullable OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
                return delegate.loadAuthorizationRequest(request);
            }

            @Override
            public void saveAuthorizationRequest(@Nullable OAuth2AuthorizationRequest authorizationRequest,
                    HttpServletRequest request, HttpServletResponse response) {
                delegate.saveAuthorizationRequest(authorizationRequest, request, response);
            }

            @Override
            public @Nullable OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                    HttpServletResponse response) {
                OAuth2AuthorizationRequest removed = delegate.removeAuthorizationRequest(request, response);
                if (removed != null && removed.getAttribute(ATTRIBUTE) instanceof String path) {
                    request.setAttribute(ReturnPaths.REQUEST_ATTRIBUTE, path);
                }
                return removed;
            }
        };
    }
}
