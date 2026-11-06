package com.flowtivo.tinfirm.shared.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;

/**
 * The security headers of every response (CONVENTIONS §5), written by every filter chain: the session chain, the
 * authorization server's, the MCP endpoint's, and the chains of the endpoints that carry no session (webhooks, chats,
 * the Gmail add-on, SCIM, the runner transport). Spring Security's defaults stay on ({@code X-Content-Type-Options:
 * nosniff}, {@code X-Frame-Options: DENY}, {@code Cache-Control}); this adds the strict CSP, the referrer policy and
 * HSTS when the public URL is https.
 */
public final class SecurityHeaders {

    /** The strict CSP of every page but the Outlook add-in's (ADR-0074). */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'";

    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000;

    private SecurityHeaders() {
    }

    /** The headers of a chain: the strict CSP and {@link #common}. */
    public static void apply(HeadersConfigurer<HttpSecurity> headers, TinfirmProperties tinfirm) {
        headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY));
        common(headers, tinfirm);
    }

    /**
     * The headers that do not depend on the page: the referrer policy, and HSTS when the public URL is https (TLS
     * usually terminates at the ingress, so it is sent whenever the public URL is https).
     */
    public static void common(HeadersConfigurer<HttpSecurity> headers, TinfirmProperties tinfirm) {
        headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
        if (tinfirm.secureCookies()) {
            headers.httpStrictTransportSecurity(hsts -> hsts.requestMatcher(AnyRequestMatcher.INSTANCE)
                .includeSubDomains(true)
                .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS));
        }
        else {
            headers.httpStrictTransportSecurity(hsts -> hsts.disable());
        }
    }
}
