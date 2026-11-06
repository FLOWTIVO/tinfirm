package com.flowtivo.tinfirm.identity.internal;

import java.io.Serial;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** An OIDC login that Tinfirm refused after the IdP authenticated the user. Already audited. */
final class OidcLoginDeniedException extends OAuth2AuthenticationException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Problem code of an OIDC login whose email belongs to another user (CONVENTIONS §6). */
    static final String EMAIL_IN_USE = "EMAIL_IN_USE";

    /**
     * Code of a first OIDC login without a usable email: none of the registration's email claims holds a value that
     * looks like an email address (CONVENTIONS §5, §6).
     */
    static final String EMAIL_MISSING = "EMAIL_MISSING";

    /**
     * Code of a first OIDC login whose email the registration's email trust does not accept: no
     * {@code email_verified=true} under {@code REQUIRE_VERIFIED_CLAIM}, or an explicit {@code email_verified=false}
     * (CONVENTIONS §5, §6). Most often a registration for Entra ID without {@code TRUST_ISSUER}.
     */
    static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";

    /**
     * Code of a first login through a provider that lists email domains
     * ({@code TINFIRM_AUTH_OIDC_PROVIDERS_<ID>_EMAIL_DOMAINS}, ADR-0075) with an email in none of them: such a provider
     * creates users of those domains only.
     */
    static final String EMAIL_DOMAIN_NOT_ALLOWED = "EMAIL_DOMAIN_NOT_ALLOWED";

    /**
     * Code of a sign-in through a provider that decides roles (ADR-0088) whose role claim maps to no role: missing, or
     * none of its values listed in {@code _ROLE_MAP_*}. A known user's membership and content are kept.
     */
    static final String ROLE_NOT_MAPPED = "ROLE_NOT_MAPPED";

    /** Generic code for every other refused OIDC login. */
    static final String OIDC_LOGIN_FAILED = "OIDC_LOGIN_FAILED";

    OidcLoginDeniedException(String code, String reason) {
        super(new OAuth2Error(code), "OIDC login denied: " + reason);
    }
}
