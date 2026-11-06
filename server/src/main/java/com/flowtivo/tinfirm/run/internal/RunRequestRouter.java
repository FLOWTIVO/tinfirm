package com.flowtivo.tinfirm.run.internal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform requests of runs ({@code run.request}, runner protocol v2): every request gets exactly one
 * {@code run.request.ack} with its id. The run module checks what is its own, in this order, and hands the rest to
 * {@link com.flowtivo.tinfirm.run.RunRequests}, which the conversation module implements:
 * <ol>
 * <li>the kind is one of the protocol's, else {@code INVALID_REQUEST};</li>
 * <li>the run is RUNNING on this host with this epoch (its lease renewed), else {@code RUN_NOT_ACTIVE};</li>
 * <li>the payload is a JSON object, else {@code INVALID_REQUEST}.</li>
 * </ol>
 * {@code ASK}, {@code ACCESS}, {@code ARTIFACT} and {@code NETWORK} are answered in the transaction that checked the
 * run, which locked the run's conversation before the run (CONVENTIONS §10): the answer writes the conversation. A
 * {@code TOOL} request is checked in a short transaction, then called outside any transaction on a virtual
 * thread ({@link RunWorkers}), so the connection's other frames are not held up; its answer goes to the host's
 * current connection ({@link RunnerConnections#send}). A host sends a tool request again, with the same id, after a
 * reconnect, to this replica or another: it is handed to the port again, whose call journal (package R, §3.2, in the
 * database) never runs a call twice and answers it from there, on every replica and after a restart; nothing is kept
 * here. The port answers within {@code TINFIRM_RUN_TOOL_TIMEOUT}; this router bounds it {@value #GRACE_SECONDS}
 * seconds longer, within the host's wait of the timeout and 5 seconds. Past the bound, and for a port that fails or
 * answers nothing, the answer is {@code TOOL_INTERRUPTED} (a tool error result, package R): the call may have run,
 * and may still record its outcome, so the agent checks before it calls again; the call's thread is interrupted.
 * <p>
 * At most {@code tinfirm.runner.tool-calls-in-flight} {@code TOOL} requests of a run wait for their answer on this
 * replica; one more is answered {@code TOO_MANY_REQUESTS} at once and never reaches the port
 * ({@link RunnerFloodControl}, the security review of 2026-10-27, M4). The rate limits of {@code run.request} are
 * checked before this router ({@link RunnerSessionHandler}).
 */
@Component
class RunRequestRouter {

    private static final Logger log = LoggerFactory.getLogger(RunRequestRouter.class);

    static final String RUN_NOT_ACTIVE = "RUN_NOT_ACTIVE";

    static final String INVALID_REQUEST = "INVALID_REQUEST";

    /** The code of a call whose outcome the run module does not know, in its result's structured content. */
    static final String TOOL_INTERRUPTED = "TOOL_INTERRUPTED";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final int GRACE_SECONDS = 2;

    /** How much longer than the tool timeout the port's call may take before it is cut off. */
    static final Duration GRACE = Duration.ofSeconds(GRACE_SECONDS);

    private final RunStore runs;

    private final RunLocks locks;

    private final RunPorts ports;

    private final RunnerConnections connections;

    private final RunWorkers workers;

    private final SystemTransactions transactions;

    private final RunnerProperties runnerProperties;

    private final RunProperties properties;

    private final TimeSource time;

    private final RunnerFloodControl flood;

    private final RunMetrics metrics;

    RunRequestRouter(RunStore runs, RunLocks locks, RunPorts ports, RunnerConnections connections,
            RunWorkers workers, SystemTransactions transactions, RunnerProperties runnerProperties,
            RunProperties properties, TimeSource time, RunnerFloodControl flood, RunMetrics metrics) {
        this.flood = flood;
        this.metrics = metrics;
        this.runs = runs;
        this.locks = locks;
        this.ports = ports;
        this.connections = connections;
        this.workers = workers;
        this.transactions = transactions;
        this.runnerProperties = runnerProperties;
        this.properties = properties;
        this.time = time;
    }

    /** A request of a kind other than {@code TOOL}, in the caller's transaction (the host's frame). */
    List<RunnerMessages.Outbound> request(ConnectedHost host, RunnerMessages.RunRequest request) {
        RunnerMessages.RequestKind kind = kind(request.kind());
        if (kind == null) {
            return List.of(rejected(request, INVALID_REQUEST, "Tinfirm does not know the request kind "
                    + request.kind() + "."));
        }
        if (kind == RunnerMessages.RequestKind.TOOL) {
            throw new IllegalArgumentException("A TOOL request is called, not requested");
        }
        RunStore.RunRow run = activeRun(host, request.runId(), request.epoch(), true);
        if (run == null) {
            return List.of(rejected(request, RUN_NOT_ACTIVE, "The run is not active."));
        }
        JsonNode payload = request.payload();
        if (payload == null || !payload.isObject()) {
            return List.of(rejected(request, INVALID_REQUEST, "The request has no payload."));
        }
        return List.of(answered(request, ports.requests().request(run.active(), kind, request.requestId(), payload)));
    }

    /**
     * A {@code TOOL} request, outside any transaction: the answer at once for a refusal of the run module, else nothing
     * now and the port's answer later through {@link RunnerConnections#send}.
     */
    List<RunnerMessages.Outbound> call(ConnectedHost host, RunnerMessages.RunRequest request) {
        if (!flood.toolCallStarted(request.runId())) {
            metrics.refused(RunMetrics.REQUEST, RunMetrics.IN_FLIGHT);
            return List.of(rejected(request, RunnerMessages.TOO_MANY_REQUESTS, "At most "
                    + runnerProperties.toolCallsInFlight() + " tool calls of a run wait for their answer at once. "
                    + "Wait for an answer before you call a tool again."));
        }
        boolean handedOn = false;
        try {
            ActiveRun run = transactions.inCompany(host.companyId(), () -> {
                RunStore.RunRow found = activeRun(host, request.runId(), request.epoch(), false);
                return found != null ? found.active() : null;
            });
            if (run == null) {
                return List.of(rejected(request, RUN_NOT_ACTIVE, "The run is not active."));
            }
            JsonNode payload = request.payload();
            if (payload == null || !payload.isObject()) {
                return List.of(rejected(request, INVALID_REQUEST, "The request has no payload."));
            }
            handedOn = workers.execute("tool call " + request.requestId() + " of run " + request.runId(), () -> {
                try {
                    connections.send(host.runnerHostId(), bounded(run, request, payload));
                }
                finally {
                    flood.toolCallEnded(request.runId());
                }
            });
            return List.of();
        }
        finally {
            if (!handedOn) {
                flood.toolCallEnded(request.runId());
            }
        }
    }

    /**
     * The port's call on a thread of its own, within the tool timeout and the grace; past them, or when the port fails
     * or answers nothing, {@code TOOL_INTERRUPTED}, and the thread is interrupted.
     */
    private RunnerMessages.RunRequestAck bounded(ActiveRun run, RunnerMessages.RunRequest request,
            JsonNode payload) {
        CompletableFuture<RunnerMessages.@Nullable RunRequestAck> result = new CompletableFuture<>();
        Future<?> work = workers.submit("tool " + request.requestId(), () -> {
            try {
                result.complete(SystemContext.callInCompany(run.companyId(),
                        () -> ports.requests().call(run, request.requestId(), payload)));
            }
            catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        });
        Duration bound = properties.toolTimeout().plus(GRACE);
        try {
            RunnerMessages.RunRequestAck answer = result.get(bound.toMillis(), TimeUnit.MILLISECONDS);
            if (answer == null) {
                log.warn("Tool call {} of run {} was answered nothing", request.requestId(), run.runId());
                return interrupted(request, "Tinfirm did not answer it");
            }
            return answered(request, answer);
        }
        catch (TimeoutException ex) {
            work.cancel(true);
            log.info("Tool call {} of run {} took longer than {}", request.requestId(), run.runId(), bound);
            return interrupted(request, "Tinfirm did not answer within " + bound.toSeconds() + " seconds");
        }
        catch (ExecutionException ex) {
            log.warn("Tool call {} of run {} failed", request.requestId(), run.runId(), ex.getCause());
            return interrupted(request, "it failed in Tinfirm");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            work.cancel(true);
            return interrupted(request, "Tinfirm stopped waiting for it");
        }
    }

    /**
     * {@code TOOL_INTERRUPTED} (package R): a tool error result that says the call may or may not have taken effect,
     * and that the agent checks the system's state before it calls again. The run module does not know the tool's
     * system, nor whether it ran: the port's journal holds its outcome, which a request sent again with the same id
     * gets.
     */
    private RunnerMessages.RunRequestAck interrupted(RunnerMessages.RunRequest request, String why) {
        String tool = request.payload() != null && request.payload().path("tool").isString()
                ? request.payload().path("tool").asString() : null;
        String text = "The call" + (tool != null ? " of `" + tool + "`" : "") + " was interrupted (" + TOOL_INTERRUPTED
                + "): " + why + ", so it may or may not have taken effect. Check the system's state, with a read "
                + "tool where you have one, before you call it again.";
        return RunnerMessages.RunRequestAck.done(request.runId(), request.requestId(), RunnerMessages.ToolResult.text(
                text, JSON.createObjectNode().put("code", TOOL_INTERRUPTED).put("cause", "TIMEOUT"), true), null);
    }

    /**
     * The run a platform request is about, with its lease renewed: RUNNING on this host with this epoch; null when it
     * is not (the request is then {@code RUN_NOT_ACTIVE}, never an {@code error}).
     *
     * @param withConversation whether the transaction goes on to write the run's conversation, whose row is then locked
     *                         first (CONVENTIONS §10)
     */
    private RunStore.@Nullable RunRow activeRun(ConnectedHost host, UUID runId, long epoch,
            boolean withConversation) {
        RunStore.RunRow run = (withConversation ? locks.lock(runId) : runs.lock(runId)).orElse(null);
        if (run == null || !host.runnerHostId().equals(run.runnerHostId()) || run.status() != RunStatus.RUNNING
                || run.epoch() != epoch) {
            return null;
        }
        runs.renewLease(run.id(), run.epoch(), host.runnerHostId(), time.now().plus(runnerProperties.leaseTtl()));
        return run;
    }

    /** The port's answer, held to the request: the same run and request id, whatever the port said. */
    private static RunnerMessages.RunRequestAck answered(RunnerMessages.RunRequest request,
            RunnerMessages.RunRequestAck answer) {
        if (answer.runId().equals(request.runId()) && answer.requestId().equals(request.requestId())) {
            return answer;
        }
        return new RunnerMessages.RunRequestAck(request.runId(), request.requestId(), answer.status(),
                answer.payload(), answer.code(), answer.message());
    }

    static RunnerMessages.RunRequestAck rejected(RunnerMessages.RunRequest request, String code, String message) {
        return RunnerMessages.RunRequestAck.rejected(request.runId(), request.requestId(), code, message);
    }

    static RunnerMessages.@Nullable RequestKind kind(@Nullable String kind) {
        if (kind == null) {
            return null;
        }
        try {
            return RunnerMessages.RequestKind.valueOf(kind);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
