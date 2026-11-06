package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.ToolCallResult;
import com.flowtivo.tinfirm.system.ToolInfo;
import com.flowtivo.tinfirm.system.ToolOutcome;
import com.flowtivo.tinfirm.system.ToolReplay;

import tools.jackson.databind.JsonNode;

/**
 * Parked calls (the package 3 contract, §5.3): a tool call that meets a waiting binding is parked on it, at most 10,
 * and answered {@code PENDING} with the binding's id; the turn ends {@code WAITING} ({@code ACCOUNT}).
 * <ul>
 * <li>When the binding is {@code BOUND} ({@link #bound}, in part 1's transaction), each parked call passes
 * {@code ConversationTools.modeOf} again with the access now: a refusal keeps the refusal; {@code ASK} raises its
 * approval, with the call's request id, while the conversation holds fewer than 10 open approvals (else the call is
 * refused with {@code TOO_MANY_REQUESTS}); {@code ALLOW} runs it after the commit, outside any transaction, with the
 * purpose {@code binding-call:{bindingId}}, and keeps its result, unless a kill switch holds the conversation
 * ({@link ConversationHolds}, M6 of the security review of 2026-10-27): while the company's runs are stopped it stays
 * parked, and the stop settles it ({@link #stopped}); while its trigger or its agent is paused it is refused (I18), as
 * an approved call is. Once none waits, a {@code CONNECTED} turn is requested, which part 1 starts once no decision and
 * no binding of the conversation is open; its {@code # Answers} tells the results.</li>
 * <li>When the company's runs are stopped ({@link #stopped}, in the stop's transaction), every parked call that has
 * not started is refused ({@code COMPANY_STOPPED}), and its {@code CONNECTED} turn is requested after the commit: it
 * waits in the queue until the stop is lifted, and its {@code # Answers} then tells the agent that the call did not
 * run.</li>
 * <li>A call whose token cannot be got right now (the binding holds) stays {@code ALLOWED}, and the recovery
 * ({@link CallRecovery}) runs it again later, as it runs the calls a restart left {@code ALLOWED} and settles those it
 * left {@code RUNNING} ({@link #recover}).</li>
 * <li>When the binding is {@code GONE} ({@link #gone}), its parked calls are answered with the reason, which the next
 * turn tells; that turn is requested as {@code CONNECTED} too, so that the agent goes on without the system.</li>
 * <li>When the conversation ends or is cancelled ({@link #cancel}), its parked calls are cancelled.</li>
 * </ul>
 */
@Component
class ParkedCalls implements BindingHook {

    private static final Logger log = LoggerFactory.getLogger(ParkedCalls.class);

    private final ParkedCallStore store;

    private final ConversationStore conversations;

    private final ConversationCore core;

    private final ConversationTools tools;

    private final DecisionOps decisions;

    private final ToolRunner runner;

    private final Settings settings;

    private final ConversationWorkers workers;

    private final SystemTransactions transactions;

    private final ConversationHolds holds;

    private final DecisionStore decisionStore;

    private final TimeSource time;

    ParkedCalls(ParkedCallStore store, ConversationStore conversations, ConversationCore core, ConversationTools tools,
            DecisionOps decisions, ToolRunner runner, Settings settings, ConversationWorkers workers,
            SystemTransactions transactions, ConversationHolds holds, DecisionStore decisionStore, TimeSource time) {
        this.store = store;
        this.conversations = conversations;
        this.core = core;
        this.tools = tools;
        this.decisions = decisions;
        this.runner = runner;
        this.settings = settings;
        this.workers = workers;
        this.transactions = transactions;
        this.holds = holds;
        this.decisionStore = decisionStore;
        this.time = time;
    }

    /** What parking answered. */
    enum Park {
        /** The call waits on the binding (also when its request id was parked before). */
        PARKED,
        /** The binding holds as many calls as it may. */
        FULL
    }

