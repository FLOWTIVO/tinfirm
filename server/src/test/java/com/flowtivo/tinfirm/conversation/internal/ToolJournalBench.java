package com.flowtivo.tinfirm.conversation.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;

import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;
import com.flowtivo.tinfirm.system.SystemTools;

import tools.jackson.databind.JsonNode;

/**
 * The bench of the call journal's tests (package R, §3.2; TEST FIXTURE ONLY): part 2's bench, a second replica of the
 * conversation module's tool calls over the same database ({@link #replica}: its own {@link ToolCalls} and
 * {@link ToolJournal}, nothing shared in memory) with a clock the test moves ({@link MovableClock}) and a tool timeout
 * of its own, the journal's rows as the runtime role reads them, and a stopped replica's row written as it left it.
 */
abstract class ToolJournalBench extends ConversationBench {

    @Autowired
    ConversationCore core;

    @Autowired
    DecisionOps decisionOps;

    @Autowired
    ParkedCalls parked;

    @Autowired
    ParkedCallStore parkedStore;

    @Autowired
    ToolRunner runner;

    @Autowired
    ToolAudit toolAudit;

    @Autowired
    SystemTools systemTools;

    @Autowired
    ToolCallStore toolCallStore;

    @Autowired
    ToolJournal journal;

    @Autowired
    ToolJournalSweep sweep;

    @Autowired
    AuditLog auditLog;

    @Autowired
    TurnService turnService;

    @Autowired
    RunReads runReads;

    @Autowired
    Accounts accounts;

    @Autowired
    ApplicationEventPublisher events;

    @Value("${tinfirm.conversation.tool-journal.answer-within}")
    Duration answerWithin;

    /** A clock the test moves forward, instead of sleeping. */
    static final class MovableClock extends Clock {

        private volatile Duration offset = Duration.ZERO;

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }

