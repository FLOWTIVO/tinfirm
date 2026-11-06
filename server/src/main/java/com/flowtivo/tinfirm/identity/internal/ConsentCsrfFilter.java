package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * CSRF protection of the consent (ADR-0045): {@code POST /oauth2/authorize} needs the {@code X-XSRF-TOKEN} header
 * equal to the {@code XSRF-TOKEN} cookie of the web app's session, as every mutating request of the web app does
 * (CONVENTIONS §5). Spring's authorization server exempts its endpoints from CSRF, the consent included, and relies on
 * the consent's state alone; the consent page is Tinfirm's own, so it keeps the same protection as the rest.
 */
final class ConsentCsrfFilter extends OncePerRequestFilter {

    static final String HEADER = "X-XSRF-TOKEN";

    private final RequestMatcher consent;

    private final CsrfTokenRepository tokens;

    private final ProblemWriter problems;

    ConsentCsrfFilter(RequestMatcher consent, CsrfTokenRepository tokens, ProblemWriter problems) {
        this.consent = consent;
        this.tokens = tokens;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (consent.matches(request)) {
            CsrfToken expected = tokens.loadToken(request);
            String actual = request.getHeader(HEADER);
            if (expected == null || actual == null || !MessageDigest.isEqual(
                    expected.getToken().getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
                problems.write(request, response, ApiException.forbidden("The CSRF token is missing or wrong"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
