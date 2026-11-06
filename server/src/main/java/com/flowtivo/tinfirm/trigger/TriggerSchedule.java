package com.flowtivo.tinfirm.trigger;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A schedule's timing (the package 4 contract, §3.1, §5.10; ADR-0032).
 *
 * @param cron        five fields, at most every 15 minutes
 * @param zone        an IANA zone
 * @param overlap     {@code SKIP}: no firing while the previous one's conversation is busy; {@code CREATE}: one anyway
 * @param catchUp     {@code NONE}: a firing missed by more than {@code tinfirm.trigger.missed-after} is skipped;
 *                    {@code ONE}: one firing for any number missed
 * @param nextRunAt   the next due time; null while it is paused or deleted
 * @param lastRunAt   the last due time it handled
 * @param lastOutcome the last firing's outcome
 */
public record TriggerSchedule(String cron, String zone, Overlap overlap, CatchUp catchUp, @Nullable Instant nextRunAt,
        @Nullable Instant lastRunAt, @Nullable FiringOutcome lastOutcome) {

    public TriggerSchedule {
        Objects.requireNonNull(cron, "cron");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(overlap, "overlap");
        Objects.requireNonNull(catchUp, "catchUp");
    }

    public enum Overlap {
        SKIP, CREATE
    }

    public enum CatchUp {
        NONE, ONE
    }
}
