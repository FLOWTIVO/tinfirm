package com.flowtivo.tinfirm.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

/**
 * The current time, truncated to microseconds: the precision of PostgreSQL {@code timestamptz}.
 * Truncating up front keeps in-memory values equal to what is stored, which the audit hash chain relies on.
 */
@Component
public class TimeSource {

    private final Clock clock;

    public TimeSource(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
