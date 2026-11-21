package com.flowtivo.tinfirm.run;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.runner.RunnerMessages;

import tools.jackson.databind.JsonNode;

/**
 * Port: the platform requests of a run ({@code run.request}, runner protocol v2; the package 3 contract, §5.3 to
 * §5.5, §5.8), implemented by the conversation module (package 3c), which owns decisions, bindings, tools and
 * outputs. The run module has checked that the run is RUNNING on the host with the request's epoch (else
 * {@code RUN_NOT_ACTIVE}), that the kind is one of {@link RunnerMessages.RequestKind} and the payload a JSON object
 * (else {@code INVALID_REQUEST}), and renewed the run's lease; it sends exactly one {@code run.request.ack} with the
 * request's id for every request. The answers' codes are the contract's §7 ({@code UNKNOWN_TOOL}, {@code TOOL_DENIED},
 * {@code ACCOUNT_GONE}, {@code ALREADY_PENDING}, …), and a request id sent again (after a reconnect, on any replica,
 * after a restart) never raises a second decision, output or tool call.
 * <p>
 * Without an implementation (a context without the conversation module), every request is answered {@code REJECTED}
 * with {@code REQUEST_UNAVAILABLE} ({@code UNKNOWN_TOOL} for a tool, since a run then lists none).
 */
public interface RunRequests {

    /**
     * {@code ASK}, {@code ACCESS}, {@code ARTIFACT} and {@code NETWORK}: in the transaction that checked the run, which
     * holds the run's row lock, taken after its conversation's ({@link ConversationLock}, CONVENTIONS §10), in its
     * company's tenant context as actor SYSTEM; the answer is sent once it commits.
     */
    RunnerMessages.RunRequestAck request(ActiveRun run, RunnerMessages.RequestKind kind, UUID requestId,
            JsonNode payload);

    /**
     * {@code TOOL}: outside any transaction (CONVENTIONS §10), as actor SYSTEM, on a virtual thread of its own, so
     * the host's other frames are not held up; the implementation opens its own short transactions in the run's
     * company. A request id sent again (after a reconnect, to another replica, after a restart) is handed over again:
     * the implementation keeps the calls in its journal (package R, §3.2), never runs one twice and answers it from
     * there. It always answers, within {@code TINFIRM_RUN_TOOL_TIMEOUT} itself (an {@code UNSAFE} call past it
     * {@code TOOL_INTERRUPTED}, a {@code SAFE} one {@code TOOL_FAILED}; a call that runs elsewhere still when the
     * request's wait ends likewise), since the host sends a request again only after a reconnect. The run module
     * bounds it a little longer (past that, or when it fails or answers null: {@code TOOL_INTERRUPTED}, since the call
     * may have run, and the thread is interrupted) and sends the answer to the host's current connection.
     */
    RunnerMessages.RunRequestAck call(ActiveRun run, UUID requestId, JsonNode payload);

    /**
     * {@code GIT_TOKEN} (ADR-0116, decision 9): new tokens of the run's repositories whose token a GitHub App minted,
     * outside any transaction, as actor SYSTEM, on a virtual thread of its own. The run module has checked the run,
     * the payload, and that each repository is one of the run's current assignment that no revocation names; it hands
     * over each with the binding its account came from and whether the run may only read it. The implementation checks
     * each binding again through the gate ({@code ConversationBindings.use}, purpose {@code run:{runId}}) and mints;
     * every repository is answered, with a token or a refusal. The run module records a {@code GIT_REVOKED} refusal as
     * a revocation, with its {@code reason}.
     */
    default GitTokenAnswer gitTokens(ActiveRun run, List<GitTokenRepository> repositories) {
        Map<UUID, Refusal> refused = new HashMap<>();
        repositories.forEach(repository -> refused.put(repository.componentId(), new Refusal(
                RunnerMessages.GIT_UNAVAILABLE, "REFRESH_REFUSED", "Tinfirm does not renew git tokens on this server.")));
        return new GitTokenAnswer(Map.of(), refused);
    }

    /** A repository of a GIT_TOKEN request: the run's, with the binding its token came from. */
    record GitTokenRepository(UUID componentId, @Nullable UUID bindingId, boolean readOnly) {
    }

    /** The answer of {@link #gitTokens}: every repository in exactly one of the two. */
    record GitTokenAnswer(Map<UUID, RunnerMessages.GitToken> tokens, Map<UUID, Refusal> refused) {

        public GitTokenAnswer {
            tokens = Map.copyOf(tokens);
            refused = Map.copyOf(refused);
        }
    }

    /**
     * Why a repository was not renewed.
     *
     * @param code    {@code GIT_REVOKED}, {@code GIT_HELD} or {@code GIT_UNAVAILABLE}
     * @param reason  for {@code GIT_REVOKED}, the revocation's reason (the binding's, or {@code REFRESH_REFUSED})
     * @param message the text: for {@code GIT_REVOKED}, the cause in words, which the revocation's message ends with
     */
    record Refusal(String code, String reason, String message) {
    }
}
