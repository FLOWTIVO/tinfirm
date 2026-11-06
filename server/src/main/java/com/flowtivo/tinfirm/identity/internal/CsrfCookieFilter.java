package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Renders the {@code XSRF-TOKEN} cookie whenever the browser does not have one yet (Spring Security loads CSRF
 * tokens lazily). {@code GET /api/auth/providers}, the first call of the SPA, is therefore guaranteed to set it.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfTokens.render(request);
        chain.doFilter(request, response);
    }
}
