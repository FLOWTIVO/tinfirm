package com.flowtivo.tinfirm.system.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * At most one read of an MCP server's catalogue per system and interval (the security review of 2026-10-27, M9):
 * each read costs a session with the server, up to 10 pages of 1 MiB and a transaction over the catalogue's rows. In
 * memory and per replica, as the other limiters are; a zero interval admits every read.
 */
final class RefreshLimiter {

    /** The most systems tracked at once; the least recently read one is dropped beyond it. */
    static final long MAX_TRACKED_SYSTEMS = 100_000;

    private final Duration interval;

    private final Cache<UUID, Instant> lastRead;

    RefreshLimiter(Duration interval) {
        if (interval.isNegative()) {
            throw new IllegalArgumentException("The catalogue refresh interval is zero (off) or more");
        }
        this.interval = interval;
        this.lastRead = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_SYSTEMS)
            .expireAfterWrite(interval.isZero() ? Duration.ofSeconds(1) : interval)
            .build();
    }

    /** Takes the system's read now: empty when admitted, else how long until the next one would be. */
    Optional<Duration> admit(UUID systemId, Instant now) {
        if (interval.isZero()) {
            return Optional.empty();
        }
        AtomicReference<Duration> wait = new AtomicReference<>();
        lastRead.asMap().compute(systemId, (id, previous) -> {
            if (previous != null && previous.plus(interval).isAfter(now)) {
                wait.set(Duration.between(now, previous.plus(interval)));
                return previous;
            }
            return now;
        });
        return Optional.ofNullable(wait.get());
    }
}
