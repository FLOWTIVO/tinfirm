package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Sends a failed OIDC login back to the SPA: {@code /login?error=EMAIL_IN_USE} when the email belongs to another
 * user, {@code /login?error=EMAIL_MISSING} when the claims hold no usable email for a new user,
 * {@code /login?error=EMAIL_NOT_VERIFIED} when the registration's email trust does not accept that email,
 * {@code /login?error=EMAIL_DOMAIN_NOT_ALLOWED} when a provider that lists email domains sent an email outside them,
 * {@code /login?error=ROLE_NOT_MAPPED} when a provider that decides roles maps the person to none (ADR-0088),
 * {@code /login?error=OIDC_LOGIN_FAILED} otherwise. Provisioning has already audited its refusals with their reason
 * ({@code email_in_use}, {@code email_missing}, {@code email_not_verified}, {@code email_domain_not_allowed},
 * ...); failures raised by the IdP flow itself are audited here.
 */
final class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Pattern REGISTRATION_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private static final String CALLBACK_PREFIX = "/login/oauth2/code/";

    private final String loginUrl;

    private final LoginAudit loginAudit;

    OidcLoginFailureHandler(String publicBaseUrl, LoginAudit loginAudit) {
        this.loginUrl = publicBaseUrl + "/login?error=";
        this.loginAudit = loginAudit;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String code;
        if (exception instanceof OidcLoginDeniedException denied) {
            code = denied.getError().getErrorCode();
        }
        else {
            code = OidcLoginDeniedException.OIDC_LOGIN_FAILED;
            loginAudit.failed(null, "oidc_error", provider(request));
        }
        response.sendRedirect(loginUrl + code);
    }

    private static @Nullable String provider(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(CALLBACK_PREFIX)) {
            return null;
        }
        String id = uri.substring(CALLBACK_PREFIX.length());
        return REGISTRATION_ID.matcher(id).matches() ? id : null;
    }
}
