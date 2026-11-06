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
            @Nullable Instant eventsPurgedAt) {
    }

    record QueueReasonDetails(String code, Instant since, String text, @Nullable UUID runnerHostId,
            @Nullable String runnerHostName, @Nullable String credentialLabel, @Nullable Integer used,
            @Nullable Integer limit, @Nullable Instant lastSeenAt, @Nullable Instant retryAt, @Nullable String reason,
            @Nullable String rejectCode, @Nullable String message, @Nullable String runtime) {
    }

    record RepositoryDetails(@Nullable UUID repositoryId, @Nullable UUID systemId, String repositoryName, String dir,
            boolean readOnly, @Nullable String readOnlyCause, @Nullable Boolean pushed, @Nullable String headSha,
            @Nullable Integer commits) {
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
