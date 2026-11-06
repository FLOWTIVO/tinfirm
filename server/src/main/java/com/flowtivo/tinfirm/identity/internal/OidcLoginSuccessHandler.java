package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * Where an OIDC login ends (CONVENTIONS §5, ADR-0008 as ADR-0045 changes it): on {@code /}, except when the login
 * interrupted an OAuth authorization request. Then the authorization server's chain saved that request in the
 * session's request cache, and the person returns to it. Only a same-origin path of the authorization endpoint is
 * honoured; anything else in the cache is ignored and removed.
 */
final class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    static final String AUTHORIZATION_ENDPOINT = "/oauth2/authorize";

    private final String publicBaseUrl;

    private final RequestCache requestCache;

    OidcLoginSuccessHandler(String publicBaseUrl, RequestCache requestCache) {
        this.publicBaseUrl = publicBaseUrl;
        this.requestCache = requestCache;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        SavedRequest saved = requestCache.getRequest(request, response);
        String target = saved != null ? authorizationRequest(saved.getRedirectUrl()) : null;
        if (saved != null) {
            requestCache.removeRequest(request, response);
        }
        response.sendRedirect(publicBaseUrl + (target != null ? target : "/"));
    }

    /** The path and query of a saved authorization request, or null for anything else. */
    static @Nullable String authorizationRequest(@Nullable String savedUrl) {
        if (savedUrl == null) {
            return null;
        }
        try {
            URI uri = URI.create(savedUrl);
            String path = uri.getRawPath();
            if (!AUTHORIZATION_ENDPOINT.equals(path)) {
                return null;
            }
            return uri.getRawQuery() != null ? path + "?" + uri.getRawQuery() : path;
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
