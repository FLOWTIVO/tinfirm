package com.flowtivo.tinfirm.system.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** One read of a server's catalogue per system and minute (the security review of 2026-10-27, M9). */
class RefreshLimiterTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void oneReadPerSystemAndMinute() {
        RefreshLimiter limiter = new RefreshLimiter(Duration.ofMinutes(1));
        UUID crm = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertThat(limiter.admit(crm, NOW)).isEmpty();
        assertThat(limiter.admit(crm, NOW.plusSeconds(20))).contains(Duration.ofSeconds(40));
        // Another system has its own minute; a refused read does not move the first one's.
        assertThat(limiter.admit(other, NOW.plusSeconds(20))).isEmpty();
        assertThat(limiter.admit(crm, NOW.plusSeconds(59))).contains(Duration.ofSeconds(1));
        assertThat(limiter.admit(crm, NOW.plusSeconds(60))).isEmpty();
    }

    @Test
    void aZeroIntervalAdmitsEveryRead() {
        RefreshLimiter limiter = new RefreshLimiter(Duration.ZERO);
        UUID crm = UUID.randomUUID();

        assertThat(limiter.admit(crm, NOW)).isEmpty();
        assertThat(limiter.admit(crm, NOW)).isEmpty();
    }
}
