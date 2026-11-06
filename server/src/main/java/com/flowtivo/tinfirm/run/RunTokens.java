package com.flowtivo.tinfirm.run;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * What a run's sandbox reported of its use (the totals of its {@code USAGE} events) and the model it was assigned
 * with (the package 6 contract, §3.1): only an estimate, shown on the run until its spend settles; it never sets a
 * cost. A subscription's run, neither relayed nor metered, keeps these tokens as its spend, without a cost.
 *
 * @param model          the settings' model at assignment; null for the runtime's default, and before assignment
 * @param relayRequests  what the model relay passed for the run, recorded at its end; null before
 */
public record RunTokens(@Nullable String model, long inputTokens, long outputTokens, long cachedInputTokens,
        long cacheWriteTokens, @Nullable Double estimatedCostUsd, @Nullable Long relayRequests, Instant createdAt,
        @Nullable Instant startedAt, @Nullable Instant finishedAt) {
}
