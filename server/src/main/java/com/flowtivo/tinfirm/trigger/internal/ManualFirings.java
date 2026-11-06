package com.flowtivo.tinfirm.trigger.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Seam for part b: run now (the package 4 contract, §5.6, {@code POST /triggers/{triggerId}/run}). Part a's API checks
 * who may run the trigger (its author, its team, admins: else {@code 404} or {@code 403}) and that it is not paused
 * ({@code 409 TRIGGER_PAUSED}), then hands the run to this seam, outside any transaction, as the caller.
 * <p>
 * Part b implements it: on a tracker trigger it reads {@code subjectKey} with the source account
 * ({@code SystemPolls.read}, outside any transaction; a subject it cannot read is {@code 409 SUBJECT_NOT_VISIBLE}),
 * checks scope, subject kind and conditions on that read ({@code 409 TRIGGER_CONDITIONS_FAIL}, naming the failing
 * clauses) and fires it as its first event: {@code manual_by} set, never {@code once}, depth 0, the gate and the rate
 * applied. A schedule fires once now, with its overlap and without its catch-up, and its next firing does not move.
 * Audited as {@code trigger.run} by the user, and as {@code trigger.fired}. Answers the firing.
 * <p>
 * Without part b's bean, run now answers {@code 409 SUBJECT_NOT_VISIBLE}.
 */
public interface ManualFirings {

    /**
     * @param subjectKey the subject to fire on (a tracker trigger's, required: else {@code 400 VALIDATION_FAILED});
     *                   null for a schedule
     * @param userId     the user who runs it
     */
    FiringView run(UUID companyId, UUID triggerId, @Nullable String subjectKey, UUID userId);
}
