package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.ToolInfo;
import com.flowtivo.tinfirm.system.ToolOutcome;
import com.flowtivo.tinfirm.system.ToolReplay;

/**
 * What an approval sets going once the transaction that approved it committed (the package 3 contract, §5.8):
 * <ul>
 * <li>An approved tool call passes steps 1 to 5 again and runs, with the conversation's binding and the purpose
 * {@code decision-tool:{decisionId}}, unless a kill switch holds the conversation (the company's runs stopped, its
 * trigger or its agent paused: {@link ConversationHolds}); its result, or why it did not run, is the
 * decision's {@code result}, and the {@code DECIDED} turn is requested after it, so that its {@code # Answers} tells
 * it. The contract runs the call in the deciding transaction; package 3d's rule that no system is called inside a
 * transaction (CONVENTIONS §10) moves it right after the commit, on a thread of its own. It is taken to run under the
 * decision's lock ({@code decision.call_started_at}), so that no other thread or replica runs it too; a token that
 * cannot be got right now gives it back, and the recovery ({@link CallRecovery}) runs it again later, as it runs one
 * a restart left behind, and settles one a restart caught running ({@link #recover}).</li>
 * <li>An approved change request is opened by part 3 ({@link ChangeRequestOpening}).</li>
 * </ul>
 */
@Component
class ApprovedCalls {

    private static final Logger log = LoggerFactory.getLogger(ApprovedCalls.class);

    private final DecisionStore store;

    private final DecisionOps decisions;

    private final ConversationCore core;

    private final ConversationTools tools;

    private final ToolRunner runner;

    private final ToolAudit audit;

    private final Agents agents;

    private final Settings settings;

    private final ConversationWorkers workers;

    private final SystemTransactions transactions;

    private final ObjectProvider<ChangeRequestOpening> openings;

    private final TimeSource time;

    private final ConversationHolds holds;

    ApprovedCalls(DecisionStore store, DecisionOps decisions, ConversationCore core, ConversationTools tools,
            ToolRunner runner, ToolAudit audit, Agents agents, Settings settings, ConversationWorkers workers,
            SystemTransactions transactions, ObjectProvider<ChangeRequestOpening> openings, TimeSource time,
            ConversationHolds holds) {
        this.holds = holds;
        this.store = store;
        this.decisions = decisions;
        this.core = core;
        this.tools = tools;
        this.runner = runner;
        this.audit = audit;
        this.agents = agents;
        this.settings = settings;
        this.workers = workers;
        this.transactions = transactions;
        this.openings = openings;
        this.time = time;
    }

    /** Runs the approved call after the current transaction committed. */
    void runAfterCommit(UUID companyId, UUID decisionId) {
        workers.afterCommit(companyId, "The approved call of decision " + decisionId, () -> run(companyId,
                decisionId));
    }

    /** Opens the approved change request after the current transaction committed, through part 3. */
    void openAfterCommit(UUID companyId, UUID conversationId, UUID decisionId, UUID repositoryId) {
        ChangeRequestOpening opening = openings.getIfUnique();
        if (opening == null) {
            return;
        }
        workers.afterCommit(companyId, "The approved change request of decision " + decisionId,
                () -> opening.approved(companyId, conversationId, decisionId, repositoryId));
    }

    /** What running an approved call needs, or why it does not run. */
    private record Prepared(ToolRunner.@Nullable Prepared call, @Nullable String refused, UUID conversationId) {
    }

    /** The approved call, outside any transaction; its result kept on the decision; then the {@code DECIDED} turn. */
    void run(UUID companyId, UUID decisionId) {
        ToolRunner.Prepared call = transactions.inCompany(companyId, () -> take(companyId, decisionId));
        if (call == null) {
            return;
        }
        ToolRunner.Ran ran = null;
        try {
            ran = runner.run(call, "decision-tool:" + decisionId);
        }
        catch (RuntimeException ex) {
            log.warn("The approved call of decision {} failed", decisionId, ex);
        }
        ToolRunner.Ran outcome = ran;
        transactions.runInCompany(companyId, () -> settle(companyId, decisionId, call, outcome));
    }

