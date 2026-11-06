package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The write-back's delivery ({@code tinfirm.trigger.outbox.*}, the package 4 contract, §5.9). The tick itself
 * ({@code tinfirm.trigger.outbox.tick}, {@code PT15S}, the poll tick's) is read by the schedule only.
 *
 * @param deliverAfterCommit whether a transaction that queued rows starts their delivery after its commit, on a thread
 *                           of its own; else the tick delivers them
 * @param claim              how long a delivery holds a row it claimed
 * @param batch              the due rows one round claims, each the oldest undelivered row of its conversation
 * @param rounds             rounds per pass while they make progress
 * @param maxAttempts        attempts before a row that keeps failing is dropped (at most 10, {@code outbox_attempts_ck})
 * @param firstRetry         the wait after a first failure, doubling after each
 * @param lastRetry          the longest wait
 * @param bindingWait        how long a row waits for its binding before it is dropped
 */
@Validated
@ConfigurationProperties("tinfirm.trigger.outbox")
record OutboxProperties(@DefaultValue("true") boolean deliverAfterCommit, @DefaultValue("PT5M") Duration claim,
        @Min(1) @Max(500) @DefaultValue("20") int batch, @Min(1) @Max(100) @DefaultValue("10") int rounds,
        @Min(1) @Max(10) @DefaultValue("10") int maxAttempts, @DefaultValue("PT1M") Duration firstRetry,
        @DefaultValue("PT1H") Duration lastRetry, @DefaultValue("P7D") Duration bindingWait) {

    OutboxProperties {
        if (claim.isNegative() || claim.isZero() || firstRetry.isNegative() || firstRetry.isZero()
                || lastRetry.compareTo(firstRetry) < 0 || bindingWait.isNegative() || bindingWait.isZero()) {
            throw new IllegalArgumentException("tinfirm.trigger.outbox: the claim, the retries and the binding's wait "
                    + "are positive, and the last retry at least the first");
        }
    }

    /** The wait after the {@code attempts}-th failure: the first retry, doubling, at most the last. */
    Duration retryDelay(int attempts) {
        Duration delay = firstRetry.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 20));
        return delay.compareTo(lastRetry) > 0 ? lastRetry : delay;
    }
}
