package com.flowtivo.tinfirm.mcp.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * The rate limit of the MCP endpoint (ADR-0045; threat model 10.9): a token bucket per credential, the bearer
 * grant's id (a personal access token's or an OAuth grant's), else the user's. A bucket holds
 * {@code perMinute} requests and refills them greedily over a minute, so a client may send a burst of that many and
 * then one request every {@code 60 / perMinute} seconds. Every POST to the endpoint counts, whatever its JSON-RPC method.
 * <p>
 * In memory and per replica, as the login throttle is: behind N replicas a credential gets up to N times the limit.
 * The buckets live in a bounded cache; one untouched for a minute is full again, so dropping it loses nothing.
 */
final class McpRateLimiter {

    /** The most credentials tracked at once; the least recently used bucket is dropped beyond it. */
    static final long MAX_TRACKED_CREDENTIALS = 100_000;

    private static final Duration PERIOD = Duration.ofMinutes(1);

    private final int perMinute;

    private final Cache<UUID, Bucket> buckets;

    McpRateLimiter(int perMinute) {
        if (perMinute < 0) {
            throw new IllegalArgumentException("The MCP rate limit must be 0 (off) or more");
        }
        this.perMinute = perMinute;
        this.buckets = Caffeine.newBuilder().maximumSize(MAX_TRACKED_CREDENTIALS).expireAfterAccess(PERIOD).build();
    }

    /** A limiter that admits everything. */
    static McpRateLimiter off() {
        return new McpRateLimiter(0);
    }

    boolean enabled() {
        return perMinute > 0;
    }

    /**
     * Takes one request of the credential's bucket: empty when admitted, else how long until the next request would
     * be.
     */
    Optional<Duration> admit(UUID credential) {
        if (!enabled()) {
            return Optional.empty();
        }
        ConsumptionProbe probe = buckets.get(credential, key -> bucket()).tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private Bucket bucket() {
        return Bucket.builder()
            .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, PERIOD))
            .build();
    }
}
