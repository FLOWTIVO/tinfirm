package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** Read models of the Runs API, with every display value resolved; enums as their names. */
final class RunViews {

    private RunViews() {
    }

    record RunDetails(UUID id, UUID conversationId, String conversationKey, UUID agentId, String runtime,
            String turnReason, int turnNumber, UUID personPrincipalId, @Nullable String personName,
            UUID payerPrincipalId, @Nullable String payerName, String modelAccess, @Nullable String subscriptionLabel,
            @Nullable UUID runnerHostId, @Nullable String runnerHostName, @Nullable String runnerHostKind,
            String status, @Nullable String failureClass, @Nullable String failureMessage,
            @Nullable QueueReasonDetails queueReason, Instant createdAt, @Nullable Instant startedAt,
            @Nullable Instant finishedAt, @Nullable String summary, @Nullable ResultDetails result,
            @Nullable String branch, List<RepositoryDetails> repositories, @Nullable UsageDetails usage,
            @Nullable RelayDetails relay, @Nullable CountsDetails counts, @Nullable Boolean resumed,
            @Nullable String resumeOutcome, long lastEventSeq, @Nullable Instant textsPurgedAt,
            @Nullable Instant eventsPurgedAt, @Nullable String isolationLevel, @Nullable String placementNote) {
    }

    record QueueReasonDetails(String code, Instant since, String text, @Nullable UUID runnerHostId,
            @Nullable String runnerHostName, @Nullable String credentialLabel, @Nullable Integer used,
            @Nullable Integer limit, @Nullable Instant lastSeenAt, @Nullable Instant retryAt, @Nullable String reason,
            @Nullable String rejectCode, @Nullable String message, @Nullable String runtime,
            @Nullable String isolationLevel) {
    }

    record RepositoryDetails(@Nullable UUID repositoryId, @Nullable UUID systemId, String repositoryName, String dir,
            boolean readOnly, @Nullable String readOnlyCause, @Nullable Boolean pushed, @Nullable String headSha,
            @Nullable Integer commits, @Nullable GitRevocationDetails gitRevoked) {
    }

    /**
     * ADR-0116: the revocation of a repository's git token in the run's current assignment.
     *
     * @param delivery    {@code CONFIRMED}, the host answered {@code run.git.revoked}; {@code SENT}, the frame was
     *                    written to the host's connection and not confirmed yet; {@code PENDING}, the host was not
     *                    connected, or a send failed, and it goes at the host's next {@code hello};
     *                    {@code UNSUPPORTED}, the host cannot drop a token
     * @param confirmedAt when the host first confirmed it; null unless {@code CONFIRMED}
     */
    record GitRevocationDetails(String reason, String message, Instant revokedAt, String delivery,
            @Nullable Instant confirmedAt) {
    }

    record ResultDetails(String outcome, String summary, List<String> nextSteps, List<String> openQuestions) {
    }

    record UsageDetails(Long inputTokens, Long outputTokens, @Nullable Long cachedInputTokens,
            @Nullable Long cacheWriteTokens, @Nullable Double costUsd) {
    }

    record RelayDetails(long requests, long requestBytes, long responseBytes) {
    }

    record CountsDetails(int toolCalls, int messages, int thoughts) {
    }
}