    /**
     * Takes the approved call to run, under the conversation's lock and then the decision's: nothing when it has a
     * result or was taken already. One that cannot run now is settled at once with why, and its {@code DECIDED} turn
     * requested.
     */
    private ToolRunner.@Nullable Prepared take(UUID companyId, UUID decisionId) {
        DecisionStore.DecisionRow found = store.find(decisionId).orElse(null);
        if (found == null || !pending(found)) {
            return null;
        }
        // The conversation first (CONVENTIONS §10): checking the binding may change it, which audits and may start the
        // CONNECTED turn.
        store.lockConversation(found.conversationId());
        DecisionStore.DecisionRow decision = store.lock(decisionId).orElse(null);
        if (decision == null || !pending(decision)) {
            return null;
        }
        Prepared prepared = prepare(companyId, decision);
        if (prepared.call() == null) {
            decisions.recordResult(decisionId, Objects.requireNonNullElse(prepared.refused(),
                    "The approved call did not run."));
            core.turns().request(companyId, prepared.conversationId(), TurnReason.DECIDED);
            return null;
        }
        store.claimCall(decisionId, time.now());
        return prepared.call();
    }

    /** An approved call without a result that nobody took to run yet. */
    private static boolean pending(DecisionStore.DecisionRow decision) {
        return decision.approved() && decision.result() == null && decision.callStartedAt() == null;
    }

    private Prepared prepare(UUID companyId, DecisionStore.DecisionRow decision) {
        DecisionSubject subject = decision.requireSubject();
        ConversationInfo conversation = core.conversations().find(companyId, decision.conversationId()).orElse(null);
        if (conversation == null || !conversation.open()) {
            return new Prepared(null, "The approved call did not run: the conversation ended.",
                    decision.conversationId());
        }
        AgentInfo agent = agents.find(companyId, conversation.agentId()).orElse(null);
        if (agent == null || !agent.live()) {
            return new Prepared(null, "The approved call did not run: the agent was deleted.", conversation.id());
        }
        // G4: an approved call not yet run does not run while the company's runs are stopped (package 6), its
        // trigger is paused (M6 of the security review of 2026-10-27) or its agent is paused (I18). An early exit that
        // says why: the reveal is the gate.
        Optional<ConversationHolds.Held> held = holds.of(conversation);
        if (held.isPresent()) {
            return new Prepared(null, "The approved call did not run: " + held.get().why() + ".", conversation.id());
        }
        UUID systemId = Objects.requireNonNull(subject.systemId());
        String name = Objects.requireNonNull(subject.name());
        String tool = Objects.requireNonNull(subject.tool());
        SystemInfo system = tools.systemOf(companyId, systemId);
        if (system == null) {
            return new Prepared(null, "The approved call did not run: its system was removed.", conversation.id());
        }
        ConversationTools.Texts texts = tools.texts(conversation);
        ConversationTools.Target target = new ConversationTools.Target(system, tool, tools.resolve(conversation, name)
            .filter(found -> found.system().id().equals(systemId) && found.tool().equals(tool))
            .map(ConversationTools.Target::served)
            .orElse(null));
        EffectiveSettings effective = settings.effective(companyId, conversation.agentId(),
                conversation.settingsOwner());
        // An approved call passes steps 1 to 5 again when it runs (§5.4).
        BindingInfo binding = core.bindings().of(companyId, conversation.id(), systemId).orElse(null);
        if (binding == null) {
            return new Prepared(null, "The approved call did not run: " + system.name() + " is not connected for "
                    + "this conversation.", conversation.id());
        }
        ConversationBindings.BindingCheck check = core.bindings().check(companyId, binding.id());
        if (check instanceof ConversationBindings.BindingCheck.Waiting waiting) {
            return new Prepared(null, "The approved call did not run: " + system.name() + " is not connected for "
                    + "this conversation now; it waits until " + ToolRules.waitingReason(waiting.binding().reason(),
                            texts.names(system)) + ".", conversation.id());
        }
        ToolRules.Decision mode = tools.decide(conversation, effective, target, name, tools.view(companyId, check,
                texts));
        if (mode instanceof ToolRules.Decision.Refused refused) {
            if (decision.runId() != null && refused.step() != ToolRules.Step.SERVED) {
                audit.denied(companyId, conversation.id(), decision.runId(), conversation.agentId(), systemId, tool,
                        refused.step());
            }
            return new Prepared(null, "The approved call did not run: " + refused.message(), conversation.id());
        }
        return new Prepared(new ToolRunner.Prepared(companyId, conversation.id(), decision.runId(),
                conversation.agentId(), binding.id(), systemId, name, tool, Objects.requireNonNull(subject.arguments()),
                Objects.requireNonNull(target.served()).effect().effective(), texts.agent(), texts.person()), null,
                conversation.id());
    }

