package com.flowtivo.tinfirm.shared.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The first filter inside Spring Session's: marks a request whose chain returned normally, the handler's answer
 * written whole. Spring Session's filter commits the session once more after it ({@link RequestInputFilter} lets a
 * failure of that commit for want of a connection pass once the answer is committed); a request whose chain threw is
 * not marked.
 */
final class SessionAnswered extends OncePerRequestFilter {

    /** The request attribute of a request whose chain inside Spring Session's filter returned normally. */
    static final String ANSWERED = SessionAnswered.class.getName() + ".answered";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        request.setAttribute(ANSWERED, Boolean.TRUE);
    }
}