        void advance(Duration by) {
            offset = offset.plus(by);
        }
    }

    /** Another replica's tool calls: the same beans and database, its own journal, clock and tool timeout. */
    ToolCalls replica(Clock clock, Duration toolTimeout) {
        return replica(clock, toolTimeout, answerWithin, runner);
    }

    /**
     * Another replica's tool calls with its own wait for a call running elsewhere ({@code answer-within}) and its own
     * {@link ToolRunner} (one that does something of the test's around the call: {@link #runnerThat}).
     */
    ToolCalls replica(Clock clock, Duration toolTimeout, Duration answerWithin, ToolRunner calls) {
        ToolJournal own = new ToolJournal(toolCallStore, decisionStore, core, tools, auditLog, transactions,
                new TimeSource(clock), runReads, toolTimeout, answerWithin);
        return new ToolCalls(core, tools, decisionStore, decisionOps, parked, parkedStore, calls, toolAudit, settings,
                systemTools, transactions, own, workers);
    }

    /** What a test's {@link ToolRunner} does around a call: before it runs, and when it is recorded. */
    interface Around {

        /** Before the call reaches its system (outside any transaction). */
        default void before() {
        }

        /** In the record's transaction, before {@code tool.called}. */
        default void recording() {
        }
    }

    /** A {@link ToolRunner} that does the test's {@link Around} around each call. */
    ToolRunner runnerThat(Around around) {
        return new ToolRunner(core, systemTools, accounts, toolAudit, events) {

            @Override
            Ran run(Prepared call, String purpose) {
                around.before();
                return super.run(call, purpose);
            }

            @Override
            void audited(Prepared call, Ran.Called called, String mode, @Nullable UUID decisionId,
                    @Nullable UUID parked) {
                around.recording();
                super.audited(call, called, mode, decisionId, parked);
            }
        };
    }

    /** An admin pauses the bench's Jira (package 6, §5.4), as the system module writes it. */
    void pauseJira(Bench bench) {
        update(bench.companyId(), "UPDATE external_system SET paused_at = now(), paused_reason = 'Incident review' "
                + "WHERE id = ?", bench.jiraId());
    }

    /**
     * A conversation of a trigger of the bench's agent (package 4): authored by the bench's person, paid by the payer (a
     * service identity), no person of its own, bound to the author's account on Jira as the trigger's start binds
     * ({@code FIXED}), and its first turn running.
     */
    Conversation triggerConversation(Bench bench, UUID accountId, UUID payerPrincipalId) {
        UUID author = bench.personPrincipalId();
        UUID triggerId = TestTriggers.schedule(bench.companyId(), bench.agentId(), author, payerPrincipalId);
        UUID id = UUID.randomUUID();
        String key = "sched-" + id.toString().substring(0, 8);
        TestTriggers.conversation(bench.companyId(), id, key, triggerId, bench.agentId(), author, payerPrincipalId);
        update(bench.companyId(), """
                INSERT INTO conversation_binding (id, company_id, conversation_id, system_id, state, how, account_id,
                                                  holder_principal_id, external_id, bound_at, created_at, updated_at)
                SELECT gen_random_uuid(), ?, ?, ?, 'BOUND', 'FIXED', a.id, ?, a.external_id, now(), now(), now()
                  FROM account a WHERE a.id = ?""", bench.companyId(), id, bench.jiraId(), author, accountId);
        UUID runId = UUID.randomUUID();
        TestRuns.ofTrigger(bench.companyId(), runId, id, key, bench.agentId(), triggerId, author, payerPrincipalId,
                "RUNNING");
        return new Conversation(bench, id, key, runId, new ActiveRun(bench.companyId(), runId, 1, UUID.randomUUID(), id,
                bench.agentId(), author, payerPrincipalId, 1));
    }

    /** Settles the request's row as a replica that ran its call records it: {@code DONE} with this answer. */
    void recorded(Conversation conversation, UUID requestId, RunnerMessages.RunRequestAck answer) {
        UUID companyId = conversation.bench().companyId();
        SystemContext.runInCompany(companyId, () -> transactions.runInCompany(companyId,
                () -> journal.finished(conversation.runId(), requestId, 1, answer)));
    }

    /** A {@code TOOL} request of the conversation's run on that replica, outside any transaction. */
    RunnerMessages.RunRequestAck callOn(ToolCalls replica, Conversation conversation, UUID requestId, String tool,
            Map<String, Object> arguments) {
        JsonNode payload = JSON.valueToTree(Map.of("tool", tool, "arguments", arguments));
        return SystemContext.callInCompany(conversation.bench().companyId(),
                () -> replica.call(conversation.run(), requestId, payload));
    }

    /** The journal's row of the request, as a map of its columns; empty for none. */
    Map<String, Object> journalRow(Conversation conversation, UUID requestId) {
        return row(conversation.bench().companyId(), """
                SELECT state, attempt, replay, effect, tool, name, result::text AS result, finished_at, run_ended_at,
                       started_at
                  FROM tool_call WHERE run_id = ? AND request_id = ?""", conversation.runId(), requestId);
    }

    /** A call a stopped replica left RUNNING, claimed now, as it wrote it. */
    void leftRunning(Conversation conversation, UUID requestId, String handle, String tool,
            Map<String, Object> arguments, String effect, String replay) {
        Bench bench = conversation.bench();
        update(bench.companyId(), """
                INSERT INTO tool_call (company_id, run_id, request_id, conversation_id, system_id, tool, name, effect,
                                       replay, arguments_sha256, state, attempt, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', 1, now())""", bench.companyId(),
                conversation.runId(), requestId, conversation.id(), bench.jiraId(), tool, handle + "_" + tool, effect,
                replay, ToolJournal.sha256(JSON.valueToTree(arguments)));
    }

    /** The system's handle on the bench's Jira: its tools are listed as {@code {handle}_{tool}}. */
    String handle(Bench bench) {
        return String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                bench.jiraId()).get("handle"));
    }

    /** The run of the conversation ends, as the run module tells the conversation in the run's end. */
    void runEnds(Conversation conversation) {
        Bench bench = conversation.bench();
        endTurn(conversation);
        SystemContext.runInCompany(bench.companyId(), () -> transactions.runInCompany(bench.companyId(),
                () -> turnService.turnEnded(new TurnEnded.TurnEnd(bench.companyId(), conversation.runId(),
                        conversation.id(), conversation.run().agentId(), TurnReason.FIRST, 1,
                        bench.personPrincipalId(), bench.personPrincipalId(), ModelAccessChoice.BUDGET,
                        RunStatus.SUCCEEDED, null, null, "Done.", null, null, List.of(), List.of(), null))));
    }

    /** The structured content of a DONE answer's result. */
    static JsonNode structured(RunnerMessages.RunRequestAck ack) {
        return ((RunnerMessages.ToolResult) java.util.Objects.requireNonNull(ack.payload())).structuredContent();
    }

    /** Whether a DONE answer is a tool error. */
    static boolean isError(RunnerMessages.RunRequestAck ack) {
        return ((RunnerMessages.ToolResult) java.util.Objects.requireNonNull(ack.payload())).isError();
    }
}
