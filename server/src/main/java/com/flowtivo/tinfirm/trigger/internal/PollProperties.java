package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The polls' and schedules' settings ({@code tinfirm.trigger.*}, the package 4 contract, §5.4 and §5.10), beside
 * {@link TriggerProperties}' firing limits.
 *
 * @param pollTick      how often a {@code worker} replica looks for due polls (each at its system's interval)
 * @param scheduleTick  how often a {@code worker} replica looks for due schedules
 * @param settle        a subject changed more recently is left for the next pass, so that the transaction recording a
 *                      write of Tinfirm's has committed before the change is read; beyond the tool timeout
 * @param overlap       how far a window's bound reads back, for changes a system lists late
 * @param catchUp       a change older than this when first read updates the snapshot and fires nothing
 * @param missedAfter   a schedule's firing due longer ago than this was missed; its catch-up decides
 * @param lease         how long a pass holds its poll
 * @param maxPerPass    the most subjects one pass handles
 * @param unreachableAfter after failing this long, a poll's triggers wait {@code SYSTEM_UNREACHABLE}
 */
@ConfigurationProperties("tinfirm.trigger")
record PollProperties(@DefaultValue("PT15S") Duration pollTick, @DefaultValue("PT30S") Duration scheduleTick,
        @DefaultValue("PT90S") Duration settle, @DefaultValue("PT2M") Duration overlap,
        @DefaultValue("PT24H") Duration catchUp, @DefaultValue("PT1H") Duration missedAfter,
        @DefaultValue("PT10M") Duration lease, @DefaultValue("200") int maxPerPass,
        @DefaultValue("PT1H") Duration unreachableAfter) {

    PollProperties {
        if (maxPerPass < 1 || maxPerPass > 1000) {
            throw new IllegalArgumentException("tinfirm.trigger.max-per-pass must be 1 to 1000");
        }
        if (settle.isNegative() || overlap.isNegative()) {
            throw new IllegalArgumentException("tinfirm.trigger.settle and overlap must not be negative");
        }
    }
}
