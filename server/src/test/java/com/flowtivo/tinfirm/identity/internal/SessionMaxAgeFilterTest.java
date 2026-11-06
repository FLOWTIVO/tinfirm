package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class SessionMaxAgeFilterTest {

    @Test
    void invalidatesSessionsOlderThanTheAbsoluteLifetime() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Instant created = Instant.ofEpochMilli(session.getCreationTime());

        run(session, Clock.fixed(created.plus(Duration.ofDays(7)).minusSeconds(1), ZoneOffset.UTC));
        assertThat(session.isInvalid()).isFalse();

        run(session, Clock.fixed(created.plus(Duration.ofDays(7)).plusSeconds(1), ZoneOffset.UTC));
        assertThat(session.isInvalid()).isTrue();
    }

    private static void run(MockHttpSession session, Clock clock) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setSession(session);
        MockFilterChain chain = new MockFilterChain();
        new SessionMaxAgeFilter(Duration.ofDays(7), clock).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