    private void settle(UUID companyId, UUID decisionId, ToolRunner.Prepared call, ToolRunner.@Nullable Ran ran) {
        // The conversation first (CONVENTIONS §10): the call's audit, the binding's check, then the DECIDED turn.
        store.lockConversation(call.conversationId());
        store.lock(decisionId);
        String result = switch (ran) {
            case null -> "The approved call did not run: Tinfirm could not run it.";
            // The binding holds and nothing was called: given back below.
            case ToolRunner.Ran.Failed failed -> null;
            case ToolRunner.Ran.Unavailable unavailable -> "The approved call did not run: its system is not "
                    + "connected for this conversation now.";
            // D5: a kill switch went on after the call was prepared; the reveal refused it.
            case ToolRunner.Ran.Held held -> "The approved call did not run: " + held.why() + ".";
            case ToolRunner.Ran.Called called -> {
                runner.record(call, called, "ASK", decisionId, null);
                yield switch (called.outcome()) {
                    case ToolOutcome.Done done -> ParkedCalls.result(done.result());
                    case ToolOutcome.Reconnect reconnect -> "The approved call did not run: the system refused the "
                            + "account's credential, which must be signed in again.";
                    case ToolOutcome.Refused refused -> "The approved call did not run (" + refused.code().name()
                            + "): " + refused.message();
                };
            }
        };
        if (result == null) {
            // Its token could not be got right now: the call is given back, and the recovery runs it again later.
            store.releaseCall(decisionId);
            return;
        }
        decisions.recordResult(decisionId, result);
        core.turns().request(companyId, call.conversationId(), TurnReason.DECIDED);
    }

    // ---- what a restart left behind ---------------------------------------------------------------------------------

    /**
     * The recovery's pass over one approved call without a result ({@link CallRecovery}), safe on several replicas at
     * once: one taken to run before {@code runningBefore} was running when Tinfirm stopped. Its tool {@code SAFE}
     * (package R, §3.3), it is given back and runs again now; {@code UNSAFE}, it may have written, so it is settled as
     * of unknown outcome with the words of {@code TOOL_INTERRUPTED} and never run again, and its {@code DECIDED} turn
     * requested. One nobody took runs now, as after its approval ({@link #run}, which takes it under the decision's
     * lock).
     */
    void recover(UUID companyId, UUID decisionId, Instant runningBefore) {
        boolean lost = Boolean.TRUE.equals(transactions.inCompany(companyId, () -> {
            DecisionStore.DecisionRow found = store.find(decisionId).orElse(null);
            if (found == null || !runningSince(found, runningBefore)) {
                return false;
            }
            store.lockConversation(found.conversationId());
            DecisionStore.DecisionRow decision = store.lock(decisionId).orElse(null);
            if (decision == null || !runningSince(decision, runningBefore)) {
                return false;
            }
            DecisionSubject subject = decision.requireSubject();
            if (replayOf(companyId, decision.conversationId(), subject) == ToolReplay.SAFE) {
                log.info("The approved call of decision {} was running when Tinfirm stopped; it is SAFE and runs "
                        + "again", decisionId);
                store.releaseCall(decisionId);
                return false;
            }
            log.warn("The approved call of decision {} was running when Tinfirm stopped; it is settled as of unknown "
                    + "outcome", decisionId);
            SystemInfo system = subject.systemId() != null ? tools.systemOf(companyId, subject.systemId()) : null;
            decisions.recordResult(decisionId, ToolJournal.restarted(Objects.requireNonNullElse(subject.name(),
                    "the tool"), system != null ? system.name() : "its system"));
            core.turns().request(companyId, decision.conversationId(), TurnReason.DECIDED);
            return true;
        }));
        if (!lost) {
            run(companyId, decisionId);
        }
    }

    /**
     * The replay class of an approved call's tool as its conversation lists it now; {@code UNSAFE} when the tool is not
     * served any more.
     */
    private ToolReplay replayOf(UUID companyId, UUID conversationId, DecisionSubject subject) {
        ConversationInfo conversation = core.conversations().find(companyId, conversationId).orElse(null);
        if (conversation == null || subject.name() == null) {
            return ToolReplay.UNSAFE;
        }
        return tools.resolve(conversation, subject.name())
            .filter(found -> found.system().id().equals(subject.systemId()) && found.tool().equals(subject.tool()))
            .map(ConversationTools.Target::served)
            .map(ToolInfo::replay)
            .orElse(ToolReplay.UNSAFE);
    }

    private static boolean runningSince(DecisionStore.DecisionRow decision, Instant before) {
        return decision.approved() && decision.result() == null && decision.callStartedAt() != null
                && decision.callStartedAt().isBefore(before);
    }
}
