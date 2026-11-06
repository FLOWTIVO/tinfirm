package com.flowtivo.tinfirm.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.runner.RunnerMessages;

/**
 * The server's model relay for runs paid by a budget (the package 2 contract, §6.1, M7): the runner host's model
 * proxy sends a run's model requests to {@code {TINFIRM_PUBLIC_URL}/api/runner/v1/model/…} with the run's one-run
 * token, and the relay forwards them to the company's LiteLLM with the paying principal's key, which never leaves the
 * server. Every method runs in the caller's tenant context and transaction.
 */
public interface ModelRelay {

    /**
     * What a run the relay serves is: what its token is bound to (the run, its epoch and host) and what its requests
     * are tagged with in LiteLLM ({@code run:}, {@code conversation:}, {@code agent:}).
     */
    record RelayedRun(UUID companyId, UUID runId, long epoch, UUID runnerHostId, UUID payingPrincipalId,
            @Nullable UUID conversationId, @Nullable UUID agentId) {
    }

    /** What the relay passed for a run, over all its tokens: requests, and the bytes of their bodies each way. */
    record RelayUsage(long requests, long requestBytes, long responseBytes) {
    }

    /**
     * What the relay saw of a run's answers on one model with one key (the package 6 contract, §3.1): each answer's
     * {@code usage} as its format reports it, summed. {@code inputTokens} counts the input that was neither read from
     * nor written to the provider's cache.
     *
     * @param keyId LiteLLM's id of the key the requests went with (its SHA-256); a rotation may fall within a run
     */
    record ModelUsage(String model, String keyId, long requests, long inputTokens, long outputTokens,
            long cachedInputTokens, long cacheWriteTokens) {
    }

    /**
     * A new one-run token for the run's assignment to its host, as the {@code modelAccess} of {@code run.assign}: the
     * relay's root for the format as {@code baseUrl} ({@code …/model/anthropic} or {@code …/model/openai/v1}) and the
     * token as {@code apiKey}. The run's tokens of the assignment's epoch and of earlier ones are revoked (a
     * reassignment), never a later epoch's: an assignment that went stale while it was prepared (its run went back to
     * the queue and was assigned again) may open its token after the later assignment opened its own, and must not
     * revoke the token the run uses. Only the token's hash is kept; the token is shown here once.
     *
     * @param format {@code ANTHROPIC} or {@code OPENAI}, the relay's two formats
     * @throws IllegalArgumentException for another format
     */
    RunnerMessages.ModelAccess open(RelayedRun run, RunnerMessages.ModelAccessFormat format);

    /** Revokes every token of the run (it ended); the relay refuses them from then on. */
    void revoke(UUID companyId, UUID runId);

    /**
     * Revokes the run's tokens of this epoch only: those {@link #open} gave an assignment that turned out stale before
     * it was recorded. The tokens of the run's other assignments are left alone: a later one's, which the run may be
     * using, and an earlier one's of a run lost by lease expiry, which may come back with it.
     */
    void revoke(UUID companyId, UUID runId, long epoch);

    /** What the relay passed for the run; zero for a run it never served. */
    RelayUsage usage(UUID companyId, UUID runId);

    /**
     * How many requests of the run the relay admitted whose answers have not ended (the package 6 contract, §5.1):
     * what {@link #usage} and {@link #usageByModel} do not count yet. A run may end while an answer still streams,
     * so its settlement waits for them (CONVENTIONS §15.12); a replica that went away mid-answer, or an end the relay
     * failed to count, leaves one under way for good, which the relay's hour outwaits.
     */
    long answersUnderWay(UUID companyId, UUID runId);

    /**
     * The relay's own count of the run's answers, per model and key (the package 6 contract, §5.1): what settles a
     * run's spend when LiteLLM's spend logs do not. Empty for a run it never served; rows are kept 30 days.
     */
    List<ModelUsage> usageByModel(UUID companyId, UUID runId);

    /**
     * The model the gateway last answered it does not serve, for a request of the run's execution of this epoch:
     * LiteLLM's "Invalid model name". The runner sees only the runtime's error and classes it {@code PROTOCOL_ERROR};
     * the run's end says what it was. An earlier epoch's answer does not count: a reassignment reads the settings, and
     * so the model, again.
     */
    Optional<String> modelNotServed(UUID companyId, UUID runId, long epoch);
}
