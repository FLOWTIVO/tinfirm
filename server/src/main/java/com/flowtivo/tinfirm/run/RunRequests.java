package com.flowtivo.tinfirm.run;

import java.util.UUID;

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
}
