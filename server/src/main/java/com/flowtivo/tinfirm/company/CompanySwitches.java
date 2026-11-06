package com.flowtivo.tinfirm.company;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The company's stop of its runs, a kill switch (the package 6 contract, §5.4, open point 7): its state is on the
 * company's row, and every module that runs something asks it ({@link #runsStopped}): the dispatcher's step 0, a
 * conversation's start and an approval, an approved call, the model relay, package 4's gate and outbox, and package 5's
 * webhooks. What the stop does in the modules above (cancelling turns, arming triggers, releasing the held
 * write-back) they do in {@link CompanyRunsDependents}, called in the transaction of the stop and of the lift.
 */
public interface CompanySwitches {

    /** The company's stop, while its runs are stopped. Runs in the caller's transaction and tenant context. */
    Optional<RunsStop> runsStopped(UUID companyId);

    /**
     * Stops the company's runs ({@code STOP_RUNS}: OWNER, ADMIN, an instance admin under break-glass): in one
     * transaction the company is marked and {@link CompanyRunsDependents#runsStopping} cancels what runs. Audited as
     * {@code company.runs_stopped}; a stopped company stays as it is.
     */
    Stopped stopRuns(UUID companyId, String reason);

    /**
     * Lifts the company's stop ({@code STOP_RUNS}): {@link CompanyRunsDependents#runsLifted} arms the triggers again
     * and releases the held write-back, and the queue is dispatched. Audited as {@code company.runs_resumed}; a company
     * that is not stopped stays as it is.
     */
    CompanyRunsDependents.Lifted liftRuns(UUID companyId);

    /** The stop: since when, by whom and why (the reason stays on the company's row, out of the audit chain). */
    record RunsStop(Instant stoppedAt, @Nullable UUID byUserId, String reason) {
    }

    /** What a stop did: the stop, and the runs it cancelled (none when the company was stopped already). */
    record Stopped(RunsStop stop, List<UUID> runsCancelled) {

        public Stopped {
            runsCancelled = List.copyOf(runsCancelled);
        }
    }
}
