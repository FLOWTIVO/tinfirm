package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the absolute session lifetime ({@code TINFIRM_SESSION_MAX_AGE}) on every request: a session older than
 * that, counted from its creation time, is deleted before the security context is loaded from it. A SAML session also
 * ends at its assertion's {@code SessionNotOnOrAfter} when that comes first (ADR-0086, decision 4).
 */
final class SessionMaxAgeFilter extends OncePerRequestFilter {

    /** The session attribute that holds a SAML session's end, in epoch milliseconds. */
    static final String NOT_ON_OR_AFTER = "tinfirm.session.notOnOrAfter";

    private final Duration maxAge;

    private final Clock clock;

    SessionMaxAgeFilter(Duration maxAge, Clock clock) {
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && ended(session, clock.instant())) {
            session.invalidate();
        }
        chain.doFilter(request, response);
    }

    private boolean ended(HttpSession session, Instant now) {
        return ended(Instant.ofEpochMilli(session.getCreationTime()), maxAge, session.getAttribute(NOT_ON_OR_AFTER), now);
    }

    /**
     * The rule: a session ends {@code maxAge} after it was created, or at its assertion's end when that is a
     * {@link #NOT_ON_OR_AFTER} attribute and comes first. The event streams ({@link HttpSessionWatches}) apply it to the
     * session they were opened with.
     */
    static boolean ended(Instant created, Duration maxAge, Object notOnOrAfter, Instant now) {
        if (created.plus(maxAge).isBefore(now)) {
            return true;
        }
        return notOnOrAfter instanceof Long end && !now.isBefore(Instant.ofEpochMilli(end));
    }
}
