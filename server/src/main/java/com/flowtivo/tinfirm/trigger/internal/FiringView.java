package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.trigger.FiringOutcome;
import com.flowtivo.tinfirm.trigger.TriggerEvent;

/**
 * A row of {@code trigger_firing} (the package 4 contract, §3.11), as {@code GET /triggers/{triggerId}/firings} and run
 * now answer it.
 *
 * @param subjectKey     null for a schedule
 * @param manualBy       run now: the user
 * @param conversationId the conversation that took it; null when it took none
 * @param runId          the turn that took it; null while it waits for one
 */
public record FiringView(UUID id, UUID triggerId, @Nullable String subjectId, @Nullable String subjectKey,
        TriggerEvent event, @Nullable String value, @Nullable Instant changedAt, @Nullable Instant dueAt,
        @Nullable UUID manualBy, boolean once, FiringOutcome outcome, @Nullable String reason, int chainDepth,
        @Nullable UUID conversationId, @Nullable UUID runId, Instant createdAt) {
}
