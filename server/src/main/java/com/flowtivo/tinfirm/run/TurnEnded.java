package com.flowtivo.tinfirm.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.model.ModelAccessChoice;

/**
 * Port: the end of a turn's run, implemented by the conversation module (package 3c), which records the turn's
 * result or failure, posts what follows, starts the next waiting turn ({@link Turns#start}, in the same transaction)
 * and opens change requests after a push (§5.4, open point 5). Without that module, nobody is told.
 */
public interface TurnEnded {

    /**
     * The run ended (SUCCEEDED, FAILED, CANCELLED or LOST), in the transaction that ended it: the run's row is final
     * and locked, its model token revoked (but for a loss by lease expiry, which may be resumed), its slot free.
     * Runs in the run's company's tenant context, as the actor of what ended it (SYSTEM for a host's frame or the
     * reaper, the person for a cancel of a queued run). The conversation's row was locked before the run's
     * ({@link ConversationLock}, CONVENTIONS §10), so the conversation records the end in this transaction without
     * waiting on another that holds it.
     */
    void turnEnded(TurnEnd end);

    /**
     * A run lost by lease expiry that its host still runs (it reported the run with its epoch within the resume
     * window): whether the conversation takes the turn back, in the transaction that would resume it, which locked
     * the conversation's row before the run's ({@link ConversationLock}). The run is the conversation's latest. False
     * keeps it LOST, and its host is told to cancel it.
     */
    boolean turnResumed(ActiveRun run);

    /**
     * The end of a turn's run.
     *
     * @param modelAccess    who paid; null for a subscription whose host was removed
     * @param status         SUCCEEDED, FAILED, CANCELLED or LOST
     * @param failureClass   a FAILED or LOST run's class: the runner's ({@code USAGE_LIMIT}, …), {@code LOST},
     *                       {@code RUNTIME_REMOVED}, {@code MODEL_NOT_ALLOWED}; null otherwise and when none was named
     * @param summary        the agent's {@code finish} summary, else its last message; null when it left none
     * @param result         the agent's last {@code finish}, or null (for instance a turn that ends on its own
     *                       questions, ADR-0036)
     * @param retryAt        for a {@code USAGE_LIMIT} failure: when the payer's budget or the limit resets, when known
     *                       (package 3's open point 8: the turn stays FAILED with Retry and the time, and is not
     *                       retried by itself)
     * @param repositories   the workspace with each repository's git result, in workspace order
     * @param outputFiles    the files of {@code /output} the host uploaded and listed in {@code run.finished}
     * @param lostCause      why a LOST run was lost ({@code LEASE_EXPIRED}, {@code HOST_RESTARTED},
     *                       {@code STALE_ON_RECONNECT}); null otherwise
     */
    record TurnEnd(UUID companyId, UUID runId, UUID conversationId, UUID agentId, TurnReason reason, int turnNumber,
            UUID actingPrincipalId, UUID payerPrincipalId, @Nullable ModelAccessChoice modelAccess, RunStatus status,
            @Nullable String failureClass, @Nullable String failureMessage, @Nullable String summary,
            RunInfo.@Nullable Result result, @Nullable Instant retryAt, List<RepositoryResult> repositories,
            List<OutputFile> outputFiles, @Nullable String lostCause) {

        public TurnEnd {
            repositories = List.copyOf(repositories);
            outputFiles = List.copyOf(outputFiles);
        }
    }

    /**
     * A repository of the run's workspace at its end.
     *
     * @param pushed  whether the run pushed the branch; null when the host reported no result for it
     * @param commits commits on the branch that are not on the default branch
     */
    record RepositoryResult(@Nullable UUID repositoryId, @Nullable UUID systemId, @Nullable UUID bindingId, String name,
            String branch, boolean readOnly, @Nullable Boolean pushed, @Nullable String headSha,
            @Nullable Integer commits) {
    }

    /** A file of {@code /output} the host uploaded with {@code artifact=true}, by its path under {@code /output}. */
    record OutputFile(UUID attachmentId, String name, long sizeBytes) {
    }
}