    /**
     * Parks a call on a waiting binding, in the caller's transaction; a request id parked before is not parked twice.
     * A binding makes room by dropping the calls whose results a turn told already.
     */
    Park park(BindingInfo binding, UUID runId, UUID requestId, String name, String tool, JsonNode arguments,
            @Nullable String what) {
        ParkedCallStore.Parked parked = store.lock(binding.id()).orElseThrow();
        if (parked.calls().stream().anyMatch(call -> call.requestId().equals(requestId))) {
            return Park.PARKED;
        }
        List<ParkedCallStore.ParkedCall> calls = new ArrayList<>(parked.calls()
            .stream()
            .filter(call -> call.status().pending() || call.reportedRunId() == null)
            .toList());
        if (calls.size() >= ParkedCallStore.MAX_PARKED) {
            return Park.FULL;
        }
        calls.add(new ParkedCallStore.ParkedCall(runId, requestId, name, tool, arguments, what,
                ParkedCallStore.Status.PARKED, null, time.now(), null, null, null));
        store.write(binding.id(), calls);
        return Park.PARKED;
    }

    // ---- BindingHook ------------------------------------------------------------------------------------------------

    /**
     * The binding is {@code BOUND}: each parked call passes the tools' rule again (§5.3). In part 1's transaction,
     * which locked the conversation before the binding (CONVENTIONS §10).
     */
    @Override
    public void bound(BindingInfo binding) {
        // The conversation first: at no cost, since every transaction that binds holds it already.
        conversations.lock(binding.companyId(), binding.conversationId());
        ParkedCallStore.Parked parked = store.lock(binding.id()).orElse(null);
        if (parked == null || parked.calls().stream().noneMatch(call -> call.status() == ParkedCallStore.Status.PARKED)) {
            return;
        }
        ConversationInfo conversation = core.conversations().find(binding.companyId(), binding.conversationId())
            .orElse(null);
        EffectiveSettings effective = conversation != null && conversation.open() ? effective(conversation) : null;
        if (conversation == null || effective == null) {
            settle(parked, ParkedCallStore.Status.CANCELLED, "The call did not run: the conversation ended.");
            return;
        }
        // M6: the kill switches, read once. The reveal refuses them too (D5); this says why in the thread.
        ConversationHolds.Held held = holds.of(conversation).orElse(null);
        ConversationTools.Texts texts = tools.texts(conversation);
        ToolRules.BindingView view = tools.view(binding.companyId(), binding, texts);
        // The approvals open now, counted once: the bind raises none past the ten (§5.8).
        int approvals = decisionStore.countOpen(conversation.id(), DecisionStore.Kind.APPROVAL);
        Instant now = time.now();
        List<ParkedCallStore.ParkedCall> next = new ArrayList<>();
        boolean allowed = false;
        boolean kept = false;
        for (ParkedCallStore.ParkedCall call : parked.calls()) {
            if (call.status() != ParkedCallStore.Status.PARKED) {
                next.add(call);
                continue;
            }
            Optional<ConversationTools.Target> target = tools.resolve(conversation, call.name())
                .filter(found -> found.system().id().equals(binding.systemId()));
            ToolRules.Decision decision = target.isEmpty()
                    ? new ToolRules.Decision.Refused(ToolRules.Step.SERVED, ToolRules.UNKNOWN_TOOL, "There is no "
                            + "tool " + ToolRules.quoted(call.name()) + " in this conversation any more.")
                    : tools.decide(conversation, effective, target.get(), call.name(), view);
            switch (decision) {
                case ToolRules.Decision.Refused refused -> next.add(call.settled(ParkedCallStore.Status.REFUSED,
                        "The call did not run: " + refused.message(), now));
                case ToolRules.Decision.Ask ask -> {
                    if (approvals >= DecisionOps.MAX_OPEN) {
                        next.add(call.settled(ParkedCallStore.Status.REFUSED, TOO_MANY_APPROVALS, now));
                    }
                    else {
                        // Approving it is refused while the agent is paused (I18), so raising it is not.
                        decisions.approval(conversation, call.runId(), call.requestId(), DecisionSubject.toolCall(
                                binding.systemId(), call.name(), call.tool(), call.arguments(), call.what()), null,
                                true, ToolCalls.approvalCard(call.name(), call.what(),
                                        texts.names(target.get().system())));
                        approvals++;
                    }
                }
                case ToolRules.Decision.Allow allow -> {
                    if (held != null && held.hold() == ConversationBindings.Hold.COMPANY_STOPPED) {
                        // M6: it stays parked while the company's runs are stopped; the stop settles it.
                        next.add(call);
                        kept = true;
                    }
                    else if (held != null) {
                        // I18 and M6: a paused agent's or trigger's calls do not run, as its approved calls do not.
                        next.add(call.settled(ParkedCallStore.Status.REFUSED, refused(held), now));
                    }
                    else {
                        next.add(call.claimed(ParkedCallStore.Status.ALLOWED, now));
                        allowed = true;
                    }
                }
            }
        }
        store.write(binding.id(), next);
        if (allowed) {
            UUID companyId = binding.companyId();
            UUID bindingId = binding.id();
            workers.afterCommit(companyId, "The parked calls of binding " + bindingId,
                    () -> runAllowed(companyId, bindingId));
        }
        else if (!kept) {
            core.turns().request(binding.companyId(), binding.conversationId(), TurnReason.CONNECTED);
        }
    }

