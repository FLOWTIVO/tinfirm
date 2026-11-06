package com.flowtivo.tinfirm.conversation.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
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

import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.ToolCheck;
import com.flowtivo.tinfirm.system.ToolInfo;
import com.flowtivo.tinfirm.system.ToolOutcome;
import com.flowtivo.tinfirm.system.ToolReplay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A run's {@code run.request} of kind {@code TOOL} (the package 3 contract, §5.3, §5.4; {@code RunRequests.call}),
 * outside any transaction:
 * <ol>
 * <li>In a short transaction, under the conversation's row lock taken first: the conversation open (else
 * {@code CONVERSATION_ENDED}); a request id sent again answers what it raised before (its approval, or the binding it
 * waits on); the name mapped back to its system's tool (else {@code UNKNOWN_TOOL}); steps 1 to 4 of
 * {@link ConversationTools} ({@code TOOL_DENIED}, audited as {@code tool.denied} once per run and tool); the arguments
 * checked ({@code SystemTools.check}); the binding, resolved at the system's first use
 * ({@code ConversationBindings.resolve}) and checked: gone is {@code ACCOUNT_GONE}, waiting parks the call
 * ({@link ParkedCalls}) and answers {@code PENDING} with the binding's id, a read-only grant refuses a write
 * ({@code TOOL_DENIED} naming the grant); {@code ASK} raises the approval and answers {@code PENDING} with it.</li>
 * <li>{@code ALLOW}: the call is claimed in the journal ({@link ToolJournal}, package R; {@code RUN_NOT_ACTIVE} for a
 * run that ended meanwhile), runs ({@link ToolRunner}, purpose {@code run-tool:{runId}}) until shortly before the tool
 * timeout, and its answer is the system's ({@code DONE}), or the
 * system module's refusal ({@code REJECTED}); a credential the system refused (a {@code 401}) turns the account
 * {@code RECONNECT}, the binding waits, and the call is parked. A token that cannot be got right now while the
 * binding holds (its endpoint down or busy) answers {@code TOOL_FAILED}: try again shortly. The answer is the
 * journal row's, written in the record's transaction after the account and the binding, before {@code tool.called}
 * (CONVENTIONS §10). A system paused after the check gives no token: {@code TOOL_DENIED}, never called. A row
 * settled while the call ran keeps its answer; a record that fails settles the call ({@code TOOL_INTERRUPTED} for an
 * {@code UNSAFE} one).</li>
 * </ol>
 * Arguments that are not a JSON object, or larger than 64 KiB as JSON in UTF-8 ({@link #tooLarge}), are
 * {@code INVALID_REQUEST} before anything else. A request id the journal holds (sent again after a reconnect, to
 * another replica, after a restart) is answered from its row before anything else is checked: the stored answer of a
 * call that ended; for a call that runs elsewhere, its answer once it ends, or {@code TOOL_INTERRUPTED}
 * ({@code TOOL_FAILED} for a {@code SAFE} call) when the wait ends first, never nothing, since the host sends a request
 * again only after a reconnect; for a call its replica lost, a {@code SAFE} call runs again and an {@code UNSAFE} one
 * is {@code TOOL_INTERRUPTED}. Other arguments under the same id are {@code INVALID_REQUEST}, never a call. Past the
 * tool timeout an {@code UNSAFE} call is {@code TOOL_INTERRUPTED}, a {@code SAFE} one {@code TOOL_FAILED}; its thread
 * is interrupted. Every answer comes within {@code TINFIRM_RUN_TOOL_TIMEOUT} of the request, so that the run module's
 * bound, a little longer, never cuts one off.
 */
@Component
class ToolCalls {

    private static final Logger log = LoggerFactory.getLogger(ToolCalls.class);

    /** How often a request reads the row of a call that runs elsewhere. */
    static final Duration POLL = Duration.ofMillis(500);

    static final String INVALID_REQUEST = "INVALID_REQUEST";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String RUN_NOT_ACTIVE = "RUN_NOT_ACTIVE";

    static final String CONVERSATION_ENDED = "CONVERSATION_ENDED";

    static final String TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";

    /** The system module's code of a call that failed in Tinfirm; the agent may try it again (§15.2). */
    static final String TOOL_FAILED = "TOOL_FAILED";

    private final ConversationCore core;

    private final ConversationTools tools;

    private final DecisionStore decisionStore;

    private final DecisionOps decisions;

    private final ParkedCalls parked;

    private final ParkedCallStore parkedStore;

    private final ToolRunner runner;

    private final ToolAudit audit;

    private final Settings settings;

    private final SystemTools systemTools;

    private final SystemTransactions transactions;

    private final ToolJournal journal;

    private final ConversationWorkers workers;

    ToolCalls(ConversationCore core, ConversationTools tools, DecisionStore decisionStore, DecisionOps decisions,
            ParkedCalls parked, ParkedCallStore parkedStore, ToolRunner runner, ToolAudit audit, Settings settings,
            SystemTools systemTools, SystemTransactions transactions, ToolJournal journal,
            ConversationWorkers workers) {
        this.core = core;
        this.tools = tools;
        this.decisionStore = decisionStore;
        this.decisions = decisions;
        this.parked = parked;
        this.parkedStore = parkedStore;
        this.runner = runner;
        this.audit = audit;
        this.settings = settings;
        this.systemTools = systemTools;
        this.transactions = transactions;
        this.journal = journal;
        this.workers = workers;
    }

    /** The checks' outcome: the answer at once, or a call to run with its tool's replay class. */
    private record Checked(RunnerMessages.@Nullable RunRequestAck answer, ToolRunner.@Nullable Prepared call,
            @Nullable String what, @Nullable ToolReplay replay) {

        static Checked answer(RunnerMessages.RunRequestAck answer) {
            return new Checked(answer, null, null, null);
        }
    }

    /**
     * A {@code TOOL} request, outside any transaction: its answer, always, within the tool timeout (a call that runs
     * elsewhere still when the wait ends is answered without its outcome, {@link ToolJournal#unanswered}).
     */
    RunnerMessages.RunRequestAck call(ActiveRun run, UUID requestId, JsonNode payload) {
        long deadline = System.nanoTime() + journal.toolTimeout().toNanos();
        JsonNode tool = payload.get("tool");
        JsonNode arguments = payload.get("arguments");
        if (tool == null || !tool.isString() || tool.asString().isBlank()) {
            return rejected(run, requestId, INVALID_REQUEST, "Name the tool to call.");
        }
        String name = tool.asString().strip();
        if (arguments == null || arguments.isNull()) {
            arguments = JSON.createObjectNode();
        }
        if (!arguments.isObject()) {
            return rejected(run, requestId, INVALID_REQUEST, "The arguments of " + ToolRules.quoted(name)
                    + " are a JSON object.");
        }
        if (tooLarge(arguments)) {
            return rejected(run, requestId, INVALID_REQUEST, "The arguments of " + ToolRules.quoted(name)
                    + " are larger than " + SystemTools.MAX_ARGUMENT_BYTES / 1024 + " KiB as JSON in UTF-8, the most "
                    + "a tool call takes. Shorten them. Nothing was called.");
        }
        JsonNode given = arguments;
        String argumentsSha256 = ToolJournal.sha256(given);
        // A request id sent again (a reconnect, another replica, a restart): what the journal holds, before any check.
        Optional<ToolCallStore.Row> known = journal.find(run, requestId);
        if (known.isPresent()) {
            return again(run, requestId, name, given, argumentsSha256, known.get(), deadline);
        }
        Checked checked = transactions.inCompany(run.companyId(), () -> check(run, requestId, name, given));
        if (checked.answer() != null) {
            return checked.answer();
        }
        ToolRunner.Prepared call = Objects.requireNonNull(checked.call());
        ToolJournal.Claim claim = journal.claim(run, requestId, call, Objects.requireNonNull(checked.replay()),
                argumentsSha256);
        if (claim instanceof ToolJournal.Claim.Known raced) {
            return again(run, requestId, name, given, argumentsSha256, raced.row(), deadline);
        }
        if (claim instanceof ToolJournal.Claim.RunEnded) {
            return rejected(run, requestId, RUN_NOT_ACTIVE, "The run is not active.");
        }
        return execute(run, requestId, call, checked.what(), checked.replay(),
                ((ToolJournal.Claim.Claimed) claim).attempt(), deadline);
    }

    /**
     * Whether a call's arguments, serialised as JSON in UTF-8, exceed {@link SystemTools#MAX_ARGUMENT_BYTES} (64 KiB):
     * such a call is refused {@code INVALID_REQUEST} before anything else, never journaled nor sent to a system. What
     * an agent passes to a tool goes to the tool's system verbatim, and any system an agent calls is a destination for
     * what its conversation read elsewhere (the security review of 2026-10-27, D3, accepted with this cap); the runner
     * host caps the arguments the same way after redaction, so a compliant host never meets this refusal.
     */
    static boolean tooLarge(JsonNode arguments) {
        return arguments.toString().getBytes(StandardCharsets.UTF_8).length > SystemTools.MAX_ARGUMENT_BYTES;
    }

    /**
     * Whether a request sent again is the call the journal holds under its id (the security review of 2026-10-27, I4):
     * the same tool, as the run lists it, with the same arguments. Another tool under a known id is another call, never
     * the first one's answer.
     */
    static boolean sameCall(ToolCallStore.Row first, String name, String argumentsSha256) {
        return first.name().equals(name) && first.argumentsSha256().equals(argumentsSha256);
    }

    /**
     * A request whose id the journal holds: the stored answer, a wait for the call that runs elsewhere (at most
     * {@link ToolJournal#untilAnswer}), or a call its replica lost run again ({@code SAFE}) or answered
     * {@code TOOL_INTERRUPTED} ({@code UNSAFE}).
     */
    private RunnerMessages.RunRequestAck again(ActiveRun run, UUID requestId, String name, JsonNode arguments,
            String argumentsSha256, ToolCallStore.Row first, long deadline) {
        if (!sameCall(first, name, argumentsSha256)) {
            return rejected(run, requestId, INVALID_REQUEST, "The request " + requestId + " called "
                    + ToolRules.quoted(first.name()) + (first.name().equals(name) ? " with other arguments" : "")
                    + " before: a request sent again is the same call. Call it with a new request id.");
        }
        ToolCallStore.Row row = first;
        while (true) {
            boolean lost = switch (row.state()) {
                case DONE -> row.result() == null;
                case INTERRUPTED -> row.replay() == ToolReplay.SAFE;
                case RUNNING -> !journal.young(row);
            };
            if (!lost) {
                if (row.state() != ToolCallStore.State.RUNNING) {
                    return journal.answerOf(row);
                }
                // It runs elsewhere, on this replica or another: its answer once it ends, else, once the wait ends
                // within the host's, an answer without its outcome, never nothing.
                long now = System.nanoTime();
                long left = Math.min(deadline - now, journal.untilAnswer(row).toNanos());
                if (left <= 0 || !pause(Math.min(left, POLL.toNanos()))) {
                    return journal.unanswered(run, row);
                }
            }
            else {
                ToolJournal.Retake retake = journal.retake(run, requestId, row);
                if (retake instanceof ToolJournal.Retake.Answered answered) {
                    return answered.answer();
                }
                if (retake instanceof ToolJournal.Retake.Claimed claimed) {
                    return rerun(run, requestId, name, arguments, claimed.attempt(), deadline);
                }
            }
            row = journal.find(run, requestId).orElse(null);
            if (row == null) {
                // Its row went with the conversation's purge.
                return rejected(run, requestId, RUN_NOT_ACTIVE, "The run is not active.");
            }
        }
    }

    /** A {@code SAFE} call its replica lost, claimed again: checked again, then run. */
    private RunnerMessages.RunRequestAck rerun(ActiveRun run, UUID requestId, String name,
            JsonNode arguments, int attempt, long deadline) {
        Checked checked;
        try {
            checked = transactions.inCompany(run.companyId(), () -> check(run, requestId, name, arguments));
        }
        catch (RuntimeException ex) {
            log.warn("Tool call {} of run {} could not be checked again", requestId, run.runId(), ex);
            return journal.failed(run, requestId, attempt);
        }
        if (checked.answer() != null) {
            RunnerMessages.RunRequestAck answer = checked.answer();
            transactions.runInCompany(run.companyId(), () -> journal.finished(run.runId(), requestId, attempt,
                    answer));
            return answer;
        }
        return execute(run, requestId, Objects.requireNonNull(checked.call()), checked.what(), ToolReplay.SAFE,
                attempt, deadline);
    }

    /**
     * Runs a claimed call on a thread of its own, until {@link ToolJournal#recordReserve} before the request's tool
     * timeout, and records it in the rest. Past that the thread is interrupted and the journal settles the call
     * ({@link ToolJournal#timedOut}); a call that ends later is still recorded ({@code tool.called}), its answer gone. A
     * record that fails settles the call too ({@link ToolJournal#recordFailed}): the host never hears an outcome the
     * journal does not hold.
     */
    private RunnerMessages.RunRequestAck execute(ActiveRun run, UUID requestId, ToolRunner.Prepared call,
            @Nullable String what, @Nullable ToolReplay replay, int attempt, long deadline) {
        long callDeadline = deadline - journal.recordReserve().toNanos();
        CompletableFuture<ToolRunner.Ran> result = new CompletableFuture<>();
        Future<?> work;
        try {
            work = workers.submit(run.companyId(), () -> {
                try {
                    result.complete(runner.run(call, "run-tool:" + run.runId()));
                }
                catch (RuntimeException ex) {
                    result.completeExceptionally(ex);
                }
            });
        }
        catch (RuntimeException ex) {
            log.warn("Tool call {} of run {} could not start", requestId, run.runId(), ex);
            return journal.failed(run, requestId, attempt);
        }
        ToolRunner.Ran ran;
        try {
            ran = result.get(Math.max(0, callDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException ex) {
            work.cancel(true);
            RunnerMessages.RunRequestAck answer = journal.timedOut(run, requestId, call.conversationId(), attempt);
            // On a thread of its own: the call's thread was interrupted.
            result.thenAccept(late -> workers.execute(run.companyId(), "The late end of tool call " + requestId,
                    () -> recordLate(run, call, late)));
            return answer;
        }
        catch (ExecutionException ex) {
            log.warn("Tool call {} of run {} failed", requestId, run.runId(), ex.getCause());
            return journal.failed(run, requestId, attempt);
        }
        catch (InterruptedException ex) {
            // The run module's bound gave up on this request: the journal settles the call on another thread.
            Thread.currentThread().interrupt();
            work.cancel(true);
            workers.execute(run.companyId(), "The timeout of tool call " + requestId,
                    () -> journal.timedOut(run, requestId, call.conversationId(), attempt));
            return ToolJournal.interruptedAck(run.runId(), requestId, call.name(), systemName(run, call),
                    ToolJournal.Cause.TIMEOUT, journal.callBound());
        }
        // The conversation first (CONVENTIONS §10): the answer audits, may check the binding again or park the call,
        // and a binding bound again may start the CONNECTED turn.
        try {
            return transactions.inCompany(run.companyId(), () -> {
                decisionStore.lockConversation(run.conversationId());
                return answer(run, requestId, call, what, ran, attempt);
            });
        }
        catch (RuntimeException ex) {
            log.warn("The record of tool call {} of run {} failed", requestId, run.runId(), ex);
            return journal.recordFailed(run, requestId, call, replay != null ? replay : ToolReplay.UNSAFE, attempt);
        }
    }

    /** A call that ended after its timeout's answer went out: what it left, and {@code tool.called}; no answer. */
    private void recordLate(ActiveRun run, ToolRunner.Prepared call, ToolRunner.Ran ran) {
        if (!(ran instanceof ToolRunner.Ran.Called called)) {
            return;
        }
        try {
            transactions.runInCompany(run.companyId(), () -> {
                decisionStore.lockConversation(run.conversationId());
                runner.effects(call, called);
                runner.audited(call, called, "ALLOW", null, null);
            });
        }
        catch (RuntimeException ex) {
            log.warn("The late end of a tool call of run {} could not be recorded", run.runId(), ex);
        }
    }

    /** Sleeps for the time; false when the thread was interrupted. */
    private static boolean pause(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
            return true;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Checked check(ActiveRun run, UUID requestId, String name, JsonNode arguments) {
        // The conversation's row first, then read: an end that commits meanwhile is seen, and nothing is raised or
        // parked in an ended conversation.
        decisionStore.lockConversation(run.conversationId());
        ConversationInfo conversation = core.conversations().find(run.companyId(), run.conversationId()).orElse(null);
        if (conversation == null) {
            return Checked.answer(rejected(run, requestId, RUN_NOT_ACTIVE, "The run is not active."));
        }
        if (!conversation.open()) {
            return Checked.answer(rejected(run, requestId, CONVERSATION_ENDED, "The conversation ended, so "
                    + "nothing more runs in it. Put what you needed in your summary."));
        }
        ConversationTools.Texts texts = tools.texts(conversation);
        // A request id sent again (after a reconnect) answers what it raised: never a second approval or parked call.
        var raised = decisionStore.byRequest(conversation.id(), requestId);
        if (raised.isPresent()) {
            return Checked.answer(RunnerMessages.RunRequestAck.pending(run.runId(), requestId, raised.get().id(),
                    approvalPending(name, texts)));
        }
        var parkedOn = parkedStore.bindingOf(conversation.id(), requestId);
        if (parkedOn.isPresent()) {
            String system = core.bindings().find(run.companyId(), parkedOn.get())
                .map(binding -> tools.systemOf(run.companyId(), binding.systemId()))
                .map(info -> info != null ? info.name() : "The system")
                .orElse("The system");
            return Checked.answer(RunnerMessages.RunRequestAck.pending(run.runId(), requestId, parkedOn.get(),
                    parkedMessage(system, texts.person())));
        }
        ConversationTools.Target target = tools.resolve(conversation, name).orElse(null);
        if (target == null) {
            return Checked.answer(rejected(run, requestId, ToolRules.UNKNOWN_TOOL, "There is no tool "
                    + ToolRules.quoted(name) + " in this conversation. The tools you can call are those your tool "
                    + "list shows."));
        }
        EffectiveSettings effective = settings.effective(run.companyId(), conversation.agentId(),
                conversation.settingsOwner());
        // Steps 1 to 4, before anything binds.
        ToolRules.Decision mode = tools.decide(conversation, effective, target, name,
                ToolRules.BindingView.unbound());
        if (mode instanceof ToolRules.Decision.Refused refused) {
            return Checked.answer(refuse(run, conversation, target, refused, requestId));
        }
        ToolCheck checked = systemTools.check(target.system().kind(), target.tool(), arguments);
        if (checked instanceof ToolCheck.Invalid invalid) {
            return Checked.answer(rejected(run, requestId, invalid.code().name(), invalid.message()));
        }
        ToolCheck.Valid valid = (ToolCheck.Valid) checked;
        // A channel's thread reads that thread only (the package 5 contract, open point 13).
        ToolRules.Decision.Refused outside = tools.outsideThread(conversation, target, name, valid.arguments(),
                texts);
        if (outside != null) {
            return Checked.answer(refuse(run, conversation, target, outside, requestId));
        }
        // 5. The binding: resolved at the system's first use, then checked. A trigger's conversation has its
        // trigger's table's bindings only, made at its start: none on a system without a row (§5.7), but an MCP
        // server that needs no account, which binds without one (ADR-0071).
        Optional<BindingInfo> held = core.bindings().of(run.companyId(), conversation.id(), target.system().id());
        if (held.isEmpty() && conversation.triggered() && target.system().accountsRequired()) {
            ToolRules.Decision noRow = tools.decide(conversation, effective, target, name,
                    ToolRules.BindingView.noRow());
            if (noRow instanceof ToolRules.Decision.Refused refused) {
                return Checked.answer(refuse(run, conversation, target, refused, requestId));
            }
        }
        BindingInfo binding = held
            .orElseGet(() -> core.bindings().resolve(run.companyId(), conversation.id(), target.system().id(),
                    run.runId()));
        ToolRules.BindingView view;
        if (binding.bound()) {
            ConversationBindings.BindingCheck check = core.bindings().check(run.companyId(), binding.id());
            binding = check.binding();
            view = tools.view(run.companyId(), check, texts);
        }
        else {
            view = tools.view(run.companyId(), binding, texts);
        }
        if (binding.waiting()) {
            return Checked.answer(park(run, requestId, binding, name, target, valid, texts));
        }
        mode = tools.decide(conversation, effective, target, name, view);
        if (mode instanceof ToolRules.Decision.Refused refused) {
            return Checked.answer(refuse(run, conversation, target, refused, requestId));
        }
        if (mode instanceof ToolRules.Decision.Ask) {
            if (decisionStore.countOpen(conversation.id(), DecisionStore.Kind.APPROVAL) >= DecisionOps.MAX_OPEN) {
                return Checked.answer(rejected(run, requestId, TOO_MANY_REQUESTS, "This conversation has "
                        + DecisionOps.MAX_OPEN + " approvals open already, the most it may have. End your turn now: "
                        + "Tinfirm wakes you with the decisions."));
            }
            DecisionOps.Raised approval = decisions.approval(conversation, run.runId(), requestId,
                    DecisionSubject.toolCall(target.system().id(), name, target.tool(), valid.arguments(),
                            valid.what()),
                    null, true, approvalCard(name, valid.what(), texts.names(target.system())));
            return Checked.answer(RunnerMessages.RunRequestAck.pending(run.runId(), requestId,
                    approval.decision().id(), approvalPending(name, texts)));
        }
        ToolInfo served = Objects.requireNonNull(target.served());
        return new Checked(null, new ToolRunner.Prepared(run.companyId(), conversation.id(), run.runId(),
                conversation.agentId(), binding.id(), target.system().id(), name, target.tool(), valid.arguments(),
                served.effect().effective(), texts.agent(), texts.person()), valid.what(), served.replay());
    }

    /**
     * After the call, in the record's transaction, which locked the conversation: what the call left on its account
     * and binding, its answer (a parked call's, too), the journal row with that answer, and {@code tool.called} last
     * (CONVENTIONS §10: the account, then the journal row, then the audit).
     */
    private RunnerMessages.RunRequestAck answer(ActiveRun run, UUID requestId, ToolRunner.Prepared call,
            @Nullable String what, ToolRunner.Ran ran, int attempt) {
        ToolCallStore.Row current = journal.current(run.runId(), requestId).orElse(null);
        if (current != null && (current.state() != ToolCallStore.State.RUNNING || current.attempt() != attempt)) {
            // Settled meanwhile (the sweep, a request sent again elsewhere): the call still happened, and its answer is
            // the row's, as every replica answers it; nothing is parked.
            return settledMeanwhile(call, ran, current);
        }
        RunnerMessages.RunRequestAck answer = switch (ran) {
            case ToolRunner.Ran.Unavailable unavailable -> unavailable(run, requestId, call, what,
                    unavailable.binding());
            // The binding holds; the token could not be got right now (its endpoint down or busy): try again.
            case ToolRunner.Ran.Failed failed -> rejected(run, requestId, TOOL_FAILED, "Tinfirm could not get a "
                    + "token for " + systemName(run, call) + " right now; try again shortly.");
            // A kill switch went on after the call's check (D5: the reveal is the gate): nothing was called.
            case ToolRunner.Ran.Held held -> rejected(run, requestId, ToolRules.TOOL_DENIED, "The call did not run: "
                    + held.why() + ". End your turn now.");
            case ToolRunner.Ran.Called called -> {
                runner.effects(call, called);
                yield switch (called.outcome()) {
                    case ToolOutcome.Done done -> RunnerMessages.RunRequestAck.done(run.runId(), requestId,
                            ToolRunner.toolResult(done.result()), null);
                    case ToolOutcome.Refused refused -> rejected(run, requestId, refused.code().name(),
                            refused.message());
                    case ToolOutcome.Reconnect reconnect -> {
                        // The account turned RECONNECT and the binding waits for its card: the call waits with it.
                        BindingInfo binding = core.bindings().find(run.companyId(), call.bindingId())
                            .orElse(called.use().binding());
                        yield unavailable(run, requestId, call, what, binding);
                    }
                };
            }
        };
        if (!journal.finished(run.runId(), requestId, attempt, answer)) {
            ToolCallStore.Row settled = journal.current(run.runId(), requestId).orElse(null);
            if (ran instanceof ToolRunner.Ran.Called called) {
                runner.audited(call, called, "ALLOW", null, null);
            }
            return settled != null ? journal.answerOf(settled) : answer;
        }
        if (ran instanceof ToolRunner.Ran.Called called) {
            runner.audited(call, called, "ALLOW", null, null);
        }
        return answer;
    }

    /** A call whose row was settled while it ran: what it left and {@code tool.called}; the row's answer. */
    private RunnerMessages.RunRequestAck settledMeanwhile(ToolRunner.Prepared call, ToolRunner.Ran ran,
            ToolCallStore.Row row) {
        if (ran instanceof ToolRunner.Ran.Called called) {
            runner.effects(call, called);
            runner.audited(call, called, "ALLOW", null, null);
        }
        return journal.answerOf(row);
    }

    /**
     * The binding gave no token: its system was paused after the call's check (refused as the check refuses it), or
     * the binding waits (the call is parked) or is gone.
     */
    private RunnerMessages.RunRequestAck unavailable(ActiveRun run, UUID requestId, ToolRunner.Prepared call,
            @Nullable String what, BindingInfo binding) {
        ConversationInfo conversation = core.conversations().find(run.companyId(), run.conversationId())
            .orElseThrow();
        ConversationTools.Texts texts = tools.texts(conversation);
        String system = systemName(run, call);
        SystemInfo info = tools.systemOf(run.companyId(), call.systemId());
        if (info != null && info.paused()) {
            // Package 6, §5.4: a paused system's binding keeps its state and nothing uses it; the call never ran, and
            // its journal row ends with this answer.
            audit.denied(run.companyId(), conversation.id(), run.runId(), run.agentId(), call.systemId(), call.tool(),
                    ToolRules.Step.BINDING);
            return rejected(run, requestId, ToolRules.TOOL_DENIED, system + " is paused by an admin. Tell "
                    + texts.person() + " in your summary when your work needs it.");
        }
        if (binding.waiting()) {
            ParkedCalls.Park park = parked.park(binding, run.runId(), requestId, call.name(), call.tool(),
                    call.arguments(), what);
            return park == ParkedCalls.Park.FULL ? full(run, requestId, system)
                    : RunnerMessages.RunRequestAck.pending(run.runId(), requestId, binding.id(),
                            parkedMessage(system, texts.person()));
        }
        ToolRules.Names names = new ToolRules.Names(texts.agent(), texts.person(), system);
        audit.denied(run.companyId(), conversation.id(), run.runId(), run.agentId(), call.systemId(), call.tool(),
                ToolRules.Step.BINDING);
        return rejected(run, requestId, ToolRules.ACCOUNT_GONE, system + " can no longer be used in this "
                + "conversation (" + ToolRules.goneReason(binding.reason(), names) + "). " + texts.person()
                + " starts a new conversation to use " + system + "; tell them in your summary.");
    }

    private String systemName(ActiveRun run, ToolRunner.Prepared call) {
        return Optional.ofNullable(tools.systemOf(run.companyId(), call.systemId()))
            .map(info -> info.name())
            .orElse("The system");
    }

    private RunnerMessages.RunRequestAck park(ActiveRun run, UUID requestId, BindingInfo binding, String name,
            ConversationTools.Target target, ToolCheck.Valid valid, ConversationTools.Texts texts) {
        ParkedCalls.Park park = parked.park(binding, run.runId(), requestId, name, target.tool(), valid.arguments(),
                valid.what());
        if (park == ParkedCalls.Park.FULL) {
            return full(run, requestId, target.system().name());
        }
        return RunnerMessages.RunRequestAck.pending(run.runId(), requestId, binding.id(),
                parkedMessage(target.system().name(), texts.person()));
    }

    private RunnerMessages.RunRequestAck refuse(ActiveRun run, ConversationInfo conversation,
            ConversationTools.Target target, ToolRules.Decision.Refused refused, UUID requestId) {
        if (refused.step() != ToolRules.Step.SERVED) {
            audit.denied(run.companyId(), conversation.id(), run.runId(), run.agentId(), target.system().id(),
                    target.tool(), refused.step());
        }
        return rejected(run, requestId, refused.code(), refused.message());
    }

    private static RunnerMessages.RunRequestAck full(ActiveRun run, UUID requestId, String system) {
        return rejected(run, requestId, TOO_MANY_REQUESTS, ParkedCallStore.MAX_PARKED + " calls wait for " + system
                + " to be connected already, the most that may. End your turn now: Tinfirm wakes you once it is "
                + "connected.");
    }

    /** The answer to a call parked on a waiting binding (§5.3). */
    static String parkedMessage(String system, String person) {
        return system + " is not connected for this conversation yet: Tinfirm asked " + person + " to connect it, "
                + "and runs your call once they do. End your turn now.";
    }

    /** The answer to a call that waits for an approval. */
    static String approvalPending(String name, ConversationTools.Texts texts) {
        return "Your call of " + ToolRules.quoted(name) + " waits for " + texts.person() + "'s approval: it runs once "
                + "they approve it, and Tinfirm wakes you with the outcome and the result. When you have nothing else "
                + "to do, end your turn without a summary.";
    }

    /** An approval's card for a tool call: the tool and what the call does; the decision holds the arguments. */
    static String approvalCard(String name, @Nullable String what, ToolRules.Names names) {
        return names.agent() + " asks to call " + ToolRules.quoted(name) + " on " + names.system()
                + (what != null && !what.isBlank() ? ": " + what.strip() : "") + ".\n\nIt runs once you approve it.";
    }

    static RunnerMessages.RunRequestAck rejected(ActiveRun run, UUID requestId, String code, String message) {
        return RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, code, message);
    }
}
