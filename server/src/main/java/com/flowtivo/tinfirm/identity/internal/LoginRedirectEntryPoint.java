package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.util.UriUtils;

/**
 * Sends a browser without a session from the authorization endpoint to the web app's login, with the request in
 * {@code returnTo} (ADR-0045): {@code /login?returnTo=%2Foauth2%2Fauthorize%3F...}. After a local login the SPA
 * navigates there; after an OIDC login the server does ({@link OidcLoginSuccessHandler}, from the request cache).
 */
final class LoginRedirectEntryPoint implements AuthenticationEntryPoint {

    private final String publicBaseUrl;

    LoginRedirectEntryPoint(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String returnTo = request.getQueryString() != null ? path + "?" + request.getQueryString() : path;
        response.sendRedirect(publicBaseUrl + "/login?returnTo=" + UriUtils.encode(returnTo, StandardCharsets.UTF_8));
    }
}