    /** Why a call refused for the ten open approvals did not run. */
    static final String TOO_MANY_APPROVALS = "The call did not run (" + ToolCalls.TOO_MANY_REQUESTS + "): this "
            + "conversation had " + DecisionOps.MAX_OPEN + " approvals open already, the most it may have. Call it "
            + "again once they are decided.";

    /** Why a call did not run under a kill switch (I18, M6): e.g. "The call did not run: Triage is paused." */
    static String refused(ConversationHolds.Held held) {
        return "The call did not run: " + held.why() + ".";
    }

    /** Why a parked call did not run once the company's runs were stopped (M6). */
    static final String COMPANY_STOPPED = "The call did not run (COMPANY_STOPPED): the company's runs were stopped by "
            + "an admin while it waited. Call it again once the stop is lifted, if it is still needed.";

    /**
     * The company's runs are being stopped (M6), in the stop's transaction, which locked the company's row: every
     * parked call of the company that has not started ({@code PARKED} or {@code ALLOWED}) is refused
     * {@code COMPANY_STOPPED}, each conversation's row locked before its bindings', in the order of their ids
     * (CONVENTIONS §10). A call that is running keeps running: its token was revealed before the stop. After the commit
     * each open conversation's {@code CONNECTED} turn is requested, which waits in the queue until the stop is lifted
     * ({@code COMPANY_STOPPED}) and then tells the agent.
     *
     * @return the conversations whose calls were refused
     */
    List<UUID> stopped(UUID companyId) {
        List<UUID> conversationIds = store.conversationsWithWaitingCalls(companyId);
        Instant now = time.now();
        for (UUID conversationId : conversationIds) {
            conversations.lock(companyId, conversationId);
            for (ParkedCallStore.Parked parked : store.lockOfConversation(conversationId)) {
                List<ParkedCallStore.ParkedCall> next = parked.calls()
                    .stream()
                    .map(call -> call.status() == ParkedCallStore.Status.PARKED
                            || call.status() == ParkedCallStore.Status.ALLOWED
                                    ? call.settled(ParkedCallStore.Status.REFUSED, COMPANY_STOPPED, now) : call)
                    .toList();
                if (!next.equals(parked.calls())) {
                    store.write(parked.bindingId(), next);
                }
            }
        }
        if (!conversationIds.isEmpty()) {
            workers.afterCommit(companyId, "The turns of the parked calls the company's stop refused",
                    () -> conversationIds.forEach(conversationId -> requestConnected(companyId, conversationId)));
        }
        return conversationIds;
    }

    /** The {@code CONNECTED} turn of an open conversation, in a transaction of its own; a failure is logged. */
    private void requestConnected(UUID companyId, UUID conversationId) {
        try {
            transactions.runInCompany(companyId, () -> {
                ConversationInfo conversation = core.conversations().find(companyId, conversationId).orElse(null);
                if (conversation != null && conversation.open()) {
                    core.turns().request(companyId, conversationId, TurnReason.CONNECTED);
                }
            });
        }
        catch (RuntimeException ex) {
            log.warn("The turn of conversation {} after the company's stop could not be requested", conversationId,
                    ex);
        }
    }

