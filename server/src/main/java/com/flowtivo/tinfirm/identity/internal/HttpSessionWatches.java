package com.flowtivo.tinfirm.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpSession;

import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.flowtivo.tinfirm.shared.security.SessionWatch;
import com.flowtivo.tinfirm.shared.security.SessionWatches;

/**
 * Watches the HTTP session of the request that opens an event stream: its id and its absolute end, by the rule of
 * {@link SessionMaxAgeFilter}, are captured at the opening; the session ends the stream when Spring Session no longer
 * finds it (logout, a password change, the idle timeout, an administrator) or the absolute lifetime has passed.
 * {@code findById} reads the session without touching it, so watching never keeps a session alive.
 */
@Component
class HttpSessionWatches implements SessionWatches {

    private final SessionRepository<? extends Session> sessions;

    private final Duration maxAge;

    private final Clock clock;

    HttpSessionWatches(SessionRepository<? extends Session> sessions, SessionLifetimeProperties lifetime, Clock clock) {
        this.sessions = sessions;
        this.maxAge = lifetime.maxAge();
        this.clock = clock;
    }

    @Override
    public SessionWatch open() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return SessionWatch.NONE;
        }
        HttpSession session = attributes.getRequest().getSession(false);
        if (session == null) {
            return SessionWatch.NONE;
        }
        return watch(session.getId(), Instant.ofEpochMilli(session.getCreationTime()),
                session.getAttribute(SessionMaxAgeFilter.NOT_ON_OR_AFTER));
    }

    SessionWatch watch(String sessionId, Instant created, Object notOnOrAfter) {
        return () -> SessionMaxAgeFilter.ended(created, maxAge, notOnOrAfter, clock.instant())
                || sessions.findById(sessionId) == null;
    }
}
