package com.flowtivo.tinfirm.run.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.run.PlacementNote;
import com.flowtivo.tinfirm.runner.IsolationLevel;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Run metrics on the management port ({@code /actuator/prometheus}). {@code tinfirm_runs_finished_total} counts the
 * runs a runner host finished, by whether they resumed their agent's session of the previous turn, why not, and
 * runtime; {@code tinfirm_run_late_events_total} the events a runner host sent for a run that had ended, which are
 * dropped; {@code tinfirm_runner_refused_frames_total} the {@code run.event} and {@code run.request} frames the flood
 * controls refused or dropped, by frame and reason ({@code rate}, {@code size}, {@code budget}, {@code in_flight});
 * {@code tinfirm_runs_assigned_total} the runs the dispatcher assigned, by the level of isolation of their host,
 * whether the level was chosen for the agent or automatic, and the placement's note (ADR-0113, decision 4).
 */
@Component
class RunMetrics {

    static final String RUNS_FINISHED = "tinfirm.runs.finished";

    static final String RUNS_ASSIGNED = "tinfirm.runs.assigned";

    static final String LATE_EVENTS = "tinfirm.run.late.events";

    static final String REFUSED_FRAMES = "tinfirm.runner.refused.frames";

    /** The frames and the reasons of {@link #refused}. */
    static final String EVENT = "event";

    static final String REQUEST = "request";

    static final String RATE = "rate";

    static final String SIZE = "size";

    static final String BUDGET = "budget";

    static final String IN_FLIGHT = "in_flight";

    private final MeterRegistry registry;

    private final Counter lateEvents;

    RunMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.lateEvents = Counter.builder(LATE_EVENTS)
            .description("Events a runner host sent for a run that had ended; they are dropped")
            .register(registry);
    }

    /** Counts an event of a run that had ended, which was dropped. */
    void lateEvent() {
        lateEvents.increment();
    }

    /** Counts a frame of a runner host that the flood controls refused or dropped. */
    void refused(String frame, String reason) {
        Counter.builder(REFUSED_FRAMES)
            .description("run.event and run.request frames of runner hosts refused or dropped by the flood controls")
            .tag("frame", frame)
            .tag("reason", reason)
            .register(registry)
            .increment();
    }

    /**
     * Counts a run the dispatcher assigned, once the transaction commits: tags {@code isolation_level} (the level its
     * host was placed at), {@code isolation} ({@code automatic} or {@code chosen}) and {@code placement_note}
     * ({@code none}, or the note's name).
     */
    void runAssigned(IsolationLevel level, boolean chosen, @Nullable PlacementNote note) {
        Counter counter = Counter.builder(RUNS_ASSIGNED)
            .description("Runs the dispatcher assigned, by the level of isolation of their host (ADR-0113)")
            .tag("isolation_level", level.name())
            .tag("isolation", chosen ? "chosen" : "automatic")
            .tag("placement_note", note != null ? note.name() : "none")
            .register(registry);
        AfterCommit.run(counter::increment);
    }

    /**
     * Counts a run its host finished, once the transaction commits.
     *
     * @param resumed       null from a host that did not report it: tag {@code unknown}
     * @param resumeOutcome null for a resumed run (tag {@code none}), and for one that ended before its host tried
     *                      (tag {@code unknown})
     */
    void runFinished(@Nullable Boolean resumed, RunnerMessages.@Nullable ResumeOutcome resumeOutcome,
            RuntimeKind runtime) {
        Counter counter = Counter.builder(RUNS_FINISHED)
            .description("Runs that a runner host finished, by whether they resumed their agent's earlier session")
            .tag("resumed", resumed != null ? resumed.toString() : "unknown")
            .tag("resume_outcome", Boolean.TRUE.equals(resumed) ? "none"
                    : resumeOutcome != null ? resumeOutcome.name() : "unknown")
            .tag("runtime", runtime.name())
            .register(registry);
        AfterCommit.run(counter::increment);
    }
}
