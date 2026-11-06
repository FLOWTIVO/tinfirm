package com.flowtivo.tinfirm.trigger.internal;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * The base of the write-back's tests (the package 4 contract, §5.8, §5.9): a company with a team and an agent, a
 * tracker trigger made through the API, its conversation started as part b's firing starts it
 * ({@link FiringConversations}), its turns driven by a fake runner host, and the outbox read as the runtime role. TEST
 * FIXTURE ONLY: every token is a test value.
 */
abstract class OutboxTestSupport extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(30);

    final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    RunnerConnections connections;

    @Autowired
    SystemTransactions transactions;

    @Autowired
    FiringConversations firings;

    @Autowired
    TriggerConversations conversations;

    @Autowired
    OutboxDelivery delivery;

    /**
     * A company: OWNER, ADMIN, Alice (MEMBER, the author) and Bob (the team), both in the agent's group; the bundled
     * gateway with their keys; an agent of that name, shared with the group.
     */
    record Bench(TestCompany company, ApiClient owner, ApiClient admin, ApiClient alice, ApiClient bob,
            UUID alicePrincipal, UUID bobPrincipal, UUID users, UUID team, UUID agentId, int agentVersion,
            String agentName) {

        String path(String suffix) {
            return company.path(suffix);
        }

        UUID id() {
            return company.id();
        }
    }

    Bench bench(String agentName) {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String bobEmail = uniqueEmail("bob");
        UUID bobUser = createUser(admin(), bobEmail, false);
        addMember(owner, company.id(), bobEmail, "MEMBER");
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        UUID bobPrincipal = memberPrincipal(company, bobUser);
        ModelGateways.configureBundled(owner, company);
        for (UUID principal : List.of(alicePrincipal, bobPrincipal)) {
            ModelGateways.awaitKey(litellm, company, principal);
            ModelGateways.awaitStatus(owner, company, principal, "OK");
        }
        UUID users = createGroup(owner, company, unique("Users"), List.of(company.userId("MEMBER"), bobUser));
        UUID team = createGroup(owner, company, unique("Team"), List.of(bobUser));
        // Each bench is a company of its own: the agent's name is unique in it.
        String name = agentName;
        ApiResponse agent = owner.post(company.path("/agents"), Map.of("name", name, "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "You prepare issues.", "shareGroupIds",
                List.of(users.toString())));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return new Bench(company, owner, loginAs(company.email("ADMIN")), loginAs(company.email("MEMBER")),
                loginAs(bobEmail), alicePrincipal, bobPrincipal, users, team,
                UUID.fromString(agent.json().get("id").asString()), agent.json().get("version").asInt(), name);
    }

    static final String KEY = "SHOP-1";

    /** A bench with a Jira Data Center system and Alice's account on it. */
    record Jira(Bench bench, JiraStub.Instance stub, UUID system, UUID account, String token) {

        String issue(String suffix) {
            return stub.url("/rest/api/2/issue/" + KEY + suffix);
        }
    }

    Jira jira(String agentName) {
        Bench bench = bench(agentName);
        JiraStub.Instance stub = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", stub.baseUrl(), false);
        String token = "test-jira-" + unique("alice");
        UUID account = connectToken(bench.alice(), bench.path("/me/accounts/" + system), stub, token, "alice");
        return new Jira(bench, stub, system, account, token);
    }

    static void stubTransitions(Jira jira) {
        JiraStub.server().stubFor(get(urlEqualTo(jira.issue("/transitions"))).willReturn(aResponse().withStatus(200)
            .withBody("""
                    {"transitions":[{"id":"21","name":"Start Progress","to":{"name":"In Progress"}},
                                    {"id":"31","name":"Ask for review","to":{"name":"In Review"}}]}""")));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/transitions"))).willReturn(aResponse().withStatus(204)));
    }

    /** The results of a trigger: a comment, a review, and the actions after a success. */
    static Map<String, Object> results(boolean comment, boolean review, List<Map<String, Object>> onSuccess,
            List<Map<String, Object>> onFailure) {
        return Map.of("comment", comment, "review", review, "onSuccess", onSuccess, "onFailure", onFailure);
    }

    static Map<String, Object> action(String kind, @Nullable String value) {
        Map<String, Object> action = new HashMap<>();
        action.put("kind", kind);
        if (value != null) {
            action.put("value", value);
        }
        return action;
    }

    /** A tracker trigger of the bench's agent, made by the author through the API; answers its id. */
    UUID trigger(ApiClient author, Bench bench, UUID systemId, String scope, UUID accountId, UUID payer,
            @Nullable UUID team, Map<String, Object> results) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Prepare"));
        body.put("source", "TRACKER");
        body.put("systemId", systemId.toString());
        body.put("events", List.of(Map.of("event", "ASSIGNED")));
        body.put("subject", "ISSUE");
        body.put("scope", scope);
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", bench.agentVersion());
        body.put("instruction", "Prepare the issue for the team.");
        body.put("accounts", List.of(Map.of("systemId", systemId.toString(), "accountId", accountId.toString())));
        body.put("payerPrincipalId", payer.toString());
        if (team != null) {
            body.put("teamGroupId", team.toString());
        }
        body.put("results", results);
        body.put("acknowledgeOpenEvents", true);
        ApiResponse created = author.post(bench.path("/triggers"), withModel(body));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        return UUID.fromString(created.json().get("id").asString());
    }

    /** A fake runner host of the company, connected and configured. */
    FakeRunner runner(Bench bench) {
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloV2AndWelcome(List.of(), List.of(), "0.2.0", 4);
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        hosts.put(runner, host);
        return runner;
    }

    private final Map<FakeRunner, RunnerHostRef> hosts = new HashMap<>();

    /** The host's token, for its uploads. */
    String hostToken(FakeRunner runner) {
        return hosts.get(runner).token();
    }

    /** Part b's firing: the trigger's conversation on the subject, with its first turn. */
    TriggerConversations.Started start(Bench bench, UUID triggerId, UUID systemId, String subjectId, String key,
            String url) {
        TriggerConversations.Started started = transactions.inCompany(bench.id(), () -> firings.start(bench.id(),
                triggerId, new FiringConversations.Place.Subject(new TriggerConversations.TrackerSubject(systemId,
                        subjectId, key, "ISSUE", url, "Checkout fails")), 0, null, null));
        assertThat(started.runId()).isPresent();
        return started;
    }

    /**
     * Part b's firing on the open conversation ({@code CONTINUED}): a comment on the issue, waiting for a turn; its
     * {@code EVENT} turn starts now. Answers the run.
     */
    UUID event(Bench bench, UUID triggerId, UUID conversationId, String request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.accepted("""
                    INSERT INTO trigger_firing (id, company_id, trigger_id, subject_id, subject_key, event, outcome,
                                                request, conversation_id, changed_at, created_at)
                    VALUES (?, ?, ?, '10001', 'SHOP-1', 'COMMENTED', 'CONTINUED', ?, ?, ?, ?)""", UUID.randomUUID(),
                    bench.id(), triggerId, request, conversationId, now, now);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return transactions.inCompany(bench.id(), () -> firings.continueWith(bench.id(), conversationId))
            .orElseThrow();
    }

    /** A turn the runner took: its run and epoch, accepted. */
    record Turn(UUID runId, long epoch) {
    }

    static Turn take(FakeRunner runner) {
        JsonNode assign = runner.expect("run.assign");
        Turn turn = new Turn(UUID.fromString(assign.get("runId").asString()), assign.get("epoch").asLong());
        runner.accepted(turn.runId(), turn.epoch());
        return turn;
    }

    /**
     * Waits until the server has handled the turn's {@code run.accepted}, which comes over the host's socket while the
     * test goes on: the run is {@code RUNNING}, so the host's uploads for it are taken (a {@code QUEUED} run's are 404).
     */
    static void awaitRunning(UUID companyId, Turn turn) {
        await().atMost(WAIT).until(() -> {
            try (TenantSql sql = TenantSql.in(companyId)) {
                return sql.column("SELECT status FROM run WHERE id = ?", turn.runId());
            }
        }, statuses -> statuses.contains("RUNNING"));
    }

    /** {@code run.finished}: succeeded with a completed result, its summary and next steps. */
    static Map<String, Object> completed(Turn turn, String summary, List<String> nextSteps) {
        Map<String, Object> finished = new LinkedHashMap<>(finished(turn, "SUCCEEDED", summary));
        finished.put("result", Map.of("outcome", "COMPLETED", "summary", summary, "nextSteps", nextSteps,
                "openQuestions", List.of()));
        return finished;
    }

    /** {@code run.finished} of the outcome, without a result. */
    static Map<String, Object> finished(Turn turn, String outcome, String summary) {
        Map<String, Object> finished = new LinkedHashMap<>();
        finished.put("type", "run.finished");
        finished.put("runId", turn.runId().toString());
        finished.put("epoch", turn.epoch());
        finished.put("outcome", outcome);
        finished.put("lastSeq", 0);
        finished.put("summary", summary);
        finished.put("sessionId", "session-" + turn.runId().toString().substring(0, 8));
        finished.put("git", List.of());
        return finished;
    }

    /** The conversation's outbox, in its order, as the runtime role reads it in the company. */
    static List<Map<String, Object>> outbox(UUID companyId, UUID conversationId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.rows("""
                    SELECT id, seq, kind, cause, cause_key, status, attempts, external_id, body, value, url,
                           dropped_reason, last_error, ambiguous, binding_id
                      FROM outbox WHERE conversation_id = ? ORDER BY seq""", conversationId);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Waits until the conversation's outbox satisfies the condition, and answers it. */
    static List<Map<String, Object>> awaitOutbox(UUID companyId, UUID conversationId,
            Predicate<List<Map<String, Object>>> condition) {
        return await().atMost(WAIT).until(() -> outbox(companyId, conversationId), condition);
    }

    static boolean allDelivered(List<Map<String, Object>> rows, int count) {
        return rows.size() == count && rows.stream().allMatch(row -> "DELIVERED".equals(row.get("status")));
    }

    static List<String> column(List<Map<String, Object>> rows, String column) {
        List<String> values = new ArrayList<>();
        rows.forEach(row -> values.add(String.valueOf(row.get(column))));
        return values;
    }

    /** The conversation's pending rows are due now and unclaimed (a test does not wait the backoff). */
    static void dueNow(UUID companyId, UUID conversationId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.update("""
                    UPDATE outbox SET next_attempt_at = now() - interval '1 second', claimed_until = NULL
                     WHERE conversation_id = ? AND status = 'PENDING'""", conversationId);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Tinfirm's records of its writes to the subject. */
    static List<Map<String, Object>> systemWrites(UUID companyId, UUID conversationId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.rows("""
                    SELECT kind, subject_key, external_id, written_values::text AS written_values
                      FROM system_write WHERE conversation_id = ? ORDER BY written_at, kind""", conversationId);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * A poll of the account on the system with its snapshot of the subject, as part b keeps them: the baseline a write
     * of Tinfirm's updates. Answers the poll.
     */
    static UUID snapshot(UUID companyId, UUID systemId, UUID accountId, UUID holderPrincipalId, String subjectId,
            String key, String status, String labelsJson) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID poll;
        try (TenantSql sql = TenantSql.in(companyId)) {
            // The trigger's poll, made after its creation by part b's PollKeeper, or here first.
            sql.update("""
                    INSERT INTO trigger_poll (id, company_id, system_id, account_id, holder_principal_id, next_poll_at,
                                              created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (system_id, account_id) DO NOTHING""", UUID.randomUUID(), companyId, systemId,
                    accountId, holderPrincipalId, now, now, now);
            poll = (UUID) sql.column("SELECT id FROM trigger_poll WHERE system_id = ? AND account_id = ?", systemId,
                    accountId).getFirst();
            sql.accepted("""
                    INSERT INTO trigger_subject (company_id, poll_id, subject_id, key, kind, open, status, labels,
                                                 seen_at)
                    VALUES (?, ?, ?, ?, 'ISSUE', true, ?, CAST(? AS jsonb), ?)""", companyId, poll, subjectId, key,
                    status, labelsJson, now);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return poll;
    }

    static Map<String, Object> snapshotOf(UUID companyId, UUID poll, String subjectId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.rows("SELECT open, status, labels::text AS labels FROM trigger_subject WHERE poll_id = ? "
                    + "AND subject_id = ?", poll, subjectId).getFirst();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The audit's {@code source.written} records of the company. */
    List<JsonNode> sourceWritten(Bench bench) {
        return auditEvents(bench.owner(), bench.company(), "source.written");
    }
}
