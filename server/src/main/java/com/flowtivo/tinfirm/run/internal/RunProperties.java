package com.flowtivo.tinfirm.run.internal;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Run settings ({@code tinfirm.run.*}).
 *
 * @param maxWallClock    wall-clock limit of a run ({@code TINFIRM_RUN_MAX_WALL_CLOCK})
 * @param maxTurns        tool-call limit of a run ({@code TINFIRM_RUN_MAX_TURNS})
 * @param acceptTimeout   an assigned run without {@code run.accepted} goes back to the queue after this
 * @param prepareTimeout  how long the assignment of a run may take to prepare its {@code run.assign} (the turn's
 *                        content, its git tokens, the relay's token) before the accept timeout may take it back
 * @param rejectBackoff   a rejected run is not dispatched again before this
 * @param maxEventsPerRun event cap per run; LOG events are dropped first
 * @param streamTimeout   lifetime of one SSE stream; the client reconnects with {@code Last-Event-ID}
 * @param streamKeepAlive an idle SSE stream gets a comment after this, so proxies keep it open
 * @param toolTimeout     how long a server-executed tool call of a run may take ({@code TINFIRM_RUN_TOOL_TIMEOUT})
 *                        before it is answered {@code TOOL_INTERRUPTED} (cause {@code TIMEOUT}), or {@code TOOL_FAILED}
 *                        for a {@code SAFE} tool, which a request sent again may run (CONVENTIONS §15.14); the run
 *                        module's router answers {@code TOOL_INTERRUPTED} past this plus 2 seconds, and the runner host
 *                        waits this plus 5 seconds
 */
@Validated
@ConfigurationProperties("tinfirm.run")
record RunProperties(@NotNull @DefaultValue("PT60M") Duration maxWallClock, @Min(1) @DefaultValue("200") int maxTurns,
        @NotNull @DefaultValue("PT10S") Duration acceptTimeout, @NotNull @DefaultValue("PT2M") Duration prepareTimeout,
        @NotNull @DefaultValue("PT10S") Duration rejectBackoff,
        @Min(100) @DefaultValue("20000") int maxEventsPerRun, @NotNull @DefaultValue("PT30M") Duration streamTimeout,
        @NotNull @DefaultValue("PT15S") Duration streamKeepAlive,
        @NotNull @DefaultValue("PT60S") Duration toolTimeout) {

    RunProperties {
        if (maxWallClock.toSeconds() < 60) {
            throw new IllegalArgumentException("tinfirm.run.max-wall-clock must be at least 60 seconds");
        }
        if (streamKeepAlive.toMillis() < 100) {
            throw new IllegalArgumentException("tinfirm.run.stream-keep-alive must be at least 100 milliseconds");
        }
        if (prepareTimeout.toSeconds() < 1) {
            throw new IllegalArgumentException("tinfirm.run.prepare-timeout must be at least 1 second");
        }
        if (toolTimeout.toMillis() < 100 || toolTimeout.toSeconds() > 60) {
            throw new IllegalArgumentException("tinfirm.run.tool-timeout must be between 100 milliseconds and 60 "
                    + "seconds: the runner host waits 65 seconds for a tool's answer");
        }
    }
}
