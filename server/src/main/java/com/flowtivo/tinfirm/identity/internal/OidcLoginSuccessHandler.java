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
 * Where an OIDC or SAML sign-in ends (CONVENTIONS §5, ADR-0008 as ADR-0045 changes it, threat model 3.4):
 * <ol>
 * <li>on the OAuth authorization request the login interrupted: the authorization server's chain saved it in the
 * session's request cache. Only a same-origin path of the authorization endpoint is honoured; anything else in the
 * cache is ignored and removed;</li>
 * <li>else on the page the sign-in's start named, kept server-side with that login's saved request and handed to this
 * request as {@link ReturnPaths#REQUEST_ATTRIBUTE} ({@link OidcReturnPath}, the SAML AuthnRequest's row), checked
 * again here;</li>
 * <li>else on {@code /}.</li>
 * </ol>
 * Only a successful sign-in comes here: a failure handler never reads the return path.
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
        if (target == null && request.getAttribute(ReturnPaths.REQUEST_ATTRIBUTE) instanceof String page) {
            target = ReturnPaths.check(page);
        }
        request.removeAttribute(ReturnPaths.REQUEST_ATTRIBUTE);
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
