package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.flowtivo.tinfirm.shared.security.SessionWatch;

/**
 * An event stream ends with the HTTP session it was opened with (security review L1): when Spring Session no longer
 * finds it, and at its absolute lifetime by the rule of {@link SessionMaxAgeFilter}.
 */
class HttpSessionWatchesTest {

    private static final Duration MAX_AGE = Duration.ofDays(7);

    private final SessionRepository<Session> repository = repository();

    private Instant now = Instant.parse("2026-10-27T10:00:00Z");

    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private final HttpSessionWatches watches = new HttpSessionWatches(repository,
            new SessionLifetimeProperties(MAX_AGE), clock);

    @SuppressWarnings("unchecked")
    private static SessionRepository<Session> repository() {
        return mock(SessionRepository.class);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void lastsWhileTheSessionExistsAndEndsWhenTheStoreNoLongerHasIt() {
        when(repository.findById("s1")).thenReturn(new MapSession("s1"));
        SessionWatch watch = watches.watch("s1", now, null);
        assertThat(watch.ended()).isFalse();

        // Logout, a password change, the idle timeout or an administrator deleted it.
        when(repository.findById("s1")).thenReturn(null);
        assertThat(watch.ended()).isTrue();
    }

    @Test
    void endsAtTheAbsoluteLifetimeEvenWhileTheStoreStillHasTheSession() {
        when(repository.findById("s1")).thenReturn(new MapSession("s1"));
        SessionWatch watch = watches.watch("s1", now, null);

        now = now.plus(MAX_AGE).minusSeconds(1);
        assertThat(watch.ended()).isFalse();
        now = now.plusSeconds(2);
        assertThat(watch.ended()).isTrue();
    }

    @Test
    void endsAtASamlSessionsAssertionEndWhenThatComesFirst() {
        when(repository.findById("s1")).thenReturn(new MapSession("s1"));
        SessionWatch watch = watches.watch("s1", now, now.plus(Duration.ofHours(1)).toEpochMilli());

        now = now.plus(Duration.ofMinutes(59));
        assertThat(watch.ended()).isFalse();
        now = now.plus(Duration.ofMinutes(1));
        assertThat(watch.ended()).isTrue();
    }

    @Test
    void takesTheSessionOfTheRequestBeingHandled() {
        MockHttpSession session = new MockHttpSession(null, "s-request");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/conversations/x/stream");
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(repository.findById("s-request")).thenReturn(new MapSession("s-request"));

        SessionWatch watch = watches.open();
        assertThat(watch.ended()).isFalse();

        when(repository.findById("s-request")).thenReturn(null);
        assertThat(watch.ended()).isTrue();
    }

    @Test
    void watchesNothingForARequestWithoutASession() {
        assertThat(watches.open()).isSameAs(SessionWatch.NONE);

        RequestContextHolder
            .setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("GET", "/api/x")));
        assertThat(watches.open()).isSameAs(SessionWatch.NONE);
    }
}
