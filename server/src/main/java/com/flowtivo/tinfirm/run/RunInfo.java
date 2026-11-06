package com.flowtivo.tinfirm.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;

/**
 * A run as other modules see it: one turn of a conversation.
 *
 * @param modelAccess      who pays: the payer's budget or a subscription; null for a subscription whose host was
 *                         removed
 * @param runnerHostId     the host it was assigned to; null before, and after the host was removed
 * @param runnerHostKind   the kind of that host when it was assigned
 * @param queueReason      why a QUEUED run waits, as the dispatcher last found; null otherwise and before it looked
 * @param retryAt          for {@code BUDGET_SPENT}, {@code PREVIOUS_RUN_ACTIVE}, {@code RUNNER_HOST_REJECTED} and
 *                         {@code RETRY_SCHEDULED}: the earliest time it may go on
 * @param failureClass     a FAILED or LOST run's class: the runner's, {@code LOST}, {@code RUNTIME_REMOVED} or
 *                         {@code MODEL_NOT_ALLOWED}
 * @param summary          the agent's {@code finish} summary, else its last message
 * @param result           the agent's last {@code finish}, or null
 * @param sessionId        the agent's session the host reported at the end; the next turn resumes it
 * @param epoch            the run's current assignment
 */
public record RunInfo(UUID id, UUID companyId, UUID conversationId, String conversationKey, UUID agentId,
        RuntimeKind runtime, TurnReason turnReason, int turnNumber, UUID actingPrincipalId, UUID payerPrincipalId,
        @Nullable ModelAccessChoice modelAccess, RunStatus status, @Nullable UUID runnerHostId,
        @Nullable HostScope runnerHostKind, @Nullable QueueReasonCode queueReason, @Nullable Instant retryAt,
        @Nullable String failureClass, @Nullable String failureMessage, @Nullable String summary,
        @Nullable Result result, @Nullable String sessionId, long epoch, Instant createdAt,
        @Nullable Instant startedAt, @Nullable Instant finishedAt) {

    /** The agent's last {@code finish} call of the run: what it reported, within the protocol's limits. */
    public record Result(RunnerMessages.ResultOutcome outcome, String summary, List<String> nextSteps,
            List<String> openQuestions) {

        public Result {
            nextSteps = List.copyOf(nextSteps);
            openQuestions = List.copyOf(openQuestions);
        }
    }
}