    /** The binding is {@code GONE}: its parked calls are answered with the reason, in the next turn (§5.3). */
    @Override
    public void gone(BindingInfo binding) {
        conversations.lock(binding.companyId(), binding.conversationId());
        ParkedCallStore.Parked parked = store.lock(binding.id()).orElse(null);
        if (parked == null || parked.calls().stream().noneMatch(call -> call.status().pending())) {
            return;
        }
        ConversationInfo conversation = core.conversations().find(binding.companyId(), binding.conversationId())
            .orElse(null);
        String why = "its account is gone";
        String system = "The system";
        if (conversation != null) {
            ConversationTools.Texts texts = tools.texts(conversation);
            var info = tools.systemOf(binding.companyId(), binding.systemId());
            ToolRules.Names names = info != null ? texts.names(info)
                    : new ToolRules.Names(texts.agent(), texts.person(), "The system");
            system = names.system();
            why = ToolRules.goneReason(binding.reason(), names);
        }
        settle(parked, ParkedCallStore.Status.REFUSED, "The call did not run: " + system + " can no longer be used "
                + "in this conversation (" + why + ").");
        if (conversation != null && conversation.open()) {
            core.turns().request(binding.companyId(), binding.conversationId(), TurnReason.CONNECTED);
        }
    }

    /** The conversation was ended or cancelled: its parked calls are cancelled. In the caller's transaction. */
    void cancel(UUID conversationId, String cause) {
        for (ParkedCallStore.Parked parked : store.lockOfConversation(conversationId)) {
            settle(parked, ParkedCallStore.Status.CANCELLED, "The call did not run: it was cancelled ("
                    + AnswersSection.cause(cause) + ").");
        }
    }

    private void settle(ParkedCallStore.Parked parked, ParkedCallStore.Status status, String result) {
        List<ParkedCallStore.ParkedCall> next = parked.calls()
            .stream()
            .map(call -> call.status().pending() ? call.settled(status, result, time.now()) : call)
            .toList();
        store.write(parked.bindingId(), next);
    }

    // ---- running the allowed calls, after the commit ----------------------------------------------------------------

    /** A parked call taken to run, with what running it needs. */
    private record Taken(ParkedCallStore.ParkedCall call, ToolRunner.Prepared prepared) {
    }

    /**
     * Runs the binding's {@code ALLOWED} calls, each outside any transaction with the purpose
     * {@code binding-call:{bindingId}}, keeps their results, and requests the {@code CONNECTED} turn once none waits.
     */
    void runAllowed(UUID companyId, UUID bindingId) {
        List<Taken> taken = transactions.inCompany(companyId, () -> take(companyId, bindingId));
        List<Ran> ran = new ArrayList<>();
        for (Taken one : taken) {
            ToolRunner.Ran outcome;
            try {
                outcome = runner.run(one.prepared(), "binding-call:" + bindingId);
            }
            catch (RuntimeException ex) {
                log.warn("Parked call {} of binding {} failed", one.call().requestId(), bindingId, ex);
                outcome = null;
            }
            ran.add(new Ran(one, outcome));
        }
        transactions.runInCompany(companyId, () -> settleRan(companyId, bindingId, ran));
    }

    private record Ran(Taken taken, ToolRunner.@Nullable Ran outcome) {
    }

