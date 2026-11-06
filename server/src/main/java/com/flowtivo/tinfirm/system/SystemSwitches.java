package com.flowtivo.tinfirm.system;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A company system's pause, a kill switch (the package 6 contract, §5.4, open point 6). Its state is on the system's
 * row and reaches other modules as {@link SystemInfo#paused()}: {@code Accounts.check} answers {@code SYSTEM_PAUSED},
 * a run lists none of its tools and refuses a call of one, the dispatcher holds a turn whose workspace holds one of its
 * repositories. What the pause does above the system module (cancelling the running turns of its conversations,
 * pausing and resuming its triggers) the modules there do in {@link SystemSwitchDependents}, in the same transaction.
 * Both calls authorise the caller ({@code MANAGE_SYSTEMS}); only a person undoes the switch (G7).
 */
public interface SystemSwitches {

    /**
     * Pauses a company system (a personal MCP server is {@code 400 PERSONAL_SYSTEM}: the company's switch of personal
     * servers stops those). Audited as {@code system.paused}; a paused system stays as it is.
     */
    Paused pause(UUID companyId, UUID systemId, String reason, boolean stopRunning);

    /** Resumes a paused system, and with {@code resumeTriggers} the triggers its pause paused. Audited as {@code system.resumed}. */
    Resumed resume(UUID companyId, UUID systemId, boolean resumeTriggers);

    /** The company's paused systems, newest pause first, in the caller's transaction and tenant context. */
    List<Pause> paused(UUID companyId);

    /** A system's pause: since when, by whom, why (the reason stays on the system's row, out of the audit chain). */
    record Pause(UUID systemId, String name, Instant pausedAt, @Nullable UUID byUserId, String reason) {
    }

    /** What a pause did. */
    record Paused(Pause pause, List<UUID> runsCancelled, int triggersPaused) {

        public Paused {
            runsCancelled = List.copyOf(runsCancelled);
        }
    }

    /** What a resume did. */
    record Resumed(UUID systemId, int triggersResumed, int triggersStillPaused) {
    }
}
