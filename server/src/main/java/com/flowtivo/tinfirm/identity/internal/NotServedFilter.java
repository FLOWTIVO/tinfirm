package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Answers 404 for the authorization server's endpoints Tinfirm does not serve (ADR-0045), whoever asks and with
 * whatever method: open dynamic client registration ({@code /oauth2/register}) and OpenID Connect discovery. Without
 * it, a browser without a session would be sent to the login, and one with a session would get 405 from the SPA.
 */
final class NotServedFilter extends OncePerRequestFilter {

    private final RequestMatcher notServed;

    private final ProblemWriter problems;

    NotServedFilter(RequestMatcher notServed, ProblemWriter problems) {
        this.notServed = notServed;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (notServed.matches(request)) {
            problems.write(request, response, ApiException.notFound("Not served on this server"));
            return;
        }
        chain.doFilter(request, response);
    }
}