    private List<Taken> take(UUID companyId, UUID bindingId) {
        lockConversationOf(companyId, bindingId);
        ParkedCallStore.Parked parked = store.lock(bindingId).orElse(null);
        if (parked == null) {
            return List.of();
        }
        ConversationInfo conversation = core.conversations().find(companyId, parked.conversationId()).orElse(null);
        if (conversation == null || !conversation.open()) {
            return List.of();
        }
        // I18 and M6: a kill switch may have gone on since the bind; its calls do not run then.
        ConversationHolds.Held held = holds.of(conversation).orElse(null);
        ConversationTools.Texts texts = tools.texts(conversation);
        Instant now = time.now();
        List<Taken> taken = new ArrayList<>();
        List<ParkedCallStore.ParkedCall> next = new ArrayList<>();
        for (ParkedCallStore.ParkedCall call : parked.calls()) {
            if (call.status() != ParkedCallStore.Status.ALLOWED) {
                next.add(call);
                continue;
            }
            if (held != null && held.hold() == ConversationBindings.Hold.COMPANY_STOPPED) {
                // Parked again while the company's runs are stopped; the stop settles it.
                next.add(call.with(ParkedCallStore.Status.PARKED));
                continue;
            }
            if (held != null) {
                next.add(call.settled(ParkedCallStore.Status.REFUSED, refused(held), now));
                continue;
            }
            Optional<ConversationTools.Target> target = tools.resolve(conversation, call.name());
            if (target.isEmpty() || target.get().served() == null) {
                next.add(call.settled(ParkedCallStore.Status.REFUSED, "The call did not run: there is no tool "
                        + ToolRules.quoted(call.name()) + " in this conversation any more.", now));
                continue;
            }
            // Taken under the binding's lock: no other thread or replica runs it too.
            ParkedCallStore.ParkedCall running = call.claimed(ParkedCallStore.Status.RUNNING, now);
            next.add(running);
            taken.add(new Taken(running, new ToolRunner.Prepared(companyId, conversation.id(), call.runId(),
                    conversation.agentId(), bindingId, parked.systemId(), call.name(), call.tool(), call.arguments(),
                    target.get().served().effect().effective(), texts.agent(), texts.person())));
        }
        store.write(bindingId, next);
        return taken;
    }

    private void settleRan(UUID companyId, UUID bindingId, List<Ran> ran) {
        lockConversationOf(companyId, bindingId);
        ParkedCallStore.Parked parked = store.lock(bindingId).orElse(null);
        if (parked == null) {
            return;
        }
        List<ParkedCallStore.ParkedCall> next = new ArrayList<>(parked.calls());
        for (Ran one : ran) {
            ParkedCallStore.ParkedCall call = one.taken().call();
            ParkedCallStore.ParkedCall settled = switch (one.outcome()) {
                case null -> call.settled(ParkedCallStore.Status.REFUSED, "The call did not run: Tinfirm could not "
                        + "run it.", time.now());
                case ToolRunner.Ran.Unavailable unavailable -> unavailable.binding().waiting()
                        ? call.with(ParkedCallStore.Status.PARKED)
                        : call.settled(ParkedCallStore.Status.REFUSED, "The call did not run: its system can no "
                                + "longer be used in this conversation.", time.now());
                // The binding holds, and the token could not be got right now: it runs again later (CallRecovery).
                case ToolRunner.Ran.Failed failed -> call.claimed(ParkedCallStore.Status.ALLOWED, time.now());
                // A kill switch went on after it was taken (D5: the reveal refused it); the stop's settling ran before,
                // so it is refused here, never parked again.
                case ToolRunner.Ran.Held held -> call.settled(ParkedCallStore.Status.REFUSED, "The call did not run: "
                        + held.why() + ".", time.now());
                case ToolRunner.Ran.Called called -> {
                    runner.record(one.taken().prepared(), called, "ALLOW", null, bindingId);
                    yield settledBy(call, called.outcome());
                }
            };
            replace(next, call.requestId(), settled);
        }
        store.write(bindingId, next);
        if (next.stream().noneMatch(call -> call.status().pending())) {
            core.turns().request(companyId, parked.conversationId(), TurnReason.CONNECTED);
        }
    }

    // ---- what a restart left behind ---------------------------------------------------------------------------------


