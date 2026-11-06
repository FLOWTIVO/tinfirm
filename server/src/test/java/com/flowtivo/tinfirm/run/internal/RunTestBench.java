package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.NewTurn;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.run.Turns;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.flowtivo.tinfirm.support.TestSettings;
import com.flowtivo.tinfirm.support.TestTurns;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The run module's integration tests' bench (TEST FIXTURE ONLY): a company whose MEMBER is the person of the
 * conversations, its bundled model gateway with the member's key, runner hosts and their fake hosts, the turns of
 * conversations started as the conversation module would ({@link Turns#start}), and SQL as the runtime role for moving
 * time. The conversation module's ports are {@link TestTurns}.
 */
abstract class RunTestBench extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(20);

    /** The runner version its hosts say in {@code hello}: any, since it decides nothing (ADR-0109). */
    static final String RUNNER_VERSION = "0.2.0";

    static final String MESSAGES = "/api/runner/v1/model/anthropic/v1/messages";

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    Turns turns;

    @Autowired
    Principals principals;

    @Autowired
    SystemTransactions transactions;

    @Autowired
    RunDispatcher dispatcher;

    @Autowired
    RunReaper reaper;

    /**
     * A company, its OWNER and the MEMBER who is the conversations' person, with their principal, and the person's
     * agent (on Claude Code and Codex) that every conversation of the bench talks to.
     */
    record Bench(TestCompany company, ApiClient owner, ApiClient person, UUID personPrincipalId, UUID agentId,
            String agentName) {

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

    /**
     * A company and its member, whose settings of the agent name a model (a budget's run names one); with the bundled
     * gateway configured and the member's key usable when asked.
     */
    Bench bench(boolean gateway) {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient person = loginAs(company.email("MEMBER"));
        UUID principalId = principalOf(company.id(), company.userIds().get("MEMBER"));
        if (gateway) {
            ModelGateways.configureBundled(owner, company);
            ModelGateways.awaitKey(litellm, company, principalId);
            ModelGateways.awaitStatus(owner, company, principalId, "OK");
        }
        String agentName = unique("Builder");
        ApiResponse agent = person.post(company.path("/agents"), Map.of("name", agentName, "runtimes",
                List.of("CLAUDE_CODE", "CODEX")));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        Bench bench = new Bench(company, owner, person, principalId,
                UUID.fromString(agent.json().get("id").asString()), agentName);
        settings(bench, TestSettings.MODEL, "DEFAULT");
        return bench;
    }

    UUID principalOf(UUID companyId, UUID userId) {
        return transactions.inCompany(companyId, () -> principals.ofMember(companyId, userId)).orElseThrow().id();
    }

    /** A company host connected over protocol v2 with this runner version and slots, configured. */
    FakeRunner companyHost(Bench bench, String version, int slots) {
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        return connect(host, version, slots);
    }

    FakeRunner connect(RunnerHostRef host, String version, int slots) {
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloV2AndWelcome(List.of(), List.of(), version, slots);
        awaitConfigured(host.id());
        return runner;
    }

    /** Waits until the host confirmed its capacity on its connection (it takes runs from then on). */
    void awaitConfigured(UUID hostId) {
        await().atMost(WAIT).until(() -> connections.isConfigured(hostId));
    }

    @Autowired
    com.flowtivo.tinfirm.runner.RunnerConnections connections;

    /** A new conversation's first turn, paid by the person's budget. */
    Turn start(Bench bench) {
        return start(bench, UUID.randomUUID(), TurnReason.FIRST, 1, ModelAccessChoice.BUDGET);
    }

    Turn start(Bench bench, UUID conversationId, TurnReason reason, int number, ModelAccessChoice access) {
        String key = "conv-" + conversationId.toString().substring(0, 8);
        Optional<UUID> runId = startTurn(bench, conversationId, key, reason, number, access);
        return new Turn(conversationId, key, runId.orElseThrow(() -> new AssertionError("The turn did not start")));
    }

    Optional<UUID> startTurn(Bench bench, UUID conversationId, String key, TurnReason reason, int number,
            ModelAccessChoice access) {
        return startTurn(bench, conversationId, key, RuntimeKind.CLAUDE_CODE, reason, number, access);
    }

    Optional<UUID> startTurn(Bench bench, UUID conversationId, String key, RuntimeKind runtime, TurnReason reason,
            int number, ModelAccessChoice access) {
        // The conversation as the conversation module would have started it: a bare row (a run's conversation is a
        // foreign key), whose ports are TestTurns'.
        TestConversations.bare(bench.companyId(), conversationId, key, bench.agentId(), bench.personPrincipalId(),
                runtime.name());
        TestTurns.conversation(conversationId);
        return transactions.inCompany(bench.companyId(), () -> turns.start(new NewTurn(bench.companyId(),
                conversationId, key, bench.agentId(), runtime, reason, number, bench.personPrincipalId(),
                bench.personPrincipalId(), access, null)));
    }

    record Turn(UUID conversationId, String key, UUID runId) {
    }

    /**
     * Writes the person's settings of the bench's agent as the agent module stores them: a model (one the settings' API
     * would refuse too, or none) and an effort, with the agent's current version accepted.
     */
    static void settings(Bench bench, @Nullable String model, String effort) {
        update(bench.companyId(), """
                INSERT INTO agent_settings (id, company_id, agent_id, principal_id, model, effort, accepted_version,
                                            created_at, updated_at)
                SELECT ?, ?, ?, ?, ?, ?, version, now(), now() FROM agent WHERE id = ?
                ON CONFLICT (agent_id, principal_id) DO UPDATE
                   SET model = excluded.model, effort = excluded.effort, accepted_version = excluded.accepted_version""",
                UUID.randomUUID(), bench.companyId(), bench.agentId(), bench.personPrincipalId(), model, effort,
                bench.agentId());
    }

    JsonNode expectAssign(FakeRunner runner, UUID runId) {
        JsonNode assign = runner.expect("run.assign");
        assertThat(assign.get("runId").asString()).isEqualTo(runId.toString());
        return assign;
    }

    JsonNode getRun(Bench bench, UUID runId) {
        ApiResponse response = bench.person().get(bench.path("/runs/" + runId));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }

    void awaitRun(Bench bench, UUID runId, String status) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(getRun(bench, runId).get("status").asString())
            .isEqualTo(status));
    }

    /** Waits until the queued run waits for this reason, dispatching meanwhile. */
    JsonNode awaitQueueReason(Bench bench, UUID runId, String code) {
        await().atMost(WAIT).untilAsserted(() -> {
            dispatcher.dispatch();
            JsonNode run = getRun(bench, runId);
            assertThat(run.get("status").asString()).isEqualTo("QUEUED");
            assertThat(run.has("queueReason") ? run.get("queueReason").get("code").asString() : null).as("%s", run)
                .isEqualTo(code);
        });
        return getRun(bench, runId).get("queueReason");
    }

    static Map<String, Object> finished(UUID runId, long epoch, String outcome, long lastSeq, String summary,
            String sessionId) {
        return Map.of("type", "run.finished", "runId", runId.toString(), "epoch", epoch, "outcome", outcome,
                "lastSeq", lastSeq, "summary", summary, "sessionId", sessionId, "git", List.of());
    }

    /**
     * A request through the model relay with the run's token and its host's own token, as the host's model proxy sends
     * both: 200 while the run is active, 401 otherwise.
     */
    int relay(String token, String hostToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + MESSAGES))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .header("X-Tinfirm-Runner-Token", hostToken)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"))
            .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    // ---- SQL as the runtime role in the company's context, for moving time --------------------------------------

    static int update(UUID companyId, String sql, Object... params) {
        try (Connection connection = context(companyId); PreparedStatement statement = prepare(connection, sql,
                params)) {
            int rows = statement.executeUpdate();
            connection.commit();
            return rows;
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static Object query(UUID companyId, String sql, Object... params) {
        try (Connection connection = context(companyId); PreparedStatement statement = prepare(connection, sql,
                params); ResultSet row = statement.executeQuery()) {
            Object value = row.next() ? row.getObject(1) : null;
            connection.commit();
            return value;
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    Instant lease(UUID companyId, UUID runId) {
        try (Connection connection = context(companyId); PreparedStatement statement = prepare(connection,
                "SELECT lease_expires_at FROM run WHERE id = ?", runId); ResultSet row = statement.executeQuery()) {
            Instant lease = row.next() ? row.getObject(1, OffsetDateTime.class).toInstant() : null;
            connection.commit();
            return lease;
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Locks the runner host's row as a dispatcher does ({@code FOR NO KEY UPDATE}), on a connection of its own as the
     * runtime role, its transaction left open: every dispatcher of a run for that host waits for it until the
     * connection commits.
     */
    static Connection holdHost(UUID companyId, UUID hostId) throws SQLException {
        Connection connection = context(companyId);
        try (PreparedStatement lock = prepare(connection, "SELECT id FROM runner_host WHERE id = ? FOR NO KEY UPDATE",
                hostId); ResultSet row = lock.executeQuery()) {
            assertThat(row.next()).as("runner host %s", hostId).isTrue();
        }
        return connection;
    }

    /** The backends of this database that wait for a lock while running a statement that contains this text. */
    static long waitingIn(UUID companyId, String statement) {
        return (Long) query(companyId, """
                SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE ?""",
                "%" + statement + "%");
    }

    /** The backends of this database that wait for a lock the backend with this process id holds. */
    static long blockedBy(UUID companyId, int pid) {
        return (Long) query(companyId, """
                SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND ? = ANY (pg_blocking_pids(pid))""", pid);
    }

    private static Connection context(UUID companyId) throws SQLException {
        Connection connection = TestDatabase.connectAsApp();
        connection.setAutoCommit(false);
        try (PreparedStatement context = connection.prepareStatement("""
                SELECT set_config('app.company_id', ?, true), set_config('app.user_id', '', true),
                       set_config('app.instance_admin', 'false', true)""")) {
            context.setString(1, companyId.toString());
            context.execute();
        }
        return connection;
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }
}
