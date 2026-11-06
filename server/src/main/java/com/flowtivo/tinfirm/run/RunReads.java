package com.flowtivo.tinfirm.run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.Run;

/**
 * Reads of runs for other modules. Runs in the caller's transaction and tenant context: row-level security limits
 * every read to the company of that context. Who may see a run is the caller's to check ({@link RunVisibility} for
 * the Runs API).
 */
public interface RunReads {

    Optional<RunInfo> find(UUID companyId, UUID runId);

    /**
     * Why the QUEUED run waits, in words: the Runs API's {@code queueReason.text} (the inbox's waiting turn shows it).
     * Empty for a run that does not wait, or before the dispatcher looked at it.
     */
    Optional<String> queueReasonText(UUID companyId, UUID runId);

    /** The conversation's QUEUED or RUNNING run. */
    Optional<RunInfo> activeOf(UUID companyId, UUID conversationId);

    /** The conversation's runs, its turns, oldest first. */
    List<RunInfo> ofConversation(UUID companyId, UUID conversationId);

    /** The conversation's runs as the Runs API shows them ({@code Run}), oldest first, for the conversation's page. */
    List<Run> views(UUID companyId, UUID conversationId);

    /** A branch a run pushed to a repository: the run, and the head commit it pushed. */
    record Push(UUID runId, String headSha) {
    }

    /**
     * The latest push of the conversation's branch to the repository, by turn: of its runs other than
     * {@code exceptRunId} (null: of all of them); empty when none pushed there.
     */
    Optional<Push> lastPush(UUID companyId, UUID conversationId, UUID repositoryId, @Nullable UUID exceptRunId);

    /**
     * The run, when it is RUNNING on the host with the epoch: for a runner host's request outside the WebSocket (an
     * upload of {@code /output}, an attachment's download), which names the run and its epoch.
     */
    Optional<ActiveRun> activeOnHost(UUID companyId, UUID runId, long epoch, UUID runnerHostId);

    /** What the run's sandbox reported of its use, and its model (package 6, §3.1); empty for an unknown run. */
    Optional<RunTokens> tokens(UUID companyId, UUID runId);

    /** How many of the company's QUEUED runs paid by the principal wait for the reason (package 6, §5.3). */
    int countQueued(UUID companyId, UUID payerPrincipalId, QueueReasonCode reason);
}