    /**
     * The recovery's pass over one binding ({@link CallRecovery}), safe on several replicas at once since every step
     * claims under the binding's lock: a call left {@code RUNNING} since before {@code runningBefore} may have written,
     * so it is settled as of unknown outcome and never run again; a call left {@code PARKED} on a bound binding passes
     * the tools' rule again ({@link #bound}); then the calls {@code ALLOWED} since before {@code startedBefore} run,
     * outside any transaction, as after a bind. The {@code CONNECTED} turn follows once none is pending.
     */
    void recover(UUID companyId, UUID bindingId, Instant startedBefore, Instant runningBefore) {
        boolean due = Boolean.TRUE.equals(transactions.inCompany(companyId, () -> {
            lockConversationOf(companyId, bindingId);
            ParkedCallStore.Parked parked = store.lock(bindingId).orElse(null);
            if (parked == null) {
                return false;
            }
            Instant now = time.now();
            ConversationInfo conversation = core.conversations().find(companyId, parked.conversationId()).orElse(null);
            List<ParkedCallStore.ParkedCall> next = new ArrayList<>();
            boolean lost = false;
            for (ParkedCallStore.ParkedCall call : parked.calls()) {
                if (call.status() == ParkedCallStore.Status.RUNNING && claimedBefore(call, runningBefore)) {
                    ToolInfo served = conversation != null ? tools.resolve(conversation, call.name())
                        .filter(found -> found.system().id().equals(parked.systemId())
                                && found.tool().equals(call.tool()))
                        .map(ConversationTools.Target::served)
                        .orElse(null) : null;
                    if (served != null && served.replay() == ToolReplay.SAFE) {
                        // Package R, §3.3: a SAFE call runs again, as one allowed and never taken.
                        next.add(call.with(ParkedCallStore.Status.ALLOWED));
                        lost = true;
                        continue;
                    }
                    SystemInfo system = tools.systemOf(companyId, parked.systemId());
                    next.add(call.settled(ParkedCallStore.Status.REFUSED, ToolJournal.restarted(call.name(),
                            system != null ? system.name() : "its system"), now));
                    lost = true;
                }
                else {
                    next.add(call);
                }
            }
            if (lost) {
                log.warn("Binding {} held calls running when Tinfirm stopped; the SAFE ones run again, the others are "
                        + "settled as of unknown outcome", bindingId);
                store.write(bindingId, next);
                if (next.stream().noneMatch(call -> call.status().pending())) {
                    core.turns().request(companyId, parked.conversationId(), TurnReason.CONNECTED);
                }
            }
            BindingInfo binding = core.bindings().find(companyId, bindingId).orElse(null);
            if (binding != null && binding.bound()
                    && next.stream().anyMatch(call -> call.status() == ParkedCallStore.Status.PARKED)) {
                bound(binding);
            }
            return next.stream().anyMatch(call -> call.status() == ParkedCallStore.Status.ALLOWED
                    && claimedBefore(call, startedBefore));
        }));
        if (due) {
            runAllowed(companyId, bindingId);
        }
    }

    private static boolean claimedBefore(ParkedCallStore.ParkedCall call, Instant cutoff) {
        return (call.claimedAt() != null ? call.claimedAt() : call.parkedAt()).isBefore(cutoff);
    }

    /**
     * Locks the binding's conversation's row, in the transactions of this class's own that start at a binding:
     * settling the calls audits them, checks the binding again and may start the {@code CONNECTED} turn, whose run
     * comes after the conversation (CONVENTIONS §10).
     */
    private void lockConversationOf(UUID companyId, UUID bindingId) {
        core.bindings().find(companyId, bindingId)
            .ifPresent(binding -> conversations.lock(companyId, binding.conversationId()));
    }

    private ParkedCallStore.ParkedCall settledBy(ParkedCallStore.ParkedCall call, ToolOutcome outcome) {
        return switch (outcome) {
            case ToolOutcome.Done done -> call.settled(ParkedCallStore.Status.DONE, result(done.result()), time.now());
            // The binding waits again for its card: the call stays parked.
            case ToolOutcome.Reconnect reconnect -> call.with(ParkedCallStore.Status.PARKED);
            case ToolOutcome.Refused refused -> call.settled(ParkedCallStore.Status.REFUSED, "The call did not run ("
                    + refused.code().name() + "): " + refused.message(), time.now());
        };
    }

    /** A system's answer as the next turn tells it, at most 50 000 characters. */
    static String result(ToolCallResult result) {
        String text = result.text();
        return DecisionOps.cut((result.isError() ? "The system answered with an error: " : "") + (text.isBlank()
                ? "(no result)" : text), DecisionOps.MAX_ANSWER_LENGTH);
    }

    private static void replace(List<ParkedCallStore.ParkedCall> calls, UUID requestId,
            ParkedCallStore.ParkedCall settled) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).requestId().equals(requestId)) {
                calls.set(i, settled);
                return;
            }
        }
    }

    private @Nullable EffectiveSettings effective(ConversationInfo conversation) {
        try {
            return settings.effective(conversation.companyId(), conversation.agentId(),
                    conversation.settingsOwner());
        }
        catch (IllegalArgumentException ex) {
            // A deleted agent: its conversations end.
            return null;
        }
    }
}
