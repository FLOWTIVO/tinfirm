package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bench of part 2's integration tests (TEST FIXTURE ONLY): a company with its OWNER, ADMIN and MEMBER (the
 * conversations' person), a Jira Data Center stub registered as a system, the person's agent with Jira's tools, and
 * conversations as rows ({@link TestConversations}) with a running turn ({@link TestRuns#ofConversation}), whose
 * platform requests go to the conversation module's {@code RunRequests} as the run module sends them; part 1's beans
 * bind, start turns and post messages as in production. A turn the conversation requests is a run the tests read
 * ({@link #requested}) and take ({@link #nextTurn}). Tokens are test values.
 */
abstract class ConversationBench extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(20);

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    ConversationRequests requests;

    @Autowired
    ConversationTools tools;

    @Autowired
    AnswersSection answers;

    @Autowired
    DecisionStore decisionStore;

    @Autowired
    ConversationWorkers workers;

    @Autowired
    Conversations conversations;

    @Autowired
    ConversationBindings bindings;

    @Autowired
    Settings settings;

    @Autowired
    ConversationWorkspace workspace;

    @Autowired
    SystemTransactions transactions;

    /**
     * A company; its Jira; the MEMBER's agent with Jira's {@code get} and {@code search} ALLOW and {@code comment}
     * ASK.
     */
    record Bench(TestCompany company, ApiClient owner, ApiClient admin, ApiClient person, UUID personPrincipalId,
            UUID agentId, String agentName, JiraStub.Instance jira, UUID jiraId) {

        UUID companyId() {
            return company.id();
        }

        String path(String suffix) {
            return company.path(suffix);
        }

        String agentPath(String suffix) {
            return company.path("/agents/" + agentId + suffix);
        }
    }

    /** A conversation of the bench's person with a running turn. */
    record Conversation(Bench bench, UUID id, String key, UUID runId, ActiveRun run) {
    }

    Bench bench() {
        return bench(List.of(Map.of("name", "get", "mode", "ALLOW"), Map.of("name", "search", "mode", "ALLOW"),
                Map.of("name", "comment", "mode", "ASK")));
    }

    Bench bench(List<Map<String, Object>> jiraTools) {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient person = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID jiraId = createSystem(admin, company, unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        String agentName = unique("Analyst");
        ApiResponse agent = person.post(company.path("/agents"), Map.of("name", agentName, "runtimes",
                List.of("CLAUDE_CODE"), "systems", List.of(Map.of("systemId", jiraId.toString(), "tools",
                        jiraTools))));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return new Bench(company, owner, admin, person, memberPrincipal(company, company.userId("MEMBER")),
                UUID.fromString(agent.json().get("id").asString()), agentName, jira, jiraId);
    }

    /** A new conversation of the bench's person with its agent, and its first turn running. */
    Conversation conversation(Bench bench) {
        return conversation(bench, bench.agentId(), bench.personPrincipalId());
    }

    Conversation conversation(Bench bench, UUID agentId, UUID personPrincipalId) {
        UUID id = UUID.randomUUID();
        String key = "conv-" + id.toString().substring(0, 8);
        TestConversations.bare(bench.companyId(), id, key, agentId, personPrincipalId, "CLAUDE_CODE");
        UUID runId = UUID.randomUUID();
        TestRuns.ofConversation(bench.companyId(), runId, id, key, agentId, personPrincipalId, 1, "RUNNING");
        return new Conversation(bench, id, key, runId, new ActiveRun(bench.companyId(), runId, 1, UUID.randomUUID(),
                id, agentId, personPrincipalId, personPrincipalId, 1));
    }

    /** The conversation's running turn ends, as the agent ends it after a request: the next can start. */
    void endTurn(Conversation conversation) {
        TestRuns.ended(conversation.bench().companyId(), conversation.runId());
    }

    /**
     * The conversation's next turn runs: the one the conversation requested (its QUEUED run, taken as its host would
     * take it), else a new one; the running one ends first.
     */
    Conversation nextTurn(Conversation conversation, int number) {
        Bench bench = conversation.bench();
        TestRuns.ended(bench.companyId(), conversation.runId());
        Object queued = row(bench.companyId(), "SELECT id FROM run WHERE conversation_id = ? AND status = 'QUEUED'",
                conversation.id()).get("id");
        UUID runId;
        if (queued != null) {
            runId = (UUID) queued;
            update(bench.companyId(), """
                    UPDATE run SET status = 'RUNNING', dispatched_at = NULL, queue_reason = NULL,
                                   queue_reason_details = NULL, queue_reason_since = NULL, started_at = now(),
                                   lease_expires_at = now() + interval '1 hour'
                     WHERE id = ?""", runId);
        }
        else {
            runId = UUID.randomUUID();
            TestRuns.ofConversation(bench.companyId(), runId, conversation.id(), conversation.key(),
                    conversation.run().agentId(), conversation.run().actingPrincipalId(), number, "RUNNING");
        }
        return new Conversation(bench, conversation.id(), conversation.key(), runId, new ActiveRun(bench.companyId(),
                runId, 1, UUID.randomUUID(), conversation.id(), conversation.run().agentId(),
                conversation.run().actingPrincipalId(), conversation.run().payerPrincipalId(), number));
    }

    /** The turns the conversation requested, in order: the reasons of the runs part 1 started for them. */
    static List<String> requested(Conversation conversation) {
        return rows(conversation.bench().companyId(), """
                SELECT turn_reason FROM run WHERE conversation_id = ? AND turn_reason IN ('DECIDED', 'CONNECTED')
                 ORDER BY created_at, id""", conversation.id())
            .stream()
            .map(row -> String.valueOf(row.get("turn_reason")))
            .toList();
    }

    /** A {@code TOOL} request of the conversation's run, outside any transaction, as the run module makes it. */
    RunnerMessages.RunRequestAck call(Conversation conversation, String tool, Map<String, Object> arguments) {
        return call(conversation, UUID.randomUUID(), tool, arguments);
    }

    RunnerMessages.RunRequestAck call(Conversation conversation, UUID requestId, String tool,
            Map<String, Object> arguments) {
        JsonNode payload = JSON.valueToTree(Map.of("tool", tool, "arguments", arguments));
        return SystemContext.callInCompany(conversation.bench().companyId(),
                () -> requests.call(conversation.run(), requestId, payload));
    }

    /** Another kind of request, in a transaction, as the run module makes it. */
    RunnerMessages.RunRequestAck request(Conversation conversation, String kind, Map<String, Object> payload) {
        return request(conversation, UUID.randomUUID(), kind, payload);
    }

    RunnerMessages.RunRequestAck request(Conversation conversation, UUID requestId, String kind,
            Map<String, Object> payload) {
        JsonNode body = JSON.valueToTree(payload);
        return transactions.inCompany(conversation.bench().companyId(), () -> requests.request(conversation.run(),
                RunnerMessages.RequestKind.valueOf(kind), requestId, body));
    }

    /** The run's tools, as its {@code run.assign} lists them. */
    List<RunnerMessages.ToolSpec> listed(Conversation conversation) {
        Bench bench = conversation.bench();
        return SystemContext.callInCompany(bench.companyId(), () -> tools.tools(turn(conversation)));
    }

    /** The conversation's next turn as part 2's seams see it. */
    TurnContext turn(Conversation conversation) {
        Bench bench = conversation.bench();
        ConversationInfo info = transactions.inCompany(bench.companyId(), () -> conversations.find(
                bench.companyId(), conversation.id())).orElseThrow();
        EffectiveSettings effective = transactions.inCompany(bench.companyId(), () -> settings.effective(
                bench.companyId(), info.agentId(), new SettingsOwner.Person(info.personPrincipalId())));
        return new TurnContext(info, new TurnSource.TurnRef(bench.companyId(), conversation.runId(), info.id(),
                info.key(), info.agentId(), RuntimeKind.CLAUDE_CODE, TurnReason.FIRST,
                conversation.run().turnNumber(), info.personPrincipalId(), info.payerPrincipalId(),
                ModelAccessChoice.BUDGET), effective);
    }

    /** The workspace of the conversation's next turn, as {@code ConversationWorkspace.of} makes it. */
    List<ConversationWorkspace.Repository> workspaceOf(Conversation conversation) {
        Bench bench = conversation.bench();
        TurnContext turn = turn(conversation);
        return transactions.inCompany(bench.companyId(), () -> workspace.of(turn.conversation(), turn.settings()));
    }

    /** The prompt's {@code # Answers} of the conversation's running turn. */
    @Nullable String answersOf(Conversation conversation) {
        return SystemContext.callInCompany(conversation.bench().companyId(),
                () -> answers.section(turn(conversation)).orElse(null));
    }

    /** Waits until the work after commits (approved calls, parked calls) is done. */
    void awaitWorkers() {
        await().atMost(WAIT).until(() -> workers.running() == 0);
    }

    /** The person connects their own Jira account with this token, which Jira's myself accepts. */
    UUID connectOwnJira(Bench bench, String token, String name) {
        return connectToken(bench.person(), bench.path("/me/accounts/" + bench.jiraId()), bench.jira(), token, name);
    }

    /** The conversation's binding on the system, as a map of its row's columns. */
    Map<String, Object> bindingRow(Conversation conversation, UUID systemId) {
        return row(conversation.bench().companyId(), """
                SELECT id, state, reason, how, grant_id, waiting_calls::text AS calls FROM conversation_binding
                 WHERE conversation_id = ? AND system_id = ?""", conversation.id(), systemId);
    }

    /** A row of a query as the runtime role in the company's context, as a map of its columns; empty for none. */
    static Map<String, Object> row(UUID companyId, String sql, Object... params) {
        try (TenantSql tenant = TenantSql.in(companyId)) {
            List<Map<String, Object>> rows = tenant.rows(sql, params);
            return rows.isEmpty() ? new HashMap<>() : rows.getFirst();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static List<Map<String, Object>> rows(UUID companyId, String sql, Object... params) {
        try (TenantSql tenant = TenantSql.in(companyId)) {
            return tenant.rows(sql, params);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A change as the runtime role in the company's context, committed. */
    static int update(UUID companyId, String sql, Object... params) {
        try (TenantSql tenant = TenantSql.in(companyId)) {
            int rows = tenant.update(sql, params);
            tenant.commit();
            return rows;
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The answer's code, or its status when it is not refused. */
    static String outcome(RunnerMessages.RunRequestAck ack) {
        return ack.code() != null ? ack.code() : ack.status().name();
    }

    /** The decision or binding id a PENDING answer carries. */
    static UUID pendingId(RunnerMessages.RunRequestAck ack) {
        assertThat(ack.status()).as("%s", ack).isEqualTo(RunnerMessages.RequestStatus.PENDING);
        return ((RunnerMessages.DecisionPayload) java.util.Objects.requireNonNull(ack.payload())).decisionId();
    }

    /** The text of a DONE answer's result. */
    static String resultText(RunnerMessages.RunRequestAck ack) {
        assertThat(ack.status()).as("%s", ack).isEqualTo(RunnerMessages.RequestStatus.DONE);
        RunnerMessages.ToolResult result = (RunnerMessages.ToolResult) java.util.Objects.requireNonNull(ack.payload());
        return result.content().getFirst().text();
    }
}
