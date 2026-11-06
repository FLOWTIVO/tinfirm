package com.flowtivo.tinfirm.conversation.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.ToolReplay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The call journal of a run's tool calls (package R, §3.2; {@link ToolCallStore}): a call is claimed before it runs,
 * so that a request sent again (after a reconnect, to another replica, after a restart) never runs it twice. What a
 * request finds:
 * <ul>
 * <li>no row: the checks of {@link ToolCalls}, then the claim, in a short transaction of its own that locks nothing but
 * the row ({@link #claim}: {@code INSERT … ON CONFLICT DO NOTHING}, then the row {@code FOR NO KEY UPDATE});</li>
 * <li>{@code DONE}: its answer as it went out; {@code INTERRUPTED} and {@code UNSAFE}: {@code TOOL_INTERRUPTED}
 * again;</li>
 * <li>{@code RUNNING} and claimed less than the tool timeout and {@value #RUNNING_MARGIN_SECONDS} seconds ago
 * ({@link #young}): it runs elsewhere, on this replica or another; the request waits for it ({@link ToolCalls}), at most
 * until {@code tinfirm.conversation.tool-journal.answer-within} (55 seconds) after the call's claim, within the host's
 * wait of 65 seconds from its first send; a call still running then is answered {@link #unanswered} and left as it
 * is;</li>
 * <li>{@code RUNNING} and older, or a {@code SAFE} call the sweep set {@code INTERRUPTED}: its replica stopped. A
 * {@code SAFE} call is claimed again, one attempt more, and runs; an {@code UNSAFE} one is set {@code INTERRUPTED},
 * audited ({@code tool.interrupted}, cause {@code RESTART}) and answered {@code TOOL_INTERRUPTED} ({@link #retake}).</li>
 * </ul>
 * A call is cut off {@link #recordReserve} before the tool timeout, so that its record has the rest of it, and settled
 * by {@link #timedOut}: an {@code UNSAFE} one {@code INTERRUPTED} (cause {@code TIMEOUT}), a {@code SAFE} one
 * {@code DONE} with {@code TOOL_FAILED}; a call whose record failed is settled so too by {@link #recordFailed} (cause
 * {@code ERROR}). {@code TOOL_INTERRUPTED} is a tool error
 * result ({@code isError}), not a rejection: it names the tool and the system, says the call may or may not have taken
 * effect, and asks the agent to check the system's state before it calls again; the conversation shows it as a
 * {@code SYSTEM} message. Every transaction here that audits locks the conversation first, then the row (CONVENTIONS
 * §10).
 */
@Component
class ToolJournal {

    private static final Logger log = LoggerFactory.getLogger(ToolJournal.class);

    static final int RUNNING_MARGIN_SECONDS = 15;

    /** How much longer than the tool timeout a call may run on another replica before it counts as lost. */
    static final Duration RUNNING_MARGIN = Duration.ofSeconds(RUNNING_MARGIN_SECONDS);

    /** How long before the tool timeout a call is cut off at most, so that its record has the rest of it. */
    static final int RECORD_RESERVE_SECONDS = 5;

    static final Duration RECORD_RESERVE = Duration.ofSeconds(RECORD_RESERVE_SECONDS);

    /** The code of a call Tinfirm lost track of, in its result's structured content. */
    static final String TOOL_INTERRUPTED = "TOOL_INTERRUPTED";

    static final String TOOL_FAILED = "TOOL_FAILED";

    /** The target type of {@code tool.interrupted}. */
    static final String TARGET = "conversation";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Why Tinfirm lost track of a call. */
    enum Cause {
        /** Past {@code TINFIRM_RUN_TOOL_TIMEOUT}. */
        TIMEOUT,
        /** Its replica stopped during the call (a restart, a crash, a lost connection to it). */
        RESTART,
        /** The call ended, but Tinfirm failed to record its outcome. */
        ERROR
    }

    private final ToolCallStore store;

    private final DecisionStore decisions;

    private final ConversationCore core;

    private final ConversationTools tools;

    private final AuditLog auditLog;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final RunReads runs;

    private final Duration toolTimeout;

    private final Duration answerWithin;

    ToolJournal(ToolCallStore store, DecisionStore decisions, ConversationCore core, ConversationTools tools,
            AuditLog auditLog, SystemTransactions transactions, TimeSource time, RunReads runs,
            @Value("${tinfirm.run.tool-timeout:PT60S}") Duration toolTimeout,
            @Value("${tinfirm.conversation.tool-journal.answer-within:PT55S}") Duration answerWithin) {
        if (answerWithin.isNegative() || answerWithin.isZero()) {
            throw new IllegalArgumentException("tinfirm.conversation.tool-journal.answer-within must be positive, not "
                    + answerWithin);
        }
        this.store = store;
        this.decisions = decisions;
        this.core = core;
        this.tools = tools;
        this.auditLog = auditLog;
        this.transactions = transactions;
        this.time = time;
        this.runs = runs;
        this.toolTimeout = toolTimeout;
        this.answerWithin = answerWithin;
    }

    /** {@code TINFIRM_RUN_TOOL_TIMEOUT}: how long a call may take, and how long a request waits for one elsewhere. */
    Duration toolTimeout() {
        return toolTimeout;
    }

    /**
     * How long before the tool timeout a claimed call is cut off: {@value #RECORD_RESERVE_SECONDS} seconds, a quarter of
     * a shorter timeout. The record (its conversation's lock, its answer, {@code tool.called}) has the rest, within the
     * run module's bound.
     */
    Duration recordReserve() {
        Duration quarter = toolTimeout.dividedBy(4);
        return quarter.compareTo(RECORD_RESERVE) < 0 ? quarter : RECORD_RESERVE;
    }

    /** How long a claimed call may run: the tool timeout less {@link #recordReserve}. */
    Duration callBound() {
        return toolTimeout.minus(recordReserve());
    }

    /**
     * How much longer a request may wait for the call of a row that runs elsewhere: until
     * {@code tinfirm.conversation.tool-journal.answer-within} after the row's claim, so that the host, which waits 65
     * seconds from its first send of the request (it does not wait again for a send after a reconnect), gets the
     * answer in time. Zero when that has passed.
     */
    Duration untilAnswer(ToolCallStore.Row row) {
        Duration left = Duration.between(time.now(), row.startedAt().plus(answerWithin));
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** The request's row, if it reached a call before; in a short transaction of its own. */
    Optional<ToolCallStore.Row> find(ActiveRun run, UUID requestId) {
        return transactions.inCompany(run.companyId(), () -> store.find(run.runId(), requestId));
    }

    /** What the claim of a call found. */
    sealed interface Claim {

        /** The call is this request's to run, at this attempt. */
        record Claimed(int attempt) implements Claim {
        }

        /** The request has a row already: another request with its id claimed it first. */
        record Known(ToolCallStore.Row row) implements Claim {
        }

        /** The run is no longer {@code RUNNING} with the request's epoch: nothing is claimed or called. */
        record RunEnded() implements Claim {
        }
    }

    /**
     * Claims a checked call before it runs, in a short transaction of its own that locks nothing but its row: the row
     * is inserted {@code RUNNING} at attempt 1, or the request's row is found. The run's status is read again first: a
     * check that waited for the conversation's lock while the run's end held it claims nothing for the ended run, whose
     * end cleared its rows already.
     */
    Claim claim(ActiveRun run, UUID requestId, ToolRunner.Prepared call, ToolReplay replay, String argumentsSha256) {
        return transactions.inCompany(run.companyId(), () -> {
            boolean running = runs.find(run.companyId(), run.runId())
                .filter(found -> found.status() == RunStatus.RUNNING && found.epoch() == run.epoch())
                .isPresent();
            if (!running) {
                return new Claim.RunEnded();
            }
            boolean inserted = store.insert(new ToolCallStore.Row(run.companyId(), run.runId(), requestId,
                    call.conversationId(), call.systemId(), call.tool(), call.name(), call.effect(), replay,
                    argumentsSha256, ToolCallStore.State.RUNNING, 1, time.now(), null, null, null));
            ToolCallStore.Row row = store.lock(run.runId(), requestId).orElseThrow();
            return inserted ? new Claim.Claimed(1) : new Claim.Known(row);
        });
    }

    /** Whether a {@code RUNNING} row's call may still run elsewhere: claimed within the tool timeout and the margin. */
    boolean young(ToolCallStore.Row row) {
        return row.startedAt().isAfter(time.now().minus(toolTimeout).minus(RUNNING_MARGIN));
    }

    /** What a request sent again does with a call its replica lost. */
    sealed interface Retake {

        /** A {@code SAFE} call, claimed again for this request at this attempt: it runs. */
        record Claimed(int attempt) implements Retake {
        }

        /** An {@code UNSAFE} call, settled: its answer. */
        record Answered(RunnerMessages.RunRequestAck answer) implements Retake {
        }

        /** The row changed since it was read (another request took it, or its call ended): read it again. */
        record Changed() implements Retake {
        }
    }

    /**
     * A request sent again finds its call lost: {@code RUNNING} and older than the tool timeout and the margin, a
     * {@code SAFE} call the sweep set {@code INTERRUPTED}, or a call whose answer went with its run's end (a run
     * resumed after its loss). Under the conversation's lock and then the row's: a {@code SAFE} call is claimed again
     * (attempt + 1); an {@code UNSAFE} one running is set {@code INTERRUPTED}, noted and audited (cause
     * {@code RESTART}); an {@code UNSAFE} one that ended is answered {@code TOOL_INTERRUPTED} as it stands.
     */
    Retake retake(ActiveRun run, UUID requestId, ToolCallStore.Row seen) {
        return transactions.inCompany(run.companyId(), () -> {
            decisions.lockConversation(seen.conversationId());
            ToolCallStore.Row row = store.lock(run.runId(), requestId).orElse(null);
            if (row == null || row.state() != seen.state() || row.attempt() != seen.attempt()
                    || (row.state() == ToolCallStore.State.RUNNING && young(row))) {
                return new Retake.Changed();
            }
            if (row.replay() == ToolReplay.SAFE) {
                store.reclaim(row.runId(), row.requestId(), row.attempt(), time.now());
                log.info("Tool call {} of run {} was lost; it is SAFE and runs again (attempt {})", requestId,
                        run.runId(), row.attempt() + 1);
                return new Retake.Claimed(row.attempt() + 1);
            }
            if (row.state() == ToolCallStore.State.RUNNING) {
                log.warn("Tool call {} of run {} was running when its replica stopped; it is UNSAFE and is settled "
                        + "TOOL_INTERRUPTED", requestId, run.runId());
                return new Retake.Answered(interrupt(row, Cause.RESTART));
            }
            return new Retake.Answered(answerOf(row));
        });
    }

    /**
     * The answer of a call that ended: as it went out. An {@code UNSAFE} call without one (set {@code INTERRUPTED}
     * without a stored answer, or whose answer went with its run's end) is answered {@code TOOL_INTERRUPTED}.
     */
    RunnerMessages.RunRequestAck answerOf(ToolCallStore.Row row) {
        if (row.result() != null) {
            return answer(row.runId(), row.requestId(), row.result());
        }
        return interruptedAck(row.runId(), row.requestId(), row.name(), systemName(row), Cause.RESTART, toolTimeout);
    }

    /** The request's row as the caller's transaction reads it. */
    Optional<ToolCallStore.Row> current(UUID runId, UUID requestId) {
        return store.find(runId, requestId);
    }

    /**
     * A request that waited for its call running elsewhere until {@link #untilAnswer} ran out, the row still
     * {@code RUNNING}: an {@code UNSAFE} call is answered {@code TOOL_INTERRUPTED} (cause {@code TIMEOUT}), a
     * {@code SAFE} one {@code TOOL_FAILED}, never nothing, since the host does not send the request again on its own.
     * The row stays as it is, unaudited: the replica that runs the call records it, or the sweep settles it, and a
     * request sent again later gets that answer.
     */
    RunnerMessages.RunRequestAck unanswered(ActiveRun run, ToolCallStore.Row row) {
        log.info("Tool call {} of run {} still runs elsewhere after {}; the request is answered without its outcome",
                row.requestId(), run.runId(), answerWithin);
        if (row.replay() == ToolReplay.SAFE) {
            return RunnerMessages.RunRequestAck.rejected(run.runId(), row.requestId(), TOOL_FAILED, "The tool did not "
                    + "answer within " + Math.max(1, answerWithin.toSeconds()) + " seconds.");
        }
        return interruptedAck(run.runId(), row.requestId(), row.name(), systemName(row), Cause.TIMEOUT, answerWithin);
    }

    /**
     * The attempt ends with its answer, in the caller's transaction: the record's, after the account and the binding,
     * before the audit (CONVENTIONS §10). Nothing when the attempt was settled meanwhile (its timeout, the sweep, a
     * request sent again elsewhere): false, and the row's answer is the call's.
     */
    boolean finished(UUID runId, UUID requestId, int attempt, RunnerMessages.RunRequestAck answer) {
        return store.finish(runId, requestId, attempt, ToolCallStore.State.DONE, stored(answer), time.now());
    }

    /**
     * The call passed the tool timeout, in a transaction of its own: the conversation, then the row. An {@code UNSAFE}
     * call is set {@code INTERRUPTED}, noted and audited (cause {@code TIMEOUT}), and answered {@code TOOL_INTERRUPTED};
     * a {@code SAFE} one is {@code DONE} with {@code TOOL_FAILED}, so that a request sent again does not run it again.
     * When the attempt ended meanwhile, its answer.
     */
    RunnerMessages.RunRequestAck timedOut(ActiveRun run, UUID requestId, UUID conversationId, int attempt) {
        return transactions.inCompany(run.companyId(), () -> {
            decisions.lockConversation(conversationId);
            ToolCallStore.Row row = store.lock(run.runId(), requestId).orElse(null);
            if (row == null) {
                return failedAck(run.runId(), requestId);
            }
            if (row.state() != ToolCallStore.State.RUNNING || row.attempt() != attempt) {
                return answerOf(row);
            }
            log.info("Tool call {} of run {} took longer than {} ({})", requestId, run.runId(), toolTimeout,
                    row.replay());
            if (row.replay() == ToolReplay.SAFE) {
                RunnerMessages.RunRequestAck failed = failedAck(run.runId(), requestId);
                store.finish(run.runId(), requestId, attempt, ToolCallStore.State.DONE, stored(failed), time.now());
                return failed;
            }
            return interrupt(row, Cause.TIMEOUT);
        });
    }

    /**
     * The call ended in an exception inside Tinfirm, in a transaction of its own: {@code DONE} with {@code TOOL_FAILED},
     * never run again for this request.
     */
    RunnerMessages.RunRequestAck failed(ActiveRun run, UUID requestId, int attempt) {
        RunnerMessages.RunRequestAck failed = RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, TOOL_FAILED,
                "The tool call failed in Tinfirm.");
        return transactions.inCompany(run.companyId(), () -> {
            if (store.finish(run.runId(), requestId, attempt, ToolCallStore.State.DONE, stored(failed), time.now())) {
                return failed;
            }
            return store.find(run.runId(), requestId)
                .filter(row -> row.result() != null)
                .map(row -> answer(run.runId(), requestId, java.util.Objects.requireNonNull(row.result())))
                .orElse(failed);
        });
    }

    /**
     * The call ended but its record failed (its transaction rolled back), in a transaction of its own: the conversation,
     * then the row. An {@code UNSAFE} call is set {@code INTERRUPTED}, noted and audited (cause {@code ERROR}), and
     * answered {@code TOOL_INTERRUPTED}; a {@code SAFE} one is {@code DONE} with {@code TOOL_FAILED}. When the attempt
     * was settled meanwhile, its answer; when this fails too, the same answer without a record (the row stays
     * {@code RUNNING}, and the sweep settles it).
     */
    RunnerMessages.RunRequestAck recordFailed(ActiveRun run, UUID requestId, ToolRunner.Prepared call, ToolReplay replay,
            int attempt) {
        RunnerMessages.RunRequestAck failed = RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, TOOL_FAILED,
                "The tool call failed in Tinfirm.");
        try {
            return transactions.inCompany(run.companyId(), () -> {
                decisions.lockConversation(call.conversationId());
                ToolCallStore.Row row = store.lock(run.runId(), requestId).orElse(null);
                if (row == null) {
                    return replay == ToolReplay.SAFE ? failed : interruptedAck(run.runId(), requestId, call.name(),
                            systemName(call.companyId(), call.systemId()), Cause.ERROR, toolTimeout);
                }
                if (row.state() != ToolCallStore.State.RUNNING || row.attempt() != attempt) {
                    return answerOf(row);
                }
                if (row.replay() == ToolReplay.SAFE) {
                    store.finish(run.runId(), requestId, attempt, ToolCallStore.State.DONE, stored(failed),
                            time.now());
                    return failed;
                }
                return interrupt(row, Cause.ERROR);
            });
        }
        catch (RuntimeException ex) {
            log.warn("Tool call {} of run {} could not be settled after its record failed", requestId, run.runId(),
                    ex);
            return replay == ToolReplay.SAFE ? failed : interruptedAck(run.runId(), requestId, call.name(),
                    systemName(call.companyId(), call.systemId()), Cause.ERROR, toolTimeout);
        }
    }

    /**
     * The sweep's settling of one call its replica left {@code RUNNING} and whose request never came back, in a
     * transaction of its own in the row's company: an {@code UNSAFE} one is {@code INTERRUPTED}, noted and audited; a
     * {@code SAFE} one {@code INTERRUPTED} without an audit, claimed again if its request comes back.
     *
     * @return whether it was settled now
     */
    boolean settleLost(UUID companyId, UUID runId, UUID requestId, Instant runningBefore) {
        return Boolean.TRUE.equals(transactions.inCompany(companyId, () -> {
            ToolCallStore.Row found = store.find(runId, requestId).orElse(null);
            if (found == null || found.state() != ToolCallStore.State.RUNNING
                    || !found.startedAt().isBefore(runningBefore)) {
                return false;
            }
            decisions.lockConversation(found.conversationId());
            ToolCallStore.Row row = store.lock(runId, requestId).orElse(null);
            if (row == null || row.state() != ToolCallStore.State.RUNNING || !row.startedAt().isBefore(runningBefore)) {
                return false;
            }
            if (row.replay() == ToolReplay.SAFE) {
                return store.finish(runId, requestId, row.attempt(), ToolCallStore.State.INTERRUPTED, null,
                        time.now());
            }
            log.warn("Tool call {} of run {} was running when its replica stopped and was never sent again; it is "
                    + "UNSAFE and is settled TOOL_INTERRUPTED", requestId, runId);
            interrupt(row, Cause.RESTART);
            return true;
        }));
    }

    /** The run ended, in its end's transaction: its rows' answers are cleared. */
    void runEnded(UUID runId) {
        store.runEnded(runId, time.now());
    }

    /** A run lost by lease expiry runs again: its rows stay. */
    void runResumed(UUID runId) {
        store.runResumed(runId);
    }

    /**
     * Sets an {@code UNSAFE} call {@code INTERRUPTED}, under the conversation's and the row's locks the caller holds:
     * its answer stored, a {@code SYSTEM} message in the open conversation, {@code tool.interrupted} last.
     */
    private RunnerMessages.RunRequestAck interrupt(ToolCallStore.Row row, Cause cause) {
        String system = systemName(row);
        RunnerMessages.RunRequestAck ack = interruptedAck(row.runId(), row.requestId(), row.name(), system, cause,
                callBound());
        store.finish(row.runId(), row.requestId(), row.attempt(), ToolCallStore.State.INTERRUPTED, stored(ack),
                time.now());
        ConversationInfo conversation = core.conversations().find(row.companyId(), row.conversationId()).orElse(null);
        if (conversation != null && conversation.open()) {
            String agent = tools.texts(conversation).agent();
            core.messages().post(row.companyId(), row.conversationId(), ConversationMessages.Kind.SYSTEM, null,
                    agent + "'s call of " + ToolRules.quoted(row.name()) + " on " + system + " was interrupted: "
                            + why(cause, callBound()) + ". It may or may not have taken effect; " + agent
                            + " was told to check " + system + " before calling it again.",
                    row.runId(), null);
        }
        auditLog.record(AuditRecord.company(row.companyId(), AuditAction.TOOL_INTERRUPTED, Actor.SYSTEM)
            .target(TARGET, row.conversationId())
            .detail("runId", row.runId().toString())
            .detail("requestId", row.requestId().toString())
            .detail("systemId", row.systemId() != null ? row.systemId().toString() : null)
            .detail("tool", row.tool())
            .detail("attempt", row.attempt())
            .detail("cause", cause.name())
            .build());
        return ack;
    }

    private String systemName(ToolCallStore.Row row) {
        return systemName(row.companyId(), row.systemId());
    }

    /** The system's name; for a memory tool's call, which no system serves, "your memory". */
    private String systemName(UUID companyId, @Nullable UUID systemId) {
        if (systemId == null) {
            return "your memory";
        }
        SystemInfo system = tools.systemOf(companyId, systemId);
        return system != null ? system.name() : "its system";
    }

    // ---- the answers --------------------------------------------------------------------------------------------

    /** The call took longer than the tool timeout; a {@code SAFE} one's answer. */
    RunnerMessages.RunRequestAck failedAck(UUID runId, UUID requestId) {
        return RunnerMessages.RunRequestAck.rejected(runId, requestId, TOOL_FAILED, "The tool did not answer within "
                + Math.max(1, callBound().toSeconds()) + " seconds.");
    }

    /** Why a call was interrupted, in words. */
    static String why(Cause cause, Duration timeout) {
        return switch (cause) {
            case TIMEOUT -> "it did not answer within " + Math.max(1, timeout.toSeconds()) + " seconds";
            case RESTART -> "Tinfirm restarted during the call";
            case ERROR -> "Tinfirm failed while it recorded the call's outcome";
        };
    }

    /**
     * The text of {@code TOOL_INTERRUPTED} (package R, §3.2): the tool, the system, that the call may or may not have
     * taken effect, and that the agent checks the system's state before it calls again. The recovery of approved and
     * parked calls answers an {@code UNSAFE} call a restart caught running with the same words (§3.3).
     */
    static String interruptedText(String name, String system, Cause cause, Duration timeout) {
        return "The call of " + ToolRules.quoted(name) + " on " + system + " was interrupted (" + TOOL_INTERRUPTED
                + "): " + why(cause, timeout) + ", so it may or may not have taken effect. Check " + system
                + "'s state, with a read tool where you have one, before you call it again.";
    }

    /** The text of {@code TOOL_INTERRUPTED} for a call a restart caught running. */
    static String restarted(String name, String system) {
        return interruptedText(name, system, Cause.RESTART, Duration.ZERO);
    }

    /** {@code TOOL_INTERRUPTED}: a tool error result, with its code and cause as structured content. */
    static RunnerMessages.RunRequestAck interruptedAck(UUID runId, UUID requestId, String name, String system,
            Cause cause, Duration timeout) {
        ObjectNode structured = JSON.createObjectNode().put("code", TOOL_INTERRUPTED).put("cause", cause.name());
        return RunnerMessages.RunRequestAck.done(runId, requestId, RunnerMessages.ToolResult.text(
                interruptedText(name, system, cause, timeout), structured, true), null);
    }

    /** An answer as the journal keeps it: its status with its payload, or its code and message. */
    static ObjectNode stored(RunnerMessages.RunRequestAck ack) {
        ObjectNode node = JSON.createObjectNode().put("status", ack.status().name());
        if (ack.payload() != null) {
            node.set("payload", JSON.valueToTree(ack.payload()));
        }
        if (ack.code() != null) {
            node.put("code", ack.code());
        }
        if (ack.message() != null) {
            node.put("message", ack.message());
        }
        return node;
    }

    /** A stored answer, to the request again. */
    static RunnerMessages.RunRequestAck answer(UUID runId, UUID requestId, JsonNode stored) {
        RunnerMessages.RequestStatus status = RunnerMessages.RequestStatus.valueOf(stored.get("status").asString());
        String message = stored.hasNonNull("message") ? stored.get("message").asString() : null;
        JsonNode payload = stored.get("payload");
        return switch (status) {
            case REJECTED -> RunnerMessages.RunRequestAck.rejected(runId, requestId, stored.get("code").asString(),
                    message != null ? message : "The call was refused.");
            case PENDING -> RunnerMessages.RunRequestAck.pending(runId, requestId,
                    UUID.fromString(payload.get("decisionId").asString()), message);
            case DONE -> {
                List<RunnerMessages.ToolContent> content = new ArrayList<>();
                payload.path("content").forEach(block -> content.add(new RunnerMessages.ToolContent(
                        block.path("type").asString("text"), block.path("text").asString(""))));
                JsonNode structured = payload.get("structuredContent");
                yield RunnerMessages.RunRequestAck.done(runId, requestId, new RunnerMessages.ToolResult(content,
                        structured != null && !structured.isNull() ? structured : null,
                        payload.path("isError").asBoolean(false)), message);
            }
        };
    }

    // ---- the arguments ------------------------------------------------------------------------------------------

    /** The SHA-256 of the arguments' canonical form, in hex: keys sorted at every level, no whitespace. */
    static String sha256(JsonNode arguments) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical(arguments).toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The node with every object's keys sorted. */
    static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                sorted.put(property.getKey(), canonical(property.getValue()));
            }
            ObjectNode copy = JSON.createObjectNode();
            sorted.forEach(copy::set);
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            node.forEach(element -> copy.add(canonical(element)));
            return copy;
        }
        return node;
    }

}
